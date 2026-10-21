package com.akane.akastudio.core.export.process

import com.akane.akastudio.core.color.api.HalfFloatImage
import com.akane.akastudio.core.color.math.ColorMath
import kotlin.math.floor

/**
 * Quantizes linear-light half rows to sRGB-encoded integer rows.
 *
 * Every input is a half, so the sRGB encode (clamped to [0, 1], NaN treated as 0) is tabulated over all 65 536 half
 * bit patterns. 8-bit colour adds blue noise before flooring (`q = floor(v + n)`), offset per channel so the three
 * noise fields are decorrelated; alpha is rounded without dither.
 */
object Dither {

    private const val NOISE_MASK = BlueNoise.SIZE - 1
    private val OFFSET_X = intArrayOf(0, 21, 43)
    private val OFFSET_Y = intArrayOf(0, 37, 11)

    /** sRGB-encoded value scaled to [0, 255] for each half bit pattern. */
    private val ENCODED_255 = FloatArray(1 shl 16) { ColorMath.linearToSrgb(unit(Half.bitsToFloat(it))) * 255f }

    /** Rounded sRGB-encoded 16-bit code for each half bit pattern. */
    private val ENCODED_65535 = IntArray(1 shl 16) { roundCode(ColorMath.linearToSrgb(unit(Half.bitsToFloat(it))), 65535) }

    /** Linear-light half image -> sRGB-encoded 8-bit ARGB rows with blue-noise dithering. */
    fun toSrgb8Row(img: HalfFloatImage, y: Int, out: IntArray) {
        require(y in 0 until img.height) { "row $y outside 0 until ${img.height}" }
        require(out.size >= img.width) { "out holds ${out.size} pixels, needs ${img.width}" }
        val noise = BlueNoise.ranks
        val src = img.rgba
        val base = y * img.width * 4
        val rowR = ((y + OFFSET_Y[0]) and NOISE_MASK) * BlueNoise.SIZE
        val rowG = ((y + OFFSET_Y[1]) and NOISE_MASK) * BlueNoise.SIZE
        val rowB = ((y + OFFSET_Y[2]) and NOISE_MASK) * BlueNoise.SIZE
        for (x in 0 until img.width) {
            val p = base + x * 4
            val r = dithered(src[p], noise[rowR + ((x + OFFSET_X[0]) and NOISE_MASK)])
            val g = dithered(src[p + 1], noise[rowG + ((x + OFFSET_X[1]) and NOISE_MASK)])
            val b = dithered(src[p + 2], noise[rowB + ((x + OFFSET_X[2]) and NOISE_MASK)])
            val a = roundCode(unit(Half.bitsToFloat(src[p + 3].toInt())), 255)
            out[x] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /** Linear-light -> sRGB-encoded 16-bit (no dither needed; rounding). [out] receives interleaved R, G, B, A codes (unsigned in the Short bits), size >= width * 4. */
    fun toSrgb16Row(img: HalfFloatImage, y: Int, out: ShortArray) {
        require(y in 0 until img.height) { "row $y outside 0 until ${img.height}" }
        val stride = img.width * 4
        require(out.size >= stride) { "out holds ${out.size} samples, needs $stride" }
        val src = img.rgba
        val base = y * stride
        for (x in 0 until img.width) {
            val p = x * 4
            out[p] = ENCODED_65535[src[base + p].toInt() and 0xFFFF].toShort()
            out[p + 1] = ENCODED_65535[src[base + p + 1].toInt() and 0xFFFF].toShort()
            out[p + 2] = ENCODED_65535[src[base + p + 2].toInt() and 0xFFFF].toShort()
            out[p + 3] = roundCode(unit(Half.bitsToFloat(src[base + p + 3].toInt())), 65535).toShort()
        }
    }

    private fun dithered(half: Short, noise: Float): Int =
        floor(ENCODED_255[half.toInt() and 0xFFFF] + noise).toInt().coerceIn(0, 255)

    private fun unit(v: Float): Float = if (v.isNaN()) 0f else v.coerceIn(0f, 1f)

    private fun roundCode(v: Float, max: Int): Int = (v * max + 0.5f).toInt().coerceIn(0, max)
}
