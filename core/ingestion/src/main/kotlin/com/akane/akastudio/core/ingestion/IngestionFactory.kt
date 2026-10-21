package com.akane.akastudio.core.ingestion

import android.content.ContentResolver
import android.content.Context
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import androidx.core.net.toUri
import com.akane.akastudio.core.ingestion.api.AnalysisDecoder
import com.akane.akastudio.core.ingestion.api.IngestionSession
import com.akane.akastudio.core.ingestion.api.SourceOpener
import com.akane.akastudio.core.ingestion.api.ZipLimits
import com.akane.akastudio.core.ingestion.decode.SampledAnalysisDecoder
import com.akane.akastudio.core.ingestion.metadata.ExifAndGainMapParser
import com.akane.akastudio.core.ingestion.model.PhotoSource
import com.akane.akastudio.core.ingestion.picker.MediaPickerIngestor
import com.akane.akastudio.core.ingestion.pipeline.DefaultIngestionSession
import com.akane.akastudio.core.ingestion.zip.StreamingZipExtractor
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID

private const val DEFAULT_STALE_MILLIS = 24L * 60 * 60 * 1000
private const val INGEST_DIR = "ingest"

internal class ContentSourceOpener(private val resolver: ContentResolver) : SourceOpener {
    override fun open(source: PhotoSource): InputStream = when (source) {
        is PhotoSource.ContentUri ->
            resolver.openInputStream(source.uri.toUri()) ?: throw IOException("cannot open ${source.uri}")
        is PhotoSource.ExtractedFile -> FileInputStream(source.path)
    }
}

internal fun sourceSize(resolver: ContentResolver, source: PhotoSource): Long? = when (source) {
    is PhotoSource.ExtractedFile -> File(source.path).length()
    is PhotoSource.ContentUri -> try {
        resolver.query(source.uri.toUri(), arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) c.getLong(idx) else null
        }
    } catch (_: RuntimeException) {
        null
    }
}

fun createIngestionSession(context: Context, limits: ZipLimits = ZipLimits()): IngestionSession {
    val app = context.applicationContext
    val resolver = app.contentResolver
    val opener = ContentSourceOpener(resolver)
    val workDir = File(app.cacheDir, "$INGEST_DIR/${UUID.randomUUID()}").apply { mkdirs() }
    val storage = app.getSystemService(StorageManager::class.java)
    return DefaultIngestionSession(
        workDir = workDir,
        extractor = StreamingZipExtractor(limits, freeSpaceProvider = { allocatableBytes(storage, it) }),
        parser = ExifAndGainMapParser(opener, { sourceSize(resolver, it) }, contentResolver = resolver),
        picker = MediaPickerIngestor(resolver)::resolve,
        zipOpener = { uri -> opener.open(PhotoSource.ContentUri(uri, "")) },
    )
}

/**
 * Bytes the app can still claim on [dir]'s volume. Unlike [File.getUsableSpace], [StorageManager.getAllocatableBytes]
 * also counts cached data the system will clear on the app's behalf, so a nearly full device whose space is mostly
 * other apps' caches is not refused outright. The extractor still subtracts [ZipLimits.freeSpaceReserveBytes] from
 * this figure. A path on no known volume falls back to the plain usable space.
 */
private fun allocatableBytes(storage: StorageManager, dir: File): Long = try {
    storage.getAllocatableBytes(storage.getUuidForPath(dir))
} catch (_: IOException) {
    dir.usableSpace
}

fun createAnalysisDecoder(context: Context): AnalysisDecoder {
    val resolver = context.applicationContext.contentResolver
    return SampledAnalysisDecoder(resolver)
}

/** Deletes crash-leftover session dirs under cacheDir/ingest older than [olderThanMillis]. */
fun purgeStaleIngestionDirs(context: Context, olderThanMillis: Long = DEFAULT_STALE_MILLIS) {
    val root = File(context.applicationContext.cacheDir, INGEST_DIR)
    val dirs = root.listFiles { f -> f.isDirectory }?.toList() ?: return
    staleDirs(dirs, System.currentTimeMillis(), olderThanMillis).forEach { it.deleteRecursively() }
}

internal fun staleDirs(dirs: List<File>, now: Long, olderThan: Long): List<File> =
    dirs.filter { it.isDirectory && now - it.lastModified() > olderThan }
