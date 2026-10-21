package com.akane.akastudio.core.ingestion.pipeline

import com.akane.akastudio.core.ingestion.model.IngestionTelemetry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the running [IngestionTelemetry]. [record] applies a change and publishes the resulting snapshot under one
 * lock, so concurrent producers publish snapshots in the order they were applied (monotonic for the collector).
 */
class TelemetryAccumulator(initial: IngestionTelemetry = IngestionTelemetry()) {

    private val mutex = Mutex()
    private var current = initial

    suspend fun <T> record(
        change: (IngestionTelemetry) -> IngestionTelemetry,
        publish: suspend (IngestionTelemetry) -> T,
    ): T = mutex.withLock {
        val next = change(current)
        current = next
        publish(next)
    }
}
