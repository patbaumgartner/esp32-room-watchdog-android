package com.patbaumgartner.roomwatchdog.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Iterative radix-2 FFT with precomputed twiddles; allocation free once built. */
internal class Fft(private val size: Int) {

    private val cosTable = DoubleArray(size / 2) { cos(-2.0 * PI * it / size) }
    private val sinTable = DoubleArray(size / 2) { sin(-2.0 * PI * it / size) }
    private val reversed = IntArray(size).also { table ->
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) table[i] = Integer.reverse(i) ushr (32 - bits)
    }

    fun forward(re: DoubleArray, im: DoubleArray) = transform(re, im)

    fun inverse(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until size) im[i] = -im[i]
        transform(re, im)
        val scale = 1.0 / size
        for (i in 0 until size) {
            re[i] *= scale
            im[i] *= -scale
        }
    }

    private fun transform(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until size) {
            val j = reversed[i]
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var length = 2
        while (length <= size) {
            val step = size / length
            var start = 0
            while (start < size) {
                for (k in 0 until length / 2) {
                    val twiddle = k * step
                    val wRe = cosTable[twiddle]
                    val wIm = sinTable[twiddle]
                    val even = start + k
                    val odd = even + length / 2
                    val oddRe = re[odd] * wRe - im[odd] * wIm
                    val oddIm = re[odd] * wIm + im[odd] * wRe
                    re[odd] = re[even] - oddRe
                    im[odd] = im[even] - oddIm
                    re[even] += oddRe
                    im[even] += oddIm
                }
                start += length
            }
            length = length shl 1
        }
    }
}
