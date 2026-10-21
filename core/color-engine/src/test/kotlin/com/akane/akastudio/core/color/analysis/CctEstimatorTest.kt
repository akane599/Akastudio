package com.akane.akastudio.core.color.analysis

import com.akane.akastudio.core.color.api.ImagePixels
import com.akane.akastudio.core.color.math.ColorMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.random.Random

class CctEstimatorTest {

    /** Kim et al. (2002) cubic-spline fit of the Planckian locus in CIE 1931 xy, valid 1667–25000 K. */
    private fun planckianXy(kelvin: Double): Pair<Double, Double> {
        val t = kelvin
        val x = if (t <= 4000.0) {
            -0.2661239e9 / (t * t * t) - 0.2343589e6 / (t * t) + 0.8776956e3 / t + 0.179910
        } else {
            -3.0258469e9 / (t * t * t) + 2.1070379e6 / (t * t) + 0.2226347e3 / t + 0.240390
        }
        val y = when {
            t <= 2222.0 -> -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
            t <= 4000.0 -> -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867
            else -> 3.0817580 * x * x * x - 5.87338670 * x * x + 3.75112997 * x - 0.37001483
        }
        return x to y
    }

    /** Linear sRGB (unnormalised) of chromaticity ([x], [y]) at luminance Y = 1, via the sRGB D65 inverse matrix. */
    private fun xyToLinearRgb(x: Double, y: Double): DoubleArray {
        val bigX = x / y
        val bigY = 1.0
        val bigZ = (1.0 - x - y) / y
        return doubleArrayOf(
            3.2404542 * bigX - 1.5371385 * bigY - 0.4985314 * bigZ,
            -0.9692660 * bigX + 1.8760108 * bigY + 0.0415560 * bigZ,
            0.0556434 * bigX - 0.2040259 * bigY + 1.0572252 * bigZ,
        )
    }

    /** Linear sRGB of the Planckian white at [kelvin], scaled so its largest channel is 1. */
    private fun planckianLinearRgb(kelvin: Double): DoubleArray {
        val (x, y) = planckianXy(kelvin)
        val rgb = xyToLinearRgb(x, y)
        val max = rgb.max()
        assertTrue("illuminant at $kelvin K inside the sRGB gamut", rgb.all { it > 0.0 })
        return DoubleArray(3) { rgb[it] / max }
    }

    /** Achromatic surfaces of random lightness with ±5 % per-channel reflectance noise, lit by [kelvin]. */
    private fun greyWorldImage(kelvin: Double, seed: Int = 17): ImagePixels {
        val light = planckianLinearRgb(kelvin)
        val random = Random(seed)
        val width = 256
        val height = 256
        val argb = IntArray(width * height) {
            val reflectance = 0.05 + 0.85 * random.nextDouble()
            var packed = 0xFF shl 24
            for (c in 0 until 3) {
                val noise = 0.95 + 0.10 * random.nextDouble()
                val linear = (reflectance * noise * light[c]).toFloat()
                val code = (ColorMath.linearToSrgb(linear) * 255f).roundToInt().coerceIn(0, 255)
                packed = packed or (code shl (16 - 8 * c))
            }
            packed
        }
        return ImagePixels(width, height, argb)
    }

    @Test
    fun planckian3000K_isEstimatedWithin250K() {
        val cct = ColorAnalysis.estimateCctKelvin(greyWorldImage(3000.0))
        assertEquals(3000f, cct, 250f)
    }

    @Test
    fun planckian6500K_isEstimatedWithin250K() {
        val cct = ColorAnalysis.estimateCctKelvin(greyWorldImage(6500.0))
        assertEquals(6500f, cct, 250f)
    }

    @Test
    fun estimates_areMonotoneInTemperature() {
        val estimates = listOf(2700.0, 4000.0, 5500.0, 7500.0).map { ColorAnalysis.estimateCctKelvin(greyWorldImage(it)) }
        for (i in 1 until estimates.size) {
            assertTrue("estimates $estimates must increase", estimates[i] > estimates[i - 1])
        }
    }

    @Test
    fun blackImage_returnsD65Default() {
        val black = ImagePixels(32, 32, IntArray(32 * 32) { 0xFF000000.toInt() })
        assertEquals(6504f, ColorAnalysis.estimateCctKelvin(black), 0f)
    }

    @Test
    fun fullyClippedImage_returnsD65Default() {
        val white = ImagePixels(32, 32, IntArray(32 * 32) { 0xFFFFFFFF.toInt() })
        assertEquals(6504f, ColorAnalysis.estimateCctKelvin(white), 0f)
    }

    @Test
    fun neutralGrey_readsAsD65() {
        val grey = ImagePixels(16, 16, IntArray(16 * 16) { 0xFF808080.toInt() })
        assertEquals(6504f, ColorAnalysis.estimateCctKelvin(grey), 5f)
    }

    @Test
    fun mcCamy_isClampedToValidRange() {
        // xy (0.5, 0.317) sits near McCamy's cubic minimum (n = -1.28, about 1620 K): clamped up.
        val low = xyToLinearRgb(0.5, 0.317)
        assertEquals(1667f, CctEstimator.cctFromLinearSrgb(low[0], low[1], low[2]), 0f)
        // xy (0.24, 0.24): n = 1.70, about 29400 K: clamped down.
        val high = xyToLinearRgb(0.24, 0.24)
        assertEquals(25_000f, CctEstimator.cctFromLinearSrgb(high[0], high[1], high[2]), 0f)
        assertEquals(6504f, CctEstimator.cctFromLinearSrgb(0.0, 0.0, 0.0), 0f)
    }
}
