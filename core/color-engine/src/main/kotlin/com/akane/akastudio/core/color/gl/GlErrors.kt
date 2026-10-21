package com.akane.akastudio.core.color.gl

import android.opengl.GLES20

/** A GL/EGL call failed in a way the renderer cannot recover from locally. */
internal class GlException(message: String) : RuntimeException(message)

/** Drains the GL error queue and throws if any error was pending after [operation]. */
internal fun checkGlError(operation: String) {
    var first = GLES20.GL_NO_ERROR
    while (true) {
        val error = GLES20.glGetError()
        if (error == GLES20.GL_NO_ERROR) break
        if (first == GLES20.GL_NO_ERROR) first = error
    }
    if (first != GLES20.GL_NO_ERROR) throw GlException("$operation failed: GL error 0x${Integer.toHexString(first)}")
}
