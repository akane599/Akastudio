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
    fun `from API 34 the HDR boost switch is on and enabled only under Ultra HDR`() {
        val a = ExportOptions.availability(34)
        ExportFormat.entries.forEach { format ->
            val sw = ExportOptions.hdrBoostSwitch(a, format, hdrBoost = true, editable = true)
            val ultraHdr = format == ExportFormat.ULTRA_HDR_JPEG
            assertEquals("$format", ultraHdr, sw.checked)
            assertEquals("$format", ultraHdr, sw.enabled)
            assertEquals("$format", if (ultraHdr) HdrBoostNote.APPLIES else HdrBoostNote.ULTRA_HDR_ONLY, sw.note)
        }
    }

    @Test
    fun `below API 34 the HDR boost switch is off and disabled for every format`() {
        val a = ExportOptions.availability(33)
        ExportFormat.entries.forEach { format ->
            val sw = ExportOptions.hdrBoostSwitch(a, format, hdrBoost = true, editable = true)
            assertEquals("$format", HdrBoostSwitchState(false, false, HdrBoostNote.NEEDS_ANDROID_14), sw)
        }
    }

    @Test
    fun `under Ultra HDR the switch follows the flag and locks while exporting`() {
        val a = ExportOptions.availability(34)
        val off = ExportOptions.hdrBoostSwitch(a, ExportFormat.ULTRA_HDR_JPEG, hdrBoost = false, editable = true)
        assertEquals(HdrBoostSwitchState(checked = false, enabled = true, note = HdrBoostNote.APPLIES), off)
        val locked = ExportOptions.hdrBoostSwitch(a, ExportFormat.ULTRA_HDR_JPEG, hdrBoost = true, editable = false)
        assertEquals(HdrBoostSwitchState(checked = true, enabled = false, note = HdrBoostNote.APPLIES), locked)
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
