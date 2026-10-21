package com.akane.akastudio.core.ingestion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device smoke: a real session owns a fresh work dir under the target's noBackupFilesDir/ingest (not the evictable
 * cacheDir) and deletes it on close; the startup purge sweeps stale sessions there and the legacy cacheDir/ingest root.
 */
@RunWith(AndroidJUnit4::class)
class IngestionDeviceSmokeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun sessionCreatesAndDeletesWorkDirUnderNoBackupFiles() {
        val ingestRoot = File(context.noBackupFilesDir, "ingest").canonicalFile

        val session = createIngestionSession(context)
        val workDir = session.workDir.canonicalFile
        try {
            assertTrue("work dir exists: $workDir", workDir.isDirectory)
            assertEquals(ingestRoot, workDir.parentFile)
            assertFalse(
                "work dir is not in the evictable cacheDir: $workDir",
                workDir.path.startsWith(context.cacheDir.canonicalPath + "/"),
            )
            File(workDir, "probe.bin").writeBytes(ByteArray(16) { it.toByte() })
        } finally {
            session.close()
        }

        assertFalse("close() removes the work dir: $workDir", workDir.exists())
    }

    @Test
    fun purgeRemovesStaleSessionsAndTheLegacyCacheRoot() {
        val stale = File(context.noBackupFilesDir, "ingest/qa-stale").apply { mkdirs() }
        File(stale, "0001.bin").writeBytes(ByteArray(8))
        assertTrue(stale.setLastModified(System.currentTimeMillis() - 2 * DAY_MILLIS))
        val legacyRoot = File(context.cacheDir, "ingest")
        val legacySession = File(legacyRoot, "qa-legacy").apply { mkdirs() }
        File(legacySession, "0001.bin").writeBytes(ByteArray(8))
        val live = createIngestionSession(context)
        try {
            purgeStaleIngestionDirs(context)

            assertFalse("stale session purged: $stale", stale.exists())
            assertFalse("legacy cacheDir/ingest root purged: $legacyRoot", legacyRoot.exists())
            assertTrue("live session kept: ${live.workDir}", live.workDir.isDirectory)
        } finally {
            live.close()
            stale.deleteRecursively()
            legacyRoot.deleteRecursively()
        }
    }

    private companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
