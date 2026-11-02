package com.akane.akastudio.core.ingestion.decode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FullResSizingTest {

    @Test
    fun landscape_fitsLongEdgeAndKeepsAspect() {
        assertEquals(2048 to 1536, FullResSizing.targetSize(4000, 3000, 2048))
        assertEquals(4096 to 3072, FullResSizing.targetSize(8160, 6120, 4096))
    }

    @Test
    fun portrait_fitsLongEdgeAndKeepsAspect() {
        assertEquals(1536 to 2048, FullResSizing.targetSize(3000, 4000, 2048))
        assertEquals(3072 to 4096, FullResSizing.targetSize(6120, 8160, 4096))
    }

    @Test
    fun square_scalesBothSidesToTheBound() {
        assertEquals(1080 to 1080, FullResSizing.targetSize(5000, 5000, 1080))
    }

    @Test
    fun neverUpscales() {
        assertEquals(800 to 600, FullResSizing.targetSize(800, 600, 2048))
        assertEquals(600 to 800, FullResSizing.targetSize(600, 800, 2048))
        assertEquals(2048 to 1536, FullResSizing.targetSize(2048, 1536, 2048))
        assertEquals(1 to 1, FullResSizing.targetSize(1, 1, 4096))
    }

    @Test
    fun roundsToNearestPixel() {
        // 2001 * 1500 / 3000 = 1000.5 -> 1001 (half up); 1999 * 1500 / 3000 = 999.5 -> 1000.
        assertEquals(1500 to 1001, FullResSizing.targetSize(3000, 2001, 1500))
        assertEquals(1500 to 1000, FullResSizing.targetSize(3000, 1999, 1500))
        // 2999 * 1000 / 4000 = 749.75 -> 750.
        assertEquals(1000 to 750, FullResSizing.targetSize(4000, 2999, 1000))
    }

    @Test
    fun extremeAspect_shortSideStaysAtLeastOnePixel() {
        assertEquals(1000 to 1, FullResSizing.targetSize(10000, 10, 1000))
        assertEquals(1 to 1000, FullResSizing.targetSize(10, 10000, 1000))
        assertEquals(100 to 1, FullResSizing.targetSize(10000, 10, 100))
    }

    @Test
    fun maxEdgeOfOne_collapsesToSinglePixel() {
        assertEquals(1 to 1, FullResSizing.targetSize(4000, 3000, 1))
        assertEquals(1 to 1, FullResSizing.targetSize(10000, 10, 1))
        assertEquals(1 to 1, FullResSizing.targetSize(1, 1, 1))
    }

    @Test
    fun longEdgeNeverExceedsTheBound() {
        for (bound in listOf(1, 2, 3, 512, 1080, 2047, 4096)) {
            for ((w, h) in listOf(4000 to 3000, 3000 to 4000, 10000 to 10, 7 to 9999, 4097 to 4096)) {
                val (tw, th) = FullResSizing.targetSize(w, h, bound)
                assertTrue("bound=$bound size=${w}x$h -> ${tw}x$th", maxOf(tw, th) <= bound)
                assertTrue("bound=$bound size=${w}x$h -> ${tw}x$th", tw >= 1 && th >= 1)
            }
        }
    }

    @Test
    fun rejectsNonPositiveInput() {
        assertThrows(IllegalArgumentException::class.java) { FullResSizing.targetSize(0, 3000, 2048) }
        assertThrows(IllegalArgumentException::class.java) { FullResSizing.targetSize(4000, -1, 2048) }
        assertThrows(IllegalArgumentException::class.java) { FullResSizing.targetSize(4000, 3000, 0) }
    }

    @Test
    fun sampleSize_isTheLargestPowerOfTwoThatStaysAtOrAboveTheTarget() {
        // 4000x3000 -> 3072x2304: 1.30, so no subsampling; the remainder is resampled on linear values.
        assertEquals(1, FullResSizing.sampleSize(4000, 3000, 3072, 2304))
        // 6000x4000 -> 3072x2048: 1.95 < 2.
        assertEquals(1, FullResSizing.sampleSize(6000, 4000, 3072, 2048))
        // 6000x4000 -> 2048x1365: 2.93 -> 2 (3000x2000), then 1.46 on linear values.
        assertEquals(2, FullResSizing.sampleSize(6000, 4000, 2048, 1365))
        // Exactly 2:1 and 4:1 are taken whole by the codec.
        assertEquals(2, FullResSizing.sampleSize(448, 128, 224, 64))
        assertEquals(4, FullResSizing.sampleSize(8000, 6000, 2000, 1500))
        // 50 MP 8160x6120 -> 3072x2304: 2.66 -> 2 (4080x3060).
        assertEquals(2, FullResSizing.sampleSize(8160, 6120, 3072, 2304))
    }

    @Test
    fun sampleSize_neverDropsBelowTheTargetUnderIntegerDivision() {
        // 4001 / 2 = 2000 < 2001, so 2 would undershoot by a pixel.
        assertEquals(1, FullResSizing.sampleSize(4001, 3000, 2001, 1500))
        // Either side can be the limiting one.
        assertEquals(1, FullResSizing.sampleSize(4000, 3001, 2000, 1501))
        assertEquals(2, FullResSizing.sampleSize(4000, 3000, 2000, 1500))
    }

    @Test
    fun sampleSize_isOneWhenNoDownscaleIsNeeded() {
        assertEquals(1, FullResSizing.sampleSize(4000, 3000, 4000, 3000))
        assertEquals(1, FullResSizing.sampleSize(1, 1, 1, 1))
    }

    @Test
    fun sampleSize_matchesTargetSizeForPortraitAndPanoramas() {
        listOf(3000 to 4000, 12000 to 2000, 2000 to 12000, 345 to 9000).forEach { (w, h) ->
            listOf(256, 1024, 3072, 4096).forEach { edge ->
                val (tw, th) = FullResSizing.targetSize(w, h, edge)
                val sample = FullResSizing.sampleSize(w, h, tw, th)
                assertTrue("${w}x$h at $edge: sample $sample undershoots", w / sample >= tw && h / sample >= th)
                assertTrue("${w}x$h at $edge: sample $sample leaves a 2:1 remainder", w / (sample * 2) < tw || h / (sample * 2) < th)
            }
        }
    }

    @Test
    fun sampleSize_rejectsNonPositiveSizes() {
        assertThrows(IllegalArgumentException::class.java) { FullResSizing.sampleSize(0, 10, 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { FullResSizing.sampleSize(10, 10, 1, 0) }
    }
}
