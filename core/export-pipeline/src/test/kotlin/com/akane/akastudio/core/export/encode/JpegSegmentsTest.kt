package com.akane.akastudio.core.export.encode

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

class JpegSegmentsTest {

    private val exifHeader = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0)
    private val tiff = ExifWriter.tiff(ExifFields(1_720_000_000_000L, "Lisbon", "AkaStudio", 16, 16))

    private fun jpeg(width: Int, height: Int, exif: ByteArray? = null, shade: Int = 0x40): ByteArray {
        val out = ByteArrayOutputStream()
        JpegEncoder.write(out, width, height, { y, row -> row.fill(0xFF shl 24 or (shade shl 16) or (y * 8 shl 8) or 0x80) }, 90, exifTiff = exif)
        return out.toByteArray()
    }

    private fun exifSegments(file: ByteArray) =
        parseJpegSegments(file).filter { it.marker == 0xE1 && it.payload.copyOf(6).contentEquals(exifHeader) }

    @Test
    fun noExistingExif_app1BecomesFirstSegment_restUnchanged() {
        val original = jpeg(16, 16)
        val spliced = JpegSegments.insertApp1AfterSoi(original, tiff)

        val first = parseJpegSegments(spliced).first()
        assertEquals(0xE1, first.marker)
        assertArrayEquals(exifHeader + tiff, first.payload)
        val segmentSize = 4 + exifHeader.size + tiff.size
        assertEquals(original.size + segmentSize, spliced.size)
        assertArrayEquals(original.copyOfRange(2, original.size), spliced.copyOfRange(2 + segmentSize, spliced.size))
        assertNotNull(ImageIO.read(ByteArrayInputStream(spliced)))
    }

    @Test
    fun existingExif_isReplacedNotDuplicated() {
        val oldTiff = ExifWriter.tiff(ExifFields(null, "old description that is longer", "Other", 16, 16))
        val original = jpeg(16, 16, exif = oldTiff)
        val spliced = JpegSegments.insertApp1AfterSoi(original, tiff)

        val exif = exifSegments(spliced)
        assertEquals(1, exif.size)
        assertArrayEquals(exifHeader + tiff, exif.single().payload)
        assertEquals(0xE1, parseJpegSegments(spliced).first().marker)
        assertEquals(original.size - oldTiff.size + tiff.size, spliced.size)
        assertEquals(16, ImageIO.read(ByteArrayInputStream(spliced)).width)
    }

    @Test
    fun ultraHdrLayout_mpfOffsetsStillPointAtTheGainMapJpeg() {
        for (order in listOf(ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN)) {
            val primary = jpeg(32, 24)
            val secondary = jpeg(8, 6, shade = 0xC0)
            val file = withMpf(primary, secondary, order)
            val before = mpfSecondary(file)
            assertEquals("$order fixture: the MPF entry points at the gain-map SOI", 0xFFD8, u16(file, before.absolute))
            assertEquals("$order fixture: entry 0 spans the primary image", before.absolute, before.primarySize)

            val spliced = JpegSegments.insertApp1AfterSoi(file, tiff)
            val after = mpfSecondary(spliced)

            assertEquals("$order: MPF offsets are relative to the MP header, so they do not change", before.relative, after.relative)
            assertEquals(before.absolute + 4 + exifHeader.size + tiff.size, after.absolute)
            assertEquals(0xFFD8, u16(spliced, after.absolute))
            assertArrayEquals(secondary, spliced.copyOfRange(after.absolute, spliced.size))
            assertEquals(0xE1, parseJpegSegments(spliced).first().marker)
            assertEquals("$order: entry 0's size grows with the inserted APP1", after.absolute, after.primarySize)
        }
    }

    @Test
    fun ultraHdrLayout_replacingAnExifBeforeTheMpf_resizesEntryZero() {
        val oldTiff = ExifWriter.tiff(ExifFields(null, "a much longer old description than the new one has", "Other", 16, 16))
        for (order in listOf(ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN)) {
            val secondary = jpeg(8, 6, shade = 0xC0)
            val file = withMpf(jpeg(32, 24), secondary, order, leadingExif = oldTiff)
            val before = mpfSecondary(file)
            assertEquals("$order fixture: entry 0 spans the primary image", before.absolute, before.primarySize)

            val spliced = JpegSegments.insertApp1AfterSoi(file, tiff)
            val after = mpfSecondary(spliced)

            assertEquals(1, exifSegments(spliced).size)
            assertEquals("$order: the MPF offsets do not change", before.relative, after.relative)
            assertEquals(before.absolute - oldTiff.size + tiff.size, after.absolute)
            assertArrayEquals(secondary, spliced.copyOfRange(after.absolute, spliced.size))
            assertEquals("$order: entry 0's size follows the replaced APP1", after.absolute, after.primarySize)
        }
    }

    @Test
    fun malformedMpf_isRefused() {
        val file = withMpf(jpeg(16, 16), jpeg(4, 4))
        val badOrder = file.copyOf().also { it[10] = 'X'.code.toByte() }
        assertThrows(IllegalArgumentException::class.java) { JpegSegments.insertApp1AfterSoi(badOrder, tiff) }
        val ifdOutside = file.copyOf().also { it[14] = 0x7F }
        assertThrows(IllegalArgumentException::class.java) { JpegSegments.insertApp1AfterSoi(ifdOutside, tiff) }
    }

    @Test
    fun exifAfterMpf_isRefused() {
        val primaryWithExif = jpeg(16, 16, exif = tiff)
        val file = withMpf(primaryWithExif, jpeg(4, 4))
        assertThrows(IllegalArgumentException::class.java) { JpegSegments.insertApp1AfterSoi(file, tiff) }
    }

    @Test
    fun invalidInputs_areRefused() {
        val valid = jpeg(8, 8)
        assertThrows(IllegalArgumentException::class.java) { JpegSegments.insertApp1AfterSoi(valid.copyOfRange(2, valid.size), tiff) }
        assertThrows(IllegalArgumentException::class.java) { JpegSegments.insertApp1AfterSoi(valid, ByteArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { JpegSegments.insertApp1AfterSoi(valid, ByteArray(65_528)) }
        val truncated = valid.copyOf(30)
        assertThrows(IllegalArgumentException::class.java) { JpegSegments.insertApp1AfterSoi(truncated, tiff) }
    }

    @Test
    fun largestExifPayload_fills65533Bytes() {
        val spliced = JpegSegments.insertApp1AfterSoi(jpeg(8, 8), ByteArray(65_527) { it.toByte() })
        assertEquals(0xFFFF, u16(spliced, 4))
        assertEquals(65_533, parseJpegSegments(spliced).first().payload.size)
    }

    // ---- MPF fixture -------------------------------------------------------------------------------------------

    /** The second MP entry's offset ([relative] to the MP header, [absolute] in the file) and entry 0's image size. */
    private class MpfTarget(val relative: Int, val absolute: Int, val primarySize: Int)

    /**
     * Builds an Ultra HDR-shaped file: [primary] (without Exif) with an APP2 `MPF\0` segment right after its SOI, or
     * after an APP1 Exif segment carrying [leadingExif], whose second MP entry points at [secondary], appended after
     * the primary's EOI. The MP header is written in [order]. Entry 0 holds the primary's size and offset 0; the
     * other offsets are relative to the MP header (the TIFF header that follows `MPF\0`), as CIPA DC-007 specifies.
     */
    private fun withMpf(
        primary: ByteArray,
        secondary: ByteArray,
        order: ByteOrder = ByteOrder.BIG_ENDIAN,
        leadingExif: ByteArray? = null,
    ): ByteArray {
        val exifSegment = leadingExif?.let { tiff ->
            val payload = exifHeader + tiff
            byteArrayOf(0xFF.toByte(), 0xE1.toByte(), ((payload.size + 2) ushr 8).toByte(), (payload.size + 2).toByte()) + payload
        } ?: ByteArray(0)
        val ifdEntries = 3
        val entriesOffset = 8 + 2 + ifdEntries * 12 + 4
        val mpHeaderSize = entriesOffset + 2 * 16
        val app2Size = 4 + 4 + mpHeaderSize
        val primarySize = primary.size + exifSegment.size + app2Size
        val mpHeaderAbsolute = 2 + exifSegment.size + 4 + 4
        val endian = if (order == ByteOrder.BIG_ENDIAN) 'M' else 'I'
        val mp = ByteBuffer.allocate(mpHeaderSize).order(order)
        mp.put(endian.code.toByte()).put(endian.code.toByte()).putShort(0x2A).putInt(8)
        mp.putShort(ifdEntries.toShort())
        mp.putShort(0xB000.toShort()).putShort(7).putInt(4).put("0100".toByteArray(Charsets.US_ASCII))
        mp.putShort(0xB001.toShort()).putShort(4).putInt(1).putInt(2)
        mp.putShort(0xB002.toShort()).putShort(7).putInt(32).putInt(entriesOffset)
        mp.putInt(0)
        mp.putInt(0x030000).putInt(primarySize).putInt(0).putShort(0).putShort(0)
        mp.putInt(0).putInt(secondary.size).putInt(primarySize - mpHeaderAbsolute).putShort(0).putShort(0)
        val out = ByteArrayOutputStream()
        out.write(primary, 0, 2)
        out.write(exifSegment)
        out.write(byteArrayOf(0xFF.toByte(), 0xE2.toByte(), ((app2Size - 2) ushr 8).toByte(), (app2Size - 2).toByte()))
        out.write(byteArrayOf(0x4D, 0x50, 0x46, 0))
        out.write(mp.array())
        out.write(primary, 2, primary.size - 2)
        out.write(secondary)
        return out.toByteArray()
    }

    /** Parses the MPF IFD of [file] (either byte order) and resolves the second MP entry against the MP header. */
    private fun mpfSecondary(file: ByteArray): MpfTarget {
        var position = 2
        while (true) {
            check(file[position] == 0xFF.toByte()) { "expected marker at $position" }
            val marker = file[position + 1].toInt() and 0xFF
            check(marker != 0xDA) { "no MPF segment before SOS" }
            val length = u16(file, position + 2)
            val payload = position + 4
            if (marker == 0xE2 && String(file, payload, 4, Charsets.ISO_8859_1) == "MPF\u0000") {
                val header = payload + 4
                val order = when (String(file, header, 2, Charsets.ISO_8859_1)) {
                    "MM" -> ByteOrder.BIG_ENDIAN
                    "II" -> ByteOrder.LITTLE_ENDIAN
                    else -> error("bad MP endian marker")
                }
                val mp = ByteBuffer.wrap(file, header, length - 2 - 4).slice().order(order)
                val ifd = mp.getInt(4)
                val entry = (0 until mp.getShort(ifd).toInt()).map { ifd + 2 + it * 12 }.single { mp.getShort(it) == 0xB002.toShort() }
                val entries = mp.getInt(entry + 8)
                val relative = mp.getInt(entries + 16 + 8)
                return MpfTarget(relative, header + relative, mp.getInt(entries + 4))
            }
            position += 2 + length
        }
    }

    private fun u16(data: ByteArray, offset: Int): Int = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
}
