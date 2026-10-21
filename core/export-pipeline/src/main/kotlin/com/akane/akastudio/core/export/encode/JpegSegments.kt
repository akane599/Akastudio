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
     * shorten the distance between the two, so that layout is refused.
     *
     * @throws IllegalArgumentException when [jpeg] does not start with SOI, its marker segments before SOS are
     *   malformed, it carries more than one Exif segment or one after the MPF segment, [exifTiff] is empty, or the
     *   APP1 payload (`Exif\0\0` plus [exifTiff]) exceeds 65533 bytes.
     */
    fun insertApp1AfterSoi(jpeg: ByteArray, exifTiff: ByteArray): ByteArray {
        require(exifTiff.isNotEmpty()) { "EXIF block is empty" }
        val payloadSize = EXIF_HEADER.size + exifTiff.size
        require(payloadSize <= MAX_SEGMENT_PAYLOAD) { "EXIF block too large for APP1: ${exifTiff.size} bytes" }
        require(jpeg.size >= 4 && byteAt(jpeg, 0) == 0xFF && byteAt(jpeg, 1) == SOI) { "JPEG does not start with SOI" }
        val existing = existingExif(jpeg)
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
        return out
    }

    /** Byte range of the APP1 Exif segment (marker included) among the segments before SOS, or `null`. */
    private fun existingExif(jpeg: ByteArray): IntRange? {
        var exif: IntRange? = null
        var mpfSeen = false
        var position = 2
        while (position < jpeg.size) {
            require(byteAt(jpeg, position) == 0xFF) { "Expected a JPEG marker at offset $position" }
            while (position + 1 < jpeg.size && byteAt(jpeg, position + 1) == 0xFF) position++ // fill bytes
            require(position + 1 < jpeg.size) { "Truncated JPEG marker at offset $position" }
            val marker = byteAt(jpeg, position + 1)
            if (marker == SOS || marker == EOI) return exif
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
                    require(!mpfSeen) { "Exif segment after the MPF segment: replacing it would move the MPF image offsets" }
                    exif = position until end
                }
                marker == APP2 && startsWith(jpeg, payload, end, MPF_HEADER) -> mpfSeen = true
            }
            position = end
        }
        throw IllegalArgumentException("JPEG has no SOS or EOI marker")
    }

    private fun startsWith(data: ByteArray, from: Int, end: Int, prefix: ByteArray): Boolean =
        end - from >= prefix.size && prefix.indices.all { data[from + it] == prefix[it] }

    private fun byteAt(data: ByteArray, offset: Int): Int = data[offset].toInt() and 0xFF

    private val EXIF_HEADER = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0) // "Exif\0\0"
    private val MPF_HEADER = byteArrayOf(0x4D, 0x50, 0x46, 0) // "MPF\0"

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
