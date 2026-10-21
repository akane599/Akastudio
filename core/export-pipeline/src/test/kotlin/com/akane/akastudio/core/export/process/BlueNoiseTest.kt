package com.akane.akastudio.core.export.process

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class BlueNoiseTest {

    private val n = BlueNoise.SIZE * BlueNoise.SIZE

    @Test
    fun ranksAreUniqueAndCoverEveryLevel() {
        val ranks = BlueNoise.ranks
        assertEquals(n, ranks.size)
        val sorted = ranks.sortedArray()
        for (i in 0 until n) assertEquals((i + 0.5f) / n, sorted[i], 0f)
        assertTrue(sorted.first() > 0f && sorted.last() < 1f)
    }

    @Test
    fun generationIsDeterministic() {
        val a = VoidAndCluster.ranks(BlueNoise.SIZE, sigma = 1.5, initialFill = 0.10, seed = 1)
        val b = VoidAndCluster.ranks(BlueNoise.SIZE, sigma = 1.5, initialFill = 0.10, seed = 1)
        assertArrayEquals(a, b)
        val expected = FloatArray(n) { (a[it] + 0.5f) / n }
        assertArrayEquals(expected, BlueNoise.ranks, 0f)
    }

    @Test
    fun spectrumIsBlue() {
        val size = BlueNoise.SIZE
        val values = DoubleArray(n) { BlueNoise.ranks[it] - 0.5 }
        val power = powerSpectrum(values, size)
        val bandSum = DoubleArray(size)
        val bandCount = IntArray(size)
        for (v in 0 until size) for (u in 0 until size) {
            val fu = if (u <= size / 2) u else u - size
            val fv = if (v <= size / 2) v else v - size
            val r = sqrt((fu * fu + fv * fv).toDouble()).roundToInt()
            if (r == 0 || r >= size) continue
            bandSum[r] += power[v * size + u]
            bandCount[r]++
        }
        val radial = DoubleArray(size) { if (bandCount[it] == 0) 0.0 else bandSum[it] / bandCount[it] }
        val low = (1..8).map { radial[it] }.average()
        val high = (24..32).map { radial[it] }.average()
        assertTrue("low-band mean $low must be < 25% of high-band mean $high", low < 0.25 * high)
    }

    /** |DFT|^2 by separable row/column transforms. */
    private fun powerSpectrum(values: DoubleArray, size: Int): DoubleArray {
        val cosT = DoubleArray(size) { cos(2 * PI * it / size) }
        val sinT = DoubleArray(size) { sin(2 * PI * it / size) }
        val rowRe = DoubleArray(size * size)
        val rowIm = DoubleArray(size * size)
        for (y in 0 until size) for (u in 0 until size) {
            var re = 0.0
            var im = 0.0
            for (x in 0 until size) {
                val k = (u * x) % size
                re += values[y * size + x] * cosT[k]
                im -= values[y * size + x] * sinT[k]
            }
            rowRe[y * size + u] = re
            rowIm[y * size + u] = im
        }
        val power = DoubleArray(size * size)
        for (u in 0 until size) for (v in 0 until size) {
            var re = 0.0
            var im = 0.0
            for (y in 0 until size) {
                val k = (v * y) % size
                val c = cosT[k]
                val s = sinT[k]
                re += rowRe[y * size + u] * c + rowIm[y * size + u] * s
                im += rowIm[y * size + u] * c - rowRe[y * size + u] * s
            }
            power[v * size + u] = re * re + im * im
        }
        return power
    }
}
