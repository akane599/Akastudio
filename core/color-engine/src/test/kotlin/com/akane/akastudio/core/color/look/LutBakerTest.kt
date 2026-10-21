package com.akane.akastudio.core.color.look

import com.akane.akastudio.core.color.api.MasterLook
import com.akane.akastudio.core.color.api.OkLab
import com.akane.akastudio.core.color.grade.LookLutCache
import com.akane.akastudio.core.color.math.ColorMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class LutBakerTest {

    private val n = LutBaker.SIZE

    @Test
    fun pureNatural_isNearIdentity() {
        val lut = LutBaker.bakeLook(LookLibrary.spec(MasterLook.PURE_NATURAL_PRO))
        val max = (n - 1).toFloat()
        var sum = 0.0
        var i = 0
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            sum += abs(lut.data[i] - r / max) + abs(lut.data[i + 1] - g / max) + abs(lut.data[i + 2] - b / max)
            i += 3
        }
        val mean = sum / (n * n * n * 3)
        assertTrue("mean |delta| $mean", mean < 0.03)

        val out = FloatArray(3)
        lut.sample(0.5f, 0.5f, 0.5f, out)
        for (c in 0 until 3) assertEquals(0.5f, out[c], 0.02f)
    }

    @Test
    fun everyLook_isMonotoneAlongGreyAxisAndFinite() {
        for (look in MasterLook.entries) {
            val luts = LookLutCache.get(look)
            for ((name, lut) in listOf("full" to luts.look, "skinSafe" to luts.skinSafe)) {
                assertEquals(n, lut.size)
                assertFalse("$look $name has NaN", lut.data.any { it.isNaN() || it.isInfinite() })
                assertTrue("$look $name out of [0,1]", lut.data.all { it in 0f..1f })
                var prevL = -1f
                var prevY = -1f
                for (i in 0 until n) {
                    val p = ((i * n + i) * n + i) * 3
                    val r = ColorMath.srgbToLinear(lut.data[p])
                    val g = ColorMath.srgbToLinear(lut.data[p + 1])
                    val b = ColorMath.srgbToLinear(lut.data[p + 2])
                    val l = ColorMath.linearSrgbToOkLab(r, g, b).l
                    val y = ColorMath.relativeLuminance(r, g, b)
                    assertTrue("$look $name OKLab L not monotone at node $i: $prevL -> $l", l >= prevL - 1e-6f)
                    assertTrue("$look $name luminance not monotone at node $i: $prevY -> $y", y >= prevY - 1e-6f)
                    prevL = l
                    prevY = y
                }
                assertTrue("$look $name grey axis must span a range", prevL > 0.8f)
            }
        }
    }

    @Test
    fun skinSafe_movesSkinLessThanFullLook() {
        val skin = ColorMath.okLchToOkLab(0.65f, 0.08f, 50f)
        val lin = ColorMath.okLabToLinearSrgb(skin)
        val er = ColorMath.linearToSrgb(lin[0])
        val eg = ColorMath.linearToSrgb(lin[1])
        val eb = ColorMath.linearToSrgb(lin[2])
        val out = FloatArray(3)
        for (look in MasterLook.entries) {
            val luts = LookLutCache.get(look)
            luts.look.sample(er, eg, eb, out)
            val dFull = deltaE(skin, labOfEncoded(out))
            luts.skinSafe.sample(er, eg, eb, out)
            val dSafe = deltaE(skin, labOfEncoded(out))
            assertTrue("$look skin-safe dE $dSafe must be < full dE $dFull", dSafe < dFull)
        }
    }

    @Test
    fun monochrome_staysNeutralInBothLuts() {
        val luts = LookLutCache.get(MasterLook.VELVET_MONOCHROME_HP5)
        for (lut in listOf(luts.look, luts.skinSafe)) {
            for (i in lut.data.indices step 3) {
                assertEquals(lut.data[i], lut.data[i + 1], 1e-5f)
                assertEquals(lut.data[i], lut.data[i + 2], 1e-5f)
            }
        }
    }

    @Test
    fun toneCurves_areMonotoneAndKeepEndpointsNearRange() {
        for (look in MasterLook.entries) {
            val tone = LookLibrary.spec(look).tone
            var prev = tone.apply(0f)
            assertEquals("$look black", tone.blackLift, prev, 1e-6f)
            for (i in 1..4000) {
                val y = tone.apply(i / 4000f)
                assertTrue("$look tone not monotone at ${i / 4000f}", y >= prev - 1e-7f)
                prev = y
            }
            assertTrue("$look white", prev in 0.9f..1f)
        }
        // The steepest accepted shoulder (whiteClip at its lower bound) is still monotone.
        val edge = ToneCurve(blackLift = 0f, toe = 1f, shoulder = 0.7f, contrast = 0.9f, whiteClip = 1f - 0.2f * 0.7f)
        var prev = edge.apply(0f)
        for (i in 1..4000) {
            val y = edge.apply(i / 4000f)
            assertTrue("edge curve not monotone at ${i / 4000f}", y >= prev - 1e-7f)
            prev = y
        }
    }

    private fun labOfEncoded(rgb: FloatArray): OkLab = ColorMath.linearSrgbToOkLab(
        ColorMath.srgbToLinear(rgb[0]),
        ColorMath.srgbToLinear(rgb[1]),
        ColorMath.srgbToLinear(rgb[2]),
    )

    private fun deltaE(a: OkLab, b: OkLab): Float {
        val dl = a.l - b.l
        val da = a.a - b.a
        val db = a.b - b.b
        return sqrt(dl * dl + da * da + db * db)
    }
}
