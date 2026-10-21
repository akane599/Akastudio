package com.akane.akastudio.core.curator.engine

import com.akane.akastudio.core.color.api.MasterLook
import com.akane.akastudio.core.color.api.SceneType
import com.akane.akastudio.core.color.look.LookSelector
import com.akane.akastudio.core.curator.api.CurationOption
import com.akane.akastudio.core.curator.api.CurationResult
import com.akane.akastudio.core.curator.api.CuratorNote
import com.akane.akastudio.core.curator.api.PhotoAnalysis
import com.akane.akastudio.core.curator.decision.Decision
import com.akane.akastudio.core.curator.decision.DecisionEngine
import kotlin.math.roundToInt

/** Builds [CurationResult]s from decisions, and decisions back from results for a mode switch. */
internal object CurationResults {

    /** The decision pool order documented on [Decision.champions]: quality, then aesthetic, descending; then id. */
    private val POOL_ORDER: Comparator<PhotoAnalysis> = compareByDescending<PhotoAnalysis> { it.quality }
        .thenByDescending { it.aesthetic }
        .thenBy { it.candidateId }

    /**
     * Assembles the result for [decision] over [analyses] (every candidate, in input order), carrying the run's
     * [captureTimes] (by candidate id, null = unknown) so a later mode switch re-decides with them.
     */
    fun build(
        decision: Decision,
        analyses: Map<String, PhotoAnalysis>,
        captureTimes: Map<String, Long?>,
    ): CurationResult {
        val chosen = decision.chosen.photoIds.map { id ->
            requireNotNull(analyses[id]) { "decision chose an unknown candidate: $id" }
        }
        require(chosen.isNotEmpty()) { "decision chose no photos" }
        val anchor = chosen.first()
        return CurationResult(
            chosen = decision.chosen,
            alternatives = decision.alternatives,
            analyses = analyses,
            clusters = decision.clusters,
            rejectedIds = decision.rejectedIds,
            note = note(decision, chosen, analyses.size),
            suggestedLook = suggestedLook(anchor),
            captureTimes = captureTimes,
        )
    }

    /** The [Decision] that produced [result], with champions re-derived in pool order from its clusters. */
    fun decisionOf(result: CurationResult): Decision {
        val champions = result.clusters
            .map { cluster ->
                requireNotNull(result.analyses[cluster.championId]) {
                    "result references unknown champion: ${cluster.championId}"
                }
            }
            .sortedWith(POOL_ORDER)
            .map { it.candidateId }
        return Decision(
            chosen = result.chosen,
            alternatives = result.alternatives,
            clusters = result.clusters,
            rejectedIds = result.rejectedIds,
            champions = champions,
        )
    }

    /** [LookSelector.select] on the anchor's scene, white point, mean lightness and prominent-face flag. */
    fun suggestedLook(anchor: PhotoAnalysis): MasterLook = LookSelector.select(
        scene = anchor.scene,
        cctKelvin = anchor.cctKelvin,
        meanL = anchor.labStats.meanL,
        hasProminentFace = anchor.faces.any(PhotoAnalyzer::isProminent),
    )

    private fun note(decision: Decision, chosen: List<PhotoAnalysis>, totalPhotos: Int): CuratorNote = CuratorNote(
        totalPhotos = totalPhotos,
        clusterCount = decision.clusters.size,
        rejectedCount = decision.rejectedIds.size,
        chosenMode = decision.chosen.mode,
        scales = chosen.map { it.shotScale },
        dominantScene = dominantScene(chosen),
        warmth = DecisionEngine.warmth(chosen),
        harmonyPercent = harmonyPercent(decision.chosen),
    )

    /** Most frequent scene among [chosen]; a tie goes to the anchor's scene, else to the earliest in order. */
    internal fun dominantScene(chosen: List<PhotoAnalysis>): SceneType {
        val counts = chosen.groupingBy { it.scene }.eachCount()
        val top = counts.values.max()
        val anchorScene = chosen.first().scene
        if (counts.getValue(anchorScene) == top) return anchorScene
        return chosen.first { counts.getValue(it.scene) == top }.scene
    }

    /** `round(100 * Ψ)` for a collage, `round(100 * Q1)` for a hero, clamped to 0..100 (0 for a non-finite score). */
    internal fun harmonyPercent(option: CurationOption): Int {
        val fraction = option.synergy?.psi ?: option.score
        if (!fraction.isFinite()) return 0
        return (100f * fraction).roundToInt().coerceIn(0, 100)
    }
}
