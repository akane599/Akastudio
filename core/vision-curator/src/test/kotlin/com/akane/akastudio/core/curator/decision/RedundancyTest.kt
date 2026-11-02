package com.akane.akastudio.core.curator.decision

import com.akane.akastudio.core.curator.api.FaceObservation
import com.akane.akastudio.core.curator.decision.Fixtures.analysis
import com.akane.akastudio.core.curator.decision.Fixtures.face
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

class RedundancyTest {

    private val config = DecisionConfig()

    @Test
    fun hashTerm_isOneUpToTheTier1Distance_andZeroFrom18() {
        assertEquals(1f, Redundancy.hashTerm(0, config), 0f)
        assertEquals(1f, Redundancy.hashTerm(10, config), 0f)
        assertEquals(0.5f, Redundancy.hashTerm(14, config), 1e-6f)
        assertEquals(0f, Redundancy.hashTerm(18, config), 0f)
        assertEquals(0f, Redundancy.hashTerm(20, config), 0f)   // closest pair of different moments on the trace
    }

    @Test
    fun embeddingTerm_rampsFrom035To055() {
        assertEquals(0f, Redundancy.embeddingTerm(0.2f, config), 0f)
        assertEquals(0f, Redundancy.embeddingTerm(0.35f, config), 0f)
        assertEquals(0.5f, Redundancy.embeddingTerm(0.45f, config), 1e-5f)
        assertEquals(1f, Redundancy.embeddingTerm(0.55f, config), 1e-6f)
        assertEquals(1f, Redundancy.embeddingTerm(0.9f, config), 0f)
        assertEquals(0f, Redundancy.embeddingTerm(Float.NaN, config), 0f)
    }

    @Test
    fun timeTerm_isOneWhenUnknownOrWithinAMinute_thenDecays() {
        assertEquals(1f, Redundancy.timeTerm(null, 0L, config), 0f)
        assertEquals(1f, Redundancy.timeTerm(0L, null, config), 0f)
        assertEquals(1f, Redundancy.timeTerm(0L, 60_000L, config), 0f)
        assertEquals(1f, Redundancy.timeTerm(60_000L, 0L, config), 0f)
        assertEquals(exp(-1.0).toFloat(), Redundancy.timeTerm(0L, 360_000L, config), 1e-6f)
        assertEquals(0f, Redundancy.timeTerm(0L, 3 * 3_600_000L, config), 1e-12f)
    }

    @Test
    fun embeddingRamp_startsAt025_onlyWhenBothTimesAreKnownAndWithinTheWindow() {
        assertEquals(0.5f, Redundancy.embeddingTerm(0.40f, config, sameMoment = true), 1e-5f)
        assertEquals(0.25f, Redundancy.embeddingTerm(0.40f, config, sameMoment = false), 1e-5f)
        assertEquals(true, Redundancy.sameMoment(0L, 60_000L, config))
        assertEquals(false, Redundancy.sameMoment(0L, 61_000L, config))
        assertEquals(false, Redundancy.sameMoment(null, 0L, config))
        assertEquals(false, Redundancy.sameMoment(0L, null, config))

        val base = Fixtures.randomEmbedding("reframe")
        val a = analysis("a", embedding = base)
        val b = analysis("b", embedding = Fixtures.embeddingAt(base, 0.40f, "b"))
        assertEquals("60 s: same moment, ramp from 0.25", 0.5f, Redundancy.between(a, 0L, b, 60_000L, config), 1e-3f)
        val t61 = exp(-1.0 / 300.0).toFloat()
        assertEquals("61 s: ramp from 0.35, time decays", 0.25f * t61, Redundancy.between(a, 0L, b, 61_000L, config), 1e-3f)
        assertEquals("unknown time: ramp from 0.35", 0.25f, Redundancy.between(a, null, b, 60_000L, config), 1e-3f)
    }

    @Test
    fun faceFactor_vetoesOnlyWhenBothHaveRobustFacesAndNoPairMatches() {
        val veto = config.redundancyFaceVeto
        val face = face(cx = 0.3f, cy = 0.4f, area = 0.06f)
        fun f(a: List<FaceObservation>, b: List<FaceObservation>) = Redundancy.faceFactor(a, b, config)
        assertEquals(1f, f(emptyList(), emptyList()), 0f)
        assertEquals("faces on one side only are neutral", 1f, f(listOf(face), emptyList()), 0f)
        assertEquals("a small face on one side is no evidence", 1f, f(listOf(face), listOf(face(cx = 0.9f, area = 0.02f))), 0f)
        assertEquals(1f, f(listOf(face(area = 0.02f)), listOf(face(cx = 0.9f, area = 0.03f))), 0f)
        // Same-moment reframe on the trace: the face moved 0.38 with a width ratio of 1.31.
        assertEquals(1f, f(listOf(face), listOf(face(cx = 0.68f, cy = 0.4f, area = 0.06f * 1.31f * 1.31f))), 0f)
        // Different moments of the same people: width ratio 1.41 at the same place.
        assertEquals(veto, f(listOf(face), listOf(face(cx = 0.3f, cy = 0.4f, area = 0.06f * 1.41f * 1.41f))), 0f)
        assertEquals(veto, f(listOf(face(cx = 0.3f, cy = 0.4f, area = 0.06f * 1.41f * 1.41f)), listOf(face)), 0f)
        // Same size, too far apart.
        assertEquals(veto, f(listOf(face), listOf(face(cx = 0.8f, cy = 0.4f, area = 0.06f))), 0f)
        // Any matching pair of robust faces is enough.
        assertEquals(1f, f(listOf(face, face(cx = 0.9f, area = 0.2f)), listOf(face)), 0f)
    }

    @Test
    fun between_isTheMaxOfHashAndDiscountedEmbeddingTerms_andSymmetric() {
        val base = Fixtures.randomEmbedding("moment")
        val p = Fixtures.randomHash("moment", 1)
        val a = analysis("a", embedding = base, pHash = p, dHash = Fixtures.randomHash("a", 9))
        // cosine 0.45 (e = 0.5), hash 14 bits away (h = 0.5), unknown times.
        val b = analysis(
            "b", embedding = Fixtures.embeddingAt(base, 0.45f, "b"),
            pHash = Fixtures.atDistance(p, 14), dHash = Fixtures.randomHash("b", 9),
        )
        assertEquals(0.5f, Redundancy.between(a, null, b, null, config), 1e-3f)
        assertEquals(Redundancy.between(a, 0L, b, 10L, config), Redundancy.between(b, 10L, a, 0L, config), 0f)

        // Same content an hour apart: only the hash term remains.
        val c = analysis("c", embedding = Fixtures.embeddingAt(base, 0.7f, "c"), pHash = Fixtures.atDistance(p, 30))
        assertEquals(1f, Redundancy.between(a, null, c, null, config), 1e-6f)
        assertEquals(0f, Redundancy.between(a, 0L, c, 3 * 3_600_000L, config), 1e-6f)
    }

    @Test
    fun faceVeto_scalesOnlyTheEmbeddingTerm() {
        val base = Fixtures.randomEmbedding("moment")
        val p = Fixtures.randomHash("moment", 1)
        val faceLeft = listOf(face(cx = 0.2f))
        val faceRight = listOf(face(cx = 0.8f))
        val a = analysis("a", embedding = base, pHash = p, faces = faceLeft)
        val similar = analysis("b", embedding = Fixtures.embeddingAt(base, 0.7f, "b"), faces = faceRight)
        assertEquals(config.redundancyFaceVeto, Redundancy.between(a, null, similar, null, config), 1e-6f)
        // Hash-identical (one capture) stays fully redundant whatever the faces say.
        val copy = analysis("c", embedding = Fixtures.embeddingAt(base, 0.7f, "c"), pHash = p, faces = faceRight)
        assertEquals(1f, Redundancy.between(a, null, copy, null, config), 0f)
    }

    @Test
    fun betweenGroups_isTheMaximumOverEveryMemberPair() {
        val base = Fixtures.randomEmbedding("table")
        val c = analysis("c", embedding = Fixtures.embeddingAt(base, 0.67f, "c"))
        val champion = analysis("x1", embedding = Fixtures.embeddingAt(base, 0.70f, "x1"))   // ~0.47 to c
        val mate = analysis("x2", embedding = base)                                              // 0.67 to c
        val pairToChampion = Redundancy.between(c, null, champion, null, config)
        assertTrue("champion pair alone $pairToChampion", pairToChampion < 1f)
        assertEquals(1f, Redundancy.betweenGroups(listOf(c), listOf(champion, mate), emptyMap(), config), 0f)
        assertEquals(pairToChampion, Redundancy.betweenGroups(listOf(c), listOf(champion), emptyMap(), config), 0f)
        // Each member uses its own time: the mate an hour away no longer links c to the cluster.
        val apart = Redundancy.betweenGroups(listOf(c), listOf(champion, mate), mapOf("c" to 0L, "x1" to 0L, "x2" to 3_600_000L), config)
        assertEquals(Redundancy.between(c, 0L, champion, 0L, config), apart, 1e-5f)
        assertThrows(IllegalArgumentException::class.java) { Redundancy.betweenGroups(emptyList(), listOf(c), emptyMap(), config) }
    }

    @Test
    fun config_rejectsNonMonotoneBarsAndInvertedRamps() {
        assertThrows(IllegalArgumentException::class.java) { DecisionConfig(admitTrio = 0.6f) }
        assertThrows(IllegalArgumentException::class.java) { DecisionConfig(offerBar = 0.7f) }
        assertThrows(IllegalArgumentException::class.java) { DecisionConfig(redundancyCosineLow = 0.6f) }
        assertThrows(IllegalArgumentException::class.java) { DecisionConfig(redundancyHashFull = 18) }
        assertThrows(IllegalArgumentException::class.java) { DecisionConfig(redundancyTimeDecayMillis = 0L) }
        assertThrows(IllegalArgumentException::class.java) { DecisionConfig(faceMatchSizeRatio = 0.9f) }
        assertThrows(IllegalArgumentException::class.java) { DecisionConfig(redundancyCosineLowSameMoment = 0.4f) }
        assertThrows(IllegalArgumentException::class.java) { DecisionConfig().admissionBar(1) }
    }
}
