package com.akane.akastudio.core.ingestion.decode

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Build
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import com.akane.akastudio.core.ingestion.api.IngestionException
import com.akane.akastudio.core.ingestion.model.IngestionError
import com.akane.akastudio.core.ingestion.model.PhotoCandidate
import com.akane.akastudio.core.ingestion.model.PhotoSource
import java.io.File
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * A chosen photo decoded for rendering.
 *
 * @property bitmap Upright RGBA_F16 bitmap in [ColorSpace.Named.LINEAR_EXTENDED_SRGB]; it carries the source gain
 *   map when the platform decoded one (API 34+).
 * @property sourceWidth Upright (orientation applied) width of the encoded image before scaling.
 * @property sourceHeight Upright (orientation applied) height of the encoded image before scaling.
 * @property hasGainMap True when the photo has an Ultra HDR gain map: the decoded bitmap's own gain map on API 34+,
 *   otherwise the gain map detected while parsing metadata.
 */
data class DecodedPhoto(
    val bitmap: Bitmap,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val hasGainMap: Boolean,
)

/**
 * Bounded full-resolution decode of the 1–4 chosen photos into linear extended sRGB half-float pixels, the working
 * space of the color engine and renderer. ImageDecoder applies EXIF orientation, subsamples by the largest power of
 * two that keeps the decode at least the target size, decodes in the codec's native config and color space, and
 * rejects truncated input instead of returning a partially grey image; [toLinearF16] then converts to RGBA_F16 linear
 * extended sRGB and does the remaining exact downscale on linear values.
 */
class FullResolutionDecoder internal constructor(
    private val contentResolver: ContentResolver,
    private val ioDispatcher: CoroutineDispatcher,
    /** Output rows per linear conversion band; only tests change it (to put band seams inside small fixtures). */
    private val bandOutputRows: Int,
) {

    constructor(
        contentResolver: ContentResolver,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(contentResolver, ioDispatcher, BAND_OUTPUT_ROWS)

    init {
        require(bandOutputRows > 0) { "bandOutputRows must be positive: $bandOutputRows" }
    }

    /**
     * Upright RGBA_F16 bitmap in ColorSpace LINEAR_EXTENDED_SRGB, long edge <= [maxLongEdge] (never upscaled).
     *
     * On [OutOfMemoryError] the decode is retried once at half of [maxLongEdge], unless this coroutine or the caller
     * ([callerCancelled], for a caller that runs the decode under NonCancellable) was cancelled meanwhile.
     *
     * @throws IngestionException with [IngestionError.Io] when the source cannot be read or decoded, or when the
     *   retry also runs out of memory.
     * @throws IllegalArgumentException when [maxLongEdge] is not positive.
     * @throws CancellationException when the first attempt ran out of memory after a cancel.
     */
    suspend fun decode(
        candidate: PhotoCandidate,
        maxLongEdge: Int,
        callerCancelled: () -> Boolean = { false },
    ): DecodedPhoto {
        require(maxLongEdge > 0) { "maxLongEdge must be positive: $maxLongEdge" }
        return withContext(ioDispatcher) {
            val decoded = try {
                decodeBounded(candidate.source, maxLongEdge)
            } catch (firstOom: OutOfMemoryError) {
                ensureActive()
                if (callerCancelled()) {
                    throw CancellationException("Decode of ${candidate.source.displayName} cancelled before its retry")
                }
                val retryEdge = (maxLongEdge / 2).coerceAtLeast(1)
                try {
                    decodeBounded(candidate.source, retryEdge)
                } catch (retryOom: OutOfMemoryError) {
                    retryOom.addSuppressed(firstOom)
                    throw IngestionException(
                        IngestionError.Io("out of memory decoding ${candidate.source.displayName} at ${retryEdge}px"),
                    ).apply { initCause(retryOom) }
                }
            }
            DecodedPhoto(
                bitmap = decoded.bitmap,
                sourceWidth = decoded.sourceWidth,
                sourceHeight = decoded.sourceHeight,
                hasGainMap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    decoded.bitmap.hasGainmap()
                } else {
                    candidate.metadata.hasUltraHdrGainMap
                },
            )
        }
    }

    private fun decodeBounded(source: PhotoSource, maxLongEdge: Int): Decoded {
        var sourceWidth = 0
        var sourceHeight = 0
        var targetWidth = 0
        var targetHeight = 0
        val bitmap = try {
            ImageDecoder.decodeBitmap(createSource(source)) { decoder, info, _ ->
                sourceWidth = info.size.width
                sourceHeight = info.size.height
                val target = FullResSizing.targetSize(sourceWidth, sourceHeight, maxLongEdge)
                targetWidth = target.first
                targetHeight = target.second
                // Only the power-of-two part of the downscale happens in the codec. A setTargetSize with the exact
                // target would also make the codec resample the non-integer remainder on encoded 8-bit values, which
                // darkens fine bright detail; toLinearF16 does that remainder on linear values instead.
                decoder.setTargetSampleSize(FullResSizing.sampleSize(sourceWidth, sourceHeight, targetWidth, targetHeight))
                // No setTargetColorSpace: the HEIF codec's RGBA_F16 "linear" output keeps the encoded (sRGB/P3 OETF)
                // values while tagging them LINEAR_EXTENDED_SRGB (verified on SM-S938B, API 37), which washes every
                // HEIC out. The native decode (8888, or 1010102 for 10-bit sources) is tagged honestly instead.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setOnPartialImageListener { false }
            }
        } catch (e: IngestionException) {
            throw e
        } catch (e: IOException) {
            // Also covers ImageDecoder.DecodeException, including the abort requested by the partial-image listener.
            throw IngestionException(
                IngestionError.Io("${source.displayName}: ${e.message ?: e.javaClass.simpleName}"),
            ).apply { initCause(e) }
        }
        return Decoded(toLinearF16(bitmap, targetWidth, targetHeight), sourceWidth, sourceHeight)
    }

    private fun createSource(source: PhotoSource): ImageDecoder.Source = when (source) {
        is PhotoSource.ContentUri -> ImageDecoder.createSource(contentResolver, source.uri.toUri())
        is PhotoSource.ExtractedFile -> ImageDecoder.createSource(File(source.path))
    }

    /**
     * Converts the codec's native bitmap into a fresh [targetWidth] x [targetHeight] RGBA_F16 linear extended sRGB
     * bitmap, carries over the gain map, and recycles the native bitmap (also when this throws).
     *
     * Canvas applies the source color space's EOTF and primaries, unclamped, so Display P3 colors outside sRGB survive
     * as extended values. Every format takes this path: the decision can only see the config and color space tag,
     * never whether the pixel values match the tag, so the codec's own linear conversion is never requested. 10-bit
     * sources arrive as RGBA_1010102 and keep their sub-8-bit steps; a 16-bit PNG arrives as non-linear RGBA_F16, so
     * it is rounded to half floats twice (encoded, then linear), about one half-float step near white. A bitmap the
     * codec natively returns as RGBA_F16 linear extended sRGB is already in the working space and is only resized.
     *
     * When the native bitmap is larger than the target, the remaining (at most 2:1) downscale is a bilinear Canvas draw
     * whose source is already linear F16, so the filter averages light rather than encoded values. The source is
     * converted in horizontal bands of [bandOutputRows] output rows, so only one band of F16 source rows exists next
     * to the native bitmap and the output, instead of a full-size F16 copy.
     */
    private fun toLinearF16(native: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        val alreadyLinear = native.config == Bitmap.Config.RGBA_F16 && native.colorSpace == linearExtendedSrgb
        val sameSize = native.width == targetWidth && native.height == targetHeight
        if (alreadyLinear && sameSize) return native
        try {
            val output = createLinearF16(targetWidth, targetHeight, native.hasAlpha())
            try {
                when {
                    sameSize -> Canvas(output).drawBitmap(native, 0f, 0f, null)
                    alreadyLinear -> Canvas(output).drawBitmap(native, downscaleMatrix(native, output, 0), filterPaint())
                    else -> downscaleInLinearBands(native, output)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && native.hasGainmap()) {
                    output.gainmap = native.gainmap
                }
            } catch (e: Throwable) {
                output.recycle()
                throw e
            }
            return output
        } finally {
            native.recycle()
        }
    }

    private fun downscaleInLinearBands(native: Bitmap, output: Bitmap) {
        val scaleY = native.height.toDouble() / output.height
        val bandSourceRows = minOf(native.height, ceil(bandOutputRows * scaleY).toInt() + 2 * BAND_MARGIN_ROWS + 2)
        val band = createLinearF16(native.width, bandSourceRows, native.hasAlpha())
        try {
            val bandCanvas = Canvas(band)
            val outputCanvas = Canvas(output)
            val copy = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
            val filter = filterPaint()
            var outputTop = 0
            while (outputTop < output.height) {
                val outputBottom = minOf(outputTop + bandOutputRows, output.height)
                // Bilinear taps for output rows [outputTop, outputBottom) lie within these source rows.
                val sourceTop = maxOf(0, floor(outputTop * scaleY).toInt() - BAND_MARGIN_ROWS)
                val sourceBottom = minOf(native.height, ceil(outputBottom * scaleY).toInt() + BAND_MARGIN_ROWS)
                check(sourceBottom - sourceTop <= band.height) { "band of ${sourceBottom - sourceTop} rows > ${band.height}" }
                // 1:1 at an integer offset: a pure color space conversion into linear F16, no resampling.
                bandCanvas.drawBitmap(native, 0f, -sourceTop.toFloat(), copy)
                outputCanvas.save()
                outputCanvas.clipRect(0, outputTop, output.width, outputBottom)
                outputCanvas.drawBitmap(band, downscaleMatrix(native, output, sourceTop), filter)
                outputCanvas.restore()
                outputTop = outputBottom
            }
        } finally {
            band.recycle()
        }
    }

    /** Maps source rows starting at [sourceTop] (row 0 of the drawn bitmap) onto the output, scaled to fit exactly. */
    private fun downscaleMatrix(native: Bitmap, output: Bitmap, sourceTop: Int): Matrix = Matrix().apply {
        setTranslate(0f, sourceTop.toFloat())
        postScale(output.width.toFloat() / native.width, output.height.toFloat() / native.height)
    }

    private fun filterPaint() = Paint(Paint.FILTER_BITMAP_FLAG)

    private fun createLinearF16(width: Int, height: Int, hasAlpha: Boolean): Bitmap =
        createBitmap(width, height, Bitmap.Config.RGBA_F16, hasAlpha, linearExtendedSrgb)

    private class Decoded(val bitmap: Bitmap, val sourceWidth: Int, val sourceHeight: Int)

    private companion object {
        val linearExtendedSrgb: ColorSpace = ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB)

        /** Output rows per conversion band: at a 2:1 remainder on a 6000 px wide source, about 12 MiB of F16 rows. */
        const val BAND_OUTPUT_ROWS = 128

        /** Extra source rows above and below each band, beyond the bilinear footprint. */
        const val BAND_MARGIN_ROWS = 2
    }
}
