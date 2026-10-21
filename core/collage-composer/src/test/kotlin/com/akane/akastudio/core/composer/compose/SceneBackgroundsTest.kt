package com.akane.akastudio.core.composer.compose

import com.akane.akastudio.core.color.api.OkLab
import com.akane.akastudio.core.color.api.PaletteSwatch
import com.akane.akastudio.core.color.api.SceneBackground
import com.akane.akastudio.core.composer.api.BackgroundStyle
import com.akane.akastudio.core.composer.api.ComposerPhoto
import com.akane.akastudio.core.curator.api.SaliencyMap
import com.akane.akastudio.core.ingestion.model.CaptureMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneBackgroundsTest {

    private fun anchor(palette: List<PaletteSwatch>) = ComposerPhoto(
        id = "id-0",
        imageKey = "key-0",
        widthPx = 1600,
        heightPx = 1200,
        saliency = SaliencyMap(4, 3, FloatArray(12) { 1f }),
        faces = emptyList(),
        palette = palette,
        horizonTiltDegrees = 0f,
        metadata = CaptureMetadata.EMPTY,
    )

    private val brightAnchor = anchor(listOf(PaletteSwatch(OkLab(0.95f, 0.01f, 0.02f), 1f)))
    private val darkAnchor = anchor(listOf(PaletteSwatch(OkLab(0.15f, 0.02f, -0.01f), 1f)))

    private fun lightMatte(style: BackgroundStyle, photo: ComposerPhoto) =
        SceneBackgrounds.isLightMatte(SceneBackgrounds.resolve(style, photo))

    @Test
    fun solidMattes_readTheirLightnessFromTheirOwnColour() {
        assertTrue(lightMatte(BackgroundStyle.OffWhite, darkAnchor))
        assertFalse(lightMatte(BackgroundStyle.Anthracite, brightAnchor))
        assertTrue("a light dominant swatch gives a light matte", lightMatte(BackgroundStyle.PaletteDerived, brightAnchor))
        assertFalse("a dark dominant swatch gives a dark matte", lightMatte(BackgroundStyle.PaletteDerived, darkAnchor))
        assertFalse("an empty palette falls back to Anthracite", lightMatte(BackgroundStyle.PaletteDerived, anchor(emptyList())))
    }

    @Test
    fun frostedGlass_isAnImage_notAMatte_evenOverABrightAnchor() {
        val frosted = SceneBackgrounds.resolve(BackgroundStyle.FrostedGlass, brightAnchor)
        assertEquals(SceneBackground.FrostedImage("key-0"), frosted)
        assertFalse(SceneBackgrounds.isLightMatte(frosted))
    }
}
