package com.akane.akastudio.core.color.analysis

import com.akane.akastudio.core.color.api.ImagePixels
import com.akane.akastudio.core.color.math.ColorMath
import kotlin.math.pow

/**
 * Scene correlated colour temperature from a Shades-of-Gray illuminant estimate.
 *
 * The illuminant is the per-channel Minkowski p-norm mean (p = 6) of linear sRGB over the pixels
 * that are neither near-clipped (any channel > 0.98) nor near-black (luminance < 0.02). It is
 * converted to CIE XYZ with the sRGB D65 matrix, projected to xy and mapped to kelvin with McCamy's
 * cubic, clamped to [1667, 25000] K.
 */
internal object CctEstimator {
    const val DEGENERATE_CCT_KELVIN = 6504f
    const val MIN_CCT_KELVIN = 1667f
    const val MAX_CCT_KELVIN = 25_000f
    const val MAX_SAMPLES = 65_536

    private const val MINKOWSKI_P = 6.0
    private const val CLIP_CHANNEL = 0.98f
    private const val MIN_LUMINANCE = 0.02f

    private val linearLut = FloatArray(256) { ColorMath.srgbToLinear(it / 255f) }

    fun estimate(pixels: ImagePixels, maxSamples: Int = MAX_SAMPLES): Float {
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var count = 0
        val argb = pixels.argb
        SampleGrid.forEachIndex(pixels.width, pixels.height, maxSamples) { index ->
            val c = argb[index]
            val r = linearLut[(c ushr 16) and 0xFF]
            val g = linearLut[(c ushr 8) and 0xFF]
            val b = linearLut[c and 0xFF]
            val clipped = r > CLIP_CHANNEL || g > CLIP_CHANNEL || b > CLIP_CHANNEL
            if (!clipped && ColorMath.relativeLuminance(r, g, b) >= MIN_LUMINANCE) {
                sumR += pow6(r.toDouble())
                sumG += pow6(g.toDouble())
                sumB += pow6(b.toDouble())
                count++
            }
        }
        if (count == 0) return DEGENERATE_CCT_KELVIN
        val inverseP = 1.0 / MINKOWSKI_P
        return cctFromLinearSrgb(
            (sumR / count).pow(inverseP),
            (sumG / count).pow(inverseP),
            (sumB / count).pow(inverseP),
        )
    }

    /** McCamy CCT of a linear-sRGB (D65) illuminant colour; a zero or undefined colour yields 6504 K. */
    fun cctFromLinearSrgb(r: Double, g: Double, b: Double): Float {
        val bigX = 0.4124564 * r + 0.3575761 * g + 0.1804375 * b
        val bigY = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
        val bigZ = 0.0193339 * r + 0.1191920 * g + 0.9503041 * b
        val sum = bigX + bigY + bigZ
        if (!(sum > 0.0) || !sum.isFinite()) return DEGENERATE_CCT_KELVIN
        val x = bigX / sum
        val y = bigY / sum
        val n = (x - 0.3320) / (0.1858 - y)
        val cct = 449.0 * n * n * n + 3525.0 * n * n + 6823.3 * n + 5520.33
        if (cct.isNaN()) return DEGENERATE_CCT_KELVIN
        return cct.coerceIn(MIN_CCT_KELVIN.toDouble(), MAX_CCT_KELVIN.toDouble()).toFloat()
    }

    private fun pow6(v: Double): Double {
        val v2 = v * v
        return v2 * v2 * v2
    }
}
