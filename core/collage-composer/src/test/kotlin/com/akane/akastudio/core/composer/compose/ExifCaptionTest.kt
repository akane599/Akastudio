package com.akane.akastudio.core.composer.compose

import com.akane.akastudio.core.ingestion.model.CaptureMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExifCaptionTest {

    private fun meta(focal: Int? = null, f: Double? = null, t: Double? = null, iso: Int? = null) =
        CaptureMetadata.EMPTY.copy(focalLength35mm = focal, fNumber = f, exposureTimeSeconds = t, iso = iso)

    @Test
    fun full_formatsEveryPartInOrder() {
        assertEquals("24mm · f/1.8 · 1/250s · ISO 64", ExifCaption.format(meta(24, 1.8, 0.004, 64)))
        assertEquals("50mm · f/2 · 2s · ISO 3200", ExifCaption.format(meta(50, 2.0, 2.0, 3200)))
        assertEquals("13mm · f/2.2 · 1.5s · ISO 800", ExifCaption.format(meta(13, 2.2, 1.5, 800)))
    }

    @Test
    fun partial_keepsOnlyKnownParts() {
        assertEquals("f/1.8 · ISO 64", ExifCaption.format(meta(f = 1.8, iso = 64)))
        assertEquals("26mm", ExifCaption.format(meta(focal = 26)))
        assertEquals("1/60s", ExifCaption.format(meta(t = 1.0 / 60.0)))
        assertEquals("f/1.8", ExifCaption.format(meta(f = 1.75)))
        assertEquals("0.8s", ExifCaption.format(meta(t = 0.8)))
    }

    @Test
    fun exposure_usesAFractionOnlyWhenItIsWithinFivePercent() {
        // Standard fast speeds, including the camera's rounded values, stay fractions.
        assertEquals("1/2s", ExifCaption.format(meta(t = 0.5)))
        assertEquals("1/3s", ExifCaption.format(meta(t = 1.0 / 3.0)))
        assertEquals("1/4s", ExifCaption.format(meta(t = 0.25)))
        assertEquals("1/8000s", ExifCaption.format(meta(t = 1.0 / 8000.0)))
        assertEquals("1/13s", ExifCaption.format(meta(t = 0.077)))
        // Between 1/3 and 1/2 and between 1/2 and 1 s no whole denominator is close: decimal seconds.
        assertEquals("0.4s", ExifCaption.format(meta(t = 0.4)))
        assertEquals("0.6s", ExifCaption.format(meta(t = 0.6)))
        assertEquals("0.3s", ExifCaption.format(meta(t = 0.3)))
    }

    @Test
    fun none_orOnlyInvalidValues_isNull() {
        assertNull(ExifCaption.format(CaptureMetadata.EMPTY))
        assertNull(ExifCaption.format(meta(focal = 0, f = -1.0, t = Double.NaN, iso = 0)))
        assertNull(ExifCaption.format(meta(f = Double.POSITIVE_INFINITY, t = 0.0)))
    }
}
