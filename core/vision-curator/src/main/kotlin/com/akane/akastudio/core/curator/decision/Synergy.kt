package com.akane.akastudio.core.curator.decision

import com.akane.akastudio.core.curator.api.PhotoAnalysis
import com.akane.akastudio.core.curator.api.ShotScale
import com.akane.akastudio.core.curator.api.SynergyBreakdown
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Collage synergy Ψ: how well a set of photos works together.
 *
 * `Ψ = 0.40 * H_color + 0.35 * H_rhythm + 0.25 * H_context` (see [PaletteHarmony.colorScore], [rhythm], [context]),
 * and `Score_Collage(S) = (0.5 * meanQ(S) + 0.5 * Ψ) * (1 - 0.03 * (m - 2))` for `m = |S|`. [DecisionEngine] ranks
 * sets with the mean unique quality (each member's Q discounted by its [Redundancy] to earlier members) in place of
 * meanQ, so repeating a moment is never rewarded by its colour similarity.
 */
object Synergy {

    internal const val COLOR_WEIGHT = 0.40
    internal const val RHYTHM_WEIGHT = 0.35
    internal const val CONTEXT_WEIGHT = 0.25
    private const val RHYTHM_FLOOR = 0.35
    private const val TIME_SCALE_MILLIS = 3.0 * 60 * 60 * 1000
    private const val UNKNOWN_TIME_SCORE = 0.6
    private const val MIRED_SCALE = 25.0
    private const val SIZE_PENALTY = 0.03

    /**
     * H_rhythm: variety of shot scales. With `d` distinct scales among `m` shots,
     * `0.35 + 0.65 * (d - 1) / (min(m, 3) - 1)`; a single shot scores 1.
     *
     * @throws IllegalArgumentException when [scales] is empty.
     */
    fun rhythm(scales: List<ShotScale>): Float {
        require(scales.isNotEmpty()) { "rhythm needs at least one shot" }
        val m = scales.size
        if (m == 1) return 1f
        val distinct = scales.toSet().size
        return (RHYTHM_FLOOR + (1.0 - RHYTHM_FLOOR) * (distinct - 1) / (min(m, 3) - 1)).toFloat()
    }

    /**
     * H_context: shared moment and light. Time term: with at least two known capture times,
     * `t = exp(-(max - min) / 3h)`, otherwise 0.6. Light term: `c = exp(-σ / 25)` with σ the population standard
     * deviation of the colour temperatures in mired (1e6 / K); no temperatures give σ = 0. Result `0.5 * t + 0.5 * c`.
     *
     * @throws IllegalArgumentException when a colour temperature is not a positive finite number.
     */
    fun context(captureEpochMillis: List<Long?>, cctKelvin: List<Float>): Float {
        val known = captureEpochMillis.filterNotNull()
        val time = if (known.size >= 2) {
            exp(-(known.max() - known.min()).toDouble() / TIME_SCALE_MILLIS)
        } else {
            UNKNOWN_TIME_SCORE
        }
        val light = exp(-miredDeviation(cctKelvin) / MIRED_SCALE)
        return (0.5 * time + 0.5 * light).toFloat()
    }

    /**
     * Synergy of [members] with their capture times ([captureTimes] aligned by index, null = unknown).
     *
     * @throws IllegalArgumentException when [members] is empty, the lists differ in size, or a member's palette or
     *   colour temperature is invalid.
     */
    fun evaluate(members: List<PhotoAnalysis>, captureTimes: List<Long?>): SynergyBreakdown {
        require(members.isNotEmpty()) { "synergy needs at least one member" }
        require(captureTimes.size == members.size) {
            "captureTimes (${captureTimes.size}) must align with members (${members.size})"
        }
        return GroupScorer(members, captureTimes).synergy(IntArray(members.size) { it })
    }

    /**
     * Score_Collage: `(0.5 * meanQ + 0.5 * Ψ) * (1 - 0.03 * (m - 2))`.
     *
     * @throws IllegalArgumentException when fewer than two members are given.
     */
    fun collageScore(members: List<PhotoAnalysis>, synergy: SynergyBreakdown): Float {
        require(members.size >= 2) { "a collage needs at least two members: ${members.size}" }
        val meanQuality = members.sumOf { it.quality.toDouble() } / members.size
        return combineScore(meanQuality, synergy.psi, members.size)
    }

    internal fun combineScore(meanQuality: Double, psi: Float, size: Int): Float =
        ((0.5 * meanQuality + 0.5 * psi) * (1.0 - SIZE_PENALTY * (size - 2))).toFloat()

    internal fun psi(color: Float, rhythm: Float, context: Float): Float =
        (COLOR_WEIGHT * color + RHYTHM_WEIGHT * rhythm + CONTEXT_WEIGHT * context).toFloat()

    private fun miredDeviation(cctKelvin: List<Float>): Double {
        if (cctKelvin.isEmpty()) return 0.0
        val mireds = cctKelvin.map { kelvin ->
            require(kelvin.isFinite() && kelvin > 0f) { "colour temperature must be positive and finite: $kelvin" }
            1e6 / kelvin
        }
        val mean = mireds.average()
        return sqrt(mireds.sumOf { (it - mean) * (it - mean) } / mireds.size)
    }
}

/**
 * Scores subsets of a fixed candidate list with the pairwise palette terms precomputed once, so exhaustive subset
 * enumeration costs one EMD per candidate pair rather than per subset. Subsets are index arrays into [members];
 * pair means run in the order of the index array, matching [PaletteHarmony.colorScore] for increasing indices.
 * [checkpoint] is called before each pairwise EMD (see [DecisionEngine.decide]).
 */
internal class GroupScorer(
    private val members: List<PhotoAnalysis>,
    private val captureTimes: List<Long?>,
    checkpoint: () -> Unit = {},
) {

    private val emd: Array<DoubleArray>
    private val hues: List<Float?> = members.map { PaletteHarmony.dominantHue(it.palette) }

    init {
        require(captureTimes.size == members.size) { "captureTimes must align with members" }
        val n = members.size
        emd = Array(n) { DoubleArray(n) }
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                checkpoint()
                val distance = PaletteHarmony.emd(members[i].palette, members[j].palette).toDouble()
                emd[i][j] = distance
                emd[j][i] = distance
            }
        }
    }

    fun synergy(subset: IntArray): SynergyBreakdown {
        val meanEmd = PaletteHarmony.meanOverPairs(subset.size, empty = 0.0) { a, b -> emd[subset[a]][subset[b]] }
        val wheel = PaletteHarmony.meanOverPairs(subset.size, empty = 1.0) { a, b ->
            PaletteHarmony.pairWheel(hues[subset[a]], hues[subset[b]])
        }
        val color = PaletteHarmony.combine(meanEmd, wheel)
        val rhythm = Synergy.rhythm(subset.map { members[it].shotScale })
        val context = Synergy.context(subset.map { captureTimes[it] }, subset.map { members[it].cctKelvin })
        return SynergyBreakdown(color = color, rhythm = rhythm, context = context, psi = Synergy.psi(color, rhythm, context))
    }
}
