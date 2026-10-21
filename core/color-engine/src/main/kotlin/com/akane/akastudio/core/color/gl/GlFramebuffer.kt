package com.akane.akastudio.core.color.gl

import android.opengl.GLES20

/**
 * A framebuffer with one single-level colour texture. GL thread only; [release] is idempotent. Production code builds
 * it with [create]; the constructor is open to the module so JVM tests can wrap a texture without a GL driver.
 */
internal class GlFramebuffer internal constructor(val id: Int, val texture: GlTexture) {
    val width: Int get() = texture.width
    val height: Int get() = texture.height
    val format: GlFormat get() = texture.format

    var isReleased: Boolean = false
        private set

    /** Binds for drawing and sets the viewport to the whole attachment. */
    fun bind() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, id)
        GLES20.glViewport(0, 0, width, height)
    }

    fun release() {
        if (isReleased) return
        isReleased = true
        GLES20.glDeleteFramebuffers(1, intArrayOf(id), 0)
        texture.release()
    }

    companion object {
        /** Creates a complete framebuffer, or throws [GlException] naming the incomplete status. */
        fun create(width: Int, height: Int, format: GlFormat): GlFramebuffer {
            val texture = GlTexture.allocate(width, height, format)
            val ids = IntArray(1)
            GLES20.glGenFramebuffers(1, ids, 0)
            val framebuffer = GlFramebuffer(ids[0], texture)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, ids[0])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture.id, 0)
            val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                framebuffer.release()
                throw GlException("framebuffer ${width}x$height $format incomplete: 0x${Integer.toHexString(status)}")
            }
            return framebuffer
        }
    }
}
