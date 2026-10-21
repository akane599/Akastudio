package com.akane.akastudio.feature.studio.ui.canvas

import com.akane.akastudio.core.composer.api.CellOverride
import kotlin.math.max

/**
 * Pure math for the two-finger cell gesture: maps a pinch factor and a pan in canvas pixels onto a [CellOverride]
 * (`zoom` in `[MIN_ZOOM, MAX_ZOOM]`, `panX`/`panY` in `[-PAN_LIMIT, PAN_LIMIT]`).
 *
 * The composer zooms about the solved crop's centre and treats pan as a fraction of the slack between that centre and
 * the edge of the valid source region. The slack depends on the source, which the canvas cannot see, so the pan gain
 * assumes the zoom-only slack `(1 - 1/zoom) / 2` of the crop per side: moving the fingers by one cell width at zoom
 * `z` moves the pan by `2 / (z - 1)`, with `z - 1` floored at [MIN_SLACK_ZOOM] so a pan at zoom 1 (where any slack
 * comes from the cell aspect) still moves the image at a usable rate.
 */
internal object CellGestureMath {
    /** The smallest zoom; 1 shows the solved crop. */
    const val MIN_ZOOM = 1f

    /** The largest zoom. */
    const val MAX_ZOOM = 4f

    /** Pan magnitude limit on each axis. */
    const val PAN_LIMIT = 1f

    private const val PAN_GAIN = 2f
    private const val MIN_SLACK_ZOOM = 1f

    /** [zoom] clamped to `[MIN_ZOOM, MAX_ZOOM]`; a non-finite value resets to [MIN_ZOOM]. */
    fun clampZoom(zoom: Float): Float = if (zoom.isFinite()) zoom.coerceIn(MIN_ZOOM, MAX_ZOOM) else MIN_ZOOM

    /** [pan] clamped to `[-PAN_LIMIT, PAN_LIMIT]`; a non-finite value resets to 0. */
    fun clampPan(pan: Float): Float = if (pan.isFinite()) pan.coerceIn(-PAN_LIMIT, PAN_LIMIT) else 0f

    /**
     * The pan change for a finger move of [dragPx] across a cell [cellPx] long on that axis at [zoom]. Moving the
     * fingers right or down moves the image the same way, which moves the crop (and the pan) the opposite way.
     */
    fun panDelta(dragPx: Float, cellPx: Float, zoom: Float): Float {
        if (!dragPx.isFinite() || !cellPx.isFinite() || cellPx <= 0f) return 0f
        val slack = max(clampZoom(zoom) - 1f, MIN_SLACK_ZOOM)
        return -PAN_GAIN * dragPx / cellPx / slack
    }

    /**
     * [base] after a pinch by [zoomFactor] and a pan by ([dxPx], [dyPx]) canvas pixels over a cell of
     * [cellWidthPx] x [cellHeightPx]. An explicit crop in [base] is dropped, since the composer ignores zoom and pan
     * while one is set. A non-finite or non-positive [zoomFactor] leaves the zoom unchanged.
     */
    fun transform(
        base: CellOverride,
        zoomFactor: Float,
        dxPx: Float,
        dyPx: Float,
        cellWidthPx: Float,
        cellHeightPx: Float,
    ): CellOverride {
        val factor = if (zoomFactor.isFinite() && zoomFactor > 0f) zoomFactor else 1f
        val zoom = clampZoom(clampZoom(base.zoom) * factor)
        return CellOverride(
            crop = null,
            zoom = zoom,
            panX = clampPan(clampPan(base.panX) + panDelta(dxPx, cellWidthPx, zoom)),
            panY = clampPan(clampPan(base.panY) + panDelta(dyPx, cellHeightPx, zoom)),
        )
    }
}

/**
 * Lets at most one emission through per [intervalNanos], measured on [clock] (nanoseconds, monotonic).
 * Callers flush the last value themselves when the gesture ends.
 */
internal class FrameThrottle(
    private val intervalNanos: Long,
    private val clock: () -> Long,
) {
    init {
        require(intervalNanos > 0) { "intervalNanos must be positive, was $intervalNanos" }
    }

    private var lastNanos: Long? = null

    /** True (and starts a new interval) when the previous emission is at least one interval old. */
    fun tryAcquire(): Boolean {
        val now = clock()
        val last = lastNanos
        if (last != null && now - last < intervalNanos) return false
        lastNanos = now
        return true
    }

    companion object {
        /** Nanoseconds per frame at [refreshRateHz]; a non-positive or non-finite rate falls back to 60 Hz. */
        fun frameIntervalNanos(refreshRateHz: Float): Long {
            val hz = if (refreshRateHz.isFinite() && refreshRateHz > 0f) refreshRateHz else FALLBACK_HZ
            return (NANOS_PER_SECOND / hz).toLong().coerceAtLeast(1L)
        }

        private const val FALLBACK_HZ = 60f
        private const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}

/**
 * One two-finger gesture on the cell of [photoId]: accumulates pinch and pan onto [base] and decides, through
 * [throttle], which intermediate overrides to emit. [finish] returns the final override if it was not emitted yet.
 */
internal class CellPinchTracker(
    val photoId: String,
    base: CellOverride,
    private val cellWidthPx: Float,
    private val cellHeightPx: Float,
    private val throttle: FrameThrottle,
) {
    /** The accumulated override. */
    var current: CellOverride = base
        private set

    private var emitted: CellOverride = base

    /** Applies one pointer event's [zoomFactor] and pan; returns the override to emit now, or null to wait. */
    fun update(zoomFactor: Float, dxPx: Float, dyPx: Float): CellOverride? {
        current = CellGestureMath.transform(current, zoomFactor, dxPx, dyPx, cellWidthPx, cellHeightPx)
        if (current == emitted || !throttle.tryAcquire()) return null
        emitted = current
        return current
    }

    /** The final override when it differs from the last emitted one, else null. */
    fun finish(): CellOverride? {
        if (current == emitted) return null
        emitted = current
        return current
    }
}
