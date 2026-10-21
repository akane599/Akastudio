package com.akane.akastudio.core.curator.hash

import com.akane.akastudio.core.curator.metrics.TestImages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerceptualHashTest {
    private val w = 512
    private val h = 384
    private val scene = TestImages.scene(w, h)

    @Test
    fun identicalImages_haveZeroDistance() {
        val a = TestImages.toPixels(scene, w, h)
        val b = TestImages.toPixels(scene.copyOf(), w, h)
        assertEquals(0, PerceptualHash.hamming(PerceptualHash.pHash(a), PerceptualHash.pHash(b)))
        assertEquals(0, PerceptualHash.hamming(PerceptualHash.dHash(a), PerceptualHash.dHash(b)))
    }

    @Test
    fun fivePercentBrighter_keepsPHashWithinFourBits() {
        val base = PerceptualHash.pHash(TestImages.toPixels(scene, w, h))
        val brighter = PerceptualHash.pHash(TestImages.toPixels(FloatArray(scene.size) { scene[it] * 1.05f }, w, h))
        val distance = PerceptualHash.hamming(base, brighter)
        assertTrue("distance=$distance", distance <= 4)
    }

    @Test
    fun differentScenes_areFarApart() {
        val gradient = TestImages.toPixels(TestImages.plane(w, h) { x, y -> (x + y) * 255f / (w + h - 2) }, w, h)
        val board = TestImages.toPixels(TestImages.checkerboard(w, h, cell = 64), w, h)
        val distance = PerceptualHash.hamming(PerceptualHash.pHash(gradient), PerceptualHash.pHash(board))
        assertTrue("distance=$distance", distance >= 20)
    }

    @Test
    fun dHash_isStableUnderTwoPixelBlur() {
        val base = PerceptualHash.dHash(TestImages.toPixels(scene, w, h))
        val blurred = PerceptualHash.dHash(TestImages.toPixels(TestImages.boxBlur(scene, w, h, radius = 2), w, h))
        val distance = PerceptualHash.hamming(base, blurred)
        assertTrue("distance=$distance", distance <= 6)
    }

    @Test
    fun pHash_dcBitIsAlwaysClear_andHalfTheAcBitsAreSet() {
        val hash = PerceptualHash.pHash(TestImages.toPixels(scene, w, h))
        assertEquals(0L, hash and 1L)
        // Strictly-greater-than-median over 63 distinct coefficients sets exactly 31 bits.
        assertEquals(31, hash.countOneBits())
    }

    @Test
    fun dHash_encodesLeftDarkerThanRightRowMajor() {
        val ramp = TestImages.toPixels(TestImages.plane(w, h) { x, _ -> x * 255f / (w - 1) }, w, h)
        assertEquals(-1L, PerceptualHash.dHash(ramp))
        val reverse = TestImages.toPixels(TestImages.plane(w, h) { x, _ -> 255f - x * 255f / (w - 1) }, w, h)
        assertEquals(0L, PerceptualHash.dHash(reverse))
        assertEquals(64, PerceptualHash.hamming(0L, -1L))
    }
}
