package com.akane.akastudio.core.ingestion

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IngestionFactoryTest {
    @Test
    fun staleDirsSelectsOnlyDirectoriesOlderThanThreshold() {
        val root = Files.createTempDirectory("ingest-test").toFile()
        try {
            val old = File(root, "old").apply { mkdirs(); setLastModified(1_000L) }
            val fresh = File(root, "fresh").apply { mkdirs(); setLastModified(9_500L) }
            val file = File(root, "f.txt").apply { writeText("x"); setLastModified(1_000L) }
            val result = staleDirs(listOf(old, fresh, file), now = 10_000L, olderThan = 5_000L)
            assertEquals(listOf(old), result)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun staleDirsBoundaryIsExclusive() {
        val root = Files.createTempDirectory("ingest-test").toFile()
        try {
            val d = File(root, "d").apply { mkdirs(); setLastModified(5_000L) }
            assertEquals(emptyList<File>(), staleDirs(listOf(d), now = 10_000L, olderThan = 5_000L))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun purgeRemovesStaleSessionsUnderTheRootAndTheWholeLegacyRoot() {
        val base = Files.createTempDirectory("ingest-test").toFile()
        try {
            val root = File(base, "no_backup/ingest")
            val stale = File(root, "stale").apply { mkdirs(); File(this, "0001.bin").writeText("x") }
            stale.setLastModified(1_000L)
            val live = File(root, "live").apply { mkdirs(); setLastModified(9_500L) }
            val legacyRoot = File(base, "cache/ingest")
            // Legacy sessions go regardless of age: no current build stages there.
            val legacyFresh = File(legacyRoot, "fresh").apply { mkdirs(); setLastModified(9_500L) }

            purgeIngestionRoots(root, legacyRoot, now = 10_000L, olderThan = 5_000L)

            assertFalse("stale session purged", stale.exists())
            assertTrue("live session kept", live.isDirectory)
            assertFalse("legacy session purged: $legacyFresh", legacyFresh.exists())
            assertFalse("legacy root removed", legacyRoot.exists())
            assertTrue("current root kept", root.isDirectory)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun purgeToleratesMissingRoots() {
        val base = Files.createTempDirectory("ingest-test").toFile()
        try {
            val root = File(base, "no_backup/ingest")
            purgeIngestionRoots(root, File(base, "cache/ingest"), now = 10_000L, olderThan = 5_000L)
            assertTrue(base.listFiles().orEmpty().isEmpty())
        } finally {
            base.deleteRecursively()
        }
    }
}
