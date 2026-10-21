package com.akane.akastudio.feature.studio.ui.controls

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.akane.akastudio.core.export.api.ExportFormat
import com.akane.akastudio.core.export.api.ExportResolution
import com.akane.akastudio.feature.studio.R
import com.akane.akastudio.feature.studio.mvi.ExportUi
import com.akane.akastudio.feature.studio.mvi.StudioIntent
import com.akane.akastudio.feature.studio.mvi.StudioPhase
import com.akane.akastudio.feature.studio.mvi.StudioState
import com.akane.akastudio.feature.studio.ui.theme.DarkroomShapeTokens
import com.akane.akastudio.feature.studio.ui.theme.DarkroomTheme

private val RESOLUTIONS: List<ExportResolution> = listOf(ExportResolution.HD_1080, ExportResolution.UHD_4K)

/**
 * Export settings and actions: format (Ultra HDR and HDR Story Boost need API 34), resolution, HDR boost,
 * [Export] and [Export & share], and the running stage with a determinate bar, then where the file was saved.
 * Settings are locked while an export runs.
 *
 * @param sdkInt The platform level used to decide availability; defaults to the device's.
 */
@Composable
fun ExportSheet(
    state: StudioState,
    onIntent: (StudioIntent) -> Unit,
    modifier: Modifier = Modifier,
    sdkInt: Int = Build.VERSION.SDK_INT,
) {
    val spacing = DarkroomTheme.spacing
    val availability = ExportOptions.availability(sdkInt)
    val export = state.export
    val exporting = state.phase == StudioPhase.Exporting
    val editable = !exporting
    val canExport = state.phase == StudioPhase.Ready && availability.isEnabled(export.format)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.l),
        verticalArrangement = Arrangement.spacedBy(spacing.m),
    ) {
        SectionLabel(stringResource(R.string.controls_format_title))
        Column(Modifier.selectableGroup()) {
            availability.formats.forEach { option ->
                FormatRow(
                    format = option.format,
                    selected = option.format == export.format,
                    available = option.enabled,
                    enabled = option.enabled && editable,
                    onSelect = { onIntent(StudioIntent.SetExportFormat(option.format)) },
                )
            }
        }
        SectionLabel(stringResource(R.string.controls_resolution_title))
        SegmentedControl(
            options = listOf(
                stringResource(R.string.controls_resolution_1080),
                stringResource(R.string.controls_resolution_4k),
            ),
            selectedIndex = RESOLUTIONS.indexOf(export.resolution),
            onSelect = { onIntent(StudioIntent.SetExportResolution(RESOLUTIONS[it])) },
            enabled = editable,
        )
        SwitchRow(
            title = stringResource(R.string.controls_hdr_boost),
            summary = stringResource(
                if (availability.hdrBoostAvailable) R.string.controls_hdr_boost_summary else R.string.controls_needs_android_14,
            ),
            checked = export.hdrBoost && availability.hdrBoostAvailable,
            onCheckedChange = { onIntent(StudioIntent.SetHdrBoost(it)) },
            enabled = availability.hdrBoostAvailable && editable,
        )
        ExportStatus(export = export, exporting = exporting)
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
            Button(
                onClick = { onIntent(StudioIntent.Export(share = false)) },
                enabled = canExport,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = ControlMetrics.TouchTarget),
            ) {
                Text(stringResource(R.string.controls_export_action), textAlign = TextAlign.Center)
            }
            OutlinedButton(
                onClick = { onIntent(StudioIntent.Export(share = true)) },
                enabled = canExport,
                border = if (canExport) {
                    BorderStroke(ControlMetrics.Hairline, boundaryColor)
                } else {
                    ButtonDefaults.outlinedButtonBorder(enabled = false)
                },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = ControlMetrics.TouchTarget),
            ) {
                Text(stringResource(R.string.controls_export_share_action), textAlign = TextAlign.Center)
            }
        }
    }
}

/**
 * [ExportSheet] in a modal bottom sheet, for the top bar's export button. The caller shows it while its own
 * visibility flag is set and clears that flag in [onDismissRequest].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportModalSheet(
    state: StudioState,
    onIntent: (StudioIntent) -> Unit,
    onDismissRequest: () -> Unit,
    sdkInt: Int = Build.VERSION.SDK_INT,
) {
    val spacing = DarkroomTheme.spacing
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = DarkroomShapeTokens.sheetTop,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = { ExportSheetDragHandle() },
    ) {
        ExportSheetBody(
            state = state,
            onIntent = onIntent,
            sdkInt = sdkInt,
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = spacing.l),
        )
    }
}

/** The export sheet's 32×4 dp Material drag handle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExportSheetDragHandle(modifier: Modifier = Modifier) {
    BottomSheetDefaults.DragHandle(modifier = modifier)
}

/** The modal export sheet's content under its drag handle: the "Export" heading over [ExportSheet]. */
@Composable
internal fun ExportSheetBody(
    state: StudioState,
    onIntent: (StudioIntent) -> Unit,
    modifier: Modifier = Modifier,
    sdkInt: Int = Build.VERSION.SDK_INT,
) {
    val spacing = DarkroomTheme.spacing
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.m),
    ) {
        Text(
            text = stringResource(R.string.controls_export_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(horizontal = spacing.l)
                .semantics { heading() },
        )
        ExportSheet(state = state, onIntent = onIntent, sdkInt = sdkInt)
    }
}

@Composable
private fun FormatRow(
    format: ExportFormat,
    selected: Boolean,
    available: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    val spacing = DarkroomTheme.spacing
    val scheme = MaterialTheme.colorScheme
    val (title, summary) = format.textRes()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ControlMetrics.TouchTarget)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(title),
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = DISABLED_ALPHA),
            )
            // A format locked by a running export dims like the other locked settings; an unavailable one keeps
            // its reason ("Needs Android 14") legible.
            Text(
                text = stringResource(if (available) summary else R.string.controls_needs_android_14),
                style = MaterialTheme.typography.labelSmall,
                color = if (available && !enabled) {
                    scheme.onSurfaceVariant.copy(alpha = DISABLED_ALPHA)
                } else {
                    scheme.onSurfaceVariant
                },
            )
        }
    }
}

/** The running stage and a determinate bar while exporting, then the save location after a finished export. */
@Composable
private fun ExportStatus(export: ExportUi, exporting: Boolean) {
    val spacing = DarkroomTheme.spacing
    val scheme = MaterialTheme.colorScheme
    val progress = export.progress
    when {
        exporting -> Column(
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(spacing.s),
        ) {
            val fraction = progress?.fraction ?: 0f
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(progress?.stage?.labelRes() ?: R.string.controls_export_starting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                ValueReadout(percentText(fraction))
            }
            MeterBar(fraction, Modifier.fillMaxWidth(), progressSemantics = true)
        }
        export.lastResult != null -> Text(
            text = stringResource(R.string.controls_export_saved),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

private fun ExportFormat.textRes(): Pair<Int, Int> = when (this) {
    ExportFormat.PNG_16 -> R.string.controls_format_png16 to R.string.controls_format_png16_summary
    ExportFormat.PNG_8 -> R.string.controls_format_png8 to R.string.controls_format_png8_summary
    ExportFormat.WEBP_LOSSLESS -> R.string.controls_format_webp to R.string.controls_format_webp_summary
    ExportFormat.SOCIAL_STORY_JPEG -> R.string.controls_format_social to R.string.controls_format_social_summary
    ExportFormat.ULTRA_HDR_JPEG -> R.string.controls_format_ultra_hdr to R.string.controls_format_ultra_hdr_summary
}
