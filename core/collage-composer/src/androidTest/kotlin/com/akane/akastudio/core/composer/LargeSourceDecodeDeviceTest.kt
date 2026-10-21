package com.akane.akastudio.core.composer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akane.akastudio.core.ingestion.decode.FullResolutionDecoder
import com.akane.akastudio.core.ingestion.decode.SampledAnalysisDecoder
import com.akane.akastudio.core.ingestion.model.CaptureMetadata
import com.akane.akastudio.core.ingestion.model.ImageFormat
import com.akane.akastudio.core.ingestion.model.PhotoCandidate
import com.akane.akastudio.core.ingestion.model.PhotoSource
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The composer's inputs come from two decodes of each chosen photo: the subsampled analysis decode that feeds the
 * horizon detector (1024 px) and the bounded full-resolution decode the renderer draws (3072 px collage budget).
 * On a 6000×4000 JPEG written on the device, both must decode straight to their bound without materialising the
 * full-resolution image, with the Java heap untouched by pixels. A heap sampler records the peaks; its negative
 * control is an unsampled BitmapFactory decode, which must show the full 96 MB allocation the bounded decodes avoid.
 */
@RunWith(AndroidJUnit4::class)
class LargeSourceDecodeDeviceTest {

    @Test
    fun analysisDecodeSubsamplesWithoutTheFullResolutionAllocation() {
        val (bitmap, peak) = sampled { runBlocking { SampledAnalysisDecoder({ error("unused") }, resolver).decode(source, 1024) } }
        DeviceRender.log("analysis decode -> ${bitmap.width}x${bitmap.height} ${bitmap.config}; $peak")
        assertEquals(1024, bitmap.width)
        assertEquals(683, bitmap.height)
        assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
        assertTrue("analysis decode native peak ${peak.nativeMb} MB stays far below the $FULL_8888_MB MB full decode", peak.nativeBytes < FULL_8888_BYTES / 4)
        assertTrue("analysis decode Java heap peak ${peak.javaMb} MB stays bounded", peak.javaBytes < JAVA_BOUND_BYTES)
        bitmap.recycle()
    }

    @Test
    fun fullResolutionDecodeIsBoundedToTheCollageEdge() {
        val candidate = PhotoCandidate("big", source, ImageFormat.JPEG, file.length(), WIDTH, HEIGHT, CaptureMetadata.EMPTY)
        val (decoded, peak) = sampled { runBlocking { FullResolutionDecoder(resolver).decode(candidate, COLLAGE_EDGE) } }
        val bitmap = decoded.bitmap
        val outBytes = bitmap.allocationByteCount.toLong()
        DeviceRender.log("full-res decode -> ${bitmap.width}x${bitmap.height} ${bitmap.config} (${outBytes / MB} MB) from ${decoded.sourceWidth}x${decoded.sourceHeight}; $peak")
        assertEquals(WIDTH, decoded.sourceWidth)
        assertEquals(HEIGHT, decoded.sourceHeight)
        assertEquals(COLLAGE_EDGE, bitmap.width)
        assertEquals(2048, bitmap.height)
        assertEquals(Bitmap.Config.RGBA_F16, bitmap.config)
        // A full-resolution RGBA_F16 decode needs 183 MiB; a sampled decode to the bound stays under the full 8888 size
        // (sample size 2 measures about 47 MiB here). Failing on this means the full-resolution image was materialised.
        assertTrue(
            "full-res decode native peak ${peak.nativeMb} MB stays below the $FULL_8888_MB MB full-resolution 8888 decode",
            peak.nativeBytes < FULL_8888_BYTES,
        )
        assertTrue("full-res decode Java heap peak ${peak.javaMb} MB stays bounded", peak.javaBytes < JAVA_BOUND_BYTES)
        bitmap.recycle()
    }

    @Test
    fun negativeControlUnsampledDecodeShowsTheFullAllocation() {
        val (bitmap, peak) = sampled { BitmapFactory.decodeFile(file.path) }
        DeviceRender.log("unsampled BitmapFactory -> ${bitmap.width}x${bitmap.height}; $peak")
        assertEquals(WIDTH, bitmap.width)
        assertTrue("sampler sees the full 8888 allocation: ${peak.nativeMb} MB", peak.nativeBytes >= FULL_8888_BYTES * 9 / 10)
        bitmap.recycle()
    }

    private class Peak(val nativeBytes: Long, val javaBytes: Long, val samples: Int) {
        val nativeMb get() = nativeBytes / MB
        val javaMb get() = javaBytes / MB
        override fun toString() = "peak delta native=${nativeBytes / 1024} KiB java=${javaBytes / 1024} KiB ($samples samples)"
    }

    /** Runs [block] while a thread samples native and Java heap use every millisecond; returns the peak growth. */
    private fun <T> sampled(block: () -> T): Pair<T, Peak> {
        settle()
        val runtime = Runtime.getRuntime()
        val nativeBase = Debug.getNativeHeapAllocatedSize()
        val javaBase = runtime.totalMemory() - runtime.freeMemory()
        val nativePeak = AtomicLong(nativeBase)
        val javaPeak = AtomicLong(javaBase)
        val running = AtomicBoolean(true)
        var samples = 0
        val sampler = Thread {
            while (running.get()) {
                nativePeak.accumulateAndGet(Debug.getNativeHeapAllocatedSize()) { a, b -> maxOf(a, b) }
                javaPeak.accumulateAndGet(runtime.totalMemory() - runtime.freeMemory()) { a, b -> maxOf(a, b) }
                samples++
                Thread.sleep(1)
            }
        }.apply { start() }
        val result = try {
            block()
        } finally {
            nativePeak.accumulateAndGet(Debug.getNativeHeapAllocatedSize()) { a, b -> maxOf(a, b) }
            javaPeak.accumulateAndGet(runtime.totalMemory() - runtime.freeMemory()) { a, b -> maxOf(a, b) }
            running.set(false)
            sampler.join()
        }
        return result to Peak(nativePeak.get() - nativeBase, javaPeak.get() - javaBase, samples)
    }

    companion object {
        private const val WIDTH = 6000
        private const val HEIGHT = 4000
        private const val COLLAGE_EDGE = 3072
        private const val MB = 1024L * 1024L
        private const val FULL_8888_BYTES = 6000L * 4000L * 4L
        private const val FULL_8888_MB = FULL_8888_BYTES / MB
        private const val JAVA_BOUND_BYTES = 16 * MB

        private val resolver get() = DeviceRender.context.contentResolver
        private lateinit var file: File
        private val source get() = PhotoSource.ExtractedFile(file.path, "big.jpg", "big.jpg")

        /** Writes a 6000×4000 gradient JPEG on the device. */
        @BeforeClass
        @JvmStatic
        fun writeLargeJpeg() {
            file = File(DeviceRender.context.cacheDir, "sq60-6000x4000.jpg")
            val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
            val paint = Paint().apply {
                shader = LinearGradient(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), 0xFF2050A0.toInt(), 0xFFE0A040.toInt(), Shader.TileMode.CLAMP)
            }
            Canvas(bitmap).drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            check(bounds.outWidth == WIDTH && bounds.outHeight == HEIGHT) { "wrote ${bounds.outWidth}x${bounds.outHeight}" }
            DeviceRender.log("wrote ${file.length() / 1024} KiB ${WIDTH}x$HEIGHT JPEG")
        }

        @AfterClass
        @JvmStatic
        fun deleteLargeJpeg() {
            if (::file.isInitialized) file.delete()
        }

        private fun settle() {
            repeat(3) {
                Runtime.getRuntime().gc()
                System.runFinalization()
                Thread.sleep(50)
            }
        }
    }
}
