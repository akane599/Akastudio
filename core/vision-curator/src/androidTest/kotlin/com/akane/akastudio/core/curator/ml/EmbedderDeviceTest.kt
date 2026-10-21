package com.akane.akastudio.core.curator.ml

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.akane.akastudio.core.curator.DeviceScenes
import com.akane.akastudio.core.curator.DeviceScenes.Kind
import com.akane.akastudio.core.curator.decision.Clustering
import com.akane.akastudio.core.curator.decision.DecisionConfig
import com.akane.akastudio.core.curator.engine.CuratorAnalysisModels
import com.akane.akastudio.core.curator.logTiming
import com.akane.akastudio.core.curator.logValue
import com.akane.akastudio.core.curator.timed
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real MobileNetV3-small embeddings: 1024-d, L2-normalized, and separating bursts from unrelated scenes at the burst cosine. */
@RunWith(AndroidJUnit4::class)
class EmbedderDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val burstCosine = DecisionConfig().clusterCosine
    private lateinit var embedder: MediaPipeEmbedder

    @Before
    fun setUp() {
        ColdLoad.ensureMeasured(context)
        embedder = MediaPipeEmbedder.create(context, Dispatchers.Default)
    }

    @After
    fun tearDown() {
        embedder.close()
    }

    private fun embed(bitmap: Bitmap): FloatArray = runBlocking { embedder.embed(bitmap) }.also { vector ->
        assertEquals(1024, vector.size)
        val norm = sqrt(vector.sumOf { it.toDouble() * it })
        assertEquals("embedding must be L2-normalized", 1.0, norm, 1e-3)
        // A per-image MediaPipe failure returns the fitted fallback descriptor; the real model must answer instead.
        val pixels = BitmapSupport.pixelsOf(bitmap)
        assertFalse(
            "embedder fell back to the FallbackEmbedder descriptor",
            CuratorAnalysisModels.withoutPerImageFallback(vector, pixels).all { it == 0f },
        )
    }

    @Test
    fun dimensionIs1024AndRepeatEmbeddingIsStable() {
        assertEquals(1024, embedder.dimension)
        val bitmap = DeviceScenes.scene(Kind.CITY, seed = 1)
        val first = embed(bitmap)
        val second = embed(bitmap)
        assertArrayEquals(first, second, 1e-5f)

        val runs = 10
        val (_, ms) = timed { repeat(runs) { runBlocking { embedder.embed(bitmap) } } }
        logTiming("infer.mobilenet_v3_small.per_image", ms / runs)
    }

    @Test
    fun nearIdenticalFramesClearTheBurstCosine() {
        for (kind in listOf(Kind.CITY, Kind.FOREST, Kind.SUNSET)) {
            val base = DeviceScenes.scene(kind, seed = 1)
            val baseVector = embed(base)
            val shifted = embed(DeviceScenes.scene(kind, seed = 2, dx = 6f, dy = -3f))
            val recompressed = embed(DeviceScenes.recompressed(base, quality = 55))
            val shiftedCosine = Clustering.cosine(baseVector, shifted)
            val recompressedCosine = Clustering.cosine(baseVector, recompressed)
            logValue("embed.cosine.$kind.shifted", shiftedCosine)
            logValue("embed.cosine.$kind.recompressed", recompressedCosine)
            assertTrue("$kind shifted cosine $shiftedCosine < $burstCosine", shiftedCosine >= burstCosine)
            assertTrue("$kind recompressed cosine $recompressedCosine < $burstCosine", recompressedCosine >= burstCosine)
        }
    }

    @Test
    fun unrelatedScenesStayWellBelowTheBurstCosine() {
        // Negative control for the burst assertion above: the same model and threshold must not join unrelated scenes.
        val kinds = Kind.entries
        val vectors = kinds.associateWith { embed(DeviceScenes.scene(it, seed = 1)) }
        var maxCosine = -1f
        var maxPair = ""
        for (i in kinds.indices) {
            for (j in i + 1 until kinds.size) {
                val cosine = Clustering.cosine(vectors.getValue(kinds[i]), vectors.getValue(kinds[j]))
                logValue("embed.cosine.${kinds[i]}~${kinds[j]}", cosine)
                if (cosine > maxCosine) {
                    maxCosine = cosine
                    maxPair = "${kinds[i]}~${kinds[j]}"
                }
            }
        }
        logValue("embed.cosine.unrelated.max", "$maxCosine ($maxPair)")
        assertTrue("unrelated scenes $maxPair reached cosine $maxCosine", maxCosine <= burstCosine - UNRELATED_MARGIN)
    }

    private companion object {
        /** "Well below": unrelated scenes must stay this far under the burst threshold. */
        const val UNRELATED_MARGIN = 0.15f
    }
}
