package com.akane.akastudio.core.ingestion.device

import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akane.akastudio.core.ingestion.api.IngestionRequest
import com.akane.akastudio.core.ingestion.createIngestionSession
import com.akane.akastudio.core.ingestion.model.ExifOrientation
import com.akane.akastudio.core.ingestion.model.ImageFormat
import com.akane.akastudio.core.ingestion.model.IngestionTelemetry
import com.akane.akastudio.core.ingestion.model.PhotoSource
import com.akane.akastudio.core.ingestion.model.SkipReason
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Case 1: JPEGs inserted through the real MediaStore are ingested through the real picker/ContentUri wiring
 * ([createIngestionSession]: MediaPickerIngestor + ContentResolver opener + OpenableColumns size).
 */
@RunWith(AndroidJUnit4::class)
class PickerPathDeviceTest {

    private class Written(
        val name: String,
        val uri: Uri,
        val bytes: Int,
        val epochMillis: Long,
        val exifOrientation: Int,
    )

    private val store = QaMediaStore()
    private lateinit var written: List<Written>

    @Before
    fun insertPhotos() {
        written = (1..PHOTO_COUNT).map { i ->
            val orientation = when (i) {
                2 -> ExifInterface.ORIENTATION_ROTATE_90
                4 -> ExifInterface.ORIENTATION_ROTATE_180
                else -> ExifInterface.ORIENTATION_NORMAL
            }
            val local = LocalDateTime.of(2024, 7, 10 + i, 18, 30, i)
            val bytes = withPlatformExif(
                deviceJpeg(WIDTH, HEIGHT, Color.rgb(20 * i, 40, 200), Color.rgb(250, 20 * i, 10)),
                mapOf(
                    ExifInterface.TAG_DATETIME_ORIGINAL to "2024:07:${10 + i} 18:30:0$i",
                    ExifInterface.TAG_OFFSET_TIME_ORIGINAL to "+02:00",
                    ExifInterface.TAG_ORIENTATION to orientation.toString(),
                ),
            )
            val epoch = local.toInstant(ZoneOffset.ofHours(2)).toEpochMilli()
            val name = "qa_picker_$i.jpg"
            Written(name, store.insertImage(name, bytes, dateTakenMillis = epoch), bytes.size, epoch, orientation)
        }
    }

    @After
    fun deleteRows() {
        assertEquals("every inserted MediaStore row is deleted", PHOTO_COUNT, store.deleteAll())
    }

    @Test
    fun everyInsertedPhotoIsReadWithItsWrittenMetadata() {
        val events = createIngestionSession(targetContext).use { session ->
            session.collect(IngestionRequest.PickedMedia(written.map { it.uri.toString() }))
        }

        val done = events.completed()
        assertEquals(
            IngestionTelemetry(sourcesTotal = PHOTO_COUNT, filesRead = PHOTO_COUNT, candidatesAccepted = PHOTO_COUNT),
            done.telemetry,
        )
        assertTrue("no skips: ${events.skips()}", events.skips().isEmpty())
        val byUri = done.candidates.associateBy { (it.source as PhotoSource.ContentUri).uri }
        for (w in written) {
            val candidate = checkNotNull(byUri[w.uri.toString()]) { "missing candidate for ${w.name}" }
            val orientation = ExifOrientation.fromExif(w.exifOrientation)
            assertEquals(w.name, candidate.source.displayName)
            assertEquals(ImageFormat.JPEG, candidate.format)
            assertEquals("${w.name} size", w.bytes.toLong(), candidate.byteSize)
            assertEquals("${w.name} dateTaken", w.epochMillis, candidate.metadata.captureEpochMillis)
            assertTrue("${w.name} offset", candidate.metadata.captureHasUtcOffset)
            assertEquals("${w.name} orientation", orientation, candidate.metadata.orientation)
            val (uprightW, uprightH) = if (orientation.swapsDimensions) HEIGHT to WIDTH else WIDTH to HEIGHT
            assertEquals("${w.name} upright size", uprightW to uprightH, candidate.widthPx to candidate.heightPx)
        }
        // Completion order is capture time, which the fixture makes ascending with the index.
        assertEquals(written.map { it.name }, done.candidates.map { it.source.displayName })
    }

    /** SQ-54 on device, control side: a URI repeated inside one picker result is deduped by the resolver. */
    @Test
    fun sameUriTwiceInOnePickerResult_isDedupedWithoutASkip() {
        val uris = written.map { it.uri.toString() }
        val events = createIngestionSession(targetContext).use { session ->
            session.collect(IngestionRequest.PickedMedia(uris + uris.first()))
        }

        assertEquals(
            IngestionTelemetry(sourcesTotal = PHOTO_COUNT, filesRead = PHOTO_COUNT, candidatesAccepted = PHOTO_COUNT),
            events.completed().telemetry,
        )
        assertTrue(events.skips().isEmpty())
    }

    /**
     * SQ-54 on device: a URI that reaches the pipeline twice (two picker results in one run) gives exactly one
     * DUPLICATE skip, and the read count stays at the distinct count. A duplicate can never produce the field
     * report's "N read, 1 skipped" shape for N distinct picks.
     */
    @Test
    fun sameUriAcrossTwoPickerResults_isExactlyOneDuplicateSkip() {
        val uris = written.map { it.uri.toString() }
        val events = createIngestionSession(targetContext).use { session ->
            session.collect(IngestionRequest.PickedMedia(uris), IngestionRequest.PickedMedia(listOf(uris.first())))
        }

        val skip = events.skips().single()
        assertEquals(SkipReason.DUPLICATE, skip.reason)
        assertEquals(written.first().name, skip.displayName)
        assertEquals(
            IngestionTelemetry(
                sourcesTotal = PHOTO_COUNT + 1,
                filesRead = PHOTO_COUNT,
                candidatesAccepted = PHOTO_COUNT,
                skipped = 1,
            ),
            events.completed().telemetry,
        )
    }

    private companion object {
        const val PHOTO_COUNT = 5
        const val WIDTH = 96
        const val HEIGHT = 64
    }
}
