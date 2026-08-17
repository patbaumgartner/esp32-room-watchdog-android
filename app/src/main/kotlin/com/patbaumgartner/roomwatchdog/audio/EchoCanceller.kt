package com.patbaumgartner.roomwatchdog.audio

import kotlin.math.max

/**
 * Removes the phone's own playback from the device's microphone feed.
 *
 * While listening, the speaker plays the room back into the room, the device's microphone picks
 * that up, and it arrives again a fraction of a second later - obvious when both sit on the same
 * desk. The player knows exactly what it sent to the speaker, so that signal is the reference for
 * a partitioned frequency-domain adaptive filter (NLMS): it learns the path from speaker to
 * microphone, predicts the echo and subtracts it. Whatever survives - the part no linear filter can
 * model, mostly loudspeaker distortion and the microphone's own AGC - is ducked by a residual
 * suppressor that only engages while the estimated echo dominates the block.
 *
 * The filter spans [PARTITIONS] blocks, so it covers the buffering of both ends; nothing has to be
 * told the loop delay.
 */
class EchoCanceller(sampleRate: Int) {

    private val fft = Fft(FRAME)

    /** Reference spectra, newest first, one per block of playback. */
    private val referenceRe = Array(PARTITIONS) { DoubleArray(BINS) }
    private val referenceIm = Array(PARTITIONS) { DoubleArray(BINS) }
    private val filterRe = Array(PARTITIONS) { DoubleArray(BINS) }
    private val filterIm = Array(PARTITIONS) { DoubleArray(BINS) }
    private val referencePower = DoubleArray(BINS)

    private val referenceTail = DoubleArray(BLOCK)
    private val referenceBlock = DoubleArray(BLOCK)

    /**
     * Playback is handed over in whole network buffers, capture is consumed block by block, so the
     * two only stay aligned if the reference is drawn one block at a time from a queue.
     */
    private val referenceQueue = DoubleArray(QUEUE_CAPACITY)
    private var queueRead = 0
    private var queueWrite = 0
    private var queued = 0

    private val captureBlock = DoubleArray(BLOCK)

    /** Cancelled samples waiting to be handed back, primed with one block of silence. */
    private val cancelled = DoubleArray(CANCELLED_CAPACITY)
    private var cancelledRead = 0
    private var cancelledWrite = BLOCK

    private val workRe = DoubleArray(FRAME)
    private val workIm = DoubleArray(FRAME)

    private var captureFill = 0
    private var newest = 0
    private var partitionsFilled = 0
    private var residualGain = 1.0

    fun reset() {
        for (partition in 0 until PARTITIONS) {
            referenceRe[partition].fill(0.0)
            referenceIm[partition].fill(0.0)
            filterRe[partition].fill(0.0)
            filterIm[partition].fill(0.0)
        }
        referencePower.fill(0.0)
        referenceTail.fill(0.0)
        referenceQueue.fill(0.0)
        queueRead = 0
        queueWrite = 0
        queued = 0
        cancelled.fill(0.0)
        cancelledRead = 0
        cancelledWrite = BLOCK
        captureFill = 0
        newest = 0
        partitionsFilled = 0
        residualGain = 1.0
    }

    /**
     * Registers what is being handed to the speaker. Pass [silent] while playback is muted: the
     * samples still advance the timeline, but a muted speaker produces no echo to learn from.
     */
    fun playback(buffer: ByteArray, length: Int, silent: Boolean = false) {
        var index = 0
        while (index + 1 < length) {
            val sample = if (silent) {
                0.0
            } else {
                ((buffer[index + 1].toInt() shl 8) or (buffer[index].toInt() and 0xFF)).toShort() / SCALE
            }
            referenceQueue[queueWrite] = sample
            queueWrite = (queueWrite + 1) % QUEUE_CAPACITY
            if (queued == QUEUE_CAPACITY) queueRead = (queueRead + 1) % QUEUE_CAPACITY else queued++
            index += 2
        }
    }

    /**
     * Subtracts the learnt echo from freshly captured samples, in place. A block rarely lines up
     * with a network read, so cancelled samples leave through a FIFO that is primed with one block
     * of silence: every call writes back exactly what it was given, one block behind.
     */
    fun process(buffer: ByteArray, length: Int) {
        var index = 0
        while (index + 1 < length) {
            captureBlock[captureFill++] =
                ((buffer[index + 1].toInt() shl 8) or (buffer[index].toInt() and 0xFF)).toShort() / SCALE
            if (captureFill == BLOCK) {
                pullReference()
                pushReference()
                cancel()
                for (i in 0 until BLOCK) {
                    cancelled[cancelledWrite] = captureBlock[i]
                    cancelledWrite = (cancelledWrite + 1) % CANCELLED_CAPACITY
                }
                captureFill = 0
            }

            val value = (cancelled[cancelledRead] * SCALE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            cancelledRead = (cancelledRead + 1) % CANCELLED_CAPACITY
            buffer[index] = (value and 0xFF).toByte()
            buffer[index + 1] = ((value shr 8) and 0xFF).toByte()
            index += 2
        }
    }

    /** Silence before playback starts is real: the speaker had nothing to echo back yet. */
    private fun pullReference() {
        for (i in 0 until BLOCK) {
            if (queued > 0) {
                referenceBlock[i] = referenceQueue[queueRead]
                queueRead = (queueRead + 1) % QUEUE_CAPACITY
                queued--
            } else {
                referenceBlock[i] = 0.0
            }
        }
    }

    private fun pushReference() {
        for (i in 0 until BLOCK) {
            workRe[i] = referenceTail[i]
            workRe[BLOCK + i] = referenceBlock[i]
            workIm[i] = 0.0
            workIm[BLOCK + i] = 0.0
        }
        System.arraycopy(referenceBlock, 0, referenceTail, 0, BLOCK)
        fft.forward(workRe, workIm)

        newest = (newest + PARTITIONS - 1) % PARTITIONS
        val evictedRe = referenceRe[newest]
        val evictedIm = referenceIm[newest]
        for (bin in 0 until BINS) {
            referencePower[bin] -= evictedRe[bin] * evictedRe[bin] + evictedIm[bin] * evictedIm[bin]
            evictedRe[bin] = workRe[bin]
            evictedIm[bin] = workIm[bin]
            referencePower[bin] =
                max(0.0, referencePower[bin] + evictedRe[bin] * evictedRe[bin] + evictedIm[bin] * evictedIm[bin])
        }
        if (partitionsFilled < PARTITIONS) partitionsFilled++
    }

    private fun cancel() {
        if (partitionsFilled == 0) return

        // Overlap-save: the echo estimate is the sum of every reference block through its own filter.
        workRe.fill(0.0)
        workIm.fill(0.0)
        for (partition in 0 until partitionsFilled) {
            val slot = (newest + partition) % PARTITIONS
            val xRe = referenceRe[slot]
            val xIm = referenceIm[slot]
            val wRe = filterRe[partition]
            val wIm = filterIm[partition]
            for (bin in 0 until BINS) {
                workRe[bin] += xRe[bin] * wRe[bin] - xIm[bin] * wIm[bin]
                workIm[bin] += xRe[bin] * wIm[bin] + xIm[bin] * wRe[bin]
            }
        }
        mirror()
        fft.inverse(workRe, workIm)

        var echoEnergy = 0.0
        var residual = 0.0
        for (i in 0 until BLOCK) {
            val predicted = workRe[BLOCK + i]
            val error = captureBlock[i] - predicted
            echoEnergy += predicted * predicted
            residual += error * error
            captureBlock[i] = error
        }

        adapt()
        suppressResidual(echoEnergy, residual)
    }

    private fun adapt() {
        for (i in 0 until BLOCK) {
            workRe[i] = 0.0
            workRe[BLOCK + i] = captureBlock[i]
            workIm[i] = 0.0
            workIm[BLOCK + i] = 0.0
        }
        fft.forward(workRe, workIm)

        for (partition in 0 until partitionsFilled) {
            val slot = (newest + partition) % PARTITIONS
            val xRe = referenceRe[slot]
            val xIm = referenceIm[slot]
            val wRe = filterRe[partition]
            val wIm = filterIm[partition]
            for (bin in 0 until BINS) {
                val step = STEP_SIZE / (referencePower[bin] + REGULARISATION)
                val eRe = workRe[bin]
                val eIm = workIm[bin]
                wRe[bin] += step * (xRe[bin] * eRe + xIm[bin] * eIm)
                wIm[bin] += step * (xRe[bin] * eIm - xIm[bin] * eRe)
            }
        }
    }

    /**
     * What the linear filter cannot model - loudspeaker distortion, the microphone's own AGC - is
     * ducked while the predicted echo still dominates the block, and released as soon as it does not.
     */
    private fun suppressResidual(echoEnergy: Double, residualEnergy: Double) {
        val leakedEcho = echoEnergy * RESIDUAL_LEAK
        val target = if (leakedEcho <= 0.0) {
            1.0
        } else {
            max(RESIDUAL_FLOOR, residualEnergy / (residualEnergy + leakedEcho))
        }
        val start = residualGain
        residualGain += (target - residualGain) *
                if (target < residualGain) RESIDUAL_ATTACK else RESIDUAL_RELEASE
        for (i in 0 until BLOCK) {
            val blend = start + (residualGain - start) * (i.toDouble() / BLOCK)
            captureBlock[i] *= blend
        }
    }

    /** Rebuilds the negative half of a real signal's spectrum before the inverse transform. */
    private fun mirror() {
        for (bin in 1 until BINS - 1) {
            workRe[FRAME - bin] = workRe[bin]
            workIm[FRAME - bin] = -workIm[bin]
        }
        workIm[0] = 0.0
        workIm[BINS - 1] = 0.0
    }

    private companion object {
        const val SCALE = 32768.0
        const val BLOCK = 256
        const val FRAME = BLOCK * 2
        const val BINS = FRAME / 2 + 1

        /** 96 blocks of 256 samples cover half a second of loop delay at 48 kHz. */
        const val PARTITIONS = 96
        const val QUEUE_CAPACITY = BLOCK * 128
        const val CANCELLED_CAPACITY = BLOCK * 4
        const val STEP_SIZE = 0.4
        const val REGULARISATION = 1e-6

        /** Assumed share of the echo the linear filter cannot reach. */
        const val RESIDUAL_LEAK = 0.25
        const val RESIDUAL_FLOOR = 0.1
        const val RESIDUAL_ATTACK = 0.4
        const val RESIDUAL_RELEASE = 0.05
    }
}
