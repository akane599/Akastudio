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
    private const val SHOULDER_KNEE = 0.6f
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
     * Tone-maps [hdr] with a smooth shoulder on the maximum linear RGB channel. A frame with peak channel <= 1
     * keeps its SDR base; in an HDR frame, pixels whose maximum channel <= 0.6 also keep their colour. Above that
     * knee, the shoulder starts with slope 1 and maps the frame's peak channel to SDR white. The same factor scales
     * all three channels, keeping their ratios and fitting the encoder's [0, 1] range before gain is measured.
     *
     * Encodes `g = log2((Y_hdr + eps) / (Y_sdr + eps))` on a grid downscaled by [gainDownscale], using box-averaged
     * luminances per cell. This scalar gain with luminance epsilon retains the existing per-channel approximation.
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

    /** The same SDR rendition as [compute], without allocating gain codes or cell accumulators. */
    fun sdrBase(hdr: HalfFloatImage): HalfFloatImage {
        val rgba = ShortArray(hdr.rgba.size)
        toneMap(hdr, cell = 1, gainWidth = 0, log2Gain = null, sdrOut = rgba)
        return HalfFloatImage(hdr.width, hdr.height, rgba)
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

    private fun peakChannel(img: HalfFloatImage): Float {
        val row = FloatArray(img.width * 4)
        var peak = 0f
        for (y in 0 until img.height) {
            HalfRows.read(img, y, row)
            for (x in 0 until img.width) for (c in 0..2) {
                peak = max(peak, max(0f, HalfRows.finite(row[x * 4 + c])))
            }
        }
        return peak
    }

    /**
     * Tone-maps against [hdr]'s peak channel (see [compute] for the per-pixel factor), writes the SDR base into
     * [sdrOut] (when given) and fills [log2Gain] cell by cell. Luminance sums for one band of [cell] rows are kept per gain column and flushed when the band (or
     * the image) ends; edge cells average over the pixels they cover.
     */
    private fun toneMap(hdr: HalfFloatImage, cell: Int, gainWidth: Int, log2Gain: FloatArray?, sdrOut: ShortArray?) {
        val w = hdr.width
        val peak = peakChannel(hdr)
        val row = FloatArray(w * 4)
        val sumHdr = log2Gain?.let { DoubleArray(gainWidth) }
        val sumSdr = log2Gain?.let { DoubleArray(gainWidth) }
        for (y in 0 until hdr.height) {
            HalfRows.read(hdr, y, row)
            for (x in 0 until w) {
                val p = x * 4
                // Match the encoder's lower channel bound before measuring either rendition's luminance.
                val r = max(0f, HalfRows.finite(row[p]))
                val g = max(0f, HalfRows.finite(row[p + 1]))
                val b = max(0f, HalfRows.finite(row[p + 2]))
                val yHdr = max(0f, ColorMath.relativeLuminance(r, g, b))
                val factor = shoulderFactor(max(r, max(g, b)), peak)
                row[p] = r * factor
                row[p + 1] = g * factor
                row[p + 2] = b * factor
                if (sumHdr != null && sumSdr != null) {
                    sumHdr[x / cell] += yHdr.toDouble()
                    sumSdr[x / cell] += (yHdr * factor).toDouble()
                }
            }
            if (sdrOut != null) HalfRows.write(sdrOut, w, y, row)
            val bandEnds = (y + 1) % cell == 0 || y == hdr.height - 1
            if (bandEnds && log2Gain != null && sumHdr != null && sumSdr != null) {
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

    private fun shoulderFactor(maxChannel: Float, peak: Float): Float {
        if (peak <= 1f || maxChannel <= SHOULDER_KNEE) return 1f
        val t = maxChannel - SHOULDER_KNEE
        val length = peak - SHOULDER_KNEE
        val a = 1f - SHOULDER_KNEE
        val mapped = SHOULDER_KNEE + a * t / (a + t * (1f - a / length))
        return (mapped / maxChannel).coerceAtMost(1f)
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
