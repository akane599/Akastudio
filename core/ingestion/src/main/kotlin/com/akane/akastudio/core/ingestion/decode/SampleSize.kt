package com.akane.akastudio.core.ingestion.decode

internal object SampleSize {
    /**
     * Largest power of two `sample` with `longEdge / sample >= maxEdge`, so a sampled decode never undershoots
     * [maxEdge]. Minimum 1; non-positive inputs yield 1.
     */
    fun forTarget(width: Int, height: Int, maxEdge: Int): Int {
        if (width <= 0 || height <= 0 || maxEdge <= 0) return 1
        val longEdge = maxOf(width, height).toLong()
        var sample = 1L
        while (longEdge / (sample * 2) >= maxEdge) sample *= 2
        return sample.toInt()
    }
}
