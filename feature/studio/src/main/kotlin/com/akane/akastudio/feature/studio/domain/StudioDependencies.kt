package com.akane.akastudio.feature.studio.domain

import com.akane.akastudio.core.color.api.HarmonizationParams
import com.akane.akastudio.core.color.api.LabStats
import com.akane.akastudio.core.color.api.StoryRenderer
import com.akane.akastudio.core.composer.api.CollageComposer
import com.akane.akastudio.core.composer.api.ComposerPhoto
import com.akane.akastudio.core.curator.api.CurationEngine
import com.akane.akastudio.core.curator.api.PhotoAnalysis
import com.akane.akastudio.core.export.api.StoryExporter
import com.akane.akastudio.core.ingestion.api.IngestionSession
import com.akane.akastudio.core.ingestion.model.PhotoCandidate
import com.akane.akastudio.core.ingestion.picker.PickerItems
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Everything the studio needs from the outside world. The app's container implements it with the real core
 * modules; JVM tests implement it with fakes. The studio never constructs a dependency itself.
 *
 * The [renderer] is owned by the container: the studio uses it but never closes it.
 */
interface StudioDependencies {
    /** Opens a fresh ingestion session (its own temp dir). The studio closes it on reset and on clear. */
    fun newIngestionSession(): IngestionSession
    val curation: CurationEngine
    val composer: CollageComposer
    val renderer: StoryRenderer
    val exporter: StoryExporter
    val preparer: PhotoPreparer
    val harmonizer: HarmonizationPlanner
    val dispatchers: StudioDispatchers
    val errors: StudioErrorMapper

    /**
     * The user-visible name of the picked document at [uri]. Blocking (a provider query): the studio calls it on
     * `dispatchers.io`. Defaults to the uri's last segment.
     */
    fun documentDisplayName(uri: String): String = PickerItems.displayNameFallback(uri)
}

/**
 * The dispatchers the studio runs on. [main] confines all state mutation; [default] runs curation and export
 * collection; [io] runs ingestion, photo preparation and session cleanup. Tests pass one `StandardTestDispatcher`
 * for all three.
 */
data class StudioDispatchers(
    val main: CoroutineDispatcher,
    val default: CoroutineDispatcher,
    val io: CoroutineDispatcher,
)

/**
 * Turns chosen candidates into composer-ready photos: full-resolution decode, `StoryRenderer.registerImage`,
 * horizon tilt detection and palette lookup. Implementations register each photo under its own renderer key, so
 * preparing an id again replaces the earlier registration.
 */
interface PhotoPreparer {
    /**
     * Prepares [candidates] (any order); returns exactly one [ComposerPhoto] per candidate, with
     * `ComposerPhoto.id == PhotoCandidate.id`. [hero] asks for the single-photo quality budget.
     */
    suspend fun prepare(
        candidates: List<PhotoCandidate>,
        analyses: Map<String, PhotoAnalysis>,
        hero: Boolean,
    ): List<ComposerPhoto>

    /** Unregisters and frees the photos with these ids. Unknown ids are ignored. */
    suspend fun release(ids: Collection<String>)
}

/**
 * Plans the cross-image OKLab harmonization of a collage: one [HarmonizationParams] per entry of [stats]
 * (same order), pulling every image toward the image at [anchorIndex] by [strength] in [0, 1].
 */
fun interface HarmonizationPlanner {
    fun plan(stats: List<LabStats>, anchorIndex: Int, strength: Float): List<HarmonizationParams>
}
