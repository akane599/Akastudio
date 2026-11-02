package com.akane.akastudio.core.curator.decision

import com.akane.akastudio.core.curator.api.StoryMode

/**
 * Tunables of the curator decision engine. Every tier-2 and choice constant below is calibrated on one real set only
 * (the 2026-11-02 night-out trace, `docs/stabilization/evidence/curation-20261102/`, 28 photos) and should be
 * re-checked against the pairwise distributions of each further real set.
 *
 * Tier 1 (burst clustering, [Clustering]):
 * @property clusterCosine two photos join a burst cluster when their embedding cosine is strictly greater than this.
 * @property clusterPHashMax two photos join a burst cluster when their pHash Hamming distance is at most this.
 * @property clusterDHashMax two photos join a burst cluster when their dHash Hamming distance is at most this.
 *
 * Tier 2 (soft redundancy between champions' clusters, [Redundancy]; calibrated on the 2026-11-02 trace):
 * @property redundancyHashFull the hash term is 1 when min(pHash, dHash) Hamming distance is at most this.
 * @property redundancyHashZero the hash term falls linearly to 0 at this distance (every pair of the trace at or
 *   below 18 was one moment; the closest pair of different moments was 20).
 * @property redundancyCosineLow the embedding term is 0 at or below this cosine (trace: same-moment pairs >= 0.40),
 *   unless both capture times are known and within [redundancySameMomentMillis] of each other.
 * @property redundancyCosineLowSameMoment the embedding term's lower end when both capture times are known and at most
 *   [redundancySameMomentMillis] apart. Inside that window the embedder is too weak for reframes without faces: on
 *   the 2026-11-02 trace the wide table reframe 210001 has cosine 0.40 and 0.44 to frames 4-24 s away. Of the 21
 *   other pairs within 60 s at cosine 0.25-0.45, 15 are people pairs whose robust faces do not match (face veto,
 *   redundancy <= 0.19), 3 are frames of one series burst (redundancy 0.58-0.62, correctly), and 3 are people pairs
 *   with matching faces that now get partial redundancy 0.29-0.38. The chosen story on that set is unchanged.
 * @property redundancyCosineHigh the embedding term is 1 at or above this cosine (trace: different moments <= 0.58).
 * @property redundancySameMomentMillis the time term is 1 when two known capture times are at most this far apart
 *   (trace: bursts span <= 28 s).
 * @property redundancyTimeDecayMillis beyond the same-moment window the time term decays as
 *   `exp(-(|Δt| - window) / decay)`.
 * @property redundancyFaceVeto the factor on the embedding term when both photos show a robust face and no pair of
 *   robust faces matches (see [faceMatchDistance], [faceMatchSizeRatio]).
 * @property robustFaceArea a face box covering at least this fraction of the frame is robust; a side without one
 *   gives no face evidence (trace: series faces flicker around 0.02).
 * @property faceMatchDistance two robust faces can match only when their normalized centres are at most this far
 *   apart (trace: same-moment reframes move faces up to 0.38).
 * @property faceMatchSizeRatio two robust faces can match only when the larger box width is at most this times the
 *   smaller (trace: same moment <= 1.31, different moments of the same people >= 1.41).
 *
 * Choice (see [DecisionEngine]; calibrated on the 2026-11-02 trace):
 * @property admitPair every member after the first of an automatic COLLAGE_2 must add unique quality of at least
 *   this times Q1.
 * @property admitTrio the same bar for COLLAGE_3.
 * @property admitQuad the same bar for COLLAGE_4.
 * @property offerBar the drawer offers a collage mode only when every member after the first adds unique quality of
 *   at least this times Q1.
 * @property maxEventSpanMillis a set whose known capture times span more than this is neither chosen nor offered.
 * @property subsetPoolSize the number of top champions over which collage subsets are enumerated exhaustively.
 * @property layoutCatalog the host's layouts per photo count; [LayoutCatalog.EMPTY] keeps selection layout-agnostic.
 * @property layoutFitter verifies a candidate set against the catalogue (crop feasibility, slot order, layout choice).
 */
data class DecisionConfig(
    val clusterCosine: Float = 0.82f,
    val clusterPHashMax: Int = 10,
    val clusterDHashMax: Int = 8,
    val redundancyHashFull: Int = 10,
    val redundancyHashZero: Int = 18,
    val redundancyCosineLow: Float = 0.35f,
    val redundancyCosineHigh: Float = 0.55f,
    val redundancyCosineLowSameMoment: Float = 0.25f,
    val redundancySameMomentMillis: Long = 60_000L,
    val redundancyTimeDecayMillis: Long = 300_000L,
    val redundancyFaceVeto: Float = 0.4f,
    val robustFaceArea: Float = 0.04f,
    val faceMatchDistance: Float = 0.40f,
    val faceMatchSizeRatio: Float = 1.35f,
    val admitPair: Float = 0.65f,
    val admitTrio: Float = 0.72f,
    val admitQuad: Float = 0.80f,
    val offerBar: Float = 0.5f,
    val maxEventSpanMillis: Long = 6 * 60 * 60 * 1000L,
    val subsetPoolSize: Int = 24,
    val layoutCatalog: LayoutCatalog = LayoutCatalog.EMPTY,
    val layoutFitter: LayoutFitter = LayoutFitter.AlwaysFits,
) {
    init {
        require(clusterCosine.isFinite()) { "clusterCosine must be finite: $clusterCosine" }
        require(clusterPHashMax in 0..Long.SIZE_BITS) { "clusterPHashMax must be in 0..64: $clusterPHashMax" }
        require(clusterDHashMax in 0..Long.SIZE_BITS) { "clusterDHashMax must be in 0..64: $clusterDHashMax" }
        require(redundancyHashFull >= 0 && redundancyHashFull < redundancyHashZero && redundancyHashZero <= Long.SIZE_BITS) {
            "need 0 <= redundancyHashFull < redundancyHashZero <= 64: $redundancyHashFull, $redundancyHashZero"
        }
        require(redundancyCosineLow.isFinite() && redundancyCosineHigh.isFinite() && redundancyCosineLow < redundancyCosineHigh) {
            "need finite redundancyCosineLow < redundancyCosineHigh: $redundancyCosineLow, $redundancyCosineHigh"
        }
        require(redundancyCosineLowSameMoment.isFinite() && redundancyCosineLowSameMoment <= redundancyCosineLow) {
            "need finite redundancyCosineLowSameMoment <= redundancyCosineLow: $redundancyCosineLowSameMoment, $redundancyCosineLow"
        }
        require(redundancySameMomentMillis >= 0L) { "redundancySameMomentMillis must be >= 0: $redundancySameMomentMillis" }
        require(redundancyTimeDecayMillis > 0L) { "redundancyTimeDecayMillis must be > 0: $redundancyTimeDecayMillis" }
        require(redundancyFaceVeto in 0f..1f) { "redundancyFaceVeto must be in 0..1: $redundancyFaceVeto" }
        require(robustFaceArea > 0f && robustFaceArea <= 1f) { "robustFaceArea must be in (0, 1]: $robustFaceArea" }
        require(faceMatchDistance.isFinite() && faceMatchDistance >= 0f) {
            "faceMatchDistance must be finite and >= 0: $faceMatchDistance"
        }
        require(faceMatchSizeRatio.isFinite() && faceMatchSizeRatio >= 1f) {
            "faceMatchSizeRatio must be finite and >= 1: $faceMatchSizeRatio"
        }
        // Monotone bars are what make prefix pruning exact: gains only fall as a set grows, bars must only rise.
        require(0f <= offerBar && offerBar <= admitPair && admitPair <= admitTrio && admitTrio <= admitQuad && admitQuad <= 1f) {
            "need 0 <= offerBar <= admitPair <= admitTrio <= admitQuad <= 1: $offerBar, $admitPair, $admitTrio, $admitQuad"
        }
        require(maxEventSpanMillis >= 0L) { "maxEventSpanMillis must be >= 0: $maxEventSpanMillis" }
        require(subsetPoolSize >= StoryMode.COLLAGE_4.photoCount) {
            "subsetPoolSize must be at least ${StoryMode.COLLAGE_4.photoCount}: $subsetPoolSize"
        }
    }

    /** The automatic-choice bar for a collage of [photoCount] photos, as a fraction of Q1. */
    fun admissionBar(photoCount: Int): Float = when (photoCount) {
        StoryMode.COLLAGE_2.photoCount -> admitPair
        StoryMode.COLLAGE_3.photoCount -> admitTrio
        StoryMode.COLLAGE_4.photoCount -> admitQuad
        else -> throw IllegalArgumentException("no admission bar for $photoCount photos")
    }
}
