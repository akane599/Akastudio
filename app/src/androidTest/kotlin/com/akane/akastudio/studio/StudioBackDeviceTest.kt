package com.akane.akastudio.studio

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akane.akastudio.feature.studio.R
import com.akane.akastudio.feature.studio.mvi.StudioPhase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** SQ-50: system BACK on a real window closes overlays first, asks before discarding a story, and leaves from Empty. */
@RunWith(AndroidJUnit4::class)
class StudioBackDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val studio by lazy { StudioDeviceHarness(compose) }

    @After
    fun tearDown() = studio.close()

    @Test
    fun backWithTheModeDrawerOpenClosesOnlyTheDrawer() {
        val ai = studio.curateStory()
        val chip = studio.modeChip(studio.aiPickLabel(ai))
        val drawerTitle = studio.string(R.string.controls_drawer_title)
        compose.onNode(chip).performClick()
        compose.onNodeWithText(drawerTitle).assertIsDisplayed()
        compose.onNode(chip and hasStateDescription(studio.string(R.string.controls_mode_drawer_expanded))).assertExists()

        studio.pressBack()
        studio.assertStillResumed("BACK with the drawer open")
        compose.waitForIdle()

        compose.onAllNodes(hasText(drawerTitle)).assertCountEquals(0)
        compose.onNode(chip and hasStateDescription(studio.string(R.string.controls_mode_drawer_collapsed))).assertIsDisplayed()
        compose.onAllNodes(hasText(studio.string(R.string.studio_discard_title))).assertCountEquals(0)
        assertEquals(Lifecycle.State.RESUMED, studio.activityState)
        val state = studio.state
        assertEquals(StudioPhase.Ready, state.phase)
        assertFalse(state.modeDrawerOpen)
        assertEquals(ai, state.selection.mode)
    }

    @Test
    fun backInReadyAsksBeforeDiscardingAndDiscardReturnsToTheDropzone() {
        val ai = studio.curateStory()
        val chip = studio.modeChip(studio.aiPickLabel(ai))
        val discardTitle = studio.string(R.string.studio_discard_title)

        studio.pressBack()
        studio.assertStillResumed("BACK in Ready")
        compose.onNodeWithText(discardTitle).assertIsDisplayed()

        compose.onNodeWithText(studio.string(R.string.studio_discard_keep)).performClick()
        compose.onAllNodes(hasText(discardTitle)).assertCountEquals(0)
        compose.onNode(chip).assertIsDisplayed()
        assertEquals(StudioPhase.Ready, studio.state.phase)
        assertEquals(Lifecycle.State.RESUMED, studio.activityState)

        studio.pressBack()
        studio.assertStillResumed("Second BACK in Ready")
        compose.onNodeWithText(discardTitle).assertIsDisplayed()
        compose.onNodeWithText(studio.string(R.string.studio_discard_confirm)).performClick()
        compose.waitForIdle()

        compose.onAllNodes(hasText(discardTitle)).assertCountEquals(0)
        compose.onNodeWithText(studio.string(R.string.dropzone_tagline)).assertIsDisplayed()
        compose.onNodeWithText(studio.string(R.string.dropzone_select_photos)).assertIsDisplayed()
        compose.onAllNodes(chip).assertCountEquals(0)
        val state = studio.state
        assertEquals(StudioPhase.Empty, state.phase)
        assertEquals(null, state.curation)
        studio.assertStillResumed("Discard")
    }

    @Test
    fun backInTheEmptyStudioFinishesTheActivity() {
        studio.launch()
        compose.onNodeWithText(studio.string(R.string.dropzone_tagline)).assertIsDisplayed()
        assertEquals(StudioPhase.Empty, studio.state.phase)
        assertEquals(Lifecycle.State.RESUMED, studio.activityState)

        studio.pressBack()

        awaitCondition("activity destroyed after BACK in Empty", UI_TIMEOUT_MS) {
            studio.activityState == Lifecycle.State.DESTROYED
        }
    }
}
