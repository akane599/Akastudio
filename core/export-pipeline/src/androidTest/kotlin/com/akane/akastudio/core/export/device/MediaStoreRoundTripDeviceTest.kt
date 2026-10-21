package com.akane.akastudio.core.export.device

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.akane.akastudio.core.export.api.ExportFormat
import com.akane.akastudio.core.export.api.ExportResolution
import com.akane.akastudio.core.export.pipeline.ExportProcessor
import com.akane.akastudio.core.export.store.MediaStoreSink
import com.akane.akastudio.core.export.store.PlatformUltraHdrEncoder
import com.akane.akastudio.core.export.store.PlatformWebpLosslessEncoder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every export format through the production [MediaStoreSink] (insert pending, stream, publish). The sink writes to
 * `Pictures/AkaStudio/`; each published row is then moved into `Pictures/AkaStudioQA/<run-id>/` and deleted after the
 * test.
 */
@RunWith(AndroidJUnit4::class)
class MediaStoreRoundTripDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val qa = MediaStoreQa(context.contentResolver)

    @After
    fun deleteRows() {
        assertEquals("rows left behind in ${qa.relativePath}", 0, qa.cleanUp())
    }

    @Test
    fun everyFormatIsPublishedWithItsMimeTypeAndDimensions() {
        val ultraHdr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) PlatformUltraHdrEncoder() else null
        val exporter = ExportProcessor(SyntheticRenderer(gradient(peak = 4f)), MediaStoreSink(context), ultraHdr, PlatformWebpLosslessEncoder())
        record("mediastore.runFolder", qa.relativePath)
        for (format in ExportFormat.entries) {
            val result = exporter.exportBlocking(exportRequest(format, ExportResolution.HD_1080))
            val uri = Uri.parse(result.uri)
            qa.track(uri)
            val expectedFormat = if (format == ExportFormat.ULTRA_HDR_JPEG && ultraHdr == null) ExportFormat.SOCIAL_STORY_JPEG else format
            val mime = MIME.getValue(expectedFormat)
            assertEquals(expectedFormat, result.format)
            assertEquals(mime, result.mimeType)

            val published = checkNotNull(qa.row(uri)) { "$format: no MediaStore row for $uri" }
            record("mediastore.$format", published)
            assertEquals("$format MIME_TYPE", mime, published.mimeType)
            assertEquals("$format IS_PENDING", 0, published.isPending)
            assertEquals("$format RELATIVE_PATH", "Pictures/AkaStudio/", published.relativePath)
            assertTrue("$format DISPLAY_NAME ${published.displayName}", published.displayName.endsWith("_${expectedFormat.name}.${EXTENSION.getValue(expectedFormat)}"))
            assertEquals("$format SIZE", result.byteSize, published.size)
            assertEquals("$format WIDTH", 1080, published.width)
            assertEquals("$format HEIGHT", 1920, published.height)
            assertEquals("$format DATE_TAKEN", CAPTURE_MILLIS, published.dateTaken)

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
            assertEquals("$format decoded MIME", mime, bounds.outMimeType)
            assertEquals("$format decoded width", 1080, bounds.outWidth)
            assertEquals("$format decoded height", 1920, bounds.outHeight)

            assertEquals("$format move into the QA folder", 1, qa.moveIntoRunFolder(uri))
            val moved = checkNotNull(qa.row(uri))
            assertEquals(qa.relativePath, moved.relativePath)
            assertEquals(0, moved.isPending)
        }
        assertEquals(ExportFormat.entries.size, qa.rowsInRunFolder().size)
    }

    private companion object {
        val MIME = mapOf(
            ExportFormat.PNG_16 to "image/png",
            ExportFormat.PNG_8 to "image/png",
            ExportFormat.WEBP_LOSSLESS to "image/webp",
            ExportFormat.SOCIAL_STORY_JPEG to "image/jpeg",
            ExportFormat.ULTRA_HDR_JPEG to "image/jpeg",
        )
        val EXTENSION = mapOf(
            ExportFormat.PNG_16 to "png",
            ExportFormat.PNG_8 to "png",
            ExportFormat.WEBP_LOSSLESS to "webp",
            ExportFormat.SOCIAL_STORY_JPEG to "jpg",
            ExportFormat.ULTRA_HDR_JPEG to "jpg",
        )
    }
}
