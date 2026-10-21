package com.akane.akastudio.core.export.store

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.akane.akastudio.core.export.pipeline.EncodedSink
import java.io.IOException
import java.io.OutputStream

/**
 * [EncodedSink] writing into MediaStore at `Pictures/AkaStudio` with the IS_PENDING protocol: the row is inserted
 * pending, written through `openOutputStream(uri, "w")`, then published with `IS_PENDING = 0`. On any throwable
 * (including `CancellationException`) the row is deleted and the throwable rethrown, so no pending or partial entry
 * survives a failed export.
 */
class MediaStoreSink(context: Context) : EncodedSink {

    private val resolver = context.applicationContext.contentResolver

    override fun write(displayName: String, mimeType: String, dateTakenMillis: Long?, block: (OutputStream) -> Unit): String {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (dateTakenMillis != null) put(MediaStore.MediaColumns.DATE_TAKEN, dateTakenMillis)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore refused to create $displayName")
        try {
            val stream = resolver.openOutputStream(uri, "w") ?: throw IOException("MediaStore returned no output stream for $uri")
            stream.use(block)
            val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            val updated = resolver.update(uri, published, null, null)
            if (updated != 1) throw IOException("MediaStore published $updated rows for $uri, expected 1")
            return uri.toString()
        } catch (failure: Throwable) {
            try {
                resolver.delete(uri, null, null)
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    private companion object {
        val RELATIVE_PATH = "${Environment.DIRECTORY_PICTURES}/AkaStudio"
    }
}
