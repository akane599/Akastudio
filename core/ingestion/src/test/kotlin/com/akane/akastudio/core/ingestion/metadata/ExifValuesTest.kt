package com.akane.akastudio.core.ingestion.metadata

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExifValuesTest {
    private val tokyo = ZoneId.of("Asia/Tokyo")

    private fun millis(iso: String, offset: ZoneOffset): Long =
        LocalDateTime.parse(iso).toInstant(offset).toEpochMilli()

    @Test
    fun parseCaptureTime_withPositiveOffset_usesOffset() {
        val result = ExifValues.parseCaptureTime("2024:06:01 12:30:45", "+03:00", tokyo)
        assertEquals(millis("2024-06-01T12:30:45", ZoneOffset.ofHours(3)) to true, result)
    }

    @Test
    fun parseCaptureTime_withNegativeHalfHourOffset_usesOffset() {
        val result = ExifValues.parseCaptureTime("2024:06:01 12:30:45", "-05:30", tokyo)
        assertEquals(millis("2024-06-01T12:30:45", ZoneOffset.ofHoursMinutes(-5, -30)) to true, result)
    }

    @Test
    fun parseCaptureTime_withZuluOffset_isUtc() {
        val result = ExifValues.parseCaptureTime("2024:06:01 12:30:45", "Z", tokyo)
        assertEquals(millis("2024-06-01T12:30:45", ZoneOffset.UTC) to true, result)
    }

    @Test
    fun parseCaptureTime_withoutOffset_usesFallbackZone() {
        val expected = LocalDateTime.parse("2024-06-01T12:30:45").atZone(tokyo).toInstant().toEpochMilli()
        assertEquals(expected to false, ExifValues.parseCaptureTime("2024:06:01 12:30:45", null, tokyo))
        assertEquals(expected to false, ExifValues.parseCaptureTime("2024:06:01 12:30:45", "  ", tokyo))
    }

    @Test
    fun parseCaptureTime_withMalformedOffset_fallsBackToZoneWithoutOffsetFlag() {
        val expected = LocalDateTime.parse("2024-06-01T12:30:45").atZone(tokyo).toInstant().toEpochMilli()
        assertEquals(expected to false, ExifValues.parseCaptureTime("2024:06:01 12:30:45", "  :  ", tokyo))
    }

    @Test
    fun parseCaptureTime_toleratesTrailingNulAndSpaces() {
        val result = ExifValues.parseCaptureTime(" 2024:06:01 12:30:45\u0000", "+03:00", tokyo)
        assertEquals(millis("2024-06-01T12:30:45", ZoneOffset.ofHours(3)) to true, result)
    }

    @Test
    fun parseCaptureTime_rejectsBlankZeroedMalformedAndOutOfRange() {
        val rejected = listOf(
            null,
            "",
            "   ",
            "0000:00:00 00:00:00",
            "2024-06-01 12:30:45",
            "2024:06:01",
            "not a date",
            "2024:13:01 12:00:00",
            "2024:02:30 12:00:00",
            "2024:06:01 24:00:00",
            "2024:06:01 12:60:00",
        )
        for (value in rejected) {
            assertEquals("input=$value", null to false, ExifValues.parseCaptureTime(value, "+03:00", tokyo))
        }
    }

    @Test
    fun parseRational_parsesFractionsAndDecimals() {
        assertEquals(0.004, ExifValues.parseRational("1/250")!!, 1e-12)
        assertEquals(2.8, ExifValues.parseRational("28/10")!!, 1e-12)
        assertEquals(0.004, ExifValues.parseRational("0.004")!!, 1e-12)
        assertEquals(1.25e-4, ExifValues.parseRational("1.25E-4")!!, 1e-15)
        assertEquals(0.5, ExifValues.parseRational(" 1 / 2 ")!!, 1e-12)
    }

    @Test
    fun parseRational_rejectsZeroDenominatorNegativesAndGarbage() {
        assertNull(ExifValues.parseRational("0/0"))
        assertNull(ExifValues.parseRational("5/0"))
        assertNull(ExifValues.parseRational("-1/3"))
        assertNull(ExifValues.parseRational("1/-3"))
        assertNull(ExifValues.parseRational("-0.5"))
        assertNull(ExifValues.parseRational(null))
        assertNull(ExifValues.parseRational(""))
        assertNull(ExifValues.parseRational("abc"))
        assertNull(ExifValues.parseRational("1/x"))
        assertNull(ExifValues.parseRational("NaN"))
        assertNull(ExifValues.parseRational("Infinity"))
    }

    @Test
    fun parseIntTag_takesFirstValueAndRejectsBlank() {
        assertEquals(100, ExifValues.parseIntTag("100"))
        assertEquals(100, ExifValues.parseIntTag("100,200"))
        assertEquals(100, ExifValues.parseIntTag(" 100 , 200"))
        assertNull(ExifValues.parseIntTag(""))
        assertNull(ExifValues.parseIntTag("  "))
        assertNull(ExifValues.parseIntTag(null))
        assertNull(ExifValues.parseIntTag("ISO"))
    }
}
