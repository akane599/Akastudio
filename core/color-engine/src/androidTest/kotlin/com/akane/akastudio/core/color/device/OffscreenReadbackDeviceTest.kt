package com.akane.akastudio.core.color.device

import android.opengl.GLES20
import android.opengl.GLES30
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.akane.akastudio.core.color.api.GradeParams
import com.akane.akastudio.core.color.api.HalfFloatImage
import com.akane.akastudio.core.color.api.MasterLook
import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.color.api.Rect4f
import com.akane.akastudio.core.color.api.RenderScene
import com.akane.akastudio.core.color.api.SceneBackground
import com.akane.akastudio.core.color.api.SceneLayer
import com.akane.akastudio.core.color.gl.GlFormat
import com.akane.akastudio.core.color.gl.GlFramebuffer
import com.akane.akastudio.core.color.gl.checkGlError
import com.akane.akastudio.core.color.render.GlStoryRenderer
import com.akane.akastudio.core.color.render.OffscreenReadbackOverrides
import com.akane.akastudio.core.color.render.PixelRect
import com.akane.akastudio.core.color.render.ReadbackEncoding
import com.akane.akastudio.core.color.render.RenderMath
import com.akane.akastudio.core.color.render.WorkingReadback
import com.akane.akastudio.core.color.render.createStoryRenderer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F-20 strip-wise readback on a real GL context: known pixels through every read encoding (including the
 * FLOAT fallback, forced), single and multi-tile assembly, against the full-frame readback it replaced; then the
 * production renderer with forced multi-tile and FLOAT readback (T-06: neither path runs on a typical device).
 */
@RunWith(AndroidJUnit4::class)
class OffscreenReadbackDeviceTest {

    @Test
    fun stripReadbackOfKnownHalfFloatPixelsMatchesTheFullFrameReadback(): Unit = OffscreenGl.open().use { gl ->
        assumeTrue("needs colour-renderable RGBA16F", gl.capabilities.floatColorBuffer)
        // Framebuffer rows, lowest first (what glReadPixels returns first): exact half values, HDR and negatives included.
        val framebufferHalves = ShortArray(W * H * 4) { i ->
            val pixel = i / 4
            val x = pixel % W
            val y = pixel / W
            val value = when (i % 4) {
                0 -> (x - 20) / 7f + y / 50f
                1 -> y / (H - 1f)
                2 -> ((x * 31 + y * 17) % 97) / 3f - 4f
                else -> 0.25f + (x % 4) * 0.25f
            }
            RenderMath.floatToHalf(value)
        }
        val expected = flipToCanvas(framebufferHalves, W, H)
        val framebuffer = GlFramebuffer.create(W, H, GlFormat.RGBA16F)
        try {
            framebuffer.texture.uploadLevel0(nativeBuffer(framebufferHalves.size * 2).apply { asShortBuffer().put(framebufferHalves) })
            framebuffer.bind()
            val encodings = buildList {
                if (implementationReadsHalfFloat()) add(ReadbackEncoding.HALF_FLOAT)
                add(ReadbackEncoding.FLOAT)
            }
            report("readback F-20: implementation read type half=${implementationReadsHalfFloat()}; testing $encodings")
            assertArrayEquals("legacy full-frame readback must return the uploaded pixels", expected, legacyFullFrame(ReadbackEncoding.FLOAT))
            for (encoding in encodings) for (strip in STRIPS) for ((maxW, maxH) in PLANS) {
                val canvas = readTiled(WorkingReadback(logDomain = false, stripRows = strip, forcedEncoding = encoding), maxW, maxH)
                assertArrayEquals("$encoding strips $strip tiles ${maxW}x$maxH", expected, canvas)
            }
            if (ReadbackEncoding.HALF_FLOAT in encodings) assertArrayEquals(expected, legacyFullFrame(ReadbackEncoding.HALF_FLOAT))
        } finally {
            framebuffer.release()
        }
    }

    @Test
    fun stripReadbackOfALogDomainTargetMatchesTheFullFrameReadback(): Unit = OffscreenGl.open().use { _ ->
        val bytes = Random(7).nextBytes(W * H * 4)
        val framebuffer = GlFramebuffer.create(W, H, GlFormat.RGBA8)
        try {
            framebuffer.texture.uploadLevel0(nativeBuffer(bytes.size).apply { put(bytes).rewind() })
            framebuffer.bind()
            val expected = legacyFullFrame(ReadbackEncoding.LOG_BYTES)
            assertArrayEquals("full-frame log readback decodes the uploaded bytes", flipToCanvas(RenderMath.logBytesToHalf(bytes), W, H), expected)
            for (strip in STRIPS) for ((maxW, maxH) in PLANS) {
                val canvas = readTiled(WorkingReadback(logDomain = true, stripRows = strip), maxW, maxH)
                assertArrayEquals("log strips $strip tiles ${maxW}x$maxH", expected, canvas)
            }
        } finally {
            framebuffer.release()
        }
    }

    @Test
    fun rendererOutputIsUnchangedByStripsTilesAndTheFloatFallback() {
        assumeTrue("needs colour-renderable RGBA16F", OffscreenGl.open().use { it.capabilities.floatColorBuffer })
        val renderer = createStoryRenderer(InstrumentationRegistry.getInstrumentation().targetContext) as GlStoryRenderer
        val photo = srgbBitmap(PHOTO_W, PHOTO_H, IntArray(PHOTO_W * PHOTO_H) { argb((it % PHOTO_W) * 255 / PHOTO_W, (it / PHOTO_W) * 255 / PHOTO_H, (it * 7) and 0xFF) })
        val hdr = linearF16Bitmap(PHOTO_W, PHOTO_H, FloatArray(PHOTO_W * PHOTO_H * 3) { j -> 0.02f + 2.5f * ((j / 3) % PHOTO_W) / PHOTO_W * (1 + j % 3) / 3f })
        try {
            runBlocking {
                renderer.registerImage("photo", photo)
                renderer.registerImage("hdr", hdr)
            }
            fun render(overrides: OffscreenReadbackOverrides?): HalfFloatImage {
                renderer.offscreenReadbackOverrides = overrides
                return runBlocking { renderer.renderOffscreen(scene()) }
            }
            val base = render(null)
            assertTrue("render must not be blank", base.rgba.any { abs(RenderMath.halfToFloat(it)) > 0.05f })
            val fullFrame = render(OffscreenReadbackOverrides(stripRows = Int.MAX_VALUE))
            val floatStrips = render(OffscreenReadbackOverrides(encoding = ReadbackEncoding.FLOAT, stripRows = 7))
            val tiles = RenderMath.planTiles(SCENE_W, SCENE_H, TILE, TILE).size
            val multiFullFrame = render(OffscreenReadbackOverrides(maxTileSize = TILE, stripRows = Int.MAX_VALUE))
            val multiFloatStrips = render(OffscreenReadbackOverrides(maxTileSize = TILE, encoding = ReadbackEncoding.FLOAT, stripRows = 13))
            val tileSeamDiff = maxDiff(base, multiFullFrame)
            report("readback F-20: ${SCENE_W}x$SCENE_H, $tiles tiles at $TILE px; single vs multi-tile render max |Δ| = $tileSeamDiff (linear)")

            assertTrue("the forced tile limit must split the canvas", tiles > 1)
            assertArrayEquals("256-row strips vs one full-frame read", fullFrame.rgba, base.rgba)
            assertArrayEquals("FLOAT fallback in 7-row strips vs default readback", base.rgba, floatStrips.rgba)
            assertArrayEquals("multi-tile: FLOAT in 13-row strips vs full-frame tiles", multiFullFrame.rgba, multiFloatStrips.rgba)
            assertTrue("multi-tile content must match the single-tile render, max |Δ| $tileSeamDiff", tileSeamDiff <= TILE_TOLERANCE)
        } finally {
            renderer.close()
            photo.recycle()
            hdr.recycle()
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    /** Reads the bound W x H target tile by tile, as `renderOffscreen` places tiles into its canvas. */
    private fun readTiled(readback: WorkingReadback, maxW: Int, maxH: Int): ShortArray {
        val canvas = ShortArray(W * H * 4)
        for (tile in RenderMath.planTiles(W, H, maxW, maxH)) {
            readback.read(PixelRect(tile.x, H - tile.y - tile.height, tile.width, tile.height), canvas, W, tile.x, tile.y)
        }
        return canvas
    }

    /** The pre-F-20 readback: one full-frame buffer and array, decoded, then flipped. */
    private fun legacyFullFrame(encoding: ReadbackEncoding): ShortArray {
        val count = W * H * 4
        val buffer = nativeBuffer(count * (encoding.bytesPerPixel / 4))
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
        val type = when (encoding) {
            ReadbackEncoding.LOG_BYTES -> GLES20.GL_UNSIGNED_BYTE
            ReadbackEncoding.HALF_FLOAT -> GLES30.GL_HALF_FLOAT
            ReadbackEncoding.FLOAT -> GLES20.GL_FLOAT
        }
        GLES20.glReadPixels(0, 0, W, H, GLES20.GL_RGBA, type, buffer)
        checkGlError("legacy glReadPixels")
        val halves = when (encoding) {
            ReadbackEncoding.LOG_BYTES -> RenderMath.logBytesToHalf(ByteArray(count).also { buffer.get(it) })
            ReadbackEncoding.HALF_FLOAT -> ShortArray(count).also { buffer.asShortBuffer().get(it) }
            ReadbackEncoding.FLOAT -> RenderMath.floatsToHalf(FloatArray(count).also { buffer.asFloatBuffer().get(it) })
        }
        RenderMath.flipRows(halves, W * 4, H)
        return halves
    }

    private fun flipToCanvas(framebufferRows: ShortArray, width: Int, height: Int): ShortArray =
        framebufferRows.copyOf().also { RenderMath.flipRows(it, width * 4, height) }

    private fun nativeBuffer(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

    private fun scene() = RenderScene(
        canvasWidth = SCENE_W,
        canvasHeight = SCENE_H,
        background = SceneBackground.FrostedImage("photo"),
        layers = listOf(
            SceneLayer("photo", Rect4f(12f, 16f, 204f, 190f), NormRect(0.05f, 0.05f, 0.95f, 0.9f), rotationDegrees = 2.5f, cornerRadiusPx = 10f, grade = GradeParams(look = MasterLook.PORTRA_400_GOLDEN)),
            SceneLayer("hdr", Rect4f(12f, 200f, 204f, 372f), NormRect.FULL),
        ),
    )

    private fun maxDiff(a: HalfFloatImage, b: HalfFloatImage): Float {
        require(a.width == b.width && a.height == b.height)
        var worst = 0f
        for (i in a.rgba.indices) worst = maxOf(worst, abs(RenderMath.halfToFloat(a.rgba[i]) - RenderMath.halfToFloat(b.rgba[i])))
        return worst
    }

    private companion object {
        /** Taller than one default strip so the 256-row path splits too; odd width. */
        const val W = 61
        const val H = 300
        val STRIPS = listOf(1, 7, WorkingReadback.STRIP_ROWS, Int.MAX_VALUE)
        val PLANS = listOf(W to H, 25 to 64)
        const val PHOTO_W = 96
        const val PHOTO_H = 160
        const val SCENE_W = 216
        const val SCENE_H = 384
        const val TILE = 100
        /** Re-rasterising at a tile offset may move the last bits of interpolated coordinates. */
        const val TILE_TOLERANCE = 2e-3f
    }
}
