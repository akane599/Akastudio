package com.akane.akastudio.feature.studio.domain

import com.akane.akastudio.core.composer.api.CanvasSize
import com.akane.akastudio.core.export.api.ExportFormat
import com.akane.akastudio.core.export.api.ExportResolution
import com.akane.akastudio.core.ingestion.api.IngestionException
import com.akane.akastudio.core.ingestion.model.IngestionError
import org.junit.Assert.assertEquals
import org.junit.Test

class StudioDomainPoliciesTest {

    @Test
    fun social_story_at_1080_composes_at_4k_for_the_2x_render() {
        assertEquals(CanvasSize.STORY_4K, ExportCanvasPolicy.canvasFor(ExportFormat.SOCIAL_STORY_JPEG, ExportResolution.HD_1080))
    }

    @Test
    fun other_formats_compose_at_the_target_resolution() {
        assertEquals(CanvasSize.STORY_1080, ExportCanvasPolicy.canvasFor(ExportFormat.PNG_16, ExportResolution.HD_1080))
        assertEquals(CanvasSize.STORY_4K, ExportCanvasPolicy.canvasFor(ExportFormat.WEBP_LOSSLESS, ExportResolution.UHD_4K))
        assertEquals(CanvasSize.STORY_4K, ExportCanvasPolicy.canvasFor(ExportFormat.SOCIAL_STORY_JPEG, ExportResolution.UHD_4K))
    }

    @Test
    fun default_mapper_maps_ingestion_exceptions() {
        val error = IngestionError.InsufficientStorage(requiredBytes = 10, availableBytes = 1)
        assertEquals(StudioError.Ingestion(error), DefaultStudioErrorMapper().map(IngestionException(error)))
    }

    @Test
    fun default_mapper_falls_back_to_unknown_with_message_or_class_name() {
        val mapper = DefaultStudioErrorMapper()
        assertEquals(StudioError.Unknown("boom"), mapper.map(RuntimeException("boom")))
        assertEquals(StudioError.Unknown("IllegalStateException"), mapper.map(IllegalStateException()))
    }

    @Test
    fun default_mapper_is_extensible() {
        class NoUsable(val rejected: Int) : RuntimeException()
        val mapper = object : DefaultStudioErrorMapper() {
            override fun map(t: Throwable): StudioError =
                if (t is NoUsable) StudioError.NoUsablePhotos(t.rejected) else super.map(t)
        }
        assertEquals(StudioError.NoUsablePhotos(3), mapper.map(NoUsable(3)))
        assertEquals(StudioError.Unknown("x"), mapper.map(RuntimeException("x")))
    }
}
