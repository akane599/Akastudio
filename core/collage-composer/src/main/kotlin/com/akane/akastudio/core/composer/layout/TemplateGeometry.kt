package com.akane.akastudio.core.composer.layout

import com.akane.akastudio.core.color.api.Rect4f
import com.akane.akastudio.core.composer.api.CanvasSize
import com.akane.akastudio.core.composer.api.LayoutStyle
import com.akane.akastudio.core.composer.api.StoryTemplate

/**
 * Cell rectangles of the editorial 9:16 templates, in canvas pixels (y down), one per slot in slot order.
 *
 * Every template is defined on the 1080×1920 base and multiplied by `canvas.width / 1080`, so the 4K canvas is
 * exactly twice the 1080 geometry. The gutter `g = style.gutterPx` (clamped to 0..48 base units) insets outer edges
 * by `g` and shared edges by `g/2` on each side; [StoryTemplate.HERO_FULL_BLEED] and
 * [StoryTemplate.HERO_GALLERY_MATTE] ignore it and [StoryTemplate.DUO_FLOATING_GALLERY] only widens its middle gap.
 */
object TemplateGeometry {
    internal const val BASE_WIDTH = 1080.0
    internal const val BASE_HEIGHT = 1920.0
    internal const val MAX_GUTTER = 48f
    internal const val MAX_CORNER_RADIUS = 64f

    private const val GALLERY_SIDE_MARGIN = 96.0
    private const val GALLERY_MAX_HEIGHT_FRACTION = 0.72
    private const val GALLERY_LIFT = 60.0
    private const val GALLERY_MAX_RADIUS = 24f
    private const val CINEMA_ASPECT = 2.35

    /**
     * Returns the cells of [template] on [canvas]. [photoAspects] holds each slot photo's upright width/height in
     * slot order; only [StoryTemplate.HERO_GALLERY_MATTE] reads it.
     *
     * @throws IllegalArgumentException when `photoAspects.size != template.photoCount` or the gallery photo's
     * aspect is not a positive finite number.
     */
    fun cells(template: StoryTemplate, canvas: CanvasSize, style: LayoutStyle, photoAspects: List<Float>): List<Rect4f> {
        require(photoAspects.size == template.photoCount) {
            "$template needs ${template.photoCount} photo aspects, got ${photoAspects.size}"
        }
        val g = gutterBase(style)
        val base = when (template) {
            StoryTemplate.HERO_FULL_BLEED -> listOf(BaseRect(0.0, 0.0, BASE_WIDTH, BASE_HEIGHT))
            StoryTemplate.HERO_GALLERY_MATTE -> listOf(galleryMatte(photoAspects[0]))
            StoryTemplate.DUO_CINEMATIC_SPLIT -> grid(g, Frac(0.0, 0.0, 1.0, 0.5), Frac(0.0, 0.5, 1.0, 1.0))
            StoryTemplate.DUO_ASYMMETRIC_EDITORIAL -> grid(g, Frac(0.0, 0.0, 1.0, 0.65), Frac(0.0, 0.65, 1.0, 1.0))
            StoryTemplate.DUO_FLOATING_GALLERY -> floatingGallery(g)
            StoryTemplate.TRIO_BENTO ->
                grid(g, Frac(0.0, 0.0, 1.0, 0.55), Frac(0.0, 0.55, 0.5, 1.0), Frac(0.5, 0.55, 1.0, 1.0))
            StoryTemplate.TRIO_CINEMA_STRIPS -> cinemaStrips(g)
            StoryTemplate.TRIO_MAGAZINE ->
                grid(g, Frac(0.0, 0.0, 0.62, 0.70), Frac(0.62, 0.0, 1.0, 0.70), Frac(0.0, 0.70, 1.0, 1.0))
            StoryTemplate.QUAD_GRID -> grid(
                g,
                Frac(0.0, 0.0, 0.5, 0.5), Frac(0.5, 0.0, 1.0, 0.5),
                Frac(0.0, 0.5, 0.5, 1.0), Frac(0.5, 0.5, 1.0, 1.0),
            )
            StoryTemplate.QUAD_HERO_FILMSTRIP -> grid(
                g,
                Frac(0.0, 0.0, 1.0, 0.70),
                Frac(0.0, 0.70, 1.0 / 3.0, 1.0), Frac(1.0 / 3.0, 0.70, 2.0 / 3.0, 1.0), Frac(2.0 / 3.0, 0.70, 1.0, 1.0),
            )
            StoryTemplate.QUAD_MOSAIC -> grid(
                g,
                Frac(0.0, 0.0, 0.58, 0.52), Frac(0.58, 0.0, 1.0, 0.30),
                Frac(0.58, 0.30, 1.0, 0.52), Frac(0.0, 0.52, 1.0, 1.0),
            )
        }
        val scale = scaleOf(canvas)
        return base.map { it.toCanvas(scale) }
    }

    /** Corner radius in canvas pixels applied to every cell of [template]. */
    internal fun cornerRadius(template: StoryTemplate, canvas: CanvasSize, style: LayoutStyle): Float {
        val radius = style.cornerRadiusPx.takeIf { it.isFinite() }?.coerceIn(0f, MAX_CORNER_RADIUS) ?: 0f
        val scale = scaleOf(canvas).toFloat()
        return when (template) {
            StoryTemplate.HERO_FULL_BLEED -> 0f
            StoryTemplate.HERO_GALLERY_MATTE -> minOf(radius, GALLERY_MAX_RADIUS) * scale
            else -> radius * scale
        }
    }

    /** Canvas pixels per 1080-wide base unit. */
    internal fun scaleOf(canvas: CanvasSize): Double = canvas.width / BASE_WIDTH

    private fun gutterBase(style: LayoutStyle): Double =
        (style.gutterPx.takeIf { it.isFinite() }?.coerceIn(0f, MAX_GUTTER) ?: 0f).toDouble()

    private fun grid(g: Double, vararg cells: Frac): List<BaseRect> = cells.map { f ->
        BaseRect(
            left = f.left * BASE_WIDTH + if (f.left == 0.0) g else g / 2,
            top = f.top * BASE_HEIGHT + if (f.top == 0.0) g else g / 2,
            right = f.right * BASE_WIDTH - if (f.right == 1.0) g else g / 2,
            bottom = f.bottom * BASE_HEIGHT - if (f.bottom == 1.0) g else g / 2,
        )
    }

    private fun galleryMatte(aspect: Float): BaseRect {
        require(aspect.isFinite() && aspect > 0f) { "gallery photo aspect must be positive and finite, got $aspect" }
        val maxHeight = GALLERY_MAX_HEIGHT_FRACTION * BASE_HEIGHT
        var width = BASE_WIDTH - 2 * GALLERY_SIDE_MARGIN
        var height = width / aspect
        if (height > maxHeight) {
            height = maxHeight
            width = height * aspect
        }
        val left = (BASE_WIDTH - width) / 2
        val top = (BASE_HEIGHT - height) / 2 - GALLERY_LIFT
        return BaseRect(left, top, left + width, top + height)
    }

    private fun floatingGallery(g: Double): List<BaseRect> = listOf(
        BaseRect(0.0667 * BASE_WIDTH, 0.0625 * BASE_HEIGHT, 0.9333 * BASE_WIDTH, 0.4844 * BASE_HEIGHT - g / 2),
        BaseRect(0.0667 * BASE_WIDTH, 0.5156 * BASE_HEIGHT + g / 2, 0.9333 * BASE_WIDTH, 0.9375 * BASE_HEIGHT),
    )

    private fun cinemaStrips(g: Double): List<BaseRect> {
        val width = BASE_WIDTH - 2 * g
        val height = width / CINEMA_ASPECT
        val firstTop = (BASE_HEIGHT - (3 * height + 2 * g)) / 2
        return (0 until 3).map { i ->
            val top = firstTop + i * (height + g)
            BaseRect(g, top, g + width, top + height)
        }
    }

    /** Template cell edges as fractions of the 1080×1920 base, before gutter insets. */
    private data class Frac(val left: Double, val top: Double, val right: Double, val bottom: Double)

    /** Cell in 1080×1920 base units. */
    private data class BaseRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
        fun toCanvas(scale: Double) = Rect4f(
            (left * scale).toFloat(),
            (top * scale).toFloat(),
            (right * scale).toFloat(),
            (bottom * scale).toFloat(),
        )
    }
}
