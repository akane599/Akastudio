package com.akane.akastudio.core.export.process

import com.akane.akastudio.core.color.api.HalfFloatImage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * Separable Lanczos-3 resampler working in linear light on premultiplied alpha.
 *
 * Pixel centres are aligned (`srcX = (dstX + 0.5) * scale - 0.5`), the kernel is widened by the scale factor when
 * downscaling, edges clamp, and ringing is kept (the quantizer clamps later). The horizontal pass writes a half-float
 * intermediate of `srcHeight x dstWidth` pixels; the vertical pass then accumulates one output row at a time.
 */
object Lanczos3 {

    /** Below this alpha a pixel's colour is undefined after unpremultiplying and is written as 0. */
    private const val MIN_ALPHA = 1f / 8192f
    private const val LOBES = 3.0

    /** Resamples [src] to exactly [dstWidth] x [dstHeight]. */
    fun resample(src: HalfFloatImage, dstWidth: Int, dstHeight: Int): HalfFloatImage {
        require(dstWidth > 0 && dstHeight > 0) { "destination size must be positive, was ${dstWidth}x$dstHeight" }
        val horizontal = weights(src.width, dstWidth)
        val vertical = weights(src.height, dstHeight)
        val intermediate = horizontalPass(src, dstWidth, horizontal)
        return verticalPass(intermediate, dstWidth, dstHeight, vertical)
    }

    private fun horizontalPass(src: HalfFloatImage, dstWidth: Int, wx: Weights): ShortArray {
        val row = FloatArray(src.width * 4)
        val out = ShortArray(src.height * dstWidth * 4)
        val taps = wx.taps
        for (y in 0 until src.height) {
            HalfRows.read(src, y, row)
            premultiply(row, src.width)
            var o = y * dstWidth * 4
            for (x in 0 until dstWidth) {
                var r = 0f
                var g = 0f
                var b = 0f
                var a = 0f
                val base = x * taps
                for (k in 0 until taps) {
                    val w = wx.weight[base + k]
                    val p = wx.index[base + k] * 4
                    r += w * row[p]
                    g += w * row[p + 1]
                    b += w * row[p + 2]
                    a += w * row[p + 3]
                }
                out[o] = Half.fromFloat(r)
                out[o + 1] = Half.fromFloat(g)
                out[o + 2] = Half.fromFloat(b)
                out[o + 3] = Half.fromFloat(a)
                o += 4
            }
        }
        return out
    }

    private fun verticalPass(inter: ShortArray, width: Int, dstHeight: Int, wy: Weights): HalfFloatImage {
        val stride = width * 4
        val acc = FloatArray(stride)
        val out = ShortArray(dstHeight * stride)
        val taps = wy.taps
        for (y in 0 until dstHeight) {
            acc.fill(0f)
            val base = y * taps
            for (k in 0 until taps) {
                val w = wy.weight[base + k]
                if (w == 0f) continue
                val off = wy.index[base + k] * stride
                for (i in 0 until stride) acc[i] += w * Half.bitsToFloat(inter[off + i].toInt())
            }
            unpremultiply(acc, width)
            HalfRows.write(out, width, y, acc)
        }
        return HalfFloatImage(width, dstHeight, out)
    }

    private fun premultiply(row: FloatArray, width: Int) {
        for (x in 0 until width) {
            val p = x * 4
            val a = row[p + 3]
            row[p] *= a
            row[p + 1] *= a
            row[p + 2] *= a
        }
    }

    /** Divides colour by the filtered alpha; the written alpha is clamped to [0, 1], colour ringing is kept. */
    private fun unpremultiply(row: FloatArray, width: Int) {
        for (x in 0 until width) {
            val p = x * 4
            val a = row[p + 3]
            if (a > MIN_ALPHA) {
                val inv = 1f / a
                row[p] *= inv
                row[p + 1] *= inv
                row[p + 2] *= inv
            } else {
                row[p] = 0f
                row[p + 1] = 0f
                row[p + 2] = 0f
            }
            row[p + 3] = a.coerceIn(0f, 1f)
        }
    }

    /** Per-output tap table: [taps] (index, weight) pairs per output sample, weights normalized to sum 1. */
    internal class Weights(val taps: Int, val index: IntArray, val weight: FloatArray)

    internal fun weights(srcSize: Int, dstSize: Int): Weights {
        val scale = srcSize.toDouble() / dstSize
        val filterScale = max(1.0, scale)
        val support = LOBES * filterScale
        val taps = floor(2.0 * support).toInt() + 1
        val index = IntArray(dstSize * taps)
        val weight = FloatArray(dstSize * taps)
        val raw = DoubleArray(taps)
        for (i in 0 until dstSize) {
            val center = (i + 0.5) * scale - 0.5
            val left = ceil(center - support).toInt()
            val right = floor(center + support).toInt()
            val base = i * taps
            var n = 0
            var sum = 0.0
            var j = left
            while (j <= right && n < taps) {
                val w = kernel((j - center) / filterScale)
                index[base + n] = j.coerceIn(0, srcSize - 1)
                raw[n] = w
                sum += w
                n++
                j++
            }
            for (k in 0 until taps) {
                weight[base + k] = if (k < n) (raw[k] / sum).toFloat() else 0f
                if (k >= n) index[base + k] = index[base]
            }
        }
        return Weights(taps, index, weight)
    }

    /** `sinc(x) * sinc(x / 3)` for |x| < 3, else 0. */
    internal fun kernel(x: Double): Double {
        val ax = abs(x)
        if (ax >= LOBES) return 0.0
        if (ax < 1e-9) return 1.0
        val px = PI * x
        return LOBES * sin(px) * sin(px / LOBES) / (px * px)
    }
}
