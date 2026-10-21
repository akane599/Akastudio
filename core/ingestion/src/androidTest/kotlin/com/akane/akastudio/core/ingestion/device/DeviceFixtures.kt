package com.akane.akastudio.core.ingestion.device

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.ExifInterface
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.akane.akastudio.core.ingestion.api.IngestionRequest
import com.akane.akastudio.core.ingestion.api.IngestionSession
import com.akane.akastudio.core.ingestion.model.IngestionEvent
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

internal const val MIB = 1024 * 1024
private const val INGEST_TIMEOUT_MS = 60_000L

internal val targetContext: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

/** A device-encoded JPEG: a two-colour gradient, so the encoder produces real entropy-coded data. */
internal fun deviceJpeg(width: Int, height: Int, from: Int, to: Int, quality: Int = 90): ByteArray {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val paint = Paint().apply {
        shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), from, to, Shader.TileMode.CLAMP)
    }
    Canvas(bitmap).drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    return try {
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
    } finally {
        bitmap.recycle()
    }
}

/** Writes [tags] into a copy of [jpeg] with the platform [ExifInterface] (not the androidx one the parser uses). */
internal fun withPlatformExif(jpeg: ByteArray, tags: Map<String, String>): ByteArray {
    val file = File.createTempFile("exif", ".jpg", targetContext.cacheDir)
    try {
        file.writeBytes(jpeg)
        val exif = ExifInterface(file.absolutePath)
        tags.forEach { (tag, value) -> exif.setAttribute(tag, value) }
        exif.saveAttributes()
        return file.readBytes()
    } finally {
        file.delete()
    }
}

/** Opens [bytes] with the platform [ExifInterface], to read back what was written. */
internal fun platformExif(bytes: ByteArray): ExifInterface = ExifInterface(bytes.inputStream())

/** Builds an archive with [ZipOutputStream]; a name ending in `/` becomes a directory entry. */
internal fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        for ((name, content) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content)
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

/** Logcat tag for measured values and observed errors, so the run's evidence can be read back from the device log. */
internal fun qaLog(message: String) {
    Log.i("AkaIngestionQA", message)
}

/**
 * Collects a whole ingestion run; fails the test instead of hanging when the flow never completes. Archive
 * failures and the final telemetry are logged as evidence.
 */
internal fun IngestionSession.collect(vararg requests: IngestionRequest): List<IngestionEvent> = runBlocking {
    withTimeout(INGEST_TIMEOUT_MS) { ingest(requests.toList()).toList() }
}.also { events ->
    events.failures().forEach { qaLog("SourceFailed ${it.displayName}: ${it.error}") }
    qaLog("Completed ${(events.lastOrNull() as? IngestionEvent.Completed)?.telemetry}")
}

internal fun List<IngestionEvent>.completed(): IngestionEvent.Completed = last() as IngestionEvent.Completed

internal fun List<IngestionEvent>.skips(): List<IngestionEvent.Skipped> = filterIsInstance<IngestionEvent.Skipped>()

internal fun List<IngestionEvent>.failures(): List<IngestionEvent.SourceFailed> =
    filterIsInstance<IngestionEvent.SourceFailed>()

/**
 * Rows this test run inserted into the shared MediaStore, all under `Pictures/AkaStudioQA/<runId>` or
 * `Download/AkaStudioQA/<runId>`. [deleteAll] removes every one of them, then those run folders.
 */
internal class QaMediaStore {
    val runId: String = "run-" + UUID.randomUUID().toString().take(8)
    private val resolver = targetContext.contentResolver
    private val inserted = mutableListOf<Uri>()

    /** The run folders on shared storage: MediaStore creates them on the first insert but never removes them. */
    private val runFolders: List<File> =
        targetContext.getSystemService(StorageManager::class.java).primaryStorageVolume.directory?.let { root ->
            listOf(Environment.DIRECTORY_PICTURES, Environment.DIRECTORY_DOWNLOADS).map { File(root, "$it/AkaStudioQA/$runId") }
        }.orEmpty()

    fun insertImage(displayName: String, bytes: ByteArray, dateTakenMillis: Long? = null): Uri =
        insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/AkaStudioQA/$runId")
                dateTakenMillis?.let { put(MediaStore.Images.Media.DATE_TAKEN, it) }
            },
            bytes,
        )

    fun insertDownload(displayName: String, bytes: ByteArray, mimeType: String): Uri =
        insert(
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/AkaStudioQA/$runId")
            },
            bytes,
        )

    private fun insert(collection: Uri, values: ContentValues, bytes: ByteArray): Uri {
        values.put(MediaStore.MediaColumns.IS_PENDING, 1)
        val uri = checkNotNull(resolver.insert(collection, values)) { "MediaStore insert refused: $values" }
        inserted += uri
        checkNotNull(resolver.openOutputStream(uri)) { "no output stream for $uri" }.use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    /**
     * Deletes every inserted row, then each run folder if it is empty, and returns how many rows were actually
     * deleted. Throws when a run folder is still on shared storage afterwards.
     */
    fun deleteAll(): Int {
        val deleted = inserted.sumOf { resolver.delete(it, null, null) }
        inserted.clear()
        runFolders.forEach(File::delete)
        val left = runFolders.filter(File::exists)
        check(left.isEmpty()) { "QA run folders left behind: $left" }
        return deleted
    }
}
