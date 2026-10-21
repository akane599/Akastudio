package com.akane.akastudio.core.composer.layout

import com.akane.akastudio.core.color.api.Rect4f
import com.akane.akastudio.core.composer.api.CanvasSize
import com.akane.akastudio.core.composer.api.LayoutStyle
import com.akane.akastudio.core.composer.api.StoryTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class TemplateGeometryTest {

    private fun aspects(template: StoryTemplate) = List(template.photoCount) { 1.5f }

    private fun cells(template: StoryTemplate, gutter: Float, canvas: CanvasSize = CanvasSize.STORY_1080) =
        TemplateGeometry.cells(template, canvas, LayoutStyle(gutterPx = gutter), aspects(template))

    @Test
    fun everyTemplate_hasOneCellPerPhoto_insideTheCanvas_withoutOverlap() {
        for (canvas in CanvasSize.entries) {
            for (gutter in listOf(0f, 12f, 48f)) {
                for (template in StoryTemplate.entries) {
                    val cells = cells(template, gutter, canvas)
                    val label = "$template g=$gutter $canvas"
                    assertEquals(label, template.photoCount, cells.size)
                    for (c in cells) {
                        assertTrue("$label inside: $c", c.left >= 0f && c.top >= 0f)
                        assertTrue("$label inside: $c", c.right <= canvas.width && c.bottom <= canvas.height)
                        assertTrue("$label non-empty: $c", c.width > 0f && c.height > 0f)
                    }
                    for (i in cells.indices) for (j in i + 1 until cells.size) {
                        val ox = min(cells[i].right, cells[j].right) - max(cells[i].left, cells[j].left)
                        val oy = min(cells[i].bottom, cells[j].bottom) - max(cells[i].top, cells[j].top)
                        assertTrue("$label cells $i/$j overlap ($ox x $oy)", ox <= 0.5f || oy <= 0.5f)
                    }
                }
            }
        }
    }

    /** Spec fractions (left, top, right, bottom) of the 1080×1920 base before gutter insets. */
    private val fractions: Map<StoryTemplate, List<DoubleArray>> = mapOf(
        StoryTemplate.DUO_CINEMATIC_SPLIT to listOf(d(0, 0, 1, .5), d(0, .5, 1, 1)),
        StoryTemplate.DUO_ASYMMETRIC_EDITORIAL to listOf(d(0, 0, 1, .65), d(0, .65, 1, 1)),
        StoryTemplate.TRIO_BENTO to listOf(d(0, 0, 1, .55), d(0, .55, .5, 1), d(.5, .55, 1, 1)),
        StoryTemplate.TRIO_MAGAZINE to listOf(d(0, 0, .62, .70), d(.62, 0, 1, .70), d(0, .70, 1, 1)),
        StoryTemplate.QUAD_GRID to listOf(d(0, 0, .5, .5), d(.5, 0, 1, .5), d(0, .5, .5, 1), d(.5, .5, 1, 1)),
        StoryTemplate.QUAD_HERO_FILMSTRIP to listOf(
            d(0, 0, 1, .70), d(0, .70, 1.0 / 3, 1), d(1.0 / 3, .70, 2.0 / 3, 1), d(2.0 / 3, .70, 1, 1),
        ),
        StoryTemplate.QUAD_MOSAIC to listOf(d(0, 0, .58, .52), d(.58, 0, 1, .30), d(.58, .30, 1, .52), d(0, .52, 1, 1)),
    )

    private fun d(vararg v: Number) = DoubleArray(v.size) { v[it].toDouble() }

    @Test
    fun fractionTemplates_insetOuterEdgesByGutterAndSharedEdgesByHalf_exactlyAtZeroAnd48() {
        for (g in listOf(0.0, 48.0)) {
            for ((template, fracs) in fractions) {
                val actual = cells(template, g.toFloat())
                fracs.forEachIndexed { i, f ->
                    val expected = Rect4f(
                        (f[0] * 1080 + if (f[0] == 0.0) g else g / 2).toFloat(),
                        (f[1] * 1920 + if (f[1] == 0.0) g else g / 2).toFloat(),
                        (f[2] * 1080 - if (f[2] == 1.0) g else g / 2).toFloat(),
                        (f[3] * 1920 - if (f[3] == 1.0) g else g / 2).toFloat(),
                    )
                    assertEquals("$template cell $i g=$g", expected, actual[i])
                }
            }
        }
    }

    @Test
    fun gutterMath_handComputedValues() {
        assertEquals(
            listOf(Rect4f(48f, 48f, 516f, 936f), Rect4f(564f, 48f, 1032f, 936f), Rect4f(48f, 984f, 516f, 1872f), Rect4f(564f, 984f, 1032f, 1872f)),
            cells(StoryTemplate.QUAD_GRID, 48f),
        )
        val film = cells(StoryTemplate.QUAD_HERO_FILMSTRIP, 48f)
        assertEquals(Rect4f(48f, 48f, 1032f, 1320f), film[0])
        assertEquals(Rect4f(48f, 1368f, 336f, 1872f), film[1])
        assertEquals(Rect4f(384f, 1368f, 696f, 1872f), film[2])
        assertEquals(Rect4f(744f, 1368f, 1032f, 1872f), film[3])
        assertEquals(listOf(Rect4f(0f, 0f, 1080f, 960f), Rect4f(0f, 960f, 1080f, 1920f)), cells(StoryTemplate.DUO_CINEMATIC_SPLIT, 0f))
    }

    @Test
    fun heroFullBleed_ignoresGutter() {
        assertEquals(listOf(Rect4f(0f, 0f, 1080f, 1920f)), cells(StoryTemplate.HERO_FULL_BLEED, 48f))
        assertEquals(listOf(Rect4f(0f, 0f, 2160f, 3840f)), cells(StoryTemplate.HERO_FULL_BLEED, 48f, CanvasSize.STORY_4K))
    }

    @Test
    fun floatingGallery_gutterWidensOnlyTheMiddleGap() {
        for (g in listOf(0.0, 48.0)) {
            val c = cells(StoryTemplate.DUO_FLOATING_GALLERY, g.toFloat())
            assertEquals(Rect4f((.0667 * 1080).toFloat(), (.0625 * 1920).toFloat(), (.9333 * 1080).toFloat(), (.4844 * 1920 - g / 2).toFloat()), c[0])
            assertEquals(Rect4f((.0667 * 1080).toFloat(), (.5156 * 1920 + g / 2).toFloat(), (.9333 * 1080).toFloat(), (.9375 * 1920).toFloat()), c[1])
        }
    }

    @Test
    fun cinemaStrips_areCentred235StripsSeparatedByTheGutter() {
        for (g in listOf(0f, 48f)) {
            val c = cells(StoryTemplate.TRIO_CINEMA_STRIPS, g)
            val width = 1080.0 - 2 * g
            val height = width / 2.35
            val firstTop = (1920 - (3 * height + 2 * g)) / 2
            c.forEachIndexed { i, cell ->
                val top = firstTop + i * (height + g)
                assertEquals(Rect4f(g, top.toFloat(), (g + width).toFloat(), (top + height).toFloat()), cell)
                assertEquals(2.35f, cell.width / cell.height, 1e-3f)
            }
            assertEquals("vertically centred", c[0].top, 1920f - c[2].bottom, 1e-3f)
        }
    }

    @Test
    fun galleryMatte_usesPhotoAspect_shrinksToMaxHeight_andIgnoresGutter() {
        val style = LayoutStyle(gutterPx = 48f)
        val landscape = TemplateGeometry.cells(StoryTemplate.HERO_GALLERY_MATTE, CanvasSize.STORY_1080, style, listOf(4f / 3f))[0]
        assertEquals(96f, landscape.left, 1e-3f)
        assertEquals(984f, landscape.right, 1e-3f)
        assertEquals(666f, landscape.height, 1e-2f)
        assertEquals((1920f - 666f) / 2f - 60f, landscape.top, 1e-2f)

        val tall = TemplateGeometry.cells(StoryTemplate.HERO_GALLERY_MATTE, CanvasSize.STORY_1080, style, listOf(9f / 16f))[0]
        assertEquals(0.72f * 1920f, tall.height, 1e-2f)
        assertEquals(0.72f * 1920f * 9f / 16f, tall.width, 1e-2f)
        assertEquals(540f, (tall.left + tall.right) / 2f, 1e-3f)
        assertEquals((1920f - tall.height) / 2f - 60f, tall.top, 1e-2f)
    }

    @Test
    fun fourK_isExactlyTwiceThe1080Geometry() {
        for (g in listOf(0f, 12f, 33.3f, 48f)) {
            for (template in StoryTemplate.entries) {
                val base = cells(template, g)
                val big = cells(template, g, CanvasSize.STORY_4K)
                base.zip(big).forEach { (b, k) ->
                    assertEquals("$template g=$g", Rect4f(b.left * 2, b.top * 2, b.right * 2, b.bottom * 2), k)
                }
            }
        }
    }

    @Test
    fun gutterIsClampedTo0Through48() {
        assertEquals(cells(StoryTemplate.QUAD_GRID, 48f), cells(StoryTemplate.QUAD_GRID, 500f))
        assertEquals(cells(StoryTemplate.QUAD_GRID, 0f), cells(StoryTemplate.QUAD_GRID, -5f))
        assertEquals(cells(StoryTemplate.QUAD_GRID, 0f), cells(StoryTemplate.QUAD_GRID, Float.NaN))
    }

    @Test
    fun cornerRadius_scalesAndCapsTheGalleryHero() {
        val style = LayoutStyle(cornerRadiusPx = 40f)
        assertEquals(0f, TemplateGeometry.cornerRadius(StoryTemplate.HERO_FULL_BLEED, CanvasSize.STORY_1080, style))
        assertEquals(24f, TemplateGeometry.cornerRadius(StoryTemplate.HERO_GALLERY_MATTE, CanvasSize.STORY_1080, style))
        assertEquals(48f, TemplateGeometry.cornerRadius(StoryTemplate.HERO_GALLERY_MATTE, CanvasSize.STORY_4K, style))
        assertEquals(40f, TemplateGeometry.cornerRadius(StoryTemplate.TRIO_BENTO, CanvasSize.STORY_1080, style))
        assertEquals(80f, TemplateGeometry.cornerRadius(StoryTemplate.TRIO_BENTO, CanvasSize.STORY_4K, style))
        assertEquals(10f, TemplateGeometry.cornerRadius(StoryTemplate.HERO_GALLERY_MATTE, CanvasSize.STORY_1080, LayoutStyle(cornerRadiusPx = 10f)))
    }

    @Test
    fun wrongAspectCount_orInvalidGalleryAspect_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            TemplateGeometry.cells(StoryTemplate.QUAD_GRID, CanvasSize.STORY_1080, LayoutStyle(), listOf(1f, 1f))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TemplateGeometry.cells(StoryTemplate.HERO_GALLERY_MATTE, CanvasSize.STORY_1080, LayoutStyle(), listOf(0f))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TemplateGeometry.cells(StoryTemplate.HERO_GALLERY_MATTE, CanvasSize.STORY_1080, LayoutStyle(), listOf(Float.NaN))
        }
    }
}
