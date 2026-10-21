package com.akane.akastudio.feature.studio.ui.dropzone

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.akane.akastudio.core.ingestion.model.IngestionError
import com.akane.akastudio.core.ingestion.picker.PickerContracts
import com.akane.akastudio.core.ingestion.picker.PickerItems
import com.akane.akastudio.feature.studio.R
import com.akane.akastudio.feature.studio.domain.StudioError
import com.akane.akastudio.feature.studio.mvi.StudioIntent
import com.akane.akastudio.feature.studio.mvi.StudioPhase
import com.akane.akastudio.feature.studio.mvi.StudioState
import com.akane.akastudio.feature.studio.ui.note.CuratorNoteFormatter
import com.akane.akastudio.feature.studio.ui.note.ResourceNoteStrings
import com.akane.akastudio.feature.studio.ui.theme.DarkroomTextStyles
import com.akane.akastudio.feature.studio.ui.theme.DarkroomTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "Dropzone"
private const val PERCENT = 100

/** Component dimensions fixed by the Dropzone brief. */
private val CardMinHeight = 72.dp
private val CardIconTile = 40.dp
private val CardIconSize = 24.dp
private val ProgressLineHeight = 2.dp
private val ProgressGap = 0.dp
private val WordmarkTracking = 0.12.em

/**
 * The Dropzone: the welcome screen with the two import cards, then the live curation readout while photos are
 * read, analyzed and decided.
 *
 * Stateless over [state]: every action is sent to [onIntent]. It owns only the two system pickers, whose results
 * become [StudioIntent.ImportPhotos] and [StudioIntent.ImportZip]; repeated imports accumulate until curation
 * starts.
 */
@Composable
fun DropzoneScreen(state: StudioState, onIntent: (StudioIntent) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnIntent by rememberUpdatedState(onIntent)
    var lastSource by rememberSaveable { mutableStateOf(ImportSource.PHOTOS) }

    val photoContract = remember { PickerContracts.pickPhotos() }
    val photoLauncher = rememberLauncherForActivityResult(photoContract) { uris ->
        if (uris.isNotEmpty()) currentOnIntent(StudioIntent.ImportPhotos(uris.map(Uri::toString)))
    }
    val zipContract = remember { PickerContracts.openZip() }
    val zipLauncher = rememberLauncherForActivityResult(zipContract) { uri ->
        if (uri != null) {
            val resolver = context.contentResolver
            scope.launch {
                val name = withContext(Dispatchers.IO) { displayNameOf(resolver, uri) }
                currentOnIntent(StudioIntent.ImportZip(uri.toString(), name))
            }
        }
    }

    val pickPhotos: () -> Unit = {
        lastSource = ImportSource.PHOTOS
        launchPicker { photoLauncher.launch(PickerContracts.pickPhotosRequest()) }
    }
    val pickZip: () -> Unit = {
        lastSource = ImportSource.ZIP
        launchPicker { zipLauncher.launch(PickerContracts.ZIP_MIME_TYPES) }
    }
    DropzoneContent(
        state = state,
        onIntent = onIntent,
        onPickPhotos = pickPhotos,
        onPickZip = pickZip,
        onRetry = {
            onIntent(StudioIntent.DismissError)
            if (DropzoneModel.retryReopensPicker(state.phase)) {
                when (lastSource) {
                    ImportSource.PHOTOS -> pickPhotos()
                    ImportSource.ZIP -> pickZip()
                }
            }
        },
        modifier = modifier,
    )
}

/** Launches a system picker; a device without one (no documents UI) leaves the screen as it was. */
private inline fun launchPicker(launch: () -> Unit) {
    try {
        launch()
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "No activity can handle the picker request", e)
    }
}

/** The document's display name, or the last uri segment when the provider has none or refuses the query. */
private fun displayNameOf(resolver: ContentResolver, uri: Uri): String {
    val queried = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }
    } catch (e: SecurityException) {
        Log.w(TAG, "Display name query refused", e)
        null
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "Display name query rejected", e)
        null
    }
    return queried?.takeIf { it.isNotBlank() } ?: PickerItems.displayNameFallback(uri.toString())
}

/** The Dropzone layout with the pickers hoisted out; previews and screenshots render it through [DropzoneScreen]. */
@Composable
internal fun DropzoneContent(
    state: StudioState,
    onIntent: (StudioIntent) -> Unit,
    onPickPhotos: () -> Unit,
    onPickZip: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = DarkroomTheme.spacing
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = spacing.xl, vertical = spacing.xl),
        ) {
            if (DropzoneModel.showsWelcome(state.phase)) {
                WelcomeContent(state.error, onPickPhotos, onPickZip, onRetry)
            } else {
                PipelineContent(state, onIntent, onPickPhotos, onPickZip, onRetry)
            }
        }
    }
}

@Composable
private fun ColumnScope.WelcomeContent(
    error: StudioError?,
    onPickPhotos: () -> Unit,
    onPickZip: () -> Unit,
    onRetry: () -> Unit,
) {
    val spacing = DarkroomTheme.spacing
    Spacer(Modifier.weight(1f))
    Text(
        text = stringResource(R.string.studio_app_name),
        style = MaterialTheme.typography.displaySmall.copy(letterSpacing = WordmarkTracking),
        color = MaterialTheme.colorScheme.onBackground,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { heading() },
    )
    Spacer(Modifier.height(spacing.s))
    Text(
        text = stringResource(R.string.dropzone_tagline),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.weight(1f))
    Spacer(Modifier.height(spacing.xl))
    if (error != null) {
        ErrorCard(error, onRetry)
        Spacer(Modifier.height(spacing.l))
    }
    ActionCard(
        title = stringResource(R.string.dropzone_select_photos),
        subtitle = stringResource(R.string.dropzone_select_photos_formats),
        icon = DropzoneIcons.Photos,
        onClick = onPickPhotos,
    )
    Spacer(Modifier.height(spacing.m))
    ActionCard(
        title = stringResource(R.string.dropzone_import_zip),
        subtitle = stringResource(R.string.dropzone_import_zip_subtitle),
        icon = DropzoneIcons.Archive,
        onClick = onPickZip,
    )
}

@Composable
private fun ActionCard(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    val spacing = DarkroomTheme.spacing
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = CardMinHeight)
            .semantics { role = Role.Button },
        shape = MaterialTheme.shapes.large,
        color = scheme.surfaceContainer,
        contentColor = scheme.onSurface,
        border = BorderStroke(Dp.Hairline, scheme.outline),
    ) {
        Row(
            modifier = Modifier.padding(spacing.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(CardIconTile)
                    .background(scheme.surfaceContainerHigh, MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center,
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = scheme.onSurface, modifier = Modifier.size(CardIconSize))
            }
            Spacer(Modifier.width(spacing.l))
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
                Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ColumnScope.PipelineContent(
    state: StudioState,
    onIntent: (StudioIntent) -> Unit,
    onPickPhotos: () -> Unit,
    onPickZip: () -> Unit,
    onRetry: () -> Unit,
) {
    val spacing = DarkroomTheme.spacing
    val title = when (state.phase) {
        StudioPhase.Ingesting -> R.string.dropzone_title_reading
        StudioPhase.Curating -> R.string.dropzone_title_curating
        else -> R.string.dropzone_title_decided
    }
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(spacing.l))
    ProgressLine(DropzoneModel.progress(state.telemetry))
    Spacer(Modifier.height(spacing.xl))
    TelemetrySteps(state.telemetry)
    state.curation?.note?.let { note ->
        val context = LocalContext.current
        val configuration = LocalConfiguration.current
        val sentence = remember(note, configuration) { CuratorNoteFormatter.format(note, ResourceNoteStrings(context)) }
        Spacer(Modifier.height(spacing.l))
        Text(
            text = sentence,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = stepLabelInset()),
        )
    }
    Spacer(Modifier.weight(1f))
    Spacer(Modifier.height(spacing.xl))
    state.error?.let { error ->
        ErrorCard(error, onRetry)
        Spacer(Modifier.height(spacing.l))
    }
    PipelineActions(state, onIntent, onPickPhotos, onPickZip)
}

@Composable
private fun ProgressLine(progress: Float) {
    val animated by animateFloatAsState(
        targetValue = progress,
        animationSpec = DarkroomTheme.motion.standard(),
        label = "dropzone-progress",
    )
    val description = stringResource(R.string.dropzone_progress_description, (progress * PERCENT).roundToInt())
    LinearProgressIndicator(
        progress = { animated },
        modifier = Modifier
            .fillMaxWidth()
            .height(ProgressLineHeight)
            .semantics { contentDescription = description },
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.outline,
        strokeCap = StrokeCap.Butt,
        gapSize = ProgressGap,
        drawStopIndicator = {},
    )
}

@Composable
private fun PipelineActions(
    state: StudioState,
    onIntent: (StudioIntent) -> Unit,
    onPickPhotos: () -> Unit,
    onPickZip: () -> Unit,
) {
    val spacing = DarkroomTheme.spacing
    val canCancel = DropzoneModel.canCancel(state.phase)
    val canAddMore = DropzoneModel.canAddMore(state)
    if (!canCancel && !canAddMore) return
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.s, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(spacing.s),
    ) {
        if (canCancel) {
            TextButton(
                onClick = { onIntent(StudioIntent.CancelWork) },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        }
        if (canAddMore) {
            AddMoreButton(onPickPhotos, onPickZip)
            Button(onClick = { onIntent(StudioIntent.StartCuration) }) {
                Text(stringResource(R.string.dropzone_curate_now))
            }
        }
    }
}

@Composable
private fun AddMoreButton(onPickPhotos: () -> Unit, onPickZip: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(stringResource(R.string.dropzone_add_more))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.dropzone_add_more_photos)) },
                leadingIcon = { Icon(DropzoneIcons.Photos, contentDescription = null, modifier = Modifier.size(CardIconSize)) },
                onClick = {
                    expanded = false
                    onPickPhotos()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.dropzone_add_more_zip)) },
                leadingIcon = { Icon(DropzoneIcons.Archive, contentDescription = null, modifier = Modifier.size(CardIconSize)) },
                onClick = {
                    expanded = false
                    onPickZip()
                },
            )
        }
    }
}

@Composable
private fun ErrorCard(error: StudioError, onRetry: () -> Unit) {
    val spacing = DarkroomTheme.spacing
    val scheme = MaterialTheme.colorScheme
    val (message, detail) = errorText(error)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.large,
        color = scheme.errorContainer,
        contentColor = scheme.onErrorContainer,
    ) {
        Column(modifier = Modifier.padding(start = spacing.l, top = spacing.l, end = spacing.s, bottom = spacing.s)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(end = spacing.s),
            )
            if (detail != null) {
                Spacer(Modifier.height(spacing.xs))
                Text(text = detail, style = DarkroomTextStyles.mono, modifier = Modifier.padding(end = spacing.s))
            }
            TextButton(
                onClick = onRetry,
                modifier = Modifier.align(Alignment.End),
                colors = ButtonDefaults.textButtonColors(contentColor = scheme.onErrorContainer),
            ) {
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}

/** The localized sentence for [error], plus a technical detail line when the error only has a raw description. */
@Composable
private fun errorText(error: StudioError): Pair<String, String?> = when (error) {
    is StudioError.Ingestion -> ingestionErrorText(error.error) to null
    is StudioError.NoUsablePhotos -> stringResource(R.string.error_no_usable_photos) to null
    is StudioError.Decode -> stringResource(R.string.dropzone_error_decode, error.name) to null
    is StudioError.Export -> stringResource(R.string.dropzone_error_export, error.reason) to null
    is StudioError.Unknown -> stringResource(R.string.dropzone_error_unknown) to error.message.takeIf { it.isNotBlank() }
}

@Composable
private fun ingestionErrorText(error: IngestionError): String = when (error) {
    is IngestionError.ZipSlip -> stringResource(R.string.error_ingestion_zip_slip)
    is IngestionError.ZipBomb -> stringResource(R.string.error_ingestion_zip_bomb)
    is IngestionError.LimitExceeded -> stringResource(R.string.error_ingestion_limit_exceeded)
    is IngestionError.InvalidArchive -> stringResource(R.string.error_ingestion_invalid_archive)
    is IngestionError.InsufficientStorage -> {
        val context = LocalContext.current
        stringResource(
            R.string.error_ingestion_insufficient_storage,
            Formatter.formatShortFileSize(context, error.requiredBytes),
            Formatter.formatShortFileSize(context, error.availableBytes),
        )
    }
    is IngestionError.Io -> stringResource(R.string.error_ingestion_io)
}
