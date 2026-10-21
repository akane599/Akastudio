package com.akane.akastudio.core.export.process

import com.akane.akastudio.core.color.math.ColorMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class DitherTest {

    private val width = 600
    private val height = 64

    /** Slow ramp of sRGB-encoded 0.40..0.42 along x, stored as linear half. */
    private val ramp = halfImage(width, height) { x, _ ->
        gray(ColorMath.srgbToLinear(0.40f + 0.02f * x / (width - 1)))
    }

    private fun trueCode(x: Int, y: Int): Float = ColorMath.linearToSrgb(ramp.at(x, y, 0)) * 255f

    private fun ditheredRows(): Array<IntArray> = Array(height) { y -> IntArray(width).also { Dither.toSrgb8Row(ramp, y, it) } }

    @Test
    fun blockMeansTrackTheTrueValue() {
        val rows = ditheredRows()
        for (shift in intArrayOf(16, 8, 0)) {           // R, G, B
            for (by in 0 until height / 16) for (bx in 0 until width / 16) {
                var sum = 0.0
                var truth = 0.0
                for (y in by * 16 until by * 16 + 16) for (x in bx * 16 until bx * 16 + 16) {
                    sum += (rows[y][x] ushr shift) and 0xFF
                    truth += trueCode(x, y)
                }
                val err = abs(sum - truth) / 256.0
                assertTrue("channel shift $shift block ($bx,$by) error $err", err < 0.6)
            }
        }
    }

    /**
     * Near an integer crossing a pixel leaves the code only when its noise falls in the top (or bottom) fraction
     * equal to the distance from the crossing, so run lengths along one row vary with how that row slice of the 2D
     * blue noise spreads its extreme ranks. A typical row (the median over rows and channels) stays below 40 px and
     * no row reaches the 64 px tile period, while plain rounding bands every row for more than 100 px.
     */
    @Test
    fun ditheringBreaksUpBands() {
        val rows = ditheredRows()
        val ditheredRuns = mutableListOf<Int>()
        for (y in 0 until height) {
            for (shift in intArrayOf(16, 8, 0)) {
                ditheredRuns += longestRun(IntArray(width) { (rows[y][it] ushr shift) and 0xFF })
            }
            val rounded = longestRun(IntArray(width) { trueCode(it, y).roundToInt() })
            assertTrue("plain rounding longest run $rounded in row $y", rounded > 100)
        }
        val sorted = ditheredRuns.sorted()
        assertTrue("typical dithered longest run ${sorted[sorted.size / 2]}", sorted[sorted.size / 2] < 40)
        assertTrue("worst dithered longest run ${sorted.last()}", sorted.last() < BlueNoise.SIZE)
    }

    @Test
    fun alphaIsExact() {
        val img = halfImage(256, 2) { x, _ -> floatArrayOf(0.3f, 0.5f, 0.7f, x / 255f) }
        val out = IntArray(256)
        Dither.toSrgb8Row(img, 1, out)
        for (x in 0 until 256) assertEquals("alpha at $x", x, out[x] ushr 24)
        val out16 = ShortArray(256 * 4)
        Dither.toSrgb16Row(img, 0, out16)
        assertEquals(0, out16[3].toInt() and 0xFFFF)
        assertEquals(65535, out16[255 * 4 + 3].toInt() and 0xFFFF)
        assertEquals((img.at(128, 0, 3) * 65535f + 0.5f).toInt(), out16[128 * 4 + 3].toInt() and 0xFFFF)
    }

    @Test
    fun outOfRangeAndNaNClamp() {
        val img = halfImage(4, 1) { x, _ ->
            when (x) {
                0 -> floatArrayOf(-0.5f, 2f, Float.NaN, 1f)
                1 -> floatArrayOf(Float.POSITIVE_INFINITY, 0f, 1f, 1.5f)
                2 -> floatArrayOf(1f, 1f, 1f, -1f)
                else -> floatArrayOf(0f, 0f, 0f, Float.NaN)
            }
        }
        val out = IntArray(4)
        Dither.toSrgb8Row(img, 0, out)
        assertEquals(0xFF00FF00.toInt(), out[0])
        assertEquals(0xFFFF00FF.toInt(), out[1])
        assertEquals(0x00FFFFFF, out[2])
        assertEquals(0x00000000, out[3])
    }

    @Test
    fun sixteenBitRoundsTheEncodedValue() {
        val img = halfImage(64, 1) { x, _ -> floatArrayOf(x / 63f, (x / 63f) * 0.5f, 0.0001f * x, 1f) }
        val out = ShortArray(64 * 4)
        Dither.toSrgb16Row(img, 0, out)
        for (x in 0 until 64) for (c in 0 until 3) {
            val expected = (ColorMath.linearToSrgb(img.at(x, 0, c).coerceIn(0f, 1f)) * 65535f + 0.5f).toInt()
            assertEquals("($x,$c)", expected, out[x * 4 + c].toInt() and 0xFFFF)
        }
    }

    private fun longestRun(codes: IntArray): Int {
        var best = 1
        var run = 1
        for (i in 1 until codes.size) {
            run = if (codes[i] == codes[i - 1]) run + 1 else 1
            best = maxOf(best, run)
        }
        return best
    }
}
