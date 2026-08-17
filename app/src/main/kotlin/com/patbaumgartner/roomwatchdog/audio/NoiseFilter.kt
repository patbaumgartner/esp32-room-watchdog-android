package com.patbaumgartner.roomwatchdog.audio

import java.util.Arrays
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Cleans up the microphone's raw stream in real time. The device feeds a noisy ADC, so its
 * hiss is broadband and stationary - a plain gate barely dents it. The chain is therefore
 * DC blocker -> spectral (Wiener) subtraction -> low-pass.
 *
 * The noise profile is learnt from the room itself: the first seconds of a session build it
 * up while suppression eases in, and it keeps tracking afterwards, so a fan starting or the
 * fridge stopping is absorbed without anyone touching a setting.
 *
 * Works in place on little-endian 16-bit mono PCM and keeps its state between calls, so
 * consecutive buffers of one session filter as one continuous signal. Costs one frame of
 * latency, about 11 ms at 48 kHz.
 */
class NoiseFilter(sampleRate: Int) {

    private val denoiser = SpectralDenoiser(sampleRate)
    private val lowPass = Biquad.lowPass(sampleRate, LOW_PASS_HZ, LOW_PASS_Q)
    private val dcPole = 1.0 - (2.0 * PI * HIGH_PASS_HZ / sampleRate)

    private var lastInput = 0.0
    private var lastOutput = 0.0

    /** True while the room profile is still being learnt and suppression is easing in. */
    val isLearning: Boolean get() = denoiser.isLearning

    /** Drops the filter history so a new session does not inherit the previous room. */
    fun reset() {
        denoiser.reset()
        lowPass.reset()
        lastInput = 0.0
        lastOutput = 0.0
    }

    fun process(buffer: ByteArray, length: Int) {
        var index = 0
        while (index + 1 < length) {
            val raw = ((buffer[index + 1].toInt() shl 8) or (buffer[index].toInt() and 0xFF)).toShort()
            val cleaned = lowPass.process(denoiser.process(dcBlock(raw.toDouble() / SCALE)))
            val value = (cleaned * SCALE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buffer[index] = (value and 0xFF).toByte()
            buffer[index + 1] = ((value shr 8) and 0xFF).toByte()
            index += 2
        }
    }

    /** One-pole high-pass that strips the microphone's DC offset and low-frequency rumble. */
    private fun dcBlock(sample: Double): Double {
        val output = sample - lastInput + dcPole * lastOutput
        lastInput = sample
        lastOutput = output
        return output
    }

    /**
     * Short-time spectral attenuation that tunes itself.
     *
     * The per-bin noise floor comes from minimum statistics: the quietest smoothed power seen
     * in a sliding window of sub-windows. A minimum ignores anything transient, so speech in
     * the room never gets learnt as noise, while a genuinely new steady sound is picked up as
     * soon as the window rolls over. How hard the result is subtracted follows the measured
     * signal-to-noise ratio - aggressive when the room is pure hiss, gentle once something is
     * actually audible - and the Wiener gains run off a decision-directed SNR, which is what
     * stops the residual from warbling into "musical noise".
     */
    private class SpectralDenoiser(sampleRate: Int) {

        private val learningFrames = (LEARNING_SECONDS * sampleRate / HOP).toInt()
        private val subWindowFrames = (SUB_WINDOW_SECONDS * sampleRate / HOP).toInt()

        private val fft = Fft(FRAME)
        private val window = DoubleArray(FRAME) { 0.5 - 0.5 * cos(2.0 * PI * it / FRAME) }
        private val frame = DoubleArray(FRAME)
        private val re = DoubleArray(FRAME)
        private val im = DoubleArray(FRAME)
        private val overlap = DoubleArray(FRAME)
        private val hop = DoubleArray(HOP)
        private val noise = DoubleArray(BINS)
        private val smoothed = DoubleArray(BINS)
        private val priorSnr = DoubleArray(BINS)
        private val pending = DoubleArray(RING)

        /** Minimum statistics: the running minimum plus one slot per finished sub-window. */
        private val subWindowMin = DoubleArray(BINS) { Double.MAX_VALUE }
        private val history = Array(SUB_WINDOWS) { DoubleArray(BINS) { Double.MAX_VALUE } }
        private val windowMin = DoubleArray(BINS) { Double.MAX_VALUE }

        private var hopFill = 0
        private var readIndex = 0
        private var writeIndex = FRAME
        private var framesSeen = 0
        private var subWindowFill = 0
        private var historyIndex = 0
        private var snrDb = 0.0

        val isLearning: Boolean get() = framesSeen < learningFrames

        fun reset() {
            frame.fill(0.0)
            overlap.fill(0.0)
            pending.fill(0.0)
            noise.fill(0.0)
            smoothed.fill(0.0)
            priorSnr.fill(0.0)
            subWindowMin.fill(Double.MAX_VALUE)
            windowMin.fill(Double.MAX_VALUE)
            history.forEach { it.fill(Double.MAX_VALUE) }
            hopFill = 0
            readIndex = 0
            writeIndex = FRAME
            framesSeen = 0
            subWindowFill = 0
            historyIndex = 0
            snrDb = 0.0
        }

        fun process(sample: Double): Double {
            hop[hopFill++] = sample
            if (hopFill == HOP) {
                transformHop()
                hopFill = 0
            }
            val result = pending[readIndex]
            pending[readIndex] = 0.0
            readIndex = (readIndex + 1) % RING
            return result
        }

        private fun transformHop() {
            System.arraycopy(frame, HOP, frame, 0, FRAME - HOP)
            System.arraycopy(hop, 0, frame, FRAME - HOP, HOP)

            for (i in 0 until FRAME) {
                re[i] = frame[i] * window[i]
                im[i] = 0.0
            }
            fft.forward(re, im)
            attenuate()
            fft.inverse(re, im)

            for (i in 0 until FRAME) overlap[i] += re[i] * OVERLAP_GAIN
            for (i in 0 until HOP) {
                pending[writeIndex] = overlap[i]
                writeIndex = (writeIndex + 1) % RING
            }
            System.arraycopy(overlap, HOP, overlap, 0, FRAME - HOP)
            Arrays.fill(overlap, FRAME - HOP, FRAME, 0.0)
        }

        private fun attenuate() {
            framesSeen++
            var signalPower = 0.0
            var noisePower = 0.0

            for (bin in 0 until BINS) {
                val power = re[bin] * re[bin] + im[bin] * im[bin]
                val smooth = POWER_SMOOTHING * smoothed[bin] + (1.0 - POWER_SMOOTHING) * power
                smoothed[bin] = smooth
                if (smooth < subWindowMin[bin]) subWindowMin[bin] = smooth
                noise[bin] = MINIMUM_BIAS * min(windowMin[bin], subWindowMin[bin])
                signalPower += smooth
                noisePower += noise[bin]
            }

            if (++subWindowFill >= subWindowFrames) rollSubWindow()

            val measured = 10.0 * log10(max(signalPower - noisePower, EPSILON) / max(noisePower, EPSILON))
            snrDb = SNR_SMOOTHING * snrDb + (1.0 - SNR_SMOOTHING) * measured

            // Quiet room: subtract hard. Something worth hearing: back off and keep it clean.
            val overSubtraction = (OVER_SUBTRACTION_MAX - OVER_SUBTRACTION_SLOPE * snrDb)
                .coerceIn(OVER_SUBTRACTION_MIN, OVER_SUBTRACTION_MAX)
            val gainFloor = (GAIN_FLOOR_BASE * 10.0.pow(snrDb / 40.0))
                .coerceIn(GAIN_FLOOR_MIN, GAIN_FLOOR_MAX)
            val confidence = EASE_IN + (1.0 - EASE_IN) *
                    min(1.0, framesSeen.toDouble() / learningFrames)

            for (bin in 0 until BINS) {
                val floor = noise[bin] * overSubtraction + EPSILON
                val posterior = smoothed[bin] / floor
                val prior = DECISION_DIRECTED * priorSnr[bin] +
                        (1.0 - DECISION_DIRECTED) * max(0.0, posterior - 1.0)
                val gain = max(gainFloor, prior / (1.0 + prior))
                priorSnr[bin] = gain * gain * posterior

                val applied = 1.0 - confidence * (1.0 - gain)
                re[bin] *= applied
                im[bin] *= applied
                if (bin in 1 until BINS - 1) {
                    val mirror = FRAME - bin
                    re[mirror] *= applied
                    im[mirror] *= applied
                }
            }
        }

        private fun rollSubWindow() {
            System.arraycopy(subWindowMin, 0, history[historyIndex], 0, BINS)
            historyIndex = (historyIndex + 1) % SUB_WINDOWS
            for (bin in 0 until BINS) {
                var lowest = Double.MAX_VALUE
                for (slot in history) lowest = min(lowest, slot[bin])
                windowMin[bin] = lowest
            }
            subWindowMin.fill(Double.MAX_VALUE)
            subWindowFill = 0
        }

        private companion object {
            const val FRAME = 512
            const val HOP = 128
            const val BINS = FRAME / 2 + 1
            const val RING = FRAME * 2

            /** Hann at 75 % overlap sums to 2.0, so undo that on the way out. */
            const val OVERLAP_GAIN = 0.5
            const val POWER_SMOOTHING = 0.55
            const val LEARNING_SECONDS = 5.0
            const val SUB_WINDOW_SECONDS = 0.4
            const val SUB_WINDOWS = 4

            /** A minimum sits below the true mean power, so scale the estimate back up. */
            const val MINIMUM_BIAS = 1.9
            const val SNR_SMOOTHING = 0.9
            const val OVER_SUBTRACTION_MAX = 4.5
            const val OVER_SUBTRACTION_MIN = 1.2
            const val OVER_SUBTRACTION_SLOPE = 0.15
            const val GAIN_FLOOR_BASE = 0.035
            const val GAIN_FLOOR_MIN = 0.015
            const val GAIN_FLOOR_MAX = 0.12

            /** Suppression starts here and reaches full strength when the profile is learnt. */
            const val EASE_IN = 0.55
            const val DECISION_DIRECTED = 0.98
            const val EPSILON = 1e-12
        }
    }

    /** Direct form I biquad, enough for the single low-pass stage this filter needs. */
    private class Biquad(
        private val b0: Double,
        private val b1: Double,
        private val b2: Double,
        private val a1: Double,
        private val a2: Double,
    ) {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun process(input: Double): Double {
            val output = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = input
            y2 = y1
            y1 = output
            return output
        }

        fun reset() {
            x1 = 0.0
            x2 = 0.0
            y1 = 0.0
            y2 = 0.0
        }

        companion object {
            fun lowPass(sampleRate: Int, cutoffHz: Double, q: Double): Biquad {
                val w0 = 2.0 * PI * min(cutoffHz, sampleRate / 2.0 - 1.0) / sampleRate
                val alpha = sin(w0) / (2.0 * q)
                val cosW0 = cos(w0)
                val a0 = 1.0 + alpha
                return Biquad(
                    b0 = ((1.0 - cosW0) / 2.0) / a0,
                    b1 = (1.0 - cosW0) / a0,
                    b2 = ((1.0 - cosW0) / 2.0) / a0,
                    a1 = (-2.0 * cosW0) / a0,
                    a2 = (1.0 - alpha) / a0,
                )
            }
        }
    }

    private companion object {
        const val SCALE = 32768.0
        const val LOW_PASS_HZ = 6000.0
        const val LOW_PASS_Q = 0.707
        const val HIGH_PASS_HZ = 90.0
    }
}
