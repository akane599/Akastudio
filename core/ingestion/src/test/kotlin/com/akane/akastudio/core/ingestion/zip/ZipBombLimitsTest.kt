package com.akane.akastudio.core.ingestion.zip

import com.akane.akastudio.core.ingestion.api.ZipExtractionEvent.EntryExtracted
import com.akane.akastudio.core.ingestion.api.ZipLimits
import com.akane.akastudio.core.ingestion.model.IngestionError
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
}
