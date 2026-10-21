package com.akane.akastudio.feature.studio.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.akane.akastudio.core.color.api.Rect4f

/**
 * Aspect-fit mapping between a view of [viewWidth] x [viewHeight] px and a story canvas of
 * [canvasWidth] x [canvasHeight] px.
 *
 * The canvas is scaled uniformly to the largest size that fits the view and centred, so a view wider than the canvas
 * aspect gets side bars and a taller one gets top/bottom bars. A degenerate size on either side (zero, negative or not
 * finite) yields [isEmpty]; every mapping then returns null.
 */
internal data class CanvasTransform(
    val viewWidth: Float,
    val viewHeight: Float,
    val canvasWidth: Float,
    val canvasHeight: Float,
) {
    /** View pixels per canvas pixel; 0 when [isEmpty]. */
    val scale: Float = if (valid(viewWidth) && valid(viewHeight) && valid(canvasWidth) && valid(canvasHeight)) {
        minOf(viewWidth / canvasWidth, viewHeight / canvasHeight)
    } else {
        0f
    }

    /** True when either size is degenerate and nothing can be mapped. */
    val isEmpty: Boolean get() = scale <= 0f

    /** Width of the fitted canvas frame in view pixels. */
    val frameWidth: Float get() = canvasWidth * scale

    /** Height of the fitted canvas frame in view pixels. */
    val frameHeight: Float get() = canvasHeight * scale

    /** Left edge of the fitted frame in view pixels. */
    val frameLeft: Float get() = if (isEmpty) 0f else (viewWidth - frameWidth) / 2f

    /** Top edge of the fitted frame in view pixels. */
    val frameTop: Float get() = if (isEmpty) 0f else (viewHeight - frameHeight) / 2f

    /** The fitted frame in view pixels, or null when [isEmpty]. */
    val frame: Rect?
        get() = if (isEmpty) null else Rect(frameLeft, frameTop, frameLeft + frameWidth, frameTop + frameHeight)

    /** The canvas point under view point ([x], [y]); points outside the frame map outside the canvas bounds. */
    fun viewToCanvas(x: Float, y: Float): Offset? =
        if (isEmpty) null else Offset((x - frameLeft) / scale, (y - frameTop) / scale)

    /** The view point showing canvas point ([x], [y]). */
    fun canvasToView(x: Float, y: Float): Offset? =
        if (isEmpty) null else Offset(frameLeft + x * scale, frameTop + y * scale)

    /** [rect] in canvas pixels mapped to view pixels. */
    fun canvasToView(rect: Rect4f): Rect? = if (isEmpty) {
        null
    } else {
        Rect(
            left = frameLeft + rect.left * scale,
            top = frameTop + rect.top * scale,
            right = frameLeft + rect.right * scale,
            bottom = frameTop + rect.bottom * scale,
        )
    }

    /** The canvas point under view point ([x], [y]) when it lies inside the canvas, else null. */
    fun viewToCanvasInside(x: Float, y: Float): Offset? = viewToCanvas(x, y)?.takeIf {
        it.x in 0f..canvasWidth && it.y in 0f..canvasHeight
    }

    private companion object {
        fun valid(v: Float): Boolean = v.isFinite() && v > 0f
    }
}
