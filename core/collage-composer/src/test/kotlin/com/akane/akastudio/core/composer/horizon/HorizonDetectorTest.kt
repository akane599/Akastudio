package com.akane.akastudio.core.composer.horizon

import com.akane.akastudio.core.color.api.ImagePixels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthetic scenes are drawn in a level "content" frame and shown rotated counter-clockwise on screen by
 * `contentDegrees` (a positive angle raises the right side of a horizon). The detector's documented sign returns
 * that same angle: rotating the image by `+tilt`, clockwise on screen, levels it.
 */
class HorizonDetectorTest {

    /** Renders [width]×[height] gray pixels from a level-content function of `(qx, qy)` (centre origin, y down). */
    private fun render(width: Int, height: Int, contentDegrees: Double, noise: Double, seed: Int, shade: (Double, Double) -> Double): ImagePixels {
        val t = Math.toRadians(contentDegrees)
        val c = cos(t)
        val s = sin(t)
        val random = Random(seed)
        val argb = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val px = x - (width - 1) / 2.0
            val py = y - (height - 1) / 2.0
            // Undo the on-screen counter-clockwise rotation to find the content coordinate.
            val qx = px * c - py * s
            val qy = px * s + py * c
            val v = (shade(qx, qy) + (random.nextDouble() * 2 - 1) * noise).coerceIn(0.0, 1.0)
            val g = (v * 255).roundToInt()
            argb[y * width + x] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
        return ImagePixels(width, height, argb)
    }

    /** Bright sky above a darker sea, anti-aliased over one pixel. */
    private fun seaAndSky(width: Int, height: Int, contentDegrees: Double, seed: Int = 7) =
        render(width, height, contentDegrees, noise = 0.02, seed = seed) { _, qy ->
            val sea = (0.5 + qy).coerceIn(0.0, 1.0)
            0.82 * (1 - sea) + 0.30 * sea
        }

    /** Dark vertical columns (building edges) on a light facade. */
    private fun columns(width: Int, height: Int, contentDegrees: Double) =
        render(width, height, contentDegrees, noise = 0.02, seed = 11) { qx, _ ->
            var cover = 0.0
            for (centre in listOf(-200.0, -70.0, 60.0, 190.0)) {
                val inside = (minOf(qx - (centre - 15) + 0.5, (centre + 15) - qx + 0.5)).coerceIn(0.0, 1.0)
                cover = maxOf(cover, inside)
            }
            0.85 * (1 - cover) + 0.25 * cover
        }

    @Test
    fun seaSkyBoundaryAtPlus3_recoversPlus3() {
        val tilt = HorizonDetector.detectTiltDegrees(seaAndSky(800, 600, 3.0))
        assertEquals(3f, tilt, 0.3f)
    }

    @Test
    fun seaSkyBoundaryAtMinus3_recoversMinus3_documentingTheSign() {
        // Content rotated clockwise on screen (horizon falling to the right) needs a counter-clockwise, negative fix.
        val tilt = HorizonDetector.detectTiltDegrees(seaAndSky(800, 600, -3.0))
        assertEquals(-3f, tilt, 0.3f)
    }

    @Test
    fun portraitSeaSky_recoversTheTilt() {
        val tilt = HorizonDetector.detectTiltDegrees(seaAndSky(600, 900, 1.7))
        assertEquals(1.7f, tilt, 0.3f)
    }

    @Test
    fun verticalsAtMinus2_areRecovered() {
        val tilt = HorizonDetector.detectTiltDegrees(columns(600, 800, -2.0))
        assertEquals(-2f, tilt, 0.3f)
    }

    @Test
    fun noiseOnly_givesZero() {
        val random = Random(99)
        val argb = IntArray(640 * 480) {
            val g = random.nextInt(256)
            (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
        assertEquals(0f, HorizonDetector.detectTiltDegrees(ImagePixels(640, 480, argb)), 0f)
    }

    @Test
    fun tiltOf8Degrees_isOutOfRangeAndGivesZero() {
        assertEquals(0f, HorizonDetector.detectTiltDegrees(seaAndSky(800, 600, 8.0)), 0f)
        assertEquals(0f, HorizonDetector.detectTiltDegrees(seaAndSky(800, 600, -8.0)), 0f)
    }

    @Test
    fun levelHorizon_givesZero() {
        assertEquals(0f, HorizonDetector.detectTiltDegrees(seaAndSky(800, 600, 0.0)), 0f)
        assertEquals(0f, HorizonDetector.detectTiltDegrees(seaAndSky(800, 600, 0.1)), 0f)
    }

    @Test
    fun flatOrTinyImages_giveZero() {
        assertEquals(0f, HorizonDetector.detectTiltDegrees(ImagePixels(300, 200, IntArray(300 * 200) { 0xFF808080.toInt() })), 0f)
        assertEquals(0f, HorizonDetector.detectTiltDegrees(ImagePixels(8, 8, IntArray(64) { it * 1000 })), 0f)
    }

    @Test
    fun largeImages_areDownscaledAndStillRecovered() {
        val tilt = HorizonDetector.detectTiltDegrees(seaAndSky(1600, 1200, 4.2))
        assertEquals(4.2f, tilt, 0.3f)
        assertTrue(kotlin.math.abs(tilt) <= HorizonDetector.MAX_TILT_DEGREES)
    }
}
