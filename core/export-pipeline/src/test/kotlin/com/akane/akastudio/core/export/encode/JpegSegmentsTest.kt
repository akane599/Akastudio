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
        val primary = jpeg(32, 24)
        val secondary = jpeg(8, 6, shade = 0xC0)
        val file = withMpf(primary, secondary)
        val before = mpfSecondary(file)
        assertEquals("fixture: the MPF entry points at the gain-map SOI", 0xFFD8, u16(file, before.absolute))

        val spliced = JpegSegments.insertApp1AfterSoi(file, tiff)
        val after = mpfSecondary(spliced)

        assertEquals("MPF offsets are relative to the MP header, so they do not change", before.relative, after.relative)
        assertEquals(before.absolute + 4 + exifHeader.size + tiff.size, after.absolute)
        assertEquals(0xFFD8, u16(spliced, after.absolute))
        assertArrayEquals(secondary, spliced.copyOfRange(after.absolute, spliced.size))
        assertEquals(0xE1, parseJpegSegments(spliced).first().marker)
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

    private class MpfTarget(val relative: Int, val absolute: Int)

    /**
     * Builds an Ultra HDR-shaped file: [primary] with an APP2 `MPF\0` segment right after its SOI whose second MP
     * entry points at [secondary], appended after the primary's EOI. Offsets are relative to the MP header (the TIFF
     * header that follows `MPF\0`), as CIPA DC-007 specifies.
     */
    private fun withMpf(primary: ByteArray, secondary: ByteArray): ByteArray {
        val ifdEntries = 3
        val entriesOffset = 8 + 2 + ifdEntries * 12 + 4
        val mpHeaderSize = entriesOffset + 2 * 16
        val app2Size = 4 + 4 + mpHeaderSize
        val primarySize = primary.size + app2Size
        val mpHeaderAbsolute = 2 + 4 + 4
        val mp = ByteBuffer.allocate(mpHeaderSize).order(ByteOrder.BIG_ENDIAN)
        mp.put('M'.code.toByte()).put('M'.code.toByte()).putShort(0x2A).putInt(8)
        mp.putShort(ifdEntries.toShort())
        mp.putShort(0xB000.toShort()).putShort(7).putInt(4).put("0100".toByteArray(Charsets.US_ASCII))
        mp.putShort(0xB001.toShort()).putShort(4).putInt(1).putInt(2)
        mp.putShort(0xB002.toShort()).putShort(7).putInt(32).putInt(entriesOffset)
        mp.putInt(0)
        mp.putInt(0x030000).putInt(primarySize).putInt(0).putShort(0).putShort(0)
        mp.putInt(0).putInt(secondary.size).putInt(primarySize - mpHeaderAbsolute).putShort(0).putShort(0)
        val out = ByteArrayOutputStream()
        out.write(primary, 0, 2)
        out.write(byteArrayOf(0xFF.toByte(), 0xE2.toByte(), ((app2Size - 2) ushr 8).toByte(), (app2Size - 2).toByte()))
        out.write(byteArrayOf(0x4D, 0x50, 0x46, 0))
        out.write(mp.array())
        out.write(primary, 2, primary.size - 2)
        out.write(secondary)
        return out.toByteArray()
    }

    /** Parses the MPF IFD of [file] and resolves the second MP entry's offset to an absolute file position. */
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
                check(String(file, header, 2, Charsets.ISO_8859_1) == "MM") { "fixture is big-endian" }
                val ifd = header + u32(file, header + 4)
                val count = u16(file, ifd)
                val entry = (0 until count).map { ifd + 2 + it * 12 }.single { u16(file, it) == 0xB002 }
                val entries = header + u32(file, entry + 8)
                val relative = u32(file, entries + 16 + 8)
                return MpfTarget(relative, header + relative)
            }
            position += 2 + length
        }
    }

    private fun u16(data: ByteArray, offset: Int): Int = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun u32(data: ByteArray, offset: Int): Int = (u16(data, offset) shl 16) or u16(data, offset + 2)
}
