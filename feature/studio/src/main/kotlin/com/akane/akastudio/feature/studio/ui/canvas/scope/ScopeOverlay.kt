package com.akane.akastudio.feature.studio.ui.canvas.scope

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
private const val GRATICULE_ALPHA = 0.32f
private const val HISTOGRAM_FILL_ALPHA = 0.38f
private val GRATICULE_FRACTIONS = floatArrayOf(0f, 0.5f, 1f)

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

/** Draws precomputed scope [data] (or just the graticule while it is null) in the translucent scope panel. */
@Composable
internal fun ScopePanel(data: ScopeData?, mode: ScopeMode, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val colors = DarkroomTheme.colors
    val spacing = DarkroomTheme.spacing
    val description = stringResource(
        when (mode) {
            ScopeMode.RGB_PARADE -> R.string.canvas_scope_parade
            ScopeMode.HISTOGRAM -> R.string.canvas_scope_histogram
            ScopeMode.LUMA_WAVEFORM, ScopeMode.OFF -> R.string.canvas_scope_luma
        },
    )
    val graticule = scheme.onSurfaceVariant.copy(alpha = GRATICULE_ALPHA)
    Box(
        modifier = modifier
            .aspectRatio(SCOPE_ASPECT)
            .background(scheme.surfaceContainerLowest.copy(alpha = PANEL_ALPHA), MaterialTheme.shapes.small)
            .border(Dp.Hairline, scheme.outline, MaterialTheme.shapes.small)
            .semantics { contentDescription = description },
    ) {
        Canvas(Modifier.fillMaxSize().padding(spacing.s)) {
            when (data) {
                is ScopeData.Waveform -> {
                    drawLevelGraticule(Offset.Zero, size, graticule)
                    drawRaster(data.image, Offset.Zero, size)
                }
                is ScopeData.Parade -> {
                    val gap = spacing.xs.toPx()
                    val panel = Size((size.width - 2 * gap) / 3f, size.height)
                    listOf(data.red, data.green, data.blue).forEachIndexed { i, image ->
                        val origin = Offset(i * (panel.width + gap), 0f)
                        drawLevelGraticule(origin, panel, graticule)
                        drawRaster(image, origin, panel)
                    }
                }
                is ScopeData.Histogram -> {
                    drawCodeGraticule(graticule)
                    drawHistogramFill(data.red, colors.scopeR)
                    drawHistogramFill(data.green, colors.scopeG)
                    drawHistogramFill(data.blue, colors.scopeB)
                    drawHistogramLine(data.luma, colors.scopeLuma, 1.dp.toPx())
                }
                null -> if (mode == ScopeMode.HISTOGRAM) {
                    drawCodeGraticule(graticule)
                } else {
                    drawLevelGraticule(Offset.Zero, size, graticule)
                }
            }
        }
        if (mode != ScopeMode.LUMA_WAVEFORM) ChannelLegend(mode, Modifier.align(Alignment.TopStart).padding(spacing.s))
    }
}

/** R/G/B identity for the multi-channel scopes, so channels are never told apart by colour alone. */
@Composable
private fun ChannelLegend(mode: ScopeMode, modifier: Modifier = Modifier) {
    val colors = DarkroomTheme.colors
    val spacing = DarkroomTheme.spacing
    val channels = listOf("R" to colors.scopeR, "G" to colors.scopeG, "B" to colors.scopeB)
    if (mode == ScopeMode.RGB_PARADE) {
        Row(modifier.fillMaxWidth()) {
            channels.forEach { (label, _) ->
                Box(Modifier.weight(1f)) { ChannelLabel(label) }
            }
        }
    } else {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(spacing.s), verticalAlignment = Alignment.CenterVertically) {
            channels.forEach { (label, colour) ->
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(spacing.s).background(colour, CircleShape))
                    ChannelLabel(label)
                }
            }
        }
    }
}

@Composable
private fun ChannelLabel(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Hairlines at 0, 50 and 100 IRE across a waveform plot at [origin] of [plot] size. */
private fun DrawScope.drawLevelGraticule(origin: Offset, plot: Size, colour: Color) {
    GRATICULE_FRACTIONS.forEach { f ->
        val y = origin.y + plot.height * (1f - f)
        drawLine(colour, Offset(origin.x, y), Offset(origin.x + plot.width, y), strokeWidth = Stroke.HairlineWidth)
    }
}

/** Vertical hairlines at code 0, the midpoint and code 255 across a histogram. */
private fun DrawScope.drawCodeGraticule(colour: Color) {
    GRATICULE_FRACTIONS.forEach { f ->
        val x = size.width * f
        drawLine(colour, Offset(x, 0f), Offset(x, size.height), strokeWidth = Stroke.HairlineWidth)
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

private fun DrawScope.histogramPath(heights: FloatArray, closed: Boolean): Path {
    val path = Path()
    val last = (heights.size - 1).coerceAtLeast(1)
    if (closed) path.moveTo(0f, size.height)
    heights.forEachIndexed { i, h ->
        val x = size.width * i / last
        val y = size.height * (1f - h)
        if (i == 0 && !closed) path.moveTo(x, y) else path.lineTo(x, y)
    }
    if (closed) {
        path.lineTo(size.width, size.height)
        path.close()
    }
    return path
}

private fun DrawScope.drawHistogramFill(heights: FloatArray, colour: Color) {
    drawPath(histogramPath(heights, closed = true), colour.copy(alpha = HISTOGRAM_FILL_ALPHA))
}

private fun DrawScope.drawHistogramLine(heights: FloatArray, colour: Color, width: Float) {
    drawPath(histogramPath(heights, closed = false), colour, style = Stroke(width = width))
}
