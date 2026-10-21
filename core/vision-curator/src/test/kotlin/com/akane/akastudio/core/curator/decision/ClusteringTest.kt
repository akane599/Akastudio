package com.akane.akastudio.core.curator.decision

import com.akane.akastudio.core.curator.api.RejectReason
import com.akane.akastudio.core.curator.decision.Fixtures.analysis
import com.akane.akastudio.core.curator.decision.Fixtures.flip
import com.akane.akastudio.core.curator.decision.Fixtures.randomHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

class ClusteringTest {

    private fun memberSets(ids: List<String>, vararg photos: com.akane.akastudio.core.curator.api.PhotoAnalysis) =
        Clustering.cluster(photos.toList()).map { it.memberIds.toSet() }.toSet().also { sets ->
            assertEquals(ids.toSet(), sets.flatten().toSet())
        }

    @Test
    fun unrelatedPhotos_stayInSeparateClusters() {
        val sets = memberSets(listOf("a", "b", "c"), analysis("a"), analysis("b"), analysis("c"))
        assertEquals(setOf(setOf("a"), setOf("b"), setOf("c")), sets)
    }

    @Test
    fun cosineAboveThreshold_merges() {
        val shared = Fixtures.randomEmbedding("shared")
        val sets = memberSets(
            listOf("a", "b"),
            analysis("a", embedding = shared),
            analysis("b", embedding = shared.copyOf()),
        )
        assertEquals(setOf(setOf("a", "b")), sets)
    }

    @Test
    fun cosineBelowThresholdDoesNotMerge_aboveDoes() {
        // Unit vectors at cosine 0.81 and 0.83 around the 0.82 threshold (hashes stay unrelated).
        val base = floatArrayOf(1f, 0f)
        val at = floatArrayOf(0.81f, sqrt(1f - 0.81f * 0.81f))
        val above = floatArrayOf(0.83f, sqrt(1f - 0.83f * 0.83f))
        val config = DecisionConfig()
        assertEquals(false, Clustering.isNearDuplicate(analysis("a", embedding = base), analysis("b", embedding = at), config))
        assertEquals(true, Clustering.isNearDuplicate(analysis("a", embedding = base), analysis("b", embedding = above), config))
    }

    @Test
    fun pHashWithinTenBits_merges_elevenDoesNot() {
        val p = randomHash("burst", 9)
        assertEquals(
            setOf(setOf("a", "b")),
            memberSets(listOf("a", "b"), analysis("a", pHash = p), analysis("b", pHash = flip(p, 10))),
        )
        assertEquals(
            setOf(setOf("a"), setOf("b")),
            memberSets(listOf("a", "b"), analysis("a", pHash = p), analysis("b", pHash = flip(p, 11))),
        )
    }

    @Test
    fun dHashWithinEightBits_merges_nineDoesNot() {
        val d = randomHash("burst", 3)
        assertEquals(
            setOf(setOf("a", "b")),
            memberSets(listOf("a", "b"), analysis("a", dHash = d), analysis("b", dHash = flip(d, 8))),
        )
        assertEquals(
            setOf(setOf("a"), setOf("b")),
            memberSets(listOf("a", "b"), analysis("a", dHash = d), analysis("b", dHash = flip(d, 9))),
        )
    }

    @Test
    fun transitiveChain_mergesIntoOneCluster() {
        // a~b by pHash, b~c by dHash, a and c unrelated directly.
        val p = randomHash("chain", 1)
        val d = randomHash("chain", 2)
        val a = analysis("a", pHash = p)
        val b = analysis("b", pHash = flip(p, 2), dHash = d)
        val c = analysis("c", dHash = flip(d, 3))
        assertEquals(false, Clustering.isNearDuplicate(a, c, DecisionConfig()))
        assertEquals(setOf(setOf("a", "b", "c")), memberSets(listOf("a", "b", "c"), a, b, c))
    }

    @Test
    fun embeddingDimensionMismatch_countsAsZeroAndDoesNotMerge() {
        assertEquals(0f, Clustering.cosine(floatArrayOf(1f, 0f, 0f), floatArrayOf(1f, 0f, 0f, 0f)), 0f)
        val sets = memberSets(
            listOf("a", "b"),
            analysis("a", embedding = floatArrayOf(1f, 0f, 0f)),
            analysis("b", embedding = floatArrayOf(1f, 0f, 0f, 0f)),
        )
        assertEquals(setOf(setOf("a"), setOf("b")), sets)
    }

    @Test
    fun rejectedPhotos_areExcluded() {
        val p = randomHash("dup", 1)
        val clusters = Clustering.cluster(
            listOf(
                analysis("a", pHash = p),
                analysis("r", pHash = p, reject = RejectReason.BLURRY),
                analysis("b"),
            ),
        )
        assertEquals(setOf("a", "b"), clusters.flatMap { it.memberIds }.toSet())
    }

    @Test
    fun championIsMaxQuality_membersOrderedByQualityThenId_idsByBestQuality() {
        val p = randomHash("burst", 4)
        val clusters = Clustering.cluster(
            listOf(
                analysis("solo", quality = 0.95f),
                analysis("b1", quality = 0.6f, pHash = p),
                analysis("b2", quality = 0.9f, pHash = flip(p, 1)),
                analysis("b0", quality = 0.6f, pHash = flip(p, 2)),
                analysis("low", quality = 0.3f),
            ),
        )
        assertEquals(listOf(0, 1, 2), clusters.map { it.id })
        assertEquals(listOf("solo"), clusters[0].memberIds)
        assertEquals(listOf("b2", "b0", "b1"), clusters[1].memberIds)
        assertEquals("b2", clusters[1].championId)
        assertEquals(listOf("low"), clusters[2].memberIds)
    }

    @Test
    fun resultIsIndependentOfInputOrder() {
        val p = randomHash("x", 1)
        val photos = listOf(
            analysis("a", quality = 0.5f, pHash = p),
            analysis("b", quality = 0.8f, pHash = flip(p, 4)),
            analysis("c", quality = 0.7f),
            analysis("d", quality = 0.7f),
        )
        assertEquals(Clustering.cluster(photos), Clustering.cluster(photos.reversed()))
    }

    @Test
    fun duplicateIds_areRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            Clustering.cluster(listOf(analysis("a"), analysis("a")))
        }
    }

    // ---- Cancellation checkpoints (AUD-05) ----

    @Test
    fun checkpoint_runsAtEntryEachRowAndEveryStrideOfPairs_withoutChangingTheResult() {
        val photos = List(300) { analysis("p$it") }
        var calls = 0
        val checked = Clustering.cluster(photos) { calls++ }

        assertEquals(Clustering.cluster(photos), checked)
        val n = photos.size
        val strideHits = (0 until n).sumOf { i -> (i + 1 until n).count { j -> j % Clustering.CHECKPOINT_STRIDE == 0 } }
        assertEquals(1 + n + strideHits, calls)
    }

    @Test
    fun checkpointThrowingCancellation_stopsThePairLoopBeforeItCompletes() {
        val photos = List(300) { analysis("p$it") }
        var total = 0
        Clustering.cluster(photos) { total++ }
        val stopAt = total / 2   // about a quarter of the rows (row i compares n - 1 - i pairs)
        var calls = 0

        val thrown = assertThrows(CancellationException::class.java) {
            Clustering.cluster(photos) { if (++calls == stopAt) throw CancellationException("collector cancelled") }
        }

        assertEquals("collector cancelled", thrown.message)
        // No checkpoint ran after the throwing one: the remaining rows and pairs were never compared.
        assertEquals(stopAt, calls)
        assertTrue(calls < total)
    }

    private fun sqrt(x: Float): Float = kotlin.math.sqrt(x)
}
