package com.akane.akastudio.core.composer.compose

import com.akane.akastudio.core.color.api.OkLab
import com.akane.akastudio.core.color.api.PaletteSwatch
import com.akane.akastudio.core.color.api.SceneBackground
import com.akane.akastudio.core.color.math.ColorMath
import com.akane.akastudio.core.composer.api.BackgroundStyle
import com.akane.akastudio.core.composer.api.ComposerPhoto

/** Maps [BackgroundStyle] to a [SceneBackground] using the anchor (slot 0) photo. */
internal object SceneBackgrounds {
    const val OFF_WHITE: Int = 0xFFF7F5F0.toInt()
    const val ANTHRACITE: Int = 0xFF121316.toInt()
    private const val LIGHT_L = 0.93f
    private const val DARK_L = 0.14f
    private const val CHROMA_SCALE = 0.25f

    private const val LIGHT_MATTE_MIN_L = 0.5f

    fun resolve(style: BackgroundStyle, anchor: ComposerPhoto): SceneBackground = when (style) {
        BackgroundStyle.OffWhite -> SceneBackground.Solid(OFF_WHITE)
        BackgroundStyle.Anthracite -> SceneBackground.Solid(ANTHRACITE)
        BackgroundStyle.PaletteDerived -> paletteDerived(anchor.palette)
        BackgroundStyle.FrostedGlass -> SceneBackground.FrostedImage(anchor.imageKey)
    }

    /**
     * Whether [background] is a solid matte light enough (OKLab L > 0.5) to take dark caption ink, read from its own
     * colour. An image background is never a matte: its lightness varies across the frame, so text over it uses light
     * ink on a halo instead.
     */
    fun isLightMatte(background: SceneBackground): Boolean =
        background is SceneBackground.Solid && ColorMath.argbToOkLab(background.argb).l > LIGHT_MATTE_MIN_L

    /**
     * The dominant (highest-weight) swatch with L pushed to 0.93 when it is lighter than 0.5 (else 0.14) and its
     * chroma scaled by 0.25 at the same hue. An empty or non-finite palette falls back to Anthracite.
     */
    private fun paletteDerived(palette: List<PaletteSwatch>): SceneBackground.Solid {
        val dominant = palette
            .filter { it.weight.isFinite() && it.lab.l.isFinite() && it.lab.a.isFinite() && it.lab.b.isFinite() }
            .maxByOrNull { it.weight }
            ?: return SceneBackground.Solid(ANTHRACITE)
        val light = dominant.lab.l > LIGHT_MATTE_MIN_L
        val lab = OkLab(
            l = if (light) LIGHT_L else DARK_L,
            a = dominant.lab.a * CHROMA_SCALE,
            b = dominant.lab.b * CHROMA_SCALE,
        )
        return SceneBackground.Solid(ColorMath.okLabToArgb(lab))
    }
}
