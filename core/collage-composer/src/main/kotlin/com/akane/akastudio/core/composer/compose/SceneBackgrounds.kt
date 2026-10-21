package com.akane.akastudio.core.composer.compose

import com.akane.akastudio.core.color.api.OkLab
import com.akane.akastudio.core.color.api.PaletteSwatch
import com.akane.akastudio.core.color.api.SceneBackground
import com.akane.akastudio.core.color.math.ColorMath
import com.akane.akastudio.core.composer.api.BackgroundStyle
import com.akane.akastudio.core.composer.api.ComposerPhoto

/** Scene background for a [BackgroundStyle] and whether it is a light matte (dark caption ink). */
internal data class ResolvedBackground(val background: SceneBackground, val isLight: Boolean)

/** Maps [BackgroundStyle] to a [SceneBackground] using the anchor (slot 0) photo. */
internal object SceneBackgrounds {
    const val OFF_WHITE: Int = 0xFFF7F5F0.toInt()
    const val ANTHRACITE: Int = 0xFF121316.toInt()
    private const val LIGHT_L = 0.93f
    private const val DARK_L = 0.14f
    private const val CHROMA_SCALE = 0.25f

    fun resolve(style: BackgroundStyle, anchor: ComposerPhoto): ResolvedBackground = when (style) {
        BackgroundStyle.OffWhite -> ResolvedBackground(SceneBackground.Solid(OFF_WHITE), isLight = true)
        BackgroundStyle.Anthracite -> ResolvedBackground(SceneBackground.Solid(ANTHRACITE), isLight = false)
        BackgroundStyle.PaletteDerived -> paletteDerived(anchor.palette)
        BackgroundStyle.FrostedGlass -> ResolvedBackground(SceneBackground.FrostedImage(anchor.imageKey), isLight = false)
    }

    /**
     * The dominant (highest-weight) swatch with L pushed to 0.93 when it is lighter than 0.5 (else 0.14) and its
     * chroma scaled by 0.25 at the same hue. An empty or non-finite palette falls back to Anthracite.
     */
    private fun paletteDerived(palette: List<PaletteSwatch>): ResolvedBackground {
        val dominant = palette
            .filter { it.weight.isFinite() && it.lab.l.isFinite() && it.lab.a.isFinite() && it.lab.b.isFinite() }
            .maxByOrNull { it.weight }
            ?: return ResolvedBackground(SceneBackground.Solid(ANTHRACITE), isLight = false)
        val light = dominant.lab.l > 0.5f
        val lab = OkLab(
            l = if (light) LIGHT_L else DARK_L,
            a = dominant.lab.a * CHROMA_SCALE,
            b = dominant.lab.b * CHROMA_SCALE,
        )
        return ResolvedBackground(SceneBackground.Solid(ColorMath.okLabToArgb(lab)), isLight = light)
    }
}
