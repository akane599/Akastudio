package com.akane.akastudio.core.ingestion.api

import com.akane.akastudio.core.ingestion.model.IngestionEvent
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.flow.Flow

sealed interface IngestionRequest {
    data class PickedMedia(val uris: List<String>) : IngestionRequest
    data class ZipArchive(val uri: String, val displayName: String) : IngestionRequest
}

/** Owns a session temp dir under cacheDir/ingest/<sessionId>/; close() deletes it recursively. */
interface IngestionSession : Closeable {
    val workDir: File
    fun ingest(requests: List<IngestionRequest>): Flow<IngestionEvent>
}
