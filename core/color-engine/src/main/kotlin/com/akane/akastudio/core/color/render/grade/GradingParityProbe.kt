package com.akane.akastudio.core.color.render.grade

import android.content.res.AssetManager
import android.graphics.RectF
import android.opengl.GLES20
import android.opengl.GLES30
import android.os.Looper
import android.util.Log
import com.akane.akastudio.core.color.gl.EglCore
import com.akane.akastudio.core.color.gl.FboPool
import com.akane.akastudio.core.color.gl.FullscreenQuad
import com.akane.akastudio.core.color.gl.GlCapabilities
import com.akane.akastudio.core.color.gl.GlEnvironment
import com.akane.akastudio.core.color.gl.GlFormat
import com.akane.akastudio.core.color.gl.GlTexture
import com.akane.akastudio.core.color.gl.checkGlError
import com.akane.akastudio.core.color.render.RenderMath
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Result of one [GradingParityProbe] run. Errors are in 8-bit sRGB code units (1.0 = 1/255).
 *
 * @property caseMaxErrors worst channel error per parity case, keyed by case name.
 * @property maxError worst error over every case.
 * @property passed whether [maxError] is within the 2/255 precision contract.
 */
data class GradingParityReport(val caseMaxErrors: Map<String, Float>, val maxError: Float, val passed: Boolean)

/**
 * Debug-only device harness for the GPU grading precision contract. Not called by production code and not run
 * in CI (it needs a real GLES 3 driver).
 *
 * It renders the 256-colour grid of `GradingParityFixtures` as a 16×16 RGBA16F image through
 * `ShaderLayerGrader` (stages 1–6 only: no halation or grain) for each of the six parity grades, reads the
 * result back and compares the clamped sRGB encode against the CPU pipeline's expected table.
 *
 * How to run: on a device or emulator with GLES 3 and `EXT_color_buffer_half_float`/`EXT_color_buffer_float`,
 * from any debug-only entry point (a debug menu action or an instrumentation test), call
 * `GradingParityProbe.run(context.assets)` on a background thread that has no EGL context of its own (not
 * the main thread and not the renderer's GL thread). The per-case errors are also logged under the tag
 * `GradingParity`, e.g. `adb logcat -s GradingParity`.
 */
object GradingParityProbe {
    private const val TAG = "GradingParity"

    /** Creates a private offscreen GLES 3 context, runs every parity case and tears the context down. */
    fun run(assets: AssetManager): GradingParityReport {
        check(Looper.myLooper() != Looper.getMainLooper()) { "GradingParityProbe.run must not run on the main thread" }
        val egl = EglCore()
        try {
            egl.initialize()
            return runOnCurrentContext(assets)
        } finally {
            egl.release()
        }
    }

    private fun runOnCurrentContext(assets: AssetManager): GradingParityReport {
        val capabilities = GlCapabilities.query()
        check(capabilities.floatColorBuffer) { "parity needs RGBA16F colour buffers; this context only offers the reduced 8-bit grader" }
        val quad = FullscreenQuad.create()
        val pool = FboPool()
        try {
            val grader = ShaderLayerGrader(GlEnvironment(assets, capabilities, pool, quad), GradeVariant.FLOAT, filmEffects = false)
            try {
                return compare(grader)
            } finally {
                grader.close()
            }
        } finally {
            pool.releaseAll()
            quad.release()
        }
    }

    private fun compare(grader: ShaderLayerGrader): GradingParityReport {
        val size = GradingParityFixtures.GRID_SIZE
        val source = gridTexture()
        try {
            val region = RectF(0f, 0f, size.toFloat(), size.toFloat())
            val errors = LinkedHashMap<String, Float>()
            GradingParityFixtures.expectations().forEachIndexed { slot, expectation ->
                val output = grader.gradeInto(source, region, size, size, expectation.case.params, slot)
                output.bind()
                val actual = readRgba(size, size)
                var worst = 0f
                for (i in 0 until GradingParityFixtures.COLOR_COUNT) {
                    for (c in 0 until 3) {
                        val gpu = GradingParityFixtures.encodeClamped(actual[i * 4 + c])
                        worst = maxOf(worst, abs(gpu - expectation.expectedEncoded[i * 3 + c]) * 255f)
                    }
                }
                errors[expectation.case.name] = worst
                Log.i(TAG, "${expectation.case.name}: max error ${"%.3f".format(worst)}/255")
            }
            val max = errors.values.maxOrNull() ?: 0f
            val passed = max <= GradingParityFixtures.TOLERANCE_CODES
            Log.i(TAG, "overall max error ${"%.3f".format(max)}/255 -> ${if (passed) "PASS" else "FAIL"}")
            return GradingParityReport(errors, max, passed)
        } finally {
            source.release()
        }
    }

    /** The grid as an opaque RGBA16F texture, row 0 = top (texture row 0), no mips. */
    private fun gridTexture(): GlTexture {
        val size = GradingParityFixtures.GRID_SIZE
        val linear = GradingParityFixtures.gridLinear()
        val rgba = FloatArray(GradingParityFixtures.COLOR_COUNT * 4) { j ->
            val c = j % 4
            if (c == 3) 1f else linear[(j / 4) * 3 + c]
        }
        val halves = RenderMath.floatsToHalf(rgba)
        val buffer = ByteBuffer.allocateDirect(halves.size * Short.SIZE_BYTES).order(ByteOrder.nativeOrder())
        buffer.asShortBuffer().put(halves)
        val texture = GlTexture.allocate(size, size, GlFormat.RGBA16F)
        try {
            texture.uploadLevel0(buffer)
        } catch (e: RuntimeException) {
            texture.release()
            throw e
        }
        return texture
    }

    /** Reads the bound RGBA16F framebuffer as floats, framebuffer row 0 first (= the top grid row here). */
    private fun readRgba(width: Int, height: Int): FloatArray {
        val count = width * height * 4
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
        val format = IntArray(1)
        val type = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_IMPLEMENTATION_COLOR_READ_FORMAT, format, 0)
        GLES20.glGetIntegerv(GLES20.GL_IMPLEMENTATION_COLOR_READ_TYPE, type, 0)
        return if (format[0] == GLES20.GL_RGBA && type[0] == GLES30.GL_HALF_FLOAT) {
            val buffer = ByteBuffer.allocateDirect(count * Short.SIZE_BYTES).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES30.GL_HALF_FLOAT, buffer)
            checkGlError("parity half-float glReadPixels")
            val halves = ShortArray(count).also { buffer.asShortBuffer().get(it) }
            FloatArray(count) { RenderMath.halfToFloat(halves[it]) }
        } else {
            val buffer = ByteBuffer.allocateDirect(count * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_FLOAT, buffer)
            checkGlError("parity float glReadPixels")
            FloatArray(count).also { buffer.asFloatBuffer().get(it) }
        }
    }
}
