package com.akane.akastudio.feature.studio.ui.canvas.scope

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.akane.akastudio.core.color.api.ImagePixels
import com.akane.akastudio.feature.studio.R
import com.akane.akastudio.feature.studio.mvi.ScopeMode
import com.akane.akastudio.feature.studio.ui.theme.DarkroomColors
import com.akane.akastudio.feature.studio.ui.theme.DarkroomTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The scope panel's width-to-height ratio. */
internal const val SCOPE_ASPECT = 16f / 10f

private const val PANEL_ALPHA = 0.82f
private const val HISTOGRAM_FILL_ALPHA = 0.38f

/** Level ticks at 0, 50 and 100 % of the signal range, with their axis labels in the same order. */
private val TICK_FRACTIONS = floatArrayOf(0f, 0.5f, 1f)
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
 * screen until the new one is ready. Size it with [modifier] (the studio canvas docks it at 40% of the canvas width).
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
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = scheme.onSurfaceVariant)
    val tickLabels = remember(measurer, labelStyle) { TICK_LABELS.map { measurer.measure(it, labelStyle) } }
    val channelLabels = remember(measurer, labelStyle) { CHANNEL_LABELS.map { measurer.measure(it, labelStyle) } }
    val tick = scheme.outline
    Column(
        modifier = modifier
            .aspectRatio(SCOPE_ASPECT)
            .background(scheme.surfaceContainerLowest.copy(alpha = PANEL_ALPHA), MaterialTheme.shapes.small)
            .border(Dp.Hairline, scheme.outline, MaterialTheme.shapes.small)
            .semantics { contentDescription = caption }
            .padding(spacing.s),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Text(
            text = caption,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // The panel already announces the scope; the caption is its visible twin.
            modifier = Modifier.clearAndSetSemantics {},
        )
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Canvas(Modifier.fillMaxSize()) {
                val gap = spacing.xs.toPx()
                if (mode == ScopeMode.HISTOGRAM) {
                    val plot = Size(size.width, size.height - tickLabels.maxOf { it.size.height } - gap)
                    drawCodeTicks(plot, tick, tickLabels)
                    if (data is ScopeData.Histogram) {
                        drawHistogramFill(data.red, colors.scopeR, plot)
                        drawHistogramFill(data.green, colors.scopeG, plot)
                        drawHistogramFill(data.blue, colors.scopeB, plot)
                        drawHistogramLine(data.luma, colors.scopeLuma, 1.dp.toPx(), plot)
                    }
                } else {
                    val gutter = tickLabels.maxOf { it.size.width } + gap
                    drawLevelLabels(tickLabels, gutter - gap)
                    translate(left = gutter) {
                        val area = Size(size.width - gutter, size.height)
                        if (mode == ScopeMode.RGB_PARADE) {
                            drawParade(data as? ScopeData.Parade, area, gap, tick, channelLabels)
                        } else {
                            drawLevelTicks(Offset.Zero, area, tick)
                            (data as? ScopeData.Waveform)?.let { drawRaster(it.image, Offset.Zero, area) }
                        }
                    }
                }
            }
            if (mode == ScopeMode.HISTOGRAM) ChannelLegend(Modifier.align(Alignment.TopEnd).padding(horizontal = spacing.xs))
        }
    }
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
                )
            }
        }
    }
}

/** Three side-by-side channel plots in [area], each with its level ticks, its trace (if any) and its R/G/B letter. */
private fun DrawScope.drawParade(
    data: ScopeData.Parade?,
    area: Size,
    gap: Float,
    tick: Color,
    channelLabels: List<TextLayoutResult>,
) {
    val panel = Size((area.width - 2 * gap) / 3f, area.height)
    val images = data?.let { listOf(it.red, it.green, it.blue) }
    channelLabels.forEachIndexed { i, label ->
        val origin = Offset(i * (panel.width + gap), 0f)
        drawLevelTicks(origin, panel, tick)
        images?.let { drawRaster(it[i], origin, panel) }
        drawText(label, topLeft = origin + Offset(gap, gap))
    }
}

/** Hairlines at 0, 50 and 100 % across a waveform plot at [origin] of [plot] size. */
private fun DrawScope.drawLevelTicks(origin: Offset, plot: Size, colour: Color) {
    TICK_FRACTIONS.forEach { f ->
        val y = origin.y + plot.height * (1f - f)
        drawLine(colour, Offset(origin.x, y), Offset(origin.x + plot.width, y), strokeWidth = Stroke.HairlineWidth)
    }
}

/** The 0 / 50 / 100 level labels, right-aligned to [right] and centred on their ticks, kept inside the plot height. */
private fun DrawScope.drawLevelLabels(labels: List<TextLayoutResult>, right: Float) {
    labels.forEachIndexed { i, label ->
        val height = label.size.height.toFloat()
        val top = (size.height * (1f - TICK_FRACTIONS[i]) - height / 2f).coerceIn(0f, size.height - height)
        drawText(label, topLeft = Offset(right - label.size.width, top))
    }
}

/** Vertical hairlines at code 0, the midpoint and code 255 across a histogram [plot], labelled 0 / 50 / 100 below it. */
private fun DrawScope.drawCodeTicks(plot: Size, colour: Color, labels: List<TextLayoutResult>) {
    TICK_FRACTIONS.forEachIndexed { i, f ->
        val x = plot.width * f
        drawLine(colour, Offset(x, 0f), Offset(x, plot.height), strokeWidth = Stroke.HairlineWidth)
        val label = labels[i]
        val left = (x - label.size.width / 2f).coerceIn(0f, size.width - label.size.width)
        drawText(label, topLeft = Offset(left, size.height - label.size.height))
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
