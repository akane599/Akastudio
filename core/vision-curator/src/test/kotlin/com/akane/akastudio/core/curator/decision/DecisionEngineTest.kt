package com.akane.akastudio.core.curator.decision

import com.akane.akastudio.core.curator.api.PhotoAnalysis
import com.akane.akastudio.core.curator.api.RejectReason
import com.akane.akastudio.core.curator.api.ShotScale
import com.akane.akastudio.core.curator.api.StoryMode
import com.akane.akastudio.core.curator.api.Warmth
import com.akane.akastudio.core.curator.decision.Fixtures.analysis
import com.akane.akastudio.core.curator.decision.Fixtures.flip
import com.akane.akastudio.core.curator.decision.Fixtures.randomHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DecisionEngineTest {

    private val hour = 3_600_000L
    private val far = Fixtures.solid(0.9f, 0.2f, 0.1f)   // ~0.46 OKLab away from the grey default
    private val tau = DecisionConfig().tau

    private fun times(vararg pairs: Pair<String, Long?>): Map<String, Long?> = mapOf(*pairs)

    private fun allIds(decision: Decision): Set<String> =
        (decision.alternatives.values + decision.chosen).flatMap { it.photoIds }.toSet()

    // ---- Hero vs collage ----

    @Test
    fun singleCluster_isHero() {
        val p = randomHash("burst", 1)
        val photos = listOf(
            analysis("a", quality = 0.7f, pHash = p),
            analysis("b", quality = 0.9f, pHash = flip(p, 2)),
            analysis("c", quality = 0.8f, pHash = flip(p, 3)),
        )
        val decision = DecisionEngine.decide(photos, emptyMap())
        assertEquals(StoryMode.HERO, decision.chosen.mode)
        assertEquals(listOf("b"), decision.chosen.photoIds)
        assertEquals(0.9f, decision.chosen.score, 0f)
        assertNull(decision.chosen.synergy)
        assertEquals(listOf("b"), decision.champions)
        assertEquals(setOf(StoryMode.HERO), decision.alternatives.keys)
    }

    @Test
    fun secondChampionBelowMinSecondQuality_isHeroEvenWhenAPairWouldClear() {
        val config = DecisionConfig(minMemberQuality = 0.4f)
        val photos = listOf(
            analysis("a", quality = 0.6f, scale = ShotScale.WIDE),
            analysis("b", quality = 0.5f, scale = ShotScale.MEDIUM),
        )
        val decision = DecisionEngine.decide(photos, times("a" to 0L, "b" to 0L), config)
        assertEquals(StoryMode.HERO, decision.chosen.mode)
        val pair = decision.alternatives.getValue(StoryMode.COLLAGE_2)
        assertTrue("pair ${pair.score} would clear tau*Q1", pair.score >= config.tau * 0.6f)
    }

    @Test
    fun strongTrio_becomesCollage3WhenNoQuadClears() {
        val photos = listOf(
            analysis("a", quality = 0.80f, scale = ShotScale.WIDE),
            analysis("b", quality = 0.78f, scale = ShotScale.MEDIUM),
            analysis("c", quality = 0.76f, scale = ShotScale.DETAIL),
            analysis("d", quality = 0.62f, scale = ShotScale.WIDE, palette = far, cct = 2500f),
        )
        val decision = DecisionEngine.decide(photos, times("a" to 0L, "b" to 0L, "c" to 0L, "d" to 12 * hour))
        val threshold = tau * 0.80f
        assertTrue(decision.alternatives.getValue(StoryMode.COLLAGE_4).score < threshold)
        assertEquals(StoryMode.COLLAGE_3, decision.chosen.mode)
        assertEquals(setOf("a", "b", "c"), decision.chosen.photoIds.toSet())
        assertTrue(decision.chosen.score >= threshold)
        // The size penalty no longer arbitrates between sizes: the best pair scores higher but the trio wins.
        assertTrue(decision.alternatives.getValue(StoryMode.COLLAGE_2).score > decision.chosen.score)
        assertEquals(decision.chosen, decision.alternatives[StoryMode.COLLAGE_3])
    }

    @Test
    fun clearingQuad_winsOverHigherScoringPair_andIsAnchorOrdered() {
        val photos = listOf(
            analysis("a", quality = 0.80f, aesthetic = 0.5f, scale = ShotScale.WIDE),
            analysis("b", quality = 0.78f, aesthetic = 0.6f, scale = ShotScale.MEDIUM),
            analysis("c", quality = 0.76f, aesthetic = 0.4f, scale = ShotScale.DETAIL),
            analysis("d", quality = 0.74f, aesthetic = 0.9f, scale = ShotScale.WIDE),
        )
        val decision = DecisionEngine.decide(photos, photos.associate { it.candidateId to 0L })
        assertEquals(StoryMode.COLLAGE_4, decision.chosen.mode)
        assertTrue(decision.alternatives.getValue(StoryMode.COLLAGE_2).score > decision.chosen.score)
        // Highest aesthetic first (the chromatic anchor), then quality descending.
        assertEquals(listOf("d", "a", "b", "c"), decision.chosen.photoIds)
        val synergy = decision.chosen.synergy!!
        assertEquals(1f, synergy.color, 1e-6f)
        assertEquals(1f, synergy.rhythm, 1e-6f)
        assertEquals(((0.5 * 0.77 + 0.5 * synergy.psi) * 0.94).toFloat(), decision.chosen.score, 1e-5f)
    }

    @Test
    fun onlyAPairClears_pairWins() {
        val photos = listOf(
            analysis("a", quality = 0.80f, scale = ShotScale.WIDE),
            analysis("b", quality = 0.78f, scale = ShotScale.MEDIUM),
            analysis("c", quality = 0.62f, scale = ShotScale.DETAIL, palette = far, cct = 2500f),
            analysis("d", quality = 0.62f, scale = ShotScale.DETAIL, palette = far, cct = 2500f),
        )
        val decision = DecisionEngine.decide(
            photos,
            times("a" to 0L, "b" to 0L, "c" to 12 * hour, "d" to 12 * hour),
        )
        val threshold = tau * 0.80f
        assertTrue(decision.alternatives.getValue(StoryMode.COLLAGE_4).score < threshold)
        assertTrue(decision.alternatives.getValue(StoryMode.COLLAGE_3).score < threshold)
        assertEquals(StoryMode.COLLAGE_2, decision.chosen.mode)
        assertEquals(setOf("a", "b"), decision.chosen.photoIds.toSet())
    }

    @Test
    fun outstandingHero_beatsMediocreCollages() {
        val photos = listOf(
            analysis("a", quality = 0.95f, cct = 3000f),
            analysis("b", quality = 0.64f, palette = far, cct = 5500f),
            analysis("c", quality = 0.63f, palette = Fixtures.solid(0.2f, -0.2f, 0.15f), cct = 8000f),
        )
        val decision = DecisionEngine.decide(photos, emptyMap())
        assertEquals(StoryMode.HERO, decision.chosen.mode)
        assertEquals(listOf("a"), decision.chosen.photoIds)
        assertEquals(0.95f, decision.chosen.score, 0f)
        assertEquals(setOf(StoryMode.HERO, StoryMode.COLLAGE_2, StoryMode.COLLAGE_3), decision.alternatives.keys)
        decision.alternatives.values.filter { it.mode != StoryMode.HERO }.forEach {
            assertTrue("${it.mode} ${it.score}", it.score < tau * 0.95f)
        }
    }

    @Test
    fun memberBelowMinMemberQuality_isExcludedFromEverySubset() {
        val photos = listOf(
            analysis("a", quality = 0.80f, scale = ShotScale.WIDE),
            analysis("b", quality = 0.78f, scale = ShotScale.MEDIUM),
            analysis("c", quality = 0.60f, scale = ShotScale.DETAIL),
        )
        val captured = photos.associate { it.candidateId to 0L }
        val decision = DecisionEngine.decide(photos, captured)
        assertEquals(setOf(StoryMode.HERO, StoryMode.COLLAGE_2), decision.alternatives.keys)
        assertFalse("c" in allIds(decision))
        assertEquals(StoryMode.COLLAGE_2, decision.chosen.mode)

        // The same trio with the gate lowered becomes the chosen collage: the gate alone excluded it.
        val relaxed = DecisionEngine.decide(photos, captured, DecisionConfig(minMemberQuality = 0.55f))
        assertEquals(StoryMode.COLLAGE_3, relaxed.chosen.mode)
        assertTrue("c" in relaxed.chosen.photoIds)
    }

    @Test
    fun exactScoreTies_goToTheLexicographicallySmallerIdList() {
        val photos = listOf("z", "x", "y").map { analysis(it, quality = 0.7f, aesthetic = 0.5f) }
        val decision = DecisionEngine.decide(photos, emptyMap())
        assertEquals(listOf("x", "y"), decision.alternatives.getValue(StoryMode.COLLAGE_2).photoIds)
        assertEquals(listOf("x", "y", "z"), decision.champions)
    }

    @Test
    fun rejectedPhotos_areReportedAndNeverUsed() {
        val photos = listOf(
            analysis("r2", quality = 0.99f, reject = RejectReason.EYES_CLOSED),
            analysis("a", quality = 0.8f, scale = ShotScale.WIDE),
            analysis("b", quality = 0.78f, scale = ShotScale.DETAIL),
            analysis("r1", quality = 0.99f, reject = RejectReason.BLURRY),
        )
        val decision = DecisionEngine.decide(photos, emptyMap())
        assertEquals(listOf("r1", "r2"), decision.rejectedIds)
        assertFalse(allIds(decision).any { it.startsWith("r") })
        assertFalse(decision.clusters.flatMap { it.memberIds }.any { it.startsWith("r") })
    }

    @Test
    fun allRejected_throwsIllegalState_emptyThrowsIllegalArgument() {
        val error = assertThrows(IllegalStateException::class.java) {
            DecisionEngine.decide(listOf(analysis("a", reject = RejectReason.UNREADABLE)), emptyMap())
        }
        assertEquals("all candidates rejected", error.message)
        assertThrows(IllegalArgumentException::class.java) { DecisionEngine.decide(emptyList(), emptyMap()) }
    }

    @Test
    fun decision_isDeterministicUnderInputShuffling() {
        val p = randomHash("burst", 7)
        val photos = listOf(
            analysis("a", quality = 0.80f, aesthetic = 0.7f, scale = ShotScale.WIDE, pHash = p),
            analysis("a2", quality = 0.75f, scale = ShotScale.WIDE, pHash = flip(p, 1)),
            analysis("b", quality = 0.78f, scale = ShotScale.MEDIUM, palette = Fixtures.solid(0.55f, 0.04f, 0.02f)),
            analysis("c", quality = 0.76f, scale = ShotScale.DETAIL, cct = 4500f),
            analysis("d", quality = 0.70f, aesthetic = 0.9f, scale = ShotScale.MEDIUM, palette = far),
            analysis("e", quality = 0.70f, aesthetic = 0.9f, scale = ShotScale.DETAIL),
            analysis("r", quality = 0.95f, reject = RejectReason.BAD_EXPOSURE),
        )
        val captured = times("a" to 0L, "b" to hour, "c" to 2 * hour, "d" to null, "e" to 0L)
        val reference = DecisionEngine.decide(photos, captured)
        repeat(8) { seed ->
            assertEquals(reference, DecisionEngine.decide(photos.shuffled(Random(seed)), captured))
        }
    }

    @Test
    fun twentyFourEligibleChampions_decideWellWithinBudget() {
        val random = Random(99)
        val photos = List(24) { i ->
            analysis(
                "p$i",
                quality = 0.62f + random.nextFloat() * 0.33f,
                aesthetic = random.nextFloat(),
                scale = ShotScale.entries[random.nextInt(3)],
                cct = 3000f + random.nextFloat() * 5000f,
                palette = List(5) {
                    com.akane.akastudio.core.color.api.PaletteSwatch(
                        com.akane.akastudio.core.color.api.OkLab(
                            random.nextFloat(), random.nextFloat() * 0.4f - 0.2f, random.nextFloat() * 0.4f - 0.2f,
                        ),
                        0.05f + random.nextFloat(),
                    )
                },
            )
        }
        val captured = photos.associate { it.candidateId to random.nextLong(0, 6 * hour) }
        val start = System.nanoTime()
        val decision = DecisionEngine.decide(photos, captured)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(24, decision.champions.size)
        assertEquals(StoryMode.entries.toSet(), decision.alternatives.keys)
        assertTrue("decide took $elapsedMs ms", elapsedMs < 5_000)
    }

    // ---- redecide ----

    /** Clusters A = {A1 .9, A2 .85}, B = {B1 .8, B2 .7}, C = {C1 .5}; R rejected. C1 is below the member gate. */
    private fun threeClusters(): List<PhotoAnalysis> {
        val pa = randomHash("A", 1)
        val pb = randomHash("B", 1)
        return listOf(
            analysis("A1", quality = 0.9f, scale = ShotScale.WIDE, pHash = pa),
            analysis("A2", quality = 0.85f, scale = ShotScale.WIDE, pHash = flip(pa, 1)),
            analysis("B1", quality = 0.8f, scale = ShotScale.MEDIUM, pHash = pb),
            analysis("B2", quality = 0.7f, scale = ShotScale.MEDIUM, pHash = flip(pb, 1)),
            analysis("C1", quality = 0.5f, scale = ShotScale.DETAIL),
            analysis("R", quality = 0.99f, reject = RejectReason.BLURRY),
        )
    }

    @Test
    fun redecide_nullForcedMode_isPlainDecide() {
        val photos = threeClusters()
        val previous = DecisionEngine.decide(photos, emptyMap())
        assertEquals(previous, DecisionEngine.redecide(previous, photos, emptyMap(), forcedMode = null))
    }

    @Test
    fun redecide_forcedHero_isTopChampion_keepingTheOfferedAlternatives() {
        val photos = threeClusters()
        val previous = DecisionEngine.decide(photos, emptyMap())
        assertEquals(setOf(StoryMode.HERO, StoryMode.COLLAGE_2), previous.alternatives.keys)
        val forced = DecisionEngine.redecide(previous, photos, emptyMap(), StoryMode.HERO)
        assertEquals(StoryMode.HERO, forced.chosen.mode)
        assertEquals(listOf("A1"), forced.chosen.photoIds)
        assertEquals(previous.alternatives, forced.alternatives)
        assertEquals(previous.clusters, forced.clusters)
        assertEquals(previous.rejectedIds, forced.rejectedIds)
        assertEquals(previous.champions, forced.champions)
    }

    /**
     * SQ-67: forcing an offered mode must not re-score. The drawer's options (modes, photos and scores) stay the
     * original curation's, the forced choice is exactly the offered option, and forcing back restores the original.
     * Clusters A1 .9, B1 .8, C1 .75 pass the member gate (0.62); D1 .6 does not, so the curation offers HERO, 2 and 3.
     */
    @Test
    fun redecide_forcingAnOfferedMode_keepsEveryOptionAndScore() {
        val fourClusters = listOf(
            analysis("A1", quality = 0.9f, scale = ShotScale.WIDE, cct = 5500f),
            analysis("B1", quality = 0.8f, scale = ShotScale.MEDIUM, cct = 5500f),
            analysis("C1", quality = 0.75f, scale = ShotScale.WIDE, cct = 2500f),
            analysis("D1", quality = 0.6f, scale = ShotScale.DETAIL, cct = 5500f),
        )
        val fourTimes = times("A1" to 0L, "B1" to 0L, "C1" to 30 * hour, "D1" to 0L)
        for ((photos, captured) in listOf(threeClusters() to emptyMap(), fourClusters to fourTimes)) {
            val previous = DecisionEngine.decide(photos, captured)
            assertTrue(previous.alternatives.size >= 2)
            for ((mode, offered) in previous.alternatives) {
                val forced = DecisionEngine.redecide(previous, photos, captured, mode)
                assertEquals("options after forcing $mode", previous.alternatives, forced.alternatives)
                assertEquals("forced $mode", offered, forced.chosen)
                val back = DecisionEngine.redecide(forced, photos, captured, previous.chosen.mode)
                assertEquals("forcing back from $mode", previous, back)
            }
        }
    }

    @Test
    fun redecide_forcedCollages_useAllChampionsIgnoringTheGate_thenFill() {
        val photos = threeClusters()
        val previous = DecisionEngine.decide(photos, emptyMap())

        val two = DecisionEngine.redecide(previous, photos, emptyMap(), StoryMode.COLLAGE_2)
        assertEquals(StoryMode.COLLAGE_2, two.chosen.mode)
        assertEquals(setOf("A1", "B1"), two.chosen.photoIds.toSet())

        val three = DecisionEngine.redecide(previous, photos, emptyMap(), StoryMode.COLLAGE_3)
        assertEquals(StoryMode.COLLAGE_3, three.chosen.mode)
        assertEquals(setOf("A1", "B1", "C1"), three.chosen.photoIds.toSet())   // C1 (Q 0.5) despite the gate

        // K = 3 < 4: champions plus the best non-champion of a distinct cluster (A2 0.85 over B2 0.7).
        val four = DecisionEngine.redecide(previous, photos, emptyMap(), StoryMode.COLLAGE_4)
        assertEquals(StoryMode.COLLAGE_4, four.chosen.mode)
        assertEquals(setOf("A1", "B1", "C1", "A2"), four.chosen.photoIds.toSet())
        assertNotNullSynergyAndScore(four)

        assertEquals(previous.alternatives, two.alternatives)
        for (decision in listOf(three, four)) {
            assertFalse("R" in allIds(decision))
            // Only the forced, previously unoffered mode is added; the offered options are untouched.
            assertEquals(previous.alternatives + (decision.chosen.mode to decision.chosen), decision.alternatives)
        }
    }

    @Test
    fun redecide_fillsFromSameClusterByQualityWhenRunnersUpRunOut() {
        val pa = randomHash("A", 2)
        val photos = listOf(
            analysis("A1", quality = 0.9f, pHash = pa),
            analysis("A2", quality = 0.8f, pHash = flip(pa, 1)),
            analysis("A3", quality = 0.7f, pHash = flip(pa, 2)),
            analysis("A4", quality = 0.6f, pHash = flip(pa, 3)),
            analysis("B1", quality = 0.75f),
        )
        val previous = DecisionEngine.decide(photos, emptyMap())
        assertEquals(listOf("A1", "B1"), previous.champions)
        val three = DecisionEngine.redecide(previous, photos, emptyMap(), StoryMode.COLLAGE_3)
        assertEquals(setOf("A1", "B1", "A2"), three.chosen.photoIds.toSet())
        val four = DecisionEngine.redecide(previous, photos, emptyMap(), StoryMode.COLLAGE_4)
        assertEquals(setOf("A1", "B1", "A2", "A3"), four.chosen.photoIds.toSet())
    }

    @Test
    fun redecide_shortage_fallsBackToLargestFeasibleMode() {
        val photos = listOf(
            analysis("A1", quality = 0.9f),
            analysis("B1", quality = 0.8f),
            analysis("R", quality = 0.99f, reject = RejectReason.BLURRY),
        )
        val previous = DecisionEngine.decide(photos, emptyMap())
        for (mode in listOf(StoryMode.COLLAGE_3, StoryMode.COLLAGE_4)) {
            val forced = DecisionEngine.redecide(previous, photos, emptyMap(), mode)
            assertEquals(StoryMode.COLLAGE_2, forced.chosen.mode)
            assertEquals(setOf("A1", "B1"), forced.chosen.photoIds.toSet())
            assertEquals(setOf(StoryMode.HERO, StoryMode.COLLAGE_2), forced.alternatives.keys)
        }

        val single = listOf(analysis("only", quality = 0.4f), analysis("R", reject = RejectReason.BLURRY))
        val singlePrevious = DecisionEngine.decide(single, emptyMap())
        val forced = DecisionEngine.redecide(singlePrevious, single, emptyMap(), StoryMode.COLLAGE_2)
        assertEquals(StoryMode.HERO, forced.chosen.mode)
        assertEquals(listOf("only"), forced.chosen.photoIds)
        assertEquals(setOf(StoryMode.HERO), forced.alternatives.keys)
    }

    @Test
    fun redecide_rejectsAnalysesMissingAPreviousId() {
        val photos = threeClusters()
        val previous = DecisionEngine.decide(photos, emptyMap())
        assertThrows(IllegalArgumentException::class.java) {
            DecisionEngine.redecide(previous, photos.filter { it.candidateId != "B2" }, emptyMap(), StoryMode.HERO)
        }
    }

    private fun assertNotNullSynergyAndScore(decision: Decision) {
        val synergy = decision.chosen.synergy!!
        assertTrue(synergy.psi in 0f..1f)
        assertTrue(decision.chosen.score > 0f)
    }

    // ---- warmth ----

    @Test
    fun warmth_everyBranch() {
        fun w(vararg cctAndL: Pair<Float, Float>) =
            DecisionEngine.warmth(cctAndL.mapIndexed { i, (k, l) -> analysis("w$i", cct = k, meanL = l) })
        assertEquals(Warmth.NIGHT_TUNGSTEN, w(3000f to 0.2f, 3400f to 0.3f))
        assertEquals(Warmth.GOLDEN_WARM, w(3000f to 0.6f))                  // warm but bright: not night
        assertEquals(Warmth.GOLDEN_WARM, w(3600f to 0.2f))                  // 3600 K is not < 3600 K
        assertEquals(Warmth.GOLDEN_WARM, w(4000f to 0.5f, 5000f to 0.5f))   // mean 4500 K
        assertEquals(Warmth.NEUTRAL, w(4800f to 0.5f))
        assertEquals(Warmth.NEUTRAL, w(6800f to 0.5f))
        assertEquals(Warmth.COOL_BLUE, w(7000f to 0.5f, 7200f to 0.5f))
        assertThrows(IllegalArgumentException::class.java) { DecisionEngine.warmth(emptyList()) }
    }
}
