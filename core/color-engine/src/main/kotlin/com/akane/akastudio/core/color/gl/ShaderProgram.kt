package com.akane.akastudio.core.color.gl

import android.content.res.AssetManager
import android.opengl.GLES20

/**
 * A linked vertex + fragment program loaded from `assets/shaders/<name>`. Sources are self-contained
 * (`#include` is rejected). Compile and link failures throw [GlException] carrying the driver info log.
 */
internal class ShaderProgram private constructor(val id: Int, private val label: String) {
    private val locations = HashMap<String, Int>()
    private var released = false

    fun use() {
        GLES20.glUseProgram(id)
    }

    /**
     * Location of [name], or -1 when the linker eliminated it (for example the vertex `u_uvMatrix` in a
     * program whose fragment stage ignores `v_uv`); GL defines uploads to -1 as no-ops. Name drift between
     * Kotlin and GLSL is caught by the JVM `ShaderSourcesTest` against [label]'s catalog entry.
     */
    fun location(name: String): Int = locations.getOrPut(name) { GLES20.glGetUniformLocation(id, name) }

    fun setInt(name: String, value: Int) = GLES20.glUniform1i(location(name), value)
    fun setFloat(name: String, value: Float) = GLES20.glUniform1f(location(name), value)
    fun setVec2(name: String, x: Float, y: Float) = GLES20.glUniform2f(location(name), x, y)
    fun setVec3(name: String, x: Float, y: Float, z: Float) = GLES20.glUniform3f(location(name), x, y, z)
    fun setVec4(name: String, x: Float, y: Float, z: Float, w: Float) = GLES20.glUniform4f(location(name), x, y, z, w)
    fun setFloatArray(name: String, values: FloatArray, count: Int = values.size) =
        GLES20.glUniform1fv(location(name), count, values, 0)

    /** [columnMajor] holds 9 floats in column-major order. */
    fun setMat3(name: String, columnMajor: FloatArray) = GLES20.glUniformMatrix3fv(location(name), 1, false, columnMajor, 0)

    fun release() {
        if (released) return
        released = true
        GLES20.glDeleteProgram(id)
    }

    override fun toString(): String = "ShaderProgram($label)"

    companion object {
        private const val SHADER_DIR = "shaders"

        fun load(assets: AssetManager, vertexName: String, fragmentName: String): ShaderProgram =
            load(assets, vertexName, fragmentName, fragmentPrelude = "")

        /**
         * Same as [load], with [fragmentPrelude] (declarations such as generated `const` matrices) inserted into
         * the fragment source right after its first line, which must be the `#version` directive.
         */
        fun load(assets: AssetManager, vertexName: String, fragmentName: String, fragmentPrelude: String): ShaderProgram {
            val label = "$vertexName+$fragmentName"
            val vertex = compile(GLES20.GL_VERTEX_SHADER, vertexName, readSource(assets, vertexName))
            val fragment = try {
                val source = withPrelude(fragmentName, readSource(assets, fragmentName), fragmentPrelude)
                compile(GLES20.GL_FRAGMENT_SHADER, fragmentName, source)
            } catch (e: GlException) {
                GLES20.glDeleteShader(vertex)
                throw e
            }
            val program = GLES20.glCreateProgram()
            if (program == 0) {
                GLES20.glDeleteShader(vertex)
                GLES20.glDeleteShader(fragment)
                throw GlException("glCreateProgram failed for $label")
            }
            GLES20.glAttachShader(program, vertex)
            GLES20.glAttachShader(program, fragment)
            GLES20.glLinkProgram(program)
            // Shaders are flagged for deletion now and freed with the program.
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(program)
                GLES20.glDeleteProgram(program)
                throw GlException("link failed for $label: $log")
            }
            return ShaderProgram(program, label)
        }

        private fun readSource(assets: AssetManager, name: String): String {
            val source = assets.open("$SHADER_DIR/$name").bufferedReader().use { it.readText() }
            if (source.lineSequence().any { it.trimStart().startsWith("#include") }) {
                throw GlException("$name uses #include; shader sources must be self-contained")
            }
            return source
        }

        private fun withPrelude(name: String, source: String, prelude: String): String {
            if (prelude.isEmpty()) return source
            val firstLineEnd = source.indexOf('\n')
            if (firstLineEnd < 0 || !source.substring(0, firstLineEnd).trimStart().startsWith("#version")) {
                throw GlException("$name must start with a #version line to take a prelude")
            }
            return source.substring(0, firstLineEnd + 1) + prelude.trimEnd('\n') + "\n" + source.substring(firstLineEnd + 1)
        }

        private fun compile(type: Int, name: String, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            if (shader == 0) throw GlException("glCreateShader failed for $name")
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw GlException("compile failed for $name: $log")
            }
            return shader
        }
    }
}
