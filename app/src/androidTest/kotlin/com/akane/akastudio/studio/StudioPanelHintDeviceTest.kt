package com.akane.akastudio.studio

import android.content.Context
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akane.akastudio.feature.studio.R
import com.akane.akastudio.feature.studio.ui.controls.CompareHintPrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** SQ-45 on a real window: the collapsible control panel and the one-time A/B compare hint. */
@RunWith(AndroidJUnit4::class)
class StudioPanelHintDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val studio by lazy { StudioDeviceHarness(compose) }

    private val hintPrefs get() = studio.context.getSharedPreferences(CompareHintPrefs.FILE_NAME, Context.MODE_PRIVATE)

    @Before
    fun clearHintSeen() {
        assertTrue(hintPrefs.edit().remove(CompareHintPrefs.KEY_SEEN).commit())
    }

    @After
    fun tearDown() = studio.close()

    @Test
    fun panelPeeksCollapsedExpandsOnTapAndDragAndNeverCoversTheCompareHandle() {
        studio.curateStory()
        val windowDp = compose.onRoot().fetchSemanticsNode().boundsInRoot.height.toDp()
        val expandedDp = windowDp * EXPANDED_FRACTION
        val collapsedDp = minOf(PEEK_DP, expandedDp)

        assertPanel(collapsed = true, heightDp = collapsedDp)

        handle().performClick()
        compose.waitForIdle()
        assertPanel(collapsed = false, heightDp = expandedDp)

        handle().performClick()
        compose.waitForIdle()
        assertPanel(collapsed = true, heightDp = collapsedDp)

        val dragPx = DRAG_DP * density()
        handle().performTouchInput { swipeUp(startY = centerY, endY = centerY - dragPx, durationMillis = DRAG_MS) }
        compose.waitForIdle()
        assertPanel(collapsed = false, heightDp = expandedDp)

        // Expanded with compare on: the split handle is fully shown and clear of the panel.
        compose.onNode(hasContentDescription(studio.string(R.string.controls_compare)) and hasRole(Role.Switch))
            .performClick()
        compose.waitForIdle()
        val split = compose.onNode(hasContentDescription(studio.string(R.string.canvas_compare_handle)))
        split.assertIsDisplayed()
        val splitBounds = split.fetchSemanticsNode().boundsInRoot
        val panel = panelBounds()
        assertFalse("panel $panel covers the A/B handle $splitBounds", panel.overlapsArea(splitBounds))
        assertTrue("A/B handle $splitBounds ends below the panel top ${panel.top}", splitBounds.bottom <= panel.top)

        handle().performTouchInput { swipeDown(startY = centerY, endY = centerY + dragPx, durationMillis = DRAG_MS) }
        compose.waitForIdle()
        assertPanel(collapsed = true, heightDp = collapsedDp)
    }

    @Test
    fun compareHintShowsOnTheFirstReadyAndNotAgainOnceDismissed() {
        studio.curateStory()
        val hint = hasText(studio.string(R.string.controls_compare_hint))

        compose.onNode(hint).assertIsDisplayed()
        assertFalse(hintPrefs.getBoolean(CompareHintPrefs.KEY_SEEN, false))

        compose.onNode(hint).performClick()
        compose.waitForIdle()
        compose.onAllNodes(hint).assertCountEquals(0)
        assertTrue("dismissal not persisted", hintPrefs.getBoolean(CompareHintPrefs.KEY_SEEN, false))

        // A new activity reads the flag again from SharedPreferences: still Ready, no hint.
        studio.scenario.recreate()
        compose.waitForIdle()
        compose.onNode(handleMatcher()).assertIsDisplayed()
        compose.onAllNodes(hint).assertCountEquals(0)
    }

    private fun handleMatcher(): SemanticsMatcher =
        hasContentDescription(studio.string(R.string.controls_panel_handle)) and hasRole(Role.Button)

    private fun handle() = compose.onNode(handleMatcher())

    /** From the handle's top to the bottom of the panel's scrolling tab content. */
    private fun panelBounds(): Rect {
        val top = handle().fetchSemanticsNode().boundsInRoot
        val content = compose.onAllNodes(isVerticallyScrollable()).fetchSemanticsNodes()
            .map { it.boundsInRoot }
            .single { it.top >= top.bottom - 1f }
        return Rect(top.left, top.top, top.right, content.bottom)
    }

    private fun assertPanel(collapsed: Boolean, heightDp: Float) {
        val state = studio.string(if (collapsed) R.string.controls_panel_collapsed else R.string.controls_panel_expanded)
        handle().assertIsDisplayed()
        compose.onNode(handleMatcher() and hasStateDescription(state)).assertIsDisplayed()
        assertEquals("panel height (collapsed=$collapsed)", heightDp, panelBounds().height.toDp(), TOLERANCE_DP)
    }

    private fun isVerticallyScrollable(): SemanticsMatcher =
        SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

    private fun density(): Float = studio.context.resources.displayMetrics.density

    private fun Float.toDp(): Float = this / density()

    private companion object {
        const val PEEK_DP = 240f
        const val EXPANDED_FRACTION = 0.4f
        const val TOLERANCE_DP = 2f
        const val DRAG_DP = 200f
        const val DRAG_MS = 400L
    }
}
