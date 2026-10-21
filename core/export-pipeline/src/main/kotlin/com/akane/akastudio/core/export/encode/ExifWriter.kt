package com.akane.akastudio.core.export.encode

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

/**
 * Metadata written into the exported file's EXIF block.
 *
 * @property captureEpochMillis capture time of the anchor photo; `null` omits DateTimeOriginal/OffsetTimeOriginal.
 * @property description optional ImageDescription; `null` omits the tag.
 * @property software Software tag value.
 * @property width PixelXDimension of the encoded image.
 * @property height PixelYDimension of the encoded image.
 */
data class ExifFields(
    val captureEpochMillis: Long?,
    val description: String?,
    val software: String,
    val width: Int,
    val height: Int,
)

/**
 * Writes a big-endian TIFF block for EXIF payloads (JPEG APP1 after `Exif\0\0`, PNG `eXIf`, WebP `EXIF`).
 *
 * IFD0: ImageDescription (if any), Orientation = 1, X/YResolution 72/1, ResolutionUnit = 2 (inch),
 * Software and the ExifIFD pointer. ExifIFD: ExifVersion "0232", DateTimeOriginal + OffsetTimeOriginal
 * (if a capture time is known and its year is 0..9999 in the zone), ColorSpace = 1 (sRGB) and
 * PixelX/YDimension. Strings are UTF-8 with any NUL characters removed, then NUL-terminated; values longer
 * than 4 bytes are stored out of line at even (word-aligned) offsets.
 */
object ExifWriter {
    /** The TIFF block (starting with `MM\0*`, no `Exif\0\0` header); dates use the device default zone. */
    fun tiff(fields: ExifFields): ByteArray = tiff(fields, ZoneId.systemDefault())

    /** [tiff] with an explicit [zone] for DateTimeOriginal/OffsetTimeOriginal. */
    internal fun tiff(fields: ExifFields, zone: ZoneId): ByteArray {
        require(fields.width > 0 && fields.height > 0) { "EXIF pixel dimensions must be positive: ${fields.width}x${fields.height}" }
        val exifEntries = exifIfdEntries(fields, zone)
        val ifd0Without = ifd0Entries(fields, exifIfdOffset = 0)
        val exifIfdOffset = HEADER_SIZE + blockSize(ifd0Without)
        val ifd0 = ifd0Entries(fields, exifIfdOffset)
        val total = exifIfdOffset + blockSize(exifEntries)

        val buffer = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
        buffer.put('M'.code.toByte()).put('M'.code.toByte()).putShort(TIFF_MAGIC.toShort()).putInt(HEADER_SIZE)
        writeIfd(buffer, ifd0, HEADER_SIZE)
        writeIfd(buffer, exifEntries, exifIfdOffset)
        check(buffer.position() == total) { "TIFF layout mismatch: wrote ${buffer.position()} of $total bytes" }
        return buffer.array()
    }

    private fun ifd0Entries(fields: ExifFields, exifIfdOffset: Int): List<Entry> = buildList {
        fields.description?.let { add(ascii(TAG_IMAGE_DESCRIPTION, it)) }
        add(short(TAG_ORIENTATION, 1))
        add(rational(TAG_X_RESOLUTION, 72, 1))
        add(rational(TAG_Y_RESOLUTION, 72, 1))
        add(short(TAG_RESOLUTION_UNIT, 2))
        add(ascii(TAG_SOFTWARE, fields.software))
        add(long(TAG_EXIF_IFD_POINTER, exifIfdOffset.toLong()))
    }

    private fun exifIfdEntries(fields: ExifFields, zone: ZoneId): List<Entry> = buildList {
        add(Entry(TAG_EXIF_VERSION, TYPE_UNDEFINED, 4, "0232".toByteArray(Charsets.US_ASCII)))
        fields.captureEpochMillis?.let { millis ->
            val local = Instant.ofEpochMilli(millis).atZone(zone)
            if (local.year in 0..9999) {
                val dateTime = String.format(
                    Locale.ROOT,
                    "%04d:%02d:%02d %02d:%02d:%02d",
                    local.year, local.monthValue, local.dayOfMonth, local.hour, local.minute, local.second,
                )
                add(ascii(TAG_DATE_TIME_ORIGINAL, dateTime))
                add(ascii(TAG_OFFSET_TIME_ORIGINAL, formatOffset(local.offset.totalSeconds)))
            }
        }
        add(short(TAG_COLOR_SPACE, 1))
        add(long(TAG_PIXEL_X_DIMENSION, fields.width.toLong()))
        add(long(TAG_PIXEL_Y_DIMENSION, fields.height.toLong()))
    }

    /** "+hh:mm" / "-hh:mm" (UTC is "+00:00"). */
    internal fun formatOffset(totalSeconds: Int): String {
        val sign = if (totalSeconds < 0) '-' else '+'
        val magnitude = abs(totalSeconds)
        return String.format(Locale.ROOT, "%c%02d:%02d", sign, magnitude / 3600, (magnitude % 3600) / 60)
    }

    /** Bytes taken by an IFD plus its out-of-line values; always even. */
    private fun blockSize(entries: List<Entry>): Int =
        ifdSize(entries) + entries.filter { it.value.size > 4 }.sumOf { even(it.value.size) }

    private fun ifdSize(entries: List<Entry>): Int = 2 + ENTRY_SIZE * entries.size + 4

    private fun writeIfd(buffer: ByteBuffer, entries: List<Entry>, ifdOffset: Int) {
        check(buffer.position() == ifdOffset) { "IFD expected at $ifdOffset, writer at ${buffer.position()}" }
        val sorted = entries.sortedBy { it.tag }
        var dataOffset = ifdOffset + ifdSize(sorted)
        buffer.putShort(sorted.size.toShort())
        for (entry in sorted) {
            buffer.putShort(entry.tag.toShort()).putShort(entry.type.toShort()).putInt(entry.count)
            if (entry.value.size <= 4) {
                buffer.put(entry.value)
                repeat(4 - entry.value.size) { buffer.put(0.toByte()) }
            } else {
                buffer.putInt(dataOffset)
                dataOffset += even(entry.value.size)
            }
        }
        buffer.putInt(0) // no next IFD
        for (entry in sorted) {
            if (entry.value.size > 4) {
                buffer.put(entry.value)
                if (entry.value.size % 2 != 0) buffer.put(0.toByte())
            }
        }
    }

    private fun even(size: Int): Int = size + (size and 1)

    private fun ascii(tag: Int, value: String): Entry {
        val bytes = value.replace("\u0000", "").toByteArray(Charsets.UTF_8) + 0.toByte()
        return Entry(tag, TYPE_ASCII, bytes.size, bytes)
    }

    private fun short(tag: Int, value: Int): Entry =
        Entry(tag, TYPE_SHORT, 1, byteArrayOf((value ushr 8).toByte(), value.toByte()))

    private fun long(tag: Int, value: Long): Entry =
        Entry(tag, TYPE_LONG, 1, ByteBuffer.allocate(4).putInt(value.toInt()).array())

    private fun rational(tag: Int, numerator: Int, denominator: Int): Entry =
        Entry(tag, TYPE_RATIONAL, 1, ByteBuffer.allocate(8).putInt(numerator).putInt(denominator).array())

    /** One IFD entry; [value] holds exactly `count * typeSize` big-endian bytes. */
    private class Entry(val tag: Int, val type: Int, val count: Int, val value: ByteArray)

    private const val HEADER_SIZE = 8
    private const val TIFF_MAGIC = 42
    private const val ENTRY_SIZE = 12

    private const val TYPE_ASCII = 2
    private const val TYPE_SHORT = 3
    private const val TYPE_LONG = 4
    private const val TYPE_RATIONAL = 5
    private const val TYPE_UNDEFINED = 7

    internal const val TAG_IMAGE_DESCRIPTION = 0x010E
    internal const val TAG_ORIENTATION = 0x0112
    internal const val TAG_X_RESOLUTION = 0x011A
    internal const val TAG_Y_RESOLUTION = 0x011B
    internal const val TAG_RESOLUTION_UNIT = 0x0128
    internal const val TAG_SOFTWARE = 0x0131
    internal const val TAG_EXIF_IFD_POINTER = 0x8769
    internal const val TAG_EXIF_VERSION = 0x9000
    internal const val TAG_DATE_TIME_ORIGINAL = 0x9003
    internal const val TAG_OFFSET_TIME_ORIGINAL = 0x9011
    internal const val TAG_COLOR_SPACE = 0xA001
    internal const val TAG_PIXEL_X_DIMENSION = 0xA002
    internal const val TAG_PIXEL_Y_DIMENSION = 0xA003
}
