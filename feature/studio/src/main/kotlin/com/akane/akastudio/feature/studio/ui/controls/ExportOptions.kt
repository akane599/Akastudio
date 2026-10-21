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

/** Why the "HDR Story Boost" switch reads as it does; each maps to one summary line. */
internal enum class HdrBoostNote {
    /** The boost applies to the chosen format. */
    APPLIES,

    /** The device supports it, but only Ultra HDR embeds a gain map; the other formats ignore the boost. */
    ULTRA_HDR_ONLY,

    /** The device is below [ULTRA_HDR_MIN_SDK]. */
    NEEDS_ANDROID_14,
}

/** How the "HDR Story Boost" switch is drawn: on only when the boost will reach the exported file. */
internal data class HdrBoostSwitchState(val checked: Boolean, val enabled: Boolean, val note: HdrBoostNote)

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

    /**
     * The HDR boost switch for [format]: it applies only to [ExportFormat.ULTRA_HDR_JPEG] on a device with
     * [ExportAvailability.hdrBoostAvailable]. Otherwise it reads off and is disabled, whatever [hdrBoost] holds,
     * since the exporter reads the flag only on the Ultra HDR path. [editable] is false while an export runs.
     */
    fun hdrBoostSwitch(
        availability: ExportAvailability,
        format: ExportFormat,
        hdrBoost: Boolean,
        editable: Boolean,
    ): HdrBoostSwitchState {
        val note = when {
            !availability.hdrBoostAvailable -> HdrBoostNote.NEEDS_ANDROID_14
            format != ExportFormat.ULTRA_HDR_JPEG -> HdrBoostNote.ULTRA_HDR_ONLY
            else -> HdrBoostNote.APPLIES
        }
        val applies = note == HdrBoostNote.APPLIES
        return HdrBoostSwitchState(checked = hdrBoost && applies, enabled = applies && editable, note = note)
    }
}
