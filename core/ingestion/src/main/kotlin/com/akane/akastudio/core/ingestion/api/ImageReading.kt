package com.akane.akastudio.core.ingestion.api

import android.graphics.Bitmap
import com.akane.akastudio.core.ingestion.model.CaptureMetadata
import com.akane.akastudio.core.ingestion.model.ImageFormat
import com.akane.akastudio.core.ingestion.model.PhotoSource
import java.io.InputStream

data class ParsedImage(
    val format: ImageFormat,
    val widthPx: Int,
    val heightPx: Int,
    val byteSize: Long?,
    val metadata: CaptureMetadata,
)

/**
 * Bounds + metadata only; never full-decodes. Returns null when the bytes are not a decodable supported image
 * (caller emits Skipped(UNDECODABLE)).
 */
interface MetadataParser {
    suspend fun parse(source: PhotoSource): ParsedImage?
}

/**
 * Upright (orientation applied), software-allocated ARGB_8888 bitmap whose long edge is <= maxEdgePx; never
 * allocates the full-resolution image.
 */
interface AnalysisDecoder {
    suspend fun decode(source: PhotoSource, maxEdgePx: Int = 512): Bitmap
}

/** Opens bytes for any PhotoSource (ContentResolver for ContentUri, FileInputStream for ExtractedFile). */
fun interface SourceOpener {
    fun open(source: PhotoSource): InputStream
}
