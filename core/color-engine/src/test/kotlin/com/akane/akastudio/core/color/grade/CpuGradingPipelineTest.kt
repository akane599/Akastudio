package com.akane.akastudio.core.color.grade

import com.akane.akastudio.core.color.api.GradeParams
import com.akane.akastudio.core.color.api.HarmonizationParams
import com.akane.akastudio.core.color.api.MasterLook
import com.akane.akastudio.core.color.math.ColorMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.roundToInt

class CpuGradingPipelineTest {

    private val pureLuts = LookLutCache.get(MasterLook.PURE_NATURAL_PRO)
    private val noLook = GradeParams.NEUTRAL.copy(lookIntensity = 0f)
    private val samples = listOf(
        floatArrayOf(0.18f, 0.18f, 0.18f),
        floatArrayOf(0.6f, 0.3f, 0.1f),
        floatArrayOf(0.05f, 0.2f, 0.7f),
        floatArrayOf(0.9f, 0.85f, 0.8f),
        floatArrayOf(0.01f, 0.02f, 0.015f),
    )

    private fun pixel(rgb: FloatArray, grade: GradeParams, luts: LookLuts = pureLuts): FloatArray =
        FloatArray(3).also { CpuGradingPipeline.gradePixel(rgb, grade, luts, it) }

    @Test
    fun neutralWithLookOff_returnsInput() {
        for (s in samples) assertArrayEquals(s, pixel(s, noLook), 1e-4f)
        val hdr = floatArrayOf(3.5f, 1.2f, 0.4f)
        assertArrayEquals(hdr, pixel(hdr, noLook), 1e-4f)
    }

    @Test
    fun exposurePlusOne_doublesLinear() {
        for (s in samples) {
            val out = pixel(s, noLook.copy(exposureEv = 1f))
            for (c in 0 until 3) assertEquals(2f * s[c], out[c], 1e-5f)
        }
    }

    @Test
    fun contrastZero_isIdentity_andContrastKeepsChromaAxes() {
        for (s in samples) assertArrayEquals(s, pixel(s, noLook.copy(contrast = 0f)), 1e-5f)

        val s = floatArrayOf(0.6f, 0.3f, 0.1f)
        val inLab = ColorMath.linearSrgbToOkLab(s[0], s[1], s[2])
        val out = pixel(s, noLook.copy(contrast = 0.8f))
        val outLab = ColorMath.linearSrgbToOkLab(out[0], out[1], out[2])
        assertEquals(inLab.a, outLab.a, 1e-4f)
        assertEquals(inLab.b, outLab.b, 1e-4f)
        assertTrue("L above 0.5 rises with contrast", outLab.l > inLab.l)
        val dark = pixel(floatArrayOf(0.02f, 0.02f, 0.02f), noLook.copy(contrast = 0.8f))
        assertTrue("dark greys fall with contrast", dark[1] < 0.02f)
        val flat = pixel(floatArrayOf(0.02f, 0.02f, 0.02f), noLook.copy(contrast = -0.8f))
        assertTrue("dark greys rise with negative contrast", flat[1] > 0.02f)
    }

    @Test
    fun saturationMinusOne_removesChroma() {
        for (look in listOf(MasterLook.PURE_NATURAL_PRO, MasterLook.KODACHROME_64_CLASSIC)) {
            for (s in samples) {
                val out = pixel(s, GradeParams(look = look, saturation = -1f), LookLutCache.get(look))
                val lab = ColorMath.linearSrgbToOkLab(out[0], out[1], out[2])
                assertEquals(0f, hypot(lab.a, lab.b), 1e-4f)
            }
        }
    }

    @Test
    fun fullSkinProtection_givesSkinSafeLutOnSkin() {
        val luts = LookLutCache.get(MasterLook.PORTRA_400_GOLDEN)
        val skin = ColorMath.okLabToLinearSrgb(ColorMath.okLchToOkLab(0.65f, 0.08f, 50f))
        val out = pixel(skin, GradeParams(look = MasterLook.PORTRA_400_GOLDEN, skinProtection = 1f), luts)

        val enc = FloatArray(3) { ColorMath.linearToSrgb(skin[it]) }
        val safe = FloatArray(3)
        luts.skinSafe.sample(enc[0], enc[1], enc[2], safe)
        val full = FloatArray(3)
        luts.look.sample(enc[0], enc[1], enc[2], full)
        for (c in 0 until 3) assertEquals(ColorMath.srgbToLinear(safe[c]), out[c], 1e-4f)
        assertFalse("full and skin-safe differ on skin", (0 until 3).all { kotlin.math.abs(full[it] - safe[it]) < 1e-3f })
    }

    @Test
    fun harmonization_appliesOkLabAffine() {
        val h = HarmonizationParams(1.1f, -0.02f, 0.9f, 0.01f, 1f, -0.005f)
        val s = floatArrayOf(0.4f, 0.3f, 0.2f)
        val inLab = ColorMath.linearSrgbToOkLab(s[0], s[1], s[2])
        val out = pixel(s, noLook.copy(harmonization = h))
        val outLab = ColorMath.linearSrgbToOkLab(out[0], out[1], out[2])
        assertEquals(1.1f * inLab.l - 0.02f, outLab.l, 1e-4f)
        assertEquals(0.9f * inLab.a + 0.01f, outLab.a, 1e-4f)
        assertEquals(inLab.b - 0.005f, outLab.b, 1e-4f)
    }

    @Test
    fun hdrHighlights_keepHeadroomThroughTheLook() {
        val out = pixel(floatArrayOf(4f, 4f, 4f), GradeParams.NEUTRAL)
        val white = FloatArray(3)
        pureLuts.look.sample(1f, 1f, 1f, white)
        for (c in 0 until 3) assertEquals(4f * ColorMath.srgbToLinear(white[c]), out[c], 1e-4f)
    }

    @Test
    fun grade_withoutSpatialStages_equalsPerPixelStages() {
        val image = gradient(24, 16)
        for (grade in listOf(GradeParams.NEUTRAL, GradeParams(contrast = 0.3f, temperature = 0.4f, saturation = 0.2f))) {
            val out = CpuGradingPipeline.grade(image, grade, seed = 9)
            val px = FloatArray(3)
            for (i in 0 until image.width * image.height) {
                CpuGradingPipeline.gradePixel(image.rgba.copyOfRange(i * 4, i * 4 + 3), grade, pureLuts, px)
                for (c in 0 until 3) assertEquals(px[c], out.rgba[i * 4 + c], 0f)
                assertEquals(image.rgba[i * 4 + 3], out.rgba[i * 4 + 3], 0f)
            }
        }
    }

    @Test
    fun halation_addsWarmEnergyOnlyNearBrightPixels() {
        val w = 200
        val h = 200
        val rgba = FloatArray(w * h * 4)
        for (i in 0 until w * h) {
            rgba[i * 4] = 0.05f; rgba[i * 4 + 1] = 0.05f; rgba[i * 4 + 2] = 0.05f; rgba[i * 4 + 3] = 1f
        }
        val cx = 40
        val cy = 100
        for (dy in -1..1) for (dx in -1..1) {
            val p = ((cy + dy) * w + cx + dx) * 4
            rgba[p] = 6f; rgba[p + 1] = 6f; rgba[p + 2] = 6f
        }
        val image = LinearImage(w, h, rgba)
        val base = CpuGradingPipeline.grade(image, GradeParams.NEUTRAL)
        val glow = CpuGradingPipeline.grade(image, GradeParams(halation = 1f))
        val sigma = Halation.sigma(w, h)

        val nearX = cx + (2 * sigma).roundToInt() + 2
        val near = (cy * w + nearX) * 4
        val dr = glow.rgba[near] - base.rgba[near]
        val db = glow.rgba[near + 2] - base.rgba[near + 2]
        assertTrue("red glow near the dot ($dr)", dr > 1e-3f)
        assertTrue("glow is red-orange", dr > 5f * db && db >= 0f)

        val farX = cx + (20 * sigma).roundToInt() + 1
        val far = (cy * w + farX) * 4
        for (c in 0 until 3) assertEquals(base.rgba[far + c], glow.rgba[far + c], 1e-4f)
    }

    @Test
    fun grain_isDeterministicAndMeanPreserving() {
        val w = 256
        val h = 256
        val grey = 0.18f
        val rgba = FloatArray(w * h * 4) { if (it % 4 == 3) 1f else grey }
        val image = LinearImage(w, h, rgba)
        val grade = GradeParams(grain = 0.5f)
        val base = CpuGradingPipeline.grade(image, GradeParams.NEUTRAL)
        val a = CpuGradingPipeline.grade(image, grade, seed = 42)
        val b = CpuGradingPipeline.grade(image, grade, seed = 42)
        val c = CpuGradingPipeline.grade(image, grade, seed = 43)
        assertArrayEquals(a.rgba, b.rgba, 0f)
        assertFalse(a.rgba.contentEquals(c.rgba))

        var baseL = 0.0
        var grainL = 0.0
        var baseLin = 0.0
        var grainLin = 0.0
        var varL = 0.0
        for (i in 0 until w * h) {
            val p = i * 4
            val lb = ColorMath.linearSrgbToOkLab(base.rgba[p], base.rgba[p + 1], base.rgba[p + 2]).l
            val lg = ColorMath.linearSrgbToOkLab(a.rgba[p], a.rgba[p + 1], a.rgba[p + 2]).l
            baseL += lb
            grainL += lg
            varL += (lg - lb) * (lg - lb)
            baseLin += base.rgba[p + 1]
            grainLin += a.rgba[p + 1]
        }
        val count = (w * h).toDouble()
        assertEquals(baseL / count, grainL / count, 0.005)
        assertEquals(baseLin / count, grainLin / count, 0.005)
        assertTrue("grain is visible", varL / count > 1e-5)
    }

    @Test
    fun monochromeGrain_hasNoColour() {
        val w = 64
        val h = 64
        val rgba = FloatArray(w * h * 4) { if (it % 4 == 3) 1f else 0.2f }
        val out = CpuGradingPipeline.grade(LinearImage(w, h, rgba), GradeParams(look = MasterLook.VELVET_MONOCHROME_HP5))
        for (i in 0 until w * h) {
            val p = i * 4
            val lab = ColorMath.linearSrgbToOkLab(out.rgba[p], out.rgba[p + 1], out.rgba[p + 2])
            assertEquals(0f, hypot(lab.a, lab.b), 1e-3f)
        }
    }

    @Test
    fun everyLook_gradesWithoutNaN() {
        val image = gradient(32, 32)
        for (look in MasterLook.entries) {
            val out = CpuGradingPipeline.grade(image, GradeParams(look = look, halation = 0.3f, grain = 0.3f), seed = 1)
            assertTrue("$look produced non-finite output", out.rgba.all { it.isFinite() })
        }
    }

    private fun gradient(w: Int, h: Int): LinearImage {
        val rgba = FloatArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val p = (y * w + x) * 4
            rgba[p] = x / (w - 1f) * 1.5f
            rgba[p + 1] = y / (h - 1f)
            rgba[p + 2] = (x + y) / (w + h - 2f) * 0.8f
            rgba[p + 3] = 0.5f + 0.5f * x / (w - 1f)
        }
        return LinearImage(w, h, rgba)
    }
}
