package com.akane.akastudio.core.export.encode

/** Marker-segment edits on finished JPEG files (pure JVM). */
object JpegSegments {

    /**
     * Returns [jpeg] with an APP1 `Exif\0\0` segment carrying [exifTiff] (a TIFF block, see [ExifWriter]) immediately
     * after SOI. An existing APP1 Exif segment is replaced: it is removed and the new one takes the first position.
     *
     * Bytes only move by a constant amount from SOI onwards, so offsets measured from a point inside the file stay
     * valid. In particular an Ultra HDR file's MPF entries, which are relative to the MP header inside its APP2
     * segment, still point at the gain-map JPEG. Removing an Exif segment that sits after the MPF segment would
     * shorten the distance between the two, so that layout is refused. What does change is the primary image's
     * length, so MP entry 0's Individual Image Size changes by the same amount.
     *
     * @throws IllegalArgumentException when [jpeg] does not start with SOI, its marker segments before SOS are
     *   malformed, it carries more than one Exif or MPF segment or an Exif one after the MPF segment, its MPF segment
     *   is malformed, [exifTiff] is empty, or the APP1 payload (`Exif\0\0` plus [exifTiff]) exceeds 65533 bytes.
     */
    fun insertApp1AfterSoi(jpeg: ByteArray, exifTiff: ByteArray): ByteArray {
        require(exifTiff.isNotEmpty()) { "EXIF block is empty" }
        val payloadSize = EXIF_HEADER.size + exifTiff.size
        require(payloadSize <= MAX_SEGMENT_PAYLOAD) { "EXIF block too large for APP1: ${exifTiff.size} bytes" }
        require(jpeg.size >= 4 && byteAt(jpeg, 0) == 0xFF && byteAt(jpeg, 1) == SOI) { "JPEG does not start with SOI" }
        val layout = scanHeaderSegments(jpeg)
        val existing = layout.exif
        val removed = existing?.let { it.last - it.first + 1 } ?: 0
        val out = ByteArray(jpeg.size - removed + MARKER_AND_LENGTH + payloadSize)
        out[0] = 0xFF.toByte()
        out[1] = SOI.toByte()
        out[2] = 0xFF.toByte()
        out[3] = APP1.toByte()
        out[4] = ((payloadSize + 2) ushr 8).toByte()
        out[5] = (payloadSize + 2).toByte()
        EXIF_HEADER.copyInto(out, 6)
        exifTiff.copyInto(out, 6 + EXIF_HEADER.size)
        val rest = 6 + payloadSize
        if (existing == null) {
            jpeg.copyInto(out, rest, 2, jpeg.size)
        } else {
            jpeg.copyInto(out, rest, 2, existing.first)
            jpeg.copyInto(out, rest + existing.first - 2, existing.last + 1, jpeg.size)
        }
        layout.mpfPayload?.let { mpf ->
            // Everything inserted or removed sits before the MPF segment, so the segment moved by the size change.
            val delta = out.size - jpeg.size
            growPrimaryImage(out, mpf.first + delta, mpf.last + 1 + delta, delta)
        }
        return out
    }

    /**
     * Segments before SOS that the splice cares about: the APP1 Exif segment (marker included) and the payload of the
     * APP2 MPF segment (from `MPF\0`), each `null` when absent.
     */
    private class HeaderLayout(val exif: IntRange?, val mpfPayload: IntRange?)

    private fun scanHeaderSegments(jpeg: ByteArray): HeaderLayout {
        var exif: IntRange? = null
        var mpf: IntRange? = null
        var position = 2
        while (position < jpeg.size) {
            require(byteAt(jpeg, position) == 0xFF) { "Expected a JPEG marker at offset $position" }
            while (position + 1 < jpeg.size && byteAt(jpeg, position + 1) == 0xFF) position++ // fill bytes
            require(position + 1 < jpeg.size) { "Truncated JPEG marker at offset $position" }
            val marker = byteAt(jpeg, position + 1)
            if (marker == SOS || marker == EOI) return HeaderLayout(exif, mpf)
            require(marker != SOI && marker !in RST0..RST7 && marker != TEM) {
                "Unexpected standalone marker 0x%02X at offset $position".format(marker)
            }
            require(position + MARKER_AND_LENGTH <= jpeg.size) { "Truncated segment header at offset $position" }
            val length = (byteAt(jpeg, position + 2) shl 8) or byteAt(jpeg, position + 3)
            val end = position + 2 + length
            require(length >= 2 && end <= jpeg.size) { "Segment 0x%02X at offset $position overruns the file".format(marker) }
            val payload = position + MARKER_AND_LENGTH
            when {
                marker == APP1 && startsWith(jpeg, payload, end, EXIF_HEADER) -> {
                    require(exif == null) { "JPEG carries more than one Exif segment" }
                    require(mpf == null) { "Exif segment after the MPF segment: replacing it would move the MPF image offsets" }
                    exif = position until end
                }
                marker == APP2 && startsWith(jpeg, payload, end, MPF_HEADER) -> {
                    require(mpf == null) { "JPEG carries more than one MPF segment" }
                    mpf = payload until end
                }
            }
            position = end
        }
        throw IllegalArgumentException("JPEG has no SOS or EOI marker")
    }

    /**
     * Adds [delta] to MP entry 0's Individual Image Size in the MPF payload `data[from, end)` (CIPA DC-007): `MPF\0`,
     * then the MP header (byte order `MM`/`II`, 0x002A, offset of the MP Index IFD), whose MP Entry tag (0xB002)
     * points at 16-byte entries of attribute, size, offset and two dependent-image fields. Entry 0 is the primary
     * image, which starts at SOI and has offset 0, so it grows with the splice. The other entries' offsets are relative
     * to the MP header, which moved by the same amount as their images, so they stay as they are.
     */
    private fun growPrimaryImage(data: ByteArray, from: Int, end: Int, delta: Int) {
        val header = from + MPF_HEADER.size
        val length = end - header
        val bigEndian = when {
            length >= 2 && byteAt(data, header) == 'M'.code && byteAt(data, header + 1) == 'M'.code -> true
            length >= 2 && byteAt(data, header) == 'I'.code && byteAt(data, header + 1) == 'I'.code -> false
            else -> throw IllegalArgumentException("MPF segment has no MM/II byte order")
        }

        fun field(offset: Long, size: Int): Int {
            require(offset >= 0 && offset + size <= length) { "MPF field at $offset overruns the $length-byte MP header" }
            return offset.toInt()
        }

        fun read(offset: Long, size: Int): Long {
            val at = header + field(offset, size)
            var value = 0L
            for (i in 0 until size) {
                val shift = if (bigEndian) 8 * (size - 1 - i) else 8 * i
                value = value or (byteAt(data, at + i).toLong() shl shift)
            }
            return value
        }

        require(read(2, 2) == TIFF_MAGIC) { "MPF header lacks the TIFF magic number" }
        val ifd = read(4, 4)
        val count = read(ifd, 2)
        val tag = (0 until count).map { ifd + 2 + 12 * it }.firstOrNull { read(it, 2) == MP_ENTRY_TAG }
        requireNotNull(tag) { "MPF Index IFD has no MP Entry tag" }
        require(read(tag + 4, 4) >= MP_ENTRY_SIZE) { "MP Entry holds no image entry" }
        val sizeField = read(tag + 8, 4) + 4
        val size = read(sizeField, 4) + delta
        require(size in 0..0xFFFF_FFFFL) { "MP entry 0's image size $size is out of range" }
        val at = header + field(sizeField, 4)
        for (i in 0 until 4) {
            val shift = if (bigEndian) 8 * (3 - i) else 8 * i
            data[at + i] = (size ushr shift).toByte()
        }
    }

    private fun startsWith(data: ByteArray, from: Int, end: Int, prefix: ByteArray): Boolean =
        end - from >= prefix.size && prefix.indices.all { data[from + it] == prefix[it] }

    private fun byteAt(data: ByteArray, offset: Int): Int = data[offset].toInt() and 0xFF

    private val EXIF_HEADER = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0) // "Exif\0\0"
    private val MPF_HEADER = byteArrayOf(0x4D, 0x50, 0x46, 0) // "MPF\0"

    private const val TIFF_MAGIC = 0x2AL
    private const val MP_ENTRY_TAG = 0xB002L
    private const val MP_ENTRY_SIZE = 16L
    private const val MAX_SEGMENT_PAYLOAD = 0xFFFF - 2
    private const val MARKER_AND_LENGTH = 4
    private const val TEM = 0x01
    private const val RST0 = 0xD0
    private const val RST7 = 0xD7
    private const val SOI = 0xD8
    private const val EOI = 0xD9
    private const val SOS = 0xDA
    private const val APP1 = 0xE1
    private const val APP2 = 0xE2
}
