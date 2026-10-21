package com.akane.akastudio.core.ingestion.metadata

import com.akane.akastudio.core.ingestion.model.ImageFormat
import com.akane.akastudio.core.ingestion.model.PhotoSource

/** Resolves an [ImageFormat] from the names a [PhotoSource] carries, for when the decoder reports no MIME type. */
internal object SourceNames {
    fun formatFromExtension(source: PhotoSource): ImageFormat? {
        val names = when (source) {
            is PhotoSource.ContentUri -> listOf(source.displayName, source.uri.substringBefore('?'))
            is PhotoSource.ExtractedFile -> listOf(source.archiveEntryName, source.path, source.displayName)
        }
        return names.firstNotNullOfOrNull { name -> extensionOf(name)?.let(ImageFormat::fromExtension) }
    }

    private fun extensionOf(name: String): String? =
        name.substringAfterLast('/').substringAfterLast('.', missingDelimiterValue = "").takeIf { it.isNotEmpty() }
}
