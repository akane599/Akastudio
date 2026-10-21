package com.akane.akastudio.core.ingestion.device

import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akane.akastudio.core.ingestion.api.IngestionRequest
import com.akane.akastudio.core.ingestion.api.ZipLimits
import com.akane.akastudio.core.ingestion.createIngestionSession
import com.akane.akastudio.core.ingestion.model.IngestionError
import com.akane.akastudio.core.ingestion.model.IngestionEvent
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Case 5: the extractor's free-space provider in [createIngestionSession] is the work dir volume's usable space.
 * Its value is not exposed, so the test brackets it through [ZipLimits.freeSpaceReserveBytes] (the guard compares
 * provider − reserve against the entry estimate).
 */
@RunWith(AndroidJUnit4::class)
class StorageGuardDeviceTest {

    private val appDir = File(targetContext.filesDir, "qa-storage").apply { mkdirs() }
    private val zipUri = Uri.fromFile(
        File(appDir, "one.zip").apply {
            writeBytes(zipOf("IMG_1.jpg" to deviceJpeg(64, 48, Color.BLACK, Color.WHITE)))
        },
    ).toString()

    @After
    fun cleanUp() {
        appDir.deleteRecursively()
    }

    @Test
    fun usableBytesForTheIngestVolumeIsPositive() {
        val usable = usableBytes()
        qaLog("noBackupFilesDir.usableSpace = $usable")
        assertTrue("usable bytes on device: $usable", usable > 0)
    }

    /** Leaving half of today's usable space as reserve still fits a small entry: the provider is not 0. */
    @Test
    fun reserveBelowUsable_extracts() {
        val events = ingestWithReserve(usableBytes() / 2)

        assertTrue("no failure: ${events.failures()}", events.failures().isEmpty())
        assertEquals(1, events.completed().candidates.size)
    }

    /** Negative control: a reserve above the usable figure trips the guard with the documented error. */
    @Test
    fun reserveAboveUsable_rejectsWithInsufficientStorage() {
        val events = ingestWithReserve(usableBytes() * 2)

        val error = events.failures().single().error
        assertTrue("InsufficientStorage expected, got $error", error is IngestionError.InsufficientStorage)
        error as IngestionError.InsufficientStorage
        assertEquals(0L, error.availableBytes)
        assertTrue(error.requiredBytes > 0)
        assertTrue(error.message.startsWith("Not enough free storage"))
        assertTrue(events.completed().candidates.isEmpty())
    }

    private fun ingestWithReserve(reserve: Long): List<IngestionEvent> =
        createIngestionSession(targetContext, ZipLimits(freeSpaceReserveBytes = reserve)).use {
            it.collect(IngestionRequest.ZipArchive(zipUri, "one.zip"))
        }

    private fun usableBytes(): Long = targetContext.noBackupFilesDir.usableSpace
}
