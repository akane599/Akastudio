package com.akane.akastudio.feature.studio.di

import com.akane.akastudio.core.composer.api.StoryTemplate
import com.akane.akastudio.core.curator.decision.CellShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioLayoutCatalogTest {
    private val catalog = studioLayoutCatalog()

    private fun aspects(template: StoryTemplate): List<CellShape.Aspect> =
        catalog.layouts
            .single { it.id == template.name }
            .cells
            .map { it as CellShape.Aspect }

    @Test
    fun oneLayoutPerTemplate_inDeclarationOrder_withTheTemplatesPhotoCount() {
        assertEquals(StoryTemplate.entries.map { it.name }, catalog.layouts.map { it.id })
        for (template in StoryTemplate.entries) {
            assertEquals(template.photoCount, catalog.layouts.single { it.id == template.name }.photoCount)
            assertEquals(StoryTemplate.forCount(template.photoCount).map { it.name }, catalog.forCount(template.photoCount).map { it.id })
        }
    }

    @Test
    fun galleryMatte_isUncropped() {
        assertEquals(listOf(CellShape.Uncropped), catalog.layouts.single { it.id == StoryTemplate.HERO_GALLERY_MATTE.name }.cells)
    }

    @Test
    fun nominalAspects_matchTheTemplateGeometryAtTheDefaultGutter() {
        val expected =
            mapOf(
                StoryTemplate.HERO_FULL_BLEED to listOf(0.5625f),
                StoryTemplate.DUO_CINEMATIC_SPLIT to listOf(1.121f, 1.121f),
                StoryTemplate.DUO_ASYMMETRIC_EDITORIAL to listOf(0.859f, 1.615f),
                StoryTemplate.DUO_FLOATING_GALLERY to listOf(1.164f, 1.164f),
                StoryTemplate.TRIO_BENTO to listOf(1.017f, 0.617f, 0.617f),
                StoryTemplate.TRIO_CINEMA_STRIPS to listOf(2.35f, 2.35f, 2.35f),
                StoryTemplate.TRIO_MAGAZINE to listOf(0.491f, 0.296f, 1.892f),
                StoryTemplate.QUAD_GRID to List(4) { 0.554f },
                StoryTemplate.QUAD_HERO_FILMSTRIP to listOf(0.796f, 0.613f, 0.624f, 0.613f),
                StoryTemplate.QUAD_MOSAIC to listOf(0.621f, 0.781f, 1.061f, 1.169f),
            )
        for ((template, values) in expected) {
            val actual = aspects(template)
            assertEquals(template.name, values.size, actual.size)
            values.zip(actual).forEach { (want, cell) -> assertEquals(template.name, want, cell.widthOverHeight, 0.0015f) }
        }
    }

    @Test
    fun aspectRanges_spanTheGutterSlider() {
        val quad = aspects(StoryTemplate.QUAD_GRID).first()
        assertEquals(468f / 888f, quad.minWidthOverHeight, 1e-4f) // 48 px gutter
        assertEquals(540f / 960f, quad.maxWidthOverHeight, 1e-4f) // no gutter
        // The narrow magazine cell moves 10% over the slider, more than the curator's 5% margin.
        val magazine = aspects(StoryTemplate.TRIO_MAGAZINE)[1]
        assertTrue("min ${magazine.minWidthOverHeight}", magazine.minWidthOverHeight < 0.296f * 0.95f)
        // Fixed-aspect cells do not move.
        val hero = aspects(StoryTemplate.HERO_FULL_BLEED).single()
        assertEquals(hero.widthOverHeight, hero.minWidthOverHeight, 0f)
        assertEquals(hero.widthOverHeight, hero.maxWidthOverHeight, 0f)
        aspects(StoryTemplate.TRIO_CINEMA_STRIPS).forEach { assertEquals(2.35f, it.minWidthOverHeight, 1e-4f) }
    }
}
