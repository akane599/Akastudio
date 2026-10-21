package com.akane.akastudio.core.composer.crop

import com.akane.akastudio.core.color.api.NormRect
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Valid-crop geometry of a `width`×`height` upright source (pixels, y down) that the renderer rotates by
 * [rotationDegrees] about the crop centre.
 *
 * Rotation sign: positive degrees turn the source clockwise on screen (Android `Canvas.rotate` convention), so a
 * source point at offset `q` from the crop centre lands at `R·q` with `R = [[cos, -sin], [sin, cos]]` in y-down
 * coordinates. A crop of size `cw×ch` is valid when that rotated rectangle stays inside the source, which holds
 * exactly when its bounding box does; the unrotated crop must also stay inside so its [NormRect] is in `[0, 1]`.
 */
internal class CropFrame(val width: Double, val height: Double, rotationDegrees: Float) {
    private val radians = Math.toRadians(if (rotationDegrees.isFinite()) rotationDegrees.toDouble() else 0.0)
    val cos: Double = cos(radians)
    val sin: Double = sin(radians)
    private val absCos = abs(cos)
    private val absSin = abs(sin)

    init {
        require(width > 0.0 && height > 0.0) { "source must have a positive size, got ${width}x$height" }
    }

    /**
     * Height of the largest crop of [aspect] (width/height) that stays inside the source when rotated: the closed
     * form of the largest axis-aligned rectangle of that aspect inscribed in the source rotated by the frame angle.
     */
    fun maxCropHeight(aspect: Double): Double = minOf(
        width / (aspect * absCos + absSin),
        height / (aspect * absSin + absCos),
        width / aspect,
        height,
    )

    /** Half extents of the source footprint of a `cw×ch` crop: the larger of its rotated bounding box and itself. */
    fun halfExtentX(cw: Double, ch: Double): Double = max(cw, cw * absCos + ch * absSin) / 2
    fun halfExtentY(cw: Double, ch: Double): Double = max(ch, cw * absSin + ch * absCos) / 2

    /** Valid crop-centre interval along x for a `cw×ch` crop; collapses to the image centre when none exists. */
    fun centreRangeX(cw: Double, ch: Double): ClosedFloatingPointRange<Double> = range(halfExtentX(cw, ch), width)
    fun centreRangeY(cw: Double, ch: Double): ClosedFloatingPointRange<Double> = range(halfExtentY(cw, ch), height)

    /** Largest factor in `(0, 1]` that makes a `cw×ch` crop (scaled about its centre) fit the source. */
    fun fitFactor(cw: Double, ch: Double): Double =
        minOf(1.0, (width / 2) / halfExtentX(cw, ch), (height / 2) / halfExtentY(cw, ch))

    /** The crop centred at ([cx], [cy]) with size [cw]×[ch], normalized to the source and clamped to `[0, 1]`. */
    fun toNorm(cx: Double, cy: Double, cw: Double, ch: Double): NormRect = NormRect(
        ((cx - cw / 2) / width).toFloat().coerceIn(0f, 1f),
        ((cy - ch / 2) / height).toFloat().coerceIn(0f, 1f),
        ((cx + cw / 2) / width).toFloat().coerceIn(0f, 1f),
        ((cy + ch / 2) / height).toFloat().coerceIn(0f, 1f),
    )

    private fun range(halfExtent: Double, size: Double): ClosedFloatingPointRange<Double> {
        val lo = halfExtent
        val hi = size - halfExtent
        return if (lo <= hi) lo..hi else (size / 2)..(size / 2)
    }

    companion object {
        /** `(width, height)` of the largest crop of [aspect] inside a [width]×[height] source rotated by [degrees]. */
        fun inscribedSize(width: Double, height: Double, aspect: Double, degrees: Float): Pair<Double, Double> {
            val h = CropFrame(width, height, degrees).maxCropHeight(aspect)
            return aspect * h to h
        }
    }
}
