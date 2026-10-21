package com.akane.akastudio.core.color.analysis

import com.akane.akastudio.core.color.api.ImagePixels
import com.akane.akastudio.core.color.grade.PlanckianLocus
import com.akane.akastudio.core.color.math.ColorMath
import kotlin.math.pow

/**
 * Scene correlated colour temperature from a Shades-of-Gray illuminant estimate.
 *
 * The illuminant is the per-channel Minkowski p-norm mean (p = 6) of linear sRGB over the pixels
 * that are neither near-clipped (any channel > 0.98) nor near-black (luminance < 0.02). It is
 * converted to CIE XYZ with the sRGB D65 matrix, projected to CIE 1960 uv and mapped to the kelvin of the
 * nearest point on the Planckian locus between 40 and 600 mired, so the result lies in [1667, 25000] K.
 *
 * The nearest-point search is the definition of CCT and, unlike McCamy's cubic, stays monotone off the
 * locus: a strongly blue illuminant (y below McCamy's epicentre, 0.1858) reads as very cold instead of
 * wrapping round to the warm end.
 */
internal object CctEstimator {
    const val DEGENERATE_CCT_KELVIN = 6504f
    const val MIN_CCT_KELVIN = 1667f
    const val MAX_CCT_KELVIN = 25_000f
    const val MAX_SAMPLES = 65_536

    private const val MIN_MIRED = 40
    private const val MAX_MIRED = 600
    private const val REFINE_ITERATIONS = 32
    private const val GOLDEN = 0.6180339887498949

    private const val MINKOWSKI_P = 6.0
    private const val CLIP_CHANNEL = 0.98f
    private const val MIN_LUMINANCE = 0.02f

    private val linearLut = FloatArray(256) { ColorMath.srgbToLinear(it / 255f) }

    /** Planckian locus in CIE 1960 uv at every whole mired from [MIN_MIRED] to [MAX_MIRED]. */
    private val locusU = DoubleArray(MAX_MIRED - MIN_MIRED + 1)
    private val locusV = DoubleArray(MAX_MIRED - MIN_MIRED + 1)

    init {
        for (i in locusU.indices) {
            val uv = PlanckianLocus.uv(1e6 / (MIN_MIRED + i))
            locusU[i] = uv[0]
            locusV[i] = uv[1]
        }
    }

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

    /** CCT of a linear-sRGB (D65) illuminant colour; a zero or undefined colour yields 6504 K. */
    fun cctFromLinearSrgb(r: Double, g: Double, b: Double): Float {
        val bigX = 0.4124564 * r + 0.3575761 * g + 0.1804375 * b
        val bigY = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
        val bigZ = 0.0193339 * r + 0.1191920 * g + 0.9503041 * b
        val sum = bigX + bigY + bigZ
        if (!(sum > 0.0) || !sum.isFinite()) return DEGENERATE_CCT_KELVIN
        val denominator = bigX + 15.0 * bigY + 3.0 * bigZ
        if (!(denominator > 0.0) || !denominator.isFinite()) return DEGENERATE_CCT_KELVIN
        val u = 4.0 * bigX / denominator
        val v = 6.0 * bigY / denominator
        val mired = nearestLocusMired(u, v)
        return (1e6 / mired).coerceIn(MIN_CCT_KELVIN.toDouble(), MAX_CCT_KELVIN.toDouble()).toFloat()
    }

    /**
     * Mired of the locus point nearest (u, v): the closest whole-mired table entry, then a golden-section
     * refinement over its two neighbouring mired steps.
     */
    private fun nearestLocusMired(u: Double, v: Double): Double {
        var best = 0
        var bestDistance = Double.POSITIVE_INFINITY
        for (i in locusU.indices) {
            val du = locusU[i] - u
            val dv = locusV[i] - v
            val distance = du * du + dv * dv
            if (distance < bestDistance) {
                bestDistance = distance
                best = i
            }
        }
        var lo = (MIN_MIRED + best - 1).coerceAtLeast(MIN_MIRED).toDouble()
        var hi = (MIN_MIRED + best + 1).coerceAtMost(MAX_MIRED).toDouble()
        repeat(REFINE_ITERATIONS) {
            val m1 = hi - GOLDEN * (hi - lo)
            val m2 = lo + GOLDEN * (hi - lo)
            if (locusDistance(m1, u, v) < locusDistance(m2, u, v)) hi = m2 else lo = m1
        }
        return 0.5 * (lo + hi)
    }

    private fun locusDistance(mired: Double, u: Double, v: Double): Double {
        val uv = PlanckianLocus.uv(1e6 / mired)
        val du = uv[0] - u
        val dv = uv[1] - v
        return du * du + dv * dv
    }

    private fun pow6(v: Double): Double {
        val v2 = v * v
        return v2 * v2 * v2
    }
}
