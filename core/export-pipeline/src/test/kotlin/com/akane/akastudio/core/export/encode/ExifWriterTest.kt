package com.akane.akastudio.core.export.encode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class ExifWriterTest {

    private val zone = ZoneId.of("Asia/Kolkata") // +05:30, exercises minutes in the offset
    private val capture = LocalDateTime.of(2025, 7, 14, 18, 5, 9).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun roundTrip_ifd0AndExifIfdCarryEveryField() {
        val tiff = ExifWriter.tiff(ExifFields(capture, "Lisbon at dusk", "AkaStudio 1.0", 1080, 1920), zone)
        val reader = TestTiffReader(tiff)
        assertEquals(8, reader.ifd0Offset)
        val ifd0 = reader.readIfd(reader.ifd0Offset)

        assertEquals("Lisbon at dusk", ifd0[ExifWriter.TAG_IMAGE_DESCRIPTION]!!.ascii())
        assertEquals("AkaStudio 1.0", ifd0[ExifWriter.TAG_SOFTWARE]!!.ascii())
        assertEquals(1L, ifd0[ExifWriter.TAG_ORIENTATION]!!.uint())
        assertEquals(72L to 1L, ifd0[ExifWriter.TAG_X_RESOLUTION]!!.rational())
        assertEquals(72L to 1L, ifd0[ExifWriter.TAG_Y_RESOLUTION]!!.rational())
        assertEquals(2L, ifd0[ExifWriter.TAG_RESOLUTION_UNIT]!!.uint())
        assertEquals(0, ifd0.next)

        val exif = reader.readIfd(ifd0[ExifWriter.TAG_EXIF_IFD_POINTER]!!.uint().toInt())
        assertEquals("2025:07:14 18:05:09", exif[ExifWriter.TAG_DATE_TIME_ORIGINAL]!!.ascii())
        assertEquals("+05:30", exif[ExifWriter.TAG_OFFSET_TIME_ORIGINAL]!!.ascii())
        assertEquals(1L, exif[ExifWriter.TAG_COLOR_SPACE]!!.uint())
        assertEquals(1080L, exif[ExifWriter.TAG_PIXEL_X_DIMENSION]!!.uint())
        assertEquals(1920L, exif[ExifWriter.TAG_PIXEL_Y_DIMENSION]!!.uint())
        val version = exif[ExifWriter.TAG_EXIF_VERSION]!!
        assertEquals(7, version.type)
        assertEquals("0232", String(version.value, Charsets.US_ASCII))
        assertEquals(0, exif.next)
    }

    @Test
    fun layout_entriesSortedAndOutOfLineValuesAlignedWithoutOverlap() {
        val tiff = ExifWriter.tiff(ExifFields(capture, "odd", "Seven!!", 17, 9), zone)
        val reader = TestTiffReader(tiff)
        val ifd0 = reader.readIfd(reader.ifd0Offset)
        val exif = reader.readIfd(ifd0[ExifWriter.TAG_EXIF_IFD_POINTER]!!.uint().toInt())
        val ranges = ArrayList<IntRange>()
        for (ifd in listOf(ifd0, exif)) {
            val tags = ifd.entries.map { it.tag }
            assertEquals(tags.sorted(), tags)
            ranges += ifd.offset until ifd.offset + 2 + 12 * ifd.entries.size + 4
            ifd.entries.mapNotNull { e -> e.valueOffset?.let { it until it + e.value.size } }.forEach { ranges += it }
        }
        val sorted = ranges.sortedBy { it.first }
        for (i in 1 until sorted.size) assertTrue("overlap $sorted", sorted[i].first > sorted[i - 1].last)
        assertTrue(sorted.last().last < tiff.size)
        // "Seven!!" + NUL is 8 bytes, stored out of line; "odd" + NUL fits inline.
        assertTrue(ifd0[ExifWriter.TAG_SOFTWARE]!!.valueOffset != null)
        assertNull(ifd0[ExifWriter.TAG_IMAGE_DESCRIPTION]!!.valueOffset)
        assertEquals("odd", ifd0[ExifWriter.TAG_IMAGE_DESCRIPTION]!!.ascii())
    }

    @Test
    fun optionalFields_omittedWhenNull() {
        val tiff = ExifWriter.tiff(ExifFields(null, null, "AkaStudio", 2160, 3840), zone)
        val reader = TestTiffReader(tiff)
        val ifd0 = reader.readIfd(reader.ifd0Offset)
        assertNull(ifd0[ExifWriter.TAG_IMAGE_DESCRIPTION])
        val exif = reader.readIfd(ifd0[ExifWriter.TAG_EXIF_IFD_POINTER]!!.uint().toInt())
        assertNull(exif[ExifWriter.TAG_DATE_TIME_ORIGINAL])
        assertNull(exif[ExifWriter.TAG_OFFSET_TIME_ORIGINAL])
        assertEquals(3840L, exif[ExifWriter.TAG_PIXEL_Y_DIMENSION]!!.uint())
    }

    @Test
    fun strings_utf8WithEmbeddedNulRemoved() {
        val tiff = ExifWriter.tiff(ExifFields(null, "Café\u0000 night", "Aka", 1, 1), zone)
        val reader = TestTiffReader(tiff)
        assertEquals("Café night", reader.readIfd(reader.ifd0Offset)[ExifWriter.TAG_IMAGE_DESCRIPTION]!!.ascii())
    }

    @Test
    fun offsets_formatSignAndUtc() {
        assertEquals("+00:00", ExifWriter.formatOffset(ZoneOffset.UTC.totalSeconds))
        assertEquals("-03:30", ExifWriter.formatOffset(-(3 * 3600 + 30 * 60)))
        assertEquals("+14:00", ExifWriter.formatOffset(14 * 3600))
    }

    @Test
    fun utcCapture_writesUtcWallClock() {
        val millis = LocalDateTime.of(2024, 2, 29, 23, 59, 59).toInstant(ZoneOffset.UTC).toEpochMilli()
        val tiff = ExifWriter.tiff(ExifFields(millis, null, "Aka", 4, 4), ZoneOffset.UTC)
        val reader = TestTiffReader(tiff)
        val exif = reader.readIfd(reader.readIfd(8)[ExifWriter.TAG_EXIF_IFD_POINTER]!!.uint().toInt())
        assertEquals("2024:02:29 23:59:59", exif[ExifWriter.TAG_DATE_TIME_ORIGINAL]!!.ascii())
        assertEquals("+00:00", exif[ExifWriter.TAG_OFFSET_TIME_ORIGINAL]!!.ascii())
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonPositiveDimensions_rejected() {
        ExifWriter.tiff(ExifFields(null, null, "Aka", 0, 10))
    }
}
