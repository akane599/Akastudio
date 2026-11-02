package com.akane.akastudio.core.export.device

import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.akane.akastudio.core.color.api.HalfFloatImage
import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.color.api.Rect4f
import com.akane.akastudio.core.color.api.RenderScene
import com.akane.akastudio.core.color.api.SceneBackground
import com.akane.akastudio.core.color.api.SceneLayer
import com.akane.akastudio.core.color.api.StoryRenderer
import com.akane.akastudio.core.color.render.createStoryRenderer
import com.akane.akastudio.core.export.api.ExportFormat
import com.akane.akastudio.core.export.api.ExportResolution
import com.akane.akastudio.core.export.pipeline.ExportProcessor
import com.akane.akastudio.core.export.process.GainMapMath
import com.akane.akastudio.core.export.process.Half
import com.akane.akastudio.core.export.store.PlatformUltraHdrEncoder
import com.akane.akastudio.core.export.store.PlatformWebpLosslessEncoder
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.ln
import kotlin.math.pow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in actual codec boundary outputs from controlled exposures of a real natural-photo GL frame. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class NaturalFormatBoundaryDeviceTest {
    @Test
    fun captureIdentityFloorAndFormerOneThirdStopBoundary() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires qaNaturalBoundaryRun", args.containsKey("qaNaturalBoundaryRun"))
        val run = checkNotNull(args.getString("qaNaturalBoundaryRun"))
        require(run.matches(Regex("[A-Za-z0-9_-]+")))
        val directory = File(instrumentation.targetContext.filesDir, "akadroidqa/$run")
        check(!directory.exists()) { "Refusing to overwrite $directory" }
        check(directory.mkdirs())
        val sources = instrumentation.context.assets.open("natural-comparisons/sources.json").bufferedReader().use { it.readText() }
        File(directory, "sources.json").writeText(sources)
        val manifest = JSONObject(sources).getJSONArray("fixtures")
        val renderer = createStoryRenderer(instrumentation.targetContext)
        val measurements = JSONArray()
        try {
            for (filename in listOf("astronaut.png", "clouds.jpeg")) {
                val input = instrumentation.context.assets.open("natural-comparisons/$filename").use { it.readBytes() }
                val fixture = (0 until manifest.length()).map { manifest.getJSONObject(it) }.single { it.getString("file") == filename }
                assertEquals(fixture.getString("sha256"), sha256(input))
                val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(input))) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB))
                }
                assertFalse(bitmap.hasGainmap())
                renderer.registerImage(filename, bitmap)
                try {
                    val cropWidth = (2160f / 3840) / (bitmap.width.toFloat() / bitmap.height)
                    val scene = RenderScene(2160, 3840, SceneBackground.Solid(0xFF000000.toInt()), listOf(
                        SceneLayer(filename, Rect4f(0f, 0f, 2160f, 3840f), NormRect((1 - cropWidth) / 2, 0f, (1 + cropWidth) / 2, 1f)),
                    ))
                    val natural = renderer.renderOffscreen(scene)
                    fun scaled(ev: Float): HalfFloatImage {
                        val multiplier = 2f.pow(ev)
                        val rgba = natural.rgba.copyOf()
                        for (p in rgba.indices step 4) for (c in 0..2) rgba[p + c] = Half.fromFloat(Half.toFloat(rgba[p + c]) * multiplier)
                        return HalfFloatImage(2160, 3840, rgba)
                    }
                    fun ratioAt(ev: Float) = GainMapMath.ratioMax(scaled(ev), gainDownscale = ExportProcessor.GAIN_DOWNSCALE)
                    fun bracket(target: Float): Pair<Float, Float> {
                        var low = -2f
                        var high = 4f
                        assertTrue(ratioAt(low) < target)
                        assertTrue(ratioAt(high) >= target)
                        repeat(12) {
                            val mid = (low + high) / 2
                            if (ratioAt(mid) >= target) high = mid else low = mid
                        }
                        return low to high
                    }
                    // ratioMax deliberately snaps <=.01 stop to unity, so no .005-stop nonunit result exists.
                    val floorBracket = bracket(ExportProcessor.MIN_HDR_RATIO)
                    val cases = listOf(
                        "below-identity-floor" to (floorBracket.first - .002f),
                        "above-identity-floor" to (floorBracket.second + .002f),
                        "below-former-one-third" to bracket(2f.pow(1f / 3f - .01f)).second,
                        "above-former-one-third" to bracket(2f.pow(1f / 3f + .01f)).second,
                    )
                    for ((label, ev) in cases) {
                        val frame = scaled(ev)
                        val ratio = GainMapMath.ratioMax(frame, gainDownscale = ExportProcessor.GAIN_DOWNSCALE)
                        if (label == "below-former-one-third") assertTrue(ratio < 2f.pow(1f / 3f))
                        if (label == "above-former-one-third") assertTrue(ratio > 2f.pow(1f / 3f))
                        val frozen = object : StoryRenderer by renderer {
                            override suspend fun renderOffscreen(scene: RenderScene) = frame
                        }
                        val name = "${filename.substringBeforeLast('.')}-${label}"
                        val socialSink = MemorySink()
                        val socialExporter = ExportProcessor(frozen, socialSink, PlatformUltraHdrEncoder(), PlatformWebpLosslessEncoder())
                        val socialResult = socialExporter.exportBlocking(exportRequest(ExportFormat.SOCIAL_STORY_JPEG, ExportResolution.UHD_4K).copy(scene = scene))
                        assertEquals(ExportFormat.SOCIAL_STORY_JPEG, socialResult.format)
                        val socialBytes = socialSink.files.single().bytes
                        File(directory, "$name-social.jpg").writeBytes(socialBytes)
                        val sink = MemorySink()
                        val exporter = ExportProcessor(frozen, sink, PlatformUltraHdrEncoder(), PlatformWebpLosslessEncoder())
                        val result = exporter.exportBlocking(exportRequest(ExportFormat.ULTRA_HDR_JPEG, ExportResolution.UHD_4K).copy(scene = scene))
                        val bytes = sink.files.single().bytes
                        File(directory, "$name-requested-ultra.jpg").writeBytes(bytes)
                        val bitmapOut = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                        try {
                            val expected = if (label == "below-identity-floor") ExportFormat.SOCIAL_STORY_JPEG else ExportFormat.ULTRA_HDR_JPEG
                            assertEquals("$filename/$label follows the explicit request policy", expected, result.format)
                            assertEquals(expected == ExportFormat.ULTRA_HDR_JPEG, bitmapOut.hasGainmap())
                            if (expected == ExportFormat.SOCIAL_STORY_JPEG) assertTrue("Below-floor fallback is byte-identical to explicit same-frame Social", bytes.contentEquals(socialBytes))
                            assertEquals(if (expected == ExportFormat.ULTRA_HDR_JPEG) "4:2:0" else "4:4:4", sampling(bytes))
                            val metadata = JSONObject().put("fixture", filename).put("case", label).put("inputSha256", sha256(input))
                                .put("inputProvenance", "SDR-origin natural image, not a verified HDR capture")
                                .put("exposureProtocol", "Linear multiplier 2^EV applied after one real neutral GPU render; not an app GradeParams exposure test")
                                .put("postRenderExposureEv", ev).put("preSharpenRatio", ratio).put("preSharpenStops", ln(ratio) / ln(2f))
                                .put("currentExplicitRatioThreshold", ExportProcessor.MIN_HDR_RATIO).put("formerRatioThreshold", 2f.pow(1f / 3f))
                                .put("actualFormat", result.format.name).put("hasGainmap", bitmapOut.hasGainmap()).put("baseSampling", sampling(bytes))
                                .put("width", bitmapOut.width).put("height", bitmapOut.height).put("jpegSha256", sha256(bytes))
                                .put("socialJpegSha256", sha256(socialBytes)).put("continuousReference", "Actual production Social on the same UHD F16 frame; C base matches, codec chroma may differ")
                            val socialBitmap = checkNotNull(BitmapFactory.decodeByteArray(socialBytes, 0, socialBytes.size))
                            try { metadata.put("decodedPrimaryVsSameFrameSocial", pixelDifference(bitmapOut, socialBitmap)) } finally { socialBitmap.recycle() }
                            bitmapOut.gainmap?.let { gain -> metadata.put("encodedRatioMax", JSONArray(gain.ratioMax.toList())) }
                            bitmapOut.gainmap = null
                            val base = File(directory, "$name-decoded-sdr-base.png")
                            base.outputStream().use { assertTrue(bitmapOut.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
                            metadata.put("decodedSdrBaseSha256", sha256(base.readBytes()))
                            measurements.put(metadata)
                            File(directory, "$name-settings.json").writeText(metadata.toString(2))
                            record("natural.boundary.$name", metadata.toString())
                        } finally { bitmapOut.recycle() }
                    }
                } finally {
                    renderer.unregisterImage(filename)
                    bitmap.recycle()
                }
            }
        } finally { renderer.close() }
        File(directory, "measurements.json").writeText(measurements.toString(2))
        record("natural.boundary.directory", directory.absolutePath)
    }

    private fun pixelDifference(first: android.graphics.Bitmap, second: android.graphics.Bitmap): JSONObject {
        assertEquals(first.width to first.height, second.width to second.height)
        val a = IntArray(first.width)
        val b = IntArray(first.width)
        var maximum = 0
        var sum = 0L
        for (y in 0 until first.height) {
            first.getPixels(a, 0, first.width, 0, y, first.width, 1)
            second.getPixels(b, 0, first.width, 0, y, first.width, 1)
            for (x in a.indices) for (c in 0..2) {
                val shift = 16 - c * 8
                val d = kotlin.math.abs(((a[x] ushr shift) and 255) - ((b[x] ushr shift) and 255))
                maximum = kotlin.math.max(maximum, d)
                sum += d
            }
        }
        return JSONObject().put("allPixelMaxAbsoluteSrgbCodeDifference", maximum)
            .put("allPixelMeanAbsoluteSrgbCodeDifference", sum.toDouble() / (first.width.toLong() * first.height * 3))
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun sampling(bytes: ByteArray): String {
        var offset = 2
        while (offset + 4 < bytes.size) {
            val marker = bytes[offset + 1].toInt() and 255
            val length = ((bytes[offset + 2].toInt() and 255) shl 8) or (bytes[offset + 3].toInt() and 255)
            if (marker in 0xC0..0xC2) return when (bytes[offset + 11].toInt() and 255) { 0x11 -> "4:4:4"; 0x22 -> "4:2:0"; else -> "other" }
            offset += length + 2
        }
        error("No JPEG SOF")
    }
}
