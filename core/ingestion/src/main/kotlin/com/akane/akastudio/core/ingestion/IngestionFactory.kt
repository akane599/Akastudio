package com.akane.akastudio.core.ingestion

import android.content.ContentResolver
import android.content.Context
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
    val workDir = File(ingestRoot(app), UUID.randomUUID().toString()).apply { mkdirs() }
    return DefaultIngestionSession(
        workDir = workDir,
        extractor = StreamingZipExtractor(limits, freeSpaceProvider = ::usableBytes),
        parser = ExifAndGainMapParser(opener, { sourceSize(resolver, it) }, contentResolver = resolver),
        picker = MediaPickerIngestor(resolver)::resolve,
        zipOpener = { uri -> opener.open(PhotoSource.ContentUri(uri, "")) },
    )
}

/**
 * Staging root for session work dirs: `noBackupFilesDir/ingest`. Extracted zip entries are read again long after
 * extraction (decoders, the preparer, full-res export), so they must not live in cacheDir, where the system may
 * delete them under storage pressure mid-session. noBackupFilesDir is app-private, never evicted and never backed up;
 * sessions delete their own dir on close and [purgeStaleIngestionDirs] sweeps what a killed process left behind.
 */
private fun ingestRoot(app: Context): File = File(app.noBackupFilesDir, INGEST_DIR)

/** Where builds before N-06 staged sessions; nothing uses it any more, so the startup purge removes it whole. */
private fun legacyIngestRoot(app: Context): File = File(app.cacheDir, INGEST_DIR)

/**
 * Bytes actually free on [dir]'s volume, i.e. the internal app storage holding the work dir.
 * `StorageManager.getAllocatableBytes` would also count other apps' cache, which only `allocateBytes` frees, and the
 * extractor never calls that, so the figure could promise space a write cannot get. The extractor checks the real
 * free space and reports a shortfall up front; the user can free space and retry. [ZipLimits.freeSpaceReserveBytes]
 * is still subtracted from this figure.
 */
private fun usableBytes(dir: File): Long = dir.usableSpace

fun createAnalysisDecoder(context: Context): AnalysisDecoder {
    val resolver = context.applicationContext.contentResolver
    return SampledAnalysisDecoder(resolver)
}

/**
 * Deletes crash-leftover session dirs under noBackupFilesDir/ingest older than [olderThanMillis], and the whole legacy
 * cacheDir/ingest root that builds before N-06 staged into, so an upgrade leaves no orphaned photos behind.
 */
fun purgeStaleIngestionDirs(context: Context, olderThanMillis: Long = DEFAULT_STALE_MILLIS) {
    val app = context.applicationContext
    purgeIngestionRoots(ingestRoot(app), legacyIngestRoot(app), System.currentTimeMillis(), olderThanMillis)
}

/** Pure part of [purgeStaleIngestionDirs]: stale session dirs under [root] go, and [legacyRoot] goes entirely. */
internal fun purgeIngestionRoots(root: File, legacyRoot: File, now: Long, olderThan: Long) {
    if (legacyRoot.exists()) legacyRoot.deleteRecursively()
    val dirs = root.listFiles { f -> f.isDirectory }?.toList() ?: return
    staleDirs(dirs, now, olderThan).forEach { it.deleteRecursively() }
}

internal fun staleDirs(dirs: List<File>, now: Long, olderThan: Long): List<File> =
    dirs.filter { it.isDirectory && now - it.lastModified() > olderThan }
