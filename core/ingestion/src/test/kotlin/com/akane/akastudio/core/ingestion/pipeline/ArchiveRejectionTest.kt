package com.akane.akastudio.core.ingestion.pipeline

import com.akane.akastudio.core.ingestion.api.IngestionRequest
import com.akane.akastudio.core.ingestion.api.MetadataParser
import com.akane.akastudio.core.ingestion.api.ParsedImage
import com.akane.akastudio.core.ingestion.api.ZipLimits
import com.akane.akastudio.core.ingestion.model.CaptureMetadata
import com.akane.akastudio.core.ingestion.model.ImageFormat
import com.akane.akastudio.core.ingestion.model.IngestionError
import com.akane.akastudio.core.ingestion.model.IngestionEvent
import com.akane.akastudio.core.ingestion.model.IngestionTelemetry
import com.akane.akastudio.core.ingestion.model.PhotoSource
import com.akane.akastudio.core.ingestion.model.SkipReason
import com.akane.akastudio.core.ingestion.picker.PickedItem
import com.akane.akastudio.core.ingestion.zip.MIB
import com.akane.akastudio.core.ingestion.zip.StreamingZipExtractor
import com.akane.akastudio.core.ingestion.zip.plentyOfSpace
import com.akane.akastudio.core.ingestion.zip.randomBytes
import com.akane.akastudio.core.ingestion.zip.zipOf
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Collections
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
 * Fail closed: an archive rejected as a whole contributes nothing, even entries it extracted cleanly before the
 * bad one. Real archives through the real [StreamingZipExtractor]; only metadata parsing is faked.
 */
class ArchiveRejectionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val good = "trip/good.jpg" to randomBytes(2_000, seed = 1)

    @Test
    fun zipSlipAfterAGoodEntry_withdrawsItAndDeletesItsFileBeforeClose() = runTest {
        val slip = zipOf(good, "../evil.jpg" to randomBytes(500, seed = 2))
        val (session, parser) = session(mapOf(SLIP to slip))

        val events = session.ingest(listOf(IngestionRequest.ZipArchive(SLIP, "slip.zip"))).toList()

        assertEquals(IngestionError.ZipSlip("../evil.jpg"), events.failures().single().error)
        assertWithdrawn(events, session, parser)
    }

    @Test
    fun bombAfterThreeGoodEntries_withdrawsThemAndDeletesTheirFilesBeforeClose() = runTest {
        val goods = (1..3).map { "trip/good$it.jpg" to randomBytes(2_000, seed = it.toLong()) }
        val bomb = zipOf(*goods.toTypedArray(), "trip/zeros.jpg" to ByteArray(16 * MIB))
        val (session, parser) = session(mapOf(BOMB to bomb))

        val events = session.ingest(listOf(IngestionRequest.ZipArchive(BOMB, "bomb.zip"))).toList()

        assertTrue("${events.failures()}", events.failures().single().error is IngestionError.ZipBomb)
        assertWithdrawn(events, session, parser)
    }

    @Test
    fun entryCountLimitAfterGoodEntries_withdrawsThem() = runTest {
        val many = zipOf(good, "trip/two.jpg" to randomBytes(900, 3), "trip/three.jpg" to randomBytes(900, 4))
        val (session, parser) = session(mapOf(MANY to many), ZipLimits(maxEntries = 2))

        val events = session.ingest(listOf(IngestionRequest.ZipArchive(MANY, "many.zip"))).toList()

        assertTrue("${events.failures()}", events.failures().single().error is IngestionError.LimitExceeded)
        assertWithdrawn(events, session, parser)
    }

    @Test
    fun mixedSession_keepsTheGoodZipAndPickerPhotos_andACorruptEntryIsStillOnlySkipped() = runTest {
        val goodZip = zipOf(
            "trip/a.jpg" to randomBytes(1_000, 5),
            "trip/corrupt.jpg" to randomBytes(300, 6),
            "trip/b.jpg" to randomBytes(1_200, 7),
        )
        val slip = zipOf("trip/c.jpg" to randomBytes(800, 8), "../evil.jpg" to randomBytes(500, 9))
        val (session, parser) = session(mapOf(GOOD to goodZip, SLIP to slip))

        val events = session.ingest(
            listOf(
                IngestionRequest.ZipArchive(GOOD, "good.zip"),
                IngestionRequest.ZipArchive(SLIP, "slip.zip"),
                IngestionRequest.PickedMedia(listOf(PICKED.source.uri)),
            ),
        ).toList()

        assertEquals(listOf("slip.zip"), events.failures().map { it.displayName })
        val completed = events.last() as IngestionEvent.Completed
        assertEquals(listOf("a.jpg", "b.jpg", "picked"), completed.candidates.map { it.source.displayName }.sorted())
        assertEquals(
            listOf("corrupt.jpg" to SkipReason.UNDECODABLE),
            events.filterIsInstance<IngestionEvent.Skipped>().map { it.displayName to it.reason },
        )
        assertEquals(
            IngestionTelemetry(sourcesTotal = 4, filesRead = 4, candidatesAccepted = 3, skipped = 1, bytesExtracted = 2_500),
            completed.telemetry,
        )
        assertTrue("slip.zip entries never parsed: ${parser.calls}", parser.calls.none { it.displayName == "c.jpg" })
        val kept = completed.candidates.mapNotNull { (it.source as? PhotoSource.ExtractedFile)?.path }.map(::File)
        kept.forEach { assertTrue("good.zip photo kept on disk: $it", it.isFile) }
        assertEquals(
            "only good.zip's files remain under the work dir",
            listOf("a.jpg", "b.jpg", "corrupt.jpg"),
            filesUnder(session.workDir).map { it.name.substringAfterLast('_') }.sorted(),
        )
        session.close()
    }

    private fun assertWithdrawn(events: List<IngestionEvent>, session: DefaultIngestionSession, parser: RecordingParser) {
        assertEquals("nothing from the rejected archive is announced", emptyList<IngestionEvent>(), events.announcements())
        val completed = events.last() as IngestionEvent.Completed
        assertEquals(emptyList<Any>(), completed.candidates)
        assertEquals("no orphan counts", IngestionTelemetry(), completed.telemetry)
        assertEquals("rejected entries are never parsed", emptyList<PhotoSource>(), parser.calls.toList())
        assertEquals("staged files deleted before close", emptyList<File>(), filesUnder(session.workDir))
        session.close()
    }

    private fun TestScope.session(
        archives: Map<String, ByteArray>,
        limits: ZipLimits = ZipLimits(),
    ): Pair<DefaultIngestionSession, RecordingParser> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val parser = RecordingParser()
        val session = DefaultIngestionSession(
            workDir = tmp.newFolder(),
            extractor = StreamingZipExtractor(limits, dispatcher, plentyOfSpace),
            parser = parser,
            picker = { uris -> uris.map { PICKED } },
            zipOpener = { uri -> ByteArrayInputStream(archives.getValue(uri)) },
            ioDispatcher = dispatcher,
        )
        return session to parser
    }

    /** Parses everything except `corrupt.jpg`; an extracted file must still exist when it is parsed. */
    private class RecordingParser : MetadataParser {
        val calls: MutableList<PhotoSource> = Collections.synchronizedList(mutableListOf())

        override suspend fun parse(source: PhotoSource): ParsedImage? {
            calls += source
            if (source is PhotoSource.ExtractedFile) check(File(source.path).isFile) { "parsed a missing file: $source" }
            if (source.displayName == "corrupt.jpg") return null
            return ParsedImage(ImageFormat.JPEG, 4000, 3000, 1234L, CaptureMetadata.EMPTY)
        }
    }

    private companion object {
        const val SLIP = "content://zip/slip"
        const val BOMB = "content://zip/bomb"
        const val MANY = "content://zip/many"
        const val GOOD = "content://zip/good"
        val PICKED = PickedItem(PhotoSource.ContentUri("content://m/picked", "picked"), ImageFormat.JPEG, 100L)

        fun List<IngestionEvent>.failures() = filterIsInstance<IngestionEvent.SourceFailed>()

        /** Everything but the failure and the final completion. */
        fun List<IngestionEvent>.announcements() =
            filterNot { it is IngestionEvent.SourceFailed || it is IngestionEvent.Completed }

        fun filesUnder(dir: File): List<File> = dir.walkTopDown().filter { it.isFile }.toList()
    }
}
