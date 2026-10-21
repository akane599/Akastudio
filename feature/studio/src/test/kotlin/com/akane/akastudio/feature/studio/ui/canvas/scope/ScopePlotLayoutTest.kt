package com.akane.akastudio.feature.studio.ui.canvas.scope

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.akane.akastudio.feature.studio.mvi.ScopeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil
import kotlin.math.floor

private const val DENSITY = 2.625f

/**
 * SQ-65: on a ~195 dp canvas the 40% scope was 78 dp wide and the histogram's 0/50/100 labels landed on its R/G/B
 * legend. [ScopeLabelLayout.layout] must never return overlapping or out-of-plot labels, and at [ScopeMinWidth] it
 * must keep every label, at font scale 1 and 1.3.
 */
class ScopePlotLayoutTest {

    private val modes = listOf(ScopeMode.LUMA_WAVEFORM, ScopeMode.RGB_PARADE, ScopeMode.HISTOGRAM)

    /** Whole pixels for [dp] at the emulator's 2.625 density, rounded up as text layout sizes are. */
    private fun px(dp: Float): Float = ceil(dp * DENSITY)

    /** labelSmall (11 sp on a 14 sp line) measured generously, in pixels, at font scale [scale]. */
    private inner class Labels(scale: Float) {
        private val line = px(14f * scale + 1f)
        val ticks = listOf(Size(px(7f * scale + 1f), line), Size(px(14f * scale + 1f), line), Size(px(20f * scale + 1f), line))
        val channels = List(3) { Size(px(8f * scale + 1f), line) }

        /** [ChannelLegend]: three 8 dp dots, 4 dp to each letter, 8 dp between channels. */
        val legend = Size(channels.sumOf { (px(8f) + px(4f) + it.width).toDouble() }.toFloat() + 2 * px(8f), line)
        val captionHeight = line
    }

    private val gap = px(4f)

    private fun layout(mode: ScopeMode, plot: Size, labels: Labels) =
        ScopeLabelLayout.layout(mode, plot, labels.ticks, labels.channels, labels.legend, gap)

    private fun ScopePlotLayout.labels(): List<Rect> =
        tickLabels.filterNotNull() + channelLabels.filterNotNull() + listOfNotNull(legend)

    private fun assertClearAndInside(where: String, layout: ScopePlotLayout, plot: Size) {
        val rects = layout.labels()
        rects.forEach { r ->
            val inside = r.left >= 0f && r.top >= 0f && r.right <= plot.width && r.bottom <= plot.height
            assertTrue("$where: $r leaves the plot $plot", inside)
        }
        rects.forEachIndexed { i, a ->
            rects.drop(i + 1).forEach { b -> assertFalse("$where: $a overlaps $b", a.overlaps(b)) }
        }
    }

    /** The plot, in whole pixels, of a [widthDp]-wide 16:10 panel with 8 dp padding, under a caption and a 4 dp gap. */
    private fun plotAt(widthDp: Float, labels: Labels): Size {
        val width = floor(widthDp * DENSITY)
        val content = Size(width - 2 * px(8f), floor(width / SCOPE_ASPECT) - 2 * px(8f))
        return Size(content.width, content.height - labels.captionHeight - gap)
    }

    @Test
    fun `no label overlaps another or leaves the plot at any size, font scale or mode`() {
        listOf(1f, 1.3f, 2f).forEach { scale ->
            val labels = Labels(scale)
            modes.forEach { mode ->
                for (w in 0..480 step 3) for (h in 0..240 step 3) {
                    val plot = Size(w.toFloat(), h.toFloat())
                    assertClearAndInside("$mode ${scale}x $plot", layout(mode, plot, labels), plot)
                }
            }
        }
    }

    @Test
    fun `degenerate plots drop every label instead of throwing`() {
        val labels = Labels(1f)
        val sizes = listOf(
            Size.Zero,
            Size(-5f, -5f),
            Size(Float.NaN, 40f),
            Size(200f, Float.NaN),
            Size(Float.POSITIVE_INFINITY, 50f),
        )
        modes.forEach { mode ->
            sizes.forEach { plot -> assertTrue("$mode $plot", layout(mode, plot, labels).labels().isEmpty()) }
        }
    }

    @Test
    fun `at the minimum width every label shows in every mode, at 1x and 1_3x font scale`() {
        listOf(1f, 1.3f).forEach { scale ->
            val labels = Labels(scale)
            val plot = plotAt(ScopeMinWidth.value, labels)
            modes.forEach { mode ->
                val result = layout(mode, plot, labels)
                assertClearAndInside("$mode ${scale}x", result, plot)
                result.tickLabels.forEachIndexed { i, r -> assertNotNull("$mode ${scale}x tick $i dropped", r) }
                if (mode == ScopeMode.RGB_PARADE) result.channelLabels.forEach { assertNotNull("$mode ${scale}x letter", it) }
                if (mode == ScopeMode.HISTOGRAM) assertNotNull("${scale}x legend dropped", result.legend)
            }
        }
    }

    @Test
    fun `the old 78 dp histogram shows no colliding labels`() {
        val labels = Labels(1f)
        val plot = plotAt(78f, labels)
        val result = layout(ScopeMode.HISTOGRAM, plot, labels)
        assertClearAndInside("78 dp", result, plot)
        // The legend and the code labels cannot both fit in that height; whatever is left must not touch.
        assertFalse(result.legend != null && result.tickLabels.all { it != null })
    }

    @Test
    fun `a crowded waveform axis drops its middle label before either end`() {
        val labels = Labels(1f)
        val line = labels.ticks[0].height
        // Room for the two end labels but not for the middle one between them.
        val plot = Size(px(120f), 2.5f * line)
        val result = layout(ScopeMode.LUMA_WAVEFORM, plot, labels)
        assertNotNull(result.tickLabels[0])
        assertNull(result.tickLabels[1])
        assertNotNull(result.tickLabels[2])
    }

    @Test
    fun `waveform labels sit right-aligned in the gutter, left of the plot`() {
        val labels = Labels(1f)
        val plot = Size(px(200f), px(100f))
        val result = layout(ScopeMode.LUMA_WAVEFORM, plot, labels)
        val gutter = labels.ticks.maxOf { it.width } + gap
        assertEquals(gutter, result.traces.single().left, 1e-4f)
        result.tickLabels.forEach { assertEquals(gutter - gap, it!!.right, 1e-4f) }
        assertEquals(0f, result.tickLabels[2]!!.top, 1e-4f)
        assertEquals(plot.height, result.tickLabels[0]!!.bottom, 1e-4f)
    }

    @Test
    fun `histogram plot stops above the label band and the legend sits in its top end`() {
        val labels = Labels(1f)
        val plot = Size(px(200f), px(100f))
        val result = layout(ScopeMode.HISTOGRAM, plot, labels)
        val area = result.traces.single()
        assertEquals(plot.height - labels.ticks.maxOf { it.height } - gap, area.bottom, 1e-4f)
        val legend = result.legend!!
        assertEquals(0f, legend.top, 1e-4f)
        assertTrue(legend.bottom <= area.bottom)
        assertEquals(plot.width - gap, legend.right, 1e-4f)
        result.tickLabels.forEach { assertEquals(plot.height, it!!.bottom, 1e-4f) }
    }

    @Test
    fun `parade letters sit inside their own channel plot`() {
        val labels = Labels(1f)
        val result = layout(ScopeMode.RGB_PARADE, Size(px(240f), px(100f)), labels)
        assertEquals(3, result.traces.size)
        result.channelLabels.forEachIndexed { i, r ->
            val panel = result.traces[i]
            assertNotNull(r)
            assertTrue("letter $i outside its plot", r!!.left >= panel.left && r.right <= panel.right + 0.5f)
        }
    }
}
