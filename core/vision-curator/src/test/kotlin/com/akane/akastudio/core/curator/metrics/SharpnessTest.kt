package com.akane.akastudio.core.curator.metrics

import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.curator.api.SaliencyMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharpnessTest {
    private val w = 512
    private val h = 512

    private fun score(gray: FloatArray, weights: FloatArray? = null) =
        Sharpness.score(Sharpness.laplacianVariance(gray, w, h, weights))

    @Test
    fun checkerboard_isSharp_andBlurredIsNot() {
        val board = TestImages.checkerboard(w, h, cell = 64)
        val sharp = score(board)
        val blurred = score(TestImages.boxBlur(board, w, h, radius = 4))
        assertTrue("sharp=$sharp", sharp > 0.7f)
        assertTrue("blurred=$blurred", blurred < 0.25f)
    }

    @Test
    fun textLikeEdges_areSharp_andBlurredAreNot() {
        val text = TestImages.textLike(w, h)
        val sharp = score(text)
        val blurred = score(TestImages.boxBlur(text, w, h, radius = 4))
        assertTrue("sharp=$sharp", sharp > 0.7f)
        assertTrue("blurred=$blurred", blurred < 0.25f)
    }

    @Test
    fun subjectWeighting_favoursASharpCentreOverABlurredBackground() {
        val lowContrast = TestImages.checkerboard(w, h, cell = 32, low = 100f, high = 160f)
        val background = TestImages.boxBlur(lowContrast, w, h, radius = 4)
        val box = NormRect(1f / 3, 1f / 3, 2f / 3, 2f / 3)
        val inBox = Sharpness.subjectWeights(w, h, box)
        val mixed = FloatArray(w * h) { if (inBox[it] > 1f) lowContrast[it] else background[it] }
        val unweighted = score(mixed)
        val weighted = score(mixed, inBox)
        assertTrue("unweighted=$unweighted weighted=$weighted", weighted > unweighted)
    }

    @Test
    fun score_logMapEndpoints() {
        assertEquals(0f, Sharpness.score(0f), 0f)
        assertEquals(0f, Sharpness.score(20f), 1e-5f)
        assertEquals(1f, Sharpness.score(800f), 1e-5f)
        assertEquals(1f, Sharpness.score(1e6f), 0f)
        val mid = Sharpness.score(128.6f)
        assertEquals(0.5f, mid, 1e-3f)
    }

    @Test
    fun laplacianVariance_flatAndTinyPlanesAreZero() {
        assertEquals(0f, Sharpness.laplacianVariance(FloatArray(100) { 77f }, 10, 10), 0f)
        assertEquals(0f, Sharpness.laplacianVariance(FloatArray(4) { it * 50f }, 2, 2), 0f)
        assertEquals(0f, Sharpness.laplacianVariance(FloatArray(100) { it.toFloat() }, 10, 10, FloatArray(100)), 0f)
    }

    @Test
    fun faceSharpness_scoresOnlyTheBoxCrop() {
        val board = TestImages.checkerboard(w, h, cell = 16)
        val flatOutside = FloatArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (x in 128 until 256 && y in 128 until 256) board[i] else 128f
        }
        val sharpBox = Sharpness.faceSharpness(flatOutside, w, h, NormRect(0.25f, 0.25f, 0.5f, 0.5f))
        val flatBox = Sharpness.faceSharpness(flatOutside, w, h, NormRect(0.6f, 0.6f, 0.9f, 0.9f))
        assertEquals(1f, sharpBox, 1e-6f)
        assertEquals(0f, flatBox, 0f)
        assertEquals(0f, Sharpness.faceSharpness(flatOutside, w, h, NormRect(0.5f, 0.5f, 0.5f, 0.6f)), 0f)
    }

    @Test
    fun subjectBox_prefersFaceUnion_thenSalientCells() {
        val faces = listOf(
            TestImages.face(NormRect(0.1f, 0.2f, 0.3f, 0.4f)),
            TestImages.face(NormRect(0.5f, 0.1f, 0.7f, 0.3f)),
        )
        assertEquals(NormRect(0.1f, 0.1f, 0.7f, 0.4f), Sharpness.subjectBox(faces, null))

        val values = FloatArray(8 * 4)
        values[1 * 8 + 2] = 0.5f
        values[2 * 8 + 5] = 0.9f
        values[3 * 8 + 7] = 0.49f
        val map = SaliencyMap(8, 4, values)
        assertEquals(NormRect(2f / 8, 1f / 4, 6f / 8, 3f / 4), Sharpness.subjectBox(emptyList(), map))
        assertNull(Sharpness.subjectBox(emptyList(), SaliencyMap(2, 2, FloatArray(4) { 0.2f })))
        assertNull(Sharpness.subjectBox(emptyList(), null))
    }

    @Test
    fun subjectWeights_tripleInsideTheBoxOnly() {
        val weights = Sharpness.subjectWeights(10, 10, NormRect(0.2f, 0.3f, 0.5f, 0.6f))
        assertEquals(3f, weights[3 * 10 + 2], 0f)
        assertEquals(3f, weights[5 * 10 + 4], 0f)
        assertEquals(1f, weights[6 * 10 + 4], 0f)
        assertEquals(1f, weights[3 * 10 + 5], 0f)
        assertEquals(9, weights.count { it == 3f })
        assertTrue(Sharpness.subjectWeights(4, 4, null).all { it == 1f })
    }
}
