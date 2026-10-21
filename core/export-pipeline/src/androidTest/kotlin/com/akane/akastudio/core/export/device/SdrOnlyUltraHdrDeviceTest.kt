package com.akane.akastudio.core.export.device

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Shader
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akane.akastudio.core.color.api.GradeParams
import com.akane.akastudio.core.color.api.MasterLook
import com.akane.akastudio.core.color.grade.CpuGradingPipeline
import com.akane.akastudio.core.color.grade.LinearImage
import com.akane.akastudio.core.export.api.ExportFormat
import com.akane.akastudio.core.export.api.ExportResolution
import com.akane.akastudio.core.export.api.ExportResult
import com.akane.akastudio.core.export.pipeline.ExportProcessor
import com.akane.akastudio.core.export.process.GainMapMath
import com.akane.akastudio.core.export.process.Half
import com.akane.akastudio.core.export.store.PlatformUltraHdrEncoder
import com.akane.akastudio.core.export.store.PlatformWebpLosslessEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.graphics.Paint as AndroidPaint

/**
 * SQ-66: an SDR-only story (a JPEG with no gain map, decoded the way the app decodes, then graded with a look that
 * nudges highlights past SDR white) asked for ULTRA_HDR_JPEG must come out as the plain 4:4:4 Social Story JPEG, not
 * an Ultra HDR file with a near-empty gain map (SQ-52: ratioMax ~1.05).
 */
@RunWith(AndroidJUnit4::class)
class SdrOnlyUltraHdrDeviceTest {

    @Before
    fun needsGainmapApi() {
        assumeTrue("Ultra HDR export needs API 34+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    }

    @Test
    fun sdrOnlyStoryWithUltraHdrRequestExportsSocialJpeg() {
        val sdr = decodeLikeTheApp(sdrPhotoJpeg())
        val graded = CpuGradingPipeline.grade(sdr, GradeParams(look = MasterLook.MEDITERRANEAN_AZURE, contrast = 0.5f))
        val renderer = SyntheticRenderer(sample(graded))
        val ratioMax = GainMapMath.compute(renderer.render(1080, 1920)).ratioMax
        record("sdrOnly.ratioMax", ratioMax)
        assertTrue(
            "the graded SDR render must overshoot white a little, like SQ-52's (ratioMax $ratioMax)",
            ratioMax > 1.02f && ratioMax < ExportProcessor.MIN_HDR_RATIO,
        )

        val (result, jpeg) = exportUltraHdr(renderer)
        assertEquals("the reported format must name the fallback", ExportFormat.SOCIAL_STORY_JPEG, result.format)
        assertTrue(result.uri, result.uri.endsWith("_SOCIAL_STORY_JPEG.jpg"))
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
        assertFalse("an SDR-only story must not carry a gain map", bitmap.hasGainmap())
        assertEquals(1080 to 1920, bitmap.width to bitmap.height)
        bitmap.recycle()
        assertEquals("the Social fallback keeps full chroma", "4:4:4", firstFrameSampling(jpeg))
    }

    /**
     * The platform `Bitmap.compress(JPEG)` with a Gainmap encodes the base 4:2:0 and offers no option to change it
     * (PlatformUltraHdrEncoder). Pinned here so the documented limitation is re-checked on every device run.
     */
    @Test
    fun platformUltraHdrBaseIsChromaSubsampled() {
        val (result, jpeg) = exportUltraHdr(SyntheticRenderer(gradient(peak = 4f)))
        assertEquals(ExportFormat.ULTRA_HDR_JPEG, result.format)
        val sampling = firstFrameSampling(jpeg)
        record("ultraHdr.baseSampling", sampling)
        assertEquals("platform Ultra HDR base sampling changed; update PlatformUltraHdrEncoder's KDoc", "4:2:0", sampling)
    }

    private fun exportUltraHdr(renderer: SyntheticRenderer): Pair<ExportResult, ByteArray> {
        val sink = MemorySink()
        val exporter = ExportProcessor(renderer, sink, PlatformUltraHdrEncoder(), PlatformWebpLosslessEncoder())
        val result = exporter.exportBlocking(exportRequest(ExportFormat.ULTRA_HDR_JPEG, ExportResolution.HD_1080))
        return result to sink.files.single().bytes
    }

    /** A plain 8-bit JPEG photo: a sky fading to white, saturated patches and a grey ramp up to white. */
    private fun sdrPhotoJpeg(): ByteArray {
        val w = 270
        val h = 480
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = AndroidPaint()
        paint.shader = LinearGradient(0f, 0f, 0f, h / 3f, 0xFFFFFFFF.toInt(), 0xFF6FA8DC.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w.toFloat(), h / 3f, paint)
        paint.shader = null
        val patches = intArrayOf(0xFFE53935.toInt(), 0xFFFDD835.toInt(), 0xFF00ACC1.toInt(), 0xFF43A047.toInt(), 0xFFFFFFFF.toInt())
        val patchW = w / patches.size.toFloat()
        for ((i, color) in patches.withIndex()) {
            paint.color = color
            canvas.drawRect(i * patchW, h / 3f, (i + 1) * patchW, 2 * h / 3f, paint)
        }
        paint.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 2 * h / 3f, w.toFloat(), h.toFloat(), paint)
        val out = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out))
        bitmap.recycle()
        return out.toByteArray()
    }

    /** FullResolutionDecoder's decode: software RGBA_F16 in linear extended sRGB; asserts the source has no gain map. */
    private fun decodeLikeTheApp(jpeg: ByteArray): LinearImage {
        val linear = ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB)
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(jpeg))) { decoder, _, _ ->
            decoder.setTargetColorSpace(linear)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        try {
            assertFalse("the SDR source must have no gain map", bitmap.hasGainmap())
            assertEquals(Bitmap.Config.RGBA_F16, bitmap.config)
            assertEquals(linear, bitmap.colorSpace)
            val halves = ByteBuffer.allocate(bitmap.byteCount).order(ByteOrder.nativeOrder())
            bitmap.copyPixelsToBuffer(halves)
            halves.rewind()
            val shorts = halves.asShortBuffer()
            val rgba = FloatArray(bitmap.width * bitmap.height * 4) { Half.toFloat(shorts.get(it)) }
            return LinearImage(bitmap.width, bitmap.height, rgba)
        } finally {
            bitmap.recycle()
        }
    }

    /** Nearest-neighbour sampling of [image] onto the export canvas. */
    private fun sample(image: LinearImage) = Paint { x, y, w, h, out ->
        val sx = x * image.width / w
        val sy = y * image.height / h
        val o = (sy * image.width + sx) * 4
        out[0] = image.rgba[o]
        out[1] = image.rgba[o + 1]
        out[2] = image.rgba[o + 2]
    }

    /** Chroma sampling ("4:4:4", "4:2:0", ...) of the first SOF frame header of [jpeg] (the primary image). */
    private fun firstFrameSampling(jpeg: ByteArray): String {
        var p = 2
        while (p + 4 <= jpeg.size) {
            check(jpeg[p].toInt() and 0xFF == 0xFF) { "not a marker at $p" }
            val marker = jpeg[p + 1].toInt() and 0xFF
            val length = (jpeg[p + 2].toInt() and 0xFF shl 8) or (jpeg[p + 3].toInt() and 0xFF)
            if (marker in 0xC0..0xC2) {
                val components = jpeg[p + 9].toInt() and 0xFF
                check(components == 3) { "expected YCbCr, got $components components" }
                val luma = jpeg[p + 11].toInt() and 0xFF
                val chroma = jpeg[p + 14].toInt() and 0xFF
                check(chroma == 0x11) { "chroma sampling ${chroma.toString(16)}" }
                return when (luma) {
                    0x11 -> "4:4:4"
                    0x21 -> "4:2:2"
                    0x22 -> "4:2:0"
                    else -> "luma ${luma.toString(16)}"
                }
            }
            p += 2 + length
        }
        error("no SOF marker")
    }
}
