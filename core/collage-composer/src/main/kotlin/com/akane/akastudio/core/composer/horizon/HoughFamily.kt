package com.akane.akastudio.core.composer.horizon

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Magnitude-weighted Hough accumulator for one line family: normals within ±[HALF_RANGE_DEGREES] of
 * [normalDegrees] at [STEP_DEGREES] resolution and ρ bins of 1 px, with ρ measured from the image centre
 * (`ρ = x·cos φ + y·sin φ`, y down). A near-horizontal line has a normal near 90°, a near-vertical one near 0°.
 *
 * Only edges whose gradient points within [GATE_DEGREES] of the family normal vote, so the two families do not
 * see each other's edges. Each vote is split linearly between the two nearest ρ bins; an unweighted count of the
 * nearest bin backs the line-length check.
 */
internal class HoughFamily(
    edges: EdgeMap,
    private val width: Int,
    private val height: Int,
    private val normalDegrees: Double,
) {
    private val angleCount = (2 * HALF_RANGE_DEGREES / STEP_DEGREES).roundToInt() + 1
    private val rhoOffset = ceil(hypot(width.toDouble(), height.toDouble()) / 2).toInt() + 2
    private val rhoCount = 2 * rhoOffset + 1
    private val votes = FloatArray(angleCount * rhoCount)
    private val counts = IntArray(angleCount * rhoCount)

    /** Mean accumulator value over every bin of the family. */
    val mean: Double

    init {
        val cosTable = DoubleArray(angleCount)
        val sinTable = DoubleArray(angleCount)
        for (k in 0 until angleCount) {
            val phi = Math.toRadians(normalDegrees + deviationOf(k))
            cosTable[k] = cos(phi)
            sinTable[k] = sin(phi)
        }
        val cx = (width - 1) / 2.0
        val cy = (height - 1) / 2.0
        for (e in 0 until edges.size) {
            if (!gatePasses(edges.directions[e])) continue
            val x = edges.xs[e] - cx
            val y = edges.ys[e] - cy
            val m = edges.magnitudes[e]
            for (k in 0 until angleCount) {
                val r = x * cosTable[k] + y * sinTable[k] + rhoOffset
                val i0 = floor(r).toInt()
                val frac = (r - i0).toFloat()
                val row = k * rhoCount
                votes[row + i0] += m * (1f - frac)
                votes[row + i0 + 1] += m * frac
                counts[row + r.roundToInt()]++
            }
        }
        var sum = 0.0
        for (v in votes) sum += v
        mean = sum / votes.size
    }

    /**
     * Up to [n] strongest bins, strongest first; each pick suppresses bins within ±[SUPPRESS_DEGREES] and
     * ±[SUPPRESS_RHO] px so the next pick is a distinct line. Empty bins are never peaks.
     */
    fun topPeaks(n: Int): List<Peak> {
        val suppressed = BooleanArray(votes.size)
        val peaks = ArrayList<Peak>(n)
        val angleWindow = (SUPPRESS_DEGREES / STEP_DEGREES).roundToInt()
        repeat(n) {
            var best = -1
            for (i in votes.indices) {
                if (!suppressed[i] && votes[i] > 0f && (best < 0 || votes[i] > votes[best])) best = i
            }
            if (best < 0) return peaks
            val k = best / rhoCount
            val r = best % rhoCount
            peaks += Peak(deviationOf(k), votes[best].toDouble(), lineLength(k, r))
            for (kk in max(0, k - angleWindow)..min(angleCount - 1, k + angleWindow)) {
                for (rr in max(0, r - SUPPRESS_RHO)..min(rhoCount - 1, r + SUPPRESS_RHO)) suppressed[kk * rhoCount + rr] = true
            }
        }
        return peaks
    }

    /**
     * A peak is confident when it exceeds [PEAK_TO_MEAN] × the family mean and its line spans at least
     * [MIN_LENGTH_FRACTION] of the image dimension the family runs along (width for horizontals, height for
     * verticals).
     */
    fun isConfident(peak: Peak): Boolean {
        val dimension = if (abs(normalDegrees - 90.0) < 45.0) width else height
        return peak.value > PEAK_TO_MEAN * mean && peak.lengthPx >= MIN_LENGTH_FRACTION * dimension
    }

    /** Edge pixels on the line, counted within ±1 ρ bin to absorb staircase quantization. */
    private fun lineLength(k: Int, r: Int): Int {
        var total = 0
        for (rr in max(0, r - 1)..min(rhoCount - 1, r + 1)) total += counts[k * rhoCount + rr]
        return total
    }

    private fun gatePasses(gradient: Float): Boolean {
        var diff = abs(Math.toDegrees(gradient.toDouble()) - normalDegrees) % 180.0
        if (diff > 90.0) diff = 180.0 - diff
        return diff <= GATE_DEGREES
    }

    private fun deviationOf(k: Int): Double = -HALF_RANGE_DEGREES + k * STEP_DEGREES

    /** An accumulator peak: normal deviation from the family centre in degrees, weighted votes, line length. */
    data class Peak(val deviationDegrees: Double, val value: Double, val lengthPx: Int)

    companion object {
        const val HALF_RANGE_DEGREES = 7.0
        const val STEP_DEGREES = 0.1
        const val GATE_DEGREES = 22.0
        const val PEAK_TO_MEAN = 2.5
        const val MIN_LENGTH_FRACTION = 0.35
        const val SUPPRESS_DEGREES = 1.0
        const val SUPPRESS_RHO = 10
    }
}
