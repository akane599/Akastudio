package com.akane.akastudio.core.color.device

import android.graphics.Bitmap
import android.opengl.GLES20
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.akane.akastudio.core.color.api.GradeParams
import com.akane.akastudio.core.color.api.HalfFloatImage
import com.akane.akastudio.core.color.api.HarmonizationParams
import com.akane.akastudio.core.color.api.MasterLook
import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.color.api.Rect4f
import com.akane.akastudio.core.color.api.RenderScene
import com.akane.akastudio.core.color.api.SceneBackground
import com.akane.akastudio.core.color.api.SceneLayer
import com.akane.akastudio.core.color.gl.GlThread
import com.akane.akastudio.core.color.grade.CpuGradingPipeline
import com.akane.akastudio.core.color.grade.LookLutCache
import com.akane.akastudio.core.color.render.GlStoryRenderer
import com.akane.akastudio.core.color.render.ImageRegistry
import com.akane.akastudio.core.color.render.RenderMath
import com.akane.akastudio.core.color.render.createStoryRenderer
import com.akane.akastudio.core.color.render.grade.GradingParityFixtures
import java.lang.reflect.InvocationTargetException
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 3 cases 4–6 through the production `StoryRenderer` (its own GL thread and EGL context):
 * half-float readback precision, context destroy/recreate with image re-upload (the SQ-26 decision), and a
 * 4K 9:16 offscreen render against the context's limits.
 */
@RunWith(AndroidJUnit4::class)
class StoryRendererDeviceTest {

    private fun newRenderer(): GlStoryRenderer =
        createStoryRenderer(InstrumentationRegistry.getInstrumentation().targetContext) as GlStoryRenderer

    // ---- Case 4: 16-bit readback --------------------------------------------------------------------------

    @Test
    fun smoothRampReadsBackWithMoreThan256Levels() {
        val ramp = FloatArray(RAMP_WIDTH * RAMP_HEIGHT * 3) { j -> ((j / 3) % RAMP_WIDTH) / (RAMP_WIDTH - 1f) }
        val levels = rampLevels(linearF16Bitmap(RAMP_WIDTH, RAMP_HEIGHT, ramp))
        report("readback: linear F16 0→1 ramp over $RAMP_WIDTH px -> ${levels.distinct} distinct half-float levels, range ${levels.first}..${levels.last}, worst dip ${levels.worstDip}")
        assertTrue("a smooth ramp must keep more than 256 levels, got ${levels.distinct}", levels.distinct > 256)
        assertTrue("ramp must stay monotonic (no grain/noise inflating the count), worst dip ${levels.worstDip}", levels.worstDip <= 1e-3f)
        assertTrue("ramp must span the range, got ${levels.first}..${levels.last}", levels.first < 0.01f && levels.last > 0.9f)
    }

    /** Negative control: the same count on an 8-bit source ramp must see the 8-bit quantization. */
    @Test
    fun eightBitSourceRampIsCountedAsQuantized() {
        val argb = IntArray(RAMP_WIDTH * RAMP_HEIGHT) { i ->
            val code = Math.round((i % RAMP_WIDTH) * 255f / (RAMP_WIDTH - 1))
            argb(code, code, code)
        }
        val levels = rampLevels(srgbBitmap(RAMP_WIDTH, RAMP_HEIGHT, argb))
        report("readback control: 8-bit sRGB ramp -> ${levels.distinct} distinct levels")
        assertTrue("an 8-bit source must read back as at most 256 levels, got ${levels.distinct}", levels.distinct <= 256)
    }

    private class RampLevels(val distinct: Int, val first: Float, val last: Float, val worstDip: Float)

    private fun rampLevels(source: Bitmap): RampLevels {
        val renderer = newRenderer()
        try {
            val image = runBlocking {
                renderer.registerImage("ramp", source)
                renderer.renderOffscreen(
                    RenderScene(
                        RAMP_WIDTH, RAMP_HEIGHT, SceneBackground.Solid(BLACK),
                        listOf(SceneLayer("ramp", Rect4f(0f, 0f, RAMP_WIDTH.toFloat(), RAMP_HEIGHT.toFloat()), NormRect.FULL)),
                    ),
                )
            }
            val row = RAMP_HEIGHT / 2
            val red = FloatArray(RAMP_WIDTH) { x -> RenderMath.halfToFloat(image.rgba[(row * RAMP_WIDTH + x) * 4]) }
            var dip = 0f
            for (x in 1 until RAMP_WIDTH) dip = maxOf(dip, red[x - 1] - red[x])
            val distinct = (0 until RAMP_WIDTH).mapTo(HashSet()) { x -> image.rgba[(row * RAMP_WIDTH + x) * 4] }.size
            return RampLevels(distinct, red.first(), red.last(), dip)
        } finally {
            renderer.close()
            source.recycle()
        }
    }

    // ---- Case 5: context loss -----------------------------------------------------------------------------

    @Test
    fun contextRecreationReuploadsRegisteredImagesAndReproducesTheRender() {
        val renderer = newRenderer()
        val photo = srgbBitmap(PHOTO_W, PHOTO_H, IntArray(PHOTO_W * PHOTO_H) { photoPixel(it % PHOTO_W, it / PHOTO_W) })
        val linear = linearF16Bitmap(PHOTO_W, PHOTO_H, FloatArray(PHOTO_W * PHOTO_H * 3) { j -> 0.05f + 1.3f * ((j / 3) % PHOTO_W) / PHOTO_W * (1 + j % 3) / 3f })
        try {
            val before = runBlocking {
                renderer.registerImage("photo", photo)
                renderer.registerImage("linear", linear)
                renderer.renderOffscreen(lossScene())
            }
            assertTrue("pre-loss render must not be blank", maxAbs(before) > 0.05f)
            val sentinelBefore = renderer.onGlThread { plantSentinelTexture() }
            assertTrue("sentinel texture must exist in the original context", sentinelBefore)

            renderer.onGlThread { invokePrivate(renderer, "recoverContext") }

            val sentinelAfter = renderer.onGlThread { GLES20.glIsTexture(SENTINEL_TEXTURE) }
            assertFalse("after recreation the old context's objects must be gone (a new EGL context is current)", sentinelAfter)
            val after = runBlocking { renderer.renderOffscreen(lossScene()) }
            val diff = maxDiff(before, after)
            report("context loss: re-render after destroy/recreate max |Δ| = $diff (linear), ${before.width}x${before.height}")
            assertEquals("post-recreation render must equal the pre-loss render", 0f, diff, 0f)

            // Control: the match above depends on the re-upload. Drop the textures without re-uploading and the
            // same scene can no longer render.
            renderer.onGlThread { (privateField(renderer, "images") as ImageRegistry).releaseTextures() }
            try {
                runBlocking { renderer.renderOffscreen(lossScene()) }
                fail("rendering with released, non-reuploaded textures must fail")
            } catch (expected: IllegalArgumentException) {
                report("context loss control: without re-upload -> ${expected.message}")
            }
        } finally {
            renderer.close()
        }
    }

    private fun lossScene() = RenderScene(
        canvasWidth = LOSS_W,
        canvasHeight = LOSS_H,
        background = SceneBackground.FrostedImage("photo"),
        layers = listOf(
            SceneLayer("photo", Rect4f(12f, 16f, 204f, 190f), NormRect(0.05f, 0.05f, 0.95f, 0.9f), rotationDegrees = 2.5f, cornerRadiusPx = 10f, grade = GradeParams(look = MasterLook.PORTRA_400_GOLDEN)),
            SceneLayer(
                "linear", Rect4f(12f, 200f, 204f, 372f), NormRect.FULL,
                grade = GradeParams(look = MasterLook.MEDITERRANEAN_AZURE, harmonization = HarmonizationParams(1.05f, -0.02f, 0.9f, 0.01f, 1.1f, -0.015f)),
            ),
        ),
    )

    /** Binds an unused texture name in the current context so a later check can tell whether it is still that context. */
    private fun plantSentinelTexture(): Boolean {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, SENTINEL_TEXTURE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return GLES20.glIsTexture(SENTINEL_TEXTURE)
    }

    // ---- Case 6: large surfaces ---------------------------------------------------------------------------

    @Test
    fun fourKNineBySixteenRenderSucceedsWithinTheContextLimits() {
        val limits = OffscreenGl.open().use { gl -> gl.capabilities }
        val tiles = RenderMath.planTiles(UHD_W, UHD_H, limits.maxTargetWidth, limits.maxTargetHeight)
        report(
            "large surface: GL_MAX_TEXTURE_SIZE=${limits.maxTextureSize} GL_MAX_RENDERBUFFER_SIZE=${limits.maxRenderbufferSize} " +
                "GL_MAX_VIEWPORT_DIMS=${limits.maxViewportWidth}x${limits.maxViewportHeight} -> max target ${limits.maxTargetWidth}x${limits.maxTargetHeight}; " +
                "${UHD_W}x$UHD_H plans ${tiles.size} tile(s)",
        )
        assertTrue(
            "a 4K 9:16 target must fit the limits or be tiled",
            (UHD_W <= limits.maxTargetWidth && UHD_H <= limits.maxTargetHeight) || tiles.size > 1,
        )

        val renderer = newRenderer()
        val source = srgbBitmap(QUAD_W, QUAD_H, IntArray(QUAD_W * QUAD_H) { quadrantColour(it % QUAD_W >= QUAD_W / 2, it / QUAD_W >= QUAD_H / 2) })
        try {
            val started = SystemClock.elapsedRealtime()
            val image = runBlocking {
                assertEquals(limits.maxTextureSize, renderer.maxTextureSize())
                renderer.registerImage("quad", source)
                renderer.renderOffscreen(
                    RenderScene(UHD_W, UHD_H, SceneBackground.Solid(BLACK), listOf(SceneLayer("quad", Rect4f(0f, 0f, UHD_W.toFloat(), UHD_H.toFloat()), NormRect.FULL))),
                )
            }
            val elapsed = SystemClock.elapsedRealtime() - started
            assertEquals(UHD_W, image.width)
            assertEquals(UHD_H, image.height)

            val probes = listOf(
                Triple(UHD_W / 4, UHD_H / 4, false to false), Triple(3 * UHD_W / 4, UHD_H / 4, true to false),
                Triple(UHD_W / 4, 3 * UHD_H / 4, false to true), Triple(3 * UHD_W / 4, 3 * UHD_H / 4, true to true),
                Triple(2, 2, false to false), Triple(UHD_W - 3, 2, true to false),
                Triple(2, UHD_H - 3, false to true), Triple(UHD_W - 3, UHD_H - 3, true to true),
            )
            var worst = 0f
            for ((x, y, quadrant) in probes) {
                val expected = expectedEncoded(quadrantColour(quadrant.first, quadrant.second))
                for (c in 0 until 3) {
                    val actual = GradingParityFixtures.encodeClamped(pixel(image, x, y, c))
                    worst = maxOf(worst, abs(actual - expected[c]) * 255f)
                }
            }
            report("large surface: ${UHD_W}x$UHD_H rendered in $elapsed ms, ${tiles.size} tile(s), worst probe error %.3f/255".format(worst))
            assertTrue("4K render content must match the CPU grade at quadrant centres and corners, worst $worst/255", worst <= TOLERANCE_CODES)
        } finally {
            renderer.close()
            source.recycle()
        }
    }

    private fun expectedEncoded(argb: Int): FloatArray {
        val px = FloatArray(3) { codeToLinear(channel(argb, it)) }
        CpuGradingPipeline.gradePixel(px, GradeParams.NEUTRAL, LookLutCache.get(GradeParams.NEUTRAL.look), px)
        return FloatArray(3) { GradingParityFixtures.encodeClamped(px[it]) }
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private fun <T> GlStoryRenderer.onGlThread(block: () -> T): T {
        val thread = privateField(this, "glThread") as GlThread
        return runBlocking { withContext(thread.dispatcher) { block() } }
    }

    private fun privateField(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun invokePrivate(target: Any, name: String) {
        try {
            target.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(target)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun pixel(image: HalfFloatImage, x: Int, y: Int, c: Int): Float = RenderMath.halfToFloat(image.rgba[(y * image.width + x) * 4 + c])

    private fun maxAbs(image: HalfFloatImage): Float = image.rgba.maxOf { abs(RenderMath.halfToFloat(it)) }

    private fun maxDiff(a: HalfFloatImage, b: HalfFloatImage): Float {
        require(a.width == b.width && a.height == b.height)
        var worst = 0f
        for (i in a.rgba.indices) worst = maxOf(worst, abs(RenderMath.halfToFloat(a.rgba[i]) - RenderMath.halfToFloat(b.rgba[i])))
        return worst
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val RAMP_WIDTH = 1024
        const val RAMP_HEIGHT = 8
        const val PHOTO_W = 96
        const val PHOTO_H = 160
        const val LOSS_W = 216
        const val LOSS_H = 384
        const val UHD_W = 2160
        const val UHD_H = 3840
        const val QUAD_W = 540
        const val QUAD_H = 960
        const val SENTINEL_TEXTURE = 987_654

        fun photoPixel(x: Int, y: Int): Int = argb((x * 255) / PHOTO_W, (y * 255) / PHOTO_H, ((x + y) * 7) and 0xFF)

        fun quadrantColour(right: Boolean, bottom: Boolean): Int = when {
            !right && !bottom -> argb(200, 60, 50)
            right && !bottom -> argb(50, 170, 80)
            !right && bottom -> argb(60, 80, 200)
            else -> argb(220, 200, 70)
        }
    }
}
