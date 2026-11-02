package com.akane.akastudio.core.curator.engine

import com.akane.akastudio.core.color.api.MasterLook
import com.akane.akastudio.core.color.api.SceneType
import com.akane.akastudio.core.curator.api.CurationProgress
import com.akane.akastudio.core.curator.api.CurationProgress.Analyzing
import com.akane.akastudio.core.curator.api.CurationProgress.Clustered
import com.akane.akastudio.core.curator.api.CurationProgress.Decided
import com.akane.akastudio.core.curator.api.CurationResult
import com.akane.akastudio.core.curator.api.RejectReason
import com.akane.akastudio.core.curator.api.ShotScale
import com.akane.akastudio.core.curator.api.StoryMode
import com.akane.akastudio.core.curator.api.Warmth
import com.akane.akastudio.core.curator.decision.Clustering
import com.akane.akastudio.core.curator.decision.DecisionConfig
import com.akane.akastudio.core.curator.decision.DecisionEngine
import com.akane.akastudio.core.curator.decision.Fixtures
import com.akane.akastudio.core.curator.engine.EngineImages.noise
import com.akane.akastudio.core.curator.engine.FakeWorld.Companion.face
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

class DefaultCurationEngineTest {

    private fun engine(world: FakeWorld, parallelism: Int = 3, source: PixelSource = world.source, dispatcher: CoroutineDispatcher? = null) =
        if (dispatcher == null) {
            DefaultCurationEngine(source, world.models, parallelism = parallelism)
        } else {
            DefaultCurationEngine(source, world.models, dispatcher = dispatcher, parallelism = parallelism)
        }

    private fun decided(emissions: List<CurationProgress>): CurationResult =
        (emissions.last() as Decided).result

    /**
     * Three sharp, well-exposed grey frames shot 20 s apart with one shot scale each: WIDE (24 mm, no face),
     * MEDIUM (a 5% face) and DETAIL (85 mm, a 30% face). [othersNima] is the NIMA mean of the MEDIUM and DETAIL
     * frames; the WIDE frame always scores 7.
     */
    private fun trio(othersNima: Float): FakeWorld = FakeWorld(
        linkedMapOf(
            "wide" to Shot(noise(1), nima = 7f, captureMillis = T0, focal = 24),
            "medium" to Shot(noise(2), faces = listOf(face(0.05f)), nima = othersNima, captureMillis = T0 + 20_000),
            "detail" to Shot(noise(3), faces = listOf(face(0.30f)), nima = othersNima, captureMillis = T0 + 40_000, focal = 85),
        ),
    )

    @Test
    fun curate_emitsZeroProgressThenEachAnalysisThenClusteredThenOneDecided() = runBlocking {
        val world = FakeWorld((1..5).associate { "p$it" to Shot(noise(10 + it)) })
        val emissions = engine(world, parallelism = 2).curate(world.candidates).toList()

        assertEquals((0..5).map { Analyzing(it, 5) }, emissions.take(6))
        assertEquals(Clustered(clusterCount = 5, rejectedCount = 0), emissions[6])
        assertTrue(emissions[7] is Decided)
        assertEquals(8, emissions.size)
        val result = decided(emissions)
        assertEquals(world.candidates.map { it.id }, result.analyses.keys.toList())
        assertEquals(5, result.note.totalPhotos)
        assertEquals(5, result.note.clusterCount)
    }

    @Test
    fun curate_neverRunsMoreAnalysesThanTheParallelismBound() = runTest {
        val world = FakeWorld((1..9).associate { "p$it" to Shot(noise(20 + it)) })
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        val source = PixelSource { candidate ->
            peak.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            delay(50)
            world.source.load(candidate)
        }
        val models = object : AnalysisModels by world.models {
            override suspend fun scene(img: AnalysisImage): SceneType {
                delay(50)
                return world.models.scene(img).also { inFlight.decrementAndGet() }
            }
        }
        val engine = DefaultCurationEngine(source, models, dispatcher = StandardTestDispatcher(testScheduler), parallelism = 2)

        val emissions = engine.curate(world.candidates).toList()

        assertEquals(2, peak.get())
        assertEquals(0, inFlight.get())
        assertEquals(Analyzing(9, 9), emissions[9])
    }

    @Test
    fun curate_unreadableCandidateIsRejectedAndStillCountsTowardProgress() = runBlocking {
        val world = FakeWorld(
            linkedMapOf(
                "good1" to Shot(noise(31)),
                "broken" to Shot(noise(32), failLoad = true),
                "good2" to Shot(noise(33)),
            ),
        )
        val emissions = engine(world).curate(world.candidates).toList()

        assertEquals(Analyzing(3, 3), emissions[3])
        assertEquals(Clustered(clusterCount = 2, rejectedCount = 1), emissions[4])
        val result = decided(emissions)
        val broken = result.analyses.getValue("broken")
        assertEquals(RejectReason.UNREADABLE, broken.rejectReason)
        assertEquals(0, broken.embedding.size)
        assertEquals(5, broken.palette.size)
        assertTrue(broken.palette.all { it.weight == 0.2f && it.lab.a == 0f && it.lab.b == 0f })
        assertEquals(listOf("broken"), result.rejectedIds)
        assertFalse("broken" in result.chosen.photoIds)
        assertEquals(1, result.note.rejectedCount)
    }

    @Test
    fun curate_closedEyeFaceIsRejected() = runBlocking {
        val world = FakeWorld(
            linkedMapOf(
                "open" to Shot(noise(41), faces = listOf(face(0.05f))),
                "closed" to Shot(noise(42), faces = listOf(face(0.05f, blink = 0.9f))),
            ),
        )
        val result = decided(engine(world).curate(world.candidates).toList())

        val closed = result.analyses.getValue("closed")
        assertEquals(RejectReason.EYES_CLOSED, closed.rejectReason)
        assertEquals(0.1f, closed.faces.single().leftEyeOpenness, 1e-6f)
        assertNull(result.analyses.getValue("open").rejectReason)
        assertEquals(listOf("closed"), result.rejectedIds)
        assertEquals(listOf("open"), result.chosen.photoIds)
    }

    @Test
    fun curate_sharpLowKeyAndHighKeyFramesPassTheGateButFlatFramesDoNot() = runBlocking {
        val world = FakeWorld(
            linkedMapOf(
                "night" to Shot(EngineImages.clipped(seed = 45, fraction = 0.4f, fill = 0)),
                "snow" to Shot(EngineImages.clipped(seed = 46, fraction = 0.3f, fill = 255)),
                "black" to Shot(EngineImages.flat(0)),
                "white" to Shot(EngineImages.flat(255)),
            ),
        )
        val result = decided(engine(world).curate(world.candidates).toList())

        assertNull(result.analyses.getValue("night").rejectReason)
        assertNull(result.analyses.getValue("snow").rejectReason)
        assertEquals(listOf("black", "white"), result.rejectedIds)
        // Clipping still costs ranking: the exposure score is driven to zero.
        assertEquals(0f, result.analyses.getValue("night").technical.exposure, 0f)
        assertEquals(0f, result.analyses.getValue("snow").technical.exposure, 0f)
    }

    @Test
    fun curate_oneMissingNimaScoreMovesTheWholeRunToTheFallbackAesthetic() = runBlocking {
        val shots = linkedMapOf(
            "scored1" to Shot(noise(71), nima = 8f),
            "failed" to Shot(noise(72), nima = null),
            "scored2" to Shot(noise(73), nima = 6f),
            "broken" to Shot(noise(74), failLoad = true),
        )
        val world = FakeWorld(shots)
        val result = decided(engine(world).curate(world.candidates).toList())

        for (id in listOf("scored1", "failed", "scored2")) {
            val a = result.analyses.getValue(id)
            val fallback = PhotoAnalyzer.aestheticScore(null, a.composition, a.palette)
            assertEquals(id, fallback, a.aesthetic, 0f)
            assertEquals(id, PhotoAnalyzer.quality(a.technical.total, fallback), a.quality, 0f)
        }
        assertEquals(0f, result.analyses.getValue("broken").aesthetic, 0f)

        // With every score present, the NIMA formula is kept.
        val allScored = FakeWorld(linkedMapOf("scored1" to Shot(noise(71), nima = 8f), "scored2" to Shot(noise(73), nima = 6f)))
        val kept = decided(engine(allScored).curate(allScored.candidates).toList())
        val a = kept.analyses.getValue("scored1")
        assertEquals(PhotoAnalyzer.aestheticScore(8f, a.composition, a.palette), a.aesthetic, 0f)
    }

    @Test
    fun curate_threeNearDuplicatesCollapseToOneCluster() = runBlocking {
        val base = noise(51)
        val world = FakeWorld(
            linkedMapOf(
                "burst1" to Shot(base),
                "burst2" to Shot(EngineImages.nudged(base, 100)),
                "burst3" to Shot(EngineImages.nudged(base, 4000)),
                "other" to Shot(noise(52)),
            ),
        )
        val emissions = engine(world).curate(world.candidates).toList()

        assertEquals(Clustered(clusterCount = 2, rejectedCount = 0), emissions[5])
        val clusters = decided(emissions).clusters
        val burst = clusters.single { it.memberIds.size == 3 }
        assertEquals(setOf("burst1", "burst2", "burst3"), burst.memberIds.toSet())
        assertEquals(listOf("other"), clusters.single { it !== burst }.memberIds)
    }

    @Test
    fun curate_harmoniousTrioOfDistinctScalesBecomesCollage3() = runBlocking {
        val world = trio(othersNima = 7f)
        val result = decided(engine(world).curate(world.candidates).toList())

        assertEquals(StoryMode.COLLAGE_3, result.chosen.mode)
        assertEquals(setOf("wide", "medium", "detail"), result.chosen.photoIds.toSet())
        val analyses = result.analyses
        assertEquals(ShotScale.WIDE, analyses.getValue("wide").shotScale)
        assertEquals(ShotScale.MEDIUM, analyses.getValue("medium").shotScale)
        assertEquals(ShotScale.DETAIL, analyses.getValue("detail").shotScale)
        assertEquals(SceneType.GENERAL, analyses.getValue("medium").scene)
        assertEquals(SceneType.PORTRAIT, analyses.getValue("detail").scene)

        val note = result.note
        assertEquals(3, note.totalPhotos)
        assertEquals(3, note.clusterCount)
        assertEquals(0, note.rejectedCount)
        assertEquals(StoryMode.COLLAGE_3, note.chosenMode)
        assertEquals(result.chosen.photoIds.map { analyses.getValue(it).shotScale }, note.scales)
        assertEquals(Warmth.NEUTRAL, note.warmth)
        assertEquals((100 * result.chosen.synergy!!.psi).roundToInt(), note.harmonyPercent)
        val anchor = analyses.getValue(result.chosen.photoIds.first())
        assertEquals(CurationResults.suggestedLook(anchor), result.suggestedLook)
        assertEquals(CurationResults.dominantScene(result.chosen.photoIds.map(analyses::getValue)), note.dominantScene)
    }

    @Test
    fun curate_trioWithWeakSecondaryFramesBecomesHero() = runBlocking {
        val world = trio(othersNima = 3f)
        val result = decided(engine(world).curate(world.candidates).toList())

        assertEquals(StoryMode.HERO, result.chosen.mode)
        assertEquals(listOf("wide"), result.chosen.photoIds)
        val q1 = result.analyses.getValue("wide").quality
        assertEquals(q1, result.chosen.score, 0f)
        assertEquals((100 * q1).roundToInt(), result.note.harmonyPercent)
        assertEquals(listOf(ShotScale.WIDE), result.note.scales)
        assertEquals(SceneType.GENERAL, result.note.dominantScene)
        assertEquals(MasterLook.PURE_NATURAL_PRO, result.suggestedLook)
        // The weak frames bring less than the pair bar (admitPair * Q1) of new content.
        val bar = DecisionConfig().admitPair * q1
        assertTrue(result.analyses.getValue("medium").quality < bar)
        assertTrue(result.analyses.getValue("detail").quality < bar)
    }

    @Test
    fun decide_forcedModeRebuildsChoiceNoteAndLook() = runBlocking {
        val world = trio(othersNima = 7f)
        val engine = engine(world)
        val collage = decided(engine.curate(world.candidates).toList())

        val hero = engine.decide(collage, StoryMode.HERO)
        assertEquals(StoryMode.HERO, hero.chosen.mode)
        assertEquals(1, hero.chosen.photoIds.size)
        assertEquals(StoryMode.HERO, hero.note.chosenMode)
        assertEquals(listOf(hero.analyses.getValue(hero.chosen.photoIds[0]).shotScale), hero.note.scales)
        assertEquals((100 * hero.chosen.score).roundToInt(), hero.note.harmonyPercent)
        assertEquals(CurationResults.suggestedLook(hero.analyses.getValue(hero.chosen.photoIds[0])), hero.suggestedLook)
        assertEquals(collage.clusters, hero.clusters)
        assertEquals(collage.analyses, hero.analyses)
        assertEquals(collage.alternatives, hero.alternatives)   // SQ-67: a switch never re-scores the options

        val pair = engine.decide(hero, StoryMode.COLLAGE_2)
        assertEquals(StoryMode.COLLAGE_2, pair.chosen.mode)
        assertEquals(2, pair.chosen.photoIds.size)
        assertEquals(2, pair.note.scales.size)
        assertEquals((100 * pair.chosen.synergy!!.psi).roundToInt(), pair.note.harmonyPercent)

        val tooMany = engine.decide(hero, StoryMode.COLLAGE_4)
        assertEquals(StoryMode.COLLAGE_3, tooMany.chosen.mode)

        val automatic = engine.decide(pair, null)
        assertEquals(collage.chosen, automatic.chosen)
        assertEquals(collage.note, automatic.note)
    }

    @Test
    fun curate_cancelledMidAnalysisEmitsNoDecidedAndLeavesNoAnalysisRunning() = runTest {
        val world = FakeWorld(
            linkedMapOf(
                "fast1" to Shot(noise(61)),
                "slow1" to Shot(noise(62)),
                "fast2" to Shot(noise(63)),
                "slow2" to Shot(noise(64)),
            ),
        )
        val started = AtomicInteger()
        val stopped = AtomicInteger()
        val source = PixelSource { candidate ->
            if (candidate.id.startsWith("slow")) {
                started.incrementAndGet()
                try {
                    awaitCancellation()
                } finally {
                    stopped.incrementAndGet()
                }
            }
            world.source.load(candidate)
        }
        val engine = engine(world, parallelism = 4, source = source, dispatcher = StandardTestDispatcher(testScheduler))
        val emissions = mutableListOf<CurationProgress>()

        val job = launch { engine.curate(world.candidates).collect { emissions += it } }
        advanceUntilIdle()
        assertEquals(Analyzing(2, 4), emissions.last())
        assertEquals(2, started.get())

        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(2, stopped.get())
        assertTrue(emissions.none { it is Decided || it is Clustered })
    }

    @Test
    fun curate_cancelledDuringTheDecisionStopsAtTheNextCheckpointAndEmitsNoDecided() = runTest {
        // 200 unrelated photos (distinct 1024-d embeddings and noise), analysed instantly: the decision then faces
        // 19,900 clustering pairs. The probe cancels the collector at the fifth checkpoint, which is inside the first
        // row of the pair loop: decide entry, clustering entry, row 0, then its pairs j = 64 and j = 128.
        val n = 200
        val world = FakeWorld(
            (0 until n).associate { "p$it" to Shot(noise(1_000 + it), embedding = Fixtures.randomEmbedding("p$it", 1024)) },
        )
        val stopAt = 3 + 128 / Clustering.CHECKPOINT_STRIDE
        val probes = AtomicInteger()
        val emissions = mutableListOf<CurationProgress>()
        var failure: Throwable? = null

        val job = launch(Dispatchers.Default) {
            val collector = coroutineContext.job
            val engine = DefaultCurationEngine(
                world.source, world.models, Dispatchers.Default, parallelism = 3, config = DecisionConfig(),
                onDecisionCheckpoint = { if (probes.incrementAndGet() == stopAt) collector.cancel() },
            )
            try {
                engine.curate(world.candidates).collect { emissions += it }
            } catch (e: CancellationException) {
                failure = e
                throw e
            }
        }
        job.join()

        assertTrue(job.isCancelled)
        assertTrue(failure is CancellationException)
        // The checkpoint that saw the cancellation threw: no further pair, palette or subset work ran.
        assertEquals(stopAt, probes.get())
        assertEquals((0..n).map { Analyzing(it, n) }, emissions)
        assertTrue(emissions.none { it is Clustered || it is Decided })
    }

    // ---- Capture times are owned by each result (AUD-06) ----

    @Test
    fun curate_failedRunsLeaveNoEngineState_aLaterResultCarriesExactlyItsOwnCaptureTimes() = runBlocking {
        val failing = (0 until 20).flatMap { batch ->
            (0 until 5).map { k -> "fail$batch-$k" to Shot(noise(500 + 5 * batch + k), captureMillis = T0 + batch, failLoad = true) }
        }
        val world = FakeWorld(
            linkedMapOf(*failing.toTypedArray()) + linkedMapOf(
                "good1" to Shot(noise(91), captureMillis = T0 + 5_000),
                "good2" to Shot(noise(92), captureMillis = null),
            ),
        )
        val engine = engine(world)
        val byId = world.candidates.associateBy { it.id }
        for (batch in 0 until 20) {
            val candidates = (0 until 5).map { byId.getValue("fail$batch-$it") }
            assertThrows(NoUsablePhotosException::class.java) { runBlocking { engine.curate(candidates).toList() } }
        }

        val result = decided(engine.curate(listOf(byId.getValue("good1"), byId.getValue("good2"))).toList())

        assertEquals(mapOf("good1" to T0 + 5_000, "good2" to null), result.captureTimes)
        // An unknown capture time stays an explicit null entry, never a borrowed or default timestamp.
        assertTrue(result.captureTimes.containsKey("good2"))
        assertNull(result.captureTimes.getValue("good2"))
        assertEquals(result.captureTimes, engine.decide(result, null).captureTimes)
    }

    @Test
    fun decide_reDecidesWithTheResultsOwnCaptureTimes_notThoseOfALaterRunWithTheSameIds() = runBlocking {
        // Three unrelated frames. Run A shoots x and y together; run B, same ids and pixels, shoots x and z together.
        // Each run's own drawer reflects its times, and a forced COLLAGE_2 on a result that does not offer it
        // searches every champion pair with that result's capture-time term.
        val world = FakeWorld(
            linkedMapOf(
                "x" to Shot(noise(81), nima = 1f),
                "y" to Shot(noise(82), nima = 1f),
                "z" to Shot(noise(83), nima = 1f),
            ),
        )
        val day = 24 * 3_600_000L
        val timesA = mapOf("x" to T0, "y" to T0 + 60_000, "z" to T0 + day)
        val timesB = mapOf("x" to T0, "y" to T0 + day, "z" to T0 + 60_000)
        fun run(times: Map<String, Long>) = world.candidates.map {
            it.copy(metadata = it.metadata.copy(captureEpochMillis = times.getValue(it.id)))
        }
        val engine = engine(world)

        val a = decided(engine.curate(run(timesA)).toList())
        val b = decided(engine.curate(run(timesB)).toList())

        assertEquals(timesA, a.captureTimes)
        assertEquals(timesB, b.captureTimes)
        // z is a day away in run A (x and y a day away in run B): beyond the event span, so only the pair is offered.
        assertEquals(setOf("x", "y"), a.alternatives.getValue(StoryMode.COLLAGE_2).photoIds.toSet())
        assertEquals(setOf("x", "z"), b.alternatives.getValue(StoryMode.COLLAGE_2).photoIds.toSet())

        // A hero-only drawer, so COLLAGE_2 is not offered and the forced switch has to search.
        val hero = a.alternatives.getValue(StoryMode.HERO)
        val heroOnly = a.copy(chosen = hero, alternatives = mapOf(StoryMode.HERO to hero))
        val previous = CurationResults.decisionOf(heroOnly)
        val analyses = heroOnly.analyses.values.toList()
        val withA = DecisionEngine.redecide(previous, analyses, timesA, StoryMode.COLLAGE_2).chosen
        val withB = DecisionEngine.redecide(previous, analyses, timesB, StoryMode.COLLAGE_2).chosen
        assertNotEquals("the capture-time term must decide the pair in this setup", withA, withB)

        val forced = engine.decide(heroOnly, StoryMode.COLLAGE_2)

        assertEquals(withA, forced.chosen)
        assertEquals(timesA, forced.captureTimes)
        // Every redecision path keeps the result's timestamps: an offered mode and the automatic re-decision.
        assertEquals(timesA, engine.decide(forced, StoryMode.HERO).captureTimes)
        assertEquals(timesA, engine.decide(forced, null).captureTimes)
        assertEquals(a.chosen, engine.decide(forced, null).chosen)
    }

    @Test
    fun curate_emptyInputThrowsAtCollection() {
        val world = FakeWorld(emptyMap())
        val flow = engine(world).curate(emptyList())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { flow.toList() } }
    }

    @Test
    fun curate_allRejectedThrowsAfterClustered() = runBlocking {
        val world = FakeWorld(
            linkedMapOf(
                "blink1" to Shot(noise(71), faces = listOf(face(0.05f, blink = 1f))),
                "blink2" to Shot(noise(72), faces = listOf(face(0.05f, blink = 0.95f))),
                "broken" to Shot(noise(73), failLoad = true),
            ),
        )
        val emissions = mutableListOf<CurationProgress>()
        var failure: Throwable? = null
        engine(world).curate(world.candidates).catch { failure = it }.collect { emissions += it }

        assertEquals((0..3).map { Analyzing(it, 3) } + Clustered(clusterCount = 0, rejectedCount = 3), emissions)
        val error = failure as NoUsablePhotosException
        assertEquals(3, error.rejectedCount)
    }

    private companion object {
        const val T0 = 1_700_000_000_000L
    }
}
