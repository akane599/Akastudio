package com.akane.akastudio.core.composer.compose

import com.akane.akastudio.core.ingestion.model.CaptureMetadata
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Formats the technical caption, for example `24mm · f/1.8 · 1/250s · ISO 64`.
 *
 * Parts appear in that order and only when known and positive: 35 mm-equivalent focal length, f-number to one
 * decimal (trailing `.0` dropped), exposure time and ISO. Exposure reads `1/Ns` below one second when `1/N` is within
 * 5% of the true time (every standard fast speed), and decimal seconds otherwise, so 0.4 s is "0.4s" rather than a
 * "1/3s" that is a third of a stop off. Formatting is locale-independent.
 */
object ExifCaption {
    private const val SEPARATOR = " · "
    /** Largest relative gap between `1/seconds` and its nearest whole denominator still shown as a fraction. */
    private const val FRACTION_TOLERANCE = 0.05

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
            val reciprocal = 1.0 / seconds
            val denominator = reciprocal.roundToLong()
            if (denominator > 1 && abs(reciprocal - denominator) <= FRACTION_TOLERANCE * denominator) return "1/${denominator}s"
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
