package com.akane.akastudio.core.ingestion

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IngestionFactoryTest {
    @Test
    fun orphanDirsSelectsOnlyDirectoriesModifiedBeforeTheCutoff() {
        val root = Files.createTempDirectory("ingest-test").toFile()
        try {
            val old = File(root, "old").apply { mkdirs(); setLastModified(1_000L) }
            val fresh = File(root, "fresh").apply { mkdirs(); setLastModified(9_500L) }
            val file = File(root, "f.txt").apply { writeText("x"); setLastModified(1_000L) }
            assertEquals(listOf(old), orphanDirs(listOf(old, fresh, file), cutoff = 5_000L))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun orphanDirsBoundaryIsExclusive() {
        val root = Files.createTempDirectory("ingest-test").toFile()
        try {
            val d = File(root, "d").apply { mkdirs(); setLastModified(5_000L) }
            assertEquals(emptyList<File>(), orphanDirs(listOf(d), cutoff = 5_000L))
        } finally {
            root.deleteRecursively()
        }
    }

    /** Regression: an orphan from a process killed an hour ago goes now, not after the old 24h wait. */
    @Test
    fun purgeRemovesAnHourOldOrphanAndKeepsADirModifiedAfterTheCutoff() {
        val base = Files.createTempDirectory("ingest-test").toFile()
        try {
            val root = File(base, "no_backup/ingest")
            val cutoff = 100L * HOUR
            val orphan = File(root, "orphan").apply { mkdirs(); File(this, "0001.bin").writeText("x") }
            orphan.setLastModified(cutoff - HOUR)
            val live = File(root, "live").apply { mkdirs(); setLastModified(cutoff + 1_000L) }

            purgeIngestionRoots(root, File(base, "cache/ingest"), cutoff)

            assertFalse("hour-old orphan purged", orphan.exists())
            assertTrue("dir modified after the cutoff kept", live.isDirectory)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun processStartWallMillisSubtractsTheElapsedTimeSinceStartFromNow() {
        val start = processStartWallMillis(nowWall = 1_000_000L, nowElapsed = 5_000L, startElapsed = 2_000L)
        assertEquals(997_000L, start)
    }

    @Test
    fun purgeRemovesOrphansUnderTheRootAndTheWholeLegacyRoot() {
        val base = Files.createTempDirectory("ingest-test").toFile()
        try {
            val root = File(base, "no_backup/ingest")
            val stale = File(root, "stale").apply { mkdirs(); File(this, "0001.bin").writeText("x") }
            stale.setLastModified(1_000L)
            val live = File(root, "live").apply { mkdirs(); setLastModified(9_500L) }
            val legacyRoot = File(base, "cache/ingest")
            // Legacy sessions go regardless of age: no current build stages there.
            val legacyFresh = File(legacyRoot, "fresh").apply { mkdirs(); setLastModified(9_500L) }

            purgeIngestionRoots(root, legacyRoot, cutoff = 5_000L)

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
            purgeIngestionRoots(root, File(base, "cache/ingest"), cutoff = 5_000L)
            assertTrue(base.listFiles().orEmpty().isEmpty())
        } finally {
            base.deleteRecursively()
        }
    }

    private companion object {
        const val HOUR = 60L * 60 * 1000
    }
}
