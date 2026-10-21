package com.akane.akastudio.core.ingestion.pipeline

import com.akane.akastudio.core.ingestion.api.ArchiveExtractor
import com.akane.akastudio.core.ingestion.api.IngestionException
import com.akane.akastudio.core.ingestion.api.IngestionRequest
import com.akane.akastudio.core.ingestion.api.IngestionSession
import com.akane.akastudio.core.ingestion.api.MetadataParser
import com.akane.akastudio.core.ingestion.api.ParsedImage
import com.akane.akastudio.core.ingestion.api.ZipExtractionEvent
import com.akane.akastudio.core.ingestion.model.IngestionError
import com.akane.akastudio.core.ingestion.model.IngestionEvent
import com.akane.akastudio.core.ingestion.model.IngestionTelemetry
import com.akane.akastudio.core.ingestion.model.PhotoCandidate
import com.akane.akastudio.core.ingestion.model.PhotoSource
import com.akane.akastudio.core.ingestion.model.SkipReason
import com.akane.akastudio.core.ingestion.model.stableCandidateId
import com.akane.akastudio.core.ingestion.picker.PickedItem
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore

/**
 * Turns picker selections and zip archives into one cold [IngestionEvent] stream. Archives are extracted one at a
 * time and published only once complete; metadata parsing runs concurrently (at most [parseParallelism] at once)
 * and overlaps the extraction of later archives.
 *
 * Duplicates are detected across every [ingest] call of the session, so re-importing a photo or an archive adds
 * nothing. An extracted file that is skipped (duplicate, undecodable, unreadable) is deleted at once rather than
 * waiting for [close].
 */
class DefaultIngestionSession(
    override val workDir: File,
    private val extractor: ArchiveExtractor,
    private val parser: MetadataParser,
    private val picker: suspend (List<String>) -> List<PickedItem>,
    private val zipOpener: (String) -> InputStream,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val parseParallelism: Int = 4,
    private val skipLogger: SkipLogger = LogcatSkipLogger,
) : IngestionSession {

    private val closed = AtomicBoolean(false)

    /**
     * Source identities (picker URI, or archive URI + entry name) claimed by any run of this session. A run that
     * does not complete (cancelled or failed) releases its claims, so retrying the same import parses it again.
     */
    private val seenIdentities: MutableSet<String> = ConcurrentHashMap.newKeySet()

    init {
        require(parseParallelism >= 1) { "parseParallelism must be >= 1, was $parseParallelism" }
    }

    override fun ingest(requests: List<IngestionRequest>): Flow<IngestionEvent> {
        checkOpen()
        val pending = requests.toList()
        return channelFlow {
            checkOpen()
            IngestionRun(this).execute(pending)
        }.flowOn(ioDispatcher)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) workDir.deleteRecursively()
    }

    private fun checkOpen() = check(!closed.get()) { "IngestionSession is closed" }

    private inner class IngestionRun(private val out: ProducerScope<IngestionEvent>) {
        private val telemetry = TelemetryAccumulator()
        private val parseSlots = Semaphore(parseParallelism)

        /** This run's claims in [seenIdentities]. Confined to the producer coroutine, which enqueues sequentially. */
        private val claimed = ArrayList<String>()

        /** Mutated only inside [TelemetryAccumulator.record], i.e. under its lock. */
        private val accepted = ArrayList<PhotoCandidate>()

        suspend fun execute(requests: List<IngestionRequest>) {
            try {
                coroutineScope {
                    for (request in requests) {
                        when (request) {
                            is IngestionRequest.PickedMedia -> ingestPicked(request.uris)
                            is IngestionRequest.ZipArchive -> ingestZip(request)
                        }
                    }
                }
                telemetry.record({ it }) { snapshot ->
                    out.send(IngestionEvent.Completed(accepted.sortedWith(COMPLETION_ORDER), snapshot))
                }
            } catch (failure: Throwable) {
                seenIdentities.removeAll(claimed.toSet())
                throw failure
            }
        }

        private suspend fun CoroutineScope.ingestPicked(uris: List<String>) {
            val items = picker(uris)
            if (items.isEmpty()) return
            emitProgress { it.copy(sourcesTotal = it.sourcesTotal + items.size) }
            for (item in items) {
                if (item.format == null) {
                    skip(item.source.displayName, SkipReason.UNSUPPORTED_TYPE)
                } else {
                    enqueueParse(item.source, identity = item.source.uri)
                }
            }
        }

        /**
         * Fails closed: an archive contributes only once it has been read cleanly to its end. A whole-archive
         * failure (Zip Slip, bomb, limit, I/O) publishes nothing but [IngestionEvent.SourceFailed], so no entry
         * extracted before the bad one is counted, parsed or kept on disk.
         */
        private suspend fun CoroutineScope.ingestZip(request: IngestionRequest.ZipArchive) {
            val staged = try {
                extractStaged(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IngestionException) {
                return sourceFailed(request.displayName, e.error)
            } catch (e: Exception) {
                return sourceFailed(request.displayName, IngestionError.Io(e.message ?: e.javaClass.simpleName))
            }
            for (event in staged) {
                when (event) {
                    is ZipExtractionEvent.EntryExtracted -> {
                        emitProgress {
                            it.copy(sourcesTotal = it.sourcesTotal + 1, bytesExtracted = it.bytesExtracted + event.bytes)
                        }
                        enqueueParse(event.source, identity = archiveEntryIdentity(request.uri, event.source))
                    }
                    is ZipExtractionEvent.EntrySkipped -> skip(event.entryName, event.reason)
                }
            }
        }

        /**
         * Extracts into a fresh staging folder owned by this request alone (the same archive requested twice gets
         * two), buffering the events. Any failure, cancellation included, deletes that folder before propagating.
         */
        private suspend fun extractStaged(request: IngestionRequest.ZipArchive): List<ZipExtractionEvent> {
            workDir.mkdirs()
            val prefix = stableCandidateId(request.uri).take(ARCHIVE_DIR_HEX_CHARS) + "-"
            val staging = Files.createTempDirectory(workDir.toPath(), prefix).toFile()
            try {
                return extractor.extract({ zipOpener(request.uri) }, request.uri, staging).toList()
            } catch (failure: Throwable) {
                staging.deleteRecursively()
                throw failure
            }
        }

        /** Duplicates are rejected before parsing; otherwise waits for a parse slot (backpressure on extraction). */
        private suspend fun CoroutineScope.enqueueParse(source: PhotoSource, identity: String) {
            if (!seenIdentities.add(identity)) {
                discard(source)
                skip(source.displayName, SkipReason.DUPLICATE)
                return
            }
            claimed += identity
            parseSlots.acquire()
            launch {
                try {
                    parse(source, identity)
                } finally {
                    parseSlots.release()
                }
            }
        }

        private suspend fun parse(source: PhotoSource, identity: String) {
            val parsed = try {
                parseWithRetry(source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                discard(source)
                return parseSkipped(source.displayName, SkipReason.UNREADABLE, e)
            }
            if (parsed == null) {
                discard(source)
                return parseSkipped(source.displayName, SkipReason.UNDECODABLE)
            }

            val candidate = PhotoCandidate(
                id = stableCandidateId(identity),
                source = source,
                format = parsed.format,
                byteSize = parsed.byteSize,
                widthPx = parsed.widthPx,
                heightPx = parsed.heightPx,
                metadata = parsed.metadata,
            )
            telemetry.record({ it.copy(filesRead = it.filesRead + 1, candidatesAccepted = it.candidatesAccepted + 1) }) {
                accepted += candidate
                out.send(IngestionEvent.CandidateReady(candidate, it))
            }
        }

        /**
         * A content URI can fail to open transiently (e.g. while MediaProvider or the photo picker is still syncing
         * a just-added file), so it gets one retry after a short pause. Extracted files are local and never retried.
         */
        private suspend fun parseWithRetry(source: PhotoSource): ParsedImage? {
            if (source is PhotoSource.ContentUri) {
                try {
                    return parser.parse(source)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    delay(CONTENT_URI_RETRY_DELAY_MS)
                }
            }
            return parser.parse(source)
        }

        /** A skipped extracted file is never read again, so it leaves the session folder now rather than at close. */
        private fun discard(source: PhotoSource) {
            if (source is PhotoSource.ExtractedFile) File(source.path).delete()
        }

        private suspend fun emitProgress(change: (IngestionTelemetry) -> IngestionTelemetry) =
            telemetry.record(change) { out.send(IngestionEvent.Progress(it)) }

        private suspend fun skip(displayName: String, reason: SkipReason) =
            telemetry.record({ it.copy(skipped = it.skipped + 1) }) {
                skipLogger.skipped(displayName, reason, null)
                out.send(IngestionEvent.Skipped(displayName, reason, it))
            }

        private suspend fun parseSkipped(displayName: String, reason: SkipReason, cause: Throwable? = null) =
            telemetry.record({ it.copy(filesRead = it.filesRead + 1, skipped = it.skipped + 1) }) {
                skipLogger.skipped(displayName, reason, cause)
                out.send(IngestionEvent.Skipped(displayName, reason, it))
            }

        private suspend fun sourceFailed(displayName: String, error: IngestionError) =
            telemetry.record({ it }) { out.send(IngestionEvent.SourceFailed(displayName, error, it)) }
    }

    private companion object {
        const val ARCHIVE_DIR_HEX_CHARS = 16
        const val CONTENT_URI_RETRY_DELAY_MS = 250L

        val COMPLETION_ORDER: Comparator<PhotoCandidate> =
            compareBy<PhotoCandidate, Long?>(nullsLast()) { it.metadata.captureEpochMillis }
                .thenBy { it.source.displayName }

        fun archiveEntryIdentity(archiveUri: String, source: PhotoSource.ExtractedFile): String =
            archiveUri + "\u0000" + source.archiveEntryName
    }
}
