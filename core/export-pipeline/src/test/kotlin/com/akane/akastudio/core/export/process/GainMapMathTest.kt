package com.akane.akastudio.core.export.process

import com.akane.akastudio.core.color.math.ColorMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.akane.akastudio.core.color.api.HalfFloatImage
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.max
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
        // A frame whose channels all fit SDR keeps the identity base.
        assertArrayEquals(src.rgba, result.sdr.rgba)
    }

    @Test
    fun hdrPeakPreservesChannelsAtAndBelowTheShoulderKnee() {
        val src = halfImage(8, 4) { x, _ ->
            if (x < 4) floatArrayOf(0.6f, 0.3f, 0.15f, 0.4f) else floatArrayOf(4f, 4f, 4f, 0.7f)
        }
        val base = GainMapMath.compute(src).sdr
        for (y in 0 until src.height) for (x in 0 until src.width) {
            assertEquals("alpha passes through", src.at(x, y, 3), base.at(x, y, 3), 0f)
            if (x < 4) for (c in 0..2) {
                assertEquals("the HDR peak must not darken channel $c below the knee", src.at(x, y, c), base.at(x, y, c), 0f)
            }
        }
    }

    /** The knee is the maximum linear RGB channel, so a saturated peak also compresses high SDR colours. */
    @Test
    fun saturatedRedPeakCompressesHighSdrColoursButKeepsBlackAndLowChannels() {
        val src = halfImage(17, 5) { x, _ ->
            when {
                x < 4 -> gray(0f)
                x < 8 -> gray(0.001f)
                x < 12 -> floatArrayOf(4f, 0f, 0f, 1f)
                x < 16 -> floatArrayOf(0.8f, 0.4f, 0.2f, 1f)
                else -> gray(0.5f)
            }
        }
        for (downscale in listOf(1, 4, 7)) {
            val gain = GainMapMath.compute(src, gainDownscale = downscale)
            assertEquals(gain.ratioMax, GainMapMath.ratioMax(src, gainDownscale = downscale), 0f)
            val base = gain.sdr
            for (x in listOf(0, 4, 16)) for (c in 0..2) {
                assertEquals("black, near-black and the partial-edge cell retain identity", src.at(x, 0, c), base.at(x, 0, c), 0f)
            }
            assertTrue("high SDR colour enters the linear max-channel shoulder", base.at(12, 0, 0) in 0.735f..0.742f)
            assertEquals("red peak fits white", 1f, base.at(8, 0, 0), 0f)
        }
    }

    @Test
    fun shoulderIsSmoothAtTheKneeMonotoneBoundedAndPreservesColourRatios() {
        val src = halfImage(1025, 1) { x, _ ->
            val m = x / 256f
            floatArrayOf(m, m / 2f, m / 4f, 1f)
        }
        val gain = GainMapMath.compute(src, gainDownscale = 1)
        val base = gain.sdr
        for (x in 1 until src.width) {
            val red = base.at(x, 0, 0)
            assertTrue("shoulder must stay monotone at $x", red >= base.at(x - 1, 0, 0))
            assertTrue("shoulder fits SDR without brightening at $x", red in 0f..minOf(1f, src.at(x, 0, 0)))
            assertEquals("green/red ratio at $x", 0.5f, base.at(x, 0, 1) / red, 0.001f)
            assertEquals("blue/red ratio at $x", 0.25f, base.at(x, 0, 2) / red, 0.001f)
        }
        val step = 1f / 256f
        val beforeKnee = (base.at(153, 0, 0) - base.at(152, 0, 0)) / step
        val afterKnee = (base.at(155, 0, 0) - base.at(154, 0, 0)) / step
        assertEquals("identity slope below knee", 1f, beforeKnee, 0.001f)
        assertEquals("continuous slope above knee, within half storage precision", beforeKnee, afterKnee, 0.04f)
        assertEquals("global peak maps to SDR white", 1f, base.at(1024, 0, 0), 0f)
        assertEquals(gain.ratioMax, GainMapMath.ratioMax(src, gainDownscale = 1), 0f)
    }

    @Test
    fun nearUnitPeakApproachesTheIdentityWithoutAVisibleBaseJump() {
        for (peak in listOf(1f, 1.0009765625f, 1.00390625f, 1.0078125f, 1.03125f)) {
            val src = halfImage(8, 4) { x, _ -> gray(if (x < 4) 0.8f else peak) }
            val gain = GainMapMath.compute(src)
            assertEquals(gain.ratioMax, GainMapMath.ratioMax(src), 0f)
            val loss = src.at(0, 0, 0) - gain.sdr.at(0, 0, 0)
            assertTrue("peak $peak approaches identity (base loss $loss)", loss >= 0f && loss <= peak - 1f + 0.001f)
            if (peak <= 1.00390625f) {
                assertEquals("sub-floor gain stays unit", 1f, gain.ratioMax, 0f)
                assertArrayEquals(ByteArray(gain.gain.size), gain.gain)
            } else if (peak >= 1.03125f) {
                assertTrue("usable gain exceeds the unchanged 0.01-stop floor", gain.ratioMax > 1f)
            }
        }
    }

    @Test
    fun sdrBaseMatchesGainMapBaseForSdrHdrSignedAndNonfinitePixels() {
        val images = listOf(
            halfImage(9, 5) { x, y -> floatArrayOf(x / 10f, y / 6f, 0.2f, 0.3f) },
            redBesideWhite(),
            halfImage(7, 5) { x, _ -> floatArrayOf(if (x < 3) 2f else 0.4f, -0.1f, 0.2f, 0.7f) },
            halfImage(5, 3) { x, _ -> floatArrayOf(Float.NaN, Float.NEGATIVE_INFINITY, if (x == 4) 3f else 0.2f, 0.5f) },
        )
        for ((index, image) in images.withIndex()) for (downscale in listOf(1, 4, 7)) {
            assertArrayEquals("fixture $index, cell $downscale", GainMapMath.compute(image, downscale).sdr.rgba, GainMapMath.sdrBase(image).rgba)
        }
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
            redBesideWhite(),
            GainMapMath.boostHighlights(cyanBesideWhite(), 2f),
            // An SDR-peak image whose only excess is one saturated channel: luminance alone sees no headroom.
            halfImage(8, 8) { x, _ -> if (x < 4) floatArrayOf(1.4f, 0.2f, 0.1f, 1f) else gray(0.5f) },
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

    /**
     * AUD-03: every channel of the SDR base fits SDR (the encoder clamps each channel to [0, 1], so a base channel
     * above 1 would be clipped after the gain was measured against it).
     */
    @Test
    fun sdrBaseKeepsEveryChannelWithinSdr() {
        val random = Random(11)
        val images = listOf(
            redBesideWhite(),
            GainMapMath.boostHighlights(cyanBesideWhite(), 2f),
            halfImage(8, 8) { x, _ -> if (x < 4) floatArrayOf(1.4f, 0.2f, 0.1f, 1f) else gray(0.5f) },
            halfImage(13, 11) { _, _ -> floatArrayOf(random.nextFloat() * 6f, random.nextFloat() * 0.5f, random.nextFloat() * 3f, 1f) },
        )
        for ((i, image) in images.withIndex()) {
            val sdr = GainMapMath.compute(image).sdr
            for (y in 0 until sdr.height) for (x in 0 until sdr.width) for (c in 0 until 3) {
                val v = sdr.at(x, y, c)
                assertTrue("image $i base ($x, $y) channel $c = $v exceeds SDR white", v <= 1f)
            }
        }
    }

    /**
     * AUD-03 (a): linear-HDR red (4, 0, 0) beside white (4, 4, 4), reconstructed with the platform formula from the
     * base as it is actually encoded (blue-noise dithered sRGB 8-bit, decoded back to linear).
     *
     * Tolerance 5% on the red channel: the gain is one luminance ratio per cell with its epsilon offset defined on
     * luminance, so a channel whose luminance is a fraction of its value gets `(Y + e) / (fY + e)` instead of
     * `(c + e) / (fc + e)`. For pure red (Y = 0.2126 c, f = 1/4, e = 1/64) that is 3.838 for 4, a 4.05% shortfall,
     * analytically; the red and white bases are exact 8-bit codes (255 / 0) and both gain codes are the range ends,
     * so quantization adds nothing here. Before the fix the base red was clipped from 2.28 to 1 and red came back as
     * 1.745 (56% low). Neutral white must come back within 0.5%, the near-zero green/blue within the epsilon floor.
     */
    @Test
    fun colouredHdrHighlightReconstructsFromTheEncodedBase() {
        val hdr = redBesideWhite()
        val r = GainMapMath.compute(hdr, gainDownscale = 4)
        val recon = reconstructFromEncodedBase(r, cell = 4)
        val red = worstRelativeError(hdr, recon, channels = intArrayOf(0)) { x, _ -> x < 8 }
        val white = worstRelativeError(hdr, recon, channels = intArrayOf(0, 1, 2)) { x, _ -> x >= 8 }
        println("AUD-03 red/white: worst red error ${"%.4f".format(red)}, worst white error ${"%.4f".format(white)}")
        assertTrue("red channel reconstructs within 5% (worst ${"%.4f".format(red)})", red <= 0.05f)
        assertTrue("white reconstructs within 0.5% (worst ${"%.5f".format(white)})", white <= 0.005f)
        val floor = r.epsilonSdr * (r.ratioMax - 1f) + 1e-3f
        for (y in 0 until hdr.height) for (x in 0 until 8) for (c in 1..2) {
            assertTrue("zero channel stays at the epsilon floor", abs(recon.at(x, y, c)) <= floor)
        }
    }

    /**
     * AUD-03 (b): SDR cyan (0, 1, 1) beside white after HDR Story Boost (cyan reaches 3.22, white 4). Tolerance 1%:
     * it covers the luminance-epsilon bias for cyan (Y = 0.787 c) plus quantization of the shoulder base. Before the
     * channel-bound fix the boosted cyan base was clipped from 1.056 to 1 and came back as 3.047 (5.45% low).
     */
    @Test
    fun boostedSdrColourReconstructsFromTheEncodedBase() {
        val hdr = GainMapMath.boostHighlights(cyanBesideWhite(), 2f)
        assertTrue("fixture boosts cyan past 3", hdr.at(0, 0, 1) > 3f)
        val r = GainMapMath.compute(hdr, gainDownscale = 4)
        val recon = reconstructFromEncodedBase(r, cell = 4)
        val cyan = worstRelativeError(hdr, recon, channels = intArrayOf(1, 2)) { x, _ -> x < 8 }
        val white = worstRelativeError(hdr, recon, channels = intArrayOf(0, 1, 2)) { x, _ -> x >= 8 }
        println("AUD-03 boosted cyan/white: worst cyan error ${"%.5f".format(cyan)}, worst white error ${"%.5f".format(white)}")
        assertTrue("cyan reconstructs within 1% (worst ${"%.5f".format(cyan)})", cyan <= 0.01f)
        assertTrue("white reconstructs within 0.5% (worst ${"%.5f".format(white)})", white <= 0.005f)
    }

    /** L6: negative working RGB cannot be encoded; the reconstruction target is the nonnegative HDR colour. */
    @Test
    fun negativeWorkingChannelsReconstructTheRepresentableHdrLuminance() {
        val hdr = halfImage(8, 8) { _, _ -> floatArrayOf(0.2f, -0.05f, 2f, 1f) }
        val gain = GainMapMath.compute(hdr)
        val reconstructed = reconstructFromEncodedBase(gain, cell = 4)
        assertCellLuminanceMatchesNonnegativeHdr(hdr, reconstructed, tolerance = 0.005f)
        assertBaseIsNonnegative(gain.sdr)
        assertEquals(gain.ratioMax, GainMapMath.ratioMax(hdr), 0f)
    }

    /** The production sharpener creates negative green/blue beside a saturated red/white edge. */
    @Test
    fun sharpenedSaturatedEdgeHasAnEncodableBaseAndMatchingHeadroom() {
        val hdr = LumaSharpen.apply(redBesideWhite())
        assertTrue("the fixture must exercise sharpening's signed RGB", hdr.rgba.indices.any {
            it % 4 < 3 && Half.toFloat(hdr.rgba[it]) < -0.001f
        })
        val gain = GainMapMath.compute(hdr)
        assertBaseIsNonnegative(gain.sdr)
        assertEquals(gain.ratioMax, GainMapMath.ratioMax(hdr), 0f)
        assertCellLuminanceMatchesNonnegativeHdr(hdr, reconstructFromEncodedBase(gain, cell = 4), tolerance = 0.015f)
    }

    private fun assertBaseIsNonnegative(base: HalfFloatImage) {
        for (y in 0 until base.height) for (x in 0 until base.width) for (c in 0 until 3) {
            val v = base.at(x, y, c)
            assertTrue("base ($x, $y) channel $c = $v must fit the encoder's [0, 1] range", v in 0f..1f)
        }
    }

    private fun assertCellLuminanceMatchesNonnegativeHdr(hdr: HalfFloatImage, reconstructed: HalfFloatImage, tolerance: Float) {
        for (cy in 0 until (hdr.height + 3) / 4) for (cx in 0 until (hdr.width + 3) / 4) {
            var expected = 0f
            var actual = 0f
            for (y in cy * 4 until minOf(hdr.height, (cy + 1) * 4)) {
                for (x in cx * 4 until minOf(hdr.width, (cx + 1) * 4)) {
                    expected += ColorMath.relativeLuminance(
                        max(0f, hdr.at(x, y, 0)), max(0f, hdr.at(x, y, 1)), max(0f, hdr.at(x, y, 2)),
                    )
                    actual += ColorMath.relativeLuminance(
                        reconstructed.at(x, y, 0), reconstructed.at(x, y, 1), reconstructed.at(x, y, 2),
                    )
                }
            }
            val relativeError = abs(actual - expected) / max(expected, 1e-4f)
            assertTrue("cell ($cx, $cy) reconstructs nonnegative HDR luminance (error $relativeError)", relativeError <= tolerance)
        }
    }

    /** Left half (8 of 16 columns, two gain cells at downscale 4) linear red at 4, right half white at 4. */
    private fun redBesideWhite() = halfImage(16, 8) { x, _ -> if (x < 8) floatArrayOf(4f, 0f, 0f, 1f) else gray(4f) }

    /** Left half SDR cyan, right half SDR white. */
    private fun cyanBesideWhite() = halfImage(16, 8) { x, _ -> if (x < 8) floatArrayOf(0f, 1f, 1f, 1f) else gray(1f) }

    /**
     * Android's gain-map reconstruction `hdr = (sdr + epsilonSdr) * 2^g - epsilonHdr` per channel, with sdr the base
     * after the encoder's quantization ([Dither.toSrgb8Row], decoded from sRGB 8-bit back to linear) and g the
     * nearest gain cell's log2 boost (gamma 1).
     */
    private fun reconstructFromEncodedBase(r: GainMapResult, cell: Int): HalfFloatImage {
        assertEquals(1f, r.gamma, 0f)
        val sdr = r.sdr
        val row = IntArray(sdr.width)
        val lo = log2(r.ratioMin)
        val hi = log2(r.ratioMax)
        return halfImageOf(sdr.width, sdr.height) { y, out ->
            Dither.toSrgb8Row(sdr, y, row)
            for (x in 0 until sdr.width) {
                val code = r.gain[(y / cell) * r.gainWidth + x / cell].toInt() and 0xFF
                val boost = 2f.pow(lo + code / 255f * (hi - lo))
                for (c in 0 until 3) {
                    val encoded = (row[x] shr (16 - 8 * c)) and 0xFF
                    out[x * 4 + c] = (ColorMath.srgbToLinear(encoded / 255f) + r.epsilonSdr) * boost - r.epsilonHdr
                }
                out[x * 4 + 3] = 1f
            }
        }
    }

    private fun halfImageOf(width: Int, height: Int, fillRow: (y: Int, out: FloatArray) -> Unit): HalfFloatImage {
        val rows = Array(height) { y -> FloatArray(width * 4).also { fillRow(y, it) } }
        return halfImage(width, height) { x, y -> rows[y].copyOfRange(x * 4, x * 4 + 4) }
    }

    private fun worstRelativeError(
        expected: HalfFloatImage,
        actual: HalfFloatImage,
        channels: IntArray,
        where: (x: Int, y: Int) -> Boolean,
    ): Float {
        var worst = 0f
        for (y in 0 until expected.height) for (x in 0 until expected.width) {
            if (!where(x, y)) continue
            for (c in channels) worst = max(worst, abs(actual.at(x, y, c) - expected.at(x, y, c)) / expected.at(x, y, c))
        }
        return worst
    }
}
