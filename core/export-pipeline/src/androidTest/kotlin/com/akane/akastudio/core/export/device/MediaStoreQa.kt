package com.akane.akastudio.core.export.device

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.storage.StorageManager
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/** A MediaStore row as these tests read it back (pending rows included). */
internal data class MediaRow(
    val displayName: String,
    val mimeType: String,
    val relativePath: String,
    val isPending: Int,
    val size: Long,
    val width: Int,
    val height: Int,
    val dateTaken: Long?,
)

/**
 * MediaStore bookkeeping for one test run: exported rows are moved into `Pictures/AkaStudioQA/<run-id>/` and every
 * row this run created, and then that folder, is deleted by [cleanUp], so QA files never mix with real exports.
 */
internal class MediaStoreQa(context: Context) {

    private val resolver: ContentResolver = context.contentResolver

    val runId: String = "run-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + "-" +
        Random.nextInt(0x10000).toString(16)

    /** `Pictures/AkaStudioQA/<run-id>/`, in MediaStore's RELATIVE_PATH form (trailing slash). */
    val relativePath: String = "Pictures/AkaStudioQA/$runId/"

    /** The run folder on shared storage: MediaStore creates it for the first row moved in but never removes it. */
    private val runFolder: File? =
        context.getSystemService(StorageManager::class.java).primaryStorageVolume.directory?.let { File(it, relativePath) }

    private val created = mutableListOf<Uri>()

    /** Remembers [uri] for [cleanUp]. */
    fun track(uri: Uri) {
        created += uri
    }

    /** Moves the published row [uri] into this run's QA folder and returns the number of rows updated. */
    fun moveIntoRunFolder(uri: Uri): Int =
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath) }, null, null)

    fun row(uri: Uri): MediaRow? = query(uri, null, null).singleOrNull()

    /** Rows (pending included) currently in this run's QA folder. */
    fun rowsInRunFolder(): List<MediaRow> = query(
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
        arrayOf(relativePath),
    )

    /**
     * Deletes every tracked row, then the run folder if it is empty, and returns how many rows of this run are left
     * (tracked or in the QA folder).
     */
    fun cleanUp(): Int {
        for (uri in created) resolver.delete(uri, null, null)
        val leftTracked = created.count { row(it) != null }
        val left = leftTracked + rowsInRunFolder().size
        runFolder?.delete()
        return left
    }

    /** True while the run folder is still on shared storage (an empty folder left behind counts). */
    fun runFolderExists(): Boolean = runFolder?.exists() == true

    private fun query(uri: Uri, selection: String?, args: Array<String>?): List<MediaRow> {
        val queryArgs = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            if (selection != null) {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
            }
        }
        val rows = ArrayList<MediaRow>()
        resolver.query(uri, PROJECTION, queryArgs, null)?.use { c ->
            while (c.moveToNext()) {
                rows += MediaRow(
                    displayName = c.getString(0),
                    mimeType = c.getString(1),
                    relativePath = c.getString(2),
                    isPending = c.getInt(3),
                    size = c.getLong(4),
                    width = c.getInt(5),
                    height = c.getInt(6),
                    dateTaken = if (c.isNull(7)) null else c.getLong(7),
                )
            }
        }
        return rows
    }

    private companion object {
        val PROJECTION = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.IS_PENDING,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.DATE_TAKEN,
        )
    }
}
