package com.akane.akastudio.core.color.device

import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akane.akastudio.core.color.api.GradeParams
import com.akane.akastudio.core.color.api.MasterLook
import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.color.api.Rect4f
import com.akane.akastudio.core.color.api.RenderScene
import com.akane.akastudio.core.color.api.SceneBackground
import com.akane.akastudio.core.color.api.SceneLayer
import com.akane.akastudio.core.color.gl.EglCore
import com.akane.akastudio.core.color.gl.FboPool
import com.akane.akastudio.core.color.gl.GlTexture
import com.akane.akastudio.core.color.gl.GlThread
import com.akane.akastudio.core.color.render.GlStoryRenderer
import com.akane.akastudio.core.color.render.GradeSlotSpace
import com.akane.akastudio.core.color.render.LayerGrader
import com.akane.akastudio.core.color.render.defaultLayerGrader
import com.akane.akastudio.core.color.render.grade.ShaderLayerGrader
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * GPU memory lifetime of the production renderer: the preview, the scope and the export keep separate grade slots
 * (F-21), so rendering one never evicts another's cached grade; an export frees its own grades and the pool's idle
 * targets, and `trimMemory` / `releaseSurface` free the rest without closing the renderer (F-03).
 */
@RunWith(AndroidJUnit4::class)
class RendererGpuMemoryDeviceTest {

    /** Delegates to the production grader and records every grade it hands out, on the GL thread. */
    private class RecordingGrader(val inner: LayerGrader) : LayerGrader {
        val grades = ArrayList<Pair<Int, GlTexture>>()

        override fun grade(source: GlTexture, region: RectF, outWidth: Int, outHeight: Int, params: GradeParams, slot: Int): GlTexture =
            inner.grade(source, region, outWidth, outHeight, params, slot).also { grades += slot to it }

        override fun trim(space: GradeSlotSpace) = inner.trim(space)

        override fun close() = inner.close()
    }

    @Volatile private var grader: RecordingGrader? = null
    @Volatile private var pool: FboPool? = null
    private lateinit var renderer: GlStoryRenderer
    private lateinit var photo: Bitmap

    @Before
    fun setUp() {
        renderer = GlStoryRenderer(testAssets) { environment ->
            pool = environment.fboPool
            RecordingGrader(defaultLayerGrader(environment)).also { grader = it }
        }
        photo = srgbBitmap(PHOTO_EDGE, PHOTO_EDGE, IntArray(PHOTO_EDGE * PHOTO_EDGE) { argb((it % PHOTO_EDGE) / 2, (it / PHOTO_EDGE) / 2, 90) })
        runBlocking { renderer.registerImage(KEY, photo) }
        assumeTrue("the context must grade on the GPU for slot caching to exist", recorder().inner is ShaderLayerGrader)
    }

    @After
    fun tearDown() {
        renderer.close()
        photo.recycle()
    }

    @Test
    fun exportDoesNotEvictTheScopeGrade() {
        runBlocking { renderer.renderPreviewPixels(scene(), SCOPE_W, SCOPE_H) }
        val scope = gradesSince(0)
        runBlocking { renderer.renderOffscreen(scene()) }
        val export = gradesSince(scope.size)
        runBlocking { renderer.renderPreviewPixels(scene(), SCOPE_W, SCOPE_H) }
        val scopeAgain = gradesSince(scope.size + export.size)

        val scopeSlots = scope.map { it.first }.toSet()
        val exportSlots = export.map { it.first }.toSet()
        report("grade slots: scope $scopeSlots, export $exportSlots")
        assertEquals("one graded layer per render", listOf(1, 1, 1), listOf(scope.size, export.size, scopeAgain.size))
        assertSame("the scope's cached grade must survive an export in between", scope.single().second, scopeAgain.single().second)
        assertTrue("scope slots $scopeSlots must all be in the scope space", scopeSlots.all { it in GradeSlotSpace.SCOPE })
        assertTrue("export slots $exportSlots must all be in the export space", exportSlots.all { it in GradeSlotSpace.EXPORT })
    }

    @Test
    fun exportFreesItsGradesAndTheIdleTargetsButKeepsTheScopeGrade() {
        runBlocking { renderer.renderPreviewPixels(scene(), SCOPE_W, SCOPE_H) }
        val scope = gradesSince(0).single().second
        runBlocking { renderer.renderOffscreen(scene()) }
        val export = gradesSince(1).single().second

        report("after export: export grade released=${export.isReleased}, pool free=${pool().freeCount} lent=${pool().inUseCount}")
        assertTrue("the export grade must be freed once the export is read back", export.isReleased)
        assertFalse("the scope grade must stay cached", scope.isReleased)
        assertEquals("no idle render target may stay pinned after an export", 0, pool().freeCount)
        assertEquals(0, pool().inUseCount)

        val again = runBlocking { renderer.renderOffscreen(scene()) }
        assertTrue("a second export after the trim must still render", again.rgba.any { it != 0.toShort() })
    }

    @Test
    fun trimMemoryFreesEveryCacheAndTheRendererStaysUsable() {
        val before = runBlocking { renderer.renderPreviewPixels(scene(), SCOPE_W, SCOPE_H) }
        val scope = gradesSince(0).single().second
        assertTrue("the scope render leaves its targets pooled", pool().freeCount > 0)

        renderer.trimMemory()
        awaitGlQueue()

        assertTrue("trimMemory must free the cached grade", scope.isReleased)
        assertEquals("trimMemory must free every idle render target", 0, pool().freeCount)
        val after = runBlocking { renderer.renderPreviewPixels(scene(), SCOPE_W, SCOPE_H) }
        assertArrayEquals("the render after a trim must match the one before", before.argb, after.argb)
        assertFalse(gradesSince(1).single().second.isReleased)
    }

    @Test
    fun trimOfAnUnusedRendererDoesNotCreateAContextAndAClosedOneIgnoresIt() {
        val fresh = GlStoryRenderer(testAssets) { environment -> defaultLayerGrader(environment) }
        val texture = SurfaceTexture(false)
        val surface = Surface(texture)
        try {
            fresh.trimMemory()
            fresh.releaseSurface(surface)
            val glThread = privateField(fresh, "glThread") as GlThread
            runBlocking { withContext(glThread.dispatcher) {} }
            assertNull("a trim must not build the EGL context", privateField(fresh, "egl"))
            assertNull("a trim must not build the GL runtime", privateField(fresh, "runtime"))
        } finally {
            fresh.close()
            surface.release()
            texture.release()
        }
        fresh.trimMemory()
        fresh.releaseSurface(surface)
    }

    @Test
    fun windowSurfaceIsDroppedOnReleaseSurfaceAndOnTrim() {
        val texture = SurfaceTexture(false).apply { setDefaultBufferSize(PREVIEW_W, PREVIEW_H) }
        val surface = Surface(texture)
        val other = Surface(SurfaceTexture(false))
        try {
            runBlocking { renderer.renderToSurface(scene(), surface, PREVIEW_W, PREVIEW_H) }
            assertSame("the preview caches a window surface for its target", surface, windowTarget())

            renderer.releaseSurface(other)
            awaitGlQueue()
            assertSame("releasing another surface keeps the cached one", surface, windowTarget())

            renderer.releaseSurface(surface)
            awaitGlQueue()
            assertNull("releasing the target surface drops its EGL window surface", windowTarget())

            runBlocking { renderer.renderToSurface(scene(), surface, PREVIEW_W, PREVIEW_H) }
            assertSame(surface, windowTarget())
            renderer.trimMemory()
            awaitGlQueue()
            assertNull("trimMemory drops the cached EGL window surface", windowTarget())
        } finally {
            surface.release()
            other.release()
            texture.release()
        }
    }

    private fun pool(): FboPool = checkNotNull(pool) { "the renderer has not built its context" }

    /** Waits until the GL thread has run everything queued before this call (it is one FIFO queue). */
    private fun awaitGlQueue() {
        runBlocking { renderer.maxTextureSize() }
    }

    /** The Surface the renderer's EGL window surface was created for, read on the test thread after a GL round trip. */
    private fun windowTarget(): Surface? {
        val egl = privateField(renderer, "egl") as EglCore
        return privateField(egl, "windowTarget") as Surface?
    }

    private fun privateField(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun recorder(): RecordingGrader = checkNotNull(grader) { "the renderer has not built its context" }

    /** A copy of the grades recorded after the first [from], read after the GL thread finished the render. */
    private fun gradesSince(from: Int): List<Pair<Int, GlTexture>> = recorder().grades.drop(from)

    private fun scene() = RenderScene(
        CANVAS_W, CANVAS_H, SceneBackground.Solid(BLACK),
        listOf(SceneLayer(KEY, Rect4f(0f, 0f, CANVAS_W.toFloat(), CANVAS_H.toFloat()), NormRect(0f, 0f, 0.5625f, 1f), grade = GradeParams(look = MasterLook.PORTRA_400_GOLDEN))),
    )

    private companion object {
        const val KEY = "photo"
        const val BLACK = 0xFF000000.toInt()
        const val PHOTO_EDGE = 512
        const val CANVAS_W = 540
        const val CANVAS_H = 960
        const val SCOPE_W = 135
        const val SCOPE_H = 240
        const val PREVIEW_W = 90
        const val PREVIEW_H = 160
    }
}
