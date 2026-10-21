package com.akane.akastudio.feature.studio.ui.controls

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import com.akane.akastudio.feature.studio.R
import com.akane.akastudio.feature.studio.ui.theme.DarkroomTheme

/**
 * A labelled darkroom slider: label and tabular readout above a thin track.
 *
 * A [bipolar] slider draws its active segment from the centred zero point, with a tick at zero; a unipolar
 * slider fills from the start. Double-tapping the slider (or the accessibility "Reset to default" action)
 * sends [resetValue]. Every change, including each drag step, goes to [onValueChange].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ControlSlider(
    label: String,
    readout: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    bipolar: Boolean,
    resetValue: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val spacing = DarkroomTheme.spacing
    val scheme = MaterialTheme.colorScheme
    val resetLabel = stringResource(R.string.controls_reset)
    val reset = { onValueChange(resetValue) }
    val latestReset = rememberUpdatedState(reset)
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clearAndSetSemantics { },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) scheme.onSurface else scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ValueReadout(readout)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .doubleTapToReset(enabled) { latestReset.value() }
                .semantics {
                    contentDescription = label
                    stateDescription = readout
                    customActions = listOf(CustomAccessibilityAction(resetLabel) { reset(); true })
                },
            thumb = {
                Box(
                    Modifier
                        .size(ControlMetrics.ThumbSize)
                        .background(
                            if (enabled) DarkroomTheme.colors.sliderActive else scheme.onSurfaceVariant,
                            CircleShape,
                        ),
                )
            },
            track = {
                SliderTrack(
                    fraction = fractionOf(value, range),
                    zeroFraction = if (bipolar) fractionOf(0f, range) else 0f,
                    bipolar = bipolar,
                    enabled = enabled,
                )
            },
        )
    }
}

/** Position of [value] along [range] as a 0..1 fraction; a degenerate range maps to 0. */
internal fun fractionOf(value: Float, range: ClosedFloatingPointRange<Float>): Float {
    val span = range.endInclusive - range.start
    if (span <= 0f || !value.isFinite()) return 0f
    return ((value - range.start) / span).coerceIn(0f, 1f)
}

/**
 * Horizontal position, in a track [width] px wide, of a 0..1 [fraction] of the range. The native [Slider] puts
 * the range start on the right under [LayoutDirection.Rtl], so the drawn track mirrors with it to stay on the
 * thumb's side; under LTR this is exactly `width * fraction`.
 */
internal fun trackX(width: Float, fraction: Float, layoutDirection: LayoutDirection): Float =
    width * if (layoutDirection == LayoutDirection.Rtl) 1f - fraction else fraction

@Composable
private fun SliderTrack(fraction: Float, zeroFraction: Float, bipolar: Boolean, enabled: Boolean) {
    val colors = DarkroomTheme.colors
    val scheme = MaterialTheme.colorScheme
    val active = if (enabled) colors.sliderActive else scheme.onSurfaceVariant
    // Disabled, the active part falls to onSurfaceVariant, so the boundary dims too to stay distinct from it.
    val inactive = if (enabled) boundaryColor else boundaryColor.copy(alpha = DISABLED_ALPHA)
    val tickColor = scheme.onSurfaceVariant
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(ControlMetrics.CenterTickHeight),
    ) {
        val y = size.height / 2f
        val stroke = ControlMetrics.TrackHeight.toPx()
        drawLine(inactive, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
        // Both ends and the zero tick follow the layout direction, as the native Slider places its thumb.
        val from = trackX(size.width, zeroFraction, layoutDirection)
        val to = trackX(size.width, fraction, layoutDirection)
        if (from != to) {
            drawLine(active, Offset(from, y), Offset(to, y), strokeWidth = stroke)
        }
        if (bipolar) {
            drawLine(tickColor, Offset(from, 0f), Offset(from, size.height), strokeWidth = stroke)
        }
    }
}

/**
 * Watches taps in the Initial pass and calls [onDoubleTap] when two short taps land within the platform
 * double-tap timeout and touch slop. Only the second tap's up is consumed: the Slider's own tap-to-jump
 * (`detectTapGestures` in the Main pass) then sees a consumed up and skips its `onTap`, which would otherwise
 * overwrite the reset with the tapped position. Drags and single taps pass through untouched.
 * The gesture loop is keyed on nothing, so [onDoubleTap] must read its target through a stable state.
 */
private fun Modifier.doubleTapToReset(enabled: Boolean, onDoubleTap: () -> Unit): Modifier = if (!enabled) {
    this
} else {
    this.pointerInput(Unit) {
        var lastUpMillis = Long.MIN_VALUE
        var lastUpPosition = Offset.Zero
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val up = waitForUpOrCancellation(pass = PointerEventPass.Initial) ?: return@awaitEachGesture
            val slop = viewConfiguration.touchSlop
            val isTap = (up.position - down.position).getDistance() <= slop &&
                up.uptimeMillis - down.uptimeMillis <= viewConfiguration.longPressTimeoutMillis
            if (!isTap) {
                lastUpMillis = Long.MIN_VALUE
                return@awaitEachGesture
            }
            val sinceLast = down.uptimeMillis - lastUpMillis
            val nearLast = (down.position - lastUpPosition).getDistance() <= slop * 2
            if (lastUpMillis != Long.MIN_VALUE && sinceLast <= viewConfiguration.doubleTapTimeoutMillis && nearLast) {
                lastUpMillis = Long.MIN_VALUE
                up.consume()
                onDoubleTap()
            } else {
                lastUpMillis = up.uptimeMillis
                lastUpPosition = up.position
            }
        }
    }
}
