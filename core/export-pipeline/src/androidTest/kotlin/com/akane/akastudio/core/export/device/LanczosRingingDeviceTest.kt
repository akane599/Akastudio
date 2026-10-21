package com.akane.akastudio.core.export.device

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akane.akastudio.core.color.api.HalfFloatImage
import com.akane.akastudio.core.color.api.OkLab
import com.akane.akastudio.core.color.math.ColorMath
import com.akane.akastudio.core.export.api.ExportFormat
import com.akane.akastudio.core.export.api.ExportResolution
import com.akane.akastudio.core.export.pipeline.ExportProcessor
import com.akane.akastudio.core.export.process.Half
import com.akane.akastudio.core.export.process.Lanczos3
import com.akane.akastudio.core.export.process.LumaSharpen
import com.akane.akastudio.core.export.store.PlatformWebpLosslessEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ringing of the 2160x3840 -> 1080x1920 Social Story downsample on a neutral step edge.
 *
 * The documented bound (Lanczos3Test.sharpEdgeStaysMonotoneWithLimitedOvershoot) is an overshoot and undershoot
 * under 10% of the step height when downscaling. The Social Story export then runs LumaSharpen, whose documented halo
 * clamp lets OKLab lightness exceed its 3x3 neighbourhood range by HALO_MARGIN (0.02), so the exported file's bound is
 * the Lanczos bound widened by that margin ([SHARPENED_OVERSHOOT_BOUND], [SHARPENED_UNDERSHOOT_BOUND]). The step runs
 * from 0.05 to 0.5 linear, so ringing on both sides stays inside [0, 1] and survives the 8-bit quantizer's clamp.
 */
@RunWith(AndroidJUnit4::class)
class LanczosRingingDeviceTest {

    @Test
    fun fourKStepDownsampledByLanczosStaysWithinTheDocumentedBound() {
        val source = SyntheticRenderer(step(LOW, HIGH, EDGE_X)).render(2160, 3840)
        val out = Lanczos3.resample(source, 1080, 1920)
        val ringing = ringing(middleRow(out))
        record("lanczos.direct", ringing)
        assertTrue("direct Lanczos overshoot $ringing", ringing.overshoot < DOCUMENTED_BOUND)
        assertTrue("direct Lanczos undershoot $ringing", ringing.undershoot < DOCUMENTED_BOUND)
        assertTrue("direct Lanczos has no transition: $ringing", ringing.high - ringing.low > 0.9f * (HIGH - LOW))

        // The export's next stage, measured before quantization and JPEG.
        val sharpened = ringing(middleRow(LumaSharpen.apply(out)))
        record("lanczos.thenSharpen", "$sharpened bounds=$SHARPENED_OVERSHOOT_BOUND/$SHARPENED_UNDERSHOOT_BOUND")
        assertTrue("sharpened overshoot $sharpened", sharpened.overshoot < SHARPENED_OVERSHOOT_BOUND)
        assertTrue("sharpened undershoot $sharpened", sharpened.undershoot < SHARPENED_UNDERSHOOT_BOUND)
    }

    private fun middleRow(image: HalfFloatImage): FloatArray =
        FloatArray(image.width) { x -> Half.toFloat(image.rgba[((image.height / 2) * image.width + x) * 4 + 1]) }

    @Test
    fun fourKStepExportedAt1080StaysWithinTheDocumentedBound() {
        val sink = MemorySink()
        val exporter = ExportProcessor(SyntheticRenderer(step(LOW, HIGH, EDGE_X)), sink, ultraHdr = null, webpLossless = PlatformWebpLosslessEncoder())
        val result = exporter.exportBlocking(exportRequest(ExportFormat.SOCIAL_STORY_JPEG, ExportResolution.HD_1080))
        assertEquals(1080, result.width)
        assertEquals(1920, result.height)
        val jpeg = sink.files.single().bytes
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
        assertEquals(1080, bitmap.width)
        assertEquals(1920, bitmap.height)

        // Column means of linear light over the middle rows average the blue-noise dither and JPEG noise out.
        val pixels = bitmap.argbPixels()
        val rows = 64 until 1856
        val profile = FloatArray(bitmap.width) { x ->
            var sum = 0.0
            for (y in rows) sum += ColorMath.srgbToLinear(Color.green(pixels[y * bitmap.width + x]) / 255f)
            (sum / rows.count()).toFloat()
        }
        bitmap.recycle()
        val ringing = ringing(profile)
        record("lanczos.export", ringing)
        assertTrue("exported overshoot $ringing", ringing.overshoot < SHARPENED_OVERSHOOT_BOUND)
        assertTrue("exported undershoot $ringing", ringing.undershoot < SHARPENED_UNDERSHOOT_BOUND)
        assertTrue("export lost the step: $ringing", ringing.high - ringing.low > 0.9f * (HIGH - LOW))
    }

    /**
     * Negative control: upscaling a raw step rings past 10% (about 11% per Lanczos3Test; 10.3% here), so the same
     * plateau-relative metric does report ringing above the bound. Lanczos3 is pure arithmetic, so this is
     * deterministic.
     */
    @Test
    fun upscaledStepRingsBeyondTheBound() {
        val source = SyntheticRenderer(step(LOW, HIGH, 270)).render(540, 4)
        val out: HalfFloatImage = Lanczos3.resample(source, 1080, 4)
        val profile = FloatArray(out.width) { x -> Half.toFloat(out.rgba[(out.width + x) * 4]) }
        val ringing = ringing(profile)
        record("lanczos.upscaleControl", ringing)
        assertTrue("control did not ring: $ringing", ringing.overshoot > DOCUMENTED_BOUND || ringing.undershoot > DOCUMENTED_BOUND)
    }

    /** Overshoot and undershoot of a low-to-high step [profile] relative to its plateaus (first and last eighth). */
    private fun ringing(profile: FloatArray): Ringing {
        val n = profile.size
        val low = profile.copyOfRange(0, n / 8).average().toFloat()
        val high = profile.copyOfRange(n - n / 8, n).average().toFloat()
        val height = high - low
        return Ringing(low, high, (profile.max() - high) / height, (low - profile.min()) / height)
    }

    data class Ringing(val low: Float, val high: Float, val overshoot: Float, val undershoot: Float)

    private companion object {
        const val LOW = 0.05f
        const val HIGH = 0.5f
        /** Odd source column, so the edge falls inside an output pixel's footprint rather than on a pixel boundary. */
        const val EDGE_X = 1081
        const val DOCUMENTED_BOUND = 0.1f
        /** LumaSharpen.HALO_MARGIN: OKLab lightness allowed past the 3x3 neighbourhood min/max. */
        const val HALO_MARGIN = 0.02f

        /** Neutral linear value whose OKLab lightness is [linear]'s shifted by [deltaL]. */
        private fun shiftLightness(linear: Float, deltaL: Float): Float {
            val l = ColorMath.linearSrgbToOkLab(linear, linear, linear).l + deltaL
            return ColorMath.okLabToLinearSrgb(OkLab(l, 0f, 0f))[1]
        }

        /** A Lanczos peak at the documented bound, raised by the sharpen halo margin, relative to the step height. */
        val SHARPENED_OVERSHOOT_BOUND = (shiftLightness(HIGH + DOCUMENTED_BOUND * (HIGH - LOW), HALO_MARGIN) - HIGH) / (HIGH - LOW)

        /** A Lanczos trough at the documented bound, lowered by the sharpen halo margin, relative to the step height. */
        val SHARPENED_UNDERSHOOT_BOUND = (LOW - shiftLightness(LOW - DOCUMENTED_BOUND * (HIGH - LOW), -HALO_MARGIN)) / (HIGH - LOW)
    }
}
