package com.akane.akastudio.core.color.gl

import android.opengl.GLES20
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit quad (0,0)-(1,1) as a triangle strip in a VAO, bound to attribute location [POSITION_LOCATION]
 * (`layout(location = 0) in vec2 a_pos` in `quad.vert.glsl`). GL thread only.
 */
internal class FullscreenQuad private constructor(private val vao: Int, private val vbo: Int) {
    private var released = false

    fun draw() {
        GLES30.glBindVertexArray(vao)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
    }

    fun release() {
        if (released) return
        released = true
        GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        GLES20.glDeleteBuffers(1, intArrayOf(vbo), 0)
    }

    companion object {
        const val POSITION_LOCATION = 0
        private val CORNERS = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

        fun create(): FullscreenQuad {
            val ids = IntArray(1)
            GLES30.glGenVertexArrays(1, ids, 0)
            val vao = ids[0]
            GLES20.glGenBuffers(1, ids, 0)
            val vbo = ids[0]
            val data = ByteBuffer.allocateDirect(CORNERS.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
            data.asFloatBuffer().put(CORNERS)
            GLES30.glBindVertexArray(vao)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.capacity(), data, GLES20.GL_STATIC_DRAW)
            GLES20.glEnableVertexAttribArray(POSITION_LOCATION)
            GLES20.glVertexAttribPointer(POSITION_LOCATION, 2, GLES20.GL_FLOAT, false, 2 * Float.SIZE_BYTES, 0)
            GLES30.glBindVertexArray(0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            checkGlError("quad VAO setup")
            return FullscreenQuad(vao, vbo)
        }
    }
}
