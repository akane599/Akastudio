package com.akane.akastudio.core.curator.ml

import android.graphics.Bitmap
import com.akane.akastudio.core.color.api.ImagePixels
import com.akane.akastudio.core.color.api.SceneType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Degraded face detection: reports no faces, so every photo is treated as face-free. */
internal object NoFaceDetector : FaceDetector {
    override suspend fun detect(bitmap: Bitmap): List<DetectedFace> = emptyList()
}

/** Degraded embedder backed by the pure [FallbackEmbedder]. */
internal class FallbackEmbedderAdapter(private val dispatcher: CoroutineDispatcher) : Embedder {
    override val dimension: Int = FallbackEmbedder.DIMENSION

    override suspend fun embed(bitmap: Bitmap): FloatArray = withContext(dispatcher) {
        FallbackEmbedder.embed(BitmapSupport.pixelsOf(bitmap))
    }
}

/** Degraded aesthetics: the neural score is always unavailable. */
internal object UnavailableAestheticScorer : AestheticScorer {
    override suspend fun score(bitmap: Bitmap): Float? = null
}

/** Degraded scene classification backed by the pure [HeuristicSceneClassifier]. */
internal class HeuristicSceneClassifierAdapter(private val dispatcher: CoroutineDispatcher) : SceneClassifier {
    override suspend fun classify(bitmap: Bitmap, pixels: ImagePixels): SceneType = withContext(dispatcher) {
        HeuristicSceneClassifier.classify(pixels)
    }
}
