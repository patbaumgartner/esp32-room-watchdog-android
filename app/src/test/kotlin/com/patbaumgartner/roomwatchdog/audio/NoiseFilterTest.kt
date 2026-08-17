package com.patbaumgartner.roomwatchdog.audio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoiseFilterTest {

    private val sampleRate = 48_000
    private val random = Random(7)

    /** Long enough for the 5 s learning ramp to finish, plus a second to measure. */
    private val duration = sampleRate * 6
    private val settled = sampleRate * 5

    @Test
    fun `steady hiss is suppressed once the room profile is learnt`() {
        val noise = pcm(duration) { hiss() }
        val before = rms(noise, settled, duration)

        NoiseFilter(sampleRate).process(noise, noise.size)

        val after = rms(noise, settled, duration)
        assertTrue("expected >20 dB of suppression, got $after from $before", after < before / 10)
    }

    @Test
    fun `suppression eases in while the profile is still being learnt`() {
        val noise = pcm(duration) { hiss() }
        val early = rms(noise, sampleRate / 2, sampleRate)

        NoiseFilter(sampleRate).process(noise, noise.size)

        val afterEarly = rms(noise, sampleRate / 2, sampleRate)
        val afterSettled = rms(noise, settled, duration)
        assertTrue("expected the first second to be treated gently", afterEarly > early / 8)
        assertTrue("expected the settled part to be cleaner", afterSettled < afterEarly)
    }

    @Test
    fun `speech-band tone survives the same hiss`() {
        val raw = pcm(duration) { index ->
            hiss() + if (index >= settled) 4000 * sin(2 * PI * 900 * index / sampleRate) else 0.0
        }
        val filtered = raw.copyOf()
        val before = rms(raw, settled, duration)

        NoiseFilter(sampleRate).process(filtered, filtered.size)

        val after = rms(filtered, settled, duration)
        assertTrue("expected the tone to pass, got $after from $before", after > before * 0.5)
    }

    @Test
    fun `learning flag clears once the profile is built`() {
        val filter = NoiseFilter(sampleRate)
        val warmUp = pcm(sampleRate) { hiss() }
        filter.process(warmUp, warmUp.size)
        assertTrue("still learning after one second", filter.isLearning)

        val rest = pcm(sampleRate * 5) { hiss() }
        filter.process(rest, rest.size)
        assertFalse("learning should be done after six seconds", filter.isLearning)
    }

    @Test
    fun `constant offset is removed`() {
        val offset = pcm(sampleRate) { 6000.0 }

        NoiseFilter(sampleRate).process(offset, offset.size)

        assertEquals(0.0, rms(offset, sampleRate - 4096, sampleRate), 5.0)
    }

    private fun hiss() = random.nextInt(-1200, 1200).toDouble()

    private inline fun pcm(samples: Int, sample: (Int) -> Double): ByteArray {
        val buffer = ByteArray(samples * 2)
        for (index in 0 until samples) {
            val value = sample(index).toInt()
            buffer[index * 2] = (value and 0xFF).toByte()
            buffer[index * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return buffer
    }

    private fun rms(buffer: ByteArray, from: Int, until: Int): Double {
        var sum = 0.0
        for (index in from until until) {
            val value = ((buffer[index * 2 + 1].toInt() shl 8) or (buffer[index * 2].toInt() and 0xFF))
                .toShort().toDouble()
            sum += value * value
        }
        return sqrt(sum / (until - from))
    }
}
