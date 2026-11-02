package com.akane.akastudio.core.export.device

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
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
import com.akane.akastudio.core.export.api.ExportFormat
import com.akane.akastudio.core.export.api.ExportResolution
import com.akane.akastudio.core.export.pipeline.ExportProcessor
import com.akane.akastudio.core.export.process.GainMapMath
import com.akane.akastudio.core.export.process.Half
import com.akane.akastudio.core.export.process.LumaSharpen
import com.akane.akastudio.core.export.store.PlatformUltraHdrEncoder
import com.akane.akastudio.core.export.store.PlatformWebpLosslessEncoder
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
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

/** Opt-in natural SDR-origin inputs, real GL renderer and unchanged production export/codec path. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class NaturalComparisonArtifactsDeviceTest {
    @Test
    fun captureNaturalSocialUltraHdrAndDecodedBase() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires qaNaturalRun", args.containsKey("qaNaturalRun"))
        val run = checkNotNull(args.getString("qaNaturalRun"))
        require(run.matches(Regex("[A-Za-z0-9_-]+")))
        val productionOnly = (args.getString("qaNaturalProductionOnly") ?: "false").toBooleanStrict()
        val reuseRun = args.getString("qaNaturalReuseRun") ?: "natural_final_02"
        require(reuseRun.matches(Regex("[A-Za-z0-9_-]+")))
        val reuseDirectory = File(instrumentation.targetContext.filesDir, "akadroidqa/$reuseRun")
        if (productionOnly) check(reuseDirectory.isDirectory) { "Missing preserved controls $reuseDirectory" }
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
                val name = filename.substringBeforeLast('.')
                val input = assets.open("natural-comparisons/$filename").use { it.readBytes() }
                assertEquals("input identity", fixture.getString("sha256"), sha256(input))
                val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(input))) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB))
                }
                assertFalse("Inputs have no verified HDR provenance", bitmap.hasGainmap())
                assertEquals(Bitmap.Config.RGBA_F16, bitmap.config)
                renderer.registerImage(name, bitmap)
                try {
                    val cropWidth = (WIDTH.toFloat() / HEIGHT) / (bitmap.width.toFloat() / bitmap.height)
                    require(cropWidth <= 1f) { "This fixture protocol expects sources at least 9:16 wide" }
                    val crop = NormRect((1f - cropWidth) / 2f, 0f, (1f + cropWidth) / 2f, 1f)
                    val eligibility = JSONObject()
                    if (!productionOnly) for ((label, grade) in listOf(
                        "neutral" to GradeParams.NEUTRAL,
                        "azure-contrast1" to GradeParams(look = MasterLook.MEDITERRANEAN_AZURE, contrast = 1f),
                    )) {
                        val frame = renderer.renderOffscreen(scene(name, crop, grade))
                        val ratio = GainMapMath.ratioMax(frame, gainDownscale = ExportProcessor.GAIN_DOWNSCALE)
                        eligibility.put(label, JSONObject().put("preSharpenRatio", ratio)
                            .put("frozenARatio", NaturalBaselineA.ratioMax(frame)).put("formerOneThirdStopThreshold", 2f.pow(1f / 3f))
                            .put("wouldExport", if (ratio >= ExportProcessor.MIN_HDR_RATIO) "ULTRA_HDR_JPEG" else "SOCIAL_STORY_JPEG")
                            .put("renderSha256", hashFrame(frame)))
                    }
                    if (!productionOnly) File(directory, "$name-eligibility.json").writeText(eligibility.toString(2))
                    for ((label, grade) in listOf(
                        "typical" to GradeParams(look = MasterLook.MEDITERRANEAN_AZURE, contrast = 0.5f),
                        "stress" to GradeParams(look = MasterLook.MEDITERRANEAN_AZURE, contrast = 1f, exposureEv = 1f),
                    )) {
                        val prefix = "$name-$label"
                        val reference = if (productionOnly) JSONObject(File(reuseDirectory, "$prefix-C-settings.json").readText()) else null
                        val measured = MeasuredRenderer(renderer)
                        val settings = JSONObject()
                            .put("fixture", filename).put("inputSha256", sha256(input))
                            .put("inputProvenance", "SDR-origin, not a verified HDR capture")
                            .put("renderer", "production GlStoryRenderer; F16 hashes assert identical renders across both formats")
                            .put("resolution", "UHD_4K").put("width", WIDTH).put("height", HEIGHT)
                            .put("crop", JSONArray(listOf(crop.left, crop.top, crop.right, crop.bottom)))
                            .put("look", grade.look.name).put("lookIntensity", grade.lookIntensity)
                            .put("contrast", grade.contrast).put("exposureEv", grade.exposureEv)
                            .put("otherGradeParams", "production GradeParams defaults")
                            .put("hdrBoost", false).put("sharpen", "production LumaSharpen defaults on both formats")
                            .put("rendition", "C production ExportProcessor + platform encoder")
                            .put("quality", 95).put("productionGainDownscale", ExportProcessor.GAIN_DOWNSCALE)
                            .put("captureMode", if (productionOnly) "production-only; unchanged controls reused by exact hash" else "full protocol")
                            .put("evaluationCellSize", 4)
                            .put("numericalSampling", "one sample per8x8 block; x/y offsets1..4 cycle independently by block index to balance all16 fixed4x4 evaluation-cell phases")
                        if (reference != null) {
                            for (key in listOf("inputSha256", "look", "lookIntensity", "contrast", "exposureEv", "otherGradeParams", "hdrBoost", "sharpen", "quality", "crop", "width", "height", "resolution")) {
                                val previous = reference.get(key)
                                val current = settings.get(key)
                                if (previous is Number && current is Number) {
                                    assertEquals("Preserved control $key must match current settings", previous.toDouble(), current.toDouble(), 0.0)
                                } else {
                                    assertEquals("Preserved control $key must match current settings", previous.toString(), current.toString())
                                }
                            }
                            val reused = JSONObject().put("run", reuseRun)
                                .put("priorCSettings", evidenceReference(reuseDirectory, "$prefix-C-settings.json"))
                                .put("eligibilityControl", evidenceReference(reuseDirectory, "$name-eligibility.json"))
                                .put("eligibilityControlMeaning", "Preserved old-density probe; current preflight ratio is freshly measured with production density")
                            if (label == "stress") {
                                val frozenA = JSONObject(File(reuseDirectory, "$prefix-settings.json").readText())
                                assertEquals("Preserved frozen A shares exact prior C render", reference.getJSONObject("requested-ultra").getString("renderSha256"), frozenA.getJSONObject("requested-ultra").getString("renderSha256"))
                                val frozenJpeg = evidenceReference(reuseDirectory, "${prefix}_requested-ultra.jpg")
                                val frozenBase = evidenceReference(reuseDirectory, "${prefix}_decoded-sdr-base.png")
                                assertEquals("Preserved frozen A JPEG keeps its recorded bytes", frozenA.getJSONObject("requested-ultra").getString("sha256"), frozenJpeg.getString("sha256"))
                                assertEquals("Preserved frozen A base keeps its recorded bytes", frozenA.getString("decodedSdrBasePngSha256"), frozenBase.getString("sha256"))
                                reused.put("frozenASettings", evidenceReference(reuseDirectory, "$prefix-settings.json"))
                                    .put("frozenAJpeg", frozenJpeg).put("frozenABase", frozenBase)
                                if (name == "shinjuku") reused.put("forcedTileSettings", evidenceReference(reuseDirectory, "$prefix-tiled-C-settings.json"))
                            }
                            settings.put("reusedEvidence", reused)
                        }
                        var socialHash: String? = null
                        for ((format, suffix) in listOf(
                            ExportFormat.SOCIAL_STORY_JPEG to "social",
                            ExportFormat.ULTRA_HDR_JPEG to "requested-ultra",
                        )) {
                            measured.captureTarget = format == ExportFormat.ULTRA_HDR_JPEG
                            val sink = MemorySink()
                            val exporter = ExportProcessor(measured, sink, PlatformUltraHdrEncoder(), PlatformWebpLosslessEncoder())
                            val request = exportRequest(format, ExportResolution.UHD_4K).copy(scene = scene(name, crop, grade))
                            val result = exporter.exportBlocking(request)
                            val bytes = sink.files.single().bytes
                            if (reference != null) {
                                assertEquals("Fresh production render must exactly match preserved control", reference.getJSONObject("requested-ultra").getString("renderSha256"), measured.hash)
                                if (format == ExportFormat.SOCIAL_STORY_JPEG) assertEquals("Shared-C Social bytes stay identical across gain densities", reference.getJSONObject("social").getString("sha256"), sha256(bytes))
                            }
                            val outputPrefix = if (format == ExportFormat.ULTRA_HDR_JPEG) "$prefix-C" else prefix
                            File(directory, "${outputPrefix}_$suffix.jpg").writeBytes(bytes)
                            settings.put(suffix, JSONObject().put("actualFormat", result.format.name)
                                .put("sha256", sha256(bytes)).put("bytes", bytes.size).put("renderSha256", measured.hash))
                            if (format == ExportFormat.SOCIAL_STORY_JPEG) {
                                socialHash = measured.hash
                            } else {
                                assertEquals("Both exports must start from bit-identical production renders", socialHash, measured.hash)
                                settings.put("preSharpenRatio", measured.ratio)
                                assertEquals(measured.ratio >= ExportProcessor.MIN_HDR_RATIO, result.format == ExportFormat.ULTRA_HDR_JPEG)
                                if (label == "stress") assertEquals("stress pair needs actual Ultra HDR", ExportFormat.ULTRA_HDR_JPEG, result.format)
                                inspectAndMeasure(directory, "$prefix-C", prefix, bytes, result.format, measured.target, measured.edges, measured.cellTarget, measured.cellEdges, settings)
                                if (reference != null) {
                                    assertEquals("Decoded shared-C SDR base stays identical across gain densities", reference.getString("decodedSdrBasePngSha256"), settings.getString("decodedSdrBasePngSha256"))
                                    settings.getJSONObject("reusedEvidence").put("freshRenderMatches", true).put("freshSocialMatches", true).put("freshDecodedBaseMatches", true)
                                }
                            }
                        }
                        writeTargetSamples(directory, prefix, measured.target, measured.edges, measured.cellTarget, measured.cellEdges, settings)
                        if (label == "stress" && !productionOnly) {
                            val frame = renderer.renderOffscreen(scene(name, crop, grade))
                            assertEquals("Frozen A shares the exact production C/Social render", measured.hash, hashFrame(frame))
                            val baselineRatio = NaturalBaselineA.ratioMax(frame)
                            val candidate = NaturalBaselineA.compute(LumaSharpen.apply(frame))
                            val output = ByteArrayOutputStream()
                            PlatformUltraHdrEncoder().encode(candidate.sdr, candidate, output, 95, null)
                            val candidateBytes = output.toByteArray()
                            File(directory, "${prefix}_requested-ultra.jpg").writeBytes(candidateBytes)
                            val candidateSettings = JSONObject(settings.toString())
                                .put("rendition", "A frozen pre-C baseline counterfactual, direct PlatformUltraHdrEncoder")
                                .put("baselineEncoder", "test-only direct encoder; no production ExportProcessor eligibility decision")
                                .put("baselineSourceSha256", "2d0f85d880709556dd89bc53011ed3028dce378ba865a93409f33fc9b4caf8a1")
                                .put("preSharpenRatio", baselineRatio)
                                .put("oldOneThirdStopEligibility", baselineRatio >= 2f.pow(1f / 3f))
                                .put("requested-ultra", JSONObject().put("actualFormat", "ULTRA_HDR_JPEG").put("sha256", sha256(candidateBytes))
                                    .put("bytes", candidateBytes.size).put("renderSha256", measured.hash))
                            inspectAndMeasure(directory, prefix, prefix, candidateBytes, ExportFormat.ULTRA_HDR_JPEG, measured.target, measured.edges, measured.cellTarget, measured.cellEdges, candidateSettings)
                            File(directory, "$prefix-settings.json").writeText(candidateSettings.toString(2))
                            record("natural.$prefix-A", candidateSettings.toString())
                        }
                        File(directory, "$prefix-C-settings.json").writeText(settings.toString(2))
                        record("natural.$prefix-C", settings.toString())
                        if (!productionOnly && name == "shinjuku" && label == "stress") captureForcedTileComparison(renderer, scene(name, crop, grade), directory, prefix, settings)
                    }
                } finally {
                    renderer.unregisterImage(name)
                    bitmap.recycle()
                }
            }
        } finally {
            renderer.close()
        }
        record("natural.directory", directory.absolutePath)
    }

    private class MeasuredRenderer(private val delegate: StoryRenderer) : StoryRenderer by delegate {
        var captureTarget = false
        var hash = ""
        var ratio = 1f
        var target = FloatArray(0)
        var edges = BooleanArray(0)
        var cellTarget = FloatArray(0)
        var cellEdges = BooleanArray(0)
        override suspend fun renderOffscreen(scene: RenderScene): HalfFloatImage {
            val frame = delegate.renderOffscreen(scene)
            hash = hashFrame(frame)
            if (captureTarget) {
                ratio = GainMapMath.ratioMax(frame, gainDownscale = ExportProcessor.GAIN_DOWNSCALE)
                val sharpened = LumaSharpen.apply(frame)
                target = FloatArray((WIDTH / STRIDE) * (HEIGHT / STRIDE) * 3)
                edges = BooleanArray(target.size / 3)
                cellTarget = FloatArray(target.size)
                cellEdges = BooleanArray(edges.size)
                var i = 0
                for (by in 0 until HEIGHT / STRIDE) for (bx in 0 until WIDTH / STRIDE) {
                    val y = samplePosition(by)
                    val x = samplePosition(bx)
                    val p = (y * WIDTH + x) * 4
                    var edge = false
                    for (c in 0..2) {
                        val value = max(0f, Half.toFloat(sharpened.rgba[p + c]))
                        for (direction in 0..3) {
                            val dx = when (direction) { 0 -> -1; 1 -> 1; else -> 0 }
                            val dy = when (direction) { 2 -> -1; 3 -> 1; else -> 0 }
                            val neighbour = max(0f, Half.toFloat(sharpened.rgba[((y + dy) * WIDTH + x + dx) * 4 + c]))
                            if (abs(neighbour - value) > 0.05f) edge = true
                        }
                        target[i++] = value
                    }
                    edges[(i - 1) / 3] = edge
                    val cellX = x / 4 * 4
                    val cellY = y / 4 * 4
                    var cellEdge = false
                    val sums = DoubleArray(3)
                    for (cy in cellY until cellY + 4) for (cx in cellX until cellX + 4) for (c in 0..2) {
                        val value = max(0f, Half.toFloat(sharpened.rgba[(cy * WIDTH + cx) * 4 + c]))
                        sums[c] += value
                        for (direction in 0..3) {
                            val dx = when (direction) { 0 -> -1; 1 -> 1; else -> 0 }
                            val dy = when (direction) { 2 -> -1; 3 -> 1; else -> 0 }
                            val nx = (cx + dx).coerceIn(0, WIDTH - 1)
                            val ny = (cy + dy).coerceIn(0, HEIGHT - 1)
                            val neighbor = max(0f, Half.toFloat(sharpened.rgba[(ny * WIDTH + nx) * 4 + c]))
                            if (abs(neighbor - value) > .05f) cellEdge = true
                        }
                    }
                    for (c in 0..2) cellTarget[i - 3 + c] = (sums[c] / 16).toFloat()
                    cellEdges[(i - 1) / 3] = cellEdge
                }
            }
            return frame
        }
    }

    private fun inspectAndMeasure(directory: File, prefix: String, socialPrefix: String, bytes: ByteArray, format: ExportFormat, target: FloatArray, edges: BooleanArray, cellTarget: FloatArray, cellEdges: BooleanArray, settings: JSONObject) {
        val base = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        val social = checkNotNull(BitmapFactory.decodeFile(File(directory, "${socialPrefix}_social.jpg").absolutePath))
        try {
            assertEquals(WIDTH to HEIGHT, base.width to base.height)
            assertEquals(format == ExportFormat.ULTRA_HDR_JPEG, base.hasGainmap())
            val gain = base.gainmap
            if (gain != null) {
                val contents = gain.gainmapContents
                val raw = ByteBuffer.allocate(contents.byteCount)
                contents.copyPixelsToBuffer(raw)
                val rawFile = File(directory, "${prefix}_decoded-gain-raw.bin")
                rawFile.writeBytes(raw.array())
                fun normalize(row: IntArray) {
                    for (x in row.indices) {
                        val code = if (contents.config == Bitmap.Config.ALPHA_8) row[x] ushr 24 else (row[x] ushr 16) and 255
                        row[x] = 0xFF000000.toInt() or (code * 0x010101)
                    }
                }
                val normalized = Bitmap.createBitmap(contents.width, contents.height, Bitmap.Config.ARGB_8888)
                val originalRow = IntArray(contents.width)
                for (y in 0 until contents.height) {
                    contents.getPixels(originalRow, 0, contents.width, 0, y, contents.width, 1)
                    normalize(originalRow)
                    normalized.setPixels(originalRow, 0, contents.width, 0, y, contents.width, 1)
                }
                val gainFile = File(directory, "${prefix}_decoded-gain.png")
                try {
                    gainFile.outputStream().use { assertTrue(normalized.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { normalized.recycle() }
                val gainRoundtrip = checkNotNull(BitmapFactory.decodeFile(gainFile.absolutePath))
                try {
                    val roundtripRow = IntArray(contents.width)
                    for (y in 0 until contents.height) {
                        contents.getPixels(originalRow, 0, contents.width, 0, y, contents.width, 1)
                        normalize(originalRow)
                        gainRoundtrip.getPixels(roundtripRow, 0, contents.width, 0, y, contents.width, 1)
                        assertTrue("Normalized PNG preserves every original decoded gain code exactly in RGB with alpha255", originalRow.contentEquals(roundtripRow))
                    }
                } finally { gainRoundtrip.recycle() }
                settings.put("decodedGainPngSha256", sha256(gainFile.readBytes()))
                    .put("decodedGainPngEncoding", "opaque grayscale ARGB PNG; RGB=original decoded gain code (ALPHA_8 alpha or scalar red); raw plane retained separately")
                    .put("decodedGainRaw", JSONObject().put("file", rawFile.name).put("sha256", sha256(raw.array()))
                        .put("config", contents.config.toString()).put("rowBytes", contents.rowBytes).put("width", contents.width).put("height", contents.height))
                settings.put("gain", JSONObject().put("ratioMin", JSONArray(gain.ratioMin.toList()))
                    .put("ratioMax", JSONArray(gain.ratioMax.toList())).put("gamma", JSONArray(gain.gamma.toList()))
                    .put("epsilonSdr", JSONArray(gain.epsilonSdr.toList())).put("epsilonHdr", JSONArray(gain.epsilonHdr.toList()))
                    .put("fullDisplayRatio", gain.displayRatioForFullHdr).put("minDisplayRatio", gain.minDisplayRatioForHdrTransition)
                    .put("width", gain.gainmapContents.width).put("height", gain.gainmapContents.height)
                    .put("config", gain.gainmapContents.config.toString()))
            }
            val gainPixels = gain?.gainmapContents?.argbPixels()
            val ratioMin = gain?.ratioMin
            val ratioMax = gain?.ratioMax
            val gamma = gain?.gamma
            val epsilonSdr = gain?.epsilonSdr
            val epsilonHdr = gain?.epsilonHdr
            val logMin = FloatArray(3) { if (ratioMin != null) ln(ratioMin[it]) else 0f }
            val logSpan = FloatArray(3) { if (ratioMax != null) ln(ratioMax[it]) - logMin[it] else 0f }
            fun reconstruct(rgb: FloatArray, code: Float): FloatArray = FloatArray(3) { c ->
                val logGain = logMin[c] + code.pow(checkNotNull(gamma)[c]) * logSpan[c]
                (rgb[c] + checkNotNull(epsilonSdr)[c]) * kotlin.math.exp(logGain) - checkNotNull(epsilonHdr)[c]
            }
            val decodedSocialRgb = ErrorStats()
            val bins = List(3) { ToneStats() }
            val nearest = ErrorStats()
            val bilinear = ErrorStats()
            val interior = ErrorStats()
            val edge = ErrorStats()
            val cells = ErrorStats()
            val interiorCells = ErrorStats()
            val edgeCells = ErrorStats()
            val cellRows = IntArray(WIDTH * 4)
            val row = IntArray(WIDTH)
            val socialRow = IntArray(WIDTH)
            var i = 0
            for (by in 0 until HEIGHT / STRIDE) {
                val y = samplePosition(by)
                base.getPixels(row, 0, WIDTH, 0, y, WIDTH, 1)
                if (gain != null) base.getPixels(cellRows, 0, WIDTH, 0, y / 4 * 4, WIDTH, 4)
                social.getPixels(socialRow, 0, WIDTH, 0, y, WIDTH, 1)
                for (bx in 0 until WIDTH / STRIDE) {
                    val x = samplePosition(bx)
                    val b = linearRgb(row[x])
                    val s = linearRgb(socialRow[x])
                    decodedSocialRgb.add(s, b)
                    val sy = ColorMath.relativeLuminance(s[0], s[1], s[2]).toDouble()
                    val by = ColorMath.relativeLuminance(b[0], b[1], b[2]).toDouble()
                    val bin = when { sy < 0.18 -> 0; sy < 0.6 -> 1; else -> 2 }
                    if (sy >= 0.02) bins[bin].add(sy, by)
                    if (gain != null) {
                        val tx = (x + 0.5f) * gain.gainmapContents.width / WIDTH
                        val ty = (y + 0.5f) * gain.gainmapContents.height / HEIGHT
                        val targetRgb = target.copyOfRange(i, i + 3)
                        for ((interpolate, stats) in listOf(false to nearest, true to bilinear)) {
                            val code = gainCode(gain.gainmapContents, checkNotNull(gainPixels), tx, ty, interpolate)
                            val reconstructed = reconstruct(b, code)
                            stats.add(targetRgb, reconstructed)
                            if (interpolate) (if (edges[i / 3]) edge else interior).add(targetRgb, reconstructed)
                        }
                        val reconstructedSums = DoubleArray(3)
                        val cellX = x / 4 * 4
                        val cellY = y / 4 * 4
                        for (dy in 0..3) for (dx in 0..3) {
                            val px = cellX + dx
                            val py = cellY + dy
                            val rgb = linearRgb(cellRows[dy * WIDTH + px])
                            val code = gainCode(gain.gainmapContents, checkNotNull(gainPixels),
                                (px + .5f) * gain.gainmapContents.width / WIDTH, (py + .5f) * gain.gainmapContents.height / HEIGHT, true)
                            val reconstructed = reconstruct(rgb, code)
                            for (c in 0..2) reconstructedSums[c] += reconstructed[c]
                        }
                        val meanReconstructed = FloatArray(3) { (reconstructedSums[it] / 16).toFloat() }
                        val meanTarget = cellTarget.copyOfRange(i, i + 3)
                        cells.add(meanTarget, meanReconstructed)
                        (if (cellEdges[i / 3]) edgeCells else interiorCells).add(meanTarget, meanReconstructed)
                    }
                    i += 3
                }
            }
            settings.put("decodedBaseVsSocialRGB", decodedSocialRgb.json())
            settings.put("decodedBaseVsSocial", JSONObject().put("shadowY0.02to0.18", bins[0].json())
                .put("midtoneY0.18to0.6", bins[1].json()).put("highlightY0.6to1", bins[2].json()))
            if (gain != null) settings.put("fullGainReconstruction", JSONObject()
                .put("meaning", "Decoded JPEG base + decoded gain; analytical full-gain formula, not display rendering")
                .put("nearest", nearest.json()).put("bilinearLogGainApproximation", bilinear.json())
                .put("edgeDefinition", "Target maximum absolute channel change >.05 to any four-connected immediate neighbor")
                .put("bilinearInterior", interior.json()).put("bilinearEdges", edge.json())
                .put("selected4x4CellMeans", JSONObject().put("meaning", "True RGB means of all16 target and reconstructed pixels in each fixed4x4 evaluation cell, independent of gain-map density; bilinear gain estimator")
                    .put("all", cells.json()).put("interiorCells", interiorCells.json()).put("edgeCells", edgeCells.json())
                    .put("edgeDefinition", "Any of16 cell pixels has >.05 absolute target-channel difference to a four-connected neighbor")))
            base.gainmap = null
            val png = File(directory, "${prefix}_decoded-sdr-base.png")
            png.outputStream().use { assertTrue(base.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val roundtrip = checkNotNull(BitmapFactory.decodeFile(png.absolutePath))
            try {
                assertFalse(roundtrip.hasGainmap())
                val verifyRow = IntArray(WIDTH)
                for (y in 0 until HEIGHT) {
                    base.getPixels(row, 0, WIDTH, 0, y, WIDTH, 1)
                    roundtrip.getPixels(verifyRow, 0, WIDTH, 0, y, WIDTH, 1)
                    assertTrue("PNG preserves every decoded SDR pixel", row.contentEquals(verifyRow))
                }
            } finally { roundtrip.recycle() }
            settings.put("decodedSdrBasePngSha256", sha256(png.readBytes()))
        } finally {
            base.recycle()
            social.recycle()
        }
    }

    private fun evidenceReference(directory: File, filename: String): JSONObject {
        val file = File(directory, filename)
        check(file.isFile) { "Missing preserved evidence $file" }
        return JSONObject().put("path", "akadroidqa/${directory.name}/$filename").put("sha256", sha256(file.readBytes()))
    }

    private suspend fun captureForcedTileComparison(renderer: StoryRenderer, scene: RenderScene, directory: File, prefix: String, original: JSONObject) {
        val field = renderer.javaClass.getDeclaredField("offscreenReadbackOverrides").apply { isAccessible = true }
        val previous = field.get(renderer)
        val overrideClass = Class.forName("com.akane.akastudio.core.color.render.OffscreenReadbackOverrides")
        val encodingClass = Class.forName("com.akane.akastudio.core.color.render.ReadbackEncoding")
        val constructor = overrideClass.getDeclaredConstructor(Integer::class.java, encodingClass, Integer::class.java).apply { isAccessible = true }
        val settings = JSONObject(original.toString()).put("forcedTileLimit", 1024)
            .put("rendition", "C production real export with test-only renderer maxTileSize1024 override")
        val comparisons = JSONArray()
        val measured = MeasuredRenderer(renderer)
        var tiledSocialHash: String? = null
        try {
            field.set(renderer, constructor.newInstance(1024, null, null))
            for ((format, suffix) in listOf(ExportFormat.SOCIAL_STORY_JPEG to "social", ExportFormat.ULTRA_HDR_JPEG to "requested-ultra")) {
                measured.captureTarget = format == ExportFormat.ULTRA_HDR_JPEG
                val sink = MemorySink()
                val exporter = ExportProcessor(measured, sink, PlatformUltraHdrEncoder(), PlatformWebpLosslessEncoder())
                val result = exporter.exportBlocking(exportRequest(format, ExportResolution.UHD_4K).copy(scene = scene))
                val bytes = sink.files.single().bytes
                val filename = "${prefix}-tiled_$suffix.jpg"
                File(directory, filename).writeBytes(bytes)
                settings.put(suffix, JSONObject().put("actualFormat", result.format.name).put("sha256", sha256(bytes))
                    .put("renderSha256", measured.hash).put("bytes", bytes.size))
                val single = File(directory, if (format == ExportFormat.SOCIAL_STORY_JPEG) "${prefix}_social.jpg" else "${prefix}-C_requested-ultra.jpg")
                comparisons.put(compareDecodedFiles(single, File(directory, filename)).put("format", format.name)
                    .put("singleJpegSha256", sha256(single.readBytes())).put("tiledJpegSha256", sha256(bytes)))
                if (format == ExportFormat.SOCIAL_STORY_JPEG) tiledSocialHash = measured.hash else {
                    assertEquals("Forced-tile pair shares identical GL renders", tiledSocialHash, measured.hash)
                    assertEquals(ExportFormat.ULTRA_HDR_JPEG, result.format)
                    settings.put("preSharpenRatio", measured.ratio).put("forcedRenderSha256", measured.hash)
                    settings.put("requested-ultra", JSONObject().put("actualFormat", result.format.name).put("sha256", sha256(bytes))
                        .put("renderSha256", measured.hash).put("bytes", bytes.size))
                    inspectAndMeasure(directory, "$prefix-tiled-C", "$prefix-tiled", bytes, result.format,
                        measured.target, measured.edges, measured.cellTarget, measured.cellEdges, settings)
                    writeTargetSamples(directory, "$prefix-tiled", measured.target, measured.edges, measured.cellTarget, measured.cellEdges, settings)
                }
            }
        } finally { field.set(renderer, previous) }
        settings.put("actualEncodedFileComparison", comparisons)
        File(directory, "$prefix-tiled-C-settings.json").writeText(settings.toString(2))
        record("natural.$prefix-tiled-C", settings.toString())
    }

    private fun compareDecodedFiles(singleFile: File, tiledFile: File): JSONObject {
        val single = checkNotNull(BitmapFactory.decodeFile(singleFile.absolutePath))
        val tiled = checkNotNull(BitmapFactory.decodeFile(tiledFile.absolutePath))
        try {
            assertEquals(single.width to single.height, tiled.width to tiled.height)
            assertEquals(single.hasGainmap(), tiled.hasGainmap())
            val a = IntArray(single.width)
            val b = IntArray(single.width)
            var maximum = 0
            var sum = 0L
            var changed = 0
            for (y in 0 until single.height) {
                single.getPixels(a, 0, single.width, 0, y, single.width, 1)
                tiled.getPixels(b, 0, single.width, 0, y, single.width, 1)
                for (x in a.indices) {
                    if (a[x] != b[x]) changed++
                    for (c in 0..2) {
                        val shift = 16 - c * 8
                        val difference = abs(((a[x] ushr shift) and 255) - ((b[x] ushr shift) and 255))
                        maximum = max(maximum, difference)
                        sum += difference
                    }
                }
            }
            val json = JSONObject().put("allPixelMaxAbsoluteSrgbCodeDifference", maximum)
                .put("allPixelMeanAbsoluteSrgbCodeDifference", sum.toDouble() / (single.width.toLong() * single.height * 3))
                .put("changedPixels", changed).put("pixels", single.width * single.height)
            single.gainmap?.let { json.put("singleEncodedRatioMax", JSONArray(it.ratioMax.toList())) }
            tiled.gainmap?.let { json.put("tiledEncodedRatioMax", JSONArray(it.ratioMax.toList())) }
            return json
        } finally { single.recycle(); tiled.recycle() }
    }

    private fun writeTargetSamples(directory: File, prefix: String, target: FloatArray, edges: BooleanArray, cellTarget: FloatArray, cellEdges: BooleanArray, settings: JSONObject) {
        val targetFile = File(directory, "${prefix}_target-f32be.gz")
        DataOutputStream(BufferedOutputStream(GZIPOutputStream(targetFile.outputStream()))).use { stream ->
            for (value in target) stream.writeFloat(value)
        }
        val edgeFile = File(directory, "${prefix}_target-edges-u8.gz")
        GZIPOutputStream(edgeFile.outputStream()).use { stream -> for (edge in edges) stream.write(if (edge) 1 else 0) }
        val cellFile = File(directory, "${prefix}_target-cellmeans-f32be.gz")
        DataOutputStream(BufferedOutputStream(GZIPOutputStream(cellFile.outputStream()))).use { stream -> for (value in cellTarget) stream.writeFloat(value) }
        val cellEdgeFile = File(directory, "${prefix}_target-cell-edges-u8.gz")
        GZIPOutputStream(cellEdgeFile.outputStream()).use { stream -> for (edge in cellEdges) stream.write(if (edge) 1 else 0) }
        settings.put("targetSamples", JSONObject().put("file", targetFile.name).put("sha256", sha256(targetFile.readBytes()))
            .put("format", "gzip of big-endian float32 interleaved nonnegative linear RGB, row-major sampled block order")
            .put("samples", target.size / 3).put("blocksWide", WIDTH / STRIDE).put("blocksHigh", HEIGHT / STRIDE)
            .put("position", "coordinate(block)=block*8+1+block%4, independently for x/y")
            .put("edgesFile", edgeFile.name).put("edgesSha256", sha256(edgeFile.readBytes()))
            .put("cellMeansFile", cellFile.name).put("cellMeansSha256", sha256(cellFile.readBytes()))
            .put("cellMeanDefinition", "True nonnegative RGB mean of all16 pixels in selected cell; top-left=(sample/4)*4")
            .put("cellEdgesFile", cellEdgeFile.name).put("cellEdgesSha256", sha256(cellEdgeFile.readBytes())))
    }

    private class ToneStats {
        var count = 0
        var social = 0.0
        var base = 0.0
        var darker = 0
        fun add(s: Double, b: Double) { count++; social += s; base += b; if (b < s * 0.95) darker++ }
        fun json() = JSONObject().put("samples", count).put("meanSocialY", if (count > 0) social / count else 0.0)
            .put("meanBaseY", if (count > 0) base / count else 0.0).put("ratioOfMeanY", if (social > 0) base / social else 1.0)
            .put("fractionMoreThan5PercentDarker", if (count > 0) darker.toDouble() / count else 0.0)
    }

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
        private fun samplePosition(block: Int) = block * STRIDE + 1 + block % 4
        private fun scene(key: String, crop: NormRect, grade: GradeParams) = RenderScene(WIDTH, HEIGHT, SceneBackground.Solid(0xFF000000.toInt()),
            listOf(SceneLayer(key, Rect4f(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat()), crop, grade = grade)))
        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun hashFrame(frame: HalfFloatImage): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val row = ByteBuffer.allocate(frame.width * 8)
            for (y in 0 until frame.height) { row.clear(); for (i in 0 until frame.width * 4) row.putShort(frame.rgba[y * frame.width * 4 + i]); digest.update(row.array()) }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        private fun linearRgb(argb: Int) = FloatArray(3) { c -> ColorMath.srgbToLinear(((argb ushr (16 - c * 8)) and 255) / 255f) }
        private fun gainCode(bitmap: Bitmap, pixels: IntArray, x: Float, y: Float, bilinear: Boolean): Float {
            fun code(px: Int, py: Int): Float {
                val pixel = pixels[py.coerceIn(0, bitmap.height - 1) * bitmap.width + px.coerceIn(0, bitmap.width - 1)]
                return (if (bitmap.config == Bitmap.Config.ALPHA_8) pixel ushr 24 else (pixel ushr 16) and 255) / 255f
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
