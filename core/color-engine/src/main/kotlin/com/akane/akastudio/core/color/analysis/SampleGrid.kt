package com.akane.akastudio.core.color.analysis

import kotlin.math.min
import kotlin.math.sqrt

/** Deterministic stride grid that reads at most a fixed number of pixels from a raster. */
internal object SampleGrid {

    /**
     * Calls [action] with the flat row-major index of every pixel on a stride grid over a
     * [width] x [height] raster. The grid holds at most [maxSamples] cells: column and row counts are
     * chosen so that `cols * rows <= maxSamples` with the cells as square as the aspect ratio allows,
     * and each stride is the ceiling of the extent over that count. Each cell is read at its centre.
     */
    inline fun forEachIndex(width: Int, height: Int, maxSamples: Int, action: (Int) -> Unit) {
        require(width > 0 && height > 0) { "raster must be non-empty, was ${width}x$height" }
        require(maxSamples >= 1) { "maxSamples must be >= 1, was $maxSamples" }
        val stepX: Int
        val stepY: Int
        if (width.toLong() * height <= maxSamples) {
            stepX = 1
            stepY = 1
        } else {
            val cols = sqrt(maxSamples.toDouble() * width / height).toInt().coerceIn(1, min(width, maxSamples))
            val rows = (maxSamples / cols).coerceIn(1, height)
            stepX = (width + cols - 1) / cols
            stepY = (height + rows - 1) / rows
        }
        var y = (stepY - 1) / 2
        while (y < height) {
            val rowStart = y * width
            var x = (stepX - 1) / 2
            while (x < width) {
                action(rowStart + x)
                x += stepX
            }
            y += stepY
        }
    }
}
