package com.akane.akastudio.core.export.device

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.Gainmap
import android.graphics.ImageDecoder
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.akane.akastudio.core.color.api.GradeParams
import com.akane.akastudio.core.color.api.HalfFloatImage
import com.akane.akastudio.core.color.api.MasterLook
import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.color.api.Rect4f
import com.akane.akastudio.core.color.api.RenderScene
import com.akane.akastudio.core.color.api.SceneBackground
import com.akane.akastudio.core.color.api.SceneLayer
import com.akane.akastudio.core.color.api.StoryRenderer
import com.akane.akastudio.core.color.math.ColorMath
import com.akane.akastudio.core.color.render.createStoryRenderer
import com.akane.akastudio.core.export.process.Dither
import com.akane.akastudio.core.export.process.GainMapMath
import com.akane.akastudio.core.export.process.Half
import com.akane.akastudio.core.export.process.HalfRows
import com.akane.akastudio.core.export.process.LumaSharpen
import com.akane.akastudio.core.export.store.PlatformUltraHdrEncoder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in codec experiment: three-channel gain on the exact production C base, without production changes. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class RgbGainComparisonDeviceTest {
    @Test
    fun compareEncodedRgbGainOnIdenticalCBase() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires qaRgbGainRun", args.containsKey("qaRgbGainRun"))
        val run = checkNotNull(args.getString("qaRgbGainRun"))
        val scalarDownscale = args.getString("qaScalarGainDownscale", "4").toInt()
        require(scalarDownscale in setOf(1, 2, 4))
        val allowedFixtures = setOf("astronaut.png", "coffee.png", "rocket.jpg", "shinjuku.jpg", "clouds.jpeg")
        val selectedFixtures = args.getString("qaRgbFixtures")?.split(',')?.map { it.trim() }?.toSet()
            ?: setOf("astronaut.png", "shinjuku.jpg")
        require(selectedFixtures.isNotEmpty() && selectedFixtures.all { it in allowedFixtures })
        val processedFixtures = mutableSetOf<String>()
        val scalarKey = if (scalarDownscale == 4) "scalarC" else "scalarD$scalarDownscale"
        val scalarLabel = if (scalarDownscale == 4) "scalar-C" else "scalar-D$scalarDownscale"
        require(run.matches(Regex("[A-Za-z0-9_-]+")))
        val directory = File(instrumentation.targetContext.filesDir, "akadroidqa/$run")
        check(!directory.exists()) { "Refusing to overwrite $directory" }
        check(directory.mkdirs())
        val assets = instrumentation.context.assets
        val sources = assets.open("natural-comparisons/sources.json").bufferedReader().use { it.readText() }
        File(directory, "sources.json").writeText(sources)
        val fixtures = JSONObject(sources).getJSONArray("fixtures")
        val renderer = createStoryRenderer(instrumentation.targetContext)
        try {
            for (index in 0 until fixtures.length()) {
                val fixture = fixtures.getJSONObject(index)
                val filename = fixture.getString("file")
                if (filename !in selectedFixtures) continue
                val name = filename.substringBeforeLast('.')
                val input = assets.open("natural-comparisons/$filename").use { it.readBytes() }
                assertEquals("fixture identity", fixture.getString("sha256"), sha256(input))
                val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(input))) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB))
                }
                assertFalse(bitmap.hasGainmap())
                assertEquals(Bitmap.Config.RGBA_F16, bitmap.config)
                renderer.registerImage(name, bitmap)
                try {
                    val cropWidth = (WIDTH.toFloat() / HEIGHT) / (bitmap.width.toFloat() / bitmap.height)
                    require(cropWidth <= 1f)
                    val crop = NormRect((1f - cropWidth) / 2f, 0f, (1f + cropWidth) / 2f, 1f)
                    val grade = GradeParams(look = MasterLook.MEDITERRANEAN_AZURE, contrast = 1f, exposureEv = 1f)
                    val scene = RenderScene(WIDTH, HEIGHT, SceneBackground.Solid(0xFF000000.toInt()),
                        listOf(SceneLayer(name, Rect4f(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat()), crop, grade = grade)))
                    val settings = JSONObject().put("fixture", filename).put("inputSha256", sha256(input))
                        .put("inputProvenance", "SDR-origin natural raster; no verified HDR capture provenance")
                        .put("width", WIDTH).put("height", HEIGHT).put("quality", 95)
                        .put("selectedFixtures", JSONArray(selectedFixtures.sorted()))
                        .put("scalarComparatorKey", scalarKey).put("scalarComparatorLabel", scalarLabel)
                        .put("scalarGainDownscale", scalarDownscale).put("rgbGainDownscale", 4).put("evaluationCellSize", 4)
                        .put("preSharpenRatioMeaning", "Default4 production preflight diagnostic, not dense-comparator eligibility")
                        .put("crop", JSONArray(listOf(crop.left, crop.top, crop.right, crop.bottom)))
                        .put("look", grade.look.name).put("lookIntensity", grade.lookIntensity)
                        .put("contrast", grade.contrast).put("exposureEv", grade.exposureEv)
                        .put("otherGradeParams", "production defaults").put("hdrBoost", false)
                        .put("rendition", "Test-only RGB gain on C base; not a Social-like clipped base alternative")
                        .put("baseQuantization", "Same production Dither.toSrgb8Row, opaque ARGB_8888, platform JPEG95")
                        .put("gainGrid", "RGB4 uses 4x4 box means of target and stored C half base; scalar uses requested ${scalarDownscale}x$scalarDownscale grid and production luminance math; gamma 1, eps 1/64")
                        .put("precisionConfound", "Production scalar C measures ideal float factor before half storage; RGB measures stored half base. Half rounding may contribute to marginal differences.")
                        .put("sampling", "coordinate(block)=block*8+1+block%4 independently x/y; all16 gain-cell phases")
                    val target = renderTarget(renderer, scene, settings)
                    val scalar = GainMapMath.compute(target, scalarDownscale)
                    val baseHash = hashFrame(scalar.sdr)
                    if (scalarDownscale != 4) {
                        assertEquals("$scalarLabel and default4 scalar have identical stored C base", hashFrame(GainMapMath.compute(target, 4).sdr), baseHash)
                        settings.put("scalarVsDefault4BaseF16Identical", true)
                    }
                    settings.put("storedCBaseSha256", baseHash).put("gainTargetSha256", hashFrame(target))
                    val scalarBytes = ByteArrayOutputStream().also { PlatformUltraHdrEncoder().encode(scalar.sdr, scalar, it, 95, null) }.toByteArray()
                    val scalarFile = File(directory, "$name-$scalarLabel-ultra.jpg").apply { writeBytes(scalarBytes) }
                    settings.put(scalarKey, JSONObject().put("jpegSha256", sha256(scalarBytes)).put("bytes", scalarFile.length()))
                    try {
                        val rgb = computeRgb(target, scalar.sdr)
                        settings.put("requestedRgbMetadata", rgb.metadata())
                        val rgbBytes = encodeRgb(scalar.sdr, rgb)
                        val rgbFile = File(directory, "$name-rgb-on-C-ultra.jpg").apply { writeBytes(rgbBytes) }
                        val decodedScalar = checkNotNull(BitmapFactory.decodeByteArray(scalarBytes, 0, scalarBytes.size))
                        val decodedRgb = checkNotNull(BitmapFactory.decodeByteArray(rgbBytes, 0, rgbBytes.size))
                        try {
                            assertTrue("Scalar comparator codec keeps gain", decodedScalar.hasGainmap())
                            assertEquals(WIDTH / scalarDownscale to HEIGHT / scalarDownscale,
                                checkNotNull(decodedScalar.gainmap).gainmapContents.width to checkNotNull(decodedScalar.gainmap).gainmapContents.height)
                            assertTrue("RGB codec keeps gain", decodedRgb.hasGainmap())
                            assertEquals(WIDTH to HEIGHT, decodedRgb.width to decodedRgb.height)
                            val decodedGain = checkNotNull(decodedRgb.gainmap)
                            val actual = decodedGain.gainmapContents
                            settings.put("decodedRgbMetadata", metadata(decodedGain)).put("rgbJpegSha256", sha256(rgbBytes)).put("rgbBytes", rgbFile.length())
                            assertTrue("RGB codec must retain RGB gain storage", actual.config != Bitmap.Config.ALPHA_8)
                            for (c in 0..2) {
                                assertEquals("ratioMin[$c]", rgb.min[c], decodedGain.ratioMin[c], 0.001f)
                                assertEquals("ratioMax[$c]", rgb.max[c], decodedGain.ratioMax[c], 0.001f)
                                assertEquals(1f, decodedGain.gamma[c], 0.00001f)
                                assertEquals(EPSILON, decodedGain.epsilonSdr[c], 0.00001f)
                                assertEquals(EPSILON, decodedGain.epsilonHdr[c], 0.00001f)
                            }
                            assertEquals(rgb.max.max(), decodedGain.displayRatioForFullHdr, .001f)
                            assertEquals(1f, decodedGain.minDisplayRatioForHdrTransition, .00001f)
                            val gainPixels = actual.argbPixels()
                            settings.put("decodedRgbPixelsWithDifferentChannels", gainPixels.count {
                                ((it ushr 16) and 255) != ((it ushr 8) and 255) || ((it ushr 8) and 255) != (it and 255)
                            })
                            assertIdenticalBase(decodedScalar, decodedRgb)
                            settings.put("decodedPrimaryBaseArgbIdentical", true)
                            settings.put(scalarKey, settings.getJSONObject(scalarKey).put("decodedMetadata", metadata(checkNotNull(decodedScalar.gainmap)))
                                .put("reconstruction", measure(target, decodedScalar)))
                            settings.put("rgbOnC", JSONObject().put("reconstruction", measure(target, decodedRgb)))
                            saveDecoded(directory, "$name-$scalarLabel", decodedScalar, settings)
                            saveDecoded(directory, "$name-rgb-on-C", decodedRgb, settings)
                            settings.put("codecStatus", "PASS: RGB gain and three-channel metadata survived actual JPEG encode/decode")
                        } finally { decodedScalar.recycle(); decodedRgb.recycle() }
                    } catch (failure: Throwable) {
                        settings.put("codecStatus", "FAIL: ${failure.javaClass.name}: ${failure.message}")
                        throw failure
                    } finally {
                        File(directory, "$name-settings.json").writeText(settings.toString(2))
                        record("rgbGain.$name", settings.toString())
                    }
                } finally { renderer.unregisterImage(name); bitmap.recycle() }
                processedFixtures += filename
            }
            assertEquals("Every requested fixture must be processed", selectedFixtures, processedFixtures)
        } finally { renderer.close() }
        record("rgbGain.directory", directory.absolutePath)
    }

    private suspend fun renderTarget(renderer: StoryRenderer, scene: RenderScene, settings: JSONObject): HalfFloatImage {
        val frame = renderer.renderOffscreen(scene)
        settings.put("renderSha256", hashFrame(frame)).put("preSharpenRatio", GainMapMath.ratioMax(frame))
        val sharpened = LumaSharpen.apply(frame)
        settings.put("sharpenedF16Sha256", hashFrame(sharpened))
        for (p in sharpened.rgba.indices step 4) for (c in 0..2) {
            sharpened.rgba[p + c] = Half.fromFloat(max(0f, HalfRows.finite(Half.toFloat(sharpened.rgba[p + c]))))
        }
        return sharpened
    }

    private data class RgbGain(val pixels: IntArray, val min: FloatArray, val max: FloatArray) {
        fun metadata() = JSONObject().put("ratioMin", JSONArray(min.toList())).put("ratioMax", JSONArray(max.toList()))
            .put("gamma", 1).put("epsilonSdr", EPSILON).put("epsilonHdr", EPSILON).put("fullDisplayRatio", max.max())
    }

    private fun computeRgb(target: HalfFloatImage, base: HalfFloatImage): RgbGain {
        val logs = FloatArray(GAIN_WIDTH * GAIN_HEIGHT * 3)
        val low = FloatArray(3) { Float.POSITIVE_INFINITY }
        val high = FloatArray(3)
        for (gy in 0 until GAIN_HEIGHT) for (gx in 0 until GAIN_WIDTH) {
            val sumTarget = DoubleArray(3)
            val sumBase = DoubleArray(3)
            for (dy in 0..3) for (dx in 0..3) {
                val p = ((gy * 4 + dy) * WIDTH + gx * 4 + dx) * 4
                for (c in 0..2) {
                    sumTarget[c] += Half.toFloat(target.rgba[p + c])
                    sumBase[c] += Half.toFloat(base.rgba[p + c])
                }
            }
            for (c in 0..2) {
                val value = (ln((sumTarget[c] / 16 + EPSILON) / (sumBase[c] / 16 + EPSILON)) / ln(2.0)).toFloat()
                logs[(gy * GAIN_WIDTH + gx) * 3 + c] = value
                low[c] = kotlin.math.min(low[c], value)
                high[c] = max(high[c], value)
            }
        }
        for (c in 0..2) {
            low[c] = max(0f, low[c])
            if (high[c] - low[c] < 1e-3f) low[c] = 0f
        }
        val pixels = IntArray(GAIN_WIDTH * GAIN_HEIGHT) { cell ->
            var pixel = 0xFF000000.toInt()
            for (c in 0..2) {
                val code = if (high[c] <= 1e-6f) 0 else
                    (((logs[cell * 3 + c] - low[c]) / (high[c] - low[c])).coerceIn(0f, 1f) * 255f).roundToInt()
                pixel = pixel or (code shl (16 - c * 8))
            }
            pixel
        }
        return RgbGain(pixels, FloatArray(3) { if (high[it] <= 1e-6f) 1f else 2f.pow(low[it]) },
            FloatArray(3) { if (high[it] <= 1e-6f) 1f else 2f.pow(high[it]) })
    }

    private fun encodeRgb(sdr: HalfFloatImage, gain: RgbGain): ByteArray {
        val base = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val contents = Bitmap.createBitmap(GAIN_WIDTH, GAIN_HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            val row = IntArray(WIDTH)
            for (y in 0 until HEIGHT) {
                Dither.toSrgb8Row(sdr, y, row)
                for (x in row.indices) row[x] = row[x] or (0xFF shl 24)
                base.setPixels(row, 0, WIDTH, 0, y, WIDTH, 1)
            }
            contents.setPixels(gain.pixels, 0, GAIN_WIDTH, 0, 0, GAIN_WIDTH, GAIN_HEIGHT)
            base.gainmap = Gainmap(contents).apply {
                setRatioMin(gain.min[0], gain.min[1], gain.min[2])
                setRatioMax(gain.max[0], gain.max[1], gain.max[2])
                setGamma(1f, 1f, 1f)
                setEpsilonSdr(EPSILON, EPSILON, EPSILON)
                setEpsilonHdr(EPSILON, EPSILON, EPSILON)
                setMinDisplayRatioForHdrTransition(1f)
                setDisplayRatioForFullHdr(gain.max.max())
            }
            return ByteArrayOutputStream().also { assertTrue("RGB Ultra HDR compress", base.compress(Bitmap.CompressFormat.JPEG, 95, it)) }.toByteArray()
        } finally { base.recycle(); contents.recycle() }
    }

    private fun assertIdenticalBase(a: Bitmap, b: Bitmap) {
        assertEquals(a.width to a.height, b.width to b.height)
        val ar = IntArray(WIDTH)
        val br = IntArray(WIDTH)
        for (y in 0 until HEIGHT) {
            a.getPixels(ar, 0, WIDTH, 0, y, WIDTH, 1)
            b.getPixels(br, 0, WIDTH, 0, y, WIDTH, 1)
            assertTrue("Scalar and RGB codecs must preserve identical primary SDR pixels, row $y", ar.contentEquals(br))
        }
    }

    private fun saveDecoded(directory: File, prefix: String, bitmap: Bitmap, settings: JSONObject) {
        val contents = checkNotNull(bitmap.gainmap).gainmapContents
        val raw = ByteBuffer.allocate(contents.rowBytes * contents.height)
        contents.copyPixelsToBuffer(raw)
        val rawFile = File(directory, "$prefix-decoded-gain-raw.bin").apply { writeBytes(raw.array()) }
        val gainFile = File(directory, "$prefix-decoded-gain.png")
        val gainPng = if (contents.config == Bitmap.Config.ALPHA_8) {
            val codes = contents.argbPixels()
            val grayscale = IntArray(codes.size) {
                val code = codes[it] ushr 24
                0xFF000000.toInt() or (code shl 16) or (code shl 8) or code
            }
            Bitmap.createBitmap(contents.width, contents.height, Bitmap.Config.ARGB_8888).apply {
                setPixels(grayscale, 0, width, 0, 0, width, height)
                val actual = argbPixels()
                for (i in codes.indices) {
                    val code = codes[i] ushr 24
                    assertEquals("Scalar gain adapter preserves red code", code, (actual[i] ushr 16) and 255)
                    assertEquals("Scalar gain adapter preserves green code", code, (actual[i] ushr 8) and 255)
                    assertEquals("Scalar gain adapter preserves blue code", code, actual[i] and 255)
                }
            }
        } else contents
        try {
            gainFile.outputStream().use { assertTrue(gainPng.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            assertPngRoundtrip(gainPng, gainFile)
        } finally { if (gainPng !== contents) gainPng.recycle() }
        bitmap.gainmap = null
        val baseFile = File(directory, "$prefix-decoded-sdr-base.png")
        baseFile.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertPngRoundtrip(bitmap, baseFile)
        settings.put("$prefix-artifacts", JSONObject().put("decodedGainSha256", sha256(gainFile.readBytes()))
            .put("decodedSdrBaseSha256", sha256(baseFile.readBytes()))
            .put("decodedGainRawSha256", sha256(rawFile.readBytes())).put("rawGainFile", rawFile.name)
            .put("rawGainConfig", contents.config.toString()).put("rawGainRowBytes", contents.rowBytes)
            .put("rawGainWidth", contents.width).put("rawGainHeight", contents.height)
            .put("gainPngRepresentation", if (contents.config == Bitmap.Config.ALPHA_8)
                "Original alpha gain codes replicated to opaque R/G/B, exact per-code adapter and PNG roundtrip asserted"
                else "Original RGB gain planes, exact PNG roundtrip asserted"))
    }

    private fun assertPngRoundtrip(original: Bitmap, file: File) {
        val decoded = checkNotNull(BitmapFactory.decodeFile(file.absolutePath))
        try {
            assertFalse("Saved PNG must not carry gain metadata", decoded.hasGainmap())
            assertEquals(original.width to original.height, decoded.width to decoded.height)
            val a = IntArray(original.width)
            val b = IntArray(original.width)
            for (y in 0 until original.height) {
                original.getPixels(a, 0, original.width, 0, y, original.width, 1)
                decoded.getPixels(b, 0, original.width, 0, y, original.width, 1)
                assertTrue("PNG preserves decoded pixels: ${file.name}, row $y", a.contentEquals(b))
            }
        } finally { decoded.recycle() }
    }

    private fun metadata(gain: Gainmap) = JSONObject().put("ratioMin", JSONArray(gain.ratioMin.toList()))
        .put("ratioMax", JSONArray(gain.ratioMax.toList())).put("gamma", JSONArray(gain.gamma.toList()))
        .put("epsilonSdr", JSONArray(gain.epsilonSdr.toList())).put("epsilonHdr", JSONArray(gain.epsilonHdr.toList()))
        .put("fullDisplayRatio", gain.displayRatioForFullHdr).put("minDisplayRatio", gain.minDisplayRatioForHdrTransition)
        .put("gainConfig", gain.gainmapContents.config.toString()).put("width", gain.gainmapContents.width).put("height", gain.gainmapContents.height)

    private fun measure(target: HalfFloatImage, base: Bitmap): JSONObject {
        val gain = DecodedGain(checkNotNull(base.gainmap))
        val pointStats = List(2) { List(4) { ErrorStats() } }
        val cellStats = List(2) { List(4) { ErrorStats() } }
        val rows = IntArray(WIDTH * 4)
        for (by in 0 until HEIGHT / STRIDE) {
            val y = samplePosition(by)
            val cellY = y / 4 * 4
            base.getPixels(rows, 0, WIDTH, 0, cellY, WIDTH, 4)
            for (bx in 0 until WIDTH / STRIDE) {
                val x = samplePosition(bx)
                val cellX = x / 4 * 4
                val t = targetRgb(target, x, y)
                val edge = isEdge(target, x, y)
                val cellTarget = FloatArray(3)
                var cellEdge = false
                for (dy in 0..3) for (dx in 0..3) {
                    val ct = targetRgb(target, cellX + dx, cellY + dy)
                    for (c in 0..2) cellTarget[c] += ct[c] / 16
                    cellEdge = cellEdge || isEdge(target, cellX + dx, cellY + dy)
                }
                for (mode in 0..1) {
                    val point = reconstruct(linearRgb(rows[(y % 4) * WIDTH + x]), gain, x, y, mode == 1)
                    addSubsets(pointStats[mode], t, point, edge)
                    val cell = FloatArray(3)
                    for (dy in 0..3) for (dx in 0..3) {
                        val r = reconstruct(linearRgb(rows[dy * WIDTH + cellX + dx]), gain, cellX + dx, cellY + dy, mode == 1)
                        for (c in 0..2) cell[c] += r[c] / 16
                    }
                    addSubsets(cellStats[mode], cellTarget, cell, cellEdge)
                }
            }
        }
        fun subsets(stats: List<ErrorStats>) = JSONObject().put("all", stats[0].json()).put("nearBlackYbelow0.05", stats[1].json())
            .put("edges", stats[2].json()).put("interior", stats[3].json())
        val result = JSONObject().put("meaning", "Decoded JPEG primary base plus decoded gain, analytical full-gain estimator; not physical display rendering or interpolation truth")
            .put("edgeDefinition", "Any channel differs >0.05 from a four-connected target neighbor; cell edges test all16pixels")
        for (mode in 0..1) result.put(if (mode == 0) "nearest" else "bilinearNormalizedCodeApproximation",
            JSONObject().put("points", subsets(pointStats[mode])).put("selected4x4CellMeans", subsets(cellStats[mode])))
        return result
    }

    private fun addSubsets(stats: List<ErrorStats>, target: FloatArray, result: FloatArray, edge: Boolean) {
        stats[0].add(target, result)
        if (ColorMath.relativeLuminance(target[0], target[1], target[2]) < .05f) stats[1].add(target, result)
        stats[if (edge) 2 else 3].add(target, result)
    }

    private class DecodedGain(gain: Gainmap) {
        val contents = gain.gainmapContents
        val pixels = IntArray(contents.width * contents.height).also { contents.getPixels(it, 0, contents.width, 0, 0, contents.width, contents.height) }
        val gamma = gain.gamma
        val epsilonSdr = gain.epsilonSdr
        val epsilonHdr = gain.epsilonHdr
        val logMin = gain.ratioMin.map { ln(it) }.toFloatArray()
        val logSpan = gain.ratioMax.mapIndexed { c, value -> ln(value) - logMin[c] }.toFloatArray()
    }

    private fun reconstruct(base: FloatArray, gain: DecodedGain, x: Int, y: Int, bilinear: Boolean): FloatArray {
        val contents = gain.contents
        val tx = (x + .5f) * contents.width / WIDTH
        val ty = (y + .5f) * contents.height / HEIGHT
        return FloatArray(3) { c ->
            val code = gainCode(contents, gain.pixels, tx, ty, bilinear, c)
            val log = gain.logMin[c] + code.pow(gain.gamma[c]) * gain.logSpan[c]
            (base[c] + gain.epsilonSdr[c]) * kotlin.math.exp(log) - gain.epsilonHdr[c]
        }
    }

    private fun targetRgb(target: HalfFloatImage, x: Int, y: Int) = FloatArray(3) { Half.toFloat(target.rgba[(y * WIDTH + x) * 4 + it]) }
    private fun isEdge(target: HalfFloatImage, x: Int, y: Int): Boolean {
        val p = (y * WIDTH + x) * 4
        for ((dx, dy) in NEIGHBORS) {
            val q = ((y + dy).coerceIn(0, HEIGHT - 1) * WIDTH + (x + dx).coerceIn(0, WIDTH - 1)) * 4
            for (c in 0..2) if (abs(Half.toFloat(target.rgba[p + c]) - Half.toFloat(target.rgba[q + c])) > .05f) return true
        }
        return false
    }

    private fun Bitmap.argbPixels() = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

    private class ErrorStats {
        var count = 0
        var absSum = 0.0
        var squareSum = 0.0
        var maxAbs = 0.0
        var yCount = 0
        var yRelativeSum = 0.0
        var yRelativeMax = 0.0
        var signedYSum = 0.0
        var signedRelativeYSum = 0.0
        var yAbsSum = 0.0
        var yAbsMax = 0.0
        var floorRelativeYSum = 0.0
        var floorRelativeYMax = 0.0
        var pixels = 0
        private val channelErrors = FloatArray(WIDTH / STRIDE * HEIGHT / STRIDE * 3)
        private val absoluteYErrors = FloatArray(WIDTH / STRIDE * HEIGHT / STRIDE)
        private val relativeYErrors = FloatArray(WIDTH / STRIDE * HEIGHT / STRIDE)
        private val floorRelativeYErrors = FloatArray(WIDTH / STRIDE * HEIGHT / STRIDE)
        fun add(t: FloatArray, r: FloatArray) {
            for (c in 0..2) { val d = abs(t[c] - r[c]).toDouble(); channelErrors[count++] = d.toFloat(); absSum += d; squareSum += d * d; maxAbs = max(maxAbs, d) }
            val ty = ColorMath.relativeLuminance(t[0], t[1], t[2])
            val ry = ColorMath.relativeLuminance(r[0], r[1], r[2])
            signedYSum += ry - ty
            val absoluteY = abs(ry - ty)
            absoluteYErrors[pixels] = absoluteY
            val floorRelative = absoluteY / max(ty, .05f)
            floorRelativeYErrors[pixels++] = floorRelative
            floorRelativeYSum += floorRelative
            floorRelativeYMax = max(floorRelativeYMax, floorRelative.toDouble())
            yAbsSum += absoluteY
            yAbsMax = max(yAbsMax, absoluteY.toDouble())
            if (ty >= 0.05f) { signedRelativeYSum += (ry - ty) / ty; val d = absoluteY / ty; relativeYErrors[yCount++] = d; yRelativeSum += d; yRelativeMax = max(yRelativeMax, d.toDouble()) }
        }
        fun json(): JSONObject {
            val channels = channelErrors.copyOf(count).apply { sort() }
            val yAbsolute = absoluteYErrors.copyOf(pixels).apply { sort() }
            val yRelative = relativeYErrors.copyOf(yCount).apply { sort() }
            val yFloorRelative = floorRelativeYErrors.copyOf(pixels).apply { sort() }
            fun quantile(sorted: FloatArray, p: Double) = if (sorted.isEmpty()) 0f else sorted[(ceil(sorted.size * p).toInt() - 1).coerceAtLeast(0)]
            return JSONObject().put("channelSamples", count).put("meanAbsoluteLinearChannelError", if (count > 0) absSum / count else 0.0)
                .put("rmseLinearChannel", if (count > 0) sqrt(squareSum / count) else 0.0).put("maxAbsoluteLinearChannelError", maxAbs)
                .put("p95AbsoluteLinearChannelError", quantile(channels, .95)).put("p99AbsoluteLinearChannelError", quantile(channels, .99))
                .put("meanSignedLinearLuminanceBias", if (pixels > 0) signedYSum / pixels else 0.0)
                .put("meanSignedRelativeLuminanceBiasYatLeast0.05", if (yCount > 0) signedRelativeYSum / yCount else 0.0)
                .put("meanAbsoluteLinearLuminanceError", if (pixels > 0) yAbsSum / pixels else 0.0).put("maxAbsoluteLinearLuminanceError", yAbsMax)
                .put("p95AbsoluteLinearLuminanceError", quantile(yAbsolute, .95)).put("p99AbsoluteLinearLuminanceError", quantile(yAbsolute, .99))
                .put("luminanceSamplesYatLeast0.05", yCount).put("meanRelativeLuminanceError", if (yCount > 0) yRelativeSum / yCount else 0.0)
                .put("maxRelativeLuminanceError", yRelativeMax).put("p95RelativeLuminanceError", quantile(yRelative, .95))
                .put("p99RelativeLuminanceError", quantile(yRelative, .99))
                .put("relativeYDenominatorFloor", .05).put("meanRelativeLuminanceErrorWithFloor", if (pixels > 0) floorRelativeYSum / pixels else 0.0)
                .put("maxRelativeLuminanceErrorWithFloor", floorRelativeYMax)
                .put("p95RelativeLuminanceErrorWithFloor", quantile(yFloorRelative, .95)).put("p99RelativeLuminanceErrorWithFloor", quantile(yFloorRelative, .99))
        }
    }

    companion object {
        private const val WIDTH = 2160
        private const val HEIGHT = 3840
        private const val STRIDE = 8
        private const val EPSILON = 1f / 64f
        private const val GAIN_WIDTH = WIDTH / 4
        private const val GAIN_HEIGHT = HEIGHT / 4
        private val SRGB_TO_LINEAR = FloatArray(256) { ColorMath.srgbToLinear(it / 255f) }
        private val NEIGHBORS = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
        private fun samplePosition(block: Int) = block * STRIDE + 1 + block % 4
        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun hashFrame(frame: HalfFloatImage): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val row = ByteBuffer.allocate(frame.width * 8)
            for (y in 0 until frame.height) { row.clear(); for (i in 0 until frame.width * 4) row.putShort(frame.rgba[y * frame.width * 4 + i]); digest.update(row.array()) }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        private fun linearRgb(argb: Int) = FloatArray(3) { c -> SRGB_TO_LINEAR[(argb ushr (16 - c * 8)) and 255] }
        private fun gainCode(bitmap: Bitmap, pixels: IntArray, x: Float, y: Float, bilinear: Boolean, channel: Int): Float {
            fun code(px: Int, py: Int): Float {
                val pixel = pixels[py.coerceIn(0, bitmap.height - 1) * bitmap.width + px.coerceIn(0, bitmap.width - 1)]
                return (if (bitmap.config == Bitmap.Config.ALPHA_8) pixel ushr 24 else (pixel ushr (16 - channel * 8)) and 255) / 255f
            }
            if (!bilinear) return code(x.toInt(), y.toInt())
            val sx = x - 0.5f
            val sy = y - 0.5f
            val x0 = floor(sx).toInt()
            val y0 = floor(sy).toInt()
            val fx = sx - x0
            val fy = sy - y0
            return (code(x0, y0) * (1 - fx) + code(x0 + 1, y0) * fx) * (1 - fy) +
                (code(x0, y0 + 1) * (1 - fx) + code(x0 + 1, y0 + 1) * fx) * fy
        }
    }
}
