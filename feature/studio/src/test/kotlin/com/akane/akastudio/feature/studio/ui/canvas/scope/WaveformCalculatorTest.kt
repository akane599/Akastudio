package com.akane.akastudio.feature.studio.ui.canvas.scope

import com.akane.akastudio.core.color.api.ImagePixels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class WaveformCalculatorTest {

    private fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun image(width: Int, height: Int, pixel: (x: Int, y: Int) -> Int) =
        ImagePixels(width, height, IntArray(width * height) { pixel(it % width, it / width) })

    @Test
    fun `uniform grey puts each column's whole mass in one level`() {
        val pixels = image(256, 40) { _, _ -> argb(128, 128, 128) }
        val map = WaveformCalculator.luma(pixels, columns = 128, levels = 100)
        val expectedLevel = WaveformCalculator.levelOf(128 / 255f, 100)
        assertEquals(50, expectedLevel)
        for (column in 0 until 128) {
            assertEquals(2 * 40, map[column, expectedLevel])
            assertEquals(2 * 40, map.columnTotal(column))
        }
    }

    @Test
    fun `horizontal gradient traces a diagonal`() {
        val pixels = image(100, 10) { x, _ -> (x * 255f / 99f).roundToInt().let { argb(it, it, it) } }
        val map = WaveformCalculator.luma(pixels, columns = 100, levels = 100)
        var previous = -1
        for (column in 0 until 100) {
            val levels = (0 until 100).filter { map[column, it] > 0 }
            assertEquals("column $column has one level", 1, levels.size)
            val level = levels.single()
            assertEquals(10, map[column, level])
            assertTrue("level rises left to right", level >= previous)
            assertTrue("column $column near the diagonal (level $level)", abs(level - column) <= 1)
            previous = level
        }
        assertEquals(0, (0 until 100).first { map[0, it] > 0 })
        assertEquals(99, (0 until 100).first { map[99, it] > 0 })
    }

    @Test
    fun `luma uses Rec 709 weights on encoded values`() {
        assertEquals(0.2126f, WaveformCalculator.lumaOf(argb(255, 0, 0)), 1e-4f)
        assertEquals(0.7152f, WaveformCalculator.lumaOf(argb(0, 255, 0)), 1e-4f)
        assertEquals(0.0722f, WaveformCalculator.lumaOf(argb(0, 0, 255)), 1e-4f)
        assertEquals(1f, WaveformCalculator.lumaOf(argb(255, 255, 255)), 1e-4f)
    }

    @Test
    fun `rgb parade separates channels`() {
        val pixels = image(64, 8) { _, _ -> argb(255, 0, 128) }
        val parade = WaveformCalculator.rgbParade(pixels, columns = 32, levels = 100)
        val blueLevel = WaveformCalculator.levelOf(128 / 255f, 100)
        for (column in 0 until 32) {
            assertEquals(16, parade.red[column, 99])
            assertEquals(16, parade.green[column, 0])
            assertEquals(16, parade.blue[column, blueLevel])
            assertEquals(16, parade.red.columnTotal(column))
            assertEquals(16, parade.green.columnTotal(column))
            assertEquals(16, parade.blue.columnTotal(column))
        }
    }

    @Test
    fun `histogram channels and luma each sum to the pixel count`() {
        val pixels = image(37, 23) { x, y -> argb((x * 7) % 256, (y * 11) % 256, (x * y) % 256) }
        val h = WaveformCalculator.histogram(pixels)
        val total = 37 * 23
        listOf(h.red, h.green, h.blue, h.luma).forEach { bins ->
            assertEquals(256, bins.size)
            assertEquals(total, bins.sum())
        }
        val white = WaveformCalculator.histogram(image(4, 4) { _, _ -> argb(255, 255, 255) })
        assertEquals(16, white.luma[255])
        assertEquals(16, white.red[255])
    }

    @Test
    fun `columns map evenly when the image is narrower than the bucket count`() {
        val pixels = image(4, 1) { _, _ -> argb(0, 0, 0) }
        val map = WaveformCalculator.luma(pixels, columns = 8, levels = 10)
        assertEquals(4, (0 until 8).sumOf { map.columnTotal(it) })
        assertEquals(listOf(1, 0, 1, 0, 1, 0, 1, 0), (0 until 8).map { map.columnTotal(it) })
    }
}
