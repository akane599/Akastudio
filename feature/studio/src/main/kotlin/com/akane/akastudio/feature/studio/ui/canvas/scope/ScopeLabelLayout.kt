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
 * Where everything in the scope panel's content goes, in the content's own pixels.
 *
 * [caption] is the caption line, [shortCaption] whether it shows the short form. [plot] is the area the ticks and
 * traces are drawn in, and [traces] its parts: one for the luma waveform and the histogram, three (R, G, B) for the
 * parade. No label touches [plot]: the channel identity (the parade's [channelLabels], the histogram's [legend])
 * sits on the caption row or on its own row under it. A null label is one that does not fit without leaving the
 * content or touching another label or the plot; the panel simply does not show it.
 */
internal class ScopePlotLayout(
    val caption: Rect,
    val shortCaption: Boolean,
    val plot: Rect,
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
     * Lays out [mode]'s scope in [content]: [caption] and [shortCaption] are the two caption sizes, [tickLabels] the
     * 0 / 50 / 100 label sizes, [channelLabels] the R / G / B letters (parade), [legend] the channel legend
     * (histogram), [gap] the spacing between parts.
     *
     * The channel identity goes on the caption row, beside the full caption or else the short one; when it fits
     * beside neither it gets its own row between the caption and the plot. Every label it returns lies inside the
     * content and touches neither another label, the caption nor the plot, at any size (zero and NaN included).
     * Positions are whole pixels so the drawn labels keep that guarantee. Never throws.
     */
    fun layout(
        mode: ScopeMode,
        content: Size,
        caption: Size,
        shortCaption: Size,
        tickLabels: List<Size>,
        channelLabels: List<Size>,
        legend: Size,
        gap: Float,
    ): ScopePlotLayout {
        val bounds = Rect(0f, 0f, content.width.orZero(), content.height.orZero())
        val gutter = if (mode == ScopeMode.HISTOGRAM) 0f else (tickLabels.maxOfOrNull { it.width } ?: 0f) + gap
        val left = gutter.coerceAtMost(bounds.right)
        val columns = if (mode == ScopeMode.RGB_PARADE) columns(left, bounds.right, channelLabels.size, gap) else emptyList()
        val identity = when (mode) {
            ScopeMode.HISTOGRAM -> listOf(Rect(Offset(bounds.right - legend.width, 0f), legend))
            ScopeMode.RGB_PARADE -> channelLabels.mapIndexed { i, size -> centredOver(columns[i], size) }
            ScopeMode.LUMA_WAVEFORM, ScopeMode.OFF -> emptyList()
        }
        val header = header(bounds, caption, shortCaption, identity, gap)
        val plotTop = (header.bottom + gap).coerceAtMost(bounds.bottom)
        val band = if (mode == ScopeMode.HISTOGRAM) (tickLabels.maxOfOrNull { it.height } ?: 0f) + gap else 0f
        val plot = Rect(left, plotTop, bounds.right, (bounds.bottom - band).coerceAtLeast(plotTop))
        val placer = Placer(bounds, listOf(header.caption, plot))
        val placedIdentity = header.identity.map { placer.place(it) }
        val ticks = if (mode == ScopeMode.HISTOGRAM) {
            placeTicks(placer, tickLabels) { size, fraction ->
                start(plot.width * fraction, size.width, bounds.width)
                    ?.let { x -> Rect(Offset(x, bounds.bottom - size.height), size) }
            }
        } else {
            placeTicks(placer, tickLabels) { size, fraction ->
                start(plot.height * (1f - fraction), size.height, plot.height)
                    ?.let { y -> Rect(Offset(left - gap - size.width, plot.top + y), size) }
            }
        }
        return ScopePlotLayout(
            caption = header.caption,
            shortCaption = header.short,
            plot = plot,
            traces = if (columns.isEmpty()) listOf(plot) else columns.map { Rect(it.left, plot.top, it.right, plot.bottom) },
            tickLabels = ticks,
            channelLabels = if (mode == ScopeMode.RGB_PARADE) placedIdentity.ifEmpty { channelLabels.map { null } } else emptyList(),
            legend = if (mode == ScopeMode.HISTOGRAM) placedIdentity.firstOrNull() else null,
        )
    }

    /** [count] equal columns side by side from [left] to [right], [gap] apart; only their horizontal extents count. */
    private fun columns(left: Float, right: Float, count: Int, gap: Float): List<Rect> {
        val width = ((right - left - (count - 1) * gap) / count).coerceAtLeast(0f)
        return List(count) { i -> (left + i * (width + gap)).let { x -> Rect(x, 0f, x + width, 0f) } }
    }

    /** A [size] label centred over [column], at the top; null when the column is narrower than the label. */
    private fun centredOver(column: Rect, size: Size): Rect? =
        start(column.width / 2f, size.width, column.width)?.let { x -> Rect(Offset(column.left + x, 0f), size) }

    /** The caption and, when it fits across the content, the channel [identity] moved onto its row. */
    private class Header(val caption: Rect, val short: Boolean, val identity: List<Rect>, val bottom: Float)

    private fun header(bounds: Rect, full: Size, short: Size, identity: List<Rect?>, gap: Float): Header {
        val fitting = identity.filterNotNull().takeIf { row ->
            row.isNotEmpty() && row.size == identity.size && row.all { it.left >= bounds.left && it.right <= bounds.right }
        }.orEmpty()
        val rowHeight = fitting.maxOfOrNull { it.height } ?: 0f
        if (fitting.isNotEmpty()) {
            listOf(false to full, true to short).forEach { (isShort, caption) ->
                if (fitting.all { it.left >= caption.width.orZero() + gap }) {
                    val row = maxOf(caption.height.orZero(), rowHeight)
                    val centred = fitting.map { it.translate(0f, (row - it.height) / 2f) }
                    return Header(captionRect(bounds, caption), isShort, centred, row)
                }
            }
        }
        val isShort = !(full.width <= bounds.width)
        val caption = captionRect(bounds, if (isShort) short else full)
        if (fitting.isEmpty()) return Header(caption, isShort, emptyList(), caption.bottom)
        val top = caption.bottom + gap
        return Header(caption, isShort, fitting.map { it.translate(0f, top) }, top + rowHeight)
    }

    /** The caption line at the top start, cut to the content width as the drawn caption is ellipsized. */
    private fun captionRect(bounds: Rect, caption: Size) =
        Rect(0f, 0f, caption.width.orZero().coerceAtMost(bounds.width), caption.height.orZero())

    private inline fun placeTicks(placer: Placer, ticks: List<Size>, candidate: (Size, Float) -> Rect?): List<Rect?> {
        val placed = arrayOfNulls<Rect>(ticks.size)
        TICK_PRIORITY.filter { it < ticks.size }.forEach { i ->
            placed[i] = placer.place(candidate(ticks[i], TICK_FRACTIONS[i]))
        }
        return placed.toList()
    }

    /** Accepts each label that lies inside [bounds] and clear of the [taken] areas and every label accepted before. */
    private class Placer(private val bounds: Rect, taken: List<Rect>) {
        private val placed = taken.toMutableList()

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
