package com.akane.akastudio.studio

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akane.akastudio.feature.studio.R
import com.akane.akastudio.feature.studio.mvi.ScopeMode
import com.akane.akastudio.feature.studio.mvi.StudioIntent
import com.akane.akastudio.feature.studio.ui.canvas.scope.SCOPE_LABEL_TAG
import com.akane.akastudio.feature.studio.ui.canvas.scope.SCOPE_PLOT_TAG
import com.akane.akastudio.feature.studio.ui.canvas.scope.ScopeMinWidth
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil

/** SQ-65: the docked scope on the device's real canvas, sized to be readable, with no text touching in any mode. */
@RunWith(AndroidJUnit4::class)
class StudioScopeDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val studio by lazy { StudioDeviceHarness(compose) }

    @After
    fun tearDown() = studio.close()

    /**
     * On this device's ~195 dp canvas the 40% scope was 78 dp wide: the histogram's 0/50/100 labels sat on its R/G/B
     * legend and "Luma waveform" was cut off. Each mode must now be at least [ScopeMinWidth] wide and show all of its
     * text (caption, 0/50/100, and the R/G/B letters or legend) inside the panel, no two pieces overlapping.
     * SQ-68: the legend and the parade letters were drawn over the traces; no text may touch the plot either.
     */
    @Test
    fun scopeIsAtLeastTheMinimumWidthAndItsLabelsNeverOverlapInAnyMode() {
        studio.curateStory()
        val floor = with(compose.density) { ScopeMinWidth.toPx() }
        // Caption + 0/50/100, plus three parade letters or the one histogram legend.
        val expected = mapOf(ScopeMode.LUMA_WAVEFORM to 4, ScopeMode.RGB_PARADE to 7, ScopeMode.HISTOGRAM to 5)

        expected.forEach { (mode, count) ->
            studio.send(StudioIntent.SetScope(mode))
            compose.waitForIdle()
            val caption = studio.string(captionRes(mode))
            val shortCaption = studio.string(shortCaptionRes(mode))
            val panel = compose.onNode(hasContentDescription(caption)).fetchSemanticsNode().boundsInRoot
            assertTrue("$mode: scope is ${panel.width} px wide, under the $floor px minimum", panel.width >= floor - 1f)
            val plot = compose.onNodeWithTag(SCOPE_PLOT_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("$mode: plot $plot is empty", plot.width > 0f && plot.height > 0f)

            val labels = compose.onAllNodesWithTag(SCOPE_LABEL_TAG, useUnmergedTree = true).fetchSemanticsNodes()
            assertEquals("$mode: scope text shown ${labels.map { it.describe() }}", count, labels.size)
            labels.forEach { label ->
                val b = label.boundsInRoot
                val inside = b.left >= panel.left && b.top >= panel.top && b.right <= panel.right && b.bottom <= panel.bottom
                assertTrue("$mode: ${label.describe()} at $b leaves the panel $panel", inside)
                assertFalse("$mode: ${label.describe()} at $b is drawn over the plot $plot", b.overlapsArea(plot))
            }
            labels.forEachIndexed { i, a ->
                labels.drop(i + 1).forEach { b ->
                    assertFalse(
                        "$mode: ${a.describe()} ${a.boundsInRoot} overlaps ${b.describe()} ${b.boundsInRoot}",
                        a.boundsInRoot.overlapsArea(b.boundsInRoot),
                    )
                }
            }

            // The caption gives way to its short form to keep the R/G/B identity on its row.
            val captionNode = labels.single { it.text() == caption || it.text() == shortCaption }
            val layout = textLayout(captionNode)
            // hasVisualOverflow is no use here: the paragraph is laid out at the panel's full width, wider than the node.
            val fullWidth = ceil(layout.multiParagraph.intrinsics.maxIntrinsicWidth)
            val metrics = "size=${layout.size} full text=$fullWidth lines=${layout.lineCount}"
            assertEquals("$mode: caption \"$caption\" wraps ($metrics)", 1, layout.lineCount)
            assertFalse("$mode: caption \"$caption\" is ellipsized ($metrics)", layout.isLineEllipsized(0))
            assertFalse("$mode: caption \"$caption\" is cut at the bottom ($metrics)", layout.didOverflowHeight)
            assertTrue("$mode: caption \"$caption\" is cut at the end ($metrics)", fullWidth <= layout.size.width)
        }
    }

    private fun captionRes(mode: ScopeMode): Int = when (mode) {
        ScopeMode.RGB_PARADE -> R.string.canvas_scope_parade
        ScopeMode.HISTOGRAM -> R.string.canvas_scope_histogram
        else -> R.string.canvas_scope_luma
    }

    private fun shortCaptionRes(mode: ScopeMode): Int = when (mode) {
        ScopeMode.RGB_PARADE -> R.string.canvas_scope_parade_short
        ScopeMode.HISTOGRAM -> R.string.canvas_scope_histogram_short
        else -> R.string.canvas_scope_luma_short
    }

    private fun SemanticsNode.text(): String? =
        config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }

    private fun SemanticsNode.describe(): String = text()?.let { "\"$it\"" } ?: "legend"

    private fun textLayout(node: SemanticsNode): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.runOnUiThread { node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results) }
        return results.single()
    }
}
