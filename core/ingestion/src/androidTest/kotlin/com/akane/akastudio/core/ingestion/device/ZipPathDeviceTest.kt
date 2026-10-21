package com.akane.akastudio.core.ingestion.device

import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akane.akastudio.core.ingestion.api.IngestionRequest
import com.akane.akastudio.core.ingestion.api.ZipLimits
import com.akane.akastudio.core.ingestion.createIngestionSession
import com.akane.akastudio.core.ingestion.model.ImageFormat
import com.akane.akastudio.core.ingestion.model.IngestionError
import com.akane.akastudio.core.ingestion.model.IngestionEvent
import com.akane.akastudio.core.ingestion.model.PhotoSource
import com.akane.akastudio.core.ingestion.model.SkipReason
import java.io.File
import java.util.Random
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Case 2: real archives on device storage, read through ContentResolver (file:// and MediaStore Downloads). */
@RunWith(AndroidJUnit4::class)
class ZipPathDeviceTest {

    private val store = QaMediaStore()
    private val appDir = File(targetContext.filesDir, "qa-zip-${store.runId}").apply { mkdirs() }
    private val ingestRoot = File(targetContext.cacheDir, "ingest")

    private val photo1 = deviceJpeg(80, 60, Color.RED, Color.BLUE)
    private val photo2 = deviceJpeg(60, 80, Color.GREEN, Color.MAGENTA)

    /** Two photos plus every junk kind the rules filter, and a directory entry. */
    private val tripZip = zipOf(
        "trip/" to ByteArray(0),
        "trip/IMG_0001.jpg" to photo1,
        "trip/IMG_0002.jpg" to photo2,
        "__MACOSX/trip/._IMG_0001.jpg" to ByteArray(120) { 7 },
        "trip/.DS_Store" to ByteArray(64) { 1 },
        "trip/Thumbs.db" to ByteArray(64) { 2 },
    )

    @After
    fun cleanUp() {
        appDir.deleteRecursively()
        store.deleteAll()
    }

    @Test
    fun zipInAppStorage_ingestsPhotos_filtersJunk_andCloseRemovesWorkDir() {
        val uri = Uri.fromFile(File(appDir, "trip.zip").apply { writeBytes(tripZip) }).toString()
        val session = createIngestionSession(targetContext)
        val workDir = session.workDir.canonicalFile
        val events = try {
            session.collect(IngestionRequest.ZipArchive(uri, "trip.zip"))
        } catch (e: Throwable) {
            session.close()
            throw e
        }

        assertTripIngested(events)
        val extracted = events.completed().candidates.map { File((it.source as PhotoSource.ExtractedFile).path) }
        extracted.forEach { file ->
            assertTrue("extracted under the session work dir: $file", file.canonicalPath.startsWith(workDir.path + "/"))
            assertTrue("extracted file exists before close: $file", file.isFile)
        }

        session.close()

        assertFalse("close() removes the work dir", workDir.exists())
        extracted.forEach { assertFalse("close() removes $it", it.exists()) }
    }

    @Test
    fun zipInPublicDownloads_viaMediaStoreUri_ingests() {
        val uri = store.insertDownload("trip.zip", tripZip, "application/zip")

        val events = createIngestionSession(targetContext).use {
            it.collect(IngestionRequest.ZipArchive(uri.toString(), "trip.zip"))
        }

        assertTripIngested(events)
    }

    /** Negative control for the junk filter: the same names without the junk markers are real candidates. */
    @Test
    fun junkNamesAreTheOnlyThingFiltered_controlArchiveWithPlainNamesIsAccepted() {
        val control = zipOf("trip/IMG_0001.jpg" to photo1, "trip/IMG_0003.jpg" to photo1, "trip/IMG_0004.jpg" to photo2)
        val uri = Uri.fromFile(File(appDir, "control.zip").apply { writeBytes(control) }).toString()

        val events = createIngestionSession(targetContext).use {
            it.collect(IngestionRequest.ZipArchive(uri, "control.zip"))
        }

        assertTrue(events.skips().isEmpty())
        assertEquals(3, events.completed().candidates.size)
    }

    @Test
    fun zipSlipEntry_rejectsArchiveWithZipSlip_andNothingEscapes() {
        val slip = zipOf("trip/IMG_0001.jpg" to photo1, "../evil.jpg" to photo2)
        val uri = Uri.fromFile(File(appDir, "slip.zip").apply { writeBytes(slip) }).toString()

        val session = createIngestionSession(targetContext)
        val events = session.use { it.collect(IngestionRequest.ZipArchive(uri, "slip.zip")) }

        val failure = events.failures().single()
        assertEquals("slip.zip", failure.displayName)
        val error = failure.error
        assertTrue("ZipSlip expected, got $error", error is IngestionError.ZipSlip)
        error as IngestionError.ZipSlip
        assertTrue("names the escaping entry: $error", error.entryName.contains("../evil.jpg"))
        assertTrue(error.message.startsWith("Archive entry escapes the extraction folder"))
        assertFalse(File(session.workDir.parentFile, "evil.jpg").exists())
        assertFalse(File(ingestRoot.parentFile, "evil.jpg").exists())
        assertTrue("completion still follows the failure", events.last() is IngestionEvent.Completed)
    }

    @Test
    fun highRatioBomb_rejectsArchiveWithZipBomb_andLeavesNoPartialFile() {
        val bomb = zipOf("trip/zeros.jpg" to ByteArray(16 * MIB))
        assertTrue("fixture is a real high-ratio archive (${bomb.size} bytes)", bomb.size < 64 * 1024)
        val uri = Uri.fromFile(File(appDir, "bomb.zip").apply { writeBytes(bomb) }).toString()

        createIngestionSession(targetContext).use { session ->
            val events = session.collect(IngestionRequest.ZipArchive(uri, "bomb.zip"))

            val error = events.failures().single().error
            assertTrue("ZipBomb expected, got $error", error is IngestionError.ZipBomb)
            assertTrue(error.message.startsWith("Archive looks like a decompression bomb: compression ratio"))
            assertTrue(events.completed().candidates.isEmpty())
            val leftovers = session.workDir.walkTopDown().filter { it.isFile }.toList()
            assertTrue("partial output deleted: $leftovers", leftovers.isEmpty())
        }
    }

    @Test
    fun archiveOverTotalLimit_rejectsArchiveWithLimitExceeded() {
        val noise = ByteArray(2 * MIB).also { Random(42).nextBytes(it) }
        val big = zipOf("trip/noise.jpg" to noise)
        val uri = Uri.fromFile(File(appDir, "big.zip").apply { writeBytes(big) }).toString()

        createIngestionSession(targetContext, ZipLimits(maxTotalBytes = 1L * MIB)).use { session ->
            val events = session.collect(IngestionRequest.ZipArchive(uri, "big.zip"))

            val error = events.failures().single().error
            assertTrue("LimitExceeded expected, got $error", error is IngestionError.LimitExceeded)
            assertTrue(error.message.contains("archive larger than ${MIB}"))
        }
    }

    private fun assertTripIngested(events: List<IngestionEvent>) {
        assertTrue("no archive failure: ${events.failures()}", events.failures().isEmpty())
        val done = events.completed()
        assertEquals(listOf("IMG_0001.jpg", "IMG_0002.jpg"), done.candidates.map { it.source.displayName }.sorted())
        done.candidates.forEach { assertEquals(ImageFormat.JPEG, it.format) }
        val sizes = done.candidates.associate { it.source.displayName to (it.widthPx to it.heightPx) }
        assertEquals(80 to 60, sizes["IMG_0001.jpg"])
        assertEquals(60 to 80, sizes["IMG_0002.jpg"])
        assertEquals(
            setOf(
                "trip/" to SkipReason.DIRECTORY,
                "__MACOSX/trip/._IMG_0001.jpg" to SkipReason.JUNK_FILE,
                "trip/.DS_Store" to SkipReason.JUNK_FILE,
                "trip/Thumbs.db" to SkipReason.JUNK_FILE,
            ),
            events.skips().map { it.displayName to it.reason }.toSet(),
        )
        assertEquals(4, events.skips().size)
        assertEquals(2, done.telemetry.candidatesAccepted)
        assertEquals((photo1.size + photo2.size).toLong(), done.telemetry.bytesExtracted)
    }
}
