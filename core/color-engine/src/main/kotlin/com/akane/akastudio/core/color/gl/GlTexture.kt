package com.akane.akastudio.core.color.gl

import android.opengl.GLES20
import android.opengl.GLES30
import java.nio.Buffer

/** Sized internal format with the client format/type used to upload or read it. */
internal enum class GlFormat(val internalFormat: Int, val format: Int, val type: Int, val bytesPerPixel: Int) {
    /** Linear-light working format. */
    RGBA16F(GLES30.GL_RGBA16F, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, 8),

    /** Display/readback targets and the log-domain fallback working format. */
    RGBA8(GLES30.GL_RGBA8, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, 4),

    /** Opaque sRGB images decoded to linear by the sampler. */
    SRGB8_ALPHA8(GLES30.GL_SRGB8_ALPHA8, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, 4),
}

/** An immutable-storage 2D texture. GL thread only; [release] is idempotent. */
internal class GlTexture private constructor(
    val id: Int,
    val width: Int,
    val height: Int,
    val format: GlFormat,
    val levels: Int,
) {
    var isReleased: Boolean = false
        private set

    fun bind(unit: Int = 0) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
    }

    /** Uploads the top-first level-0 pixels in [format]'s client layout, then builds the mip chain if any. */
    fun uploadLevel0(pixels: Buffer) {
        bind()
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height, format.format, format.type, pixels)
        if (levels > 1) GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        checkGlError("texture upload ${width}x$height $format")
    }

    fun release() {
        if (isReleased) return
        isReleased = true
        GLES20.glDeleteTextures(1, intArrayOf(id), 0)
    }

    companion object {
        /**
         * Allocates [levels] levels of storage, clamp-to-edge, bilinear (trilinear when mipmapped).
         * Throws [GlException] if the driver rejects the allocation.
         */
        fun allocate(width: Int, height: Int, format: GlFormat, levels: Int = 1): GlTexture {
            require(width > 0 && height > 0 && levels >= 1)
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            val texture = GlTexture(ids[0], width, height, format, levels)
            texture.bind()
            GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, levels, format.internalFormat, width, height)
            val minFilter = if (levels > 1) GLES20.GL_LINEAR_MIPMAP_LINEAR else GLES20.GL_LINEAR
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, minFilter)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            try {
                checkGlError("glTexStorage2D ${width}x$height $format levels=$levels")
            } catch (e: GlException) {
                texture.release()
                throw e
            }
            return texture
        }
    }
}
