package com.akane.akastudio.core.ingestion.device

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Gainmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.os.Build
import android.util.Half
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.akane.akastudio.core.ingestion.decode.DecodedPhoto
import com.akane.akastudio.core.ingestion.decode.FullResSizing
import com.akane.akastudio.core.ingestion.decode.FullResolutionDecoder
import com.akane.akastudio.core.ingestion.model.CaptureMetadata
import com.akane.akastudio.core.ingestion.model.ImageFormat
import com.akane.akastudio.core.ingestion.model.PhotoCandidate
import com.akane.akastudio.core.ingestion.model.PhotoSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow

/**
 * The full-resolution decode must hand the renderer physically linear light: an RGBA_F16 bitmap tagged
 * LINEAR_EXTENDED_SRGB whose values are the source's encoded samples run through the source's EOTF (and, for a
 * Display P3 source, the P3 → sRGB primaries matrix, unclamped).
 *
 * The fixtures (`assets/linear-decode/`, synthetic, made with pillow-heif / Pillow) are 448x128: the top row holds
 * seven flat grey patches (codes [GREYS]), the bottom row seven flat colour patches ([COLOURS]), 64x64 px each. The
 * HEIF codec's own "linear" F16 output on API 37 keeps the sRGB-encoded values while tagging them linear, which
 * washes every HEIC out by one extra sRGB OETF; these tests catch that by comparing against the analytic values.
 */
@RunWith(AndroidJUnit4::class)
class LinearDecodeDeviceTest {
    private val dir = File(targetContext.cacheDir, "qa-linear-decode").apply { mkdirs() }

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun heicDisplayP3_decodesToLinearLight_andKeepsOutOfSrgbGamut() = assertLinear("patches_p3.heic", ImageFormat.HEIC, displayP3 = true)

    @Test
    fun heicSrgbIcc_decodesToLinearLight() = assertLinear("patches_srgb.heic", ImageFormat.HEIC, displayP3 = false)

    @Test
    fun heicWithoutIcc_decodesToLinearLight() = assertLinear("patches_noicc.heic", ImageFormat.HEIC, displayP3 = false)

    @Test
    fun heic10BitDisplayP3_decodesToLinearLight() = assertLinear("patches_p3_10bit.heic", ImageFormat.HEIC, displayP3 = true)

    @Test
    fun jpegDisplayP3_decodesToLinearLight() = assertLinear("patches_p3.jpg", ImageFormat.JPEG, displayP3 = true)

    @Test
    fun pngSrgb_decodesToLinearLight() = assertLinear("patches_srgb.png", ImageFormat.PNG, displayP3 = false)

    /** A 16-bit PNG comes back from the codec at more than 8 bits (RGBA_F16), the >8-bit native path. */
    @Test
    fun png16BitSrgb_decodesToLinearLight() = assertLinear("patches_srgb_16bit.png", ImageFormat.PNG, displayP3 = false)

    /** Decode-time downscaling (setTargetSize) goes through the same conversion: half size, patches stay flat. */
    @Test
    fun heicDisplayP3_scaledDecode_decodesToLinearLight() =
        assertLinear("patches_p3.heic", ImageFormat.HEIC, displayP3 = true, maxLongEdge = WIDTH / 2)

    /**
     * Resampling must average light, not encoded values: a 1-px black/white checker shrunk by a non-integer factor
     * (448 → 345, 1.30) has a linear mean of 0.5. Averaging the sRGB-encoded codes instead gives ~0.21.
     */
    @Test
    fun checker1px_nonIntegerDownscale_averagesLinearLight() = assertCheckerMean("checker_1px.png", NON_INTEGER_EDGE)

    @Test
    fun checker2px_nonIntegerDownscale_averagesLinearLight() = assertCheckerMean("checker_2px.png", NON_INTEGER_EDGE)

    /** 2:1 is reached by the codec's own power-of-two sampling with no further resampling. */
    @Test
    fun checker2px_halfSizeDecode_averagesLinearLight() = assertCheckerMean("checker_2px.png", WIDTH / 2)

    /**
     * A 10-bit HEIC keeps steps finer than 8 bits. The fixture holds runs of patches one 10-bit code apart (512…518,
     * 256…259 and 768…770 of 1023; exact codes checked by decoding it back with libheif/libde265, `make_steps10.py`).
     *
     * Three checks. (1) Our conversion keeps what the codec delivered: each decoded patch, re-encoded with the sRGB
     * OETF (grey has the same transfer curve in Display P3), is within [TEN_BIT_CONVERSION_TOLERANCE] codes of the
     * codec's own RGBA_1010102 sample. (2) The levels stay finer than 8 bits: no step between neighbours exceeds
     * [TEN_BIT_STEP_RANGE]'s upper end, and at least [TEN_BIT_MIN_FINE_STEPS] of the 11 steps fall inside the range.
     * An 8-bit intermediate (rounded or truncated) gives only steps of 0 and ~4.01 codes, so it fails both. The
     * Samsung HEIF codec on API 37 itself merges a few neighbouring codes (3 of 11 steps are 0 there) and sits up to
     * ~2.1 codes low, which a per-step or tight per-code bound would misread as precision loss. (3) Each code is
     * within [TEN_BIT_CODE_TOLERANCE] of its value, a coarse transfer-curve sanity check only: with rounding, an 8-bit
     * intermediate misses individual codes by just 0.25–1.76.
     */
    @Test
    fun heic10Bit_keepsSubEightBitSteps() {
        val asset = "steps_p3_10bit.heic"
        val decoded = decodeAsset(asset, ImageFormat.HEIC, 3072)
        val native = ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(dir, asset))) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val bitmap = decoded.bitmap
        try {
            assumeTrue("the codec returns this 10-bit HEIC as ${native.config}, not RGBA_1010102", native.config == Bitmap.Config.RGBA_1010102)
            val pixels = F16Pixels(bitmap)
            val nativeGreen = Packed1010102Green(native)
            val report = StringBuilder("$asset ${bitmap.config} (native ${native.config}):")
            val failures = mutableListOf<String>()
            val steps = mutableListOf<Double>()
            val runs = listOf(0 to TEN_BIT_TOP.indices, 1 to 0..3, 1 to 4..6)
            for ((row, patches) in runs) {
                val codes = if (row == 0) TEN_BIT_TOP else TEN_BIT_BOTTOM
                val decodedCodes = patches.map { i ->
                    val cx = ((i + 0.5f) * PATCH).toInt()
                    val cy = ((row + 0.5f) * PATCH).toInt()
                    val radius = (PATCH / 4).toInt()
                    val got = srgbOetf(pixels.mean(cx, cy, radius)[1]) * 1023
                    val codec = nativeGreen.mean(cx, cy, radius)
                    report.append(" ${codes[i]}->codec ${fmt(codec)} ours ${fmt(got)};")
                    if (abs(got - codec) > TEN_BIT_CONVERSION_TOLERANCE) {
                        failures += "code ${codes[i]}: ours ${fmt(got)} vs codec ${fmt(codec)}"
                    }
                    if (abs(got - codes[i]) > TEN_BIT_CODE_TOLERANCE) failures += "code ${codes[i]} decoded as ${fmt(got)}"
                    got
                }
                decodedCodes.zipWithNext().forEach { (a, b) -> steps += b - a }
            }
            report.append(" steps=${steps.joinToString("/") { fmt(it) }}")
            qaLog(report.toString())
            val fineSteps = steps.count { it in TEN_BIT_STEP_RANGE }
            if (steps.any { it > TEN_BIT_STEP_RANGE.endInclusive }) failures += "a step exceeds ${TEN_BIT_STEP_RANGE.endInclusive} codes"
            if (fineSteps < TEN_BIT_MIN_FINE_STEPS) failures += "only $fineSteps of ${steps.size} steps are finer than 8 bits"
            assertEquals(Bitmap.Config.RGBA_F16, bitmap.config)
            assertEquals(linearExtendedSrgb, bitmap.colorSpace)
            assertTrue("sub-8-bit steps lost: $failures\n$report", failures.isEmpty())
        } finally {
            bitmap.recycle()
            native.recycle()
        }
    }

    /** The green channel of an RGBA_1010102 bitmap as raw 10-bit codes (R bits 0–9, G 10–19, B 20–29). */
    private class Packed1010102Green(
        bitmap: Bitmap,
    ) {
        private val stride = bitmap.rowBytes / 4
        private val data: IntArray = run {
            val buffer = ByteBuffer.allocate(bitmap.byteCount).order(ByteOrder.nativeOrder())
            bitmap.copyPixelsToBuffer(buffer)
            buffer.rewind()
            IntArray(bitmap.byteCount / 4).also { buffer.asIntBuffer().get(it) }
        }

        fun mean(
            cx: Int,
            cy: Int,
            radius: Int,
        ): Double {
            var sum = 0.0
            var n = 0
            for (y in cy - radius until cy + radius) {
                for (x in cx - radius until cx + radius) {
                    sum += (data[y * stride + x] ushr 10) and 0x3FF
                    n++
                }
            }
            return sum / n
        }
    }

    /**
     * The banded linear downscale must equal a single pass (whole native bitmap converted to linear F16 once, then
     * one filtered draw to the same target) on every row, including the rows on both sides of each band seam. The
     * fixture (`bands_300x800.png`, `make_bands.py`) changes on every row, so a duplicated, dropped or shifted row at a
     * seam would show. Bands of 8 rows put many seams in the image; the default puts them every 128 rows. Edge 600
     * is a 1.33 linear downscale with no subsampling, edge 350 a 2:1 subsample followed by 1.14.
     */
    @Test
    fun bandedDownscale_matchesSinglePass_acrossBandSeams() {
        val asset = "bands_300x800.png"
        val file = File(dir, asset)
        InstrumentationRegistry.getInstrumentation().context.assets.open("linear-decode/$asset").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val failures = mutableListOf<String>()
        for (edge in intArrayOf(600, 350)) {
            val reference = singlePassReference(file, edge)
            try {
                val referencePixels = F16Pixels(reference)
                for ((label, decoder) in listOf(
                    "band8" to FullResolutionDecoder(targetContext.contentResolver, Dispatchers.IO, 8),
                    "default" to FullResolutionDecoder(targetContext.contentResolver),
                )) {
                    val bandHeight = if (label == "band8") 8 else 128
                    val candidate = PhotoCandidate(
                        id = asset,
                        source = PhotoSource.ExtractedFile(file.absolutePath, asset, asset),
                        format = ImageFormat.PNG,
                        byteSize = file.length(),
                        widthPx = 300,
                        heightPx = 800,
                        metadata = CaptureMetadata.EMPTY,
                    )
                    val bitmap = runBlocking { decoder.decode(candidate, edge) }.bitmap
                    try {
                        assertEquals("$label edge $edge size", reference.width to reference.height, bitmap.width to bitmap.height)
                        val pixels = F16Pixels(bitmap)
                        val rowDiff = DoubleArray(bitmap.height) { y ->
                            var worst = 0.0
                            for (x in 0 until bitmap.width) {
                                for (c in 0 until 3) worst = maxOf(worst, abs(pixels.at(x, y, c) - referencePixels.at(x, y, c)).toDouble())
                            }
                            worst
                        }
                        val seams = (bandHeight until bitmap.height step bandHeight).toList()
                        val seamRows = seams.flatMap { listOf(it - 1, it) }
                        val seamWorst = seamRows.maxOf { rowDiff[it] }
                        qaLog(
                            "$asset edge=$edge $label ${bitmap.width}x${bitmap.height} seams=${seams.size} " +
                                "maxRowDiff=${"%.2e".format(rowDiff.max())} maxSeamRowDiff=${"%.2e".format(seamWorst)} " +
                                "first seams ${seams.take(3).joinToString { "$it:${"%.1e".format(rowDiff[it - 1])}/${"%.1e".format(rowDiff[it])}" }}",
                        )
                        assertTrue("$label edge $edge: expected band seams inside the image", seams.size >= 2)
                        rowDiff.forEachIndexed { y, d ->
                            if (d > BAND_SEAM_TOLERANCE) {
                                failures += "$label edge $edge row $y${if (y in seamRows) " (seam)" else ""}: ${"%.2e".format(d)}"
                            }
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
            } finally {
                reference.recycle()
            }
        }
        assertTrue("banded decode differs from the single pass: ${failures.take(20)}", failures.isEmpty())
    }

    /** The same native decode as production, converted to linear F16 in one piece and drawn once to the target. */
    private fun singlePassReference(
        file: File,
        maxLongEdge: Int,
    ): Bitmap {
        var target = 0 to 0
        val native = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            target = FullResSizing.targetSize(info.size.width, info.size.height, maxLongEdge)
            decoder.setTargetSampleSize(FullResSizing.sampleSize(info.size.width, info.size.height, target.first, target.second))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val full = Bitmap.createBitmap(native.width, native.height, Bitmap.Config.RGBA_F16, native.hasAlpha(), linearExtendedSrgb)
        Canvas(full).drawBitmap(native, 0f, 0f, null)
        native.recycle()
        val output = Bitmap.createBitmap(target.first, target.second, Bitmap.Config.RGBA_F16, false, linearExtendedSrgb)
        val scale = Matrix().apply { setScale(target.first.toFloat() / full.width, target.second.toFloat() / full.height) }
        Canvas(output).drawBitmap(full, scale, Paint(Paint.FILTER_BITMAP_FLAG))
        full.recycle()
        return output
    }

    /**
     * EXIF orientation 6 (rotate 90° clockwise) with a non-integer and a ≥2 downscale: the output is the upright
     * [FullResSizing.targetSize], the stored left half (red) ends up on top and the stored right half (blue) at the
     * bottom.
     */
    @Test
    fun rotatedJpeg_scaledDecodes_areUprightAtTheTargetSize() {
        val stored = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        Canvas(stored).apply {
            drawRect(0f, 0f, 200f, 300f, Paint().apply { color = Color.RED })
            drawRect(200f, 0f, 400f, 300f, Paint().apply { color = Color.BLUE })
        }
        val jpeg = ByteArrayOutputStream().also { stored.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
        stored.recycle()
        val file = File(dir, "rotated.jpg")
        file.writeBytes(withPlatformExif(jpeg, mapOf(ExifInterface.TAG_ORIENTATION to "6")))
        for (edge in intArrayOf(250, 150)) {
            val decoded = decode(file, ImageFormat.JPEG, edge, 300, 400)
            val bitmap = decoded.bitmap
            try {
                assertEquals("upright source size", 300 to 400, decoded.sourceWidth to decoded.sourceHeight)
                assertEquals("edge $edge output size", FullResSizing.targetSize(300, 400, edge), bitmap.width to bitmap.height)
                val pixels = F16Pixels(bitmap)
                val top = pixels.mean(bitmap.width / 2, bitmap.height / 4, bitmap.width / 8)
                val bottom = pixels.mean(bitmap.width / 2, bitmap.height * 3 / 4, bitmap.width / 8)
                qaLog(
                    "rotated.jpg edge=$edge ${bitmap.width}x${bitmap.height} " +
                        "top=${top.joinToString("/") { fmt(it) }} bottom=${bottom.joinToString("/") { fmt(it) }}",
                )
                assertTrue("edge $edge top is red: ${top.toList()}", top[0] > 0.9 && top[2] < 0.05)
                assertTrue("edge $edge bottom is blue: ${bottom.toList()}", bottom[2] > 0.9 && bottom[0] < 0.05)
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun assertCheckerMean(asset: String, maxLongEdge: Int) {
        val decoded = decodeAsset(asset, ImageFormat.PNG, maxLongEdge)
        val bitmap = decoded.bitmap
        try {
            val mean = F16Pixels(bitmap).mean(bitmap.width / 2, bitmap.height / 2, bitmap.height / 4)
            qaLog("$asset edge=$maxLongEdge ${bitmap.width}x${bitmap.height} linear mean=${mean.joinToString("/") { fmt(it) }}")
            assertEquals(FullResSizing.targetSize(WIDTH, HEIGHT, maxLongEdge), bitmap.width to bitmap.height)
            assertEquals(Bitmap.Config.RGBA_F16, bitmap.config)
            assertEquals(linearExtendedSrgb, bitmap.colorSpace)
            mean.forEachIndexed { c, value ->
                assertEquals("$asset at edge $maxLongEdge: channel $c linear mean", 0.5, value, CHECKER_TOLERANCE)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeAsset(
        asset: String,
        format: ImageFormat,
        maxLongEdge: Int,
    ): DecodedPhoto {
        val file = File(dir, asset)
        InstrumentationRegistry.getInstrumentation().context.assets.open("linear-decode/$asset").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return decode(file, format, maxLongEdge, WIDTH, HEIGHT)
    }

    private fun decode(
        file: File,
        format: ImageFormat,
        maxLongEdge: Int,
        width: Int,
        height: Int,
    ): DecodedPhoto {
        val candidate = PhotoCandidate(
            id = file.name,
            source = PhotoSource.ExtractedFile(file.absolutePath, file.name, file.name),
            format = format,
            byteSize = file.length(),
            widthPx = width,
            heightPx = height,
            metadata = CaptureMetadata.EMPTY,
        )
        return runBlocking { FullResolutionDecoder(targetContext.contentResolver).decode(candidate, maxLongEdge) }
    }

    /** The redraw into RGBA_F16 must carry the source's Ultra HDR gain map across (platform-encoded fixture). */
    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun ultraHdrJpeg_keepsGainmapThroughConversion() {
        val base = Bitmap.createBitmap(256, 192, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(180, 140, 90)) }
        val contents = Bitmap.createBitmap(128, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 200, 200)) }
        base.gainmap =
            Gainmap(contents).apply {
                setRatioMin(1f, 1f, 1f)
                setRatioMax(4f, 4f, 4f)
                displayRatioForFullHdr = 4f
            }
        val file = File(dir, "ultrahdr.jpg")
        file.outputStream().use { check(base.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        base.recycle()
        contents.recycle()
        val candidate =
            PhotoCandidate(
                id = "ultrahdr",
                source = PhotoSource.ExtractedFile(file.absolutePath, file.name, file.name),
                format = ImageFormat.JPEG,
                byteSize = file.length(),
                widthPx = 256,
                heightPx = 192,
                metadata = CaptureMetadata.EMPTY,
            )
        val decoded = runBlocking { FullResolutionDecoder(targetContext.contentResolver).decode(candidate, 3072) }
        val bitmap = decoded.bitmap
        try {
            assertEquals(Bitmap.Config.RGBA_F16, bitmap.config)
            assertEquals(linearExtendedSrgb, bitmap.colorSpace)
            assertTrue("DecodedPhoto reports the gain map", decoded.hasGainMap)
            assertTrue("converted bitmap carries the gain map", bitmap.hasGainmap())
            val measured = F16Pixels(bitmap).mean(128, 96, 32)
            val expected = intArrayOf(180, 140, 90).map { srgbEotf(it / 255.0) }
            qaLog("ultrahdr.jpg base exp=${expected.joinToString("/") { fmt(it) }} got=${measured.joinToString("/") { fmt(it) }}")
            val codes = intArrayOf(180, 140, 90)
            measured.forEachIndexed { c, value ->
                assertEquals("SDR base channel $c (in sRGB codes)", codes[c].toDouble(), srgbCode(value), GREY_CODE_TOLERANCE)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertLinear(
        asset: String,
        format: ImageFormat,
        displayP3: Boolean,
        maxLongEdge: Int = 3072,
    ) {
        val decoded = decodeAsset(asset, format, maxLongEdge)
        logNativeDecode(File(dir, asset))
        val bitmap = decoded.bitmap
        try {
            val scale = minOf(1f, maxLongEdge.toFloat() / WIDTH)
            val patch = PATCH * scale
            val pixels = F16Pixels(bitmap)
            val failures = mutableListOf<String>()
            val report =
                StringBuilder(
                    "$asset edge=$maxLongEdge ${bitmap.config} ${bitmap.colorSpace?.name} " +
                        "${bitmap.width}x${bitmap.height}:",
                )

            GREYS.forEachIndexed { i, code ->
                val expected = srgbEotf(code / 255.0)
                val measured = pixels.mean(((i + 0.5f) * patch).toInt(), (0.5f * patch).toInt(), (patch / 4).toInt())
                report.append(" grey$code exp=${fmt(expected)} got=${measured.joinToString("/") { fmt(it) }};")
                measured.forEachIndexed { c, value ->
                    val codeError = abs(srgbCode(value) - code)
                    if (codeError > GREY_CODE_TOLERANCE) {
                        failures += "grey $code channel $c: ${fmt(value)} vs ${fmt(expected)} (${fmt(codeError)} codes)"
                    }
                }
            }
            COLOURS.forEachIndexed { i, rgb ->
                val linear = DoubleArray(3) { srgbEotf(rgb[it] / 255.0) }
                val expected = if (displayP3) P3_TO_SRGB.map { row -> row.indices.sumOf { row[it] * linear[it] } } else linear.toList()
                val measured = pixels.mean(((i + 0.5f) * patch).toInt(), (1.5f * patch).toInt(), (patch / 4).toInt())
                report.append(
                    " colour${rgb.toList()} exp=${expected.joinToString("/") { fmt(it) }} " +
                        "got=${measured.joinToString("/") { fmt(it) }};",
                )
                measured.forEachIndexed { c, value ->
                    if (abs(value - expected[c]) > COLOUR_TOLERANCE) {
                        failures += "colour ${rgb.toList()} channel $c: ${fmt(value)} vs ${fmt(expected[c])}"
                    }
                }
            }
            qaLog(report.toString())

            assertEquals("config the renderer uploads as linear RGBA16F", Bitmap.Config.RGBA_F16, bitmap.config)
            assertEquals("color space tag", linearExtendedSrgb, bitmap.colorSpace)
            assertEquals("source width", WIDTH, decoded.sourceWidth)
            assertEquals("source height", HEIGHT, decoded.sourceHeight)
            assertTrue("$asset not linear light: $failures\n$report", failures.isEmpty())
            if (displayP3) {
                // Display P3 red sits outside sRGB: linear sRGB green must stay negative (extended range, not clipped).
                val red = pixels.mean((0.5f * patch).toInt(), (1.5f * patch).toInt(), (patch / 4).toInt())
                assertTrue("P3 red keeps R > 1 (got ${fmt(red[0])})", red[0] > 1.1)
                assertTrue("P3 red keeps G < 0 (got ${fmt(red[1])})", red[1] < -0.02)
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** Evidence only: what the platform codec returns with no target color space for this fixture. */
    private fun logNativeDecode(file: File) {
        val native =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        qaLog("${file.name} native decode: ${native.config} ${native.colorSpace?.name} ${native.width}x${native.height}")
        native.recycle()
    }

    /** Raw half-float RGBA readback, so extended (negative or > 1) values are seen unclamped. */
    private class F16Pixels(
        private val bitmap: Bitmap,
    ) {
        private val data: ShortArray =
            run {
                check(bitmap.config == Bitmap.Config.RGBA_F16) { "not RGBA_F16: ${bitmap.config}" }
                val buffer = ByteBuffer.allocate(bitmap.byteCount).order(ByteOrder.nativeOrder())
                bitmap.copyPixelsToBuffer(buffer)
                buffer.rewind()
                ShortArray(bitmap.byteCount / 2).also { buffer.asShortBuffer().get(it) }
            }
        private val stride = bitmap.rowBytes / 2

        /** Raw linear value of channel [c] at ([x], [y]). */
        fun at(
            x: Int,
            y: Int,
            c: Int,
        ): Float = Half.toFloat(data[y * stride + x * 4 + c])

        /** Mean linear R, G, B over the square of half-size [radius] centred on ([cx], [cy]). */
        fun mean(
            cx: Int,
            cy: Int,
            radius: Int,
        ): DoubleArray {
            val sum = DoubleArray(3)
            var n = 0
            for (y in cy - radius until cy + radius) {
                for (x in cx - radius until cx + radius) {
                    for (c in 0 until 3) sum[c] += Half.toFloat(data[y * stride + x * 4 + c]).toDouble()
                    n++
                }
            }
            return DoubleArray(3) { sum[it] / n }
        }
    }

    private companion object {
        const val WIDTH = 448
        const val HEIGHT = 128
        const val PATCH = 64f
        val GREYS = intArrayOf(16, 64, 96, 128, 160, 192, 240)
        val COLOURS =
            listOf(
                intArrayOf(255, 0, 0),
                intArrayOf(0, 255, 0),
                intArrayOf(0, 0, 255),
                intArrayOf(200, 120, 60),
                intArrayOf(60, 140, 200),
                intArrayOf(128, 128, 128),
                intArrayOf(255, 255, 255),
            )

        /**
         * Greys are compared after re-encoding the linear value with the sRGB OETF: quality-95 HEIC/JPEG on flat
         * patches stays within ~1 code, and 1.5 codes rejects a wrong transfer curve (gamma 2.2 misses grey 16 by 5).
         */
        const val GREY_CODE_TOLERANCE = 1.5

        const val NON_INTEGER_EDGE = 345
        const val CHECKER_TOLERANCE = 0.03
        const val BAND_SEAM_TOLERANCE = 1e-3
        val TEN_BIT_TOP = intArrayOf(512, 513, 514, 515, 516, 517, 518)
        val TEN_BIT_BOTTOM = intArrayOf(256, 257, 258, 259, 768, 769, 770)
        const val TEN_BIT_CODE_TOLERANCE = 3.0
        const val TEN_BIT_CONVERSION_TOLERANCE = 0.3
        val TEN_BIT_STEP_RANGE = 0.4..2.2
        const val TEN_BIT_MIN_FINE_STEPS = 7

        /** Saturated primaries also absorb the fixture profile's rounding of the P3 matrix. */
        const val COLOUR_TOLERANCE = 0.03

        /** Linear Display P3 → linear sRGB (both D65), from the published primaries. */
        val P3_TO_SRGB =
            listOf(
                doubleArrayOf(1.2249401, -0.2249404, 0.0),
                doubleArrayOf(-0.0420569, 1.0420571, 0.0),
                doubleArrayOf(-0.0196376, -0.0786361, 1.0982735),
            )

        val linearExtendedSrgb: ColorSpace = ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB)

        fun srgbEotf(v: Double): Double = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)

        fun srgbOetf(l: Double): Double = if (l <= 0.0031308) l * 12.92 else 1.055 * l.pow(1 / 2.4) - 0.055

        /** Linear value re-encoded as an 8-bit sRGB code (unrounded). */
        fun srgbCode(l: Double): Double = srgbOetf(l) * 255

        fun fmt(v: Double): String = "%.4f".format(v)
    }
}
