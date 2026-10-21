package com.akane.akastudio.core.ingestion.pipeline

import com.akane.akastudio.core.ingestion.api.IngestionRequest
import com.akane.akastudio.core.ingestion.api.MetadataParser
import com.akane.akastudio.core.ingestion.api.ParsedImage
import com.akane.akastudio.core.ingestion.api.ZipLimits
import com.akane.akastudio.core.ingestion.model.CaptureMetadata
import com.akane.akastudio.core.ingestion.model.ImageFormat
import com.akane.akastudio.core.ingestion.model.IngestionEvent
import com.akane.akastudio.core.ingestion.model.PhotoSource
import com.akane.akastudio.core.ingestion.model.SkipReason
import com.akane.akastudio.core.ingestion.zip.StreamingZipExtractor
import com.akane.akastudio.core.ingestion.zip.plentyOfSpace
import com.akane.akastudio.core.ingestion.zip.randomBytes
import com.akane.akastudio.core.ingestion.zip.zipOf
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Disk hygiene inside one session: a re-imported archive adds nothing and leaves no second copy, and an extracted
 * file whose parse is skipped is deleted at once instead of at close. Real archives through the real
 * [StreamingZipExtractor]; only metadata parsing is faked.
 */
class ReimportAndSkipCleanupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val trip = zipOf(
        "trip/a.jpg" to randomBytes(1_000, 1),
        "trip/corrupt.jpg" to randomBytes(300, 2),
        "trip/broken.jpg" to randomBytes(400, 3),
        "trip/b.jpg" to randomBytes(1_200, 4),
    )

    @Test
    fun undecodableAndUnreadableExtractedFilesAreDeletedBeforeClose() = runTest {
        val (session, _) = session()

        val events = session.ingest(listOf(IngestionRequest.ZipArchive(TRIP, "trip.zip"))).toList()

        assertEquals(
            setOf("corrupt.jpg" to SkipReason.UNDECODABLE, "broken.jpg" to SkipReason.UNREADABLE),
            events.skips().toSet(),
        )
        assertEquals(listOf("a.jpg", "b.jpg"), (events.last() as IngestionEvent.Completed).names())
        assertEquals("only accepted photos stay on disk", listOf("a.jpg", "b.jpg"), entryNamesUnder(session.workDir))
        session.close()
    }

    @Test
    fun sameArchiveInALaterIngestCall_addsNothingAndLeavesNoSecondCopyOnDisk() = runTest {
        val (session, parser) = session()
        val first = session.ingest(listOf(IngestionRequest.ZipArchive(TRIP, "trip.zip"))).toList()
        val parsedFirst = parser.calls.size
        val filesAfterFirst = filesUnder(session.workDir)

        val second = session.ingest(listOf(IngestionRequest.ZipArchive(TRIP, "trip.zip"))).toList()

        assertEquals(listOf("a.jpg", "b.jpg"), (first.last() as IngestionEvent.Completed).names())
        assertEquals(emptyList<String>(), (second.last() as IngestionEvent.Completed).names())
        assertEquals(
            listOf("a.jpg", "b.jpg", "broken.jpg", "corrupt.jpg").map { it to SkipReason.DUPLICATE },
            second.skips().sortedBy { it.first },
        )
        assertEquals("nothing is parsed twice", parsedFirst, parser.calls.size)
        assertEquals("the second extraction's files are all deleted", filesAfterFirst, filesUnder(session.workDir))
        session.close()
    }

    private fun TestScope.session(): Pair<DefaultIngestionSession, RecordingParser> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val parser = RecordingParser()
        val session = DefaultIngestionSession(
            workDir = tmp.newFolder(),
            extractor = StreamingZipExtractor(ZipLimits(), dispatcher, plentyOfSpace),
            parser = parser,
            picker = { emptyList() },
            zipOpener = { ByteArrayInputStream(trip) },
            ioDispatcher = dispatcher,
        )
        return session to parser
    }

    /** `corrupt.jpg` does not decode and `broken.jpg` fails to read; an extracted file must exist when parsed. */
    private class RecordingParser : MetadataParser {
        val calls: MutableList<PhotoSource> = Collections.synchronizedList(mutableListOf())

        override suspend fun parse(source: PhotoSource): ParsedImage? {
            calls += source
            if (source is PhotoSource.ExtractedFile) check(File(source.path).isFile) { "parsed a missing file: $source" }
            return when (source.displayName) {
                "corrupt.jpg" -> null
                "broken.jpg" -> throw IOException("truncated")
                else -> ParsedImage(ImageFormat.JPEG, 4000, 3000, 1234L, CaptureMetadata.EMPTY)
            }
        }
    }

    private companion object {
        const val TRIP = "content://zip/trip"

        fun List<IngestionEvent>.skips() =
            filterIsInstance<IngestionEvent.Skipped>().map { it.displayName to it.reason }

        fun IngestionEvent.Completed.names() = candidates.map { it.source.displayName }.sorted()

        fun filesUnder(dir: File): List<File> = dir.walkTopDown().filter { it.isFile }.sorted().toList()

        fun entryNamesUnder(dir: File): List<String> = filesUnder(dir).map { it.name.substringAfterLast('_') }.sorted()
    }
}
