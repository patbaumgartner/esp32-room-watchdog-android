package com.patbaumgartner.roomwatchdog.audio

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoCancellerTest {

    private val sampleRate = 48_000
    private val random = Random(11)

    /** Loop delay of the real setup: phone playout buffer plus the device's own buffering. */
    private val echoDelay = sampleRate / 4

    @Test
    fun `delayed echo of the played signal is cancelled`() {
        val played = farEnd(sampleRate * 8)
        val captured = echoOf(played)
        val original = captured.copyOf()

        run(played, captured)

        val settled = sampleRate * 6
        val before = rms(original, settled, original.size / 2)
        val after = rms(captured, settled, captured.size / 2)
        val erle = 20 * log10(before / after)
        assertTrue("expected >12 dB of echo removal, got %.1f dB".format(erle), erle > 12.0)
    }

    @Test
    fun `sound that is not an echo survives`() {
        val played = farEnd(sampleRate * 8)
        val captured = echoOf(played)
        val nearEndFrom = sampleRate * 6
        val nearEnd = pcm(captured.size / 2) { index ->
            if (index >= nearEndFrom) 5000 * sin(2 * PI * 440 * index / sampleRate) else 0.0
        }
        val mixed = mix(captured, nearEnd)
        val cancelled = mixed.copyOf()

        run(played, cancelled)

        val before = rms(nearEnd, nearEndFrom, nearEnd.size / 2)
        val after = rms(cancelled, nearEndFrom, cancelled.size / 2)
        assertTrue("expected the near end to survive, got $after from $before", after > before * 0.4)
    }

    @Test
    fun `silence stays silent when nothing was played`() {
        val silence = pcm(sampleRate) { 0.0 }
        val captured = pcm(sampleRate) { hiss() }
        val filtered = captured.copyOf()

        run(silence, filtered)

        val before = rms(captured, 0, captured.size / 2)
        val after = rms(filtered, 0, filtered.size / 2)
        assertTrue("nothing was played, so nothing should be removed", after > before * 0.9)
    }

    /** A socket hands over whatever arrived, so a read is rarely a whole filter block. */
    @Test
    fun `ragged network reads cancel as well as aligned ones`() {
        val played = farEnd(sampleRate * 8)
        val captured = echoOf(played)
        val original = captured.copyOf()

        run(played, captured, chunks = listOf(1636, 4096, 512, 2750, 118, 4096))

        val settled = sampleRate * 6
        val before = rms(original, settled, original.size / 2)
        val after = rms(captured, settled, captured.size / 2)
        val erle = 20 * log10(before / after)
        assertTrue("expected >12 dB of echo removal, got %.1f dB".format(erle), erle > 12.0)
    }

    /** Feeds playback and capture in the interleaved order the live session uses. */
    private fun run(played: ByteArray, captured: ByteArray, chunks: List<Int> = listOf(4096)) {
        val canceller = EchoCanceller(sampleRate)
        var offset = 0
        var next = 0
        while (offset < captured.size) {
            val length = minOf(chunks[next++ % chunks.size], captured.size - offset)
            val block = captured.copyOfRange(offset, offset + length)
            canceller.process(block, length)
            System.arraycopy(block, 0, captured, offset, length)
            canceller.playback(played.copyOfRange(offset, offset + length), length)
            offset += length
        }
    }

    /** Speech-like far end: a couple of harmonics that come and go, plus a little noise. */
    private fun farEnd(samples: Int) = pcm(samples) { index ->
        val envelope = if ((index / (sampleRate / 2)) % 2 == 0) 1.0 else 0.15
        envelope * (
                4000 * sin(2 * PI * 220 * index / sampleRate) +
                        2500 * sin(2 * PI * 700 * index / sampleRate) +
                        1200 * sin(2 * PI * 1900 * index / sampleRate)
                ) + hiss()
    }

    /** Room path: a delayed, attenuated copy plus a short reflection. */
    private fun echoOf(played: ByteArray): ByteArray {
        val samples = played.size / 2
        return pcm(samples) { index ->
            val direct = sampleAt(played, index - echoDelay) * 0.45
            val reflection = sampleAt(played, index - echoDelay - 137) * 0.2
            direct + reflection + hiss() * 0.2
        }
    }

    private fun mix(first: ByteArray, second: ByteArray) = pcm(first.size / 2) { index ->
        sampleAt(first, index) + sampleAt(second, index)
    }

    private fun sampleAt(buffer: ByteArray, index: Int): Double {
        if (index < 0 || index * 2 + 1 >= buffer.size) return 0.0
        return ((buffer[index * 2 + 1].toInt() shl 8) or (buffer[index * 2].toInt() and 0xFF)).toShort().toDouble()
    }

    private fun hiss() = random.nextInt(-300, 300).toDouble()

    private inline fun pcm(samples: Int, sample: (Int) -> Double): ByteArray {
        val buffer = ByteArray(samples * 2)
        for (index in 0 until samples) {
            val value = sample(index).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buffer[index * 2] = (value and 0xFF).toByte()
            buffer[index * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return buffer
    }

    private fun rms(buffer: ByteArray, from: Int, until: Int): Double {
        var sum = 0.0
        for (index in from until until) sum += sampleAt(buffer, index) * sampleAt(buffer, index)
        return sqrt(sum / (until - from)).coerceAtLeast(1e-9)
    }
}
