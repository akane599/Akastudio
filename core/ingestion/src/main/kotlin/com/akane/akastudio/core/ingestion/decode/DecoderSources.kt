package com.akane.akastudio.core.ingestion.decode

import android.content.ContentResolver
import android.graphics.ImageDecoder
import androidx.core.net.toUri
import com.akane.akastudio.core.ingestion.model.PhotoSource
import java.io.File

/**
 * Seekable [ImageDecoder.Source] for [source]. ImageDecoder cannot read a plain InputStream, so a content URI
 * needs a [resolver]; without one this returns null.
 */
internal fun imageDecoderSource(source: PhotoSource, resolver: ContentResolver?): ImageDecoder.Source? =
    when (source) {
        is PhotoSource.ExtractedFile -> ImageDecoder.createSource(File(source.path))
        is PhotoSource.ContentUri -> resolver?.let { ImageDecoder.createSource(it, source.uri.toUri()) }
    }
