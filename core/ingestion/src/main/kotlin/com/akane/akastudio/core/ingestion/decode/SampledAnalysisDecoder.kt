package com.akane.akastudio.core.ingestion.decode

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.core.graphics.scale
import com.akane.akastudio.core.ingestion.api.AnalysisDecoder
import com.akane.akastudio.core.ingestion.api.IngestionException
import com.akane.akastudio.core.ingestion.model.IngestionError
import com.akane.akastudio.core.ingestion.model.PhotoSource
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Subsampled ImageDecoder decode: never allocates the full-resolution image, lets ImageDecoder apply EXIF
 * orientation, keeps the source color space, then finishes with an exact scale and an ARGB_8888 copy if needed.
 *
 * ImageDecoder needs a seekable source, so decoding goes through [contentResolver] (content URIs) or the extracted
 * file directly.
 */
class SampledAnalysisDecoder(
    private val contentResolver: ContentResolver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AnalysisDecoder {

    override suspend fun decode(source: PhotoSource, maxEdgePx: Int): Bitmap {
        require(maxEdgePx > 0) { "maxEdgePx must be positive: $maxEdgePx" }
        return withContext(ioDispatcher) {
            val sampled = decodeSampled(source, maxEdgePx)
            toArgb8888(fitToBound(sampled, maxEdgePx))
        }
    }

    private fun decodeSampled(source: PhotoSource, maxEdgePx: Int): Bitmap {
        val decoderSource = imageDecoderSource(source, contentResolver)
            ?: throw ioFailure("no decoder source for ${source.displayName}")
        return try {
            ImageDecoder.decodeBitmap(decoderSource) { decoder, info, _ ->
                decoder.setTargetSampleSize(SampleSize.forTarget(info.size.width, info.size.height, maxEdgePx))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_DEFAULT
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IngestionException) {
            throw e
        } catch (e: IOException) {
            throw ioFailure(e)
        } catch (e: RuntimeException) {
            // ImageDecoder reports unsupported or corrupt input with IllegalArgumentException/IllegalStateException.
            throw ioFailure(e)
        }
    }

    private fun fitToBound(bitmap: Bitmap, maxEdgePx: Int): Bitmap {
        if (maxOf(bitmap.width, bitmap.height) <= maxEdgePx) return bitmap
        val (width, height) = ScaleMath.fitLongEdge(bitmap.width, bitmap.height, maxEdgePx)
        val scaled = bitmap.scale(width, height)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun toArgb8888(bitmap: Bitmap): Bitmap {
        if (bitmap.config == Bitmap.Config.ARGB_8888) return bitmap
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        bitmap.recycle()
        return copy ?: throw ioFailure("could not convert ${bitmap.config} bitmap to ARGB_8888")
    }

    private fun ioFailure(cause: Throwable): IngestionException =
        ioFailure(cause.message ?: cause.javaClass.simpleName).apply { initCause(cause) }

    private fun ioFailure(reason: String): IngestionException = IngestionException(IngestionError.Io(reason))
}
