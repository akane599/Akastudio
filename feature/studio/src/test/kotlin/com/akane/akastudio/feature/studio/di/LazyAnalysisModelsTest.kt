package com.akane.akastudio.feature.studio.di

import com.akane.akastudio.core.color.api.ImagePixels
import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.color.api.SceneType
import com.akane.akastudio.core.curator.engine.AnalysisImage
import com.akane.akastudio.core.curator.engine.AnalysisModels
import com.akane.akastudio.core.curator.ml.DetectedFace
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LazyAnalysisModelsTest {

    private val image = AnalysisImage(bitmap = null, pixels = ImagePixels(1, 1, intArrayOf(0)))
    private val face = DetectedFace(NormRect(0f, 0f, 0.5f, 0.5f), 0f, 0f)

    private val real = object : AnalysisModels {
        override suspend fun faces(img: AnalysisImage) = listOf(face)
        override suspend fun embed(img: AnalysisImage) = floatArrayOf(1f, 2f)
        override suspend fun aesthetic(img: AnalysisImage) = 7.5f
        override suspend fun scene(img: AnalysisImage) = SceneType.BEACH_SEA
    }

    @Test
    fun modelsLoadOnceAtFirstUseNotAtConstruction() = runTest {
        var loads = 0
        val lazy = LazyAnalysisModels(StandardTestDispatcher(testScheduler)) {
            loads++
            real
        }
        assertEquals(0, loads)

        val calls = listOf(
            async { lazy.faces(image) },
            async { lazy.embed(image) },
            async { lazy.aesthetic(image) },
            async { lazy.scene(image) },
        ).awaitAll()

        assertEquals(1, loads)
        assertEquals(listOf(face), calls[0])
        assertArrayEquals(floatArrayOf(1f, 2f), calls[1] as FloatArray, 0f)
        assertEquals(7.5f, calls[2])
        assertEquals(SceneType.BEACH_SEA, calls[3])

        lazy.scene(image)
        assertEquals(1, loads)
    }
}
