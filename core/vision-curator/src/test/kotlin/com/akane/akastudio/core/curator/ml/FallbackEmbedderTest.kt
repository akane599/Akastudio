package com.akane.akastudio.core.curator.ml

import com.akane.akastudio.core.curator.ml.SyntheticImages.beach
import com.akane.akastudio.core.curator.ml.SyntheticImages.brighten
import com.akane.akastudio.core.curator.ml.SyntheticImages.forest
import com.akane.akastudio.core.curator.ml.SyntheticImages.paint
import com.akane.akastudio.core.curator.ml.SyntheticImages.rgb
import com.akane.akastudio.core.curator.ml.SyntheticImages.solid
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackEmbedderTest {

    @Test
    fun dimensionIs192() {
        assertEquals(192, FallbackEmbedder.DIMENSION)
        assertEquals(192, FallbackEmbedder.embed(beach()).size)
    }

    @Test
    fun outputHasUnitNorm() {
        val images = listOf(
            beach(),
            forest(),
            solid(10, 10, rgb(128, 128, 128)),
            solid(64, 64, rgb(255, 255, 255)),
            solid(3, 2, rgb(0, 0, 0)),
            paint(500, 37) { x, y -> rgb(x % 256, y * 6, (x + y) % 256) },
        )
        for (image in images) assertEquals(1.0, norm(FallbackEmbedder.embed(image)), 1e-5)
    }

    @Test
    fun twoPercentBrightnessShiftIsANearDuplicate() {
        for (image in listOf(beach(), forest(), beach(seed = 3))) {
            val cos = cosine(FallbackEmbedder.embed(image), FallbackEmbedder.embed(brighten(image, 1.02f)))
            assertTrue("near-duplicate cosine $cos", cos > 0.95)
        }
    }

    @Test
    fun differentScenesAreDissimilar() {
        val cos = cosine(FallbackEmbedder.embed(beach()), FallbackEmbedder.embed(forest()))
        assertTrue("different-scene cosine $cos", cos < 0.8)
    }

    @Test
    fun sameSceneAtDifferentResolutionStaysClose() {
        val cos = cosine(FallbackEmbedder.embed(beach(192, 144)), FallbackEmbedder.embed(beach(384, 288)))
        assertTrue("resolution cosine $cos", cos > 0.95)
    }

    @Test
    fun embeddingIsDeterministic() {
        assertArrayEquals(FallbackEmbedder.embed(beach()), FallbackEmbedder.embed(beach()), 0f)
    }

    @Test
    fun fitToLongerDimensionZeroPadsAndKeepsUnitNorm() {
        val v = FallbackEmbedder.embed(beach())
        val fitted = FallbackEmbedder.fitTo(v, 1024)
        assertEquals(1024, fitted.size)
        assertEquals(1.0, norm(fitted), 1e-5)
        assertArrayEquals(v, fitted.copyOf(FallbackEmbedder.DIMENSION), 1e-6f)
        assertTrue(fitted.drop(FallbackEmbedder.DIMENSION).all { it == 0f })
    }

    @Test
    fun fitToShorterDimensionFoldsAndRenormalizes() {
        val v = FallbackEmbedder.embed(forest())
        val fitted = FallbackEmbedder.fitTo(v, 100)
        assertEquals(100, fitted.size)
        assertEquals(1.0, norm(fitted), 1e-5)
        val folded = FloatArray(100) { i -> v[i] + (if (i + 100 < v.size) v[i + 100] else 0f) }
        val expectedNorm = norm(folded)
        for (i in 0 until 100) assertEquals((folded[i] / expectedNorm).toFloat(), fitted[i], 1e-5f)
    }

    @Test
    fun fitToSameDimensionReturnsAnEqualCopy() {
        val v = FallbackEmbedder.embed(beach())
        val fitted = FallbackEmbedder.fitTo(v, FallbackEmbedder.DIMENSION)
        assertArrayEquals(v, fitted, 0f)
        assertTrue(fitted !== v)
    }

    private fun norm(v: FloatArray): Double = sqrt(v.sumOf { it.toDouble() * it })

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        for (i in a.indices) dot += a[i].toDouble() * b[i]
        return dot / (norm(a) * norm(b))
    }
}
