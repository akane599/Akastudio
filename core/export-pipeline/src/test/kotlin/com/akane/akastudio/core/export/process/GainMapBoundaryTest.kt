package com.akane.akastudio.core.export.process

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The M1 accepted numerical boundary (docs/stabilization/M1_COMPLETION.md: C shoulder on the maximum linear RGB
 * channel with knee .6, the unchanged .01-stop gain floor) and the AUD-08 alpha seam of the Ultra HDR base, pinned
 * with fixed numbers rather than relations between two code paths.
 */
class GainMapBoundaryTest {
    /**
     * Pins the accepted C shoulder at fixed points: `k + (1 - k) t / ((1 - k) + t (1 - (1 - k) / (peak - k)))` with
     * `t = max - k`, knee `k = 0.6`. Expected values are the closed form evaluated in double precision and hard-coded,
     * so a change to the knee, the curve or its peak normalisation fails here. Inputs are exact half values; the
     * tolerance is about one half-float step below 1 (2^-11 ~ 4.9e-4) plus rounding.
     */
    @Test
    fun shoulderMatchesTheAcceptedClosedFormAtFixedPoints() {
        val cases =
            mapOf(
                4f to
                    listOf(
                        0.5f to 0.5f,
                        0.6f to 0.6f,
                        0.625f to 0.623693f,
                        0.75f to 0.712707f,
                        0.875f to 0.771167f,
                        1f to 0.8125f,
                        1.5f to 0.901478f,
                        2f to 0.942446f,
                        3f to 0.981308f,
                        4f to 1f,
                    ),
                2f to
                    listOf(
                        0.6f to 0.6f,
                        0.625f to 0.623932f,
                        0.75f to 0.71831f,
                        0.875f to 0.784431f,
                        1f to 0.833333f,
                        1.5f to 0.945205f,
                        2f to 1f,
                    ),
            )
        for ((peak, points) in cases) {
            val src = halfImage(points.size, 1) { x, _ -> gray(points[x].first) }
            assertEquals("fixture peak", peak, points.maxOf { it.first }, 0f)
            for (downscale in listOf(1, 4)) {
                val base = GainMapMath.compute(src, gainDownscale = downscale).sdr
                for ((x, point) in points.withIndex()) {
                    for (c in 0..2) {
                        assertEquals(
                            "peak $peak, max channel ${point.first}, channel $c, cell $downscale",
                            point.second,
                            base.at(x, 0, c),
                            6e-4f,
                        )
                    }
                }
            }
            val sdrBase = GainMapMath.sdrBase(src)
            for ((x, point) in points.withIndex()) {
                assertEquals("sdrBase peak $peak at ${point.first}", point.second, sdrBase.at(x, 0, 0), 6e-4f)
            }
        }
    }

    /** A frame whose peak channel is exactly 1 (SDR white) keeps its base bit for bit, even far above the knee. */
    @Test
    fun frameWithPeakExactlyOneKeepsItsBaseUnchanged() {
        val src =
            halfImage(12, 6) { x, y ->
                when ((x + y) % 6) {
                    0 -> gray(1f)
                    1 -> floatArrayOf(1f, 0.61f, 0f, 1f)
                    2 -> floatArrayOf(0.95f, 0.9f, 0.7f, 0.5f)
                    3 -> gray(0.6f)
                    4 -> floatArrayOf(0.2f, 1f, 0.8f, 1f)
                    else -> gray(0f)
                }
            }
        for (downscale in listOf(1, 4)) {
            val gain = GainMapMath.compute(src, gainDownscale = downscale)
            assertArrayEquals("cell $downscale: peak 1 is an identity base", src.rgba, gain.sdr.rgba)
            assertEquals(1f, gain.ratioMin, 0f)
            assertEquals(1f, gain.ratioMax, 0f)
            assertArrayEquals(ByteArray(gain.gain.size), gain.gain)
            assertEquals(1f, GainMapMath.ratioMax(src, gainDownscale = downscale), 0f)
        }
        assertArrayEquals(src.rgba, GainMapMath.sdrBase(src).rgba)
    }

    /**
     * The decision floor is `log2 gain > 0.01`. One neutral pixel of value P in a mid-grey frame tone-maps to 1, so its
     * own full-resolution cell has `log2((P + e) / (1 + e))`, e = 1/64. Adjacent half values straddle the floor:
     * P = 1 + 7/1024 gives 0.00968 stops (unit gain), P = 1 + 8/1024 gives 0.01106 stops, ratioMax 1.0076923.
     */
    @Test
    fun adjacentHalfPeaksStraddleThePointZeroOneStopFloor() {
        val below = singleHighlight(1f + 7f / 1024f)
        val above = singleHighlight(1f + 8f / 1024f)
        val unit = GainMapMath.compute(below, gainDownscale = 1)
        assertEquals("0.00968 stops is below the floor", 1f, unit.ratioMax, 0f)
        assertEquals(1f, GainMapMath.ratioMax(below, gainDownscale = 1), 0f)
        assertArrayEquals(ByteArray(unit.gain.size), unit.gain)
        val usable = GainMapMath.compute(above, gainDownscale = 1)
        assertEquals("0.01106 stops is kept", 1.0076923f, usable.ratioMax, 2e-6f)
        assertEquals(usable.ratioMax, GainMapMath.ratioMax(above, gainDownscale = 1), 0f)
        assertEquals("unit gain elsewhere keeps ratioMin at 1", 1f, usable.ratioMin, 0f)
        assertEquals("the highlight cell carries the top code", 255, usable.gain[3 * 9 + 4].toInt() and 0xFF)
        assertEquals("the base maps the peak to SDR white", 1f, usable.sdr.at(4, 3, 0), 0f)
    }

    /**
     * AUD-08 seam for a translucent Ultra HDR frame: gain and base colour are computed on premultiplied colour (the
     * scene over black), so they equal those of the same colour at alpha 1, and alpha passes through to the base. The
     * platform encoder then forces the base opaque (device test `AlphaContractDeviceTest`).
     */
    @Test
    fun gainAndBaseColourOfATranslucentFrameEqualThoseOfItsOverBlackComposite() {
        val alphaAt = { x: Int, y: Int ->
            if (x < 8) {
                0.5f
            } else if (y < 2) {
                0f
            } else {
                1f
            }
        }
        val colourAt = { x: Int, y: Int ->
            val a = alphaAt(x, y)
            when {
                a == 0f -> floatArrayOf(0f, 0f, 0f)
                x in 4 until 12 && y in 4 until 8 -> floatArrayOf(4f * a, 3f * a, 2f * a)
                else -> floatArrayOf(0.75f * a, 0.5f * a, 0.25f * a)
            }
        }
        val translucent = halfImage(16, 12) { x, y -> withAlpha(colourAt(x, y), alphaAt(x, y)) }
        val overBlack = halfImage(16, 12) { x, y -> withAlpha(colourAt(x, y), 1f) }
        for (downscale in listOf(1, 4)) {
            val a = GainMapMath.compute(translucent, gainDownscale = downscale)
            val b = GainMapMath.compute(overBlack, gainDownscale = downscale)
            assertTrue("fixture has headroom", a.ratioMax > 1f)
            assertArrayEquals("cell $downscale gain codes ignore alpha", b.gain, a.gain)
            assertEquals(b.ratioMin, a.ratioMin, 0f)
            assertEquals(b.ratioMax, a.ratioMax, 0f)
            for (y in 0 until 12) {
                for (x in 0 until 16) {
                    for (c in 0..2) assertEquals("base colour ($x,$y,$c)", b.sdr.at(x, y, c), a.sdr.at(x, y, c), 0f)
                    assertEquals("alpha passes through ($x,$y)", alphaAt(x, y), a.sdr.at(x, y, 3), 0f)
                }
            }
        }
    }

    private fun singleHighlight(peak: Float) = halfImage(9, 7) { x, y -> gray(if (x == 4 && y == 3) peak else 0.5f) }

    private fun withAlpha(
        rgb: FloatArray,
        alpha: Float,
    ) = floatArrayOf(rgb[0], rgb[1], rgb[2], alpha)
}
