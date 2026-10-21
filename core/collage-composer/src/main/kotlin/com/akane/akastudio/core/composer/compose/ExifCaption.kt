package com.akane.akastudio.core.composer.compose

import com.akane.akastudio.core.ingestion.model.CaptureMetadata
import kotlin.math.roundToLong

/**
 * Formats the technical caption, for example `24mm · f/1.8 · 1/250s · ISO 64`.
 *
 * Parts appear in that order and only when known and positive: 35 mm-equivalent focal length, f-number to one
 * decimal (trailing `.0` dropped), exposure time (`1/N s` below one second, decimal seconds otherwise) and ISO.
 * Formatting is locale-independent.
 */
object ExifCaption {
    private const val SEPARATOR = " · "

    /** The caption for [m], or null when none of its parts is known. */
    fun format(m: CaptureMetadata): String? {
        val parts = listOfNotNull(
            m.focalLength35mm?.takeIf { it > 0 }?.let { "${it}mm" },
            m.fNumber?.takeIf { it.isFinite() && it > 0.0 }?.let { "f/${oneDecimal(it)}" },
            m.exposureTimeSeconds?.takeIf { it.isFinite() && it > 0.0 }?.let(::exposure),
            m.iso?.takeIf { it > 0 }?.let { "ISO $it" },
        )
        return if (parts.isEmpty()) null else parts.joinToString(SEPARATOR)
    }

    private fun exposure(seconds: Double): String {
        if (seconds < 1.0) {
            val denominator = (1.0 / seconds).roundToLong()
            if (denominator > 1) return "1/${denominator}s"
        }
        return "${oneDecimal(seconds)}s"
    }

    /** One decimal place without a locale, dropping a trailing `.0`: 1.8 → "1.8", 2.0 → "2", 1.75 → "1.8". */
    private fun oneDecimal(value: Double): String {
        val tenths = (value * 10).roundToLong()
        val whole = tenths / 10
        val fraction = tenths % 10
        return if (fraction == 0L) "$whole" else "$whole.$fraction"
    }
}
