package com.akane.akastudio.core.ingestion.zip

import com.akane.akastudio.core.ingestion.api.ZipExtractionEvent.EntryExtracted
import com.akane.akastudio.core.ingestion.api.ZipExtractionEvent.EntrySkipped
import com.akane.akastudio.core.ingestion.api.ZipLimits
import com.akane.akastudio.core.ingestion.model.IngestionError
import com.akane.akastudio.core.ingestion.model.SkipReason
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
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
 * Decompression bombs and size limits. Limits apply to bytes actually inflated, so every lying-header case is
 * capped by counting, never by what the header claims. Each failing archive starts with a valid image, so the
 * tests also prove the failure contract: earlier complete files stay and the offending partial file is gone.
 */
class ZipBombLimitsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun TestScope.extractor(limits: ZipLimits = ZipLimits()) =
        StreamingZipExtractor(limits, StandardTestDispatcher(testScheduler), plentyOfSpace)

    private val okImage = "ok.jpg" to randomBytes(2_000)

    private suspend fun TestScope.failureFor(zip: ByteArray, limits: ZipLimits = ZipLimits()): Pair<IngestionError, File> {
        val dest = temp.newFolder()
        return failureOf(extractor(limits).extract({ ByteArrayInputStream(zip) }, "archive", dest)) to dest
    }

    @Test
    fun highlyCompressedEntryIsZipBomb() = runTest {
        val bomb = "bomb.jpg" to ByteArray(64 * MIB) // zeros deflate about 1000:1
        val zip = zipOf(okImage, bomb)

        val (error, dest) = failureFor(zip)

        assertTrue("$error", error is IngestionError.ZipBomb)
        assertEquals(listOf("ok.jpg"), survivingEntries(dest, okImage, bomb))
    }

    @Test
    fun ratioIsCumulativeSoManySmallEntriesCannotEvadeIt() = runTest {
        val entries = listOf(okImage) + (1..200).map { "part$it.jpg" to ByteArray(64 * KIB) }
        val zip = zipOf(*entries.toTypedArray())

        val (error, dest) = failureFor(zip)

        assertTrue("$error", error is IngestionError.ZipBomb)
        val survivors = survivingEntries(dest, *entries.toTypedArray())
        assertEquals("complete files before the trip are kept", "ok.jpg", survivors.firstOrNull())
        assertTrue("stopped early: ${survivors.size} of ${entries.size}", survivors.size < entries.size / 2)
        assertEquals("survivors are the entries before the trip", entries.map { it.first }.take(survivors.size), survivors)
    }

    @Test
    fun bombInsideASkippedEntryStillTripsTheRatioGuard() = runTest {
        val zip = zipOf(okImage, "notes.txt" to ByteArray(64 * MIB))

        val (error, dest) = failureFor(zip)

        assertTrue("$error", error is IngestionError.ZipBomb)
        assertEquals(listOf("ok.jpg"), survivingEntries(dest, okImage))
    }

    @Test
    fun entryOverMaxEntryBytesIsLimitExceeded() = runTest {
        // DEFLATED via ZipOutputStream uses a data descriptor: the local header declares no size at all.
        val big = "big.jpg" to randomBytes(2 * MIB)
        val zip = zipOf(okImage, big)

        val (error, dest) = failureFor(zip, ZipLimits(maxEntryBytes = 1L * MIB))

        assertTrue("$error", error is IngestionError.LimitExceeded)
        assertEquals(listOf("ok.jpg"), survivingEntries(dest, okImage, big))
    }

    @Test
    fun archiveOverMaxTotalBytesIsLimitExceeded() = runTest {
        val entries = arrayOf(
            okImage,
            "a.jpg" to randomBytes(400 * KIB, seed = 1),
            "b.jpg" to randomBytes(400 * KIB, seed = 2),
            "c.jpg" to randomBytes(400 * KIB, seed = 3),
        )

        val (error, dest) = failureFor(zipOf(*entries), ZipLimits(maxTotalBytes = 1L * MIB))

        assertTrue("$error", error is IngestionError.LimitExceeded)
        assertEquals(listOf("ok.jpg", "a.jpg", "b.jpg"), survivingEntries(dest, *entries))
    }

    @Test
    fun skippedEntryOverMaxEntryBytesIsLimitExceeded() = runTest {
        val limits = ZipLimits(maxEntryBytes = 1L * MIB)
        val notes = "notes.txt" to compressibleBytes(2 * MIB)
        val zip = zipOf(okImage, notes)
        assertBelowRatio(zip, limits, okImage, notes)

        val (error, dest) = failureFor(zip, limits)

        assertEquals(IngestionError.LimitExceeded("entry larger than ${1L * MIB} bytes"), error)
        assertEquals(listOf("ok.jpg"), survivingEntries(dest, okImage, notes))
    }

    @Test
    fun skippedEntriesUnderTheEntryCapButOverMaxTotalBytesAreLimitExceeded() = runTest {
        val limits = ZipLimits(maxEntryBytes = 1L * MIB, maxTotalBytes = 2L * MIB)
        // Every skip kind that drains: unsupported type, hidden, junk and duplicate, each under the entry cap.
        val skipped = arrayOf(
            "notes.txt" to compressibleBytes(700 * KIB, seed = 1),
            "trip/.hidden.jpg" to compressibleBytes(700 * KIB, seed = 2),
            "__MACOSX/trip/._ok.jpg" to compressibleBytes(700 * KIB, seed = 3),
            "ok.jpg" to compressibleBytes(700 * KIB, seed = 4),
        )
        // ZipOutputStream refuses a repeated name, so this archive's local headers are written by hand.
        val deflated = skipped.map { (name, content) -> deflatedEntry(name, content) }
        val zip = archive(RawZip.stored(okImage.first, okImage.second), *deflated.toTypedArray())
        assertBelowRatio(zip, limits, okImage, *skipped)

        val (error, dest) = failureFor(zip, limits)

        assertEquals(IngestionError.LimitExceeded("archive larger than ${2L * MIB} bytes"), error)
        assertEquals(listOf("ok.jpg"), survivingEntries(dest, okImage))
    }

    @Test
    fun skippedAndImageBytesShareOneCumulativeBudget() = runTest {
        val limits = ZipLimits(maxTotalBytes = 1L * MIB)
        val notes = "notes.txt" to compressibleBytes(700 * KIB, seed = 1)
        val photo = "photo.jpg" to compressibleBytes(500 * KIB, seed = 2)
        val withoutNotes = zipOf(okImage, photo)
        val withNotes = zipOf(okImage, notes, photo)
        assertBelowRatio(withNotes, limits, okImage, notes, photo)

        val alone = extractor(limits)
            .extract({ ByteArrayInputStream(withoutNotes) }, "archive", temp.newFolder())
            .toList()
        val (error, dest) = failureFor(withNotes, limits)

        assertEquals("the image fits the budget on its own", 2, alone.filterIsInstance<EntryExtracted>().size)
        assertEquals(IngestionError.LimitExceeded("archive larger than ${1L * MIB} bytes"), error)
        assertEquals(
            "the image that crossed the shared budget is deleted",
            listOf("ok.jpg"),
            survivingEntries(dest, okImage, photo),
        )
    }

    @Test
    fun charsetFallbackReplayCountsAlreadyHandledEntriesOnce() = runTest {
        // A malformed UTF-8 name makes the first pass give up and replay the archive as CP437; the replay drains
        // the entries the first pass handled. Counted twice, ok.jpg + notes.txt + café.jpg would exceed 1 MiB.
        val limits = ZipLimits(maxTotalBytes = 1L * MIB)
        val notes = compressibleBytes(600 * KIB, seed = 1)
        val cafeName = "caf".toByteArray() + byteArrayOf(0x82.toByte()) + ".jpg".toByteArray() // 0x82 is é in CP437
        val cafe = randomBytes(1_000, seed = 2)
        val prefix = arrayOf(
            RawZip.stored(okImage.first, okImage.second),
            deflatedEntry("notes.txt", notes),
            RawZip.stored(cafeName, cafe),
        )
        var opens = 0

        val fits = archive(*prefix)
        val events = extractor(limits)
            .extract({ opens++; ByteArrayInputStream(fits) }, "archive", temp.newFolder())
            .toList()

        assertEquals("the fallback replay ran", 2, opens)
        assertEquals(
            listOf("ok.jpg", "notes.txt", "café.jpg"),
            events.map { if (it is EntryExtracted) it.source.displayName else (it as EntrySkipped).entryName },
        )
        assertEquals(EntrySkipped("notes.txt", SkipReason.UNSUPPORTED_TYPE), events[1])

        // ...but counted once they still are: 450 KiB more after the replay crosses the budget.
        val more = "more.jpg" to compressibleBytes(450 * KIB, seed = 3)
        val over = archive(*prefix, deflatedEntry(more.first, more.second))
        val (error, dest) = failureFor(over, limits)

        assertEquals(IngestionError.LimitExceeded("archive larger than ${1L * MIB} bytes"), error)
        assertEquals(listOf("ok.jpg", "café.jpg"), survivingEntries(dest, okImage, "café.jpg" to cafe, more))
    }

    @Test
    fun deflatedEntryDeclaringTenBytesIsCappedByCountedBytes() = runTest {
        val actual = randomBytes(5 * MIB)
        val compressed = RawZip.deflate(actual)
        val lying = RawZip.entry(
            name = "liar.jpg".toByteArray(),
            data = compressed,
            method = ZipEntry.DEFLATED,
            declaredSize = 10,
            declaredCompressedSize = compressed.size.toLong(),
            crc = RawZip.crc(actual.copyOf(10)),
        )
        val zip = archive(RawZip.stored(okImage.first, okImage.second), lying)

        val (error, dest) = failureFor(zip, ZipLimits(maxEntryBytes = 1L * MIB))

        assertTrue("$error", error is IngestionError.LimitExceeded)
        assertEquals(listOf("ok.jpg"), survivingEntries(dest, okImage, "liar.jpg" to actual))
    }

    @Test
    fun storedEntryDeclaringTenBytesNeverWritesMoreThanItCounted() = runTest {
        val actual = ByteArray(5 * MIB) { (it % 251).toByte() }
        val lying = RawZip.entry(
            name = "liar.jpg".toByteArray(),
            data = actual,
            declaredSize = 10,
            declaredCompressedSize = 10,
            crc = RawZip.crc(actual.copyOf(10)),
        )
        val dest = temp.newFolder()

        val events = extractor().extract({ ByteArrayInputStream(lying) }, "archive", dest).toList()

        val extracted = events.single() as EntryExtracted
        assertEquals(10L, extracted.bytes)
        assertEquals(10L, File(extracted.source.path).length())
    }

    @Test
    fun endlessEmptyDeflateBlocksAreCappedByCompressedBytesRead() = runTest {
        // Inflates to zero bytes, so no inflated-byte limit or ratio can ever trip; only the compressed cap can.
        val source = CountingSource(ByteArrayInputStream(endlessEmptyDeflateArchive(30 * MIB)))

        val error = failureOf(
            extractor(ZipLimits(maxTotalBytes = 1L * MIB)).extract({ source }, "archive", temp.newFolder()),
        )

        assertTrue("$error", error is IngestionError.LimitExceeded)
        assertTrue("read ${source.delivered} bytes of 30 MiB", source.delivered <= 1L * MIB + 128 * KIB)
    }

    @Test
    fun moreThanMaxEntriesIsLimitExceededCountingDirectoriesAndSkips() = runTest {
        val limits = ZipLimits(maxEntries = 3)
        val late = "late.jpg" to ByteArray(1)
        val atLimit = zipOf("dir/" to ByteArray(0), "notes.txt" to ByteArray(1), okImage)
        val overLimit = zipOf("dir/" to ByteArray(0), "notes.txt" to ByteArray(1), okImage, late)

        assertEquals(3, extractor(limits).extract({ ByteArrayInputStream(atLimit) }, "a", temp.newFolder()).toList().size)
        val (error, dest) = failureFor(overLimit, limits)

        assertTrue("$error", error is IngestionError.LimitExceeded)
        assertEquals(listOf("ok.jpg"), survivingEntries(dest, okImage, late))
    }

    @Test
    fun pathDeeperThanMaxPathDepthIsLimitExceeded() = runTest {
        val deep = (1..17).joinToString("/") { "d$it" } + "/x.jpg"
        val zip = archive(RawZip.stored(okImage.first, okImage.second), RawZip.stored(deep, ByteArray(10)))

        val (error, dest) = failureFor(zip)

        assertTrue("$error", error is IngestionError.LimitExceeded)
        assertEquals(listOf("ok.jpg"), survivingEntries(dest, okImage, deep to ByteArray(10)))
    }

    /** An honest DEFLATED entry without a data descriptor: the local header declares its real sizes and CRC. */
    private fun deflatedEntry(name: String, content: ByteArray): ByteArray {
        val compressed = RawZip.deflate(content)
        return RawZip.entry(
            name = name.toByteArray(),
            data = compressed,
            method = ZipEntry.DEFLATED,
            declaredSize = content.size.toLong(),
            declaredCompressedSize = compressed.size.toLong(),
            crc = RawZip.crc(content),
        )
    }

    /** Only the inflated-byte caps may trip: the archive stays below the ratio limit and its compressed cap. */
    private fun assertBelowRatio(zip: ByteArray, limits: ZipLimits, vararg entries: Pair<String, ByteArray>) {
        val ratio = entries.sumOf { it.second.size.toLong() }.toDouble() / zip.size
        val ceiling = limits.maxCompressionRatio / 10
        assertTrue("ratio $ratio:1 must stay well below ${limits.maxCompressionRatio}:1", ratio < ceiling)
        assertTrue("compressed ${zip.size} must stay under ${limits.maxTotalBytes}", zip.size < limits.maxTotalBytes)
    }
}
