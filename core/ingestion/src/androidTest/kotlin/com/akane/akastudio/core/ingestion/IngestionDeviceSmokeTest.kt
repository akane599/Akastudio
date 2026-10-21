package com.akane.akastudio.core.ingestion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** On-device smoke: a real session owns a fresh work dir under the target's cacheDir/ingest and deletes it on close. */
@RunWith(AndroidJUnit4::class)
class IngestionDeviceSmokeTest {

    @Test
    fun sessionCreatesAndDeletesWorkDirUnderCache() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ingestRoot = File(context.cacheDir, "ingest").canonicalFile

        val session = createIngestionSession(context)
        val workDir = session.workDir.canonicalFile
        try {
            assertTrue("work dir exists: $workDir", workDir.isDirectory)
            assertEquals(ingestRoot, workDir.parentFile)
            File(workDir, "probe.bin").writeBytes(ByteArray(16) { it.toByte() })
        } finally {
            session.close()
        }

        assertFalse("close() removes the work dir: $workDir", workDir.exists())
    }
}
