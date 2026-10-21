package com.akane.akastudio.core.ingestion.zip

import com.akane.akastudio.core.ingestion.api.ArchiveExtractor
import com.akane.akastudio.core.ingestion.api.IngestionException
import com.akane.akastudio.core.ingestion.api.ZipExtractionEvent
import com.akane.akastudio.core.ingestion.api.ZipLimits
import com.akane.akastudio.core.ingestion.model.IngestionError
import com.akane.akastudio.core.ingestion.model.PhotoSource
import com.akane.akastudio.core.ingestion.model.SkipReason
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Streams a zip archive into a flat destination directory, treating every byte and header as untrusted.
 *
 * Limits are enforced on bytes actually inflated, never on declared header sizes. Any failure, including
 * cancellation or a downstream that stops collecting early, deletes only the file being written at that moment.
 * Files already fully written are complete and belong to the consumer (the session's work dir owns their
 * lifetime). Nothing this collection did not create is ever deleted.
 */
class StreamingZipExtractor(
    private val limits: ZipLimits = ZipLimits(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val freeSpaceProvider: (File) -> Long = { it.usableSpace },
) : ArchiveExtractor {

    override fun extract(
        input: () -> InputStream,
        archiveIdentity: String,
        destinationDir: File,
    ): Flow<ZipExtractionEvent> = flow {
        ExtractionRun(limits, freeSpaceProvider, input, archiveIdentity, destinationDir, this).execute()
    }.flowOn(ioDispatcher)
}

private const val BUFFER_SIZE = 64 * 1024
private const val MIN_SPACE_ESTIMATE = 64L * 1024
private const val UNKNOWN_SIZE_ESTIMATE = 8L * 1024 * 1024
private const val SPACE_CHECK_INTERVAL = 8L * 1024 * 1024
private const val FALLBACK_NAME_CHARSET = "IBM437"

/** Android's ZipPathValidator (targetSdk >= 34) rejects unsafe names before our own guard sees them. */
private val unsafeEntryNameMessage = Regex("(?i)(invalid|unsafe)\\s+(zip\\s+)?entry\\s+(path|name)")

/** One collection of one archive. Holds all mutable state; never shared between collections. */
private class ExtractionRun(
    private val limits: ZipLimits,
    private val freeSpaceProvider: (File) -> Long,
    private val input: () -> InputStream,
    private val archiveIdentity: String,
    private val destinationDir: File,
    private val collector: FlowCollector<ZipExtractionEvent>,
) {
    private val buffer = ByteArray(BUFFER_SIZE)
    /** The file being written right now; the only file a failure deletes. */
    private var partialFile: Path? = null
    private val createdNames = HashSet<String>()
    private var downstreamFailure: Throwable? = null
    private lateinit var destinationRoot: Path
    private lateinit var compressed: CountingInputStream

    /** Bytes written to disk across all entries. */
    private var totalWritten = 0L

    /** Bytes inflated across all entries, written or drained; the ratio guard numerator. */
    private var totalInflated = 0L

    /** Entries already fully handled; a charset retry skips this many without re-emitting. */
    private var entriesHandled = 0

    /** Entries returned by `nextEntry` in the current pass. */
    private var passEntryIndex = 0

    suspend fun execute() {
        try {
            prepareDestination()
            if (!runPass(Charsets.UTF_8)) {
                val fallback = runCatching { Charset.forName(FALLBACK_NAME_CHARSET) }.getOrNull()
                if (fallback == null || !runPass(fallback)) {
                    throw IngestionException(IngestionError.InvalidArchive("malformed entry name"))
                }
            }
        } catch (failure: Throwable) {
            deletePartialFile()
            throw toIngestionFailure(failure)
        }
    }

    private fun prepareDestination() {
        if (!destinationDir.isDirectory && !destinationDir.mkdirs() && !destinationDir.isDirectory) {
            throw IngestionException(IngestionError.Io("cannot create ${destinationDir.name}"))
        }
        destinationRoot = destinationDir.canonicalFile.toPath()
    }

    /** Returns false when an entry name is not valid in [charset], so the caller can retry once. */
    private suspend fun runPass(charset: Charset): Boolean {
        passEntryIndex = 0
        compressed = CountingInputStream(input(), currentCoroutineContext()[Job], limits.maxTotalBytes)
        ZipInputStream(BufferedInputStream(compressed, BUFFER_SIZE), charset).use { zip ->
            while (true) {
                val entry = try {
                    zip.nextEntry
                } catch (malformedName: IllegalArgumentException) {
                    return false
                } ?: break
                passEntryIndex++
                if (passEntryIndex <= entriesHandled) {
                    drain(zip, countTowardRatio = false)
                    continue
                }
                handleEntry(zip, entry)
                entriesHandled = passEntryIndex
            }
        }
        if (passEntryIndex == 0) throw IngestionException(IngestionError.InvalidArchive("empty archive"))
        return true
    }

    private suspend fun handleEntry(zip: ZipInputStream, entry: ZipEntry) {
        if (passEntryIndex > limits.maxEntries) {
            fail(IngestionError.LimitExceeded("more than ${limits.maxEntries} entries"))
        }
        val name = entry.name
        ArchiveEntryRules.nameViolation(name, limits)?.let(::fail)
        when (val classification = ArchiveEntryRules.classify(name, entry.isDirectory)) {
            is EntryClassification.Skip -> skip(zip, name, classification.reason)
            is EntryClassification.Image -> extractImage(zip, entry, classification)
        }
    }

    private suspend fun skip(zip: ZipInputStream, name: String, reason: SkipReason) {
        emit(ZipExtractionEvent.EntrySkipped(name, reason))
        drain(zip, countTowardRatio = true)
    }

    private suspend fun extractImage(zip: ZipInputStream, entry: ZipEntry, image: EntryClassification.Image) {
        val name = entry.name
        val outName = ArchiveEntryRules.outputFileName(archiveIdentity, name, image.fileName)
        if (outName in createdNames) return skip(zip, name, SkipReason.DUPLICATE)
        val outFile = File(destinationDir, outName)
        if (!outFile.canonicalFile.toPath().startsWith(destinationRoot)) fail(IngestionError.ZipSlip(name))
        requireSpace(spaceEstimate(entry.size, written = 0))
        val path = outFile.toPath()
        val bytes = Files.newOutputStream(path, CREATE_NEW, WRITE).use { out ->
            partialFile = path
            createdNames.add(outName)
            copyBounded(zip, out, entry)
        }
        partialFile = null
        val source = PhotoSource.ExtractedFile(
            path = outFile.absolutePath,
            archiveEntryName = name,
            displayName = ArchiveEntryRules.displayName(image.fileName),
        )
        emit(ZipExtractionEvent.EntryExtracted(source, image.format, bytes))
    }

    /** A downstream exception is the collector's own and must reach it unchanged (flow exception transparency). */
    private suspend fun emit(event: ZipExtractionEvent) {
        try {
            collector.emit(event)
        } catch (downstream: Throwable) {
            downstreamFailure = downstream
            throw downstream
        }
    }

    private suspend fun copyBounded(zip: ZipInputStream, out: OutputStream, entry: ZipEntry): Long {
        var entryBytes = 0L
        var sinceSpaceCheck = 0L
        while (true) {
            val read = zip.read(buffer)
            if (read < 0) return entryBytes
            entryBytes += read
            totalWritten += read
            totalInflated += read
            if (entryBytes > limits.maxEntryBytes) {
                fail(IngestionError.LimitExceeded("entry larger than ${limits.maxEntryBytes} bytes"))
            }
            if (totalWritten > limits.maxTotalBytes) {
                fail(IngestionError.LimitExceeded("archive larger than ${limits.maxTotalBytes} bytes"))
            }
            checkRatio()
            currentCoroutineContext().ensureActive()
            out.write(buffer, 0, read)
            sinceSpaceCheck += read
            if (sinceSpaceCheck >= SPACE_CHECK_INTERVAL) {
                sinceSpaceCheck = 0
                requireSpace(spaceEstimate(entry.size, entryBytes))
            }
        }
    }

    /**
     * Reads a skipped entry to its end ourselves rather than letting `nextEntry` do it, so a bomb hidden in a
     * skipped entry still hits the ratio guard and cancellation stays responsive.
     */
    private suspend fun drain(zip: ZipInputStream, countTowardRatio: Boolean) {
        while (true) {
            val read = zip.read(buffer)
            if (read < 0) return
            if (countTowardRatio) {
                totalInflated += read
                checkRatio()
            }
            currentCoroutineContext().ensureActive()
        }
    }

    /** Compressed bytes are counted under the read-ahead buffer, so they never trail actual archive reads. */
    private fun checkRatio() {
        if (totalInflated <= limits.ratioGraceBytes) return
        val ratio = totalInflated.toDouble() / maxOf(1L, compressed.count)
        if (ratio > limits.maxCompressionRatio) {
            fail(IngestionError.ZipBomb("compression ratio ${ratio.toLong()}:1 exceeds ${limits.maxCompressionRatio}:1"))
        }
    }

    private fun spaceEstimate(declaredSize: Long, written: Long): Long =
        if (declaredSize >= 0) maxOf(declaredSize - written, MIN_SPACE_ESTIMATE) else UNKNOWN_SIZE_ESTIMATE

    private fun requireSpace(needed: Long) {
        val available = freeSpaceProvider(destinationDir) - limits.freeSpaceReserveBytes
        if (available < needed) fail(IngestionError.InsufficientStorage(needed, maxOf(0L, available)))
    }

    private fun deletePartialFile() {
        partialFile?.let { path -> runCatching { Files.deleteIfExists(path) } }
        partialFile = null
    }

    /** Cancellation and errors (such as OOM) pass through; everything else becomes an [IngestionException]. */
    private fun toIngestionFailure(failure: Throwable): Throwable {
        if (failure === downstreamFailure || failure is IngestionException || failure is CancellationException) {
            return failure
        }
        if (failure !is IOException && failure !is RuntimeException) return failure
        val message = failure.message ?: failure.javaClass.simpleName
        val error = when {
            failure is ZipException && unsafeEntryNameMessage.containsMatchIn(message) ->
                IngestionError.ZipSlip(message.substringAfter(": ", message))
            failure is ZipException || failure is EOFException -> IngestionError.InvalidArchive(message)
            else -> IngestionError.Io(message)
        }
        return IngestionException(error)
    }

    private fun fail(error: IngestionError): Nothing = throw IngestionException(error)
}

/**
 * Counts bytes read from the raw archive stream. `InflaterInputStream.read` keeps pulling input for as long as
 * the inflater produces no output, so a stream of empty deflate blocks never returns to the copy loop. This
 * stream is therefore where cancellation is honoured and where the compressed size is capped at [maxBytes]
 * ([ZipLimits.maxTotalBytes]: an archive is never allowed to be larger than its whole inflated budget).
 */
private class CountingInputStream(
    source: InputStream,
    private val job: Job?,
    private val maxBytes: Long,
) : FilterInputStream(source) {
    var count = 0L
        private set

    override fun read(): Int {
        job?.ensureActive()
        return super.read().also { if (it >= 0) consumed(1) }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        job?.ensureActive()
        return super.read(b, off, len).also { if (it > 0) consumed(it.toLong()) }
    }

    override fun skip(n: Long): Long {
        job?.ensureActive()
        return super.skip(n).also { consumed(it) }
    }

    override fun markSupported(): Boolean = false

    private fun consumed(bytes: Long) {
        count += bytes
        if (count > maxBytes) {
            throw IngestionException(IngestionError.LimitExceeded("archive larger than $maxBytes compressed bytes"))
        }
    }
}
