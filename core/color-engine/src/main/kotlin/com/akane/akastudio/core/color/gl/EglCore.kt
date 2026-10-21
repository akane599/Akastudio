package com.akane.akastudio.core.color.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface

/** Outcome of an EGL call that can lose its surface or context without it being a programming error. */
internal enum class EglStatus { OK, SURFACE_LOST, CONTEXT_LOST }

/**
 * EGL14 owner: one OpenGL ES 3 context, a 1x1 pbuffer for offscreen work, and one cached window surface
 * (recreated when a different or invalid [Surface] is presented). GL thread only.
 */
internal class EglCore {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var config: EGLConfig? = null
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var windowSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var windowTarget: Surface? = null

    /** Creates the display connection, context and pbuffer and makes the pbuffer current. */
    fun initialize() {
        val newDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (newDisplay == EGL14.EGL_NO_DISPLAY) throw GlException("eglGetDisplay failed: ${eglErrorHex()}")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(newDisplay, version, 0, version, 1)) throw GlException("eglInitialize failed: ${eglErrorHex()}")
        display = newDisplay
        val chosen = chooseConfig(newDisplay)
        config = chosen
        val newContext = EGL14.eglCreateContext(
            newDisplay, chosen, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
        )
        if (newContext == null || newContext == EGL14.EGL_NO_CONTEXT) throw GlException("eglCreateContext(ES 3) failed: ${eglErrorHex()}")
        context = newContext
        val newPbuffer = EGL14.eglCreatePbufferSurface(
            newDisplay, chosen,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        if (newPbuffer == null || newPbuffer == EGL14.EGL_NO_SURFACE) throw GlException("eglCreatePbufferSurface failed: ${eglErrorHex()}")
        pbuffer = newPbuffer
        if (makeCurrentOffscreen() != EglStatus.OK) throw GlException("eglMakeCurrent(pbuffer) failed: ${eglErrorHex()}")
    }

    /** Binds the context to the 1x1 pbuffer. */
    fun makeCurrentOffscreen(): EglStatus = makeCurrent(pbuffer, isWindow = false)

    /**
     * Returns the cached window surface for [surface], creating it when the target changed. Returns null
     * (after dropping any stale cache entry) when the Surface is invalid or EGL refuses its native window.
     */
    fun windowSurfaceFor(surface: Surface): EGLSurface? {
        if (!surface.isValid) {
            dropWindowSurface()
            return null
        }
        if (surface === windowTarget && windowSurface != EGL14.EGL_NO_SURFACE) return windowSurface
        dropWindowSurface()
        val created = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (created == null || created == EGL14.EGL_NO_SURFACE) {
            val error = EGL14.eglGetError()
            if (error == EGL14.EGL_BAD_NATIVE_WINDOW || error == EGL14.EGL_BAD_ALLOC || error == EGL14.EGL_BAD_SURFACE) return null
            throw GlException("eglCreateWindowSurface failed: 0x${Integer.toHexString(error)}")
        }
        windowSurface = created
        windowTarget = surface
        return created
    }

    /** Binds the context to a window surface obtained from [windowSurfaceFor]. */
    fun makeCurrentWindow(surface: EGLSurface): EglStatus = makeCurrent(surface, isWindow = true)

    /** Presents the window surface. */
    fun swapBuffers(surface: EGLSurface): EglStatus {
        if (EGL14.eglSwapBuffers(display, surface)) return EglStatus.OK
        return classify(EGL14.eglGetError(), "eglSwapBuffers", isWindow = true)
    }

    /** Destroys the cached window surface (after making the pbuffer current if it was bound). */
    fun dropWindowSurface() {
        if (windowSurface != EGL14.EGL_NO_SURFACE) {
            if (EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW) == windowSurface) makeCurrentOffscreen()
            EGL14.eglDestroySurface(display, windowSurface)
        }
        windowSurface = EGL14.EGL_NO_SURFACE
        windowTarget = null
    }

    /** Unbinds the context from this thread; GL calls become no-ops until something is made current again. */
    fun makeNothingCurrent() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        }
    }

    /**
     * Destroys every surface and the context. The display is left initialized: it is the process-wide
     * default display, shared with the platform's own renderer.
     */
    fun release() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        makeNothingCurrent()
        if (windowSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, windowSurface)
        if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        windowSurface = EGL14.EGL_NO_SURFACE
        windowTarget = null
        pbuffer = EGL14.EGL_NO_SURFACE
        context = EGL14.EGL_NO_CONTEXT
        config = null
        display = EGL14.EGL_NO_DISPLAY
    }

    private fun makeCurrent(surface: EGLSurface, isWindow: Boolean): EglStatus {
        if (EGL14.eglMakeCurrent(display, surface, surface, context)) return EglStatus.OK
        return classify(EGL14.eglGetError(), "eglMakeCurrent", isWindow)
    }

    private fun classify(error: Int, operation: String, isWindow: Boolean): EglStatus = when {
        error == EGL14.EGL_CONTEXT_LOST -> EglStatus.CONTEXT_LOST
        isWindow && (error == EGL14.EGL_BAD_SURFACE || error == EGL14.EGL_BAD_NATIVE_WINDOW) -> EglStatus.SURFACE_LOST
        else -> throw GlException("$operation failed: 0x${Integer.toHexString(error)}")
    }

    private fun chooseConfig(display: EGLDisplay): EGLConfig {
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, configs.size, count, 0) || count[0] < 1) {
            throw GlException("eglChooseConfig found no RGBA8888 ES 3 config: ${eglErrorHex()}")
        }
        return configs[0] ?: throw GlException("eglChooseConfig returned a null config")
    }

    private fun eglErrorHex(): String = "0x${Integer.toHexString(EGL14.eglGetError())}"
}
