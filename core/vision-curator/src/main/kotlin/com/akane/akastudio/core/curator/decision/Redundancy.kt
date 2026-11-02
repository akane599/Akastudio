package com.akane.akastudio.core.curator.decision

import com.akane.akastudio.core.curator.api.FaceObservation
import com.akane.akastudio.core.curator.api.PhotoAnalysis
import com.akane.akastudio.core.curator.hash.PerceptualHash
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max

/**
 * Tier-2 soft redundancy: how much two photos show the same moment, in `[0, 1]`. Tier-1 clustering already merged
 * near-identical frames; this catches reframed shots of one moment that stay separate champions. Between two
 * champions it is taken over their whole tier-1 clusters ([betweenGroups]): a frame that links to a cluster only
 * through a non-champion member is still a repeat of that moment.
 *
 * `r(a, b) = max(h, e · t · f)` with
 * - `h`, the hash term: 1 when `min(pHash, dHash)` Hamming distance is at most [DecisionConfig.redundancyHashFull],
 *   falling linearly to 0 at [DecisionConfig.redundancyHashZero]. Hash closeness is pixel evidence of one capture,
 *   so no other factor scales it.
 * - `e`, the embedding term: 0 at cosine [DecisionConfig.redundancyCosineLow], linear to 1 at
 *   [DecisionConfig.redundancyCosineHigh]. When both capture times are known and at most
 *   [DecisionConfig.redundancySameMomentMillis] apart, the lower end is
 *   [DecisionConfig.redundancyCosineLowSameMoment] instead (see [sameMoment]).
 * - `t`, the time term: 1 when either capture time is unknown or `|Δt| <=`
 *   [DecisionConfig.redundancySameMomentMillis], else `exp(-(|Δt| - window) / `
 *   [DecisionConfig.redundancyTimeDecayMillis]`)`. An unknown time never lowers the time term (the embedding ramp still keeps its standard lower end, see above).
 * - `f`, the face factor (see [faceFactor]): [DecisionConfig.redundancyFaceVeto] or 1.
 *
 * Symmetric and pure. A non-finite result counts as fully redundant (1), so bad numbers never make a frame novel.
 */
object Redundancy {

    /** r(a, b) for photos captured at [timeA] and [timeB] (epoch millis, null = unknown). */
    fun between(a: PhotoAnalysis, timeA: Long?, b: PhotoAnalysis, timeB: Long?, config: DecisionConfig): Float {
        val distance = minOf(PerceptualHash.hamming(a.pHash, b.pHash), PerceptualHash.hamming(a.dHash, b.dHash))
        val h = hashTerm(distance, config)
        val e = embeddingTerm(Clustering.cosine(a.embedding, b.embedding), config, sameMoment(timeA, timeB, config))
        if (e == 0f || h >= 1f) return h
        val r = max(h, e * timeTerm(timeA, timeB, config) * faceFactor(a.faces, b.faces, config))
        return if (r.isFinite()) r else 1f   // fail closed: an unreadable score counts as a repeat
    }

    /**
     * The redundancy between two tier-1 clusters: the maximum of [between] over every member pair, each member with
     * its own capture time from [captureTimes] (missing or null = unknown).
     *
     * @throws IllegalArgumentException when either group is empty.
     */
    fun betweenGroups(
        a: List<PhotoAnalysis>,
        b: List<PhotoAnalysis>,
        captureTimes: Map<String, Long?>,
        config: DecisionConfig,
    ): Float {
        require(a.isNotEmpty() && b.isNotEmpty()) { "redundancy needs non-empty groups" }
        var worst = 0f
        for (x in a) {
            for (y in b) {
                worst = max(worst, between(x, captureTimes[x.candidateId], y, captureTimes[y.candidateId], config))
                if (worst >= 1f) return 1f
            }
        }
        return worst
    }

    /** The hash term for a minimum pHash/dHash Hamming [distance]. */
    fun hashTerm(distance: Int, config: DecisionConfig): Float = ramp(
        (config.redundancyHashZero - distance).toFloat() / (config.redundancyHashZero - config.redundancyHashFull),
    )

    /**
     * The embedding term for a [cosine]; a non-finite cosine counts as unrelated (0). With [sameMoment] (both times
     * known and inside the window) the ramp starts at [DecisionConfig.redundancyCosineLowSameMoment].
     */
    fun embeddingTerm(cosine: Float, config: DecisionConfig, sameMoment: Boolean = false): Float {
        if (!cosine.isFinite()) return 0f
        val low = if (sameMoment) config.redundancyCosineLowSameMoment else config.redundancyCosineLow
        return ramp((cosine - low) / (config.redundancyCosineHigh - low))
    }

    /** True when both capture times are known and at most [DecisionConfig.redundancySameMomentMillis] apart. */
    fun sameMoment(a: Long?, b: Long?, config: DecisionConfig): Boolean =
        a != null && b != null && abs(a.toDouble() - b.toDouble()) <= config.redundancySameMomentMillis.toDouble()

    /** The time term for capture times [a] and [b] (epoch millis, null = unknown). */
    fun timeTerm(a: Long?, b: Long?, config: DecisionConfig): Float {
        if (a == null || b == null) return 1f
        val gap = abs(a.toDouble() - b.toDouble())
        val window = config.redundancySameMomentMillis.toDouble()
        if (gap <= window) return 1f
        return exp(-(gap - window) / config.redundancyTimeDecayMillis).toFloat()
    }

    /**
     * The face factor on the embedding term. It is [DecisionConfig.redundancyFaceVeto] only when both photos have at
     * least one robust face (box area >= [DecisionConfig.robustFaceArea]) and no robust face of one matches a robust
     * face of the other; otherwise 1. Two faces match when their centres are at most
     * [DecisionConfig.faceMatchDistance] apart and the larger box width is at most [DecisionConfig.faceMatchSizeRatio]
     * times the smaller. A side without a robust face (no face, or only faces under the robust area) gives no
     * evidence: a missed or flickering small detection must not split one moment.
     */
    fun faceFactor(a: List<FaceObservation>, b: List<FaceObservation>, config: DecisionConfig): Float {
        val robustA = a.filter { area(it) >= config.robustFaceArea }
        val robustB = b.filter { area(it) >= config.robustFaceArea }
        if (robustA.isEmpty() || robustB.isEmpty()) return 1f
        val anyMatch = robustA.any { x -> robustB.any { y -> matches(x, y, config) } }
        return if (anyMatch) 1f else config.redundancyFaceVeto
    }

    private fun matches(a: FaceObservation, b: FaceObservation, config: DecisionConfig): Boolean {
        val wide = max(a.box.width, b.box.width)
        val narrow = minOf(a.box.width, b.box.width)
        return centreDistance(a, b) <= config.faceMatchDistance && narrow > 0f && wide <= config.faceMatchSizeRatio * narrow
    }

    private fun area(face: FaceObservation): Float = max(0f, face.box.width) * max(0f, face.box.height)

    private fun centreDistance(a: FaceObservation, b: FaceObservation): Float = hypot(
        (a.box.left + a.box.right - b.box.left - b.box.right) / 2f,
        (a.box.top + a.box.bottom - b.box.top - b.box.bottom) / 2f,
    )

    private fun ramp(x: Float): Float = x.coerceIn(0f, 1f)
}

/**
 * Pairwise cluster-level [Redundancy] over a fixed member list, computed once, and the unique-quality gains of index
 * subsets. [groups] aligns with [members] by index: the tier-1 cluster each member represents (it must contain the
 * member; a singleton when the member stands alone). [checkpoint] is called before each member pair.
 */
internal class RedundancyMatrix(
    private val members: List<PhotoAnalysis>,
    groups: List<List<PhotoAnalysis>>,
    captureTimes: Map<String, Long?>,
    config: DecisionConfig,
    checkpoint: () -> Unit = {},
) {
    private val n = members.size
    private val r = FloatArray(n * n)

    init {
        require(groups.size == n) { "groups must align with members" }
        for (i in 0 until n) {
            r[i * n + i] = 1f
            for (j in i + 1 until n) {
                checkpoint()
                val value = Redundancy.betweenGroups(groups[i], groups[j], captureTimes, config)
                r[i * n + j] = value
                r[j * n + i] = value
            }
        }
    }

    operator fun get(i: Int, j: Int): Float = r[i * n + j]

    /**
     * Unique-quality gain of member [k] after the members [prefix] (indices, in quality order before [k]):
     * `Q_k · (1 - max_j r(k, j))`, or `Q_k` for an empty prefix.
     */
    fun gain(k: Int, prefix: IntArray, prefixSize: Int = prefix.size): Float {
        var worst = 0f
        for (p in 0 until prefixSize) worst = max(worst, this[k, prefix[p]])
        return members[k].quality * (1f - worst)
    }
}
