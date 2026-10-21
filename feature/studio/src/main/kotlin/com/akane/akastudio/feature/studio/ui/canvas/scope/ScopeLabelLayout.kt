package com.akane.akastudio.feature.studio.ui.canvas.scope

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.akane.akastudio.feature.studio.mvi.ScopeMode
import kotlin.math.roundToInt

/** Level ticks at 0, 50 and 100 % of the signal range; the axis labels follow the same order. */
internal val TICK_FRACTIONS = floatArrayOf(0f, 0.5f, 1f)

/** Axis labels are placed ends first, so a crowded axis drops its middle (50) label before either end. */
private val TICK_PRIORITY = intArrayOf(0, 2, 1)

/**
 * Where everything in the scope's plot goes, in the plot's own pixels.
 *
 * [traces] are the areas the ticks and traces are drawn in: one for the luma waveform and the histogram, three
 * (R, G, B) for the parade. A null label is one that does not fit without leaving the plot or touching another label;
 * the panel simply does not show it.
 */
internal class ScopePlotLayout(
    val traces: List<Rect>,
    val tickLabels: List<Rect?>,
    val channelLabels: List<Rect?>,
    val legend: Rect?,
)

/** Placement math for the scope's labels, kept free of composables so it can be tested on the JVM. */
internal object ScopeLabelLayout {

    /**
     * Start offset (top or left) of a label [extent] long, centred on [centre] and kept inside `[0, available]`.
     *
     * Null when the label does not fit, which happens while the panel animates in from zero size: the caller skips
     * that label rather than drawing it outside the plot. Never throws.
     */
    fun start(centre: Float, extent: Float, available: Float): Float? {
        val room = available - extent
        if (!(room >= 0f)) return null // also rejects NaN, which coerceIn would pass through unclamped
        return (centre - extent / 2f).coerceIn(0f, room)
    }

    /**
     * Lays out [mode]'s plot of [plot] size: [tickLabels] are the 0 / 50 / 100 label sizes, [channelLabels] the
     * R / G / B letters (parade), [legend] the channel legend (histogram), [gap] the spacing between parts.
     *
     * Every label it returns lies inside the plot and overlaps no other returned label, at any plot size (zero and
     * NaN included). Positions are whole pixels so the drawn labels keep that guarantee. Never throws.
     */
    fun layout(
        mode: ScopeMode,
        plot: Size,
        tickLabels: List<Size>,
        channelLabels: List<Size>,
        legend: Size,
        gap: Float,
    ): ScopePlotLayout {
        val bounds = Rect(0f, 0f, plot.width.orZero(), plot.height.orZero())
        return if (mode == ScopeMode.HISTOGRAM) {
            histogram(bounds, tickLabels, legend, gap)
        } else {
            waveform(bounds, tickLabels, if (mode == ScopeMode.RGB_PARADE) channelLabels else emptyList(), gap)
        }
    }

    /** Level labels right-aligned in a left gutter, the plot (or three parade plots) to its right. */
    private fun waveform(bounds: Rect, ticks: List<Size>, channels: List<Size>, gap: Float): ScopePlotLayout {
        val gutter = (ticks.maxOfOrNull { it.width } ?: 0f) + gap
        val area = Rect(gutter, 0f, bounds.right.coerceAtLeast(gutter), bounds.bottom)
        val placer = Placer(bounds)
        val tickRects = placeTicks(placer, ticks) { size, fraction ->
            start(bounds.height * (1f - fraction), size.height, bounds.height)
                ?.let { top -> Rect(Offset(gutter - gap - size.width, top), size) }
        }
        if (channels.isEmpty()) return ScopePlotLayout(listOf(area), tickRects, emptyList(), null)
        val panelWidth = ((area.width - (channels.size - 1) * gap) / channels.size).coerceAtLeast(0f)
        val panels = channels.indices.map { i ->
            val left = area.left + i * (panelWidth + gap)
            Rect(left, area.top, left + panelWidth, area.bottom)
        }
        val letters = channels.mapIndexed { i, size ->
            val panel = panels[i]
            placer.place(Rect(Offset(panel.left + gap, panel.top + gap), size).takeIf { it.isInside(panel) })
        }
        return ScopePlotLayout(panels, tickRects, letters, null)
    }

    /** The legend at the top end, the 0 / 50 / 100 code labels in a band along the bottom, the plot between. */
    private fun histogram(bounds: Rect, ticks: List<Size>, legend: Size, gap: Float): ScopePlotLayout {
        val band = (ticks.maxOfOrNull { it.height } ?: 0f) + gap
        val area = Rect(0f, 0f, bounds.right, (bounds.bottom - band).coerceAtLeast(0f))
        val placer = Placer(bounds)
        // Inset one gap from the end so the legend sits off the 100 % hairline.
        val legendRect = Rect(Offset(bounds.right - gap - legend.width, 0f), legend)
        val placedLegend = placer.place(legendRect.takeIf { it.isInside(area) })
        val tickRects = placeTicks(placer, ticks) { size, fraction ->
            start(area.width * fraction, size.width, bounds.width)
                ?.let { left -> Rect(Offset(left, bounds.bottom - size.height), size) }
        }
        return ScopePlotLayout(listOf(area), tickRects, emptyList(), placedLegend)
    }

    private inline fun placeTicks(placer: Placer, ticks: List<Size>, candidate: (Size, Float) -> Rect?): List<Rect?> {
        val placed = arrayOfNulls<Rect>(ticks.size)
        TICK_PRIORITY.filter { it < ticks.size }.forEach { i ->
            placed[i] = placer.place(candidate(ticks[i], TICK_FRACTIONS[i]))
        }
        return placed.toList()
    }

    /** Accepts each label that lies inside the plot and clear of every label accepted before it. */
    private class Placer(private val bounds: Rect) {
        private val placed = mutableListOf<Rect>()

        fun place(candidate: Rect?): Rect? {
            // Checked before snapping too: rounding a NaN edge would throw.
            if (candidate == null || !candidate.isInside(bounds)) return null
            val rect = candidate.snapped()
            if (!rect.isInside(bounds) || placed.any { it.overlaps(rect) }) return null
            placed += rect
            return rect
        }
    }

    private fun Float.orZero(): Float = if (this > 0f && isFinite()) this else 0f

    /** Whole-pixel position, same size, so a label drawn at the rounded offset covers exactly this rectangle. */
    private fun Rect.snapped(): Rect = Rect(Offset(left.roundToInt().toFloat(), top.roundToInt().toFloat()), size)

    /** False for NaN edges, so an undefined rectangle is never accepted. */
    private fun Rect.isInside(outer: Rect): Boolean =
        left >= outer.left && top >= outer.top && right <= outer.right && bottom <= outer.bottom
}
