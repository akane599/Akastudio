package com.akane.akastudio.core.ingestion.picker

import android.os.Build
import android.os.ext.SdkExtensions
import android.provider.MediaStore
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts

/** Activity-result plumbing for the photo picker and the zip document picker. */
object PickerContracts {

    /** `PickMultipleVisualMedia` requires more than one item. */
    private const val MIN_PICK_ITEMS = 2

    /** Mime types offered to [openZip]; a fresh array on every read so callers cannot mutate a shared one. */
    val ZIP_MIME_TYPES: Array<String>
        get() = arrayOf("application/zip", "application/x-zip-compressed", "application/x-zip", "multipart/x-zip")

    fun pickPhotosRequest(): PickVisualMediaRequest =
        PickVisualMediaRequest.Builder()
            .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
            .build()

    /** [maxItems] is clamped to `[2, MediaStore.getPickImagesMaxLimit()]`; the platform limit is read on each call. */
    fun pickPhotos(maxItems: Int = 100): ActivityResultContracts.PickMultipleVisualMedia {
        val upper = maxOf(MIN_PICK_ITEMS, platformPickLimit())
        return ActivityResultContracts.PickMultipleVisualMedia(maxItems.coerceIn(MIN_PICK_ITEMS, upper))
    }

    fun openZip(): ActivityResultContracts.OpenDocument = ActivityResultContracts.OpenDocument()

    /** Devices without the system photo picker fall back to ACTION_OPEN_DOCUMENT, which has no item limit. */
    private fun platformPickLimit(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ||
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 2
        ) {
            MediaStore.getPickImagesMaxLimit()
        } else {
            Int.MAX_VALUE
        }
}
