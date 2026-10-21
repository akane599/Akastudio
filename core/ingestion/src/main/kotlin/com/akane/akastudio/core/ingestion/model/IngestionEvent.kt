package com.akane.akastudio.core.ingestion.model

/**
 * @property sourcesTotal Picker items + archive image entries discovered so far.
 * @property filesRead Sources whose metadata parse finished (accepted or skipped).
 */
data class IngestionTelemetry(
    val sourcesTotal: Int = 0,
    val filesRead: Int = 0,
    val candidatesAccepted: Int = 0,
    val skipped: Int = 0,
    val bytesExtracted: Long = 0,
)

sealed interface IngestionEvent {
    data class Progress(val telemetry: IngestionTelemetry) : IngestionEvent

    data class CandidateReady(val candidate: PhotoCandidate, val telemetry: IngestionTelemetry) : IngestionEvent

    data class Skipped(val displayName: String, val reason: SkipReason, val telemetry: IngestionTelemetry) : IngestionEvent

    /** Whole archive rejected; other sources continue. */
    data class SourceFailed(
        val displayName: String,
        val error: IngestionError,
        val telemetry: IngestionTelemetry,
    ) : IngestionEvent

    /** Always last on normal completion. */
    data class Completed(val candidates: List<PhotoCandidate>, val telemetry: IngestionTelemetry) : IngestionEvent
}
