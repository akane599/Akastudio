package com.akane.akastudio.core.composer.crop

import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.composer.api.CellOverride
import kotlin.math.max
import kotlin.math.min

/**
 * Applies a user [CellOverride] to a solved crop, always returning a crop that is valid for the rotated source.
 *
 * A non-null, non-degenerate [CellOverride.crop] wins: its edges are sorted and clamped to `[0, 1]`, it is shrunk
 * about its centre when it does not fit the rotated source, then moved into the valid region. Otherwise
 * [CellOverride.zoom] (at least 1) shrinks the solved crop about its centre and [CellOverride.panX] /
 * [CellOverride.panY] in `[-1, 1]` move it by that fraction of the slack between the solved centre and the
 * valid-region edge on the pan side, before a final clamp.
 */
internal object CropOverrides {

    fun apply(
        widthPx: Int,
        heightPx: Int,
        solved: NormRect,
        override: CellOverride?,
        rotationDegrees: Float,
    ): NormRect {
        if (override == null) return solved
        val frame = CropFrame(widthPx.toDouble(), heightPx.toDouble(), rotationDegrees)
        explicitCrop(frame, override.crop)?.let { return it }
        return zoomAndPan(frame, solved, override)
    }

    private fun explicitCrop(frame: CropFrame, crop: NormRect?): NormRect? {
        if (crop == null) return null
        val edges = floatArrayOf(crop.left, crop.top, crop.right, crop.bottom)
        if (edges.any { !it.isFinite() }) return null
        val left = min(crop.left, crop.right).coerceIn(0f, 1f) * frame.width
        val right = max(crop.left, crop.right).coerceIn(0f, 1f) * frame.width
        val top = min(crop.top, crop.bottom).coerceIn(0f, 1f) * frame.height
        val bottom = max(crop.top, crop.bottom).coerceIn(0f, 1f) * frame.height
        if (right - left < MIN_CROP_PX || bottom - top < MIN_CROP_PX) return null
        return fitted(frame, (left + right) / 2, (top + bottom) / 2, right - left, bottom - top)
    }

    private fun zoomAndPan(frame: CropFrame, solved: NormRect, override: CellOverride): NormRect {
        val zoom = override.zoom.takeIf { it.isFinite() }?.coerceAtLeast(1f)?.toDouble() ?: 1.0
        val cw = solved.width * frame.width / zoom
        val ch = solved.height * frame.height / zoom
        val factor = frame.fitFactor(cw, ch)
        val w = cw * factor
        val h = ch * factor
        val cx0 = (solved.left + solved.right) / 2.0 * frame.width
        val cy0 = (solved.top + solved.bottom) / 2.0 * frame.height
        val cx = panned(cx0, override.panX, frame.centreRangeX(w, h))
        val cy = panned(cy0, override.panY, frame.centreRangeY(w, h))
        return frame.toNorm(cx, cy, w, h)
    }

    private fun panned(centre: Double, pan: Float, range: ClosedFloatingPointRange<Double>): Double {
        val c = centre.coerceIn(range)
        val p = pan.takeIf { it.isFinite() }?.coerceIn(-1f, 1f)?.toDouble() ?: 0.0
        val moved = if (p >= 0.0) c + p * (range.endInclusive - c) else c + p * (c - range.start)
        return moved.coerceIn(range)
    }

    private fun fitted(frame: CropFrame, cx: Double, cy: Double, cw: Double, ch: Double): NormRect {
        val factor = frame.fitFactor(cw, ch)
        val w = cw * factor
        val h = ch * factor
        return frame.toNorm(cx.coerceIn(frame.centreRangeX(w, h)), cy.coerceIn(frame.centreRangeY(w, h)), w, h)
    }

    /** Explicit crops narrower or shorter than this many source pixels are treated as absent. */
    private const val MIN_CROP_PX = 1.0
}
