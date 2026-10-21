package com.akane.akastudio.core.curator.engine

import android.graphics.Bitmap
import com.akane.akastudio.core.color.api.ImagePixels
import com.akane.akastudio.core.color.api.SceneType
import com.akane.akastudio.core.curator.ml.BitmapSupport
import com.akane.akastudio.core.curator.ml.CuratorModels
import com.akane.akastudio.core.curator.ml.DetectedFace
import com.akane.akastudio.core.curator.ml.FallbackEmbedder
import com.akane.akastudio.core.curator.ml.HeuristicSceneClassifier
import com.akane.akastudio.core.ingestion.api.AnalysisDecoder
import com.akane.akastudio.core.ingestion.model.PhotoCandidate

/** Bitmap -> ImagePixels bridge kept tiny so the engine is JVM-testable with fakes. */
fun interface PixelSource {
    /** Loads the upright analysis image of [candidate]. Any exception marks the candidate unreadable. */
    suspend fun load(candidate: PhotoCandidate): AnalysisImage
}

/**
 * One analysis image: [pixels] is the upright sRGB ARGB raster every pure metric reads; [bitmap] is the same image
 * for the on-device models, or null when there is none (JVM tests). The engine recycles [bitmap] once the analysis of
 * its candidate finishes.
 */
class AnalysisImage(val bitmap: Bitmap?, val pixels: ImagePixels)

/** The model-backed parts of a photo analysis, abstracted so the engine runs against fakes on the JVM. */
interface AnalysisModels {
    /** Faces in [img], upright normalized boxes; empty when none are found. */
    suspend fun faces(img: AnalysisImage): List<DetectedFace>

    /** Global embedding of [img]; an all-zero (or empty) vector means "unavailable" and never clusters by embedding. */
    suspend fun embed(img: AnalysisImage): FloatArray

    /** NIMA mean score in [1, 10], or null when the aesthetic model is unavailable for [img]. */
    suspend fun aesthetic(img: AnalysisImage): Float?

    /** Scene of [img]; the engine itself promotes GENERAL to PORTRAIT for a prominent face. */
    suspend fun scene(img: AnalysisImage): SceneType
}

/**
 * Adapts the on-device [CuratorModels] to [AnalysisModels].
 *
 * Without a bitmap: no faces, no aesthetic score, the [HeuristicSceneClassifier] scene and the [FallbackEmbedder]
 * descriptor (192 values; clustering scores vectors of different lengths, such as the 1024-d MediaPipe ones, as
 * cosine 0, so the two kinds never compare).
 *
 * Embeddings must share one feature space for cosine similarity to mean anything. When the MediaPipe embedder is
 * loaded, a vector it produced through its per-image fallback (detected by comparing against
 * `FallbackEmbedder.fitTo(FallbackEmbedder.embed(pixels), dimension)`) yields an all-zero vector: embedding
 * unavailable, so that photo clusters on its perceptual hashes alone.
 */
fun CuratorModels.asAnalysisModels(): AnalysisModels = CuratorAnalysisModels(this)

/** Decodes each candidate at most [maxEdgePx] on the long edge and reads it out as upright ARGB_8888 pixels. */
fun AnalysisDecoder.asPixelSource(maxEdgePx: Int = 512): PixelSource {
    require(maxEdgePx > 0) { "maxEdgePx must be positive: $maxEdgePx" }
    return PixelSource { candidate ->
        val bitmap = decode(candidate.source, maxEdgePx)
        val pixels = try {
            BitmapSupport.pixelsOf(bitmap)
        } catch (e: Exception) {
            bitmap.recycle()
            throw e
        }
        AnalysisImage(bitmap, pixels)
    }
}

internal class CuratorAnalysisModels(private val models: CuratorModels) : AnalysisModels {

    private val embedderDegraded: Boolean get() = CuratorModels.EMBEDDER in models.degraded

    override suspend fun faces(img: AnalysisImage): List<DetectedFace> {
        val bitmap = img.bitmap ?: return emptyList()
        return models.faces.detect(bitmap)
    }

    override suspend fun embed(img: AnalysisImage): FloatArray {
        val bitmap = img.bitmap ?: return FallbackEmbedder.embed(img.pixels)
        val vector = models.embedder.embed(bitmap)
        return if (embedderDegraded) vector else withoutPerImageFallback(vector, img.pixels)
    }

    override suspend fun aesthetic(img: AnalysisImage): Float? {
        val bitmap = img.bitmap ?: return null
        return models.aesthetics.score(bitmap)
    }

    override suspend fun scene(img: AnalysisImage): SceneType {
        val bitmap = img.bitmap ?: return HeuristicSceneClassifier.classify(img.pixels)
        return models.scenes.classify(bitmap, img.pixels)
    }

    internal companion object {
        /**
         * [vector], or an all-zero vector of its length when it is exactly the [FallbackEmbedder] descriptor of
         * [pixels] fitted to that length, which is what the MediaPipe embedder returns when inference fails for
         * one image.
         */
        fun withoutPerImageFallback(vector: FloatArray, pixels: ImagePixels): FloatArray {
            if (vector.isEmpty()) return vector
            val perImageFallback = FallbackEmbedder.fitTo(FallbackEmbedder.embed(pixels), vector.size)
            return if (vector.contentEquals(perImageFallback)) FloatArray(vector.size) else vector
        }
    }
}
