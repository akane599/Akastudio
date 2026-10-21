package com.akane.akastudio.core.curator.decision

import com.akane.akastudio.core.curator.api.PhotoAnalysis
import com.akane.akastudio.core.curator.api.SceneCluster
import com.akane.akastudio.core.curator.hash.PerceptualHash
import kotlin.math.sqrt

/**
 * Burst clustering: groups near-duplicate shots so only the best of each burst (its champion) competes.
 *
 * Union-find over every pair of non-rejected photos. A pair joins when the embedding cosine is greater than
 * [DecisionConfig.clusterCosine], OR the pHash Hamming distance is at most [DecisionConfig.clusterPHashMax], OR the
 * dHash Hamming distance is at most [DecisionConfig.clusterDHashMax]. Joins are transitive.
 */
object Clustering {

    /**
     * Clusters the non-rejected photos of [analyses]; photos with a `rejectReason` are ignored.
     *
     * Cluster ids are `0..` in order of each cluster's best quality, descending (ties by champion id). Members are
     * ordered by quality descending, ties by id, so the champion (the highest-quality member) is always first.
     * The result does not depend on the order of [analyses].
     *
     * @throws IllegalArgumentException when two analyses share a `candidateId`.
     */
    fun cluster(analyses: List<PhotoAnalysis>, config: DecisionConfig = DecisionConfig()): List<SceneCluster> {
        requireDistinctIds(analyses)
        val photos = analyses.filter { it.rejectReason == null }.sortedBy { it.candidateId }
        val parent = IntArray(photos.size) { it }
        for (i in photos.indices) {
            for (j in i + 1 until photos.size) {
                if (isNearDuplicate(photos[i], photos[j], config)) union(parent, i, j)
            }
        }
        return photos.indices
            .groupBy { find(parent, it) }
            .values
            .map { group -> group.map { photos[it] }.sortedWith(BY_QUALITY_THEN_ID) }
            .sortedWith(compareByDescending<List<PhotoAnalysis>> { it.first().quality }.thenBy { it.first().candidateId })
            .mapIndexed { id, members ->
                SceneCluster(id = id, memberIds = members.map { it.candidateId }, championId = members.first().candidateId)
            }
    }

    /** Cosine similarity of two embeddings; mismatched dimensions, empty or zero-norm vectors count as 0. */
    internal fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (k in a.indices) {
            dot += a[k].toDouble() * b[k]
            normA += a[k].toDouble() * a[k]
            normB += b[k].toDouble() * b[k]
        }
        if (normA <= 0.0 || normB <= 0.0) return 0f
        return (dot / (sqrt(normA) * sqrt(normB))).toFloat()
    }

    internal fun isNearDuplicate(a: PhotoAnalysis, b: PhotoAnalysis, config: DecisionConfig): Boolean =
        PerceptualHash.hamming(a.pHash, b.pHash) <= config.clusterPHashMax ||
            PerceptualHash.hamming(a.dHash, b.dHash) <= config.clusterDHashMax ||
            cosine(a.embedding, b.embedding) > config.clusterCosine

    internal fun requireDistinctIds(analyses: List<PhotoAnalysis>) {
        val seen = HashSet<String>(analyses.size * 2)
        for (analysis in analyses) {
            require(seen.add(analysis.candidateId)) { "duplicate candidateId: ${analysis.candidateId}" }
        }
    }

    /** Quality descending, ties by id ascending. */
    internal val BY_QUALITY_THEN_ID: Comparator<PhotoAnalysis> =
        compareByDescending<PhotoAnalysis> { it.quality }.thenBy { it.candidateId }

    private fun find(parent: IntArray, x: Int): Int {
        var root = x
        while (parent[root] != root) root = parent[root]
        var node = x
        while (parent[node] != root) {
            val next = parent[node]
            parent[node] = root
            node = next
        }
        return root
    }

    private fun union(parent: IntArray, a: Int, b: Int) {
        val rootA = find(parent, a)
        val rootB = find(parent, b)
        if (rootA != rootB) parent[maxOf(rootA, rootB)] = minOf(rootA, rootB)
    }
}
