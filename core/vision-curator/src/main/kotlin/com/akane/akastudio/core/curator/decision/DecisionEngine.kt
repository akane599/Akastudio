package com.akane.akastudio.core.curator.decision

import com.akane.akastudio.core.curator.api.CurationOption
import com.akane.akastudio.core.curator.api.PhotoAnalysis
import com.akane.akastudio.core.curator.api.SceneCluster
import com.akane.akastudio.core.curator.api.StoryMode
import com.akane.akastudio.core.curator.api.SynergyBreakdown
import com.akane.akastudio.core.curator.api.Warmth

/**
 * Outcome of one curator decision.
 *
 * @property chosen the option the story uses.
 * @property alternatives the best feasible option per mode, including `chosen.mode`, for the user's mode switch.
 * @property clusters burst clusters of the non-rejected photos (see [Clustering.cluster]).
 * @property rejectedIds ids eliminated at the technical gate, sorted ascending.
 * @property champions the champion of each cluster, ordered as the decision pool: quality descending, then aesthetic
 *   descending, then id ascending.
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
 * The pool is the cluster champions ordered by quality Q (Q1 >= Q2 >= ... >= QK). HERO is the top champion, scored
 * Q1. Collage subsets of size m in 2..4 are enumerated exhaustively over the eligible champions (Q >=
 * [DecisionConfig.minMemberQuality], top [DecisionConfig.subsetPoolSize]); the best subset per m maximizes
 * Score_Collage, ties going to the lexicographically smaller sorted id list.
 *
 * Choice rule: HERO when K < 2 or Q2 < [DecisionConfig.minSecondQuality]. Otherwise m is tried from 4 down to 2 and
 * the first m whose best subset scores >= tau * Q1 wins as COLLAGE_m; when none does, HERO. The size penalty
 * `1 - 0.03 * (m - 2)` stays inside Score_Collage (it ranks subsets within one m and is reported in the score) but
 * does not arbitrate between sizes. Under "the highest score over m wins", any eligible trio that clears tau * Q1 is
 * provably outscored by one of its own sub-pairs (proof in SQ-14 comment c_mvhhnytg_dc42bd), so the 3- and 4-photo
 * collage modes would never be chosen.
 */
object DecisionEngine {

    private const val ALL_REJECTED = "all candidates rejected"

    private const val NIGHT_MAX_KELVIN = 3600.0
    private const val NIGHT_MAX_LIGHTNESS = 0.35
    private const val GOLDEN_MAX_KELVIN = 4800.0
    private const val COOL_MIN_KELVIN = 6800.0

    private val COLLAGE_MODES = listOf(StoryMode.COLLAGE_2, StoryMode.COLLAGE_3, StoryMode.COLLAGE_4)

    /** Decision pool order: quality descending, then aesthetic descending, then id ascending. */
    private val POOL_ORDER: Comparator<PhotoAnalysis> = compareByDescending<PhotoAnalysis> { it.quality }
        .thenByDescending { it.aesthetic }
        .thenBy { it.candidateId }

    /**
     * Decides the story mode and photos for [analyses]. [captureTimes] maps candidate id to capture epoch millis
     * (missing or null = unknown).
     *
     * @throws IllegalArgumentException when [analyses] is empty or has duplicate ids.
     * @throws IllegalStateException with message "all candidates rejected" when every photo has a reject reason;
     *   the curation engine maps it to its error state.
     */
    fun decide(
        analyses: List<PhotoAnalysis>,
        captureTimes: Map<String, Long?>,
        config: DecisionConfig = DecisionConfig(),
    ): Decision {
        require(analyses.isNotEmpty()) { "analyses must not be empty" }
        val clusters = Clustering.cluster(analyses, config)
        check(clusters.isNotEmpty()) { ALL_REJECTED }
        val byId = analyses.associateBy { it.candidateId }
        val pool = clusters.map { byId.getValue(it.championId) }.sortedWith(POOL_ORDER)
        val hero = heroOption(pool)

        val eligible = pool.filter { it.quality >= config.minMemberQuality }.take(config.subsetPoolSize)
        val scorer = GroupScorer(eligible, eligible.map { captureTimes[it.candidateId] })
        val collages = COLLAGE_MODES
            .filter { eligible.size >= it.photoCount }
            .associateWith { bestSubset(scorer, eligible, it) }

        val chosen = chooseOption(pool, hero, collages, config)
        return Decision(
            chosen = chosen,
            alternatives = linkedMapOf(StoryMode.HERO to hero).apply { putAll(collages) },
            clusters = clusters,
            rejectedIds = rejectedIds(analyses),
            champions = pool.map { it.candidateId },
        )
    }

    /**
     * Re-decides for the user's mode switch, keeping [previous]'s clusters, rejections and champions.
     *
     * - [forcedMode] null: a plain [decide].
     * - HERO: the top champion.
     * - COLLAGE_m: the best subset of size m over all champions (top [DecisionConfig.subsetPoolSize]) with no quality
     *   gate. With fewer than m champions, all champions are filled up with the best non-champion member of distinct
     *   clusters, then with the remaining cluster members by quality. Rejected photos are never used. With fewer than
     *   m non-rejected photos, the largest feasible mode is chosen instead.
     *
     * Alternatives are built the same way for HERO and every collage size up to the non-rejected photo count.
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
    ): Decision {
        if (forcedMode == null) return decide(analyses, captureTimes, config)
        Clustering.requireDistinctIds(analyses)
        val byId = analyses.associateBy { it.candidateId }
        fun lookup(id: String): PhotoAnalysis =
            requireNotNull(byId[id]) { "previous decision references unknown candidate: $id" }

        check(previous.clusters.isNotEmpty()) { ALL_REJECTED }
        val clusters = previous.clusters.map { cluster -> cluster.memberIds.map(::lookup) }
        val champions = previous.champions.map(::lookup)
        val available = clusters.sumOf { it.size }
        val hero = heroOption(champions)

        val alternatives = linkedMapOf(StoryMode.HERO to hero)
        for (mode in COLLAGE_MODES) {
            if (mode.photoCount > available) break
            alternatives[mode] = forcedCollage(mode, clusters, champions, captureTimes, config)
        }
        val feasible = StoryMode.entries.last { it.photoCount <= minOf(forcedMode.photoCount, available) }
        return previous.copy(chosen = alternatives.getValue(feasible), alternatives = alternatives)
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

    private fun chooseOption(
        pool: List<PhotoAnalysis>,
        hero: CurationOption,
        collages: Map<StoryMode, CurationOption>,
        config: DecisionConfig,
    ): CurationOption {
        if (pool.size < 2 || pool[1].quality < config.minSecondQuality) return hero
        val threshold = config.tau * pool[0].quality
        return COLLAGE_MODES.asReversed()
            .firstNotNullOfOrNull { mode -> collages[mode]?.takeIf { it.score >= threshold } }
            ?: hero
    }

    private fun heroOption(pool: List<PhotoAnalysis>): CurationOption {
        val top = pool.first()
        return CurationOption(StoryMode.HERO, listOf(top.candidateId), top.quality, synergy = null)
    }

    private fun forcedCollage(
        mode: StoryMode,
        clusters: List<List<PhotoAnalysis>>,
        champions: List<PhotoAnalysis>,
        captureTimes: Map<String, Long?>,
        config: DecisionConfig,
    ): CurationOption {
        val m = mode.photoCount
        if (champions.size >= m) {
            val candidates = champions.take(config.subsetPoolSize)
            return bestSubset(GroupScorer(candidates, candidates.map { captureTimes[it.candidateId] }), candidates, mode)
        }
        val runnersUp = clusters.mapNotNull { it.getOrNull(1) }.sortedWith(Clustering.BY_QUALITY_THEN_ID)
        val remaining = clusters.flatMap { it.drop(2) }.sortedWith(Clustering.BY_QUALITY_THEN_ID)
        val members = (champions + runnersUp + remaining).take(m)
        val scorer = GroupScorer(members, members.map { captureTimes[it.candidateId] })
        val all = IntArray(members.size) { it }
        val synergy = scorer.synergy(all)
        return CurationOption(mode, anchorOrder(members).map { it.candidateId }, scorer.score(all, synergy), synergy)
    }

    /** Exhaustive best subset of size `mode.photoCount` over [candidates]; requires enough candidates. */
    private fun bestSubset(scorer: GroupScorer, candidates: List<PhotoAnalysis>, mode: StoryMode): CurationOption {
        val m = mode.photoCount
        val subset = IntArray(m) { it }
        var bestSubset: IntArray? = null
        var bestScore = Float.NEGATIVE_INFINITY
        var bestKey: List<String> = emptyList()
        var bestSynergy: SynergyBreakdown? = null
        while (true) {
            val synergy = scorer.synergy(subset)
            val score = scorer.score(subset, synergy)
            if (bestSubset == null || score > bestScore) {
                bestSubset = subset.copyOf()
                bestScore = score
                bestKey = sortedIds(candidates, subset)
                bestSynergy = synergy
            } else if (score == bestScore) {
                val key = sortedIds(candidates, subset)
                if (compareIdLists(key, bestKey) < 0) {
                    bestSubset = subset.copyOf()
                    bestKey = key
                    bestSynergy = synergy
                }
            }
            if (!nextCombination(subset, candidates.size)) break
        }
        val members = checkNotNull(bestSubset).map { candidates[it] }
        return CurationOption(mode, anchorOrder(members).map { it.candidateId }, bestScore, bestSynergy)
    }

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

    /** The highest-aesthetic member first (the chromatic anchor), then quality descending; ties by id. */
    private fun anchorOrder(members: List<PhotoAnalysis>): List<PhotoAnalysis> {
        val anchor = members.sortedWith(
            compareByDescending<PhotoAnalysis> { it.aesthetic }.thenByDescending { it.quality }.thenBy { it.candidateId },
        ).first()
        return listOf(anchor) + members.filter { it !== anchor }.sortedWith(Clustering.BY_QUALITY_THEN_ID)
    }

    private fun sortedIds(candidates: List<PhotoAnalysis>, subset: IntArray): List<String> =
        subset.map { candidates[it].candidateId }.sorted()

    private fun compareIdLists(a: List<String>, b: List<String>): Int {
        for (k in 0 until minOf(a.size, b.size)) {
            val c = a[k].compareTo(b[k])
            if (c != 0) return c
        }
        return a.size.compareTo(b.size)
    }

    private fun rejectedIds(analyses: List<PhotoAnalysis>): List<String> =
        analyses.filter { it.rejectReason != null }.map { it.candidateId }.sorted()
}
