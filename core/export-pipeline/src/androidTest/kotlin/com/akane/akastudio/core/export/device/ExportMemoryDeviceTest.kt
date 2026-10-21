package com.akane.akastudio.core.export.device

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.akane.akastudio.core.export.api.ExportFormat
import com.akane.akastudio.core.export.api.ExportResolution
import com.akane.akastudio.core.export.pipeline.ExportProcessor
import com.akane.akastudio.core.export.store.MediaStoreSink
import com.akane.akastudio.core.export.store.PlatformWebpLosslessEncoder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.max

/** Peak PSS of a 4K PNG16 export through the production sink (SQ-43 saw 1.16 GB PSS for the whole app). */
@RunWith(AndroidJUnit4::class)
class ExportMemoryDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val qa = MediaStoreQa(context)

    @After
    fun deleteRows() {
        assertEquals("rows left behind in ${qa.relativePath}", 0, qa.cleanUp())
        assertFalse("run folder left behind: ${qa.relativePath}", qa.runFolderExists())
    }

    @Test
    fun fourKPng16ExportPeakPss() {
        val exporter = ExportProcessor(SyntheticRenderer(gradient(peak = 1f)), MediaStoreSink(context), ultraHdr = null, webpLossless = PlatformWebpLosslessEncoder())
        val request = exportRequest(ExportFormat.PNG_16, ExportResolution.UHD_4K)
        settle()
        val beforeKb = Debug.getPss()
        val runtime = Runtime.getRuntime()
        val javaBefore = runtime.totalMemory() - runtime.freeMemory()

        val running = AtomicBoolean(true)
        val peak = AtomicLong(beforeKb)
        val javaPeakBytes = AtomicLong(javaBefore)
        val sampleCount = AtomicInteger()
        val sampler = thread(name = "pss-sampler") {
            while (running.get()) {
                peak.accumulateAndGet(Debug.getPss()) { a, b -> max(a, b) }
                javaPeakBytes.accumulateAndGet(runtime.totalMemory() - runtime.freeMemory()) { a, b -> max(a, b) }
                sampleCount.incrementAndGet()
                Thread.sleep(SAMPLE_MILLIS)
            }
        }
        val started = System.nanoTime()
        val result = try {
            exporter.exportBlocking(request)
        } finally {
            running.set(false)
            sampler.join()
        }
        val peakKb = peak.get()
        val javaPeak = javaPeakBytes.get()
        val samples = sampleCount.get()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        val uri = Uri.parse(result.uri)
        qa.track(uri)
        val afterKb = Debug.getPss()
        settle()
        val afterGcKb = Debug.getPss()

        val deltaMb = (peakKb - beforeKb) / 1024
        record(
            "memory.png16.uhd",
            "pssBeforeKb=$beforeKb pssPeakKb=$peakKb pssAfterKb=$afterKb pssAfterGcKb=$afterGcKb peakDeltaMb=$deltaMb " +
                "javaBeforeMb=${javaBefore shr 20} javaPeakMb=${javaPeak shr 20} samples=$samples elapsedMs=$elapsedMs bytes=${result.byteSize}",
        )
        assertEquals(2160, result.width)
        assertEquals(3840, result.height)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        assertEquals(2160, bounds.outWidth)
        assertEquals(3840, bounds.outHeight)
        assertTrue("no PSS samples were taken during the export", samples > 0)
        assertTrue("4K PNG16 export grew PSS by $deltaMb MB (budget $BUDGET_MB MB)", deltaMb < BUDGET_MB)
        assertEquals(1, qa.moveIntoRunFolder(uri))
    }

    private fun settle() {
        repeat(2) {
            Runtime.getRuntime().gc()
            System.runFinalization()
        }
        Thread.sleep(200)
    }

    private companion object {
        const val SAMPLE_MILLIS = 20L

        /**
         * The PNG16 path holds one 2160x3840 half-float frame (63 MiB) plus two rows and the deflater; the synthetic
         * renderer allocates that same frame. Four frames' worth leaves room for GC slack without hiding a regression to
         * a second full-frame copy per stage.
         */
        const val BUDGET_MB = 256
    }
}
