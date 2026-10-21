package com.akane.akastudio.core.curator.engine

import android.graphics.Bitmap
import com.akane.akastudio.core.color.api.ImagePixels
import com.akane.akastudio.core.color.api.OkLab
import com.akane.akastudio.core.color.api.PaletteSwatch
import com.akane.akastudio.core.color.api.SceneType
import com.akane.akastudio.core.curator.api.CurationOption
import com.akane.akastudio.core.curator.api.StoryMode
import com.akane.akastudio.core.curator.api.SynergyBreakdown
import com.akane.akastudio.core.curator.ml.AestheticScorer
import com.akane.akastudio.core.curator.ml.CuratorModels
import com.akane.akastudio.core.curator.ml.DetectedFace
import com.akane.akastudio.core.curator.ml.Embedder
import com.akane.akastudio.core.curator.ml.FaceDetector
import com.akane.akastudio.core.curator.ml.FallbackEmbedder
import com.akane.akastudio.core.curator.ml.HeuristicSceneClassifier
import com.akane.akastudio.core.curator.ml.SceneClassifier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineMathTest {

    private val grey = listOf(PaletteSwatch(OkLab(0.5f, 0f, 0f), 1f))

    @Test
    fun aesthetic_withNimaBlendsNormalizedMeanAndComposition() {
        assertEquals(0.7f * 0.5f + 0.3f * 0.4f, PhotoAnalyzer.aestheticScore(5f, 0.4f, grey), 1e-6f)
        assertEquals(0.3f * 0.4f, PhotoAnalyzer.aestheticScore(2f, 0.4f, grey), 1e-6f)
        assertEquals(0.7f + 0.3f * 0.4f, PhotoAnalyzer.aestheticScore(9.5f, 0.4f, grey), 1e-6f)
    }

    @Test
    fun aesthetic_withoutNimaBlendsCompositionAndPaletteColorfulness() {
        val vivid = listOf(PaletteSwatch(OkLab(0.6f, 0.06f, 0f), 0.5f), PaletteSwatch(OkLab(0.6f, 0f, 0.24f), 0.5f))
        // Weighted mean chroma (0.06 + 0.24) / 2 = 0.15 saturates at 0.12.
        assertEquals(1f, PhotoAnalyzer.colorfulness(vivid), 1e-6f)
        val muted = listOf(PaletteSwatch(OkLab(0.6f, 0.03f, 0f), 0.75f), PaletteSwatch(OkLab(0.6f, 0f, 0f), 0.25f))
        assertEquals(0.0225f / 0.12f, PhotoAnalyzer.colorfulness(muted), 1e-5f)
        assertEquals(0.6f * 0.5f + 0.4f * (0.0225f / 0.12f), PhotoAnalyzer.aestheticScore(null, 0.5f, muted), 1e-5f)
        assertEquals(0.6f * 0.5f, PhotoAnalyzer.aestheticScore(null, 0.5f, grey), 1e-6f)
    }

    @Test
    fun quality_weighsTechnicalAndAesthetic() {
        assertEquals(0.45f * 0.8f + 0.55f * 0.6f, PhotoAnalyzer.quality(0.8f, 0.6f), 1e-6f)
    }

    @Test
    fun dominantScene_prefersMostFrequentThenAnchor() {
        val beach = analysis("a", SceneType.BEACH_SEA)
        val forest = analysis("b", SceneType.NATURE_FOREST)
        val forest2 = analysis("c", SceneType.NATURE_FOREST)
        assertEquals(SceneType.NATURE_FOREST, CurationResults.dominantScene(listOf(beach, forest, forest2)))
        assertEquals(SceneType.BEACH_SEA, CurationResults.dominantScene(listOf(beach, forest)))
        assertEquals(SceneType.NATURE_FOREST, CurationResults.dominantScene(listOf(forest, beach)))
    }

    @Test
    fun harmonyPercent_usesPsiForCollageAndScoreForHero() {
        val hero = CurationOption(StoryMode.HERO, listOf("a"), score = 0.876f, synergy = null)
        assertEquals(88, CurationResults.harmonyPercent(hero))
        val collage = CurationOption(StoryMode.COLLAGE_2, listOf("a", "b"), 0.7f, SynergyBreakdown(1f, 1f, 1f, 0.734f))
        assertEquals(73, CurationResults.harmonyPercent(collage))
    }

    @Test
    fun perImageFallbackEmbeddingBecomesUnavailable() {
        val pixels = EngineImages.noise(81)
        val fallback = FallbackEmbedder.fitTo(FallbackEmbedder.embed(pixels), 1024)
        assertArrayEquals(FloatArray(1024), CuratorAnalysisModels.withoutPerImageFallback(fallback, pixels), 0f)

        val genuine = FloatArray(1024) { if (it == 3) 1f else 0f }
        assertSame(genuine, CuratorAnalysisModels.withoutPerImageFallback(genuine, pixels))
        val otherImage = FallbackEmbedder.fitTo(FallbackEmbedder.embed(EngineImages.noise(82)), 1024)
        assertSame(otherImage, CuratorAnalysisModels.withoutPerImageFallback(otherImage, pixels))
    }

    @Test
    fun curatorModelsAdapter_withoutBitmapUsesPureFallbacks() = runBlocking {
        val models = CuratorModels(
            faces = object : FaceDetector {
                override suspend fun detect(bitmap: Bitmap): List<DetectedFace> = error("no bitmap expected")
            },
            embedder = object : Embedder {
                override val dimension = 1024
                override suspend fun embed(bitmap: Bitmap): FloatArray = error("no bitmap expected")
            },
            aesthetics = object : AestheticScorer {
                override suspend fun score(bitmap: Bitmap): Float = error("no bitmap expected")
            },
            scenes = object : SceneClassifier {
                override suspend fun classify(bitmap: Bitmap, pixels: ImagePixels): SceneType = error("no bitmap expected")
            },
            degraded = emptySet(),
        ).asAnalysisModels()
        val image = AnalysisImage(bitmap = null, pixels = EngineImages.noise(83))

        assertTrue(models.faces(image).isEmpty())
        assertNull(models.aesthetic(image))
        assertEquals(HeuristicSceneClassifier.classify(image.pixels), models.scene(image))
        assertArrayEquals(FallbackEmbedder.embed(image.pixels), models.embed(image), 0f)
    }

    private fun analysis(id: String, scene: SceneType) = PhotoAnalyzer.unreadable(id).copy(scene = scene, rejectReason = null)
}
