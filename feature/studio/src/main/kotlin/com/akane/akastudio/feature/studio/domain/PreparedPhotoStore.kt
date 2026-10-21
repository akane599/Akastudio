package com.akane.akastudio.feature.studio.domain

import com.akane.akastudio.core.composer.api.ComposerPhoto
import com.akane.akastudio.core.curator.api.PhotoAnalysis
import com.akane.akastudio.core.ingestion.model.PhotoCandidate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Use case owning the set of prepared photos. It prepares only what is missing (or was prepared with the other
 * hero budget), releases only what is no longer used, and cleans up a partially prepared batch when preparation
 * fails or is cancelled.
 *
 * Not thread-safe: the caller confines every call to one dispatcher (the studio's main dispatcher). Only the
 * preparer calls themselves hop to [io].
 */
internal class PreparedPhotoStore(
    private val preparer: PhotoPreparer,
    private val io: CoroutineDispatcher,
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
     * Nothing is released here; call [retainOnly] once the new selection is committed. When preparation throws
     * (including cancellation), the whole batch is released and forgotten before the exception propagates.
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
                withContext(io) { preparer.prepare(missing, analyses, hero) }
            } catch (t: Throwable) {
                withContext(NonCancellable) { preparer.release(missingIds) }
                throw t
            }
            val byId = fresh.associateBy { it.id }
            if (byId.keys != missingIds.toSet()) {
                withContext(NonCancellable) { preparer.release(missingIds + byId.keys) }
                throw IllegalStateException(
                    "Preparer returned ${byId.keys.sorted()} for requested ${missingIds.sorted()}",
                )
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
        withContext(NonCancellable + io) { preparer.release(drop) }
    }

    /**
     * Forgets every prepared photo at once and returns their ids; the caller releases them through the preparer
     * (after any cancelled preparation has finished). Lets a reset empty the store synchronously.
     */
    fun detachAll(): List<String> {
        val ids = entries.keys.toList()
        entries.clear()
        return ids
    }
}
