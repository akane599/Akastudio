package com.akane.akastudio.core.color.render

import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.color.api.OverlayAlign
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.random.Random

class RenderMathTest {

    // ---- letterbox ----

    @Test
    fun letterbox_wideBoxPillarboxesTallContent() {
        val fit = RenderMath.letterbox(1080, 1920, 1920, 1080)
        assertEquals(1080, fit.height)
        assertEquals(0, fit.y)
        assertEquals(608, fit.width) // 1080 * 0.5625 = 607.5, rounded half up
        assertEquals((1920 - 608) / 2, fit.x)
        assertEquals(0.5625f, fit.scale, 1e-6f)
    }

    @Test
    fun letterbox_tallBoxLetterboxesWideContent() {
        val fit = RenderMath.letterbox(1920, 1080, 1080, 1920)
        assertEquals(1080, fit.width)
        assertEquals(0, fit.x)
        assertEquals(608, fit.height)
        assertEquals((1920 - 608) / 2, fit.y)
    }

    @Test
    fun letterbox_exactAspectFillsTheBox() {
        assertEquals(LetterboxFit(0, 0, 540, 960, 0.5f), RenderMath.letterbox(1080, 1920, 540, 960))
        assertEquals(LetterboxFit(0, 0, 1080, 1920, 1f), RenderMath.letterbox(1080, 1920, 1080, 1920))
    }

    // ---- crop + rotation UV transform ----

    private val crop = NormRect(0.1f, 0.2f, 0.7f, 0.9f)

    private fun assertMaps(matrix: Mat3, u: Float, v: Float, s: Float, t: Float) {
        val (x, y) = matrix.apply(u, v)
        assertEquals("s at ($u, $v)", s, x, 1e-6f)
        assertEquals("t at ($u, $v)", t, y, 1e-6f)
    }

    @Test
    fun uvMatrix_atZeroRotationMapsDestinationCornersToCropCorners() {
        val m = RenderMath.layerUvMatrix(crop, 0f, 4000, 3000)
        assertMaps(m, 0f, 0f, crop.left, crop.top)
        assertMaps(m, 1f, 0f, crop.right, crop.top)
        assertMaps(m, 0f, 1f, crop.left, crop.bottom)
        assertMaps(m, 1f, 1f, crop.right, crop.bottom)
    }

    @Test
    fun uvMatrix_keepsCropCentreFixedAtPlusMinusFiveDegrees() {
        for (degrees in floatArrayOf(5f, -5f)) {
            val m = RenderMath.layerUvMatrix(crop, degrees, 4000, 3000)
            assertMaps(m, 0.5f, 0.5f, (crop.left + crop.right) / 2f, (crop.top + crop.bottom) / 2f)
        }
    }

    @Test
    fun uvMatrix_rotatesInSourcePixelSpaceWithoutShear() {
        val width = 4000
        val height = 3000
        val m = RenderMath.layerUvMatrix(crop, 5f, width, height)
        val (s0, t0) = m.apply(0f, 0.5f)
        val (s1, t1) = m.apply(1f, 0.5f)
        val dx = (s1 - s0) * width
        val dy = (t1 - t0) * height
        // The destination's horizontal axis spans the crop width in source pixels, rotated by -5 degrees.
        assertEquals(crop.width * width, hypot(dx, dy), 0.05f)
        assertEquals(-5.0, Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())), 1e-3)
        val (s2, t2) = m.apply(0.5f, 0f)
        val (s3, t3) = m.apply(0.5f, 1f)
        val ex = (s3 - s2) * width
        val ey = (t3 - t2) * height
        val cosine = (dx * ex + dy * ey) / (hypot(dx, dy) * hypot(ex, ey))
        assertEquals(0f, cosine, 1e-5f) // axes stay perpendicular in pixel space
        assertEquals(crop.height * height, hypot(ex, ey), 0.05f)
    }

    @Test
    fun coverCrop_centresTheVisibleRegion() {
        val wide = RenderMath.coverCrop(4000, 2000, 1000, 1000)
        assertEquals(0.25f, wide.left, 1e-6f)
        assertEquals(0.75f, wide.right, 1e-6f)
        assertEquals(0f, wide.top, 0f)
        assertEquals(1f, wide.bottom, 0f)
        val tall = RenderMath.coverCrop(1000, 4000, 1000, 1000)
        assertEquals(0.375f, tall.top, 1e-6f)
        assertEquals(0.625f, tall.bottom, 1e-6f)
    }

    // ---- rounded-rect SDF ----

    @Test
    fun sdf_isNegativeInsideZeroOnEdgesPositiveOutside() {
        val hw = 100f
        val hh = 50f
        val r = 10f
        assertEquals(-50f, RenderMath.roundedRectSdf(0f, 0f, hw, hh, r), 1e-5f)
        assertEquals(0f, RenderMath.roundedRectSdf(100f, 0f, hw, hh, r), 1e-5f)
        assertEquals(0f, RenderMath.roundedRectSdf(0f, -50f, hw, hh, r), 1e-5f)
        assertEquals(10f, RenderMath.roundedRectSdf(110f, 0f, hw, hh, r), 1e-5f)
        assertEquals(-10f, RenderMath.roundedRectSdf(-90f, 0f, hw, hh, r), 1e-5f)
    }

    @Test
    fun sdf_roundsCornersByTheRadius() {
        val hw = 100f
        val hh = 50f
        val r = 10f
        // The sharp corner lies outside the arc by r * (sqrt(2) - 1).
        assertEquals(r * (sqrt(2f) - 1f), RenderMath.roundedRectSdf(100f, 50f, hw, hh, r), 1e-4f)
        assertEquals(r * (sqrt(2f) - 1f), RenderMath.roundedRectSdf(-100f, -50f, hw, hh, r), 1e-4f)
        val onArc = r / sqrt(2f)
        assertEquals(0f, RenderMath.roundedRectSdf(90f + onArc, 40f + onArc, hw, hh, r), 1e-4f)
        // Radius 0 keeps the sharp corner on the edge.
        assertEquals(0f, RenderMath.roundedRectSdf(100f, 50f, hw, hh, 0f), 1e-5f)
        // Oversized radii clamp to the short half extent (stadium).
        assertEquals(0f, RenderMath.roundedRectSdf(0f, 50f, hw, hh, 1000f), 1e-4f)
        assertEquals(0f, RenderMath.roundedRectSdf(100f, 0f, hw, hh, 1000f), 1e-4f)
    }

    @Test
    fun sdfCoverage_isAOnePixelRamp() {
        assertEquals(0.5f, RenderMath.sdfCoverage(0f, 1f), 0f)
        assertEquals(1f, RenderMath.sdfCoverage(-0.5f, 1f), 0f)
        assertEquals(0f, RenderMath.sdfCoverage(0.5f, 1f), 0f)
        assertEquals(1f, RenderMath.sdfCoverage(-3f, 1f), 0f)
        assertEquals(0f, RenderMath.sdfCoverage(0.25f, 2f), 0f) // half a canvas px is one target px at 2x
    }

    // ---- half float ----

    @Test
    fun half_allBitPatternsRoundTrip() {
        for (bits in 0..0xFFFF) {
            val value = RenderMath.halfToFloat(bits.toShort())
            val back = RenderMath.floatToHalf(value).toInt() and 0xFFFF
            if (value.isNaN()) {
                assertTrue("NaN 0x${bits.toString(16)} stays NaN", RenderMath.halfToFloat(back.toShort()).isNaN())
            } else {
                assertEquals("0x${bits.toString(16)} -> $value", bits, back)
            }
        }
    }

    @Test
    fun half_knownValues() {
        assertEquals(1f, RenderMath.halfToFloat(0x3C00.toShort()), 0f)
        assertEquals(65504f, RenderMath.halfToFloat(0x7BFF.toShort()), 0f)
        assertEquals(5.9604645E-8f, RenderMath.halfToFloat(0x0001.toShort()), 0f)
        assertEquals(Float.POSITIVE_INFINITY, RenderMath.halfToFloat(0x7C00.toShort()), 0f)
        assertEquals(0xFC00, RenderMath.floatToHalf(Float.NEGATIVE_INFINITY).toInt() and 0xFFFF)
        assertEquals(0x7C00, RenderMath.floatToHalf(65520f).toInt() and 0xFFFF) // ties to even overflows
        assertEquals(0x7BFF, RenderMath.floatToHalf(65519f).toInt() and 0xFFFF)
        assertEquals(0x8000, RenderMath.floatToHalf(-0f).toInt() and 0xFFFF)
        assertEquals(0x0000, RenderMath.floatToHalf(2.9802322E-8f).toInt() and 0xFFFF) // 2^-25 ties to 0
        assertEquals(0x0001, RenderMath.floatToHalf(3.0e-8f).toInt() and 0xFFFF)
        assertTrue(RenderMath.halfToFloat(RenderMath.floatToHalf(Float.NaN)).isNaN())
    }

    @Test
    fun half_floatToHalfRoundsToNearestEven() {
        val random = Random(0x5EED)
        val positives = (0 until 0x7C00).map { RenderMath.halfToFloat(it.toShort()).toDouble() }
        repeat(1000) { sample ->
            val magnitude = if (sample % 2 == 0) {
                // Exact midpoint between two adjacent finite halves (a tie), representable in Float.
                val index = random.nextInt(0, 0x7BFF)
                ((positives[index] + positives[index + 1]) / 2.0).toFloat()
            } else {
                random.nextDouble(0.0, 65504.0 * Math.pow(2.0, -random.nextInt(0, 40).toDouble())).toFloat()
            }
            val value = if (random.nextBoolean()) -magnitude else magnitude
            val expected = referenceHalf(value, positives)
            assertEquals("floatToHalf($value)", expected, RenderMath.floatToHalf(value).toInt() and 0xFFFF)
        }
    }

    /** Brute-force round-to-nearest-even over the sorted finite half magnitudes. */
    private fun referenceHalf(value: Float, positives: List<Double>): Int {
        val sign = if (value < 0f || (value == 0f && 1f / value < 0f)) 0x8000 else 0
        val x = abs(value.toDouble())
        var low = 0
        var high = positives.size - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (positives[mid] <= x) low = mid else high = mid - 1
        }
        if (low == positives.size - 1) {
            // Past 65504 the next step would be 2^16 (even): the midpoint 65520 and above round to infinity.
            val gap = positives[low] - positives[low - 1]
            return sign or if (x - positives[low] < gap / 2) low else 0x7C00
        }
        val below = positives[low]
        val above = positives[low + 1]
        val chosen = when {
            x - below < above - x -> low
            x - below > above - x -> low + 1
            else -> if (low % 2 == 0) low else low + 1
        }
        return sign or chosen
    }

    // ---- row flip / packing / tiles ----

    @Test
    fun flipRows_reversesRowOrder() {
        val data = shortArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)
        RenderMath.flipRows(data, 3, 3)
        assertArrayEquals(shortArrayOf(7, 8, 9, 4, 5, 6, 1, 2, 3), data)
        val even = shortArrayOf(1, 2, 3, 4)
        RenderMath.flipRows(even, 2, 2)
        assertArrayEquals(shortArrayOf(3, 4, 1, 2), even)
    }

    @Test
    fun rgbaBottomUpToArgb_flipsAndPacks() {
        val bottomRow = byteArrayOf(1, 2, 3, 4)
        val topRow = byteArrayOf(0xFF.toByte(), 0x80.toByte(), 0x10, 0xFF.toByte())
        val argb = RenderMath.rgbaBottomUpToArgb(bottomRow + topRow, 1, 2)
        assertEquals(0xFFFF8010.toInt(), argb[0])
        assertEquals(0x04010203, argb[1])
    }

    @Test
    fun planTiles_coversTheCanvasExactlyOnce() {
        val cases = listOf(
            intArrayOf(1080, 1920, 512, 700),
            intArrayOf(7, 5, 3, 2),
            intArrayOf(1024, 1024, 1024, 1024),
            intArrayOf(4097, 300, 4096, 4096),
            intArrayOf(1, 1, 1, 1),
        )
        for ((width, height, maxW, maxH) in cases.map { it.toList() }) {
            val tiles = RenderMath.planTiles(width, height, maxW, maxH)
            val hits = IntArray(width * height)
            for (tile in tiles) {
                assertTrue("tile $tile within limits", tile.width in 1..maxW && tile.height in 1..maxH)
                for (y in tile.y until tile.y + tile.height) for (x in tile.x until tile.x + tile.width) hits[y * width + x]++
            }
            assertTrue("${width}x$height / ${maxW}x$maxH covered exactly once", hits.all { it == 1 })
        }
        assertEquals(1, RenderMath.planTiles(1024, 1024, 1024, 1024).size)
    }

    @Test
    fun blitTile_placesRowsAtTheOffset() {
        val dest = ShortArray(3 * 2)
        RenderMath.blitTile(shortArrayOf(7, 8), 1, 2, dest, 3, 2, 0, 1)
        assertArrayEquals(shortArrayOf(0, 0, 7, 0, 0, 8), dest)
    }

    // ---- blur, log domain, overlay placement, mips ----

    @Test
    fun blurPlan_singlePassForSmallSigmaAndIteratesForLargeSigma() {
        val small = RenderMath.blurPlan(5.4f)
        assertEquals(1, small.passCount)
        assertEquals(17, small.radius)
        val large = RenderMath.blurPlan(40f)
        assertTrue(large.passCount > 1)
        assertTrue(large.radius <= RenderMath.MAX_BLUR_RADIUS)
        assertEquals(40.0, large.passSigma * sqrt(large.passCount.toDouble()), 1e-3)
        for (plan in listOf(small, large)) {
            val total = plan.weights[0] + 2f * plan.weights.drop(1).sum()
            assertEquals(1f, total, 1e-5f)
            assertEquals(plan.radius + 1, plan.weights.size)
        }
        assertEquals(0, RenderMath.blurPlan(0f).passCount)
    }

    @Test
    fun logDomain_roundTripsAndClamps() {
        for (x in floatArrayOf(0f, 0.001f, 0.18f, 1f, 4f, 16f)) {
            assertEquals(x, RenderMath.logDecode(RenderMath.logEncode(x)), x * 1e-5f + 1e-7f)
        }
        assertEquals(1f, RenderMath.logEncode(100f), 0f)
        assertEquals(0f, RenderMath.logEncode(-1f), 0f)
    }

    @Test
    fun overlayPlacement_alignsAroundTheAnchor() {
        val center = RenderMath.overlayPlacement(500f, 300f, 200f, -40f, 10f, OverlayAlign.CENTER, 4)
        assertEquals(396, center.left)
        assertEquals(256, center.top)
        assertEquals(500f, center.left + center.originX + 100f, 1e-4f)
        assertEquals(300f, center.top + center.baselineY, 1e-4f)
        val start = RenderMath.overlayPlacement(500f, 300f, 200f, -40f, 10f, OverlayAlign.START, 4)
        assertEquals(500f, start.left + start.originX, 1e-4f)
        val end = RenderMath.overlayPlacement(500f, 300f, 200f, -40f, 10f, OverlayAlign.END, 4)
        assertEquals(500f, end.left + end.originX + 200f, 1e-4f)
        assertTrue(end.left + end.width >= 504)
    }

    @Test
    fun fittedTextSize_shrinksOnlyRunsWiderThanAPositiveFiniteLimit() {
        assertEquals(13f, RenderMath.fittedTextSize(26f, 600f, 300f), 1e-5f)
        assertEquals("already fits", 26f, RenderMath.fittedTextSize(26f, 280f, 300f), 0f)
        assertEquals("exact fit", 26f, RenderMath.fittedTextSize(26f, 300f, 300f), 0f)
        assertEquals("no limit", 26f, RenderMath.fittedTextSize(26f, 9000f, Float.POSITIVE_INFINITY), 0f)
        assertEquals("NaN limit", 26f, RenderMath.fittedTextSize(26f, 9000f, Float.NaN), 0f)
        assertEquals("non-positive limit", 26f, RenderMath.fittedTextSize(26f, 9000f, 0f), 0f)
    }

    @Test
    fun haloExtent_coversHalfTheStrokeAndTheBlurTail() {
        val size = 26f
        val extent = RenderMath.haloExtentPx(size)
        val blurSigma = 0.577f * size * RenderMath.HALO_BLUR_EM + 0.5f
        assertEquals(size * RenderMath.HALO_STROKE_EM / 2f + 3f * blurSigma, extent, 1e-4f)
        assertTrue(RenderMath.haloExtentPx(52f) > extent)
    }

    @Test
    fun mipLevelCount_coversTheFullChain() {
        assertEquals(1, RenderMath.mipLevelCount(1, 1))
        assertEquals(12, RenderMath.mipLevelCount(4000, 3000))
        assertEquals(11, RenderMath.mipLevelCount(1024, 1))
    }

    @Test
    fun argbToLinearPremultiplied_decodesAndPremultiplies() {
        val opaque = RenderMath.argbToLinearPremultiplied(0xFF0B0C0E.toInt())
        assertEquals(RenderMath.srgbDecode(0x0B / 255f), opaque[0], 1e-7f)
        assertEquals(1f, opaque[3], 0f)
        val half = RenderMath.argbToLinearPremultiplied(0x80FFFFFF.toInt())
        assertEquals(128 / 255f, half[0], 1e-6f)
        val log = RenderMath.encodeWorking(floatArrayOf(0.5f, 0.25f, 0f, 0.5f), logDomain = true)
        assertEquals(RenderMath.logEncode(1f) * 0.5f, log[0], 1e-6f)
        assertEquals(0.5f, log[3], 0f)
    }
}
