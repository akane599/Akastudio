package com.akane.akastudio.core.export.encode

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** One parsed PNG chunk with its stored and recomputed CRC. */
internal class PngChunk(val type: String, val data: ByteArray, val storedCrc: Int, val computedCrc: Int)

/** Parses every chunk after the PNG signature, failing on any structural error. */
internal fun parsePngChunks(png: ByteArray): List<PngChunk> {
    val signature = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
    check(png.copyOfRange(0, 8).contentEquals(signature)) { "bad PNG signature" }
    val buffer = ByteBuffer.wrap(png).order(ByteOrder.BIG_ENDIAN)
    buffer.position(8)
    val chunks = ArrayList<PngChunk>()
    while (buffer.remaining() > 0) {
        val length = buffer.int
        val typeBytes = ByteArray(4).also { buffer.get(it) }
        val data = ByteArray(length).also { buffer.get(it) }
        val stored = buffer.int
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        chunks += PngChunk(String(typeBytes, Charsets.US_ASCII), data, stored, crc.value.toInt())
    }
    return chunks
}

/** A JPEG marker segment before the entropy-coded data. */
internal class JpegSegment(val marker: Int, val payload: ByteArray)

/** Walks marker segments from SOI through SOS (inclusive). */
internal fun parseJpegSegments(jpeg: ByteArray): List<JpegSegment> {
    check(jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { "missing SOI" }
    val segments = ArrayList<JpegSegment>()
    var position = 2
    while (true) {
        check(jpeg[position] == 0xFF.toByte()) { "expected marker at $position" }
        val marker = jpeg[position + 1].toInt() and 0xFF
        val length = ((jpeg[position + 2].toInt() and 0xFF) shl 8) or (jpeg[position + 3].toInt() and 0xFF)
        segments += JpegSegment(marker, jpeg.copyOfRange(position + 4, position + 2 + length))
        position += 2 + length
        if (marker == 0xDA) return segments
    }
}

/** A resolved TIFF IFD entry; [value] holds the `count * typeSize` value bytes. */
internal class TiffEntry(val tag: Int, val type: Int, val count: Int, val value: ByteArray, val valueOffset: Int?) {
    fun ascii(): String {
        check(type == 2) { "tag 0x%04X is type $type, not ASCII".format(tag) }
        check(value.last() == 0.toByte()) { "ASCII tag 0x%04X not NUL-terminated".format(tag) }
        return String(value, 0, value.size - 1, Charsets.UTF_8)
    }

    fun uint(): Long = when (type) {
        3 -> ByteBuffer.wrap(value).short.toLong() and 0xFFFF
        4 -> ByteBuffer.wrap(value).int.toLong() and 0xFFFFFFFFL
        else -> error("tag 0x%04X is type $type, not SHORT/LONG".format(tag))
    }

    fun rational(): Pair<Long, Long> {
        check(type == 5) { "tag 0x%04X is type $type, not RATIONAL".format(tag) }
        val buffer = ByteBuffer.wrap(value)
        return (buffer.int.toLong() and 0xFFFFFFFFL) to (buffer.int.toLong() and 0xFFFFFFFFL)
    }
}

/** An IFD: entries in file order and the next-IFD offset. */
internal class TiffIfd(val offset: Int, val entries: List<TiffEntry>, val next: Int) {
    operator fun get(tag: Int): TiffEntry? = entries.firstOrNull { it.tag == tag }
}

/** Minimal big-endian TIFF reader that validates every offset it follows. */
internal class TestTiffReader(private val tiff: ByteArray) {
    private val buffer = ByteBuffer.wrap(tiff).order(ByteOrder.BIG_ENDIAN)

    init {
        check(tiff.size >= 8) { "TIFF too short" }
        check(tiff[0] == 'M'.code.toByte() && tiff[1] == 'M'.code.toByte()) { "not big-endian TIFF" }
        check(buffer.getShort(2).toInt() == 42) { "bad TIFF magic" }
    }

    val ifd0Offset: Int get() = buffer.getInt(4)

    fun readIfd(offset: Int): TiffIfd {
        check(offset in 8 until tiff.size && offset % 2 == 0) { "IFD offset $offset invalid" }
        val count = buffer.getShort(offset).toInt() and 0xFFFF
        check(offset + 2 + count * 12 + 4 <= tiff.size) { "IFD at $offset overruns the block" }
        val entries = (0 until count).map { index ->
            val at = offset + 2 + index * 12
            val tag = buffer.getShort(at).toInt() and 0xFFFF
            val type = buffer.getShort(at + 2).toInt() and 0xFFFF
            val n = buffer.getInt(at + 4)
            val size = n * typeSize(type)
            if (size <= 4) {
                TiffEntry(tag, type, n, tiff.copyOfRange(at + 8, at + 8 + size), null)
            } else {
                val valueOffset = buffer.getInt(at + 8)
                check(valueOffset >= 8 && valueOffset + size <= tiff.size) { "tag 0x%04X value offset $valueOffset out of range".format(tag) }
                check(valueOffset % 2 == 0) { "tag 0x%04X value offset $valueOffset not word-aligned".format(tag) }
                TiffEntry(tag, type, n, tiff.copyOfRange(valueOffset, valueOffset + size), valueOffset)
            }
        }
        return TiffIfd(offset, entries, buffer.getInt(offset + 2 + count * 12))
    }

    private fun typeSize(type: Int): Int = when (type) {
        1, 2, 7 -> 1
        3 -> 2
        4 -> 4
        5 -> 8
        else -> error("unsupported TIFF type $type")
    }
}
