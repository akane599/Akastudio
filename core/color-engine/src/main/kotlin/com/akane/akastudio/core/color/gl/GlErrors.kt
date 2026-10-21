package com.akane.akastudio.core.color.gl

import android.opengl.GLES20
import android.util.Log

private const val TAG = "GlErrors"

/** A GL/EGL call failed in a way the renderer cannot recover from locally. */
internal class GlException(message: String) : RuntimeException(message)

/** Empties the GL error queue and returns the first error that was pending, or `GL_NO_ERROR`. */
private fun drainGlErrors(): Int {
    var first = GLES20.GL_NO_ERROR
    while (true) {
        val error = GLES20.glGetError()
        if (error == GLES20.GL_NO_ERROR) break
        if (first == GLES20.GL_NO_ERROR) first = error
    }
    return first
}

/** Drains the GL error queue and throws if any error was pending after [operation]. */
internal fun checkGlError(operation: String) {
    val first = drainGlErrors()
    if (first != GLES20.GL_NO_ERROR) throw GlException("$operation failed: GL error 0x${Integer.toHexString(first)}")
}

/**
 * Discards errors an earlier, unrelated call left pending, so the next [checkGlError] blames only [operation]'s own
 * calls. A present to a window surface that is no longer available leaves GL_INVALID_OPERATION queued, for example.
 */
internal fun discardStaleGlErrors(operation: String) {
    val stale = drainGlErrors()
    if (stale != GLES20.GL_NO_ERROR) Log.w(TAG, "Discarded stale GL error 0x${Integer.toHexString(stale)} pending before $operation")
}
