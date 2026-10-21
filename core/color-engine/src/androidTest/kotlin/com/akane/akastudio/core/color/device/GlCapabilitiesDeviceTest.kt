package com.akane.akastudio.core.color.device

import android.graphics.RectF
import android.opengl.GLES20
import android.opengl.GLES30
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akane.akastudio.core.color.api.GradeParams
import com.akane.akastudio.core.color.gl.GlCapabilities
import com.akane.akastudio.core.color.gl.GlFormat
import com.akane.akastudio.core.color.look.LutBaker
import com.akane.akastudio.core.color.render.defaultLayerGrader
import com.akane.akastudio.core.color.render.grade.ShaderLayerGrader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 3 case 1: RGBA16F colour-renderable detection and the 8-bit fallback choice, judged against an
 * independent raw-GL probe of this device rather than against `GlCapabilities` itself.
 */
@RunWith(AndroidJUnit4::class)
class GlCapabilitiesDeviceTest {

    @Test
    fun rgba16fColourRenderableSupportIsDetectedCorrectly() {
        OffscreenGl.open().use { gl -> checkDetection(gl) }
    }

    @Test
    fun eightBitFallbackIsChosenOnlyWhenRgba16fIsUnavailable() {
        OffscreenGl.open().use { gl -> checkFallback(gl) }
    }

    private fun checkDetection(gl: OffscreenGl) {
        val major = intOf(GLES30.GL_MAJOR_VERSION)
        val minor = intOf(GLES30.GL_MINOR_VERSION)
        val extensions = extensions()
        val halfExt = "GL_EXT_color_buffer_half_float" in extensions
        val floatExt = "GL_EXT_color_buffer_float" in extensions
        val coreFloatTargets = major > 3 || (major == 3 && minor >= 2)
        val probe = independentHalfFloatProbe()
        val caps = gl.capabilities
        report(
            "capabilities: GL_VERSION='${GLES20.glGetString(GLES20.GL_VERSION)}' renderer='${GLES20.glGetString(GLES20.GL_RENDERER)}' " +
                "ES $major.$minor EXT_color_buffer_half_float=$halfExt EXT_color_buffer_float=$floatExt " +
                "rawProbe(complete=${probe.complete}, storedRed=${probe.storedRed}) floatColorBuffer=${caps.floatColorBuffer} " +
                "workingFormat=${caps.workingFormat} readsHalf=${implementationReadsHalfFloat()}",
        )

        // The probe can tell a float target from an 8-bit one: a cleared 2.5 survives only in float storage.
        val probeSaysFloat = probe.complete && probe.storedRed > 1.5f
        assertEquals(
            "GlCapabilities.floatColorBuffer must agree with a raw RGBA16F FBO completeness + >1.0 storage probe",
            probeSaysFloat, caps.floatColorBuffer,
        )
        if (coreFloatTargets || halfExt || floatExt) {
            assertTrue(
                "ES $major.$minor (3.2 core makes RGBA16F colour-renderable) or an advertised extension must yield floatColorBuffer",
                caps.floatColorBuffer,
            )
        }
        assertEquals(GlFormat.RGBA16F, caps.workingFormat)
        assertFalse("float targets must not use the log-encoded 8-bit domain", caps.logDomain)
        assertTrue("RGBA16F images keep their mip chain on a float-renderable context", caps.canMipmap(GlFormat.RGBA16F))
    }

    private fun checkFallback(gl: OffscreenGl) {
        val max3D = intOf(GLES30.GL_MAX_3D_TEXTURE_SIZE)
        report("capabilities: GL_MAX_3D_TEXTURE_SIZE=$max3D (look LUT lattice ${LutBaker.SIZE})")
        assertTrue("3D LUT textures must fit, else grading silently becomes the identity", max3D >= LutBaker.SIZE)

        val real = gl.capabilities
        val expectedReal = if (real.floatColorBuffer) GlFormat.RGBA16F else GlFormat.SRGB8_ALPHA8
        assertEquals("grader storage on the real context", expectedReal, gradedFormat(gl, real))

        // The same selector on the same context, told that RGBA16F is not renderable, must fall back to 8-bit.
        val withoutFloat = real.copy(floatColorBuffer = false)
        assertEquals(GlFormat.RGBA8, withoutFloat.workingFormat)
        assertTrue(withoutFloat.logDomain)
        assertFalse(withoutFloat.canMipmap(GlFormat.RGBA16F))
        assertEquals("grader storage without RGBA16F", GlFormat.SRGB8_ALPHA8, gradedFormat(gl, withoutFloat))
    }

    /** Builds the production grader for [capabilities] and returns the storage format of its output. */
    private fun gradedFormat(gl: OffscreenGl, capabilities: GlCapabilities): GlFormat {
        val grader = defaultLayerGrader(gl.environment(capabilities))
        try {
            assertTrue("defaultLayerGrader fell back to ${grader::class.simpleName} (shader build failed?)", grader is ShaderLayerGrader)
            val source = LinearRgbImage(4, 4, FloatArray(4 * 4 * 3) { 0.25f }).upload()
            try {
                return (grader as ShaderLayerGrader).gradeInto(source, RectF(0f, 0f, 4f, 4f), 4, 4, GradeParams.NEUTRAL, 0).format
            } finally {
                source.release()
            }
        } finally {
            grader.close()
        }
    }

    private class HalfFloatProbe(val complete: Boolean, val storedRed: Float)

    /** Raw GL, no engine code: RGBA16F texture → FBO → status, then clear to 2.5 and read it back. */
    private fun independentHalfFloatProbe(): HalfFloatProbe {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val texture = ids[0]
        GLES20.glGenFramebuffers(1, ids, 0)
        val framebuffer = ids[0]
        try {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, GLES30.GL_RGBA16F, 4, 4)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0)
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                GLES20.glGetError()
                return HalfFloatProbe(complete = false, storedRed = 0f)
            }
            GLES20.glViewport(0, 0, 4, 4)
            GLES20.glClearColor(2.5f, 0.5f, 0.25f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            return HalfFloatProbe(complete = true, storedRed = readFloatRgba(4, 4)[0])
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
        }
    }

    private fun intOf(name: Int): Int = IntArray(1).also { GLES20.glGetIntegerv(name, it, 0) }[0]

    private fun extensions(): Set<String> =
        (0 until intOf(GLES30.GL_NUM_EXTENSIONS)).mapNotNullTo(HashSet()) { GLES30.glGetStringi(GLES20.GL_EXTENSIONS, it) }
}
