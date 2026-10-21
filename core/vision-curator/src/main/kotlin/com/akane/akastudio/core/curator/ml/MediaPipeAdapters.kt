package com.akane.akastudio.core.curator.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.akane.akastudio.core.color.api.ImagePixels
import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.color.api.SceneType
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.imageclassifier.ImageClassifier
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Wraps [bitmap] for MediaPipe without taking ownership. MPImage.close() recycles the wrapped bitmap, so
 * images built here are never closed; only a private ARGB_8888 conversion copy is recycled by [use].
 */
private inline fun <R> withMpImage(bitmap: Bitmap, use: (MPImage) -> R): R {
    val argb = BitmapSupport.argb8888(bitmap)
    try {
        return use(BitmapImageBuilder(argb).build())
    } finally {
        if (argb !== bitmap) argb.recycle()
    }
}

private fun baseOptions(entry: ModelManifest.Entry): BaseOptions =
    BaseOptions.builder().setModelAssetPath(entry.assetPath).build()

/** MediaPipe FaceLandmarker (IMAGE mode, up to 6 faces, blendshapes on). */
internal class MediaPipeFaceDetector private constructor(
    private val task: SerializedResource<FaceLandmarker>,
) : FaceDetector, AutoCloseable {

    override suspend fun detect(bitmap: Bitmap): List<DetectedFace> = task.withResource { landmarker ->
        val result = withMpImage(bitmap) { landmarker.detect(it) }
        val blendshapes = result.faceBlendshapes().orElse(emptyList())
        result.faceLandmarks().mapIndexedNotNull { index, landmarks ->
            if (landmarks.isEmpty()) return@mapIndexedNotNull null
            val xs = FloatArray(landmarks.size) { landmarks[it].x() }
            val ys = FloatArray(landmarks.size) { landmarks[it].y() }
            val scores = blendshapes.getOrNull(index).orEmpty().map { it.categoryName() to it.score() }
            DetectedFace(
                box = FaceGeometry.landmarkBox(xs, ys),
                leftBlink = FaceGeometry.blendshape(scores, FaceGeometry.EYE_BLINK_LEFT),
                rightBlink = FaceGeometry.blendshape(scores, FaceGeometry.EYE_BLINK_RIGHT),
            )
        }
    }

    override fun close() = task.close()

    companion object {
        const val MAX_FACES = 6

        fun create(context: Context, dispatcher: CoroutineDispatcher): MediaPipeFaceDetector {
            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions(ModelManifest.FACE_LANDMARKER))
                .setRunningMode(RunningMode.IMAGE)
                .setNumFaces(MAX_FACES)
                .setOutputFaceBlendshapes(true)
                .build()
            val landmarker = FaceLandmarker.createFromOptions(context, options)
            return MediaPipeFaceDetector(SerializedResource(landmarker, dispatcher) { it.close() })
        }
    }
}

/** MediaPipe ImageEmbedder (IMAGE mode, L2-normalized float output). */
internal class MediaPipeEmbedder private constructor(
    private val task: SerializedResource<ImageEmbedder>,
    override val dimension: Int,
) : Embedder, AutoCloseable {

    override suspend fun embed(bitmap: Bitmap): FloatArray = task.withResource { embedder ->
        val vector = withMpImage(bitmap) { embedVector(embedder, it) }
        check(vector.size == dimension) { "embedder returned ${vector.size} values, expected $dimension" }
        vector
    }

    override fun close() = task.close()

    companion object {
        private const val PROBE_EDGE = 32

        /** Loads the model and runs one probe embedding to pin [dimension]; throws if either fails. */
        fun create(context: Context, dispatcher: CoroutineDispatcher): MediaPipeEmbedder {
            val options = ImageEmbedder.ImageEmbedderOptions.builder()
                .setBaseOptions(baseOptions(ModelManifest.IMAGE_EMBEDDER))
                .setRunningMode(RunningMode.IMAGE)
                .setL2Normalize(true)
                .setQuantize(false)
                .build()
            val embedder = ImageEmbedder.createFromOptions(context, options)
            try {
                val probe = Bitmap.createBitmap(PROBE_EDGE, PROBE_EDGE, Bitmap.Config.ARGB_8888)
                val dimension = try {
                    probe.eraseColor(PROBE_GRAY)
                    embedVector(embedder, BitmapImageBuilder(probe).build()).size
                } finally {
                    probe.recycle()
                }
                check(dimension > 0) { "embedder produced an empty vector" }
                return MediaPipeEmbedder(SerializedResource(embedder, dispatcher) { it.close() }, dimension)
            } catch (e: Throwable) {
                embedder.close()
                throw e
            }
        }

        private const val PROBE_GRAY = 0xFF808080.toInt()

        private fun embedVector(embedder: ImageEmbedder, image: MPImage): FloatArray {
            val embedding = embedder.embed(image).embeddingResult().embeddings().firstOrNull()
                ?: error("embedder returned no embedding")
            return embedding.floatEmbedding().copyOf()
        }
    }
}

/**
 * MediaPipe ImageClassifier (EfficientNet-Lite0, ImageNet-1k, top 5 above 0.05) mapped to a scene through
 * [ImageNetSceneMapping]. When the mapping abstains, or inference fails, the deterministic
 * [HeuristicSceneClassifier] decides instead.
 */
internal class MediaPipeSceneClassifier private constructor(
    private val task: SerializedResource<ImageClassifier>,
) : SceneClassifier, AutoCloseable {

    override suspend fun classify(bitmap: Bitmap, pixels: ImagePixels): SceneType {
        val labels = try {
            task.withResource { classifier ->
                val result = withMpImage(bitmap) { classifier.classify(it) }
                result.classificationResult().classifications().firstOrNull()?.categories().orEmpty()
                    .map { it.categoryName() to it.score() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            Log.w(TAG, "image classifier failed; using the heuristic scene", e)
            return HeuristicSceneClassifier.classify(pixels)
        }
        return ImageNetSceneMapping.map(labels, HeuristicSceneClassifier.meanL(pixels))
            ?: HeuristicSceneClassifier.classify(pixels)
    }

    override fun close() = task.close()

    companion object {
        private const val TAG = "SceneClassifier"
        const val MAX_RESULTS = 5
        const val SCORE_THRESHOLD = 0.05f

        fun create(context: Context, dispatcher: CoroutineDispatcher): MediaPipeSceneClassifier {
            val options = ImageClassifier.ImageClassifierOptions.builder()
                .setBaseOptions(baseOptions(ModelManifest.IMAGE_CLASSIFIER))
                .setRunningMode(RunningMode.IMAGE)
                .setMaxResults(MAX_RESULTS)
                .setScoreThreshold(SCORE_THRESHOLD)
                .build()
            val classifier = ImageClassifier.createFromOptions(context, options)
            return MediaPipeSceneClassifier(SerializedResource(classifier, dispatcher) { it.close() })
        }
    }
}

/** Pure face-mesh geometry and blendshape lookup. */
internal object FaceGeometry {
    const val EYE_BLINK_LEFT = "eyeBlinkLeft"
    const val EYE_BLINK_RIGHT = "eyeBlinkRight"

    /** Bounding box of normalized landmark coordinates, each edge clamped to [0, 1]. */
    fun landmarkBox(xs: FloatArray, ys: FloatArray): NormRect {
        require(xs.isNotEmpty() && xs.size == ys.size) { "need matching, non-empty landmark coordinates" }
        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (i in xs.indices) {
            minX = minOf(minX, xs[i])
            maxX = maxOf(maxX, xs[i])
            minY = minOf(minY, ys[i])
            maxY = maxOf(maxY, ys[i])
        }
        return NormRect(minX.coerceIn(0f, 1f), minY.coerceIn(0f, 1f), maxX.coerceIn(0f, 1f), maxY.coerceIn(0f, 1f))
    }

    /** Score of the blendshape called [name], clamped to [0, 1]; 0 when absent. */
    fun blendshape(categories: List<Pair<String, Float>>, name: String): Float =
        categories.firstOrNull { it.first == name }?.second?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
}
