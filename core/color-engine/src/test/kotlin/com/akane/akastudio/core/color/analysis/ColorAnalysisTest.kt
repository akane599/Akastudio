package com.akane.akastudio.core.color.analysis

import com.akane.akastudio.core.color.api.ImagePixels
import com.akane.akastudio.core.color.api.OkLab
import com.akane.akastudio.core.color.math.ColorMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

class ColorAnalysisTest {

    private val skinArgb = ColorMath.okLabToArgb(ColorMath.okLchToOkLab(0.7f, 0.07f, 50f))
    private val blueArgb = 0xFF3C5AA0.toInt()

    private fun uniform(width: Int, height: Int, argb: Int) = ImagePixels(width, height, IntArray(width * height) { argb })

    private fun columnsSplit(width: Int, height: Int, splitX: Int, left: Int, right: Int) =
        ImagePixels(width, height, IntArray(width * height) { if (it % width < splitX) left else right })

    private fun noise(width: Int, height: Int, seed: Int): ImagePixels {
        val random = Random(seed)
        return ImagePixels(width, height, IntArray(width * height) { (0xFF shl 24) or random.nextInt(0x1000000) })
    }

    private fun deltaE(p: OkLab, q: OkLab): Float {
        val dl = p.l - q.l
        val da = p.a - q.a
        val db = p.b - q.b
        return sqrt(dl * dl + da * da + db * db)
    }

    // ---- sampling ----

    @Test
    fun sampleGrid_neverExceedsMaxSamples() {
        val shapes = listOf(512 to 384, 384 to 512, 1 to 100_000, 100_000 to 1, 1000 to 999, 7 to 7, 300 to 2)
        for ((w, h) in shapes) {
            for (max in intArrayOf(1, 3, 17, 4_096, 65_536)) {
                var count = 0
                var last = -1
                SampleGrid.forEachIndex(w, h, max) {
                    assertTrue("index $it in range for ${w}x$h", it in 0 until w * h)
                    assertTrue("strictly increasing", it > last)
                    last = it
                    count++
                }
                assertTrue("${w}x$h max $max read $count", count in 1..max)
            }
        }
    }

    @Test
    fun sampleGrid_readsEveryPixelWhenSmallEnough() {
        var count = 0
        SampleGrid.forEachIndex(64, 32, 65_536) { assertEquals(count, it); count++ }
        assertEquals(64 * 32, count)
    }

    // ---- labStats ----

    @Test
    fun uniformImage_hasZeroStdAndColorMathMean() {
        for (argb in intArrayOf(blueArgb, skinArgb, 0xFF808080.toInt(), 0xFFFFFFFF.toInt(), 0xFF2E8B57.toInt())) {
            val stats = ColorAnalysis.labStats(uniform(300, 200, argb))
            val expected = ColorMath.argbToOkLab(argb)
            assertEquals("L", expected.l, stats.meanL, 1e-4f)
            assertEquals("a", expected.a, stats.meanA, 1e-4f)
            assertEquals("b", expected.b, stats.meanB, 1e-4f)
            assertEquals(0f, stats.stdL, 0f)
            assertEquals(0f, stats.stdA, 0f)
            assertEquals(0f, stats.stdB, 0f)
        }
    }

    @Test
    fun skinHalf_isDownWeightedRelativeToUnweightedMean() {
        val skin = ColorMath.argbToOkLab(skinArgb)
        val blue = ColorMath.argbToOkLab(blueArgb)
        val skinMask = SkinMask.weight(skin)
        assertTrue("test colour must read as skin ($skinMask)", skinMask > 0.9f)
        assertEquals("blue is not skin", 0f, SkinMask.weight(blue), 0f)

        val stats = ColorAnalysis.labStats(columnsSplit(200, 100, 100, skinArgb, blueArgb))
        val weighted = OkLab(stats.meanL, stats.meanA, stats.meanB)
        val unweighted = OkLab((skin.l + blue.l) / 2f, (skin.a + blue.a) / 2f, (skin.b + blue.b) / 2f)
        assertTrue("weighted mean sits closer to the non-skin half", deltaE(weighted, blue) < deltaE(unweighted, blue))

        val wSkin = 1f - 0.8f * skinMask
        val expectedA = (wSkin * skin.a + blue.a) / (wSkin + 1f)
        val expectedL = (wSkin * skin.l + blue.l) / (wSkin + 1f)
        assertEquals(expectedA, stats.meanA, 1e-4f)
        assertEquals(expectedL, stats.meanL, 1e-4f)
        assertEquals(100f * 100f * (wSkin + 1f), stats.weight, 1f)
    }

    @Test
    fun nearClippedPixels_weighOneFifth() {
        val white = 0xFFFFFFFF.toInt()
        val grey = 0xFF777777.toInt()
        val stats = ColorAnalysis.labStats(columnsSplit(100, 100, 50, white, grey))
        val greyL = ColorMath.argbToOkLab(grey).l
        val expectedL = (0.2f * 1f + greyL) / 1.2f
        assertEquals(expectedL, stats.meanL, 1e-4f)
        // population std of a two-point mixture with weights 0.2/1.2 and 1/1.2
        val p = 0.2f / 1.2f
        assertEquals(sqrt(p * (1 - p)) * abs(1f - greyL), stats.stdL, 1e-4f)
    }

    @Test
    fun labStats_respectsMaxSamplesInWeight() {
        val stats = ColorAnalysis.labStats(uniform(512, 512, blueArgb), maxSamples = 1_000)
        assertTrue("weight ${stats.weight} counts at most 1000 unit samples", stats.weight in 1f..1_000f)
    }

    // ---- palette ----

    @Test
    fun twoColourImage_yieldsTwoDominantSwatches() {
        val red = 0xFFC82828.toInt()
        val teal = 0xFF1E788C.toInt()
        val palette = ColorAnalysis.palette(columnsSplit(100, 100, 70, red, teal))
        assertEquals(5, palette.size)
        assertTrue("red swatch ${palette[0]}", deltaE(palette[0].lab, ColorMath.argbToOkLab(red)) < 0.01f)
        assertTrue("teal swatch ${palette[1]}", deltaE(palette[1].lab, ColorMath.argbToOkLab(teal)) < 0.01f)
        assertEquals(0.7f, palette[0].weight, 0.02f)
        assertEquals(0.3f, palette[1].weight, 0.02f)
        assertEquals(1f, palette.sumOf { it.weight.toDouble() }.toFloat(), 1e-5f)
    }

    @Test
    fun palette_isDeterministicForTheSameSeed() {
        val image = noise(128, 96, 7)
        assertEquals(ColorAnalysis.palette(image, seed = 11L), ColorAnalysis.palette(image, seed = 11L))
        assertEquals(ColorAnalysis.analyze(image), ColorAnalysis.analyze(image))
    }

    @Test
    fun palette_alwaysHasFiveSwatchesSummingToOneSortedDescending() {
        val gradient = ImagePixels(256, 64, IntArray(256 * 64) { i ->
            val x = i % 256
            (0xFF shl 24) or (x shl 16) or ((255 - x) shl 8) or ((i / 256) * 4)
        })
        val images = listOf(
            uniform(50, 50, blueArgb),
            columnsSplit(80, 40, 30, skinArgb, blueArgb),
            noise(200, 150, 3),
            gradient,
            uniform(1, 1, 0xFF000000.toInt()),
        )
        for (image in images) {
            for (seed in longArrayOf(1L, 42L, 9_001L)) {
                val palette = ColorAnalysis.palette(image, seed = seed)
                assertEquals(5, palette.size)
                assertEquals(1f, palette.sumOf { it.weight.toDouble() }.toFloat(), 1e-5f)
                for (i in 1 until palette.size) assertTrue("sorted", palette[i - 1].weight >= palette[i].weight)
                assertTrue("weights in [0,1]", palette.all { it.weight in 0f..1f })
            }
        }
    }

    @Test
    fun flatImage_duplicatesCentroidWithEqualWeights() {
        val palette = ColorAnalysis.palette(uniform(64, 64, blueArgb))
        val blue = ColorMath.argbToOkLab(blueArgb)
        assertEquals(5, palette.size)
        for (swatch in palette) {
            assertEquals(0.2f, swatch.weight, 1e-6f)
            assertTrue(deltaE(swatch.lab, blue) < 1e-5f)
        }
    }

    @Test
    fun noisyImage_fillsAllFiveClusters() {
        val palette = ColorAnalysis.palette(noise(128, 128, 5))
        assertTrue("every cluster populated: $palette", palette.all { it.weight > 0.05f })
    }

    @Test
    fun palette_honoursCustomK() {
        val palette = ColorAnalysis.palette(noise(64, 64, 9), k = 3)
        assertEquals(3, palette.size)
        assertEquals(1f, palette.sumOf { it.weight.toDouble() }.toFloat(), 1e-5f)
    }

    // ---- analyze ----

    @Test
    fun analyze_combinesPrimitives() {
        val image = columnsSplit(120, 80, 60, skinArgb, blueArgb)
        val result = ColorAnalysis.analyze(image)
        assertEquals(ColorAnalysis.labStats(image), result.labStats)
        assertEquals(ColorAnalysis.palette(image), result.palette)
        assertEquals(ColorAnalysis.estimateCctKelvin(image), result.cctKelvin, 0f)
        assertEquals(SkinMask.weight(ColorMath.argbToOkLab(skinArgb)) / 2f, result.skinFraction, 1e-4f)
    }
}
