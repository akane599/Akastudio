package com.akane.akastudio.core.composer.compose

import com.akane.akastudio.core.color.api.GradeParams
import com.akane.akastudio.core.color.api.MasterLook
import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.color.api.OkLab
import com.akane.akastudio.core.color.api.OverlayAlign
import com.akane.akastudio.core.color.api.OverlayFont
import com.akane.akastudio.core.color.api.PaletteSwatch
import com.akane.akastudio.core.color.api.Rect4f
import com.akane.akastudio.core.color.api.SceneBackground
import com.akane.akastudio.core.color.api.TextOverlay
import com.akane.akastudio.core.color.math.ColorMath
import com.akane.akastudio.core.composer.api.BackgroundStyle
import com.akane.akastudio.core.composer.api.CanvasSize
import com.akane.akastudio.core.composer.api.CellOverride
import com.akane.akastudio.core.composer.api.ComposerPhoto
import com.akane.akastudio.core.composer.api.CompositionRequest
import com.akane.akastudio.core.composer.api.LayoutStyle
import com.akane.akastudio.core.composer.api.StoryTemplate
import com.akane.akastudio.core.composer.layout.TemplateGeometry
import com.akane.akastudio.core.curator.api.SaliencyMap
import com.akane.akastudio.core.ingestion.model.CaptureMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultCollageComposerTest {

    private val composer = DefaultCollageComposer()
    private val exif = CaptureMetadata.EMPTY.copy(focalLength35mm = 24, fNumber = 1.8, exposureTimeSeconds = 0.004, iso = 64)

    private fun photo(
        index: Int,
        palette: List<PaletteSwatch> = emptyList(),
        tilt: Float = 0f,
        metadata: CaptureMetadata = exif,
        width: Int = 1600,
        height: Int = 1200,
    ) = ComposerPhoto(
        id = "id-$index",
        imageKey = "key-$index",
        widthPx = width,
        heightPx = height,
        saliency = SaliencyMap(64, 48, FloatArray(64 * 48) { 1f }),
        faces = emptyList(),
        palette = palette,
        horizonTiltDegrees = tilt,
        metadata = metadata,
    )

    private fun request(
        template: StoryTemplate,
        style: LayoutStyle = LayoutStyle(),
        canvas: CanvasSize = CanvasSize.STORY_1080,
        photos: List<ComposerPhoto> = List(template.photoCount) { photo(it) },
        grades: Map<String, GradeParams> = emptyMap(),
        overrides: Map<String, CellOverride> = emptyMap(),
    ) = CompositionRequest(template, style, canvas, photos, grades, overrides)

    @Test
    fun solidBackgrounds_offWhiteAndAnthracite() {
        val off = composer.compose(request(StoryTemplate.QUAD_GRID, LayoutStyle(background = BackgroundStyle.OffWhite)))
        assertEquals(SceneBackground.Solid(0xFFF7F5F0.toInt()), off.background)
        val dark = composer.compose(request(StoryTemplate.QUAD_GRID, LayoutStyle(background = BackgroundStyle.Anthracite)))
        assertEquals(SceneBackground.Solid(0xFF121316.toInt()), dark.background)
    }

    @Test
    fun paletteDerivedBackground_usesTheAnchorDominantSwatch() {
        val lightAnchor = listOf(PaletteSwatch(OkLab(0.2f, -0.05f, 0.02f), 0.3f), PaletteSwatch(OkLab(0.7f, 0.08f, 0.04f), 0.7f))
        val photos = listOf(photo(0, lightAnchor), photo(1, listOf(PaletteSwatch(OkLab(0.1f, 0.1f, 0.1f), 1f))))
        val light = composer.compose(request(StoryTemplate.DUO_CINEMATIC_SPLIT, LayoutStyle(background = BackgroundStyle.PaletteDerived), photos = photos))
        assertEquals(SceneBackground.Solid(ColorMath.okLabToArgb(OkLab(0.93f, 0.02f, 0.01f))), light.background)

        val darkAnchor = listOf(PaletteSwatch(OkLab(0.35f, 0.12f, -0.08f), 0.9f), PaletteSwatch(OkLab(0.9f, 0f, 0f), 0.1f))
        val dark = composer.compose(
            request(StoryTemplate.HERO_FULL_BLEED, LayoutStyle(background = BackgroundStyle.PaletteDerived), photos = listOf(photo(0, darkAnchor))),
        )
        assertEquals(SceneBackground.Solid(ColorMath.okLabToArgb(OkLab(0.14f, 0.03f, -0.02f))), dark.background)

        val empty = composer.compose(request(StoryTemplate.HERO_FULL_BLEED, LayoutStyle(background = BackgroundStyle.PaletteDerived)))
        assertEquals("empty palette falls back to Anthracite", SceneBackground.Solid(0xFF121316.toInt()), empty.background)
    }

    @Test
    fun frostedGlassBackground_blursTheAnchorImage() {
        val scene = composer.compose(request(StoryTemplate.TRIO_BENTO, LayoutStyle(background = BackgroundStyle.FrostedGlass)))
        assertEquals(SceneBackground.FrostedImage("key-0"), scene.background)
    }

    @Test
    fun compose_buildsOneLayerPerSlotFromTheLayout() {
        val grade = GradeParams(look = MasterLook.PORTRA_400_GOLDEN, exposureEv = 0.5f)
        val photos = listOf(photo(0, tilt = 2.5f), photo(1), photo(2), photo(3))
        val style = LayoutStyle(gutterPx = 24f, cornerRadiusPx = 16f)
        val req = request(StoryTemplate.QUAD_MOSAIC, style, CanvasSize.STORY_4K, photos, grades = mapOf("id-1" to grade))
        val scene = composer.compose(req)
        val placements = composer.layout(req)
        val cells = TemplateGeometry.cells(StoryTemplate.QUAD_MOSAIC, CanvasSize.STORY_4K, style, List(4) { 4f / 3f })

        assertEquals(2160, scene.canvasWidth)
        assertEquals(3840, scene.canvasHeight)
        assertEquals(4, scene.layers.size)
        scene.layers.forEachIndexed { i, layer ->
            assertEquals("key-$i", layer.imageKey)
            assertEquals(cells[i], layer.dst)
            assertEquals(placements[i].crop, layer.crop)
            assertEquals(32f, layer.cornerRadiusPx)
        }
        assertEquals(2.5f, scene.layers[0].rotationDegrees)
        assertEquals(2.5f, placements[0].rotationDegrees)
        assertEquals(grade, scene.layers[1].grade)
        assertEquals(GradeParams.NEUTRAL, scene.layers[2].grade)
        assertEquals(listOf("id-0", "id-1", "id-2", "id-3"), placements.map { it.photoId })
    }

    @Test
    fun layout_cropsMatchTheSlotAspect_andApplyOverridesByPhotoId() {
        val req = request(StoryTemplate.DUO_ASYMMETRIC_EDITORIAL, overrides = mapOf("id-1" to CellOverride(crop = NormRect(-1f, 0.1f, 0.5f, 0.6f))))
        val placements = composer.layout(req)
        val dst = placements[0].dst
        val crop = placements[0].crop
        assertEquals(dst.width / dst.height, (crop.width * 1600) / (crop.height * 1200), 1e-3f)
        val overridden = placements[1].crop
        assertEquals(0f, overridden.left, 1e-6f)
        assertEquals(0.1f, overridden.top, 1e-6f)
        assertEquals(0.5f, overridden.right, 1e-6f)
        assertEquals(0.6f, overridden.bottom, 1e-6f)
    }

    @Test
    fun caption_galleryMatte_sitsUnderThePhotoInDarkInkOnOffWhite() {
        val style = LayoutStyle(cornerRadiusPx = 40f, background = BackgroundStyle.OffWhite, showExifCaption = true)
        val req = request(StoryTemplate.HERO_GALLERY_MATTE, style)
        val scene = composer.compose(req)
        val overlay = scene.overlays.single()
        assertEquals("24mm · f/1.8 · 1/250s · ISO 64", overlay.text)
        assertEquals(scene.layers[0].dst.bottom + 56f, overlay.baselineY, 1e-3f)
        assertEquals(540f, overlay.anchorX)
        assertEquals(26f, overlay.sizePx)
        assertEquals(0.12f, overlay.letterSpacingEm)
        assertEquals(OverlayFont.MONO, overlay.font)
        assertEquals(OverlayAlign.CENTER, overlay.align)
        assertEquals(0xFF2B2B2B.toInt(), overlay.argb)
        assertEquals("a solid matte needs no halo", 0, overlay.haloArgb)
        assertEquals(1080f - 48f, overlay.maxWidthPx)
        assertEquals(24f, scene.layers[0].cornerRadiusPx, 0f)
        assertTrue("the caption lies on the matte, clear of the photo", captionBox(overlay).top > scene.layers[0].dst.bottom)
        assertTrue("and inside the canvas", captionBox(overlay).bottom < scene.canvasHeight)

        val anthracite = composer.compose(request(StoryTemplate.HERO_GALLERY_MATTE, style.copy(background = BackgroundStyle.Anthracite)))
        assertEquals(0xFFD9D6CF.toInt(), anthracite.overlays.single().argb)
        assertEquals(0, anthracite.overlays.single().haloArgb)
    }

    @Test
    fun caption_onTheFrostedMatte_usesLightInkOnAHalo() {
        val style = LayoutStyle(background = BackgroundStyle.FrostedGlass, showExifCaption = true)
        val overlay = composer.compose(request(StoryTemplate.HERO_GALLERY_MATTE, style)).overlays.single()
        assertEquals(0xFFD9D6CF.toInt(), overlay.argb)
        assertEquals(0xB3000000.toInt(), overlay.haloArgb)
    }

    @Test
    fun caption_otherTemplates_sitInsideTheAnchorCell_scaledFor4K_inLightInkOnAHalo() {
        // OffWhite would give dark ink on the matte, but the text sits on a photo, so it must not follow the matte.
        val style = LayoutStyle(background = BackgroundStyle.OffWhite, showExifCaption = true)
        val scene = composer.compose(request(StoryTemplate.QUAD_GRID, style, CanvasSize.STORY_4K))
        val overlay = scene.overlays.single()
        val anchor = scene.layers[0].dst
        assertEquals(anchor.bottom - 128f, overlay.baselineY)
        assertEquals((anchor.left + anchor.right) / 2f, overlay.anchorX)
        assertEquals(anchor.width - 96f, overlay.maxWidthPx)
        assertEquals(52f, overlay.sizePx)
        assertEquals(0xFFD9D6CF.toInt(), overlay.argb)
        assertEquals(0xB3000000.toInt(), overlay.haloArgb)

        val hero = composer.compose(request(StoryTemplate.HERO_FULL_BLEED, style.copy(background = BackgroundStyle.Anthracite)))
        assertEquals("full bleed keeps its edge offset", 1920f - 64f, hero.overlays.single().baselineY)
        assertEquals(540f, hero.overlays.single().anchorX)
        assertEquals(0xB3000000.toInt(), hero.overlays.single().haloArgb)
    }

    @Test
    fun caption_everyTemplate_describesThePhotoItSitsOn() {
        val photos = List(4) { i -> photo(i, metadata = CaptureMetadata.EMPTY.copy(focalLength35mm = 20 + i, iso = 100 * (i + 1))) }
        for (template in StoryTemplate.entries) for (gutter in floatArrayOf(0f, 12f, 48f)) for (canvas in CanvasSize.entries) {
            val style = LayoutStyle(gutterPx = gutter, cornerRadiusPx = 64f, background = BackgroundStyle.PaletteDerived, showExifCaption = true)
            val scene = composer.compose(request(template, style, canvas, photos.take(template.photoCount)))
            val overlay = scene.overlays.single()
            val box = captionBox(overlay)
            val cells = scene.layers.map { it.dst }
            val label = "$template gutter=$gutter $canvas"
            assertEquals(label, "20mm · ISO 100", overlay.text)
            if (template == StoryTemplate.HERO_GALLERY_MATTE) {
                assertTrue("$label: under the photo", box.top > cells[0].bottom && box.bottom < scene.canvasHeight)
            } else {
                val a = cells[0]
                assertTrue("$label: $box inside the anchor $a", box.left >= a.left && box.right <= a.right && box.top >= a.top && box.bottom <= a.bottom)
            }
            cells.drop(1).forEachIndexed { i, other ->
                val overlaps = box.left < other.right && box.right > other.left && box.top < other.bottom && box.bottom > other.top
                assertTrue("$label: caption overlaps slot ${i + 1}", !overlaps)
            }
        }
    }

    @Test
    fun caption_isAbsentWhenDisabledOrUnknown() {
        assertTrue(composer.compose(request(StoryTemplate.HERO_FULL_BLEED)).overlays.isEmpty())
        val unknown = request(
            StoryTemplate.HERO_FULL_BLEED,
            LayoutStyle(showExifCaption = true),
            photos = listOf(photo(0, metadata = CaptureMetadata.EMPTY)),
        )
        assertTrue(composer.compose(unknown).overlays.isEmpty())
    }

    /**
     * A generous bound on where the rasterizer can put ink for [overlay]: the run at most `maxWidthPx` wide, font
     * extents of 1.2 em above and 0.35 em below the baseline, plus the halo (about 0.22 em + 1.5 px).
     */
    private fun captionBox(overlay: TextOverlay): Rect4f {
        val halo = if (overlay.haloArgb ushr 24 != 0) 0.25f * overlay.sizePx + 2f else 0f
        val half = overlay.maxWidthPx / 2f + halo
        return Rect4f(
            overlay.anchorX - half,
            overlay.baselineY - 1.2f * overlay.sizePx - halo,
            overlay.anchorX + half,
            overlay.baselineY + 0.35f * overlay.sizePx + halo,
        )
    }

    @Test
    fun hitTest_returnsTheSlotUnderThePoint_orNull() {
        val req = request(StoryTemplate.QUAD_GRID, LayoutStyle(gutterPx = 48f))
        assertEquals(0, composer.hitTest(req, 200f, 300f))
        assertEquals(1, composer.hitTest(req, 900f, 300f))
        assertEquals(2, composer.hitTest(req, 200f, 1500f))
        assertEquals(3, composer.hitTest(req, 900f, 1500f))
        assertNull("gutter", composer.hitTest(req, 540f, 300f))
        assertNull("outer margin", composer.hitTest(req, 10f, 10f))
        assertNull("outside canvas", composer.hitTest(req, -5f, 5000f))
        assertNull("non-finite", composer.hitTest(req, Float.NaN, 300f))

        val strips = request(StoryTemplate.TRIO_CINEMA_STRIPS)
        assertNull("background above the strips", composer.hitTest(strips, 540f, 20f))
        assertEquals(1, composer.hitTest(strips, 540f, 960f))
    }

    @Test
    fun photoCountMismatch_throwsIllegalArgument() {
        val bad = request(StoryTemplate.TRIO_MAGAZINE, photos = listOf(photo(0), photo(1)))
        assertThrows(IllegalArgumentException::class.java) { composer.layout(bad) }
        assertThrows(IllegalArgumentException::class.java) { composer.compose(bad) }
        assertThrows(IllegalArgumentException::class.java) { composer.hitTest(bad, 10f, 10f) }
        val empty = request(StoryTemplate.HERO_FULL_BLEED, photos = listOf(photo(0, width = 0)))
        assertThrows(IllegalArgumentException::class.java) { composer.compose(empty) }
    }
}
