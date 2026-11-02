package com.akane.akastudio.core.export.device

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.akane.akastudio.core.export.api.ExportFormat
import com.akane.akastudio.core.export.api.ExportResolution
import com.akane.akastudio.core.export.pipeline.ExportProcessor
import com.akane.akastudio.core.export.store.PlatformUltraHdrEncoder
import com.akane.akastudio.core.export.store.PlatformWebpLosslessEncoder
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in, paired real codec artifacts. Synthetic renderer: these are export evidence, not GPU evidence. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class ComparisonArtifactsDeviceTest {
    @Test
    fun capturePairedSocialUltraHdrAndDecodedSdrBase() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Artifact capture requires qaArtifactRun", arguments.containsKey("qaArtifactRun"))
        val run = checkNotNull(arguments.getString("qaArtifactRun"))
        require(run.matches(Regex("[A-Za-z0-9_-]+")))
        val directory = File(instrumentation.targetContext.filesDir, "akadroidqa/$run")
        check(!directory.exists()) { "Refusing to overwrite $directory" }
        check(directory.mkdirs())
        val azureFixture = SdrOnlyUltraHdrDeviceTest()
        val azureInput = azureFixture.comparisonInputJpeg()
        File(directory, "azure-input.jpg").writeBytes(azureInput)
        val fixtures = linkedMapOf(
            "mixed-highlight" to SyntheticRenderer(Paint { x, y, w, h, out ->
                val rgb = when {
                    y < h / 3 && x < w / 2 -> floatArrayOf(1.3f, 0.5f, 0.2f)
                    y < h / 3 -> floatArrayOf(4f, 4f, 4f)
                    y < 2 * h / 3 && x < w / 2 -> floatArrayOf(4f, 0f, 0f)
                    y < 2 * h / 3 -> floatArrayOf(0f, 3.222656f, 3.222656f)
                    else -> FloatArray(3) { x.toFloat() / (w - 1) }
                }
                rgb.copyInto(out)
            }),
            "hdr-gradient" to SyntheticRenderer(gradient(4f)),
            "azure-contrast1" to azureFixture.comparisonRenderer(1f, azureInput),
        )
        for ((name, renderer) in fixtures) {
            val sink = MemorySink()
            val exporter = ExportProcessor(renderer, sink, PlatformUltraHdrEncoder(), PlatformWebpLosslessEncoder())
            val settings = JSONObject()
                .put("fixture", name)
                .put("renderer", "SyntheticRenderer; no GPU grading")
                .put("grade", if (name == "azure-contrast1") "MEDITERRANEAN_AZURE intensity1 contrast1; remaining GradeParams defaults" else "neutral; fixture is linear HDR")
                .put("resolution", "HD_1080")
                .put("hdrBoost", false)
                .put("sharpen", "production defaults")
                .put("gainDownscale", ExportProcessor.GAIN_DOWNSCALE)
                .put("preSharpenRatio", com.akane.akastudio.core.export.process.GainMapMath.ratioMax(renderer.render(1080, 1920), gainDownscale = ExportProcessor.GAIN_DOWNSCALE))
            for ((format, suffix) in listOf(
                ExportFormat.SOCIAL_STORY_JPEG to "social",
                ExportFormat.ULTRA_HDR_JPEG to "ultra-hdr",
            )) {
                val result = exporter.exportBlocking(exportRequest(format, ExportResolution.HD_1080))
                assertEquals(format, result.format)
                val bytes = sink.files.last().bytes
                File(directory, "${name}_$suffix.jpg").writeBytes(bytes)
                val decoded = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                try {
                    assertEquals(1080 to 1920, decoded.width to decoded.height)
                    if (format == ExportFormat.SOCIAL_STORY_JPEG) {
                        assertFalse(decoded.hasGainmap())
                    } else {
                        assertTrue(decoded.hasGainmap())
                        val gain = checkNotNull(decoded.gainmap)
                        settings.put("gain", JSONObject()
                            .put("ratioMin", JSONArray(gain.ratioMin.toList()))
                            .put("ratioMax", JSONArray(gain.ratioMax.toList()))
                            .put("gamma", JSONArray(gain.gamma.toList()))
                            .put("epsilonSdr", JSONArray(gain.epsilonSdr.toList()))
                            .put("epsilonHdr", JSONArray(gain.epsilonHdr.toList()))
                            .put("minDisplayRatio", gain.minDisplayRatioForHdrTransition)
                            .put("fullDisplayRatio", gain.displayRatioForFullHdr)
                            .put("width", gain.gainmapContents.width)
                            .put("height", gain.gainmapContents.height))
                        // Lossless PNG of the decoded primary image: no recompression or gain application.
                        decoded.gainmap = null
                        assertFalse(decoded.hasGainmap())
                        File(directory, "${name}_sdr-base.png").outputStream().use {
                            assertTrue(decoded.compress(Bitmap.CompressFormat.PNG, 100, it))
                        }
                        File(directory, "${name}_sdr-base.png").inputStream().use {
                            val base = checkNotNull(BitmapFactory.decodeStream(it))
                            try {
                                assertFalse(base.hasGainmap())
                                assertTrue(decoded.argbPixels().contentEquals(base.argbPixels()))
                            } finally {
                                base.recycle()
                            }
                        }
                    }
                } finally {
                    decoded.recycle()
                }
            }
            File(directory, "$name-settings.json").writeText(settings.toString(2))
        }
        record("comparison.directory", directory.absolutePath)
    }
}
