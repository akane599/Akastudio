package com.akane.akastudio.core.ingestion.model

sealed interface PhotoSource {
    val displayName: String

    data class ContentUri(val uri: String, override val displayName: String) : PhotoSource

    data class ExtractedFile(
        val path: String,
        val archiveEntryName: String,
        override val displayName: String,
    ) : PhotoSource
}
