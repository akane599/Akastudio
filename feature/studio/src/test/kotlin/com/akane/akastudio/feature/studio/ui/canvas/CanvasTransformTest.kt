package com.akane.akastudio.feature.studio.ui.canvas

import com.akane.akastudio.core.color.api.Rect4f
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasTransformTest {

    private val eps = 1e-3f

    @Test
    fun `wide view pillarboxes the canvas and centres it horizontally`() {
        val t = CanvasTransform(viewWidth = 1000f, viewHeight = 960f, canvasWidth = 1080f, canvasHeight = 1920f)
        assertEquals(0.5f, t.scale, eps)
        assertEquals(540f, t.frameWidth, eps)
        assertEquals(960f, t.frameHeight, eps)
        assertEquals(230f, t.frameLeft, eps)
        assertEquals(0f, t.frameTop, eps)
    }

    @Test
    fun `tall view letterboxes the canvas and centres it vertically`() {
        val t = CanvasTransform(viewWidth = 540f, viewHeight = 1200f, canvasWidth = 1080f, canvasHeight = 1920f)
        assertEquals(0.5f, t.scale, eps)
        assertEquals(0f, t.frameLeft, eps)
        assertEquals(120f, t.frameTop, eps)
        assertEquals(960f, t.frameHeight, eps)
    }

    @Test
    fun `view to canvas maps frame corners to canvas corners`() {
        val t = CanvasTransform(1000f, 960f, 1080f, 1920f)
        val topLeft = t.viewToCanvas(230f, 0f)!!
        val bottomRight = t.viewToCanvas(770f, 960f)!!
        assertEquals(0f, topLeft.x, eps)
        assertEquals(0f, topLeft.y, eps)
        assertEquals(1080f, bottomRight.x, eps)
        assertEquals(1920f, bottomRight.y, eps)
    }

    @Test
    fun `inverse round trip returns the original point both ways`() {
        val t = CanvasTransform(733f, 1291f, 1080f, 1920f)
        listOf(0f to 0f, 123.4f to 987.6f, 1080f to 1920f, 540f to 960f).forEach { (x, y) ->
            val view = t.canvasToView(x, y)!!
            val back = t.viewToCanvas(view.x, view.y)!!
            assertEquals(x, back.x, eps)
            assertEquals(y, back.y, eps)
        }
        listOf(10f to 20f, 366.5f to 645.5f).forEach { (x, y) ->
            val canvas = t.viewToCanvas(x, y)!!
            val back = t.canvasToView(canvas.x, canvas.y)!!
            assertEquals(x, back.x, eps)
            assertEquals(y, back.y, eps)
        }
    }

    @Test
    fun `canvas rect maps through the same scale and offset`() {
        val t = CanvasTransform(1000f, 960f, 1080f, 1920f)
        val r = t.canvasToView(Rect4f(100f, 200f, 300f, 600f))!!
        assertEquals(280f, r.left, eps)
        assertEquals(100f, r.top, eps)
        assertEquals(380f, r.right, eps)
        assertEquals(300f, r.bottom, eps)
    }

    @Test
    fun `points in the side bars are outside the canvas`() {
        val t = CanvasTransform(1000f, 960f, 1080f, 1920f)
        assertNull(t.viewToCanvasInside(100f, 480f))
        assertTrue(t.viewToCanvasInside(500f, 480f) != null)
    }

    @Test
    fun `degenerate sizes map nothing`() {
        listOf(
            CanvasTransform(0f, 100f, 1080f, 1920f),
            CanvasTransform(100f, 100f, 0f, 1920f),
            CanvasTransform(Float.NaN, 100f, 1080f, 1920f),
        ).forEach { t ->
            assertTrue(t.isEmpty)
            assertNull(t.frame)
            assertNull(t.viewToCanvas(1f, 1f))
            assertNull(t.canvasToView(1f, 1f))
        }
    }
}
