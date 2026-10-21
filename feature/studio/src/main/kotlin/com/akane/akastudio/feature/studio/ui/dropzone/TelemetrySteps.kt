package com.akane.akastudio.feature.studio.ui.dropzone

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.akane.akastudio.core.curator.api.StoryMode
import com.akane.akastudio.feature.studio.R
import com.akane.akastudio.feature.studio.mvi.TelemetryUi
import com.akane.akastudio.feature.studio.ui.theme.DarkroomTheme
import com.akane.akastudio.feature.studio.ui.theme.tabularNumbers

/** Marker rail and dot dimensions fixed by the Dropzone brief. */
private val RailWidth = 20.dp
private val DotSize = 8.dp
private val PulseDepth = 2.dp
private val CheckSize = 16.dp
private const val PULSE_MILLIS = 900
private const val MAX_PERCENT = 100
private const val NO_BREAK_SPACE = ' '

/** Horizontal offset of the step labels, so lines placed under the list (the curator note) align with them. */
@Composable
internal fun stepLabelInset(): Dp = RailWidth + DarkroomTheme.spacing.m

/**
 * The live curation pipeline as a vertical step list joined by a hairline rail. Each row is one merged
 * accessibility node carrying its status; the running row is a polite live region so progress is announced.
 */
@Composable
internal fun TelemetrySteps(telemetry: TelemetryUi, modifier: Modifier = Modifier) {
    val steps = DropzoneModel.steps(telemetry)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { isTraversalGroup = true },
    ) {
        steps.forEachIndexed { index, item ->
            StepRow(item = item, telemetry = telemetry, isLast = index == steps.lastIndex)
        }
    }
}

@Composable
private fun StepRow(item: PipelineStepUi, telemetry: TelemetryUi, isLast: Boolean) {
    val spacing = DarkroomTheme.spacing
    val scheme = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.bodyMedium.tabularNumbers()
    val lineHeight = with(LocalDensity.current) { style.lineHeight.toDp() }
    val (main, detail) = stepText(item, telemetry)
    // Pending rows are content still to come, not disabled controls, so they keep AA contrast (tertiary).
    val mainColor = if (item.status == StepStatus.PENDING) scheme.tertiary else scheme.onSurface
    val detailColor = scheme.onSurfaceVariant
    val label = buildAnnotatedString {
        withStyle(SpanStyle(color = mainColor)) { append(main) }
        if (detail != null) {
            withStyle(SpanStyle(color = detailColor)) {
                append(stringResource(R.string.dropzone_detail_separator))
                // The detail ("3 skipped") wraps as one unit so its count never strands at a line end.
                append(detail.replace(' ', NO_BREAK_SPACE))
            }
        }
    }
    val status = stringResource(
        when (item.status) {
            StepStatus.PENDING -> R.string.dropzone_step_pending
            StepStatus.ACTIVE -> R.string.dropzone_step_active
            StepStatus.DONE -> R.string.dropzone_step_done
        },
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .semantics(mergeDescendants = true) {
                stateDescription = status
                if (item.status == StepStatus.ACTIVE) liveRegion = LiveRegionMode.Polite
            },
    ) {
        Column(
            modifier = Modifier
                .width(RailWidth)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(modifier = Modifier.size(width = RailWidth, height = lineHeight), contentAlignment = Alignment.Center) {
                StepMarker(item.status)
            }
            if (!isLast) {
                val railColor = scheme.outline
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .drawBehind {
                            drawLine(
                                color = railColor,
                                start = Offset(center.x, 0f),
                                end = Offset(center.x, size.height),
                                strokeWidth = Stroke.HairlineWidth,
                            )
                        },
                )
            }
        }
        Spacer(Modifier.width(spacing.m))
        Text(
            text = label,
            style = style,
            modifier = Modifier.padding(bottom = if (isLast) spacing.xs else spacing.l),
        )
    }
}

@Composable
private fun StepMarker(status: StepStatus) {
    val scheme = MaterialTheme.colorScheme
    when (status) {
        StepStatus.PENDING -> Box(
            modifier = Modifier
                .size(DotSize)
                .border(Dp.Hairline, scheme.tertiary, CircleShape),
        )
        StepStatus.ACTIVE ->
            if (DarkroomTheme.motion.reducedMotion) StillDot(scheme.primary) else PulsingDot(scheme.primary)
        StepStatus.DONE -> Icon(
            imageVector = DropzoneIcons.Check,
            contentDescription = null,
            tint = scheme.onSurface,
            modifier = Modifier.size(CheckSize),
        )
    }
}

@Composable
private fun StillDot(color: Color) {
    Box(
        modifier = Modifier
            .size(DotSize)
            .background(color, CircleShape),
    )
}

/** A dot whose diameter breathes by [PulseDepth]; the pulse is read only while drawing, so it never relayouts. */
@Composable
private fun PulsingDot(color: Color) {
    val pulse: State<Float> = rememberInfiniteTransition(label = "step-pulse").animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = PULSE_MILLIS, easing = DarkroomTheme.motion.easing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "step-pulse-depth",
    )
    Canvas(modifier = Modifier.size(DotSize)) {
        val shrink = PulseDepth.toPx() * (1f - pulse.value)
        drawCircle(color = color, radius = (size.minDimension - shrink) / 2f)
    }
}

/** The main label and the optional dimmed detail of one step. Counters animate toward their new value. */
@Composable
private fun stepText(item: PipelineStepUi, telemetry: TelemetryUi): Pair<String, String?> = when (item.step) {
    PipelineStep.READ -> {
        val read by animatedCount(telemetry.photosRead)
        val skipped by animatedCount(telemetry.skipped)
        pluralStringResource(R.plurals.dropzone_photos_read, read, read) to
            skipped.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.dropzone_skipped, it, it) }
    }
    PipelineStep.CLUSTER -> {
        val clusters by animatedCount(telemetry.clusters)
        val analyzed by animatedCount(telemetry.analyzed)
        when (item.status) {
            StepStatus.DONE -> pluralStringResource(R.plurals.dropzone_bursts_clustered, clusters, clusters) to null
            StepStatus.ACTIVE -> stringResource(R.string.dropzone_step_cluster) to
                telemetry.analyzeTotal.takeIf { it > 0 }?.let {
                    stringResource(R.string.dropzone_analyzed, analyzed, it)
                }
            StepStatus.PENDING -> stringResource(R.string.dropzone_step_cluster) to null
        }
    }
    PipelineStep.FOCUS -> {
        val rejected by animatedCount(telemetry.rejected)
        if (item.status == StepStatus.DONE) {
            stringResource(R.string.dropzone_step_focus_done) to
                pluralStringResource(R.plurals.dropzone_rejected, rejected, rejected)
        } else {
            stringResource(R.string.dropzone_step_focus) to null
        }
    }
    PipelineStep.SYNERGY -> synergyText(item.status, telemetry) to null
}

@Composable
private fun synergyText(status: StepStatus, telemetry: TelemetryUi): String {
    val mode = telemetry.finalMode
    val harmony = telemetry.harmonyPercent
    if (status != StepStatus.DONE || mode == null || harmony == null) return stringResource(R.string.dropzone_step_synergy)
    val percent = stringResource(R.string.note_percent, harmony.coerceIn(0, MAX_PERCENT))
    return if (mode == StoryMode.HERO) {
        stringResource(R.string.dropzone_synergy_hero, percent)
    } else {
        stringResource(R.string.dropzone_synergy_collage, mode.photoCount, percent)
    }
}

@Composable
private fun animatedCount(value: Int): State<Int> =
    animateIntAsState(targetValue = value, animationSpec = DarkroomTheme.motion.standard(), label = "telemetry-count")
