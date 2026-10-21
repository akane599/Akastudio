package com.akane.akastudio.core.curator.ml

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * The curator's model set. Each slot holds either the on-device model adapter or its deterministic
 * fallback; [degraded] names the slots running on a fallback ([FACES], [EMBEDDER], [AESTHETICS],
 * [SCENES]).
 *
 * [close] releases every native model. After it, an on-device adapter behaves as follows: the scene
 * classifier degrades to the [HeuristicSceneClassifier] result, the aesthetic scorer returns `null`, and the
 * face detector and embedder throw IllegalStateException. Fallback adapters hold no native model and keep
 * working.
 */
class CuratorModels(
    val faces: FaceDetector,
    val embedder: Embedder,
    val aesthetics: AestheticScorer,
    val scenes: SceneClassifier,
    val degraded: Set<String>,
) : AutoCloseable {

    override fun close() {
        var failure: Throwable? = null
        for (component in listOf(faces, embedder, aesthetics, scenes)) {
            try {
                (component as? AutoCloseable)?.close()
            } catch (e: Exception) {
                if (failure == null) failure = e else failure.addSuppressed(e)
            }
        }
        failure?.let { throw it }
    }

    /** Names used in [degraded]. */
    companion object {
        const val FACES = "faces"
        const val EMBEDDER = "embedder"
        const val AESTHETICS = "aesthetics"
        const val SCENES = "scenes"
    }
}

/** Builds [CuratorModels] from the pinned assets. Loading takes hundreds of milliseconds: call it off the main thread. */
object CuratorModelsFactory {

    private const val TAG = "CuratorModels"

    /**
     * Loads every model; never throws. A model that fails to load (missing asset, unsupported ABI, tensor
     * contract mismatch, native error) is replaced by its fallback and listed in [CuratorModels.degraded].
     */
    fun create(context: Context): CuratorModels = create(context, Dispatchers.Default)

    internal fun create(context: Context, dispatcher: CoroutineDispatcher): CuratorModels {
        val app = context.applicationContext ?: context
        val degraded = linkedSetOf<String>()
        val faces = load(CuratorModels.FACES, degraded, { NoFaceDetector }) {
            MediaPipeFaceDetector.create(app, dispatcher)
        }
        val embedder = load(CuratorModels.EMBEDDER, degraded, { FallbackEmbedderAdapter(dispatcher) }) {
            MediaPipeEmbedder.create(app, dispatcher)
        }
        val aesthetics = load(CuratorModels.AESTHETICS, degraded, { UnavailableAestheticScorer }) {
            LiteRtNimaScorer.create(app)
        }
        val scenes = load(CuratorModels.SCENES, degraded, { HeuristicSceneClassifierAdapter(dispatcher) }) {
            MediaPipeSceneClassifier.create(app, dispatcher)
        }
        return CuratorModels(faces, embedder, aesthetics, scenes, degraded.toSet())
    }

    /**
     * Native loaders fail with exceptions as well as linkage errors (UnsatisfiedLinkError on an ABI without
     * the library, ExceptionInInitializerError from a failed static load); both mean "use the fallback".
     */
    private inline fun <T> load(name: String, degraded: MutableSet<String>, fallback: () -> T, loader: () -> T): T =
        try {
            loader()
        } catch (e: Exception) {
            Log.w(TAG, "$name model unavailable; using fallback", e)
            degraded += name
            fallback()
        } catch (e: LinkageError) {
            Log.w(TAG, "$name native library unavailable; using fallback", e)
            degraded += name
            fallback()
        }
}
