package com.akane.akastudio.feature.studio.ui.canvas.scope

import kotlin.math.ln
import kotlin.math.roundToInt

/** Pure conversion of scope data into drawable values: density rasters and normalized histogram heights. */
internal object ScopeRaster {
    private const val OPAQUE = 255
    private const val ALPHA_SHIFT = 24
    private const val RGB_MASK = 0xFFFFFF

    /**
     * [map] as unpremultiplied ARGB pixels, [DensityMap.columns] wide and [DensityMap.levels] tall with the top level
     * in the first row. Every pixel has [rgb]'s colour; its alpha is the log-scaled density
     * `ln(1 + w) / ln(1 + maxW)`, so a single sample stays visible next to a dense trace.
     *
     * `w` is the count reweighted to the fullest column (`count * maxColumnTotal / columnTotal`): when the image width
     * is not a multiple of the column count, buckets hold different pixel counts, and unweighted they would show as
     * vertical stripes. Empty cells are fully transparent.
     */
    fun density(map: DensityMap, rgb: Int): IntArray {
        val out = IntArray(map.columns * map.levels)
        val totals = IntArray(map.columns) { map.columnTotal(it) }
        val maxTotal = totals.maxOrNull() ?: 0
        if (maxTotal == 0) return out
        val weights = DoubleArray(map.columns) { if (totals[it] > 0) maxTotal.toDouble() / totals[it] else 0.0 }
        var maxWeighted = 0.0
        for (level in 0 until map.levels) {
            for (column in 0 until map.columns) maxWeighted = maxOf(maxWeighted, map[column, level] * weights[column])
        }
        val norm = ln(1.0 + maxWeighted)
        val colour = rgb and RGB_MASK
        for (level in 0 until map.levels) {
            val row = (map.levels - 1 - level) * map.columns
            for (column in 0 until map.columns) {
                val count = map[column, level]
                if (count == 0) continue
                val alpha = (ln(1.0 + count * weights[column]) / norm * OPAQUE).roundToInt().coerceIn(1, OPAQUE)
                out[row + column] = (alpha shl ALPHA_SHIFT) or colour
            }
        }
        return out
    }

    /**
     * [bins] scaled to `[0, 1]` against the largest interior bin (every bin but the first and last), so clipped
     * shadows or highlights piling into the end bins saturate instead of flattening the rest. Falls back to the overall
     * maximum when the interior is empty; an all-zero input stays zero.
     */
    fun histogramHeights(bins: IntArray): FloatArray {
        val interior = if (bins.size > 2) (1 until bins.size - 1).maxOf { bins[it] } else 0
        val reference = if (interior > 0) interior else bins.maxOrNull() ?: 0
        if (reference == 0) return FloatArray(bins.size)
        return FloatArray(bins.size) { (bins[it].toFloat() / reference).coerceAtMost(1f) }
    }
}
