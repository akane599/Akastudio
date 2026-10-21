package com.akane.akastudio.core.composer.crop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class CropFrameTest {

    /**
     * True when every corner of a centred [cw]×[ch] axis-aligned rectangle lies inside the [w]×[h] rectangle rotated
     * by [degrees] about the shared centre (checked in the rotated rectangle's own frame).
     */
    private fun cornersInsideRotated(cw: Double, ch: Double, w: Double, h: Double, degrees: Float, tol: Double): Boolean {
        val t = Math.toRadians(degrees.toDouble())
        for (sx in listOf(-1.0, 1.0)) for (sy in listOf(-1.0, 1.0)) {
            val x = sx * cw / 2
            val y = sy * ch / 2
            val u = cos(t) * x + sin(t) * y
            val v = -sin(t) * x + cos(t) * y
            if (abs(u) > w / 2 + tol || abs(v) > h / 2 + tol) return false
        }
        return true
    }

    @Test
    fun inscribedRectangle_cornersStayInsideTheRotatedImage_andItIsMaximal() {
        val sizes = listOf(4000.0 to 3000.0, 3000.0 to 4000.0, 1080.0 to 1920.0, 6000.0 to 1500.0)
        val aspects = listOf(9.0 / 16.0, 0.75, 1.0, 4.0 / 3.0, 2.35)
        val angles = listOf(-5f, -3.2f, -0.5f, 0f, 0.7f, 2f, 5f, 12f)
        for ((w, h) in sizes) for (aspect in aspects) for (deg in angles) {
            val (cw, ch) = CropFrame.inscribedSize(w, h, aspect, deg)
            val label = "${w}x$h aspect=$aspect deg=$deg -> ${cw}x$ch"
            assertEquals(label, aspect, cw / ch, 1e-9)
            assertTrue(label, cornersInsideRotated(cw, ch, w, h, deg, tol = 1e-6))
            assertFalse("$label is not maximal", cornersInsideRotated(cw * 1.001, ch * 1.001, w, h, deg, tol = 0.0))
        }
    }

    @Test
    fun inscribedRectangle_matchesKnownValues() {
        // No rotation: the aspect-fit rectangle.
        val (w0, h0) = CropFrame.inscribedSize(1600.0, 900.0, 1.0, 0f)
        assertEquals(900.0, w0, 1e-9)
        assertEquals(900.0, h0, 1e-9)
        // A square inside a square rotated by θ has side s / (cos θ + sin θ).
        val t = Math.toRadians(5.0)
        val (ws, hs) = CropFrame.inscribedSize(1000.0, 1000.0, 1.0, 5f)
        assertEquals(1000.0 / (cos(t) + sin(t)), ws, 1e-9)
        assertEquals(ws, hs, 1e-9)
    }

    @Test
    fun centreRange_collapsesToTheImageCentreWhenTheCropCannotMove() {
        val frame = CropFrame(1000.0, 1000.0, 0f)
        assertEquals(500.0..500.0, frame.centreRangeX(1000.0, 1000.0))
        assertEquals(250.0..750.0, frame.centreRangeX(500.0, 500.0))
        assertEquals(1.0, frame.fitFactor(500.0, 500.0), 0.0)
        assertEquals(0.5, frame.fitFactor(2000.0, 500.0), 1e-12)
    }

    @Test
    fun nonFiniteRotation_isTreatedAsLevel() {
        val frame = CropFrame(1600.0, 900.0, Float.NaN)
        assertEquals(900.0, frame.maxCropHeight(1.0), 1e-9)
    }
}
