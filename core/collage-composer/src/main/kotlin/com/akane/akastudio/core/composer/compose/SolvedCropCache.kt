package com.akane.akastudio.core.composer.compose

import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.composer.api.ComposerPhoto
import com.akane.akastudio.core.composer.crop.SmartCropSolver
import com.akane.akastudio.core.curator.api.FaceObservation
import com.akane.akastudio.core.curator.api.SaliencyMap

/** Solves the crop of a photo for a slot aspect (width/height) and horizon rotation, like [SmartCropSolver.solve]. */
internal typealias CropSolver = (photo: ComposerPhoto, slotAspect: Float, rotationDegrees: Float) -> NormRect

/**
 * Least-recently-used memo of [solver] results, at most [capacity] entries.
 *
 * The key holds every input the solver reads: the photo's id, pixel size, saliency map, faces, the slot aspect and
 * the rotation, so a hit returns exactly what a fresh solve would. The saliency map is matched by identity (it is
 * an immutable-by-contract analysis product prepared once per photo); a new map instance always solves again.
 * Solving runs outside the lock, so concurrent misses for one key may both solve and store the same result.
 */
internal class SolvedCropCache(private val solver: CropSolver, private val capacity: Int = DEFAULT_CAPACITY) {
    init {
        require(capacity > 0) { "capacity must be positive, got $capacity" }
    }

    private val entries = object : LinkedHashMap<Key, NormRect>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, NormRect>?): Boolean = size > capacity
    }

    fun solve(photo: ComposerPhoto, slotAspect: Float, rotationDegrees: Float): NormRect {
        val key = Key(photo.id, photo.widthPx, photo.heightPx, photo.saliency, photo.faces, slotAspect, rotationDegrees)
        synchronized(entries) { entries[key] }?.let { return it }
        val solved = solver(photo, slotAspect, rotationDegrees)
        synchronized(entries) { entries[key] = solved }
        return solved
    }

    private data class Key(
        val photoId: String,
        val widthPx: Int,
        val heightPx: Int,
        val saliency: SaliencyMap,           // identity equality
        val faces: List<FaceObservation>,
        val slotAspect: Float,
        val rotationDegrees: Float,
    )

    private companion object {
        /** Four slots across several templates and both canvas sizes, with room for a swapped-in photo set. */
        const val DEFAULT_CAPACITY = 64
    }
}
