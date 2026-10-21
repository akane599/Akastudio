package com.akane.akastudio.core.ingestion.decode

/** Output size for a bounded full-resolution decode. */
object FullResSizing {
    /**
     * Size whose long edge is at most [maxLongEdge], keeping the aspect ratio of [width] x [height]. An image that
     * already fits is returned unchanged (never upscaled); otherwise each side is rounded to the nearest pixel
     * (half up) and is at least 1.
     *
     * @throws IllegalArgumentException when any argument is not positive.
     */
    fun targetSize(width: Int, height: Int, maxLongEdge: Int): Pair<Int, Int> {
        require(width > 0 && height > 0) { "image size must be positive: ${width}x$height" }
        require(maxLongEdge > 0) { "maxLongEdge must be positive: $maxLongEdge" }
        if (maxOf(width, height) <= maxLongEdge) return width to height
        return ScaleMath.fitLongEdge(width, height, maxLongEdge)
    }
}
