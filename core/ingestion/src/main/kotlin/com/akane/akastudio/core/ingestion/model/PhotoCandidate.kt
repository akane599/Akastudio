package com.akane.akastudio.core.ingestion.model

/**
 * @property id Stable within a session: hex SHA-256 of the source identity string (uri, or archive uri + entry name).
 * @property widthPx Upright (orientation already applied) pixel size.
 */
data class PhotoCandidate(
    val id: String,
    val source: PhotoSource,
    val format: ImageFormat,
    val byteSize: Long?,
    val widthPx: Int,
    val heightPx: Int,
    val metadata: CaptureMetadata,
)
