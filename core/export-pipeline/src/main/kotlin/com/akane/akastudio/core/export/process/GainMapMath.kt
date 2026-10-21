package com.akane.akastudio.core.export.process

import com.akane.akastudio.core.color.api.HalfFloatImage
import com.akane.akastudio.core.color.math.ColorMath
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Ultra HDR (ISO 21496-1 style) gain map: an SDR base plus the parameters the platform `Gainmap` takes.
 *
 * Reconstruction is `hdr = (sdr + epsilonSdr) * 2^g - epsilonHdr` with
 * `g = log2(ratioMin) + (gain / 255)^gamma * (log2(ratioMax) - log2(ratioMin))`.
 *
 * @property sdr tone-mapped linear-light base image (same size as the HDR input)
 * @property gain single-channel gain codes, row-major, [gainWidth] x [gainHeight]
 */
data class GainMapResult(
    val sdr: HalfFloatImage,
    val gain: ByteArray,
    val gainWidth: Int,
    val gainHeight: Int,
    val ratioMin: Float,
    val ratioMax: Float,
    val gamma: Float,
    val epsilonSdr: Float,
    val epsilonHdr: Float,
    val displayRatioSdr: Float,
    val displayRatioHdr: Float,
)

/** HDR headroom synthesis and gain-map computation on linear-light half images. */
object GainMapMath {

    private const val EPSILON = 1f / 64f
    private const val GAMMA = 1f
    /** At or below this maximum log2 gain the image has no usable headroom. */
    private const val MIN_HEADROOM_LOG2 = 0.01f
    /** Smallest log2 span kept between ratioMin and ratioMax so the code range never collapses. */
    private const val MIN_SPAN_LOG2 = 1e-3f
    private val LN2 = ln(2.0)

    /**
     * "HDR Story Boost": multiplies linear RGB by `m(L) = 1 + (2^boostStops - 1) * smoothstep(0.70, 1.0, L)`, where L
     * is the sRGB-encoded relative luminance clamped to [0, 1]. Midtones are untouched; diffuse white gains
     * [boostStops] stops. Alpha passes through.
     */
    fun boostHighlights(img: HalfFloatImage, boostStops: Float = 2f): HalfFloatImage {
        require(boostStops.isFinite() && boostStops >= 0f) { "boostStops must be finite and >= 0, was $boostStops" }
        val extra = 2f.pow(boostStops) - 1f
        val out = img.rgba.copyOf()
        val row = FloatArray(img.width * 4)
        for (y in 0 until img.height) {
            HalfRows.read(img, y, row)
            for (x in 0 until img.width) {
                val p = x * 4
                val r = HalfRows.finite(row[p])
                val g = HalfRows.finite(row[p + 1])
                val b = HalfRows.finite(row[p + 2])
                val luma = ColorMath.linearToSrgb(ColorMath.relativeLuminance(r, g, b).coerceIn(0f, 1f))
                val m = 1f + extra * smoothstep(0.70f, 1.0f, luma)
                if (m == 1f) continue
                val o = (y * img.width + x) * 4
                out[o] = Half.fromFloat(r * m)
                out[o + 1] = Half.fromFloat(g * m)
                out[o + 2] = Half.fromFloat(b * m)
            }
        }
        return HalfFloatImage(img.width, img.height, out)
    }

    /**
     * Tone-maps [hdr] to an SDR base with extended Reinhard on luminance (`Y' = Y (1 + Y / Lw^2) / (1 + Y)`, Lw the
     * peak luminance, at least 1), scaling RGB by `min(Y' / Y, 1 / max(1, maxChannel))` so every base channel also
     * stays within SDR white (the encoder clamps channels to [0, 1]; a saturated highlight would otherwise be clipped
     * after its gain was measured), and encodes `g = log2((Y_hdr + eps) / (Y_sdr + eps))` on a grid downscaled by
     * [gainDownscale] (box-averaged luminances per cell). Neutral pixels are unaffected by the channel bound, since
     * `Y' <= 1` there already.
     */
    fun compute(hdr: HalfFloatImage, gainDownscale: Int = 4): GainMapResult {
        require(gainDownscale >= 1) { "gainDownscale must be >= 1, was $gainDownscale" }
        val gainWidth = (hdr.width + gainDownscale - 1) / gainDownscale
        val gainHeight = (hdr.height + gainDownscale - 1) / gainDownscale
        val log2Gain = FloatArray(gainWidth * gainHeight)
        val sdrRgba = ShortArray(hdr.rgba.size)
        toneMap(hdr, gainDownscale, gainWidth, log2Gain, sdrRgba)
        val sdr = HalfFloatImage(hdr.width, hdr.height, sdrRgba)

        var minG = Float.POSITIVE_INFINITY
        var maxG = Float.NEGATIVE_INFINITY
        for (g in log2Gain) {
            minG = min(minG, g)
            maxG = max(maxG, g)
        }
        if (maxG <= MIN_HEADROOM_LOG2) {
            return result(sdr, ByteArray(log2Gain.size), gainWidth, gainHeight, 1f, 1f)
        }
        var logMin = max(minG, 0f)
        if (maxG - logMin < MIN_SPAN_LOG2) logMin = 0f
        val span = maxG - logMin
        val gain = ByteArray(log2Gain.size) {
            val t = ((log2Gain[it] - logMin) / span).coerceIn(0f, 1f)
            (255f * t.pow(1f / GAMMA)).roundToInt().coerceIn(0, 255).toByte()
        }
        return result(sdr, gain, gainWidth, gainHeight, 2f.pow(logMin), 2f.pow(maxG))
    }

    /**
     * The [GainMapResult.ratioMax] that [compute] returns for [hdr] (1 when there is no usable headroom), without
     * allocating the SDR base, so a caller can decide on the headroom while still holding another full frame.
     */
    fun ratioMax(hdr: HalfFloatImage, gainDownscale: Int = 4): Float {
        require(gainDownscale >= 1) { "gainDownscale must be >= 1, was $gainDownscale" }
        val gainWidth = (hdr.width + gainDownscale - 1) / gainDownscale
        val gainHeight = (hdr.height + gainDownscale - 1) / gainDownscale
        val log2Gain = FloatArray(gainWidth * gainHeight)
        toneMap(hdr, gainDownscale, gainWidth, log2Gain, sdrOut = null)
        var maxG = Float.NEGATIVE_INFINITY
        for (g in log2Gain) maxG = max(maxG, g)
        return if (maxG <= MIN_HEADROOM_LOG2) 1f else 2f.pow(maxG)
    }

    private fun result(sdr: HalfFloatImage, gain: ByteArray, w: Int, h: Int, ratioMin: Float, ratioMax: Float) =
        GainMapResult(
            sdr = sdr, gain = gain, gainWidth = w, gainHeight = h,
            ratioMin = ratioMin, ratioMax = ratioMax, gamma = GAMMA,
            epsilonSdr = EPSILON, epsilonHdr = EPSILON,
            displayRatioSdr = 1f, displayRatioHdr = ratioMax,
        )

    private fun peakLuminance(img: HalfFloatImage): Float {
        val row = FloatArray(img.width * 4)
        var peak = 0f
        for (y in 0 until img.height) {
            HalfRows.read(img, y, row)
            for (x in 0 until img.width) peak = max(peak, luminance(row, x * 4))
        }
        return peak
    }

    /**
     * Tone-maps against [hdr]'s peak luminance (see [compute] for the per-pixel factor), writes the SDR base into
     * [sdrOut] (when given) and fills [log2Gain] cell by cell. Luminance sums for one band of [cell] rows are kept per gain column and flushed when the band (or
     * the image) ends; edge cells average over the pixels they cover.
     */
    private fun toneMap(hdr: HalfFloatImage, cell: Int, gainWidth: Int, log2Gain: FloatArray, sdrOut: ShortArray?) {
        val w = hdr.width
        val peak = max(1f, peakLuminance(hdr))
        val invPeak2 = 1f / (peak * peak)
        val row = FloatArray(w * 4)
        val sumHdr = DoubleArray(gainWidth)
        val sumSdr = DoubleArray(gainWidth)
        for (y in 0 until hdr.height) {
            HalfRows.read(hdr, y, row)
            for (x in 0 until w) {
                val p = x * 4
                val r = HalfRows.finite(row[p])
                val g = HalfRows.finite(row[p + 1])
                val b = HalfRows.finite(row[p + 2])
                val yHdr = max(0f, ColorMath.relativeLuminance(r, g, b))
                val luminanceFactor = (1f + yHdr * invPeak2) / (1f + yHdr)
                // The encoder clamps each channel to SDR white, so the base must already fit there per channel: a
                // clipped channel would no longer be the base the gain below was measured against.
                val channelFactor = 1f / max(1f, max(r, max(g, b)))
                val factor = min(luminanceFactor, channelFactor)
                row[p] = r * factor
                row[p + 1] = g * factor
                row[p + 2] = b * factor
                sumHdr[x / cell] += yHdr.toDouble()
                sumSdr[x / cell] += (yHdr * factor).toDouble()
            }
            if (sdrOut != null) HalfRows.write(sdrOut, w, y, row)
            val bandEnds = (y + 1) % cell == 0 || y == hdr.height - 1
            if (bandEnds) {
                val bandRows = y % cell + 1
                val gy = y / cell
                for (gx in 0 until gainWidth) {
                    val count = (min(w, (gx + 1) * cell) - gx * cell) * bandRows
                    val meanHdr = sumHdr[gx] / count
                    val meanSdr = sumSdr[gx] / count
                    log2Gain[gy * gainWidth + gx] = (ln((meanHdr + EPSILON) / (meanSdr + EPSILON)) / LN2).toFloat()
                }
                sumHdr.fill(0.0)
                sumSdr.fill(0.0)
            }
        }
    }

    /** Non-negative Rec.709 luminance of the sanitized pixel at [p]. */
    private fun luminance(row: FloatArray, p: Int): Float = max(
        0f,
        ColorMath.relativeLuminance(HalfRows.finite(row[p]), HalfRows.finite(row[p + 1]), HalfRows.finite(row[p + 2])),
    )

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
