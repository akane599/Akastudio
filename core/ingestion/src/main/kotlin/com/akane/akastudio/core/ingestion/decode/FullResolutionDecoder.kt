package com.akane.akastudio.core.ingestion.decode

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.os.Build
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import com.akane.akastudio.core.ingestion.api.IngestionException
import com.akane.akastudio.core.ingestion.model.IngestionError
import com.akane.akastudio.core.ingestion.model.PhotoCandidate
import com.akane.akastudio.core.ingestion.model.PhotoSource
import java.io.File
import java.io.IOException
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
 * space of the color engine and renderer. ImageDecoder applies EXIF orientation, decodes straight to the target size
 * and color space, and rejects truncated input instead of returning a partially grey image.
 */
class FullResolutionDecoder(
    private val contentResolver: ContentResolver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Upright RGBA_F16 bitmap in ColorSpace LINEAR_EXTENDED_SRGB, long edge <= [maxLongEdge] (never upscaled).
     *
     * On [OutOfMemoryError] the decode is retried once at half of [maxLongEdge].
     *
     * @throws IngestionException with [IngestionError.Io] when the source cannot be read or decoded, or when the
     *   retry also runs out of memory.
     * @throws IllegalArgumentException when [maxLongEdge] is not positive.
     */
    suspend fun decode(candidate: PhotoCandidate, maxLongEdge: Int): DecodedPhoto {
        require(maxLongEdge > 0) { "maxLongEdge must be positive: $maxLongEdge" }
        return withContext(ioDispatcher) {
            val decoded = try {
                decodeBounded(candidate.source, maxLongEdge)
            } catch (firstOom: OutOfMemoryError) {
                ensureActive()
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
        val bitmap = try {
            ImageDecoder.decodeBitmap(createSource(source)) { decoder, info, _ ->
                sourceWidth = info.size.width
                sourceHeight = info.size.height
                val (targetWidth, targetHeight) = FullResSizing.targetSize(sourceWidth, sourceHeight, maxLongEdge)
                decoder.setTargetSize(targetWidth, targetHeight)
                decoder.setTargetColorSpace(linearExtendedSrgb)
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
        return Decoded(toLinearF16(bitmap), sourceWidth, sourceHeight)
    }

    private fun createSource(source: PhotoSource): ImageDecoder.Source = when (source) {
        is PhotoSource.ContentUri -> ImageDecoder.createSource(contentResolver, source.uri.toUri())
        is PhotoSource.ExtractedFile -> ImageDecoder.createSource(File(source.path))
    }

    /**
     * Some codecs ignore the target color space or config; redraw those into a fresh RGBA_F16 linear extended sRGB
     * bitmap (Canvas performs the color space conversion), carry over the gain map, and recycle the original.
     */
    private fun toLinearF16(bitmap: Bitmap): Bitmap {
        if (bitmap.config == Bitmap.Config.RGBA_F16 && bitmap.colorSpace == linearExtendedSrgb) return bitmap
        val converted = try {
            createBitmap(
                bitmap.width,
                bitmap.height,
                Bitmap.Config.RGBA_F16,
                bitmap.hasAlpha(),
                linearExtendedSrgb,
            )
        } catch (e: OutOfMemoryError) {
            bitmap.recycle()
            throw e
        }
        Canvas(converted).drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && bitmap.hasGainmap()) {
            converted.gainmap = bitmap.gainmap
        }
        bitmap.recycle()
        return converted
    }

    private class Decoded(val bitmap: Bitmap, val sourceWidth: Int, val sourceHeight: Int)

    private companion object {
        val linearExtendedSrgb: ColorSpace = ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB)
    }
}
