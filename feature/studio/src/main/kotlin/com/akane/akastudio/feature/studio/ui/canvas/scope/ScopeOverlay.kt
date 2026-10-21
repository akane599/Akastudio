package com.akane.akastudio.feature.studio.ui.canvas.scope

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.akane.akastudio.core.color.api.ImagePixels
import com.akane.akastudio.feature.studio.R
import com.akane.akastudio.feature.studio.mvi.ScopeMode
import com.akane.akastudio.feature.studio.ui.theme.DarkroomColors
import com.akane.akastudio.feature.studio.ui.theme.DarkroomTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** The scope panel's width-to-height ratio. */
internal const val SCOPE_ASPECT = 16f / 10f

/**
 * The narrowest the docked scope panel gets (the canvas still caps it): wide enough for the caption, the 0 / 50 / 100
 * labels and the R / G / B legend side by side without touching, in English and Turkish at 1.3x font scale.
 */
val ScopeMinWidth: Dp = 160.dp

/** Test tag on every piece of text the scope panel shows: its caption, axis labels, channel letters and legend. */
const val SCOPE_LABEL_TAG = "scope_label"

private const val PANEL_ALPHA = 0.82f
private const val HISTOGRAM_FILL_ALPHA = 0.38f

private val TICK_LABELS = listOf("0", "50", "100")
private val CHANNEL_LABELS = listOf("R", "G", "B")

/** Scope content ready to draw: rasters for waveforms, normalized heights for the histogram. */
@Immutable
internal sealed interface ScopeData {
    /** A luma waveform raster. */
    class Waveform(val image: ImageBitmap) : ScopeData

    /** Three channel waveform rasters, drawn side by side. */
    class Parade(val red: ImageBitmap, val green: ImageBitmap, val blue: ImageBitmap) : ScopeData

    /** Per-channel histogram heights in `[0, 1]`, 256 bins each. */
    class Histogram(val red: FloatArray, val green: FloatArray, val blue: FloatArray, val luma: FloatArray) : ScopeData

    companion object {
        /** Computes [mode]'s scope for [pixels] coloured with [colors]; null for [ScopeMode.OFF]. CPU-bound. */
        fun compute(pixels: ImagePixels, mode: ScopeMode, colors: DarkroomColors): ScopeData? = when (mode) {
            ScopeMode.OFF -> null
            ScopeMode.LUMA_WAVEFORM -> Waveform(raster(WaveformCalculator.luma(pixels), colors.scopeLuma))
            ScopeMode.RGB_PARADE -> WaveformCalculator.rgbParade(pixels).let {
                Parade(
                    red = raster(it.red, colors.scopeR),
                    green = raster(it.green, colors.scopeG),
                    blue = raster(it.blue, colors.scopeB),
                )
            }
            ScopeMode.HISTOGRAM -> WaveformCalculator.histogram(pixels).let {
                Histogram(
                    red = ScopeRaster.histogramHeights(it.red),
                    green = ScopeRaster.histogramHeights(it.green),
                    blue = ScopeRaster.histogramHeights(it.blue),
                    luma = ScopeRaster.histogramHeights(it.luma),
                )
            }
        }

        private fun raster(map: DensityMap, colour: Color): ImageBitmap = Bitmap
            .createBitmap(ScopeRaster.density(map, colour.toArgb()), map.columns, map.levels, Bitmap.Config.ARGB_8888)
            .asImageBitmap()
    }
}

/**
 * A translucent video-scope panel (16:10) for [pixels] in [mode]; draws nothing for [ScopeMode.OFF].
 *
 * The scope is recomputed on [Dispatchers.Default] only when [pixels] or [mode] change; the previous trace stays on
 * screen until the new one is ready. Size it with [modifier] (the studio canvas docks it at 40% of the canvas width,
 * never narrower than [ScopeMinWidth] while the canvas allows).
 */
@Composable
fun ScopeOverlay(pixels: ImagePixels?, mode: ScopeMode, modifier: Modifier = Modifier) {
    if (mode == ScopeMode.OFF) return
    val colors = DarkroomTheme.colors
    val data by produceState<ScopeData?>(initialValue = null, pixels, mode, colors) {
        value = pixels?.let { p -> withContext(Dispatchers.Default) { ScopeData.compute(p, mode, colors) } }
    }
    ScopePanel(data = data, mode = mode, modifier = modifier)
}

/**
 * Draws precomputed scope [data] (or just the labelled level ticks while it is null) in the translucent scope panel,
 * under a caption naming the active [mode].
 *
 * The caption falls back to its short form when the full one does not fit, and a label that would leave the plot or
 * touch another is left out (see [ScopeLabelLayout.layout]), so no text overlaps or clips at any panel size.
 */
@Composable
internal fun ScopePanel(data: ScopeData?, mode: ScopeMode, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val colors = DarkroomTheme.colors
    val spacing = DarkroomTheme.spacing
    val caption = stringResource(
        when (mode) {
            ScopeMode.RGB_PARADE -> R.string.canvas_scope_parade
            ScopeMode.HISTOGRAM -> R.string.canvas_scope_histogram
            ScopeMode.LUMA_WAVEFORM, ScopeMode.OFF -> R.string.canvas_scope_luma
        },
    )
    val shortCaption = stringResource(
        when (mode) {
            ScopeMode.RGB_PARADE -> R.string.canvas_scope_parade_short
            ScopeMode.HISTOGRAM -> R.string.canvas_scope_histogram_short
            ScopeMode.LUMA_WAVEFORM, ScopeMode.OFF -> R.string.canvas_scope_luma_short
        },
    )
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = scheme.onSurfaceVariant)
    val tickLabels = remember(measurer, labelStyle) { TICK_LABELS.map { measurer.measure(it, labelStyle).size.toSize() } }
    val channelLabels = remember(measurer, labelStyle) {
        CHANNEL_LABELS.map { measurer.measure(it, labelStyle).size.toSize() }
    }
    val tick = scheme.outline
    BoxWithConstraints(
        modifier = modifier
            .aspectRatio(SCOPE_ASPECT)
            .background(scheme.surfaceContainerLowest.copy(alpha = PANEL_ALPHA), MaterialTheme.shapes.small)
            .border(Dp.Hairline, scheme.outline, MaterialTheme.shapes.small)
            .semantics { contentDescription = caption }
            .padding(spacing.s),
    ) {
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else 0
        val shown = remember(measurer, labelStyle, caption, shortCaption, width) {
            if (measurer.measure(caption, labelStyle, maxLines = 1).size.width <= width) caption else shortCaption
        }
        val density = LocalDensity.current
        val gap = with(density) { spacing.xs.roundToPx() }
        val legend = with(density) { legendSize(channelLabels, spacing.s.roundToPx(), spacing.xs.roundToPx()) }
        val plotTop = remember(measurer, labelStyle, shown) { measurer.measure(shown, labelStyle, maxLines = 1).size.height } + gap
        val plot = remember(mode, width, height, plotTop, tickLabels, channelLabels, legend, gap) {
            val plotSize = Size(width.toFloat(), (height - plotTop).coerceAtLeast(0).toFloat())
            ScopeLabelLayout.layout(mode, plotSize, tickLabels, channelLabels, legend, gap.toFloat())
        }
        Canvas(Modifier.fillMaxSize()) {
            translate(top = plotTop.toFloat()) { drawPlot(mode, data, plot, tick, colors) }
        }
        ScopeText(shown, labelStyle, IntOffset.Zero)
        plot.tickLabels.forEachIndexed { i, rect -> rect?.let { ScopeText(TICK_LABELS[i], labelStyle, it.at(plotTop)) } }
        plot.channelLabels.forEachIndexed { i, rect ->
            rect?.let { ScopeText(CHANNEL_LABELS[i], labelStyle, it.at(plotTop)) }
        }
        plot.legend?.let { ChannelLegend(Modifier.offset { it.at(plotTop) }.testTag(SCOPE_LABEL_TAG)) }
    }
}

/** [rect]'s whole-pixel top-left in the panel, the plot starting [plotTop] pixels down. */
private fun Rect.at(plotTop: Int) = IntOffset(left.roundToInt(), top.roundToInt() + plotTop)

/**
 * One line of scope text at [offset]. It is decoration for sighted users (the panel announces the scope), so it is
 * hidden from accessibility services but tagged for layout checks.
 */
@Composable
private fun ScopeText(text: String, style: TextStyle, offset: IntOffset) {
    Text(
        text = text,
        style = style,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .offset { offset }
            .testTag(SCOPE_LABEL_TAG)
            .semantics { hideFromAccessibility() },
    )
}

/**
 * The size [ChannelLegend] lays out at, from its [letters]' sizes: per channel a [dot]-wide dot, an [innerGap] and
 * the letter, channels [dot] apart (the legend's own spacing tokens, in pixels).
 */
private fun legendSize(letters: List<Size>, dot: Int, innerGap: Int): Size {
    val width = letters.sumOf { (dot + innerGap + it.width).toDouble() }.toFloat() + dot * (letters.size - 1)
    val height = maxOf(dot.toFloat(), letters.maxOfOrNull { it.height } ?: 0f)
    return Size(width, height)
}

/** Dot-and-letter R/G/B identity for the histogram, so channels are never told apart by colour alone. */
@Composable
private fun ChannelLegend(modifier: Modifier = Modifier) {
    val colors = DarkroomTheme.colors
    val spacing = DarkroomTheme.spacing
    val channels = CHANNEL_LABELS.zip(listOf(colors.scopeR, colors.scopeG, colors.scopeB))
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(spacing.s), verticalAlignment = Alignment.CenterVertically) {
        channels.forEach { (label, colour) ->
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(spacing.s).background(colour, CircleShape))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The ticks and traces of [plot]; its labels are separate text nodes. */
private fun DrawScope.drawPlot(mode: ScopeMode, data: ScopeData?, plot: ScopePlotLayout, tick: Color, colors: DarkroomColors) {
    when (mode) {
        ScopeMode.HISTOGRAM -> {
            val area = plot.traces.first().size
            drawCodeTicks(area, tick)
            if (data is ScopeData.Histogram) {
                drawHistogramFill(data.red, colors.scopeR, area)
                drawHistogramFill(data.green, colors.scopeG, area)
                drawHistogramFill(data.blue, colors.scopeB, area)
                drawHistogramLine(data.luma, colors.scopeLuma, 1.dp.toPx(), area)
            }
        }
        ScopeMode.RGB_PARADE -> {
            val images = (data as? ScopeData.Parade)?.let { listOf(it.red, it.green, it.blue) }
            plot.traces.forEachIndexed { i, area ->
                drawLevelTicks(area.topLeft, area.size, tick)
                images?.getOrNull(i)?.let { drawRaster(it, area.topLeft, area.size) }
            }
        }
        ScopeMode.LUMA_WAVEFORM, ScopeMode.OFF -> {
            val area = plot.traces.first()
            drawLevelTicks(area.topLeft, area.size, tick)
            (data as? ScopeData.Waveform)?.let { drawRaster(it.image, area.topLeft, area.size) }
        }
    }
}

/** Hairlines at 0, 50 and 100 % across a waveform plot at [origin] of [plot] size. */
private fun DrawScope.drawLevelTicks(origin: Offset, plot: Size, colour: Color) {
    TICK_FRACTIONS.forEach { f ->
        val y = origin.y + plot.height * (1f - f)
        drawLine(colour, Offset(origin.x, y), Offset(origin.x + plot.width, y), strokeWidth = Stroke.HairlineWidth)
    }
}

/** Vertical hairlines at code 0, the midpoint and code 255 across a histogram [plot]. */
private fun DrawScope.drawCodeTicks(plot: Size, colour: Color) {
    TICK_FRACTIONS.forEach { f ->
        val x = plot.width * f
        drawLine(colour, Offset(x, 0f), Offset(x, plot.height), strokeWidth = Stroke.HairlineWidth)
    }
}

private fun DrawScope.drawRaster(image: ImageBitmap, origin: Offset, plot: Size) {
    drawImage(
        image = image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(origin.x.toInt(), origin.y.toInt()),
        dstSize = IntSize(plot.width.toInt(), plot.height.toInt()),
        filterQuality = FilterQuality.Low,
    )
}

private fun histogramPath(heights: FloatArray, closed: Boolean, plot: Size): Path {
    val path = Path()
    val last = (heights.size - 1).coerceAtLeast(1)
    if (closed) path.moveTo(0f, plot.height)
    heights.forEachIndexed { i, h ->
        val x = plot.width * i / last
        val y = plot.height * (1f - h)
        if (i == 0 && !closed) path.moveTo(x, y) else path.lineTo(x, y)
    }
    if (closed) {
        path.lineTo(plot.width, plot.height)
        path.close()
    }
    return path
}

private fun DrawScope.drawHistogramFill(heights: FloatArray, colour: Color, plot: Size) {
    drawPath(histogramPath(heights, closed = true, plot), colour.copy(alpha = HISTOGRAM_FILL_ALPHA))
}

private fun DrawScope.drawHistogramLine(heights: FloatArray, colour: Color, width: Float, plot: Size) {
    drawPath(histogramPath(heights, closed = false, plot), colour, style = Stroke(width = width))
}
