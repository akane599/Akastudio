package com.akane.akastudio.core.curator.decision

import com.akane.akastudio.core.curator.api.CurationOption
import com.akane.akastudio.core.curator.api.PhotoAnalysis
import com.akane.akastudio.core.curator.api.RejectReason
import com.akane.akastudio.core.curator.api.SceneCluster
import com.akane.akastudio.core.curator.api.StoryMode
import com.akane.akastudio.core.curator.api.SynergyBreakdown
import com.akane.akastudio.core.curator.api.Warmth

/**
 * Outcome of one curator decision.
 *
 * @property chosen the option the story uses.
 * @property alternatives the options the mode drawer offers, including `chosen.mode`: HERO always, and each collage
 *   mode that has a set clearing the offer bar (see [DecisionEngine]); a mode with no such set is absent.
 * @property clusters burst clusters of the non-rejected photos (see [Clustering.cluster]).
 * @property rejectedIds ids eliminated at the technical gate, plus ids whose quality is not a finite number (handled as
 *   UNREADABLE so one corrupt analysis can never open a gate), sorted ascending.
 * @property champions the champion of each cluster, ordered as the decision pool: quality descending, then aesthetic
 *   descending, then id ascending. Screen captures are listed here but never enter a set or become the hero.
 */
data class Decision(
    val chosen: CurationOption,
    val alternatives: Map<StoryMode, CurationOption>,
    val clusters: List<SceneCluster>,
    val rejectedIds: List<String>,
    val champions: List<String>,
)

/**
 * The Hero-vs-Collage decision over analyzed photos. Pure and deterministic: shuffling the input does not change the
 * result.
 *
 * **Pool.** The cluster champions without [PhotoAnalysis.screenCapture], in pool order (quality Q descending, then
 * aesthetic descending, then id), top [DecisionConfig.subsetPoolSize]. There is no absolute quality floor. A photo
 * whose quality is NaN or infinite is treated as rejected (UNREADABLE) before clustering, and every gate compares so
 * that a non-finite gain or score fails it: the decision fails closed.
 *
 * **HERO** is the first pool photo (Q1; the top champion overall when every champion is a screen capture), scored
 * Q1. Its layout is whatever [DecisionConfig.layoutFitter] picks for it among the catalogue's one-photo layouts, or
 * null without one.
 *
 * **Unique quality.** A set's members in pool order p1..pm have gains `gain_1 = Q_1` and
 * `gain_k = Q_k · (1 - max_{j<k} r(p_k, p_j))`, with r the tier-2 [Redundancy]. A frame repeating an earlier member
 * adds almost nothing, however well its colours match. r between two champions is the maximum over every member pair of
 * their two tier-1 clusters, so a frame linked to a moment only through a non-champion burst member still repeats it.
 *
 * **Score_Collage** ranks sets of one size m: `(0.5 · mean(gain) + 0.5 · Ψ) · (1 - 0.03 · (m - 2))`, with Ψ the
 * unchanged [Synergy] formula. Ties go to the lexicographically smaller sorted id list. It never compares sizes.
 *
 * **Gates.** A set is *admissible* (may be chosen automatically) when every gain_k for k >= 2 is at least
 * [DecisionConfig.admissionBar]`(m) · Q1`; it is *offerable* (may appear in the drawer) when every such gain is at
 * least [DecisionConfig.offerBar]` · Q1`. Both also require that the known capture times span at most
 * [DecisionConfig.maxEventSpanMillis] (unknown times pass; a span too large for a Long exceeds it) and a layout fit: when the catalogue has layouts for m,
 * [LayoutFitter.fit] must return one (its slot order is kept, the anchor stays in slot 0, and its id is recorded as
 * [CurationOption.layoutId]); with none for m the set is feasible in the anchor order with a null layout id.
 *
 * **Search.** Exhaustive over the pool in pool order, pruning a prefix as soon as a member's gain falls below the
 * offer bar or the span is exceeded: later members never change an earlier member's gain, and the bars only rise
 * with m, so no pruned superset could pass.
 *
 * **Choice.** Per collage mode, the alternative is the best admissible set that fits, else the best offerable set
 * that fits, else the mode is not offered. The chosen option is the largest m whose alternative is admissible; with
 * none, HERO. "Largest wins" is safe because every extra photo must bring a rising share of Q1 as new content.
 *
 * **Order.** Every option lists its anchor first: the highest-aesthetic member (the colour anchor, caption and
 * background source, and the suggested look's input), then quality descending; a layout fit may reorder slots 1+.
 */
object DecisionEngine {

    private const val ALL_REJECTED = "all candidates rejected"

    private const val NIGHT_MAX_KELVIN = 3600.0
    private const val NIGHT_MAX_LIGHTNESS = 0.35
    private const val GOLDEN_MAX_KELVIN = 4800.0
    private const val COOL_MIN_KELVIN = 6800.0
    private const val MAX_COLLAGE = 4

    private val COLLAGE_MODES = listOf(StoryMode.COLLAGE_2, StoryMode.COLLAGE_3, StoryMode.COLLAGE_4)

    /** Decision pool order: quality descending, then aesthetic descending, then id ascending. */
    private val POOL_ORDER: Comparator<PhotoAnalysis> = compareByDescending<PhotoAnalysis> { it.quality }
        .thenByDescending { it.aesthetic }
        .thenBy { it.candidateId }

    /**
     * Decides the story mode and photos for [analyses]. [captureTimes] maps candidate id to capture epoch millis
     * (missing or null = unknown).
     *
     * [checkpoint] is called at entry and throughout the CPU-bound stages (clustering pairs, palette pairs, champion
     * redundancy pairs, every enumerated subset); the curation engine passes a coroutine cancellation check, so a
     * cancelled collector stops here instead of finishing the decision. Anything it throws propagates unchanged; when it returns normally the
     * decision is identical to one made without it.
     *
     * @throws IllegalArgumentException when [analyses] is empty or has duplicate ids.
     * @throws IllegalStateException with message "all candidates rejected" when every photo has a reject reason or a
     *   non-finite quality; the curation engine maps it to its error state; also when the layout fitter breaks its
     *   contract.
     */
    fun decide(
        analyses: List<PhotoAnalysis>,
        captureTimes: Map<String, Long?>,
        config: DecisionConfig = DecisionConfig(),
        checkpoint: () -> Unit = {},
    ): Decision {
        checkpoint()
        require(analyses.isNotEmpty()) { "analyses must not be empty" }
        val sane = analyses.map { analysis ->
            if (analysis.rejectReason == null && !analysis.quality.isFinite()) {
                analysis.copy(rejectReason = RejectReason.UNREADABLE)
            } else {
                analysis
            }
        }
        val clusters = Clustering.cluster(sane, config, checkpoint)
        check(clusters.isNotEmpty()) { ALL_REJECTED }
        val byId = sane.associateBy { it.candidateId }
        val champions = clusters.map { byId.getValue(it.championId) }.sortedWith(POOL_ORDER)
        val hero = heroOption(champions, config)
        val q1 = hero.score

        val pool = champions.filterNot { it.screenCapture }.take(config.subsetPoolSize)
        val clusterOf = clusters.associate { cluster -> cluster.championId to cluster.memberIds.map(byId::getValue) }
        val search = SetSearch(pool, pool.map { clusterOf.getValue(it.candidateId) }, captureTimes, config, checkpoint)
        val offers = search.offers(q1)
        val chosen = COLLAGE_MODES.asReversed()
            .firstNotNullOfOrNull { mode -> offers[mode]?.takeIf { it.admissible }?.option }
            ?: hero
        return Decision(
            chosen = chosen,
            alternatives = linkedMapOf(StoryMode.HERO to hero).apply {
                for (mode in COLLAGE_MODES) offers[mode]?.let { put(mode, it.option) }
            },
            clusters = clusters,
            rejectedIds = rejectedIds(sane),
            champions = champions.map { it.candidateId },
        )
    }

    /**
     * Re-decides for the user's mode switch, keeping [previous]'s clusters, rejections, champions and alternatives.
     * A switch never re-scores: the offered options and their scores stay those of the original curation.
     *
     * - [forcedMode] null: a plain [decide].
     * - With fewer than m non-rejected photos, the largest feasible mode is used instead of [forcedMode].
     * - A mode [previous] already offers: exactly that alternative.
     * - Otherwise HERO is the hero of [decide], and COLLAGE_m is the best-scoring set of size m over the non-screen-
     *   capture champions (top [DecisionConfig.subsetPoolSize]) with no gain or span gate, preferring one the layout
     *   fitter accepts (else with a null layout id). With fewer than m such champions, they are filled up with the
     *   best non-champion member of distinct clusters, then with the remaining cluster members by quality, screen
     *   captures last. Rejected photos are never used. That option is added to the alternatives for its mode alone.
     *
     * [checkpoint] behaves as in [decide].
     *
     * @throws IllegalArgumentException when [analyses] has duplicate ids or lacks an id referenced by [previous].
     * @throws IllegalStateException with message "all candidates rejected" when [previous] has no clusters.
     */
    fun redecide(
        previous: Decision,
        analyses: List<PhotoAnalysis>,
        captureTimes: Map<String, Long?>,
        forcedMode: StoryMode?,
        config: DecisionConfig = DecisionConfig(),
        checkpoint: () -> Unit = {},
    ): Decision {
        checkpoint()
        if (forcedMode == null) return decide(analyses, captureTimes, config, checkpoint)
        Clustering.requireDistinctIds(analyses)
        val byId = analyses.associateBy { it.candidateId }
        fun lookup(id: String): PhotoAnalysis =
            requireNotNull(byId[id]) { "previous decision references unknown candidate: $id" }

        check(previous.clusters.isNotEmpty()) { ALL_REJECTED }
        val clusters = previous.clusters.map { cluster -> cluster.memberIds.map(::lookup) }
        val champions = previous.champions.map(::lookup)
        val available = clusters.sumOf { it.size }
        val feasible = StoryMode.entries.last { it.photoCount <= minOf(forcedMode.photoCount, available) }
        previous.alternatives[feasible]?.let { return previous.copy(chosen = it) }

        val chosen = if (feasible == StoryMode.HERO) {
            heroOption(champions, config)
        } else {
            forcedCollage(feasible, clusters, champions, captureTimes, config, checkpoint)
        }
        val alternatives = LinkedHashMap(previous.alternatives).apply { put(feasible, chosen) }
        return previous.copy(chosen = chosen, alternatives = alternatives)
    }

    /**
     * Overall warmth of [members] for the curator note: mean CCT < 3600 K with mean OKLab L < 0.35 is NIGHT_TUNGSTEN,
     * else mean CCT < 4800 K is GOLDEN_WARM, > 6800 K is COOL_BLUE, otherwise NEUTRAL.
     *
     * @throws IllegalArgumentException when [members] is empty.
     */
    fun warmth(members: List<PhotoAnalysis>): Warmth {
        require(members.isNotEmpty()) { "warmth needs at least one photo" }
        val meanKelvin = members.sumOf { it.cctKelvin.toDouble() } / members.size
        val meanLightness = members.sumOf { it.labStats.meanL.toDouble() } / members.size
        return when {
            meanKelvin < NIGHT_MAX_KELVIN && meanLightness < NIGHT_MAX_LIGHTNESS -> Warmth.NIGHT_TUNGSTEN
            meanKelvin < GOLDEN_MAX_KELVIN -> Warmth.GOLDEN_WARM
            meanKelvin > COOL_MIN_KELVIN -> Warmth.COOL_BLUE
            else -> Warmth.NEUTRAL
        }
    }

    /** The best non-screen-capture champion of [champions] (pool order), or the top one when all are captures. */
    private fun heroOption(champions: List<PhotoAnalysis>, config: DecisionConfig): CurationOption {
        val top = champions.firstOrNull { !it.screenCapture } ?: champions.first()
        val layoutId = config.layoutFitter.fit(listOf(top), config.layoutCatalog)?.layoutId
        return CurationOption(StoryMode.HERO, listOf(top.candidateId), top.quality, synergy = null, layoutId = layoutId)
    }

    private fun forcedCollage(
        mode: StoryMode,
        clusters: List<List<PhotoAnalysis>>,
        champions: List<PhotoAnalysis>,
        captureTimes: Map<String, Long?>,
        config: DecisionConfig,
        checkpoint: () -> Unit,
    ): CurationOption {
        val m = mode.photoCount
        val usable = champions.filterNot { it.screenCapture }
        if (usable.size >= m) {
            val pool = usable.take(config.subsetPoolSize)
            val clusterOf = clusters.associateBy { it.first().candidateId }
            return SetSearch(pool, pool.map { clusterOf.getValue(it.candidateId) }, captureTimes, config, checkpoint)
                .forced(m)
        }
        val runnersUp = clusters.mapNotNull { it.getOrNull(1) }.sortedWith(Clustering.BY_QUALITY_THEN_ID)
        val remaining = clusters.flatMap { it.drop(2) }.sortedWith(Clustering.BY_QUALITY_THEN_ID)
        val ranked = (champions + runnersUp + remaining).sortedBy { it.screenCapture }   // stable: captures last
        val members = ranked.take(m).sortedWith(POOL_ORDER)
        // Members may share a cluster here, so each counts as its own group: the fill is forced, not judged.
        val search = SetSearch(members, members.map { listOf(it) }, captureTimes, config, checkpoint)
        return search.forcedAll()
    }

    /** The highest-aesthetic member first (the chromatic anchor), then quality descending; ties by id. */
    internal fun anchorOrder(members: List<PhotoAnalysis>): List<PhotoAnalysis> {
        val anchor = members.sortedWith(
            compareByDescending<PhotoAnalysis> { it.aesthetic }.thenByDescending { it.quality }.thenBy { it.candidateId },
        ).first()
        return listOf(anchor) + members.filter { it !== anchor }.sortedWith(Clustering.BY_QUALITY_THEN_ID)
    }

    internal fun compareIdLists(a: List<String>, b: List<String>): Int {
        for (k in 0 until minOf(a.size, b.size)) {
            val c = a[k].compareTo(b[k])
            if (c != 0) return c
        }
        return a.size.compareTo(b.size)
    }

    private fun rejectedIds(analyses: List<PhotoAnalysis>): List<String> =
        analyses.filter { it.rejectReason != null }.map { it.candidateId }.sorted()

    /** A collage alternative and whether it may be chosen automatically. */
    private class Offer(val option: CurationOption, val admissible: Boolean)

    /** One scored set: [indices] into the pool, increasing (= quality order), with its unique-quality [gains]. */
    private class Scored(
        val indices: IntArray,
        val gains: FloatArray,
        val synergy: SynergyBreakdown,
        val score: Float,
        val admissible: Boolean,
        private val pool: List<PhotoAnalysis>,
    ) {
        val key: List<String> by lazy(LazyThreadSafetyMode.NONE) { indices.map { pool[it].candidateId }.sorted() }
        val members: List<PhotoAnalysis> get() = indices.map { pool[it] }
    }

    private val BEST_FIRST: Comparator<Scored> = compareByDescending<Scored> { it.score }
        .thenComparator { a, b -> compareIdLists(a.key, b.key) }

    /**
     * Set enumeration and scoring over [pool] (in pool order); [groups] are the tier-1 clusters the pool members
     * represent, aligned by index. The pairwise palette distances and cluster-level redundancies are computed once per
     * search.
     */
    private class SetSearch(
        private val pool: List<PhotoAnalysis>,
        groups: List<List<PhotoAnalysis>>,
        captureTimes: Map<String, Long?>,
        private val config: DecisionConfig,
        private val checkpoint: () -> Unit,
    ) {
        private val times: List<Long?> = pool.map { captureTimes[it.candidateId] }
        private val scorer = GroupScorer(pool, times, checkpoint)
        private val redundancy = RedundancyMatrix(pool, groups, captureTimes, config, checkpoint)

        /** The drawer's collage alternatives by mode (see the [DecisionEngine] KDoc), at hero quality [q1]. */
        fun offers(q1: Float): Map<StoryMode, Offer> {
            val bySize = HashMap<Int, MutableList<Scored>>()
            val offerFloor = config.offerBar * q1
            val prefix = IntArray(MAX_COLLAGE)
            val gains = FloatArray(MAX_COLLAGE)

            fun extend(depth: Int, start: Int, minTime: Long?, maxTime: Long?) {
                for (c in start until pool.size) {
                    val gain = redundancy.gain(c, prefix, depth)
                    if (depth >= 1 && !(gain >= offerFloor)) continue   // written so that NaN fails
                    val time = times[c]
                    val lo = if (time == null) minTime else minOf(time, minTime ?: time)
                    val hi = if (time == null) maxTime else maxOf(time, maxTime ?: time)
                    if (lo != null && hi != null && spanExceeds(lo, hi)) continue
                    prefix[depth] = c
                    gains[depth] = gain
                    val size = depth + 1
                    if (size >= 2) {
                        checkpoint()
                        val scored = score(prefix.copyOf(size), gains.copyOf(size), q1)
                        if (scored.score.isFinite()) bySize.getOrPut(size) { mutableListOf() } += scored
                    }
                    if (size < MAX_COLLAGE) extend(size, c + 1, lo, hi)
                }
            }
            extend(depth = 0, start = 0, minTime = null, maxTime = null)

            val offers = LinkedHashMap<StoryMode, Offer>()
            for (mode in COLLAGE_MODES) {
                val ranked = bySize[mode.photoCount]?.sortedWith(BEST_FIRST) ?: continue
                val admitted = ranked.asSequence().filter { it.admissible }.firstNotNullOfOrNull(::fitted)
                val offer = admitted?.let { Offer(it, admissible = true) }
                    ?: ranked.asSequence().filter { !it.admissible }.firstNotNullOfOrNull(::fitted)
                        ?.let { Offer(it, admissible = false) }
                if (offer != null) offers[mode] = offer
            }
            return offers
        }

        /** The best-scoring m-subset of the pool with no gain or span gate, preferring one that fits a layout. */
        fun forced(m: Int): CurationOption {
            val all = ArrayList<Scored>()
            val subset = IntArray(m) { it }
            while (true) {
                checkpoint()
                all += score(subset.copyOf(), gainsOf(subset), q1 = 0f)
                if (!nextCombination(subset, pool.size)) break
            }
            val ranked = all.filter { it.score.isFinite() }.ifEmpty { all }.sortedWith(BEST_FIRST)
            return ranked.asSequence().firstNotNullOfOrNull(::fitted) ?: unfitted(ranked.first())
        }

        /** The whole pool as one forced set, fitted to a layout when one accepts it. */
        fun forcedAll(): CurationOption {
            val all = IntArray(pool.size) { it }
            val scored = score(all, gainsOf(all), q1 = 0f)
            return fitted(scored) ?: unfitted(scored)
        }

        /** True when `hi - lo` exceeds the event span, or is too large to represent (extreme capture times). */
        private fun spanExceeds(lo: Long, hi: Long): Boolean = try {
            Math.subtractExact(hi, lo) > config.maxEventSpanMillis
        } catch (overflow: ArithmeticException) {
            true
        }

        private fun gainsOf(subset: IntArray): FloatArray =
            FloatArray(subset.size) { k -> redundancy.gain(subset[k], subset, k) }

        private fun score(subset: IntArray, gains: FloatArray, q1: Float): Scored {
            val synergy = scorer.synergy(subset)
            val meanGain = gains.sumOf { it.toDouble() } / gains.size
            val bar = config.admissionBar(subset.size) * q1
            var admissible = true
            for (k in 1 until gains.size) if (!(gains[k] >= bar)) admissible = false   // written so that NaN fails
            return Scored(
                indices = subset,
                gains = gains,
                synergy = synergy,
                score = Synergy.combineScore(meanGain, synergy.psi, subset.size),
                admissible = admissible,
                pool = pool,
            )
        }

        /** [set] as an option in a layout the fitter accepts, or null; legacy (null layout) without layouts for m. */
        private fun fitted(set: Scored): CurationOption? {
            val ordered = anchorOrder(set.members)
            val mode = modeFor(ordered.size)
            if (config.layoutCatalog.forCount(ordered.size).isEmpty()) {
                return CurationOption(mode, ordered.map { it.candidateId }, set.score, set.synergy, layoutId = null)
            }
            val fit = config.layoutFitter.fit(ordered, config.layoutCatalog) ?: return null
            val ids = ordered.map { it.candidateId }
            check(fit.orderedIds.firstOrNull() == ids.first() && fit.orderedIds.sorted() == ids.sorted()) {
                "layout fitter must permute only slots 1+ of $ids, returned ${fit.orderedIds} for ${fit.layoutId}"
            }
            return CurationOption(mode, fit.orderedIds, set.score, set.synergy, layoutId = fit.layoutId)
        }

        private fun unfitted(set: Scored): CurationOption {
            val ordered = anchorOrder(set.members)
            return CurationOption(modeFor(ordered.size), ordered.map { it.candidateId }, set.score, set.synergy)
        }

        private fun modeFor(photoCount: Int): StoryMode = StoryMode.entries.first { it.photoCount == photoCount }

        /** Advances [subset] to the next m-combination of 0 until [n] in lexicographic order; false after the last. */
        private fun nextCombination(subset: IntArray, n: Int): Boolean {
            val m = subset.size
            var i = m - 1
            while (i >= 0 && subset[i] == n - m + i) i--
            if (i < 0) return false
            subset[i]++
            for (j in i + 1 until m) subset[j] = subset[j - 1] + 1
            return true
        }
    }
}
