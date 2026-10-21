package com.akane.akastudio.feature.studio.mvi

import com.akane.akastudio.core.color.api.MasterLook
import com.akane.akastudio.core.composer.api.BackgroundStyle
import com.akane.akastudio.core.composer.api.CellOverride
import com.akane.akastudio.core.composer.api.StoryTemplate
import com.akane.akastudio.core.curator.api.StoryMode
import com.akane.akastudio.core.export.api.ExportFormat
import com.akane.akastudio.core.export.api.ExportResolution

/** Every user interaction with the studio, sent to `StudioViewModel.onIntent`. */
sealed interface StudioIntent {
    /** Import picked photos (content uris). Accumulates into the current session until curation starts. */
    data class ImportPhotos(val uris: List<String>) : StudioIntent

    /**
     * Import a zip archive; [displayName] is the user-visible file name (used in messages and errors). When null or
     * blank, the view model looks it up from the document provider off the main thread before reading the zip.
     */
    data class ImportZip(val uri: String, val displayName: String? = null) : StudioIntent

    /**
     * A system picker opened. Auto-curation waits until [PickerClosed], so the picked photos join the batch being
     * read; when opened while photos are being read ("+ Add more"), its result never starts a new story.
     */
    data object PickerOpened : StudioIntent

    /** The picker closed: sent after its import intent, if any, or on cancellation. Re-arms auto-curation. */
    data object PickerClosed : StudioIntent

    /** Start curation now instead of waiting for the post-import debounce. */
    data object StartCuration : StudioIntent

    /** Cancel the running work and return to the previous stable phase. */
    data object CancelWork : StudioIntent

    /** Re-decide the story for [mode] without re-analysis. */
    data class ForceMode(val mode: StoryMode) : StudioIntent

    /** Replace the photo in [slot] with [candidateId] (a burst/cluster alternative). */
    data class SwapCandidate(val slot: Int, val candidateId: String) : StudioIntent

    /** Exchange the photos in slots [from] and [to]. */
    data class SwapSlots(val from: Int, val to: Int) : StudioIntent

    /** Set the crop/zoom/pan of [photoId]; null clears it. */
    data class SetCellOverride(val photoId: String, val override: CellOverride?) : StudioIntent

    /** Use [template]; ignored unless its photo count matches the current mode. */
    data class SelectTemplate(val template: StoryTemplate) : StudioIntent

    /** Gutter in 1080-wide pixels, clamped to 0..48. */
    data class SetGutter(val px: Float) : StudioIntent

    /** Cell corner radius in 1080-wide pixels, clamped to 0..64. */
    data class SetCornerRadius(val px: Float) : StudioIntent

    /** Canvas background behind the cells. */
    data class SetBackground(val style: BackgroundStyle) : StudioIntent

    /** Show or hide the EXIF caption. */
    data class SetExifCaption(val show: Boolean) : StudioIntent

    /** Apply a master look. */
    data class SelectLook(val look: MasterLook) : StudioIntent

    /** Move a grade slider; [value] is clamped to the field's range. */
    data class AdjustGrade(val field: GradeField, val value: Float) : StudioIntent

    /** Before/after split position in [0, 1], or null to turn comparison off. */
    data class SetCompare(val split: Float?) : StudioIntent

    /** Show a scope, or [ScopeMode.OFF]. */
    data class SetScope(val mode: ScopeMode) : StudioIntent

    /** Switch the control panel tab. */
    data class SelectTab(val tab: ControlTab) : StudioIntent

    /** Expand or collapse the story-mode drawer. */
    data object ToggleModeDrawer : StudioIntent

    /** Choose the export format. */
    data class SetExportFormat(val format: ExportFormat) : StudioIntent

    /** Choose the export resolution. */
    data class SetExportResolution(val resolution: ExportResolution) : StudioIntent

    /** Explicitly enable or disable the Ultra HDR gain map. */
    data class SetHdrBoost(val enabled: Boolean) : StudioIntent

    /** Export the story; [share] is true for "Export & share", which opens the share sheet when done. */
    data class Export(val share: Boolean) : StudioIntent

    /** Clear the shown error. */
    data object DismissError : StudioIntent

    /** Drop everything and return to an empty studio. */
    data object Reset : StudioIntent
}
