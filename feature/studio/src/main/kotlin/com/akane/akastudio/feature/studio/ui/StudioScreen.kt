package com.akane.akastudio.feature.studio.ui

import android.content.Context
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import com.akane.akastudio.core.color.api.StoryRenderer
import com.akane.akastudio.core.export.api.ExportFormat
import com.akane.akastudio.core.ingestion.model.IngestionError
import com.akane.akastudio.feature.studio.R
import com.akane.akastudio.feature.studio.domain.StudioError
import com.akane.akastudio.feature.studio.mvi.StudioIntent
import com.akane.akastudio.feature.studio.mvi.StudioMessage
import com.akane.akastudio.feature.studio.mvi.StudioPhase
import com.akane.akastudio.feature.studio.mvi.StudioState
import com.akane.akastudio.feature.studio.ui.canvas.StoryCanvas
import com.akane.akastudio.feature.studio.ui.controls.ControlPanel
import com.akane.akastudio.feature.studio.ui.controls.ExportModalSheet
import com.akane.akastudio.feature.studio.ui.controls.ModeDrawer
import com.akane.akastudio.feature.studio.ui.controls.StudioTopBar
import com.akane.akastudio.feature.studio.ui.dropzone.DropzoneScreen
import com.akane.akastudio.feature.studio.ui.theme.DarkroomTheme

/** Share of the screen height the bottom control panel takes in the workspace. */
private const val CONTROL_PANEL_HEIGHT_FRACTION = 0.4f
private const val SHIMMER_PERIOD_MS = 1400
private const val SHIMMER_ALPHA = 0.10f

/**
 * The studio, assembled by phase. Empty, Ingesting and Curating show the [DropzoneScreen] (which shows its own error
 * card); Preparing, Ready and Exporting show the workspace: [StudioTopBar], the [StoryCanvas] with its scope overlay
 * and the [ModeDrawer] over it, the bottom [ControlPanel], and the modal export sheet. Workspace errors and the
 * view model's messages appear in one snackbar host. System BACK follows [studioBackAction].
 *
 * @param renderer The preview renderer, or null for a placeholder canvas (screenshots).
 * @param hitTest Slot index under a canvas point for the current composition, or null.
 * @param snackbarHostState Shows workspace errors here; the route also posts the view model's messages to it.
 */
@Composable
fun StudioScreen(
    state: StudioState,
    onIntent: (StudioIntent) -> Unit,
    renderer: StoryRenderer?,
    hitTest: (Float, Float) -> Int?,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val workspace = showsWorkspace(state.phase)
    // Both reset whenever the studio leaves or enters the workspace.
    var exportOpen by rememberSaveable(workspace) { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable(workspace) { mutableStateOf(false) }
    val exportShown = exportOpen && state.phase != StudioPhase.Preparing
    val backAction = studioBackAction(state.phase, exportShown, state.modeDrawerOpen)
    BackHandler(enabled = backAction != StudioBackAction.NOT_HANDLED) {
        when (backAction) {
            StudioBackAction.CLOSE_EXPORT_SHEET -> exportOpen = false
            StudioBackAction.CLOSE_MODE_DRAWER -> onIntent(StudioIntent.ToggleModeDrawer)
            StudioBackAction.CONFIRM_DISCARD -> confirmDiscard = true
            StudioBackAction.CANCEL_WORK -> onIntent(StudioIntent.CancelWork)
            StudioBackAction.NOT_HANDLED -> Unit
        }
    }
    if (workspace) WorkspaceErrorSnackbar(state.error, snackbarHostState, onIntent)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (workspace) {
            StudioWorkspace(
                state = state,
                onIntent = onIntent,
                renderer = renderer,
                hitTest = hitTest,
                snackbarHostState = snackbarHostState,
                exportShown = exportShown,
                onExportOpenChange = { exportOpen = it },
            )
            if (confirmDiscard) {
                DiscardStoryDialog(
                    exporting = state.phase == StudioPhase.Exporting,
                    onKeepEditing = { confirmDiscard = false },
                    onDiscard = {
                        confirmDiscard = false
                        // Never cancel a running MediaStore write from here; the button is disabled meanwhile.
                        if (state.phase != StudioPhase.Exporting) onIntent(StudioIntent.Reset)
                    },
                )
            }
        } else {
            DropzoneScreen(state = state, onIntent = onIntent)
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            )
        }
    }
}

/** True for the phases that show the editing workspace instead of the Dropzone. */
internal fun showsWorkspace(phase: StudioPhase): Boolean = when (phase) {
    StudioPhase.Empty, StudioPhase.Ingesting, StudioPhase.Curating -> false
    StudioPhase.Preparing, StudioPhase.Ready, StudioPhase.Exporting -> true
}

/** What system BACK does in the studio; see [studioBackAction]. */
internal enum class StudioBackAction {
    /** Close the export sheet and nothing else. */
    CLOSE_EXPORT_SHEET,

    /** Collapse the story-mode drawer and nothing else. */
    CLOSE_MODE_DRAWER,

    /** Ask before discarding the story on screen. */
    CONFIRM_DISCARD,

    /** Cancel the running import or curation, back to the empty studio. */
    CANCEL_WORK,

    /** Not intercepted: the activity finishes as usual. */
    NOT_HANDLED,
}

/**
 * The BACK decision for [phase]: in the workspace, an open overlay closes first ([exportSheetOpen] before
 * [modeDrawerOpen]), otherwise the user confirms before the story is discarded; while importing or curating BACK
 * cancels the work; the empty studio lets the activity finish.
 */
internal fun studioBackAction(phase: StudioPhase, exportSheetOpen: Boolean, modeDrawerOpen: Boolean): StudioBackAction =
    when (phase) {
        StudioPhase.Empty -> StudioBackAction.NOT_HANDLED
        StudioPhase.Ingesting, StudioPhase.Curating -> StudioBackAction.CANCEL_WORK
        StudioPhase.Preparing, StudioPhase.Ready, StudioPhase.Exporting -> when {
            exportSheetOpen -> StudioBackAction.CLOSE_EXPORT_SHEET
            modeDrawerOpen -> StudioBackAction.CLOSE_MODE_DRAWER
            else -> StudioBackAction.CONFIRM_DISCARD
        }
    }

@Composable
private fun StudioWorkspace(
    state: StudioState,
    onIntent: (StudioIntent) -> Unit,
    renderer: StoryRenderer?,
    hitTest: (Float, Float) -> Int?,
    snackbarHostState: SnackbarHostState,
    exportShown: Boolean,
    onExportOpenChange: (Boolean) -> Unit,
) {
    val sideInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            StudioTopBar(
                state = state,
                onIntent = onIntent,
                onOpenExport = { onExportOpenChange(true) },
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.background)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
            )
        },
        bottomBar = {
            ControlPanel(
                state = state,
                onIntent = onIntent,
                modifier = Modifier
                    .fillMaxHeight(CONTROL_PANEL_HEIGHT_FRACTION)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                    ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(sideInsets),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(DarkroomTheme.colors.canvasBackdrop),
            ) {
                StoryCanvas(
                    scene = state.scene,
                    revision = state.sceneRevision,
                    renderer = renderer,
                    compare = state.compare,
                    onIntent = onIntent,
                    hitTest = hitTest,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = DarkroomTheme.spacing.s),
                    photoIds = state.selection.photoIds,
                    overrides = state.overrides,
                    scope = state.scope,
                    scopePixels = state.scopePixels,
                )
                if (state.phase == StudioPhase.Preparing) PreparingShimmer(Modifier.fillMaxSize())
                ModeDrawer(
                    state = state,
                    onIntent = onIntent,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth(),
                )
            }
        }
    }
    if (exportShown) {
        ExportModalSheet(state = state, onIntent = onIntent, onDismissRequest = { onExportOpenChange(false) })
    }
}

/**
 * Asks before BACK discards the story: "Keep editing" closes the dialog, "Discard" empties the studio. While
 * [exporting], Discard is disabled and the dialog says why, so a MediaStore write is never cancelled silently.
 */
@Composable
private fun DiscardStoryDialog(exporting: Boolean, onKeepEditing: () -> Unit, onDiscard: () -> Unit) {
    Dialog(onDismissRequest = onKeepEditing) {
        DiscardStoryCard(exporting = exporting, onKeepEditing = onKeepEditing, onDiscard = onDiscard)
    }
}

/** The discard dialog's surface, drawn without its window so screenshots can render it inline. */
@Composable
internal fun DiscardStoryCard(
    exporting: Boolean,
    onKeepEditing: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val spacing = DarkroomTheme.spacing
    val title = stringResource(R.string.studio_discard_title)
    Surface(
        modifier = modifier.semantics { paneTitle = title },
        shape = MaterialTheme.shapes.extraLarge,
        color = scheme.surfaceContainerHigh,
        contentColor = scheme.onSurface,
    ) {
        Column(Modifier.padding(start = spacing.xl, top = spacing.xl, end = spacing.l, bottom = spacing.l)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(end = spacing.s),
            )
            Spacer(Modifier.height(spacing.l))
            Text(
                text = stringResource(R.string.studio_discard_body),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(end = spacing.s),
            )
            if (exporting) {
                Spacer(Modifier.height(spacing.s))
                Text(
                    text = stringResource(R.string.studio_discard_exporting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.secondary,
                    modifier = Modifier
                        .padding(end = spacing.s)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Spacer(Modifier.height(spacing.xl))
            Row(
                modifier = Modifier.align(Alignment.End),
                horizontalArrangement = Arrangement.spacedBy(spacing.s),
            ) {
                TextButton(onClick = onKeepEditing) {
                    Text(stringResource(R.string.studio_discard_keep))
                }
                TextButton(
                    onClick = onDiscard,
                    enabled = !exporting,
                    colors = ButtonDefaults.textButtonColors(contentColor = scheme.error),
                ) {
                    Text(stringResource(R.string.studio_discard_confirm))
                }
            }
        }
    }
}

/**
 * A soft light band sweeping across the canvas while full-resolution photos are prepared; a still veil when the
 * user turned animations off.
 */
@Composable
private fun PreparingShimmer(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.studio_preparing)
    val highlight = MaterialTheme.colorScheme.onSurface.copy(alpha = SHIMMER_ALPHA)
    val reducedMotion = DarkroomTheme.motion.reducedMotion
    BoxWithConstraints(
        modifier = modifier.semantics {
            contentDescription = description
            liveRegion = LiveRegionMode.Polite
        },
    ) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        if (reducedMotion) {
            Box(Modifier.fillMaxSize().background(highlight.copy(alpha = SHIMMER_ALPHA / 2)))
        } else {
            val transition = rememberInfiniteTransition(label = "preparingShimmer")
            val progress by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(SHIMMER_PERIOD_MS, easing = LinearEasing), RepeatMode.Restart),
                label = "preparingShimmerProgress",
            )
            val band = widthPx / 2f
            val start = -band + progress * (widthPx + 2f * band)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color.Transparent, highlight, Color.Transparent),
                            start = Offset(start - band, 0f),
                            end = Offset(start + band, band),
                        ),
                    ),
            )
        }
    }
}

/** Shows a workspace [error] once in the snackbar, then clears it from the state. */
@Composable
private fun WorkspaceErrorSnackbar(
    error: StudioError?,
    snackbarHostState: SnackbarHostState,
    onIntent: (StudioIntent) -> Unit,
) {
    val context = LocalContext.current
    val dismiss = stringResource(R.string.studio_action_dismiss)
    val currentOnIntent by rememberUpdatedState(onIntent)
    LaunchedEffect(error) {
        if (error == null) return@LaunchedEffect
        snackbarHostState.showSnackbar(
            message = studioErrorText(context, error),
            actionLabel = dismiss,
            duration = SnackbarDuration.Long,
        )
        currentOnIntent(StudioIntent.DismissError)
    }
}

/** The localized sentence for a studio [error]. */
internal fun studioErrorText(context: Context, error: StudioError): String = when (error) {
    is StudioError.Ingestion -> ingestionErrorText(context, error.error)
    is StudioError.NoUsablePhotos -> context.getString(R.string.error_no_usable_photos)
    is StudioError.Decode -> context.getString(R.string.dropzone_error_decode, error.name)
    is StudioError.Export -> context.getString(R.string.dropzone_error_export, error.reason)
    is StudioError.Unknown -> context.getString(R.string.dropzone_error_unknown)
}

/** The localized sentence for a view model [message]. */
internal fun studioMessageText(context: Context, message: StudioMessage): String = when (message) {
    StudioMessage.Busy -> context.getString(R.string.studio_message_busy)
    is StudioMessage.SourceFailed -> context.getString(
        R.string.studio_message_source_failed,
        message.displayName,
        ingestionErrorText(context, message.error),
    )
    is StudioMessage.ExportSaved ->
        context.getString(R.string.studio_message_export_saved, context.getString(formatName(message.result.format)))
}

private fun ingestionErrorText(context: Context, error: IngestionError): String = when (error) {
    is IngestionError.ZipSlip -> context.getString(R.string.error_ingestion_zip_slip)
    is IngestionError.ZipBomb -> context.getString(R.string.error_ingestion_zip_bomb)
    is IngestionError.LimitExceeded -> context.getString(R.string.error_ingestion_limit_exceeded)
    is IngestionError.InvalidArchive -> context.getString(R.string.error_ingestion_invalid_archive)
    is IngestionError.InsufficientStorage -> context.getString(
        R.string.error_ingestion_insufficient_storage,
        Formatter.formatShortFileSize(context, error.requiredBytes),
        Formatter.formatShortFileSize(context, error.availableBytes),
    )
    is IngestionError.Io -> context.getString(R.string.error_ingestion_io)
}

private fun formatName(format: ExportFormat): Int = when (format) {
    ExportFormat.PNG_16 -> R.string.controls_format_png16
    ExportFormat.PNG_8 -> R.string.controls_format_png8
    ExportFormat.WEBP_LOSSLESS -> R.string.controls_format_webp
    ExportFormat.SOCIAL_STORY_JPEG -> R.string.controls_format_social
    ExportFormat.ULTRA_HDR_JPEG -> R.string.controls_format_ultra_hdr
}
