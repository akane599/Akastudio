package com.akane.akastudio.core.curator.metrics

import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Exposure quality from the luma histogram: tonal entropy penalised by clipped highlights and shadows.
 *
 * The [Result.score] ranks photos and penalises clipping steeply, so a well-exposed low-key or high-key frame
 * (night, fireworks, snow, overcast sky) can score near zero. Whether a frame is unusable is a separate, much
 * stricter test, [Result.unusable].
 */
object Exposure {
    /** Entropy (bits) at which the entropy term saturates at 1. */
    private const val FULL_ENTROPY_BITS = 7.2f
    private const val HIGHLIGHT_ALLOWANCE = 0.01f
    private const val SHADOW_ALLOWANCE = 0.02f
    private const val HIGHLIGHT_PENALTY = 5f
    private const val SHADOW_PENALTY = 3f

    /** Clipped-highlight fraction above which the frame is unusable (mostly blown to white). */
    private const val UNUSABLE_CLIP_HIGH = 0.6f
    /** Clipped-shadow fraction above which the frame is unusable (mostly crushed to black). */
    private const val UNUSABLE_CLIP_LOW = 0.7f
    /** Entropy (bits) below which the frame is unusable: fewer tones than about six equally used levels. */
    private const val UNUSABLE_ENTROPY_BITS = 2.5f

    /**
     * Exposure evaluation.
     * @property score combined 0..1 exposure score.
     * @property entropyBits Shannon entropy of the 256-bin luma histogram, in bits.
     * @property clipHigh fraction of pixels whose rounded luma is above 252.
     * @property clipLow fraction of pixels whose rounded luma is below 3.
     */
    data class Result(val score: Float, val entropyBits: Float, val clipHigh: Float, val clipLow: Float) {
        /**
         * True when the frame carries too little tonal information to be a usable photo: more than 60% of it clipped
         * to white, more than 70% crushed to black, or a histogram entropy below 2.5 bits. Independent of [score].
         */
        val unusable: Boolean
            get() = clipHigh > UNUSABLE_CLIP_HIGH || clipLow > UNUSABLE_CLIP_LOW || entropyBits < UNUSABLE_ENTROPY_BITS
    }

    /**
     * Evaluates a 0..255 luma plane:
     * `score = clamp(H/7.2) * (1 - clamp(5*max(0, clipHigh-0.01))) * (1 - clamp(3*max(0, clipLow-0.02)))`.
     */
    fun evaluate(gray: FloatArray): Result {
        require(gray.isNotEmpty()) { "gray must not be empty" }
        val histogram = IntArray(256)
        for (y in gray) histogram[y.roundToInt().coerceIn(0, 255)]++
        val n = gray.size.toDouble()
        var entropy = 0.0
        for (count in histogram) {
            if (count == 0) continue
            val p = count / n
            entropy -= p * ln(p)
        }
        val entropyBits = (entropy / ln(2.0)).toFloat()
        val clipHigh = ((253..255).sumOf { histogram[it] } / n).toFloat()
        val clipLow = ((0..2).sumOf { histogram[it] } / n).toFloat()
        val entropyTerm = clamp01(entropyBits / FULL_ENTROPY_BITS)
        val highlightTerm = 1f - clamp01(HIGHLIGHT_PENALTY * maxOf(0f, clipHigh - HIGHLIGHT_ALLOWANCE))
        val shadowTerm = 1f - clamp01(SHADOW_PENALTY * maxOf(0f, clipLow - SHADOW_ALLOWANCE))
        return Result(entropyTerm * highlightTerm * shadowTerm, entropyBits, clipHigh, clipLow)
    }
}

internal fun clamp01(v: Float): Float = v.coerceIn(0f, 1f)
