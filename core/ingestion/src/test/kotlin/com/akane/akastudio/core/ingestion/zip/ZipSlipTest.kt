package com.akane.akastudio.core.ingestion.zip

import com.akane.akastudio.core.ingestion.api.ZipExtractionEvent.EntryExtracted
import com.akane.akastudio.core.ingestion.api.ZipExtractionEvent.EntrySkipped
import com.akane.akastudio.core.ingestion.api.ZipLimits
import com.akane.akastudio.core.ingestion.model.IngestionError
import com.akane.akastudio.core.ingestion.model.SkipReason
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Path-escape attempts. Each slip archive starts with a valid image, so the tests also prove the failure contract:
 * the earlier, complete file stays (it belongs to the consumer) and nothing is written for the escaping entry.
 * Every check also looks outside the destination.
 */
class ZipSlipTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun TestScope.extractor() = StreamingZipExtractor(ZipLimits(), StandardTestDispatcher(testScheduler), plentyOfSpace)

    /** A fresh `<root>/dest` so anything written beside or above the destination is visible in `<root>`. */
    private fun newSandbox(): Pair<File, File> {
        val root = temp.newFolder()
        return root to File(root, "dest").apply { mkdirs() }
    }

    @Test
    fun everyEscapingNameThrowsZipSlipAndWritesNothingForIt() = runTest {
        val escapes = listOf(
            "../evil.jpg",
            "a/../../evil.jpg",
            "/abs/evil.jpg",
            "..\\evil.jpg",
            "C:/evil.jpg",
            "a/./../../evil.jpg",
            "a/..\\../evil.jpg", // mixed separators
            "\\abs\\evil.jpg",
            "inside/../evil.jpg", // any `..` segment rejects, even one that would stay inside
            "../Icon\r", // a control character never excuses real traversal
        )
        val ok = "ok.jpg" to randomBytes(2_000)
        for (name in escapes) {
            val (root, dest) = newSandbox()
            val escaping = name to randomBytes(1_000)
            val zip = archive(
                RawZip.stored(ok.first, ok.second),
                RawZip.stored(escaping.first.toByteArray(Charsets.UTF_8), escaping.second),
            )

            val error = failureOf(extractor().extract({ ByteArrayInputStream(zip) }, "archive", dest))

            assertEquals("for \"$name\"", IngestionError.ZipSlip(name), error)
            assertEquals("dest after \"$name\"", listOf("ok.jpg"), survivingEntries(dest, ok, escaping))
            assertEquals("outside dest after \"$name\"", listOf("dest", "dest/${outputNameOf("ok.jpg")}"), filesUnder(root))
        }
    }

    @Test
    fun controlCharacterNamesAreSkippedAsUnsupportedNotRejected() = runTest {
        // `Folder/Icon\r` is the custom-folder-icon file macOS Finder puts in archives.
        val names = listOf("Folder/Icon\r", "evil\u0000.jpg", "evil\n.jpg")
        val (root, dest) = newSandbox()
        val zip = archive(
            *names.map { RawZip.stored(it, randomBytes(100)) }.toTypedArray(),
            RawZip.stored("ok.jpg", randomBytes(2_000)),
        )

        val events = extractor().extract({ ByteArrayInputStream(zip) }, "archive", dest).toList()

        assertEquals(names.map { EntrySkipped(it, SkipReason.UNSUPPORTED_TYPE) }, events.dropLast(1))
        assertEquals("ok.jpg", (events.last() as EntryExtracted).source.archiveEntryName)
        assertEquals(listOf("dest", "dest/${outputNameOf("ok.jpg")}"), filesUnder(root))
    }

    @Test
    fun encodedAndLookalikeTraversalIsInertAfterFlattening() = runTest {
        val names = listOf("%2e%2e%2fevil.jpg", "%2e%2e/evil.jpg", "\u2025/evil.jpg", "\uFF0E\uFF0E/evil.jpg", "x/%2e%2e%5cevil.jpg")
        val (root, dest) = newSandbox()
        val zip = archive(*names.mapIndexed { i, name -> RawZip.stored(name, randomBytes(100, seed = i.toLong())) }.toTypedArray())

        val events = extractor().extract({ ByteArrayInputStream(zip) }, "archive", dest).toList()

        assertEquals(names, events.map { (it as EntryExtracted).source.archiveEntryName })
        for (event in events) {
            assertEquals(dest.canonicalFile, File((event as EntryExtracted).source.path).canonicalFile.parentFile)
        }
        assertEquals(names.size, filesUnder(dest).size)
        assertEquals(listOf("dest") + filesUnder(dest).map { "dest/$it" }, filesUnder(root))
    }

    @Test
    fun symlinkPlantedAtTheOutputNameIsNeverFollowed() = runTest {
        val (root, dest) = newSandbox()
        val outside = File(root, "outside.jpg").apply { writeText("precious") }
        val plantedName = ArchiveEntryRules.outputFileName("archive", "photo.jpg", "photo.jpg")
        val link = File(dest, plantedName).toPath()
        Files.createSymbolicLink(link, outside.toPath())
        val zip = archive(RawZip.stored("photo.jpg", randomBytes(1_000)))

        val error = failureOf(extractor().extract({ ByteArrayInputStream(zip) }, "archive", dest))

        assertEquals(IngestionError.ZipSlip("photo.jpg"), error)
        assertTrue("planted link is untouched", Files.isSymbolicLink(link))
        assertEquals("precious", outside.readText())
    }

    @Test
    fun danglingSymlinkInsideDestAtTheOutputNameIsNotOverwritten() = runTest {
        val (_, dest) = newSandbox()
        val plantedName = ArchiveEntryRules.outputFileName("archive", "photo.jpg", "photo.jpg")
        val link = File(dest, plantedName).toPath()
        Files.createSymbolicLink(link, File(dest, "missing-target").toPath())
        val zip = archive(RawZip.stored("photo.jpg", randomBytes(1_000)))

        val error = failureOf(extractor().extract({ ByteArrayInputStream(zip) }, "archive", dest))

        assertTrue("$error", error is IngestionError.Io)
        assertTrue("planted link is untouched", Files.isSymbolicLink(link))
        assertTrue("CREATE_NEW never created the link target", !File(dest, "missing-target").exists())
    }
}
