package com.akane.akastudio.core.curator.engine

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.akane.akastudio.core.curator.DeviceScenes
import com.akane.akastudio.core.curator.DeviceScenes.Kind
import com.akane.akastudio.core.curator.api.CurationOption
import com.akane.akastudio.core.curator.api.CurationProgress
import com.akane.akastudio.core.curator.api.CurationResult
import com.akane.akastudio.core.curator.api.PhotoAnalysis
import com.akane.akastudio.core.curator.api.StoryMode
import com.akane.akastudio.core.curator.decision.DecisionConfig
import com.akane.akastudio.core.curator.decision.DecisionEngine
import com.akane.akastudio.core.curator.logTiming
import com.akane.akastudio.core.curator.logValue
import com.akane.akastudio.core.curator.ml.BitmapSupport
import com.akane.akastudio.core.curator.ml.ColdLoad
import com.akane.akastudio.core.curator.ml.CuratorModels
import com.akane.akastudio.core.curator.ml.CuratorModelsFactory
import com.akane.akastudio.core.curator.timed
import com.akane.akastudio.core.ingestion.model.CaptureMetadata
import com.akane.akastudio.core.ingestion.model.ImageFormat
import com.akane.akastudio.core.ingestion.model.PhotoCandidate
import com.akane.akastudio.core.ingestion.model.PhotoSource
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [DefaultCurationEngine] over device-drawn photos with every real model loaded: a three-frame burst whose sharp
 * original is the clear best of its frames, six unrelated scenes (the vivid flower field is the clear best overall),
 * one defocused and one near-black shot.
 */
@RunWith(AndroidJUnit4::class)
class CurationEndToEndDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var models: CuratorModels

    @Before
    fun setUp() {
        ColdLoad.ensureMeasured(context)
        models = CuratorModelsFactory.create(context)
        assertTrue("models degraded: ${models.degraded}", models.degraded.isEmpty())
    }

    @After
    fun tearDown() {
        models.close()
    }

    /** Photo id -> a fresh bitmap for it (the engine recycles each analysis bitmap). */
    private val photos: Map<String, () -> Bitmap> = linkedMapOf(
        BURST_SHARP to { DeviceScenes.scene(Kind.CITY, seed = 1) },
        BURST_SOFT to { DeviceScenes.blurred(DeviceScenes.scene(Kind.CITY, seed = 2, dx = 4f, dy = 2f), factor = 2) },
        BURST_JPEG to { DeviceScenes.recompressed(DeviceScenes.blurred(DeviceScenes.scene(Kind.CITY, seed = 3, dx = -4f), 2), quality = 40) },
        "sunset" to { DeviceScenes.scene(Kind.SUNSET, seed = 4) },
        "forest" to { DeviceScenes.scene(Kind.FOREST, seed = 5) },
        "beach" to { DeviceScenes.scene(Kind.BEACH, seed = 6) },
        CLEAR_BEST to { DeviceScenes.scene(Kind.FLOWERS, seed = 7) },
        "mountain" to { DeviceScenes.scene(Kind.MOUNTAIN, seed = 8) },
        "table" to { DeviceScenes.scene(Kind.TABLE, seed = 9) },
        DEFOCUSED to { DeviceScenes.blurred(DeviceScenes.scene(Kind.STRIPES, seed = 10), factor = 64) },
        NEAR_BLACK to { DeviceScenes.darkened(DeviceScenes.scene(Kind.TABLE, seed = 11), gain = 0.02f) },
    )

    private val burst = listOf(BURST_SHARP, BURST_SOFT, BURST_JPEG)

    private fun candidates(): List<PhotoCandidate> = photos.keys.mapIndexed { index, id ->
        // The burst is shot one second apart; the rest are spread over the afternoon.
        val time = if (id in burst) T0 + burst.indexOf(id) * 1_000L else T0 + (index + 1) * 20 * 60_000L
        PhotoCandidate(
            id = id,
            source = PhotoSource.ExtractedFile(path = "/device/$id.jpg", archiveEntryName = "$id.jpg", displayName = "$id.jpg"),
            format = ImageFormat.JPEG,
            byteSize = null,
            widthPx = DeviceScenes.WIDTH,
            heightPx = DeviceScenes.HEIGHT,
            metadata = CaptureMetadata.EMPTY.copy(captureEpochMillis = time),
        )
    }

    private val pixelSource = PixelSource { candidate ->
        val bitmap = photos.getValue(candidate.id)()
        AnalysisImage(bitmap, BitmapSupport.pixelsOf(bitmap))
    }

    @Test
    fun curatesBurstAndScenesIntoADecisionThatFollowsTheRule() {
        val engine = DefaultCurationEngine(pixelSource, models.asAnalysisModels())
        val input = candidates()
        val (events, ms) = timed { runBlocking { engine.curate(input).toList() } }
        logTiming("curate.total.${input.size}_photos", ms)
        logTiming("curate.per_image", ms / input.size)

        val result = checkProgress(events, input.size)
        logAnalyses(result)
        checkAnalysesInRange(result)
        checkRejectionsAndBurst(result)
        checkDecisionRule(result)
        checkSizeOrderOnRealScores(result, input)
    }

    /**
     * Option B on the real analyses: with τ lowered until every offered collage clears τ·Q₁, the first size tried
     * (the largest, m = 4 before 3 before 2) wins even when a smaller collage scores higher.
     */
    private fun checkSizeOrderOnRealScores(result: CurationResult, input: List<PhotoCandidate>) {
        val collages = result.alternatives.filterKeys { it != StoryMode.HERO }
        val q1 = result.alternatives.getValue(StoryMode.HERO).score
        if (collages.isEmpty()) return
        val lowTau = collages.values.minOf { it.score } / q1 * 0.999f
        val times = input.associate { it.id to it.metadata.captureEpochMillis }
        val decision = DecisionEngine.decide(result.analyses.values.toList(), times, DecisionConfig(tau = lowTau))
        val largest = collages.keys.maxBy { it.photoCount }
        val argmax = collages.maxBy { it.value.score }.key
        logValue("decision.lowTau", "tau=$lowTau chosen=${decision.chosen.mode} largest=$largest argmax=$argmax")
        assertEquals("m is tried 4, 3, 2: the largest clearing collage wins", largest, decision.chosen.mode)
        assertEquals(result.alternatives.getValue(largest).photoIds, decision.chosen.photoIds)
    }

    /** Analyzing(0..n) in order, then one Clustered, then exactly one Decided, last. */
    private fun checkProgress(events: List<CurationProgress>, n: Int): CurationResult {
        val analyzing = events.filterIsInstance<CurationProgress.Analyzing>()
        assertEquals((0..n).toList(), analyzing.map { it.done })
        assertTrue(analyzing.all { it.total == n })
        val decided = events.filterIsInstance<CurationProgress.Decided>()
        assertEquals("exactly one Decided", 1, decided.size)
        assertTrue("Decided must be last", events.last() is CurationProgress.Decided)
        val clustered = events.filterIsInstance<CurationProgress.Clustered>().single()
        val result = decided.single().result
        assertEquals(result.clusters.size, clustered.clusterCount)
        assertEquals(result.rejectedIds.size, clustered.rejectedCount)
        assertEquals(n, result.analyses.size)
        return result
    }

    private fun checkAnalysesInRange(result: CurationResult) {
        for (a in result.analyses.values) {
            val t = a.technical
            for ((name, v) in listOf("sharpness" to t.sharpness, "exposure" to t.exposure, "faceQuality" to t.faceQuality,
                "T" to t.total, "A" to a.aesthetic, "composition" to a.composition, "Q" to a.quality)) {
                assertTrue("${a.candidateId} $name=$v outside [0, 1]", v.isFinite() && v in 0f..1f)
            }
            assertEquals("${a.candidateId} T", 0.5f * t.sharpness + 0.3f * t.exposure + 0.2f * t.faceQuality, t.total, 1e-5f)
            assertEquals("${a.candidateId} Q = 0.45 T + 0.55 A", 0.45f * t.total + 0.55f * a.aesthetic, a.quality, 1e-5f)
            assertEquals("${a.candidateId} embedding dimension", 1024, a.embedding.size)
            // With NIMA live, A = 0.7 nima + 0.3 composition; the no-NIMA formula must not reproduce it.
            val withoutNima = PhotoAnalyzer.aestheticScore(null, a.composition, a.palette)
            assertNotEquals("${a.candidateId} aesthetic equals the no-NIMA fallback", withoutNima, a.aesthetic, 1e-6f)
            assertTrue("${a.candidateId} has faces ${a.faces}", a.faces.isEmpty())
        }
    }

    private fun checkRejectionsAndBurst(result: CurationResult) {
        assertNotNull("defocused shot passed the gate", result.analyses.getValue(DEFOCUSED).rejectReason)
        assertNotNull("near-black shot passed the gate", result.analyses.getValue(NEAR_BLACK).rejectReason)
        assertEquals(listOf(DEFOCUSED, NEAR_BLACK).sorted(), result.rejectedIds)

        val burstCluster = result.clusters.single { BURST_SHARP in it.memberIds }
        assertEquals("the burst collapses into one cluster", burst.toSet(), burstCluster.memberIds.toSet())
        assertEquals("the sharp original is the clear best of its burst", BURST_SHARP, burstCluster.championId)
        // Burst collapsed, nothing else merged: 9 usable photos, 7 clusters.
        val usable = result.analyses.size - result.rejectedIds.size
        assertEquals("clusters ${result.clusters}", usable - (burst.size - 1), result.clusters.size)
        for (cluster in result.clusters) {
            val best = cluster.memberIds.maxOf { result.analyses.getValue(it).quality }
            assertEquals("cluster ${cluster.id} champion", best, result.analyses.getValue(cluster.championId).quality, 0f)
        }
    }

    /** τ = 0.92, Q₂ ≥ 0.55, member Q ≥ 0.62, collage sizes tried 4, 3, 2 (option B); recomputed from reported values. */
    private fun checkDecisionRule(result: CurationResult) {
        val q = { id: String -> result.analyses.getValue(id).quality }
        val pool = result.clusters.map { result.analyses.getValue(it.championId) }
            .sortedWith(compareByDescending<PhotoAnalysis> { it.quality }.thenByDescending { it.aesthetic }.thenBy { it.candidateId })
        val q1 = pool[0].quality
        val q2 = pool.getOrNull(1)?.quality
        val eligible = pool.filter { it.quality >= MIN_MEMBER_Q }.map { it.candidateId }.toSet()

        val hero = result.alternatives.getValue(StoryMode.HERO)
        assertEquals(listOf(pool[0].candidateId), hero.photoIds)
        // The clear best: the sharp, vivid flower field is the top champion (real NIMA ranks it highest by a wide margin).
        assertEquals("clear best photo", CLEAR_BEST, pool[0].candidateId)
        assertEquals(q1, hero.score, 0f)

        for (mode in listOf(StoryMode.COLLAGE_2, StoryMode.COLLAGE_3, StoryMode.COLLAGE_4)) {
            val option = result.alternatives[mode]
            assertEquals("$mode offered iff ${eligible.size} eligible champions", eligible.size >= mode.photoCount, option != null)
            if (option != null) checkCollage(option, eligible, q)
        }

        val threshold = TAU * q1
        val expected = if (q2 == null || q2 < MIN_SECOND_Q) {
            StoryMode.HERO
        } else {
            listOf(StoryMode.COLLAGE_4, StoryMode.COLLAGE_3, StoryMode.COLLAGE_2)
                .firstOrNull { (result.alternatives[it]?.score ?: Float.NEGATIVE_INFINITY) >= threshold } ?: StoryMode.HERO
        }
        logValue("decision", "Q1=$q1 Q2=$q2 eligible=${eligible.size} tau*Q1=$threshold " +
            "scores=${result.alternatives.mapValues { it.value.score }} expected=$expected chosen=${result.chosen.mode}")
        assertEquals("chosen mode under the decision rule", expected, result.chosen.mode)
        assertEquals(result.alternatives.getValue(expected), result.chosen)
        if (expected != StoryMode.HERO) {
            assertTrue("chosen collage member below $MIN_MEMBER_Q", result.chosen.photoIds.all { q(it) >= MIN_MEMBER_Q })
        }
        assertEquals(result.chosen.mode, result.note.chosenMode)
        assertEquals(result.analyses.size, result.note.totalPhotos)
        assertEquals(result.clusters.size, result.note.clusterCount)
        assertEquals(result.rejectedIds.size, result.note.rejectedCount)
        assertTrue(result.note.harmonyPercent in 0..100)
    }

    private fun checkCollage(option: CurationOption, eligible: Set<String>, q: (String) -> Float) {
        val m = option.mode.photoCount
        assertEquals(m, option.photoIds.size)
        assertEquals(m, option.photoIds.toSet().size)
        assertTrue("${option.mode} uses a non-eligible photo: ${option.photoIds}", eligible.containsAll(option.photoIds))
        val s = checkNotNull(option.synergy) { "${option.mode} has no synergy" }
        for ((name, v) in listOf("color" to s.color, "rhythm" to s.rhythm, "context" to s.context, "psi" to s.psi)) {
            assertTrue("${option.mode} Ψ.$name=$v outside [0, 1]", v.isFinite() && v in 0f..1f)
        }
        assertEquals("${option.mode} Ψ", 0.40f * s.color + 0.35f * s.rhythm + 0.25f * s.context, s.psi, 1e-5f)
        val meanQ = option.photoIds.map(q).average().toFloat()
        val expected = (0.5f * meanQ + 0.5f * s.psi) * (1f - 0.03f * (m - 2))
        assertEquals("${option.mode} Score_Collage", expected, option.score, 1e-5f)
    }

    private fun logAnalyses(result: CurationResult) {
        for (a in result.analyses.values) {
            logValue(
                "analysis.${a.candidateId}",
                "T=%.4f(s=%.3f e=%.3f) A=%.4f comp=%.3f Q=%.4f scene=%s reject=%s".format(
                    a.technical.total, a.technical.sharpness, a.technical.exposure, a.aesthetic, a.composition,
                    a.quality, a.scene, a.rejectReason,
                ),
            )
        }
        logValue("clusters", result.clusters)
        for ((mode, option) in result.alternatives) logValue("option.$mode", "${option.photoIds} score=${option.score} ${option.synergy}")
    }

    private companion object {
        const val BURST_SHARP = "burst-1-sharp"
        const val BURST_SOFT = "burst-2-soft"
        const val BURST_JPEG = "burst-3-jpeg"
        const val DEFOCUSED = "reject-defocused"
        const val NEAR_BLACK = "reject-near-black"
        const val CLEAR_BEST = "flowers"
        const val T0 = 1_760_000_000_000L
        const val TAU = 0.92f
        const val MIN_SECOND_Q = 0.55f
        const val MIN_MEMBER_Q = 0.62f
    }
}
