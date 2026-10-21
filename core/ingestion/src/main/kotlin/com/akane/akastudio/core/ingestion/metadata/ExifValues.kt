package com.akane.akastudio.core.ingestion.metadata

import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** Parsers for untrusted EXIF strings. Every malformed value becomes null instead of throwing. */
internal object ExifValues {
    private val EXIF_DATE_TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)

    /**
     * Parses an EXIF `yyyy:MM:dd HH:mm:ss` timestamp. A valid [offset] (`+03:00`, `-05:30`, `Z`) pins the instant
     * and reports `true`; a missing or malformed offset falls back to [fallbackZone] and reports `false`. A blank,
     * zeroed, malformed or out-of-range timestamp yields `(null, false)`.
     */
    fun parseCaptureTime(dateTime: String?, offset: String?, fallbackZone: ZoneId): Pair<Long?, Boolean> {
        val local = parseLocal(dateTime) ?: return null to false
        val zoneOffset = parseOffset(offset)
        val instant = if (zoneOffset != null) local.toInstant(zoneOffset) else local.atZone(fallbackZone).toInstant()
        return instant.toEpochMilli() to (zoneOffset != null)
    }

    /** `"1/250"`, `"28/10"`, `"0.004"`, `"1.25E-4"`; a zero denominator, negative or non-finite value is null. */
    fun parseRational(s: String?): Double? {
        val text = s?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val value = if ('/' in text) {
            val numerator = text.substringBefore('/').trim().toDoubleOrNull() ?: return null
            val denominator = text.substringAfter('/').trim().toDoubleOrNull() ?: return null
            if (denominator == 0.0) return null
            numerator / denominator
        } else {
            text.toDoubleOrNull() ?: return null
        }
        return value.takeIf { it.isFinite() && it >= 0.0 }
    }

    /** `"100"`; a multi-value `"100,200"` takes the first; blank or non-numeric is null. */
    fun parseIntTag(s: String?): Int? = s?.substringBefore(',')?.trim()?.toIntOrNull()

    private fun parseLocal(dateTime: String?): LocalDateTime? {
        val text = dateTime?.trim(' ', '\u0000')?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            LocalDateTime.parse(text, EXIF_DATE_TIME)
        } catch (e: DateTimeException) {
            null
        }
    }

    private fun parseOffset(offset: String?): ZoneOffset? {
        val text = offset?.trim(' ', '\u0000')?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            ZoneOffset.of(text)
        } catch (e: DateTimeException) {
            null
        }
    }
}
