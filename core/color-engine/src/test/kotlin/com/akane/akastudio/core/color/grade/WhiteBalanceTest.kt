package com.akane.akastudio.core.color.grade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WhiteBalanceTest {

    private fun apply(m: FloatArray, r: Float, g: Float, b: Float) = floatArrayOf(
        m[0] * r + m[1] * g + m[2] * b,
        m[3] * r + m[4] * g + m[5] * b,
        m[6] * r + m[7] * g + m[8] * b,
    )

    @Test
    fun neutral_isIdentityAndKeepsWhite() {
        val m = WhiteBalance.matrix(0f, 0f)
        for (i in 0 until 9) assertEquals("m[$i]", if (i % 4 == 0) 1f else 0f, m[i], 1e-4f)
        val white = apply(m, 1f, 1f, 1f)
        for (c in 0 until 3) assertEquals(1f, white[c], 1e-5f)
    }

    @Test
    fun temperature_warmsAndCoolsGrey() {
        val warm = apply(WhiteBalance.matrix(1f, 0f), 0.18f, 0.18f, 0.18f)
        assertTrue("warm R/B ${warm[0] / warm[2]}", warm[0] / warm[2] > 1.1f)
        val cool = apply(WhiteBalance.matrix(-1f, 0f), 0.18f, 0.18f, 0.18f)
        assertTrue("cool R/B ${cool[0] / cool[2]}", cool[0] / cool[2] < 1f / 1.1f)
        val slight = apply(WhiteBalance.matrix(0.2f, 0f), 0.18f, 0.18f, 0.18f)
        assertTrue(slight[0] / slight[2] > 1f && slight[0] / slight[2] < warm[0] / warm[2])
    }

    @Test
    fun tint_shiftsGreyTowardMagentaOrGreen() {
        val magenta = apply(WhiteBalance.matrix(0f, 1f), 0.18f, 0.18f, 0.18f)
        assertTrue("magenta ratio", (magenta[0] + magenta[2]) / 2f / magenta[1] > 1.02f)
        val green = apply(WhiteBalance.matrix(0f, -1f), 0.18f, 0.18f, 0.18f)
        assertTrue("green ratio", (green[0] + green[2]) / 2f / green[1] < 1f / 1.02f)
    }

    @Test
    fun matrices_areFiniteAcrossTheSliderRange() {
        for (t in -10..10) for (u in -10..10) {
            val m = WhiteBalance.matrix(t / 10f, u / 10f)
            assertEquals(9, m.size)
            assertTrue(m.all { it.isFinite() })
        }
        // Out-of-range sliders clamp rather than extrapolate.
        val clamped = WhiteBalance.matrix(5f, -5f)
        val edge = WhiteBalance.matrix(1f, -1f)
        for (i in 0 until 9) assertEquals(edge[i], clamped[i], 0f)
        assertThrows(IllegalArgumentException::class.java) { WhiteBalance.matrix(Float.NaN, 0f) }
    }

    @Test
    fun planckianLocus_matchesKnownChromaticities() {
        val a = PlanckianLocus.xy(2856.0) // CIE illuminant A lies on the locus
        assertEquals(0.4476, a[0], 2e-3)
        assertEquals(0.4074, a[1], 2e-3)
        val d = PlanckianLocus.xy(6504.0)
        assertEquals(0.3135, d[0], 2e-3)
        assertEquals(0.3237, d[1], 2e-3)
    }
}
