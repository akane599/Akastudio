package com.akane.akastudio.core.export.device

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akane.akastudio.core.export.api.ExportFormat
import com.akane.akastudio.core.export.api.ExportResolution
import com.akane.akastudio.core.export.api.ExportResult
import com.akane.akastudio.core.export.pipeline.ExportProcessor
import com.akane.akastudio.core.export.store.PlatformUltraHdrEncoder
import com.akane.akastudio.core.export.store.PlatformWebpLosslessEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.max

/** ULTRA_HDR_JPEG through the platform gain-map encoder, and its no-headroom fallback to the Social Story JPEG. */
@RunWith(AndroidJUnit4::class)
class UltraHdrDeviceTest {

    @Before
    fun needsGainmapApi() {
        assumeTrue("Ultra HDR export needs API 34+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    }

    @Test
    fun headroomExportDecodesWithAGainmap() {
        val (result, jpeg) = export(gradient(peak = 4f), ExportFormat.ULTRA_HDR_JPEG, ExportResolution.HD_1080)
        assertEquals(ExportFormat.ULTRA_HDR_JPEG, result.format)
        assertEquals("image/jpeg", result.mimeType)
        assertEquals(0xFF, jpeg[0].toInt() and 0xFF)
        assertEquals(0xD8, jpeg[1].toInt() and 0xFF)

        val viaFactory = checkNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
        val viaDecoder = decodeWithImageDecoder(jpeg)
        for ((name, bitmap) in listOf("BitmapFactory" to viaFactory, "ImageDecoder" to viaDecoder)) {
            assertEquals(name, 1080, bitmap.width)
            assertEquals(name, 1920, bitmap.height)
            assertTrue("$name: no gain map in our Ultra HDR export", bitmap.hasGainmap())
            val gainmap = checkNotNull(bitmap.gainmap)
            record("ultraHdr.$name.gainmap", "${gainmap.gainmapContents.width}x${gainmap.gainmapContents.height} " +
                "ratioMax=${gainmap.ratioMax.toList()} displayRatioForFullHdr=${gainmap.displayRatioForFullHdr}")
            assertTrue("$name gain map ratioMax ${gainmap.ratioMax.toList()}", gainmap.ratioMax[0] > 1f)
            bitmap.recycle()
        }
        record("ultraHdr.bytes", jpeg.size)
    }

    @Test
    fun zeroHeadroomFallsBackToPlainSocialJpeg() {
        val (result, jpeg) = export(gradient(peak = 1f), ExportFormat.ULTRA_HDR_JPEG, ExportResolution.HD_1080)
        assertEquals("the reported format must name the fallback", ExportFormat.SOCIAL_STORY_JPEG, result.format)
        assertEquals("image/jpeg", result.mimeType)
        assertTrue(result.uri, result.uri.endsWith("_SOCIAL_STORY_JPEG.jpg"))
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
        assertEquals(1080, bitmap.width)
        assertEquals(1920, bitmap.height)
        assertFalse("the fallback must be a plain JPEG", bitmap.hasGainmap())
        bitmap.recycle()
    }

    /**
     * SQ-43 saw the fallback differ from an explicit Social export (632,123 B vs 730,884 B at HD). The pipeline is the
     * same once the render size is the same: at UHD_4K both render 2160x3840 and the files are byte-identical. At HD the
     * explicit Social export renders 2x (2160x3840) and Lanczos-downsamples (ExportPlan), while the fallback renders at
     * the 1080x1920 target, so the inputs to the identical encode differ. Neither EXIF nor a timestamp is involved:
     * EXIF carries only the capture time, and the wall clock only names the file.
     */
    @Test
    fun fallbackMatchesExplicitSocialExportForTheSameRender() {
        val paint = gradient(peak = 1f)
        val (fallback4k, fallback4kBytes) = export(paint, ExportFormat.ULTRA_HDR_JPEG, ExportResolution.UHD_4K)
        assertEquals(ExportFormat.SOCIAL_STORY_JPEG, fallback4k.format)
        val (_, social4kBytes) = export(paint, ExportFormat.SOCIAL_STORY_JPEG, ExportResolution.UHD_4K)
        record("fallback.uhd.bytes", "fallback=${fallback4kBytes.size} social=${social4kBytes.size}")
        if (!fallback4kBytes.contentEquals(social4kBytes)) {
            throw AssertionError("4K fallback differs from the explicit Social export: ${pixelDiff(fallback4kBytes, social4kBytes)}")
        }

        val fallbackRenderer = SyntheticRenderer(paint)
        val (_, fallbackHd) = export(fallbackRenderer, ExportFormat.ULTRA_HDR_JPEG, ExportResolution.HD_1080)
        val socialRenderer = SyntheticRenderer(paint)
        val (_, socialHd) = export(socialRenderer, ExportFormat.SOCIAL_STORY_JPEG, ExportResolution.HD_1080)
        assertEquals(listOf(1080 to 1920), fallbackRenderer.canvases)
        assertEquals(listOf(2160 to 3840), socialRenderer.canvases)
        record("fallback.hd.bytes", "fallback=${fallbackHd.size} social=${socialHd.size} ${pixelDiff(fallbackHd, socialHd)}")
    }

    private fun export(paint: Paint, format: ExportFormat, resolution: ExportResolution) =
        export(SyntheticRenderer(paint), format, resolution)

    private fun export(renderer: SyntheticRenderer, format: ExportFormat, resolution: ExportResolution): Pair<ExportResult, ByteArray> {
        val sink = MemorySink()
        val exporter = ExportProcessor(renderer, sink, PlatformUltraHdrEncoder(), PlatformWebpLosslessEncoder())
        val result = exporter.exportBlocking(exportRequest(format, resolution))
        val file = sink.files.single()
        assertEquals(file.bytes.size.toLong(), result.byteSize)
        return result to file.bytes
    }

    /** Max and mean absolute per-channel difference of two same-size JPEGs' decoded pixels. */
    private fun pixelDiff(a: ByteArray, b: ByteArray): String {
        val first = checkNotNull(BitmapFactory.decodeByteArray(a, 0, a.size))
        val second = checkNotNull(BitmapFactory.decodeByteArray(b, 0, b.size))
        if (first.width != second.width || first.height != second.height) {
            return "sizes ${first.width}x${first.height} vs ${second.width}x${second.height}"
        }
        val p = first.argbPixels()
        val q = second.argbPixels()
        var maxDiff = 0
        var sum = 0L
        for (i in p.indices) {
            for (shift in intArrayOf(16, 8, 0)) {
                val d = abs((p[i] shr shift and 0xFF) - (q[i] shr shift and 0xFF))
                maxDiff = max(maxDiff, d)
                sum += d
            }
        }
        listOf<Bitmap>(first, second).forEach(Bitmap::recycle)
        return "maxAbsDiff=$maxDiff meanAbsDiff=${"%.3f".format(sum.toDouble() / (3.0 * p.size))} (of 255)"
    }
}
