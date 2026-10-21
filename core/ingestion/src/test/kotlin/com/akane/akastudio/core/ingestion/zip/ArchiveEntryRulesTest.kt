package com.akane.akastudio.core.ingestion.zip

import com.akane.akastudio.core.ingestion.api.ZipLimits
import com.akane.akastudio.core.ingestion.model.ImageFormat
import com.akane.akastudio.core.ingestion.model.IngestionError
import com.akane.akastudio.core.ingestion.model.SkipReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveEntryRulesTest {
    private val limits = ZipLimits()

    @Test
    fun nameViolation_rejectsEveryEscapeShapeAsZipSlip() {
        val escapes = listOf(
            "../evil.jpg",
            "a/../../evil.jpg",
            "a/./../../evil.jpg",
            "..\\evil.jpg",
            "a\\..\\..\\evil.jpg",
            "a/..\\../evil.jpg",
            "..",
            "a/..",
            "/abs/evil.jpg",
            "\\abs\\evil.jpg",
            "//server/share/evil.jpg",
            "C:/evil.jpg",
            "c:evil.jpg",
            "C:\\evil.jpg",
            "../Icon\r", // a control character never excuses real traversal
            "a".repeat(limits.maxNameLength + 1),
        )
        for (name in escapes) {
            assertEquals("for \"$name\"", IngestionError.ZipSlip(name), ArchiveEntryRules.nameViolation(name, limits))
        }
    }

    @Test
    fun controlCharactersAreNotAnEscapeButTheEntryIsSkippedAsUnsupported() {
        val names = listOf("Folder/Icon\r", "evil\u0000.jpg", "evil\n.jpg", "evil\u007f.jpg", "evil\u0085.jpg")
        for (name in names) {
            assertNull("nameViolation for \"$name\"", ArchiveEntryRules.nameViolation(name, limits))
            assertEquals(
                "classify \"$name\"",
                EntryClassification.Skip(SkipReason.UNSUPPORTED_TYPE),
                ArchiveEntryRules.classify(name, isDirectory = false),
            )
        }
    }

    @Test
    fun displayNameStripsUnicodeFormatCharactersOnly() {
        assertEquals("xgpj.exe.jpg", ArchiveEntryRules.displayName("x\u202Egpj.exe.jpg")) // right-to-left override
        assertEquals("ab.jpg", ArchiveEntryRules.displayName("a\u200B\u2066b\uFEFF.jpg")) // ZWSP, isolate, BOM
        assertEquals("tag.jpg", ArchiveEntryRules.displayName("tag\uDB40\uDC41.jpg")) // U+E0041, a supplementary Cf
        assertEquals("café 📷.jpg", ArchiveEntryRules.displayName("café 📷.jpg"))
    }

    @Test
    fun nameViolation_acceptsOrdinaryAndLookalikeNames() {
        val acceptable = listOf(
            "photo.jpg",
            "a/b/c/photo.jpg",
            "a/./photo.jpg",
            "..photo.jpg",
            "photo..jpg",
            "a/...b/photo.jpg",
            "%2e%2e/evil.jpg",
            "\u2025/evil.jpg", // two dot leader
            "\uFF0E\uFF0E/evil.jpg", // fullwidth full stops
            "ab:c.jpg", // only a single-letter drive prefix is rejected
            "a".repeat(limits.maxNameLength),
        )
        for (name in acceptable) assertNull("for \"$name\"", ArchiveEntryRules.nameViolation(name, limits))
    }

    @Test
    fun nameViolation_depthBeyondLimitIsLimitExceeded() {
        val atLimit = (1 until limits.maxPathDepth).joinToString("/") { "d$it" } + "/x.jpg"
        val beyondLimit = "d0/$atLimit"
        assertNull(ArchiveEntryRules.nameViolation(atLimit, limits))
        assertTrue(ArchiveEntryRules.nameViolation(beyondLimit, limits) is IngestionError.LimitExceeded)
    }

    @Test
    fun classify_mapsEachEntryKindToItsSkipReason() {
        val expected = mapOf(
            "dir/" to SkipReason.DIRECTORY,
            "dir\\" to SkipReason.DIRECTORY,
            "__MACOSX/._a.jpg" to SkipReason.JUNK_FILE,
            "__macosx/a.jpg" to SkipReason.JUNK_FILE,
            ".DS_Store" to SkipReason.JUNK_FILE,
            "Thumbs.db" to SkipReason.JUNK_FILE,
            "sub/desktop.ini" to SkipReason.JUNK_FILE,
            "dir/._c.jpg" to SkipReason.JUNK_FILE,
            ".hidden/b.jpg" to SkipReason.HIDDEN_FILE,
            "dir/.b.jpg" to SkipReason.HIDDEN_FILE,
            "notes.txt" to SkipReason.UNSUPPORTED_TYPE,
            "x.gif" to SkipReason.UNSUPPORTED_TYPE,
            "noextension" to SkipReason.UNSUPPORTED_TYPE,
            "photo.jpg.zip" to SkipReason.UNSUPPORTED_TYPE,
        )
        for ((name, reason) in expected) {
            assertEquals("for \"$name\"", EntryClassification.Skip(reason), ArchiveEntryRules.classify(name, isDirectory = false))
        }
        assertEquals(
            EntryClassification.Skip(SkipReason.DIRECTORY),
            ArchiveEntryRules.classify("looks-like-a.jpg", isDirectory = true),
        )
    }

    @Test
    fun classify_supportedImagesKeepTheirOriginalFileName() {
        assertEquals(EntryClassification.Image(ImageFormat.JPEG, "IMG 1.JPG"), ArchiveEntryRules.classify("a/b/IMG 1.JPG", false))
        assertEquals(EntryClassification.Image(ImageFormat.DNG, "raw.dng"), ArchiveEntryRules.classify("a\\raw.dng", false))
        assertEquals(EntryClassification.Image(ImageFormat.HEIC, "x.heic"), ArchiveEntryRules.classify("./x.heic", false))
    }

    @Test
    fun sanitizeFileName_keepsOnlySafeCharacters() {
        assertEquals("IMG_0001.jpg", ArchiveEntryRules.sanitizeFileName("IMG_0001.jpg"))
        assertEquals("caf__photo_1_.jpg", ArchiveEntryRules.sanitizeFileName("café photo(1).jpg"))
        assertEquals("__evil.jpg", ArchiveEntryRules.sanitizeFileName("\uFF0E\uFF0Eevil.jpg"))
    }

    @Test
    fun sanitizeFileName_truncatesTo96CharsKeepingTheExtension() {
        val sanitized = ArchiveEntryRules.sanitizeFileName("x".repeat(300) + ".jpeg")
        assertEquals(96, sanitized.length)
        assertTrue(sanitized.endsWith("x.jpeg"))
    }

    @Test
    fun outputFileName_isHashPrefixedAndDistinctPerEntry() {
        val first = ArchiveEntryRules.outputFileName("archive-1", "a/photo.jpg", "photo.jpg")
        val second = ArchiveEntryRules.outputFileName("archive-1", "b/photo.jpg", "photo.jpg")
        val otherArchive = ArchiveEntryRules.outputFileName("archive-2", "a/photo.jpg", "photo.jpg")
        assertTrue(first, first.matches(Regex("[0-9a-f]{16}_photo\\.jpg")))
        assertNotEquals(first, second)
        assertNotEquals(first, otherArchive)
        assertEquals(first, ArchiveEntryRules.outputFileName("archive-1", "a/photo.jpg", "photo.jpg"))
    }
}
