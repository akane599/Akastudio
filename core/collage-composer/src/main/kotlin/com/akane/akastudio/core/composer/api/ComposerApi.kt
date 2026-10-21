package com.akane.akastudio.core.composer.api

import com.akane.akastudio.core.color.api.GradeParams
import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.color.api.PaletteSwatch
import com.akane.akastudio.core.color.api.Rect4f
import com.akane.akastudio.core.color.api.RenderScene
import com.akane.akastudio.core.curator.api.FaceObservation
import com.akane.akastudio.core.curator.api.SaliencyMap
import com.akane.akastudio.core.ingestion.model.CaptureMetadata

enum class StoryTemplate(val photoCount: Int) {
    HERO_FULL_BLEED(1), HERO_GALLERY_MATTE(1),
    DUO_CINEMATIC_SPLIT(2), DUO_ASYMMETRIC_EDITORIAL(2), DUO_FLOATING_GALLERY(2),
    TRIO_BENTO(3), TRIO_CINEMA_STRIPS(3), TRIO_MAGAZINE(3),
    QUAD_GRID(4), QUAD_HERO_FILMSTRIP(4), QUAD_MOSAIC(4);
    companion object {
        /** Templates whose [photoCount] equals [count], in declaration order; empty when none. */
        fun forCount(count: Int): List<StoryTemplate> = entries.filter { it.photoCount == count }
    }
}

enum class CanvasSize(val width: Int, val height: Int) { STORY_1080(1080, 1920), STORY_4K(2160, 3840) }

sealed interface BackgroundStyle {
    data object OffWhite : BackgroundStyle        // #F7F5F0
    data object Anthracite : BackgroundStyle      // #121316
    data object PaletteDerived : BackgroundStyle  // derived from the anchor photo's palette
    data object FrostedGlass : BackgroundStyle    // SceneBackground.FrostedImage of the anchor photo
}

data class LayoutStyle(
    val gutterPx: Float = 12f,               // 0..48, in 1080-wide units (scaled for 4K)
    val cornerRadiusPx: Float = 0f,          // 0..64, in 1080-wide units
    val background: BackgroundStyle = BackgroundStyle.Anthracite,
    val showExifCaption: Boolean = false,
)

data class ComposerPhoto(
    val id: String,
    val imageKey: String,                    // StoryRenderer key
    val widthPx: Int, val heightPx: Int,     // upright
    val saliency: SaliencyMap,
    val faces: List<FaceObservation>,
    val palette: List<PaletteSwatch>,
    val horizonTiltDegrees: Float,           // 0 when none detected; |v| <= 5
    val metadata: CaptureMetadata,
)

data class CellOverride(val crop: NormRect? = null, val zoom: Float = 1f, val panX: Float = 0f, val panY: Float = 0f)

data class CompositionRequest(
    val template: StoryTemplate,
    val style: LayoutStyle,
    val canvas: CanvasSize,
    val photos: List<ComposerPhoto>,         // slot order; size == template.photoCount
    val grades: Map<String, GradeParams>,    // by photo id
    val overrides: Map<String, CellOverride> = emptyMap(),
)

data class CellPlacement(val photoId: String, val dst: Rect4f, val crop: NormRect, val rotationDegrees: Float)

interface CollageComposer {
    fun layout(request: CompositionRequest): List<CellPlacement>
    fun compose(request: CompositionRequest): RenderScene
    /** Slot index under a canvas point (for drag-swap / pan hit testing), or null. */
    fun hitTest(request: CompositionRequest, xPx: Float, yPx: Float): Int?
}
