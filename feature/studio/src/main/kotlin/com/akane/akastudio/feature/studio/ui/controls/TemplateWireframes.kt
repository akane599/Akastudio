package com.akane.akastudio.feature.studio.ui.controls

import com.akane.akastudio.core.composer.api.StoryTemplate

/** A wireframe cell as fractions of a 9:16 canvas, edges in [0, 1]. */
internal data class WireRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * Fixed miniature wireframes of each [StoryTemplate] for the layout carousel. They show the template's
 * structure only; the real geometry (saliency, gutters, radii) comes from the composer.
 */
internal object TemplateWireframes {

    private const val G = 0.015f
    private const val HALF = 0.5f

    /** The cells of [template] in slot order; the list size always equals `template.photoCount`. */
    fun cells(template: StoryTemplate): List<WireRect> = when (template) {
        StoryTemplate.HERO_FULL_BLEED -> listOf(WireRect(0f, 0f, 1f, 1f))
        StoryTemplate.HERO_GALLERY_MATTE -> listOf(WireRect(0.1f, 0.12f, 0.9f, 0.78f))
        StoryTemplate.DUO_CINEMATIC_SPLIT -> listOf(
            WireRect(0f, 0f, 1f, HALF - G),
            WireRect(0f, HALF + G, 1f, 1f),
        )
        StoryTemplate.DUO_ASYMMETRIC_EDITORIAL -> listOf(
            WireRect(0f, 0f, 1f, 0.64f - G),
            WireRect(0.34f, 0.64f + G, 1f, 1f),
        )
        StoryTemplate.DUO_FLOATING_GALLERY -> listOf(
            WireRect(0.1f, 0.07f, 0.72f, 0.46f),
            WireRect(0.28f, 0.54f, 0.9f, 0.93f),
        )
        StoryTemplate.TRIO_BENTO -> listOf(
            WireRect(0f, 0f, 1f, 0.58f - G),
            WireRect(0f, 0.58f + G, HALF - G, 1f),
            WireRect(HALF + G, 0.58f + G, 1f, 1f),
        )
        StoryTemplate.TRIO_CINEMA_STRIPS -> listOf(
            WireRect(0f, 0f, 1f, 1f / 3f - G),
            WireRect(0f, 1f / 3f + G, 1f, 2f / 3f - G),
            WireRect(0f, 2f / 3f + G, 1f, 1f),
        )
        StoryTemplate.TRIO_MAGAZINE -> listOf(
            WireRect(0f, 0f, 0.6f - G, 1f),
            WireRect(0.6f + G, 0f, 1f, HALF - G),
            WireRect(0.6f + G, HALF + G, 1f, 1f),
        )
        StoryTemplate.QUAD_GRID -> listOf(
            WireRect(0f, 0f, HALF - G, HALF - G),
            WireRect(HALF + G, 0f, 1f, HALF - G),
            WireRect(0f, HALF + G, HALF - G, 1f),
            WireRect(HALF + G, HALF + G, 1f, 1f),
        )
        StoryTemplate.QUAD_HERO_FILMSTRIP -> listOf(
            WireRect(0f, 0f, 1f, 0.7f - G),
            WireRect(0f, 0.7f + G, 1f / 3f - G, 1f),
            WireRect(1f / 3f + G, 0.7f + G, 2f / 3f - G, 1f),
            WireRect(2f / 3f + G, 0.7f + G, 1f, 1f),
        )
        StoryTemplate.QUAD_MOSAIC -> listOf(
            WireRect(0f, 0f, 0.6f - G, 0.44f - G),
            WireRect(0.6f + G, 0f, 1f, 0.44f - G),
            WireRect(0f, 0.44f + G, 0.4f - G, 1f),
            WireRect(0.4f + G, 0.44f + G, 1f, 1f),
        )
    }
}
