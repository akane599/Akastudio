package com.akane.akastudio.feature.studio.ui.controls

import com.akane.akastudio.core.curator.api.CurationResult

/**
 * One swap option for a story slot.
 *
 * @property id The candidate id to send with `SwapCandidate`.
 * @property sameScene True for a member of the slot photo's own scene cluster (a burst or near-duplicate),
 *   false for another cluster's champion.
 */
internal data class BurstOption(val id: String, val sameScene: Boolean)

/** Builds the "Swap from burst" row of the curation tab. */
internal object BurstAlternatives {

    /**
     * Swap options for [slot] of the story [photoIds]: first the other members of that photo's scene cluster,
     * then the champions of the other clusters, each group best quality first (ties keep cluster order).
     * Photos already in the story, rejected photos and photos without an analysis are never offered.
     * Returns an empty list when [slot] is outside [photoIds].
     */
    fun build(curation: CurationResult, photoIds: List<String>, slot: Int): List<BurstOption> {
        if (slot !in photoIds.indices) return emptyList()
        val photoId = photoIds[slot]
        val chosen = photoIds.toSet()
        val analyses = curation.analyses
        fun usable(id: String): Boolean {
            val analysis = analyses[id] ?: return false
            return id !in chosen && analysis.rejectReason == null
        }
        fun quality(id: String): Float = analyses.getValue(id).quality

        val ownCluster = curation.clusters.firstOrNull { photoId in it.memberIds }
        val members = ownCluster?.memberIds.orEmpty()
            .filter(::usable)
            .distinct()
            .sortedByDescending(::quality)
        val memberSet = members.toSet()
        val champions = curation.clusters
            .asSequence()
            .filter { it !== ownCluster }
            .map { it.championId }
            .filter { usable(it) && it !in memberSet }
            .distinct()
            .sortedByDescending(::quality)
            .toList()
        return members.map { BurstOption(it, sameScene = true) } + champions.map { BurstOption(it, sameScene = false) }
    }
}
