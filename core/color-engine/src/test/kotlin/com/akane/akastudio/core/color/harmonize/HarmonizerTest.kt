package com.akane.akastudio.core.color.harmonize

import com.akane.akastudio.core.color.api.HarmonizationParams
import com.akane.akastudio.core.color.api.LabStats
import com.akane.akastudio.core.color.api.OkLab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

class HarmonizerTest {

    private fun stats(
        l: Float, a: Float, b: Float,
        sl: Float = 0.2f, sa: Float = 0.05f, sb: Float = 0.05f,
    ) = LabStats(l, a, b, sl, sa, sb, weight = 1_000f)

    private val source = stats(0.55f, 0.01f, 0.02f)
    private val reference = stats(0.62f, -0.01f, 0.04f, sl = 0.22f, sa = 0.04f, sb = 0.06f)

    @Test
    fun strengthZero_isExactIdentity() {
        assertEquals(HarmonizationParams.IDENTITY, Harmonizer.params(source, reference, 0f))
        assertEquals(HarmonizationParams.IDENTITY, Harmonizer.params(source, reference, -0.5f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun nanStrength_isRejected() {
        Harmonizer.params(source, reference, Float.NaN)
    }

    @Test
    fun identicalStats_giveIdentity() {
        for (strength in floatArrayOf(0.3f, 1f)) {
            assertEquals(HarmonizationParams.IDENTITY, Harmonizer.params(source, source, strength))
            assertEquals(HarmonizationParams.IDENTITY, Harmonizer.params(reference, reference, strength))
        }
        val flat = stats(0.4f, 0.02f, -0.03f, sl = 0f, sa = 0f, sb = 0f)
        assertEquals(HarmonizationParams.IDENTITY, Harmonizer.params(flat, flat, 1f))
    }

    @Test
    fun fullStrengthWithEqualStd_offsetsByLambdaTimesMeanGapUntilCap() {
        val src = stats(0.50f, 0.00f, 0.00f)
        val small = Harmonizer.params(src, stats(0.60f, 0.05f, -0.04f), 1f)
        assertEquals(1f, small.scaleL, 0f)
        assertEquals(0.55f * 0.10f, small.offsetL, 1e-6f)
        assertEquals(1f, small.scaleA, 0f)
        assertEquals(0.70f * 0.05f, small.offsetA, 1e-6f)
        assertEquals(0.70f * -0.04f, small.offsetB, 1e-6f)

        val large = Harmonizer.params(src, stats(0.90f, 0.20f, -0.20f), 1f)
        assertEquals("0.55 * 0.40 = 0.22 caps at 0.15", 0.15f, large.offsetL, 0f)
        assertEquals(0.06f, large.offsetA, 0f)
        assertEquals(-0.06f, large.offsetB, 0f)
    }

    @Test
    fun contrastRatio_clampsAtHalfAndTwo() {
        val src = stats(0.5f, 0f, 0f, sl = 0.1f, sa = 0.02f, sb = 0.02f)
        val wider = Harmonizer.params(src, stats(0.5f, 0f, 0f, sl = 1f, sa = 0.2f, sb = 0.2f), 1f)
        assertEquals("k = 2", 1f + 0.55f * (2f - 1f), wider.scaleL, 1e-6f)
        assertEquals(1f + 0.70f * (2f - 1f), wider.scaleA, 1e-6f)
        val narrower = Harmonizer.params(src, stats(0.5f, 0f, 0f, sl = 0.01f, sa = 0.002f, sb = 0.002f), 1f)
        assertEquals("k = 0.5", 1f + 0.55f * (0.5f - 1f), narrower.scaleL, 1e-6f)
        assertEquals(1f + 0.70f * (0.5f - 1f), narrower.scaleB, 1e-6f)
        // a flat source against a textured reference: sigma_s floors at 1e-4, k clamps to 2
        val flatSource = Harmonizer.params(stats(0.5f, 0f, 0f, sl = 0f), stats(0.5f, 0f, 0f, sl = 0.1f), 1f)
        assertEquals(1.55f, flatSource.scaleL, 1e-6f)
    }

    @Test
    fun offsetCaps_holdWithoutTouchingScale() {
        val src = stats(0.2f, -0.3f, 0.3f, sl = 0.1f, sa = 0.05f, sb = 0.05f)
        val ref = stats(0.95f, 0.3f, -0.3f, sl = 0.15f, sa = 0.08f, sb = 0.03f)
        val p = Harmonizer.params(src, ref, 1f)
        assertEquals(1f + 0.55f * (1.5f - 1f), p.scaleL, 1e-6f)
        assertEquals(1f + 0.70f * (1.6f - 1f), p.scaleA, 1e-6f)
        assertEquals(1f + 0.70f * (0.6f - 1f), p.scaleB, 1e-6f)
        // The cap limits only the mean shift; the offset still re-centres the scale on the source mean.
        assertEquals(0.2f * (1f - p.scaleL) + 0.15f, p.offsetL, 1e-6f)
        assertEquals(-0.3f * (1f - p.scaleA) + 0.06f, p.offsetA, 1e-6f)
        assertEquals(0.3f * (1f - p.scaleB) - 0.06f, p.offsetB, 1e-6f)
        assertEquals(0.15f, meanShift(src.meanL, p.scaleL, p.offsetL), 1e-6f)
        assertEquals(0.06f, meanShift(src.meanA, p.scaleA, p.offsetA), 1e-6f)
        assertEquals(-0.06f, meanShift(src.meanB, p.scaleB, p.offsetB), 1e-6f)
        val reverse = Harmonizer.params(ref, src, 1f)
        assertEquals(-0.15f, meanShift(ref.meanL, reverse.scaleL, reverse.offsetL), 1e-6f)
        assertEquals(-0.06f, meanShift(ref.meanA, reverse.scaleA, reverse.offsetA), 1e-6f)
        assertEquals(0.06f, meanShift(ref.meanB, reverse.scaleB, reverse.offsetB), 1e-6f)
    }

    @Test
    fun meanShift_staysWithinCapForEveryContrastRatio() {
        for (k in floatArrayOf(0.5f, 1.5f, 2f)) {
            for ((sourceMean, referenceMean) in listOf(0.6f to 0.6f, 0.2f to 0.95f, 0.9f to 0.1f, 0.5f to 0.55f)) {
                val src = stats(sourceMean, sourceMean - 0.5f, 0.5f - sourceMean, sl = 0.1f, sa = 0.04f, sb = 0.04f)
                val ref = stats(
                    referenceMean, referenceMean - 0.5f, 0.5f - referenceMean,
                    sl = 0.1f * k, sa = 0.04f * k, sb = 0.04f * k,
                )
                val p = Harmonizer.params(src, ref, 1f)
                val label = "k = $k, mu_s = $sourceMean, mu_r = $referenceMean"
                val shiftL = meanShift(src.meanL, p.scaleL, p.offsetL)
                val shiftA = meanShift(src.meanA, p.scaleA, p.offsetA)
                val shiftB = meanShift(src.meanB, p.scaleB, p.offsetB)
                assertTrue("$label: L shift $shiftL", abs(shiftL) <= Harmonizer.MAX_OFFSET_L + 1e-6f)
                assertTrue("$label: a shift $shiftA", abs(shiftA) <= Harmonizer.MAX_OFFSET_AB + 1e-6f)
                assertTrue("$label: b shift $shiftB", abs(shiftB) <= Harmonizer.MAX_OFFSET_AB + 1e-6f)
                // The shift always heads toward the reference and never past it.
                val gapL = referenceMean - sourceMean
                assertTrue("$label: L shift $shiftL vs gap $gapL", shiftL * gapL >= 0f && abs(shiftL) <= abs(gapL) + 1e-6f)
            }
        }
        // The audit scenario: equal means, k = 2, default strength 0.8 used to lift mean L 0.6 to 0.714.
        val flat = stats(0.6f, 0f, 0f, sl = 0.05f)
        val contrasty = stats(0.6f, 0f, 0f, sl = 0.2f)
        val p = Harmonizer.params(flat, contrasty, 0.8f)
        assertEquals(0.6f, p.scaleL * 0.6f + p.offsetL, 1e-6f)
    }

    private fun meanShift(sourceMean: Float, scale: Float, offset: Float): Float = scale * sourceMean + offset - sourceMean

    @Test
    fun strengthAboveOne_isClamped() {
        assertEquals(Harmonizer.params(source, reference, 1f), Harmonizer.params(source, reference, 3f))
    }

    @Test
    fun forCollage_putsIdentityAtAnchorAndKeepsSize() {
        val third = stats(0.4f, 0.03f, -0.02f, sl = 0.18f)
        val list = listOf(source, reference, third)
        val params = Harmonizer.forCollage(list, anchorIndex = 1, strength = 0.8f)
        assertEquals(3, params.size)
        assertSame(HarmonizationParams.IDENTITY, params[1])
        assertEquals(Harmonizer.params(source, reference, 0.8f), params[0])
        assertEquals(Harmonizer.params(third, reference, 0.8f), params[2])
        assertNotEquals(HarmonizationParams.IDENTITY, params[0])
        assertEquals(listOf(HarmonizationParams.IDENTITY), Harmonizer.forCollage(listOf(source), 0, 1f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun forCollage_rejectsAnchorOutsideList() {
        Harmonizer.forCollage(listOf(source, reference), anchorIndex = 2, strength = 1f)
    }

    @Test
    fun apply_identityLeavesColourUnchanged() {
        val lab = OkLab(0.61f, -0.02f, 0.07f)
        assertEquals(lab, Harmonizer.apply(lab, HarmonizationParams.IDENTITY))
    }

    @Test
    fun apply_isPerChannelAffine() {
        val p = HarmonizationParams(1.2f, 0.01f, 0.8f, -0.02f, 1.1f, 0.03f)
        val out = Harmonizer.apply(OkLab(0.5f, 0.1f, -0.1f), p)
        assertEquals(1.2f * 0.5f + 0.01f, out.l, 1e-7f)
        assertEquals(0.8f * 0.1f - 0.02f, out.a, 1e-7f)
        assertEquals(1.1f * -0.1f + 0.03f, out.b, 1e-7f)
    }

    @Test
    fun apply_roundTripMovesWeightedMeanTowardReferenceByExpectedFraction() {
        val random = Random(2026)
        val samples = List(5_000) {
            OkLab(0.3f + 0.4f * random.nextFloat(), -0.05f + 0.1f * random.nextFloat(), -0.04f + 0.1f * random.nextFloat())
        }
        val weights = DoubleArray(samples.size) { 0.2 + random.nextDouble() }
        val src = weightedStats(samples, weights)
        val ref = stats(src.meanL + 0.12f, src.meanA - 0.03f, src.meanB + 0.04f, sl = src.stdL * 1.3f, sa = src.stdA * 0.8f, sb = src.stdB)
        val strength = 0.6f
        val p = Harmonizer.params(src, ref, strength)
        val moved = weightedStats(samples.map { Harmonizer.apply(it, p) }, weights)

        val wL = Harmonizer.LAMBDA_L * strength
        val wC = Harmonizer.LAMBDA_C * strength
        assertEquals(src.meanL + wL * (ref.meanL - src.meanL), moved.meanL, 1e-5f)
        assertEquals(src.meanA + wC * (ref.meanA - src.meanA), moved.meanA, 1e-5f)
        assertEquals(src.meanB + wC * (ref.meanB - src.meanB), moved.meanB, 1e-5f)
        assertEquals(p.scaleL * src.stdL, moved.stdL, 1e-5f)
        assertEquals(p.scaleA * src.stdA, moved.stdA, 1e-5f)
    }

    private fun weightedStats(samples: List<OkLab>, weights: DoubleArray): LabStats {
        val total = weights.sum()
        fun mean(channel: (OkLab) -> Float) = samples.indices.sumOf { weights[it] * channel(samples[it]) } / total
        fun std(channel: (OkLab) -> Float, m: Double) =
            sqrt(samples.indices.sumOf { val d = channel(samples[it]) - m; weights[it] * d * d } / total)
        val ml = mean { it.l }
        val ma = mean { it.a }
        val mb = mean { it.b }
        return LabStats(
            ml.toFloat(), ma.toFloat(), mb.toFloat(),
            std({ it.l }, ml).toFloat(), std({ it.a }, ma).toFloat(), std({ it.b }, mb).toFloat(),
            total.toFloat(),
        )
    }
}
