package com.akane.akastudio.core.export.process

import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 64x64 tileable blue-noise threshold matrix from Ulichney's void-and-cluster method (1993).
 *
 * Gaussian energy sigma 1.5 on a torus, deterministic 10% initial pattern from `Random(1)`. `ranks[y * SIZE + x]` is
 * `(rank + 0.5) / 4096`, so every value is distinct and lies in (0, 1).
 */
object BlueNoise {
    /** Tile edge length in pixels. */
    const val SIZE = 64

    /** SIZE*SIZE thresholds in [0, 1), row-major; generated once on first access. Treat as read-only. */
    val ranks: FloatArray by lazy {
        val rank = VoidAndCluster.ranks(SIZE, sigma = 1.5, initialFill = 0.10, seed = 1)
        val n = rank.size.toFloat()
        FloatArray(rank.size) { (rank[it] + 0.5f) / n }
    }
}

/** Void-and-cluster rank assignment with incremental (one-point splat) energy updates. */
internal object VoidAndCluster {

    fun ranks(size: Int, sigma: Double, initialFill: Double, seed: Int): IntArray {
        val field = Field(size, sigma)
        val n = size * size
        val initialOnes = (n * initialFill).roundToInt().coerceIn(1, n - 1)
        val random = Random(seed)
        var placed = 0
        while (placed < initialOnes) {
            val p = random.nextInt(n)
            if (!field.bits[p]) {
                field.add(p)
                placed++
            }
        }
        field.relaxToPrototype()
        val prototypeBits = field.bits.copyOf()
        val prototypeEnergy = field.energy.copyOf()
        val rank = IntArray(n) { -1 }

        // Phase 1: peel the tightest clusters off the prototype, highest rank first.
        for (r in initialOnes - 1 downTo 0) {
            val c = field.tightestCluster()
            field.remove(c)
            rank[c] = r
        }

        // Phases 2 and 3: fill the largest voids. Phase 3 classically ranks the tightest cluster of the zeros, but
        // zero-energy = (total kernel mass) - one-energy at every cell, so its argmax among zeros is exactly the
        // argmin of one-energy among zeros: the same selection as phase 2.
        prototypeBits.copyInto(field.bits)
        prototypeEnergy.copyInto(field.energy)
        for (r in initialOnes until n) {
            val v = field.largestVoid()
            field.add(v)
            rank[v] = r
        }
        return rank
    }

    private class Field(val size: Int, sigma: Double) {
        val n = size * size
        val bits = BooleanArray(n)
        val energy = DoubleArray(n)

        /** Toroidal Gaussian indexed by (dy * size + dx) with dx, dy in [0, size). */
        private val kernel = DoubleArray(n) { idx ->
            val dx = idx % size
            val dy = idx / size
            val wx = min(dx, size - dx).toDouble()
            val wy = min(dy, size - dy).toDouble()
            exp(-(wx * wx + wy * wy) / (2.0 * sigma * sigma))
        }

        fun add(p: Int) {
            bits[p] = true
            splat(p, 1.0)
        }

        fun remove(p: Int) {
            bits[p] = false
            splat(p, -1.0)
        }

        private fun splat(p: Int, sign: Double) {
            val px = p % size
            val py = p / size
            for (qy in 0 until size) {
                val krow = Math.floorMod(qy - py, size) * size
                val erow = qy * size
                for (qx in 0 until size) {
                    energy[erow + qx] += sign * kernel[krow + Math.floorMod(qx - px, size)]
                }
            }
        }

        /** Highest-energy set cell (first in scan order on ties). */
        fun tightestCluster(): Int {
            var best = -1
            var bestE = Double.NEGATIVE_INFINITY
            for (i in 0 until n) if (bits[i] && energy[i] > bestE) { bestE = energy[i]; best = i }
            check(best >= 0) { "no set cell left" }
            return best
        }

        /** Lowest-energy empty cell (first in scan order on ties). */
        fun largestVoid(): Int {
            var best = -1
            var bestE = Double.POSITIVE_INFINITY
            for (i in 0 until n) if (!bits[i] && energy[i] < bestE) { bestE = energy[i]; best = i }
            check(best >= 0) { "no empty cell left" }
            return best
        }

        /**
         * Moves the tightest cluster into the largest void until the move would put the point back where it came
         * from. The iteration bound guards against a two-state cycle; the pattern is then still a valid start.
         */
        fun relaxToPrototype() {
            repeat(n * 4) {
                val cluster = tightestCluster()
                remove(cluster)
                val void = largestVoid()
                add(void)
                if (void == cluster) return
            }
        }
    }
}
