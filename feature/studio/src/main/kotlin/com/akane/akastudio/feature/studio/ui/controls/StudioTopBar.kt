package com.akane.akastudio.feature.studio.ui.controls

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.akane.akastudio.feature.studio.R
import com.akane.akastudio.feature.studio.mvi.ScopeMode
import com.akane.akastudio.feature.studio.mvi.StudioPhase
import com.akane.akastudio.feature.studio.mvi.StudioIntent
import com.akane.akastudio.feature.studio.mvi.StudioState
import com.akane.akastudio.feature.studio.ui.theme.DarkroomTheme

/** Split position the compare toggle starts at. */
private const val COMPARE_START_SPLIT = 0.5f
private const val CHEVRON_OPEN_DEGREES = 180f

/**
 * The studio top bar: the story-mode chip on the left (toggles the mode drawer) and the A/B compare, scope and
 * export tools on the right. Tool state is shown by fill and luminance only.
 *
 * @param onOpenExport Opens the export sheet; the screen owns the sheet's visibility.
 */
@Composable
fun StudioTopBar(
    state: StudioState,
    onIntent: (StudioIntent) -> Unit,
    onOpenExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = DarkroomTheme.spacing
    val hasStory = state.phase == StudioPhase.Ready || state.phase == StudioPhase.Exporting
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = spacing.xs, vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Box(Modifier.weight(1f).padding(end = spacing.xs), contentAlignment = Alignment.CenterStart) {
            ModeChip(state = state, onClick = { onIntent(StudioIntent.ToggleModeDrawer) })
        }
        ToolToggle(
            icon = ControlIcons.Compare,
            description = stringResource(R.string.controls_compare),
            checked = state.compare != null,
            onCheckedChange = { on -> onIntent(StudioIntent.SetCompare(if (on) COMPARE_START_SPLIT else null)) },
            enabled = hasStory,
        )
        ScopeButton(
            mode = state.scope,
            onClick = { onIntent(StudioIntent.SetScope(state.scope.next())) },
            enabled = hasStory,
        )
        ToolButton(
            icon = ControlIcons.Export,
            description = stringResource(R.string.controls_open_export),
            onClick = onOpenExport,
            enabled = hasStory,
        )
    }
}

/**
 * Distance from the top bar's end edge to the centre of its compare tool, which is followed by the scope and export
 * tools (48 dp each) inside the bar's [DarkroomSpacing.xs][com.akane.akastudio.feature.studio.ui.theme.DarkroomSpacing]
 * end padding. Keep in step with [StudioTopBar]'s trailing tools.
 */
@Composable
internal fun compareToolCenterFromEnd(): Dp =
    DarkroomTheme.spacing.xs + ControlMetrics.TouchTarget * TOOLS_AFTER_COMPARE + ControlMetrics.TouchTarget / 2

/** Scope and export follow compare at the bar's end. */
private const val TOOLS_AFTER_COMPARE = 2

@Composable
private fun ModeChip(state: StudioState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val spacing = DarkroomTheme.spacing
    val motion = DarkroomTheme.motion
    val curation = state.curation
    val modeName = state.activeMode?.let { stringResource(it.nameRes()) }
    // Provenance comes from the curator's original recommendation, not from the latest (possibly forced) result.
    val aiPick = state.isAiPick
    val label = when {
        curation == null || modeName == null -> stringResource(R.string.controls_mode_pending)
        aiPick -> stringResource(R.string.controls_mode_ai_pick, modeName)
        else -> stringResource(R.string.controls_mode_user_pick, modeName)
    }
    // At large font the "AI pick:" / "Your pick:" prefix would push the mode name into an ellipsis, so the chip
    // shows the bare name (wrapping if it must) and the AI tag; screen readers still hear the full label.
    val largeFont = isLargeFont()
    val shown = if (largeFont && curation != null && modeName != null) modeName else label
    val open = state.modeDrawerOpen
    val enabled = curation != null
    val container by animateColorAsState(
        targetValue = if (open) scheme.surfaceContainerHighest else scheme.surfaceContainerHigh,
        animationSpec = motion.quick(),
        label = "modeChipFill",
    )
    val chevronRotation by animateFloatAsState(
        targetValue = if (open) CHEVRON_OPEN_DEGREES else 0f,
        animationSpec = motion.standard(),
        label = "modeChipChevron",
    )
    val stateText = stringResource(
        if (open) R.string.controls_mode_drawer_expanded else R.string.controls_mode_drawer_collapsed,
    )
    Row(
        modifier = modifier
            .heightIn(min = ControlMetrics.TouchTarget)
            .clip(MaterialTheme.shapes.large)
            .background(container)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = label
                stateDescription = stateText
            }
            .padding(start = spacing.m, end = spacing.s, top = spacing.xs, bottom = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        // Leading, so a name that wraps leaves its slack before the chevron rather than between name and tag.
        if (largeFont && aiPick) {
            TagChip(stringResource(R.string.controls_tag_ai), Modifier.clearAndSetSemantics { }, emphasized = true)
        }
        Text(
            text = shown,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) scheme.onSurface else scheme.onSurfaceVariant,
            maxLines = if (largeFont) 2 else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = false)
                .clearAndSetSemantics { },
        )
        Icon(
            imageVector = ControlIcons.ChevronDown,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier
                .size(ControlMetrics.IconSize)
                .rotate(chevronRotation),
        )
    }
}

@Composable
private fun ToolToggle(
    icon: ImageVector,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
) {
    ToolSurface(
        icon = icon,
        active = checked,
        enabled = enabled,
        modifier = Modifier
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .semantics { contentDescription = description },
    )
}

@Composable
private fun ScopeButton(mode: ScopeMode, onClick: () -> Unit, enabled: Boolean) {
    val modeName = stringResource(
        when (mode) {
            ScopeMode.OFF -> R.string.controls_scope_off
            ScopeMode.LUMA_WAVEFORM -> R.string.controls_scope_luma
            ScopeMode.RGB_PARADE -> R.string.controls_scope_rgb_parade
            ScopeMode.HISTOGRAM -> R.string.controls_scope_histogram
        },
    )
    val description = stringResource(R.string.controls_scope, modeName)
    ToolSurface(
        icon = ControlIcons.scope(mode),
        active = mode != ScopeMode.OFF,
        enabled = enabled,
        modifier = Modifier
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                stateDescription = modeName
            },
    )
}

@Composable
private fun ToolButton(icon: ImageVector, description: String, onClick: () -> Unit, enabled: Boolean) {
    ToolSurface(
        icon = icon,
        active = false,
        enabled = enabled,
        modifier = Modifier
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
    )
}

/** A 48 dp circular tool: active tools are filled and drawn in full-luminance ink. */
@Composable
private fun ToolSurface(icon: ImageVector, active: Boolean, enabled: Boolean, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val motion = DarkroomTheme.motion
    val fill by animateColorAsState(
        targetValue = if (active) scheme.surfaceContainerHighest else scheme.background,
        animationSpec = motion.quick(),
        label = "toolFill",
    )
    val ink = when {
        !enabled -> scheme.onSurfaceVariant.copy(alpha = DISABLED_ALPHA)
        active -> scheme.onSurface
        else -> scheme.onSurfaceVariant
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .widthIn(min = ControlMetrics.TouchTarget)
            .heightIn(min = ControlMetrics.TouchTarget)
            .clip(CircleShape)
            .background(fill)
            .then(modifier),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = ink, modifier = Modifier.size(ControlMetrics.IconSize))
    }
}
