package com.akane.akastudio.core.curator.decision

/**
 * Exact solver for the balanced transportation problem behind the earth mover's distance.
 *
 * Builds the network source -> supply nodes -> demand nodes -> sink (supply/demand edges carry the masses, the
 * dense middle edges are uncapacitated with the ground cost) and runs successive shortest paths: Bellman-Ford finds
 * the cheapest residual augmenting path, which may use negative-cost reverse edges, and its bottleneck is pushed
 * until no positive-capacity path remains. Every augmentation along a shortest path keeps the flow cost-optimal for
 * its value, so the final flow is an optimal transport plan.
 */
internal object TransportSolver {

    /** Residual capacities at or below this are treated as exhausted (absorbs floating-point dust). */
    private const val EPS = 1e-12

    /** Hard stop for a non-terminating augmentation sequence; small dense instances need a handful of rounds. */
    private const val MAX_AUGMENTATIONS = 10_000

    /** A distance must improve by more than this to relax; stops rounding noise from forming zero-cost cycles. */
    private const val RELAX_EPS = 1e-15

    /**
     * Minimum total cost of moving [supply] onto [demand] with per-unit costs [cost] (`cost[i][j]` from supply i to
     * demand j). Both mass vectors should have the same total; the flow stops at the smaller of the two.
     */
    fun minCost(supply: DoubleArray, demand: DoubleArray, cost: Array<DoubleArray>): Double {
        val n = supply.size
        val m = demand.size
        require(cost.size == n && cost.all { it.size == m }) { "cost matrix must be ${n}x$m" }
        val network = Network(nodeCount = n + m + 2, edgeCapacity = 2 * (n + n * m + m))
        val source = 0
        val sink = n + m + 1
        for (i in 0 until n) network.addEdge(source, 1 + i, supply[i], 0.0)
        for (i in 0 until n) {
            for (j in 0 until m) network.addEdge(1 + i, 1 + n + j, Double.POSITIVE_INFINITY, cost[i][j])
        }
        for (j in 0 until m) network.addEdge(1 + n + j, sink, demand[j], 0.0)

        var total = 0.0
        repeat(MAX_AUGMENTATIONS) {
            val pathCost = network.shortestPath(source, sink) ?: return total
            val pushed = network.augment(source, sink)
            if (pushed <= EPS) return total
            total += pushed * pathCost
        }
        throw IllegalStateException("transport solver did not converge after $MAX_AUGMENTATIONS augmentations")
    }

    private class Network(private val nodeCount: Int, edgeCapacity: Int) {
        private val from = IntArray(edgeCapacity)
        private val to = IntArray(edgeCapacity)
        private val capacity = DoubleArray(edgeCapacity)
        private val cost = DoubleArray(edgeCapacity)
        private var edgeCount = 0
        private val dist = DoubleArray(nodeCount)
        private val previousEdge = IntArray(nodeCount)

        /** Adds edge `u -> v` and its zero-capacity reverse at index `e xor 1`. */
        fun addEdge(u: Int, v: Int, cap: Double, unitCost: Double) {
            put(u, v, cap, unitCost)
            put(v, u, 0.0, -unitCost)
        }

        private fun put(u: Int, v: Int, cap: Double, unitCost: Double) {
            from[edgeCount] = u
            to[edgeCount] = v
            capacity[edgeCount] = cap
            cost[edgeCount] = unitCost
            edgeCount++
        }

        /** Bellman-Ford over residual edges; returns the sink distance, or null when the sink is unreachable. */
        fun shortestPath(source: Int, sink: Int): Double? {
            dist.fill(Double.POSITIVE_INFINITY)
            previousEdge.fill(-1)
            dist[source] = 0.0
            for (round in 0 until nodeCount - 1) {
                var relaxed = false
                for (e in 0 until edgeCount) {
                    if (capacity[e] <= EPS) continue
                    val u = from[e]
                    if (dist[u] == Double.POSITIVE_INFINITY) continue
                    val candidate = dist[u] + cost[e]
                    if (candidate < dist[to[e]] - RELAX_EPS) {
                        dist[to[e]] = candidate
                        previousEdge[to[e]] = e
                        relaxed = true
                    }
                }
                if (!relaxed) break
            }
            return if (dist[sink] == Double.POSITIVE_INFINITY) null else dist[sink]
        }

        /** Pushes the bottleneck along the path recorded by [shortestPath] and returns the pushed amount. */
        fun augment(source: Int, sink: Int): Double {
            var bottleneck = Double.POSITIVE_INFINITY
            var node = sink
            var steps = 0
            while (node != source) {
                check(++steps <= nodeCount) { "transport solver found a cyclic predecessor chain" }
                val e = previousEdge[node]
                bottleneck = minOf(bottleneck, capacity[e])
                node = from[e]
            }
            node = sink
            while (node != source) {
                val e = previousEdge[node]
                capacity[e] -= bottleneck
                capacity[e xor 1] += bottleneck
                node = from[e]
            }
            return bottleneck
        }
    }
}
