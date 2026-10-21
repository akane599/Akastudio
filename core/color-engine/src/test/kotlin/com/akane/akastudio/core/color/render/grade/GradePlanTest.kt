package com.akane.akastudio.core.color.render.grade

import com.akane.akastudio.core.color.api.GradeParams
import com.akane.akastudio.core.color.api.HarmonizationParams
import com.akane.akastudio.core.color.api.MasterLook
import com.akane.akastudio.core.color.grade.Halation
import com.akane.akastudio.core.color.grade.WhiteBalance
import com.akane.akastudio.core.color.look.LookLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class GradePlanTest {

    private fun plan(
        sw: Int, sh: Int, l: Float, t: Float, r: Float, b: Float, ow: Int, oh: Int, max: Int = 16384,
    ) = GradePlanner.plan(sw, sh, l, t, r, b, ow, oh, max, max)

    @Test
    fun fullCropMapsTheSourceOntoTheOutputSize() {
        val p = plan(4000, 3000, 0f, 0f, 4000f, 3000f, 1080, 810)
        assertEquals(1080, p.width)
        assertEquals(810, p.height)
        assertEquals(0f, p.regionLeft, 0f)
        assertEquals(1080f, p.regionWidth, 1e-3f)
        assertEquals(1f, p.outPerTexelX, 1e-6f)
        assertEquals(1f, p.outPerTexelY, 1e-6f)
        assertEquals("full crop grades every texel", com.akane.akastudio.core.color.render.PixelRect(0, 0, 1080, 810), p.scissor)
    }

    @Test
    fun cropRegionLandsOnExactlyTheOutputTexels() {
        val p = plan(4000, 3000, 1000f, 500f, 3000f, 2000f, 1000, 750)
        assertEquals(2000, p.width)
        assertEquals(1500, p.height)
        assertEquals(500f, p.regionLeft, 1e-3f)
        assertEquals(250f, p.regionTop, 1e-3f)
        assertEquals(1000f, p.regionWidth, 1e-3f)
        assertEquals(750f, p.regionHeight, 1e-3f)
    }

    @Test
    fun scissorCoversEveryRotationOfTheCrop() {
        val p = plan(4000, 3000, 1000f, 500f, 3000f, 2000f, 1000, 750)
        val radius = sqrt(1000f * 1000f + 750f * 750f) / 2f
        val cx = 500f + 500f
        val cy = 250f + 375f
        assertTrue(p.scissor.x <= cx - radius && p.scissor.x + p.scissor.width >= cx + radius)
        assertTrue(p.scissor.y <= cy - radius && p.scissor.y + p.scissor.height >= cy + radius)
        assertTrue("scissor stays inside the texture", p.scissor.x >= 0 && p.scissor.x + p.scissor.width <= p.width)
    }

    @Test
    fun deepZoomIsCappedButNeverBelowNativeOrOutputDensity() {
        val p = plan(4000, 3000, 1800f, 1350f, 2200f, 1650f, 1080, 810)
        // Requested 10800x8100 exceeds the budget; native source density (4000x3000) is the floor.
        assertEquals(4000, p.width)
        assertEquals(3000, p.height)
        assertTrue(p.outPerTexelX > 1f)
        // A small source zoomed far: the budget caps the texture, still well above the 40x30 native region.
        val small = plan(400, 300, 180f, 135f, 220f, 165f, 1080, 810)
        val pixels = small.width.toLong() * small.height
        assertTrue("working texture $pixels px must respect the budget", pixels <= GradePlanner.MAX_WORKING_PIXELS * 1.01)
        assertTrue("budget-capped region ${small.regionWidth} stays above native density", small.regionWidth > 40f)
        assertTrue(small.outPerTexelX > 1f)
    }

    @Test
    fun targetLimitsAreHard() {
        val p = plan(4000, 3000, 0f, 0f, 4000f, 3000f, 8000, 6000, max = 4096)
        assertTrue(p.width <= 4096 && p.height <= 4096)
    }

    @Test
    fun degenerateRegionMeansTheWholeSource() {
        val p = plan(400, 300, 10f, 10f, 10f, 50f, 200, 150)
        assertEquals(200, p.width)
        assertEquals(400f * 0.5f, p.regionWidth, 1e-3f)
    }

    @Test
    fun halationSigmaFollowsTheOutputShortEdge() {
        val p = plan(4000, 3000, 0f, 0f, 4000f, 3000f, 1080, 810)
        assertEquals(Halation.sigma(1080, 810), p.halationSigmaX, 1e-4f)
        assertEquals(Halation.sigma(1080, 810), p.halationSigmaY, 1e-4f)
    }

    @Test
    fun halationChainHalvesAndComposesToOneTwoFourSigma() {
        val p = plan(1000, 1000, 0f, 0f, 1000f, 1000f, 1000, 1000)
        val levels = GradePlanner.halationLevels(p)
        assertEquals(listOf(500, 250, 125), levels.map { it.width })
        val sigma = p.halationSigmaX
        // Total variance at level i, in full-resolution texels.
        var variance = 0.0
        levels.forEachIndexed { i, level ->
            val add = level.blurSigmaX * p.width / level.width
            variance += add.toDouble() * add
            assertEquals(Halation.SCALES[i] * sigma, sqrt(variance).toFloat(), 1e-3f)
        }
    }

    @Test
    fun uniformsMirrorTheCpuClampingAndLookDefaults() {
        val params = GradeParams(
            look = MasterLook.CINESTILL_800T_NIGHT,
            lookIntensity = 1.5f,
            exposureEv = 1f,
            temperature = 0.5f,
            tint = -0.25f,
            contrast = -3f,
            saturation = 2f,
            halation = 0.9f,
            grain = -1f,
            skinProtection = 2f,
        )
        val u = GradeUniforms.from(params)
        val spec = LookLibrary.spec(MasterLook.CINESTILL_800T_NIGHT)
        assertEquals(2f, u.exposure, 0f)
        assertEquals(1f, u.lookIntensity, 0f)
        assertEquals(1f, u.skinProtection, 0f)
        assertEquals(-1f, u.contrast, 0f)
        assertEquals(2f, u.chromaScale, 0f)
        assertEquals((spec.defaultHalation + 0.9f).coerceIn(0f, 1f) * Halation.STRENGTH, u.halationAmount, 0f)
        assertEquals(spec.defaultGrain, u.grainAmount, 0f)
        assertFalse(u.harmonize)
        val wb = WhiteBalance.matrix(0.5f, -0.25f)
        for (row in 0 until 3) for (col in 0 until 3) {
            assertEquals("column-major wb[$row][$col]", wb[row * 3 + col], u.whiteBalance[col * 3 + row], 0f)
        }
    }

    @Test
    fun harmonizationIsOnlySkippedForTheIdentity() {
        val h = HarmonizationParams(1.1f, 0.01f, 1f, 0f, 1f, 0f)
        assertTrue(GradeUniforms.from(GradeParams(harmonization = h)).harmonize)
        assertFalse(GradeUniforms.from(GradeParams()).harmonize)
    }

    @Test
    fun neutralPureNaturalHasNoFilmEffects() {
        assertFalse(GradeUniforms.from(GradeParams.NEUTRAL).hasFilmEffects)
        assertTrue(GradeUniforms.from(GradeParams(look = MasterLook.PORTRA_400_GOLDEN)).hasFilmEffects)
    }

    @Test
    fun nonFiniteInputIsRejectedLikeTheCpu() {
        assertThrows(IllegalArgumentException::class.java) { GradeUniforms.from(GradeParams(exposureEv = Float.NaN)) }
        assertThrows(IllegalArgumentException::class.java) { GradeUniforms.from(GradeParams(saturation = Float.POSITIVE_INFINITY)) }
        assertThrows(IllegalArgumentException::class.java) {
            GradeUniforms.from(GradeParams(harmonization = HarmonizationParams(Float.NaN, 0f, 1f, 0f, 1f, 0f)))
        }
    }
}
