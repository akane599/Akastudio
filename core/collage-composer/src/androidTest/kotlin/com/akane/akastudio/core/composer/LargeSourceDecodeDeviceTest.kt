package com.akane.akastudio.core.composer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
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
 * horizon detector (1024 px) and the bounded full-resolution decode the renderer draws (up to the 3072 px collage
 * budget). On a 6000×4000 JPEG written on the device, a decode whose target is at most half the source must sample
 * instead of materialising the full-resolution image, with the Java heap untouched by pixels. The full-resolution
 * decoder lets the codec subsample by a power of two in its native 8-bit config, then converts to linear RGBA_F16 in
 * bands while doing the remaining (< 2:1) downscale on linear values, so no full-size F16 copy exists. A heap sampler
 * records the peaks; its negative controls are an unsampled BitmapFactory decode (96 MB) and the full-resolution
 * RGBA_F16 decode (183 MiB) that the codec's own F16 path would allocate.
 */
@RunWith(AndroidJUnit4::class)
class LargeSourceDecodeDeviceTest {

    @Test
    fun analysisDecodeSubsamplesWithoutTheFullResolutionAllocation() {
        val (bitmap, peak) = sampled { runBlocking { SampledAnalysisDecoder(resolver).decode(source, 1024) } }
        DeviceRender.log("analysis decode -> ${bitmap.width}x${bitmap.height} ${bitmap.config}; $peak")
        assertEquals(1024, bitmap.width)
        assertEquals(683, bitmap.height)
        assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
        assertTrue("analysis decode native peak ${peak.nativeMb} MB stays far below the $FULL_8888_MB MB full decode", peak.nativeBytes < FULL_8888_BYTES / 4)
        assertTrue("analysis decode Java heap peak ${peak.javaMb} MB stays bounded", peak.javaBytes < JAVA_BOUND_BYTES)
        bitmap.recycle()
    }

    @Test
    fun fullResolutionDecodeAtMostHalfTheSourceSamplesInsteadOfDecodingFullSize() {
        val (bitmap, peak) = fullResolutionDecode(2048)
        assertEquals(2048, bitmap.width)
        assertEquals(1365, bitmap.height)
        // Factor 2.93: sample size 2 decodes 3000×2000 ARGB_8888 (22.9 MiB); the 1.46 linear downscale adds the
        // 2048×1365 RGBA_F16 output (21.3 MiB) and one band of F16 source rows (≤ 194 rows × 3000 px, 4.4 MiB):
        // about 49 MiB, against 183 MiB for the full-resolution F16 decode.
        val expected = 3000L * 2000 * ARGB_BYTES + 2048L * 1365 * F16_BYTES + 194L * 3000 * F16_BYTES
        assertTrue(
            "2048 decode native peak ${peak.nativeMb} MiB is near the sampled ${expected / MB} MiB, not the full ${FULL_F16_BYTES / MB} MiB",
            peak.nativeBytes <= expected * 11 / 10,
        )
        assertTrue("2048 decode Java heap peak ${peak.javaMb} MiB stays bounded", peak.javaBytes < JAVA_BOUND_BYTES)
        bitmap.recycle()
    }

    @Test
    fun fullResolutionDecodeAtTheCollageEdgeStaysWithinTheDocumentedBound() {
        val (bitmap, peak) = fullResolutionDecode(COLLAGE_EDGE)
        assertEquals(COLLAGE_EDGE, bitmap.width)
        assertEquals(2048, bitmap.height)
        // Factor 6000 / 3072 = 1.95 < 2, so no power-of-two sample size keeps the decode at least as large as the
        // target, and anything smaller would have to be upscaled, which the 16-bit pipeline never does. The full
        // 6000×4000 ARGB_8888 decode (91.6 MiB) is therefore inherent; the linear downscale adds the 3072×2048 RGBA_F16
        // output (48 MiB) and one band of F16 source rows (≤ 256 rows × 6000 px, 11.7 MiB): about 151 MiB (151.3 MiB
        // measured on SM-S938B, API 37), asserted with 10% headroom. The codec's own full-size F16 intermediate
        // (183 MiB + output) would exceed it.
        assertTrue(
            "3072 decode native peak ${peak.nativeMb} MiB stays within full-res 8888 + output + one band (${COLLAGE_EDGE_BOUND / MB} MiB)",
            peak.nativeBytes <= COLLAGE_EDGE_BOUND,
        )
        assertTrue("3072 decode Java heap peak ${peak.javaMb} MiB stays bounded", peak.javaBytes < JAVA_BOUND_BYTES)
        bitmap.recycle()
    }

    @Test
    fun negativeControlFullResolutionF16DecodeShowsTheBaselineSamplingAvoids() {
        val (bitmap, peak) = sampled {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB))
            }
        }
        DeviceRender.log("full-resolution F16 baseline -> ${bitmap.width}x${bitmap.height} ${bitmap.config}; $peak")
        assertEquals(WIDTH, bitmap.width)
        assertEquals(Bitmap.Config.RGBA_F16, bitmap.config)
        assertTrue("sampler sees the full ${FULL_F16_BYTES / MB} MiB F16 allocation: ${peak.nativeMb} MiB", peak.nativeBytes >= FULL_F16_BYTES * 9 / 10)
        bitmap.recycle()
    }

    private fun fullResolutionDecode(maxLongEdge: Int): Pair<Bitmap, Peak> {
        val candidate = PhotoCandidate("big", source, ImageFormat.JPEG, file.length(), WIDTH, HEIGHT, CaptureMetadata.EMPTY)
        val (decoded, peak) = sampled { runBlocking { FullResolutionDecoder(resolver).decode(candidate, maxLongEdge) } }
        val bitmap = decoded.bitmap
        DeviceRender.log(
            "full-res decode at $maxLongEdge -> ${bitmap.width}x${bitmap.height} ${bitmap.config} " +
                "(${bitmap.allocationByteCount / 1024} KiB) from ${decoded.sourceWidth}x${decoded.sourceHeight}; $peak",
        )
        assertEquals(WIDTH, decoded.sourceWidth)
        assertEquals(HEIGHT, decoded.sourceHeight)
        assertEquals(Bitmap.Config.RGBA_F16, bitmap.config)
        assertEquals(ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB), bitmap.colorSpace)
        return bitmap to peak
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
        private const val F16_BYTES = 8L
        private const val ARGB_BYTES = 4L
        private const val FULL_F16_BYTES = 6000L * 4000L * F16_BYTES
        private const val COLLAGE_EDGE_BOUND = (FULL_8888_BYTES + 3072L * 2048 * F16_BYTES + 256L * 6000 * F16_BYTES) * 11 / 10
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
