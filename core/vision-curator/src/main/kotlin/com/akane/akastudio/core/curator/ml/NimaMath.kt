package com.akane.akastudio.core.curator.ml

/**
 * Pure NIMA (Talebi & Milanfar 2018) helpers: MobileNet input normalization and the mean of the
 * 10-bin aesthetic score distribution.
 */
object NimaMath {

    /** Number of score bins the NIMA head predicts (scores 1..10). */
    const val BIN_COUNT = 10

    /** Square input edge the NIMA MobileNet expects. */
    const val INPUT_EDGE = 224

    /** Lowest and highest possible mean score. */
    const val MIN_SCORE = 1f
    const val MAX_SCORE = 10f

    /**
     * Mean score `Σ (i + 1) · p[i]` of the 10-bin distribution, in [1, 10].
     *
     * The distribution is renormalized by its sum first, so fp16 rounding of a softmax output cannot push
     * the mean outside the valid range. Throws [IllegalArgumentException] when the array is not 10 finite,
     * non-negative values with a positive sum.
     */
    fun meanScore(probabilities: FloatArray): Float {
        require(probabilities.size == BIN_COUNT) { "expected $BIN_COUNT bins, got ${probabilities.size}" }
        var sum = 0.0
        var weighted = 0.0
        for (i in probabilities.indices) {
            val p = probabilities[i]
            require(p.isFinite() && p >= 0f) { "bin $i is not a probability: $p" }
            sum += p
            weighted += (i + 1) * p.toDouble()
        }
        require(sum > 0.0) { "distribution sums to zero" }
        return (weighted / sum).toFloat().coerceIn(MIN_SCORE, MAX_SCORE)
    }

    /**
     * Writes the NHWC float input for packed ARGB pixels: per pixel R, G, B, each mapped `v / 127.5 − 1`
     * so 0 becomes −1 and 255 becomes 1. Alpha is ignored. [out] must hold exactly `argb.size * 3` values.
     */
    fun preprocess(argb: IntArray, out: FloatArray) {
        require(out.size == argb.size * 3) { "out must hold ${argb.size * 3} floats, has ${out.size}" }
        var o = 0
        for (pixel in argb) {
            out[o++] = normalize((pixel ushr 16) and 0xFF)
            out[o++] = normalize((pixel ushr 8) and 0xFF)
            out[o++] = normalize(pixel and 0xFF)
        }
    }

    private fun normalize(channel: Int): Float = channel / 127.5f - 1f
}
