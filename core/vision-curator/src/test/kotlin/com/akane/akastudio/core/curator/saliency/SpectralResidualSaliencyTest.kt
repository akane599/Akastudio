package com.akane.akastudio.core.curator.saliency

import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.curator.api.SaliencyMap
import com.akane.akastudio.core.curator.metrics.TestImages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random

class SpectralResidualSaliencyTest {

    private fun blobImage(w: Int, h: Int, cx: Float, cy: Float, radius: Float) = TestImages.toPixels(
        TestImages.plane(w, h) { x, y ->
            val dx = x - cx
            val dy = y - cy
            40f + 200f * exp(-(dx * dx + dy * dy) / (2 * radius * radius))
        },
        w, h,
    )

    private fun argMax(map: SaliencyMap): Pair<Int, Int> {
        var best = 0
        for (i in map.values.indices) if (map.values[i] > map.values[best]) best = i
        return (best % map.width) to (best / map.width)
    }

    @Test
    fun singleBlob_maximumNearBlobCentre() {
        val w = 512
        val h = 512
        val cx = 330f
        val cy = 170f
        val map = SpectralResidualSaliency.compute(blobImage(w, h, cx, cy, radius = 14f))
        val (mx, my) = argMax(map)
        val ex = cx * map.width / w
        val ey = cy * map.height / h
        assertTrue("max at ($mx,$my), blob at ($ex,$ey)", abs(mx + 0.5f - ex) <= 3f && abs(my + 0.5f - ey) <= 3f)
    }

    @Test
    fun output_isNormalizedAndKeepsAspect() {
        val landscape = SpectralResidualSaliency.compute(blobImage(512, 384, 200f, 220f, radius = 12f))
        assertEquals(64, landscape.width)
        assertEquals(48, landscape.height)
        assertTrue(landscape.values.all { it in 0f..1f })
        assertEquals(1f, landscape.values.max(), 1e-6f)
        assertEquals(0f, landscape.values.min(), 1e-6f)

        val portrait = SpectralResidualSaliency.compute(blobImage(288, 512, 140f, 300f, radius = 12f))
        assertEquals(36, portrait.width)
        assertEquals(64, portrait.height)
        val (_, my) = argMax(portrait)
        assertTrue("portrait blob row $my", abs(my + 0.5f - 300f * 64 / 512) <= 3f)

        val small = SpectralResidualSaliency.compute(blobImage(512, 384, 200f, 220f, radius = 12f), longEdge = 32)
        assertEquals(32, small.width)
        assertEquals(24, small.height)
    }

    @Test
    fun flatImage_isAllZero() {
        val flat = TestImages.toPixels(FloatArray(64 * 64) { 90f }, 64, 64)
        val map = SpectralResidualSaliency.compute(flat)
        assertTrue(map.values.all { it == 0f })
    }

    @Test
    fun fft_roundTripOnRandomData() {
        val random = Random(42)
        for ((w, h) in listOf(64 to 64, 64 to 32, 8 to 128, 1 to 16)) {
            val re = DoubleArray(w * h) { random.nextDouble(-100.0, 100.0) }
            val im = DoubleArray(w * h) { random.nextDouble(-100.0, 100.0) }
            val r = re.copyOf()
            val i = im.copyOf()
            Fft2D.forward(r, i, w, h)
            Fft2D.inverse(r, i, w, h)
            val err = re.indices.maxOf { maxOf(abs(r[it] - re[it]), abs(i[it] - im[it])) }
            assertTrue("$w x $h err=$err", err < 1e-4)
        }
    }

    @Test
    fun fft_matchesDirectDftOnAnImpulseAndDc() {
        val w = 8
        val h = 4
        val re = DoubleArray(w * h)
        val im = DoubleArray(w * h)
        re[0] = 1.0
        Fft2D.forward(re, im, w, h)
        assertTrue(re.all { abs(it - 1.0) < 1e-12 } && im.all { abs(it) < 1e-12 })

        val dc = DoubleArray(w * h) { 2.0 }
        val dcIm = DoubleArray(w * h)
        Fft2D.forward(dc, dcIm, w, h)
        assertEquals(64.0, dc[0], 1e-9)
        assertTrue((1 until w * h).all { abs(dc[it]) < 1e-9 && abs(dcIm[it]) < 1e-9 })
        assertEquals(16, Fft2D.nextPowerOfTwo(9))
        assertEquals(64, Fft2D.nextPowerOfTwo(64))
    }

    @Test(expected = IllegalArgumentException::class)
    fun fft_rejectsNonPowerOfTwo() {
        Fft2D.forward(DoubleArray(48), DoubleArray(48), 8, 6)
    }

    @Test
    fun withFaces_raisesInsideAndFeathersTheMargin() {
        val base = SaliencyMap(20, 20, FloatArray(400) { 0.1f })
        val face = TestImages.face(NormRect(0.25f, 0.25f, 0.75f, 0.75f))
        val boosted = SpectralResidualSaliency.withFaces(base, listOf(face))
        fun at(x: Int, y: Int) = boosted.values[y * 20 + x]
        assertEquals(1f, at(10, 10), 0f)
        assertEquals(1f, at(5, 5), 0f)
        // Cell 4 centre is at u = 0.225: 0.025 outside, margin 0.075 -> 1 - 1/3.
        assertEquals(2f / 3, at(4, 10), 1e-4f)
        // Cell 3 centre at 0.175 is beyond the 0.075 margin.
        assertEquals(0.1f, at(3, 10), 0f)
        assertEquals(0.1f, at(0, 0), 0f)

        val half = SpectralResidualSaliency.withFaces(base, listOf(face), boost = 0.5f)
        assertEquals(0.5f, half.values[10 * 20 + 10], 0f)
        val untouched = SpectralResidualSaliency.withFaces(base, emptyList())
        assertTrue(untouched.values.contentEquals(base.values))
    }
}
