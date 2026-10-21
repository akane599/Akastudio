package com.akane.akastudio.core.composer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akane.akastudio.core.composer.api.CanvasSize
import com.akane.akastudio.core.composer.api.ComposerPhoto
import com.akane.akastudio.core.composer.api.CompositionRequest
import com.akane.akastudio.core.composer.api.LayoutStyle
import com.akane.akastudio.core.composer.api.StoryTemplate
import com.akane.akastudio.core.composer.compose.DefaultCollageComposer
import com.akane.akastudio.core.curator.api.SaliencyMap
import com.akane.akastudio.core.ingestion.model.CaptureMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** On-device smoke: every story template lays its photos out in slot order inside the 9:16 canvas, and hit tests agree. */
@RunWith(AndroidJUnit4::class)
class ComposerDeviceSmokeTest {

    private fun photo(index: Int) = ComposerPhoto(
        id = "id-$index",
        imageKey = "key-$index",
        widthPx = 1600,
        heightPx = 1200,
        saliency = SaliencyMap(64, 48, FloatArray(64 * 48) { 1f }),
        faces = emptyList(),
        palette = emptyList(),
        horizonTiltDegrees = 0f,
        metadata = CaptureMetadata.EMPTY,
    )

    @Test
    fun everyTemplateLaysOutInsideTheCanvas() {
        val composer = DefaultCollageComposer()
        val canvas = CanvasSize.STORY_1080
        for (template in StoryTemplate.entries) {
            val photos = List(template.photoCount) { photo(it) }
            val request = CompositionRequest(template, LayoutStyle(), canvas, photos, grades = emptyMap())

            val placements = composer.layout(request)

            assertEquals("$template ids", photos.map { it.id }, placements.map { it.photoId })
            placements.forEachIndexed { slot, cell ->
                val dst = cell.dst
                assertTrue("$template ${cell.photoId} has area: $dst", dst.width > 0f && dst.height > 0f)
                assertTrue(
                    "$template ${cell.photoId} inside canvas: $dst",
                    dst.left >= 0f && dst.top >= 0f && dst.right <= canvas.width && dst.bottom <= canvas.height,
                )
                val centreX = (dst.left + dst.right) / 2f
                val centreY = (dst.top + dst.bottom) / 2f
                assertEquals("$template hit test at $slot centre", slot, composer.hitTest(request, centreX, centreY))
            }
        }
    }
}
