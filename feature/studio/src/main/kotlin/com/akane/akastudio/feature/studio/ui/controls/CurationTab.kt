package com.akane.akastudio.feature.studio.ui.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.akane.akastudio.core.curator.api.CurationResult
import com.akane.akastudio.core.curator.api.PhotoAnalysis
import com.akane.akastudio.feature.studio.R
import com.akane.akastudio.feature.studio.mvi.StudioIntent
import com.akane.akastudio.feature.studio.mvi.StudioPhase
import com.akane.akastudio.feature.studio.mvi.StudioState
import com.akane.akastudio.feature.studio.ui.theme.DarkroomTheme

/**
 * Curation & candidates: the story photos in slot order (palette, shot scale, quality) and, for [selectedSlot],
 * the burst alternatives that `SwapCandidate` can put in its place.
 */
@Composable
internal fun CurationTab(
    state: StudioState,
    onIntent: (StudioIntent) -> Unit,
    selectedSlot: Int,
    onSelectSlot: (Int) -> Unit,
) {
    val spacing = DarkroomTheme.spacing
    val curation = state.curation
    val photoIds = state.selection.photoIds
    if (curation == null || photoIds.isEmpty()) {
        Text(
            text = stringResource(R.string.controls_curation_waiting),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = spacing.l),
        )
        return
    }
    val canSwap = state.phase == StudioPhase.Ready
    val alternatives = remember(curation, photoIds, selectedSlot) {
        BurstAlternatives.build(curation, photoIds, selectedSlot)
    }
    Column(verticalArrangement = Arrangement.spacedBy(spacing.m)) {
        SectionLabel(stringResource(R.string.controls_slots_title), Modifier.padding(horizontal = spacing.l))
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            contentPadding = PaddingValues(horizontal = spacing.l),
            horizontalArrangement = Arrangement.spacedBy(spacing.m),
        ) {
            items(photoIds.size, key = { photoIds[it] }) { index ->
                SlotCard(
                    slot = index,
                    analysis = curation.analyses[photoIds[index]],
                    selected = index == selectedSlot,
                    onClick = { onSelectSlot(index) },
                )
            }
        }
        SectionLabel(
            text = stringResource(R.string.controls_burst_title, selectedSlot + 1),
            modifier = Modifier.padding(start = spacing.l, end = spacing.l, top = spacing.s),
        )
        BurstRow(
            curation = curation,
            alternatives = alternatives,
            slot = selectedSlot,
            enabled = canSwap,
            onSwap = { id -> onIntent(StudioIntent.SwapCandidate(selectedSlot, id)) },
        )
    }
}

@Composable
private fun SlotCard(slot: Int, analysis: PhotoAnalysis?, selected: Boolean, onClick: () -> Unit) {
    val spacing = DarkroomTheme.spacing
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    val scale = analysis?.let { stringResource(it.shotScale.nameRes()) }
    val quality = analysis?.let { percentText(it.quality) }
    val description = stringResource(R.string.controls_slot_description, slot + 1, scale.orEmpty(), quality.orEmpty())
    Column(
        modifier = Modifier
            .width(ControlMetrics.SlotThumbWidth + spacing.s * 2)
            .clip(shape)
            .background(if (selected) scheme.surfaceContainerHigh else scheme.surfaceContainer)
            .border(
                width = if (selected) ControlMetrics.SelectedOutline else ControlMetrics.Hairline,
                color = if (selected) scheme.primary else scheme.outlineVariant,
                shape = shape,
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(spacing.s),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Box {
            SwatchThumb(analysis, ControlMetrics.SlotThumbWidth, ControlMetrics.SlotThumbHeight)
            TagChip(
                text = (slot + 1).toString(),
                emphasized = selected,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(spacing.xs),
            )
        }
        analysis?.let { PaletteStrip(it.palette, Modifier.fillMaxWidth()) }
        if (scale != null) TagChip(scale)
        if (quality != null) {
            ValueReadout(stringResource(R.string.controls_quality, quality), Modifier.clearAndSetSemantics { })
        }
    }
}

@Composable
private fun BurstRow(
    curation: CurationResult,
    alternatives: List<BurstOption>,
    slot: Int,
    enabled: Boolean,
    onSwap: (String) -> Unit,
) {
    val spacing = DarkroomTheme.spacing
    if (alternatives.isEmpty()) {
        Text(
            text = stringResource(R.string.controls_burst_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = spacing.l),
        )
        return
    }
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = spacing.l),
        horizontalArrangement = Arrangement.spacedBy(spacing.s),
    ) {
        items(alternatives, key = { it.id }) { option ->
            BurstTile(
                option = option,
                analysis = curation.analyses[option.id],
                slot = slot,
                enabled = enabled,
                onClick = { onSwap(option.id) },
            )
        }
    }
}

@Composable
private fun BurstTile(option: BurstOption, analysis: PhotoAnalysis?, slot: Int, enabled: Boolean, onClick: () -> Unit) {
    val spacing = DarkroomTheme.spacing
    val scheme = MaterialTheme.colorScheme
    val scene = stringResource(
        if (option.sameScene) R.string.controls_burst_same_scene else R.string.controls_burst_other_scene,
    )
    val quality = analysis?.let { percentText(it.quality) }.orEmpty()
    val description = stringResource(R.string.controls_burst_description, slot + 1, scene, quality)
    Column(
        modifier = Modifier
            .width(ControlMetrics.BurstThumbWidth + spacing.s * 2)
            .clip(MaterialTheme.shapes.medium)
            .background(scheme.surfaceContainer)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(spacing.s),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        SwatchThumb(analysis, ControlMetrics.BurstThumbWidth, ControlMetrics.BurstThumbHeight)
        ValueReadout(stringResource(R.string.controls_quality, quality), Modifier.clearAndSetSemantics { })
        Text(
            text = scene,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}
