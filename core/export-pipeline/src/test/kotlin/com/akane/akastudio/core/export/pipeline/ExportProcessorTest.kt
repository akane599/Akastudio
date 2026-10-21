package com.akane.akastudio.core.export.pipeline

import com.akane.akastudio.core.color.api.HalfFloatImage
import com.akane.akastudio.core.color.api.ImagePixels
import com.akane.akastudio.core.color.api.PreviewOptions
import com.akane.akastudio.core.color.api.RenderScene
import com.akane.akastudio.core.color.api.SceneBackground
import com.akane.akastudio.core.color.api.StoryRenderer
import com.akane.akastudio.core.export.api.ExportFormat
import com.akane.akastudio.core.export.api.ExportMetadata
import com.akane.akastudio.core.export.api.ExportPlan
import com.akane.akastudio.core.export.api.ExportProgress
import com.akane.akastudio.core.export.api.ExportRequest
import com.akane.akastudio.core.export.api.ExportResolution
import com.akane.akastudio.core.export.api.ExportResult
import com.akane.akastudio.core.export.api.ExportStage
import com.akane.akastudio.core.export.encode.ExifFields
import com.akane.akastudio.core.export.encode.ExifWriter
import com.akane.akastudio.core.export.encode.JpegEncoder
import com.akane.akastudio.core.export.encode.Rgba8Rows
import com.akane.akastudio.core.export.encode.SrgbIccProfile
import com.akane.akastudio.core.export.encode.WebpContainer
import com.akane.akastudio.core.export.process.Dither
import com.akane.akastudio.core.export.process.GainMapMath
import com.akane.akastudio.core.export.process.GainMapResult
import com.akane.akastudio.core.export.process.Half
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

class ExportProcessorTest {

    private val captureMillis = Instant.parse("2026-07-14T09:30:00Z").toEpochMilli()

    @Test
    fun png16_writesDecodable16BitPng_stagesInOrder_byteSizeMatchesWrittenBytes() {
        val run = export(ExportFormat.PNG_16)
        assertEquals(listOf(ExportStage.RENDER, ExportStage.ENCODE, ExportStage.WRITE), run.stages)
        assertFile(run, "image/png", "PNG_16", "png", 1080, 1920)
        val decoded = ImageIO.read(ByteArrayInputStream(run.bytes))
        assertEquals(1080, decoded.width)
        assertEquals(1920, decoded.height)
        assertEquals(16, decoded.colorModel.getComponentSize(0))
        assertEquals(captureMillis, run.sink.dateTaken)
    }

    @Test
    fun png8_writesDecodableOpaque8BitPng() {
        val run = export(ExportFormat.PNG_8)
        assertEquals(listOf(ExportStage.RENDER, ExportStage.ENCODE, ExportStage.WRITE), run.stages)
        assertFile(run, "image/png", "PNG_8", "png", 1080, 1920)
        val decoded = ImageIO.read(ByteArrayInputStream(run.bytes))
        assertEquals(1080, decoded.width)
        assertEquals(1920, decoded.height)
        assertEquals(8, decoded.colorModel.getComponentSize(0))
        assertFalse(decoded.colorModel.hasAlpha())
    }

    @Test
    fun webpLossless_dithersIntoEncoder_addsIccAndExif() {
        val webp = FakeWebpEncoder()
        val run = export(ExportFormat.WEBP_LOSSLESS, webpEncoder = webp)
        assertEquals(listOf(ExportStage.RENDER, ExportStage.DITHER, ExportStage.ENCODE, ExportStage.WRITE), run.stages)
        assertFile(run, "image/webp", "WEBP_LOSSLESS", "webp", 1080, 1920)
        assertEquals(1920, webp.rowsPulled)
        assertEquals(1080 to 1920, webp.size)
        assertEquals("RIFF", String(run.bytes, 0, 4, Charsets.ISO_8859_1))
        assertEquals("WEBP", String(run.bytes, 8, 4, Charsets.ISO_8859_1))
        assertEquals(listOf("VP8X", "ICCP", "VP8L", "EXIF"), riffChunks(run.bytes))
    }

    @Test
    fun socialStoryJpegAtHd_renders2160x3840_outputs1080x1920() {
        val renderer = FakeRenderer()
        val run = export(ExportFormat.SOCIAL_STORY_JPEG, renderer = renderer)
        assertEquals(2160 to 3840, renderer.lastCanvas)
        assertEquals(
            listOf(ExportStage.RENDER, ExportStage.RESAMPLE, ExportStage.SHARPEN, ExportStage.ENCODE, ExportStage.WRITE),
            run.stages,
        )
        assertFile(run, "image/jpeg", "SOCIAL_STORY_JPEG", "jpg", 1080, 1920)
        val decoded = ImageIO.read(ByteArrayInputStream(run.bytes))
        assertEquals(1080, decoded.width)
        assertEquals(1920, decoded.height)
    }

    @Test
    fun ultraHdrWithoutEncoder_fallsBackToSocialJpegAtTargetSize() {
        val renderer = FakeRenderer(peak = 4f)
        val run = export(ExportFormat.ULTRA_HDR_JPEG, renderer = renderer, ultraHdr = null, hdrBoost = true)
        assertEquals(1080 to 1920, renderer.lastCanvas)
        assertEquals(ExportFormat.SOCIAL_STORY_JPEG, run.result.format)
        assertEquals(listOf(ExportStage.RENDER, ExportStage.SHARPEN, ExportStage.ENCODE, ExportStage.WRITE), run.stages)
        assertFile(run, "image/jpeg", "SOCIAL_STORY_JPEG", "jpg", 1080, 1920)
        val decoded = ImageIO.read(ByteArrayInputStream(run.bytes))
        assertEquals(1080, decoded.width)
        assertEquals(1920, decoded.height)
    }

    @Test
    fun ultraHdrWithEncoder_passesSharpenedSdrAndGainMapAtQuality95() {
        val encoder = FakeUltraHdrEncoder()
        val run = export(ExportFormat.ULTRA_HDR_JPEG, renderer = FakeRenderer(peak = 4f), ultraHdr = encoder, hdrBoost = true)
        assertEquals(ExportFormat.ULTRA_HDR_JPEG, run.result.format)
        assertEquals(listOf(ExportStage.RENDER, ExportStage.SHARPEN, ExportStage.ENCODE, ExportStage.WRITE), run.stages)
        assertEquals(listOf(0f, 0.5f, 0.75f, 1f), run.fractions(ExportStage.RENDER))
        assertFile(run, "image/jpeg", "ULTRA_HDR_JPEG", "jpg", 1080, 1920)
        assertEquals(95, encoder.quality)
        assertEquals(1080 to 1920, encoder.sdrSize)
        val gain = checkNotNull(encoder.gain)
        assertEquals(270 to 480, gain.gainWidth to gain.gainHeight)
        assertTrue("the boosted highlights give headroom", gain.ratioMax > 1f)
        val expectedExif = ExifWriter.tiff(ExifFields(captureMillis, "Lisbon", "AkaStudio", 1080, 1920))
        assertArrayEquals("the encoder receives the same EXIF block as the other encoders", expectedExif, encoder.exif)
        val decoded = ImageIO.read(ByteArrayInputStream(run.bytes))
        assertEquals(1080, decoded.width)
    }

    @Test
    fun ultraHdrWithoutHeadroom_writesPlainSocialJpegAtTargetSize() {
        val encoder = FakeUltraHdrEncoder()
        val run = export(ExportFormat.ULTRA_HDR_JPEG, renderer = FakeRenderer(peak = 1f), ultraHdr = encoder, hdrBoost = false)
        assertEquals(ExportFormat.SOCIAL_STORY_JPEG, run.result.format)
        assertEquals("the gain-map encoder is never called", -1, encoder.quality)
        assertEquals(listOf(ExportStage.RENDER, ExportStage.SHARPEN, ExportStage.ENCODE, ExportStage.WRITE), run.stages)
        assertEquals(listOf(0f, 0.5f, 1f), run.fractions(ExportStage.RENDER))
        assertFile(run, "image/jpeg", "SOCIAL_STORY_JPEG", "jpg", 1080, 1920)
        val decoded = ImageIO.read(ByteArrayInputStream(run.bytes))
        assertEquals(1080, decoded.width)
        assertEquals(1920, decoded.height)
    }

    /** SQ-66: an SDR grade that nudges highlights ~5% past white (SQ-52's 0.072-stop gain map) is not HDR. */
    @Test
    fun ultraHdrWithImperceptibleHeadroom_writesPlainSocialJpeg() {
        val renderer = FakeRenderer(peak = 1.08f)
        val ratioMax = GainMapMath.compute(runBlocking { renderer.renderOffscreen(request(ExportFormat.ULTRA_HDR_JPEG).scene) }).ratioMax
        assertTrue("fixture has a near-zero gain map like SQ-52's: ratioMax $ratioMax", ratioMax > 1.03f && ratioMax < ExportProcessor.MIN_HDR_RATIO)
        val encoder = FakeUltraHdrEncoder()
        val run = export(ExportFormat.ULTRA_HDR_JPEG, renderer = renderer, ultraHdr = encoder, hdrBoost = false)
        assertEquals("the reported format names the fallback", ExportFormat.SOCIAL_STORY_JPEG, run.result.format)
        assertEquals("the gain-map encoder is never called", -1, encoder.quality)
        assertFile(run, "image/jpeg", "SOCIAL_STORY_JPEG", "jpg", 1080, 1920)
    }

    @Test
    fun ultraHdrWithPerceptibleHeadroomAndNoBoost_keepsTheGainMap() {
        val encoder = FakeUltraHdrEncoder()
        val run = export(ExportFormat.ULTRA_HDR_JPEG, renderer = FakeRenderer(peak = 2f), ultraHdr = encoder, hdrBoost = false)
        assertEquals(ExportFormat.ULTRA_HDR_JPEG, run.result.format)
        val gain = checkNotNull(encoder.gain) { "the gain-map encoder must be called" }
        assertTrue("ratioMax ${gain.ratioMax} reaches the threshold", gain.ratioMax >= ExportProcessor.MIN_HDR_RATIO)
        assertFile(run, "image/jpeg", "ULTRA_HDR_JPEG", "jpg", 1080, 1920)
    }

    /**
     * F-10: the low-headroom fallback encodes the render itself, not the gain map's tone-mapped base, so at UHD_4K
     * (both formats render 2160x3840) it is the explicit Social Story JPEG byte for byte.
     */
    @Test
    fun ultraHdrFallbackWithImperceptibleHeadroom_isByteIdenticalToTheSocialStoryJpeg() {
        val fallback = export(
            ExportFormat.ULTRA_HDR_JPEG, renderer = FakeRenderer(peak = 1.08f), ultraHdr = FakeUltraHdrEncoder(),
            resolution = ExportResolution.UHD_4K,
        )
        assertEquals(ExportFormat.SOCIAL_STORY_JPEG, fallback.result.format)
        val social = export(ExportFormat.SOCIAL_STORY_JPEG, renderer = FakeRenderer(peak = 1.08f), resolution = ExportResolution.UHD_4K)
        assertArrayEquals("the fallback must be the Social Story JPEG of the same render", social.bytes, fallback.bytes)
    }

    /** F-27: hdrBoost on a scene with no highlights would give an all-zero gain map; it falls back to Social instead. */
    @Test
    fun ultraHdrWithBoostAndNoHighlights_writesThePlainSocialJpeg() {
        val encoder = FakeUltraHdrEncoder()
        val fallback = export(
            ExportFormat.ULTRA_HDR_JPEG, renderer = FakeRenderer(exposure = 0.2f), ultraHdr = encoder, hdrBoost = true,
            resolution = ExportResolution.UHD_4K,
        )
        assertEquals("the reported format names the fallback", ExportFormat.SOCIAL_STORY_JPEG, fallback.result.format)
        assertEquals("the gain-map encoder is never called", -1, encoder.quality)
        assertEquals(listOf(0f, 0.5f, 0.75f, 1f), fallback.fractions(ExportStage.RENDER))
        assertFile(fallback, "image/jpeg", "SOCIAL_STORY_JPEG", "jpg", 2160, 3840)
        val social = export(ExportFormat.SOCIAL_STORY_JPEG, renderer = FakeRenderer(exposure = 0.2f), resolution = ExportResolution.UHD_4K)
        assertArrayEquals("the fallback encodes the unboosted render", social.bytes, fallback.bytes)
    }

    @Test
    fun minHdrRatio_isAThirdOfAStop() {
        assertEquals(1.2599f, ExportProcessor.MIN_HDR_RATIO, 1e-4f)
    }

    @Test
    fun webpLossless_streamedFileIsByteIdenticalToBufferedContainer() {
        // A bitstream several WRITE slices long, odd so the VP8L chunk needs its pad byte.
        val run = export(ExportFormat.WEBP_LOSSLESS, webpEncoder = FakeWebpEncoder(bitstreamBytes = 200_001))
        val lossless = FakeWebpEncoder(bitstreamBytes = 200_001).encode(1080, 1920) { _, _ -> }
        val exif = ExifWriter.tiff(ExifFields(captureMillis, "Lisbon", "AkaStudio", 1080, 1920))
        assertArrayEquals(WebpContainer.withMetadata(lossless, SrgbIccProfile.bytes, exif), run.bytes)
        assertTrue("WRITE reported per slice: ${run.fractions(ExportStage.WRITE)}", run.fractions(ExportStage.WRITE).size > 4)
    }

    @Test
    fun canvasMismatch_throwsBeforeRender() {
        val renderer = FakeRenderer()
        val sink = MemorySink()
        val processor = ExportProcessor(renderer, sink, null, FakeWebpEncoder())
        val request = request(ExportFormat.SOCIAL_STORY_JPEG, canvas = 1080 to 1920)
        assertThrows(IllegalArgumentException::class.java) { processor.export(request) }
        assertEquals(0, renderer.calls)
        assertNull(sink.displayName)
    }

    @Test
    fun sinkFailure_propagates_noDoneEmitted() {
        val sink = MemorySink(failAfterBytes = 4096)
        val processor = ExportProcessor(FakeRenderer(), sink, null, FakeWebpEncoder())
        val seen = ArrayList<ExportProgress>()
        val failure = runCatching { runBlocking { processor.export(request(ExportFormat.PNG_8)).collect { seen += it } } }.exceptionOrNull()
        assertTrue("expected IOException, got $failure", failure is IOException)
        assertTrue(seen.none { it is ExportProgress.Done })
        assertTrue(sink.aborted is IOException)
        assertNull(sink.published)
    }

    @Test
    fun cancellationDuringEncode_propagates_sinkSeesAbort() {
        val sink = MemorySink(holdFirstWrite = true)
        val processor = ExportProcessor(FakeRenderer(), sink, null, FakeWebpEncoder())
        val seen = ArrayList<ExportProgress>()
        runBlocking {
            val job = launch {
                val self = coroutineContext.job
                processor.export(request(ExportFormat.PNG_16)).collect { progress ->
                    seen += progress
                    if (progress is ExportProgress.Stage && progress.name == ExportStage.ENCODE && !self.isCancelled) {
                        assertTrue("sink never started writing", sink.firstWrite.await(30, TimeUnit.SECONDS))
                        self.cancel()
                        sink.release.countDown()
                    }
                }
            }
            job.join()
            assertTrue(job.isCancelled)
        }
        assertTrue("sink saw ${sink.aborted}", sink.aborted is CancellationException)
        assertNull(sink.published)
        assertTrue(seen.none { it is ExportProgress.Done })
    }

    @Test
    fun cancelRightAfterPublish_stillReportsDoneWithThePublishedFile() {
        val collectorParked = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        lateinit var collecting: Job
        val sink = MemorySink(afterPublish = {
            // The entry is visible now; cancel the collector exactly here, like a cancel tap racing the publish step.
            check(collectorParked.await(30, TimeUnit.SECONDS)) { "collector never reached WRITE 0" }
            collecting.cancel()
            cancelled.countDown()
        })
        val processor = ExportProcessor(FakeRenderer(), sink, null, FakeWebpEncoder(), clock = { captureMillis })
        val seen = ArrayList<ExportProgress>()
        runBlocking {
            collecting = launch {
                val self = coroutineContext.job
                processor.export(request(ExportFormat.PNG_8)).collect { progress ->
                    seen += progress
                    if (progress == ExportProgress.Stage(ExportStage.WRITE, 0f)) {
                        // Stay busy (not suspended in receive) until the export has finished, so the cancellation
                        // cannot discard what the exporter sent after the publish.
                        collectorParked.countDown()
                        check(cancelled.await(30, TimeUnit.SECONDS)) { "sink never cancelled the collector" }
                        awaitExportFinished(self)
                    }
                }
            }
            collecting.join()
        }
        assertTrue(collecting.isCancelled)
        val published = checkNotNull(sink.published) { "the sink published the file" }
        val done = seen.last()
        assertTrue("expected a trailing Done, saw ${seen.takeLast(3)}", done is ExportProgress.Done)
        val result = (done as ExportProgress.Done).result
        assertEquals("content://test/${sink.displayName}", result.uri)
        assertEquals(published.size.toLong(), result.byteSize)
        assertEquals(ExportProgress.Stage(ExportStage.WRITE, 1f), seen[seen.size - 2])
    }

    @Test
    fun fileName_usesClockTimestampFormatAndExtension() {
        val millis = Instant.parse("2026-10-21T13:04:05Z").toEpochMilli()
        assertEquals("AkaStudio_20261021_130405_PNG_8.png", ExportNaming.fileName(ExportFormat.PNG_8, millis, ZoneOffset.UTC))
        assertEquals(
            "AkaStudio_20261021_130405_ULTRA_HDR_JPEG.jpg",
            ExportNaming.fileName(ExportFormat.ULTRA_HDR_JPEG, millis, ZoneOffset.UTC),
        )
        assertEquals("AkaStudio_20261021_130405_WEBP_LOSSLESS.webp", ExportNaming.fileName(ExportFormat.WEBP_LOSSLESS, millis, ZoneOffset.UTC))
    }

    // ---- harness -----------------------------------------------------------------------------------------------

    private class Run(val events: List<ExportProgress>, val result: ExportResult, val sink: MemorySink) {
        val bytes: ByteArray get() = checkNotNull(sink.published)
        val stages: List<ExportStage>
            get() = events.filterIsInstance<ExportProgress.Stage>().map { it.name }.fold(ArrayList()) { acc, s ->
                if (acc.lastOrNull() != s) acc += s
                acc
            }

        fun fractions(stage: ExportStage): List<Float> =
            events.filterIsInstance<ExportProgress.Stage>().filter { it.name == stage }.map { it.fraction }
    }

    private fun export(
        format: ExportFormat,
        renderer: FakeRenderer = FakeRenderer(),
        ultraHdr: UltraHdrEncoder? = null,
        webpEncoder: WebpLosslessEncoder = FakeWebpEncoder(),
        hdrBoost: Boolean = false,
        resolution: ExportResolution = ExportResolution.HD_1080,
    ): Run {
        val sink = MemorySink()
        val processor = ExportProcessor(renderer, sink, ultraHdr, webpEncoder, clock = { captureMillis })
        val events = runBlocking { processor.export(request(format, hdrBoost = hdrBoost, resolution = resolution)).toList() }
        assertWellFormed(events)
        return Run(events, (events.last() as ExportProgress.Done).result, sink)
    }

    /**
     * Blocks until the export's producer has completed. The flow runs it under `collect`'s scope, a child of
     * [collecting]; that scope stays open while the collector is busy, so its remaining children are the export.
     */
    private fun awaitExportFinished(collecting: Job) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (collecting.children.any { scope -> scope.children.any() }) {
            check(System.nanoTime() < deadline) { "the export never finished" }
            Thread.sleep(1)
        }
    }

    /** Exactly one trailing Done; every stage is one contiguous run whose fractions start at 0, end at 1 and never fall. */
    private fun assertWellFormed(events: List<ExportProgress>) {
        assertTrue(events.last() is ExportProgress.Done)
        assertEquals(1, events.count { it is ExportProgress.Done })
        val stages = events.filterIsInstance<ExportProgress.Stage>()
        val order = stages.map { it.name }.fold(ArrayList<ExportStage>()) { acc, s -> if (acc.lastOrNull() != s) acc += s; acc }
        assertEquals("each stage is contiguous: $order", order.distinct(), order)
        assertEquals("stages follow pipeline order", order.sortedBy { it.ordinal }, order)
        for (stage in order) {
            val fractions = stages.filter { it.name == stage }.map { it.fraction }
            assertEquals("$stage starts at 0", 0f, fractions.first())
            assertEquals("$stage ends at 1", 1f, fractions.last())
            fractions.zipWithNext().forEach { (a, b) -> assertTrue("$stage fractions monotone: $fractions", b >= a) }
        }
    }

    private fun assertFile(run: Run, mime: String, formatName: String, extension: String, width: Int, height: Int) {
        val result = run.result
        assertEquals(mime, result.mimeType)
        assertEquals(mime, run.sink.mimeType)
        assertEquals(width, result.width)
        assertEquals(height, result.height)
        assertEquals("content://test/${run.sink.displayName}", result.uri)
        assertEquals(run.bytes.size.toLong(), result.byteSize)
        val name = checkNotNull(run.sink.displayName)
        assertTrue(name, Regex("AkaStudio_\\d{8}_\\d{6}_${formatName}\\.$extension").matches(name))
    }

    private fun request(
        format: ExportFormat,
        hdrBoost: Boolean = false,
        canvas: Pair<Int, Int>? = null,
        resolution: ExportResolution = ExportResolution.HD_1080,
    ): ExportRequest {
        val (w, h) = canvas ?: ExportPlan.renderSize(format, resolution)
        return ExportRequest(
            scene = RenderScene(w, h, SceneBackground.Solid(0xFF000000.toInt()), emptyList()),
            format = format,
            resolution = resolution,
            metadata = ExportMetadata(captureEpochMillis = captureMillis, description = "Lisbon"),
            hdrBoost = hdrBoost,
        )
    }

    private fun riffChunks(webp: ByteArray): List<String> {
        val chunks = ArrayList<String>()
        var position = 12
        while (position + 8 <= webp.size) {
            chunks += String(webp, position, 4, Charsets.ISO_8859_1)
            val size = (webp[position + 4].toInt() and 0xFF) or ((webp[position + 5].toInt() and 0xFF) shl 8) or
                ((webp[position + 6].toInt() and 0xFF) shl 16) or ((webp[position + 7].toInt() and 0xFF) shl 24)
            position += 8 + size + (size and 1)
        }
        return chunks
    }

    // ---- fakes -------------------------------------------------------------------------------------------------

    /**
     * Returns an opaque gradient at the scene canvas size; the top eighth reaches [peak] (linear) for HDR headroom. RGB
     * is scaled by [exposure], so a low one leaves no highlight for hdrBoost to lift.
     */
    private class FakeRenderer(private val peak: Float = 1f, private val exposure: Float = 1f) : StoryRenderer {
        @Volatile var calls = 0
        @Volatile var lastCanvas: Pair<Int, Int>? = null

        override suspend fun renderOffscreen(scene: RenderScene): HalfFloatImage {
            calls++
            val w = scene.canvasWidth
            val h = scene.canvasHeight
            lastCanvas = w to h
            val data = ShortArray(w * h * 4)
            val one = Half.fromFloat(1f)
            for (y in 0 until h) {
                val highlight = y < h / 8
                for (x in 0 until w) {
                    val o = (y * w + x) * 4
                    val base = x.toFloat() / w
                    data[o] = Half.fromFloat(exposure * if (highlight) peak * base else base)
                    data[o + 1] = Half.fromFloat(exposure * if (highlight) peak else y.toFloat() / h)
                    data[o + 2] = Half.fromFloat(exposure * (0.25f + 0.5f * ((x + y) % 64) / 64f))
                    data[o + 3] = one
                }
            }
            return HalfFloatImage(w, h, data)
        }

        override suspend fun registerImage(key: String, bitmap: android.graphics.Bitmap) = unused()
        override suspend fun unregisterImage(key: String) = unused()
        override suspend fun renderToSurface(
            scene: RenderScene, surface: android.view.Surface, surfaceWidth: Int, surfaceHeight: Int, options: PreviewOptions,
        ) = unused()
        override suspend fun renderPreviewPixels(scene: RenderScene, width: Int, height: Int): ImagePixels = unused()
        override suspend fun maxTextureSize(): Int = unused()
        override fun close() = Unit

        private fun unused(): Nothing = throw UnsupportedOperationException("the exporter only renders offscreen")
    }

    /**
     * In-memory sink following the publish-or-abort protocol; can fail after N bytes, hold its first write, or run
     * [afterPublish] once the entry is published, just before returning its uri.
     */
    private class MemorySink(
        private val failAfterBytes: Int? = null,
        private val holdFirstWrite: Boolean = false,
        private val afterPublish: () -> Unit = {},
    ) : EncodedSink {
        @Volatile var displayName: String? = null
        @Volatile var mimeType: String? = null
        @Volatile var dateTaken: Long? = null
        @Volatile var published: ByteArray? = null
        @Volatile var aborted: Throwable? = null
        val firstWrite = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun write(displayName: String, mimeType: String, dateTakenMillis: Long?, block: (OutputStream) -> Unit): String {
            this.displayName = displayName
            this.mimeType = mimeType
            this.dateTaken = dateTakenMillis
            val buffer = ByteArrayOutputStream()
            val stream = object : OutputStream() {
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (holdFirstWrite && firstWrite.count > 0) {
                        firstWrite.countDown()
                        check(release.await(30, TimeUnit.SECONDS)) { "test never released the sink" }
                    }
                    if (failAfterBytes != null && buffer.size() + len > failAfterBytes) throw IOException("disk full")
                    buffer.write(b, off, len)
                }
            }
            try {
                block(stream)
            } catch (failure: Throwable) {
                aborted = failure
                throw failure
            }
            published = buffer.toByteArray()
            afterPublish()
            return "content://test/$displayName"
        }
    }

    /**
     * Pulls every row and returns a minimal simple-lossless WebP (`VP8L` header plus a deterministic stand-in
     * bitstream of [bitstreamBytes]).
     */
    private class FakeWebpEncoder(private val bitstreamBytes: Int = 7) : WebpLosslessEncoder {
        @Volatile var rowsPulled = 0
        @Volatile var size: Pair<Int, Int>? = null

        override fun encode(width: Int, height: Int, rows: Rgba8Rows): ByteArray {
            size = width to height
            val row = IntArray(width)
            for (y in 0 until height) {
                rows.row(y, row)
                rowsPulled++
            }
            val bits = (width - 1) or ((height - 1) shl 14)
            val payload = byteArrayOf(0x2F) + le32(bits) + ByteArray(bitstreamBytes) { (it * 31 + 1).toByte() }
            val chunk = "VP8L".toByteArray(Charsets.ISO_8859_1) + le32(payload.size) + payload +
                if (payload.size % 2 == 1) byteArrayOf(0) else byteArrayOf()
            return "RIFF".toByteArray(Charsets.ISO_8859_1) + le32(4 + chunk.size) + "WEBP".toByteArray(Charsets.ISO_8859_1) + chunk
        }

        private fun le32(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())
    }

    /** Records its inputs and writes the SDR base as a plain baseline JPEG so the output stays decodable. */
    private class FakeUltraHdrEncoder : UltraHdrEncoder {
        @Volatile var quality = -1
        @Volatile var sdrSize: Pair<Int, Int>? = null
        @Volatile var gain: GainMapResult? = null
        @Volatile var exif: ByteArray? = null

        override fun encode(sdr: HalfFloatImage, gain: GainMapResult, out: OutputStream, quality: Int, exifTiff: ByteArray?) {
            this.quality = quality
            this.sdrSize = sdr.width to sdr.height
            this.gain = gain
            this.exif = exifTiff
            check(sdr === gain.sdr) { "the encoder receives the sharpened base as both arguments" }
            JpegEncoder.write(out, sdr.width, sdr.height, { y, row -> Dither.toSrgb8Row(sdr, y, row) }, quality, exifTiff = exifTiff)
        }
    }
}
