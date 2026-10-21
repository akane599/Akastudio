package com.akane.akastudio.feature.studio.ui.controls

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.akane.akastudio.core.curator.api.CurationOption
import com.akane.akastudio.core.curator.api.CurationResult
import com.akane.akastudio.core.curator.api.PhotoAnalysis
import com.akane.akastudio.core.curator.api.RejectReason
import com.akane.akastudio.core.curator.api.StoryMode
import com.akane.akastudio.core.curator.api.SynergyBreakdown
import com.akane.akastudio.feature.studio.R
import com.akane.akastudio.feature.studio.mvi.StudioIntent
import com.akane.akastudio.feature.studio.mvi.StudioPhase
import com.akane.akastudio.feature.studio.mvi.StudioState
import com.akane.akastudio.feature.studio.ui.note.CuratorNoteFormatter
import com.akane.akastudio.feature.studio.ui.note.ResourceNoteStrings
import com.akane.akastudio.feature.studio.ui.theme.DarkroomTextStyles
import com.akane.akastudio.feature.studio.ui.theme.DarkroomTheme

/**
 * The top-anchored story-mode drawer, shown while `state.modeDrawerOpen` and a curation result exist.
 *
 * Lists every feasible mode from `CurationResult.alternatives` (Hero, 2, 3, 4) with palette-coloured photo
 * stand-ins, the score and, for collages, the colour/rhythm/context synergy. The curator's own pick carries an
 * "AI" tag; tapping a row sends `ForceMode`. Below, the rejected photos with their reasons and the curator note.
 */
@Composable
fun ModeDrawer(state: StudioState, onIntent: (StudioIntent) -> Unit, modifier: Modifier = Modifier) {
    val motion = DarkroomTheme.motion
    val curation = state.curation
    AnimatedVisibility(
        visible = state.modeDrawerOpen && curation != null,
        modifier = modifier,
        enter = expandVertically(animationSpec = motion.standard(), expandFrom = Alignment.Top) +
            fadeIn(animationSpec = motion.standard()),
        exit = shrinkVertically(animationSpec = motion.standard(), shrinkTowards = Alignment.Top) +
            fadeOut(animationSpec = motion.quick()),
    ) {
        if (curation != null) {
            DrawerContent(
                curation = curation,
                selectedMode = state.selection.mode ?: curation.chosen.mode,
                enabled = state.phase == StudioPhase.Ready,
                onIntent = onIntent,
            )
        }
    }
}

@Composable
private fun DrawerContent(
    curation: CurationResult,
    selectedMode: StoryMode,
    enabled: Boolean,
    onIntent: (StudioIntent) -> Unit,
) {
    val spacing = DarkroomTheme.spacing
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.extraLarge.copy(topStart = CornerSize(0), topEnd = CornerSize(0))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(scheme.surfaceContainer)
            .verticalScroll(rememberScrollState())
            .padding(vertical = spacing.m),
        verticalArrangement = Arrangement.spacedBy(spacing.s),
    ) {
        Text(
            text = stringResource(R.string.controls_drawer_title),
            style = MaterialTheme.typography.titleMedium,
            color = scheme.onSurface,
            modifier = Modifier
                .padding(horizontal = spacing.l)
                .semantics { heading() },
        )
        Column(Modifier.selectableGroup()) {
            StoryMode.entries.forEach { mode ->
                val option = curation.alternatives[mode] ?: return@forEach
                ModeRow(
                    option = option,
                    analyses = curation.analyses,
                    selected = mode == selectedMode,
                    aiPick = mode == curation.note.chosenMode,
                    enabled = enabled,
                    onClick = { onIntent(StudioIntent.ForceMode(mode)) },
                )
            }
        }
        if (curation.rejectedIds.isNotEmpty()) {
            RejectedSection(curation)
        }
        CuratorNoteText(curation)
    }
}

@Composable
private fun ModeRow(
    option: CurationOption,
    analyses: Map<String, PhotoAnalysis>,
    selected: Boolean,
    aiPick: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val spacing = DarkroomTheme.spacing
    val scheme = MaterialTheme.colorScheme
    val modeName = stringResource(option.mode.nameRes())
    val score = percentText(option.score)
    val description = stringResource(R.string.controls_mode_row_description, modeName, score)
    val thumbGap = spacing.xs / 2
    val maxPhotos = StoryMode.entries.maxOf { it.photoCount }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ControlMetrics.TouchTarget)
            .background(if (selected) scheme.surfaceContainerHighest else scheme.surfaceContainer)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = spacing.l, vertical = spacing.s),
        verticalArrangement = Arrangement.spacedBy(spacing.s),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.m),
        ) {
            // A fixed-width strip so every row's title starts at the same x.
            Row(
                modifier = Modifier.width(ControlMetrics.MiniThumbWidth * maxPhotos + thumbGap * (maxPhotos - 1)),
                horizontalArrangement = Arrangement.spacedBy(thumbGap),
            ) {
                option.photoIds.forEach { id ->
                    SwatchThumb(analyses[id], ControlMetrics.MiniThumbWidth, ControlMetrics.MiniThumbHeight)
                }
            }
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.s),
            ) {
                Text(
                    text = modeName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (aiPick) TagChip(stringResource(R.string.controls_tag_ai), emphasized = selected)
            }
            ValueReadout(score)
        }
        option.synergy?.let { SynergyMeters(it) }
    }
}

@Composable
private fun SynergyMeters(synergy: SynergyBreakdown) {
    val spacing = DarkroomTheme.spacing
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
        Meter(stringResource(R.string.controls_synergy_color), synergy.color, Modifier.weight(1f))
        Meter(stringResource(R.string.controls_synergy_rhythm), synergy.rhythm, Modifier.weight(1f))
        Meter(stringResource(R.string.controls_synergy_context), synergy.context, Modifier.weight(1f))
    }
}

@Composable
private fun Meter(label: String, value: Float, modifier: Modifier = Modifier) {
    val spacing = DarkroomTheme.spacing
    val percent = percentText(value)
    Column(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = "$label $percent" },
        verticalArrangement = Arrangement.spacedBy(spacing.xs / 2),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = percent,
                style = DarkroomTextStyles.tabular,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        MeterBar(value, Modifier.fillMaxWidth())
    }
}

@Composable
private fun RejectedSection(curation: CurationResult) {
    val spacing = DarkroomTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.s)) {
        SectionLabel(
            text = stringResource(R.string.controls_rejected_title, curation.rejectedIds.size),
            modifier = Modifier
                .padding(horizontal = spacing.l, vertical = spacing.xs)
                .semantics { heading() },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = spacing.l),
            horizontalArrangement = Arrangement.spacedBy(spacing.m),
        ) {
            curation.rejectedIds.forEach { id ->
                val analysis = curation.analyses[id]
                RejectedTile(analysis, analysis?.rejectReason ?: RejectReason.UNREADABLE)
            }
        }
    }
}

@Composable
private fun RejectedTile(analysis: PhotoAnalysis?, reason: RejectReason) {
    val spacing = DarkroomTheme.spacing
    val reasonText = stringResource(reason.labelRes())
    Column(
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = reasonText },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        SwatchThumb(
            analysis = analysis,
            width = ControlMetrics.RejectedThumbWidth,
            height = ControlMetrics.RejectedThumbHeight,
            modifier = Modifier.alpha(REJECTED_ALPHA),
        )
        TagChip(reasonText, Modifier.clearAndSetSemantics { })
    }
}

@Composable
private fun CuratorNoteText(curation: CurationResult) {
    val spacing = DarkroomTheme.spacing
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val sentence = remember(curation.note, configuration) {
        CuratorNoteFormatter.format(curation.note, ResourceNoteStrings(context))
    }
    Text(
        text = sentence,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = spacing.l, vertical = spacing.xs),
    )
}
