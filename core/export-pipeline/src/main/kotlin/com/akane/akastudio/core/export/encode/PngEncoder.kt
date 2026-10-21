package com.akane.akastudio.core.export.encode

import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.abs

/**
 * Streaming PNG encoder (8-bit RGB/RGBA, 16-bit RGBA).
 *
 * Writes the signature, IHDR, the sRGB colour-space declaration (`sRGB` perceptual + `gAMA` 45455 +
 * `cHRM` sRGB primaries; no `iCCP`, which the PNG spec forbids next to `sRGB`), an optional `eXIf`, IDAT
 * chunks of at most 64 KiB and IEND. Each row uses the adaptive filter (of the five PNG filters) with the
 * minimum sum of absolute signed filtered bytes. Only the previous and current rows are held in memory.
 * The stream is flushed but not closed.
 */
object PngEncoder {
    /**
     * Encodes 8-bit RGBA (colour type 6) when [alpha], otherwise 8-bit RGB (colour type 2). PNG alpha is straight, so
     * with [alpha] the rows must be straight colour; without it the alpha byte is dropped and RGB is written as given.
     */
    fun write8(out: OutputStream, width: Int, height: Int, alpha: Boolean, rows: Rgba8Rows, exif: ByteArray?, level: Int = 6) =
        write8(out, width, height, alpha, rows, exif, level, forcedFilter = null)

    /** Encodes 16-bit big-endian RGBA (colour type 6); PNG alpha is straight, so the rows must be straight colour. */
    fun write16(out: OutputStream, width: Int, height: Int, rows: Rgba16Rows, exif: ByteArray?, level: Int = 6) =
        write16(out, width, height, rows, exif, level, forcedFilter = null)

    /** [write8] with an optional fixed filter type (0..4) replacing the adaptive choice. */
    internal fun write8(
        out: OutputStream, width: Int, height: Int, alpha: Boolean, rows: Rgba8Rows, exif: ByteArray?, level: Int, forcedFilter: Int?,
    ) {
        val channels = if (alpha) 4 else 3
        validate(width, height, channels, level, forcedFilter)
        val pixels = IntArray(width)
        encode(out, width, height, if (alpha) COLOR_RGBA else COLOR_RGB, 8, channels, exif, level, forcedFilter) { y, row ->
            rows.row(y, pixels)
            var i = 0
            for (argb in pixels) {
                row[i++] = (argb ushr 16).toByte()
                row[i++] = (argb ushr 8).toByte()
                row[i++] = argb.toByte()
                if (alpha) row[i++] = (argb ushr 24).toByte()
            }
        }
    }

    /** [write16] with an optional fixed filter type (0..4) replacing the adaptive choice. */
    internal fun write16(
        out: OutputStream, width: Int, height: Int, rows: Rgba16Rows, exif: ByteArray?, level: Int, forcedFilter: Int?,
    ) {
        validate(width, height, 8, level, forcedFilter)
        val samples = ShortArray(width * 4)
        encode(out, width, height, COLOR_RGBA, 16, 8, exif, level, forcedFilter) { y, row ->
            rows.row(y, samples)
            var i = 0
            for (sample in samples) {
                val value = sample.toInt()
                row[i++] = (value ushr 8).toByte()
                row[i++] = value.toByte()
            }
        }
    }

    private fun validate(width: Int, height: Int, bytesPerPixel: Int, level: Int, forcedFilter: Int?) {
        require(width > 0 && height > 0) { "PNG dimensions must be positive: ${width}x$height" }
        require(width <= (Int.MAX_VALUE - 1) / bytesPerPixel) { "PNG width too large: $width" }
        require(level in Deflater.DEFAULT_COMPRESSION..Deflater.BEST_COMPRESSION) { "Deflate level must be in -1..9: $level" }
        require(forcedFilter == null || forcedFilter in FILTER_NONE..FILTER_PAETH) { "PNG filter must be 0..4: $forcedFilter" }
    }

    private fun encode(
        out: OutputStream,
        width: Int,
        height: Int,
        colorType: Int,
        bitDepth: Int,
        bytesPerPixel: Int,
        exif: ByteArray?,
        level: Int,
        forcedFilter: Int?,
        fillRow: (y: Int, row: ByteArray) -> Unit,
    ) {
        out.write(SIGNATURE)
        val ihdr = ByteArray(13)
        putInt(ihdr, 0, width)
        putInt(ihdr, 4, height)
        ihdr[8] = bitDepth.toByte()
        ihdr[9] = colorType.toByte()
        // compression 0 (deflate), filter method 0 (adaptive), interlace 0 (none)
        writeChunk(out, "IHDR", ihdr, ihdr.size)
        writeChunk(out, "sRGB", byteArrayOf(SRGB_PERCEPTUAL), 1)
        val gama = ByteArray(4).also { putInt(it, 0, GAMMA_SRGB) }
        writeChunk(out, "gAMA", gama, gama.size)
        val chrm = ByteArray(32)
        CHRM_SRGB.forEachIndexed { index, value -> putInt(chrm, index * 4, value) }
        writeChunk(out, "cHRM", chrm, chrm.size)
        if (exif != null) writeChunk(out, "eXIf", exif, exif.size)

        val rowBytes = width * bytesPerPixel
        var previous = ByteArray(rowBytes)
        var current = ByteArray(rowBytes)
        var best = ByteArray(rowBytes + 1)
        var candidate = ByteArray(rowBytes + 1)
        val idat = IdatWriter(out, level)
        try {
            for (y in 0 until height) {
                fillRow(y, current)
                if (forcedFilter != null) {
                    applyFilter(forcedFilter, current, previous, bytesPerPixel, best)
                } else {
                    var bestScore = Long.MAX_VALUE
                    for (type in FILTER_NONE..FILTER_PAETH) {
                        val score = applyFilter(type, current, previous, bytesPerPixel, candidate)
                        if (score < bestScore) {
                            bestScore = score
                            val swap = best
                            best = candidate
                            candidate = swap
                        }
                    }
                }
                idat.write(best)
                val swap = previous
                previous = current
                current = swap
            }
            idat.finish()
        } finally {
            idat.end()
        }
        writeChunk(out, "IEND", ByteArray(0), 0)
        out.flush()
    }

    /** Writes filter [type] of [current] into [dst] (type byte first); returns the sum of |signed filtered byte|. */
    internal fun applyFilter(type: Int, current: ByteArray, previous: ByteArray, bytesPerPixel: Int, dst: ByteArray): Long {
        dst[0] = type.toByte()
        var sum = 0L
        for (i in current.indices) {
            val x = current[i].toInt() and 0xFF
            val left = if (i >= bytesPerPixel) current[i - bytesPerPixel].toInt() and 0xFF else 0
            val up = previous[i].toInt() and 0xFF
            val upLeft = if (i >= bytesPerPixel) previous[i - bytesPerPixel].toInt() and 0xFF else 0
            val predictor = when (type) {
                FILTER_NONE -> 0
                FILTER_SUB -> left
                FILTER_UP -> up
                FILTER_AVERAGE -> (left + up) ushr 1
                else -> paeth(left, up, upLeft)
            }
            val filtered = (x - predictor).toByte()
            dst[i + 1] = filtered
            sum += abs(filtered.toInt())
        }
        return sum
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = abs(p - a)
        val pb = abs(p - b)
        val pc = abs(p - c)
        return when {
            pa <= pb && pa <= pc -> a
            pb <= pc -> b
            else -> c
        }
    }

    private fun writeChunk(out: OutputStream, type: String, data: ByteArray, length: Int) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val header = ByteArray(8)
        putInt(header, 0, length)
        typeBytes.copyInto(header, 4)
        out.write(header)
        out.write(data, 0, length)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data, 0, length)
        val trailer = ByteArray(4)
        putInt(trailer, 0, crc.value.toInt())
        out.write(trailer)
    }

    private fun putInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    /** Deflates filtered rows and emits IDAT chunks of at most [MAX_IDAT_BYTES]. */
    private class IdatWriter(private val out: OutputStream, level: Int) {
        private val deflater = Deflater(level)
        private val buffer = ByteArray(MAX_IDAT_BYTES)
        private var filled = 0

        fun write(data: ByteArray) {
            deflater.setInput(data)
            while (!deflater.needsInput()) drain()
        }

        fun finish() {
            deflater.finish()
            while (!deflater.finished()) drain()
            if (filled > 0) flushChunk()
        }

        fun end() = deflater.end()

        private fun drain() {
            filled += deflater.deflate(buffer, filled, buffer.size - filled)
            if (filled == buffer.size) flushChunk()
        }

        private fun flushChunk() {
            writeChunk(out, "IDAT", buffer, filled)
            filled = 0
        }
    }

    internal const val MAX_IDAT_BYTES = 64 * 1024

    private val SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
    private const val COLOR_RGB = 2
    private const val COLOR_RGBA = 6
    private const val SRGB_PERCEPTUAL: Byte = 0
    private const val GAMMA_SRGB = 45455

    /** White x, y; red x, y; green x, y; blue x, y; times 100000. */
    private val CHRM_SRGB = intArrayOf(31270, 32900, 64000, 33000, 30000, 60000, 15000, 6000)

    private const val FILTER_NONE = 0
    private const val FILTER_SUB = 1
    private const val FILTER_UP = 2
    private const val FILTER_AVERAGE = 3
    private const val FILTER_PAETH = 4
}
