package com.akane.akastudio.core.color.math

import com.akane.akastudio.core.color.api.OkLab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class ColorMathTest {

    private fun assertLab(expected: OkLab, actual: OkLab, tolerance: Float) {
        assertEquals("L", expected.l, actual.l, tolerance)
        assertEquals("a", expected.a, actual.a, tolerance)
        assertEquals("b", expected.b, actual.b, tolerance)
    }

    @Test
    fun srgbLinear_roundTripsOverUnitRange() {
        for (i in 0..1000) {
            val x = i / 1000f
            assertEquals("decode(encode($x))", x, ColorMath.srgbToLinear(ColorMath.linearToSrgb(x)), 1e-5f)
            assertEquals("encode(decode($x))", x, ColorMath.linearToSrgb(ColorMath.srgbToLinear(x)), 1e-5f)
        }
    }

    @Test
    fun srgbLinear_negativeInputsAreSignPreserving() {
        for (x in floatArrayOf(0.001f, 0.02f, 0.04045f, 0.2f, 0.5f, 1f, 1.5f)) {
            assertEquals(-ColorMath.srgbToLinear(x), ColorMath.srgbToLinear(-x), 0f)
            assertEquals(-ColorMath.linearToSrgb(x), ColorMath.linearToSrgb(-x), 0f)
            assertTrue(ColorMath.srgbToLinear(-x) < 0f)
            assertTrue(ColorMath.linearToSrgb(-x) < 0f)
        }
    }

    @Test
    fun whiteMapsToUnitLightness() {
        assertLab(OkLab(1f, 0f, 0f), ColorMath.linearSrgbToOkLab(1f, 1f, 1f), 1e-4f)
    }

    @Test
    fun linearRed_matchesOttossonReference() {
        assertLab(OkLab(0.62796f, 0.22486f, 0.12585f), ColorMath.linearSrgbToOkLab(1f, 0f, 0f), 1e-4f)
    }

    @Test
    fun linearBlue_matchesOttossonReference() {
        assertLab(OkLab(0.45201f, -0.03246f, -0.31153f), ColorMath.linearSrgbToOkLab(0f, 0f, 1f), 1e-4f)
    }

    @Test
    fun argb_roundTripsWithinOneCodeValue() {
        val random = Random(20261021)
        repeat(1000) {
            val rgb = random.nextInt(0x1000000)
            val argb = (0xFF shl 24) or rgb
            val back = ColorMath.okLabToArgb(ColorMath.argbToOkLab(argb))
            assertEquals("alpha", 0xFF, (back ushr 24) and 0xFF)
            for (shift in intArrayOf(16, 8, 0)) {
                val expected = (argb ushr shift) and 0xFF
                val actual = (back ushr shift) and 0xFF
                assertTrue("channel@$shift of ${Integer.toHexString(argb)} -> ${Integer.toHexString(back)}", abs(expected - actual) <= 1)
            }
        }
    }

    @Test
    fun okLchHueOfPureRed() {
        val lch = ColorMath.okLabToOkLch(ColorMath.argbToOkLab(0xFFFF0000.toInt()))
        assertEquals(29.23f, lch[2], 0.1f)
    }

    @Test
    fun okLchToOkLab_invertsOkLabToOkLch() {
        val labs = listOf(
            OkLab(0.62796f, 0.22486f, 0.12585f),
            OkLab(0.45201f, -0.03246f, -0.31153f),
            OkLab(0.5f, -0.1f, 0.05f),
            OkLab(0.8f, 0.02f, -0.07f),
            OkLab(0.3f, -0.05f, -0.0001f),
        )
        for (lab in labs) {
            val lch = ColorMath.okLabToOkLch(lab)
            assertTrue("hue in [0,360): ${lch[2]}", lch[2] >= 0f && lch[2] < 360f)
            assertLab(lab, ColorMath.okLchToOkLab(lch[0], lch[1], lch[2]), 1e-5f)
        }
    }
}
