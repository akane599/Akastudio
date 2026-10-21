package com.akane.akastudio.core.curator.ml

import android.graphics.Bitmap
import com.akane.akastudio.core.color.api.ImagePixels
import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.color.api.SceneType

/**
 * One detected face in an upright image.
 *
 * [box] is the min/max of the face mesh landmarks, clamped to the image. [leftBlink] and [rightBlink]
 * are the `eyeBlinkLeft` / `eyeBlinkRight` blendshape scores in [0, 1] (1 = fully closed); they are 0
 * when the model reported no blendshapes for the face.
 */
data class DetectedFace(val box: NormRect, val leftBlink: Float, val rightBlink: Float)

/** Finds faces and eye state. Implementations serialize calls and never recycle the caller's bitmap. */
interface FaceDetector {
    /** Faces in [bitmap], at most 6. An empty list means no face was found (or detection is degraded). */
    suspend fun detect(bitmap: Bitmap): List<DetectedFace>
}

/** Global image descriptor for near-duplicate and scene clustering. */
interface Embedder {
    /** Length of every vector [embed] returns. Constant for the lifetime of the instance. */
    val dimension: Int

    /** L2-normalized feature vector of [bitmap], of length [dimension]. */
    suspend fun embed(bitmap: Bitmap): FloatArray
}

/** Neural aesthetic quality. */
interface AestheticScorer {
    /** NIMA mean score in [1, 10], or `null` when the aesthetic model is unavailable or failed for this image. */
    suspend fun score(bitmap: Bitmap): Float?
}

/** Scene category. [pixels] is the same image as [bitmap], already read out for pure analysis. */
interface SceneClassifier {
    /** Scene of the image. Never [SceneType.PORTRAIT]: the curation engine derives that from faces. */
    suspend fun classify(bitmap: Bitmap, pixels: ImagePixels): SceneType
}
