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
 * legend. SQ-68: the legend and the parade letters were drawn over the traces. [ScopeLabelLayout.layout] must never
 * return a label that overlaps another, leaves the content or touches the plot, and at [ScopeMinWidth] it must keep
 * every label, at font scale 1 and 1.3, in English and Turkish.
 */
class ScopePlotLayoutTest {

    private val modes = listOf(ScopeMode.LUMA_WAVEFORM, ScopeMode.RGB_PARADE, ScopeMode.HISTOGRAM)

    /** The full and short captions per mode, English and Turkish. */
    private val captions = mapOf(
        ScopeMode.LUMA_WAVEFORM to listOf("Luma waveform" to "Luma", "Luma dalga formu" to "Luma"),
        ScopeMode.RGB_PARADE to listOf("RGB parade" to "RGB", "RGB geçit" to "RGB"),
        ScopeMode.HISTOGRAM to listOf("Histogram" to "Hist.", "Histogram" to "Hist."),
    )

    /** Whole pixels for [dp] at the emulator's 2.625 density, rounded up as text layout sizes are. */
    private fun px(dp: Float): Float = ceil(dp * DENSITY)

    /** labelSmall (11 sp on a 14 sp line) measured generously, in pixels, at font scale [scale]. */
    private inner class Labels(private val scale: Float) {
        private val line = px(14f * scale + 1f)
        val ticks = listOf(Size(px(7f * scale + 1f), line), Size(px(14f * scale + 1f), line), Size(px(20f * scale + 1f), line))
        val channels = List(3) { Size(px(8f * scale + 1f), line) }

        /** [ChannelLegend]: three 8 dp dots, 4 dp to each letter, 8 dp between channels. */
        val legend = Size(channels.sumOf { (px(8f) + px(4f) + it.width).toDouble() }.toFloat() + 2 * px(8f), line)

        /** A caption line, 7 sp a character (wider than labelSmall's average glyph). */
        fun caption(text: String) = Size(px(text.length * 7f * scale + 1f), line)
    }

    private val gap = px(4f)

    private fun layout(mode: ScopeMode, content: Size, labels: Labels, locale: Int = 0): ScopePlotLayout {
        val (full, short) = captions.getValue(mode)[locale]
        return ScopeLabelLayout.layout(
            mode, content, labels.caption(full), labels.caption(short), labels.ticks, labels.channels, labels.legend, gap,
        )
    }

    private fun ScopePlotLayout.labels(): List<Rect> =
        tickLabels.filterNotNull() + channelLabels.filterNotNull() + listOfNotNull(legend)

    /** Labels inside [content], clear of each other, the caption and the plot (and so of every trace). */
    private fun assertClearAndInside(where: String, layout: ScopePlotLayout, content: Size) {
        val rects = layout.labels()
        rects.forEach { r ->
            val inside = r.left >= 0f && r.top >= 0f && r.right <= content.width && r.bottom <= content.height
            assertTrue("$where: $r leaves the content $content", inside)
            assertFalse("$where: $r touches the plot ${layout.plot}", r.overlaps(layout.plot))
            layout.traces.forEach { t -> assertFalse("$where: $r touches the trace $t", r.overlaps(t)) }
            assertFalse("$where: $r touches the caption ${layout.caption}", r.overlaps(layout.caption))
        }
        rects.forEachIndexed { i, a ->
            rects.drop(i + 1).forEach { b -> assertFalse("$where: $a overlaps $b", a.overlaps(b)) }
        }
        if (layout.caption.bottom <= layout.plot.top) return
        assertTrue("$where: caption ${layout.caption} touches the plot ${layout.plot}", layout.plot.height == 0f)
    }

    /** The content, in whole pixels, of a [widthDp]-wide 16:10 panel with 8 dp padding. */
    private fun contentAt(widthDp: Float): Size {
        val width = floor(widthDp * DENSITY)
        return Size(width - 2 * px(8f), floor(width / SCOPE_ASPECT) - 2 * px(8f))
    }

    @Test
    fun `no label overlaps another, leaves the content or touches the plot at any size, font scale or mode`() {
        listOf(1f, 1.3f, 2f).forEach { scale ->
            val labels = Labels(scale)
            modes.forEach { mode ->
                for (w in 0..480 step 3) for (h in 0..240 step 3) {
                    val content = Size(w.toFloat(), h.toFloat())
                    assertClearAndInside("$mode ${scale}x $content", layout(mode, content, labels), content)
                }
            }
        }
    }

    @Test
    fun `degenerate panels drop every label instead of throwing`() {
        val labels = Labels(1f)
        val sizes = listOf(
            Size.Zero,
            Size(-5f, -5f),
            Size(Float.NaN, 40f),
            Size(200f, Float.NaN),
            Size(Float.POSITIVE_INFINITY, 50f),
        )
        modes.forEach { mode ->
            sizes.forEach { content -> assertTrue("$mode $content", layout(mode, content, labels).labels().isEmpty()) }
        }
    }

    @Test
    fun `at the minimum width every label shows on the caption row or the axes, at 1x and 1_3x, in EN and TR`() {
        val content = contentAt(ScopeMinWidth.value)
        listOf(1f, 1.3f).forEach { scale ->
            val labels = Labels(scale)
            modes.forEach { mode ->
                listOf(0, 1).forEach { locale ->
                    val where = "$mode ${scale}x locale $locale"
                    val result = layout(mode, content, labels, locale)
                    assertClearAndInside(where, result, content)
                    result.tickLabels.forEachIndexed { i, r -> assertNotNull("$where tick $i dropped", r) }
                    val identity = when (mode) {
                        ScopeMode.RGB_PARADE -> result.channelLabels
                        ScopeMode.HISTOGRAM -> listOf(result.legend)
                        else -> emptyList()
                    }
                    identity.forEach { r ->
                        assertNotNull("$where channel identity dropped", r)
                        // On the caption row, so the plot keeps its height.
                        assertTrue("$where $r is not on the caption row ${result.caption}", r!!.top < result.caption.bottom)
                    }
                }
            }
        }
    }

    @Test
    fun `the old 78 dp histogram shows no colliding labels`() {
        val labels = Labels(1f)
        val content = contentAt(78f)
        val result = layout(ScopeMode.HISTOGRAM, content, labels)
        assertClearAndInside("78 dp", result, content)
        // The legend is wider than that panel: it is left out rather than squeezed.
        assertNull(result.legend)
    }

    @Test
    fun `a crowded waveform axis drops its middle label before either end`() {
        val labels = Labels(1f)
        val line = labels.ticks[0].height
        // Under the caption, room for the two end labels but not for the middle one between them.
        val content = Size(px(120f), line + gap + 2.5f * line)
        val result = layout(ScopeMode.LUMA_WAVEFORM, content, labels)
        assertNotNull(result.tickLabels[0])
        assertNull(result.tickLabels[1])
        assertNotNull(result.tickLabels[2])
    }

    @Test
    fun `waveform labels sit right-aligned in the gutter, left of the plot, under the caption`() {
        val labels = Labels(1f)
        val content = Size(px(200f), px(100f))
        val result = layout(ScopeMode.LUMA_WAVEFORM, content, labels)
        val gutter = labels.ticks.maxOf { it.width } + gap
        val plot = result.traces.single()
        assertEquals(gutter, plot.left, 1e-4f)
        assertEquals(result.caption.bottom + gap, plot.top, 1e-4f)
        result.tickLabels.forEach { assertEquals(gutter - gap, it!!.right, 1e-4f) }
        assertEquals(plot.top, result.tickLabels[2]!!.top, 1e-4f)
        assertEquals(content.height, result.tickLabels[0]!!.bottom, 1e-4f)
    }

    @Test
    fun `histogram legend sits right-aligned on the caption row beside the full caption when it fits`() {
        val labels = Labels(1f)
        val content = Size(px(320f), px(180f))
        val result = layout(ScopeMode.HISTOGRAM, content, labels)
        assertFalse(result.shortCaption)
        val legend = result.legend!!
        assertEquals(content.width, legend.right, 1e-4f)
        assertEquals(0f, legend.top, 1e-4f)
        assertTrue(legend.left >= result.caption.right + gap)
        val plot = result.traces.single()
        assertEquals(maxOf(legend.bottom, result.caption.bottom) + gap, plot.top, 1e-4f)
        assertEquals(content.height - labels.ticks.maxOf { it.height } - gap, plot.bottom, 1e-4f)
        result.tickLabels.forEach { assertEquals(content.height, it!!.bottom, 1e-4f) }
    }

    @Test
    fun `histogram falls back to the short caption to keep the legend on the caption row`() {
        val labels = Labels(1f)
        val full = labels.caption("Histogram").width
        // Too narrow for the full caption beside the legend, wide enough for the short one.
        val content = Size(full + gap + labels.legend.width - 1f, px(180f))
        val result = layout(ScopeMode.HISTOGRAM, content, labels)
        assertTrue(result.shortCaption)
        val legend = result.legend!!
        assertTrue(legend.top < result.caption.bottom)
        assertTrue(legend.left >= result.caption.right + gap)
    }

    @Test
    fun `histogram legend gets its own row above the plot when it fits beside no caption`() {
        val labels = Labels(1f)
        // Wider than the legend and the full caption, too narrow for either caption beside the legend.
        val content = Size(labels.legend.width + gap, px(180f))
        val result = layout(ScopeMode.HISTOGRAM, content, labels)
        assertFalse(result.shortCaption)
        val legend = result.legend!!
        assertEquals(result.caption.bottom + gap, legend.top, 1e-4f)
        assertEquals(legend.bottom + gap, result.plot.top, 1e-4f)
    }

    @Test
    fun `parade letters sit centred above their own channel plot, outside it`() {
        val labels = Labels(1f)
        val result = layout(ScopeMode.RGB_PARADE, Size(px(320f), px(180f)), labels)
        assertEquals(3, result.traces.size)
        result.channelLabels.forEachIndexed { i, r ->
            val trace = result.traces[i]
            assertNotNull(r)
            assertTrue("letter $i not over its plot", r!!.left >= trace.left && r.right <= trace.right + 0.5f)
            assertEquals("letter $i off centre", trace.center.x, r.center.x, 0.5f)
            assertTrue("letter $i not above its plot", r.bottom + gap <= trace.top)
        }
    }
}
