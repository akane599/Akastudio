package com.akane.akastudio.core.composer.crop

import com.akane.akastudio.core.curator.api.SaliencyMap
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Summed-area table of a [SaliencyMap] bilinearly upsampled to a grid whose long edge is [GRID_LONG_EDGE] cells and
 * whose aspect matches the `sourceWidth×sourceHeight` source. [sum] integrates any source-pixel rectangle exactly
 * for the piecewise-constant grid, including fractional edges.
 */
internal class SaliencyIntegral private constructor(
    private val gridWidth: Int,
    private val gridHeight: Int,
    private val sourceWidth: Double,
    private val sourceHeight: Double,
    private val table: DoubleArray,
) {
    /** Saliency mass of the whole source. */
    val total: Double get() = table[table.size - 1]

    /** Saliency mass inside the source-pixel rectangle `[left, right]×[top, bottom]` (clamped to the source). */
    fun sum(left: Double, top: Double, right: Double, bottom: Double): Double {
        val x0 = toGridX(left)
        val x1 = toGridX(right)
        val y0 = toGridY(top)
        val y1 = toGridY(bottom)
        if (x1 <= x0 || y1 <= y0) return 0.0
        return max(0.0, at(x1, y1) - at(x0, y1) - at(x1, y0) + at(x0, y0))
    }

    private fun toGridX(x: Double) = (x / sourceWidth * gridWidth).coerceIn(0.0, gridWidth.toDouble())
    private fun toGridY(y: Double) = (y / sourceHeight * gridHeight).coerceIn(0.0, gridHeight.toDouble())

    /** Bilinear interpolation of the table at fractional grid coordinates, exact for piecewise-constant cells. */
    private fun at(x: Double, y: Double): Double {
        val ix = min(floor(x).toInt(), gridWidth - 1)
        val iy = min(floor(y).toInt(), gridHeight - 1)
        val fx = x - ix
        val fy = y - iy
        val stride = gridWidth + 1
        val a = table[iy * stride + ix]
        val b = table[iy * stride + ix + 1]
        val c = table[(iy + 1) * stride + ix]
        val d = table[(iy + 1) * stride + ix + 1]
        return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy
    }

    companion object {
        const val GRID_LONG_EDGE = 256

        fun of(map: SaliencyMap, sourceWidth: Int, sourceHeight: Int): SaliencyIntegral {
            require(sourceWidth > 0 && sourceHeight > 0) { "source must have a positive size" }
            val (gw, gh) = if (sourceWidth >= sourceHeight) {
                GRID_LONG_EDGE to max(1, (GRID_LONG_EDGE.toDouble() * sourceHeight / sourceWidth).roundToInt())
            } else {
                max(1, (GRID_LONG_EDGE.toDouble() * sourceWidth / sourceHeight).roundToInt()) to GRID_LONG_EDGE
            }
            val stride = gw + 1
            val table = DoubleArray(stride * (gh + 1))
            for (j in 0 until gh) {
                var rowSum = 0.0
                val v = (j + 0.5) / gh * map.height - 0.5
                for (i in 0 until gw) {
                    val u = (i + 0.5) / gw * map.width - 0.5
                    rowSum += sample(map, u, v)
                    table[(j + 1) * stride + i + 1] = table[j * stride + i + 1] + rowSum
                }
            }
            return SaliencyIntegral(gw, gh, sourceWidth.toDouble(), sourceHeight.toDouble(), table)
        }

        /** Bilinear sample at map-pixel-centre coordinates, edge-clamped; negative or non-finite values count as 0. */
        private fun sample(map: SaliencyMap, u: Double, v: Double): Double {
            val x = u.coerceIn(0.0, (map.width - 1).toDouble())
            val y = v.coerceIn(0.0, (map.height - 1).toDouble())
            val x0 = floor(x).toInt()
            val y0 = floor(y).toInt()
            val x1 = min(x0 + 1, map.width - 1)
            val y1 = min(y0 + 1, map.height - 1)
            val fx = x - x0
            val fy = y - y0
            val top = value(map, x0, y0) * (1 - fx) + value(map, x1, y0) * fx
            val bottom = value(map, x0, y1) * (1 - fx) + value(map, x1, y1) * fx
            return top * (1 - fy) + bottom * fy
        }

        private fun value(map: SaliencyMap, x: Int, y: Int): Double {
            val v = map.values[y * map.width + x]
            return if (v.isFinite() && v > 0f) v.toDouble() else 0.0
        }
    }
}
