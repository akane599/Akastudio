package com.akane.akastudio.core.export.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HalfTest {

    @Test
    fun everyHalfPattern_roundTripsExactly_andNaNStaysNaN() {
        for (bits in 0 until 65536) {
            val h = bits.toShort()
            val f = Half.toFloat(h)
            val back = Half.fromFloat(f)
            if (f.isNaN()) {
                val e = back.toInt() and 0x7C00
                val m = back.toInt() and 0x3FF
                assertTrue("0x${bits.toString(16)} must stay NaN", e == 0x7C00 && m != 0)
            } else {
                assertEquals("0x${bits.toString(16)}", h, back)
            }
        }
    }

    @Test
    fun knownValues() {
        assertEquals(0x3C00.toShort(), Half.fromFloat(1f))
        assertEquals(1f, Half.toFloat(0x3C00.toShort()))
        assertEquals(0x7BFF.toShort(), Half.fromFloat(65504f))
        assertEquals(65504f, Half.toFloat(0x7BFF.toShort()))
        val smallestSubnormal = Math.scalb(1f, -24)
        assertEquals(0x0001.toShort(), Half.fromFloat(smallestSubnormal))
        assertEquals(smallestSubnormal, Half.toFloat(0x0001.toShort()))
        assertEquals(0xC000.toShort(), Half.fromFloat(-2f))
        assertEquals(0x8000.toShort(), Half.fromFloat(-0f))
        assertEquals(0x0400.toShort(), Half.fromFloat(Math.scalb(1f, -14)))
    }

    @Test
    fun infinitiesAndOverflow() {
        assertEquals(0x7C00.toShort(), Half.fromFloat(Float.POSITIVE_INFINITY))
        assertEquals(0xFC00.toShort(), Half.fromFloat(Float.NEGATIVE_INFINITY))
        assertEquals(Float.POSITIVE_INFINITY, Half.toFloat(0x7C00.toShort()))
        assertEquals(0x7BFF.toShort(), Half.fromFloat(65519.99f))
        assertEquals(0x7C00.toShort(), Half.fromFloat(65520f))
        assertEquals(0x7C00.toShort(), Half.fromFloat(1e9f))
    }

    @Test
    fun nan() {
        assertTrue(Half.toFloat(Half.fromFloat(Float.NaN)).isNaN())
        assertTrue(Half.toFloat(0x7E00.toShort()).isNaN())
        assertTrue(Half.toFloat(Half.fromFloat(Float.fromBits(0x7F800001))).isNaN())
    }

    @Test
    fun roundsToNearestEven() {
        val ulp = Math.scalb(1f, -10)
        assertEquals(0x3C00.toShort(), Half.fromFloat(1f + ulp / 2))          // tie, 0x3C00 is even
        assertEquals(0x3C02.toShort(), Half.fromFloat(1f + 3 * ulp / 2))      // tie, rounds up to even 0x3C02
        assertEquals(0x3C01.toShort(), Half.fromFloat(1f + ulp * 0.51f))
        assertEquals(0x0000.toShort(), Half.fromFloat(Math.scalb(1f, -25)))   // tie between 0 and 2^-24
        assertEquals(0x0001.toShort(), Half.fromFloat(Math.scalb(1.01f, -25)))
        assertEquals(0x0002.toShort(), Half.fromFloat(Math.scalb(3f, -25)))   // tie 1.5 units -> 2
        assertEquals(0x0400.toShort(), Half.fromFloat(Math.scalb(1f, -14) - Math.scalb(1f, -26))) // rounds up into normals
    }

    @Test
    fun fromFloat_isNearestForSampledFloats() {
        val rnd = java.util.Random(7)
        repeat(200_000) {
            val f = Float.fromBits(rnd.nextInt() and 0x7FFFFFFF)
            if (f.isNaN() || f >= 65504f) return@repeat
            val h = Half.fromFloat(f).toInt()
            val chosen = Half.toFloat(h.toShort())
            val err = kotlin.math.abs(chosen - f)
            if (h > 0) assertTrue("below neighbour closer for $f", err <= kotlin.math.abs(Half.toFloat((h - 1).toShort()) - f))
            if (h < 0x7BFF) assertTrue("above neighbour closer for $f", err <= kotlin.math.abs(Half.toFloat((h + 1).toShort()) - f))
        }
    }
}
