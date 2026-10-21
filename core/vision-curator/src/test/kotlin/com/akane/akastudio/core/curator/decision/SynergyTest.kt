package com.akane.akastudio.core.curator.decision

import com.akane.akastudio.core.curator.api.ShotScale
import com.akane.akastudio.core.curator.api.SynergyBreakdown
import com.akane.akastudio.core.curator.decision.Fixtures.analysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.math.exp

class SynergyTest {

    private val hour = 3_600_000L

    /** Expected H_rhythm by (m, distinct scales), written out from the rule rather than recomputed. */
    private val rhythmTable = mapOf(
        (2 to 1) to 0.35f, (2 to 2) to 1.0f,
        (3 to 1) to 0.35f, (3 to 2) to 0.675f, (3 to 3) to 1.0f,
        (4 to 1) to 0.35f, (4 to 2) to 0.675f, (4 to 3) to 1.0f,
    )

    private fun allScaleLists(m: Int): List<List<ShotScale>> =
        if (m == 0) listOf(emptyList()) else allScaleLists(m - 1).flatMap { tail -> ShotScale.entries.map { tail + it } }

    @Test
    fun rhythm_everyCombinationForTwoThreeAndFourShots() {
        for (m in 2..4) {
            val lists = allScaleLists(m)
            assertEquals(Math.pow(3.0, m.toDouble()).toInt(), lists.size)
            for (scales in lists) {
                val expected = rhythmTable.getValue(m to scales.toSet().size)
                assertEquals("scales $scales", expected, Synergy.rhythm(scales), 1e-6f)
            }
        }
    }

    @Test
    fun rhythm_singleShotIsOne_emptyIsRejected() {
        assertEquals(1f, Synergy.rhythm(listOf(ShotScale.DETAIL)), 0f)
        assertThrows(IllegalArgumentException::class.java) { Synergy.rhythm(emptyList()) }
    }

    @Test
    fun context_knownTimes_decayOverThreeHours() {
        val t = exp(-1.0)  // span 3 h
        assertEquals((0.5 * t + 0.5).toFloat(), Synergy.context(listOf(0L, 3 * hour, hour), listOf(5000f, 5000f, 5000f)), 1e-6f)
        assertEquals(1f, Synergy.context(listOf(10L, 10L), listOf(5000f, 5000f)), 1e-6f)
    }

    @Test
    fun context_fewerThanTwoKnownTimes_useNeutralTimeScore() {
        assertEquals(0.8f, Synergy.context(listOf(null, null), listOf(6000f, 6000f)), 1e-6f)
        assertEquals(0.8f, Synergy.context(listOf(5L, null, null), listOf(6000f, 6000f, 6000f)), 1e-6f)
    }

    @Test
    fun context_cctSpreadIsMeasuredInMired() {
        // 5000 K = 200 mired, 4000 K = 250 mired: population σ = 25 mired -> c = e^-1.
        val expected = (0.5 * 0.6 + 0.5 * exp(-1.0)).toFloat()
        assertEquals(expected, Synergy.context(listOf(null, null), listOf(5000f, 4000f)), 1e-6f)
        assertThrows(IllegalArgumentException::class.java) { Synergy.context(emptyList(), listOf(0f)) }
    }

    @Test
    fun evaluate_combinesTermsIntoPsi() {
        val members = listOf(
            analysis("a", palette = Fixtures.solid(0.4f, 0f, 0f), scale = ShotScale.WIDE, cct = 5000f),
            analysis("b", palette = Fixtures.solid(0.5f, 0f, 0f), scale = ShotScale.DETAIL, cct = 4000f),
        )
        val synergy = Synergy.evaluate(members, listOf(0L, 3 * hour))
        val color = (0.7 * exp(-1.0) + 0.3).toFloat()
        val context = (0.5 * exp(-1.0) + 0.5 * exp(-1.0)).toFloat()
        assertEquals(color, synergy.color, 1e-5f)
        assertEquals(1f, synergy.rhythm, 1e-6f)
        assertEquals(context, synergy.context, 1e-6f)
        assertEquals((0.40 * synergy.color + 0.35 * synergy.rhythm + 0.25 * synergy.context).toFloat(), synergy.psi, 1e-6f)
        assertEquals(PaletteHarmony.colorScore(members.map { it.palette }), synergy.color, 1e-6f)
    }

    @Test
    fun evaluate_requiresAlignedTimes() {
        assertThrows(IllegalArgumentException::class.java) { Synergy.evaluate(listOf(analysis("a")), emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { Synergy.evaluate(emptyList(), emptyList()) }
    }

    @Test
    fun collageScore_appliesMeanQualityAndSizePenalty() {
        val synergy = SynergyBreakdown(color = 1f, rhythm = 1f, context = 1f, psi = 0.8f)
        val two = listOf(analysis("a", quality = 0.9f), analysis("b", quality = 0.7f))
        assertEquals(0.5f * 0.8f + 0.5f * 0.8f, Synergy.collageScore(two, synergy), 1e-6f)
        val four = two + listOf(analysis("c", quality = 0.6f), analysis("d", quality = 0.6f))
        assertEquals(((0.5 * 0.7 + 0.5 * 0.8) * 0.94).toFloat(), Synergy.collageScore(four, synergy), 1e-6f)
        assertThrows(IllegalArgumentException::class.java) { Synergy.collageScore(two.take(1), synergy) }
    }
}
