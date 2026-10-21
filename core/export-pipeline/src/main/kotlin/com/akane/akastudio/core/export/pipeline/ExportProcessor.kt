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
import kotlin.math.pow

/**
 * Production [StoryExporter]: render -> process -> encode -> [EncodedSink].
 *
 * Stages are emitted in order with fractions rising from 0 to 1 within each stage, and the flow ends with
 * [ExportProgress.Done]:
 * - PNG_16 / PNG_8 / SOCIAL_STORY_JPEG / ULTRA_HDR_JPEG stream straight into the sink, so quantization (blue-noise
 *   dither / 16-bit rounding) is fused into the encoder's row pulls and reported as ENCODE; a separate dithered frame
 *   would exceed the memory budget.
 * - SOCIAL_STORY_JPEG adds RESAMPLE (only when the render is larger than the target) and SHARPEN before ENCODE.
 * - ULTRA_HDR_JPEG reports the optional highlight boost and the headroom decision inside RENDER, then SHARPEN of the
 *   HDR frame (after RESAMPLE if the render is larger), then ENCODE, which derives the gain map and its SDR base
 *   from the sharpened HDR frame before the platform encode, so the encoded base is the one the gain describes.
 * - WEBP_LOSSLESS dithers into the platform bitmap (DITHER), lays out the ICC + EXIF container (ENCODE), then streams
 *   it into the sink (WRITE).
 * - WRITE reaches 1 once the sink has published the entry.
 *
 * ULTRA_HDR_JPEG without an [ultraHdr] encoder (below API 34) falls back to SOCIAL_STORY_JPEG processing at the
 * target size and reports SOCIAL_STORY_JPEG. So does an ULTRA_HDR_JPEG export without hdrBoost whose render has no
 * perceptible HDR headroom (gain-map ratioMax below [MIN_HDR_RATIO], a third of a stop): an SDR source whose grade only
 * nudges highlights a few percent past SDR white would get a gain map nobody can see, on a 4:2:0 platform base instead
 * of the 4:4:4 Social one, so the headroom-decision RENDER steps are followed by the plain Social Story JPEG stages. With
 * hdrBoost the same happens when the boosted render has no headroom at all ([MIN_BOOSTED_HDR_RATIO]). The fallback
 * encodes the render itself, not the gain map's tone-mapped base, so it matches an explicit Social export of the same
 * render.
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
     * gain map shows no perceptible headroom, so the file name and MIME type are resolved at publish time.
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
                Dither.toStraightSrgb8Row(image, y, row)
            }
            check(rows.complete) { "WebP encoder returned before pulling all ${image.height} rows" }
            return lossless
        }

        /**
         * The headroom decision (RENDER), RESAMPLE when larger than the target, SHARPEN on the HDR frame, then ENCODE:
         * the gain map is derived from the sharpened HDR frame and its base is what the encoder receives. Sharpening
         * the tone-mapped base after the gain was computed would encode a base the gain was never measured against
         * (and could push its channels past SDR white, where the encoder clips them).
         */
        private suspend fun writeUltraHdr(frame: Frame, encoder: UltraHdrEncoder) {
            if (!hasHeadroom(frame)) {
                format = ExportFormat.SOCIAL_STORY_JPEG
                return writeSocialJpeg(frame)
            }
            resampleIfLarger(frame)
            progress.stage(ExportStage.SHARPEN, 0f)
            frame.image = LumaSharpen.apply(frame.image)
            progress.stage(ExportStage.SHARPEN, 1f)
            progress.stage(ExportStage.ENCODE, 0f)
            val gain = GainMapMath.compute(frame.image)
            frame.image = gain.sdr
            val sdr = gain.sdr
            val exif = exif(sdr.width, sdr.height)
            publish(sdr.width, sdr.height) { out ->
                encoder.encode(sdr, gain, out, JPEG_QUALITY, exif)
                progress.stage(ExportStage.ENCODE, 1f)
                progress.stage(ExportStage.WRITE, 0f)
            }
        }

        /**
         * Finishes RENDER for Ultra HDR: the optional highlight boost and the headroom decision. Returns `false` when
         * there is no headroom worth encoding: ratioMax below [MIN_HDR_RATIO] without hdrBoost, or below
         * [MIN_BOOSTED_HDR_RATIO] (an all-zero gain map, a scene with no highlights to boost) with it. The frame then
         * still holds the render, so the fallback writes exactly what SOCIAL_STORY_JPEG would. Otherwise the frame
         * holds the HDR image to encode (the boosted render with hdrBoost). [GainMapMath.ratioMax] equals the
         * ratioMax [GainMapMath.compute] reports for the same frame without allocating the SDR base, so the
         * render, the boosted frame and the base are never all held.
         */
        private fun hasHeadroom(frame: Frame): Boolean {
            progress.stage(ExportStage.RENDER, 0.5f)
            if (!request.hdrBoost) {
                val ratioMax = GainMapMath.ratioMax(frame.image)
                progress.stage(ExportStage.RENDER, 1f)
                return ratioMax >= MIN_HDR_RATIO
            }
            val boosted = GainMapMath.boostHighlights(frame.image)
            progress.stage(ExportStage.RENDER, 0.75f)
            val keep = GainMapMath.ratioMax(boosted) >= MIN_BOOSTED_HDR_RATIO
            if (keep) frame.image = boosted
            progress.stage(ExportStage.RENDER, 1f)
            return keep
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

    internal companion object {
        private const val JPEG_QUALITY = 95
        private const val WRITE_CHUNK = 64 * 1024

        /**
         * Smallest gain-map ratioMax worth an Ultra HDR file without hdrBoost: 2^(1/3), a third of a stop, the finest
         * exposure step cameras offer as a visible change (about 26% brighter highlights).
         *
         * Measured (SQ-66): the render keeps values above SDR white, so grading an SDR-only source overshoots it a
         * little. The CPU reference grade of an SDR chart (sRGB steps 0..1, white included) gave log2 ratioMax 0 to
         * 0.044 for every Master look at default sliders, and at most 0.25 with one slider at its extreme (contrast,
         * halation, temperature). SQ-52's SDR story exported a 0.072-stop gain map (about 5% brighter) through the old
         * near-zero cut-off (ratioMax above 1 + 1e-4). hdrBoost adds 2 stops to the highlights, so it uses
         * [MIN_BOOSTED_HDR_RATIO] instead. Deliberate brightening of an SDR source past a third of a stop (exposure, a
         * strong harmonization lift) reaches the threshold and keeps the gain map too.
         *
         * AUD-03: the base is also bounded per channel, so a saturated colour graded past SDR white counts as headroom
         * as well (the luminance-only base clipped it and reported none). On a CPU-graded copy of
         * SdrOnlyUltraHdrDeviceTest's SDR photo (sky to white, saturated patches, grey ramp; no JPEG round trip),
         * default looks give 0 to 0.20 stop and MEDITERRANEAN_AZURE with contrast 1 gives 0.29, all still below the
         * threshold, while saturation +1 (0.56, 0.73 with AZURE), temperature -1 (0.56) or halation 1 (0.37) now keep
         * the gain map.
         */
        val MIN_HDR_RATIO = 2f.pow(1f / 3f)

        /**
         * Smallest gain-map ratioMax kept with hdrBoost: anything above 1. [GainMapMath.compute] reports exactly 1 for
         * a gain map with no usable headroom (all codes zero), which is what a scene with no highlights to boost
         * gives; such a scene falls back to SOCIAL_STORY_JPEG like any other no-headroom request.
         */
        const val MIN_BOOSTED_HDR_RATIO = 1f + 1e-3f
    }
}
