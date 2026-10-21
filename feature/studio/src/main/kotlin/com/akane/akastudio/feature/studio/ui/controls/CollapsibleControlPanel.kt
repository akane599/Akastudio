package com.akane.akastudio.feature.studio.ui.controls

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.akane.akastudio.feature.studio.R
import com.akane.akastudio.feature.studio.mvi.StudioIntent
import com.akane.akastudio.feature.studio.mvi.StudioState
import com.akane.akastudio.feature.studio.ui.theme.DarkroomTheme

/** Share of the available height the expanded panel takes (the panel's height before it became collapsible). */
private const val EXPANDED_HEIGHT_FRACTION = 0.4f

/** A release faster than this (dp per second) settles in the drag's direction, wherever the panel is. */
private const val FLING_DP_PER_SECOND = 400f

/** The panel's two resting heights for [available] layout height, its drag handle included. */
internal data class PanelHeights(val collapsed: Dp, val expanded: Dp)

/**
 * Expanded is the panel's original 40 % of [available]; collapsed peeks [ControlMetrics.PanelPeek] but never more
 * than expanded, so on a short (landscape) window both match and the canvas is never smaller than it used to be.
 */
internal fun panelHeights(available: Dp): PanelHeights {
    val expanded = available * EXPANDED_HEIGHT_FRACTION
    return PanelHeights(collapsed = min(ControlMetrics.PanelPeek, expanded), expanded = expanded)
}

/**
 * Where a drag released at [height] settles: a fling settles in its direction ([velocityDpPerSecond] is the
 * downward speed, so an upward fling is negative), otherwise the nearer resting height wins, ties expanding.
 */
internal fun settlesExpanded(height: Dp, heights: PanelHeights, velocityDpPerSecond: Float): Boolean = when {
    velocityDpPerSecond <= -FLING_DP_PER_SECOND -> true
    velocityDpPerSecond >= FLING_DP_PER_SECOND -> false
    else -> height >= heights.collapsed + (heights.expanded - heights.collapsed) / 2
}

/**
 * The bottom [ControlPanel] under a drag handle. Collapsed it peeks [PanelHeights.collapsed]; tapping the handle
 * toggles it, dragging the handle resizes it live and the release settles per [settlesExpanded].
 *
 * The panel sits in the screen's layout flow, below the canvas, so expanding it shrinks the canvas and never draws
 * over it (nor over the canvas's A/B handle). Its state is UI-only and hoisted by the caller.
 *
 * @param availableHeight The layout height the panel's resting heights derive from (see [panelHeights]).
 */
@Composable
internal fun CollapsibleControlPanel(
    state: StudioState,
    onIntent: (StudioIntent) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    availableHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val heights = panelHeights(availableHeight)
    val target = if (expanded) heights.expanded else heights.collapsed
    val motion = DarkroomTheme.motion
    val height = remember { Animatable(target, Dp.VectorConverter) }
    // Non-null while a drag is in progress: the live height under the finger.
    var dragHeight by remember { mutableStateOf<Dp?>(null) }
    LaunchedEffect(target) { height.animateTo(target, motion.standard()) }
    val density = LocalDensity.current
    val dragState = rememberDraggableState { deltaPx ->
        val from = dragHeight ?: height.value
        // Dragging up (negative delta) grows the panel.
        dragHeight = (from - with(density) { deltaPx.toDp() }).coerceIn(heights.collapsed, heights.expanded)
    }
    Column(modifier.fillMaxWidth()) {
        PanelHandle(
            expanded = expanded,
            onToggle = { onExpandedChange(!expanded) },
            modifier = Modifier.draggable(
                state = dragState,
                orientation = Orientation.Vertical,
                onDragStopped = { velocityPx ->
                    val released = dragHeight ?: return@draggable
                    val next = settlesExpanded(released, heights, with(density) { velocityPx.toDp().value })
                    height.snapTo(released)
                    dragHeight = null
                    if (next == expanded) height.animateTo(target, motion.standard()) else onExpandedChange(next)
                },
            ),
        )
        ControlPanel(
            state = state,
            onIntent = onIntent,
            modifier = Modifier.height(((dragHeight ?: height.value) - ControlMetrics.TouchTarget).coerceAtLeast(0.dp)),
        )
    }
}

/** The full-width drag handle: a pill centred in a touch-target-high strip that also toggles on tap. */
@Composable
private fun PanelHandle(expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val description = stringResource(R.string.controls_panel_handle)
    val stateText = stringResource(if (expanded) R.string.controls_panel_expanded else R.string.controls_panel_collapsed)
    val actionLabel = stringResource(if (expanded) R.string.controls_panel_collapse else R.string.controls_panel_expand)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .height(ControlMetrics.TouchTarget)
            .background(scheme.surfaceContainerLow)
            .clickable(role = Role.Button, onClickLabel = actionLabel, onClick = onToggle)
            .semantics {
                contentDescription = description
                stateDescription = stateText
            },
    ) {
        Box(
            Modifier
                .size(ControlMetrics.HandleWidth, ControlMetrics.HandleThickness)
                .background(scheme.onSurfaceVariant, CircleShape),
        )
    }
}
