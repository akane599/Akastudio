package com.akane.akastudio.core.curator.metrics

import com.akane.akastudio.core.curator.api.SaliencyMap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Geometric composition score in [0,1] from a saliency map:
 * `0.6*thirds + 0.25*balance + 0.15*spread`.
 */
object Composition {
    private const val THIRDS_SIGMA = 0.09
    private const val CENTRE_WEIGHT = 0.6
    private const val BALANCE_GAIN = 1.5f
    private const val SPREAD_ALLOWANCE = 0.35f

    /** Std of the mass of a uniform map over the unit square, `sqrt(1/12 + 1/12)`; normalizes [spreadOf]. */
    private val UNIFORM_STD = sqrt(1.0 / 6.0)

    private const val PHI_LOW = 0.382
    private const val PHI_HIGH = 0.618

    /** Candidate points `(x, y, weight)`: rule-of-thirds intersections, phi-grid points, the centre. */
    private val POINTS: List<Triple<Double, Double, Double>> = buildList {
        for (x in listOf(1.0 / 3, 2.0 / 3)) for (y in listOf(1.0 / 3, 2.0 / 3)) add(Triple(x, y, 1.0))
        for (x in listOf(PHI_LOW, PHI_HIGH)) for (y in listOf(PHI_LOW, PHI_HIGH)) add(Triple(x, y, 1.0))
        add(Triple(0.5, 0.5, CENTRE_WEIGHT))
    }

    /**
     * Scores [saliency]. With its centre of mass (cx, cy) in normalized coordinates:
     * `thirds = max_p w_p * exp(-d^2 / (2*0.09^2))`, `balance = 1 - clamp(|massLeft - massRight| * 1.5)`,
     * `spread = 1 - clamp(normalizedStd - 0.35)` where normalizedStd is the spatial std of the mass around
     * its centre divided by that of a uniform map. A map with no mass scores as a centred, balanced,
     * fully scattered subject.
     */
    fun score(saliency: SaliencyMap): Float {
        val stats = MassStats.of(saliency)
        var thirds = 0.0
        for ((px, py, weight) in POINTS) {
            val dx = stats.cx - px
            val dy = stats.cy - py
            thirds = maxOf(thirds, weight * exp(-(dx * dx + dy * dy) / (2 * THIRDS_SIGMA * THIRDS_SIGMA)))
        }
        val balance = 1f - clamp01(abs(stats.left - stats.right).toFloat() * BALANCE_GAIN)
        val spread = 1f - clamp01((stats.std / UNIFORM_STD).toFloat() - SPREAD_ALLOWANCE)
        return clamp01(0.6f * thirds.toFloat() + 0.25f * balance + 0.15f * spread)
    }

    /** Mass moments of a saliency map in normalized coordinates (cell centres at `(x + 0.5) / width`). */
    private class MassStats(val cx: Double, val cy: Double, val left: Double, val right: Double, val std: Double) {
        companion object {
            fun of(map: SaliencyMap): MassStats {
                val w = map.width
                val h = map.height
                var total = 0.0
                var sx = 0.0
                var sy = 0.0
                var left = 0.0
                var right = 0.0
                for (y in 0 until h) {
                    val v = (y + 0.5) / h
                    for (x in 0 until w) {
                        val m = map.values[y * w + x].toDouble().coerceAtLeast(0.0)
                        if (m == 0.0) continue
                        val u = (x + 0.5) / w
                        total += m
                        sx += m * u
                        sy += m * v
                        when {
                            u < 0.5 -> left += m
                            u > 0.5 -> right += m
                            else -> { left += m / 2; right += m / 2 }
                        }
                    }
                }
                if (total <= 0.0) return MassStats(0.5, 0.5, 0.5, 0.5, UNIFORM_STD)
                val cx = sx / total
                val cy = sy / total
                var variance = 0.0
                for (y in 0 until h) {
                    val dy = (y + 0.5) / h - cy
                    for (x in 0 until w) {
                        val m = map.values[y * w + x].toDouble().coerceAtLeast(0.0)
                        val dx = (x + 0.5) / w - cx
                        variance += m * (dx * dx + dy * dy)
                    }
                }
                return MassStats(cx, cy, left / total, right / total, sqrt(variance / total))
            }
        }
    }
}
