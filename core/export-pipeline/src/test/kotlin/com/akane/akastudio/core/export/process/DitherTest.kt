package com.akane.akastudio.core.export.process

import com.akane.akastudio.core.color.api.HalfFloatImage
import com.akane.akastudio.core.color.math.ColorMath
import com.akane.akastudio.core.export.encode.PngEncoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
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

    // ---- Straight alpha at the alpha-preserving boundary (AUD-08) ------------------------------------------------

    /** The auditor's fixture: half-transparent white under frosted gain 0.75, premultiplied linear (0.375 x3, 0.5). */
    private val frostedHalfWhite = floatArrayOf(0.375f, 0.375f, 0.375f, 0.5f)

    private fun code16(v: Short): Int = v.toInt() and 0xFFFF

    private fun encoded(linear: Float, max: Int): Float = ColorMath.linearToSrgb(linear.coerceIn(0f, 1f)) * max

    @Test
    fun sixteenBitStoresStraightColourForFractionalAlpha() {
        val out = ShortArray(4)
        Dither.toSrgb16Row(halfImage(1, 1) { _, _ -> frostedHalfWhite }, 0, out)
        // Straight colour 0.375 / 0.5 = 0.75 encodes to 57725; encoding the premultiplied 0.375 would give 42341.
        assertArrayEquals(intArrayOf(57725, 57725, 57725, 32768), IntArray(4) { code16(out[it]) })
    }

    @Test
    fun eightBitStraightStoresStraightColourForFractionalAlpha() {
        val img = halfImage(64, 64) { _, _ -> frostedHalfWhite }
        val out = IntArray(64)
        val straight = encoded(0.75f, 255)              // 224.61; the premultiplied encode would be 164.75
        for (y in 0 until 64) {
            Dither.toStraightSrgb8Row(img, y, out)
            for (x in 0 until 64) {
                assertEquals("alpha ($x,$y)", 128, out[x] ushr 24)
                for (shift in intArrayOf(16, 8, 0)) {
                    val code = (out[x] ushr shift) and 0xFF
                    assertTrue("($x,$y) shift $shift code $code", code == 224 || code == 225)
                }
            }
        }
        // Over-black policy for alpha-dropping formats: the premultiplied colour is encoded as is.
        Dither.toSrgb8Row(img, 0, out)
        for (x in 0 until 64) assertTrue("over black ${out[x] and 0xFF}", (out[x] and 0xFF) in 164..165)
        assertTrue(straight > 224f && straight < 225f)
    }

    /** Stored straight colour x stored alpha, re-encoded, lands within one code of the encoded premultiplied input. */
    @Test
    fun sixteenBitStraightColourRecomposesThePremultipliedInput() {
        val colours = 101
        val img = halfImage(colours, 15) { x, y ->
            val a = (y + 1) / 16f
            val c = x / (colours - 1f)
            floatArrayOf(c * a, (1f - c) * a, 0.5f * c * a, a)
        }
        val out = ShortArray(colours * 4)
        for (y in 0 until img.height) {
            Dither.toSrgb16Row(img, y, out)
            for (x in 0 until colours) {
                val alpha = code16(out[x * 4 + 3]) / 65535f
                for (c in 0 until 3) {
                    val recomposed = ColorMath.srgbToLinear(code16(out[x * 4 + c]) / 65535f) * alpha
                    val err = abs(encoded(recomposed, 65535) - encoded(img.at(x, y, c), 65535))
                    assertTrue("($x,$y,$c) recomposition off by $err codes", err <= 1f)
                }
            }
        }
    }

    /** Same for 8 bits: alphas k/15 are exact 8-bit codes, so dither (< 1 code of straight colour) is the only error. */
    @Test
    fun eightBitStraightColourRecomposesThePremultipliedInput() {
        val colours = 101
        val img = halfImage(colours, 14) { x, y ->
            val a = (y + 1) / 15f
            val c = x / (colours - 1f)
            floatArrayOf(c * a, (1f - c) * a, 0.5f * c * a, a)
        }
        val out = IntArray(colours)
        for (y in 0 until img.height) {
            Dither.toStraightSrgb8Row(img, y, out)
            for (x in 0 until colours) {
                val alpha = (out[x] ushr 24) / 255f
                for ((c, shift) in intArrayOf(16, 8, 0).withIndex()) {
                    val recomposed = ColorMath.srgbToLinear(((out[x] ushr shift) and 0xFF) / 255f) * alpha
                    val err = abs(encoded(recomposed, 255) - encoded(img.at(x, y, c), 255))
                    assertTrue("($x,$y,$c) recomposition off by $err codes", err <= 1f)
                }
            }
        }
    }

    @Test
    fun zeroAlphaStoresZeroColour() {
        // Alpha 0, negative and NaN alpha clamp to 0; 1e-6 and 0.001 round to alpha code 0 in 8 bits (0.001 is code 66
        // in 16 bits, so it keeps a colour there).
        val alphas = floatArrayOf(0f, -1f, Float.NaN, 1e-6f, 0.001f)
        val img = halfImage(alphas.size, 1) { x, _ -> floatArrayOf(0.3f, 0.5f, 0.7f, alphas[x]) }
        val out8 = IntArray(alphas.size)
        Dither.toStraightSrgb8Row(img, 0, out8)
        for (x in alphas.indices) assertEquals("8-bit pixel $x", 0, out8[x])
        val out16 = ShortArray(alphas.size * 4)
        Dither.toSrgb16Row(img, 0, out16)
        for (x in 0 until 4) for (c in 0 until 4) assertEquals("16-bit ($x,$c)", 0, code16(out16[x * 4 + c]))
        assertEquals(66, code16(out16[4 * 4 + 3]))
        assertEquals(65535, code16(out16[4 * 4]))         // 0.3 / 0.001 clamps to 1
    }

    /** Alpha exactly 1 keeps the table path: every half colour pattern encodes as before. */
    @Test
    fun opaquePixelsAreUnchangedByTheStraightPath() {
        val patterns = 1 shl 16
        val width = 256
        val height = patterns / width
        val data = ShortArray(patterns * 4)
        for (i in 0 until patterns) {
            data[i * 4] = i.toShort()
            data[i * 4 + 1] = (i xor 0x5555).toShort()
            data[i * 4 + 2] = (patterns - 1 - i).toShort()
            data[i * 4 + 3] = Half.fromFloat(1f)
        }
        val img = HalfFloatImage(width, height, data)
        val out16 = ShortArray(width * 4)
        val straight8 = IntArray(width)
        val overBlack8 = IntArray(width)
        for (y in 0 until height) {
            Dither.toSrgb16Row(img, y, out16)
            Dither.toStraightSrgb8Row(img, y, straight8)
            Dither.toSrgb8Row(img, y, overBlack8)
            assertArrayEquals("8-bit row $y", overBlack8, straight8)
            for (x in 0 until width) {
                for (c in 0 until 3) {
                    val v = Half.toFloat(data[(y * width + x) * 4 + c])
                    val linear = if (v.isNaN()) 0f else v.coerceIn(0f, 1f)
                    val expected = (ColorMath.linearToSrgb(linear) * 65535f + 0.5f).toInt()
                    assertEquals("16-bit ($x,$y,$c)", expected, code16(out16[x * 4 + c]))
                }
                assertEquals(65535, code16(out16[x * 4 + 3]))
            }
        }
    }

    @Test
    fun png16RoundTripStoresStraightAlphaSamples() {
        val img = halfImage(3, 1) { x, _ ->
            when (x) {
                0 -> frostedHalfWhite
                1 -> floatArrayOf(0.2f, 0.1f, 0.05f, 1f)
                else -> floatArrayOf(0.9f, 0.9f, 0.9f, 0f)
            }
        }
        val png = ByteArrayOutputStream().also { out ->
            PngEncoder.write16(out, img.width, img.height, { y, row -> Dither.toSrgb16Row(img, y, row) }, exif = null)
        }.toByteArray()
        val decoded = ImageIO.read(ByteArrayInputStream(png))
        assertTrue(decoded.colorModel.hasAlpha())
        assertFalse("PNG alpha must decode as straight", decoded.colorModel.isAlphaPremultiplied)
        val raster = decoded.raster
        assertEquals(16, raster.sampleModel.getSampleSize(0))
        fun samples(x: Int) = IntArray(4) { raster.getSample(x, 0, it) }
        assertArrayEquals(intArrayOf(57725, 57725, 57725, 32768), samples(0))
        val opaque = IntArray(3) { (ColorMath.linearToSrgb(img.at(1, 0, it)) * 65535f + 0.5f).toInt() }
        assertArrayEquals(opaque + 65535, samples(1))
        assertArrayEquals(intArrayOf(0, 0, 0, 0), samples(2))
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
