package com.akane.akastudio.core.ingestion.metadata

import com.akane.akastudio.core.ingestion.model.ColorSpaceTag
import com.akane.akastudio.core.ingestion.model.ImageFormat
import com.akane.akastudio.core.ingestion.model.PhotoSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColorSpaceAndSourceNamesTest {

    @Test
    fun toTag_mapsNamedColorSpaces() {
        assertEquals(ColorSpaceTag.SRGB, ColorSpaceNames.toTag("sRGB IEC61966-2.1"))
        assertEquals(ColorSpaceTag.SRGB, ColorSpaceNames.toTag("sRGB IEC61966-2.1 (Linear)"))
        assertEquals(ColorSpaceTag.SRGB, ColorSpaceNames.toTag("scRGB-nl IEC 61966-2-2:2003"))
        assertEquals(ColorSpaceTag.DISPLAY_P3, ColorSpaceNames.toTag("Display P3"))
        assertEquals(ColorSpaceTag.ADOBE_RGB, ColorSpaceNames.toTag("Adobe RGB (1998)"))
    }

    @Test
    fun toTag_otherAndUnknown() {
        assertEquals(ColorSpaceTag.OTHER, ColorSpaceNames.toTag("Rec. ITU-R BT.2020-1"))
        assertEquals(ColorSpaceTag.OTHER, ColorSpaceNames.toTag(""))
        assertEquals(ColorSpaceTag.UNKNOWN, ColorSpaceNames.toTag(null))
    }

    @Test
    fun formatFromExtension_extractedFilePrefersEntryName() {
        val source = PhotoSource.ExtractedFile("/cache/ingest/s/0001.bin", "trip/IMG_0001.DNG", "IMG_0001.DNG")
        assertEquals(ImageFormat.DNG, SourceNames.formatFromExtension(source))
    }

    @Test
    fun formatFromExtension_contentUriUsesDisplayNameThenUriPath() {
        assertEquals(
            ImageFormat.HEIC,
            SourceNames.formatFromExtension(PhotoSource.ContentUri("content://media/picker/0/1/42", "a.heic")),
        )
        assertEquals(
            ImageFormat.WEBP,
            SourceNames.formatFromExtension(PhotoSource.ContentUri("content://docs/root/b.webp?x=1", "photo")),
        )
    }

    @Test
    fun formatFromExtension_noUsableExtensionIsNull() {
        assertNull(SourceNames.formatFromExtension(PhotoSource.ContentUri("content://media/picker/0/1/42", "photo")))
        assertNull(SourceNames.formatFromExtension(PhotoSource.ContentUri("content://x/y.z/42", "anim.gif")))
    }
}
