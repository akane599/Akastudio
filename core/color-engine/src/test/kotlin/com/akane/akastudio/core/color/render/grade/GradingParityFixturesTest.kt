package com.akane.akastudio.core.color.render.grade

import com.akane.akastudio.core.color.analysis.SkinMask
import com.akane.akastudio.core.color.math.ColorMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GradingParityFixturesTest {

    @Test
    fun gridHoldsTwoHundredFiftySixDistinctOpaqueColours() {
        val grid = GradingParityFixtures.gridArgb()
        assertEquals(256, grid.size)
        assertEquals("grid colours must be distinct", 256, grid.toSet().size)
        assertTrue("grid colours must be opaque", grid.all { it ushr 24 == 0xFF })
    }

    @Test
    fun gridExercisesTheSkinMask() {
        val skin = GradingParityFixtures.gridArgb().count { SkinMask.weight(ColorMath.argbToOkLab(it)) > 0.5f }
        assertTrue("expected skin tones in the grid, found $skin", skin >= 8)
    }

    @Test
    fun fixturesAreDeterministic() {
        val first = GradingParityFixtures.expectations()
        val second = GradingParityFixtures.expectations()
        assertEquals(6, first.size)
        assertEquals(first.map { it.case }, second.map { it.case })
        for (i in first.indices) assertArrayEquals(first[i].expectedEncoded, second[i].expectedEncoded, 0f)
        assertArrayEquals(GradingParityFixtures.gridLinear(), GradingParityFixtures.gridLinear(), 0f)
        assertEquals(GradingParityFixtures.table(first), GradingParityFixtures.table(second))
    }

    @Test
    fun fixturesAreFiniteAndInTheEncodedRange() {
        assertTrue(GradingParityFixtures.gridLinear().all { it.isFinite() && it in 0f..1f })
        for (expectation in GradingParityFixtures.expectations()) {
            assertEquals(256 * 3, expectation.expectedEncoded.size)
            for (v in expectation.expectedEncoded) {
                assertTrue("${expectation.case.name}: $v must be finite in [0, 1]", v.isFinite() && v in 0f..1f)
            }
        }
    }

    @Test
    fun casesCoverDistinctLooksAndActuallyGrade() {
        val expectations = GradingParityFixtures.expectations()
        assertEquals("one look per case", expectations.size, expectations.map { it.case.params.look }.toSet().size)
        val identity = GradingParityFixtures.gridLinear().map { GradingParityFixtures.encodeClamped(it) }.toFloatArray()
        for (e in expectations) {
            val changed = e.expectedEncoded.indices.count { kotlin.math.abs(e.expectedEncoded[it] - identity[it]) > 2f / 255f }
            assertTrue("${e.case.name} should move colours beyond the tolerance, moved $changed", changed > 0)
        }
    }

    @Test
    fun tableHasOneLinePerColourAndCase() {
        val lines = GradingParityFixtures.table().lines().filter { it.isNotBlank() }
        assertEquals(1 + 6 * (1 + 256), lines.size)
    }
}
