package com.akane.akastudio.core.curator.decision.selection

import com.akane.akastudio.core.color.api.LabStats
import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.color.api.OkLab
import com.akane.akastudio.core.color.api.PaletteSwatch
import com.akane.akastudio.core.color.api.SceneType
import com.akane.akastudio.core.curator.api.CurationOption
import com.akane.akastudio.core.curator.api.FaceObservation
import com.akane.akastudio.core.curator.api.PhotoAnalysis
import com.akane.akastudio.core.curator.api.RejectReason
import com.akane.akastudio.core.curator.api.SaliencyMap
import com.akane.akastudio.core.curator.api.ShotScale
import com.akane.akastudio.core.curator.api.TechnicalScore
import com.akane.akastudio.core.curator.decision.Decision
import com.akane.akastudio.core.curator.decision.DecisionEngine
import com.akane.akastudio.core.curator.hash.PerceptualHash
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Opt-in selection checks on numbers derived from a real curation trace (28 photos of a night out, measured on the
 * device): Q components, palettes, face boxes, hashes, capture times and the measured embedding cosine matrix, with
 * synthetic embeddings that reproduce that matrix. The data comes from photos of real people, so it is never committed;
 * the test is skipped when no data file is given.
 *
 * Data file (JSON, derived from a trace's `trace.json`; keep it under the gitignored `docs/stabilization/evidence/`):
 * - `photos`: one object per analyzed photo with `id`, `quality`, `aesthetic`, `composition`, `technical`
 *   (`sharpness`, `exposure`, `faceQuality`, `total`), `pHash` and `dHash` (unsigned hex), `palette` (`[L, a, b, w]`
 *   rows), `labStats` (meanL, meanA, meanB, stdL, stdA, stdB, weight), `cct`, `shotScale`, `scene`, `faces`
 *   (`[left, top, right, bottom, leftEye, rightEye, sharpness]` rows), `rejectReason`, `captureEpochMillis`,
 *   `widthPx`, `heightPx` (upright), `screenCapture`, and `embedding`: rows of `V·sqrt(Λ)` from the eigendecomposition
 *   of the measured cosine matrix, normalized and zero-padded so the last axis is unused.
 * - `cosine`: the measured matrix in `photos` order; `tableIds`: the table scene's frames; `formatPairs`: one capture
 *   saved in two formats; `cosineReconstructionMaxError`.
 *
 * Run:
 * ```
 * AKASTUDIO_SELECTION_TRACE=$PWD/docs/stabilization/evidence/curation-20261102/selection_trace.json \
 *   ./gradlew :core:vision-curator:testDebugUnitTest --tests '*RealTraceSelectionTest*' --rerun --console=plain -q
 * ```
 * The module's test task does not forward `-D` properties from the Gradle command line, so the path comes from the
 * `AKASTUDIO_SELECTION_TRACE` environment variable (the system property `akastudio.selectionTrace` also works when the
 * test runs in an IDE). `--rerun` is needed because Gradle does not see the variable as a task input.
 *
 * "Same moment" here is ground truth for the test, not the engine's rule: two photos under a minute apart with
 * cosine >= 0.35, or with min(pHash, dHash) distance <= 18.
 */
class RealTraceSelectionTest {
    private class Trace(
        val photos: List<PhotoAnalysis>,
        val times: Map<String, Long?>,
        val cosine: Map<Pair<String, String>, Double>,
        val tableIds: Set<String>,
        val formatPairs: List<Pair<String, String>>,
        val screenIds: Set<String>,
        val reconstructionError: Double,
    ) {
        fun cos(
            a: String,
            b: String,
        ): Double = cosine[a to b] ?: cosine.getValue(b to a)
    }

    private fun tracePath(): File? {
        val path = System.getProperty("akastudio.selectionTrace") ?: System.getenv("AKASTUDIO_SELECTION_TRACE")
        return path?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }
    }

    private fun loadTrace(): Trace {
        val file = tracePath()
        assumeTrue("no selection trace: set AKASTUDIO_SELECTION_TRACE to the derived JSON", file != null)
        @Suppress("UNCHECKED_CAST")
        val root = MiniJson.parse(checkNotNull(file).readText()) as Map<String, Any?>
        val rows = root.list("photos").map { it.obj() }
        val photos = rows.map(::toAnalysis)
        val ids = photos.map { it.candidateId }
        val matrix = root.list("cosine").map { row -> row.list().map { it.num() } }
        val cosine = HashMap<Pair<String, String>, Double>()
        for (i in ids.indices) for (j in ids.indices) cosine[ids[i] to ids[j]] = matrix[i][j]
        return Trace(
            photos = photos,
            times = rows.associate { it.str("id") to (it["captureEpochMillis"] as Double?)?.toLong() },
            cosine = cosine,
            tableIds = root.list("tableIds").map { it as String }.toSet(),
            formatPairs = root.list("formatPairs").map { p -> p.list().let { it[0] as String to it[1] as String } },
            screenIds = photos.filter { it.screenCapture }.map { it.candidateId }.toSet(),
            reconstructionError = root["cosineReconstructionMaxError"].num(),
        )
    }

    // ---- ground truth ----

    private fun sameMoment(
        trace: Trace,
        a: String,
        b: String,
    ): Boolean {
        val pa = trace.photos.single { it.candidateId == a }
        val pb = trace.photos.single { it.candidateId == b }
        val hash = minOf(PerceptualHash.hamming(pa.pHash, pb.pHash), PerceptualHash.hamming(pa.dHash, pb.dHash))
        if (hash <= 18) return true
        val ta = trace.times[a] ?: return false
        val tb = trace.times[b] ?: return false
        return abs(ta - tb) <= 60_000L && trace.cos(a, b) >= 0.35
    }

    /** Pairs that are one moment beyond any tuning: under a minute apart with cosine >= 0.55, or tier-1 hash close. */
    private fun unmistakablySameMoment(
        trace: Trace,
        a: String,
        b: String,
    ): Boolean {
        val pa = trace.photos.single { it.candidateId == a }
        val pb = trace.photos.single { it.candidateId == b }
        if (PerceptualHash.hamming(pa.pHash, pb.pHash) <= 10 || PerceptualHash.hamming(pa.dHash, pb.dHash) <= 8) return true
        val ta = trace.times[a] ?: return false
        val tb = trace.times[b] ?: return false
        return abs(ta - tb) <= 60_000L && trace.cos(a, b) >= 0.55
    }

    private fun repeatedPairs(
        ids: List<String>,
        same: (String, String) -> Boolean,
    ): List<Pair<String, String>> =
        ids.indices.flatMap { i -> (i + 1 until ids.size).map { ids[i] to ids[it] } }.filter { (a, b) -> same(a, b) }

    private fun everyOption(decision: Decision): List<CurationOption> = decision.alternatives.values + decision.chosen

    private fun describe(decision: Decision): String =
        "chosen ${decision.chosen.mode} ${decision.chosen.photoIds}; offered " +
            decision.alternatives.entries.joinToString { (mode, o) -> "$mode ${o.photoIds}" }

    // ---- tests ----

    @Test
    fun realTrace_embeddingsReproduceTheMeasuredCosines() {
        val trace = loadTrace()
        var worst = 0.0
        for (a in trace.photos) {
            for (b in trace.photos) {
                worst = maxOf(worst, abs(SelectionFixtures.cosine(a.embedding, b.embedding) - trace.cos(a.candidateId, b.candidateId)))
            }
        }
        assertTrue("synthetic embeddings off by $worst (generator reported ${trace.reconstructionError})", worst < 1e-4)
    }

    @Test
    fun realTrace_chosenStoryRepeatsNoMoment() {
        val trace = loadTrace()
        val decision = DecisionEngine.decide(trace.photos, trace.times)
        val repeats = repeatedPairs(decision.chosen.photoIds) { a, b -> sameMoment(trace, a, b) }
        assertTrue("repeated moments $repeats: ${describe(decision)}", repeats.isEmpty())
    }

    @Test
    fun realTrace_withoutCaptureTimes_chosenStoryRepeatsNoMoment() {
        val trace = loadTrace()
        val decision = DecisionEngine.decide(trace.photos, trace.photos.associate { it.candidateId to null })
        val repeats = repeatedPairs(decision.chosen.photoIds) { a, b -> sameMoment(trace, a, b) }
        assertTrue("repeated moments $repeats: ${describe(decision)}", repeats.isEmpty())
    }

    @Test
    fun realTrace_offeredOptionsRepeatNoUnmistakableMoment() {
        val trace = loadTrace()
        val decision = DecisionEngine.decide(trace.photos, trace.times)
        for (option in decision.alternatives.values) {
            val repeats = repeatedPairs(option.photoIds) { a, b -> unmistakablySameMoment(trace, a, b) }
            assertTrue("${option.mode} repeats $repeats: ${describe(decision)}", repeats.isEmpty())
        }
    }

    @Test
    fun realTrace_formatCopiesAreNeverTogether() {
        val trace = loadTrace()
        val decision = DecisionEngine.decide(trace.photos, trace.times)
        for (option in everyOption(decision)) {
            for ((a, b) in trace.formatPairs) {
                assertFalse("$a and $b together in ${option.mode}: ${describe(decision)}", a in option.photoIds && b in option.photoIds)
            }
        }
    }

    @Test
    fun realTrace_chosenStoryIsNotFilledWithTableFrames() {
        val trace = loadTrace()
        val decision = DecisionEngine.decide(trace.photos, trace.times)
        val table = decision.chosen.photoIds.count { it in trace.tableIds }
        assertTrue("$table table frames: ${describe(decision)}", table < 3)
    }

    @Test
    fun realTrace_screenCaptureIsNeverChosenOrOffered() {
        val trace = loadTrace()
        assumeTrue("trace has no screen capture", trace.screenIds.isNotEmpty())
        for (times in listOf(trace.times, trace.photos.associate { it.candidateId to null })) {
            val decision = DecisionEngine.decide(trace.photos, times)
            for (option in everyOption(decision)) {
                assertTrue("screen capture in ${option.mode}: ${describe(decision)}", option.photoIds.none { it in trace.screenIds })
            }
        }
    }

    /**
     * The table scene alone, as the phone acceptance pass imported it: five reframes plus one of the two format copies
     * of 21:00:25 (HEIC, or the JPG in the second variant). Every frame shows the same table within 28 s.
     */
    private fun tablesOnlySubsets(trace: Trace): List<List<PhotoAnalysis>> {
        val reframes = listOf("205957", "210001", "210007", "210010", "210023").map { "20261017_$it.heic" }
        return listOf("20261017_210025.heic", "20261017_210025.jpg").map { copy ->
            val ids = (reframes + copy).toSet()
            trace.photos.filter { it.candidateId in ids }.also { check(it.size == ids.size) { "subset ids missing from the trace" } }
        }
    }

    /**
     * On the phone this subset produced COLLAGE_2 of 21:00:25 and 21:00:01 (cosine 0.40, 24 s apart): the same table
     * twice. With capture times known, the story shows one table frame and the drawer offers no collage of two.
     */
    @Test
    fun realTrace_tablesOnlySubset_neverShowsTheTableTwice() {
        val trace = loadTrace()
        for (photos in tablesOnlySubsets(trace)) {
            val ids = photos.map { it.candidateId }.toSet()
            val decision = DecisionEngine.decide(photos, trace.times.filterKeys { it in ids })
            assertTrue("chosen shows the table twice: ${describe(decision)}", decision.chosen.photoIds.count { it in ids } <= 1)
            for (option in decision.alternatives.values) {
                assertTrue("${option.mode} offers the table twice: ${describe(decision)}", option.photoIds.count { it in ids } <= 1)
            }
        }
    }

    /**
     * The same subset with every capture time unknown (a zip without EXIF). This check is deliberately weaker: without
     * time, reframes at cosine 0.40-0.60 overlap with distinct moments (the real set's cross-moment maximum is 0.58),
     * so it only requires that the chosen story never pairs two frames at cosine >= 0.6.
     */
    @Test
    fun realTrace_tablesOnlySubset_withoutCaptureTimes_neverPairsCloseReframes() {
        val trace = loadTrace()
        for (photos in tablesOnlySubsets(trace)) {
            val decision = DecisionEngine.decide(photos, photos.associate { it.candidateId to null })
            val close = repeatedPairs(decision.chosen.photoIds) { a, b -> trace.cos(a, b) >= 0.6 }
            assertTrue("chosen pairs $close at cosine >= 0.6: ${describe(decision)}", close.isEmpty())
        }
    }

    /**
     * The user's grid had one table frame that is not in the zip. Adding a table reframe classified DETAIL (high Q,
     * cosine about 0.78 to the nearest table frame, 5 s from its neighbours) lifts the old rule's rhythm term enough to
     * fill the story with table frames; the story must still show the table once.
     */
    @Test
    fun realTrace_withTheMissingDetailTableFrame_storyStillShowsTheTableOnce() {
        val trace = loadTrace()
        val extra = injectedTableFrame(trace)
        val photos = trace.photos + extra
        val times = trace.times + (extra.candidateId to trace.times.getValue("20261017_210010.heic")?.plus(5_000L))
        val decision = DecisionEngine.decide(photos, times)
        val tableIds = trace.tableIds + extra.candidateId
        val chosen = decision.chosen.photoIds
        assertTrue("table frames ${chosen.filter { it in tableIds }}: ${describe(decision)}", chosen.count { it in tableIds } <= 1)
    }

    /**
     * A table reframe orthogonal to every other photo except through the table frames' mean direction, scaled so its
     * highest cosine to a real table frame is 0.78 (below the 0.82 cluster cosine), with hashes >= 16 from every photo.
     */
    private fun injectedTableFrame(trace: Trace): PhotoAnalysis {
        val table = trace.photos.filter { it.candidateId in trace.tableIds }
        val dims = table.first().embedding.size
        val mean = DoubleArray(dims) { k -> table.sumOf { it.embedding[k].toDouble() } }
        val norm = sqrt(mean.sumOf { it * it })
        for (k in mean.indices) mean[k] /= norm
        val spare = dims - 1
        check(trace.photos.all { abs(it.embedding[spare]) < 1e-9f }) { "no spare embedding axis" }
        val maxCos = table.maxOf { p -> (0 until dims).sumOf { mean[it] * p.embedding[it] } }
        val lambda = 0.78 / maxCos
        val v = DoubleArray(dims) { lambda * mean[it] }.also { it[spare] = sqrt(1 - lambda * lambda) }
        val random = Random(1793596282031L)

        fun farHash(pick: (PhotoAnalysis) -> Long): Long {
            while (true) {
                val h = random.nextLong()
                if (trace.photos.all { PerceptualHash.hamming(h, pick(it)) >= 16 }) return h
            }
        }
        val template = trace.photos.single { it.candidateId == "20261017_210010.heic" }
        return template.copy(
            candidateId = "injected_table_detail",
            quality = 0.745f,
            aesthetic = 0.56f,
            shotScale = ShotScale.DETAIL,
            embedding = FloatArray(dims) { v[it].toFloat() },
            pHash = farHash { it.pHash },
            dHash = farHash { it.dHash },
        )
    }

    // ---- JSON to analyses ----

    private fun toAnalysis(o: Map<String, Any?>): PhotoAnalysis {
        val technical = o["technical"].obj()
        val lab = o.list("labStats").map { it.num().toFloat() }
        val width = o["widthPx"].num()
        val height = o["heightPx"].num()
        val (sw, sh) = if (width >= height) 64 to (64 * height / width).roundToInt() else (64 * width / height).roundToInt() to 64
        return PhotoAnalysis(
            candidateId = o.str("id"),
            technical =
                TechnicalScore(
                    sharpness = technical["sharpness"].num().toFloat(),
                    exposure = technical["exposure"].num().toFloat(),
                    faceQuality = technical["faceQuality"].num().toFloat(),
                    total = technical["total"].num().toFloat(),
                ),
            aesthetic = o["aesthetic"].num().toFloat(),
            composition = o["composition"].num().toFloat(),
            quality = o["quality"].num().toFloat(),
            embedding = o.list("embedding").map { it.num().toFloat() }.toFloatArray(),
            dHash = java.lang.Long.parseUnsignedLong(o.str("dHash"), 16),
            pHash = java.lang.Long.parseUnsignedLong(o.str("pHash"), 16),
            palette =
                o.list("palette").map { s ->
                    val c = s.list().map { it.num().toFloat() }
                    PaletteSwatch(OkLab(c[0], c[1], c[2]), c[3])
                },
            labStats = LabStats(lab[0], lab[1], lab[2], lab[3], lab[4], lab[5], lab[6]),
            cctKelvin = o["cct"].num().toFloat(),
            shotScale = ShotScale.valueOf(o.str("shotScale")),
            scene = SceneType.entries.firstOrNull { it.name == o["scene"] } ?: SceneType.GENERAL,
            faces =
                o.list("faces").map { f ->
                    val c = f.list().map { it.num().toFloat() }
                    FaceObservation(NormRect(c[0], c[1], c[2], c[3]), c[4], c[5], c[6])
                },
            saliency = SaliencyMap(sw, sh, FloatArray(sw * sh) { 0.5f }),
            rejectReason = (o["rejectReason"] as String?)?.let(RejectReason::valueOf),
            screenCapture = o["screenCapture"] == true,
        )
    }

    private fun Any?.num(): Double = (this as Number).toDouble()

    @Suppress("UNCHECKED_CAST")
    private fun Any?.obj(): Map<String, Any?> = this as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun Any?.list(): List<Any?> = this as List<Any?>

    private fun Map<String, Any?>.list(key: String): List<Any?> = getValue(key).list()

    private fun Map<String, Any?>.str(key: String): String = getValue(key) as String

    /** Minimal JSON reader for the derived trace: objects, arrays, strings, numbers (as Double), booleans, null. */
    private object MiniJson {
        fun parse(text: String): Any? =
            Reader(text).run {
                value().also {
                    skipWs()
                    check(pos == text.length) { "trailing data at $pos" }
                }
            }

        private class Reader(
            val s: String,
        ) {
            var pos = 0

            fun skipWs() {
                while (pos < s.length && s[pos].isWhitespace()) pos++
            }

            fun value(): Any? {
                skipWs()
                return when (val c = s[pos]) {
                    '{' -> obj()
                    '[' -> arr()
                    '"' -> str()
                    't' -> literal("true", true)
                    'f' -> literal("false", false)
                    'n' -> literal("null", null)
                    else -> if (c == '-' || c.isDigit()) num() else error("unexpected '$c' at $pos")
                }
            }

            private fun literal(
                word: String,
                v: Any?,
            ): Any? {
                check(s.startsWith(word, pos)) { "expected $word at $pos" }
                pos += word.length
                return v
            }

            private fun num(): Double {
                val start = pos
                while (pos < s.length && (s[pos].isDigit() || s[pos] in "+-.eE")) pos++
                return s.substring(start, pos).toDouble()
            }

            private fun str(): String {
                val out = StringBuilder()
                pos++
                while (s[pos] != '"') {
                    val c = s[pos++]
                    if (c != '\\') {
                        out.append(c)
                        continue
                    }
                    when (val e = s[pos++]) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'r' -> out.append('\r')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'u' -> out.append(s.substring(pos, pos + 4).toInt(16).toChar()).also { pos += 4 }
                        else -> out.append(e)
                    }
                }
                pos++
                return out.toString()
            }

            private fun arr(): List<Any?> {
                val out = ArrayList<Any?>()
                pos++
                skipWs()
                if (s[pos] == ']') return out.also { pos++ }
                while (true) {
                    out += value()
                    skipWs()
                    if (s[pos++] == ']') return out
                }
            }

            private fun obj(): Map<String, Any?> {
                val out = LinkedHashMap<String, Any?>()
                pos++
                skipWs()
                if (s[pos] == '}') return out.also { pos++ }
                while (true) {
                    skipWs()
                    val key = str()
                    skipWs()
                    check(s[pos++] == ':') { "expected ':' at $pos" }
                    out[key] = value()
                    skipWs()
                    if (s[pos++] == '}') return out
                }
            }
        }
    }
}
