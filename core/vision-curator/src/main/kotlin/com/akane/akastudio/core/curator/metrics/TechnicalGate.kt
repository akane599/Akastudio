package com.akane.akastudio.core.curator.metrics

import com.akane.akastudio.core.curator.api.FaceObservation
import com.akane.akastudio.core.curator.api.RejectReason
import com.akane.akastudio.core.curator.api.TechnicalScore

/** Combines sharpness, exposure and face quality into a [TechnicalScore] and applies the reject gate. */
object TechnicalGate {
    /** Minimum face box area, as a fraction of the frame, for a face to count as prominent. */
    const val PROMINENT_FACE_AREA = 0.02f

    private const val EYE_OPEN_FULL = 0.6f
    private const val EYES_CLOSED_BELOW = 0.35f
    private const val FACE_BLUR_BELOW = 0.15f
    private const val BLURRY_BELOW = 0.18f
    private const val BAD_EXPOSURE_BELOW = 0.15f

    /**
     * `total = 0.5*sharpness + 0.3*exposure + 0.2*faceQuality`, where faceQuality is the area-weighted mean
     * of `0.5*min(1, min(leftEye, rightEye)/0.6) + 0.5*face.sharpness` over prominent faces (1 with none).
     * [imageArea] is the frame area in the units of the face boxes (1 for normalized boxes).
     */
    fun score(sharpness: Float, exposure: Float, faces: List<FaceObservation>, imageArea: Float = 1f): TechnicalScore {
        require(imageArea > 0f) { "imageArea must be positive" }
        val s = clamp01(sharpness)
        val e = clamp01(exposure)
        var weighted = 0.0
        var totalArea = 0.0
        for (face in faces) {
            val area = faceArea(face) / imageArea
            if (area < PROMINENT_FACE_AREA) continue
            val eyes = minOf(1f, minOf(face.leftEyeOpenness, face.rightEyeOpenness).coerceAtLeast(0f) / EYE_OPEN_FULL)
            weighted += area * (0.5f * eyes + 0.5f * clamp01(face.sharpness))
            totalArea += area
        }
        val faceQuality = if (totalArea > 0.0) clamp01((weighted / totalArea).toFloat()) else 1f
        return TechnicalScore(
            sharpness = s,
            exposure = e,
            faceQuality = faceQuality,
            total = clamp01(0.5f * s + 0.3f * e + 0.2f * faceQuality),
        )
    }

    /**
     * First failing check, in order: [RejectReason.EYES_CLOSED] (a prominent face with both eyes below 0.35
     * or face sharpness below 0.15), [RejectReason.BLURRY] (sharpness below 0.18),
     * [RejectReason.BAD_EXPOSURE] (exposure below 0.15); null when the photo passes.
     */
    fun rejectReason(t: TechnicalScore, faces: List<FaceObservation>): RejectReason? {
        val closedOrBlurredFace = faces.any { face ->
            faceArea(face) >= PROMINENT_FACE_AREA &&
                (maxOf(face.leftEyeOpenness, face.rightEyeOpenness) < EYES_CLOSED_BELOW || face.sharpness < FACE_BLUR_BELOW)
        }
        return when {
            closedOrBlurredFace -> RejectReason.EYES_CLOSED
            t.sharpness < BLURRY_BELOW -> RejectReason.BLURRY
            t.exposure < BAD_EXPOSURE_BELOW -> RejectReason.BAD_EXPOSURE
            else -> null
        }
    }

    private fun faceArea(face: FaceObservation): Float =
        face.box.width.coerceAtLeast(0f) * face.box.height.coerceAtLeast(0f)
}
