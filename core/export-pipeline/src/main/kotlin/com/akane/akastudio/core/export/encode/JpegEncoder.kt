package com.akane.akastudio.core.export.encode

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Baseline sequential JPEG encoder with 4:4:4 sampling (H = V = 1 for Y, Cb and Cr).
 *
 * Colour conversion is full-range JFIF BT.601 YCbCr; quantisation uses the Annex K tables scaled by IJG
 * quality; entropy coding uses the Annex K Huffman tables; the forward DCT is the float AAN algorithm.
 * Segments: SOI, APP0 JFIF, APP1 `Exif\0\0` + TIFF, APP2 `ICC_PROFILE\0` chunks, DQT, SOF0, DHT, SOS, EOI.
 * Rows are pulled in 8-row MCU strips; alpha is ignored. The stream is flushed but not closed.
 */
object JpegEncoder {
    /**
     * Encodes [width] x [height] pixels from [rows] at IJG [quality] (1..100), embedding [icc] (split into
     * APP2 chunks of at most 65,519 bytes) and [exifTiff] (a TIFF block, see [ExifWriter]) when non-null.
     *
     * @throws IllegalArgumentException for invalid dimensions or quality, or metadata that does not fit
     *   (EXIF over 65,527 bytes, ICC needing more than 255 chunks, empty arrays). Nothing is written then.
     */
    fun write(
        out: OutputStream,
        width: Int,
        height: Int,
        rows: Rgba8Rows,
        quality: Int = 95,
        icc: ByteArray? = SrgbIccProfile.bytes,
        exifTiff: ByteArray?,
    ) {
        require(width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION) { "JPEG dimensions must be 1..$MAX_DIMENSION: ${width}x$height" }
        require(quality in 1..100) { "JPEG quality must be in 1..100: $quality" }
        if (exifTiff != null) {
            require(exifTiff.isNotEmpty()) { "EXIF block is empty" }
            require(exifTiff.size <= MAX_SEGMENT_PAYLOAD - EXIF_HEADER.size) { "EXIF block too large for APP1: ${exifTiff.size} bytes" }
        }
        if (icc != null) {
            require(icc.isNotEmpty()) { "ICC profile is empty" }
            require(iccChunkCount(icc.size) <= 255) { "ICC profile too large for APP2 chunks: ${icc.size} bytes" }
        }

        val lumaQuant = JpegTables.scaledQuant(JpegTables.LUMINANCE_QUANT, quality)
        val chromaQuant = JpegTables.scaledQuant(JpegTables.CHROMINANCE_QUANT, quality)
        out.write(byteArrayOf(0xFF.toByte(), SOI.toByte()))
        writeSegment(out, APP0, JFIF_APP0)
        if (exifTiff != null) writeSegment(out, APP1, EXIF_HEADER + exifTiff)
        if (icc != null) writeIcc(out, icc)
        writeSegment(out, DQT, dqt(lumaQuant, chromaQuant))
        writeSegment(out, SOF0, sof0(width, height))
        writeSegment(out, DHT, dht())
        writeSegment(out, SOS, SOS_HEADER)
        ScanEncoder(out, width, height, lumaQuant, chromaQuant).encode(rows)
        out.write(byteArrayOf(0xFF.toByte(), EOI.toByte()))
        out.flush()
    }

    internal fun iccChunkCount(size: Int): Int = (size + MAX_ICC_CHUNK - 1) / MAX_ICC_CHUNK

    private fun writeIcc(out: OutputStream, icc: ByteArray) {
        val count = iccChunkCount(icc.size)
        for (index in 0 until count) {
            val start = index * MAX_ICC_CHUNK
            val end = min(icc.size, start + MAX_ICC_CHUNK)
            val payload = ByteArrayOutputStream(ICC_HEADER.size + 2 + end - start)
            payload.write(ICC_HEADER)
            payload.write(index + 1)
            payload.write(count)
            payload.write(icc, start, end - start)
            writeSegment(out, APP2, payload.toByteArray())
        }
    }

    private fun writeSegment(out: OutputStream, marker: Int, payload: ByteArray) {
        val length = payload.size + 2
        check(length <= 0xFFFF) { "JPEG segment too long: $length" }
        out.write(byteArrayOf(0xFF.toByte(), marker.toByte(), (length ushr 8).toByte(), length.toByte()))
        out.write(payload)
    }

    private fun dqt(luma: IntArray, chroma: IntArray): ByteArray {
        val payload = ByteArray(2 * 65)
        payload[0] = 0x00
        payload[65] = 0x01
        for (i in 0 until 64) {
            payload[1 + i] = luma[JpegTables.ZIGZAG[i]].toByte()
            payload[66 + i] = chroma[JpegTables.ZIGZAG[i]].toByte()
        }
        return payload
    }

    private fun sof0(width: Int, height: Int): ByteArray = byteArrayOf(
        8,
        (height ushr 8).toByte(), height.toByte(),
        (width ushr 8).toByte(), width.toByte(),
        3,
        1, SAMPLING_1X1, 0,
        2, SAMPLING_1X1, 1,
        3, SAMPLING_1X1, 1,
    )

    private fun dht(): ByteArray {
        val payload = ByteArrayOutputStream()
        fun table(classAndId: Int, table: HuffmanTable) {
            payload.write(classAndId)
            table.bits.forEach { payload.write(it) }
            table.values.forEach { payload.write(it) }
        }
        table(0x00, DC_LUMA)
        table(0x10, AC_LUMA)
        table(0x01, DC_CHROMA)
        table(0x11, AC_CHROMA)
        return payload.toByteArray()
    }

    /** Converts, transforms, quantises and entropy-codes one interleaved scan, strip by strip. */
    private class ScanEncoder(
        out: OutputStream,
        private val width: Int,
        private val height: Int,
        lumaQuant: IntArray,
        chromaQuant: IntArray,
    ) {
        private val bits = JpegBitWriter(out)
        private val blocksX = (width + 7) / 8
        private val stride = blocksX * 8
        private val planes = Array(3) { FloatArray(stride * 8) }
        private val pixels = IntArray(width)
        private val block = FloatArray(64)
        private val coefficients = IntArray(64)
        private val previousDc = IntArray(3)
        private val divisors = arrayOf(divisors(lumaQuant), divisors(chromaQuant), divisors(chromaQuant))
        private val dcTables = arrayOf(DC_LUMA, DC_CHROMA, DC_CHROMA)
        private val acTables = arrayOf(AC_LUMA, AC_CHROMA, AC_CHROMA)

        fun encode(rows: Rgba8Rows) {
            var top = 0
            while (top < height) {
                val rowsInStrip = min(8, height - top)
                for (r in 0 until rowsInStrip) {
                    rows.row(top + r, pixels)
                    convertRow(r)
                }
                for (r in rowsInStrip until 8) {
                    for (plane in planes) plane.copyInto(plane, r * stride, (rowsInStrip - 1) * stride, rowsInStrip * stride)
                }
                for (bx in 0 until blocksX) {
                    for (component in 0 until 3) encodeBlock(component, bx * 8)
                }
                top += 8
            }
            bits.finish()
        }

        /** Level-shifted full-range YCbCr; columns past the image edge replicate the last pixel. */
        private fun convertRow(r: Int) {
            val y = planes[0]
            val cb = planes[1]
            val cr = planes[2]
            val base = r * stride
            for (x in 0 until stride) {
                val argb = pixels[min(x, width - 1)]
                val red = ((argb ushr 16) and 0xFF).toFloat()
                val green = ((argb ushr 8) and 0xFF).toFloat()
                val blue = (argb and 0xFF).toFloat()
                y[base + x] = 0.299f * red + 0.587f * green + 0.114f * blue - 128f
                cb[base + x] = -0.168736f * red - 0.331264f * green + 0.5f * blue
                cr[base + x] = 0.5f * red - 0.418688f * green - 0.081312f * blue
            }
        }

        private fun encodeBlock(component: Int, left: Int) {
            val plane = planes[component]
            for (row in 0 until 8) plane.copyInto(block, row * 8, row * stride + left, row * stride + left + 8)
            ForwardDct.transform(block)
            val divisor = divisors[component]
            for (k in 0 until 64) coefficients[k] = (block[k] * divisor[k]).roundToInt()
            val dc = coefficients[0].coerceIn(MIN_DC, MAX_DC)
            encodeDc(dc - previousDc[component], dcTables[component])
            previousDc[component] = dc
            encodeAc(acTables[component])
        }

        private fun encodeDc(diff: Int, table: HuffmanTable) {
            val category = magnitudeBits(diff)
            bits.write(table.codes[category], table.sizes[category])
            if (category > 0) bits.write(extraBits(diff, category), category)
        }

        private fun encodeAc(table: HuffmanTable) {
            var run = 0
            for (i in 1 until 64) {
                val value = coefficients[JpegTables.ZIGZAG[i]].coerceIn(-MAX_AC, MAX_AC)
                if (value == 0) {
                    run++
                    continue
                }
                while (run > 15) {
                    bits.write(table.codes[ZRL], table.sizes[ZRL])
                    run -= 16
                }
                val category = magnitudeBits(value)
                val symbol = (run shl 4) or category
                bits.write(table.codes[symbol], table.sizes[symbol])
                bits.write(extraBits(value, category), category)
                run = 0
            }
            if (run > 0) bits.write(table.codes[EOB], table.sizes[EOB])
        }

        private fun magnitudeBits(value: Int): Int = 32 - Integer.numberOfLeadingZeros(abs(value))

        private fun extraBits(value: Int, category: Int): Int =
            (if (value < 0) value - 1 else value) and ((1 shl category) - 1)

        /** Quantisation multipliers folding in the AAN output scaling: 1 / (q * s(u) * s(v) * 8). */
        private fun divisors(quant: IntArray): FloatArray = FloatArray(64) { k ->
            (1.0 / (quant[k] * JpegTables.AAN_SCALE[k / 8] * JpegTables.AAN_SCALE[k % 8] * 8.0)).toFloat()
        }
    }

    /** Entropy-coded segment writer with 0xFF byte stuffing and 1-bit padding at the end. */
    private class JpegBitWriter(private val out: OutputStream) {
        private val buffer = ByteArray(64 * 1024)
        private var position = 0
        private var accumulator = 0
        private var count = 0

        fun write(code: Int, size: Int) {
            check(size in 1..16) { "invalid Huffman/extra bit length $size" }
            accumulator = (accumulator shl size) or (code and ((1 shl size) - 1))
            count += size
            while (count >= 8) {
                val byte = (accumulator ushr (count - 8)) and 0xFF
                emit(byte)
                if (byte == 0xFF) emit(0)
                count -= 8
            }
            accumulator = accumulator and ((1 shl count) - 1)
        }

        fun finish() {
            if (count > 0) write((1 shl (8 - count)) - 1, 8 - count)
            out.write(buffer, 0, position)
            position = 0
        }

        private fun emit(byte: Int) {
            if (position == buffer.size) {
                out.write(buffer, 0, position)
                position = 0
            }
            buffer[position++] = byte.toByte()
        }
    }

    /** Float AAN forward DCT (Arai, Agui, Nakajima), in place on an 8x8 row-major block; outputs are AAN-scaled. */
    internal object ForwardDct {
        fun transform(data: FloatArray) {
            for (row in 0 until 8) pass(data, row * 8, 1)
            for (column in 0 until 8) pass(data, column, 8)
        }

        private fun pass(d: FloatArray, start: Int, step: Int) {
            val i0 = start
            val i1 = start + step
            val i2 = start + 2 * step
            val i3 = start + 3 * step
            val i4 = start + 4 * step
            val i5 = start + 5 * step
            val i6 = start + 6 * step
            val i7 = start + 7 * step

            val tmp0 = d[i0] + d[i7]
            val tmp7 = d[i0] - d[i7]
            val tmp1 = d[i1] + d[i6]
            val tmp6 = d[i1] - d[i6]
            val tmp2 = d[i2] + d[i5]
            val tmp5 = d[i2] - d[i5]
            val tmp3 = d[i3] + d[i4]
            val tmp4 = d[i3] - d[i4]

            // Even part.
            val tmp10 = tmp0 + tmp3
            val tmp13 = tmp0 - tmp3
            val tmp11 = tmp1 + tmp2
            val tmp12 = tmp1 - tmp2
            d[i0] = tmp10 + tmp11
            d[i4] = tmp10 - tmp11
            val z1 = (tmp12 + tmp13) * 0.707106781f
            d[i2] = tmp13 + z1
            d[i6] = tmp13 - z1

            // Odd part.
            val odd10 = tmp4 + tmp5
            val odd11 = tmp5 + tmp6
            val odd12 = tmp6 + tmp7
            val z5 = (odd10 - odd12) * 0.382683433f
            val z2 = 0.541196100f * odd10 + z5
            val z4 = 1.306562965f * odd12 + z5
            val z3 = odd11 * 0.707106781f
            val z11 = tmp7 + z3
            val z13 = tmp7 - z3
            d[i5] = z13 + z2
            d[i3] = z13 - z2
            d[i1] = z11 + z4
            d[i7] = z11 - z4
        }
    }

    private const val MAX_DIMENSION = 0xFFFF
    private const val MAX_SEGMENT_PAYLOAD = 0xFFFF - 2
    internal const val MAX_ICC_CHUNK = 65_519
    // Level-shifted 8-bit samples bound the DC coefficient to -1024..1016, so DC differences stay in category 0..11.
    private const val MIN_DC = -1024
    private const val MAX_DC = 1023
    private const val MAX_AC = 1023
    private const val ZRL = 0xF0
    private const val EOB = 0x00
    private const val SAMPLING_1X1: Byte = 0x11

    private const val SOI = 0xD8
    private const val EOI = 0xD9
    private const val APP0 = 0xE0
    private const val APP1 = 0xE1
    private const val APP2 = 0xE2
    private const val DQT = 0xDB
    private const val SOF0 = 0xC0
    private const val DHT = 0xC4
    private const val SOS = 0xDA

    /** "JFIF\0", version 1.01, density in dpi 72 x 72, no thumbnail. */
    private val JFIF_APP0 = byteArrayOf('J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0, 1, 1, 1, 0, 72, 0, 72, 0, 0)
    private val EXIF_HEADER = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)
    private val ICC_HEADER = "ICC_PROFILE".toByteArray(Charsets.US_ASCII) + 0.toByte()

    /** Three components (Y = 1 with DC/AC tables 0/0, Cb = 2 and Cr = 3 with 1/1), Ss = 0, Se = 63, Ah/Al = 0. */
    private val SOS_HEADER = byteArrayOf(3, 1, 0x00, 2, 0x11, 3, 0x11, 0, 63, 0)

    private val DC_LUMA = HuffmanTable(JpegTables.DC_LUMINANCE_BITS, JpegTables.DC_LUMINANCE_VALUES)
    private val AC_LUMA = HuffmanTable(JpegTables.AC_LUMINANCE_BITS, JpegTables.AC_LUMINANCE_VALUES)
    private val DC_CHROMA = HuffmanTable(JpegTables.DC_CHROMINANCE_BITS, JpegTables.DC_CHROMINANCE_VALUES)
    private val AC_CHROMA = HuffmanTable(JpegTables.AC_CHROMINANCE_BITS, JpegTables.AC_CHROMINANCE_VALUES)
}
