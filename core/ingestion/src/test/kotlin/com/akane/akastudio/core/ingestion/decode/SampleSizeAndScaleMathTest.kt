package com.akane.akastudio.core.ingestion.decode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleSizeAndScaleMathTest {

    @Test
    fun forTarget_pinnedCases() {
        assertEquals(4, SampleSize.forTarget(4000, 3000, 512))
        assertEquals(8, SampleSize.forTarget(8160, 6120, 512))
        assertEquals(1, SampleSize.forTarget(512, 512, 512))
        assertEquals(1, SampleSize.forTarget(100, 50, 512))
    }

    @Test
    fun forTarget_usesLongEdgeInEitherOrientation() {
        assertEquals(4, SampleSize.forTarget(3000, 4000, 512))
        assertEquals(2, SampleSize.forTarget(1024, 10, 512))
        assertEquals(1, SampleSize.forTarget(1023, 10, 512))
    }

    @Test
    fun forTarget_nonPositiveInputIsOne() {
        assertEquals(1, SampleSize.forTarget(0, 3000, 512))
        assertEquals(1, SampleSize.forTarget(4000, 0, 512))
        assertEquals(1, SampleSize.forTarget(-4000, -3000, 512))
        assertEquals(1, SampleSize.forTarget(4000, 3000, 0))
        assertEquals(1, SampleSize.forTarget(4000, 3000, -1))
    }

    @Test
    fun forTarget_neverUndershootsTheTarget() {
        for (edge in listOf(512, 513, 1000, 1023, 1024, 2047, 4032, 8160, 12_000, Int.MAX_VALUE)) {
            val sample = SampleSize.forTarget(edge, edge / 2 + 1, 512)
            assertTrue("edge=$edge sample=$sample", edge / sample >= 512)
            assertTrue("edge=$edge sample=$sample not largest", edge.toLong() / (sample * 2L) < 512)
        }
    }

    @Test
    fun fitLongEdge_keepsAspectWithExactLongEdge() {
        assertEquals(512 to 384, ScaleMath.fitLongEdge(4000, 3000, 512))
        assertEquals(384 to 512, ScaleMath.fitLongEdge(3000, 4000, 512))
        assertEquals(512 to 384, ScaleMath.fitLongEdge(1020, 765, 512))
        assertEquals(512 to 512, ScaleMath.fitLongEdge(1000, 1000, 512))
    }

    @Test
    fun fitLongEdge_roundsHalfUp() {
        // 333 * 0.512 = 170.496 -> 170; 335 * 0.512 = 171.52 -> 172.
        assertEquals(512 to 170, ScaleMath.fitLongEdge(1000, 333, 512))
        assertEquals(512 to 172, ScaleMath.fitLongEdge(1000, 335, 512))
        assertEquals(3 to 2, ScaleMath.fitLongEdge(4, 3, 3)) // 2.25 -> 2
        assertEquals(2 to 2, ScaleMath.fitLongEdge(4, 3, 2)) // 1.5 -> 2
    }

    @Test
    fun fitLongEdge_minimumSideIsOne() {
        assertEquals(512 to 1, ScaleMath.fitLongEdge(10_000, 1, 512))
        assertEquals(1 to 512, ScaleMath.fitLongEdge(1, 10_000, 512))
        assertEquals(1 to 1, ScaleMath.fitLongEdge(3, 2, 0))
    }
}
