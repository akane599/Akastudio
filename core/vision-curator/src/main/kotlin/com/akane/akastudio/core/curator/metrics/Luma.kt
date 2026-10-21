package com.akane.akastudio.core.curator.metrics

import com.akane.akastudio.core.color.api.ImagePixels

/** Gray-scale conversion shared by every curator metric. */
object Luma {
    private const val KR = 0.2126f
    private const val KG = 0.7152f
    private const val KB = 0.0722f

    /**
     * Rec.709 luma Y' computed directly from the sRGB-encoded channels, on the 0..255 scale.
     * The result is row-major with `pixels.width * pixels.height` entries.
     */
    fun of(pixels: ImagePixels): FloatArray {
        val argb = pixels.argb
        return FloatArray(argb.size) { i ->
            val c = argb[i]
            KR * ((c shr 16) and 0xFF) + KG * ((c shr 8) and 0xFF) + KB * (c and 0xFF)
        }
    }
}

/** Area-average resampling of a row-major gray plane; shared by saliency and perceptual hashing. */
internal object AreaResample {

    /** Size whose long edge equals [longEdge], keeping the aspect ratio (rounded, each side at least 1). */
    fun fitLongEdge(width: Int, height: Int, longEdge: Int): Pair<Int, Int> {
        require(width > 0 && height > 0 && longEdge > 0)
        val long = maxOf(width, height).toDouble()
        val w = Math.round(width * longEdge / long).toInt().coerceAtLeast(1)
        val h = Math.round(height * longEdge / long).toInt().coerceAtLeast(1)
        return w to h
    }

    /**
     * Resizes [src] (`w x h`) to `tw x th`; every target cell is the coverage-weighted mean of the source
     * pixels its footprint overlaps. Works for both down- and up-scaling.
     */
    fun resize(src: FloatArray, w: Int, h: Int, tw: Int, th: Int): FloatArray {
        require(w > 0 && h > 0 && tw > 0 && th > 0 && src.size == w * h)
        val horizontal = FloatArray(tw * h)
        val xs = footprints(w, tw)
        for (y in 0 until h) {
            val row = y * w
            for (tx in 0 until tw) horizontal[y * tw + tx] = xs[tx].mean { src[row + it] }
        }
        val out = FloatArray(tw * th)
        val ys = footprints(h, th)
        for (ty in 0 until th) {
            for (tx in 0 until tw) out[ty * tw + tx] = ys[ty].mean { horizontal[it * tw + tx] }
        }
        return out
    }

    private class Footprint(val indices: IntArray, val weights: FloatArray) {
        inline fun mean(sample: (Int) -> Float): Float {
            var sum = 0.0
            var weight = 0.0
            for (k in indices.indices) {
                sum += weights[k] * sample(indices[k])
                weight += weights[k]
            }
            return (sum / weight).toFloat()
        }
    }

    private fun footprints(source: Int, target: Int): Array<Footprint> {
        val scale = source.toDouble() / target
        return Array(target) { t ->
            val start = t * scale
            val end = (t + 1) * scale
            val first = start.toInt().coerceIn(0, source - 1)
            val last = (kotlin.math.ceil(end).toInt() - 1).coerceIn(first, source - 1)
            val indices = IntArray(last - first + 1) { first + it }
            val weights = FloatArray(indices.size) { k ->
                val i = indices[k]
                (minOf(end, i + 1.0) - maxOf(start, i.toDouble())).coerceAtLeast(0.0).toFloat()
            }
            if (weights.all { it <= 0f }) weights.fill(1f)
            Footprint(indices, weights)
        }
    }
}
