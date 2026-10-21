package com.akane.akastudio.core.ingestion.api

import com.akane.akastudio.core.ingestion.model.ImageFormat
import com.akane.akastudio.core.ingestion.model.PhotoSource
import com.akane.akastudio.core.ingestion.model.SkipReason
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.flow.Flow

/**
 * @property maxEntryBytes A 50 MP DNG can exceed 100 MB.
 * @property maxCompressionRatio Inflated/compressed, enforced once inflated bytes > [ratioGraceBytes].
 */
data class ZipLimits(
    val maxEntries: Int = 2_000,
    val maxEntryBytes: Long = 256L * 1024 * 1024,
    val maxTotalBytes: Long = 4L * 1024 * 1024 * 1024,
    val maxCompressionRatio: Double = 100.0,
    val ratioGraceBytes: Long = 1L * 1024 * 1024,
    val maxPathDepth: Int = 16,
    val maxNameLength: Int = 512,
    val freeSpaceReserveBytes: Long = 256L * 1024 * 1024,
)

sealed interface ZipExtractionEvent {
    data class EntryExtracted(val source: PhotoSource.ExtractedFile, val format: ImageFormat, val bytes: Long) :
        ZipExtractionEvent

    data class EntrySkipped(val entryName: String, val reason: SkipReason) : ZipExtractionEvent
}

/**
 * Streams one archive into [destinationDir]. Throws [IngestionException] carrying an
 * [com.akane.akastudio.core.ingestion.model.IngestionError] for whole-archive rejection. Cooperatively
 * cancellable; on failure/cancel it deletes partial files it created.
 */
interface ArchiveExtractor {
    fun extract(input: () -> InputStream, archiveIdentity: String, destinationDir: File): Flow<ZipExtractionEvent>
}
