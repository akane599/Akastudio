package com.akane.akastudio.core.color.device

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akane.akastudio.core.color.api.GradeParams
import com.akane.akastudio.core.color.api.MasterLook
import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.color.api.Rect4f
import com.akane.akastudio.core.color.api.RenderScene
import com.akane.akastudio.core.color.api.SceneBackground
import com.akane.akastudio.core.color.api.SceneLayer
import com.akane.akastudio.core.color.gl.GlTexture
import com.akane.akastudio.core.color.render.GlStoryRenderer
import com.akane.akastudio.core.color.render.GradeSlotSpace
import com.akane.akastudio.core.color.render.LayerGrader
import com.akane.akastudio.core.color.render.defaultLayerGrader
import com.akane.akastudio.core.color.render.grade.ShaderLayerGrader
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * GPU memory lifetime of the production renderer: the preview, the scope and the export keep separate grade slots
 * (F-21), so rendering one never evicts another's cached grade.
 */
@RunWith(AndroidJUnit4::class)
class RendererGpuMemoryDeviceTest {

    /** Delegates to the production grader and records every grade it hands out, on the GL thread. */
    private class RecordingGrader(val inner: LayerGrader) : LayerGrader {
        val grades = ArrayList<Pair<Int, GlTexture>>()

        override fun grade(source: GlTexture, region: RectF, outWidth: Int, outHeight: Int, params: GradeParams, slot: Int): GlTexture =
            inner.grade(source, region, outWidth, outHeight, params, slot).also { grades += slot to it }

        override fun close() = inner.close()
    }

    @Volatile private var grader: RecordingGrader? = null
    private lateinit var renderer: GlStoryRenderer
    private lateinit var photo: Bitmap

    @Before
    fun setUp() {
        renderer = GlStoryRenderer(testAssets) { environment ->
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
    }
}
