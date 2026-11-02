package com.akane.akastudio.core.curator.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.akane.akastudio.core.curator.DeviceScenes
import com.akane.akastudio.core.curator.DeviceScenes.Kind
import com.akane.akastudio.core.curator.api.CurationOption
import com.akane.akastudio.core.curator.api.CurationProgress
import com.akane.akastudio.core.curator.api.CurationResult
import com.akane.akastudio.core.curator.api.PhotoAnalysis
import com.akane.akastudio.core.curator.api.SceneCluster
import com.akane.akastudio.core.curator.api.StoryMode
import com.akane.akastudio.core.curator.decision.CellFit
import com.akane.akastudio.core.curator.decision.CellShape
import com.akane.akastudio.core.curator.decision.DecisionConfig
import com.akane.akastudio.core.curator.decision.DecisionEngine
import com.akane.akastudio.core.curator.decision.LayoutCatalog
import com.akane.akastudio.core.curator.decision.LayoutSpec
import com.akane.akastudio.core.curator.decision.Redundancy
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [DefaultCurationEngine] over device-drawn photos with every real model loaded: a three-frame burst whose sharp
 * original is the clear best of its frames, six unrelated scenes (the vivid flower field is the clear best overall),
 * one defocused and one near-black shot; plus a burst-only pool and a pool with a synthetic phone screenshot.
 *
 * The decision checks assert the selection rule's structure on the real analyses (gains recomputed with the engine's
 * own [Redundancy] and the [DecisionConfig] tunables), never model-dependent score margins or which scene wins.
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
        SCREENSHOT to { screenshot() },
    )

    /** The main fixture: every photo except the screenshot, which has its own test. */
    private val mainIds = photos.keys.filter { it != SCREENSHOT }

    private val burst = listOf(BURST_SHARP, BURST_SOFT, BURST_JPEG)

    private fun candidates(ids: List<String> = mainIds): List<PhotoCandidate> = ids.map { id ->
        // The burst is shot one second apart; the rest are spread over the afternoon, 20 minutes apart.
        val index = photos.keys.indexOf(id)
        val time = if (id in burst) T0 + burst.indexOf(id) * 1_000L else T0 + (index + 1) * 20 * 60_000L
        PhotoCandidate(
            id = id,
            source = PhotoSource.ExtractedFile(path = "/device/$id.jpg", archiveEntryName = "$id.jpg", displayName = "$id.jpg"),
            format = ImageFormat.JPEG,
            byteSize = null,
            widthPx = if (id == SCREENSHOT) SCREEN_W else DeviceScenes.WIDTH,
            heightPx = if (id == SCREENSHOT) SCREEN_H else DeviceScenes.HEIGHT,
            metadata = CaptureMetadata.EMPTY.copy(captureEpochMillis = time),
        )
    }

    private val pixelSource = PixelSource { candidate ->
        val bitmap = photos.getValue(candidate.id)()
        AnalysisImage(bitmap, BitmapSupport.pixelsOf(bitmap))
    }

    private fun engine(config: DecisionConfig = DecisionConfig()) =
        DefaultCurationEngine(pixelSource, models.asAnalysisModels(), config = config)

    private fun curate(ids: List<String>): CurationResult {
        val events = runBlocking { engine().curate(candidates(ids)).toList() }
        return checkProgress(events, ids.size)
    }

    @Test
    fun curatesBurstAndScenesIntoADecisionThatFollowsTheRule() {
        val engine = engine()
        val input = candidates()
        val (events, ms) = timed { runBlocking { engine.curate(input).toList() } }
        logTiming("curate.total.${input.size}_photos", ms)
        logTiming("curate.per_image", ms / input.size)

        val result = checkProgress(events, input.size)
        logAnalyses(result)
        checkAnalysesInRange(result)
        checkRejectionsAndBurst(result)
        checkNote(result)
        val config = DecisionConfig()
        checkRuleStructure("default", result.chosen, result.alternatives, result.clusters, result, config)
        checkExistenceByBruteForce("default", result.chosen, result.alternatives, result.clusters, result, config)
        checkRedecideOnRealAnalyses(result, config)
        checkLayoutCatalogues(result)
        checkForcedModes(engine, result)
    }

    /** One cluster of near-identical frames (the burst alone): nothing can join the hero, so HERO is chosen. */
    @Test
    fun aBurstAloneIsAHero() {
        val result = curate(burst)
        assertEquals("the burst collapses into one cluster: ${result.clusters}", 1, result.clusters.size)
        assertEquals(StoryMode.HERO, result.chosen.mode)
        assertEquals("only HERO is offered for one cluster", setOf(StoryMode.HERO), result.alternatives.keys)
        assertEquals(listOf(result.clusters.single().championId), result.chosen.photoIds)
        checkRuleStructure("burst", result.chosen, result.alternatives, result.clusters, result, DecisionConfig())

        // A forced pair is still built from the burst's frames (runners-up fill in), and only adds its own mode.
        val forced = engine().decide(result, StoryMode.COLLAGE_2)
        assertEquals(StoryMode.COLLAGE_2, forced.chosen.mode)
        assertEquals(2, forced.chosen.photoIds.toSet().size)
        assertTrue("forced pair uses a non-burst id: ${forced.chosen.photoIds}", burst.containsAll(forced.chosen.photoIds))
        assertEquals(result.alternatives.getValue(StoryMode.HERO), forced.alternatives.getValue(StoryMode.HERO))
        assertEquals(setOf(StoryMode.HERO, StoryMode.COLLAGE_2), forced.alternatives.keys)
    }

    /** A sharp phone screenshot among real scenes is flagged, kept out of every pick, but stays a cluster champion. */
    @Test
    fun aScreenshotIsNeverPicked() {
        val ids = listOf(SCREENSHOT, "sunset", "forest", "beach", CLEAR_BEST)
        val result = curate(ids)
        logAnalyses(result)
        val shot = result.analyses.getValue(SCREENSHOT)
        // Fixture preconditions: the screenshot survives the gate and is detected on the device pixel path.
        assertNull("screenshot rejected: ${shot.rejectReason}", shot.rejectReason)
        assertTrue("screenshot not detected as a screen capture", shot.screenCapture)
        assertTrue("screenshot is its own cluster", result.clusters.any { it.championId == SCREENSHOT })
        for ((mode, option) in result.alternatives) {
            assertFalse("$mode offers the screenshot: ${option.photoIds}", SCREENSHOT in option.photoIds)
        }
        assertFalse(SCREENSHOT in result.chosen.photoIds)
        checkRuleStructure("screenshot", result.chosen, result.alternatives, result.clusters, result, DecisionConfig())
        checkExistenceByBruteForce("screenshot", result.chosen, result.alternatives, result.clusters, result, DecisionConfig())
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
        // The real scenes are camera-like (grain, no flat UI panels): none may be flagged as a screen capture.
        for (id in mainIds - listOf(DEFOCUSED, NEAR_BLACK)) {
            assertFalse("$id flagged as a screen capture", result.analyses.getValue(id).screenCapture)
        }
    }

    private fun checkNote(result: CurationResult) {
        assertEquals(result.chosen.mode, result.note.chosenMode)
        assertEquals(result.analyses.size, result.note.totalPhotos)
        assertEquals(result.clusters.size, result.note.clusterCount)
        assertEquals(result.rejectedIds.size, result.note.rejectedCount)
        assertTrue(result.note.harmonyPercent in 0..100)
    }

    /** The decision pool exactly as the rule defines it: non-screen-capture champions in pool order, top N. */
    private class Pool(clusters: List<SceneCluster>, result: CurationResult, config: DecisionConfig) {
        val champions: List<PhotoAnalysis> = clusters.map { result.analyses.getValue(it.championId) }.sortedWith(POOL_ORDER)
        val members: List<PhotoAnalysis> = champions.filterNot { it.screenCapture }.take(config.subsetPoolSize)
        val q1: Float = (champions.firstOrNull { !it.screenCapture } ?: champions.first()).quality
        private val index = members.withIndex().associate { it.value.candidateId to it.index }
        private val groups = clusters.associate { c -> c.championId to c.memberIds.map { result.analyses.getValue(it) } }
        fun indexOf(id: String): Int? = index[id]

        /** Every member of the tier-1 cluster that [championId] champions, in cluster order. */
        fun groupOf(championId: String): List<PhotoAnalysis> = groups.getValue(championId)
    }

    /**
     * Unique-quality gains of [ids] taken in pool order: gain_1 = Q_1, gain_k = Q_k · (1 − max_{j<k} r), with r the
     * cluster-level redundancy (see [redundancy]).
     */
    private fun gains(ids: List<String>, pool: Pool, times: Map<String, Long?>, config: DecisionConfig): FloatArray {
        val ordered = ids.map { pool.members[checkNotNull(pool.indexOf(it)) { "$it is not in the pool" }] }
            .sortedBy { pool.indexOf(it.candidateId) }
        return FloatArray(ordered.size) { k ->
            var worst = 0f
            for (j in 0 until k) {
                worst = maxOf(worst, redundancy(ordered[j], ordered[k], pool, times, config))
            }
            ordered[k].quality * (1f - worst)
        }
    }

    /**
     * r between the champions [earlier] and [later] (pool order) as the engine computes it: the maximum of the pairwise
     * [Redundancy] over every member pair of their two tier-1 clusters.
     */
    private fun redundancy(
        earlier: PhotoAnalysis,
        later: PhotoAnalysis,
        pool: Pool,
        times: Map<String, Long?>,
        config: DecisionConfig,
    ): Float = Redundancy.betweenGroups(pool.groupOf(earlier.candidateId), pool.groupOf(later.candidateId), times, config)

    private fun spanOk(ids: List<String>, times: Map<String, Long?>, config: DecisionConfig): Boolean {
        val known = ids.mapNotNull { times[it] }
        return known.isEmpty() || known.max() - known.min() <= config.maxEventSpanMillis
    }

    private fun passes(gains: FloatArray, bar: Float): Boolean = (1 until gains.size).all { gains[it] >= bar }

    /**
     * The rule's structure on one decision:
     * - HERO is the top non-screen-capture champion, scored Q1, no synergy.
     * - Every offered collage uses m distinct pool photos (champions of distinct clusters, never rejected or a screen
     *   capture), its anchor (slot 0) is the highest-aesthetic member, slots 1+ follow quality unless a layout
     *   reordered them, its layout id belongs to the catalogue's layouts for m (null without any), its span fits the
     *   event window, every gain_k clears offerBar·Q1 (so no pair of clusters is redundant beyond 1 − offerBar), and its score
     *   is Score_Collage over the mean gain.
     * - The chosen option is the alternative for its mode; a chosen collage clears its admission bar φ_m·Q1 (so no
     *   chosen pair has r above 1 − φ_m); every larger mode's alternative is absent or inadmissible.
     */
    private fun checkRuleStructure(
        label: String,
        chosen: CurationOption,
        alternatives: Map<StoryMode, CurationOption>,
        clusters: List<SceneCluster>,
        result: CurationResult,
        config: DecisionConfig,
    ) {
        val times = result.captureTimes
        val pool = Pool(clusters, result, config)
        val q1 = pool.q1
        val clusterOf = clusters.flatMap { c -> c.memberIds.map { it to c.id } }.toMap()

        val hero = checkNotNull(alternatives[StoryMode.HERO]) { "$label: HERO is always offered" }
        val heroPhoto = pool.champions.firstOrNull { !it.screenCapture } ?: pool.champions.first()
        assertEquals("$label: HERO is the top non-screen-capture champion", listOf(heroPhoto.candidateId), hero.photoIds)
        assertEquals("$label: HERO score is Q1", q1, hero.score, 0f)
        assertNull("$label: HERO has no synergy", hero.synergy)
        assertTrue("$label: HERO layout ${hero.layoutId} not in the catalogue",
            hero.layoutId == null || hero.layoutId in config.layoutCatalog.forCount(1).map { it.id })

        val admissible = HashMap<StoryMode, Boolean>()
        for (mode in COLLAGE_MODES) {
            val option = alternatives[mode] ?: continue
            val m = mode.photoCount
            val ids = option.photoIds
            assertEquals("$label $mode size", m, ids.size)
            assertEquals("$label $mode distinct ids", m, ids.toSet().size)
            for (id in ids) {
                val a = result.analyses.getValue(id)
                assertNull("$label $mode uses rejected $id", a.rejectReason)
                assertFalse("$label $mode uses screen capture $id", a.screenCapture)
                assertNotNull("$label $mode uses $id outside the champion pool", pool.indexOf(id))
            }
            assertEquals("$label $mode one photo per cluster", m, ids.map { clusterOf.getValue(it) }.toSet().size)

            val members = ids.map { result.analyses.getValue(it) }
            val anchor = members.sortedWith(ANCHOR_ORDER).first()
            assertEquals("$label $mode anchor is the highest-aesthetic member", anchor.candidateId, ids.first())
            if (option.layoutId == null) {
                assertTrue("$label $mode has no layout although the catalogue has layouts for $m",
                    config.layoutCatalog.forCount(m).isEmpty())
                val rest = members.drop(1)
                assertEquals("$label $mode slots 1+ by quality", rest.sortedWith(BY_QUALITY_THEN_ID), rest)
            } else {
                assertTrue("$label $mode layout ${option.layoutId} is not a catalogue layout for $m",
                    option.layoutId in config.layoutCatalog.forCount(m).map { it.id })
            }

            assertTrue("$label $mode spans more than the event window", spanOk(ids, times, config))
            val g = gains(ids, pool, times, config)
            logValue("decision.$label.$mode", "ids=$ids gains=${g.toList()} q1=$q1 layout=${option.layoutId} score=${option.score}")
            assertTrue("$label $mode gains ${g.toList()} below offerBar·Q1=${config.offerBar * q1}", passes(g, config.offerBar * q1))
            for (i in ids.indices) for (j in i + 1 until ids.size) {
                val (x, y) = listOf(members[i], members[j]).sortedBy { pool.indexOf(it.candidateId) }
                val r = redundancy(x, y, pool, times, config)
                assertTrue("$label $mode pair ${x.candidateId}/${y.candidateId} r=$r above 1 − offerBar", r <= 1f - config.offerBar + 1e-6f)
            }

            val s = checkNotNull(option.synergy) { "$label $mode has no synergy" }
            for ((name, v) in listOf("color" to s.color, "rhythm" to s.rhythm, "context" to s.context, "psi" to s.psi)) {
                assertTrue("$label $mode Ψ.$name=$v outside [0, 1]", v.isFinite() && v in 0f..1f)
            }
            assertEquals("$label $mode Ψ", 0.40f * s.color + 0.35f * s.rhythm + 0.25f * s.context, s.psi, 1e-5f)
            val meanGain = g.map { it.toDouble() }.average()
            val expected = ((0.5 * meanGain + 0.5 * s.psi) * (1.0 - 0.03 * (m - 2))).toFloat()
            assertEquals("$label $mode Score_Collage over the mean unique-quality gain", expected, option.score, 1e-5f)
            admissible[mode] = passes(g, config.admissionBar(m) * q1)
        }

        assertEquals("$label: chosen is the offered alternative for its mode", alternatives[chosen.mode], chosen)
        if (chosen.mode != StoryMode.HERO) {
            val m = chosen.mode.photoCount
            val phi = config.admissionBar(m)
            assertEquals("$label: chosen ${chosen.mode} clears φ·Q1=${phi * q1}", true, admissible[chosen.mode])
            val members = chosen.photoIds.map { result.analyses.getValue(it) }.sortedBy { pool.indexOf(it.candidateId) }
            for (i in members.indices) for (j in i + 1 until members.size) {
                val r = redundancy(members[i], members[j], pool, times, config)
                assertTrue("$label: chosen pair ${members[i].candidateId}/${members[j].candidateId} r=$r breaks φ=$phi",
                    r <= 1f - phi + 1e-6f)
            }
        }
        for (mode in COLLAGE_MODES.filter { it.photoCount > chosen.mode.photoCount }) {
            assertNotEquals("$label: $mode is admissible but ${chosen.mode} was chosen (largest admissible m wins)", true, admissible[mode])
        }
        logValue("decision.$label", "q1=$q1 chosen=${chosen.mode} ${chosen.photoIds} offered=${alternatives.keys} admissible=$admissible")
    }

    /**
     * The pruned search is exact on the real analyses: over every m-subset of the pool (at most 35 here), a mode is
     * offered iff some subset is offerable and fits, its alternative is admissible iff some subset is admissible and
     * fits, and the chosen mode is the largest with an admissible subset. Only for a catalogue whose layouts always
     * fit (or none), so existence does not depend on crops.
     */
    private fun checkExistenceByBruteForce(
        label: String,
        chosen: CurationOption,
        alternatives: Map<StoryMode, CurationOption>,
        clusters: List<SceneCluster>,
        result: CurationResult,
        config: DecisionConfig,
    ) {
        val pool = Pool(clusters, result, config)
        val times = result.captureTimes
        var largestAdmissible = StoryMode.HERO
        for (mode in COLLAGE_MODES) {
            var offerable = false
            var admissible = false
            for (subset in subsets(pool.members.indices.toList(), mode.photoCount)) {
                val ids = subset.map { pool.members[it].candidateId }
                if (!spanOk(ids, times, config)) continue
                val g = gains(ids, pool, times, config)
                if (passes(g, config.offerBar * pool.q1)) offerable = true
                if (passes(g, config.admissionBar(mode.photoCount) * pool.q1)) admissible = true
            }
            logValue("bruteforce.$label.$mode", "offerable=$offerable admissible=$admissible")
            assertEquals("$label: $mode offered iff an offerable set exists", offerable, mode in alternatives)
            if (admissible) largestAdmissible = mode
        }
        assertEquals("$label: chosen mode is the largest with an admissible set", largestAdmissible, chosen.mode)
    }

    private fun subsets(items: List<Int>, m: Int): List<List<Int>> = when {
        m == 0 -> listOf(emptyList())
        items.size < m -> emptyList()
        else -> subsets(items.drop(1), m - 1).map { listOf(items[0]) + it } + subsets(items.drop(1), m)
    }

    /** The engine's own decision on the device analyses is deterministic and order-independent. */
    private fun checkRedecideOnRealAnalyses(result: CurationResult, config: DecisionConfig) {
        val analyses = result.analyses.values.toList()
        val again = DecisionEngine.decide(analyses, result.captureTimes, config)
        val shuffled = DecisionEngine.decide(analyses.shuffled(java.util.Random(42)), result.captureTimes, config)
        assertEquals("re-deciding the same analyses", result.chosen, again.chosen)
        assertEquals("re-deciding the same analyses", result.alternatives, again.alternatives)
        assertEquals("input order changed the decision", again.chosen, shuffled.chosen)
        assertEquals("input order changed the decision", again.alternatives, shuffled.alternatives)
    }

    /**
     * Layout ids on the real analyses: none with an empty catalogue; with a catalogue whose cells never crop (every
     * set fits) the same sets are chosen and offered, each with a catalogue layout id and the same anchor; with a
     * cropping catalogue checked by [CellFit] every option is still a valid offer, and the catalogue can only remove
     * sets (no mode appears that the layout-free decision did not offer, and the chosen size never grows).
     */
    private fun checkLayoutCatalogues(result: CurationResult) {
        val analyses = result.analyses.values.toList()
        val times = result.captureTimes

        val emptyConfig = DecisionConfig(layoutCatalog = LayoutCatalog.EMPTY, layoutFitter = CellFit())
        val plain = DecisionEngine.decide(analyses, times, emptyConfig)
        for ((mode, option) in plain.alternatives) assertNull("$mode layout id with an empty catalogue", option.layoutId)
        checkRuleStructure("empty-catalogue", plain.chosen, plain.alternatives, plain.clusters, result, emptyConfig)

        val uncroppedConfig = DecisionConfig(layoutCatalog = UNCROPPED_CATALOG, layoutFitter = CellFit())
        val uncropped = DecisionEngine.decide(analyses, times, uncroppedConfig)
        assertEquals("uncropped layouts always fit: same chosen mode", plain.chosen.mode, uncropped.chosen.mode)
        assertEquals("uncropped layouts always fit: same modes offered", plain.alternatives.keys, uncropped.alternatives.keys)
        for ((mode, option) in uncropped.alternatives) {
            val reference = plain.alternatives.getValue(mode)
            assertNotNull("$mode has no layout id although the catalogue has layouts for it", option.layoutId)
            assertEquals("$mode set changed under an always-fitting catalogue", reference.photoIds.toSet(), option.photoIds.toSet())
            assertEquals("$mode anchor moved", reference.photoIds.first(), option.photoIds.first())
        }
        checkRuleStructure("uncropped-catalogue", uncropped.chosen, uncropped.alternatives, uncropped.clusters, result, uncroppedConfig)
        checkExistenceByBruteForce("uncropped-catalogue", uncropped.chosen, uncropped.alternatives, uncropped.clusters, result, uncroppedConfig)

        val croppingConfig = DecisionConfig(layoutCatalog = CROPPING_CATALOG, layoutFitter = CellFit())
        val cropped = DecisionEngine.decide(analyses, times, croppingConfig)
        checkRuleStructure("cropping-catalogue", cropped.chosen, cropped.alternatives, cropped.clusters, result, croppingConfig)
        assertTrue("a catalogue added modes: ${cropped.alternatives.keys} vs ${plain.alternatives.keys}",
            plain.alternatives.keys.containsAll(cropped.alternatives.keys))
        assertTrue("a catalogue grew the chosen size: ${cropped.chosen.mode} vs ${plain.chosen.mode}",
            cropped.chosen.mode.photoCount <= plain.chosen.mode.photoCount)
        for ((mode, option) in cropped.alternatives) {
            if (mode != StoryMode.HERO) assertNotNull("$mode offered without a verified layout", option.layoutId)
        }
    }

    /**
     * Mode switches through the engine: an offered mode comes back as exactly the offered alternative with everything
     * else untouched; an unoffered mode yields m valid photos added for that mode alone; null re-decides to the same.
     */
    private fun checkForcedModes(engine: DefaultCurationEngine, result: CurationResult) {
        for (mode in StoryMode.entries) {
            val switched = engine.decide(result, mode)
            assertEquals("$mode analyses", result.analyses, switched.analyses)
            assertEquals("$mode clusters", result.clusters, switched.clusters)
            assertEquals("$mode rejections", result.rejectedIds, switched.rejectedIds)
            assertEquals("$mode capture times", result.captureTimes, switched.captureTimes)
            assertEquals("$mode note mode", switched.chosen.mode, switched.note.chosenMode)
            val offered = result.alternatives[mode]
            if (offered != null) {
                assertEquals("$mode switch returns the offered alternative unchanged", offered, switched.chosen)
                assertEquals("$mode switch keeps the alternatives", result.alternatives, switched.alternatives)
            } else {
                assertEquals(mode, switched.chosen.mode)
                val ids = switched.chosen.photoIds
                assertEquals("$mode forced size", mode.photoCount, ids.toSet().size)
                assertTrue("$mode forced set uses a rejected photo: $ids", ids.none { it in result.rejectedIds })
                assertEquals("$mode forced set is added for its mode alone",
                    result.alternatives + (mode to switched.chosen), switched.alternatives)
            }
        }
        val auto = engine.decide(result, null)
        assertEquals("automatic re-decision", result.chosen, auto.chosen)
        assertEquals("automatic re-decision", result.alternatives, auto.alternatives)
        // Switching to another offered mode and back returns the original choice.
        val other = result.alternatives.keys.firstOrNull { it != result.chosen.mode } ?: return
        val back = engine.decide(engine.decide(result, other), result.chosen.mode)
        assertEquals("switching back", result.chosen, back.chosen)
    }

    private fun logAnalyses(result: CurationResult) {
        for (a in result.analyses.values) {
            logValue(
                "analysis.${a.candidateId}",
                "T=%.4f(s=%.3f e=%.3f) A=%.4f comp=%.3f Q=%.4f scene=%s reject=%s screen=%s".format(
                    a.technical.total, a.technical.sharpness, a.technical.exposure, a.aesthetic, a.composition,
                    a.quality, a.scene, a.rejectReason, a.screenCapture,
                ),
            )
        }
        logValue("clusters", result.clusters)
        val top = result.clusters.map { result.analyses.getValue(it.championId) }.sortedWith(POOL_ORDER).firstOrNull()
        logValue("top_champion", "${top?.candidateId} (fixture expects $CLEAR_BEST in the main pool)")
        for ((mode, option) in result.alternatives) {
            logValue("option.$mode", "${option.photoIds} score=${option.score} layout=${option.layoutId} ${option.synergy}")
        }
    }

    /**
     * A phone-aspect (400×864) app screenshot without camera EXIF: flat status bar, panels and buttons drawn without
     * anti-aliasing (exactly uniform 8×8 blocks), sharp text bars, and one grainy photo thumbnail for tonal range.
     */
    private fun screenshot(): Bitmap {
        val bitmap = Bitmap.createBitmap(SCREEN_W, SCREEN_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { isAntiAlias = false; isFilterBitmap = true }
        canvas.drawColor(Color.rgb(214, 218, 226))
        paint.color = Color.rgb(38, 42, 52)
        canvas.drawRect(Rect(0, 0, SCREEN_W, 48), paint)
        paint.color = Color.rgb(64, 120, 200)
        canvas.drawRect(Rect(0, 48, SCREEN_W, 112), paint)
        val thumb = DeviceScenes.scene(Kind.BEACH, seed = 12)
        canvas.drawBitmap(thumb, null, Rect(16, 128, SCREEN_W - 16, 400), paint)
        thumb.recycle()
        for (card in 0 until 4) {
            val top = 416 + card * 104
            paint.color = Color.rgb(240, 241, 245)
            canvas.drawRect(Rect(16, top, SCREEN_W - 16, top + 92), paint)
            paint.color = Color.rgb(28, 28, 32)
            canvas.drawRect(Rect(32, top + 16, 32 + 180 + card * 30, top + 24), paint)
            canvas.drawRect(Rect(32, top + 40, 32 + 260 - card * 20, top + 46), paint)
            canvas.drawRect(Rect(32, top + 60, 32 + 120 + card * 40, top + 66), paint)
            paint.color = Color.rgb(64, 120, 200)
            canvas.drawRect(Rect(SCREEN_W - 96, top + 30, SCREEN_W - 32, top + 62), paint)
        }
        paint.color = Color.rgb(38, 42, 52)
        canvas.drawRect(Rect(0, SCREEN_H - 64, SCREEN_W, SCREEN_H), paint)
        return bitmap
    }

    private companion object {
        const val BURST_SHARP = "burst-1-sharp"
        const val BURST_SOFT = "burst-2-soft"
        const val BURST_JPEG = "burst-3-jpeg"
        const val DEFOCUSED = "reject-defocused"
        const val NEAR_BLACK = "reject-near-black"
        const val CLEAR_BEST = "flowers"
        const val SCREENSHOT = "screenshot"
        const val SCREEN_W = 400
        const val SCREEN_H = 864
        const val T0 = 1_760_000_000_000L

        val COLLAGE_MODES = listOf(StoryMode.COLLAGE_2, StoryMode.COLLAGE_3, StoryMode.COLLAGE_4)

        /** The rule's pool order: quality descending, then aesthetic descending, then id ascending. */
        val POOL_ORDER: Comparator<PhotoAnalysis> = compareByDescending<PhotoAnalysis> { it.quality }
            .thenByDescending { it.aesthetic }
            .thenBy { it.candidateId }

        /** Anchor choice: aesthetic descending, then quality descending, then id ascending. */
        val ANCHOR_ORDER: Comparator<PhotoAnalysis> = compareByDescending<PhotoAnalysis> { it.aesthetic }
            .thenByDescending { it.quality }
            .thenBy { it.candidateId }

        val BY_QUALITY_THEN_ID: Comparator<PhotoAnalysis> = compareByDescending<PhotoAnalysis> { it.quality }
            .thenBy { it.candidateId }

        /** Layouts for 1..4 photos whose cells never crop, so every set fits. */
        val UNCROPPED_CATALOG = LayoutCatalog(
            (1..4).map { n -> LayoutSpec("uncropped-$n", List(n) { CellShape.Uncropped }) },
        )

        /** A 9:16 story's cells: full frame, two stacked halves, a band over two halves, a 2×2 grid. */
        val CROPPING_CATALOG = LayoutCatalog(
            listOf(
                LayoutSpec("hero", listOf(CellShape.Aspect(0.5625f))),
                LayoutSpec("stack-2", List(2) { CellShape.Aspect(1.125f) }),
                LayoutSpec("band-3", listOf(CellShape.Aspect(1.125f), CellShape.Aspect(0.5625f), CellShape.Aspect(0.5625f))),
                LayoutSpec("grid-4", List(4) { CellShape.Aspect(0.5625f) }),
            ),
        )
    }
}
