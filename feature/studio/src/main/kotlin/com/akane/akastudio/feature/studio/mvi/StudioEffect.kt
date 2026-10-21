package com.akane.akastudio.feature.studio.mvi

import com.akane.akastudio.core.export.api.ExportResult
import com.akane.akastudio.core.ingestion.model.IngestionError

/** One-shot events for the UI, delivered once each through `StudioViewModel.effects`. */
sealed interface StudioEffect {
    /** Open the system share sheet for the exported image at [uri] of type [mime]. */
    data class ShareExport(val uri: String, val mime: String) : StudioEffect

    /** Show a transient message (snackbar). */
    data class ShowMessage(val message: StudioMessage) : StudioEffect
}

/** Transient messages; the UI formats each into one localized sentence. */
sealed interface StudioMessage {
    /** The request was ignored because work is running; cancel it first. */
    data object Busy : StudioMessage

    /** One source (usually an archive) was rejected; the other sources continue. */
    data class SourceFailed(val displayName: String, val error: IngestionError) : StudioMessage

    /** An export finished and was saved without sharing. */
    data class ExportSaved(val result: ExportResult) : StudioMessage
}
