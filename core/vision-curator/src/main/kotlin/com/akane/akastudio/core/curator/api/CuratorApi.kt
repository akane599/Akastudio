package com.akane.akastudio.core.curator.api

import com.akane.akastudio.core.color.api.LabStats
import com.akane.akastudio.core.color.api.MasterLook
import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.color.api.PaletteSwatch
import com.akane.akastudio.core.color.api.SceneType
import com.akane.akastudio.core.ingestion.model.PhotoCandidate

enum class ShotScale { WIDE, MEDIUM, DETAIL }
enum class StoryMode(val photoCount: Int) { HERO(1), COLLAGE_2(2), COLLAGE_3(3), COLLAGE_4(4) }
enum class RejectReason { BLURRY, EYES_CLOSED, BAD_EXPOSURE, UNREADABLE }

data class FaceObservation(
    val box: NormRect,                       // upright image, normalized
    val leftEyeOpenness: Float,              // 0 closed .. 1 open (1 - blink blendshape)
    val rightEyeOpenness: Float,
    val sharpness: Float,                    // 0..1 local sharpness inside the face box
)

/** Row-major saliency, values normalized to [0,1], aspect of the upright image (long edge 64). */
class SaliencyMap(val width: Int, val height: Int, val values: FloatArray) {
    init { require(width > 0 && height > 0 && values.size == width * height) }
}

data class TechnicalScore(
    val sharpness: Float,                    // 0..1
    val exposure: Float,                     // 0..1
    val faceQuality: Float,                  // 0..1 (1 when no faces)
    val total: Float,                        // T_i in [0,1]
)

data class PhotoAnalysis(
    val candidateId: String,
    val technical: TechnicalScore,
    val aesthetic: Float,                    // A_i in [0,1] (neural ⊕ composition)
    val composition: Float,                  // 0..1 geometric composition sub-score
    val quality: Float,                      // Q_i in [0,1]
    val embedding: FloatArray,               // L2-normalized feature vector (dimension depends on the model)
    val dHash: Long,
    val pHash: Long,
    val palette: List<PaletteSwatch>,        // 5 swatches, OKLab
    val labStats: LabStats,
    val cctKelvin: Float,
    val shotScale: ShotScale,
    val scene: SceneType,
    val faces: List<FaceObservation>,
    val saliency: SaliencyMap,
    val rejectReason: RejectReason?,         // non-null = eliminated at the technical gate
)

data class SceneCluster(val id: Int, val memberIds: List<String>, val championId: String)

data class SynergyBreakdown(val color: Float, val rhythm: Float, val context: Float, val psi: Float)

data class CurationOption(
    val mode: StoryMode,
    val photoIds: List<String>,              // ordered: hero/anchor first
    val score: Float,                        // HERO: Q_1; collage: Score_Collage(S_m)
    val synergy: SynergyBreakdown?,          // null for HERO
)

/** Structured note; the UI formats it into one localized sentence. */
data class CuratorNote(
    val totalPhotos: Int,
    val clusterCount: Int,
    val rejectedCount: Int,
    val chosenMode: StoryMode,
    val scales: List<ShotScale>,             // of the chosen photos, in order
    val dominantScene: SceneType,
    val warmth: Warmth,
    val harmonyPercent: Int,                 // 0..100 (psi or Q_1 as a percentage)
)
enum class Warmth { GOLDEN_WARM, NEUTRAL, COOL_BLUE, NIGHT_TUNGSTEN }

data class CurationResult(
    val chosen: CurationOption,
    val alternatives: Map<StoryMode, CurationOption>,   // best feasible option per mode (includes chosen.mode)
    val analyses: Map<String, PhotoAnalysis>,           // every analyzed candidate by id
    val clusters: List<SceneCluster>,
    val rejectedIds: List<String>,
    val note: CuratorNote,
    val suggestedLook: MasterLook,
)

sealed interface CurationProgress {
    data class Analyzing(val done: Int, val total: Int) : CurationProgress
    data class Clustered(val clusterCount: Int, val rejectedCount: Int) : CurationProgress
    data class Decided(val result: CurationResult) : CurationProgress
}

interface CurationEngine {
    /** Cold flow: analyzes all candidates (bounded parallel), clusters, decides. Ends with exactly one Decided. Empty input -> IllegalArgumentException. */
    fun curate(candidates: List<PhotoCandidate>): kotlinx.coroutines.flow.Flow<CurationProgress>
    /** Re-run the decision for a forced mode (user switch) without re-analysis. */
    fun decide(result: CurationResult, forcedMode: StoryMode?): CurationResult
}
