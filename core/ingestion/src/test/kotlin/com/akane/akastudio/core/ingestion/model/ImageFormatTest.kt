package com.akane.akastudio.core.ingestion.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageFormatTest {

    @Test
    fun fromExtension_isCaseInsensitive() {
        assertEquals(ImageFormat.JPEG, ImageFormat.fromExtension("JPG"))
        assertEquals(ImageFormat.JPEG, ImageFormat.fromExtension("Jpeg"))
        assertEquals(ImageFormat.JPEG, ImageFormat.fromExtension("jpg"))
        assertEquals(ImageFormat.HEIF, ImageFormat.fromExtension("heif"))
        assertEquals(ImageFormat.HEIC, ImageFormat.fromExtension("HEIC"))
        assertEquals(ImageFormat.DNG, ImageFormat.fromExtension("DNG"))
        assertEquals(ImageFormat.PNG, ImageFormat.fromExtension("Png"))
        assertEquals(ImageFormat.WEBP, ImageFormat.fromExtension("webP"))
    }

    @Test
    fun fromExtension_unknownIsNull() {
        assertNull(ImageFormat.fromExtension("gif"))
        assertNull(ImageFormat.fromExtension(""))
        assertNull(ImageFormat.fromExtension("tiff"))
    }

    @Test
    fun fromMimeType_mapsEveryListedType() {
        val expected = mapOf(
            "image/jpeg" to ImageFormat.JPEG,
            "image/jpg" to ImageFormat.JPEG,
            "image/png" to ImageFormat.PNG,
            "image/heic" to ImageFormat.HEIC,
            "image/heic-sequence" to ImageFormat.HEIC,
            "image/heif" to ImageFormat.HEIF,
            "image/heif-sequence" to ImageFormat.HEIF,
            "image/webp" to ImageFormat.WEBP,
            "image/x-adobe-dng" to ImageFormat.DNG,
            "image/dng" to ImageFormat.DNG,
        )
        expected.forEach { (mime, format) -> assertEquals(mime, format, ImageFormat.fromMimeType(mime)) }
    }

    @Test
    fun fromMimeType_nullOrUnsupportedIsNull() {
        assertNull(ImageFormat.fromMimeType(null))
        assertNull(ImageFormat.fromMimeType("image/gif"))
    }
}
