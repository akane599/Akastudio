package com.akane.akastudio.core.composer.compose

import com.akane.akastudio.core.color.api.GradeParams
import com.akane.akastudio.core.color.api.OverlayAlign
import com.akane.akastudio.core.color.api.OverlayFont
import com.akane.akastudio.core.color.api.Rect4f
import com.akane.akastudio.core.color.api.RenderScene
import com.akane.akastudio.core.color.api.SceneLayer
import com.akane.akastudio.core.color.api.TextOverlay
import com.akane.akastudio.core.composer.api.CellPlacement
import com.akane.akastudio.core.composer.api.CollageComposer
import com.akane.akastudio.core.composer.api.CompositionRequest
import com.akane.akastudio.core.composer.api.StoryTemplate
import com.akane.akastudio.core.composer.crop.CropOverrides
import com.akane.akastudio.core.composer.crop.SmartCropSolver
import com.akane.akastudio.core.composer.layout.TemplateGeometry

/**
 * [CollageComposer] for the editorial 9:16 templates.
 *
 * [layout] places each slot photo in its [TemplateGeometry] cell with a [SmartCropSolver] crop (rotated by the
 * photo's horizon tilt) adjusted by any per-photo [com.akane.akastudio.core.composer.api.CellOverride].
 * [compose] turns that into a [RenderScene] with the style's background and, when enabled and known, the anchor
 * photo's EXIF caption in MONO type: dark `#2B2B2B` ink on light mattes (OffWhite, or a light PaletteDerived
 * matte), light `#D9D6CF` ink otherwise.
 *
 * Solved smart crops are memoised (see [SolvedCropCache]), so rebuilding a scene whose layout inputs did not change,
 * for example on every grade slider tick, does not search again. The cache is thread-safe and bounded.
 *
 * Every entry point throws [IllegalArgumentException] when `photos.size != template.photoCount` or a photo has no
 * pixels.
 */
class DefaultCollageComposer internal constructor(solver: CropSolver) : CollageComposer {

    constructor() : this(SmartCropSolver::solve)

    private val crops = SolvedCropCache(solver)

    override fun layout(request: CompositionRequest): List<CellPlacement> {
        val cells = cellsOf(request)
        return request.photos.mapIndexed { slot, photo ->
            val dst = cells[slot]
            val rotation = photo.horizonTiltDegrees.takeIf { it.isFinite() } ?: 0f
            val solved = crops.solve(photo, dst.width / dst.height, rotation)
            val crop = CropOverrides.apply(photo.widthPx, photo.heightPx, solved, request.overrides[photo.id], rotation)
            CellPlacement(photo.id, dst, crop, rotation)
        }
    }

    override fun compose(request: CompositionRequest): RenderScene {
        val placements = layout(request)
        val radius = TemplateGeometry.cornerRadius(request.template, request.canvas, request.style)
        val layers = placements.mapIndexed { slot, placement ->
            val photo = request.photos[slot]
            SceneLayer(
                imageKey = photo.imageKey,
                dst = placement.dst,
                crop = placement.crop,
                rotationDegrees = placement.rotationDegrees,
                cornerRadiusPx = radius,
                grade = request.grades[photo.id] ?: GradeParams.NEUTRAL,
            )
        }
        val anchor = request.photos[0]
        val background = SceneBackgrounds.resolve(request.style.background, anchor)
        val caption = if (request.style.showExifCaption) ExifCaption.format(anchor.metadata) else null
        val overlays = caption?.let { listOf(captionOverlay(it, request, placements[0].dst, background.isLight)) }
        return RenderScene(
            canvasWidth = request.canvas.width,
            canvasHeight = request.canvas.height,
            background = background.background,
            layers = layers,
            overlays = overlays ?: emptyList(),
        )
    }

    override fun hitTest(request: CompositionRequest, xPx: Float, yPx: Float): Int? {
        if (!xPx.isFinite() || !yPx.isFinite()) return null
        val cells = cellsOf(request)
        for (slot in cells.indices.reversed()) {
            val c = cells[slot]
            if (xPx >= c.left && xPx <= c.right && yPx >= c.top && yPx <= c.bottom) return slot
        }
        return null
    }

    private fun cellsOf(request: CompositionRequest): List<Rect4f> {
        require(request.photos.size == request.template.photoCount) {
            "${request.template} needs ${request.template.photoCount} photos, got ${request.photos.size}"
        }
        request.photos.forEach { require(it.widthPx > 0 && it.heightPx > 0) { "photo ${it.id} has no pixels" } }
        val aspects = request.photos.map { it.widthPx.toFloat() / it.heightPx }
        return TemplateGeometry.cells(request.template, request.canvas, request.style, aspects)
    }

    private fun captionOverlay(text: String, request: CompositionRequest, anchorDst: Rect4f, lightMatte: Boolean): TextOverlay {
        val scale = TemplateGeometry.scaleOf(request.canvas).toFloat()
        val baseline = if (request.template == StoryTemplate.HERO_GALLERY_MATTE) {
            anchorDst.bottom + GALLERY_CAPTION_OFFSET * scale
        } else {
            request.canvas.height - EDGE_CAPTION_OFFSET * scale
        }
        return TextOverlay(
            text = text,
            anchorX = request.canvas.width / 2f,
            baselineY = baseline,
            sizePx = CAPTION_SIZE * scale,
            argb = if (lightMatte) CAPTION_DARK else CAPTION_LIGHT,
            align = OverlayAlign.CENTER,
            letterSpacingEm = CAPTION_LETTER_SPACING_EM,
            font = OverlayFont.MONO,
        )
    }

    private companion object {
        const val GALLERY_CAPTION_OFFSET = 56f
        const val EDGE_CAPTION_OFFSET = 64f
        const val CAPTION_SIZE = 26f
        const val CAPTION_LETTER_SPACING_EM = 0.12f
        val CAPTION_DARK = 0xFF2B2B2B.toInt()
        val CAPTION_LIGHT = 0xFFD9D6CF.toInt()
    }
}
