package com.akane.akastudio.core.export.process

import com.akane.akastudio.core.color.api.HalfFloatImage
import com.akane.akastudio.core.color.math.ColorMath
import kotlin.math.floor

/**
 * Quantizes linear-light half rows to sRGB-encoded integer rows.
 *
 * Input alpha contract: a [HalfFloatImage] holds premultiplied linear light (what the renderer reads back). Output
 * rows follow one of two explicit alpha policies:
 * - Straight alpha ([toSrgb16Row], [toStraightSrgb8Row]), for formats that keep alpha (PNG16, lossless WebP): colour
 *   is unpremultiplied in linear light (divided by the alpha clamped to [0, 1]), clamped to [0, 1], then
 *   sRGB-encoded, so stored colour x stored alpha recomposes the premultiplied input. A pixel whose stored alpha code
 *   is 0 stores colour 0.
 * - Over black ([toSrgb8Row]), for formats that drop alpha (PNG8 colour type 2, Social JPEG, the Ultra HDR base):
 *   the premultiplied colour is encoded as is, which is the scene composited over black in linear light.
 *
 * Opaque pixels (alpha exactly 1) take the table path in both policies: the sRGB encode (clamped to [0, 1], NaN
 * treated as 0) is tabulated over all 65 536 half bit patterns; only translucent pixels evaluate it directly. 8-bit
 * colour adds blue noise before flooring (`q = floor(v + n)`), offset per channel so the three noise fields are
 * decorrelated; alpha is rounded without dither.
 */
object Dither {

    private const val NOISE_MASK = BlueNoise.SIZE - 1
    private val OFFSET_X = intArrayOf(0, 21, 43)
    private val OFFSET_Y = intArrayOf(0, 37, 11)

    /** sRGB-encoded value scaled to [0, 255] for each half bit pattern. */
    private val ENCODED_255 = FloatArray(1 shl 16) { ColorMath.linearToSrgb(unit(Half.bitsToFloat(it))) * 255f }

    /** Rounded sRGB-encoded 16-bit code for each half bit pattern. */
    private val ENCODED_65535 = IntArray(1 shl 16) { roundCode(ColorMath.linearToSrgb(unit(Half.bitsToFloat(it))), 65535) }

    /**
     * Premultiplied linear half row -> sRGB-encoded 8-bit ARGB composited over black, with blue-noise dithering.
     *
     * RGB is the premultiplied colour encoded directly (the scene over a black matte) and A is the rounded alpha.
     * Only for consumers that discard alpha: RGB is not straight colour, so keeping both applies alpha twice.
     * Alpha-preserving consumers use [toStraightSrgb8Row].
     */
    fun toSrgb8Row(img: HalfFloatImage, y: Int, out: IntArray) = srgb8Row(img, y, out, straight = false)

    /**
     * Premultiplied linear half row -> straight-alpha sRGB-encoded 8-bit ARGB with blue-noise dithering: the
     * [com.akane.akastudio.core.export.encode.Rgba8Rows] contract, and what `Bitmap.setPixels` expects.
     */
    fun toStraightSrgb8Row(img: HalfFloatImage, y: Int, out: IntArray) = srgb8Row(img, y, out, straight = true)

    private fun srgb8Row(img: HalfFloatImage, y: Int, out: IntArray, straight: Boolean) {
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
            val nr = noise[rowR + ((x + OFFSET_X[0]) and NOISE_MASK)]
            val ng = noise[rowG + ((x + OFFSET_X[1]) and NOISE_MASK)]
            val nb = noise[rowB + ((x + OFFSET_X[2]) and NOISE_MASK)]
            val alpha = unit(Half.bitsToFloat(src[p + 3].toInt()))
            val a = roundCode(alpha, 255)
            out[x] = when {
                !straight || alpha == 1f ->
                    (a shl 24) or (dithered(src[p], nr) shl 16) or (dithered(src[p + 1], ng) shl 8) or dithered(src[p + 2], nb)
                a == 0 -> 0
                else -> {
                    val inv = 1f / alpha
                    (a shl 24) or (ditheredStraight(src[p], inv, nr) shl 16) or
                        (ditheredStraight(src[p + 1], inv, ng) shl 8) or ditheredStraight(src[p + 2], inv, nb)
                }
            }
        }
    }

    /**
     * Premultiplied linear half row -> straight-alpha sRGB-encoded 16-bit (no dither needed; rounding): the
     * [com.akane.akastudio.core.export.encode.Rgba16Rows] contract. [out] receives interleaved R, G, B, A codes
     * (unsigned in the Short bits), size >= width * 4.
     */
    fun toSrgb16Row(img: HalfFloatImage, y: Int, out: ShortArray) {
        require(y in 0 until img.height) { "row $y outside 0 until ${img.height}" }
        val stride = img.width * 4
        require(out.size >= stride) { "out holds ${out.size} samples, needs $stride" }
        val src = img.rgba
        val base = y * stride
        for (x in 0 until img.width) {
            val p = x * 4
            val s = base + p
            val alpha = unit(Half.bitsToFloat(src[s + 3].toInt()))
            val a = roundCode(alpha, 65535)
            when {
                alpha == 1f -> {
                    out[p] = ENCODED_65535[src[s].toInt() and 0xFFFF].toShort()
                    out[p + 1] = ENCODED_65535[src[s + 1].toInt() and 0xFFFF].toShort()
                    out[p + 2] = ENCODED_65535[src[s + 2].toInt() and 0xFFFF].toShort()
                }
                a == 0 -> {
                    out[p] = 0
                    out[p + 1] = 0
                    out[p + 2] = 0
                }
                else -> {
                    val inv = 1f / alpha
                    out[p] = straight16(src[s], inv)
                    out[p + 1] = straight16(src[s + 1], inv)
                    out[p + 2] = straight16(src[s + 2], inv)
                }
            }
            out[p + 3] = a.toShort()
        }
    }

    private fun dithered(half: Short, noise: Float): Int =
        floor(ENCODED_255[half.toInt() and 0xFFFF] + noise).toInt().coerceIn(0, 255)

    /** Unpremultiplies [half] (times [inv], the reciprocal alpha) in linear light, encodes and dithers to 8 bits. */
    private fun ditheredStraight(half: Short, inv: Float, noise: Float): Int =
        floor(ColorMath.linearToSrgb(unit(Half.bitsToFloat(half.toInt()) * inv)) * 255f + noise).toInt().coerceIn(0, 255)

    /** Unpremultiplies [half] (times [inv], the reciprocal alpha) in linear light and rounds the encoded 16-bit code. */
    private fun straight16(half: Short, inv: Float): Short =
        roundCode(ColorMath.linearToSrgb(unit(Half.bitsToFloat(half.toInt()) * inv)), 65535).toShort()

    private fun unit(v: Float): Float = if (v.isNaN()) 0f else v.coerceIn(0f, 1f)

    private fun roundCode(v: Float, max: Int): Int = (v * max + 0.5f).toInt().coerceIn(0, max)
}
