package com.akane.akastudio.feature.studio.ui.controls

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.akane.akastudio.feature.studio.R
import com.akane.akastudio.feature.studio.mvi.ControlTab
import com.akane.akastudio.feature.studio.mvi.StudioIntent
import com.akane.akastudio.feature.studio.mvi.StudioState
import com.akane.akastudio.feature.studio.ui.theme.DarkroomTheme

/**
 * The studio's bottom control panel: four tabs (curation, layout, colour, export) switched with `SelectTab`.
 * Fills the height it is given; the active tab's content scrolls inside it.
 *
 * The only local state is which story slot the curation tab is showing burst alternatives for.
 *
 * @param sdkInt The platform level used to decide export availability (Ultra HDR needs API 34).
 */
@Composable
fun ControlPanel(
    state: StudioState,
    onIntent: (StudioIntent) -> Unit,
    modifier: Modifier = Modifier,
    sdkInt: Int = Build.VERSION.SDK_INT,
) {
    var selectedSlot by rememberSaveable { mutableIntStateOf(0) }
    val slotCount = state.selection.photoIds.size
    val slot = if (slotCount == 0) 0 else selectedSlot.coerceIn(0, slotCount - 1)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        ControlTabRow(selected = state.tab, onSelect = { onIntent(StudioIntent.SelectTab(it)) })
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = DarkroomTheme.spacing.l),
            ) {
                when (state.tab) {
                    ControlTab.CURATION -> CurationTab(state, onIntent, slot, onSelectSlot = { selectedSlot = it })
                    ControlTab.LAYOUT -> LayoutTab(state, onIntent)
                    ControlTab.COLOR -> ColorTab(state, onIntent)
                    ControlTab.EXPORT -> ExportSheet(state, onIntent, sdkInt = sdkInt)
                }
            }
        }
    }
}

@Composable
private fun ControlTabRow(selected: ControlTab, onSelect: (ControlTab) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val spacing = DarkroomTheme.spacing
    // Equal quarter-width tabs; a tab whose label needs more (large fonts, long translations) grows and the
    // row scrolls instead of truncating the label.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val tabMinWidth = maxWidth / ControlTab.entries.size
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(scheme.surfaceContainerLow)
                .horizontalScroll(rememberScrollState())
                .selectableGroup(),
        ) {
            ControlTab.entries.forEach { tab ->
                TabCell(tab, tab == selected, tabMinWidth, onSelect)
            }
        }
    }
}

@Composable
private fun TabCell(tab: ControlTab, isSelected: Boolean, minWidth: Dp, onSelect: (ControlTab) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val spacing = DarkroomTheme.spacing
    Column(
        modifier = Modifier
            .widthIn(min = minWidth)
            .width(IntrinsicSize.Max)
            .heightIn(min = ControlMetrics.TouchTarget)
            .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(tab) }),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(tab.labelRes()),
            style = MaterialTheme.typography.labelLarge,
            color = if (isSelected) scheme.onSurface else scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = spacing.s, vertical = spacing.m),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.l)
                .height(ControlMetrics.TabIndicator)
                .background(if (isSelected) scheme.primary else scheme.surfaceContainerLow),
        )
    }
}

private fun ControlTab.labelRes(): Int = when (this) {
    ControlTab.CURATION -> R.string.controls_tab_curation
    ControlTab.LAYOUT -> R.string.controls_tab_layout
    ControlTab.COLOR -> R.string.controls_tab_color
    ControlTab.EXPORT -> R.string.controls_tab_export
}
