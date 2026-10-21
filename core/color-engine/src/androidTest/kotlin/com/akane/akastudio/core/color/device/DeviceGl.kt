package com.akane.akastudio.core.color.device

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.RectF
import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.akane.akastudio.core.color.api.GradeParams
import com.akane.akastudio.core.color.gl.EglCore
import com.akane.akastudio.core.color.gl.FboPool
import com.akane.akastudio.core.color.gl.FullscreenQuad
import com.akane.akastudio.core.color.gl.GlCapabilities
import com.akane.akastudio.core.color.gl.GlEnvironment
import com.akane.akastudio.core.color.gl.GlFormat
import com.akane.akastudio.core.color.gl.GlTexture
import com.akane.akastudio.core.color.gl.checkGlError
import com.akane.akastudio.core.color.grade.CpuGradingPipeline
import com.akane.akastudio.core.color.grade.LookLutCache
import com.akane.akastudio.core.color.grade.LookLuts
import com.akane.akastudio.core.color.math.ColorMath
import com.akane.akastudio.core.color.render.RenderMath
import com.akane.akastudio.core.color.render.grade.GradingParityFixtures
import com.akane.akastudio.core.color.render.grade.ShaderLayerGrader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Logcat tag of every measurement these device tests record (`adb logcat -s ColorEngineDevice`). */
internal const val DEVICE_TAG = "ColorEngineDevice"

/** The 2/255 GPU↔CPU precision contract, in 8-bit sRGB code units. */
internal const val TOLERANCE_CODES = GradingParityFixtures.TOLERANCE_CODES

internal fun report(message: String) {
    Log.i(DEVICE_TAG, message)
}

internal val testAssets: AssetManager get() = InstrumentationRegistry.getInstrumentation().targetContext.assets

/**
 * A private offscreen ES 3 context (the engine's own [EglCore] 1×1 pbuffer) current on the calling
 * instrumentation thread, plus the shared quad and FBO pool a grader needs.
 */
internal class OffscreenGl private constructor(
    private val egl: EglCore,
    val capabilities: GlCapabilities,
    val quad: FullscreenQuad,
    val pool: FboPool,
) : AutoCloseable {

    fun environment(capabilities: GlCapabilities = this.capabilities): GlEnvironment =
        GlEnvironment(testAssets, capabilities, pool, quad)

    override fun close() {
        try {
            pool.releaseAll()
            quad.release()
        } finally {
            egl.release()
        }
    }

    companion object {
        fun open(): OffscreenGl {
            val egl = EglCore()
            try {
                egl.initialize()
                return OffscreenGl(egl, GlCapabilities.query(), FullscreenQuad.create(), FboPool())
            } catch (e: Throwable) {
                egl.release()
                throw e
            }
        }
    }
}

/** A linear-light test image: 3 floats per pixel (linear sRGB, already rounded through half floats), top row first. */
internal class LinearRgbImage(val width: Int, val height: Int, val rgb: FloatArray) {
    init {
        require(rgb.size == width * height * 3)
    }

    val pixelCount: Int get() = width * height

    /** Opaque RGBA16F texture of this image, no mips. */
    fun upload(): GlTexture {
        val rgba = FloatArray(pixelCount * 4) { j -> if (j % 4 == 3) 1f else rgb[(j / 4) * 3 + j % 4] }
        val halves = RenderMath.floatsToHalf(rgba)
        val buffer = ByteBuffer.allocateDirect(halves.size * Short.SIZE_BYTES).order(ByteOrder.nativeOrder())
        buffer.asShortBuffer().put(halves)
        val texture = GlTexture.allocate(width, height, GlFormat.RGBA16F)
        try {
            texture.uploadLevel0(buffer)
        } catch (e: RuntimeException) {
            texture.release()
            throw e
        }
        return texture
    }
}

/** Rounds a linear value through a half float, which is what an RGBA16F texture stores. */
internal fun roundHalf(value: Float): Float = RenderMath.halfToFloat(RenderMath.floatToHalf(value))

/** Linear value of an 8-bit sRGB code, rounded through a half float. */
internal fun codeToLinear(code: Int): Float = roundHalf(ColorMath.srgbToLinear(code / 255f))

/**
 * Grades [source] (whole image, 1:1) with [grader] into [slot] and reads the slot back as linear RGBA floats,
 * top row first.
 */
internal fun gpuGrade(grader: ShaderLayerGrader, source: GlTexture, params: GradeParams, slot: Int): FloatArray {
    val region = RectF(0f, 0f, source.width.toFloat(), source.height.toFloat())
    val output = grader.gradeInto(source, region, source.width, source.height, params, slot)
    check(output.width == source.width && output.height == source.height) {
        "1:1 grade planned ${output.width}x${output.height} for a ${source.width}x${source.height} source"
    }
    output.bind()
    return readFloatRgba(source.width, source.height)
}

/** Reads the bound float framebuffer as RGBA floats, framebuffer row 0 first. */
internal fun readFloatRgba(width: Int, height: Int): FloatArray {
    val count = width * height * 4
    GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
    return if (implementationReadsHalfFloat()) {
        val buffer = ByteBuffer.allocateDirect(count * Short.SIZE_BYTES).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES30.GL_HALF_FLOAT, buffer)
        checkGlError("test half-float glReadPixels")
        val halves = ShortArray(count).also { buffer.asShortBuffer().get(it) }
        FloatArray(count) { RenderMath.halfToFloat(halves[it]) }
    } else {
        val buffer = ByteBuffer.allocateDirect(count * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_FLOAT, buffer)
        checkGlError("test float glReadPixels")
        FloatArray(count).also { buffer.asFloatBuffer().get(it) }
    }
}

internal fun implementationReadsHalfFloat(): Boolean {
    val format = IntArray(1)
    val type = IntArray(1)
    GLES20.glGetIntegerv(GLES20.GL_IMPLEMENTATION_COLOR_READ_FORMAT, format, 0)
    GLES20.glGetIntegerv(GLES20.GL_IMPLEMENTATION_COLOR_READ_TYPE, type, 0)
    return format[0] == GLES20.GL_RGBA && type[0] == GLES30.GL_HALF_FLOAT
}

/** CPU stages 1–6 of every pixel, clamped to [0, 1] and sRGB-encoded (3 floats per pixel). */
internal fun cpuExpectedEncoded(image: LinearRgbImage, params: GradeParams, luts: LookLuts = LookLutCache.get(params.look)): FloatArray {
    val px = FloatArray(3)
    val out = FloatArray(image.pixelCount * 3)
    for (i in 0 until image.pixelCount) {
        px[0] = image.rgb[i * 3]
        px[1] = image.rgb[i * 3 + 1]
        px[2] = image.rgb[i * 3 + 2]
        CpuGradingPipeline.gradePixel(px, params, luts, px)
        for (c in 0 until 3) out[i * 3 + c] = GradingParityFixtures.encodeClamped(px[c])
    }
    return out
}

/** Worst and mean channel error in 8-bit sRGB code units. */
internal data class CodeError(val max: Float, val mean: Float, val worstPixel: Int) {
    override fun toString(): String = "max %.3f mean %.4f /255 (worst pixel %d)".format(max, mean, worstPixel)
}

/** Error between GPU linear RGBA (4 floats per pixel) and the CPU's encoded RGB (3 floats per pixel). */
internal fun encodedError(gpuRgba: FloatArray, expectedEncoded: FloatArray): CodeError {
    val pixels = expectedEncoded.size / 3
    require(gpuRgba.size == pixels * 4)
    var max = 0f
    var worst = -1
    var sum = 0.0
    for (i in 0 until pixels) {
        for (c in 0 until 3) {
            val error = abs(GradingParityFixtures.encodeClamped(gpuRgba[i * 4 + c]) - expectedEncoded[i * 3 + c]) * 255f
            sum += error
            if (error > max) {
                max = error
                worst = i
            }
        }
    }
    return CodeError(max, (sum / (pixels * 3)).toFloat(), worst)
}

/** Opaque ARGB_8888 sRGB bitmap of [argb] (row-major). */
internal fun srgbBitmap(width: Int, height: Int, argb: IntArray): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888, false, ColorSpace.get(ColorSpace.Named.SRGB))
    bitmap.setPixels(argb, 0, width, 0, 0, width, height)
    bitmap.setHasAlpha(false)
    return bitmap
}

/** Opaque RGBA_F16 bitmap in LINEAR_EXTENDED_SRGB holding [rgb] (3 linear floats per pixel). */
internal fun linearF16Bitmap(width: Int, height: Int, rgb: FloatArray): Bitmap {
    require(rgb.size == width * height * 3)
    val rgba = FloatArray(width * height * 4) { j -> if (j % 4 == 3) 1f else rgb[(j / 4) * 3 + j % 4] }
    val halves = RenderMath.floatsToHalf(rgba)
    val buffer = ByteBuffer.allocateDirect(halves.size * Short.SIZE_BYTES).order(ByteOrder.nativeOrder())
    buffer.asShortBuffer().put(halves)
    buffer.rewind()
    val bitmap = Bitmap.createBitmap(
        width, height, Bitmap.Config.RGBA_F16, true, ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB),
    )
    bitmap.copyPixelsFromBuffer(buffer)
    return bitmap
}

internal fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

internal fun channel(argb: Int, c: Int): Int = (argb ushr (16 - 8 * c)) and 0xFF
