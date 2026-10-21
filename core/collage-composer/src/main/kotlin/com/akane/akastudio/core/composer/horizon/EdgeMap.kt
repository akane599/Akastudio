package com.akane.akastudio.core.composer.horizon

import com.akane.akastudio.core.color.api.ImagePixels
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/** Luma image in `[0, 1]`, row-major. */
internal class GrayImage(val width: Int, val height: Int, val values: FloatArray) {
    operator fun get(x: Int, y: Int): Float = values[y * width + x]

    companion object {
        /**
         * Rec.601 luma of the sRGB-encoded [pixels], box-averaged by the smallest integer factor that brings the long
         * edge to at most [maxLongEdge].
         */
        fun of(pixels: ImagePixels, maxLongEdge: Int): GrayImage {
            val factor = max(1, ceil(max(pixels.width, pixels.height).toDouble() / maxLongEdge).toInt())
            val w = max(1, pixels.width / factor)
            val h = max(1, pixels.height / factor)
            val out = FloatArray(w * h)
            val norm = 1f / (factor * factor * 255f)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var sum = 0f
                    for (sy in y * factor until y * factor + factor) {
                        val row = sy * pixels.width
                        for (sx in x * factor until x * factor + factor) {
                            val c = pixels.argb[row + sx]
                            sum += 0.299f * ((c ushr 16) and 0xFF) + 0.587f * ((c ushr 8) and 0xFF) + 0.114f * (c and 0xFF)
                        }
                    }
                    out[y * w + x] = sum * norm
                }
            }
            return GrayImage(w, h, out)
        }
    }
}

/** Thin edge pixels: positions, Sobel magnitude and gradient direction (radians, y down). */
internal class EdgeMap(val xs: IntArray, val ys: IntArray, val magnitudes: FloatArray, val directions: FloatArray) {
    val size: Int get() = xs.size

    companion object {
        /** Magnitudes at or below this never count as edges (flat regions, 8-bit quantization ripple). */
        private const val MIN_MAGNITUDE = 0.02f
        private const val LOW_PERCENTILE = 0.90
        private const val HIGH_PERCENTILE = 0.97

        /**
         * Canny-style edges: Sobel on the interior, non-max suppression across the gradient, hysteresis between the
         * 90th and 97th percentiles of the interior magnitudes with 8-connected growth from strong pixels.
         */
        fun detect(gray: GrayImage): EdgeMap {
            val w = gray.width
            val h = gray.height
            if (w < 3 || h < 3) return EdgeMap(IntArray(0), IntArray(0), FloatArray(0), FloatArray(0))
            val mag = FloatArray(w * h)
            val dir = FloatArray(w * h)
            sobel(gray, mag, dir)
            val (low, high) = thresholds(mag, w, h)
            val thin = suppressNonMaxima(mag, dir, w, h)
            return hysteresis(thin, mag, dir, w, h, low, high)
        }

        private fun sobel(g: GrayImage, mag: FloatArray, dir: FloatArray) {
            for (y in 1 until g.height - 1) {
                for (x in 1 until g.width - 1) {
                    val gx = (g[x + 1, y - 1] + 2 * g[x + 1, y] + g[x + 1, y + 1]) -
                        (g[x - 1, y - 1] + 2 * g[x - 1, y] + g[x - 1, y + 1])
                    val gy = (g[x - 1, y + 1] + 2 * g[x, y + 1] + g[x + 1, y + 1]) -
                        (g[x - 1, y - 1] + 2 * g[x, y - 1] + g[x + 1, y - 1])
                    val i = y * g.width + x
                    mag[i] = sqrt(gx * gx + gy * gy)
                    dir[i] = atan2(gy, gx)
                }
            }
        }

        private fun thresholds(mag: FloatArray, w: Int, h: Int): Pair<Float, Float> {
            val interior = FloatArray((w - 2) * (h - 2))
            var n = 0
            for (y in 1 until h - 1) for (x in 1 until w - 1) interior[n++] = mag[y * w + x]
            interior.sort()
            val low = interior[(LOW_PERCENTILE * (interior.size - 1)).toInt()]
            val high = interior[(HIGH_PERCENTILE * (interior.size - 1)).toInt()]
            return max(low, MIN_MAGNITUDE) to max(high, MIN_MAGNITUDE)
        }

        /**
         * Keeps pixels that are a maximum along their gradient, quantized to 0°, 45°, 90° or 135°. The comparison is
         * strict against the backward neighbour and inclusive against the forward one, so a plateau two pixels wide
         * (a step edge) keeps exactly one pixel.
         */
        private fun suppressNonMaxima(mag: FloatArray, dir: FloatArray, w: Int, h: Int): BooleanArray {
            val keep = BooleanArray(w * h)
            for (y in 1 until h - 1) {
                for (x in 1 until w - 1) {
                    val i = y * w + x
                    val m = mag[i]
                    if (m <= MIN_MAGNITUDE) continue
                    var deg = Math.toDegrees(dir[i].toDouble())
                    if (deg < 0) deg += 180.0
                    val (dx, dy) = when {
                        deg < 22.5 || deg >= 157.5 -> 1 to 0
                        deg < 67.5 -> 1 to 1
                        deg < 112.5 -> 0 to 1
                        else -> -1 to 1
                    }
                    val backward = mag[(y - dy) * w + (x - dx)]
                    val forward = mag[(y + dy) * w + (x + dx)]
                    keep[i] = m > backward && m >= forward
                }
            }
            return keep
        }

        private fun hysteresis(
            thin: BooleanArray, mag: FloatArray, dir: FloatArray, w: Int, h: Int, low: Float, high: Float,
        ): EdgeMap {
            val accepted = BooleanArray(w * h)
            val stack = IntArray(w * h)
            var top = 0
            for (i in thin.indices) {
                if (thin[i] && mag[i] >= high && !accepted[i]) {
                    accepted[i] = true
                    stack[top++] = i
                    while (top > 0) {
                        val p = stack[--top]
                        val px = p % w
                        val py = p / w
                        for (ny in py - 1..py + 1) {
                            if (ny < 1 || ny >= h - 1) continue
                            for (nx in px - 1..px + 1) {
                                if (nx < 1 || nx >= w - 1) continue
                                val q = ny * w + nx
                                if (!accepted[q] && thin[q] && mag[q] >= low) {
                                    accepted[q] = true
                                    stack[top++] = q
                                }
                            }
                        }
                    }
                }
            }
            val count = accepted.count { it }
            val xs = IntArray(count)
            val ys = IntArray(count)
            val ms = FloatArray(count)
            val ds = FloatArray(count)
            var k = 0
            for (i in accepted.indices) {
                if (!accepted[i]) continue
                xs[k] = i % w
                ys[k] = i / w
                ms[k] = mag[i]
                ds[k] = dir[i]
                k++
            }
            return EdgeMap(xs, ys, ms, ds)
        }
    }
}
