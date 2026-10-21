package com.akane.akastudio.core.color.gl

import android.opengl.GLES20
import android.opengl.GLES30

/**
 * Limits and render formats of the current context.
 *
 * [floatColorBuffer] is true when RGBA16F is colour-renderable (`EXT_color_buffer_half_float` or
 * `EXT_color_buffer_float`, confirmed by a completeness probe). When false, intermediate targets fall back
 * to RGBA8 holding log-encoded linear light (see `RenderMath.logEncode`): values are clamped to [0, 16],
 * negatives are lost, the code step is about 2.8 % of the value near diffuse white (8-bit sRGB: about
 * 0.9 %), partially covered pixels blend in the encoded domain, and registered RGBA16F images get no mip
 * chain (ES 3.0 only generates mipmaps for renderable formats), so heavy downscales alias more.
 */
internal data class GlCapabilities(
    val maxTextureSize: Int,
    val maxRenderbufferSize: Int,
    val maxViewportWidth: Int,
    val maxViewportHeight: Int,
    val floatColorBuffer: Boolean,
) {
    /** Format of every linear intermediate target. */
    val workingFormat: GlFormat get() = if (floatColorBuffer) GlFormat.RGBA16F else GlFormat.RGBA8

    /** True when [workingFormat] stores log-encoded light. */
    val logDomain: Boolean get() = !floatColorBuffer

    /** Largest framebuffer edge usable as a render target in each axis. */
    val maxTargetWidth: Int get() = minOf(maxTextureSize, maxRenderbufferSize, maxViewportWidth)
    val maxTargetHeight: Int get() = minOf(maxTextureSize, maxRenderbufferSize, maxViewportHeight)

    /** Whether a texture of [format] may get a generated mip chain. */
    fun canMipmap(format: GlFormat): Boolean = format != GlFormat.RGBA16F || floatColorBuffer

    companion object {
        /** Queries the current context. */
        fun query(): GlCapabilities {
            val value = IntArray(2)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, value, 0)
            val maxTexture = value[0]
            GLES20.glGetIntegerv(GLES20.GL_MAX_RENDERBUFFER_SIZE, value, 0)
            val maxRenderbuffer = value[0]
            GLES20.glGetIntegerv(GLES20.GL_MAX_VIEWPORT_DIMS, value, 0)
            val viewportWidth = value[0]
            val viewportHeight = value[1]
            checkGlError("limit queries")
            val extensions = extensions()
            val advertised = "GL_EXT_color_buffer_half_float" in extensions || "GL_EXT_color_buffer_float" in extensions
            return GlCapabilities(
                maxTextureSize = maxTexture,
                maxRenderbufferSize = maxRenderbuffer,
                maxViewportWidth = viewportWidth,
                maxViewportHeight = viewportHeight,
                floatColorBuffer = advertised && halfFloatTargetIsComplete(),
            )
        }

        private fun extensions(): Set<String> {
            val count = IntArray(1)
            GLES20.glGetIntegerv(GLES30.GL_NUM_EXTENSIONS, count, 0)
            return (0 until count[0]).mapNotNullTo(HashSet()) { GLES30.glGetStringi(GLES20.GL_EXTENSIONS, it) }
        }

        private fun halfFloatTargetIsComplete(): Boolean = try {
            GlFramebuffer.create(1, 1, GlFormat.RGBA16F).release()
            true
        } catch (_: GlException) {
            false
        }
    }
}
