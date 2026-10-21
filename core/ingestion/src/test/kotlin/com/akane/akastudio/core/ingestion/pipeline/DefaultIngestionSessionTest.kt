package com.akane.akastudio.core.ingestion.pipeline

import com.akane.akastudio.core.ingestion.api.ArchiveExtractor
import com.akane.akastudio.core.ingestion.api.IngestionException
import com.akane.akastudio.core.ingestion.api.IngestionRequest
import com.akane.akastudio.core.ingestion.api.MetadataParser
import com.akane.akastudio.core.ingestion.api.ParsedImage
import com.akane.akastudio.core.ingestion.api.ZipExtractionEvent
import com.akane.akastudio.core.ingestion.model.CaptureMetadata
import com.akane.akastudio.core.ingestion.model.ImageFormat
import com.akane.akastudio.core.ingestion.model.IngestionError
import com.akane.akastudio.core.ingestion.model.IngestionEvent
import com.akane.akastudio.core.ingestion.model.IngestionTelemetry
import com.akane.akastudio.core.ingestion.model.PhotoSource
import com.akane.akastudio.core.ingestion.model.SkipReason
import com.akane.akastudio.core.ingestion.model.stableCandidateId
import com.akane.akastudio.core.ingestion.picker.PickedItem
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DefaultIngestionSessionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun pickerOnly_unsupportedMimeIsSkippedAndOthersBecomeCandidates() = runTest {
        val items = listOf(picked("a"), picked("b", ImageFormat.PNG), picked("c", format = null))
        val session = session(pickerItems = items)

        val events = session.ingest(listOf(IngestionRequest.PickedMedia(items.map { it.source.uri }))).toList()

        assertEquals(2, events.filterIsInstance<IngestionEvent.CandidateReady>().size)
        val skipped = events.filterIsInstance<IngestionEvent.Skipped>()
        assertEquals(listOf("c" to SkipReason.UNSUPPORTED_TYPE), skipped.map { it.displayName to it.reason })
        val completed = events.last() as IngestionEvent.Completed
        assertEquals(
            IngestionTelemetry(sourcesTotal = 3, filesRead = 2, candidatesAccepted = 2, skipped = 1),
            completed.telemetry,
        )
        assertEquals(listOf("a", "b"), completed.candidates.map { it.source.displayName })
        assertEquals(stableCandidateId("content://m/a"), completed.candidates.first().id)
    }

    @Test
    fun zip_extractedEntriesCountAsSourcesAndSkippedEntriesOnlyAsSkipped() = runTest {
        val uri = "content://zip/1"
        val extractor = FakeExtractor(
            uri to flowOf(
                extracted("e1", 100),
                ZipExtractionEvent.EntrySkipped("folder/", SkipReason.DIRECTORY),
                ZipExtractionEvent.EntrySkipped("__MACOSX/._e1", SkipReason.JUNK_FILE),
                ZipExtractionEvent.EntrySkipped(".hidden.jpg", SkipReason.HIDDEN_FILE),
                extracted("e2", 250),
            ),
        )
        val opened = Collections.synchronizedList(mutableListOf<String>())
        val session = session(extractor = extractor, zipOpener = { opened += it; ByteArrayInputStream(ByteArray(0)) })

        val events = session.ingest(listOf(IngestionRequest.ZipArchive(uri, "Trip.zip"))).toList()

        val completed = events.last() as IngestionEvent.Completed
        assertEquals(
            IngestionTelemetry(sourcesTotal = 2, filesRead = 2, candidatesAccepted = 2, skipped = 3, bytesExtracted = 350),
            completed.telemetry,
        )
        assertEquals(3, events.filterIsInstance<IngestionEvent.Skipped>().size)
        assertEquals(stableCandidateId(uri + "\u0000" + "dir/e1"), completed.candidates.single { it.source.displayName == "e1" }.id)
        val call = extractor.calls.single()
        assertEquals(uri, call.archiveIdentity)
        assertEquals(session.workDir, call.destinationDir.parentFile)
        assertTrue(call.destinationDir.name, call.destinationDir.name.startsWith(stableCandidateId(uri).take(16) + "-"))
        assertTrue("destination dir is created before extraction", call.destinationExisted)
        assertEquals(listOf(uri), opened)
    }

    @Test
    fun zipSlip_reportsSourceFailedAndLaterRequestsStillComplete() = runTest {
        val badUri = "content://zip/bad"
        val extractor = FakeExtractor(
            badUri to flow { throw IngestionException(IngestionError.ZipSlip("../evil.jpg")) },
        )
        val items = listOf(picked("a"), picked("b"))
        val session = session(extractor = extractor, pickerItems = items)

        val events = session.ingest(
            listOf(
                IngestionRequest.ZipArchive(badUri, "Bad.zip"),
                IngestionRequest.PickedMedia(items.map { it.source.uri }),
            ),
        ).toList()

        val failed = events.filterIsInstance<IngestionEvent.SourceFailed>().single()
        assertEquals("Bad.zip", failed.displayName)
        assertEquals(IngestionError.ZipSlip("../evil.jpg"), failed.error)
        assertEquals(2, events.filterIsInstance<IngestionEvent.CandidateReady>().size)
        assertEquals(2, (events.last() as IngestionEvent.Completed).candidates.size)
    }

    @Test
    fun zipUnexpectedException_becomesIoSourceFailed() = runTest {
        val uri = "content://zip/io"
        val extractor = FakeExtractor(uri to flow { throw IOException("disk went away") })
        val session = session(extractor = extractor)

        val events = session.ingest(listOf(IngestionRequest.ZipArchive(uri, "Io.zip"))).toList()

        val failed = events.filterIsInstance<IngestionEvent.SourceFailed>().single()
        assertEquals(IngestionError.Io("disk went away"), failed.error)
        assertTrue(events.last() is IngestionEvent.Completed)
    }

    @Test
    fun parserNullIsUndecodableAndParserThrowIsUnreadable() = runTest {
        val items = listOf(picked("blank"), picked("broken"))
        val parser = FakeParser(result = { source ->
            when (source.displayName) {
                "broken" -> throw IOException("truncated")
                else -> null
            }
        })
        val session = session(parser = parser, pickerItems = items)

        val events = session.ingest(listOf(IngestionRequest.PickedMedia(items.map { it.source.uri }))).toList()

        val reasons = events.filterIsInstance<IngestionEvent.Skipped>().associate { it.displayName to it.reason }
        assertEquals(mapOf("blank" to SkipReason.UNDECODABLE, "broken" to SkipReason.UNREADABLE), reasons)
        assertEquals(
            IngestionTelemetry(sourcesTotal = 2, filesRead = 2, candidatesAccepted = 0, skipped = 2),
            (events.last() as IngestionEvent.Completed).telemetry,
        )
    }

    @Test
    fun sameUriAcrossTwoRequests_isSkippedAsDuplicateBeforeParse() = runTest {
        val a = picked("a")
        val b = picked("b")
        val parser = FakeParser()
        val session = session(parser = parser, pickerItems = listOf(a, b))

        val events = session.ingest(
            listOf(
                IngestionRequest.PickedMedia(listOf(a.source.uri)),
                IngestionRequest.PickedMedia(listOf(a.source.uri, b.source.uri)),
            ),
        ).toList()

        val duplicates = events.filterIsInstance<IngestionEvent.Skipped>().filter { it.reason == SkipReason.DUPLICATE }
        assertEquals(listOf("a"), duplicates.map { it.displayName })
        assertEquals(listOf("a", "b"), parser.calls.map { it.displayName }.sorted())
        val completed = events.last() as IngestionEvent.Completed
        assertEquals(listOf("a", "b"), completed.candidates.map { it.source.displayName })
        assertEquals(
            IngestionTelemetry(sourcesTotal = 3, filesRead = 2, candidatesAccepted = 2, skipped = 1),
            completed.telemetry,
        )
    }

    @Test
    fun sameUriInALaterIngestCall_isSkippedAsDuplicateWithoutParsingAgain() = runTest {
        val a = picked("a")
        val parser = FakeParser()
        val session = session(parser = parser, pickerItems = listOf(a))
        session.ingest(listOf(IngestionRequest.PickedMedia(listOf(a.source.uri)))).toList()

        val events = session.ingest(listOf(IngestionRequest.PickedMedia(listOf(a.source.uri)))).toList()

        assertEquals(
            listOf("a" to SkipReason.DUPLICATE),
            events.filterIsInstance<IngestionEvent.Skipped>().map { it.displayName to it.reason },
        )
        assertEquals(emptyList<Any>(), (events.last() as IngestionEvent.Completed).candidates)
        assertEquals(1, parser.calls.size)
    }

    @Test
    fun aCancelledRunReleasesItsClaims_soTheSameImportIsParsedAgain() = runTest {
        val a = picked("a")
        val parser = FakeParser(delayMs = { 1_000L })
        val session = session(parser = parser, pickerItems = listOf(a))
        val request = listOf(IngestionRequest.PickedMedia(listOf(a.source.uri)))

        val job = launch { session.ingest(request).toList() }
        advanceTimeBy(500L)
        assertEquals("the first parse is in flight", 1, parser.calls.size)
        job.cancel()
        job.join()
        val events = session.ingest(request).toList()

        assertEquals(listOf("a"), (events.last() as IngestionEvent.Completed).candidates.map { it.source.displayName })
        assertEquals(2, parser.calls.size)
    }

    @Test
    fun completedIsLast_sortedByCaptureTimeThenName_andTelemetryIsMonotonic() = runTest {
        val epochs = mapOf("c" to 2_000L, "a" to null, "b" to 1_000L, "d" to 1_000L, "e" to null)
        val items = epochs.keys.map { picked(it) }
        // Later names finish first so arrival order differs from the expected sort.
        val delays = mapOf("c" to 50L, "a" to 40L, "b" to 30L, "d" to 20L, "e" to 10L)
        val parser = FakeParser(
            delayMs = { delays.getValue(it.displayName) },
            result = { parsed(epochs.getValue(it.displayName)) },
        )
        val session = session(parser = parser, pickerItems = items, parallelism = 5)

        val events = session.ingest(listOf(IngestionRequest.PickedMedia(items.map { it.source.uri }))).toList()

        assertEquals(1, events.count { it is IngestionEvent.Completed })
        val completed = events.last() as IngestionEvent.Completed
        assertEquals(listOf("b", "d", "c", "a", "e"), completed.candidates.map { it.source.displayName })
        events.map { it.telemetry }.zipWithNext().forEach { (before, after) ->
            assertTrue("sourcesTotal regressed: $before -> $after", after.sourcesTotal >= before.sourcesTotal)
            assertTrue("filesRead regressed: $before -> $after", after.filesRead >= before.filesRead)
            assertTrue("accepted regressed: $before -> $after", after.candidatesAccepted >= before.candidatesAccepted)
            assertTrue("skipped regressed: $before -> $after", after.skipped >= before.skipped)
            assertTrue("bytes regressed: $before -> $after", after.bytesExtracted >= before.bytesExtracted)
        }
        val ready = events.filterIsInstance<IngestionEvent.CandidateReady>()
        assertEquals((1..5).toList(), ready.map { it.telemetry.candidatesAccepted })
    }

    @Test
    fun parseConcurrencyNeverExceedsParallelism() = runTest {
        val items = (1..10).map { picked("p$it") }
        val parser = FakeParser(delayMs = { 100 })
        val session = session(parser = parser, pickerItems = items, parallelism = 3)

        val events = session.ingest(listOf(IngestionRequest.PickedMedia(items.map { it.source.uri }))).toList()

        assertEquals(3, parser.maxInFlight.get())
        assertEquals(10, (events.last() as IngestionEvent.Completed).candidates.size)
    }

    @Test
    fun cancellationMidRun_emitsNoCompletedRunsExtractorCleanupAndDropsTheStagedArchive() = runTest {
        val uri = "content://zip/slow"
        var midFlight = false
        var cleanedUp = false
        val extractor = FakeExtractor(
            uri to flow {
                try {
                    emit(extracted("first", 10))
                    midFlight = true
                    awaitCancellation()
                } finally {
                    cleanedUp = true
                }
            },
        )
        val session = session(extractor = extractor)
        val events = mutableListOf<IngestionEvent>()

        val job = launch { session.ingest(listOf(IngestionRequest.ZipArchive(uri, "Slow.zip"))).collect { events += it } }
        advanceUntilIdle()
        assertTrue("run should be mid-flight", midFlight)
        assertTrue("an unfinished archive publishes nothing: $events", events.isEmpty())
        job.cancel()
        job.join()

        assertFalse(events.any { it is IngestionEvent.Completed })
        assertTrue("extractor cleanup must run on cancellation", cleanedUp)
        assertFalse("staging folder deleted on cancellation", extractor.calls.single().destinationDir.exists())
    }

    @Test
    fun close_deletesWorkDirIsIdempotentAndBlocksFurtherIngest() = runTest {
        val session = session()
        File(session.workDir, "abc/photo.jpg").apply { parentFile!!.mkdirs() }.writeText("x")
        val createdBeforeClose = session.ingest(emptyList())

        session.close()
        session.close()

        assertFalse(session.workDir.exists())
        assertThrows(IllegalStateException::class.java) { session.ingest(emptyList()) }
        val lateCollect = runCatching { createdBeforeClose.toList() }
        assertTrue(lateCollect.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun emptyRequests_completeWithEmptyTelemetry() = runTest {
        val events = session().ingest(emptyList()).toList()

        assertEquals(listOf(IngestionEvent.Completed(emptyList(), IngestionTelemetry())), events)
    }

    private fun TestScope.session(
        parser: MetadataParser = FakeParser(),
        extractor: ArchiveExtractor = FakeExtractor(),
        pickerItems: List<PickedItem> = emptyList(),
        zipOpener: (String) -> java.io.InputStream = { ByteArrayInputStream(ByteArray(0)) },
        parallelism: Int = 4,
    ): DefaultIngestionSession {
        val byUri = pickerItems.associateBy { it.source.uri }
        return DefaultIngestionSession(
            workDir = tmp.newFolder(),
            extractor = extractor,
            parser = parser,
            picker = { uris -> uris.map { byUri.getValue(it) } },
            zipOpener = zipOpener,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            parseParallelism = parallelism,
        )
    }

    private class FakeParser(
        private val delayMs: (PhotoSource) -> Long = { 0L },
        private val result: (PhotoSource) -> ParsedImage? = { parsed(null) },
    ) : MetadataParser {
        val calls: MutableList<PhotoSource> = Collections.synchronizedList(mutableListOf())
        val maxInFlight = AtomicInteger()
        private val inFlight = AtomicInteger()

        override suspend fun parse(source: PhotoSource): ParsedImage? {
            calls += source
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            try {
                delay(delayMs(source))
                return result(source)
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    private class FakeExtractor(vararg scripts: Pair<String, Flow<ZipExtractionEvent>>) : ArchiveExtractor {
        data class Call(val archiveIdentity: String, val destinationDir: File, val destinationExisted: Boolean)

        private val scripts = scripts.toMap()
        val calls: MutableList<Call> = Collections.synchronizedList(mutableListOf())

        override fun extract(
            input: () -> java.io.InputStream,
            archiveIdentity: String,
            destinationDir: File,
        ): Flow<ZipExtractionEvent> = flow {
            calls += Call(archiveIdentity, destinationDir, destinationDir.isDirectory)
            input().close()
            emitAll(scripts.getValue(archiveIdentity))
        }
    }

    private companion object {
        fun picked(name: String, format: ImageFormat? = ImageFormat.JPEG) =
            PickedItem(PhotoSource.ContentUri("content://m/$name", name), format, 100L)

        fun extracted(name: String, bytes: Long) = ZipExtractionEvent.EntryExtracted(
            PhotoSource.ExtractedFile(path = "/work/$name", archiveEntryName = "dir/$name", displayName = name),
            ImageFormat.JPEG,
            bytes,
        )

        fun parsed(epochMillis: Long?) = ParsedImage(
            format = ImageFormat.JPEG,
            widthPx = 4000,
            heightPx = 3000,
            byteSize = 1234L,
            metadata = CaptureMetadata.EMPTY.copy(captureEpochMillis = epochMillis),
        )

        val IngestionEvent.telemetry: IngestionTelemetry
            get() = when (this) {
                is IngestionEvent.Progress -> telemetry
                is IngestionEvent.CandidateReady -> telemetry
                is IngestionEvent.Skipped -> telemetry
                is IngestionEvent.SourceFailed -> telemetry
                is IngestionEvent.Completed -> telemetry
            }
    }
}
