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
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

class DecisionEngineTest {

    private val hour = 3_600_000L
    private val far = Fixtures.solid(0.9f, 0.2f, 0.1f)   // ~0.46 OKLab away from the grey default
    private val config = DecisionConfig()

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

    /** A distinct second photo that brings less than admitPair * Q1 is offered in the drawer but never chosen. */
    @Test
    fun secondChampionBelowTheAdmissionBar_isHero_butThePairIsOffered() {
        val photos = listOf(
            analysis("a", quality = 0.6f, scale = ShotScale.WIDE),
            analysis("b", quality = 0.38f, scale = ShotScale.MEDIUM),   // bar 0.65 * 0.6 = 0.39; offer bar 0.30
        )
        val decision = DecisionEngine.decide(photos, times("a" to 0L, "b" to 0L))
        assertEquals(StoryMode.HERO, decision.chosen.mode)
        assertEquals(listOf("a"), decision.chosen.photoIds)
        assertEquals(setOf("a", "b"), decision.alternatives.getValue(StoryMode.COLLAGE_2).photoIds.toSet())

        // The same pair with b just above the bar is chosen: the bar alone decided.
        val stronger = photos.map { if (it.candidateId == "b") analysis("b", quality = 0.40f, scale = ShotScale.MEDIUM) else it }
        assertEquals(StoryMode.COLLAGE_2, DecisionEngine.decide(stronger, times("a" to 0L, "b" to 0L)).chosen.mode)
    }

    /** Three strong distinct photos plus a weak distinct fourth (0.62 < admitQuad * Q1 = 0.64): COLLAGE_3. */
    @Test
    fun strongTrio_becomesCollage3_whenTheFourthBringsTooLittle() {
        val photos = listOf(
            analysis("a", quality = 0.80f, scale = ShotScale.WIDE),
            analysis("b", quality = 0.78f, scale = ShotScale.MEDIUM),
            analysis("c", quality = 0.76f, scale = ShotScale.DETAIL),
            analysis("d", quality = 0.62f, scale = ShotScale.WIDE, palette = far, cct = 2500f),
        )
        val decision = DecisionEngine.decide(photos, photos.associate { it.candidateId to 0L })
        assertEquals(StoryMode.COLLAGE_3, decision.chosen.mode)
        assertEquals(setOf("a", "b", "c"), decision.chosen.photoIds.toSet())
        // The quad stays in the drawer (d clears the offer bar 0.40) but is not chosen.
        assertEquals(setOf("a", "b", "c", "d"), decision.alternatives.getValue(StoryMode.COLLAGE_4).photoIds.toSet())
        // The size penalty does not arbitrate between sizes: the best pair scores higher but the trio wins.
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
        // Unrelated photos repeat nothing, so the mean unique quality is the mean Q (0.77).
        assertEquals(((0.5 * 0.77 + 0.5 * synergy.psi) * 0.94).toFloat(), decision.chosen.score, 1e-5f)
    }

    /** Photos 12 h apart are another event: no set mixing them is chosen or offered, so the same-event pair wins. */
    @Test
    fun onlyAPairWithinOneEvent_pairWins() {
        val photos = listOf(
            analysis("a", quality = 0.80f, scale = ShotScale.WIDE),
            analysis("b", quality = 0.78f, scale = ShotScale.MEDIUM),
            analysis("c", quality = 0.70f, scale = ShotScale.DETAIL, palette = far, cct = 2500f),
            analysis("d", quality = 0.70f, scale = ShotScale.DETAIL, palette = far, cct = 2500f),
        )
        val decision = DecisionEngine.decide(
            photos,
            times("a" to 0L, "b" to 0L, "c" to 12 * hour, "d" to 12 * hour),
        )
        assertEquals(setOf(StoryMode.HERO, StoryMode.COLLAGE_2), decision.alternatives.keys)
        assertEquals(StoryMode.COLLAGE_2, decision.chosen.mode)
        assertEquals(setOf("a", "b"), decision.chosen.photoIds.toSet())
    }

    /** Distinct fillers below admitPair * Q1 (0.6175) never join an outstanding photo automatically. */
    @Test
    fun outstandingHero_beatsMediocreCollages() {
        val photos = listOf(
            analysis("a", quality = 0.95f, cct = 3000f),
            analysis("b", quality = 0.58f, palette = far, cct = 5500f),
            analysis("c", quality = 0.57f, palette = Fixtures.solid(0.2f, -0.2f, 0.15f), cct = 8000f),
        )
        val decision = DecisionEngine.decide(photos, emptyMap())
        assertEquals(StoryMode.HERO, decision.chosen.mode)
        assertEquals(listOf("a"), decision.chosen.photoIds)
        assertEquals(0.95f, decision.chosen.score, 0f)
        // Both fillers clear the offer bar (0.475), so the drawer still offers the collages.
        assertEquals(setOf(StoryMode.HERO, StoryMode.COLLAGE_2, StoryMode.COLLAGE_3), decision.alternatives.keys)
    }

    /** The offer bar is relative to Q1: a photo below offerBar * Q1 is never offered; above it, offered, not chosen. */
    @Test
    fun memberBelowTheOfferBar_isExcludedFromEveryOption() {
        val photos = listOf(
            analysis("a", quality = 0.80f, scale = ShotScale.WIDE),
            analysis("b", quality = 0.78f, scale = ShotScale.MEDIUM),
            analysis("c", quality = 0.38f, scale = ShotScale.DETAIL),   // offer bar 0.5 * 0.80 = 0.40
        )
        val captured = photos.associate { it.candidateId to 0L }
        val decision = DecisionEngine.decide(photos, captured)
        assertEquals(setOf(StoryMode.HERO, StoryMode.COLLAGE_2), decision.alternatives.keys)
        assertFalse("c" in allIds(decision))
        assertEquals(StoryMode.COLLAGE_2, decision.chosen.mode)

        // In a weaker set the same photo clears the offer bar (0.30) but not admitTrio * Q1 (0.432).
        val weaker = listOf(
            analysis("a", quality = 0.60f, scale = ShotScale.WIDE),
            analysis("b", quality = 0.58f, scale = ShotScale.MEDIUM),
            photos[2],
        )
        val weak = DecisionEngine.decide(weaker, captured)
        assertEquals(StoryMode.COLLAGE_2, weak.chosen.mode)
        assertTrue("c" in weak.alternatives.getValue(StoryMode.COLLAGE_3).photoIds)
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

    // ---- Unique quality, gates and layout fit ----

    /** Four reframed frames of one moment: pairwise cosine ~0.72 (tier 2 sees them as one moment, tier 1 does not). */
    private fun oneMomentFrames(): List<PhotoAnalysis> {
        val moment = Fixtures.randomEmbedding("table-moment")
        val scales = listOf(ShotScale.WIDE, ShotScale.MEDIUM, ShotScale.DETAIL, ShotScale.MEDIUM)
        return List(4) { i ->
            analysis(
                "t$i",
                quality = 0.80f - 0.01f * i,
                aesthetic = 0.6f - 0.05f * i,
                embedding = Fixtures.embeddingAt(moment, 0.85f, "t$i"),
                scale = scales[i],
            )
        }
    }

    @Test
    fun nearIdenticalFramesWithHighPsi_loseToHero_orToADiversePair() {
        val frames = oneMomentFrames()
        val captured = frames.mapIndexed { i, p -> p.candidateId to i * 9_000L }.toMap<String, Long?>()
        assertEquals("tier 1 keeps them apart", 4, DecisionEngine.decide(frames, captured).clusters.size)
        // Same light, palette and moment, varied scales: the old score clears the old 0.92 * Q1 bar easily.
        val oldScore = Synergy.collageScore(frames, Synergy.evaluate(frames, frames.map { captured[it.candidateId] }))
        assertTrue("old score $oldScore", oldScore >= 0.92f * 0.80f)

        val alone = DecisionEngine.decide(frames, captured)
        assertEquals(StoryMode.HERO, alone.chosen.mode)
        assertEquals(listOf("t0"), alone.chosen.photoIds)
        assertEquals("a repeated moment is not even offered", setOf(StoryMode.HERO), alone.alternatives.keys)

        val diverse = analysis("d", quality = 0.70f, palette = far, cct = 3000f, scale = ShotScale.DETAIL)
        val mixed = DecisionEngine.decide(frames + diverse, captured + ("d" to hour))
        assertEquals(StoryMode.COLLAGE_2, mixed.chosen.mode)
        assertEquals(setOf("t0", "d"), mixed.chosen.photoIds.toSet())
        for (option in mixed.alternatives.values) {
            assertTrue("$option repeats the moment", option.photoIds.count { it.startsWith("t") } <= 1)
        }
    }

    @Test
    fun sameCaptureInTwoFormats_andAReencodedCrop_neverAppearTwice() {
        val emb = Fixtures.randomEmbedding("capture")
        val p = randomHash("capture", 1)
        val d = randomHash("capture", 2)
        val photos = listOf(
            analysis("cap.heic", quality = 0.72f, embedding = emb, pHash = p, dHash = d),
            analysis("cap.jpg", quality = 0.721f, embedding = emb, pHash = p, dHash = d),
            // An edited copy: hashes 13 bits off (beyond tier 1) and cosine 0.75 (below clusterCosine).
            analysis(
                "cap-crop.jpg", quality = 0.73f, embedding = Fixtures.embeddingAt(emb, 0.75f, "crop"),
                pHash = Fixtures.atDistance(p, 13), dHash = Fixtures.atDistance(d, 13),
            ),
            analysis("other1", quality = 0.70f, scale = ShotScale.MEDIUM),
            analysis("other2", quality = 0.69f, scale = ShotScale.DETAIL),
        )
        val decision = DecisionEngine.decide(photos, emptyMap())
        assertEquals(1, decision.clusters.count { it.memberIds.containsAll(listOf("cap.heic", "cap.jpg")) })
        for (option in decision.alternatives.values) {
            assertTrue("$option", option.photoIds.count { it.startsWith("cap") } <= 1)
        }
        assertEquals(StoryMode.COLLAGE_3, decision.chosen.mode)
    }

    @Test
    fun unknownCaptureTimes_doNotMakeBurstFramesNovel() {
        val emb = Fixtures.randomEmbedding("burst-moment")
        val photos = listOf(
            analysis("f1", quality = 0.80f, embedding = emb),
            analysis("f2", quality = 0.78f, embedding = Fixtures.embeddingAt(emb, 0.6f, "f2"), scale = ShotScale.MEDIUM),
        )
        for (captured in listOf(emptyMap(), times("f1" to 0L, "f2" to null), times("f1" to 0L, "f2" to 30_000L))) {
            val decision = DecisionEngine.decide(photos, captured)
            assertEquals("$captured", StoryMode.HERO, decision.chosen.mode)
            assertEquals("$captured", setOf(StoryMode.HERO), decision.alternatives.keys)
        }
        // Known times an hour apart: a different moment despite the similar content.
        val apart = DecisionEngine.decide(photos, times("f1" to 0L, "f2" to hour))
        assertEquals(StoryMode.COLLAGE_2, apart.chosen.mode)
    }

    @Test
    fun screenCapture_isNeverChosenNorOffered_butStaysAChampion() {
        val screen = analysis("scr", quality = 0.95f, screenCapture = true, palette = far)
        val photos = listOf(
            screen,
            analysis("a", quality = 0.80f, scale = ShotScale.WIDE),
            analysis("b", quality = 0.78f, scale = ShotScale.MEDIUM),
            analysis("c", quality = 0.76f, scale = ShotScale.DETAIL),
        )
        val decision = DecisionEngine.decide(photos, emptyMap())
        assertFalse("scr" in allIds(decision))
        assertEquals(listOf("a"), decision.alternatives.getValue(StoryMode.HERO).photoIds)
        assertEquals(0.80f, decision.alternatives.getValue(StoryMode.HERO).score, 0f)
        assertEquals(StoryMode.COLLAGE_3, decision.chosen.mode)
        assertTrue("scr" in decision.champions)

        val heroOnly = DecisionEngine.decide(listOf(screen, photos[1]), emptyMap())
        assertEquals(listOf("a"), heroOnly.chosen.photoIds)
        assertEquals(setOf(StoryMode.HERO), heroOnly.alternatives.keys)
        // A forced collage avoids it while enough other photos exist.
        val forced = DecisionEngine.redecide(decision, photos, emptyMap(), StoryMode.COLLAGE_3)
        assertFalse("scr" in forced.chosen.photoIds)

        // Only screen captures left: the hero still exists.
        assertEquals(listOf("scr"), DecisionEngine.decide(listOf(screen), emptyMap()).chosen.photoIds)
    }

    @Test
    fun eventSpanOverSixHours_isNeitherChosenNorOffered() {
        val photos = listOf(
            analysis("a", quality = 0.80f, scale = ShotScale.WIDE),
            analysis("b", quality = 0.78f, scale = ShotScale.MEDIUM),
        )
        val split = DecisionEngine.decide(photos, times("a" to 0L, "b" to 7 * hour))
        assertEquals(StoryMode.HERO, split.chosen.mode)
        assertEquals(setOf(StoryMode.HERO), split.alternatives.keys)
        assertEquals(StoryMode.COLLAGE_2, DecisionEngine.decide(photos, times("a" to 0L, "b" to 5 * hour)).chosen.mode)
        assertEquals(StoryMode.COLLAGE_2, DecisionEngine.decide(photos, times("a" to 0L, "b" to null)).chosen.mode)
    }

    /** a .80 (aesthetic .5), b .78 (.6), c .76 (.4), d .74 (.9): unrelated, so every size is admissible. */
    private fun strongQuad(): List<PhotoAnalysis> = listOf(
        analysis("a", quality = 0.80f, aesthetic = 0.5f, scale = ShotScale.WIDE),
        analysis("b", quality = 0.78f, aesthetic = 0.6f, scale = ShotScale.MEDIUM),
        analysis("c", quality = 0.76f, aesthetic = 0.4f, scale = ShotScale.DETAIL),
        analysis("d", quality = 0.74f, aesthetic = 0.9f, scale = ShotScale.WIDE),
    )

    private fun catalog(vararg counts: Int) = LayoutCatalog(
        counts.map { n -> LayoutSpec("L$n", List(n) { CellShape.Aspect(1f) }) },
    )

    @Test
    fun anchorStaysInSlot0_whenTheFitterReordersTheOtherSlots() {
        val reversing = LayoutFitter { photos, catalog ->
            val layout = catalog.forCount(photos.size).first()
            val ids = photos.map { it.candidateId }
            LayoutFit(layout.id, listOf(ids.first()) + ids.drop(1).reversed(), 1f)
        }
        val config = DecisionConfig(layoutCatalog = catalog(1, 2, 3, 4), layoutFitter = reversing)
        val decision = DecisionEngine.decide(strongQuad(), emptyMap(), config)
        assertEquals(StoryMode.COLLAGE_4, decision.chosen.mode)
        // Anchor order would be d, a, b, c; the fitter keeps d in slot 0 and reverses the rest.
        assertEquals(listOf("d", "c", "b", "a"), decision.chosen.photoIds)
        assertEquals("L4", decision.chosen.layoutId)
        assertEquals("L1", decision.alternatives.getValue(StoryMode.HERO).layoutId)

        val movingAnchor = LayoutFitter { photos, catalog ->
            LayoutFit(catalog.forCount(photos.size).first().id, photos.map { it.candidateId }.reversed(), 1f)
        }
        assertThrows(IllegalStateException::class.java) {
            DecisionEngine.decide(strongQuad(), emptyMap(), config.copy(layoutFitter = movingAnchor))
        }
    }

    @Test
    fun fitterNullForACount_skipsThatMode_andNullForASet_skipsThatSet() {
        val noQuads = LayoutFitter { photos, catalog ->
            if (photos.size == 4) null else LayoutFitter.AlwaysFits.fit(photos, catalog)
        }
        val config = DecisionConfig(layoutCatalog = catalog(2, 3, 4), layoutFitter = noQuads)
        val decision = DecisionEngine.decide(strongQuad(), emptyMap(), config)
        assertEquals(StoryMode.COLLAGE_3, decision.chosen.mode)
        assertFalse(StoryMode.COLLAGE_4 in decision.alternatives)
        assertEquals("L3", decision.chosen.layoutId)
        assertNull("no one-photo layout in the catalogue", decision.alternatives.getValue(StoryMode.HERO).layoutId)

        // Rejecting the best trio's exact set makes the next-best trio the choice.
        val best = decision.chosen.photoIds.toSet()
        val notThatTrio = LayoutFitter { photos, catalog ->
            if (photos.size == 4 || photos.map { it.candidateId }.toSet() == best) null else LayoutFitter.AlwaysFits.fit(photos, catalog)
        }
        val next = DecisionEngine.decide(strongQuad(), emptyMap(), config.copy(layoutFitter = notThatTrio))
        assertEquals(StoryMode.COLLAGE_3, next.chosen.mode)
        assertTrue(next.chosen.photoIds.toSet() != best)
    }

    @Test
    fun catalogWithoutLayoutsForACount_isLegacyFeasible_withANullLayoutId() {
        val nothingFits = LayoutFitter { _, _ -> null }
        val config = DecisionConfig(layoutCatalog = catalog(2), layoutFitter = nothingFits)
        val decision = DecisionEngine.decide(strongQuad(), emptyMap(), config)
        assertEquals(StoryMode.COLLAGE_4, decision.chosen.mode)
        assertNull(decision.chosen.layoutId)
        assertEquals(listOf("d", "a", "b", "c"), decision.chosen.photoIds)
        assertFalse("the catalogue has pairs and none fits", StoryMode.COLLAGE_2 in decision.alternatives)

        val legacy = DecisionEngine.decide(strongQuad(), emptyMap())
        assertTrue(legacy.alternatives.values.all { it.layoutId == null })
    }

    /**
     * The real set's wide table frame links to the table moment only through a non-champion: cluster X = {X1, X2}
     * (joined by hash), and C has cosine 0.67 to X2 but only 0.22 to the champion X1 (below even the same-moment ramp
     * start 0.25). Champion-level r would let C in
     * as a second table shot; cluster-level r keeps it out.
     */
    @Test
    fun redundancy_isTakenOverWholeClusters_notJustTheChampions() {
        val x2Embedding = Fixtures.randomEmbedding("x2")
        val cEmbedding = Fixtures.embeddingAt(x2Embedding, 0.67f, "c")
        val p = randomHash("table", 1)
        val photos = listOf(
            analysis("X1", quality = 0.80f, embedding = Fixtures.embeddingAt(cEmbedding, 0.22f, "x1"), pHash = p),
            analysis("X2", quality = 0.72f, embedding = x2Embedding, pHash = flip(p, 4), scale = ShotScale.MEDIUM),
            analysis("C", quality = 0.78f, embedding = cEmbedding, scale = ShotScale.DETAIL),
        )
        val captured = times("X1" to 0L, "X2" to 10_000L, "C" to 22_000L)
        val decision = DecisionEngine.decide(photos, captured)
        assertEquals(listOf("X1", "C"), decision.champions)
        assertEquals(setOf("X1", "X2"), decision.clusters.single { it.championId == "X1" }.memberIds.toSet())
        assertEquals(
            "the champions alone look like different moments",
            0f, Redundancy.between(photos[0], 0L, photos[2], 22_000L, config), 0f,
        )
        assertEquals(StoryMode.HERO, decision.chosen.mode)
        assertEquals(setOf(StoryMode.HERO), decision.alternatives.keys)
    }

    /** A burst of three where the middle frame misses its small (0.02-area) face is still one moment. */
    @Test
    fun faceCountFlickerInABurst_staysOneMoment() {
        val moment = Fixtures.randomEmbedding("series")
        val small = Fixtures.face(cx = 0.45f, cy = 0.4f, area = 0.02f)
        val photos = listOf(
            analysis("s1", quality = 0.73f, embedding = Fixtures.embeddingAt(moment, 0.8f, "s1"), faces = listOf(small)),
            analysis("s2", quality = 0.72f, embedding = Fixtures.embeddingAt(moment, 0.8f, "s2"), scale = ShotScale.MEDIUM),
            analysis(
                "s3", quality = 0.71f, embedding = Fixtures.embeddingAt(moment, 0.8f, "s3"), scale = ShotScale.DETAIL,
                faces = listOf(Fixtures.face(cx = 0.5f, cy = 0.42f, area = 0.021f)),
            ),
        )
        val captured = times("s1" to 0L, "s2" to 3_000L, "s3" to 6_000L)
        val decision = DecisionEngine.decide(photos, captured)
        assertEquals("tier 1 keeps the frames apart", 3, decision.champions.size)
        assertEquals(StoryMode.HERO, decision.chosen.mode)
        assertEquals(setOf(StoryMode.HERO), decision.alternatives.keys)
    }

    /** A corrupt analysis (non-finite quality) fails closed: never hero, never a member, reported as rejected. */
    @Test
    fun nonFiniteQuality_isNeitherHeroNorMember_andCountsAsRejected() {
        val p = randomHash("burst", 3)
        val photos = listOf(
            analysis("nan", quality = Float.NaN, aesthetic = 0.9f),
            analysis("inf", quality = Float.POSITIVE_INFINITY),
            analysis("a", quality = 0.80f, scale = ShotScale.WIDE, pHash = p),
            analysis("aNan", quality = Float.NaN, pHash = flip(p, 1)),   // same burst as a: a stays its champion
            analysis("b", quality = 0.78f, scale = ShotScale.MEDIUM),
            analysis("c", quality = 0.76f, scale = ShotScale.DETAIL),
        )
        val decision = DecisionEngine.decide(photos, emptyMap())
        val bad = setOf("nan", "inf", "aNan")
        assertTrue(allIds(decision).none { it in bad })
        assertTrue(decision.clusters.flatMap { it.memberIds }.none { it in bad })
        assertEquals(listOf("aNan", "inf", "nan"), decision.rejectedIds)
        assertEquals(listOf("a"), decision.alternatives.getValue(StoryMode.HERO).photoIds)
        assertEquals(StoryMode.COLLAGE_3, decision.chosen.mode)
        assertEquals(setOf("a", "b", "c"), decision.chosen.photoIds.toSet())
        repeat(4) { seed -> assertEquals(decision, DecisionEngine.decide(photos.shuffled(Random(seed)), emptyMap())) }

        // Nothing finite left: the documented all-rejected error, never a NaN hero.
        val error = assertThrows(IllegalStateException::class.java) {
            DecisionEngine.decide(listOf(analysis("x", quality = Float.NaN), analysis("y", quality = Float.NEGATIVE_INFINITY)), emptyMap())
        }
        assertEquals("all candidates rejected", error.message)
    }

    /** Capture times at the ends of the Long range: the span overflows a Long and must count as exceeding 6 h. */
    @Test
    fun extremeCaptureTimes_doNotOverflowTheEventSpanGate() {
        val photos = listOf(
            analysis("a", quality = 0.80f, scale = ShotScale.WIDE),
            analysis("b", quality = 0.78f, scale = ShotScale.MEDIUM),
            analysis("c", quality = 0.76f, scale = ShotScale.DETAIL),
        )
        val decision = DecisionEngine.decide(photos, times("a" to Long.MIN_VALUE, "b" to Long.MAX_VALUE, "c" to Long.MAX_VALUE))
        // Only b and c (same instant) may be together.
        for (option in decision.alternatives.values) {
            assertFalse("$option", "a" in option.photoIds && option.photoIds.size > 1)
        }
        assertEquals(setOf(StoryMode.HERO, StoryMode.COLLAGE_2), decision.alternatives.keys)
        assertEquals(setOf("b", "c"), decision.alternatives.getValue(StoryMode.COLLAGE_2).photoIds.toSet())
        assertEquals(0f, Redundancy.timeTerm(Long.MIN_VALUE, Long.MAX_VALUE, config), 0f)
    }

    /**
     * The six table frames of the 2026-11-02 trace: measured cosines, quality, aesthetic and capture offsets, no
     * faces. 210007 and 210010 are one burst (hash distance 6); 210001 is the wide reframe, only 0.40-0.47 from the
     * others except 0.67 to 210010. Hashes are otherwise random (the real minimum distances are 16 or more).
     */
    private fun sixTableFrames(): List<PhotoAnalysis> {
        val ids = listOf("t205957", "t210001", "t210007", "t210010", "t210023", "t210025")
        val cosines = listOf(
            listOf(1.0, 0.442, 0.808, 0.695, 0.773, 0.736),
            listOf(0.442, 1.0, 0.511, 0.672, 0.472, 0.402),
            listOf(0.808, 0.511, 1.0, 0.819, 0.719, 0.703),
            listOf(0.695, 0.672, 0.819, 1.0, 0.670, 0.622),
            listOf(0.773, 0.472, 0.719, 0.670, 1.0, 0.806),
            listOf(0.736, 0.402, 0.703, 0.622, 0.806, 1.0),
        )
        val quality = listOf(0.67809f, 0.7237f, 0.72337f, 0.72296f, 0.76449f, 0.71977f)
        val aesthetic = listOf(0.52106f, 0.49764f, 0.57269f, 0.53034f, 0.58005f, 0.53814f)
        val embeddings = Fixtures.embeddingsWithCosines(cosines)
        val burst = randomHash("t210007", 2)
        return ids.indices.map { i ->
            analysis(
                ids[i], quality = quality[i], aesthetic = aesthetic[i], embedding = embeddings[i],
                pHash = when (ids[i]) {
                    "t210007" -> burst
                    "t210010" -> flip(burst, 6)
                    else -> randomHash(ids[i], 2)
                },
                cct = 7500f,
            )
        }
    }

    private val sixTableTimes = times(
        "t205957" to 0L, "t210001" to 4_000L, "t210007" to 10_000L,
        "t210010" to 13_000L, "t210023" to 26_000L, "t210025" to 28_000L,
    )

    @Test
    fun tablesOnly_withKnownTimes_neverShowTheTableTwice() {
        val decision = DecisionEngine.decide(sixTableFrames(), sixTableTimes)
        assertEquals(5, decision.clusters.size)
        assertEquals(StoryMode.HERO, decision.chosen.mode)
        assertEquals(listOf("t210023"), decision.chosen.photoIds)
        assertEquals("no option shows two frames of the table", setOf(StoryMode.HERO), decision.alternatives.keys)
    }

    /**
     * Known limit: without capture times the standard ramp (from 0.35) applies, and the wide reframe 210001 at cosine
     * 0.40 to 210025 counts as only 26 % redundant, so this pair is admitted (gain 0.53 >= 0.65 * Q1 = 0.497). Every
     * other pair of the table stays out.
     */
    @Test
    fun tablesOnly_withUnknownTimes_admitsOnlyTheWideReframePair_knownLimit() {
        val decision = DecisionEngine.decide(sixTableFrames(), emptyMap())
        // Known limit: without times the wide reframe pair (cosine 0.40, gain 0.53 >= 0.497) is admitted. A later
        // fix may turn this into HERO; what must never happen is any other pair or a larger set of the table.
        val allowed = setOf(listOf("t210023"), listOf("t210025", "t210001"))
        assertTrue(decision.chosen.photoIds.toString(), decision.chosen.photoIds in allowed)
        assertTrue(decision.alternatives.keys.toString(), setOf(StoryMode.HERO, StoryMode.COLLAGE_2).containsAll(decision.alternatives.keys))
        for (option in decision.alternatives.values) {
            assertTrue(option.photoIds.toString(), option.photoIds in allowed)
        }
    }

    @Test
    fun prunedSearch_matchesBruteForce_onRandomFixtures() {
        repeat(12) { seed -> assertMatchesBruteForce(randomEvening(seed)) }
    }

    @Test
    fun decision_withFacesTimesAndScreenCaptures_isDeterministicUnderInputShuffling() {
        repeat(3) { fixture ->
            val (photos, captured) = randomEvening(100 + fixture)
            val byId = LayoutFitter { photos2, catalog ->
                val ids = photos2.map { it.candidateId }
                LayoutFit("L${ids.size}", listOf(ids.first()) + ids.drop(1).sorted(), 1f)
                    .takeIf { catalog.forCount(ids.size).isNotEmpty() }
            }
            val config = DecisionConfig(layoutCatalog = catalog(1, 2, 3, 4), layoutFitter = byId)
            val reference = DecisionEngine.decide(photos, captured, config)
            repeat(6) { seed ->
                assertEquals(reference, DecisionEngine.decide(photos.shuffled(Random(seed)), captured, config))
            }
        }
    }

    /**
     * A random evening: four moments with 1-4 frames each (frames of one moment at cosine ~0.5-0.8 to each other,
     * some faces, some unknown times), a screen capture, and qualities spread over 0.3..0.9.
     */
    private fun randomEvening(seed: Int): Pair<List<PhotoAnalysis>, Map<String, Long?>> {
        val random = Random(seed)
        val photos = mutableListOf<PhotoAnalysis>()
        val captured = mutableMapOf<String, Long?>()
        repeat(4) { m ->
            val moment = Fixtures.randomEmbedding("s$seed-m$m")
            val start = m * 40 * 60_000L + if (m == 3 && random.nextBoolean()) 7 * hour else 0L
            repeat(1 + random.nextInt(4)) { f ->
                val id = "s$seed-m$m-f$f"
                photos += analysis(
                    id,
                    quality = 0.3f + random.nextFloat() * 0.6f,
                    aesthetic = random.nextFloat(),
                    embedding = Fixtures.embeddingAt(moment, 0.72f + random.nextFloat() * 0.18f, id),
                    scale = ShotScale.entries[random.nextInt(3)],
                    cct = 3000f + random.nextFloat() * 4000f,
                    palette = Fixtures.solid(random.nextFloat(), random.nextFloat() * 0.2f - 0.1f, 0f),
                    faces = if (random.nextBoolean()) listOf(Fixtures.face(random.nextFloat(), 0.4f, 0.03f + random.nextFloat() * 0.05f)) else emptyList(),
                )
                captured[id] = if (random.nextInt(4) == 0) null else start + f * 20_000L
            }
        }
        photos += analysis("s$seed-scr", quality = 0.3f + random.nextFloat() * 0.6f, screenCapture = true)
        return photos to captured
    }

    /** Re-derives the documented rule by brute force over every subset and compares it with [DecisionEngine.decide]. */
    private fun assertMatchesBruteForce(fixture: Pair<List<PhotoAnalysis>, Map<String, Long?>>) {
        val (photos, captured) = fixture
        val decision = DecisionEngine.decide(photos, captured)
        val byId = photos.associateBy { it.candidateId }
        val pool = decision.champions.map(byId::getValue).filterNot { it.screenCapture }
        val clusterOf = decision.clusters.associate { it.championId to it.memberIds.map(byId::getValue) }
        val q1 = pool.first().quality

        fun subsets(m: Int, from: Int = 0): List<List<Int>> =
            if (m == 0) listOf(emptyList()) else (from until pool.size).flatMap { i -> subsets(m - 1, i + 1).map { listOf(i) + it } }

        val expected = linkedMapOf<StoryMode, Pair<Set<String>, Boolean>>()
        for (mode in listOf(StoryMode.COLLAGE_2, StoryMode.COLLAGE_3, StoryMode.COLLAGE_4)) {
            data class Row(val ids: List<String>, val score: Float, val admissible: Boolean)
            val rows = subsets(mode.photoCount).mapNotNull { indices ->
                val members = indices.map { pool[it] }
                val t = members.map { captured[it.candidateId] }
                val gains = members.indices.map { k ->
                    val worst = (0 until k).maxOfOrNull { j ->
                        // Cluster level, by brute force over every member pair of the two clusters.
                        clusterOf.getValue(members[k].candidateId).maxOf { x ->
                            clusterOf.getValue(members[j].candidateId).maxOf { y ->
                                Redundancy.between(x, captured[x.candidateId], y, captured[y.candidateId], config)
                            }
                        }
                    } ?: 0f
                    members[k].quality * (1f - worst)
                }
                val known = t.filterNotNull()
                val spanOk = known.size < 2 || known.max() - known.min() <= config.maxEventSpanMillis
                if (!spanOk || gains.drop(1).any { it < config.offerBar * q1 }) return@mapNotNull null
                val synergy = Synergy.evaluate(members, t)
                val score = Synergy.combineScore(gains.sumOf { it.toDouble() } / gains.size, synergy.psi, members.size)
                Row(members.map { it.candidateId }.sorted(), score, gains.drop(1).all { it >= config.admissionBar(mode.photoCount) * q1 })
            }
            val order = compareByDescending<Row> { it.score }.thenComparator { a, b -> DecisionEngine.compareIdLists(a.ids, b.ids) }
            val best = rows.filter { it.admissible }.minWithOrNull(order) ?: rows.minWithOrNull(order) ?: continue
            expected[mode] = best.ids.toSet() to best.admissible
        }
        val actual = decision.alternatives.filterKeys { it != StoryMode.HERO }.mapValues { it.value.photoIds.toSet() }
        assertEquals(expected.mapValues { it.value.first }, actual)
        val chosen = expected.entries.lastOrNull { it.value.second }?.key ?: StoryMode.HERO
        assertEquals(chosen, decision.chosen.mode)
        assertEquals(listOf(pool.first().candidateId), decision.alternatives.getValue(StoryMode.HERO).photoIds)
    }

    // ---- redecide ----

    /** Clusters A = {A1 .9, A2 .85}, B = {B1 .8, B2 .7}, C = {C1 .4}; R rejected. C1 is below the offer bar (0.45). */
    private fun threeClusters(): List<PhotoAnalysis> {
        val pa = randomHash("A", 1)
        val pb = randomHash("B", 1)
        return listOf(
            analysis("A1", quality = 0.9f, scale = ShotScale.WIDE, pHash = pa),
            analysis("A2", quality = 0.85f, scale = ShotScale.WIDE, pHash = flip(pa, 1)),
            analysis("B1", quality = 0.8f, scale = ShotScale.MEDIUM, pHash = pb),
            analysis("B2", quality = 0.7f, scale = ShotScale.MEDIUM, pHash = flip(pb, 1)),
            analysis("C1", quality = 0.4f, scale = ShotScale.DETAIL),
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
     * C1 is 30 h away from the rest, so no set with it is offered; D1 .6 clears the offer bar (0.45) but not admitTrio
     * (0.648), so the curation offers HERO, 2 and 3 and chooses 2.
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
    fun redecide_forcedCollages_useAllChampionsIgnoringTheBars_thenFill() {
        val photos = threeClusters()
        val previous = DecisionEngine.decide(photos, emptyMap())

        val two = DecisionEngine.redecide(previous, photos, emptyMap(), StoryMode.COLLAGE_2)
        assertEquals(StoryMode.COLLAGE_2, two.chosen.mode)
        assertEquals(setOf("A1", "B1"), two.chosen.photoIds.toSet())

        val three = DecisionEngine.redecide(previous, photos, emptyMap(), StoryMode.COLLAGE_3)
        assertEquals(StoryMode.COLLAGE_3, three.chosen.mode)
        assertEquals(setOf("A1", "B1", "C1"), three.chosen.photoIds.toSet())   // C1 (Q 0.4) despite the offer bar

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

    // ---- Cancellation checkpoints (AUD-05) ----

    /** Ten unrelated eligible champions, so every 2..4 subset of the pool is enumerated. */
    private fun tenChampions(): List<PhotoAnalysis> = List(10) { i ->
        analysis("c$i", quality = 0.70f + 0.02f * i, scale = ShotScale.entries[i % 3], cct = 4000f + 300f * i)
    }

    /** Checkpoints of one full [DecisionEngine.decide] over n = 10 unrelated eligible photos. */
    private val subsetCheckpoints = 45 + 120 + 210                  // C(10, 2) + C(10, 3) + C(10, 4)
    // entry, clustering, palette pairs, champion redundancy pairs, subsets
    private val decideCheckpoints = 1 + (1 + 10) + 45 + 45 + subsetCheckpoints

    private fun cancellingAt(stopAt: Int, counter: IntArray): () -> Unit = {
        if (++counter[0] == stopAt) throw CancellationException("collector cancelled")
    }

    @Test
    fun decide_checkpointCoversClusteringPalettePairsAndEverySubset_withoutChangingTheDecision() {
        val photos = tenChampions()
        val reference = DecisionEngine.decide(photos, emptyMap())
        val counter = IntArray(1)

        assertEquals(reference, DecisionEngine.decide(photos, emptyMap(), checkpoint = { counter[0]++ }))
        assertEquals(10, reference.champions.size)
        assertEquals(decideCheckpoints, counter[0])
    }

    @Test
    fun decide_checkpointThrowingMidSubsetEnumeration_stopsTheDecision() {
        val photos = tenChampions()
        // The last 375 checkpoints are the enumerated subsets; stop deep inside them.
        val stopAt = decideCheckpoints - 105
        val counter = IntArray(1)

        assertThrows(CancellationException::class.java) {
            DecisionEngine.decide(photos, emptyMap(), checkpoint = cancellingAt(stopAt, counter))
        }
        assertEquals(stopAt, counter[0])
    }

    @Test
    fun redecide_automatic_checkpointThrowingMidSubsetEnumeration_stopsTheDecision() {
        val photos = tenChampions()
        val previous = DecisionEngine.decide(photos, emptyMap())
        val total = IntArray(1)
        assertEquals(
            previous,
            DecisionEngine.redecide(previous, photos, emptyMap(), forcedMode = null, checkpoint = { total[0]++ }),
        )
        assertEquals(1 + decideCheckpoints, total[0])

        val stopAt = total[0] - 105
        val counter = IntArray(1)
        assertThrows(CancellationException::class.java) {
            DecisionEngine.redecide(previous, photos, emptyMap(), forcedMode = null, checkpoint = cancellingAt(stopAt, counter))
        }
        assertEquals(stopAt, counter[0])
    }

    @Test
    fun redecide_forcedUnofferedCollage_checkpointThrowingMidSubsetEnumeration_stopsTheDecision() {
        val photos = tenChampions()
        val decided = DecisionEngine.decide(photos, emptyMap())
        val heroOnly = decided.copy(
            chosen = decided.alternatives.getValue(StoryMode.HERO),
            alternatives = mapOf(StoryMode.HERO to decided.alternatives.getValue(StoryMode.HERO)),
        )
        val total = IntArray(1)
        val forced = DecisionEngine.redecide(heroOnly, photos, emptyMap(), StoryMode.COLLAGE_4, checkpoint = { total[0]++ })
        assertEquals(decided.alternatives.getValue(StoryMode.COLLAGE_4), forced.chosen)
        assertEquals(1 + 45 + 45 + 210, total[0])   // entry, palette pairs, redundancy pairs, 4-photo subsets

        val stopAt = total[0] - 105
        val counter = IntArray(1)
        assertThrows(CancellationException::class.java) {
            DecisionEngine.redecide(heroOnly, photos, emptyMap(), StoryMode.COLLAGE_4, checkpoint = cancellingAt(stopAt, counter))
        }
        assertEquals(stopAt, counter[0])
    }
}
