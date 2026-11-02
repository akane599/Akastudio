package com.akane.akastudio.core.export.device

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.akane.akastudio.core.color.api.HalfFloatImage
import com.akane.akastudio.core.color.api.ImagePixels
import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.color.api.PreviewOptions
import com.akane.akastudio.core.color.api.Rect4f
import com.akane.akastudio.core.color.api.RenderScene
import com.akane.akastudio.core.color.api.SceneBackground
import com.akane.akastudio.core.color.api.SceneLayer
import com.akane.akastudio.core.color.api.StoryRenderer
import com.akane.akastudio.core.color.math.ColorMath
import com.akane.akastudio.core.color.render.createStoryRenderer
import com.akane.akastudio.core.export.api.ExportFormat
import com.akane.akastudio.core.export.api.ExportMetadata
import com.akane.akastudio.core.export.api.ExportPlan
import com.akane.akastudio.core.export.api.ExportRequest
import com.akane.akastudio.core.export.api.ExportResolution
import com.akane.akastudio.core.export.encode.Rgba8Rows
import com.akane.akastudio.core.export.pipeline.ExportProcessor
import com.akane.akastudio.core.export.process.Dither
import com.akane.akastudio.core.export.process.GainMapMath
import com.akane.akastudio.core.export.process.Half
import com.akane.akastudio.core.export.store.PlatformUltraHdrEncoder
import com.akane.akastudio.core.export.store.PlatformWebpLosslessEncoder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/**
 * The AUD-08 alpha contract at the platform seams (PROGRESS.md decision log, "audit AUD-08"): alpha-preserving outputs
 * (PNG16, lossless WebP) store straight colour, zero alpha stores zero colour and the WebP bitmap is non-premultiplied;
 * opaque outputs (PNG8, Social JPEG, the Ultra HDR base) composite the premultiplied frame over black.
 *
 * Closes the docs/stabilization/COVERAGE.md rows "WebP alpha0", "WebP setPremultiplied sensitivity", "Translucent Ultra
 * HDR base" and "Frosted transparent end-to-end". The JVM side of the same contract is `ExportBoundaryAndAlphaTest`
 * and `GainMapBoundaryTest`.
 */
@RunWith(AndroidJUnit4::class)
class AlphaContractDeviceTest {
    // ---- WebP alpha 0 --------------------------------------------------------------------------------------------

    /**
     * Fully transparent pixels, including ones carrying a stray premultiplied colour and NaN/negative alpha, reach the
     * platform WebP as 0x00000000 and decode as transparent black on both decoders (no division-by-zero colour leak).
     */
    @Test
    fun webpZeroAlphaDecodesAsTransparentBlack() {
        val alphas = floatArrayOf(0f, -1f, Float.NaN, 1e-6f, 0.5f, 1f)
        val frame =
            halfFrame(SIZE, SIZE) { x, _, out ->
                val a = alphas[x * alphas.size / SIZE]
                val straight = floatArrayOf(0.3f, 0.5f, 0.7f)
                // Zero-alpha bands carry a stray premultiplied colour on purpose; valid bands are premultiplied.
                val scale = if (a.isNaN() || a <= 0.001f) 1f else a
                for (c in 0..2) out[c] = straight[c] * scale
                out[3] = a
            }
        val webp = PlatformWebpLosslessEncoder().encode(SIZE, SIZE) { y, row -> Dither.toStraightSrgb8Row(frame, y, row) }
        val straightOptions = BitmapFactory.Options().apply { inPremultiplied = false }
        val decoded =
            listOf(
                "BitmapFactory(unpremultiplied)" to checkNotNull(BitmapFactory.decodeByteArray(webp, 0, webp.size, straightOptions)),
                "ImageDecoder(unpremultiplied)" to decodeUnpremultiplied(webp),
            )
        val expected = IntArray(SIZE)
        for ((name, bitmap) in decoded) {
            try {
                assertTrue("$name lost alpha", bitmap.hasAlpha())
                val actual = IntArray(SIZE)
                var zeroPixels = 0
                for (y in 0 until SIZE) {
                    Dither.toStraightSrgb8Row(frame, y, expected)
                    bitmap.getPixels(actual, 0, SIZE, 0, y, SIZE, 1)
                    for (x in 0 until SIZE) {
                        if (expected[x] ushr 24 != 0) continue
                        zeroPixels++
                        assertEquals("$name ($x,$y): zero alpha must decode as transparent black", 0, actual[x])
                    }
                }
                record("alphaContract.webpAlpha0.$name.zeroPixels", zeroPixels)
                // Bands 0..3 (alpha 0, -1, NaN, 1e-6) are columns x * 6 / 256 < 4, i.e. x < 171.
                assertEquals("four of six bands have alpha code 0", 171 * SIZE, zeroPixels)
            } finally {
                bitmap.recycle()
            }
        }
    }

    // ---- WebP setPremultiplied(false) sensitivity ------------------------------------------------------------------

    /**
     * Low alpha codes make premultiplied 8-bit storage lose straight colour: at alpha 1/255 a premultiplied bitmap can
     * hold only colour 0 or 255. The production encoder (non-premultiplied bitmap) must keep every straight code within
     * [MAX_STRAIGHT_DRIFT]; the control, the same rows through a default premultiplied bitmap, must drift by at least
     * [MIN_CONTROL_DRIFT] codes, so this test fails if `setPremultiplied(false)` is dropped.
     */
    @Test
    fun webpStraightColourAtLowAlphaNeedsTheNonPremultipliedBitmap() {
        // Columns: alpha codes 1..16 cycling; rows: straight colour codes 0..255 per channel (offset per channel).
        val rows =
            Rgba8Rows { y, out ->
                for (x in out.indices) {
                    val a = 1 + x % 16
                    val r = y and 0xFF
                    val g = (y + 85) and 0xFF
                    val b = (y + 170) and 0xFF
                    out[x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        val production = PlatformWebpLosslessEncoder().encode(LOW_ALPHA_W, LOW_ALPHA_H, rows)
        val control = premultipliedWebp(LOW_ALPHA_W, LOW_ALPHA_H, rows)
        val productionDrift = worstStraightDrift(production, rows)
        val controlDrift = worstStraightDrift(control, rows)
        record("alphaContract.webpLowAlpha.productionDrift", productionDrift)
        record("alphaContract.webpLowAlpha.premultipliedControlDrift", controlDrift)
        assertTrue("control: a premultiplied bitmap drifts only $controlDrift codes", controlDrift >= MIN_CONTROL_DRIFT)
        assertTrue("non-premultiplied WebP drifted $productionDrift codes at low alpha", productionDrift <= MAX_STRAIGHT_DRIFT)
    }

    // ---- Translucent Ultra HDR base --------------------------------------------------------------------------------

    /**
     * A translucent HDR frame's base is its premultiplied colour over black: the platform encoder forces the base opaque,
     * so the file is byte-identical to the one encoded from the same colour at alpha 1 (gain and base colour ignore
     * alpha), the decoded base is opaque, and half-transparent frosted white decodes near the over-black 165, not the
     * straight 225 or a double-premultiplied ~120.
     */
    @Test
    fun translucentUltraHdrBaseEncodesAsItsOpaqueOverBlackComposite() {
        assumeTrue("Ultra HDR export needs API 34+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val translucent = bandFrame(forceOpaque = false)
        val opaque = bandFrame(forceOpaque = true)
        val gain = GainMapMath.compute(translucent, gainDownscale = ExportProcessor.GAIN_DOWNSCALE)
        val opaqueGain = GainMapMath.compute(opaque, gainDownscale = ExportProcessor.GAIN_DOWNSCALE)
        assertTrue("fixture has headroom", gain.ratioMax > 1f)
        assertArrayEquals("gain codes ignore alpha", opaqueGain.gain, gain.gain)

        val jpeg = ByteArrayOutputStream().also { PlatformUltraHdrEncoder().encode(gain.sdr, gain, it, 95, null) }.toByteArray()
        val opaqueJpeg =
            ByteArrayOutputStream()
                .also {
                    PlatformUltraHdrEncoder().encode(
                        opaqueGain.sdr,
                        opaqueGain,
                        it,
                        95,
                        null,
                    )
                }.toByteArray()
        record("alphaContract.translucentUltra.bytes", "${jpeg.size}/${opaqueJpeg.size}")
        assertArrayEquals("a translucent base must encode exactly as its opaque over-black composite", opaqueJpeg, jpeg)

        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
        try {
            assertTrue("no gain map in the translucent Ultra HDR export", bitmap.hasGainmap())
            val pixels = bitmap.argbPixels()
            assertTrue("the decoded base must be opaque", pixels.all { it ushr 24 == 0xFF })
            val frosted = pixels[BAND_Y_SDR * SIZE + bandCentre(1)]
            val transparent = pixels[BAND_Y_SDR * SIZE + bandCentre(0)]
            record("alphaContract.translucentUltra.frosted", "%08X".format(frosted))
            record("alphaContract.translucentUltra.transparent", "%08X".format(transparent))
            for (shift in intArrayOf(16, 8, 0)) {
                assertTrue("frosted base ${"%08X".format(frosted)}", abs(((frosted ushr shift) and 0xFF) - 165) <= 4)
                assertTrue("transparent base ${"%08X".format(transparent)}", ((transparent ushr shift) and 0xFF) <= 4)
            }
        } finally {
            bitmap.recycle()
        }
    }

    // ---- Frosted over a transparent source, end to end ---------------------------------------------------------------

    /**
     * Transparent source -> production GL renderer (Frosted background of the same image plus a layer) -> ExportProcessor
     * -> platform encoders -> platform decoders, for every format. The frame must be premultiplied and actually contain
     * transparent, translucent and opaque pixels; each file must then follow its alpha policy pixel for pixel (PNG16,
     * WebP, PNG8) or at uniform probe regions (JPEGs, which are resampled/sharpened and lossy).
     */
    @Test
    fun frostedOverTransparentExportFollowsTheAlphaContract() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val renderer = CapturingRenderer(createStoryRenderer(instrumentation.targetContext))
        val source = transparentSource()
        try {
            runBlocking { renderer.registerImage(SOURCE_KEY, source) }
            val ultra = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) PlatformUltraHdrEncoder() else null
            val formats =
                listOf(
                    ExportFormat.PNG_16,
                    ExportFormat.WEBP_LOSSLESS,
                    ExportFormat.PNG_8,
                    ExportFormat.SOCIAL_STORY_JPEG,
                    ExportFormat.ULTRA_HDR_JPEG,
                )
            for (format in formats) {
                val sink = MemorySink()
                // The JPEG writers flatten the frame over black in place, so its alpha is checked as it is rendered.
                renderer.inspect = { assertFrostedFrame(format, it) }
                val result = ExportProcessor(renderer, sink, ultra, PlatformWebpLosslessEncoder()).exportBlocking(frostedRequest(format))
                renderer.inspectFailure?.let { throw it }
                val frame = checkNotNull(renderer.last) { "no frame captured for $format" }
                renderer.last = null
                val bytes = sink.files.single().bytes
                record("alphaContract.frosted.$format", "${result.format} ${result.width}x${result.height} ${bytes.size} bytes")
                when (format) {
                    ExportFormat.PNG_16 -> {
                        assertPng16Straight(frame, bytes)
                    }

                    ExportFormat.WEBP_LOSSLESS -> {
                        assertWebpStraight(frame, bytes)
                    }

                    ExportFormat.PNG_8 -> {
                        assertPng8OverBlack(frame, bytes)
                    }

                    ExportFormat.SOCIAL_STORY_JPEG, ExportFormat.ULTRA_HDR_JPEG -> {
                        assertTrue(
                            "$format wrote ${result.format}",
                            result.format == ExportFormat.SOCIAL_STORY_JPEG || result.format == ExportFormat.ULTRA_HDR_JPEG,
                        )
                        assertJpegOverBlack(format, frame, bytes)
                    }
                }
            }
        } finally {
            runBlocking { renderer.unregisterImage(SOURCE_KEY) }
            renderer.close()
            source.recycle()
        }
    }

    /** Preconditions on the GL frame: premultiplied, and the probes are transparent / half-transparent frosted white. */
    private fun assertFrostedFrame(
        format: ExportFormat,
        frame: HalfFloatImage,
    ) {
        var transparent = 0
        var translucent = 0
        var opaque = 0
        var worstExcess = 0f
        for (i in 0 until frame.width * frame.height) {
            val a = Half.toFloat(frame.rgba[i * 4 + 3])
            when {
                a <= 0f -> transparent++
                a >= 1f -> opaque++
                else -> translucent++
            }
            for (c in 0..2) worstExcess = max(worstExcess, Half.toFloat(frame.rgba[i * 4 + c]) - max(0f, a))
        }
        record(
            "alphaContract.frosted.$format.frame",
            "transparent=$transparent translucent=$translucent opaque=$opaque worstColourOverAlpha=$worstExcess",
        )
        assertTrue("$format frame has no fully transparent pixel", transparent > 0)
        assertTrue("$format frame has no translucent pixel", translucent > 0)
        assertTrue("$format frame has no opaque pixel", opaque > 0)
        assertTrue("$format frame is not premultiplied: colour exceeds alpha by $worstExcess", worstExcess <= PREMULTIPLIED_SLACK)

        val (tx, ty) = transparentProbe(frame.width, frame.height)
        for (c in 0..3) assertEquals("$format transparent probe channel $c", 0f, frame.at(tx, ty, c), 1f / 512f)
        val (fx, fy) = frostedProbe(frame.width, frame.height)
        assertEquals("$format frosted probe alpha", 128f / 255f, frame.at(fx, fy, 3), 0.02f)
        for (c in 0..2) assertEquals("$format frosted probe colour $c", 0.75f * 128f / 255f, frame.at(fx, fy, c), 0.03f)
    }

    private fun assertPng16Straight(
        frame: HalfFloatImage,
        png: ByteArray,
    ) {
        val bitmap = decodeUnpremultiplied(png)
        try {
            assertEquals(Bitmap.Config.RGBA_F16, bitmap.config)
            assertTrue(bitmap.hasAlpha())
            val buffer = ByteBuffer.allocate(bitmap.byteCount).order(ByteOrder.nativeOrder())
            bitmap.copyPixelsToBuffer(buffer)
            buffer.rewind()
            val shorts = buffer.asShortBuffer()
            val stride = bitmap.rowBytes / 2
            val expected = ShortArray(frame.width * 4)
            var worstColour = 0f
            var worstAlpha = 0f
            for (y in 0 until frame.height) {
                Dither.toSrgb16Row(frame, y, expected)
                for (x in 0 until frame.width) {
                    val alphaCode = expected[x * 4 + 3].toInt() and 0xFFFF
                    val decodedAlpha = android.util.Half.toFloat(shorts.get(y * stride + x * 4 + 3))
                    worstAlpha = max(worstAlpha, abs(decodedAlpha - alphaCode / 65535f))
                    for (c in 0..2) {
                        val decoded = android.util.Half.toFloat(shorts.get(y * stride + x * 4 + c))
                        if (alphaCode == 0) {
                            assertEquals("PNG16 ($x,$y,$c): zero alpha stores zero colour", 0f, decoded, 0f)
                        } else {
                            worstColour = max(worstColour, abs(decoded - (expected[x * 4 + c].toInt() and 0xFFFF) / 65535f))
                        }
                    }
                }
            }
            record("alphaContract.frosted.png16", "worstStraightColour=$worstColour worstAlpha=$worstAlpha")
            assertTrue("PNG16 alpha off by $worstAlpha", worstAlpha <= F16_TOLERANCE)
            assertTrue("PNG16 straight colour off by $worstColour", worstColour <= F16_TOLERANCE)
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertWebpStraight(
        frame: HalfFloatImage,
        webp: ByteArray,
    ) {
        val options = BitmapFactory.Options().apply { inPremultiplied = false }
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(webp, 0, webp.size, options))
        try {
            val expected = IntArray(frame.width)
            val actual = IntArray(frame.width)
            var worst = 0
            for (y in 0 until frame.height) {
                Dither.toStraightSrgb8Row(frame, y, expected)
                bitmap.getPixels(actual, 0, frame.width, 0, y, frame.width, 1)
                for (x in 0 until frame.width) {
                    assertEquals("WebP alpha ($x,$y)", expected[x] ushr 24, actual[x] ushr 24)
                    if (expected[x] ushr 24 == 0) {
                        assertEquals("WebP ($x,$y): zero alpha decodes as transparent black", 0, actual[x])
                        continue
                    }
                    for (shift in intArrayOf(16, 8, 0)) {
                        worst = max(worst, abs(((actual[x] ushr shift) and 0xFF) - ((expected[x] ushr shift) and 0xFF)))
                    }
                }
            }
            record("alphaContract.frosted.webpWorstStraightCode", worst)
            assertTrue("WebP straight colour drifted by $worst codes", worst <= MAX_STRAIGHT_DRIFT)
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertPng8OverBlack(
        frame: HalfFloatImage,
        png: ByteArray,
    ) {
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(png, 0, png.size))
        try {
            val expected = IntArray(frame.width)
            val actual = IntArray(frame.width)
            for (y in 0 until frame.height) {
                Dither.toSrgb8Row(frame, y, expected)
                bitmap.getPixels(actual, 0, frame.width, 0, y, frame.width, 1)
                for (x in 0 until frame.width) {
                    assertEquals("PNG8 ($x,$y) is opaque, colour over black", expected[x] or (0xFF shl 24), actual[x])
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** JPEG probes: the transparent region decodes black and frosted white decodes as its premultiplied colour. */
    private fun assertJpegOverBlack(
        format: ExportFormat,
        frame: HalfFloatImage,
        jpeg: ByteArray,
    ) {
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
        try {
            val pixels = bitmap.argbPixels()
            assertTrue("$format decodes opaque", pixels.all { it ushr 24 == 0xFF })
            val scale = frame.width / bitmap.width
            val (tx, ty) = transparentProbe(bitmap.width, bitmap.height)
            val (fx, fy) = frostedProbe(bitmap.width, bitmap.height)
            val transparent = pixels[ty * bitmap.width + tx]
            val frosted = pixels[fy * bitmap.width + fx]
            record("alphaContract.frosted.$format.probes", "transparent=%08X frosted=%08X".format(transparent, frosted))
            for ((c, shift) in intArrayOf(16, 8, 0).withIndex()) {
                assertTrue("$format transparent probe ${"%08X".format(transparent)}", ((transparent ushr shift) and 0xFF) <= 4)
                val overBlack = ColorMath.linearToSrgb(frame.at(fx * scale, fy * scale, c).coerceIn(0f, 1f)) * 255f
                assertTrue(
                    "$format frosted probe ${"%08X".format(frosted)} vs over-black ${"%.1f".format(overBlack)}",
                    abs(((frosted ushr shift) and 0xFF) - overBlack) <= 5f,
                )
            }
        } finally {
            bitmap.recycle()
        }
    }

    // ---- fixtures and helpers ----------------------------------------------------------------------------------------

    /**
     * 256 x 256 straight-alpha source: left half fully transparent; right half opaque warm colour on top and white at
     * alpha 128 below. Cover-fit to 9:16 keeps the halves on the canvas halves, so the canvas's left quarter is far
     * (about six blur sigmas) from any visible source pixel.
     */
    private fun transparentSource(): Bitmap {
        val pixels =
            IntArray(SIZE * SIZE) { i ->
                val x = i % SIZE
                val y = i / SIZE
                when {
                    x < SIZE / 2 -> 0x00000000
                    y < SIZE / 2 -> 0xFFC8783C.toInt()
                    else -> 0x80FFFFFF.toInt()
                }
            }
        return Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply { setPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE) }
    }

    /** Frosted background of the source plus the source as a layer in the top-left corner (away from both probes). */
    private fun frostedRequest(format: ExportFormat): ExportRequest {
        val (w, h) = ExportPlan.renderSize(format, ExportResolution.HD_1080)
        val layerSize = w * 0.37f
        val inset = w * 0.037f
        return ExportRequest(
            scene =
                RenderScene(
                    w,
                    h,
                    SceneBackground.FrostedImage(SOURCE_KEY),
                    listOf(SceneLayer(SOURCE_KEY, Rect4f(inset, inset, inset + layerSize, inset + layerSize), NormRect.FULL)),
                ),
            format = format,
            resolution = ExportResolution.HD_1080,
            metadata = ExportMetadata(captureEpochMillis = CAPTURE_MILLIS, description = "AkaStudio alpha QA"),
            hdrBoost = false,
        )
    }

    private fun transparentProbe(
        w: Int,
        h: Int,
    ) = w / 8 to h / 2

    private fun frostedProbe(
        w: Int,
        h: Int,
    ) = 3 * w / 4 to 3 * h / 4

    /**
     * Quarter-width bands of a [SIZE] square: 0 transparent, 1 half-transparent frosted white (premultiplied 0.375,
     * alpha 0.5), 2 opaque 0.75 grey, 3 opaque grey; the top quarter of bands 2-3 is an HDR highlight at 4.
     */
    private fun bandFrame(forceOpaque: Boolean) =
        halfFrame(SIZE, SIZE) { x, y, out ->
            val band = x * 4 / SIZE
            val v =
                when {
                    band == 0 -> 0f
                    band == 1 -> 0.375f
                    y < SIZE / 4 -> 4f
                    band == 2 -> 0.75f
                    else -> 0.4f
                }
            out[0] = v
            out[1] = v
            out[2] = v
            out[3] =
                if (forceOpaque) {
                    1f
                } else {
                    when (band) {
                        0 -> 0f
                        1 -> 0.5f
                        else -> 1f
                    }
                }
        }

    private fun bandCentre(band: Int) = band * SIZE / 4 + SIZE / 8

    private fun halfFrame(
        w: Int,
        h: Int,
        pixel: (x: Int, y: Int, out: FloatArray) -> Unit,
    ): HalfFloatImage {
        val data = ShortArray(w * h * 4)
        val px = FloatArray(4)
        for (y in 0 until h) {
            for (x in 0 until w) {
                pixel(x, y, px)
                for (c in 0..3) data[(y * w + x) * 4 + c] = Half.fromFloat(px[c])
            }
        }
        return HalfFloatImage(w, h, data)
    }

    private fun HalfFloatImage.at(
        x: Int,
        y: Int,
        c: Int,
    ): Float = Half.toFloat(rgba[(y * width + x) * 4 + c])

    /** Unpremultiplied, software-allocated [ImageDecoder] decode, so stored straight colour is read back as is. */
    private fun decodeUnpremultiplied(bytes: ByteArray): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isUnpremultipliedRequired = true
        }

    /** The negative control: [rows] through a default (premultiplied) ARGB_8888 bitmap and `compress(WEBP_LOSSLESS)`. */
    private fun premultipliedWebp(
        w: Int,
        h: Int,
        rows: Rgba8Rows,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            assertTrue("control bitmap must be premultiplied", bitmap.isPremultiplied)
            val row = IntArray(w)
            for (y in 0 until h) {
                rows.row(y, row)
                bitmap.setPixels(row, 0, w, 0, y, w, 1)
            }
            val out = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)) { "control WebP compression failed" }
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    /** Worst straight-colour code difference between [webp] decoded unpremultiplied and [rows]; alpha must match. */
    private fun worstStraightDrift(
        webp: ByteArray,
        rows: Rgba8Rows,
    ): Int {
        val options = BitmapFactory.Options().apply { inPremultiplied = false }
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(webp, 0, webp.size, options))
        try {
            val expected = IntArray(bitmap.width)
            val actual = IntArray(bitmap.width)
            var worst = 0
            for (y in 0 until bitmap.height) {
                rows.row(y, expected)
                bitmap.getPixels(actual, 0, bitmap.width, 0, y, bitmap.width, 1)
                for (x in 0 until bitmap.width) {
                    assertEquals("alpha ($x,$y)", expected[x] ushr 24, actual[x] ushr 24)
                    for (shift in intArrayOf(16, 8, 0)) {
                        worst = max(worst, abs(((actual[x] ushr shift) and 0xFF) - ((expected[x] ushr shift) and 0xFF)))
                    }
                }
            }
            return worst
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Delegates to the production renderer and keeps the last offscreen frame for comparison with the file. [inspect]
     * runs on the frame before the exporter gets it (the JPEG writers overwrite its alpha); a failure is kept in
     * [inspectFailure] for the test thread to rethrow.
     */
    private class CapturingRenderer(
        private val delegate: StoryRenderer,
    ) : StoryRenderer {
        @Volatile var last: HalfFloatImage? = null

        @Volatile var inspect: ((HalfFloatImage) -> Unit)? = null

        @Volatile var inspectFailure: Throwable? = null

        override suspend fun renderOffscreen(scene: RenderScene): HalfFloatImage =
            delegate.renderOffscreen(scene).also { frame ->
                last = frame
                try {
                    inspect?.invoke(frame)
                } catch (e: AssertionError) {
                    inspectFailure = e
                }
            }

        override suspend fun registerImage(
            key: String,
            bitmap: Bitmap,
        ) = delegate.registerImage(key, bitmap)

        override suspend fun unregisterImage(key: String) = delegate.unregisterImage(key)

        override suspend fun renderToSurface(
            scene: RenderScene,
            surface: android.view.Surface,
            surfaceWidth: Int,
            surfaceHeight: Int,
            options: PreviewOptions,
        ) = delegate.renderToSurface(scene, surface, surfaceWidth, surfaceHeight, options)

        override suspend fun renderPreviewPixels(
            scene: RenderScene,
            width: Int,
            height: Int,
        ): ImagePixels = delegate.renderPreviewPixels(scene, width, height)

        override suspend fun maxTextureSize(): Int = delegate.maxTextureSize()

        override fun close() = delegate.close()
    }

    private companion object {
        const val SIZE = 256
        const val SOURCE_KEY = "transparent-source"
        const val LOW_ALPHA_W = 64
        const val LOW_ALPHA_H = 256

        /** Row of [bandFrame] below the HDR highlight, where the base keeps the frame's colour. */
        const val BAND_Y_SDR = 192

        /** Same allowance as WebpTransparencyDeviceTest for any internal 8-bit round trip. */
        const val MAX_STRAIGHT_DRIFT = 2

        /** Premultiplied 8-bit storage at alpha 1..16 quantizes straight colour in steps of 16..255 codes. */
        const val MIN_CONTROL_DRIFT = 8

        /** Half-float storage and GL filtering slack for "colour <= alpha" in linear light. */
        const val PREMULTIPLIED_SLACK = 1e-2f

        /** One F16 step near 1 is 2^-11 (~4.9e-4); allow two plus the 16-bit code rounding. */
        const val F16_TOLERANCE = 1.2e-3f
    }
}
