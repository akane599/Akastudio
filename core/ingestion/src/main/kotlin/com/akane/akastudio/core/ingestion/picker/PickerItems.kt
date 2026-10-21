package com.akane.akastudio.core.ingestion.picker

import com.akane.akastudio.core.ingestion.model.ImageFormat
import com.akane.akastudio.core.ingestion.model.PhotoSource

data class PickedItem(val source: PhotoSource.ContentUri, val format: ImageFormat?, val sizeBytes: Long?)

/** Pure, JVM-testable parts of picker resolution. */
object PickerItems {

    private const val DEFAULT_DISPLAY_NAME = "photo"

    /** Drops repeated identical URI strings, keeping the first-seen order. */
    fun dedupe(uris: List<String>): List<String> = uris.distinct()

    /** Last path segment of [uri] (query and fragment ignored), or "photo" when it has no path. */
    fun displayNameFallback(uri: String): String {
        val withoutSuffix = uri.substringBefore('#').substringBefore('?')
        val schemeEnd = withoutSuffix.indexOf("://")
        val path = if (schemeEnd >= 0) {
            withoutSuffix.substring(schemeEnd + 3).substringAfter('/', missingDelimiterValue = "")
        } else {
            withoutSuffix
        }
        return path.trimEnd('/').substringAfterLast('/').ifBlank { DEFAULT_DISPLAY_NAME }
    }

    /** Format implied by the file extension of [displayName], or null when it has none or it is unsupported. */
    fun formatFromDisplayName(displayName: String): ImageFormat? {
        val extension = displayName.substringAfterLast('.', missingDelimiterValue = "")
        return if (extension.isEmpty()) null else ImageFormat.fromExtension(extension)
    }
}
