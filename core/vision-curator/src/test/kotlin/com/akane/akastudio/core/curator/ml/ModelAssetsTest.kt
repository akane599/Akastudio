package com.akane.akastudio.core.curator.ml

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelAssetsTest {

    private val modelsDir: File =
        File(System.getProperty("akastudio.modelsDir") ?: "src/main/assets/${ModelManifest.DIRECTORY}")

    @Test
    fun everyPinnedModelMatchesItsSha256AndSize() {
        assertEquals(4, ModelManifest.ALL.size)
        for (entry in ModelManifest.ALL) {
            val file = File(modelsDir, entry.fileName)
            assertTrue("missing model ${file.absolutePath}", file.isFile)
            assertEquals("size of ${entry.fileName}", entry.sizeBytes, file.length())
            assertEquals("sha256 of ${entry.fileName}", entry.sha256, sha256(file))
        }
    }

    @Test
    fun noUnpinnedModelIsShipped() {
        val shipped = modelsDir.listFiles().orEmpty()
            .filter { it.extension == "tflite" || it.extension == "task" }
            .map { it.name }
            .toSet()
        assertEquals(ModelManifest.ALL.map { it.fileName }.toSet(), shipped)
    }

    @Test
    fun assetPathsLiveUnderTheModelsDirectory() {
        assertEquals("models/nima_aesthetic_fp16.tflite", ModelManifest.NIMA_AESTHETIC.assetPath)
        assertTrue(ModelManifest.ALL.all { it.sha256.matches(Regex("[0-9a-f]{64}")) })
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
