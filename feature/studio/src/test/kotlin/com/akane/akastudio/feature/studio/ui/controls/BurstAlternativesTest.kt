package com.akane.akastudio.feature.studio.ui.controls

import com.akane.akastudio.core.curator.api.RejectReason
import com.akane.akastudio.core.curator.api.SceneCluster
import com.akane.akastudio.core.curator.api.StoryMode
import com.akane.akastudio.feature.studio.domain.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BurstAlternativesTest {

    // Cluster 0: a1 (champion, in story), a2, a3 (rejected), a4. Cluster 1: b1 (champion, in story), b2.
    // Cluster 2: c1 (champion), c2. Cluster 3: d1 (champion, rejected-free, high quality).
    private val analyses = listOf(
        TestData.analysis("a1", quality = 0.9f),
        TestData.analysis("a2", quality = 0.6f),
        TestData.analysis("a3", quality = 0.95f, rejected = RejectReason.BLURRY),
        TestData.analysis("a4", quality = 0.8f),
        TestData.analysis("b1", quality = 0.85f),
        TestData.analysis("b2", quality = 0.7f),
        TestData.analysis("c1", quality = 0.5f),
        TestData.analysis("c2", quality = 0.4f),
        TestData.analysis("d1", quality = 0.75f),
    )
    private val clusters = listOf(
        SceneCluster(0, listOf("a1", "a2", "a3", "a4"), championId = "a1"),
        SceneCluster(1, listOf("b1", "b2"), championId = "b1"),
        SceneCluster(2, listOf("c1", "c2"), championId = "c1"),
        SceneCluster(3, listOf("d1"), championId = "d1"),
    )
    private val curation = TestData.result(
        chosenMode = StoryMode.COLLAGE_2,
        options = mapOf(StoryMode.HERO to listOf("a1"), StoryMode.COLLAGE_2 to listOf("a1", "b1")),
        analyses = analyses,
        clusters = clusters,
    )
    private val story = listOf("a1", "b1")

    @Test
    fun `cluster members come first by quality, then other champions by quality`() {
        val options = BurstAlternatives.build(curation, story, slot = 0)
        assertEquals(listOf("a4", "a2", "d1", "c1"), options.map { it.id })
        assertEquals(listOf(true, true, false, false), options.map { it.sameScene })
    }

    @Test
    fun `already chosen photos and rejected photos are never offered`() {
        val ids = BurstAlternatives.build(curation, story, slot = 0).map { it.id }
        assertTrue("b1" !in ids && "a1" !in ids)
        assertTrue("a3" !in ids)
    }

    @Test
    fun `the second slot offers its own burst then the champions of other clusters`() {
        val options = BurstAlternatives.build(curation, story, slot = 1)
        assertEquals(listOf("b2", "d1", "c1"), options.map { it.id })
        assertEquals(listOf(true, false, false), options.map { it.sameScene })
    }

    @Test
    fun `a slot outside the story yields nothing`() {
        assertEquals(emptyList<BurstOption>(), BurstAlternatives.build(curation, story, slot = 2))
        assertEquals(emptyList<BurstOption>(), BurstAlternatives.build(curation, story, slot = -1))
    }

    @Test
    fun `a photo without a cluster still gets other champions`() {
        val loose = curation.copy(
            analyses = curation.analyses + ("z1" to TestData.analysis("z1", quality = 0.3f)),
        )
        val ids = BurstAlternatives.build(loose, listOf("z1"), slot = 0).map { it.id }
        assertEquals(listOf("a1", "b1", "d1", "c1"), ids)
    }

    @Test
    fun `members without an analysis are skipped`() {
        val partial = curation.copy(analyses = curation.analyses - "a4")
        assertEquals(listOf("a2", "d1", "c1"), BurstAlternatives.build(partial, story, slot = 0).map { it.id })
    }
}
