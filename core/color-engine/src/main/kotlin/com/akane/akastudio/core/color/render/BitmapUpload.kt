package com.akane.akastudio.core.color.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Paint
import com.akane.akastudio.core.color.gl.GlCapabilities
import com.akane.akastudio.core.color.gl.GlFormat
import com.akane.akastudio.core.color.gl.GlTexture
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Bitmap -> premultiplied linear-light texture (sRGB primaries). GL thread only.
 *
 * - RGBA_F16 already in LINEAR_EXTENDED_SRGB and premultiplied (or opaque): uploaded as RGBA16F directly.
 * - Opaque ARGB_8888 sRGB: uploaded as SRGB8_ALPHA8 so the sampler decodes to linear.
 * - Anything else (Display P3, Adobe RGB, translucent 8-bit, other configs, hardware bitmaps): drawn through
 *   an Android Canvas onto an RGBA_F16 LINEAR_EXTENDED_SRGB bitmap, which colour-manages the conversion,
 *   then uploaded as RGBA16F.
 */
internal object BitmapUpload {
    private val linearExtendedSrgb: ColorSpace = ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB)
    private val srgb: ColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)

    /** Throws [IllegalArgumentException] for recycled bitmaps or sizes above `GL_MAX_TEXTURE_SIZE`. */
    fun upload(bitmap: Bitmap, capabilities: GlCapabilities): GlTexture {
        require(!bitmap.isRecycled) { "bitmap is recycled" }
        val max = capabilities.maxTextureSize
        require(bitmap.width in 1..max && bitmap.height in 1..max) {
            "bitmap ${bitmap.width}x${bitmap.height} exceeds GL_MAX_TEXTURE_SIZE $max; decode within maxTextureSize()"
        }
        return when {
            isLinearF16(bitmap) -> uploadPixels(bitmap, GlFormat.RGBA16F, capabilities)
            isOpaqueSrgb8(bitmap) -> uploadPixels(bitmap, GlFormat.SRGB8_ALPHA8, capabilities)
            else -> {
                val converted = toLinearF16(bitmap)
                try {
                    uploadPixels(converted, GlFormat.RGBA16F, capabilities)
                } finally {
                    converted.recycle()
                }
            }
        }
    }

    private fun isLinearF16(bitmap: Bitmap): Boolean =
        bitmap.config == Bitmap.Config.RGBA_F16 &&
            bitmap.colorSpace == linearExtendedSrgb &&
            (bitmap.isPremultiplied || !bitmap.hasAlpha())

    private fun isOpaqueSrgb8(bitmap: Bitmap): Boolean =
        bitmap.config == Bitmap.Config.ARGB_8888 && bitmap.colorSpace == srgb && !bitmap.hasAlpha()

    private fun toLinearF16(bitmap: Bitmap): Bitmap {
        // Software canvases cannot read hardware bitmaps; copy to a software bitmap first (same colour space).
        val source = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.RGBA_F16, false) ?: throw IllegalArgumentException("cannot copy hardware bitmap")
        } else {
            bitmap
        }
        try {
            val target = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.RGBA_F16, true, linearExtendedSrgb)
            Canvas(target).drawBitmap(source, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            return target
        } finally {
            if (source !== bitmap) source.recycle()
        }
    }

    private fun uploadPixels(bitmap: Bitmap, format: GlFormat, capabilities: GlCapabilities): GlTexture {
        val buffer = ByteBuffer.allocateDirect(bitmap.byteCount).order(ByteOrder.nativeOrder())
        bitmap.copyPixelsToBuffer(buffer)
        buffer.rewind()
        val levels = if (capabilities.canMipmap(format)) RenderMath.mipLevelCount(bitmap.width, bitmap.height) else 1
        val texture = GlTexture.allocate(bitmap.width, bitmap.height, format, levels)
        try {
            texture.uploadLevel0(buffer)
        } catch (e: RuntimeException) {
            texture.release()
            throw e
        }
        return texture
    }
}
