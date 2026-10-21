package com.akane.akastudio.core.ingestion.zip

import com.akane.akastudio.core.ingestion.api.ZipExtractionEvent.EntryExtracted
import com.akane.akastudio.core.ingestion.api.ZipExtractionEvent.EntrySkipped
import com.akane.akastudio.core.ingestion.api.ZipLimits
import com.akane.akastudio.core.ingestion.model.ImageFormat
import com.akane.akastudio.core.ingestion.model.IngestionError
import com.akane.akastudio.core.ingestion.model.SkipReason
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipException
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Happy path, classification, naming, charset fallback and archive-level error mapping. */
class StreamingZipExtractorTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun TestScope.extractor(limits: ZipLimits = ZipLimits()) =
        StreamingZipExtractor(limits, StandardTestDispatcher(testScheduler), plentyOfSpace)

    @Test
    fun happyPath_extractsEverySupportedFormatFlattenedAndByteIdentical() = runTest {
        val images = linkedMapOf(
            "a.jpg" to randomBytes(3_000, seed = 1),
            "nested/b.png" to randomBytes(4_000, seed = 2),
            "nested/deeper/c.heic" to randomBytes(5_000, seed = 3),
            "nested/deeper/d.webp" to randomBytes(6_000, seed = 4),
            "e.DNG" to randomBytes(200 * KIB, seed = 5),
        )
        val zip = zipOf(
            "nested/" to ByteArray(0),
            "nested/deeper/" to ByteArray(0),
            *images.map { (name, bytes) -> name to bytes }.toTypedArray(),
        )
        val dest = temp.newFolder("out")

        val events = extractor().extract({ ByteArrayInputStream(zip) }, "archive", dest).toList()

        assertEquals(
            listOf(EntrySkipped("nested/", SkipReason.DIRECTORY), EntrySkipped("nested/deeper/", SkipReason.DIRECTORY)),
            events.take(2),
        )
        val extracted = events.drop(2).map { it as EntryExtracted }
        assertEquals(images.keys.toList(), extracted.map { it.source.archiveEntryName })
        assertEquals(
            listOf(ImageFormat.JPEG, ImageFormat.PNG, ImageFormat.HEIC, ImageFormat.WEBP, ImageFormat.DNG),
            extracted.map { it.format },
        )
        for (event in extracted) {
            val file = File(event.source.path)
            val original = images.getValue(event.source.archiveEntryName)
            assertEquals("flattened into dest", dest.canonicalFile, file.canonicalFile.parentFile)
            assertEquals(original.size.toLong(), event.bytes)
            assertEquals(sha256(original), sha256(file))
            assertEquals(event.source.archiveEntryName.substringAfterLast('/'), event.source.displayName)
            assertTrue(file.name, file.name.matches(Regex("[0-9a-f]{16}_.+")))
        }
        assertEquals(images.size, dest.listFiles()!!.size)
    }

    @Test
    fun junkHiddenAndUnsupportedEntriesAreSkippedWithoutWritingFiles() = runTest {
        val zip = zipOf(
            "__MACOSX/._a.jpg" to ByteArray(10),
            ".DS_Store" to ByteArray(10),
            "Thumbs.db" to ByteArray(10),
            ".hidden/b.jpg" to ByteArray(10),
            "dir/._c.jpg" to ByteArray(10),
            "notes.txt" to ByteArray(10),
            "x.gif" to ByteArray(10),
        )
        val dest = temp.newFolder("out")

        val events = extractor().extract({ ByteArrayInputStream(zip) }, "archive", dest).toList()

        assertEquals(
            listOf(
                EntrySkipped("__MACOSX/._a.jpg", SkipReason.JUNK_FILE),
                EntrySkipped(".DS_Store", SkipReason.JUNK_FILE),
                EntrySkipped("Thumbs.db", SkipReason.JUNK_FILE),
                EntrySkipped(".hidden/b.jpg", SkipReason.HIDDEN_FILE),
                EntrySkipped("dir/._c.jpg", SkipReason.JUNK_FILE),
                EntrySkipped("notes.txt", SkipReason.UNSUPPORTED_TYPE),
                EntrySkipped("x.gif", SkipReason.UNSUPPORTED_TYPE),
            ),
            events,
        )
        assertEquals(emptyList<String>(), filesUnder(dest))
    }

    @Test
    fun sameFileNameInTwoDirectoriesProducesTwoDistinctFiles() = runTest {
        val first = randomBytes(1_000, seed = 1)
        val second = randomBytes(1_000, seed = 2)
        val zip = zipOf("a/photo.jpg" to first, "b/photo.jpg" to second)
        val dest = temp.newFolder("out")

        val extracted = extractor().extract({ ByteArrayInputStream(zip) }, "archive", dest).toList()
            .map { it as EntryExtracted }

        assertEquals(2, extracted.size)
        assertNotEquals(extracted[0].source.path, extracted[1].source.path)
        assertEquals(sha256(first), sha256(File(extracted[0].source.path)))
        assertEquals(sha256(second), sha256(File(extracted[1].source.path)))
    }

    @Test
    fun repeatedEntryNameIsSkippedAsDuplicateAndFirstCopyKept() = runTest {
        val first = randomBytes(1_000, seed = 1)
        val zip = archive(RawZip.stored("photo.jpg", first), RawZip.stored("photo.jpg", randomBytes(1_000, seed = 2)))
        val dest = temp.newFolder("out")

        val events = extractor().extract({ ByteArrayInputStream(zip) }, "archive", dest).toList()

        assertTrue(events[0] is EntryExtracted)
        assertEquals(EntrySkipped("photo.jpg", SkipReason.DUPLICATE), events[1])
        assertEquals(sha256(first), sha256(File((events[0] as EntryExtracted).source.path)))
    }

    @Test
    fun malformedUtf8NameRetriesOnceWithCp437WithoutReEmittingEarlierEntries() = runTest {
        val cafe = "caf".toByteArray() + byteArrayOf(0x82.toByte()) + ".jpg".toByteArray() // 0x82 is é in CP437
        val zip = archive(RawZip.stored("first.jpg", randomBytes(500)), RawZip.stored(cafe, randomBytes(600)))
        val dest = temp.newFolder("out")
        var opens = 0

        val events = extractor().extract({ opens++; ByteArrayInputStream(zip) }, "archive", dest).toList()

        assertEquals(2, opens)
        assertEquals(listOf("first.jpg", "café.jpg"), events.map { (it as EntryExtracted).source.displayName })
        assertEquals(2, dest.listFiles()!!.size)
    }

    @Test
    fun randomBytesAreInvalidArchive() = runTest {
        val error = failureOf(extractor().extract({ ByteArrayInputStream(randomBytes(4_096)) }, "a", temp.newFolder()))
        assertTrue("$error", error is IngestionError.InvalidArchive)
    }

    @Test
    fun emptyStreamIsInvalidArchive() = runTest {
        val error = failureOf(extractor().extract({ ByteArrayInputStream(ByteArray(0)) }, "a", temp.newFolder()))
        assertEquals(IngestionError.InvalidArchive("empty archive"), error)
    }

    @Test
    fun zipExceptionBeforeAnyEntryIsInvalidArchive() = runTest {
        val error = failureOf(extractor().extract({ failingStream(ZipException("bad header")) }, "a", temp.newFolder()))
        assertEquals(IngestionError.InvalidArchive("bad header"), error)
    }

    @Test
    fun platformUnsafeNameZipExceptionMapsToZipSlip() = runTest {
        // Android's ZipPathValidator (targetSdk >= 34) throws this from nextEntry before our guard runs.
        val thrown = ZipException("Invalid zip entry path: ../evil.jpg")
        val error = failureOf(extractor().extract({ failingStream(thrown) }, "a", temp.newFolder()))
        assertEquals(IngestionError.ZipSlip("../evil.jpg"), error)
    }

    @Test
    fun failingInputOpenIsIo() = runTest {
        val error = failureOf(extractor().extract({ throw java.io.FileNotFoundException("gone") }, "a", temp.newFolder()))
        assertEquals(IngestionError.Io("gone"), error)
    }

    @Test
    fun runtimeExceptionFromInputOpenIsIo() = runTest {
        val error = failureOf(extractor().extract({ throw SecurityException("no permission") }, "a", temp.newFolder()))
        assertEquals(IngestionError.Io("no permission"), error)
    }

    @Test
    fun runtimeExceptionFromTheStreamIsIo() = runTest {
        val stream = object : InputStream() {
            override fun read(): Int = throw IllegalStateException("provider died")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw IllegalStateException("provider died")
        }
        val error = failureOf(extractor().extract({ stream }, "a", temp.newFolder()))
        assertEquals(IngestionError.Io("provider died"), error)
    }

    @Test
    fun crcMismatchOnALaterEntryIsInvalidArchive() = runTest {
        val ok = "ok.jpg" to randomBytes(2_000)
        val content = randomBytes(1_000, seed = 9)
        val corrupt = RawZip.entry("bad.jpg".toByteArray(), content, declaredSize = 1_000, crc = RawZip.crc(content) xor 1)
        val dest = temp.newFolder()

        val error = failureOf(extractor().extract({ ByteArrayInputStream(archive(RawZip.stored(ok.first, ok.second), corrupt)) }, "archive", dest))

        assertTrue("$error", error is IngestionError.InvalidArchive)
        assertEquals(listOf("ok.jpg"), survivingEntries(dest, ok, "bad.jpg" to content))
    }

    @Test
    fun truncationInsideALaterEntryIsInvalidArchive() = runTest {
        val ok = "ok.jpg" to randomBytes(2_000)
        val cut = "cut.jpg" to randomBytes(200 * KIB, seed = 9)
        val whole = zipOf(ok, cut)
        val truncated = whole.copyOf(whole.size - 100 * KIB) // ends mid-way through cut.jpg's deflated data
        val dest = temp.newFolder()

        val error = failureOf(extractor().extract({ ByteArrayInputStream(truncated) }, "archive", dest))

        assertTrue("$error", error is IngestionError.InvalidArchive)
        assertEquals(listOf("ok.jpg"), survivingEntries(dest, ok, cut))
    }

    @Test
    fun formatCharactersAreStrippedFromDisplayNameButNotFromTheEntryName() = runTest {
        val spoof = "invoice‮gpj.exe.jpg" // U+202E right-to-left override: a classic file-name spoof
        val dest = temp.newFolder()

        val events = extractor().extract({ ByteArrayInputStream(archive(RawZip.stored(spoof, randomBytes(100)))) }, "archive", dest).toList()

        val source = (events.single() as EntryExtracted).source
        assertEquals("invoicegpj.exe.jpg", source.displayName)
        assertEquals(spoof, source.archiveEntryName)
    }

    @Test
    fun anExceptionThrownByTheCollectorReachesItUnchanged() = runTest {
        // Same dispatcher as the collector: flowOn fuses, so the collector's exception passes through the extractor.
        val sameDispatcher = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
        val extractor = StreamingZipExtractor(ZipLimits(), sameDispatcher, plentyOfSpace)
        val ok = "ok.jpg" to randomBytes(2_000)
        val zip = zipOf(ok, "second.jpg" to randomBytes(2_000, seed = 2))
        val dest = temp.newFolder()
        val consumersOwn = java.io.IOException("consumer failed")

        val thrown = runCatching { extractor.extract({ ByteArrayInputStream(zip) }, "archive", dest).collect { throw consumersOwn } }

        assertSame(consumersOwn, thrown.exceptionOrNull())
        assertEquals(listOf("ok.jpg"), survivingEntries(dest, ok))
    }

    private fun failingStream(failure: java.io.IOException) = object : InputStream() {
        override fun read(): Int = throw failure
        override fun read(b: ByteArray, off: Int, len: Int): Int = throw failure
    }
}
