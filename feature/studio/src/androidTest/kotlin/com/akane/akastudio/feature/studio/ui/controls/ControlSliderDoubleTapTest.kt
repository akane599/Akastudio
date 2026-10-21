package com.akane.akastudio.feature.studio.ui.controls

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akane.akastudio.feature.studio.ui.theme.DarkroomTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Double-tap-to-reset on a real touch pipeline: the Material3 Slider's tap-to-jump must not overwrite the reset
 * with the tapped position (AUDIT F-04). Real fingers never land on the same pixel twice, so the taps differ.
 */
@RunWith(AndroidJUnit4::class)
class ControlSliderDoubleTapTest {

    @get:Rule
    val compose = createComposeRule()

    private val values = mutableListOf<Float>()

    private fun setSlider(initial: Float) {
        compose.setContent {
            var value by mutableFloatStateOf(initial)
            DarkroomTheme {
                ControlSlider(
                    label = LABEL,
                    readout = "",
                    value = value,
                    range = 0f..1f,
                    bipolar = false,
                    resetValue = RESET,
                    onValueChange = {
                        values += it
                        value = it
                    },
                )
            }
        }
    }

    /** Two short taps [gapMillis] apart, the second [dx] px right of the first, at 80% of the track. */
    private fun TouchInjectionScope.doubleTap(dx: Float, gapMillis: Long = 80L) {
        val first = Offset(width * 0.8f, centerY)
        down(first)
        advanceEventTime(TAP_MILLIS)
        up()
        advanceEventTime(gapMillis)
        down(first + Offset(dx, 1f))
        advanceEventTime(TAP_MILLIS)
        up()
    }

    @Test
    fun doubleTapAFewPixelsApartResetsInsteadOfJumpingToTheTap() {
        setSlider(initial = 0.5f)
        compose.onNodeWithContentDescription(LABEL).performTouchInput { doubleTap(dx = 4f) }
        compose.runOnIdle { assertEquals("changes $values", RESET, values.last(), 0f) }
    }

    @Test
    fun samePixelDoubleTapResets() {
        setSlider(initial = 0.5f)
        compose.onNodeWithContentDescription(LABEL).performTouchInput { doubleTap(dx = 0f) }
        compose.runOnIdle { assertEquals("changes $values", RESET, values.last(), 0f) }
    }

    @Test
    fun singleTapStillJumpsToTheTappedPosition() {
        setSlider(initial = 0.5f)
        compose.onNodeWithContentDescription(LABEL).performTouchInput {
            down(Offset(width * 0.8f, centerY))
            advanceEventTime(TAP_MILLIS)
            up()
        }
        compose.runOnIdle {
            val last = values.lastOrNull() ?: error("single tap changed nothing")
            assertTrue("single tap read $last, changes $values", last > 0.6f)
        }
    }

    private companion object {
        const val LABEL = "Harmonization"
        const val RESET = 0.2f
        const val TAP_MILLIS = 40L
    }
}
