package com.akane.akastudio.core.ingestion.picker

import com.akane.akastudio.core.ingestion.model.ImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PickerItemsTest {

    @Test
    fun dedupe_dropsRepeatsAndKeepsFirstSeenOrder() {
        val uris = listOf("content://m/3", "content://m/1", "content://m/3", "content://m/2", "content://m/1")

        assertEquals(listOf("content://m/3", "content://m/1", "content://m/2"), PickerItems.dedupe(uris))
    }

    @Test
    fun dedupe_emptyListStaysEmpty() {
        assertEquals(emptyList<String>(), PickerItems.dedupe(emptyList()))
    }

    @Test
    fun displayNameFallback_usesLastPathSegment() {
        assertEquals(
            "1000000034",
            PickerItems.displayNameFallback("content://media/picker/0/com.android.providers.media.photopicker/media/1000000034"),
        )
        assertEquals("IMG_1.jpg", PickerItems.displayNameFallback("file:///sdcard/DCIM/IMG_1.jpg"))
    }

    @Test
    fun displayNameFallback_ignoresQueryFragmentAndTrailingSlash() {
        assertEquals("IMG_2.png", PickerItems.displayNameFallback("content://docs/tree/IMG_2.png?x=1#frag"))
        assertEquals("album", PickerItems.displayNameFallback("content://docs/album/"))
    }

    @Test
    fun displayNameFallback_withoutPathIsPhoto() {
        assertEquals("photo", PickerItems.displayNameFallback("content://media"))
        assertEquals("photo", PickerItems.displayNameFallback("content://media/"))
        assertEquals("photo", PickerItems.displayNameFallback(""))
    }

    @Test
    fun formatFromDisplayName_readsExtensionCaseInsensitively() {
        assertEquals(ImageFormat.JPEG, PickerItems.formatFromDisplayName("IMG_1.JPG"))
        assertEquals(ImageFormat.HEIC, PickerItems.formatFromDisplayName("shot.final.heic"))
        assertNull(PickerItems.formatFromDisplayName("animation.gif"))
        assertNull(PickerItems.formatFromDisplayName("1000000034"))
    }
}
