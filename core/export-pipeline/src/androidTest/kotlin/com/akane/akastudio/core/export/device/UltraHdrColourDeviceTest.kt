package com.akane.akastudio.core.export.device

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Gainmap
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.akane.akastudio.core.color.math.ColorMath
import com.akane.akastudio.core.export.api.ExportFormat
import com.akane.akastudio.core.export.api.ExportResolution
import com.akane.akastudio.core.export.pipeline.ExportProcessor
import com.akane.akastudio.core.export.store.PlatformUltraHdrEncoder
import com.akane.akastudio.core.export.store.PlatformWebpLosslessEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/**
 * AUD-03 on the platform: a saturated HDR highlight must come back from the exported Ultra HDR JPEG. Before the fix
 * the base red channel was tone-mapped to 2.28, clipped to 1 by the encoder, and the gain (measured against the
 * unclipped base) reconstructed 1.74 instead of 4.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class UltraHdrColourDeviceTest {

    /**
     * Linear red (4, 0, 0) and white (4, 4, 4) patches on an 18% grey field, exported at HD without hdrBoost, decoded,
     * and reconstructed by hand from the decoded base and gain map with the parameters the decoder reports:
     * `hdr = (sdr + epsilonSdr) * 2^g - epsilonHdr`, `g = lerp(log2 ratioMin, log2 ratioMax, code / 255)` (gamma 1).
     *
     * Tolerance 10% on the red channel, sampled at patch centres far from the edges: 4.05% is the single-channel
     * gain's luminance-epsilon bias for pure red (GainMapMathTest), the base is 4:2:0 JPEG at quality 95 (a few codes
     * on a flat saturated patch, about 1% per code near white) and the gain map is itself a lossy JPEG (a code is
     * span / 255 stops; the span here is about 1.8 stops, 0.5% per code). The pre-fix value is 56% low. White, which
     * the bias does not touch, must come back within 5%.
     */
    @Test
    fun saturatedHighlightReconstructsFromTheDecodedUltraHdr() {
        val red = Patch(x0 = 90, x1 = 450, y0 = 780, y1 = 1140)
        val white = Patch(x0 = 630, x1 = 990, y0 = 780, y1 = 1140)
        val paint = Paint { x, y, _, _, out ->
            val v = when {
                red.contains(x, y) -> floatArrayOf(4f, 0f, 0f)
                white.contains(x, y) -> floatArrayOf(4f, 4f, 4f)
                else -> floatArrayOf(0.18f, 0.18f, 0.18f)
            }
            v.copyInto(out)
        }
        val sink = MemorySink()
        val exporter = ExportProcessor(SyntheticRenderer(paint), sink, PlatformUltraHdrEncoder(), PlatformWebpLosslessEncoder())
        val result = exporter.exportBlocking(exportRequest(ExportFormat.ULTRA_HDR_JPEG, ExportResolution.HD_1080))
        assertEquals(ExportFormat.ULTRA_HDR_JPEG, result.format)
        val jpeg = sink.files.single().bytes

        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
        try {
            assertEquals(1080 to 1920, bitmap.width to bitmap.height)
            assertTrue("the export must carry a gain map", bitmap.hasGainmap())
            val gainmap = checkNotNull(bitmap.gainmap)
            assertEquals("gamma 1 is what the exporter writes", 1f, gainmap.gamma[0], 1e-3f)
            val gains = GainCodes(gainmap)
            val (rx, ry) = red.centre()
            val (wx, wy) = white.centre()
            val redOut = reconstruct(bitmap, gainmap, gains, rx, ry)
            val whiteOut = reconstruct(bitmap, gainmap, gains, wx, wy)
            record("aud03.red", redOut.toList())
            record("aud03.white", whiteOut.toList())
            record(
                "aud03.gainmap",
                "ratioMin=${gainmap.ratioMin.toList()} ratioMax=${gainmap.ratioMax.toList()} " +
                    "epsSdr=${gainmap.epsilonSdr.toList()} epsHdr=${gainmap.epsilonHdr.toList()}",
            )
            val redError = abs(redOut[0] - 4f) / 4f
            assertTrue("red reconstructs as ${redOut[0]} for 4 (error $redError)", redError <= 0.10f)
            for (c in 0 until 3) {
                val whiteError = abs(whiteOut[c] - 4f) / 4f
                assertTrue("white channel $c reconstructs as ${whiteOut[c]} for 4 (error $whiteError)", whiteError <= 0.05f)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private class Patch(val x0: Int, val x1: Int, val y0: Int, val y1: Int) {
        fun contains(x: Int, y: Int) = x in x0 until x1 && y in y0 until y1
        fun centre() = (x0 + x1) / 2 to (y0 + y1) / 2
    }

    /** Gain-map codes (0..255) of the decoded [Gainmap], whatever config the decoder chose for its contents. */
    private class GainCodes(gainmap: Gainmap) {
        private val contents: Bitmap = gainmap.gainmapContents
        val width = contents.width
        val height = contents.height
        private val alpha: ByteBuffer? = if (contents.config == Bitmap.Config.ALPHA_8) {
            ByteBuffer.allocate(contents.rowBytes * contents.height).also {
                contents.copyPixelsToBuffer(it)
                it.rewind()
            }
        } else {
            null
        }

        fun at(x: Int, y: Int): Int {
            val bytes = alpha ?: return (contents.getPixel(x, y) shr 16) and 0xFF
            return bytes.get(y * contents.rowBytes + x).toInt() and 0xFF
        }
    }

    /** Linear RGB reconstructed at base pixel ([x], [y]) from the decoded sRGB base and the nearest gain-map code. */
    private fun reconstruct(base: Bitmap, gainmap: Gainmap, gains: GainCodes, x: Int, y: Int): FloatArray {
        val code = gains.at(x * gains.width / base.width, y * gains.height / base.height)
        val pixel = base.getPixel(x, y)
        return FloatArray(3) { c ->
            val lo = log2(gainmap.ratioMin[c])
            val hi = log2(gainmap.ratioMax[c])
            val boost = 2f.pow(lo + code / 255f * (hi - lo))
            val encoded = (pixel shr (16 - 8 * c)) and 0xFF
            (ColorMath.srgbToLinear(encoded / 255f) + gainmap.epsilonSdr[c]) * boost - gainmap.epsilonHdr[c]
        }
    }

    private fun log2(v: Float): Float = (ln(v.toDouble()) / ln(2.0)).toFloat()
}
