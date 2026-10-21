package com.akane.akastudio.core.ingestion.metadata

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import com.akane.akastudio.core.ingestion.api.MetadataParser
import com.akane.akastudio.core.ingestion.api.ParsedImage
import com.akane.akastudio.core.ingestion.api.SourceOpener
import com.akane.akastudio.core.ingestion.decode.imageDecoderSource
import com.akane.akastudio.core.ingestion.model.CaptureMetadata
import com.akane.akastudio.core.ingestion.model.ColorSpaceTag
import com.akane.akastudio.core.ingestion.model.ExifOrientation
import com.akane.akastudio.core.ingestion.model.ImageFormat
import com.akane.akastudio.core.ingestion.model.PhotoSource
import java.io.IOException
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bounds, EXIF and Ultra HDR detection without a full decode. Each step opens a fresh stream from [opener] and
 * closes it. Open/bounds I/O failures propagate (the caller reports the source as unreadable); EXIF and gain-map
 * failures only drop those facts.
 *
 * ImageDecoder needs a seekable source, so the DNG header fallback and the API 34 gain-map probe work for extracted
 * files always and for content URIs only when [contentResolver] is supplied.
 */
class ExifAndGainMapParser(
    private val opener: SourceOpener,
    private val sizeOf: (PhotoSource) -> Long?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val contentResolver: ContentResolver? = null,
) : MetadataParser {

    override suspend fun parse(source: PhotoSource): ParsedImage? = withContext(ioDispatcher) {
        val bounds = readBounds(source) ?: return@withContext null
        val exif = readExif(source)
        val orientation = exif?.orientation ?: ExifOrientation.NORMAL
        val swap = orientation.swapsDimensions && !bounds.upright
        val longEdge = maxOf(bounds.width, bounds.height)
        val metadata = (exif?.metadata ?: CaptureMetadata.EMPTY).copy(
            colorSpace = bounds.colorSpace,
            hasUltraHdrGainMap = detectGainMap(source, bounds.format, longEdge),
        )
        ParsedImage(
            format = bounds.format,
            widthPx = if (swap) bounds.height else bounds.width,
            heightPx = if (swap) bounds.width else bounds.height,
            byteSize = sizeOf(source),
            metadata = metadata,
        )
    }

    /** [upright] is true when the size came from ImageDecoder, which already applies EXIF orientation. */
    private class Bounds(
        val format: ImageFormat,
        val width: Int,
        val height: Int,
        val colorSpace: ColorSpaceTag,
        val upright: Boolean,
    )

    private class ExifFields(val metadata: CaptureMetadata, val orientation: ExifOrientation)

    private fun readBounds(source: PhotoSource): Bounds? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        opener.open(source).use { BitmapFactory.decodeStream(it, null, options) }
        val format = ImageFormat.fromMimeType(options.outMimeType)
            ?: SourceNames.formatFromExtension(source)
            ?: return null
        val colorSpace = ColorSpaceNames.toTag(options.outColorSpace?.name)
        if (options.outWidth > 0 && options.outHeight > 0) {
            return Bounds(format, options.outWidth, options.outHeight, colorSpace, upright = false)
        }
        if (format != ImageFormat.DNG) return null
        val (width, height) = dngHeaderSize(source) ?: return null
        return Bounds(format, width, height, colorSpace, upright = true)
    }

    /** Thrown from the header listener to stop ImageDecoder before it allocates pixels. */
    private object HeaderRead : RuntimeException()

    /** Reads the DNG size from ImageDecoder's header callback, then aborts before any pixels are decoded. */
    private fun dngHeaderSize(source: PhotoSource): Pair<Int, Int>? {
        val decoderSource = imageDecoderSource(source, contentResolver) ?: return null
        var size: Pair<Int, Int>? = null
        try {
            ImageDecoder.decodeBitmap(decoderSource) { _, info, _ ->
                size = info.size.width to info.size.height
                throw HeaderRead
            }
        } catch (e: HeaderRead) {
            // Expected: header captured.
        } catch (e: IOException) {
            return null
        } catch (e: RuntimeException) {
            return null
        }
        return size?.takeIf { (w, h) -> w > 0 && h > 0 }
    }

    private fun readExif(source: PhotoSource): ExifFields? {
        val exif = try {
            opener.open(source).use { ExifInterface(it) }
        } catch (e: IOException) {
            return null
        } catch (e: RuntimeException) {
            // ExifInterface parses untrusted bytes; a parser bug must not drop an otherwise usable image.
            return null
        }
        val fallbackZone = zone()
        val (captureMillis, hasOffset) = ExifValues.parseCaptureTime(
            exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
            exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
            fallbackZone,
        ).takeIf { it.first != null } ?: ExifValues.parseCaptureTime(
            exif.getAttribute(ExifInterface.TAG_DATETIME),
            exif.getAttribute(ExifInterface.TAG_OFFSET_TIME),
            fallbackZone,
        )
        val orientation = ExifOrientation.fromExif(
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL),
        )
        val iso = ExifValues.parseIntTag(exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY))
        val metadata = CaptureMetadata.EMPTY.copy(
            captureEpochMillis = captureMillis,
            captureHasUtcOffset = hasOffset,
            focalLength35mm = ExifValues.parseIntTag(exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM))
                ?.takeIf { it > 0 },
            fNumber = ExifValues.parseRational(exif.getAttribute(ExifInterface.TAG_F_NUMBER)),
            exposureTimeSeconds = ExifValues.parseRational(exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)),
            iso = iso?.takeIf { it > 0 },
            orientation = orientation,
        )
        return ExifFields(metadata, orientation)
    }

    private fun detectGainMap(source: PhotoSource, format: ImageFormat, longEdge: Int): Boolean {
        if (format == ImageFormat.DNG) return false
        if (scanXmpPrefix(source)) return true
        if (format == ImageFormat.JPEG || Build.VERSION.SDK_INT < 34) return false
        return decoderReportsGainMap(source, longEdge)
    }

    private fun scanXmpPrefix(source: PhotoSource): Boolean = try {
        opener.open(source).use { GainMapDetector.containsGainMapXmp(GainMapDetector.readPrefix(it)) }
    } catch (e: IOException) {
        false
    }

    /** Small software decode; [android.graphics.Bitmap.hasGainmap] covers HEIF/AVIF/WebP/PNG gain maps. */
    @RequiresApi(34)
    private fun decoderReportsGainMap(source: PhotoSource, longEdge: Int): Boolean {
        val decoderSource = imageDecoderSource(source, contentResolver) ?: return false
        return runCatching {
            val bitmap = ImageDecoder.decodeBitmap(decoderSource) { decoder, _, _ ->
                decoder.setTargetSampleSize(maxOf(1, longEdge / 64))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            try {
                bitmap.hasGainmap()
            } finally {
                bitmap.recycle()
            }
        }.getOrDefault(false)
    }
}
