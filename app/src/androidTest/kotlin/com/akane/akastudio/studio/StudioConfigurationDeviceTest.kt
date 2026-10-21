package com.akane.akastudio.studio

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akane.akastudio.AkaStudioApp
import com.akane.akastudio.feature.studio.R
import com.akane.akastudio.feature.studio.mvi.StudioPhase
import com.akane.akastudio.feature.studio.mvi.StudioState
import com.akane.akastudio.feature.studio.ui.StudioRoute
import com.akane.akastudio.feature.studio.ui.theme.DarkroomTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Stage 5 on a real window: a rotation mid-Ready keeps the curated story; a 1.3 font scale keeps the top bar clean. */
@RunWith(AndroidJUnit4::class)
class StudioConfigurationDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val studio by lazy { StudioDeviceHarness(compose) }

    @After
    fun tearDown() = studio.close()

    @Test
    fun rotatingMidReadyKeepsTheCuratedStory() {
        val ai = studio.curateStory()
        val vm = studio.viewModel()
        val before = studio.state
        val chip = studio.modeChip(studio.aiPickLabel(ai))

        val landscape = rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
        compose.waitForIdle()
        assertSame("view model did not survive the configuration change", vm, studio.viewModel())
        compose.onNode(chip).assertIsDisplayed()
        assertStoryKept(before)

        val portrait = rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
        assertNotSame(landscape, portrait)
        compose.waitForIdle()
        assertSame(vm, studio.viewModel())
        compose.onNode(chip).assertIsDisplayed()
        assertStoryKept(before)
        assertEquals(Lifecycle.State.RESUMED, studio.activityState)
    }

    /** SQ-40: at font scale 1.3 the chip drops the "AI pick:" prefix (the AI tag stays) and nothing overlaps. */
    @Test
    fun largeFontDropsTheModePrefixAndKeepsTheTopBarFromOverlapping() {
        val ai = studio.curateStory()
        val label = studio.aiPickLabel(ai)
        val name = studio.modeName(ai)
        val tag = studio.string(R.string.controls_tag_ai)

        // Default scale: the chip draws the full sentence (also proves drawnTexts reads what the chip shows).
        assertEquals(listOf(label), chipTexts(label))

        studio.scenario.onActivity { activity ->
            val container = (activity.application as AkaStudioApp).container
            activity.setContent {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(LARGE_FONT_SCALE)) {
                    DarkroomTheme { StudioRoute(container) }
                }
            }
        }
        compose.waitForIdle()

        assertEquals(StudioPhase.Ready, studio.state.phase)
        compose.onNode(studio.modeChip(label)).assertIsDisplayed()
        val drawn = chipTexts(label)
        assertEquals(listOf(tag, name), drawn)
        assertFalse("prefix still drawn: $drawn", drawn.any { it == label })

        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val tools = listOf(
            studio.modeChip(label),
            hasContentDescription(studio.string(R.string.controls_compare)) and hasRole(Role.Switch),
            hasContentDescription(studio.string(R.string.controls_scope, studio.string(R.string.controls_scope_off))),
            hasContentDescription(studio.string(R.string.controls_open_export)) and hasRole(Role.Button),
        ).map { bounds(it) }
        tools.forEach { r ->
            assertTrue("tool $r outside the window $root", r.left >= root.left && r.right <= root.right && r.top >= root.top)
            assertTrue("tool $r has no area", r.width > 0f && r.height > 0f)
        }
        for (i in tools.indices) for (j in i + 1 until tools.size) {
            assertFalse("top bar items $i and $j overlap: ${tools[i]} / ${tools[j]}", tools[i].overlapsArea(tools[j]))
        }
        // The chip's drawn children stay inside the chip and clear of the tools to its right.
        val chip = compose.onNode(studio.modeChip(label), useUnmergedTree = true).fetchSemanticsNode()
        chip.children.forEach { child ->
            val r = child.boundsInRoot
            assertTrue("chip child $r spills out of ${chip.boundsInRoot}", chip.boundsInRoot.contains(r))
            tools.drop(1).forEach { tool -> assertFalse("chip child $r overlaps $tool", r.overlapsArea(tool)) }
        }
    }

    private fun chipTexts(label: String): List<String> =
        compose.onNode(studio.modeChip(label), useUnmergedTree = true).fetchSemanticsNode().drawnTexts()

    private fun bounds(matcher: SemanticsMatcher): Rect = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot

    private fun Rect.contains(other: Rect): Boolean =
        other.left >= left && other.right <= right && other.top >= top && other.bottom <= bottom

    /** Requests [requested] and waits for the recreated activity to report [expected]; returns the new instance. */
    private fun rotate(requested: Int, expected: Int): Activity {
        var previous: Activity? = null
        studio.scenario.onActivity { previous = it; it.requestedOrientation = requested }
        var current: Activity? = null
        awaitCondition("activity recreated in orientation $expected", UI_TIMEOUT_MS) {
            // The scenario refuses access while the old instance is being torn down; poll again.
            runCatching { studio.scenario.onActivity { current = it } }
            val activity = current
            activity !== previous && activity?.resources?.configuration?.orientation == expected &&
                studio.activityState == Lifecycle.State.RESUMED
        }
        return checkNotNull(current)
    }

    private fun assertStoryKept(before: StudioState) {
        val after = studio.state
        assertEquals(StudioPhase.Ready, after.phase)
        assertSame("curation replaced", before.curation, after.curation)
        assertEquals(before.selection.photoIds, after.selection.photoIds)
        assertEquals(before.selection.mode, after.selection.mode)
        assertEquals(before.grade, after.grade)
        assertEquals(before.template, after.template)
    }

    private companion object {
        const val LARGE_FONT_SCALE = 1.3f
    }
}
