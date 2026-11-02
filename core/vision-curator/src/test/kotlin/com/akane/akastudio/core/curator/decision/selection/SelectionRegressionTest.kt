package com.akane.akastudio.core.curator.decision.selection

import com.akane.akastudio.core.curator.api.CurationOption
import com.akane.akastudio.core.curator.api.PhotoAnalysis
import com.akane.akastudio.core.curator.api.RejectReason
import com.akane.akastudio.core.curator.api.ShotScale
import com.akane.akastudio.core.curator.api.StoryMode
import com.akane.akastudio.core.curator.decision.CellShape
import com.akane.akastudio.core.curator.decision.Decision
import com.akane.akastudio.core.curator.decision.DecisionConfig
import com.akane.akastudio.core.curator.decision.DecisionEngine
import com.akane.akastudio.core.curator.decision.LayoutCatalog
import com.akane.akastudio.core.curator.decision.LayoutFit
import com.akane.akastudio.core.curator.decision.LayoutFitter
import com.akane.akastudio.core.curator.decision.LayoutSpec
import com.akane.akastudio.core.curator.decision.Synergy
import com.akane.akastudio.core.curator.decision.selection.SelectionFixtures.EmbeddingSpace
import com.akane.akastudio.core.curator.decision.selection.SelectionFixtures.HashSpace
import com.akane.akastudio.core.curator.decision.selection.SelectionFixtures.PhotoSet
import com.akane.akastudio.core.curator.decision.selection.SelectionFixtures.SetBuilder
import com.akane.akastudio.core.curator.decision.selection.SelectionFixtures.at
import com.akane.akastudio.core.curator.decision.selection.SelectionFixtures.face
import com.akane.akastudio.core.curator.decision.selection.SelectionFixtures.peoplePalette
import com.akane.akastudio.core.curator.decision.selection.SelectionFixtures.photo
import com.akane.akastudio.core.curator.decision.selection.SelectionFixtures.selfiePalette
import com.akane.akastudio.core.curator.decision.selection.SelectionFixtures.seriesPalette
import com.akane.akastudio.core.curator.decision.selection.SelectionFixtures.tablePalette
import com.akane.akastudio.core.curator.decision.selection.SelectionFixtures.toEmbedding
import com.akane.akastudio.core.curator.hash.PerceptualHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * What the user sees from automatic selection, on sets built from the night-out trace's measured distributions (see
 * [SelectionFixtures]). Assertions are structural (mode, chosen ids as sets, "no two photos of one moment", slot 0),
 * never exact scores, so they hold for any tuning of the provisional constants inside their documented ranges.
 *
 * Each test is named for the user requirement it protects. Moments are fixture ground truth the engine never sees.
 */
class SelectionRegressionTest {
    // ---- shared helpers ----

    private fun offered(decision: Decision): List<CurationOption> = decision.alternatives.values.toList()

    private fun everyOption(decision: Decision): List<CurationOption> = offered(decision) + decision.chosen

    private fun describe(
        decision: Decision,
        set: PhotoSet,
    ): String =
        "chosen ${decision.chosen.mode} ${decision.chosen.photoIds} = ${set.moments(decision.chosen.photoIds)}; offered " +
            decision.alternatives.entries.joinToString { (mode, o) -> "$mode ${o.photoIds} = ${set.moments(o.photoIds)}" }

    /** The colour anchor rule: highest aesthetic, then highest quality, then smallest id. */
    private fun expectedAnchor(
        ids: List<String>,
        set: PhotoSet,
    ): String =
        ids
            .map(set::byId)
            .sortedWith(
                compareByDescending<PhotoAnalysis> { it.aesthetic }.thenByDescending { it.quality }.thenBy { it.candidateId },
            ).first()
            .candidateId

    /** Cross-moment blend: moment vectors share the venue at `sqrt(0.30)`, so moment-to-moment cosine is 0.30. */
    private val momentFromVenue = sqrt(0.30)

    // ---- the night-out table scene (cases a, a', b, k, n) ----

    private val tableIds = listOf("t1", "t2", "t3", "t4", "t5", "t6")

    /**
     * Six reframed shots of one table in 28 s (cosine 0.51-0.81, median about 0.70, hash distance about 21), one of
     * them also saved as a JPG copy, then a two-person photo and a selfie about 2.5 h later. Table frames carry the
     * highest Q (no faces, sharp, high NIMA), and their shot scale varies, as in the user's grid.
     *
     * @param tableCount how many of the six table frames to include.
     * @param pairQuality the two-person photo's Q.
     * @param withSelfie whether the selfie is in the set.
     * @param withCopy whether the JPG copy of the top table frame is in the set.
     */
    private fun nightOut(
        tableCount: Int = 6,
        pairQuality: Float = 0.66f,
        withSelfie: Boolean = true,
        withCopy: Boolean = false,
    ): PhotoSet {
        val space = EmbeddingSpace()
        val hashes = HashSpace()
        val venue = space.fresh()
        val set = SetBuilder()

        val table = space.blend(venue, momentFromVenue)
        val tableMoment = hashes.moment()

        data class Frame(
            val id: String,
            val b: Double,
            val q: Float,
            val a: Float,
            val s: Int,
            val scale: ShotScale,
        )
        val frames =
            listOf(
                Frame("t1", 0.88, 0.760f, 0.580f, 26, ShotScale.WIDE),
                Frame("t2", 0.905, 0.740f, 0.573f, 10, ShotScale.WIDE),
                Frame("t3", 0.90, 0.730f, 0.530f, 13, ShotScale.MEDIUM),
                Frame("t4", 0.85, 0.720f, 0.498f, 4, ShotScale.WIDE),
                Frame("t5", 0.78, 0.700f, 0.521f, 0, ShotScale.DETAIL),
                Frame("t6", 0.66, 0.680f, 0.540f, 28, ShotScale.WIDE),
            ).take(tableCount)
        for ((i, f) in frames.withIndex()) {
            val vector = space.blend(table, f.b)
            val pHash = hashes.hash(tableMoment, flip = 2 + i % 3)
            val time = at(20, 59, 57) + f.s * SelectionFixtures.SECOND
            val p =
                photo(
                    f.id,
                    f.q,
                    toEmbedding(vector),
                    pHash,
                    tablePalette(0.003f * i),
                    7340f + 90f * i,
                    f.scale,
                    f.a,
                )
            set.add(p, time, "table")
            if (withCopy && f.id == "t1") {
                // The same capture saved as JPG: cosine 0.9995, identical hashes, Q a hair higher (as on the device).
                val copy =
                    photo(
                        "t1-jpg",
                        f.q + 0.002f,
                        toEmbedding(space.blend(vector, 0.9995)),
                        pHash,
                        tablePalette(0.003f * i),
                        7340f + 90f * i,
                        f.scale,
                        f.a + 0.004f,
                    )
                set.add(copy, time, "table")
            }
        }

        val pair = space.blend(space.blend(venue, momentFromVenue), 0.97)
        set.add(
            photo(
                "pair",
                pairQuality,
                toEmbedding(pair),
                hashes.hash(hashes.moment(), flip = 3),
                peoplePalette(),
                7660f,
                ShotScale.MEDIUM,
                aesthetic = 0.432f,
                landscape = true,
                sharpness = 0.95f,
                faces = listOf(face(0.15f, 0.37f, 0.0888f, sharpness = 0.58f), face(0.79f, 0.41f, 0.0255f, sharpness = 0.67f)),
            ),
            at(22, 33, 14),
            "pair",
        )
        if (withSelfie) {
            val selfie = space.blend(space.blend(venue, momentFromVenue), 0.97)
            set.add(
                photo(
                    "selfie",
                    0.60f,
                    toEmbedding(selfie),
                    hashes.hash(hashes.moment(), flip = 3),
                    selfiePalette(),
                    5860f,
                    ShotScale.MEDIUM,
                    aesthetic = 0.540f,
                    sharpness = 0.80f,
                    faces = listOf(face(0.47f, 0.70f, 0.20f)),
                ),
                at(22, 33, 44),
                "selfie",
            )
        }
        return set.build()
    }

    // ---- (a) the user's complaint: four table shots ----

    @Test
    fun tableReframes_doNotFillTheAutomaticStory() {
        val set = nightOut()
        val decision = DecisionEngine.decide(set.photos, set.times)
        val chosen = decision.chosen.photoIds
        val tableCount = chosen.count { it in tableIds }
        assertFalse("four table shots: ${describe(decision, set)}", tableCount == 4)
        assertTrue("at most one frame of the table moment: ${describe(decision, set)}", tableCount <= 1)
        assertTrue("no repeated moment: ${describe(decision, set)}", set.repeatedMoments(chosen).isEmpty())
    }

    @Test
    fun tableReframes_withoutCaptureTimes_doNotFillTheAutomaticStory() {
        // A WhatsApp zip strips EXIF: every capture time is unknown.
        val set = nightOut().withoutTimes()
        val decision = DecisionEngine.decide(set.photos, set.times)
        val chosen = decision.chosen.photoIds
        assertTrue("at most one table frame: ${describe(decision, set)}", chosen.count { it in tableIds } <= 1)
        assertTrue("no repeated moment: ${describe(decision, set)}", set.repeatedMoments(chosen).isEmpty())
    }

    // ---- (n) good portraits are not lost to reframes of the hero's scene ----

    @Test
    fun goodPortrait_isNotLostToTableReframes() {
        // Five table reframes and one two-person photo at 0.86 of the table hero's Q.
        val set = nightOut(tableCount = 5, pairQuality = 0.86f * 0.760f, withSelfie = false)
        val decision = DecisionEngine.decide(set.photos, set.times)
        assertTrue("the two-person photo is in the story: ${describe(decision, set)}", "pair" in decision.chosen.photoIds)
        assertTrue("no repeated moment: ${describe(decision, set)}", set.repeatedMoments(decision.chosen.photoIds).isEmpty())
    }

    // ---- (b) one capture in two formats ----

    @Test
    fun formatCopies_areNeverChosenOrOfferedTogether() {
        val set = nightOut(withCopy = true)
        val decision = DecisionEngine.decide(set.photos, set.times)
        for (option in everyOption(decision)) {
            assertFalse(
                "HEIC and JPG of one capture together in ${option.mode}: ${describe(decision, set)}",
                "t1" in option.photoIds && "t1-jpg" in option.photoIds,
            )
        }
        // A switch to an offered mode returns exactly that option, so the copies cannot meet there either.
        for ((mode, option) in decision.alternatives) {
            val switched = DecisionEngine.redecide(decision, set.photos, set.times, mode)
            assertSame("switch to $mode", option, switched.chosen)
        }
    }

    // ---- (c, d) a burst of one seated person ----

    private val burstIds = (1..11).map { "w%02d".format(it) }

    /**
     * Eleven frames of one person at a table in 15 s: cosine 0.41-0.89 (median about 0.6), hash distance 20, face
     * boxes 0.019-0.028 of the frame with one frame missing its face, one blurry frame (Q 0.499) and one rejected for
     * closed eyes. Three frames are above the 0.82 cluster cosine and merge; the rest stay separate champions.
     */
    private fun seatedBurst(): PhotoSet {
        val space = EmbeddingSpace()
        val hashes = HashSpace()
        val moment = space.blend(space.fresh(), momentFromVenue)
        val hashMoment = hashes.moment()
        val set = SetBuilder()

        data class Frame(
            val b: Double,
            val q: Float,
            val s: Int,
            val faceAt: Pair<Float, Float>?,
            val area: Float,
            val blurry: Boolean = false,
            val eyesClosed: Boolean = false,
        )
        val frames =
            listOf(
                Frame(0.95, 0.733f, 11, 0.35f to 0.42f, 0.0190f),
                Frame(0.94, 0.706f, 9, 0.33f to 0.41f, 0.0198f),
                Frame(0.88, 0.704f, 14, 0.44f to 0.39f, 0.0189f),
                Frame(0.80, 0.707f, 6, 0.61f to 0.45f, 0.0204f),
                Frame(0.77, 0.694f, 13, null, 0f),
                Frame(0.75, 0.686f, 15, 0.38f to 0.42f, 0.0198f),
                Frame(0.72, 0.678f, 0, 0.51f to 0.41f, 0.0235f),
                Frame(0.70, 0.676f, 4, 0.58f to 0.44f, 0.0207f),
                Frame(0.66, 0.648f, 2, 0.49f to 0.47f, 0.0211f),
                Frame(0.62, 0.499f, 3, 0.67f to 0.50f, 0.0279f, blurry = true),
                Frame(0.70, 0.652f, 5, 0.71f to 0.43f, 0.0200f, eyesClosed = true),
            )
        for ((i, f) in frames.withIndex()) {
            val faces =
                f.faceAt
                    ?.let { (x, y) ->
                        listOf(face(x, y, f.area, eyes = if (f.eyesClosed) 0.27f else 0.8f, sharpness = if (f.blurry) 0.5f else 0.95f))
                    }.orEmpty()
            set.add(
                photo(
                    burstIds[i],
                    f.q,
                    toEmbedding(space.blend(moment, f.b)),
                    hashes.hash(hashMoment, flip = 2),
                    seriesPalette(0.002f * (i % 4)),
                    8420f + 150f * i,
                    ShotScale.WIDE,
                    aesthetic = 0.42f + 0.009f * i,
                    faces = faces,
                    sharpness = if (f.blurry) 0.49f else 1f,
                    reject = if (f.eyesClosed) RejectReason.EYES_CLOSED else null,
                ),
                at(23, 39, 16) + f.s * SelectionFixtures.SECOND,
                "series",
            )
        }
        return set.build()
    }

    @Test
    fun burstOnly_neverFillsThreeOrFourCells() {
        val set = seatedBurst()
        val decision = DecisionEngine.decide(set.photos, set.times)
        assertTrue("HERO or COLLAGE_2 at most: ${describe(decision, set)}", decision.chosen.mode.photoCount <= 2)
    }

    @Test
    fun burstOnly_withoutCaptureTimes_neverFillsThreeOrFourCells() {
        val set = seatedBurst().withoutTimes()
        val decision = DecisionEngine.decide(set.photos, set.times)
        assertTrue("HERO or COLLAGE_2 at most: ${describe(decision, set)}", decision.chosen.mode.photoCount <= 2)
    }

    // ---- (e, f, i, m) distinct moments ----

    /**
     * Four distinct moments of one evening (cross cosine 0.28, hash distance >= 26, 40-70 min apart), one frame each,
     * with the given qualities. The highest aesthetic is m2's, so the colour anchor is not simply the top-Q photo.
     */
    private fun distinctMoments(qualities: List<Float> = listOf(0.76f, 0.74f, 0.72f, 0.70f)): PhotoSet {
        val space = EmbeddingSpace()
        val hashes = HashSpace()
        val venue = space.fresh()
        val set = SetBuilder()
        val times = listOf(at(21, 0, 0), at(21, 40, 0), at(22, 30, 0), at(23, 39, 0))
        val scales = listOf(ShotScale.WIDE, ShotScale.MEDIUM, ShotScale.DETAIL, ShotScale.MEDIUM)
        val aesthetics = listOf(0.55f, 0.60f, 0.52f, 0.50f)
        val faces =
            listOf(
                emptyList(),
                listOf(face(0.47f, 0.40f, 0.10f)),
                listOf(face(0.20f, 0.50f, 0.08f), face(0.80f, 0.50f, 0.08f)),
                listOf(face(0.40f, 0.42f, 0.02f)),
            )
        for ((i, q) in qualities.withIndex()) {
            val v = space.blend(space.blend(venue, momentFromVenue), 0.97)
            set.add(
                photo(
                    "m${i + 1}",
                    q,
                    toEmbedding(v),
                    hashes.hash(hashes.moment(), flip = 3),
                    tablePalette(0.01f * i),
                    7400f + 120f * i,
                    scales[i % scales.size],
                    aesthetic = aesthetics[i % aesthetics.size],
                    faces = faces[i % faces.size],
                ),
                times[i % times.size] + (i / times.size) * 7 * SelectionFixtures.MINUTE,
                "m${i + 1}",
            )
        }
        return set.build()
    }

    @Test
    fun fourStrongMoments_formAFourPhotoCollage() {
        val set = distinctMoments()
        val decision = DecisionEngine.decide(set.photos, set.times)
        assertEquals(describe(decision, set), StoryMode.COLLAGE_4, decision.chosen.mode)
        assertEquals(setOf("m1", "m2", "m3", "m4"), decision.chosen.photoIds.toSet())
        for (option in everyOption(decision)) {
            assertEquals("anchor in slot 0 of ${option.mode}", expectedAnchor(option.photoIds, set), option.photoIds.first())
            assertNull("no catalogue, no layout id: $option", option.layoutId)
        }
    }

    @Test
    fun weakFourthMoment_isLeftOut_threePhotoCollage() {
        // The fourth moment is distinct but weak: Q ratio 0.53 / 0.76 = 0.70, under the four-photo bar.
        val set = distinctMoments(listOf(0.76f, 0.74f, 0.72f, 0.53f))
        val decision = DecisionEngine.decide(set.photos, set.times)
        assertEquals(describe(decision, set), StoryMode.COLLAGE_3, decision.chosen.mode)
        assertEquals(setOf("m1", "m2", "m3"), decision.chosen.photoIds.toSet())
    }

    @Test
    fun screenCapture_isNeverChosenOrOffered() {
        val base = distinctMoments()
        // An axis the base set never uses, so the screen capture is unrelated to every moment (cosine 0).
        val unrelated =
            DoubleArray(
                base.photos
                    .first()
                    .embedding.size,
            ).also { it[it.size - 1] = 1.0 }
        val shot =
            photo(
                "screen",
                0.80f,
                toEmbedding(unrelated),
                0x5A5A_F0F0_1234_9876L,
                peoplePalette(0.02f),
                6120f,
                ShotScale.MEDIUM,
                aesthetic = 0.62f,
                sharpness = 0.93f,
                faces = listOf(face(0.36f, 0.37f, 0.16f)),
                screenCapture = true,
            )
        val set =
            PhotoSet(
                base.photos + shot,
                base.times + ("screen" to at(22, 34, 44)),
                base.momentOf + ("screen" to "screen"),
            )
        val decision = DecisionEngine.decide(set.photos, set.times)
        for (option in everyOption(decision)) {
            assertFalse("screen capture in ${option.mode}: ${describe(decision, set)}", "screen" in option.photoIds)
        }
    }

    @Test
    fun layoutFitter_infeasibleQuad_fallsBackToThreeWithItsLayoutAndAnchorInSlotZero() {
        val set = distinctMoments()
        val catalog =
            LayoutCatalog(
                listOf(
                    LayoutSpec("L2", List(2) { CellShape.Aspect(1.12f) }),
                    LayoutSpec("L3", listOf(CellShape.Aspect(1.0f), CellShape.Aspect(0.8f), CellShape.Aspect(0.8f))),
                    LayoutSpec("L4", List(4) { CellShape.Aspect(0.554f) }),
                ),
            )
        val outputs = HashMap<List<String>, List<String>>()
        // No four-photo layout fits; three photos fit with slots 1.. reversed; anchor (photos[0]) stays in slot 0.
        val stub =
            LayoutFitter { photos, cat ->
                val ids = photos.map { it.candidateId }
                when (photos.size) {
                    4 -> null
                    3 -> LayoutFit(cat.forCount(3).single().id, listOf(ids[0]) + ids.drop(1).reversed(), 1f)
                    else -> cat.forCount(photos.size).firstOrNull()?.let { LayoutFit(it.id, ids, 1f) }
                }?.also { synchronized(outputs) { outputs[ids.sorted()] = it.orderedIds } }
            }
        val decision = DecisionEngine.decide(set.photos, set.times, DecisionConfig(layoutCatalog = catalog, layoutFitter = stub))
        val chosen = decision.chosen
        assertEquals(describe(decision, set), StoryMode.COLLAGE_3, chosen.mode)
        assertEquals("the chosen option carries the fitted layout", "L3", chosen.layoutId)
        assertEquals("colour anchor in slot 0", expectedAnchor(chosen.photoIds, set), chosen.photoIds.first())
        val fitted = assertNotNullAndGet(outputs[chosen.photoIds.sorted()], "fitter never saw the chosen set")
        assertEquals("photo ids follow the fitter's slot order", fitted, chosen.photoIds)
        assertFalse(
            "no four-photo layout fits, so COLLAGE_4 is not offered: ${describe(decision, set)}",
            StoryMode.COLLAGE_4 in decision.alternatives,
        )
    }

    private fun <T : Any> assertNotNullAndGet(
        value: T?,
        message: String,
    ): T {
        assertNotNull(message, value)
        return value ?: error(message)
    }

    // ---- (g) diversity over redundancy ----

    @Test
    fun diverseTrio_beatsFourRedundantFramesWithHigherSynergyAndQuality() {
        val space = EmbeddingSpace()
        val hashes = HashSpace()
        val venue = space.fresh()
        val b = SetBuilder()
        val redundant = space.blend(venue, momentFromVenue)
        val rMoment = hashes.moment()
        // Four reframes of one scene in 20 s (cosine 0.68-0.79), the highest Q of the set and varied shot scale.
        val rFrames =
            listOf(
                Triple("r1", 0.90, 0.80f),
                Triple("r2", 0.88, 0.79f),
                Triple("r3", 0.85, 0.78f),
                Triple("r4", 0.80, 0.78f),
            )
        val rScales = listOf(ShotScale.WIDE, ShotScale.MEDIUM, ShotScale.DETAIL, ShotScale.WIDE)
        for ((i, f) in rFrames.withIndex()) {
            b.add(
                photo(
                    f.first,
                    f.third,
                    toEmbedding(space.blend(redundant, f.second)),
                    hashes.hash(rMoment, flip = 3),
                    tablePalette(0.002f * i),
                    7400f + 40f * i,
                    rScales[i],
                    aesthetic = 0.56f - 0.01f * i,
                ),
                at(21, 0, 0) + listOf(0, 6, 12, 20)[i] * SelectionFixtures.SECOND,
                "redundant",
            )
        }
        b.add(
            photo(
                "d1",
                0.70f,
                toEmbedding(space.blend(space.blend(venue, momentFromVenue), 0.97)),
                hashes.hash(hashes.moment(), flip = 3),
                peoplePalette(),
                6200f,
                ShotScale.MEDIUM,
                aesthetic = 0.45f,
                landscape = true,
                faces = listOf(face(0.2f, 0.4f, 0.09f), face(0.8f, 0.4f, 0.03f)),
            ),
            at(21, 40, 0),
            "d1",
        )
        b.add(
            photo(
                "d2",
                0.68f,
                toEmbedding(space.blend(space.blend(venue, momentFromVenue), 0.97)),
                hashes.hash(hashes.moment(), flip = 3),
                selfiePalette(),
                5500f,
                ShotScale.MEDIUM,
                aesthetic = 0.44f,
                faces = listOf(face(0.47f, 0.6f, 0.18f)),
            ),
            at(22, 20, 0),
            "d2",
        )
        val set = b.build()

        // Preconditions: the redundant four have higher synergy and mean Q than any diverse trio with one of them.
        val rIds = rFrames.map { it.first }
        val quadPsi = psi(set, rIds)
        val quadQ = rIds.map { set.byId(it).quality }.average()
        for (r in rIds) {
            val trio = listOf(r, "d1", "d2")
            assertTrue("Ψ(redundant four) $quadPsi > Ψ($trio) ${psi(set, trio)}", quadPsi > psi(set, trio))
            assertTrue("meanQ(redundant four) > meanQ($trio)", quadQ > trio.map { set.byId(it).quality }.average())
        }

        val decision = DecisionEngine.decide(set.photos, set.times)
        val chosen = decision.chosen.photoIds
        assertEquals(describe(decision, set), StoryMode.COLLAGE_3, decision.chosen.mode)
        assertTrue("both diverse moments are in: ${describe(decision, set)}", chosen.containsAll(listOf("d1", "d2")))
        assertEquals("exactly one redundant frame: ${describe(decision, set)}", 1, chosen.count { it in rIds })
    }

    private fun psi(
        set: PhotoSet,
        ids: List<String>,
    ): Float = Synergy.evaluate(ids.map(set::byId), ids.map { set.times[it] }).psi

    // ---- (h) a single photo ----

    @Test
    fun singlePhoto_isHero() {
        val space = EmbeddingSpace()
        val only = photo("only", 0.7f, toEmbedding(space.fresh()), 0x1234_5678_9ABC_DEF0L, tablePalette(), 7400f)
        val decision = DecisionEngine.decide(listOf(only), mapOf("only" to at(21, 0, 0)))
        assertEquals(StoryMode.HERO, decision.chosen.mode)
        assertEquals(listOf("only"), decision.chosen.photoIds)
        assertEquals(setOf(StoryMode.HERO), decision.alternatives.keys)
    }

    // ---- (j) one story per event ----

    /** Two strong distinct moments on each of two evenings, 24 h apart. */
    private fun twoEvenings(): PhotoSet {
        val space = EmbeddingSpace()
        val hashes = HashSpace()
        val venue = space.fresh()
        val b = SetBuilder()
        val spec =
            listOf(
                listOf("a", 0.76f, 0, 20, ShotScale.WIDE),
                listOf("b", 0.75f, 0, 21, ShotScale.MEDIUM),
                listOf("c", 0.74f, 1, 20, ShotScale.DETAIL),
                listOf("d", 0.73f, 1, 21, ShotScale.MEDIUM),
            )
        for ((i, row) in spec.withIndex()) {
            val id = row[0] as String
            val day = row[2] as Int
            b.add(
                photo(
                    id,
                    row[1] as Float,
                    toEmbedding(space.blend(space.blend(venue, momentFromVenue), 0.97)),
                    hashes.hash(hashes.moment(), flip = 3),
                    tablePalette(0.004f * i),
                    7400f + 60f * i,
                    row[4] as ShotScale,
                    aesthetic = 0.55f - 0.01f * i,
                ),
                at(row[3] as Int, 0, 0, day = day),
                id,
                onDay = day,
            )
        }
        return b.build()
    }

    @Test
    fun eventSpanningTwoDays_isNotMixedInTheAutomaticStory() {
        val set = twoEvenings()
        val decision = DecisionEngine.decide(set.photos, set.times)
        val days =
            decision.chosen.photoIds
                .map { set.day.getValue(it) }
                .toSet()
        assertEquals("the automatic story stays on one day: ${describe(decision, set)}", 1, days.size)
    }

    @Test
    fun eventSpanningTwoDays_noOfferedCollageMixesTheDays() {
        val set = twoEvenings()
        val decision = DecisionEngine.decide(set.photos, set.times)
        for (option in offered(decision)) {
            val days = option.photoIds.map { set.day.getValue(it) }.toSet()
            assertEquals("${option.mode} mixes days: ${describe(decision, set)}", 1, days.size)
        }
    }

    // ---- (k) determinism ----

    @Test
    fun inputOrder_doesNotChangeTheDecision() {
        val set = nightOut(withCopy = true)
        val reference = DecisionEngine.decide(set.photos, set.times)
        val random = Random(20261102)
        repeat(6) { round ->
            val shuffled = set.photos.shuffled(random)
            val decision = DecisionEngine.decide(shuffled, set.times)
            assertEquals("round $round chosen", reference.chosen.mode, decision.chosen.mode)
            assertEquals("round $round chosen ids", reference.chosen.photoIds, decision.chosen.photoIds)
            assertEquals("round $round offered modes", reference.alternatives.keys, decision.alternatives.keys)
            for ((mode, option) in reference.alternatives) {
                assertEquals("round $round $mode", option.photoIds, decision.alternatives.getValue(mode).photoIds)
            }
        }
    }

    // ---- (l) the drawer ----

    /**
     * A top table frame (Q 0.76) with a reframe 6 s later, a portrait (Q 0.62) with a blurry reframe 10 s later
     * (Q 0.49), and a dark, soft frame of its own moment at Q 0.36, below half of the hero's Q. Within each pair the
     * cosine is 0.63-0.72 and the frames are under a minute apart, so the second frame adds no unique quality.
     */
    private fun drawerSet(): PhotoSet {
        val space = EmbeddingSpace()
        val hashes = HashSpace()
        val venue = space.fresh()
        val b = SetBuilder()
        val table = space.blend(venue, momentFromVenue)
        val tableMoment = hashes.moment()
        b.add(
            photo(
                "hero",
                0.76f,
                toEmbedding(space.blend(table, 0.90)),
                hashes.hash(tableMoment, 3),
                tablePalette(),
                7490f,
                ShotScale.WIDE,
                aesthetic = 0.58f,
            ),
            at(21, 0, 0),
            "table",
        )
        b.add(
            photo(
                "reframe",
                0.72f,
                toEmbedding(space.blend(table, 0.80)),
                hashes.hash(tableMoment, 3),
                tablePalette(0.004f),
                7360f,
                ShotScale.MEDIUM,
                aesthetic = 0.57f,
            ),
            at(21, 0, 6),
            "table",
        )
        val portrait = space.blend(venue, momentFromVenue)
        val portraitMoment = hashes.moment()
        b.add(
            photo(
                "portrait",
                0.62f,
                toEmbedding(space.blend(portrait, 0.90)),
                hashes.hash(portraitMoment, 3),
                selfiePalette(),
                5810f,
                ShotScale.MEDIUM,
                aesthetic = 0.40f,
                sharpness = 0.68f,
                faces = listOf(face(0.41f, 0.40f, 0.10f)),
            ),
            at(22, 34, 6),
            "portrait",
        )
        b.add(
            photo(
                "blurry",
                0.49f,
                toEmbedding(space.blend(portrait, 0.70)),
                hashes.hash(portraitMoment, 3),
                selfiePalette(0.006f),
                7050f,
                ShotScale.MEDIUM,
                aesthetic = 0.33f,
                sharpness = 0.46f,
                faces = listOf(face(0.43f, 0.42f, 0.10f, sharpness = 0.5f)),
            ),
            at(22, 34, 16),
            "portrait",
        )
        b.add(
            photo(
                "dark",
                0.36f,
                toEmbedding(space.blend(space.blend(venue, momentFromVenue), 0.97)),
                hashes.hash(hashes.moment(), 3),
                seriesPalette(),
                7180f,
                ShotScale.WIDE,
                aesthetic = 0.30f,
                sharpness = 0.40f,
                meanL = 0.18f,
            ),
            at(23, 10, 0),
            "dark",
        )
        return b.build()
    }

    @Test
    fun drawer_neverOffersAFrameAddingLessThanHalfTheHero() {
        val set = drawerSet()
        val decision = DecisionEngine.decide(set.photos, set.times)
        for (option in offered(decision)) {
            assertTrue("${option.mode} repeats a moment: ${describe(decision, set)}", set.repeatedMoments(option.photoIds).isEmpty())
            assertFalse("${option.mode} offers the Q 0.36 frame: ${describe(decision, set)}", "dark" in option.photoIds)
        }
    }

    @Test
    fun drawer_neverOffersTwoFramesOfOneMoment() {
        for ((name, set) in listOf("night-out" to nightOut(), "burst" to seatedBurst(), "drawer" to drawerSet())) {
            val decision = DecisionEngine.decide(set.photos, set.times)
            for (option in offered(decision)) {
                assertTrue(
                    "$name: ${option.mode} repeats a moment: ${describe(decision, set)}",
                    set.repeatedMoments(option.photoIds).isEmpty(),
                )
            }
        }
    }

    // ---- (o) redundancy is judged over whole tier-1 clusters ----

    /**
     * The real 210001 chain: a wide table frame W links to the table champion C only weakly ([wToChampion]) but to
     * C's cluster-mate M at 0.67, 9 s apart. C and M share a tier-1 cluster through their hashes (pHash 10, dHash 6,
     * cosine 0.80, as 210007/210010). A two-person photo and a selfie 2.5 h later are the other moments; the selfie is
     * strong enough (Q ratio 0.84) that a rule treating W as novel would admit a four-photo set {C, W, pair, selfie}.
     */
    private fun tableChain(wToChampion: Double): PhotoSet {
        val space = EmbeddingSpace()
        val hashes = HashSpace()
        val venue = space.fresh()
        val b = SetBuilder()
        val ax = space.fresh()
        val bx = space.fresh()
        val cx = space.fresh()
        val wToMate = 0.67
        val y = (wToMate - 0.80 * wToChampion) / 0.60
        val z = sqrt(1.0 - wToChampion * wToChampion - y * y)
        val champion = ax
        val mate = space.combine(ax to 0.80, bx to 0.60)
        val wide = space.combine(ax to wToChampion, bx to y, cx to z)
        val tableMoment = hashes.moment()
        val cHash = hashes.hash(tableMoment, flip = 3)
        val wHash = hashes.hash(tableMoment, flip = 3)
        // Bits 50..59 lie in the moment row, untouched by frame flips: M stays 10 from C and 32 from W.
        val mPHash = cHash xor (((1L shl 10) - 1) shl 50)
        val mDHash = HashSpace.dHashOf(cHash) xor 0b111111L
        b.add(
            photo("C", 0.76f, toEmbedding(champion), cHash, tablePalette(), 7490f, ShotScale.WIDE, aesthetic = 0.58f),
            at(21, 0, 23),
            "table",
        )
        b.add(
            photo("M", 0.72f, toEmbedding(mate), mPHash, tablePalette(0.004f), 7800f, ShotScale.WIDE, aesthetic = 0.53f, dHash = mDHash),
            at(21, 0, 10),
            "table",
        )
        b.add(
            photo("W", 0.72f, toEmbedding(wide), wHash, tablePalette(0.008f), 7870f, ShotScale.WIDE, aesthetic = 0.50f),
            at(21, 0, 1),
            "table",
        )
        b.add(
            photo(
                "pair",
                0.66f,
                toEmbedding(space.blend(space.blend(venue, momentFromVenue), 0.97)),
                hashes.hash(hashes.moment(), flip = 3),
                peoplePalette(),
                7660f,
                ShotScale.MEDIUM,
                aesthetic = 0.43f,
                landscape = true,
                faces = listOf(face(0.15f, 0.37f, 0.0888f), face(0.79f, 0.41f, 0.0255f)),
            ),
            at(22, 33, 14),
            "pair",
        )
        b.add(
            photo(
                "selfie",
                0.64f,
                toEmbedding(space.blend(space.blend(venue, momentFromVenue), 0.97)),
                hashes.hash(hashes.moment(), flip = 3),
                selfiePalette(),
                5860f,
                ShotScale.MEDIUM,
                aesthetic = 0.54f,
                faces = listOf(face(0.47f, 0.70f, 0.20f)),
            ),
            at(22, 33, 44),
            "selfie",
        )
        return b.build()
    }

    private fun assertChainNeverPairsWideWithChampion(wToChampion: Double) {
        val set = tableChain(wToChampion)
        val cos = { x: String, y: String -> SelectionFixtures.cosine(set.byId(x).embedding, set.byId(y).embedding) }
        assertEquals(wToChampion, cos("W", "C"), 1e-5)
        assertEquals(0.67, cos("W", "M"), 1e-5)
        assertEquals(0.80, cos("C", "M"), 1e-5)
        val decision = DecisionEngine.decide(set.photos, set.times)
        val cluster = decision.clusters.single { "C" in it.memberIds }
        assertEquals("C and M share one tier-1 cluster: ${decision.clusters}", setOf("C", "M"), cluster.memberIds.toSet())
        assertEquals("C is its champion", "C", cluster.championId)
        assertEquals("W is its own cluster: ${decision.clusters}", listOf("W"), decision.clusters.single { "W" in it.memberIds }.memberIds)
        for (option in everyOption(decision)) {
            assertFalse(
                "W chosen or offered with the champion in ${option.mode}: ${describe(decision, set)}",
                "W" in option.photoIds && "C" in option.photoIds,
            )
        }
        assertTrue(
            "at most one table frame: ${describe(decision, set)}",
            decision.chosen.photoIds.count { set.momentOf[it] == "table" } <= 1,
        )
    }

    @Test
    fun wideTableFrame_linkedThroughTheChampionsCluster_isNeverChosenWithTheChampion() {
        // The measured chain: 0.47 to the champion (210001 vs 210023), 0.67 to the cluster-mate (210001 vs 210010).
        assertChainNeverPairsWideWithChampion(wToChampion = 0.47)
    }

    @Test
    fun wideTableFrame_unrelatedToTheChampionButLinkedToItsClusterMate_isStillTheSameMoment() {
        // 0.33 to the champion is below any cosine-ramp start, so only cluster-level redundancy ties W to C.
        assertChainNeverPairsWideWithChampion(wToChampion = 0.33)
    }

    // ---- (p) a face the detector misses in one frame does not make that frame new ----

    /**
     * Three frames of one seated person in 6 s (cosine 0.59-0.62, the series median; hash distance 20). Two carry the
     * person's face at 0.019 and 0.0204 of the frame (the analyzer's 0.02 "prominent" cut, well below the engine's
     * 0.04 robust-face cut, so the face veto never applies here), and the middle frame has no detected face at all,
     * as 233929 in the trace. The case guards the "missing face = new moment" failure, not the veto boundary.
     */
    private fun flickerBurst(): PhotoSet {
        val space = EmbeddingSpace()
        val hashes = HashSpace()
        val ax = space.fresh()
        val bx = space.fresh()
        val cx = space.fresh()
        // Gram: faced·faced2 = 0.60, faced·noFace = 0.59, faced2·noFace = 0.62.
        val faced = ax
        val faced2 = space.combine(ax to 0.60, bx to 0.80)
        val y3 = (0.62 - 0.60 * 0.59) / 0.80
        val noFace = space.combine(ax to 0.59, bx to y3, cx to sqrt(1.0 - 0.59 * 0.59 - y3 * y3))
        val moment = hashes.moment()
        val b = SetBuilder()
        b.add(
            photo(
                "s1",
                0.733f,
                toEmbedding(faced),
                hashes.hash(moment, 2),
                seriesPalette(),
                9610f,
                aesthetic = 0.51f,
                faces = listOf(face(0.35f, 0.42f, 0.0190f)),
            ),
            at(23, 39, 27),
            "series",
        )
        b.add(
            photo("s2-no-face", 0.720f, toEmbedding(noFace), hashes.hash(moment, 2), seriesPalette(0.002f), 8590f, aesthetic = 0.44f),
            at(23, 39, 29),
            "series",
        )
        b.add(
            photo(
                "s3",
                0.707f,
                toEmbedding(faced2),
                hashes.hash(moment, 2),
                seriesPalette(0.004f),
                8520f,
                aesthetic = 0.49f,
                faces = listOf(face(0.61f, 0.45f, 0.0204f)),
            ),
            at(23, 39, 22),
            "series",
        )
        return b.build()
    }

    @Test
    fun burstFrameWithoutItsFace_isNotANewMoment() {
        for (set in listOf(flickerBurst(), flickerBurst().withoutTimes())) {
            val decision = DecisionEngine.decide(set.photos, set.times)
            assertEquals("one moment, one photo: ${describe(decision, set)}", StoryMode.HERO, decision.chosen.mode)
            for (option in offered(decision)) {
                assertTrue("${option.mode} repeats the burst: ${describe(decision, set)}", set.repeatedMoments(option.photoIds).isEmpty())
            }
        }
    }

    // ---- fixture sanity: the sets stay inside the measured distributions ----

    @Test
    fun fixtures_stayInsideTheMeasuredDistributions() {
        for ((name, set) in listOf(
            "night-out" to nightOut(withCopy = true),
            "burst" to seatedBurst(),
            "distinct" to distinctMoments(),
            "drawer" to drawerSet(),
            "two evenings" to twoEvenings(),
            "flicker" to flickerBurst(),
        )) {
            val photos = set.photos
            for (i in photos.indices) {
                for (j in i + 1 until photos.size) {
                    val a = photos[i]
                    val b = photos[j]
                    val cos = SelectionFixtures.cosine(a.embedding, b.embedding)
                    val hash = minOf(PerceptualHash.hamming(a.pHash, b.pHash), PerceptualHash.hamming(a.dHash, b.dHash))
                    val pair = "$name ${a.candidateId}/${b.candidateId} cos $cos hash $hash"
                    val copies = a.candidateId.removeSuffix("-jpg") == b.candidateId.removeSuffix("-jpg")
                    when {
                        copies -> {
                            assertTrue(pair, cos > 0.999 && hash == 0)
                        }

                        set.momentOf[a.candidateId] == set.momentOf[b.candidateId] -> {
                            assertTrue(pair, cos in 0.40..0.90)
                            assertTrue(pair, hash in 16..34)
                        }

                        else -> {
                            assertTrue(pair, cos <= 0.58)
                            assertTrue(pair, hash >= 20)
                        }
                    }
                }
            }
        }
    }
}
