package com.akane.akastudio.core.curator.hash

import com.akane.akastudio.core.color.api.ImagePixels
import com.akane.akastudio.core.curator.metrics.AreaResample
import com.akane.akastudio.core.curator.metrics.Luma
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * 64-bit perceptual hashes for near-duplicate detection. Bit `i` (`1L shl i`) holds the cell at row
 * `i / 8`, column `i % 8` (row-major).
 */
object PerceptualHash {
    private const val HASH_SIDE = 8
    private const val DCT_SIDE = 32

    /** Orthonormal DCT-II basis `c(k) * cos(pi * (2n + 1) * k / 2N)` for the first [HASH_SIDE] frequencies. */
    private val DCT_BASIS: Array<DoubleArray> = Array(HASH_SIDE) { k ->
        val c = if (k == 0) sqrt(1.0 / DCT_SIDE) else sqrt(2.0 / DCT_SIDE)
        DoubleArray(DCT_SIDE) { n -> c * cos(PI * (2 * n + 1) * k / (2.0 * DCT_SIDE)) }
    }

    /** Difference hash: 9x8 area-average luma; a bit is set when a cell is darker than its right neighbour. */
    fun dHash(pixels: ImagePixels): Long {
        val cols = HASH_SIDE + 1
        val gray = AreaResample.resize(Luma.of(pixels), pixels.width, pixels.height, cols, HASH_SIDE)
        var hash = 0L
        for (y in 0 until HASH_SIDE) {
            for (x in 0 until HASH_SIDE) {
                if (gray[y * cols + x] < gray[y * cols + x + 1]) hash = hash or (1L shl (y * HASH_SIDE + x))
            }
        }
        return hash
    }

    /**
     * DCT hash: 32x32 area-average luma, orthonormal 2D DCT-II, the top-left 8x8 coefficients compared with
     * the median of the 63 AC ones; bit 0 (the DC term) is always 0.
     */
    fun pHash(pixels: ImagePixels): Long {
        val gray = AreaResample.resize(Luma.of(pixels), pixels.width, pixels.height, DCT_SIDE, DCT_SIDE)
        // Row pass: rows[y][u] = sum_x gray[y][x] * basis[u][x].
        val rows = Array(DCT_SIDE) { y ->
            DoubleArray(HASH_SIDE) { u ->
                var acc = 0.0
                for (x in 0 until DCT_SIDE) acc += gray[y * DCT_SIDE + x] * DCT_BASIS[u][x]
                acc
            }
        }
        val coefficients = DoubleArray(HASH_SIDE * HASH_SIDE) { i ->
            val v = i / HASH_SIDE
            val u = i % HASH_SIDE
            var acc = 0.0
            for (y in 0 until DCT_SIDE) acc += rows[y][u] * DCT_BASIS[v][y]
            acc
        }
        val ac = coefficients.copyOfRange(1, coefficients.size).sortedArray()
        val median = ac[ac.size / 2]
        var hash = 0L
        for (i in 1 until coefficients.size) {
            if (coefficients[i] > median) hash = hash or (1L shl i)
        }
        return hash
    }

    /** Number of differing bits. */
    fun hamming(a: Long, b: Long): Int = (a xor b).countOneBits()
}
