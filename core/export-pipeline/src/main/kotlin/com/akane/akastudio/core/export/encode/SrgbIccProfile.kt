package com.akane.akastudio.core.export.encode

import java.io.ByteArrayOutputStream
import java.util.IdentityHashMap
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * ICC v2.1 display profile "sRGB IEC61966-2.1", generated in code.
 *
 * Matrix/TRC profile: `mntr` class, RGB data, XYZ PCS with the D50 illuminant, perceptual rendering
 * intent, the sRGB primaries Bradford-adapted from D65 to D50, and 1024-entry `curv` tone curves
 * sampling the sRGB EOTF. The profile ID is left zero.
 */
object SrgbIccProfile {
    private val cached: ByteArray by lazy { IccProfileBuilder.build() }

    /** The profile bytes. Generated once and cached; every read returns a fresh copy the caller may keep. */
    val bytes: ByteArray
        get() = cached.copyOf()
}

/** Builds the sRGB ICC profile. Kept separate from the public object so the layout math is testable. */
internal object IccProfileBuilder {
    const val DESCRIPTION: String = "sRGB IEC61966-2.1"
    const val COPYRIGHT: String = "No copyright, use freely"
    const val TRC_ENTRIES: Int = 1024

    private const val HEADER_SIZE = 128
    private const val TAG_ENTRY_SIZE = 12
    private const val VERSION_2_1 = 0x02100000

    /** ICC PCS illuminant (D50) exactly as the ICC specification encodes it. */
    val D50: DoubleArray = doubleArrayOf(0.9642, 1.0, 0.8249)

    private val BRADFORD = arrayOf(
        doubleArrayOf(0.8951, 0.2664, -0.1614),
        doubleArrayOf(-0.7502, 1.7135, 0.0367),
        doubleArrayOf(0.0389, -0.0685, 1.0296),
    )

    fun build(): ByteArray {
        val colorants = srgbToD50Xyz()
        val trc = curve()
        val tags = listOf(
            "desc" to textDescription(DESCRIPTION),
            "cprt" to text(COPYRIGHT),
            "wtpt" to xyz(D50),
            "rXYZ" to xyz(column(colorants, 0)),
            "gXYZ" to xyz(column(colorants, 1)),
            "bXYZ" to xyz(column(colorants, 2)),
            "rTRC" to trc,
            "gTRC" to trc,
            "bTRC" to trc,
        )
        return assemble(tags)
    }

    /** Header, tag table and 4-byte-aligned tag data. Tags that share one element instance share its offset. */
    private fun assemble(tags: List<Pair<String, ByteArray>>): ByteArray {
        val offsets = IdentityHashMap<ByteArray, Int>()
        val data = ByteArrayOutputStream()
        val dataStart = HEADER_SIZE + 4 + TAG_ENTRY_SIZE * tags.size
        for ((_, element) in tags) {
            if (offsets.containsKey(element)) continue
            offsets[element] = dataStart + data.size()
            data.write(element)
            repeat(padding4(element.size)) { data.write(0) }
        }
        val total = dataStart + data.size()
        val out = BigEndianBuffer(total)
        writeHeader(out, total)
        out.u32(tags.size.toLong())
        for ((signature, element) in tags) {
            out.signature(signature)
            out.u32(offsets.getValue(element).toLong())
            out.u32(element.size.toLong())
        }
        out.bytes(data.toByteArray())
        check(out.position == total) { "ICC layout mismatch: wrote ${out.position} of $total bytes" }
        return out.array
    }

    private fun writeHeader(out: BigEndianBuffer, total: Int) {
        out.u32(total.toLong())          // profile size
        out.u32(0)                       // preferred CMM: none
        out.u32(VERSION_2_1.toLong())    // version 2.1.0
        out.signature("mntr")            // device class: display
        out.signature("RGB ")            // data colour space
        out.signature("XYZ ")            // PCS
        // Creation date/time (fixed so the bytes are deterministic): 2026-01-01 00:00:00.
        out.u16(2026); out.u16(1); out.u16(1); out.u16(0); out.u16(0); out.u16(0)
        out.signature("acsp")            // profile file signature
        out.u32(0)                       // primary platform: none
        out.u32(0)                       // flags
        out.u32(0)                       // device manufacturer
        out.u32(0)                       // device model
        out.u32(0); out.u32(0)           // device attributes
        out.u32(0)                       // rendering intent: perceptual
        D50.forEach { out.s15Fixed16(it) } // PCS illuminant
        out.u32(0)                       // profile creator
        out.zeros(16)                    // profile ID left zero
        out.zeros(HEADER_SIZE - out.position) // reserved
    }

    /** sRGB-to-XYZ(D50) matrix: rows X, Y, Z; columns R, G, B. */
    fun srgbToD50Xyz(): Array<DoubleArray> {
        val red = chromaticityToXyz(0.64, 0.33)
        val green = chromaticityToXyz(0.30, 0.60)
        val blue = chromaticityToXyz(0.15, 0.06)
        val d65 = chromaticityToXyz(0.3127, 0.3290)
        val primaries = arrayOf(
            doubleArrayOf(red[0], green[0], blue[0]),
            doubleArrayOf(red[1], green[1], blue[1]),
            doubleArrayOf(red[2], green[2], blue[2]),
        )
        val scale = multiply(invert(primaries), d65)
        val rgbToXyzD65 = Array(3) { r -> DoubleArray(3) { c -> primaries[r][c] * scale[c] } }
        return multiply(bradford(d65, D50), rgbToXyzD65)
    }

    /** sRGB EOTF: encoded value in [0, 1] to linear light in [0, 1]. */
    fun srgbEotf(encoded: Double): Double =
        if (encoded <= 0.04045) encoded / 12.92 else ((encoded + 0.055) / 1.055).pow(2.4)

    private fun bradford(sourceWhite: DoubleArray, destinationWhite: DoubleArray): Array<DoubleArray> {
        val source = multiply(BRADFORD, sourceWhite)
        val destination = multiply(BRADFORD, destinationWhite)
        val gain = Array(3) { r -> DoubleArray(3) { c -> if (r == c) destination[r] / source[r] else 0.0 } }
        return multiply(invert(BRADFORD), multiply(gain, BRADFORD))
    }

    private fun chromaticityToXyz(x: Double, y: Double): DoubleArray = doubleArrayOf(x / y, 1.0, (1.0 - x - y) / y)

    private fun column(m: Array<DoubleArray>, c: Int): DoubleArray = doubleArrayOf(m[0][c], m[1][c], m[2][c])

    private fun multiply(a: Array<DoubleArray>, v: DoubleArray): DoubleArray =
        DoubleArray(3) { r -> a[r][0] * v[0] + a[r][1] * v[1] + a[r][2] * v[2] }

    private fun multiply(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> =
        Array(3) { r -> DoubleArray(3) { c -> a[r][0] * b[0][c] + a[r][1] * b[1][c] + a[r][2] * b[2][c] } }

    private fun invert(m: Array<DoubleArray>): Array<DoubleArray> {
        val c00 = m[1][1] * m[2][2] - m[1][2] * m[2][1]
        val c01 = m[1][2] * m[2][0] - m[1][0] * m[2][2]
        val c02 = m[1][0] * m[2][1] - m[1][1] * m[2][0]
        val det = m[0][0] * c00 + m[0][1] * c01 + m[0][2] * c02
        require(det != 0.0) { "singular matrix" }
        return arrayOf(
            doubleArrayOf(c00 / det, (m[0][2] * m[2][1] - m[0][1] * m[2][2]) / det, (m[0][1] * m[1][2] - m[0][2] * m[1][1]) / det),
            doubleArrayOf(c01 / det, (m[0][0] * m[2][2] - m[0][2] * m[2][0]) / det, (m[0][2] * m[1][0] - m[0][0] * m[1][2]) / det),
            doubleArrayOf(c02 / det, (m[0][1] * m[2][0] - m[0][0] * m[2][1]) / det, (m[0][0] * m[1][1] - m[0][1] * m[1][0]) / det),
        )
    }

    /** `textDescriptionType` (ICC v2): ASCII part only, empty Unicode and ScriptCode parts. */
    private fun textDescription(value: String): ByteArray {
        val ascii = value.toByteArray(Charsets.US_ASCII) + 0.toByte()
        val out = BigEndianBuffer(12 + ascii.size + 4 + 4 + 2 + 1 + 67)
        out.signature("desc")
        out.u32(0)
        out.u32(ascii.size.toLong())
        out.bytes(ascii)
        out.u32(0)      // Unicode language code
        out.u32(0)      // Unicode count
        out.u16(0)      // ScriptCode code
        out.u8(0)       // ScriptCode count
        out.zeros(67)   // Macintosh description
        return out.array
    }

    /** `textType`: NUL-terminated ASCII. */
    private fun text(value: String): ByteArray {
        val ascii = value.toByteArray(Charsets.US_ASCII) + 0.toByte()
        val out = BigEndianBuffer(8 + ascii.size)
        out.signature("text")
        out.u32(0)
        out.bytes(ascii)
        return out.array
    }

    private fun xyz(value: DoubleArray): ByteArray {
        val out = BigEndianBuffer(20)
        out.signature("XYZ ")
        out.u32(0)
        value.forEach { out.s15Fixed16(it) }
        return out.array
    }

    /** `curveType` with [TRC_ENTRIES] uInt16 samples of the sRGB EOTF. */
    private fun curve(): ByteArray {
        val out = BigEndianBuffer(12 + 2 * TRC_ENTRIES)
        out.signature("curv")
        out.u32(0)
        out.u32(TRC_ENTRIES.toLong())
        for (i in 0 until TRC_ENTRIES) {
            out.u16((srgbEotf(i / (TRC_ENTRIES - 1).toDouble()) * 65535.0).roundToInt())
        }
        return out.array
    }

    private fun padding4(size: Int): Int = (4 - size % 4) % 4

    /** Fixed-size big-endian writer for the profile structures. */
    private class BigEndianBuffer(size: Int) {
        val array = ByteArray(size)
        var position = 0
            private set

        fun u8(value: Int) {
            array[position++] = value.toByte()
        }

        fun u16(value: Int) {
            u8(value ushr 8)
            u8(value)
        }

        fun u32(value: Long) {
            u16((value ushr 16).toInt() and 0xFFFF)
            u16(value.toInt() and 0xFFFF)
        }

        fun s15Fixed16(value: Double) = u32((value * 65536.0).roundToLong() and 0xFFFFFFFFL)

        fun signature(value: String) {
            require(value.length == 4) { "ICC signature must be 4 characters: '$value'" }
            bytes(value.toByteArray(Charsets.US_ASCII))
        }

        fun bytes(value: ByteArray) {
            value.copyInto(array, position)
            position += value.size
        }

        fun zeros(count: Int) {
            position += count
        }
    }
}
