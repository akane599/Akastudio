package com.akane.akastudio.core.color.device

import android.opengl.GLES20
import android.opengl.GLES30
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akane.akastudio.core.color.gl.GlFormat
import com.akane.akastudio.core.color.gl.GlFramebuffer
import com.akane.akastudio.core.color.gl.checkGlError
import com.akane.akastudio.core.color.render.Mat3
import com.akane.akastudio.core.color.render.Uniforms
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Diagnostic only: the same known RGB16F lattice, fetched directly or through a sampler parameter. */
@RunWith(AndroidJUnit4::class)
class LutSamplerDiagnosticDeviceTest {

    @Test
    fun directFetchOfKnownRgb16fNodesMatchesTheFixture() = probe(parameter = false)

    @Test
    fun samplerParameterFetchOfKnownRgb16fNodesMatchesTheFixture() = probe(parameter = true)

    @Test
    fun nearestFetchOfKnownRgb16fNodesMatchesTheFixture() = probe(parameter = false, filter = GLES20.GL_NEAREST)

    @Test
    fun directFetchOfKnownRgba16fNodesMatchesTheFixture() = probe(parameter = false, rgba = true)

    private fun probe(parameter: Boolean, filter: Int = GLES20.GL_LINEAR, rgba: Boolean = false) = OffscreenGl.open().use { gl ->
        assertTrue("probe requires an RGBA16F target", gl.capabilities.floatColorBuffer)
        val mode = "${if (parameter) "sampler-parameter" else "direct-uniform"} " +
            "${if (rgba) "RGBA16F" else "RGB16F"} ${if (filter == GLES20.GL_NEAREST) "NEAREST" else "LINEAR"}"
        val texture = uploadLattice(filter, rgba)
        var program = 0
        var target: GlFramebuffer? = null
        try {
            program = link(fragment(parameter))
            target = GlFramebuffer.create(2, 4, GlFormat.RGBA16F)
            target.bind()
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            GLES20.glDisable(GLES20.GL_CULL_FACE)
            GLES20.glColorMask(true, true, true, true)
            GLES20.glClearColor(0.875f, 0.625f, 0.375f, 0.5f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            GLES20.glUniform4f(GLES20.glGetUniformLocation(program, Uniforms.DST_RECT), 0f, 0f, 2f, 4f)
            GLES20.glUniform4f(GLES20.glGetUniformLocation(program, Uniforms.VIEW), 1f, 1f, 0f, 0f)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(program, Uniforms.TARGET_SIZE), 2f, 4f)
            GLES20.glUniformMatrix3fv(GLES20.glGetUniformLocation(program, Uniforms.UV_MATRIX), 1, false, Mat3.IDENTITY.values, 0)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "u_probe"), 1)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + 1)
            GLES20.glBindTexture(GLES30.GL_TEXTURE_3D, texture)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            gl.quad.draw()
            checkGlError("3D diagnostic $mode draw")
            val actual = readFloatRgba(2, 4)
            report("3D diagnostic $mode RGBA, framebuffer row 0 first: ${actual.asList().chunked(4)}")
            assertEquals("$mode must preserve eight distinct RGB nodes", 8, actual.asList().chunked(4).map { it.take(3) }.toSet().size)
            for (pixel in 0 until 8) {
                val x = pixel % 2
                val row = pixel / 2
                for (channel in 0 until 3) {
                    val expected = component(x, row and 1, row shr 1, channel)
                    assertEquals("$mode pixel $pixel channel $channel", expected, actual[pixel * 4 + channel], 0f)
                }
                assertEquals("$mode pixel $pixel alpha", 1f, actual[pixel * 4 + 3], 0f)
            }
        } finally {
            target?.release()
            if (program != 0) GLES20.glDeleteProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + 1)
            GLES20.glBindTexture(GLES30.GL_TEXTURE_3D, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
        }
    }

    /** Eight exact nodes through the LUT upload path, optionally changing only filter or component format. */
    private fun uploadLattice(filter: Int, rgba: Boolean): Int {
        val channels = if (rgba) 4 else 3
        val pixels = FloatArray(8 * channels) { i ->
            val node = i / channels
            if (i % channels == 3) 1f else component(node % 2, (node / 2) % 2, node / 4, i % channels)
        }
        val format = if (rgba) GLES20.GL_RGBA else GLES20.GL_RGB
        val internalFormat = if (rgba) GLES30.GL_RGBA16F else GLES30.GL_RGB16F
        val buffer = ByteBuffer.allocateDirect(pixels.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        buffer.asFloatBuffer().put(pixels)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val texture = ids[0]
        try {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES30.GL_TEXTURE_3D, texture)
            GLES30.glTexStorage3D(GLES30.GL_TEXTURE_3D, 1, internalFormat, 2, 2, 2)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            GLES30.glTexSubImage3D(GLES30.GL_TEXTURE_3D, 0, 0, 0, 0, 2, 2, 2, format, GLES20.GL_FLOAT, buffer)
            GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
            GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
            GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindTexture(GLES30.GL_TEXTURE_3D, 0)
            checkGlError("3D diagnostic ${if (rgba) "RGBA16F" else "RGB16F"} upload")
            return texture
        } catch (t: Throwable) {
            GLES20.glDeleteTextures(1, ids, 0)
            throw t
        }
    }

    private fun component(x: Int, y: Int, z: Int, channel: Int): Float = when (channel) {
        0 -> 0.125f + 0.5f * x
        1 -> 0.25f + 0.25f * y
        else -> 0.25f + 0.5f * z
    }

    private fun fragment(parameter: Boolean): String = """
        #version 300 es
        precision highp float;
        precision highp int;
        precision highp sampler3D;
        uniform highp sampler3D u_probe;
        layout(location = 0) out vec4 o_color;
        ${if (parameter) "vec4 readNode(highp sampler3D s, ivec3 p) { return texelFetch(s, p, 0); }" else ""}
        void main() {
            int row = int(gl_FragCoord.y);
            ivec3 node = ivec3(int(gl_FragCoord.x), row & 1, row >> 1);
            o_color = ${if (parameter) "readNode(u_probe, node)" else "texelFetch(u_probe, node, 0)"};
        }
    """.trimIndent()

    /** ShaderProgram is asset-only; this helper keeps diagnostic shader strings out of production assets/APIs. */
    private fun link(fragment: String): Int {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, testAssets.open("shaders/quad.vert.glsl").bufferedReader().use { it.readText() })
        try {
            val pixel = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
            try {
                val program = GLES20.glCreateProgram()
                check(program != 0) { "diagnostic glCreateProgram failed" }
                try {
                    GLES20.glAttachShader(program, vertex)
                    GLES20.glAttachShader(program, pixel)
                    GLES20.glLinkProgram(program)
                    val status = IntArray(1)
                    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
                    check(status[0] != 0) { "diagnostic link failed: ${GLES20.glGetProgramInfoLog(program)}" }
                    return program
                } catch (t: Throwable) {
                    GLES20.glDeleteProgram(program)
                    throw t
                }
            } finally {
                GLES20.glDeleteShader(pixel)
            }
        } finally {
            GLES20.glDeleteShader(vertex)
        }
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        check(shader != 0) { "diagnostic glCreateShader failed" }
        try {
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { "diagnostic compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
            return shader
        } catch (t: Throwable) {
            GLES20.glDeleteShader(shader)
            throw t
        }
    }
}
