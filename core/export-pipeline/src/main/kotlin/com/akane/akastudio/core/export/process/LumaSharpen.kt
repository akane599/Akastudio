package com.akane.akastudio.core.export.process

import com.akane.akastudio.core.color.api.HalfFloatImage
import com.akane.akastudio.core.color.api.OkLab
import com.akane.akastudio.core.color.math.ColorMath
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * Output-referred unsharp mask on OKLab lightness only.
 *
 * `d = L - gaussian(L)`; where `|d| > threshold`, `L' = L + amount * (d - sign(d) * threshold)`, clamped to the 3x3
 * neighbourhood range of L widened by [HALO_MARGIN] (halo clamp). Colour is rebuilt from L' with the pixel's own a
 * and b; alpha passes through. Rows are processed in strips with a `ceil(3 sigma) + 1` row overlap, so only a strip
 * of lightness is ever held as floats.
 */
object LumaSharpen {

    /** Lightness headroom allowed past the 3x3 neighbourhood min/max. */
    private const val HALO_MARGIN = 0.02f
    private const val STRIP_ROWS = 64

    /** Returns a sharpened copy of [img] (linear light; alpha is passed through untouched, so opaque frames only). */
    fun apply(img: HalfFloatImage, amount: Float = 0.35f, sigma: Float = 0.8f, threshold: Float = 0.004f): HalfFloatImage {
        require(amount.isFinite() && amount >= 0f) { "amount must be finite and >= 0, was $amount" }
        require(sigma.isFinite() && sigma > 0f) { "sigma must be finite and > 0, was $sigma" }
        require(threshold.isFinite() && threshold >= 0f) { "threshold must be finite and >= 0, was $threshold" }
        val out = img.rgba.copyOf()
        if (amount == 0f) return HalfFloatImage(img.width, img.height, out)
        val kernel = gaussianKernel(sigma)
        val strip = Strip(img, kernel, overlap = kernel.size / 2 + 1)
        var y0 = 0
        while (y0 < img.height) {
            val y1 = min(img.height, y0 + STRIP_ROWS)
            strip.load(y0, y1)
            for (y in y0 until y1) sharpenRow(strip, y, out, amount, threshold)
            y0 = y1
        }
        return HalfFloatImage(img.width, img.height, out)
    }

    private fun sharpenRow(strip: Strip, y: Int, out: ShortArray, amount: Float, threshold: Float) {
        val img = strip.img
        val w = img.width
        val src = img.rgba
        val rgb = FloatArray(3)
        for (x in 0 until w) {
            val l = strip.luma(x, y)
            val d = l - strip.blurred(x, y)
            if (abs(d) <= threshold) continue
            val sharpened = l + amount * (d - sign(d) * threshold)
            val clamped = sharpened.coerceIn(strip.min3x3(x, y) - HALO_MARGIN, strip.max3x3(x, y) + HALO_MARGIN)
            val p = (y * w + x) * 4
            val lab = ColorMath.linearSrgbToOkLab(
                HalfRows.finite(Half.bitsToFloat(src[p].toInt())),
                HalfRows.finite(Half.bitsToFloat(src[p + 1].toInt())),
                HalfRows.finite(Half.bitsToFloat(src[p + 2].toInt())),
            )
            ColorMath.okLabToLinearSrgb(OkLab(clamped, lab.a, lab.b), rgb)
            out[p] = Half.fromFloat(rgb[0])
            out[p + 1] = Half.fromFloat(rgb[1])
            out[p + 2] = Half.fromFloat(rgb[2])
        }
    }

    /** Normalized Gaussian of radius `ceil(3 sigma)`. */
    internal fun gaussianKernel(sigma: Float): FloatArray {
        val radius = max(1, ceil(3.0 * sigma).toInt())
        val twoSigma2 = 2.0 * sigma * sigma
        val raw = DoubleArray(2 * radius + 1) { val t = (it - radius).toDouble(); exp(-t * t / twoSigma2) }
        val sum = raw.sum()
        return FloatArray(raw.size) { (raw[it] / sum).toFloat() }
    }

    /** OKLab lightness and its horizontal blur for rows `[y0 - overlap, y1 + overlap)` clipped to the image. */
    private class Strip(val img: HalfFloatImage, private val kernel: FloatArray, private val overlap: Int) {
        private val width = img.width
        private val capacity = STRIP_ROWS + 2 * overlap
        private val lightness = FloatArray(capacity * width)
        private val hBlur = FloatArray(capacity * width)
        private val row = FloatArray(width * 4)
        private var first = 0
        private var end = 0

        fun load(y0: Int, y1: Int) {
            first = max(0, y0 - overlap)
            end = min(img.height, y1 + overlap)
            for (yy in first until end) {
                HalfRows.read(img, yy, row)
                val base = (yy - first) * width
                for (x in 0 until width) {
                    val p = x * 4
                    lightness[base + x] = ColorMath.linearSrgbToOkLab(
                        HalfRows.finite(row[p]), HalfRows.finite(row[p + 1]), HalfRows.finite(row[p + 2]),
                    ).l
                }
                blurRowHorizontally(base)
            }
        }

        private fun blurRowHorizontally(base: Int) {
            val radius = kernel.size / 2
            for (x in 0 until width) {
                var acc = 0f
                for (k in kernel.indices) {
                    val sx = (x + k - radius).coerceIn(0, width - 1)
                    acc += kernel[k] * lightness[base + sx]
                }
                hBlur[base + x] = acc
            }
        }

        fun luma(x: Int, y: Int): Float = lightness[(y - first) * width + x]

        fun blurred(x: Int, y: Int): Float {
            val radius = kernel.size / 2
            var acc = 0f
            for (k in kernel.indices) {
                val sy = (y + k - radius).coerceIn(0, img.height - 1)
                acc += kernel[k] * hBlur[(sy - first) * width + x]
            }
            return acc
        }

        fun min3x3(x: Int, y: Int): Float {
            var m = Float.POSITIVE_INFINITY
            for (dy in -1..1) for (dx in -1..1) m = min(m, neighbour(x + dx, y + dy))
            return m
        }

        fun max3x3(x: Int, y: Int): Float {
            var m = Float.NEGATIVE_INFINITY
            for (dy in -1..1) for (dx in -1..1) m = max(m, neighbour(x + dx, y + dy))
            return m
        }

        private fun neighbour(x: Int, y: Int): Float =
            luma(x.coerceIn(0, width - 1), y.coerceIn(0, img.height - 1))
    }
}
