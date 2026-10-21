package com.akane.akastudio.core.ingestion

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
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
}
