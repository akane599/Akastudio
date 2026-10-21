package com.akane.akastudio.core.curator.ml

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class NimaMathTest {

    @Test
    fun uniformDistributionScoresFivePointFive() {
        assertEquals(5.5f, NimaMath.meanScore(FloatArray(10) { 0.1f }), 1e-5f)
    }

    @Test
    fun oneHotAtLastBinScoresTen() {
        val p = FloatArray(10).also { it[9] = 1f }
        assertEquals(10f, NimaMath.meanScore(p), 1e-6f)
    }

    @Test
    fun oneHotAtFirstBinScoresOne() {
        val p = FloatArray(10).also { it[0] = 1f }
        assertEquals(1f, NimaMath.meanScore(p), 1e-6f)
    }

    @Test
    fun meanIsWeightedSumOfBinScores() {
        val p = FloatArray(10).also { it[2] = 0.5f; it[6] = 0.5f } // scores 3 and 7
        assertEquals(5f, NimaMath.meanScore(p), 1e-6f)
    }

    @Test
    fun slightlyUnnormalizedSoftmaxIsRenormalized() {
        val p = FloatArray(10).also { it[9] = 0.999f } // fp16 rounding short of 1
        assertEquals(10f, NimaMath.meanScore(p), 1e-5f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun wrongBinCountIsRejected() {
        NimaMath.meanScore(FloatArray(9) { 1f / 9 })
    }

    @Test(expected = IllegalArgumentException::class)
    fun nanBinIsRejected() {
        NimaMath.meanScore(FloatArray(10) { if (it == 3) Float.NaN else 0.1f })
    }

    @Test
    fun preprocessMapsZeroToMinusOneAnd255ToOne() {
        val out = FloatArray(6)
        NimaMath.preprocess(intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), out)
        assertArrayEquals(floatArrayOf(-1f, -1f, -1f, 1f, 1f, 1f), out, 1e-6f)
    }

    @Test
    fun preprocessWritesRgbOrderAndIgnoresAlpha() {
        val out = FloatArray(3)
        NimaMath.preprocess(intArrayOf(0x00FF0000), out) // transparent pure red
        assertArrayEquals(floatArrayOf(1f, -1f, -1f), out, 1e-6f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun preprocessRejectsMismatchedOutput() {
        NimaMath.preprocess(IntArray(4), FloatArray(11))
    }
}
