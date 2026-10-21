package com.akane.akastudio.core.curator.metrics

import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.curator.api.FaceObservation
import com.akane.akastudio.core.curator.api.SaliencyMap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln

/**
 * Focus measure: variance of the 3x3 Laplacian `[0,1,0; 1,-4,1; 0,1,0]` on a 0..255 gray plane,
 * mapped to a 0..1 score on a log scale.
 */
object Sharpness {
    /** Weight applied to pixels inside the subject box; everything else weighs 1. */
    const val SUBJECT_WEIGHT = 3f

    /** Saliency level at or above which a cell counts as subject. */
    const val SUBJECT_SALIENCY = 0.5f

    private val LOG_LOW = ln(21.0)
    private val LOG_HIGH = ln(801.0)

    /**
     * Weighted variance (around the weighted mean) of the Laplacian response over the interior pixels of
     * [gray] (`w x h`, row-major). [weights], when given, has one entry per pixel. Returns 0 when the plane
     * has no interior pixel or no positive weight.
     */
    fun laplacianVariance(gray: FloatArray, w: Int, h: Int, weights: FloatArray? = null): Float {
        require(w > 0 && h > 0 && gray.size == w * h) { "gray must be $w x $h" }
        require(weights == null || weights.size == gray.size) { "weights must match gray" }
        if (w < 3 || h < 3) return 0f
        var sumW = 0.0
        var sum = 0.0
        var sumSq = 0.0
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val wt = weights?.get(i)?.toDouble() ?: 1.0
                if (wt <= 0.0) continue
                val lap = (gray[i - w] + gray[i + w] + gray[i - 1] + gray[i + 1] - 4f * gray[i]).toDouble()
                sumW += wt
                sum += wt * lap
                sumSq += wt * lap * lap
            }
        }
        if (sumW <= 0.0) return 0f
        val mean = sum / sumW
        return (sumSq / sumW - mean * mean).coerceAtLeast(0.0).toFloat()
    }

    /** `clamp((ln(1+v) - ln 21) / (ln 801 - ln 21), 0, 1)`: variance 20 maps to 0, variance 800 to 1. */
    fun score(variance: Float): Float {
        val v = variance.toDouble().coerceAtLeast(0.0)
        return ((ln(1.0 + v) - LOG_LOW) / (LOG_HIGH - LOG_LOW)).coerceIn(0.0, 1.0).toFloat()
    }

    /** [score] of the unweighted Laplacian variance on the crop of [gray] covered by [box]. */
    fun faceSharpness(gray: FloatArray, w: Int, h: Int, box: NormRect): Float {
        require(w > 0 && h > 0 && gray.size == w * h) { "gray must be $w x $h" }
        val bounds = pixelBounds(box, w, h) ?: return 0f
        val cw = bounds[2] - bounds[0]
        val ch = bounds[3] - bounds[1]
        val crop = FloatArray(cw * ch)
        for (y in 0 until ch) {
            System.arraycopy(gray, (bounds[1] + y) * w + bounds[0], crop, y * cw, cw)
        }
        return score(laplacianVariance(crop, cw, ch))
    }

    /**
     * The subject box: the bounding union of all face boxes, or with no faces the bounding box of the
     * saliency cells at or above [SUBJECT_SALIENCY] scaled to normalized image coordinates.
     * Null when neither source yields a box.
     */
    fun subjectBox(faces: List<FaceObservation>, saliency: SaliencyMap?): NormRect? {
        if (faces.isNotEmpty()) {
            return NormRect(
                left = faces.minOf { it.box.left }.coerceIn(0f, 1f),
                top = faces.minOf { it.box.top }.coerceIn(0f, 1f),
                right = faces.maxOf { it.box.right }.coerceIn(0f, 1f),
                bottom = faces.maxOf { it.box.bottom }.coerceIn(0f, 1f),
            )
        }
        if (saliency == null) return null
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = -1
        var maxY = -1
        for (y in 0 until saliency.height) {
            for (x in 0 until saliency.width) {
                if (saliency.values[y * saliency.width + x] >= SUBJECT_SALIENCY) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return null
        return NormRect(
            left = minX.toFloat() / saliency.width,
            top = minY.toFloat() / saliency.height,
            right = (maxX + 1).toFloat() / saliency.width,
            bottom = (maxY + 1).toFloat() / saliency.height,
        )
    }

    /** Per-pixel weights for [laplacianVariance]: 1 everywhere, [SUBJECT_WEIGHT] inside [box]. */
    fun subjectWeights(w: Int, h: Int, box: NormRect?): FloatArray {
        require(w > 0 && h > 0)
        val weights = FloatArray(w * h) { 1f }
        val bounds = box?.let { pixelBounds(it, w, h) } ?: return weights
        for (y in bounds[1] until bounds[3]) {
            weights.fill(SUBJECT_WEIGHT, y * w + bounds[0], y * w + bounds[2])
        }
        return weights
    }

    /**
     * `[x0, y0, x1, y1)` pixel bounds covered by [box], or null when it covers no pixel. Edges within
     * [EDGE_TOLERANCE] of a pixel boundary snap to it, so float noise never grows the box by a pixel.
     */
    private fun pixelBounds(box: NormRect, w: Int, h: Int): IntArray? {
        fun low(edge: Float, size: Int) = floor(edge.coerceIn(0f, 1f) * size + EDGE_TOLERANCE).toInt().coerceIn(0, size)
        fun high(edge: Float, size: Int) = ceil(edge.coerceIn(0f, 1f) * size - EDGE_TOLERANCE).toInt().coerceIn(0, size)
        val x0 = low(box.left, w)
        val y0 = low(box.top, h)
        val x1 = high(box.right, w)
        val y1 = high(box.bottom, h)
        if (x1 <= x0 || y1 <= y0) return null
        return intArrayOf(x0, y0, x1, y1)
    }

    private const val EDGE_TOLERANCE = 1e-3f
}
