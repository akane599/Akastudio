package com.akane.akastudio.feature.studio.ui.canvas

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.color.api.Rect4f
import com.akane.akastudio.core.color.api.RenderScene
import com.akane.akastudio.core.color.api.SceneBackground
import com.akane.akastudio.core.color.api.SceneLayer
import com.akane.akastudio.feature.studio.R
import com.akane.akastudio.feature.studio.mvi.StudioIntent
import com.akane.akastudio.feature.studio.ui.theme.DarkroomTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** On-device checks of the canvas gestures and accessibility actions against the studio's editable gate. */
@RunWith(AndroidJUnit4::class)
class StoryCanvasDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    /** Two cells stacked on a 1080x1920 canvas: "a" on top, "b" below. */
    private val scene = RenderScene(
        canvasWidth = 1080,
        canvasHeight = 1920,
        background = SceneBackground.Solid(0xFF121316.toInt()),
        layers = listOf(
            SceneLayer("a", Rect4f(0f, 0f, 1080f, 960f), NormRect.FULL),
            SceneLayer("b", Rect4f(0f, 960f, 1080f, 1920f), NormRect.FULL),
        ),
    )

    private val intents = mutableListOf<StudioIntent>()
    private val editable = mutableStateOf(true)

    private fun setCanvas(editable: Boolean) {
        this.editable.value = editable
        compose.setContent {
            DarkroomTheme {
                StoryCanvas(
                    scene = scene,
                    revision = 1L,
                    renderer = null,
                    compare = null,
                    onIntent = { intents += it },
                    hitTest = { _, y -> if (y < 960f) 0 else 1 },
                    editable = this.editable.value,
                    modifier = Modifier.fillMaxSize(),
                    photoIds = persistentListOf("a", "b"),
                )
            }
        }
    }

    private fun canvas() = compose.onNodeWithContentDescription(resources.getString(R.string.canvas_description))

    private fun customActions(): List<CustomAccessibilityAction> =
        canvas().fetchSemanticsNode().config.getOrElse(SemanticsActions.CustomActions) { emptyList() }

    /** Long-presses the top cell and drags it onto the bottom cell. */
    private fun dragTopOntoBottom() {
        canvas().performTouchInput {
            down(Offset(centerX, height * 0.25f))
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100L)
            moveTo(Offset(centerX, height * 0.5f))
            moveTo(Offset(centerX, height * 0.75f))
            up()
        }
        compose.waitForIdle()
    }

    @Test
    fun editableDragSwapsCells() {
        setCanvas(editable = true)
        dragTopOntoBottom()
        assertEquals(listOf<StudioIntent>(StudioIntent.SwapSlots(0, 1)), intents)
    }

    @Test
    fun lockedDragSendsNoSwap() {
        setCanvas(editable = false)
        dragTopOntoBottom()
        assertTrue(intents.toString(), intents.none { it is StudioIntent.SwapSlots })
    }

    @Test
    fun swapActionOnlyWhileEditable() {
        val swap = resources.getString(R.string.canvas_action_swap_next)
        setCanvas(editable = true)
        assertTrue(customActions().any { it.label == swap })

        editable.value = false
        compose.waitForIdle()
        assertFalse(customActions().any { it.label == swap })
    }
}
