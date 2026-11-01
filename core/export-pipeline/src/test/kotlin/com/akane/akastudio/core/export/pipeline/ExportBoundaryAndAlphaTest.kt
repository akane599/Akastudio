package com.akane.akastudio.core.export.pipeline

import com.akane.akastudio.core.color.api.HalfFloatImage
import com.akane.akastudio.core.color.api.ImagePixels
import com.akane.akastudio.core.color.api.PreviewOptions
import com.akane.akastudio.core.color.api.RenderScene
import com.akane.akastudio.core.color.api.SceneBackground
import com.akane.akastudio.core.color.api.StoryRenderer
import com.akane.akastudio.core.export.api.ExportFormat
import com.akane.akastudio.core.export.api.ExportMetadata
import com.akane.akastudio.core.export.api.ExportPlan
import com.akane.akastudio.core.export.api.ExportProgress
import com.akane.akastudio.core.export.api.ExportRequest
import com.akane.akastudio.core.export.api.ExportResolution
import com.akane.akastudio.core.export.api.ExportResult
import com.akane.akastudio.core.export.encode.JpegEncoder
import com.akane.akastudio.core.export.encode.Rgba8Rows
import com.akane.akastudio.core.export.process.Dither
import com.akane.akastudio.core.export.process.GainMapMath
import com.akane.akastudio.core.export.process.GainMapResult
import com.akane.akastudio.core.export.process.Half
import com.akane.akastudio.core.export.process.LumaSharpen
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * ExportProcessor-level pins for two stabilization coverage gaps (docs/stabilization/COVERAGE.md):
 * - the M1 numerical boundary: full-resolution gain density for preflight and encode, and the explicit Ultra HDR
 *   decision at the .01-stop gain floor (kept just above it, Social fallback just below, decided before sharpening);
 * - the AUD-08 alpha contract per output: alpha-preserving outputs (PNG16, lossless WebP) store straight colour with
 *   zero alpha as zero colour; opaque outputs (PNG8, Social JPEG, the Ultra HDR base) composite over black.
 *
 * Platform encoders (Bitmap/WebP/Gainmap) are replaced by recording fakes; their side is `AlphaContractDeviceTest`.
 */
class ExportBoundaryAndAlphaTest {
    // ---- M1 numerical boundary -------------------------------------------------------------------------------------

    @Test
    fun preflightAndEncodeUseTheFullResolutionGainGrid() {
        assertEquals("C1: export gain is measured on the full-resolution grid", 1, ExportProcessor.GAIN_DOWNSCALE)
        val encoder = RecordingUltraHdrEncoder()
        val run = export(ExportFormat.ULTRA_HDR_JPEG, singleHighlight(ABOVE_FLOOR), ultraHdr = encoder)
        assertEquals(ExportFormat.ULTRA_HDR_JPEG, run.result.format)
        val gain = checkNotNull(encoder.gain)
        assertEquals(1080 to 1920, gain.gainWidth to gain.gainHeight)
    }

    /**
     * One neutral pixel of P in a mid-grey frame: its full-resolution cell has `log2((P + 1/64) / (1 + 1/64))`. The
     * adjacent half values 1 + 8/1024 (0.01106 stops, ratioMax 1.0077 >= MIN_HDR_RATIO 1.001) and 1 + 7/1024 (0.00968
     * stops, unit gain) straddle the .01-stop floor, so an explicit Ultra HDR request is kept for the first and falls
     * back to Social for the second.
     */
    @Test
    fun explicitUltraHdrIsKeptJustAboveTheGainFloorAndFallsBackJustBelow() {
        val above = singleHighlight(ABOVE_FLOOR)
        val aboveRatio = GainMapMath.ratioMax(above.render(1080, 1920), gainDownscale = ExportProcessor.GAIN_DOWNSCALE)
        assertEquals(1.0076923f, aboveRatio, 2e-6f)
        assertTrue(aboveRatio >= ExportProcessor.MIN_HDR_RATIO)
        val kept = RecordingUltraHdrEncoder()
        val keptRun = export(ExportFormat.ULTRA_HDR_JPEG, above, ultraHdr = kept)
        assertEquals(ExportFormat.ULTRA_HDR_JPEG, keptRun.result.format)
        assertEquals(1, kept.calls)

        val below = singleHighlight(BELOW_FLOOR)
        assertEquals(1f, GainMapMath.ratioMax(below.render(1080, 1920), gainDownscale = ExportProcessor.GAIN_DOWNSCALE), 0f)
        val fallback = RecordingUltraHdrEncoder()
        val fallbackRun = export(ExportFormat.ULTRA_HDR_JPEG, below, ultraHdr = fallback)
        assertEquals(ExportFormat.SOCIAL_STORY_JPEG, fallbackRun.result.format)
        assertEquals("the gain-map encoder is never called below the floor", 0, fallback.calls)
        assertEquals("image/jpeg", fallbackRun.result.mimeType)
    }

    /**
     * M1: the decision stays before sharpening. Sharpening the sub-floor highlight overshoots it past the floor, yet the
     * unit preflight on the unsharpened frame still selects the Social fallback.
     */
    @Test
    fun sharpeningOvershootDoesNotTurnAUnitPreflightIntoUltraHdr() {
        val below = singleHighlight(BELOW_FLOOR)
        val sharpened = LumaSharpen.apply(below.render(1080, 1920))
        val sharpenedRatio = GainMapMath.ratioMax(sharpened, gainDownscale = ExportProcessor.GAIN_DOWNSCALE)
        assertTrue(
            "fixture: sharpening alone pushes the highlight past the floor ($sharpenedRatio)",
            sharpenedRatio >= ExportProcessor.MIN_HDR_RATIO,
        )
        val encoder = RecordingUltraHdrEncoder()
        val run = export(ExportFormat.ULTRA_HDR_JPEG, below, ultraHdr = encoder)
        assertEquals(ExportFormat.SOCIAL_STORY_JPEG, run.result.format)
        assertEquals(0, encoder.calls)
    }

    // ---- AUD-08 alpha contract ---------------------------------------------------------------------------------------

    /**
     * Lossless WebP receives straight rows: the exact [Dither.toStraightSrgb8Row] of the render; zero alpha (also under a
     * stray premultiplied colour) stores 0x00000000; half-transparent frosted white stores straight 0.75 (code 224/225),
     * not the over-black 164/165.
     */
    @Test
    fun webpExportFeedsStraightRowsAndStoresZeroAlphaAsTransparentBlack() {
        val renderer = translucentBands()
        val webp = RecordingWebpEncoder()
        val run = export(ExportFormat.WEBP_LOSSLESS, renderer, webpEncoder = webp)
        assertEquals(ExportFormat.WEBP_LOSSLESS, run.result.format)
        val frame = renderer.render(1080, 1920)
        val expected = IntArray(1080)
        for (y in 0 until 1920) {
            Dither.toStraightSrgb8Row(frame, y, expected)
            assertArrayEquals("row $y", expected, webp.rows[y])
        }
        for (y in intArrayOf(0, 777, 1919)) {
            val row = webp.rows[y]
            for (x in bandColumns(0) + bandColumns(3)) assertEquals("zero alpha ($x,$y)", 0, row[x])
            for (x in bandColumns(1)) {
                assertEquals("alpha ($x,$y)", 128, row[x] ushr 24)
                for (shift in intArrayOf(16, 8, 0)) assertTrue("straight colour ($x,$y)", ((row[x] ushr shift) and 0xFF) in 224..225)
            }
            for (x in bandColumns(2)) {
                assertEquals(255, row[x] ushr 24)
                for (shift in intArrayOf(16, 8, 0)) assertTrue("opaque colour ($x,$y)", ((row[x] ushr shift) and 0xFF) in 224..225)
            }
        }
    }

    @Test
    fun png16ExportStoresStraightColourAndZeroColourUnderZeroAlpha() {
        val run = export(ExportFormat.PNG_16, translucentBands())
        val decoded = ImageIO.read(ByteArrayInputStream(run.bytes))
        assertTrue(decoded.colorModel.hasAlpha())
        assertFalse(decoded.colorModel.isAlphaPremultiplied)
        val raster = decoded.raster
        for (y in intArrayOf(0, 960, 1919)) {
            for (band in 0..3) {
                val x = bandCentre(band)
                val samples = IntArray(4) { raster.getSample(x, y, it) }
                val expected =
                    when (band) {
                        0, 3 -> intArrayOf(0, 0, 0, 0)
                        1 -> intArrayOf(STRAIGHT_075_16, STRAIGHT_075_16, STRAIGHT_075_16, 32768)
                        else -> intArrayOf(STRAIGHT_075_16, STRAIGHT_075_16, STRAIGHT_075_16, 65535)
                    }
                assertArrayEquals("band $band at ($x,$y)", expected, samples)
            }
        }
    }

    @Test
    fun png8ExportCompositesTranslucencyOverBlack() {
        val run = export(ExportFormat.PNG_8, translucentBands())
        val decoded = ImageIO.read(ByteArrayInputStream(run.bytes))
        assertFalse("PNG8 drops alpha", decoded.colorModel.hasAlpha())
        for (y in intArrayOf(0, 960, 1919)) {
            for (x in bandColumns(0)) assertEquals("transparent is black ($x,$y)", 0, decoded.getRGB(x, y) and 0xFFFFFF)
            for (x in bandColumns(1)) {
                for (shift in intArrayOf(16, 8, 0)) {
                    assertTrue("frosted half white over black ($x,$y)", ((decoded.getRGB(x, y) ushr shift) and 0xFF) in 164..165)
                }
            }
        }
    }

    /** Social at HD renders 2160x3840, resamples and sharpens; band interiors stay uniform, so codes are predictable. */
    @Test
    fun socialJpegExportCompositesTranslucencyOverBlack() {
        val run = export(ExportFormat.SOCIAL_STORY_JPEG, translucentBands())
        assertEquals(ExportFormat.SOCIAL_STORY_JPEG, run.result.format)
        val decoded = ImageIO.read(ByteArrayInputStream(run.bytes))
        assertEquals(1080, decoded.width)
        for (y in intArrayOf(400, 960, 1500)) {
            val transparent = decoded.getRGB(bandCentre(0), y)
            val frosted = decoded.getRGB(bandCentre(1), y)
            val opaque = decoded.getRGB(bandCentre(2), y)
            for (shift in intArrayOf(16, 8, 0)) {
                assertTrue("transparent band is black: ${hex(transparent)}", ((transparent ushr shift) and 0xFF) <= 2)
                assertTrue("frosted half white composites over black: ${hex(frosted)}", abs(((frosted ushr shift) and 0xFF) - 165) <= 3)
                assertTrue("opaque 0.75: ${hex(opaque)}", abs(((opaque ushr shift) and 0xFF) - 225) <= 3)
            }
        }
    }

    /**
     * A translucent HDR frame: the encoder receives a base whose colour is the premultiplied (over-black) rendition and
     * whose alpha still carries the frame's alpha (the platform encoder forces it opaque, see the device test). Gain and
     * base colour equal those of the same premultiplied colour at alpha 1.
     */
    @Test
    fun ultraHdrOfATranslucentFrameEncodesTheOverBlackRendition() {
        val translucent = RecordingUltraHdrEncoder()
        val run = export(ExportFormat.ULTRA_HDR_JPEG, translucentBands(hdrPeak = 4f), ultraHdr = translucent)
        assertEquals(ExportFormat.ULTRA_HDR_JPEG, run.result.format)
        val opaque = RecordingUltraHdrEncoder()
        export(ExportFormat.ULTRA_HDR_JPEG, translucentBands(hdrPeak = 4f, forceOpaque = true), ultraHdr = opaque)

        val sdr = checkNotNull(translucent.sdr)
        val opaqueSdr = checkNotNull(opaque.sdr)
        val gain = checkNotNull(translucent.gain)
        val opaqueGain = checkNotNull(opaque.gain)
        assertArrayEquals("gain codes ignore alpha", opaqueGain.gain, gain.gain)
        assertEquals(opaqueGain.ratioMin, gain.ratioMin, 0f)
        assertEquals(opaqueGain.ratioMax, gain.ratioMax, 0f)
        for (i in sdr.rgba.indices) {
            if (i % 4 != 3) assertEquals("base colour sample $i", opaqueSdr.rgba[i], sdr.rgba[i])
        }
        val y = 1500 // below the HDR rows, so the frosted band is below the knee and keeps its colour
        assertEquals("alpha reaches the encoder", 0.5f, sample(sdr, bandCentre(1), y, 3), 0f)
        assertEquals(0f, sample(sdr, bandCentre(0), y, 3), 0f)
        val row = IntArray(1080)
        Dither.toSrgb8Row(sdr, y, row)
        for (x in bandColumns(1)) {
            for (shift in intArrayOf(16, 8, 0)) assertTrue("base over black ($x,$y)", ((row[x] ushr shift) and 0xFF) in 164..165)
        }
        for (x in bandColumns(0)) assertEquals("transparent base is black ($x,$y)", 0, row[x] and 0xFFFFFF)
    }

    // ---- fixtures ----------------------------------------------------------------------------------------------------

    /** Neutral 0.5 frame with one neutral pixel of [peak] at the canvas centre. */
    private fun singleHighlight(peak: Float) =
        PixelRenderer { x, y, w, h, out ->
            val v = if (x == w / 2 && y == h / 2) peak else 0.5f
            out[0] = v
            out[1] = v
            out[2] = v
            out[3] = 1f
        }

    /**
     * Four vertical quarter-width bands of premultiplied linear RGBA: 0 transparent (0, 0, 0, 0); 1 half-transparent
     * frosted white (0.375 x3, 0.5); 2 opaque 0.75 grey, whose top eighth reaches [hdrPeak] when given; 3 a stray
     * premultiplied colour under zero alpha (0.3, 0.5, 0.7, 0). [forceOpaque] keeps the colour and sets alpha 1.
     */
    private fun translucentBands(
        hdrPeak: Float? = null,
        forceOpaque: Boolean = false,
    ) = PixelRenderer { x, y, w, h, out ->
        val band = x * 4 / w
        val px =
            when (band) {
                0 -> {
                    floatArrayOf(0f, 0f, 0f, 0f)
                }

                1 -> {
                    floatArrayOf(0.375f, 0.375f, 0.375f, 0.5f)
                }

                2 -> {
                    if (hdrPeak != null &&
                        y < h / 8
                    ) {
                        floatArrayOf(hdrPeak, hdrPeak, hdrPeak, 1f)
                    } else {
                        floatArrayOf(0.75f, 0.75f, 0.75f, 1f)
                    }
                }

                else -> {
                    floatArrayOf(0.3f, 0.5f, 0.7f, 0f)
                }
            }
        px.copyInto(out)
        if (forceOpaque) out[3] = 1f
    }

    /** Interior columns of quarter band [band] on a 1080-wide output, away from resampling and sharpening at edges. */
    private fun bandColumns(band: Int): IntArray = IntArray(5) { bandCentre(band) - 100 + it * 50 }

    private fun bandCentre(band: Int): Int = band * 270 + 135

    private fun sample(
        img: HalfFloatImage,
        x: Int,
        y: Int,
        c: Int,
    ): Float = Half.toFloat(img.rgba[(y * img.width + x) * 4 + c])

    private fun hex(argb: Int) = "%08X".format(argb)

    // ---- harness -----------------------------------------------------------------------------------------------------

    private class Run(
        val result: ExportResult,
        val bytes: ByteArray,
    )

    private fun export(
        format: ExportFormat,
        renderer: StoryRenderer,
        ultraHdr: UltraHdrEncoder? = null,
        webpEncoder: WebpLosslessEncoder = RecordingWebpEncoder(),
    ): Run {
        var published: ByteArray? = null
        val sink =
            EncodedSink { displayName, _, _, block ->
                val buffer = ByteArrayOutputStream()
                block(buffer)
                published = buffer.toByteArray()
                "content://test/$displayName"
            }
        val processor = ExportProcessor(renderer, sink, ultraHdr, webpEncoder, clock = { 0L })
        val (w, h) = ExportPlan.renderSize(format, ExportResolution.HD_1080)
        val request =
            ExportRequest(
                scene = RenderScene(w, h, SceneBackground.Solid(0xFF000000.toInt()), emptyList()),
                format = format,
                resolution = ExportResolution.HD_1080,
                metadata = ExportMetadata(captureEpochMillis = null, description = null),
            )
        val events = runBlocking { processor.export(request).toList() }
        val done = events.last() as ExportProgress.Done
        return Run(done.result, checkNotNull(published))
    }

    /** Fills pixel ([x], [y]) of a [w] x [h] frame with premultiplied linear RGBA into `out[0..3]`. */
    private fun interface Paint {
        fun paint(
            x: Int,
            y: Int,
            w: Int,
            h: Int,
            out: FloatArray,
        )
    }

    private class PixelRenderer(
        private val paint: Paint,
    ) : StoryRenderer {
        fun render(
            w: Int,
            h: Int,
        ): HalfFloatImage {
            val data = ShortArray(w * h * 4)
            val px = FloatArray(4)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    paint.paint(x, y, w, h, px)
                    val o = (y * w + x) * 4
                    for (c in 0..3) data[o + c] = Half.fromFloat(px[c])
                }
            }
            return HalfFloatImage(w, h, data)
        }

        override suspend fun renderOffscreen(scene: RenderScene): HalfFloatImage = render(scene.canvasWidth, scene.canvasHeight)

        override suspend fun registerImage(
            key: String,
            bitmap: android.graphics.Bitmap,
        ) = unused()

        override suspend fun unregisterImage(key: String) = unused()

        override suspend fun renderToSurface(
            scene: RenderScene,
            surface: android.view.Surface,
            surfaceWidth: Int,
            surfaceHeight: Int,
            options: PreviewOptions,
        ) = unused()

        override suspend fun renderPreviewPixels(
            scene: RenderScene,
            width: Int,
            height: Int,
        ): ImagePixels = unused()

        override suspend fun maxTextureSize(): Int = unused()

        override fun close() = Unit

        private fun unused(): Nothing = throw UnsupportedOperationException("the exporter only renders offscreen")
    }

    /** Keeps every pulled row and returns a minimal simple-lossless WebP so the container can be assembled. */
    private class RecordingWebpEncoder : WebpLosslessEncoder {
        val rows = ArrayList<IntArray>()

        override fun encode(
            width: Int,
            height: Int,
            rows: Rgba8Rows,
        ): ByteArray {
            for (y in 0 until height) this.rows += IntArray(width).also { rows.row(y, it) }
            val bits = (width - 1) or ((height - 1) shl 14)
            val payload = byteArrayOf(0x2F) + le32(bits) + ByteArray(7) { (it * 31 + 1).toByte() }
            val chunk =
                "VP8L".toByteArray(Charsets.ISO_8859_1) + le32(payload.size) + payload +
                    if (payload.size % 2 == 1) byteArrayOf(0) else byteArrayOf()
            return "RIFF".toByteArray(Charsets.ISO_8859_1) + le32(4 + chunk.size) + "WEBP".toByteArray(Charsets.ISO_8859_1) + chunk
        }

        private fun le32(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())
    }

    /** Records its inputs and writes the base as a plain JPEG (over black, like the platform's opaque base). */
    private class RecordingUltraHdrEncoder : UltraHdrEncoder {
        var calls = 0
        var sdr: HalfFloatImage? = null
        var gain: GainMapResult? = null

        override fun encode(
            sdr: HalfFloatImage,
            gain: GainMapResult,
            out: OutputStream,
            quality: Int,
            exifTiff: ByteArray?,
        ) {
            calls++
            this.sdr = sdr
            this.gain = gain
            JpegEncoder.write(out, sdr.width, sdr.height, { y, row -> Dither.toSrgb8Row(sdr, y, row) }, quality, exifTiff = exifTiff)
        }
    }

    private companion object {
        /** 1 + 8/1024: the smallest half above 1 whose single-pixel gain clears the .01-stop floor. */
        const val ABOVE_FLOOR = 1f + 8f / 1024f

        /** 1 + 7/1024: the largest half above 1 whose single-pixel gain stays below the floor. */
        const val BELOW_FLOOR = 1f + 7f / 1024f

        /** sRGB-encoded 16-bit code of straight linear 0.75. */
        const val STRAIGHT_075_16 = 57725
    }
}
