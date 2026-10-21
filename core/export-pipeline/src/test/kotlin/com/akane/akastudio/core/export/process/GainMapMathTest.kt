package com.akane.akastudio.core.export.process

import com.akane.akastudio.core.color.math.ColorMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log2
import kotlin.math.pow
import kotlin.random.Random

class GainMapMathTest {

    @Test
    fun sdrOnlyImageHasNoHeadroom() {
        val rnd = Random(2)
        val src = halfImage(33, 21) { _, _ -> floatArrayOf(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat(), 1f) }
        val result = GainMapMath.compute(src)
        assertEquals(1f, result.ratioMax, 1e-6f)
        assertEquals(1f, result.ratioMin, 1e-6f)
        assertEquals(1f, result.displayRatioHdr, 1e-6f)
        assertEquals(9, result.gainWidth)
        assertEquals(6, result.gainHeight)
        assertArrayEquals(ByteArray(9 * 6), result.gain)
        // Lw = 1 makes the tone curve the identity.
        assertArrayEquals(src.rgba, result.sdr.rgba)
    }

    @Test
    fun highlightProducesMatchingHeadroomAndReconstructs() {
        val src = halfImage(32, 32) { x, y -> gray(if (x in 8 until 16 && y in 16 until 24) 4f else 0.18f) }
        val r = GainMapMath.compute(src, gainDownscale = 4)
        assertEquals(4f, r.ratioMax, 4f * 0.05f)
        assertEquals(r.ratioMax, r.displayRatioHdr, 0f)
        assertEquals(1f, r.displayRatioSdr, 0f)
        assertEquals(1f, r.gamma, 0f)
        assertEquals(1f / 64f, r.epsilonSdr, 0f)
        assertEquals(1f / 64f, r.epsilonHdr, 0f)
        assertTrue(r.ratioMin >= 1f && r.ratioMin < r.ratioMax)
        val sdr = r.sdr.at(10, 18, 0)
        assertEquals("peak tone-maps to SDR white", 1f, sdr, 2e-3f)
        val code = r.gain[(18 / 4) * r.gainWidth + 10 / 4].toInt() and 0xFF
        val g = log2(r.ratioMin) + (code / 255f).pow(r.gamma) * (log2(r.ratioMax) - log2(r.ratioMin))
        assertEquals(4f, sdr * 2f.pow(g), 4f * 0.05f)
        // Midtones keep a smaller gain than the highlight.
        val midCode = r.gain[0].toInt() and 0xFF
        assertTrue(midCode < code)
    }

    @Test
    fun sdrIsMonotoneAndBoundedByPeak() {
        val src = halfImage(64, 1) { x, _ -> gray(x / 8f) }
        val r = GainMapMath.compute(src, gainDownscale = 1)
        var previous = -1f
        for (x in 0 until 64) {
            val v = r.sdr.at(x, 0, 0)
            assertTrue(v >= previous)
            assertTrue(v <= 1f + 1e-3f)
            previous = v
        }
    }

    @Test
    fun boostLeavesMidtonesAndLiftsWhite() {
        val mid = ColorMath.srgbToLinear(0.5f)
        val src = halfImage(4, 1) { x, _ -> if (x < 2) gray(mid) else gray(1f) }
        val out = GainMapMath.boostHighlights(src, boostStops = 2f)
        for (c in 0 until 3) {
            assertEquals(mid, out.at(0, 0, c), 1e-3f)
            assertEquals(4f, out.at(3, 0, c), 4e-3f)
        }
        assertEquals(1f, out.at(3, 0, 3), 0f)
        val oneStop = GainMapMath.boostHighlights(src, boostStops = 1f)
        assertEquals(2f, oneStop.at(2, 0, 1), 2e-3f)
    }

    @Test
    fun boostedImageYieldsTheBoostAsHeadroom() {
        val src = halfImage(16, 16) { x, _ -> gray(if (x < 8) 0.2f else 1f) }
        val r = GainMapMath.compute(GainMapMath.boostHighlights(src, 2f))
        assertEquals(4f, r.ratioMax, 0.2f)
    }

    @Test
    fun ratioMaxMatchesComputeWithoutTheBase() {
        val random = Random(7)
        val images = listOf(
            halfImage(9, 7) { _, _ -> gray(0.6f) },
            halfImage(9, 7) { x, y -> gray(if (x == 8 && y == 6) 1.004f else 0.9f) },
            halfImage(16, 16) { x, _ -> gray(if (x < 8) 0.2f else 1.2f) },
            halfImage(13, 11) { _, _ -> floatArrayOf(random.nextFloat() * 3f, random.nextFloat(), random.nextFloat() * 2f, 1f) },
        )
        for (image in images) {
            for (downscale in listOf(1, 4)) {
                assertEquals(GainMapMath.compute(image, downscale).ratioMax, GainMapMath.ratioMax(image, downscale), 0f)
            }
        }
        assertEquals("a scene with no headroom reports exactly 1", 1f, GainMapMath.ratioMax(images[0]), 0f)
    }

    @Test
    fun partialEdgeCellsAverageOnlyCoveredPixels() {
        val src = halfImage(5, 5) { x, y -> gray(if (x == 4 && y == 4) 4f else 0f) }
        val r = GainMapMath.compute(src, gainDownscale = 4)
        assertEquals(2, r.gainWidth)
        assertEquals(2, r.gainHeight)
        assertEquals(255, r.gain[3].toInt() and 0xFF)
        assertEquals(4f, r.ratioMax, 0.2f)
    }
}
