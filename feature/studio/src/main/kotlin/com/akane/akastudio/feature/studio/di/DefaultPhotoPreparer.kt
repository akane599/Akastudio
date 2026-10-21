package com.akane.akastudio.feature.studio.di

import android.util.Log
import com.akane.akastudio.core.color.api.ImagePixels
import com.akane.akastudio.core.composer.api.ComposerPhoto
import com.akane.akastudio.core.composer.horizon.HorizonDetector
import com.akane.akastudio.core.curator.api.PhotoAnalysis
import com.akane.akastudio.core.ingestion.model.PhotoCandidate
import com.akane.akastudio.feature.studio.domain.PhotoPreparer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A full-resolution decode handed to the renderer. The renderer keeps a reference to the pixels while the image is
 * registered (to re-upload after a lost GL context), so the preparer frees it only after unregistering it, or after
 * a newer registration under the same key replaced it.
 *
 * @property sourceWidth Upright width of the encoded image before scaling.
 * @property sourceHeight Upright height of the encoded image before scaling.
 */
internal interface FullResolutionImage {
    val sourceWidth: Int
    val sourceHeight: Int

    /** Frees the pixels. Called once, never while the renderer still holds the image. */
    fun free()
}

/** The platform side of photo preparation: decoding and renderer registration. */
internal interface PreparerBackend {
    /** GL_MAX_TEXTURE_SIZE of the renderer's context. */
    suspend fun maxTextureSize(): Int

    /**
     * Decodes [candidate] upright with its long edge at most [maxLongEdge]; throws when it cannot. Runs under
     * NonCancellable, so [callerCancelled] tells whether the caller has since been cancelled, which skips work such
     * as a retry after running out of memory.
     */
    suspend fun decodeFull(
        candidate: PhotoCandidate,
        maxLongEdge: Int,
        callerCancelled: () -> Boolean,
    ): FullResolutionImage

    /** Registers [image] with the renderer under [key], replacing an earlier registration of that key. */
    suspend fun register(key: String, image: FullResolutionImage)

    /** Drops the renderer registration of [key]. */
    suspend fun unregister(key: String)

    /** The upright sRGB pixels of [candidate] with the long edge at most [maxEdgePx], for horizon detection. */
    suspend fun analysisPixels(candidate: PhotoCandidate, maxEdgePx: Int): ImagePixels
}

/** The full-resolution decode of the photo named [displayName] failed; [cause] says why. */
class PhotoDecodeException(val displayName: String, cause: Throwable) :
    Exception("Could not decode $displayName at full resolution", cause)

/**
 * The production [PhotoPreparer]. Decodes each candidate at the renderer's texture limit capped at [HERO_EDGE_PX]
 * (hero) or [COLLAGE_EDGE_PX] (collage), registers it under its candidate id, measures its horizon tilt on a
 * [HORIZON_EDGE_PX] analysis image, and builds the [ComposerPhoto] from the curator's analysis.
 *
 * Decodes run one at a time, and [prepare] and [release] never overlap, which caps peak memory at one decode in
 * flight. Every registered image stays tracked from the moment it is registered, so releasing the ids of a failed or
 * cancelled batch frees what that batch had already registered. An image is freed only once its unregister succeeds:
 * one whose unregister failed stays tracked, and every later [release] retries it.
 */
internal class DefaultPhotoPreparer(
    private val backend: PreparerBackend,
    private val horizon: (ImagePixels) -> Float = HorizonDetector::detectTiltDegrees,
) : PhotoPreparer {

    private val mutex = Mutex()

    /** Registered images by candidate id. Guarded by [mutex]. */
    private val registered = HashMap<String, FullResolutionImage>()

    /** Ids still in [registered] whose unregister failed, retried by every [release]. Guarded by [mutex]. */
    private val unregisterFailed = LinkedHashSet<String>()

    override suspend fun prepare(
        candidates: List<PhotoCandidate>,
        analyses: Map<String, PhotoAnalysis>,
        hero: Boolean,
    ): List<ComposerPhoto> = mutex.withLock {
        if (candidates.isEmpty()) return@withLock emptyList()
        val edge = decodeEdge(backend.maxTextureSize(), hero)
        val prepared = LinkedHashMap<String, ComposerPhoto>()
        candidates.map { candidate ->
            prepared.getOrPut(candidate.id) { prepareOne(candidate, analyses, edge) }
        }
    }

    override suspend fun release(ids: Collection<String>) = mutex.withLock {
        val requested = ids.toSet()
        var failure: Throwable? = null
        for (id in requested + unregisterFailed) {
            val image = registered[id] ?: continue
            try {
                backend.unregister(id)
            } catch (t: Throwable) {
                // The renderer may still hold the pixels, so they stay ours to retry. A retry nobody asked for here
                // was already reported to its caller when it first failed, so it is only logged.
                unregisterFailed += id
                if (id in requested) {
                    if (failure == null) failure = t else failure.addSuppressed(t)
                } else {
                    Log.w(TAG, "Retried unregister of '$id' failed again; its pixels stay held", t)
                }
                continue
            }
            registered.remove(id)
            unregisterFailed -= id
            image.free()
        }
        failure?.let { throw it }
        Unit
    }

    private suspend fun prepareOne(
        candidate: PhotoCandidate,
        analyses: Map<String, PhotoAnalysis>,
        edge: Int,
    ): ComposerPhoto {
        val analysis = checkNotNull(analyses[candidate.id]) { "No curator analysis for photo ${candidate.id}" }
        val image = try {
            decodeOwned(candidate, edge)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw PhotoDecodeException(candidate.source.displayName, e)
        }
        try {
            // Not cancellable: a cancelled caller would otherwise get CancellationException after the GL thread had
            // already registered the image, then free pixels the renderer holds and never unregister the texture.
            withContext(NonCancellable) { backend.register(candidate.id, image) }
        } catch (t: Throwable) {
            // The renderer keeps its earlier registration of this id (if any); only the new decode is ours to free.
            image.free()
            throw t
        }
        // A replaced registration no longer references the older pixels, so they can go now, and an unregister of
        // them that failed must not be retried against the new registration.
        unregisterFailed -= candidate.id
        registered.put(candidate.id, image)?.takeIf { it !== image }?.free()
        // Tracked before the cancellation check, so releasing this batch's ids unregisters it, then frees it.
        currentCoroutineContext().ensureActive()
        return ComposerPhoto(
            id = candidate.id,
            imageKey = candidate.id,
            widthPx = uprightDimension(candidate.widthPx, image.sourceWidth),
            heightPx = uprightDimension(candidate.heightPx, image.sourceHeight),
            saliency = analysis.saliency,
            faces = analysis.faces,
            palette = analysis.palette,
            horizonTiltDegrees = tiltOf(candidate),
            metadata = candidate.metadata,
        )
    }

    /**
     * Decodes [candidate] so that the result always has an owner. The decoder hops to its IO dispatcher, and a
     * `withContext` whose caller was cancelled meanwhile discards the finished decode, which nobody could then free.
     * So the decode runs to completion even after a cancel (it blocks on the IO thread either way), and a decode the
     * cancelled caller will not register is freed here. The backend still sees the caller's cancellation, so it can
     * skip a retry.
     */
    private suspend fun decodeOwned(candidate: PhotoCandidate, edge: Int): FullResolutionImage {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        var decoded: FullResolutionImage? = null
        try {
            val image = withContext(NonCancellable) {
                backend.decodeFull(candidate, edge, callerCancelled = { !caller.isActive }).also { decoded = it }
            }
            currentCoroutineContext().ensureActive()
            return image
        } catch (e: CancellationException) {
            decoded?.free()
            throw e
        }
    }

    /**
     * Horizon leveling is a refinement: when the analysis image cannot be read the photo is simply left unrotated,
     * which is also the detector's own answer for an image it is unsure about.
     */
    private suspend fun tiltOf(candidate: PhotoCandidate): Float {
        val pixels = try {
            backend.analysisPixels(candidate, HORIZON_EDGE_PX)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return 0f
        }
        return horizon(pixels)
    }

    internal companion object {
        private const val TAG = "PhotoPreparer"

        /** Long-edge cap for a single hero photo. */
        const val HERO_EDGE_PX = 4096

        /** Long-edge cap for each collage photo. */
        const val COLLAGE_EDGE_PX = 3072

        /** Long edge of the image the horizon detector reads. */
        const val HORIZON_EDGE_PX = 1024

        /** The decode long edge: the renderer's texture limit, capped by the hero or collage budget. */
        fun decodeEdge(maxTextureSize: Int, hero: Boolean): Int {
            require(maxTextureSize > 0) { "maxTextureSize must be positive: $maxTextureSize" }
            return minOf(maxTextureSize, if (hero) HERO_EDGE_PX else COLLAGE_EDGE_PX)
        }

        /**
         * The candidate's upright size, which the composer and the canvas hit test both read, or the decoder's
         * measurement when ingestion recorded none.
         */
        fun uprightDimension(candidatePx: Int, decodedSourcePx: Int): Int =
            if (candidatePx > 0) candidatePx else decodedSourcePx
    }
}
