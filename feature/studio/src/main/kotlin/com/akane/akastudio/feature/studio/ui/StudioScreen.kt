package com.akane.akastudio.feature.studio.ui

import android.content.Context
import android.text.format.Formatter
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.semantics.semantics
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
 * view model's messages appear in one snackbar host.
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
    if (workspace) WorkspaceErrorSnackbar(state.error, snackbarHostState, onIntent)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (workspace) {
            StudioWorkspace(state, onIntent, renderer, hitTest, snackbarHostState)
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

@Composable
private fun StudioWorkspace(
    state: StudioState,
    onIntent: (StudioIntent) -> Unit,
    renderer: StoryRenderer?,
    hitTest: (Float, Float) -> Int?,
    snackbarHostState: SnackbarHostState,
) {
    var exportOpen by rememberSaveable { mutableStateOf(false) }
    val sideInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            StudioTopBar(
                state = state,
                onIntent = onIntent,
                onOpenExport = { exportOpen = true },
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
    if (exportOpen && state.phase != StudioPhase.Preparing) {
        ExportModalSheet(state = state, onIntent = onIntent, onDismissRequest = { exportOpen = false })
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
