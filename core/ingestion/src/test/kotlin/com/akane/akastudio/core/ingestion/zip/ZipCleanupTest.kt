package com.akane.akastudio.core.ingestion.zip

import com.akane.akastudio.core.ingestion.api.ZipExtractionEvent
import com.akane.akastudio.core.ingestion.api.ZipExtractionEvent.EntryExtracted
import com.akane.akastudio.core.ingestion.api.ZipLimits
import com.akane.akastudio.core.ingestion.model.IngestionError
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Cancellation, storage exhaustion, and the failure contract: only the file being written is deleted. Complete
 * files already written belong to the consumer, and files this collection did not create are never touched.
 */
class ZipCleanupTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun TestScope.extractor(limits: ZipLimits = ZipLimits(), freeSpace: (File) -> Long = plentyOfSpace) =
        StreamingZipExtractor(limits, StandardTestDispatcher(testScheduler), freeSpace)

    private val first = "first.jpg" to randomBytes(10 * KIB, seed = 1)
    private val large = "large.jpg" to randomBytes(8 * MIB, seed = 2)
    private val twoEntryArchive = zipOf(first, large, method = ZipEntry.STORED)

    /** Runs [onThreshold] once, the first time more than [threshold] archive bytes have been read. */
    private class ThresholdStream(source: InputStream, private val threshold: Long, private val onThreshold: () -> Unit) :
        FilterInputStream(source) {
        var consumed = 0L
            private set
        private var fired = false

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { read ->
            if (read > 0) consumed += read
            if (!fired && consumed > threshold) {
                fired = true
                onThreshold()
            }
        }
    }

    @Test
    fun cancellationWhileCopyingTheSecondEntryDeletesOnlyThePartialFile() = runTest {
        val dest = temp.newFolder()
        var filesWhenCancelled = emptyList<String>()
        lateinit var job: Job
        val stream = ThresholdStream(ByteArrayInputStream(twoEntryArchive), threshold = 1L * MIB) {
            filesWhenCancelled = filesUnder(dest)
            job.cancel()
        }

        job = launch { extractor().extract({ stream }, "archive", dest).collect() }
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(
            "first.jpg done and large.jpg partially written",
            listOf(outputNameOf("first.jpg"), outputNameOf("large.jpg")).sorted(),
            filesWhenCancelled,
        )
        assertEquals("the partial is gone, the complete file stays", listOf("first.jpg"), survivingEntries(dest, first, large))
    }

    @Test
    fun cancellationDuringEndlessEmptyDeflateBlocksStopsReadingPromptly() = runTest {
        // The inflater produces no output, so the copy loop never regains control; the archive stream must notice.
        val dest = temp.newFolder()
        lateinit var job: Job
        val stream = ThresholdStream(ByteArrayInputStream(endlessEmptyDeflateArchive(30 * MIB)), threshold = 1L * MIB) {
            job.cancel()
        }

        job = launch { extractor().extract({ stream }, "archive", dest).collect() }
        job.join()

        assertTrue(job.isCancelled)
        assertTrue("read ${stream.consumed} bytes of 30 MiB after cancelling at 1 MiB", stream.consumed <= 1L * MIB + 128 * KIB)
        assertEquals("the partial is gone", emptyList<String>(), filesUnder(dest))
    }

    @Test
    fun cancellingTheCollectorAfterTheFirstExtractedEntryKeepsItAndDeletesThePartial() = runTest {
        val dest = temp.newFolder()
        val collectorCancelled = CountDownLatch(1)
        // The producer runs on a real IO thread and is held mid-copy of large.jpg until the collector has
        // seen first.jpg and cancelled, so the cancellation provably lands during extraction.
        val stream = ThresholdStream(ByteArrayInputStream(twoEntryArchive), threshold = 1L * MIB) {
            collectorCancelled.await(10, TimeUnit.SECONDS)
        }
        val extractor = StreamingZipExtractor(ZipLimits(), Dispatchers.IO, plentyOfSpace)
        val seen = mutableListOf<ZipExtractionEvent>()

        val job = launch {
            extractor.extract({ stream }, "archive", dest).collect { event ->
                seen += event
                if (event is EntryExtracted) {
                    currentCoroutineContext().job.cancel()
                    collectorCancelled.countDown()
                }
            }
        }
        job.join()

        assertEquals(listOf("first.jpg"), seen.map { (it as EntryExtracted).source.displayName })
        assertTrue("the emitted file still exists", File((seen.single() as EntryExtracted).source.path).isFile)
        assertEquals("the partial is gone", listOf("first.jpg"), survivingEntries(dest, first, large))
    }

    @Test
    fun tooLittleFreeSpaceBeforeAnEntryIsInsufficientStorage() = runTest {
        val zip = zipOf("notes.txt" to ByteArray(10), "photo.jpg" to randomBytes(1_000))
        val dest = temp.newFolder()

        val error = failureOf(
            extractor(freeSpace = { 1_000L }).extract({ ByteArrayInputStream(zip) }, "archive", dest),
        )

        assertTrue("$error", error is IngestionError.InsufficientStorage)
        assertEquals(emptyList<String>(), filesUnder(dest))
    }

    @Test
    fun runningOutOfSpaceMidEntryIsInsufficientStorageAndRemovesThePartialFile() = runTest {
        val dest = temp.newFolder()
        val budget = 10L * MIB
        val shrinkingSpace: (File) -> Long = { budget - filesUnder(dest).sumOf { File(dest, it).length() } }
        // DEFLATED with a data descriptor: size unknown up front, so the first check only needs 8 MiB.
        val zip = zipOf("big.jpg" to randomBytes(12 * MIB))

        val error = failureOf(
            extractor(ZipLimits(freeSpaceReserveBytes = 0), shrinkingSpace)
                .extract({ ByteArrayInputStream(zip) }, "archive", dest),
        )

        assertTrue("$error", error is IngestionError.InsufficientStorage)
        assertEquals(emptyList<String>(), filesUnder(dest))
    }

    @Test
    fun enospcPartwayThroughAWriteIsInsufficientStorageAndRemovesThePartialFile() = runTest {
        val dest = temp.newFolder()
        var partialBytesOnDisk = 0L
        // Writes really reach the file, then the volume fills up after 1 MiB, as Android reports it.
        val fillsUp: (Path) -> OutputStream = { path ->
            object : FilterOutputStream(Files.newOutputStream(path)) {
                var written = 0L

                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (written >= MIB) {
                        partialBytesOnDisk = Files.size(path)
                        throw IOException("write failed: ENOSPC (No space left on device)")
                    }
                    out.write(b, off, len)
                    out.flush()
                    written += len
                }
            }
        }
        val extractor = StreamingZipExtractor(ZipLimits(), StandardTestDispatcher(testScheduler), plentyOfSpace, fillsUp)

        val error = failureOf(
            extractor.extract({ ByteArrayInputStream(zipOf("big.jpg" to randomBytes(4 * MIB))) }, "archive", dest),
        )

        assertTrue("a partial file existed when the write failed", partialBytesOnDisk >= MIB)
        assertTrue("$error", error is IngestionError.InsufficientStorage)
        assertEquals(0L, (error as IngestionError.InsufficientStorage).availableBytes)
        assertTrue("the entry's estimate is reported", error.requiredBytes > 0)
        assertEquals("the partial is gone", emptyList<String>(), filesUnder(dest))
    }

    @Test
    fun outOfSpaceIsRecognisedThroughTheCauseChainAndOnlyThere() {
        assertTrue(isOutOfSpace(IOException("write failed: ENOSPC (No space left on device)")))
        assertTrue(isOutOfSpace(IOException("No space left on device")))
        assertTrue(isOutOfSpace(IOException("copy failed", IOException("write failed: ENOSPC"))))
        assertFalse(isOutOfSpace(IOException("read failed: EIO (I/O error)")))
        assertFalse(isOutOfSpace(IllegalStateException()))
    }

    @Test
    fun freeSpaceIsReadOncePerArchive() = runTest {
        val dest = temp.newFolder()
        var reads = 0
        val counting: (File) -> Long = { reads++; Long.MAX_VALUE / 2 }
        // Three entries, one of them past the 8 MiB re-check interval: all checks use the cached figure.
        val zip = zipOf("a.jpg" to randomBytes(KIB), "b.jpg" to randomBytes(20 * MIB), "c.jpg" to randomBytes(KIB))

        val events = extractor(freeSpace = counting).extract({ ByteArrayInputStream(zip) }, "archive", dest).toList()

        assertEquals(3, events.size)
        assertEquals(1, reads)
    }

    @Test
    fun filesThisCollectionDidNotCreateSurviveAFailure() = runTest {
        val dest = temp.newFolder()
        File(dest, "someone-elses.jpg").writeText("foreign")
        File(dest, "sub").mkdirs()
        File(dest, "sub/nested.jpg").writeText("foreign nested")
        val zip = archive(RawZip.stored("ok.jpg", randomBytes(2_000)), RawZip.stored("../evil.jpg", ByteArray(10)))

        val error = failureOf(extractor().extract({ ByteArrayInputStream(zip) }, "archive", dest))

        assertEquals(IngestionError.ZipSlip("../evil.jpg"), error)
        assertEquals(listOf("someone-elses.jpg", "sub", "sub/nested.jpg", outputNameOf("ok.jpg")).sorted(), filesUnder(dest))
        assertEquals("foreign", File(dest, "someone-elses.jpg").readText())
        assertEquals("foreign nested", File(dest, "sub/nested.jpg").readText())
    }

    @Test
    fun preExistingFileAtTheOutputNameIsNeverOverwrittenOrDeleted() = runTest {
        val dest = temp.newFolder()
        val squatter = File(dest, ArchiveEntryRules.outputFileName("archive", "b.jpg", "b.jpg")).apply { writeText("foreign") }
        val zip = archive(RawZip.stored("a.jpg", randomBytes(2_000)), RawZip.stored("b.jpg", randomBytes(2_000)))

        val error = failureOf(extractor().extract({ ByteArrayInputStream(zip) }, "archive", dest))

        assertTrue("$error", error is IngestionError.Io)
        assertEquals(listOf(squatter.name, outputNameOf("a.jpg")).sorted(), filesUnder(dest))
        assertEquals("foreign", squatter.readText())
    }

    @Test
    fun skippedEntriesBeforeAFailureWriteNothing() = runTest {
        // Events emitted before a whole-archive rejection may be dropped by flowOn; the failure is what counts.
        val dest = temp.newFolder()
        val zip = archive(RawZip.stored("notes.txt", ByteArray(10)), RawZip.stored("/abs.jpg", ByteArray(10)))

        val error = failureOf(extractor().extract({ ByteArrayInputStream(zip) }, "archive", dest))

        assertEquals(IngestionError.ZipSlip("/abs.jpg"), error)
        assertEquals(emptyList<String>(), filesUnder(dest))
    }
}
