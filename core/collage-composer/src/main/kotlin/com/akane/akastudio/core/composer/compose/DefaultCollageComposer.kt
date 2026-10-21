package com.akane.akastudio.core.composer.compose

import com.akane.akastudio.core.color.api.GradeParams
import com.akane.akastudio.core.color.api.OverlayAlign
import com.akane.akastudio.core.color.api.OverlayFont
import com.akane.akastudio.core.color.api.Rect4f
import com.akane.akastudio.core.color.api.RenderScene
import com.akane.akastudio.core.color.api.SceneBackground
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
 * (slot 0) photo's EXIF caption in MONO type, always placed on or directly under that photo so it labels the photo it
 * sits on:
 * - [StoryTemplate.HERO_GALLERY_MATTE] sets it on the matte under the photo. On a solid matte the ink follows the
 *   matte: dark `#2B2B2B` on a light one (OffWhite, or a light PaletteDerived matte), light `#D9D6CF` otherwise.
 * - Every other template sets it inside the anchor's cell, centred, its baseline 64 base units above the cell's
 *   bottom edge and shrunk to fit the cell width less 24-unit margins.
 * Text over image content (the photo, or the FrostedGlass matte, which is a blurred photo) cannot take its ink from
 * the matte and has no known local luminance at composition time, so it uses light ink on a dark halo, which reads on
 * any luminance.
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
        val overlays = caption?.let { listOf(captionOverlay(it, request, placements[0].dst, background)) }
        return RenderScene(
            canvasWidth = request.canvas.width,
            canvasHeight = request.canvas.height,
            background = background,
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

    private fun captionOverlay(text: String, request: CompositionRequest, anchorDst: Rect4f, background: SceneBackground): TextOverlay {
        val scale = TemplateGeometry.scaleOf(request.canvas).toFloat()
        val margin = CAPTION_SIDE_MARGIN * scale
        val onMatte = request.template == StoryTemplate.HERO_GALLERY_MATTE
        val solidMatte = onMatte && background is SceneBackground.Solid
        return TextOverlay(
            text = text,
            anchorX = (anchorDst.left + anchorDst.right) / 2f,
            baselineY = if (onMatte) anchorDst.bottom + GALLERY_CAPTION_OFFSET * scale else anchorDst.bottom - EDGE_CAPTION_OFFSET * scale,
            sizePx = CAPTION_SIZE * scale,
            argb = if (onMatte && SceneBackgrounds.isLightMatte(background)) CAPTION_DARK else CAPTION_LIGHT,
            align = OverlayAlign.CENTER,
            letterSpacingEm = CAPTION_LETTER_SPACING_EM,
            font = OverlayFont.MONO,
            haloArgb = if (solidMatte) 0 else CAPTION_HALO,
            maxWidthPx = if (onMatte) request.canvas.width - 2 * margin else anchorDst.width - 2 * margin,
        )
    }

    private companion object {
        const val GALLERY_CAPTION_OFFSET = 56f
        const val EDGE_CAPTION_OFFSET = 64f
        const val CAPTION_SIZE = 26f
        const val CAPTION_LETTER_SPACING_EM = 0.12f
        const val CAPTION_SIDE_MARGIN = 24f
        /** 70% black halo behind light ink set over image content. */
        val CAPTION_HALO = 0xB3000000.toInt()
        val CAPTION_DARK = 0xFF2B2B2B.toInt()
        val CAPTION_LIGHT = 0xFFD9D6CF.toInt()
    }
}
