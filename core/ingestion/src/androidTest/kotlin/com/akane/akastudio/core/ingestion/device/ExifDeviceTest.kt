package com.akane.akastudio.core.ingestion.device

import android.graphics.Color
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akane.akastudio.core.ingestion.api.ParsedImage
import com.akane.akastudio.core.ingestion.api.SourceOpener
import com.akane.akastudio.core.ingestion.metadata.ExifAndGainMapParser
import com.akane.akastudio.core.ingestion.model.ColorSpaceTag
import com.akane.akastudio.core.ingestion.model.ExifOrientation
import com.akane.akastudio.core.ingestion.model.ImageFormat
import com.akane.akastudio.core.ingestion.model.PhotoSource
import java.io.File
import java.io.FileInputStream
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Case 3: EXIF written by the platform [ExifInterface] on a device-encoded JPEG is read back by the production
 * [ExifAndGainMapParser] (which uses androidx ExifInterface), from an extracted file.
 *
 * GPS: [com.akane.akastudio.core.ingestion.model.CaptureMetadata] has no location field, so the parser cannot
 * report GPS. The test proves the written GPS survives in the file the parser reads, and that a GPS-tagged file
 * still yields every field the parser does report.
 */
@RunWith(AndroidJUnit4::class)
class ExifDeviceTest {

    private val dir = File(targetContext.cacheDir, "qa-exif").apply { mkdirs() }
    private val parser = ExifAndGainMapParser(
        opener = SourceOpener { FileInputStream((it as PhotoSource.ExtractedFile).path) },
        sizeOf = { File((it as PhotoSource.ExtractedFile).path).length() },
    )

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun platformWrittenOrientationDateTimeAndGps_areReadBack() {
        val bytes = withPlatformExif(
            deviceJpeg(WIDTH, HEIGHT, Color.YELLOW, Color.BLUE),
            mapOf(
                ExifInterface.TAG_ORIENTATION to ExifInterface.ORIENTATION_ROTATE_270.toString(),
                ExifInterface.TAG_DATETIME_ORIGINAL to "2023:12:31 23:59:58",
                ExifInterface.TAG_OFFSET_TIME_ORIGINAL to "-05:30",
                ExifInterface.TAG_GPS_LATITUDE to "48/1,51/1,2964/100",
                ExifInterface.TAG_GPS_LATITUDE_REF to "N",
                ExifInterface.TAG_GPS_LONGITUDE to "2/1,17/1,4020/100",
                ExifInterface.TAG_GPS_LONGITUDE_REF to "E",
            ),
        )

        // The platform reader sees the GPS that was written into these exact bytes.
        val latLong = FloatArray(2)
        assertTrue("GPS present in the written file", platformExif(bytes).getLatLong(latLong))
        assertArrayEquals(floatArrayOf(48.8582f, 2.2945f), latLong, 1e-3f)

        val parsed = parse("tagged.jpg", bytes)

        val expected = LocalDateTime.of(2023, 12, 31, 23, 59, 58).toInstant(ZoneOffset.of("-05:30")).toEpochMilli()
        assertEquals(ImageFormat.JPEG, parsed.format)
        assertEquals(ExifOrientation.ROTATE_270, parsed.metadata.orientation)
        assertEquals(expected, parsed.metadata.captureEpochMillis)
        assertTrue(parsed.metadata.captureHasUtcOffset)
        assertEquals("ROTATE_270 swaps to upright size", HEIGHT to WIDTH, parsed.widthPx to parsed.heightPx)
        assertEquals(bytes.size.toLong(), parsed.byteSize)
        assertEquals(ColorSpaceTag.SRGB, parsed.metadata.colorSpace)
        assertFalse(parsed.metadata.hasUltraHdrGainMap)
    }

    /** The legacy DateTime tag is the fallback; without an offset the device zone is used and flagged as such. */
    @Test
    fun dateTimeWithoutOffset_fallsBackAndIsFlaggedZoneless() {
        val bytes = withPlatformExif(
            deviceJpeg(WIDTH, HEIGHT, Color.RED, Color.CYAN),
            mapOf(ExifInterface.TAG_DATETIME to "2022:02:03 04:05:06"),
        )

        val parsed = parse("legacy.jpg", bytes)

        val expected = LocalDateTime.of(2022, 2, 3, 4, 5, 6)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, parsed.metadata.captureEpochMillis)
        assertFalse(parsed.metadata.captureHasUtcOffset)
    }

    /** Negative control: the same encoder output without EXIF reports no capture time and NORMAL orientation. */
    @Test
    fun deviceJpegWithoutExif_reportsNoCaptureTimeAndNormalOrientation() {
        val bytes = deviceJpeg(WIDTH, HEIGHT, Color.YELLOW, Color.BLUE)
        assertNull(platformExif(bytes).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))

        val parsed = parse("plain.jpg", bytes)

        assertNull(parsed.metadata.captureEpochMillis)
        assertEquals(ExifOrientation.NORMAL, parsed.metadata.orientation)
        assertEquals(WIDTH to HEIGHT, parsed.widthPx to parsed.heightPx)
    }

    private fun parse(name: String, bytes: ByteArray): ParsedImage {
        val file = File(dir, name).apply { writeBytes(bytes) }
        val parsed = runBlocking { parser.parse(PhotoSource.ExtractedFile(file.absolutePath, name, name)) }
        return checkNotNull(parsed) { "parser rejected $name" }
    }

    private companion object {
        const val WIDTH = 120
        const val HEIGHT = 72
    }
}
