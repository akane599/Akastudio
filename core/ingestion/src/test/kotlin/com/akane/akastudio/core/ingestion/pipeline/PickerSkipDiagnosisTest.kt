package com.akane.akastudio.core.ingestion.pipeline

import com.akane.akastudio.core.ingestion.api.ArchiveExtractor
import com.akane.akastudio.core.ingestion.api.IngestionRequest
import com.akane.akastudio.core.ingestion.api.MetadataParser
import com.akane.akastudio.core.ingestion.api.ParsedImage
import com.akane.akastudio.core.ingestion.api.SourceOpener
import com.akane.akastudio.core.ingestion.api.ZipExtractionEvent
import com.akane.akastudio.core.ingestion.model.CaptureMetadata
import com.akane.akastudio.core.ingestion.model.ImageFormat
import com.akane.akastudio.core.ingestion.model.IngestionEvent
import com.akane.akastudio.core.ingestion.model.IngestionTelemetry
import com.akane.akastudio.core.ingestion.model.PhotoSource
import com.akane.akastudio.core.ingestion.model.SkipReason
import com.akane.akastudio.core.ingestion.picker.PickedItem
import com.akane.akastudio.core.ingestion.picker.PickerItems
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * SQ-54: a PhotoPicker run of 12 valid JPEGs once reported "12 photos read, 1 skipped". The UI's "photos read" is
 * [IngestionTelemetry.filesRead], which only parse-stage outcomes advance. These tests pin which failure modes give
 * exactly that shape with 12 picked URIs.
 */
class PickerSkipDiagnosisTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val uris = (1..12).map { "content://media/picker/0/com.android.providers.media.photopicker/media/$it" }
    private val target = uris[4]

    @Test
    fun repeatedPickerUri_isDedupedByResolver_soNoSkip() = runTest {
        val opener = FakeOpener()
        val session = session(opener, picker = resolvingPicker)

        val telemetry = session.run(uris + target)

        assertEquals(IngestionTelemetry(sourcesTotal = 12, filesRead = 12, candidatesAccepted = 12), telemetry)
    }

    @Test
    fun duplicateReachingPipeline_readsElevenNotTwelve() = runTest {
        val logged = RecordingSkipLogger()
        val session = session(FakeOpener(), picker = rawPicker, logger = logged)

        val telemetry = session.run(uris.take(11) + uris[3])

        assertEquals(
            IngestionTelemetry(sourcesTotal = 12, filesRead = 11, candidatesAccepted = 11, skipped = 1),
            telemetry,
        )
        assertEquals(listOf(Logged("photo04.jpg", SkipReason.DUPLICATE, null)), logged.entries)
    }

    @Test
    fun openFailingOnceForOneContentUri_isRetriedAndAllTwelveAccepted() = runTest {
        val opener = FakeOpener(failOpens = mapOf(target to 1))
        val logged = RecordingSkipLogger()
        val session = session(opener, logger = logged)

        val telemetry = session.run(uris)

        assertEquals(IngestionTelemetry(sourcesTotal = 12, filesRead = 12, candidatesAccepted = 12), telemetry)
        assertEquals(2, opener.opens(target))
        assertTrue(logged.entries.isEmpty())
    }

    @Test
    fun openFailingTwice_isOneUnreadableSkipMatchingTheFieldReport_andLogsReason() = runTest {
        val opener = FakeOpener(failOpens = mapOf(target to 2))
        val logged = RecordingSkipLogger()
        val session = session(opener, logger = logged)

        val events = session.events(uris)

        assertEquals(
            IngestionTelemetry(sourcesTotal = 12, filesRead = 12, candidatesAccepted = 11, skipped = 1),
            (events.last() as IngestionEvent.Completed).telemetry,
        )
        val skipped = events.filterIsInstance<IngestionEvent.Skipped>().single()
        assertEquals("photo05.jpg" to SkipReason.UNREADABLE, skipped.displayName to skipped.reason)
        assertEquals(2, opener.opens(target))
        val entry = logged.entries.single()
        assertEquals("photo05.jpg" to SkipReason.UNREADABLE, entry.displayName to entry.reason)
        assertTrue(entry.cause is FileNotFoundException)
    }

    @Test
    fun emptyBytesForOneContentUri_isOneUndecodableSkip_notRetried() = runTest {
        val opener = FakeOpener(emptyReads = setOf(target))
        val logged = RecordingSkipLogger()
        val session = session(opener, logger = logged)

        val telemetry = session.run(uris)

        assertEquals(
            IngestionTelemetry(sourcesTotal = 12, filesRead = 12, candidatesAccepted = 11, skipped = 1),
            telemetry,
        )
        assertEquals(1, opener.opens(target))
        assertEquals(listOf(Logged("photo05.jpg", SkipReason.UNDECODABLE, null)), logged.entries)
    }

    @Test
    fun extractedFileOpenFailure_isNotRetried() = runTest {
        val path = "/work/e1.jpg"
        val opener = FakeOpener(failOpens = mapOf(path to 1))
        val extractor = ScriptedExtractor(
            flowOf(
                ZipExtractionEvent.EntryExtracted(
                    PhotoSource.ExtractedFile(path, archiveEntryName = "e1.jpg", displayName = "e1.jpg"),
                    ImageFormat.JPEG,
                    bytes = 10,
                ),
            ),
        )
        val logged = RecordingSkipLogger()
        val session = session(opener, logger = logged, extractor = extractor)

        val events = session.ingest(listOf(IngestionRequest.ZipArchive("content://zip/1", "trip.zip"))).toList()

        assertEquals(1, opener.opens(path))
        assertEquals(SkipReason.UNREADABLE, events.filterIsInstance<IngestionEvent.Skipped>().single().reason)
        assertEquals(SkipReason.UNREADABLE, logged.entries.single().reason)
    }

    private suspend fun DefaultIngestionSession.events(picked: List<String>): List<IngestionEvent> =
        ingest(listOf(IngestionRequest.PickedMedia(picked))).toList()

    private suspend fun DefaultIngestionSession.run(picked: List<String>): IngestionTelemetry =
        (events(picked).last() as IngestionEvent.Completed).telemetry

    private fun TestScope.session(
        opener: SourceOpener,
        picker: suspend (List<String>) -> List<PickedItem> = resolvingPicker,
        logger: SkipLogger = RecordingSkipLogger(),
        extractor: ArchiveExtractor = ScriptedExtractor(flowOf()),
    ) = DefaultIngestionSession(
        workDir = tmp.newFolder(),
        extractor = extractor,
        parser = OpenerBackedParser(opener),
        picker = picker,
        zipOpener = { ByteArrayInputStream(ByteArray(0)) },
        ioDispatcher = StandardTestDispatcher(testScheduler),
        skipLogger = logger,
    )

    /** Mirrors ExifAndGainMapParser's contract: open failures propagate, undecodable bytes return null. */
    private class OpenerBackedParser(private val opener: SourceOpener) : MetadataParser {
        override suspend fun parse(source: PhotoSource): ParsedImage? {
            val bytes = opener.open(source).use { it.readBytes() }
            if (bytes.isEmpty()) return null
            return ParsedImage(ImageFormat.JPEG, 4000, 3000, bytes.size.toLong(), CaptureMetadata.EMPTY)
        }
    }

    /** Opens fail with FileNotFoundException for the first [failOpens] attempts; [emptyReads] return no bytes. */
    private class FakeOpener(
        private val failOpens: Map<String, Int> = emptyMap(),
        private val emptyReads: Set<String> = emptySet(),
    ) : SourceOpener {
        private val counts = ConcurrentHashMap<String, AtomicInteger>()

        fun opens(key: String): Int = counts[key]?.get() ?: 0

        override fun open(source: PhotoSource): InputStream {
            val key = when (source) {
                is PhotoSource.ContentUri -> source.uri
                is PhotoSource.ExtractedFile -> source.path
            }
            val attempt = counts.getOrPut(key) { AtomicInteger() }.incrementAndGet()
            if (attempt <= (failOpens[key] ?: 0)) throw FileNotFoundException("not yet indexed: $key")
            return ByteArrayInputStream(if (key in emptyReads) ByteArray(0) else JPEG_STUB)
        }
    }

    private class ScriptedExtractor(private val events: Flow<ZipExtractionEvent>) : ArchiveExtractor {
        override fun extract(input: () -> InputStream, archiveIdentity: String, destinationDir: File) = events
    }

    private data class Logged(val displayName: String, val reason: SkipReason, val cause: Throwable?)

    private class RecordingSkipLogger : SkipLogger {
        val entries: MutableList<Logged> = Collections.synchronizedList(mutableListOf())

        override fun skipped(displayName: String, reason: SkipReason, cause: Throwable?) {
            entries += Logged(displayName, reason, cause)
        }
    }

    private companion object {
        val JPEG_STUB = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

        fun item(uri: String) = PickedItem(
            PhotoSource.ContentUri(uri, "photo%02d.jpg".format(uri.substringAfterLast('/').toInt())),
            ImageFormat.JPEG,
            100L,
        )

        /** Same URI handling as MediaPickerIngestor.resolve: identical strings are dropped first. */
        val resolvingPicker: suspend (List<String>) -> List<PickedItem> = { PickerItems.dedupe(it).map(::item) }

        /** A picker that would hand the pipeline every URI as given. */
        val rawPicker: suspend (List<String>) -> List<PickedItem> = { it.map(::item) }
    }
}
