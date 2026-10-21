package com.akane.akastudio.core.export.store

import android.content.Context
import android.os.Build
import com.akane.akastudio.core.color.api.StoryRenderer
import com.akane.akastudio.core.export.api.StoryExporter
import com.akane.akastudio.core.export.pipeline.ExportProcessor

/**
 * The production [StoryExporter]: [renderer] output written to MediaStore `Pictures/AkaStudio`. Ultra HDR is encoded
 * on API 34+; below that ULTRA_HDR_JPEG falls back to SOCIAL_STORY_JPEG.
 */
fun createStoryExporter(context: Context, renderer: StoryRenderer): StoryExporter =
    ExportProcessor(
        renderer = renderer,
        sink = MediaStoreSink(context),
        ultraHdr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) PlatformUltraHdrEncoder() else null,
        webpLossless = PlatformWebpLosslessEncoder(),
    )
