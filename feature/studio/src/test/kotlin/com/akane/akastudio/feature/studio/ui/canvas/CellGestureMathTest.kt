package com.akane.akastudio.feature.studio.ui.canvas

import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.composer.api.CellOverride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CellGestureMathTest {

    private val eps = 1e-5f

    @Test
    fun `zoom clamps to one through four and resets when not finite`() {
        assertEquals(1f, CellGestureMath.clampZoom(0.2f), eps)
        assertEquals(2.5f, CellGestureMath.clampZoom(2.5f), eps)
        assertEquals(4f, CellGestureMath.clampZoom(9f), eps)
        assertEquals(1f, CellGestureMath.clampZoom(Float.NaN), eps)
        assertEquals(1f, CellGestureMath.clampZoom(Float.POSITIVE_INFINITY), eps)
    }

    @Test
    fun `pan clamps to minus one through one and resets when not finite`() {
        assertEquals(-1f, CellGestureMath.clampPan(-3f), eps)
        assertEquals(0.4f, CellGestureMath.clampPan(0.4f), eps)
        assertEquals(1f, CellGestureMath.clampPan(1.7f), eps)
        assertEquals(0f, CellGestureMath.clampPan(Float.NaN), eps)
    }

    @Test
    fun `dragging right moves the crop left and gain falls with zoom`() {
        val atOne = CellGestureMath.panDelta(dragPx = 100f, cellPx = 1000f, zoom = 1f)
        val atThree = CellGestureMath.panDelta(dragPx = 100f, cellPx = 1000f, zoom = 3f)
        assertEquals(-0.2f, atOne, eps)
        assertEquals(-0.1f, atThree, eps)
        assertEquals(0f, CellGestureMath.panDelta(100f, 0f, 2f), eps)
    }

    @Test
    fun `transform multiplies zoom, accumulates pan, clamps and drops an explicit crop`() {
        val base = CellOverride(crop = NormRect(0.1f, 0.1f, 0.9f, 0.9f), zoom = 2f, panX = 0.9f, panY = 0f)
        val out = CellGestureMath.transform(base, zoomFactor = 3f, dxPx = -500f, dyPx = 300f, cellWidthPx = 500f, cellHeightPx = 1000f)
        assertNull(out.crop)
        assertEquals(4f, out.zoom, eps)
        assertEquals(1f, out.panX, eps)
        assertEquals(-0.2f, out.panY, eps)
    }

    @Test
    fun `invalid zoom factor leaves zoom unchanged`() {
        val base = CellOverride(zoom = 1.5f)
        assertEquals(1.5f, CellGestureMath.transform(base, Float.NaN, 0f, 0f, 100f, 100f).zoom, eps)
        assertEquals(1.5f, CellGestureMath.transform(base, -2f, 0f, 0f, 100f, 100f).zoom, eps)
    }

    @Test
    fun `frame throttle passes one acquisition per interval on the injected clock`() {
        var now = 0L
        val throttle = FrameThrottle(intervalNanos = 8_000_000L) { now }
        assertTrue(throttle.tryAcquire())
        now = 3_000_000L
        assertFalse(throttle.tryAcquire())
        now = 7_999_999L
        assertFalse(throttle.tryAcquire())
        now = 8_000_000L
        assertTrue(throttle.tryAcquire())
        now = 9_000_000L
        assertFalse(throttle.tryAcquire())
        throttle.reset()
        assertTrue(throttle.tryAcquire())
    }

    @Test
    fun `frame interval follows the refresh rate with a 60 Hz fallback`() {
        assertEquals(8_333_333L, FrameThrottle.frameIntervalNanos(120f))
        assertEquals(16_666_666L, FrameThrottle.frameIntervalNanos(0f))
        assertEquals(16_666_666L, FrameThrottle.frameIntervalNanos(Float.NaN))
    }

    @Test
    fun `pinch tracker emits once per frame and flushes the last value on finish`() {
        var now = 0L
        val tracker = CellPinchTracker(
            photoId = "p",
            base = CellOverride(),
            cellWidthPx = 1000f,
            cellHeightPx = 1000f,
            throttle = FrameThrottle(10L) { now },
        )
        val first = tracker.update(zoomFactor = 1.5f, dxPx = 0f, dyPx = 0f)
        assertEquals(1.5f, first!!.zoom, eps)
        now = 4L
        assertNull(tracker.update(zoomFactor = 2f, dxPx = 0f, dyPx = 0f))
        assertEquals(3f, tracker.current.zoom, eps)
        val last = tracker.finish()
        assertEquals(3f, last!!.zoom, eps)
        assertNull(tracker.finish())
    }

    @Test
    fun `pinch tracker emits nothing when the gesture changes nothing`() {
        val tracker = CellPinchTracker("p", CellOverride(zoom = 4f), 100f, 100f, FrameThrottle(1L) { 0L })
        assertNull(tracker.update(zoomFactor = 2f, dxPx = 0f, dyPx = 0f))
        assertNull(tracker.finish())
    }
}
