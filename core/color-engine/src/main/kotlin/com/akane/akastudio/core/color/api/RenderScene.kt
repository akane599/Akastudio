package com.akane.akastudio.core.color.api

sealed interface SceneBackground {
    data class Solid(val argb: Int) : SceneBackground
    /** Cover-fit, heavily blurred and dimmed copy of an image (Atmospheric Glass matte). */
    data class FrostedImage(val imageKey: String, val blurRadiusFraction: Float = 0.04f, val dim: Float = 0.25f) : SceneBackground
}

data class SceneLayer(
    val imageKey: String,                    // key registered with StoryRenderer.registerImage
    val dst: Rect4f,                         // destination in canvas pixels
    val crop: NormRect,                      // source region (upright source, normalized)
    val rotationDegrees: Float = 0f,         // rotation of the source about the crop centre (horizon leveling); crop is already inset so no empty corners
    val cornerRadiusPx: Float = 0f,
    val grade: GradeParams = GradeParams.NEUTRAL,
)

enum class OverlayFont { SANS, SERIF, MONO }
enum class OverlayAlign { START, CENTER, END }

data class TextOverlay(
    val text: String,
    val anchorX: Float, val baselineY: Float, // canvas pixels
    val sizePx: Float,
    val argb: Int,
    val align: OverlayAlign = OverlayAlign.CENTER,
    val letterSpacingEm: Float = 0.08f,
    val font: OverlayFont = OverlayFont.SANS,
)

data class RenderScene(
    val canvasWidth: Int,
    val canvasHeight: Int,
    val background: SceneBackground,
    val layers: List<SceneLayer>,
    val overlays: List<TextOverlay> = emptyList(),
)

/** Linear-light (sRGB primaries, may exceed 1.0) RGBA half-float pixels, row-major, top row first. */
class HalfFloatImage(val width: Int, val height: Int, val rgba: ShortArray) {
    init { require(rgba.size == width * height * 4) }
}

data class PreviewOptions(
    val compareSplit: Float? = null,         // null = off; else x in [0,1]: left of it renders ungraded (GradeParams.NEUTRAL with harmonization IDENTITY)
)
