package com.akane.akastudio.core.ingestion.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ExifOrientationTest {

    private data class Expected(
        val orientation: ExifOrientation,
        val rotationDegrees: Int,
        val mirrored: Boolean,
        val swapsDimensions: Boolean,
    )

    @Test
    fun fromExif_mapsAllEightValues() {
        val expected = mapOf(
            1 to Expected(ExifOrientation.NORMAL, 0, mirrored = false, swapsDimensions = false),
            2 to Expected(ExifOrientation.FLIP_HORIZONTAL, 0, mirrored = true, swapsDimensions = false),
            3 to Expected(ExifOrientation.ROTATE_180, 180, mirrored = false, swapsDimensions = false),
            4 to Expected(ExifOrientation.FLIP_VERTICAL, 180, mirrored = true, swapsDimensions = false),
            5 to Expected(ExifOrientation.TRANSPOSE, 90, mirrored = true, swapsDimensions = true),
            6 to Expected(ExifOrientation.ROTATE_90, 90, mirrored = false, swapsDimensions = true),
            7 to Expected(ExifOrientation.TRANSVERSE, 270, mirrored = true, swapsDimensions = true),
            8 to Expected(ExifOrientation.ROTATE_270, 270, mirrored = false, swapsDimensions = true),
        )
        expected.forEach { (value, want) ->
            val actual = ExifOrientation.fromExif(value)
            assertEquals("orientation for $value", want.orientation, actual)
            assertEquals("exifValue for $value", value, actual.exifValue)
            assertEquals("rotation for $value", want.rotationDegrees, actual.rotationDegrees)
            assertEquals("mirrored for $value", want.mirrored, actual.mirrored)
            assertEquals("swapsDimensions for $value", want.swapsDimensions, actual.swapsDimensions)
        }
    }

    @Test
    fun fromExif_unknownValuesAreNormal() {
        assertEquals(ExifOrientation.NORMAL, ExifOrientation.fromExif(0))
        assertEquals(ExifOrientation.NORMAL, ExifOrientation.fromExif(9))
        assertEquals(ExifOrientation.NORMAL, ExifOrientation.fromExif(-1))
    }
}
