package com.akane.akastudio.core.export.api

import com.akane.akastudio.core.color.api.RenderScene

enum class ExportFormat { PNG_16, PNG_8, WEBP_LOSSLESS, SOCIAL_STORY_JPEG, ULTRA_HDR_JPEG }
enum class ExportResolution(val width: Int, val height: Int) { HD_1080(1080, 1920), UHD_4K(2160, 3840) }

data class ExportMetadata(
    val captureEpochMillis: Long?,           // of the anchor photo
    val description: String?,                // optional ImageDescription
    val software: String = "AkaStudio",
)

data class ExportRequest(
    val scene: RenderScene,                  // canvas must equal the render size chosen for the format (see ExportPlan)
    val format: ExportFormat,
    val resolution: ExportResolution,
    val metadata: ExportMetadata,
    val hdrBoost: Boolean = false,           // Ultra HDR gain map (API 34+); ignored below 34
)

data class ExportResult(val uri: String, val mimeType: String, val width: Int, val height: Int, val byteSize: Long, val format: ExportFormat)

sealed interface ExportProgress {
    data class Stage(val name: ExportStage, val fraction: Float) : ExportProgress
    data class Done(val result: ExportResult) : ExportProgress
}
enum class ExportStage { RENDER, RESAMPLE, SHARPEN, DITHER, ENCODE, WRITE }

/** Render-size plan: SOCIAL_STORY_JPEG at HD_1080 renders the scene at 2x and Lanczos-3 downsamples; everything else renders at the target size. */
object ExportPlan {
    /** (width, height) of the scene canvas to render for [format] at [resolution]. */
    fun renderSize(format: ExportFormat, resolution: ExportResolution): Pair<Int, Int> =
        if (format == ExportFormat.SOCIAL_STORY_JPEG && resolution == ExportResolution.HD_1080) {
            resolution.width * 2 to resolution.height * 2
        } else {
            resolution.width to resolution.height
        }
}

interface StoryExporter {
    /** Renders, processes, encodes and writes to MediaStore (Pictures/AkaStudio, IS_PENDING protocol). */
    fun export(request: ExportRequest): kotlinx.coroutines.flow.Flow<ExportProgress>
}
