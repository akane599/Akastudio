package com.akane.akastudio.core.ingestion.model

/**
 * @property captureEpochMillis DateTimeOriginal (+ OffsetTimeOriginal when present; else device default zone).
 */
data class CaptureMetadata(
    val captureEpochMillis: Long?,
    val captureHasUtcOffset: Boolean,
    val focalLength35mm: Int?,
    val fNumber: Double?,
    val exposureTimeSeconds: Double?,
    val iso: Int?,
    val orientation: ExifOrientation,
    val colorSpace: ColorSpaceTag,
    val hasUltraHdrGainMap: Boolean,
) {
    companion object {
        /** All nulls, [ExifOrientation.NORMAL], [ColorSpaceTag.UNKNOWN], false. */
        val EMPTY: CaptureMetadata = CaptureMetadata(
            captureEpochMillis = null,
            captureHasUtcOffset = false,
            focalLength35mm = null,
            fNumber = null,
            exposureTimeSeconds = null,
            iso = null,
            orientation = ExifOrientation.NORMAL,
            colorSpace = ColorSpaceTag.UNKNOWN,
            hasUltraHdrGainMap = false,
        )
    }
}
