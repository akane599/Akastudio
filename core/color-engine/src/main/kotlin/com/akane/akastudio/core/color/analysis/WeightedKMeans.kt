package com.akane.akastudio.core.color.analysis

import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Weighted K-means++ over 3-D points (OKLab here) with Lloyd refinement.
 *
 * Points live in flat channel arrays; [weights] are the per-point masses (non-negative, at least one
 * positive). Distances are squared Euclidean.
 */
internal class WeightedKMeans(
    private val x: DoubleArray,
    private val y: DoubleArray,
    private val z: DoubleArray,
    private val weights: DoubleArray,
) {
    private val n = x.size

    init {
        require(n > 0 && y.size == n && z.size == n && weights.size == n) { "point arrays must be non-empty and equal-sized" }
        require(weights.all { it >= 0.0 && it.isFinite() } && weights.any { it > 0.0 }) {
            "weights must be finite, non-negative and not all zero"
        }
    }

    /** Final centroids (one row per cluster) and the total point weight assigned to each. */
    class Result(val centroids: Array<DoubleArray>, val clusterWeights: DoubleArray)

    /**
     * Seeds up to [k] centroids with weighted K-means++ drawn from [random] (fewer when the data hold
     * fewer than [k] distinct points), then runs at most [maxIterations] Lloyd steps, stopping once no
     * centroid moves by [tolerance] or more. A cluster left empty is re-seeded at the point farthest
     * from its own centroid.
     */
    fun run(k: Int, random: Random, maxIterations: Int, tolerance: Double): Result {
        require(k >= 1) { "k must be >= 1, was $k" }
        var centroids = seed(k, random)
        val labels = IntArray(n)
        val ownDistance = DoubleArray(n)
        for (iteration in 0 until maxIterations) {
            assign(centroids, labels, ownDistance)
            val updated = weightedMeans(centroids, labels)
            val reseeded = reseedEmpty(updated.centroids, updated.clusterWeights, ownDistance)
            val shift = maxShift(centroids, updated.centroids)
            centroids = updated.centroids
            if (!reseeded && shift < tolerance) break
        }
        assign(centroids, labels, ownDistance)
        val clusterWeights = DoubleArray(centroids.size)
        for (i in 0 until n) clusterWeights[labels[i]] += weights[i]
        return Result(centroids, clusterWeights)
    }

    private fun seed(k: Int, random: Random): Array<DoubleArray> {
        val chosen = ArrayList<DoubleArray>(k)
        val first = pickWeighted(random) { weights[it] }
        chosen += point(first)
        val nearest = DoubleArray(n) { distance2(it, chosen[0]) }
        while (chosen.size < k) {
            var total = 0.0
            for (i in 0 until n) total += weights[i] * nearest[i]
            if (total <= 0.0) break
            val next = point(pickWeighted(random) { weights[it] * nearest[it] })
            chosen += next
            for (i in 0 until n) nearest[i] = minOf(nearest[i], distance2(i, next))
        }
        return chosen.toTypedArray()
    }

    /** Index drawn with probability proportional to [mass]; the total mass must be positive. */
    private inline fun pickWeighted(random: Random, mass: (Int) -> Double): Int {
        var total = 0.0
        for (i in 0 until n) total += mass(i)
        val target = random.nextDouble() * total
        var cumulative = 0.0
        var lastPositive = -1
        for (i in 0 until n) {
            val m = mass(i)
            if (m <= 0.0) continue
            lastPositive = i
            cumulative += m
            if (cumulative > target) return i
        }
        return lastPositive
    }

    /** Nearest-centroid labels (ties go to the lower index) and each point's squared distance to it. */
    private fun assign(centroids: Array<DoubleArray>, labels: IntArray, ownDistance: DoubleArray) {
        for (i in 0 until n) {
            var best = 0
            var bestDistance = distance2(i, centroids[0])
            for (j in 1 until centroids.size) {
                val d = distance2(i, centroids[j])
                if (d < bestDistance) {
                    bestDistance = d
                    best = j
                }
            }
            labels[i] = best
            ownDistance[i] = bestDistance
        }
    }

    /** Weighted mean of each cluster; an empty cluster keeps its previous centroid and weight 0. */
    private fun weightedMeans(previous: Array<DoubleArray>, labels: IntArray): Result {
        val count = previous.size
        val sums = Array(count) { DoubleArray(3) }
        val mass = DoubleArray(count)
        for (i in 0 until n) {
            val j = labels[i]
            val w = weights[i]
            sums[j][0] += w * x[i]
            sums[j][1] += w * y[i]
            sums[j][2] += w * z[i]
            mass[j] += w
        }
        val centroids = Array(count) { j ->
            if (mass[j] > 0.0) doubleArrayOf(sums[j][0] / mass[j], sums[j][1] / mass[j], sums[j][2] / mass[j])
            else previous[j].copyOf()
        }
        return Result(centroids, mass)
    }

    /**
     * Moves each empty cluster onto the farthest not-yet-used point (largest distance to its own
     * centroid). Returns whether any centroid was re-seeded; nothing moves when every point already
     * coincides with its centroid.
     */
    private fun reseedEmpty(centroids: Array<DoubleArray>, mass: DoubleArray, ownDistance: DoubleArray): Boolean {
        var reseeded = false
        for (j in centroids.indices) {
            if (mass[j] > 0.0) continue
            var farthest = -1
            var farthestDistance = 0.0
            for (i in 0 until n) {
                if (weights[i] > 0.0 && ownDistance[i] > farthestDistance) {
                    farthestDistance = ownDistance[i]
                    farthest = i
                }
            }
            if (farthest < 0) continue
            centroids[j] = point(farthest)
            ownDistance[farthest] = 0.0
            reseeded = true
        }
        return reseeded
    }

    private fun maxShift(before: Array<DoubleArray>, after: Array<DoubleArray>): Double {
        var worst = 0.0
        for (j in before.indices) {
            val dx = before[j][0] - after[j][0]
            val dy = before[j][1] - after[j][1]
            val dz = before[j][2] - after[j][2]
            worst = maxOf(worst, sqrt(dx * dx + dy * dy + dz * dz))
        }
        return worst
    }

    private fun point(i: Int): DoubleArray = doubleArrayOf(x[i], y[i], z[i])

    private fun distance2(i: Int, c: DoubleArray): Double {
        val dx = x[i] - c[0]
        val dy = y[i] - c[1]
        val dz = z[i] - c[2]
        return dx * dx + dy * dy + dz * dz
    }
}
