package com.akane.akastudio.core.export.pipeline

import com.akane.akastudio.core.color.api.HalfFloatImage
import com.akane.akastudio.core.color.api.StoryRenderer
import com.akane.akastudio.core.export.api.ExportFormat
import com.akane.akastudio.core.export.api.ExportPlan
import com.akane.akastudio.core.export.api.ExportProgress
import com.akane.akastudio.core.export.api.ExportRequest
import com.akane.akastudio.core.export.api.ExportResolution
import com.akane.akastudio.core.export.api.ExportResult
import com.akane.akastudio.core.export.api.ExportStage
import com.akane.akastudio.core.export.api.StoryExporter
import com.akane.akastudio.core.export.encode.ExifFields
import com.akane.akastudio.core.export.encode.ExifWriter
import com.akane.akastudio.core.export.encode.JpegEncoder
import com.akane.akastudio.core.export.encode.PngEncoder
import com.akane.akastudio.core.export.encode.SrgbIccProfile
import com.akane.akastudio.core.export.encode.WebpContainer
import com.akane.akastudio.core.export.process.Dither
import com.akane.akastudio.core.export.process.GainMapMath
import com.akane.akastudio.core.export.process.GainMapResult
import com.akane.akastudio.core.export.process.Lanczos3
import com.akane.akastudio.core.export.process.LumaSharpen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.time.ZoneId

/**
 * Production [StoryExporter]: render -> process -> encode -> [EncodedSink].
 *
 * Stages are emitted in order with fractions rising from 0 to 1 within each stage, and the flow ends with
 * [ExportProgress.Done]:
 * - PNG_16 / PNG_8 / SOCIAL_STORY_JPEG / ULTRA_HDR_JPEG stream straight into the sink, so quantization (blue-noise
 *   dither / 16-bit rounding) is fused into the encoder's row pulls and reported as ENCODE; a separate dithered frame
 *   would exceed the memory budget.
 * - SOCIAL_STORY_JPEG adds RESAMPLE (only when the render is larger than the target) and SHARPEN before ENCODE.
 * - ULTRA_HDR_JPEG reports the optional highlight boost and the gain-map computation inside RENDER (they produce the
 *   HDR/SDR pair), then SHARPEN (after RESAMPLE if the render is larger), ENCODE.
 * - WEBP_LOSSLESS dithers into the platform bitmap (DITHER), lays out the ICC + EXIF container (ENCODE), then streams
 *   it into the sink (WRITE).
 * - WRITE reaches 1 once the sink has published the entry.
 *
 * ULTRA_HDR_JPEG without an [ultraHdr] encoder (below API 34) falls back to SOCIAL_STORY_JPEG processing at the
 * target size and reports SOCIAL_STORY_JPEG. So does an ULTRA_HDR_JPEG export without hdrBoost whose render has no
 * HDR headroom (gain-map ratioMax at most 1 + 1e-4): a gain-map container would carry nothing, so the gain-map RENDER
 * steps are followed by the plain Social Story JPEG stages.
 *
 * Cancellation is checked between stages and on every pulled row; the sink removes the partial entry. Once the sink
 * has published the entry, WRITE 1 and Done are emitted without a cancellation point, so a cancel racing the publish
 * step still reports the visible file. Heavy work runs on [Dispatchers.Default], the sink write on [Dispatchers.IO].
 *
 * Memory: a stage holds its input and output [HalfFloatImage] (8 bytes per pixel) plus row buffers. The peak is
 * RESAMPLE, which also holds Lanczos3's half-float `renderHeight x targetWidth` intermediate (SOCIAL_STORY_JPEG at
 * HD: 66 MB render + 33 MB intermediate + 17 MB output, about 116 MB). ULTRA_HDR_JPEG's platform encode adds a native
 * ARGB_8888 base bitmap and the buffered file. WEBP_LOSSLESS drops the render once it is dithered, before the container
 * is laid out and streamed.
 *
 * @param webpLossless the bitmap-backed lossless WebP encoder (Android-only in production).
 * @param clock wall-clock millis used for the file name.
 */
class ExportProcessor(
    private val renderer: StoryRenderer,
    private val sink: EncodedSink,
    private val ultraHdr: UltraHdrEncoder?,
    private val webpLossless: WebpLosslessEncoder,
    private val clock: () -> Long = System::currentTimeMillis,
) : StoryExporter {

    /**
     * Exports [request].
     *
     * @throws IllegalArgumentException immediately (before any rendering) when the scene canvas differs from
     *   [ExportPlan.renderSize] for the request's format and resolution.
     */
    override fun export(request: ExportRequest): Flow<ExportProgress> {
        val (renderWidth, renderHeight) = ExportPlan.renderSize(request.format, request.resolution)
        val scene = request.scene
        require(scene.canvasWidth == renderWidth && scene.canvasHeight == renderHeight) {
            "${request.format} at ${request.resolution} renders ${renderWidth}x$renderHeight, " +
                "but the scene canvas is ${scene.canvasWidth}x${scene.canvasHeight}"
        }
        val format = if (request.format == ExportFormat.ULTRA_HDR_JPEG && ultraHdr == null) {
            ExportFormat.SOCIAL_STORY_JPEG
        } else {
            request.format
        }
        return channelFlow {
            ExportJob(request, format, ProgressReporter(this), clock()).run()
        }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.Default)
    }

    /** Holds the one live full-frame image, so replacing or releasing it leaves the previous frame unreachable. */
    private class Frame(image: HalfFloatImage) {
        private var current: HalfFloatImage? = image

        var image: HalfFloatImage
            get() = checkNotNull(current) { "the frame was already released" }
            set(value) {
                current = value
            }

        fun release() {
            current = null
        }
    }

    /**
     * One export. [format] is the format written; it can still drop from ULTRA_HDR_JPEG to SOCIAL_STORY_JPEG once the
     * gain map shows no headroom, so the file name and MIME type are resolved at publish time.
     */
    private inner class ExportJob(
        private val request: ExportRequest,
        private var format: ExportFormat,
        private val progress: ProgressReporter,
        private val startedMillis: Long,
    ) {
        private val target: ExportResolution = request.resolution

        suspend fun run() {
            progress.stage(ExportStage.RENDER, 0f)
            val frame = Frame(renderer.renderOffscreen(request.scene))
            check(frame.image.width == request.scene.canvasWidth && frame.image.height == request.scene.canvasHeight) {
                "renderer returned ${frame.image.width}x${frame.image.height} for a " +
                    "${request.scene.canvasWidth}x${request.scene.canvasHeight} canvas"
            }
            when (format) {
                ExportFormat.PNG_16 -> {
                    progress.stage(ExportStage.RENDER, 1f)
                    writePng(frame, sixteenBit = true)
                }
                ExportFormat.PNG_8 -> {
                    progress.stage(ExportStage.RENDER, 1f)
                    writePng(frame, sixteenBit = false)
                }
                ExportFormat.WEBP_LOSSLESS -> {
                    progress.stage(ExportStage.RENDER, 1f)
                    writeWebp(frame)
                }
                ExportFormat.SOCIAL_STORY_JPEG -> {
                    progress.stage(ExportStage.RENDER, 1f)
                    writeSocialJpeg(frame)
                }
                ExportFormat.ULTRA_HDR_JPEG -> writeUltraHdr(frame, checkNotNull(ultraHdr))
            }
        }

        private fun resampleIfLarger(frame: Frame) {
            if (frame.image.width <= target.width && frame.image.height <= target.height) return
            progress.stage(ExportStage.RESAMPLE, 0f)
            frame.image = Lanczos3.resample(frame.image, target.width, target.height)
            progress.stage(ExportStage.RESAMPLE, 1f)
        }

        private suspend fun writePng(frame: Frame, sixteenBit: Boolean) {
            val image = frame.image
            val exif = exif(image.width, image.height)
            val rows = RowProgress(image.height, ExportStage.ENCODE, progress)
            progress.stage(ExportStage.ENCODE, 0f)
            publish(image.width, image.height) { out ->
                if (sixteenBit) {
                    PngEncoder.write16(out, image.width, image.height, { y, row ->
                        rows.pull(y)
                        Dither.toSrgb16Row(image, y, row)
                    }, exif)
                } else {
                    PngEncoder.write8(out, image.width, image.height, alpha = false, rows = { y, row ->
                        rows.pull(y)
                        Dither.toSrgb8Row(image, y, row)
                    }, exif = exif)
                }
                finishStreamingEncode(rows)
            }
        }

        /** RESAMPLE (when larger than the target), SHARPEN, then the 4:4:4 JPEG; RENDER is already complete. */
        private suspend fun writeSocialJpeg(frame: Frame) {
            resampleIfLarger(frame)
            progress.stage(ExportStage.SHARPEN, 0f)
            frame.image = LumaSharpen.apply(frame.image)
            progress.stage(ExportStage.SHARPEN, 1f)
            val image = frame.image
            val exif = exif(image.width, image.height)
            val rows = RowProgress(image.height, ExportStage.ENCODE, progress)
            progress.stage(ExportStage.ENCODE, 0f)
            publish(image.width, image.height) { out ->
                JpegEncoder.write(out, image.width, image.height, { y, row ->
                    rows.pull(y)
                    Dither.toSrgb8Row(image, y, row)
                }, quality = JPEG_QUALITY, icc = SrgbIccProfile.bytes, exifTiff = exif)
                finishStreamingEncode(rows)
            }
        }

        private suspend fun writeWebp(frame: Frame) {
            val width = frame.image.width
            val height = frame.image.height
            progress.stage(ExportStage.DITHER, 0f)
            val lossless = encodeLossless(frame.image)
            // Nothing reads the render any more: let it go before the container is laid out and written.
            frame.release()
            progress.stage(ExportStage.ENCODE, 0f)
            val container = WebpContainer.assemble(lossless, SrgbIccProfile.bytes, exif(width, height))
            progress.stage(ExportStage.ENCODE, 1f)
            publish(width, height) { out ->
                progress.stage(ExportStage.WRITE, 0f)
                container.writeTo(WriteProgressStream(out, container.size, progress, WRITE_CHUNK))
            }
        }

        /** Pulls every dithered row of [image] into the platform encoder (DITHER) and returns the simple WebP file. */
        private fun encodeLossless(image: HalfFloatImage): ByteArray {
            val rows = RowProgress(image.height, ExportStage.DITHER, progress)
            val lossless = webpLossless.encode(image.width, image.height) { y, row ->
                rows.pull(y)
                Dither.toSrgb8Row(image, y, row)
            }
            check(rows.complete) { "WebP encoder returned before pulling all ${image.height} rows" }
            return lossless
        }

        private suspend fun writeUltraHdr(frame: Frame, encoder: UltraHdrEncoder) {
            var gainMap = gainMapWithHeadroom(frame) ?: run {
                format = ExportFormat.SOCIAL_STORY_JPEG
                return writeSocialJpeg(frame)
            }
            if (gainMap.sdr.width > target.width || gainMap.sdr.height > target.height) {
                progress.stage(ExportStage.RESAMPLE, 0f)
                gainMap = gainMap.copy(sdr = Lanczos3.resample(gainMap.sdr, target.width, target.height))
                frame.image = gainMap.sdr
                progress.stage(ExportStage.RESAMPLE, 1f)
            }
            progress.stage(ExportStage.SHARPEN, 0f)
            gainMap = gainMap.copy(sdr = LumaSharpen.apply(gainMap.sdr))
            frame.image = gainMap.sdr
            val gain = gainMap
            progress.stage(ExportStage.SHARPEN, 1f)
            val sdr = gain.sdr
            val exif = exif(sdr.width, sdr.height)
            progress.stage(ExportStage.ENCODE, 0f)
            publish(sdr.width, sdr.height) { out ->
                encoder.encode(sdr, gain, out, JPEG_QUALITY, exif)
                progress.stage(ExportStage.ENCODE, 1f)
                progress.stage(ExportStage.WRITE, 0f)
            }
        }

        /**
         * Finishes RENDER for Ultra HDR: the optional highlight boost, then the gain map, whose SDR base replaces the
         * frame. Returns `null` when there is no headroom to encode (hdrBoost off and ratioMax at most
         * 1 + [NO_HEADROOM_EPSILON]).
         */
        private fun gainMapWithHeadroom(frame: Frame): GainMapResult? {
            progress.stage(ExportStage.RENDER, 0.5f)
            if (request.hdrBoost) {
                frame.image = GainMapMath.boostHighlights(frame.image)
                progress.stage(ExportStage.RENDER, 0.75f)
            }
            val gainMap = GainMapMath.compute(frame.image)
            frame.image = gainMap.sdr
            progress.stage(ExportStage.RENDER, 1f)
            val noHeadroom = !request.hdrBoost && gainMap.ratioMax <= 1f + NO_HEADROOM_EPSILON
            return if (noHeadroom) null else gainMap
        }

        /** Closes a streaming ENCODE inside the sink block: everything is encoded, the sink is about to publish. */
        private fun finishStreamingEncode(rows: RowProgress) {
            check(rows.complete) { "encoder finished before pulling every row" }
            progress.stage(ExportStage.ENCODE, 1f)
            progress.stage(ExportStage.WRITE, 0f)
        }

        /**
         * Runs [block] against the sink's stream on [Dispatchers.IO]. Any failure or cancellation inside [block]
         * propagates through the sink, which removes the entry. Once the sink returns, the file is published: WRITE 1
         * and Done (a [width] x [height] result) are sent right here, before the cancellable hop back off
         * [Dispatchers.IO], so a cancel that lands during the publish step cannot swallow them.
         */
        private suspend fun publish(width: Int, height: Int, block: (OutputStream) -> Unit) = withContext(Dispatchers.IO) {
            val mimeType = ExportNaming.mimeType(format)
            val displayName = ExportNaming.fileName(format, startedMillis, ZoneId.systemDefault())
            var written = 0L
            val uri = sink.write(displayName, mimeType, request.metadata.captureEpochMillis) { out ->
                progress.ensureActive()
                val counting = CountingOutputStream(out)
                block(counting)
                counting.flush()
                written = counting.count
            }
            val result = ExportResult(uri = uri, mimeType = mimeType, width = width, height = height, byteSize = written, format = format)
            withContext(NonCancellable) { progress.published(result) }
        }

        private fun exif(width: Int, height: Int): ByteArray = ExifWriter.tiff(
            ExifFields(request.metadata.captureEpochMillis, request.metadata.description, request.metadata.software, width, height),
        )
    }

    private companion object {
        const val JPEG_QUALITY = 95
        const val WRITE_CHUNK = 64 * 1024
        const val NO_HEADROOM_EPSILON = 1e-4f
    }
}
