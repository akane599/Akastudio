package com.akane.akastudio.feature.studio.ui.controls

import com.akane.akastudio.core.composer.api.StoryTemplate
import com.akane.akastudio.core.export.api.ExportFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportOptionsTest {

    @Test
    fun `below API 34 Ultra HDR and the HDR boost are unavailable, every other format is`() {
        val a = ExportOptions.availability(sdkInt = 33)
        assertFalse(a.isEnabled(ExportFormat.ULTRA_HDR_JPEG))
        assertFalse(a.hdrBoostAvailable)
        listOf(ExportFormat.PNG_16, ExportFormat.PNG_8, ExportFormat.WEBP_LOSSLESS, ExportFormat.SOCIAL_STORY_JPEG)
            .forEach { assertTrue("$it", a.isEnabled(it)) }
    }

    @Test
    fun `from API 34 every format and the HDR boost are available`() {
        listOf(34, 35, 37).forEach { sdk ->
            val a = ExportOptions.availability(sdk)
            assertTrue(ExportFormat.entries.all(a::isEnabled))
            assertTrue(a.hdrBoostAvailable)
        }
    }

    @Test
    fun `the minimum supported API 31 still offers the lossless and social formats`() {
        val a = ExportOptions.availability(31)
        assertEquals(4, a.formats.count { it.enabled })
    }

    @Test
    fun `formats are listed in declaration order`() {
        assertEquals(ExportFormat.entries.toList(), ExportOptions.availability(34).formats.map { it.format })
    }

    @Test
    fun `every template wireframe has one cell per photo, inside the canvas`() {
        StoryTemplate.entries.forEach { template ->
            val cells = TemplateWireframes.cells(template)
            assertEquals("$template", template.photoCount, cells.size)
            cells.forEach { r ->
                assertTrue("$template $r", r.left >= 0f && r.top >= 0f && r.right <= 1f && r.bottom <= 1f)
                assertTrue("$template $r", r.right > r.left && r.bottom > r.top)
            }
        }
    }
}
