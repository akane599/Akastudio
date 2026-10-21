package com.akane.akastudio.studio

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akane.akastudio.core.curator.api.StoryMode
import com.akane.akastudio.feature.studio.R
import com.akane.akastudio.feature.studio.mvi.StudioIntent
import com.akane.akastudio.feature.studio.mvi.TelemetryStep
import java.util.Collections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Stage 5 on a real window: ingest and curate, then the user's mode override (SQ-51). */
@RunWith(AndroidJUnit4::class)
class StudioCurationDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val studio by lazy { StudioDeviceHarness(compose) }

    @After
    fun tearDown() = studio.close()

    @Test
    fun eightPhotosAdvanceTelemetryToReadyWithAnAiPick() {
        studio.launch()
        val vm = studio.viewModel()
        val steps = Collections.synchronizedList(mutableListOf<TelemetryStep>())
        val tracer = Thread {
            try {
                while (true) {
                    val step = vm.state.value.telemetry.step
                    if (steps.lastOrNull() != step) steps += step
                    if (step == TelemetryStep.DONE) break
                    Thread.sleep(5)
                }
            } catch (_: InterruptedException) {
                // Stopped by the test after a failure.
            }
        }.apply { start() }
        try {
            studio.importPhotos(8)
            // The Dropzone's telemetry list is on screen with a step in progress while the curator works.
            val inProgress = studio.string(R.string.dropzone_step_active)
            compose.waitUntil(CURATION_TIMEOUT_MS) {
                compose.onAllNodes(hasStateDescription(inProgress)).fetchSemanticsNodes().isNotEmpty()
            }
            studio.awaitReady()
        } finally {
            tracer.interrupt()
            tracer.join(UI_TIMEOUT_MS)
        }

        val state = studio.state
        val ai = checkNotNull(state.curation).chosen.mode
        compose.onNode(studio.modeChip(studio.aiPickLabel(ai))).assertIsDisplayed()
        compose.onAllNodes(hasContentDescription(studio.string(R.string.controls_mode_user_pick, ""), substring = true))
            .assertCountEquals(0)

        val trace = steps.toList()
        assertTrue("telemetry never analysed: $trace", TelemetryStep.ANALYZING in trace)
        assertEquals("telemetry did not finish: $trace", TelemetryStep.DONE, trace.last())
        assertEquals("telemetry went backwards: $trace", trace.sortedBy { it.ordinal }, trace)
        assertEquals(8, state.telemetry.photosRead)
        assertEquals(8, state.telemetry.analyzeTotal)
        assertEquals(8, state.telemetry.analyzed)
    }

    /** SQ-51: a forced mode is the user's pick; the AI badge stays on the curator's mode; forcing back restores it. */
    @Test
    fun forcingAnotherModeShowsYourPickAndKeepsTheAiBadgeOnTheCuratorsMode() {
        val ai = studio.curateStory()
        val forced = if (ai == StoryMode.HERO) StoryMode.COLLAGE_2 else StoryMode.HERO

        openDrawer(studio.aiPickLabel(ai))
        modeRow(forced).performClick()
        studio.awaitReady()
        compose.onNode(studio.modeChip(studio.userPickLabel(forced))).assertIsDisplayed()
        compose.onAllNodes(studio.modeChip(studio.aiPickLabel(forced))).assertCountEquals(0)
        compose.onAllNodes(studio.modeChip(studio.aiPickLabel(ai))).assertCountEquals(0)

        openDrawer(studio.userPickLabel(forced))
        modeRow(forced).assertIsSelected()
        modeRow(ai).assertIsNotSelected()
        assertAiBadge(ai, present = true)
        assertAiBadge(forced, present = false)

        modeRow(ai).performClick()
        studio.awaitReady()
        compose.onNode(studio.modeChip(studio.aiPickLabel(ai))).assertIsDisplayed()
        compose.onAllNodes(studio.modeChip(studio.userPickLabel(ai))).assertCountEquals(0)
        openDrawer(studio.aiPickLabel(ai))
        modeRow(ai).assertIsSelected()
        assertAiBadge(ai, present = true)
        assertAiBadge(forced, present = false)
        assertEquals(ai, studio.state.selection.mode)
    }

    /** SQ-67: forcing a mode changes the composition only; the drawer keeps the original options and scores. */
    @Test
    fun forcingAModeKeepsTheDrawersOptionsAndScores() {
        val ai = studio.curateStory()
        val original = checkNotNull(studio.state.curation).alternatives
        val forced = checkNotNull(original.keys.firstOrNull { it != ai }) { "curation offered only $ai" }

        openDrawer(studio.aiPickLabel(ai))
        val before = drawerRows()
        assertEquals(original.keys.map { studio.modeName(it) }, before.map { it.substringBefore(", ") })
        modeRow(forced).performClick()
        studio.awaitReady()

        assertEquals(forced, studio.state.selection.mode)
        assertEquals(original, checkNotNull(studio.state.curation).alternatives)
        openDrawer(studio.userPickLabel(forced))
        assertEquals(before, drawerRows())
    }

    /** The drawer's mode rows, top to bottom, as their "mode, score" descriptions. */
    private fun drawerRows(): List<String> {
        val names = StoryMode.entries.map { "${studio.modeName(it)}, " }
        return compose.onAllNodes(isSelectable()).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() }
            .filter { description -> names.any { description.startsWith(it) } }
    }

    private fun openDrawer(chipLabel: String) {
        compose.onNode(studio.modeChip(chipLabel)).performClick()
        compose.onNodeWithText(studio.string(R.string.controls_drawer_title)).assertIsDisplayed()
    }

    private fun modeRow(mode: StoryMode) =
        compose.onNode(hasContentDescription("${studio.modeName(mode)}, ", substring = true) and isSelectable())

    private fun assertAiBadge(mode: StoryMode, present: Boolean) {
        val badge = hasText(studio.string(R.string.controls_tag_ai))
        val node = modeRow(mode).fetchSemanticsNode()
        assertEquals("AI badge on ${studio.modeName(mode)}", present, badge.matches(node))
    }
}
