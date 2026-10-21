package com.akane.akastudio.core.export.pipeline

import com.akane.akastudio.core.color.api.HalfFloatImage
import com.akane.akastudio.core.export.encode.Rgba8Rows
import com.akane.akastudio.core.export.process.GainMapResult
import java.io.OutputStream

/**
 * Destination of an encoded export.
 *
 * Implementations create a not-yet-visible entry, hand its stream to [write]'s `block`, and publish the entry only
 * after `block` returns normally. If `block` (or the publish step) throws anything, including a
 * `CancellationException`, the entry must be removed and the throwable rethrown: no partial file may remain.
 */
fun interface EncodedSink {
    /**
     * Writes one file named [displayName] of [mimeType]. [dateTakenMillis] is the capture time to record for the
     * entry, or `null` when unknown. The stream passed to [block] is owned by the sink (it closes it).
     *
     * @return the published entry's uri.
     */
    fun write(displayName: String, mimeType: String, dateTakenMillis: Long?, block: (OutputStream) -> Unit): String
}

/** Encodes an SDR base plus gain map as an Ultra HDR JPEG (Android 14+ platform encoder in production). */
fun interface UltraHdrEncoder {
    /**
     * Writes an Ultra HDR JPEG of [sdr] (linear light) carrying [gain]'s gain map and parameters into [out] at JPEG
     * [quality] (0..100). [gain]'s own `sdr` field is ignored in favour of [sdr]. The stream is flushed, not closed.
     */
    fun encode(sdr: HalfFloatImage, gain: GainMapResult, out: OutputStream, quality: Int)
}

/** Encodes sRGB 8-bit rows as a simple lossless WebP file (a single `VP8L` chunk; Android `Bitmap` in production). */
fun interface WebpLosslessEncoder {
    /** Pulls every row of a [width] x [height] image from [rows], top to bottom, and returns the WebP file bytes. */
    fun encode(width: Int, height: Int, rows: Rgba8Rows): ByteArray
}
