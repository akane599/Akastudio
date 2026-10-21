package com.akane.akastudio.core.curator.metrics

import com.akane.akastudio.core.color.api.ImagePixels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposureTest {
    private val w = 512
    private val h = 256

    private fun gradient(): FloatArray = TestImages.plane(w, h) { x, _ -> x * 255f / (w - 1) }

    @Test
    fun smoothFullRangeGradient_scoresHigh() {
        val result = Exposure.evaluate(gradient())
        assertTrue("score=${result.score}", result.score > 0.9f)
        assertEquals(8f, result.entropyBits, 0.05f)
    }

    @Test
    fun allWhiteAndAllBlack_scoreNearZero() {
        val white = Exposure.evaluate(FloatArray(w * h) { 255f })
        val black = Exposure.evaluate(FloatArray(w * h) { 0f })
        assertTrue("white=${white.score}", white.score < 0.05f)
        assertTrue("black=${black.score}", black.score < 0.05f)
        assertEquals(1f, white.clipHigh, 0f)
        assertEquals(1f, black.clipLow, 0f)
        assertEquals(0f, white.entropyBits, 0f)
    }

    @Test
    fun thirtyPercentClippedHighlights_arePenalised() {
        val base = gradient()
        val clipped = TestImages.plane(w, h) { x, y -> if (x >= w * 7 / 10) 255f else base[y * w + x] / 0.7f }
        val clean = Exposure.evaluate(base)
        val blown = Exposure.evaluate(clipped)
        assertEquals(0.3f, blown.clipHigh, 0.01f)
        assertTrue("clean=${clean.score} blown=${blown.score}", blown.score < clean.score - 0.5f)
        assertTrue("blown=${blown.score}", blown.score < 0.1f)
    }

    @Test
    fun clipBoundaries_followTheRoundedHistogram() {
        val values = floatArrayOf(252.4f, 252.6f, 2.4f, 2.6f)
        val result = Exposure.evaluate(values)
        assertEquals(0.25f, result.clipHigh, 0f)
        assertEquals(0.25f, result.clipLow, 0f)
    }

    @Test
    fun luma_usesRec709WeightsOnEncodedValues() {
        val pixels = ImagePixels(3, 1, intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt()))
        val y = Luma.of(pixels)
        assertEquals(0.2126f * 255f, y[0], 1e-3f)
        assertEquals(0.7152f * 255f, y[1], 1e-3f)
        assertEquals(0.0722f * 255f, y[2], 1e-3f)
        assertEquals(255f, Luma.of(ImagePixels(1, 1, intArrayOf(-1)))[0], 1e-3f)
    }
}
