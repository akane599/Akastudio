package com.akane.akastudio.core.composer.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryTemplateTest {

    @Test
    fun forCount_returnsMatchingTemplatesInDeclarationOrder() {
        assertEquals(
            listOf(StoryTemplate.HERO_FULL_BLEED, StoryTemplate.HERO_GALLERY_MATTE),
            StoryTemplate.forCount(1),
        )
        assertEquals(
            listOf(
                StoryTemplate.DUO_CINEMATIC_SPLIT,
                StoryTemplate.DUO_ASYMMETRIC_EDITORIAL,
                StoryTemplate.DUO_FLOATING_GALLERY,
            ),
            StoryTemplate.forCount(2),
        )
        assertEquals(
            listOf(StoryTemplate.TRIO_BENTO, StoryTemplate.TRIO_CINEMA_STRIPS, StoryTemplate.TRIO_MAGAZINE),
            StoryTemplate.forCount(3),
        )
        assertEquals(
            listOf(StoryTemplate.QUAD_GRID, StoryTemplate.QUAD_HERO_FILMSTRIP, StoryTemplate.QUAD_MOSAIC),
            StoryTemplate.forCount(4),
        )
    }

    @Test
    fun forCount_outOfRangeIsEmpty() {
        assertTrue(StoryTemplate.forCount(0).isEmpty())
        assertTrue(StoryTemplate.forCount(5).isEmpty())
    }
}
