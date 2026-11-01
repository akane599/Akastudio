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
import com.akane.akastudio.core.color.math.ColorMath
import com.akane.akastudio.core.export.api.ExportFormat
import com.akane.akastudio.core.export.api.ExportResolution
import com.akane.akastudio.core.export.api.ExportResult
import com.akane.akastudio.core.export.pipeline.ExportProcessor
import com.akane.akastudio.core.export.process.GainMapMath
import com.akane.akastudio.core.export.process.Half
import com.akane.akastudio.core.export.process.LumaSharpen
import com.akane.akastudio.core.export.store.PlatformUltraHdrEncoder
import com.akane.akastudio.core.export.store.PlatformWebpLosslessEncoder
import kotlin.math.log2
import kotlin.math.abs
import kotlin.math.pow
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
 * M1: explicit Ultra HDR requests preserve usable gain, including small gains on graded SDR sources. A unit
 * preflight gain falls back to Social; explicit Social exports retain their 4:4:4 codec and the same SDR shoulder.
 */
@RunWith(AndroidJUnit4::class)
class SdrOnlyUltraHdrDeviceTest {

    @Before
    fun needsGainmapApi() {
        assumeTrue("Ultra HDR export needs API 34+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    }

    @Test
    fun gradedSdrStoryWithUsableGainHonoursTheUltraHdrRequest() {
        val sdr = decodeLikeTheApp(sdrPhotoJpeg())
        val graded = CpuGradingPipeline.grade(sdr, GradeParams(look = MasterLook.MEDITERRANEAN_AZURE, contrast = 0.5f))
        val renderer = SyntheticRenderer(sample(graded))
        val ratioMax = GainMapMath.compute(renderer.render(1080, 1920), gainDownscale = ExportProcessor.GAIN_DOWNSCALE).ratioMax
        record("sdrOnly.ratioMax", ratioMax)
        assertTrue(
            "the graded SDR render must overshoot white a little, like SQ-52's (ratioMax $ratioMax)",
            ratioMax > ExportProcessor.MIN_HDR_RATIO && ratioMax < 2f.pow(1f / 3f),
        )

        val (result, jpeg) = exportUltraHdr(renderer)
        assertEquals("the reported format must preserve the explicit request", ExportFormat.ULTRA_HDR_JPEG, result.format)
        assertTrue(result.uri, result.uri.endsWith("_ULTRA_HDR_JPEG.jpg"))
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
        assertTrue("usable gain must be retained", bitmap.hasGainmap())
        assertEquals(1080 to 1920, bitmap.width to bitmap.height)
        bitmap.recycle()
        assertEquals("explicit Ultra HDR accepts platform chroma sampling", "4:2:0", firstFrameSampling(jpeg))

        val (socialResult, socialJpeg) = exportJpeg(renderer, ExportFormat.SOCIAL_STORY_JPEG)
        val social = checkNotNull(BitmapFactory.decodeByteArray(socialJpeg, 0, socialJpeg.size))
        try {
            assertEquals(ExportFormat.SOCIAL_STORY_JPEG, socialResult.format)
            assertFalse("explicit Social has no gain map", social.hasGainmap())
            assertEquals("explicit Social keeps full chroma", "4:4:4", firstFrameSampling(socialJpeg))
        } finally {
            social.recycle()
        }
    }

    /** Actual codec evidence at the numerical floor and on both sides of the former third-stop cutoff. */
    @Test
    fun nearUnitAndFormerThirdStopBoundariesKeepTheSdrRenditionContinuous() {
        var previousFormerCutoffBase: Float? = null
        for (peak in listOf(1f, 1.00390625f, 1.0078125f, 1.25f, 1.265625f)) {
            val renderer = SyntheticRenderer(Paint { x, _, w, _, out ->
                val v = if (x < w / 2) 1f else peak
                for (c in 0..2) out[c] = v
            })
            val input = renderer.render(1080, 1920)
            val preflight = GainMapMath.ratioMax(input, gainDownscale = ExportProcessor.GAIN_DOWNSCALE)
            val processed = LumaSharpen.apply(input)
            val finalGain = GainMapMath.compute(processed, gainDownscale = ExportProcessor.GAIN_DOWNSCALE)
            val (result, jpeg) = exportUltraHdr(renderer)
            val bitmap = checkNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
            try {
                val expectedUltra = preflight >= ExportProcessor.MIN_HDR_RATIO
                assertEquals(if (expectedUltra) ExportFormat.ULTRA_HDR_JPEG else ExportFormat.SOCIAL_STORY_JPEG, result.format)
                assertEquals(expectedUltra, bitmap.hasGainmap())
                assertEquals(if (expectedUltra) "4:2:0" else "4:4:4", firstFrameSampling(jpeg))
                val decodedBase = ColorMath.srgbToLinear((bitmap.getPixel(270, 960) shr 16 and 0xFF) / 255f)
                val expectedBase = finalGain.sdr.let { Half.toFloat(it.rgba[(960 * it.width + 270) * 4]) }
                assertTrue("peak $peak decodes the chosen SDR base", abs(decodedBase - expectedBase) <= 0.015f)
                record("m1.boundary.$peak.preflightRatio", preflight)
                record("m1.boundary.$peak.finalMathRatio", finalGain.ratioMax)
                record("m1.boundary.$peak.decodedBase", decodedBase)
                record("m1.boundary.$peak.format", result.format)
                if (bitmap.hasGainmap()) record("m1.boundary.$peak.encodedRatioMax", checkNotNull(bitmap.gainmap).ratioMax.toList())
                if (peak >= 1.25f) {
                    previousFormerCutoffBase?.let {
                        assertTrue("crossing the former third-stop cutoff must not jump the SDR base", abs(decodedBase - it) <= 0.02f)
                    }
                    previousFormerCutoffBase = decodedBase
                }
            } finally {
                bitmap.recycle()
            }
        }
    }

    /** Eligibility is deliberately measured before sharpening, which can introduce its own small overshoot. */
    @Test
    fun unitPreflightStillFallsBackWhenSharpeningCreatesUsableGain() {
        val renderer = SyntheticRenderer(step(low = 0.1f, high = 1f, edgeX = 540))
        val input = renderer.render(1080, 1920)
        val preflight = GainMapMath.ratioMax(input, gainDownscale = ExportProcessor.GAIN_DOWNSCALE)
        val finalRatio = GainMapMath.ratioMax(LumaSharpen.apply(input), gainDownscale = ExportProcessor.GAIN_DOWNSCALE)
        assertEquals("the input has no HDR headroom", 1f, preflight, 0f)
        assertTrue("the edge exercises a post-sharpen numerical change", finalRatio > 1f)
        val (result, jpeg) = exportUltraHdr(renderer)
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
        try {
            assertEquals("the format follows preflight, not later sharpening overshoot", ExportFormat.SOCIAL_STORY_JPEG, result.format)
            assertFalse(bitmap.hasGainmap())
            assertEquals("4:4:4", firstFrameSampling(jpeg))
            record("m1.sharpenBoundary.preflightRatio", preflight)
            record("m1.sharpenBoundary.finalRatio", finalRatio)
            record("m1.sharpenBoundary.format", result.format)
        } finally {
            bitmap.recycle()
        }
    }

    /** M1: preserve the original decoded fixture and measure both grades against the same headroom rule. */
    @Test
    fun azureContrastSettingsUseTheMeasuredHeadroomBoundary() {
        val sdr = decodeLikeTheApp(sdrPhotoJpeg())
        for (contrast in listOf(0.5f, 1f)) {
            val graded = CpuGradingPipeline.grade(sdr, GradeParams(look = MasterLook.MEDITERRANEAN_AZURE, contrast = contrast))
            val renderer = SyntheticRenderer(sample(graded))
            val ratio = GainMapMath.ratioMax(renderer.render(1080, 1920), gainDownscale = ExportProcessor.GAIN_DOWNSCALE)
            val expectedFormat = if (ratio >= ExportProcessor.MIN_HDR_RATIO) {
                ExportFormat.ULTRA_HDR_JPEG
            } else {
                ExportFormat.SOCIAL_STORY_JPEG
            }
            val (result, jpeg) = exportUltraHdr(renderer)
            val base = checkNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
            try {
                record("m1.azure.contrast$contrast.ratioMax", ratio)
                record("m1.azure.contrast$contrast.stops", log2(ratio))
                record("m1.azure.contrast$contrast.format", result.format)
                record("m1.azure.contrast$contrast.baseSampling", firstFrameSampling(jpeg))
                record("m1.azure.contrast$contrast.baseCentreArgb", base.getPixel(540, 960).toUInt().toString(16))
                record("m1.azure.contrast$contrast.hasGainmap", base.hasGainmap())
                if (base.hasGainmap()) {
                    val gain = checkNotNull(base.gainmap)
                    record("m1.azure.contrast$contrast.encodedRatioMax", gain.ratioMax.toList())
                    record("m1.azure.contrast$contrast.gainDimensions", "${gain.gainmapContents.width}x${gain.gainmapContents.height}")
                }
                assertEquals("contrast $contrast follows the measured pre-sharpen headroom", expectedFormat, result.format)
                assertEquals("contrast $contrast gain-map presence agrees with the format", expectedFormat == ExportFormat.ULTRA_HDR_JPEG, base.hasGainmap())
                assertEquals(if (expectedFormat == ExportFormat.ULTRA_HDR_JPEG) "4:2:0" else "4:4:4", firstFrameSampling(jpeg))
                assertEquals(1080 to 1920, base.width to base.height)
            } finally {
                base.recycle()
            }
        }
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
        return exportJpeg(renderer, ExportFormat.ULTRA_HDR_JPEG)
    }

    private fun exportJpeg(renderer: SyntheticRenderer, format: ExportFormat): Pair<ExportResult, ByteArray> {
        val sink = MemorySink()
        val exporter = ExportProcessor(renderer, sink, PlatformUltraHdrEncoder(), PlatformWebpLosslessEncoder())
        val result = exporter.exportBlocking(exportRequest(format, ExportResolution.HD_1080))
        return result to sink.files.single().bytes
    }

    /** Reuses the exact Canvas/JPEG/F16 fixture for the opt-in paired artifact capture. */
    internal fun comparisonInputJpeg(): ByteArray = sdrPhotoJpeg()

    internal fun comparisonRenderer(contrast: Float, jpeg: ByteArray): SyntheticRenderer {
        val sdr = decodeLikeTheApp(jpeg)
        val graded = CpuGradingPipeline.grade(sdr, GradeParams(look = MasterLook.MEDITERRANEAN_AZURE, contrast = contrast))
        return SyntheticRenderer(sample(graded))
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
