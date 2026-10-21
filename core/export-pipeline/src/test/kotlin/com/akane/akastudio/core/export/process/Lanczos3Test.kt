package com.akane.akastudio.core.export.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class Lanczos3Test {

    @Test
    fun outputDimensionsAreExact() {
        val src = halfImage(37, 23) { _, _ -> gray(0.3f) }
        for ((w, h) in listOf(1 to 1, 18 to 11, 37 to 23, 100 to 7, 5 to 60)) {
            val out = Lanczos3.resample(src, w, h)
            assertEquals(w, out.width)
            assertEquals(h, out.height)
            assertEquals(w * h * 4, out.rgba.size)
        }
    }

    @Test
    fun constantImageStaysConstant() {
        val src = halfImage(40, 30) { _, _ -> floatArrayOf(0.25f, 0.5f, 0.75f, 1f) }
        for ((w, h) in listOf(20 to 15, 13 to 29, 81 to 61, 40 to 30)) {
            val out = Lanczos3.resample(src, w, h)
            for (y in 0 until h) for (x in 0 until w) {
                assertEquals(0.25f, out.at(x, y, 0), 1e-3f)
                assertEquals(0.5f, out.at(x, y, 1), 1e-3f)
                assertEquals(0.75f, out.at(x, y, 2), 1e-3f)
                assertEquals(1f, out.at(x, y, 3), 1e-3f)
            }
        }
    }

    @Test
    fun halvingOnePixelChecker_isFlatHalf() {
        val src = halfImage(64, 64) { x, y -> gray(((x + y) and 1).toFloat()) }
        val out = Lanczos3.resample(src, 32, 32)
        // The clamped border breaks the checker's symmetry; the interior must be alias-free.
        for (y in 3 until 29) for (x in 3 until 29) {
            assertEquals("($x,$y)", 0.5f, out.at(x, y, 0), 0.02f)
        }
    }

    /** Downscaling, the exporter's use (upsampling interpolates the raw step and Lanczos-3 then rings about 11%). */
    @Test
    fun sharpEdgeStaysMonotoneWithLimitedOvershoot() {
        val src = halfImage(60, 4) { x, _ -> gray(if (x < 30) 0f else 1f) }
        for (w in listOf(30, 20, 40, 45, 17)) {
            val out = Lanczos3.resample(src, w, 4)
            val row = FloatArray(w) { out.at(it, 1, 0) }
            val lo = row.indices.minBy { row[it] }
            val hi = row.indices.maxBy { row[it] }
            assertTrue("width $w overshoot ${row[hi] - 1f}", row[hi] - 1f < 0.1f)
            assertTrue("width $w undershoot ${-row[lo]}", -row[lo] < 0.1f)
            assertTrue("width $w transition runs low->high", lo < hi)
            for (x in lo until hi) assertTrue("width $w non-monotone at $x", row[x + 1] >= row[x])
        }
    }

    @Test
    fun identitySizeKeepsPixels() {
        val rnd = Random(3)
        val src = halfImage(31, 17) { _, _ ->
            floatArrayOf(rnd.nextFloat() * 2f, rnd.nextFloat(), rnd.nextFloat() * 0.1f, 0.5f + rnd.nextFloat() * 0.5f)
        }
        val out = Lanczos3.resample(src, 31, 17)
        for (i in src.rgba.indices) {
            val expected = Half.toFloat(src.rgba[i])
            val actual = Half.toFloat(out.rgba[i])
            assertEquals("sample $i", expected, actual, 1e-3f * maxOf(1f, abs(expected)))
        }
    }

    @Test
    fun weightsAreNormalized() {
        for ((s, d) in listOf(100 to 37, 37 to 100, 64 to 64, 3 to 1)) {
            val w = Lanczos3.weights(s, d)
            for (i in 0 until d) {
                var sum = 0f
                for (k in 0 until w.taps) sum += w.weight[i * w.taps + k]
                assertEquals(1f, sum, 1e-5f)
            }
        }
    }

    @Test
    fun transparentPixelsDoNotBleedColour() {
        // Red opaque next to transparent green: premultiplied filtering keeps the opaque side pure red.
        val src = halfImage(16, 2) { x, _ -> if (x < 8) floatArrayOf(1f, 0f, 0f, 1f) else floatArrayOf(0f, 1f, 0f, 0f) }
        val out = Lanczos3.resample(src, 8, 2)
        for (x in 0 until 8) {
            if (out.at(x, 0, 3) > 0.05f) assertEquals("green at $x", 0f, out.at(x, 0, 1), 1e-3f)
        }
    }
}
