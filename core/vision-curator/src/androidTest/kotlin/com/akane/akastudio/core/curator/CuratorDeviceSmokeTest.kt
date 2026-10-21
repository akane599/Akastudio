package com.akane.akastudio.core.curator

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.akane.akastudio.core.curator.ml.ModelManifest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device smoke: every pinned model opens through AssetManager as a file descriptor at its manifest size.
 * `openFd` only succeeds for stored (uncompressed) assets, which is what MediaPipe and LiteRT need to mmap them.
 */
@RunWith(AndroidJUnit4::class)
class CuratorDeviceSmokeTest {

    @Test
    fun bundledModelsAreStoredAndComplete() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        for (entry in ModelManifest.ALL) {
            assets.openFd(entry.assetPath).use { fd ->
                assertEquals(entry.assetPath, entry.sizeBytes, fd.length)
            }
        }
    }
}
