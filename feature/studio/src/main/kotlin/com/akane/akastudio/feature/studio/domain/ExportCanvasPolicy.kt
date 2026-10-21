package com.akane.akastudio.feature.studio.domain

import com.akane.akastudio.core.composer.api.CanvasSize
import com.akane.akastudio.core.export.api.ExportFormat
import com.akane.akastudio.core.export.api.ExportPlan
import com.akane.akastudio.core.export.api.ExportResolution

/** Use case picking the composer canvas for an export: the [CanvasSize] equal to [ExportPlan.renderSize]. */
internal object ExportCanvasPolicy {
    /** 1080x1920 -> [CanvasSize.STORY_1080], 2160x3840 -> [CanvasSize.STORY_4K]; any other plan is an error. */
    fun canvasFor(format: ExportFormat, resolution: ExportResolution): CanvasSize {
        val (width, height) = ExportPlan.renderSize(format, resolution)
        return CanvasSize.entries.firstOrNull { it.width == width && it.height == height }
            ?: throw IllegalStateException("No composer canvas for a ${width}x$height render ($format, $resolution)")
    }
}
