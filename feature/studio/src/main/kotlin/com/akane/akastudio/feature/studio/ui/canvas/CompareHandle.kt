package com.akane.akastudio.feature.studio.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.akane.akastudio.feature.studio.R
import com.akane.akastudio.feature.studio.ui.theme.DarkroomTheme
import kotlin.math.roundToInt

private val HANDLE_LINE_WIDTH = 2.dp
private val HANDLE_GRIP_DIAMETER = 28.dp
private val HANDLE_TOUCH_WIDTH = 48.dp
private val CHEVRON_HALF_HEIGHT = 4.dp
private val CHEVRON_LENGTH = 4.dp
private val CHEVRON_GAP = 2.dp
private const val LABEL_SCRIM_ALPHA = 0.5f

/**
 * The vertical before/after split handle at [split] (fraction of the width, `[0, 1]`): a 2 dp line and a 28 dp
 * circular grip with left/right chevrons. Dragging it horizontally reports the new split through [onSplit];
 * accessibility services see it as a 0..1 progress value they can set directly.
 */
@Composable
internal fun CompareHandle(split: Float, onSplit: (Float) -> Unit, modifier: Modifier = Modifier) {
    val clamped = if (split.isFinite()) split.coerceIn(0f, 1f) else 0f
    val currentSplit by rememberUpdatedState(clamped)
    val currentOnSplit by rememberUpdatedState(onSplit)
    val description = stringResource(R.string.canvas_compare_handle)
    val scheme = MaterialTheme.colorScheme
    BoxWithConstraints(modifier) {
        val widthPx = constraints.maxWidth.toFloat()
        val touchPx = with(LocalDensity.current) { HANDLE_TOUCH_WIDTH.toPx() }
        Canvas(
            Modifier
                .offset { IntOffset((clamped * widthPx - touchPx / 2f).roundToInt(), 0) }
                .width(HANDLE_TOUCH_WIDTH)
                .fillMaxHeight()
                .semantics {
                    contentDescription = description
                    progressBarRangeInfo = ProgressBarRangeInfo(clamped, 0f..1f)
                    setProgress { target ->
                        currentOnSplit(target.coerceIn(0f, 1f))
                        true
                    }
                }
                .pointerInput(widthPx) {
                    var x = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { x = currentSplit * widthPx },
                        onHorizontalDrag = { change, dx ->
                            change.consume()
                            x = (x + dx).coerceIn(0f, widthPx)
                            if (widthPx > 0f) currentOnSplit(x / widthPx)
                        },
                    )
                },
        ) {
            drawHandle(line = scheme.primary, chevron = scheme.onPrimary)
        }
    }
}

private fun DrawScope.drawHandle(line: Color, chevron: Color) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    drawLine(line, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = HANDLE_LINE_WIDTH.toPx())
    drawCircle(line, radius = HANDLE_GRIP_DIAMETER.toPx() / 2f, center = Offset(cx, cy))
    val half = CHEVRON_HALF_HEIGHT.toPx()
    val length = CHEVRON_LENGTH.toPx()
    val gap = CHEVRON_GAP.toPx()
    val left = Path().apply {
        moveTo(cx - gap - length, cy)
        lineTo(cx - gap, cy - half)
        lineTo(cx - gap, cy + half)
        close()
    }
    val right = Path().apply {
        moveTo(cx + gap + length, cy)
        lineTo(cx + gap, cy - half)
        lineTo(cx + gap, cy + half)
        close()
    }
    drawPath(left, chevron)
    drawPath(right, chevron)
}

/** A "Before"/"After" corner label: labelSmall on a 50% scrim chip. */
@Composable
internal fun CompareLabel(before: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val spacing = DarkroomTheme.spacing
    Text(
        text = stringResource(if (before) R.string.canvas_compare_before else R.string.canvas_compare_after),
        style = MaterialTheme.typography.labelSmall,
        color = scheme.onSurface,
        modifier = modifier
            .background(scheme.scrim.copy(alpha = LABEL_SCRIM_ALPHA), MaterialTheme.shapes.extraSmall)
            .padding(horizontal = spacing.s, vertical = spacing.xs),
    )
}
