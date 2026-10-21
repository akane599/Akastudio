package com.akane.akastudio.feature.studio.ui.dropzone

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.akane.akastudio.core.ingestion.picker.PickerContracts
import com.akane.akastudio.core.ingestion.picker.PickerItems
import com.akane.akastudio.feature.studio.mvi.StudioIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * import intent (photos or zip, when something was picked) and then [StudioIntent.PickerClosed], also when the user
 * cancels or no picker activity exists.
 */
@Composable
internal fun rememberImportPickers(onIntent: (StudioIntent) -> Unit): ImportPickers {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnIntent by rememberUpdatedState(onIntent)
    var lastSource by rememberSaveable { mutableStateOf(ImportSource.PHOTOS) }

    val photoContract = remember { PickerContracts.pickPhotos() }
    val photoLauncher = rememberLauncherForActivityResult(photoContract) { uris ->
        if (uris.isNotEmpty()) currentOnIntent(StudioIntent.ImportPhotos(uris.map(Uri::toString)))
        currentOnIntent(StudioIntent.PickerClosed)
    }
    val zipContract = remember { PickerContracts.openZip() }
    val zipLauncher = rememberLauncherForActivityResult(zipContract) { uri ->
        if (uri == null) {
            currentOnIntent(StudioIntent.PickerClosed)
        } else {
            val resolver = context.contentResolver
            scope.launch {
                try {
                    val name = withContext(Dispatchers.IO) { displayNameOf(resolver, uri) }
                    currentOnIntent(StudioIntent.ImportZip(uri.toString(), name))
                } finally {
                    currentOnIntent(StudioIntent.PickerClosed)
                }
            }
        }
    }

    return remember(photoLauncher, zipLauncher) {
        ImportPickers(
            open = { source ->
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
                    currentOnIntent(StudioIntent.PickerClosed)
                }
            },
            lastSource = { lastSource },
        )
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
