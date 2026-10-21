package com.akane.akastudio.core.curator.decision

import com.akane.akastudio.core.color.api.PaletteSwatch
import com.akane.akastudio.core.color.math.ColorMath
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Colour compatibility of a set of palettes, the H_color term of collage synergy.
 *
 * `H_color = 0.7 * exp(-meanEMD / 0.10) + 0.3 * wheelHarmony`, where meanEMD is the mean pairwise earth mover's
 * distance between the palettes in OKLab.
 */
object PaletteHarmony {

    /** Minimum OKLCh chroma for a swatch to carry a hue; palettes without one are neutral. */
    internal const val CHROMATIC_MIN = 0.03f
    internal const val EMD_SCALE = 0.10
    internal const val EMD_WEIGHT = 0.7
    internal const val WHEEL_WEIGHT = 0.3

    /**
     * Exact earth mover's distance between two weighted palettes with Euclidean OKLab ground distance.
     *
     * Each palette's weights are normalized to sum to 1, then the transportation problem is solved exactly with
     * successive shortest paths (Bellman-Ford on the residual network).
     *
     * @throws IllegalArgumentException when a palette is empty, has a negative or non-finite weight, or has zero
     *   total weight.
     */
    fun emd(p: List<PaletteSwatch>, q: List<PaletteSwatch>): Float {
        val supply = normalizedWeights(p)
        val demand = normalizedWeights(q)
        val cost = Array(p.size) { i -> DoubleArray(q.size) { j -> groundDistance(p[i], q[j]) } }
        return TransportSolver.minCost(supply, demand, cost).toFloat()
    }

    /**
     * Mean pairwise hue-wheel harmony. Each palette's hue is that of its highest-weight swatch with chroma >= 0.03;
     * a palette without one is neutral and pairs with anything at 1.0. Pair scores by hue difference Δh: <= 30° is
     * 1.0 (analogous), |Δh - 180| <= 25 is 0.9 (complementary), |Δh - 120| <= 20 is 0.8 (triadic), otherwise 0.5.
     * Fewer than two palettes have no pairs and score 1.0.
     */
    fun wheelHarmony(palettes: List<List<PaletteSwatch>>): Float {
        val hues = palettes.map(::dominantHue)
        return meanOverPairs(hues.size, empty = 1.0) { i, j -> pairWheel(hues[i], hues[j]) }.toFloat()
    }

    /**
     * H_color of [palettes]: `0.7 * exp(-meanEMD / 0.10) + 0.3 * wheelHarmony`. Fewer than two palettes score 1.0.
     *
     * @throws IllegalArgumentException when a palette is invalid (see [emd]).
     */
    fun colorScore(palettes: List<List<PaletteSwatch>>): Float {
        val meanEmd = meanOverPairs(palettes.size, empty = 0.0) { i, j -> emd(palettes[i], palettes[j]).toDouble() }
        return combine(meanEmd, wheelHarmony(palettes).toDouble())
    }

    internal fun combine(meanEmd: Double, wheel: Double): Float =
        (EMD_WEIGHT * exp(-meanEmd / EMD_SCALE) + WHEEL_WEIGHT * wheel).toFloat()

    /** Hue in degrees of the highest-weight chromatic swatch (first on ties), or null for a neutral palette. */
    internal fun dominantHue(palette: List<PaletteSwatch>): Float? {
        var best: PaletteSwatch? = null
        var bestHue = 0f
        for (swatch in palette) {
            val lch = ColorMath.okLabToOkLch(swatch.lab)
            if (lch[1] >= CHROMATIC_MIN && (best == null || swatch.weight > best.weight)) {
                best = swatch
                bestHue = lch[2]
            }
        }
        return if (best == null) null else bestHue
    }

    /** Harmony of two dominant hues; null (neutral) pairs with anything at 1.0. */
    internal fun pairWheel(h1: Float?, h2: Float?): Double {
        if (h1 == null || h2 == null) return 1.0
        var delta = abs(h1.toDouble() - h2.toDouble()) % 360.0
        if (delta > 180.0) delta = 360.0 - delta
        return when {
            delta <= 30.0 -> 1.0
            abs(delta - 180.0) <= 25.0 -> 0.9
            abs(delta - 120.0) <= 20.0 -> 0.8
            else -> 0.5
        }
    }

    /** Mean of [pair] over all index pairs i < j of [n] items, in row-major order; [empty] when there are no pairs. */
    internal inline fun meanOverPairs(n: Int, empty: Double, pair: (Int, Int) -> Double): Double {
        if (n < 2) return empty
        var sum = 0.0
        var count = 0
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                sum += pair(i, j)
                count++
            }
        }
        return sum / count
    }

    internal fun groundDistance(a: PaletteSwatch, b: PaletteSwatch): Double {
        val dl = a.lab.l.toDouble() - b.lab.l
        val da = a.lab.a.toDouble() - b.lab.a
        val db = a.lab.b.toDouble() - b.lab.b
        return sqrt(dl * dl + da * da + db * db)
    }

    internal fun normalizedWeights(palette: List<PaletteSwatch>): DoubleArray {
        require(palette.isNotEmpty()) { "palette must not be empty" }
        var total = 0.0
        for (swatch in palette) {
            require(swatch.weight.isFinite() && swatch.weight >= 0f) { "invalid swatch weight: ${swatch.weight}" }
            total += swatch.weight
        }
        require(total > 0.0) { "palette weights must not all be zero" }
        return DoubleArray(palette.size) { palette[it].weight / total }
    }
}
