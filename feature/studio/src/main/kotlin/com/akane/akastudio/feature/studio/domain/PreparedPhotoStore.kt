package com.akane.akastudio.feature.studio.domain

import android.util.Log
import com.akane.akastudio.core.composer.api.ComposerPhoto
import com.akane.akastudio.core.curator.api.PhotoAnalysis
import com.akane.akastudio.core.ingestion.model.PhotoCandidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Use case owning one story generation's prepared photos. It prepares only what is missing (or was prepared with
 * the other hero budget), releases only what is no longer used, and cleans up a partially prepared batch when
 * preparation fails or is cancelled.
 *
 * Every preparer call carries this store's [owner], so a preparer shared with other generations can tell this
 * store's releases from theirs (see [PhotoOwner]).
 *
 * Not thread-safe: the caller confines every call to one dispatcher (the studio's main dispatcher). Only the
 * preparer calls themselves hop to [io].
 */
internal class PreparedPhotoStore(
    private val preparer: PhotoPreparer,
    private val io: CoroutineDispatcher,
    val owner: PhotoOwner = PhotoOwner(),
) {
    private class Entry(val photo: ComposerPhoto, val hero: Boolean)

    private val entries = LinkedHashMap<String, Entry>()

    /** True when every id in [ids] is prepared with the [hero] budget. */
    fun covers(ids: List<String>, hero: Boolean): Boolean = ids.all { entries[it]?.hero == hero }

    /** The prepared photos for [ids] in that order. Requires [covers]. */
    fun photos(ids: List<String>): List<ComposerPhoto> = ids.map { id ->
        checkNotNull(entries[id]) { "Photo $id is not prepared" }.photo
    }

    /**
     * Ensures every candidate is prepared with the [hero] budget and returns their photos in candidate order.
     * Nothing is released here; call [retainOnly] once the new selection is committed.
     *
     * When preparation throws (including cancellation), the whole batch is released and forgotten before the
     * exception propagates. The exception the caller sees is always the preparation's own: a failure of that cleanup
     * is attached to it as suppressed, so a cancellation never turns into a reported error.
     */
    suspend fun acquire(
        candidates: List<PhotoCandidate>,
        analyses: Map<String, PhotoAnalysis>,
        hero: Boolean,
    ): List<ComposerPhoto> {
        val missing = candidates.filter { entries[it.id]?.hero != hero }.distinctBy { it.id }
        if (missing.isNotEmpty()) {
            val missingIds = missing.map { it.id }
            // Re-preparing replaces the renderer registration, so the old entries are invalid from here on.
            missingIds.forEach { entries.remove(it) }
            val fresh = try {
                withContext(io + owner) { preparer.prepare(missing, analyses, hero) }
            } catch (t: Throwable) {
                throw t.alsoReleasing(missingIds)
            }
            val byId = fresh.associateBy { it.id }
            if (byId.keys != missingIds.toSet()) {
                throw IllegalStateException(
                    "Preparer returned ${byId.keys.sorted()} for requested ${missingIds.sorted()}",
                ).alsoReleasing(missingIds + byId.keys)
            }
            missingIds.forEach { id -> entries[id] = Entry(byId.getValue(id), hero) }
        }
        return photos(candidates.map { it.id })
    }

    /** Releases every prepared photo whose id is not in [ids]. */
    suspend fun retainOnly(ids: Collection<String>) {
        val keep = ids.toSet()
        val drop = entries.keys.filter { it !in keep }
        if (drop.isEmpty()) return
        drop.forEach { entries.remove(it) }
        release(drop)
    }

    /**
     * Forgets every prepared photo at once and returns their ids; the caller releases them through [release] once
     * any cancelled preparation has finished. Lets a retirement empty the store synchronously.
     */
    fun detachAll(): List<String> {
        val ids = entries.keys.toList()
        entries.clear()
        return ids
    }

    /**
     * Unregisters and frees [ids] under this store's [owner]. Runs to completion even when the caller is cancelled,
     * and returns normally then: a cancelled caller decides for itself whether to stop afterwards.
     */
    suspend fun release(ids: Collection<String>) {
        if (ids.isEmpty()) return
        // The inner hop's return check sees NonCancellable, and the outer, same-dispatcher block does not re-check
        // the caller's cancellation, so a finished release is never reported as a CancellationException.
        withContext(NonCancellable) {
            withContext(io + owner) { preparer.release(ids) }
        }
    }

    /** Releases [ids] on the way out of a failed or cancelled acquisition; cleanup failures become suppressed. */
    private suspend fun <T : Throwable> T.alsoReleasing(ids: Collection<String>): T {
        try {
            release(ids)
        } catch (cleanup: CancellationException) {
            // The release is non-cancellable, so this is only the caller's own cancellation surfacing on the way back.
        } catch (cleanup: Throwable) {
            if (cleanup !== this) addSuppressed(cleanup)
            // A suppressed failure on a cancellation is dropped with it by the coroutine machinery, so it is logged
            // here; the registration it concerns stays tracked by the preparer for a later retry either way.
            if (this is CancellationException) Log.w(TAG, "Releasing ${ids.size} photo(s) after a cancelled acquisition failed", cleanup)
        }
        return this
    }

    private companion object {
        const val TAG = "PreparedPhotoStore"
    }
}
