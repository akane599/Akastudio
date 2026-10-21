package com.akane.akastudio.feature.studio.ui.controls

import com.akane.akastudio.core.export.api.ExportFormat

/** Ultra HDR (JPEG_R gain maps) needs Android 14, API 34 (`Build.VERSION_CODES.UPSIDE_DOWN_CAKE`). */
internal const val ULTRA_HDR_MIN_SDK = 34

/** One row of the export format list and whether this device can produce it. */
internal data class ExportFormatOption(val format: ExportFormat, val enabled: Boolean)

/**
 * What the export sheet offers on a device.
 *
 * @property formats Every format in display order (the [ExportFormat] declaration order).
 * @property hdrBoostAvailable Whether the "HDR Story Boost" gain map can be embedded.
 */
internal data class ExportAvailability(val formats: List<ExportFormatOption>, val hdrBoostAvailable: Boolean) {

    /** Whether [format] can be exported on this device. */
    fun isEnabled(format: ExportFormat): Boolean = formats.firstOrNull { it.format == format }?.enabled == true
}

/** Pure export availability by platform level, so the rules are testable without a device. */
internal object ExportOptions {

    /** The formats and HDR boost available at [sdkInt]; Ultra HDR and the gain map need [ULTRA_HDR_MIN_SDK]. */
    fun availability(sdkInt: Int): ExportAvailability {
        val ultraHdr = sdkInt >= ULTRA_HDR_MIN_SDK
        return ExportAvailability(
            formats = ExportFormat.entries.map { format ->
                ExportFormatOption(format, enabled = format != ExportFormat.ULTRA_HDR_JPEG || ultraHdr)
            },
            hdrBoostAvailable = ultraHdr,
        )
    }
}
