package com.akane.akastudio.core.export.store

import android.annotation.TargetApi
import android.graphics.Bitmap
import android.graphics.Gainmap
import android.os.Build
import com.akane.akastudio.core.color.api.HalfFloatImage
import com.akane.akastudio.core.export.encode.Rgba8Rows
import com.akane.akastudio.core.export.pipeline.UltraHdrEncoder
import com.akane.akastudio.core.export.pipeline.WebpLosslessEncoder
import com.akane.akastudio.core.export.process.Dither
import com.akane.akastudio.core.export.process.GainMapResult
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * Ultra HDR JPEG through the platform: an sRGB ARGB_8888 base (blue-noise dithered), a single-channel ALPHA_8
 * [Gainmap] with the computed ratios, gamma and offsets, and `Bitmap.compress(JPEG)`, which writes the gain map
 * container on Android 14+.
 *
 * Requires API 34 (`androidx.annotation` is not on this module's classpath, so the requirement is declared with
 * [TargetApi] and enforced at construction).
 *
 * @throws IllegalStateException from the constructor below API 34.
 */
@TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class PlatformUltraHdrEncoder : UltraHdrEncoder {

    init {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            "Ultra HDR encoding needs API ${Build.VERSION_CODES.UPSIDE_DOWN_CAKE}, device is ${Build.VERSION.SDK_INT}"
        }
    }

    override fun encode(sdr: HalfFloatImage, gain: GainMapResult, out: OutputStream, quality: Int) {
        require(quality in 0..100) { "JPEG quality must be in 0..100: $quality" }
        require(gain.gainWidth > 0 && gain.gainHeight > 0 && gain.gain.size == gain.gainWidth * gain.gainHeight) {
            "gain map holds ${gain.gain.size} codes for ${gain.gainWidth}x${gain.gainHeight}"
        }
        val base = Bitmap.createBitmap(sdr.width, sdr.height, Bitmap.Config.ARGB_8888)
        val contents = Bitmap.createBitmap(gain.gainWidth, gain.gainHeight, Bitmap.Config.ALPHA_8)
        try {
            val row = IntArray(sdr.width)
            for (y in 0 until sdr.height) {
                Dither.toSrgb8Row(sdr, y, row)
                base.setPixels(row, 0, sdr.width, 0, y, sdr.width, 1)
            }
            val stride = contents.rowBytes
            val codes = ByteBuffer.allocate(stride * gain.gainHeight)
            for (y in 0 until gain.gainHeight) {
                codes.position(y * stride)
                codes.put(gain.gain, y * gain.gainWidth, gain.gainWidth)
            }
            codes.rewind()
            contents.copyPixelsFromBuffer(codes)
            val minDisplayRatio = max(1f, gain.displayRatioSdr)
            base.gainmap = Gainmap(contents).apply {
                setRatioMin(gain.ratioMin, gain.ratioMin, gain.ratioMin)
                setRatioMax(gain.ratioMax, gain.ratioMax, gain.ratioMax)
                setGamma(gain.gamma, gain.gamma, gain.gamma)
                setEpsilonSdr(gain.epsilonSdr, gain.epsilonSdr, gain.epsilonSdr)
                setEpsilonHdr(gain.epsilonHdr, gain.epsilonHdr, gain.epsilonHdr)
                setMinDisplayRatioForHdrTransition(minDisplayRatio)
                setDisplayRatioForFullHdr(max(minDisplayRatio, gain.displayRatioHdr))
            }
            if (!base.compress(Bitmap.CompressFormat.JPEG, quality, out)) throw IOException("Ultra HDR JPEG compression failed")
            out.flush()
        } finally {
            base.recycle()
            contents.recycle()
        }
    }
}

/** Lossless WebP through `Bitmap.compress(WEBP_LOSSLESS, 100)`; rows are copied into an ARGB_8888 bitmap first. */
internal class PlatformWebpLosslessEncoder : WebpLosslessEncoder {

    override fun encode(width: Int, height: Int, rows: Rgba8Rows): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val row = IntArray(width)
            for (y in 0 until height) {
                rows.row(y, row)
                bitmap.setPixels(row, 0, width, 0, y, width, 1)
            }
            val out = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)) throw IOException("WebP lossless compression failed")
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }
}
