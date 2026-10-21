package com.akane.akastudio.core.export.encode

import java.io.ByteArrayOutputStream

/**
 * Rewrites a WebP file into the extended (VP8X) layout carrying ICC and EXIF metadata.
 *
 * Accepts a simple lossless file (a single `VP8L` chunk) or an existing `VP8X` file. Output order:
 * `RIFF size WEBP`, `VP8X`, `ICCP`, the original image chunk(s) (`ANIM`/`ANMF` or `ALPH`/`VP8 `/`VP8L`)
 * plus unknown chunks in their original order, `EXIF`, `XMP `. Chunks are padded to even length and the RIFF
 * size is recomputed.
 */
object WebpContainer {
    /**
     * Returns [webp] with [icc] and [exifTiff] embedded. A non-null value replaces any existing chunk of that
     * kind; `null` keeps an existing chunk (an input without one stays without one).
     *
     * @throws IllegalArgumentException with the reason when [webp] is malformed or unsupported (simple lossy
     *   `VP8 ` files are rejected), or when a metadata array is empty.
     */
    fun withMetadata(webp: ByteArray, icc: ByteArray?, exifTiff: ByteArray?): ByteArray {
        require(icc == null || icc.isNotEmpty()) { "ICC profile is empty" }
        require(exifTiff == null || exifTiff.isNotEmpty()) { "EXIF block is empty" }
        val chunks = parseRiff(webp)
        val first = chunks.first()
        val layout = when (first.fourCc) {
            VP8L -> simpleLossless(chunks)
            VP8X -> extended(chunks)
            VP8 -> throw IllegalArgumentException("Simple lossy VP8 WebP is not supported; expected VP8L or VP8X")
            else -> throw IllegalArgumentException("Unexpected first WebP chunk '${first.fourCc}'")
        }
        val finalIcc = icc ?: layout.icc
        val finalExif = exifTiff ?: layout.exif

        var flags = layout.preservedFlags
        if (finalIcc != null) flags = flags or FLAG_ICC
        if (finalExif != null) flags = flags or FLAG_EXIF
        if (layout.alpha) flags = flags or FLAG_ALPHA
        if (layout.xmp != null) flags = flags or FLAG_XMP

        val vp8x = ByteArray(VP8X_PAYLOAD_SIZE)
        vp8x[0] = flags.toByte()
        putUInt24(vp8x, 4, layout.canvasWidth - 1)
        putUInt24(vp8x, 7, layout.canvasHeight - 1)

        val body = ByteArrayOutputStream(webp.size + (finalIcc?.size ?: 0) + (finalExif?.size ?: 0) + 64)
        body.write(WEBP.toByteArray(Charsets.US_ASCII))
        writeChunk(body, VP8X, vp8x, 0, vp8x.size)
        if (finalIcc != null) writeChunk(body, ICCP, finalIcc, 0, finalIcc.size)
        for (chunk in layout.imageChunks) writeChunk(body, chunk.fourCc, webp, chunk.offset, chunk.size)
        if (finalExif != null) writeChunk(body, EXIF, finalExif, 0, finalExif.size)
        layout.xmp?.let { writeChunk(body, XMP, webp, it.offset, it.size) }

        val riffSize = body.size().toLong()
        require(riffSize <= MAX_RIFF_SIZE) { "WebP output too large: $riffSize bytes" }
        val result = ByteArrayOutputStream(body.size() + 8)
        result.write(RIFF.toByteArray(Charsets.US_ASCII))
        result.write(uInt32Le(riffSize))
        body.writeTo(result)
        return result.toByteArray()
    }

    private fun simpleLossless(chunks: List<Chunk>): Layout {
        require(chunks.size == 1) { "Simple lossless WebP must contain exactly one VP8L chunk, found ${chunks.size} chunks" }
        val header = vp8lHeader(chunks[0])
        return Layout(header.width, header.height, header.alpha, 0, chunks, icc = null, exif = null, xmp = null)
    }

    private fun extended(chunks: List<Chunk>): Layout {
        val vp8x = chunks[0]
        require(vp8x.size >= VP8X_PAYLOAD_SIZE) { "VP8X chunk too short: ${vp8x.size} bytes" }
        val data = vp8x.source
        val existingFlags = data[vp8x.offset].toInt() and 0xFF
        val width = readUInt24(data, vp8x.offset + 4) + 1
        val height = readUInt24(data, vp8x.offset + 7) + 1
        var icc: ByteArray? = null
        var exif: ByteArray? = null
        var xmp: Chunk? = null
        var alpha = existingFlags and FLAG_ALPHA != 0
        var hasImage = false
        val imageChunks = ArrayList<Chunk>()
        for (chunk in chunks.drop(1)) {
            when (chunk.fourCc) {
                VP8X -> throw IllegalArgumentException("Duplicate VP8X chunk")
                ICCP -> icc = chunk.copy()
                EXIF -> exif = chunk.copy()
                XMP -> xmp = chunk
                else -> {
                    when (chunk.fourCc) {
                        ALPH -> alpha = true
                        VP8L -> {
                            hasImage = true
                            if (vp8lHeader(chunk).alpha) alpha = true
                        }
                        VP8, ANMF -> hasImage = true
                    }
                    imageChunks += chunk
                }
            }
        }
        require(hasImage) { "VP8X file has no image data (VP8, VP8L or ANMF chunk)" }
        return Layout(width, height, alpha, existingFlags and FLAG_ANIMATION, imageChunks, icc, exif, xmp)
    }

    /** VP8L header: 0x2F signature, then width-1 and height-1 (14 bits each), alpha hint (1 bit), version (3 bits, 0). */
    private fun vp8lHeader(chunk: Chunk): Vp8lHeader {
        require(chunk.size >= VP8L_HEADER_SIZE) { "VP8L chunk too short: ${chunk.size} bytes" }
        val data = chunk.source
        require(data[chunk.offset].toInt() and 0xFF == VP8L_SIGNATURE) {
            "Bad VP8L signature 0x%02X".format(data[chunk.offset].toInt() and 0xFF)
        }
        val bits = readUInt32Le(data, chunk.offset + 1)
        val version = ((bits ushr 29) and 0x7).toInt()
        require(version == 0) { "Unsupported VP8L version $version" }
        return Vp8lHeader(
            width = (bits and 0x3FFF).toInt() + 1,
            height = ((bits ushr 14) and 0x3FFF).toInt() + 1,
            alpha = (bits ushr 28) and 1L == 1L,
        )
    }

    private fun parseRiff(data: ByteArray): List<Chunk> {
        require(data.size >= 12) { "WebP truncated: ${data.size} bytes, need at least 12" }
        require(fourCc(data, 0) == RIFF) { "Missing RIFF signature" }
        require(fourCc(data, 8) == WEBP) { "Missing WEBP form type" }
        val riffSize = readUInt32Le(data, 4)
        require(riffSize >= 4) { "RIFF size $riffSize too small" }
        require(riffSize + 8 <= data.size) { "WebP truncated: RIFF declares ${riffSize + 8} bytes, have ${data.size}" }
        val end = (riffSize + 8).toInt()
        val chunks = ArrayList<Chunk>()
        var position = 12
        while (position < end) {
            require(end - position >= 8) { "Truncated chunk header at offset $position" }
            val type = fourCc(data, position)
            val size = readUInt32Le(data, position + 4)
            val payloadStart = position + 8
            require(size <= end - payloadStart) { "Chunk '$type' at offset $position declares $size bytes, only ${end - payloadStart} remain" }
            chunks += Chunk(type, data, payloadStart, size.toInt())
            // A missing pad byte is tolerated only after the final chunk.
            position = minOf(end.toLong(), payloadStart + size + (size and 1L)).toInt()
        }
        require(chunks.isNotEmpty()) { "WebP has no chunks" }
        return chunks
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, source: ByteArray, offset: Int, size: Int) {
        out.write(type.toByteArray(Charsets.US_ASCII))
        out.write(uInt32Le(size.toLong()))
        out.write(source, offset, size)
        if (size and 1 != 0) out.write(0)
    }

    private fun fourCc(data: ByteArray, offset: Int): String = String(data, offset, 4, Charsets.ISO_8859_1)

    private fun readUInt32Le(data: ByteArray, offset: Int): Long =
        (data[offset].toLong() and 0xFF) or
            ((data[offset + 1].toLong() and 0xFF) shl 8) or
            ((data[offset + 2].toLong() and 0xFF) shl 16) or
            ((data[offset + 3].toLong() and 0xFF) shl 24)

    private fun readUInt24(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8) or ((data[offset + 2].toInt() and 0xFF) shl 16)

    private fun putUInt24(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
        target[offset + 2] = (value ushr 16).toByte()
    }

    private fun uInt32Le(value: Long): ByteArray =
        byteArrayOf(value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte())

    /** A chunk payload inside [source]: [size] bytes starting at [offset] (header and padding excluded). */
    private class Chunk(val fourCc: String, val source: ByteArray, val offset: Int, val size: Int) {
        fun copy(): ByteArray = source.copyOfRange(offset, offset + size)
    }

    private class Vp8lHeader(val width: Int, val height: Int, val alpha: Boolean)

    private class Layout(
        val canvasWidth: Int,
        val canvasHeight: Int,
        val alpha: Boolean,
        val preservedFlags: Int,
        val imageChunks: List<Chunk>,
        val icc: ByteArray?,
        val exif: ByteArray?,
        val xmp: Chunk?,
    )

    private const val RIFF = "RIFF"
    private const val WEBP = "WEBP"
    private const val VP8 = "VP8 "
    private const val VP8L = "VP8L"
    private const val VP8X = "VP8X"
    private const val ICCP = "ICCP"
    private const val EXIF = "EXIF"
    private const val XMP = "XMP "
    private const val ALPH = "ALPH"
    private const val ANMF = "ANMF"

    private const val VP8L_SIGNATURE = 0x2F
    private const val VP8L_HEADER_SIZE = 5
    private const val VP8X_PAYLOAD_SIZE = 10
    private const val MAX_RIFF_SIZE = 0xFFFFFFFEL

    internal const val FLAG_ANIMATION = 0x02
    internal const val FLAG_XMP = 0x04
    internal const val FLAG_EXIF = 0x08
    internal const val FLAG_ALPHA = 0x10
    internal const val FLAG_ICC = 0x20
}
