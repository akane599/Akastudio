package com.akane.akastudio.core.curator.decision

import com.akane.akastudio.core.curator.api.StoryMode

/**
 * Tunables of the curator decision engine.
 *
 * @property clusterCosine two photos join a burst cluster when their embedding cosine is strictly greater than this.
 * @property clusterPHashMax two photos join a burst cluster when their pHash Hamming distance is at most this.
 * @property clusterDHashMax two photos join a burst cluster when their dHash Hamming distance is at most this.
 * @property tau a collage wins only when its score reaches `tau * Q1` (Q1 = best champion quality).
 * @property minSecondQuality a collage is considered only when the second-best champion reaches this quality.
 * @property minMemberQuality every member of an automatically chosen collage must reach this quality.
 * @property subsetPoolSize the number of top champions over which collage subsets are enumerated exhaustively.
 */
data class DecisionConfig(
    val clusterCosine: Float = 0.82f,
    val clusterPHashMax: Int = 10,
    val clusterDHashMax: Int = 8,
    val tau: Float = 0.92f,
    val minSecondQuality: Float = 0.55f,
    val minMemberQuality: Float = 0.62f,
    val subsetPoolSize: Int = 24,
) {
    init {
        require(clusterCosine.isFinite()) { "clusterCosine must be finite: $clusterCosine" }
        require(clusterPHashMax in 0..Long.SIZE_BITS) { "clusterPHashMax must be in 0..64: $clusterPHashMax" }
        require(clusterDHashMax in 0..Long.SIZE_BITS) { "clusterDHashMax must be in 0..64: $clusterDHashMax" }
        require(tau.isFinite() && tau >= 0f) { "tau must be finite and non-negative: $tau" }
        require(minSecondQuality.isFinite()) { "minSecondQuality must be finite: $minSecondQuality" }
        require(minMemberQuality.isFinite()) { "minMemberQuality must be finite: $minMemberQuality" }
        require(subsetPoolSize >= StoryMode.COLLAGE_4.photoCount) {
            "subsetPoolSize must be at least ${StoryMode.COLLAGE_4.photoCount}: $subsetPoolSize"
        }
    }
}
