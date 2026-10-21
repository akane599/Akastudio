package com.akane.akastudio.feature.studio.di

import android.graphics.Bitmap
import com.akane.akastudio.core.color.api.ImagePixels
import com.akane.akastudio.core.color.api.StoryRenderer
import com.akane.akastudio.core.curator.engine.asPixelSource
import com.akane.akastudio.core.ingestion.api.AnalysisDecoder
import com.akane.akastudio.core.ingestion.decode.DecodedPhoto
import com.akane.akastudio.core.ingestion.decode.FullResolutionDecoder
import com.akane.akastudio.core.ingestion.model.PhotoCandidate

/** A [DecodedPhoto]'s bitmap, recycled when the preparer frees it. */
private class BitmapImage(private val decoded: DecodedPhoto) : FullResolutionImage {
    val bitmap: Bitmap get() = decoded.bitmap
    override val sourceWidth: Int get() = decoded.sourceWidth
    override val sourceHeight: Int get() = decoded.sourceHeight
    override fun free() = decoded.bitmap.recycle()
}

/**
 * [PreparerBackend] over the real [FullResolutionDecoder], [StoryRenderer] and [analysisDecoder] (the curator's
 * subsampled decoder, which also produces the horizon detector's input).
 */
internal class AndroidPreparerBackend(
    private val decoder: FullResolutionDecoder,
    private val renderer: StoryRenderer,
    private val analysisDecoder: AnalysisDecoder,
) : PreparerBackend {

    override suspend fun maxTextureSize(): Int = renderer.maxTextureSize()

    override suspend fun decodeFull(candidate: PhotoCandidate, maxLongEdge: Int): FullResolutionImage =
        BitmapImage(decoder.decode(candidate, maxLongEdge))

    override suspend fun register(key: String, image: FullResolutionImage) {
        renderer.registerImage(key, (image as BitmapImage).bitmap)
    }

    override suspend fun unregister(key: String) = renderer.unregisterImage(key)

    override suspend fun analysisPixels(candidate: PhotoCandidate, maxEdgePx: Int): ImagePixels {
        val image = analysisDecoder.asPixelSource(maxEdgePx).load(candidate)
        image.bitmap?.recycle()
        return image.pixels
    }
}
