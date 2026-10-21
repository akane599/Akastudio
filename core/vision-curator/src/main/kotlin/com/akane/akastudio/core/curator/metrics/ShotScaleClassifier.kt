package com.akane.akastudio.core.curator.metrics

import com.akane.akastudio.core.curator.api.FaceObservation
import com.akane.akastudio.core.curator.api.SaliencyMap
import com.akane.akastudio.core.curator.api.ShotScale

/** Classifies a photo as [ShotScale.WIDE], [ShotScale.MEDIUM] or [ShotScale.DETAIL]. */
object ShotScaleClassifier {
    private const val SUBJECT_CELL = 0.5f
    private const val TELE_FOCAL = 85
    private const val WIDE_FOCAL = 24
    private const val TELE_DETAIL_SUBJECT = 0.25f
    private const val DETAIL_SUBJECT = 0.45f
    private const val MEDIUM_FACE = 0.04f
    private const val MEDIUM_SUBJECT = 0.18f
    private const val WIDE_LENS_SUBJECT = 0.25f

    /**
     * With subjectFraction the share of saliency cells at or above 0.5 and faceFraction the largest face
     * box area: a focal length (35 mm equivalent) of at most 24 mm with subjectFraction below 0.25 forces
     * WIDE; otherwise DETAIL when focal >= 85 and subjectFraction >= 0.25, or with no faces and
     * subjectFraction >= 0.45; then MEDIUM when faceFraction >= 0.04 or subjectFraction >= 0.18; else WIDE.
     */
    fun classify(saliency: SaliencyMap, faces: List<FaceObservation>, focalLength35mm: Int?): ShotScale {
        val subjectFraction = saliency.values.count { it >= SUBJECT_CELL }.toFloat() / saliency.values.size
        val faceFraction = faces.maxOfOrNull {
            it.box.width.coerceAtLeast(0f) * it.box.height.coerceAtLeast(0f)
        } ?: 0f
        return when {
            focalLength35mm != null && focalLength35mm <= WIDE_FOCAL && subjectFraction < WIDE_LENS_SUBJECT ->
                ShotScale.WIDE
            focalLength35mm != null && focalLength35mm >= TELE_FOCAL && subjectFraction >= TELE_DETAIL_SUBJECT ->
                ShotScale.DETAIL
            faces.isEmpty() && subjectFraction >= DETAIL_SUBJECT -> ShotScale.DETAIL
            faceFraction >= MEDIUM_FACE || subjectFraction >= MEDIUM_SUBJECT -> ShotScale.MEDIUM
            else -> ShotScale.WIDE
        }
    }
}
