package com.akane.akastudio.feature.studio.ui.dropzone

import android.content.ActivityNotFoundException
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.akane.akastudio.core.ingestion.picker.PickerContracts
import com.akane.akastudio.feature.studio.mvi.StudioIntent

private const val TAG = "ImportPickers"

/**
 * The two system pickers (photos and zip). They are remembered by the always-composed studio screen, not by the
 * Dropzone, so a result still arrives when the studio has moved past the Dropzone while a picker was open.
 */
@Stable
class ImportPickers internal constructor(
    private val open: (ImportSource) -> Unit,
    private val lastSource: () -> ImportSource,
) {
    /** Opens the photo picker. */
    fun pickPhotos() = open(ImportSource.PHOTOS)

    /** Opens the zip document picker. */
    fun pickZip() = open(ImportSource.ZIP)

    /** Reopens the picker the user opened last (photos when none was opened yet). */
    fun reopenLast() = open(lastSource())

    internal companion object {
        /** Pickers that open nothing, for screenshots of the screen without an activity-result registry. */
        val Inert = ImportPickers(open = {}, lastSource = { ImportSource.PHOTOS })
    }
}

/**
 * Remembers the studio's [ImportPickers]. Every launch sends [StudioIntent.PickerOpened]; every outcome sends the
 * import intent (photos or zip, when something was picked) and then [StudioIntent.PickerClosed], synchronously from
 * the result callback, also when the user cancels or no picker activity exists. A second open while a launch is
 * pending (a fast double tap) is ignored, so pickers never stack.
 */
@Composable
internal fun rememberImportPickers(onIntent: (StudioIntent) -> Unit): ImportPickers {
    val currentOnIntent by rememberUpdatedState(onIntent)
    var lastSource by rememberSaveable { mutableStateOf(ImportSource.PHOTOS) }
    // Not saved: after a recreation the open picker can't be tapped again anyway, and a lost result must never
    // leave the pickers locked. The view model counts open pickers on its own.
    var launchPending by remember { mutableStateOf(false) }

    val photoContract = remember { PickerContracts.pickPhotos() }
    val photoLauncher = rememberLauncherForActivityResult(photoContract) { uris ->
        launchPending = false
        if (uris.isNotEmpty()) currentOnIntent(StudioIntent.ImportPhotos(uris.map(Uri::toString)))
        currentOnIntent(StudioIntent.PickerClosed)
    }
    val zipContract = remember { PickerContracts.openZip() }
    val zipLauncher = rememberLauncherForActivityResult(zipContract) { uri ->
        launchPending = false
        // The view model resolves the display name off the main thread; nothing here can be cancelled.
        if (uri != null) currentOnIntent(StudioIntent.ImportZip(uri.toString()))
        currentOnIntent(StudioIntent.PickerClosed)
    }

    return remember(photoLauncher, zipLauncher) {
        ImportPickers(
            open = open@{ source ->
                if (launchPending) return@open
                launchPending = true
                lastSource = source
                currentOnIntent(StudioIntent.PickerOpened)
                try {
                    when (source) {
                        ImportSource.PHOTOS -> photoLauncher.launch(PickerContracts.pickPhotosRequest())
                        ImportSource.ZIP -> zipLauncher.launch(PickerContracts.ZIP_MIME_TYPES)
                    }
                } catch (e: ActivityNotFoundException) {
                    // A device without a picker (no documents UI) leaves the screen as it was.
                    Log.w(TAG, "No activity can handle the picker request", e)
                    launchPending = false
                    currentOnIntent(StudioIntent.PickerClosed)
                }
            },
            lastSource = { lastSource },
        )
    }
}
