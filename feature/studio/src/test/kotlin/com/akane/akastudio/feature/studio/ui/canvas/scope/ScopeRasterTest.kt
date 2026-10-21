package com.akane.akastudio.feature.studio.ui.canvas.scope

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopeRasterTest {

    private fun alpha(argb: Int) = (argb ushr 24) and 0xFF

    @Test
    fun `density raster puts the top level in the first row with log alpha`() {
        val counts = IntArray(2 * 3)
        // Both columns hold 1000 samples. Column 0: all at level 2 (top). Column 1: one at level 0, the rest at level 1.
        counts[2 * 2 + 0] = 1000
        counts[0 * 2 + 1] = 1
        counts[1 * 2 + 1] = 999
        val raster = ScopeRaster.density(DensityMap(columns = 2, levels = 3, counts = counts), 0xD8D6D0)
        assertEquals(255, alpha(raster[0]))
        assertEquals(0xD8D6D0, raster[0] and 0xFFFFFF)
        val single = alpha(raster[2 * 2 + 1])
        assertEquals((kotlin.math.ln(2.0) / kotlin.math.ln(1001.0) * 255).toInt().toDouble(), single.toDouble(), 1.0)
        assertTrue("a lone sample stays visible", single > 0)
        assertEquals(0, raster[1])
        assertEquals(0, raster[2 * 2 + 0])
    }

    @Test
    fun `columns holding fewer pixels are reweighted so a uniform image draws evenly`() {
        // Column 0 got 3 pixels, column 1 got 2 (an image width that does not divide evenly), all at level 0.
        val counts = intArrayOf(3, 2)
        val raster = ScopeRaster.density(DensityMap(columns = 2, levels = 1, counts = counts), 0xFFFFFF)
        assertEquals(255, alpha(raster[0]))
        assertEquals(255, alpha(raster[1]))
    }

    @Test
    fun `empty map is fully transparent`() {
        val raster = ScopeRaster.density(DensityMap(4, 4, IntArray(16)), 0xFFFFFF)
        assertArrayEquals(IntArray(16), raster)
    }

    @Test
    fun `histogram heights normalize to the interior maximum and saturate clipped ends`() {
        val bins = IntArray(256).also {
            it[0] = 5000
            it[100] = 50
            it[200] = 25
        }
        val heights = ScopeRaster.histogramHeights(bins)
        assertEquals(1f, heights[0], 1e-6f)
        assertEquals(1f, heights[100], 1e-6f)
        assertEquals(0.5f, heights[200], 1e-6f)
        assertEquals(0f, heights[50], 1e-6f)
    }

    @Test
    fun `histogram heights fall back to the overall maximum and keep zeros`() {
        val onlyEnds = IntArray(256).also { it[255] = 10 }
        assertEquals(1f, ScopeRaster.histogramHeights(onlyEnds)[255], 1e-6f)
        assertTrue(ScopeRaster.histogramHeights(IntArray(256)).all { it == 0f })
    }
}
