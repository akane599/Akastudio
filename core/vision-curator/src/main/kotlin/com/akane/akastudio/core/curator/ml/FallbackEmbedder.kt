package com.akane.akastudio.core.curator.ml

import com.akane.akastudio.core.color.api.ImagePixels
import com.akane.akastudio.core.color.math.ColorMath
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Deterministic 192-dimension global descriptor, used when the MediaPipe embedder cannot load.
 *
 * The image is box-resampled to a 64×64 grid, converted to OKLab, and described by three groups:
 * - 48 values: 4×4 grid of mean OKLab (L, a, b), with L centred on 0.5.
 * - 128 values: 4×4 grid of 8-bin unsigned gradient-orientation histograms of L. Each histogram covers a
 *   2×2 super-cell of 8×8-pixel cells and is block-normalized, as in HOG.
 * - 16 values: global OKLCh hue histogram weighted by chroma, over pixels with chroma > 0.02.
 *
 * Fixed group weights balance the three; the concatenation is L2-normalized. A featureless image (all
 * groups zero) maps to the constant unit vector, so the output always has norm 1.
 */
object FallbackEmbedder {

    /** Embedding length. */
    const val DIMENSION = 192

    private const val GRID = 64
    private const val COLOR_CELLS = 4
    private const val COLOR_CELL_PX = GRID / COLOR_CELLS
    private const val HOG_CELL_PX = 8
    private const val HOG_CELLS = GRID / HOG_CELL_PX
    private const val SUPER_CELLS = 4
    private const val ORIENTATION_BINS = 8
    private const val HUE_BINS = 16
    private const val HUE_MIN_CHROMA = 0.02f

    private const val COLOR_L_WEIGHT = 2f
    private const val COLOR_AB_WEIGHT = 4f
    private const val HOG_WEIGHT = 0.5f
    private const val HOG_EPSILON = 1f
    private const val HUE_WEIGHT = 1.5f

    private const val COLOR_OFFSET = 0
    private const val HOG_OFFSET = COLOR_OFFSET + COLOR_CELLS * COLOR_CELLS * 3
    private const val HUE_OFFSET = HOG_OFFSET + SUPER_CELLS * SUPER_CELLS * ORIENTATION_BINS

    /** Returns the L2-normalized [DIMENSION]-value descriptor of [pixels]. */
    fun embed(pixels: ImagePixels): FloatArray {
        val lab = resampleToOkLab(pixels)
        val out = FloatArray(DIMENSION)
        writeColorGrid(lab, out)
        writeGradientHistograms(lab.l, out)
        writeHueHistogram(lab, out)
        return l2Normalize(out)
    }

    /**
     * Fits a descriptor to [dimension] values so it can stand in for another embedder's vector of that
     * length: value `i` is added to slot `i mod dimension` (plain zero padding when [dimension] is at least
     * the descriptor's length), then the result is L2-normalized again. The fitted vector is unit length
     * but lives in this descriptor's feature space, not the other embedder's.
     */
    fun fitTo(vector: FloatArray, dimension: Int): FloatArray {
        require(dimension > 0) { "dimension must be positive, was $dimension" }
        if (vector.size == dimension) return vector.copyOf()
        val out = FloatArray(dimension)
        for (i in vector.indices) out[i % dimension] += vector[i]
        return l2Normalize(out)
    }

    private class LabGrid(val l: FloatArray, val a: FloatArray, val b: FloatArray)

    /** Area-averages 8-bit sRGB into a GRID×GRID raster (nearest pixel when the source is smaller), then OKLab. */
    private fun resampleToOkLab(pixels: ImagePixels): LabGrid {
        val size = GRID * GRID
        val l = FloatArray(size)
        val a = FloatArray(size)
        val b = FloatArray(size)
        val w = pixels.width
        val h = pixels.height
        for (gy in 0 until GRID) {
            val y0 = gy * h / GRID
            val y1 = maxOf(y0 + 1, (gy + 1) * h / GRID)
            for (gx in 0 until GRID) {
                val x0 = gx * w / GRID
                val x1 = maxOf(x0 + 1, (gx + 1) * w / GRID)
                var r = 0L
                var g = 0L
                var bl = 0L
                for (y in y0 until y1) {
                    val row = y * w
                    for (x in x0 until x1) {
                        val p = pixels.argb[row + x]
                        r += (p ushr 16) and 0xFF
                        g += (p ushr 8) and 0xFF
                        bl += p and 0xFF
                    }
                }
                val n = (y1 - y0).toLong() * (x1 - x0)
                val argb = (0xFF shl 24) or
                    (((r + n / 2) / n).toInt() shl 16) or
                    (((g + n / 2) / n).toInt() shl 8) or
                    ((bl + n / 2) / n).toInt()
                val lab = ColorMath.argbToOkLab(argb)
                val i = gy * GRID + gx
                l[i] = lab.l
                a[i] = lab.a
                b[i] = lab.b
            }
        }
        return LabGrid(l, a, b)
    }

    private fun writeColorGrid(lab: LabGrid, out: FloatArray) {
        val area = (COLOR_CELL_PX * COLOR_CELL_PX).toFloat()
        for (cy in 0 until COLOR_CELLS) {
            for (cx in 0 until COLOR_CELLS) {
                var sl = 0f
                var sa = 0f
                var sb = 0f
                for (y in cy * COLOR_CELL_PX until (cy + 1) * COLOR_CELL_PX) {
                    for (x in cx * COLOR_CELL_PX until (cx + 1) * COLOR_CELL_PX) {
                        val i = y * GRID + x
                        sl += lab.l[i]
                        sa += lab.a[i]
                        sb += lab.b[i]
                    }
                }
                val o = COLOR_OFFSET + (cy * COLOR_CELLS + cx) * 3
                out[o] = (sl / area - 0.5f) * COLOR_L_WEIGHT
                out[o + 1] = sa / area * COLOR_AB_WEIGHT
                out[o + 2] = sb / area * COLOR_AB_WEIGHT
            }
        }
    }

    private fun writeGradientHistograms(l: FloatArray, out: FloatArray) {
        val cellHist = FloatArray(HOG_CELLS * HOG_CELLS * ORIENTATION_BINS)
        for (y in 0 until GRID) {
            val up = maxOf(y - 1, 0)
            val down = minOf(y + 1, GRID - 1)
            for (x in 0 until GRID) {
                val left = maxOf(x - 1, 0)
                val right = minOf(x + 1, GRID - 1)
                val gx = l[y * GRID + right] - l[y * GRID + left]
                val gy = l[down * GRID + x] - l[up * GRID + x]
                val magnitude = hypot(gx, gy)
                if (magnitude == 0f) continue
                var angle = atan2(gy, gx).toDouble()
                if (angle < 0.0) angle += PI
                val bin = ((angle / PI) * ORIENTATION_BINS).toInt().coerceIn(0, ORIENTATION_BINS - 1)
                val cell = (y / HOG_CELL_PX) * HOG_CELLS + (x / HOG_CELL_PX)
                cellHist[cell * ORIENTATION_BINS + bin] += magnitude
            }
        }
        val block = FloatArray(ORIENTATION_BINS)
        for (sy in 0 until SUPER_CELLS) {
            for (sx in 0 until SUPER_CELLS) {
                block.fill(0f)
                for (dy in 0..1) {
                    for (dx in 0..1) {
                        val cell = (sy * 2 + dy) * HOG_CELLS + (sx * 2 + dx)
                        for (k in 0 until ORIENTATION_BINS) block[k] += cellHist[cell * ORIENTATION_BINS + k]
                    }
                }
                var sq = 0f
                for (v in block) sq += v * v
                val scale = HOG_WEIGHT / sqrt(sq + HOG_EPSILON * HOG_EPSILON)
                val o = HOG_OFFSET + (sy * SUPER_CELLS + sx) * ORIENTATION_BINS
                for (k in 0 until ORIENTATION_BINS) out[o + k] = block[k] * scale
            }
        }
    }

    private fun writeHueHistogram(lab: LabGrid, out: FloatArray) {
        val hist = FloatArray(HUE_BINS)
        for (i in lab.l.indices) {
            val chroma = hypot(lab.a[i], lab.b[i])
            if (chroma <= HUE_MIN_CHROMA) continue
            val hue = HeuristicSceneClassifier.hueDegrees(lab.a[i], lab.b[i])
            val bin = (hue / 360f * HUE_BINS).toInt().coerceIn(0, HUE_BINS - 1)
            hist[bin] += chroma
        }
        var sq = 0f
        for (v in hist) sq += v * v
        if (sq == 0f) return
        val scale = HUE_WEIGHT / sqrt(sq)
        for (k in 0 until HUE_BINS) out[HUE_OFFSET + k] = hist[k] * scale
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        var sq = 0.0
        for (x in v) sq += x.toDouble() * x
        if (sq < 1e-18) {
            v.fill((1.0 / sqrt(v.size.toDouble())).toFloat())
            return v
        }
        val inv = (1.0 / sqrt(sq)).toFloat()
        for (i in v.indices) v[i] *= inv
        return v
    }
}
