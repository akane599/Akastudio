package com.akane.akastudio.feature.studio.ui.controls

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollapsiblePanelTest {

    @Test
    fun tallWindowPeeksAtTheCollapsedHeightAndExpandsToFortyPercent() {
        val heights = panelHeights(800.dp)

        assertEquals(240.dp, heights.collapsed)
        assertEquals(320.dp, heights.expanded)
    }

    @Test
    fun shortWindowNeverCollapsesAboveTheExpandedHeight() {
        val heights = panelHeights(400.dp)

        assertEquals(160.dp, heights.expanded)
        assertEquals(heights.expanded, heights.collapsed)
    }

    @Test
    fun slowReleaseSettlesOnTheNearerHeight() {
        val heights = PanelHeights(collapsed = 240.dp, expanded = 360.dp)

        assertFalse(settlesExpanded(299.dp, heights, velocityDpPerSecond = 0f))
        assertTrue(settlesExpanded(300.dp, heights, velocityDpPerSecond = 0f))
        assertTrue(settlesExpanded(350.dp, heights, velocityDpPerSecond = 100f))
    }

    @Test
    fun flingSettlesInItsDirectionWhereverItIsReleased() {
        val heights = PanelHeights(collapsed = 240.dp, expanded = 360.dp)

        assertTrue("upward fling expands", settlesExpanded(245.dp, heights, velocityDpPerSecond = -800f))
        assertFalse("downward fling collapses", settlesExpanded(355.dp, heights, velocityDpPerSecond = 800f))
    }

    @Test
    fun hintStoreRemembersTheDismissal() {
        val store = InMemoryCompareHintStore()

        assertFalse(store.isSeen())
        store.markSeen()
        assertTrue(store.isSeen())
    }
}
