package com.akane.akastudio.core.color.analysis

import com.akane.akastudio.core.color.api.ImagePixels
import com.akane.akastudio.core.color.api.LabStats
import com.akane.akastudio.core.color.api.OkLab
import com.akane.akastudio.core.color.api.PaletteSwatch
import com.akane.akastudio.core.color.math.ColorMath
import kotlin.math.min
import kotlin.random.Random

/**
 * Colour description of one analysis image.
 *
 * @property labStats skin- and clip-weighted OKLab mean and spread (see [ColorAnalysis.labStats]).
 * @property palette dominant OKLab colours, heaviest first, weights summing to 1.
 * @property cctKelvin estimated scene illuminant correlated colour temperature, in kelvin.
 * @property skinFraction unweighted mean [SkinMask] value over the sampled pixels, in [0, 1].
 */
data class ColorAnalysisResult(
    val labStats: LabStats,
    val palette: List<PaletteSwatch>,
    val cctKelvin: Float,
    val skinFraction: Float,
)

/** OKLab statistics, palette and illuminant analysis over sRGB-encoded [ImagePixels]. */
object ColorAnalysis {
    private const val DEFAULT_STATS_SAMPLES = 65_536
    private const val DEFAULT_PALETTE_SAMPLES = 4_096
    private const val DEFAULT_PALETTE_SIZE = 5
    private const val MAX_LLOYD_ITERATIONS = 25
    private const val CENTROID_TOLERANCE = 1e-5
    private const val SKIN_DOWN_WEIGHT = 0.8
    private const val CLIP_DOWN_WEIGHT = 0.2
    private const val CLIP_HIGH_L = 0.97f
    private const val CLIP_LOW_L = 0.03f

    /**
     * Full analysis: [labStats] and the skin fraction over at most [maxSamples] pixels, a
     * five-colour [palette] over at most `min(maxSamples, 4096)` pixels seeded with [seed], and the
     * illuminant [estimateCctKelvin].
     */
    fun analyze(pixels: ImagePixels, maxSamples: Int = DEFAULT_STATS_SAMPLES, seed: Long = 42L): ColorAnalysisResult {
        val accumulator = WeightedLabAccumulator()
        var skinSum = 0.0
        var count = 0
        SampleGrid.forEachIndex(pixels.width, pixels.height, maxSamples) { index ->
            val lab = ColorMath.argbToOkLab(pixels.argb[index])
            val skin = SkinMask.weight(lab)
            accumulator.add(lab, statsWeight(lab, skin))
            skinSum += skin
            count++
        }
        return ColorAnalysisResult(
            labStats = accumulator.toLabStats(),
            palette = palette(pixels, DEFAULT_PALETTE_SIZE, min(maxSamples, DEFAULT_PALETTE_SAMPLES), seed),
            cctKelvin = estimateCctKelvin(pixels),
            skinFraction = (skinSum / count).toFloat(),
        )
    }

    /**
     * Weighted OKLab mean and population standard deviation over a deterministic stride grid of at
     * most [maxSamples] pixels. Each sample weighs `1 − 0.8·SkinMask` (so skin does not drag a colour
     * transfer), times 0.2 when near-clipped (L > 0.97 or L < 0.03). [LabStats.weight] is the weight sum.
     */
    fun labStats(pixels: ImagePixels, maxSamples: Int = DEFAULT_STATS_SAMPLES): LabStats {
        val accumulator = WeightedLabAccumulator()
        SampleGrid.forEachIndex(pixels.width, pixels.height, maxSamples) { index ->
            val lab = ColorMath.argbToOkLab(pixels.argb[index])
            accumulator.add(lab, statsWeight(lab, SkinMask.weight(lab)))
        }
        return accumulator.toLabStats()
    }

    /**
     * Dominant colours by weighted K-means++ in OKLab over at most [maxSamples] grid pixels.
     *
     * Identical sRGB samples are merged into one point weighted by its multiplicity. Seeding uses
     * `Random(seed)`, refinement runs at most 25 Lloyd iterations and stops once every centroid moves
     * less than 1e-5. Exactly [k] swatches are returned, sorted by weight descending, with weights
     * (cluster weight shares) summing to 1. A single-colour image yields [k] copies of that colour at
     * `1/k` each; any other image holding fewer than [k] distinct colours pads the list with copies of
     * the heaviest colour at weight 0.
     */
    fun palette(
        pixels: ImagePixels,
        k: Int = DEFAULT_PALETTE_SIZE,
        maxSamples: Int = DEFAULT_PALETTE_SAMPLES,
        seed: Long = 42L,
    ): List<PaletteSwatch> {
        require(k >= 1) { "k must be >= 1, was $k" }
        val multiplicity = LinkedHashMap<Int, Int>()
        SampleGrid.forEachIndex(pixels.width, pixels.height, maxSamples) { index ->
            val rgb = pixels.argb[index] and 0xFFFFFF
            multiplicity[rgb] = (multiplicity[rgb] ?: 0) + 1
        }
        val size = multiplicity.size
        val l = DoubleArray(size)
        val a = DoubleArray(size)
        val b = DoubleArray(size)
        val w = DoubleArray(size)
        var i = 0
        for ((rgb, count) in multiplicity) {
            val lab = ColorMath.argbToOkLab(rgb)
            l[i] = lab.l.toDouble()
            a[i] = lab.a.toDouble()
            b[i] = lab.b.toDouble()
            w[i] = count.toDouble()
            i++
        }
        val result = WeightedKMeans(l, a, b, w).run(k, Random(seed), MAX_LLOYD_ITERATIONS, CENTROID_TOLERANCE)
        return toSwatches(result, k)
    }

    /**
     * Correlated colour temperature, in kelvin, of the Shades-of-Gray (p = 6) illuminant estimate
     * via McCamy's approximation, clamped to [1667, 25000]. An image with no usable pixels (black or
     * fully clipped) returns 6504.
     */
    fun estimateCctKelvin(pixels: ImagePixels): Float = CctEstimator.estimate(pixels)

    private fun statsWeight(lab: OkLab, skin: Float): Double {
        val base = 1.0 - SKIN_DOWN_WEIGHT * skin
        val nearClipped = lab.l > CLIP_HIGH_L || lab.l < CLIP_LOW_L
        return if (nearClipped) base * CLIP_DOWN_WEIGHT else base
    }

    private fun toSwatches(result: WeightedKMeans.Result, k: Int): List<PaletteSwatch> {
        val total = result.clusterWeights.sum()
        val occupied = result.clusterWeights.indices
            .filter { result.clusterWeights[it] > 0.0 }
            .sortedByDescending { result.clusterWeights[it] }
        val swatches = occupied.map { j -> PaletteSwatch(labOf(result.centroids[j]), (result.clusterWeights[j] / total).toFloat()) }
        if (swatches.size == 1) {
            val share = 1f / k
            return List(k) { PaletteSwatch(swatches[0].lab, share) }
        }
        val heaviest = swatches[0].lab
        return swatches + List(k - swatches.size) { PaletteSwatch(heaviest, 0f) }
    }

    private fun labOf(centroid: DoubleArray): OkLab =
        OkLab(centroid[0].toFloat(), centroid[1].toFloat(), centroid[2].toFloat())
}
