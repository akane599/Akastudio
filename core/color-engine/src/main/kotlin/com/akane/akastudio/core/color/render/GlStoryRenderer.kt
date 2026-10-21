package com.akane.akastudio.core.color.render

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import android.view.Surface
import com.akane.akastudio.core.color.api.HalfFloatImage
import com.akane.akastudio.core.color.api.ImagePixels
import com.akane.akastudio.core.color.api.PreviewOptions
import com.akane.akastudio.core.color.api.Rect4f
import com.akane.akastudio.core.color.api.RenderScene
import com.akane.akastudio.core.color.api.StoryRenderer
import com.akane.akastudio.core.color.gl.EglCore
import com.akane.akastudio.core.color.gl.EglStatus
import com.akane.akastudio.core.color.gl.FboPool
import com.akane.akastudio.core.color.gl.FullscreenQuad
import com.akane.akastudio.core.color.gl.GlCapabilities
import com.akane.akastudio.core.color.gl.GlEnvironment
import com.akane.akastudio.core.color.gl.GlFormat
import com.akane.akastudio.core.color.gl.GlThread
import com.akane.akastudio.core.color.gl.checkGlError
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * OpenGL ES 3 [StoryRenderer]. Every public call hops onto one dedicated GL thread, so callers are
 * main-safe. The EGL context is created lazily on first use; when EGL reports `EGL_CONTEXT_LOST` the
 * context is rebuilt and registered images are re-uploaded from their retained Bitmaps.
 *
 * Output alpha is premultiplied linear light. A context lost in the middle of an offscreen render is only
 * detected by the next call (ES 3.0 has no reset query), so that one result may be blank.
 */
internal class GlStoryRenderer(
    private val assets: AssetManager,
    private val graderFactory: (GlEnvironment) -> LayerGrader,
) : StoryRenderer {

    /** Per-context resources; rebuilt after context loss. */
    private class Runtime(
        val capabilities: GlCapabilities,
        val pool: FboPool,
        val quad: FullscreenQuad,
        val programs: CompositorPrograms,
        val text: TextOverlayCache,
        val grader: LayerGrader,
        val compositor: SceneCompositor,
    )

    private val glThread = GlThread(THREAD_NAME)
    private val closed = AtomicBoolean(false)

    // GL-thread confined.
    private val images = ImageRegistry()
    private var egl: EglCore? = null
    private var runtime: Runtime? = null

    override suspend fun registerImage(key: String, bitmap: Bitmap) = onGl { rt ->
        images.register(key, bitmap, rt.capabilities)
    }

    override suspend fun unregisterImage(key: String) = onGl {
        images.unregister(key)
    }

    override suspend fun maxTextureSize(): Int = onGl { rt -> rt.capabilities.maxTextureSize }

    override suspend fun renderToSurface(
        scene: RenderScene,
        surface: Surface,
        surfaceWidth: Int,
        surfaceHeight: Int,
        options: PreviewOptions,
    ) = onGl { first ->
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return@onGl
        if (presentOnce(first, scene, surface, surfaceWidth, surfaceHeight, options)) {
            // Context lost mid-frame: rebuild once and redraw so a static preview is not left blank.
            val rebuilt = recoverContext()
            if (presentOnce(rebuilt, scene, surface, surfaceWidth, surfaceHeight, options)) recoverContext()
        }
    }

    override suspend fun renderOffscreen(scene: RenderScene): HalfFloatImage = onGl { rt ->
        try {
            frame(rt) { renderOffscreenOn(rt, scene) }
        } finally {
            // An export's full-size grades and targets are not reused by the preview, and nothing ages them out once
            // rendering stops: free them now instead of pinning hundreds of MB until the next frames.
            rt.grader.trim(GradeSlotSpace.EXPORT)
            rt.pool.releaseFree()
        }
    }

    override suspend fun renderPreviewPixels(scene: RenderScene, width: Int, height: Int): ImagePixels = onGl { rt ->
        frame(rt) { renderPreviewOn(rt, scene, width, height) }
    }

    /** Never creates the context: with no runtime there is nothing to trim. */
    override fun trimMemory() {
        postBestEffort("memory trim") {
            val core = egl ?: return@postBestEffort
            core.dropWindowSurface()
            val rt = runtime ?: return@postBestEffort
            // A lost context is rebuilt (and its objects dropped) by the next call; nothing to free in it now.
            if (core.makeCurrentOffscreen() != EglStatus.OK) return@postBestEffort
            GradeSlotSpace.entries.forEach(rt.grader::trim)
            rt.text.releaseAll()
            rt.pool.releaseFree()
        }
    }

    override fun releaseSurface(surface: Surface) {
        postBestEffort("window surface release") { egl?.dropWindowSurface(surface) }
    }

    /** Queues [block] on the GL thread unless the renderer is closed; failures are logged, never thrown. */
    private fun postBestEffort(what: String, block: () -> Unit) {
        if (closed.get()) return
        glThread.post {
            if (closed.get()) return@post
            try {
                block()
            } catch (e: RuntimeException) {
                Log.w(TAG, "$what failed", e)
            }
        }
    }

    /** Asynchronous and idempotent: queued calls finish first, then GL objects, EGL and the thread go. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val posted = glThread.post {
            try {
                egl?.makeCurrentOffscreen()
                teardownRuntime()
                images.clear()
            } catch (e: RuntimeException) {
                Log.e(TAG, "GL teardown failed; releasing EGL anyway", e)
            } finally {
                egl?.release()
                egl = null
                glThread.quitSafely()
            }
        }
        if (!posted) glThread.quitSafely()
    }

    private suspend fun <T> onGl(block: (Runtime) -> T): T {
        check(!closed.get()) { CLOSED_MESSAGE }
        return withContext(glThread.dispatcher) {
            check(!closed.get()) { CLOSED_MESSAGE }
            block(ensureRuntime())
        }
    }

    private inline fun <T> frame(rt: Runtime, render: () -> T): T = try {
        render()
    } finally {
        rt.pool.endFrame()
        rt.text.endFrame()
    }

    /** Returns true when the context was lost (the caller recovers); every other failure mode returns false. */
    private fun presentOnce(
        rt: Runtime,
        scene: RenderScene,
        surface: Surface,
        surfaceWidth: Int,
        surfaceHeight: Int,
        options: PreviewOptions,
    ): Boolean {
        val core = egl ?: return false
        val window = core.windowSurfaceFor(surface) ?: return false
        when (core.makeCurrentWindow(window)) {
            EglStatus.OK -> Unit
            EglStatus.SURFACE_LOST -> {
                core.dropWindowSurface()
                return false
            }
            EglStatus.CONTEXT_LOST -> return true
        }
        val caps = rt.capabilities
        val fit = RenderMath.letterbox(scene.canvasWidth, scene.canvasHeight, surfaceWidth, surfaceHeight)
        val width = min(fit.width, caps.maxTargetWidth)
        val height = min(fit.height, caps.maxTargetHeight)
        val scaleX = width.toFloat() / scene.canvasWidth
        val scaleY = height.toFloat() / scene.canvasHeight
        val split = options.compareSplit?.coerceIn(0f, 1f)
        frame(rt) {
            val prepared = rt.compositor.prepare(scene, scaleX, scaleY, withNeutral = split != null, GradeSlotSpace.PREVIEW)
            val sceneTarget = try {
                rt.pool.acquire(width, height, caps.workingFormat)
            } catch (e: RuntimeException) {
                prepared.release(rt.pool)
                throw e
            }
            try {
                sceneTarget.bind()
                rt.compositor.draw(
                    prepared, width, height, ViewTransform(scaleX, scaleY, 0f, 0f), min(scaleX, scaleY),
                    scissor = null, splitX = split?.let { (it * width).roundToInt() },
                )
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
                rt.compositor.clearDisplay(MATTE_ARGB)
                val box = Rect4f(fit.x.toFloat(), fit.y.toFloat(), (fit.x + fit.width).toFloat(), (fit.y + fit.height).toFloat())
                rt.compositor.present(sceneTarget.texture, box, surfaceWidth, surfaceHeight, MATTE_ARGB)
                if (split != null) {
                    val x = box.left + split * fit.width
                    val half = DIVIDER_WIDTH_PX / 2f
                    rt.compositor.fillDisplay(Rect4f(x - half, box.top, x + half, box.bottom), surfaceWidth, surfaceHeight, DIVIDER_ARGB)
                }
            } finally {
                rt.pool.recycle(sceneTarget)
                prepared.release(rt.pool)
            }
        }
        return when (core.swapBuffers(window)) {
            EglStatus.OK -> false
            EglStatus.SURFACE_LOST -> {
                core.dropWindowSurface()
                false
            }
            EglStatus.CONTEXT_LOST -> true
        }
    }

    private fun renderOffscreenOn(rt: Runtime, scene: RenderScene): HalfFloatImage {
        val canvasWidth = scene.canvasWidth
        val canvasHeight = scene.canvasHeight
        require(canvasWidth > 0 && canvasHeight > 0) { "scene canvas must be positive: ${canvasWidth}x$canvasHeight" }
        require(canvasWidth.toLong() * canvasHeight * 4 <= Int.MAX_VALUE) { "canvas ${canvasWidth}x$canvasHeight too large for one HalfFloatImage" }
        val caps = rt.capabilities
        val tiles = RenderMath.planTiles(canvasWidth, canvasHeight, caps.maxTargetWidth, caps.maxTargetHeight)
        val tileWidth = tiles.maxOf { it.width }
        val tileHeight = tiles.maxOf { it.height }
        val output = ShortArray(canvasWidth * canvasHeight * 4)
        val prepared = rt.compositor.prepare(scene, 1f, 1f, withNeutral = false, GradeSlotSpace.EXPORT)
        try {
            val target = rt.pool.acquire(tileWidth, tileHeight, caps.workingFormat)
            try {
                for (tile in tiles) {
                    target.bind()
                    // The tile's top-left canvas pixel lands at the target's top-left; GL rows count from the bottom.
                    val region = PixelRect(0, tileHeight - tile.height, tile.width, tile.height)
                    rt.compositor.draw(
                        prepared, tileWidth, tileHeight,
                        ViewTransform(1f, 1f, -tile.x.toFloat(), -tile.y.toFloat()), 1f,
                        scissor = region, splitX = null,
                    )
                    val pixels = readWorkingPixels(caps, region)
                    RenderMath.blitTile(pixels, tile.width, tile.height, output, canvasWidth, tile.x, tile.y, CHANNELS)
                }
            } finally {
                rt.pool.recycle(target)
            }
        } finally {
            prepared.release(rt.pool)
        }
        return HalfFloatImage(canvasWidth, canvasHeight, output)
    }

    private fun renderPreviewOn(rt: Runtime, scene: RenderScene, width: Int, height: Int): ImagePixels {
        val caps = rt.capabilities
        require(width > 0 && height > 0) { "preview size must be positive: ${width}x$height" }
        require(width <= caps.maxTargetWidth && height <= caps.maxTargetHeight) {
            "preview ${width}x$height exceeds the render target limit ${caps.maxTargetWidth}x${caps.maxTargetHeight}"
        }
        require(scene.canvasWidth > 0 && scene.canvasHeight > 0) { "scene canvas must be positive" }
        val scaleX = width.toFloat() / scene.canvasWidth
        val scaleY = height.toFloat() / scene.canvasHeight
        val prepared = rt.compositor.prepare(scene, scaleX, scaleY, withNeutral = false, GradeSlotSpace.SCOPE)
        try {
            val sceneTarget = rt.pool.acquire(width, height, caps.workingFormat)
            try {
                val displayTarget = rt.pool.acquire(width, height, GlFormat.RGBA8)
                try {
                    sceneTarget.bind()
                    rt.compositor.draw(prepared, width, height, ViewTransform(scaleX, scaleY, 0f, 0f), min(scaleX, scaleY), null, null)
                    displayTarget.bind()
                    rt.compositor.present(sceneTarget.texture, Rect4f(0f, 0f, width.toFloat(), height.toFloat()), width, height, MATTE_ARGB)
                    val bytes = ByteBuffer.allocateDirect(width * height * CHANNELS).order(ByteOrder.nativeOrder())
                    GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
                    GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
                    checkGlError("preview glReadPixels")
                    val raw = ByteArray(width * height * CHANNELS)
                    bytes.get(raw)
                    return ImagePixels(width, height, RenderMath.rgbaBottomUpToArgb(raw, width, height))
                } finally {
                    rt.pool.recycle(displayTarget)
                }
            } finally {
                rt.pool.recycle(sceneTarget)
            }
        } finally {
            prepared.release(rt.pool)
        }
    }

    /**
     * Reads [region] of the bound working target as linear premultiplied half floats, top row first.
     * Uses (RGBA, HALF_FLOAT) when the implementation offers it, else the always-legal (RGBA, FLOAT) for
     * float targets; log-domain RGBA8 targets are read as bytes and decoded.
     */
    private fun readWorkingPixels(caps: GlCapabilities, region: PixelRect): ShortArray {
        val count = region.width * region.height * CHANNELS
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
        val halves = if (caps.logDomain) {
            val buffer = ByteBuffer.allocateDirect(count).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(region.x, region.y, region.width, region.height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
            checkGlError("log-domain glReadPixels")
            val raw = ByteArray(count)
            buffer.get(raw)
            RenderMath.logBytesToHalf(raw)
        } else if (implementationReadsHalfFloat()) {
            val buffer = ByteBuffer.allocateDirect(count * Short.SIZE_BYTES).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(region.x, region.y, region.width, region.height, GLES20.GL_RGBA, GLES30.GL_HALF_FLOAT, buffer)
            checkGlError("half-float glReadPixels")
            ShortArray(count).also { buffer.asShortBuffer().get(it) }
        } else {
            val buffer = ByteBuffer.allocateDirect(count * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(region.x, region.y, region.width, region.height, GLES20.GL_RGBA, GLES20.GL_FLOAT, buffer)
            checkGlError("float glReadPixels")
            val floats = FloatArray(count)
            buffer.asFloatBuffer().get(floats)
            RenderMath.floatsToHalf(floats)
        }
        RenderMath.flipRows(halves, region.width * CHANNELS, region.height)
        return halves
    }

    private fun implementationReadsHalfFloat(): Boolean {
        val format = IntArray(1)
        val type = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_IMPLEMENTATION_COLOR_READ_FORMAT, format, 0)
        GLES20.glGetIntegerv(GLES20.GL_IMPLEMENTATION_COLOR_READ_TYPE, type, 0)
        return format[0] == GLES20.GL_RGBA && type[0] == GLES30.GL_HALF_FLOAT
    }

    private fun ensureRuntime(): Runtime {
        val core = egl
        val current = runtime
        if (core == null || current == null) return createContext()
        return when (core.makeCurrentOffscreen()) {
            EglStatus.OK -> current
            EglStatus.CONTEXT_LOST, EglStatus.SURFACE_LOST -> recoverContext()
        }
    }

    private fun createContext(): Runtime {
        val core = EglCore()
        try {
            core.initialize()
        } catch (e: RuntimeException) {
            core.release()
            throw e
        }
        egl = core
        val rt = try {
            buildRuntime()
        } catch (e: RuntimeException) {
            core.release()
            egl = null
            throw e
        }
        runtime = rt
        images.reuploadAll(rt.capabilities)
        return rt
    }

    private fun buildRuntime(): Runtime {
        val capabilities = GlCapabilities.query()
        val quad = FullscreenQuad.create()
        val programs = try {
            CompositorPrograms.load(assets)
        } catch (e: RuntimeException) {
            quad.release()
            throw e
        }
        val pool = FboPool()
        val text = TextOverlayCache(capabilities.maxTextureSize)
        val grader = try {
            graderFactory(GlEnvironment(assets, capabilities, pool, quad))
        } catch (e: RuntimeException) {
            programs.release()
            quad.release()
            throw e
        }
        val compositor = SceneCompositor(programs, quad, pool, capabilities, images, text, grader)
        return Runtime(capabilities, pool, quad, programs, text, grader, compositor)
    }

    /**
     * Context loss: unbind first so the deletes below are no-ops instead of hitting names that a new
     * context might reuse, drop every per-context object, then rebuild and re-upload registered images.
     */
    private fun recoverContext(): Runtime {
        Log.w(TAG, "EGL context lost; recreating it and re-uploading registered images")
        egl?.makeNothingCurrent()
        teardownRuntime()
        images.releaseTextures()
        egl?.release()
        egl = null
        return createContext()
    }

    private fun teardownRuntime() {
        val rt = runtime ?: return
        runtime = null
        try {
            rt.grader.close()
        } catch (e: Exception) {
            Log.e(TAG, "layer grader close failed", e)
        }
        rt.text.releaseAll()
        rt.pool.releaseAll()
        rt.programs.release()
        rt.quad.release()
    }

    private companion object {
        const val TAG = "GlStoryRenderer"
        const val THREAD_NAME = "AkaStudio-GL"
        const val CLOSED_MESSAGE = "StoryRenderer is closed"
        const val CHANNELS = 4
        const val MATTE_ARGB = 0xFF0B0C0E.toInt()
        const val DIVIDER_ARGB = 0xFFE8E6E1.toInt()
        const val DIVIDER_WIDTH_PX = 2f
    }
}
