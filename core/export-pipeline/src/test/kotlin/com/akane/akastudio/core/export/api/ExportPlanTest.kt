package com.akane.akastudio.core.export.api

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportPlanTest {

    @Test
    fun socialStoryJpegAtHd_rendersAtDoubleSize() {
        assertEquals(2160 to 3840, ExportPlan.renderSize(ExportFormat.SOCIAL_STORY_JPEG, ExportResolution.HD_1080))
    }

    @Test
    fun socialStoryJpegAt4k_rendersAtNativeSize() {
        assertEquals(2160 to 3840, ExportPlan.renderSize(ExportFormat.SOCIAL_STORY_JPEG, ExportResolution.UHD_4K))
    }

    @Test
    fun everyOtherFormat_rendersAtResolutionSize() {
        for (format in ExportFormat.entries.filter { it != ExportFormat.SOCIAL_STORY_JPEG }) {
            for (resolution in ExportResolution.entries) {
                assertEquals(
                    "$format at $resolution",
                    resolution.width to resolution.height,
                    ExportPlan.renderSize(format, resolution),
                )
            }
        }
    }
}
