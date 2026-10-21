package com.akane.akastudio.core.color.harmonize

import com.akane.akastudio.core.color.api.HarmonizationParams
import com.akane.akastudio.core.color.api.LabStats
import com.akane.akastudio.core.color.api.OkLab

/**
 * Cross-image OKLab harmonization: a partial, capped Reinhard-style mean/std transfer of each image
 * toward a chromatic anchor.
 *
 * Per channel, with source mean/std μs, σs and reference μr, σr:
 * `k = clamp(σr / max(σs, 1e-4), 0.5, 2)`, `w = λ · clamp(strength, 0, 1)` (λ = [LAMBDA_L] for L,
 * [LAMBDA_C] for a and b), `scale = 1 + w(k − 1)` and `offset = μs(1 − scale) + clamp(w(μr − μs), ±cap)`,
 * which equals `w(μr − k·μs)` below the cap. The scale pivots on the source mean, so only the mean
 * shift is capped: the source mean moves by at most |ΔL| ≤ 0.15 and |Δa|, |Δb| ≤ 0.06 toward the
 * reference, whatever k is. When both σs and σr are below 1e-4 the channel carries no contrast
 * information and k is 1, so identical flat stats still give the identity.
 */
object Harmonizer {
    /** Fraction of the full lightness transfer applied at strength 1. */
    const val LAMBDA_L = 0.55f

    /** Fraction of the full chroma-axis (a, b) transfer applied at strength 1. */
    const val LAMBDA_C = 0.70f

    internal const val MAX_OFFSET_L = 0.15f
    internal const val MAX_OFFSET_AB = 0.06f
    internal const val MIN_STD = 1e-4f
    internal const val MIN_CONTRAST_RATIO = 0.5f
    internal const val MAX_CONTRAST_RATIO = 2f

    /** Transfer moving [source] toward [reference] at [strength] in [0, 1] (clamped; NaN is rejected). */
    fun params(source: LabStats, reference: LabStats, strength: Float): HarmonizationParams {
        require(!strength.isNaN()) { "strength must not be NaN" }
        val s = strength.coerceIn(0f, 1f)
        if (s == 0f) return HarmonizationParams.IDENTITY
        val wL = LAMBDA_L * s
        val wC = LAMBDA_C * s
        val kL = contrastRatio(source.stdL, reference.stdL)
        val kA = contrastRatio(source.stdA, reference.stdA)
        val kB = contrastRatio(source.stdB, reference.stdB)
        return HarmonizationParams(
            scaleL = scale(wL, kL),
            offsetL = offset(wL, kL, source.meanL, reference.meanL, MAX_OFFSET_L),
            scaleA = scale(wC, kA),
            offsetA = offset(wC, kA, source.meanA, reference.meanA, MAX_OFFSET_AB),
            scaleB = scale(wC, kB),
            offsetB = offset(wC, kB, source.meanB, reference.meanB, MAX_OFFSET_AB),
        )
    }

    /**
     * One params per input; the anchor gets IDENTITY. strength in [0,1].
     *
     * Every other image is transferred toward `stats[anchorIndex]`. [anchorIndex] must index [stats].
     */
    fun forCollage(stats: List<LabStats>, anchorIndex: Int, strength: Float): List<HarmonizationParams> {
        require(anchorIndex in stats.indices) { "anchorIndex $anchorIndex outside 0 until ${stats.size}" }
        val anchor = stats[anchorIndex]
        return stats.mapIndexed { index, source ->
            if (index == anchorIndex) HarmonizationParams.IDENTITY else params(source, anchor, strength)
        }
    }

    /** Applies params to one OKLab colour (reference implementation used by the CPU and GPU pipelines). */
    fun apply(lab: OkLab, p: HarmonizationParams): OkLab = OkLab(
        l = p.scaleL * lab.l + p.offsetL,
        a = p.scaleA * lab.a + p.offsetA,
        b = p.scaleB * lab.b + p.offsetB,
    )

    private fun contrastRatio(sourceStd: Float, referenceStd: Float): Float {
        if (sourceStd < MIN_STD && referenceStd < MIN_STD) return 1f
        return (referenceStd / maxOf(sourceStd, MIN_STD)).coerceIn(MIN_CONTRAST_RATIO, MAX_CONTRAST_RATIO)
    }

    private fun scale(w: Float, k: Float): Float = 1f + w * (k - 1f)

    /** Re-centres [scale] on [sourceMean], then adds the capped mean shift toward [referenceMean]. */
    private fun offset(w: Float, k: Float, sourceMean: Float, referenceMean: Float, cap: Float): Float {
        val shift = (w * (referenceMean - sourceMean)).coerceIn(-cap, cap)
        return sourceMean * (1f - scale(w, k)) + shift
    }
}
