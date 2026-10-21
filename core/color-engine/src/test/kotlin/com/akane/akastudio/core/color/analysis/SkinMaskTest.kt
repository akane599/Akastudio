package com.akane.akastudio.core.color.analysis

import com.akane.akastudio.core.color.math.ColorMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkinMaskTest {

    private fun lch(l: Float, c: Float, h: Float): Float = SkinMask.weight(ColorMath.okLchToOkLab(l, c, h))

    @Test
    fun centreOfSkinRegion_isNearOne() {
        val w = lch(0.62f, 0.08f, 50f)
        assertTrue("centre weight $w", w >= 0.99f)
    }

    @Test
    fun pureBlue_isZero() {
        assertEquals(0f, SkinMask.weight(ColorMath.argbToOkLab(0xFF0000FF.toInt())), 0f)
    }

    @Test
    fun grey_isZero() {
        assertEquals(0f, lch(0.62f, 0f, 50f), 0f)
        assertEquals(0f, SkinMask.weight(0.62f, 0f, 0f), 0f)
    }

    @Test
    fun darkSkinHue_atLightness02_isZero() {
        assertEquals(0f, lch(0.2f, 0.08f, 50f), 0f)
    }

    @Test
    fun overloads_agree() {
        val lab = ColorMath.okLchToOkLab(0.7f, 0.06f, 41f)
        assertEquals(SkinMask.weight(lab), SkinMask.weight(lab.l, lab.a, lab.b), 0f)
    }

    @Test
    fun upperHueEdge_fallsOffMonotonically() {
        var previous = lch(0.62f, 0.08f, 68f)
        assertTrue("inside edge $previous", previous >= 0.99f)
        var h = 68.25f
        while (h <= 74f) {
            val w = lch(0.62f, 0.08f, h)
            assertTrue("hue $h: $w must be < $previous", w < previous)
            previous = w
            h += 0.25f
        }
        assertEquals(0f, lch(0.62f, 0.08f, 74.5f), 0f)
    }

    @Test
    fun lowerHueEdge_risesMonotonically() {
        var previous = lch(0.62f, 0.08f, 26f)
        assertEquals(0f, previous, 1e-6f)
        var h = 26.25f
        while (h <= 32f) {
            val w = lch(0.62f, 0.08f, h)
            assertTrue("hue $h: $w must be > $previous", w > previous)
            previous = w
            h += 0.25f
        }
    }

    @Test
    fun hueDistance_isMeasuredOnTheCircle() {
        assertEquals(0f, lch(0.62f, 0.08f, 230f), 0f)
        assertEquals(0f, lch(0.62f, 0.08f, 355f), 0f)
        assertEquals(lch(0.62f, 0.08f, 71f), lch(0.62f, 0.08f, 29f), 1e-4f)
    }

    @Test
    fun weight_staysInUnitInterval() {
        var l = 0f
        while (l <= 1f) {
            var h = 0f
            while (h < 360f) {
                val w = lch(l, 0.09f, h)
                assertTrue("L $l h $h -> $w", w in 0f..1f)
                h += 7f
            }
            l += 0.05f
        }
    }
}
