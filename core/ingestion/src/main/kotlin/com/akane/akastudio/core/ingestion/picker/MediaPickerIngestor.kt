package com.akane.akastudio.core.ingestion.picker

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.akane.akastudio.core.ingestion.model.ImageFormat
import com.akane.akastudio.core.ingestion.model.PhotoSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Thin ContentResolver adapter turning picker URIs into [PickedItem]s. */
class MediaPickerIngestor(
    private val contentResolver: ContentResolver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun resolve(uris: List<String>): List<PickedItem> = withContext(ioDispatcher) {
        PickerItems.dedupe(uris).map { uri ->
            ensureActive()
            resolveOne(uri)
        }
    }

    private fun resolveOne(uriString: String): PickedItem {
        val uri = Uri.parse(uriString)
        persistReadPermission(uri)
        val openable = queryOpenable(uri)
        val displayName = openable?.displayName?.takeUnless { it.isBlank() }
            ?: PickerItems.displayNameFallback(uriString)
        val format = ImageFormat.fromMimeType(mimeTypeOf(uri)) ?: PickerItems.formatFromDisplayName(displayName)
        return PickedItem(PhotoSource.ContentUri(uriString, displayName), format, openable?.sizeBytes)
    }

    /** Picker URIs are often not persistable; the grant is best-effort. */
    private fun persistReadPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Not persistable: the session-scoped grant still covers this ingestion.
        }
    }

    private fun queryOpenable(uri: Uri): Openable? =
        try {
            contentResolver.query(uri, OPENABLE_PROJECTION, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                Openable(
                    displayName = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) cursor.getString(nameIndex) else null,
                    sizeBytes = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null,
                )
            }
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun mimeTypeOf(uri: Uri): String? =
        try {
            contentResolver.getType(uri)
        } catch (_: SecurityException) {
            null
        }

    private class Openable(val displayName: String?, val sizeBytes: Long?)

    private companion object {
        val OPENABLE_PROJECTION = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
    }
}
