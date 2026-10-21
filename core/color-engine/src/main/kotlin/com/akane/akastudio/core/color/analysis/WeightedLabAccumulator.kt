package com.akane.akastudio.core.color.analysis

import com.akane.akastudio.core.color.api.LabStats
import com.akane.akastudio.core.color.api.OkLab
import kotlin.math.sqrt

/**
 * Weighted mean and weighted population standard deviation of OKLab samples, accumulated in double
 * precision with West's incremental algorithm so a constant input yields exactly zero spread.
 */
internal class WeightedLabAccumulator {
    private var weightSum = 0.0
    private val mean = DoubleArray(CHANNELS)
    private val m2 = DoubleArray(CHANNELS)

    val isEmpty: Boolean get() = weightSum == 0.0

    /** Adds [lab] with a non-negative finite [weight]; zero-weight samples are ignored. */
    fun add(lab: OkLab, weight: Double) {
        require(weight >= 0.0 && weight.isFinite()) { "weight must be finite and >= 0, was $weight" }
        if (weight == 0.0) return
        if (weightSum == 0.0) {
            weightSum = weight
            mean[0] = lab.l.toDouble()
            mean[1] = lab.a.toDouble()
            mean[2] = lab.b.toDouble()
            m2.fill(0.0)
            return
        }
        weightSum += weight
        update(0, lab.l.toDouble(), weight)
        update(1, lab.a.toDouble(), weight)
        update(2, lab.b.toDouble(), weight)
    }

    /** Snapshot of the accumulated statistics; requires at least one positively weighted sample. */
    fun toLabStats(): LabStats {
        check(!isEmpty) { "no weighted samples accumulated" }
        return LabStats(
            meanL = mean[0].toFloat(),
            meanA = mean[1].toFloat(),
            meanB = mean[2].toFloat(),
            stdL = std(0),
            stdA = std(1),
            stdB = std(2),
            weight = weightSum.toFloat(),
        )
    }

    private fun update(channel: Int, x: Double, weight: Double) {
        val delta = x - mean[channel]
        mean[channel] += delta * weight / weightSum
        m2[channel] += weight * delta * (x - mean[channel])
    }

    private fun std(channel: Int): Float = sqrt((m2[channel] / weightSum).coerceAtLeast(0.0)).toFloat()

    private companion object {
        const val CHANNELS = 3
    }
}
