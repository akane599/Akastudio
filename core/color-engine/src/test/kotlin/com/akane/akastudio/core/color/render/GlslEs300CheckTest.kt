package com.akane.akastudio.core.color.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlslEs300CheckTest {

    private fun fragment(body: String, declarations: String = "") = """
        #version 300 es
        precision highp float;
        uniform sampler2D u_tex;
        $declarations
        layout(location = 0) out vec4 o_color;
        void main() {
            $body
        }
    """.trimIndent()

    @Test
    fun plainEs300Fragment_passes() {
        val source = fragment(
            body = "mat3 m = mat3(1.0); float x = m[0][1]; o_color = texture(u_tex, vec2(x)) * texelFetch(u_tex, ivec2(0), 0);",
            declarations = "layout(std140) uniform Block { vec4 u_v; };\nfloat u_w[4];",
        )
        assertEquals(emptyList<String>(), GlslEs300Check.violations(source, "frag"))
    }

    @Test
    fun es31OnlyBuiltIns_areRejected() {
        for (call in listOf(
            "o_color = textureGather(u_tex, vec2(0.0));",
            "o_color = vec4(float(bitfieldExtract(7, 0, 2)));",
            "o_color = unpackUnorm4x8(1u);",
            "o_color = vec4(float(gl_HelperInvocation));",
        )) {
            assertTrue(call, GlslEs300Check.violations(fragment(call), "frag").isNotEmpty())
        }
    }

    @Test
    fun es31OnlyDeclarations_areRejected() {
        val cases = mapOf(
            "layout(binding = 1) uniform sampler2D u_b;" to "frag",
            "layout(std430) buffer Data { float d[]; };" to "frag",
            "layout(location = 2) in vec2 v_uv;" to "frag",
            "layout(location = 2) out vec2 v_uv;" to "vert",
            "float u_grid[2][3];" to "frag",
            "uniform highp image2D u_img;" to "frag",
            "uniform highp sampler2DMS u_ms;" to "frag",
        )
        for ((declaration, stage) in cases) {
            assertTrue(declaration, GlslEs300Check.violations(fragment("o_color = vec4(0.0);", declaration), stage).isNotEmpty())
        }
    }

    @Test
    fun commentsAreIgnored() {
        val source = fragment("// textureGather is ES 3.1\n/* layout(binding = 0) */ o_color = vec4(0.0);")
        assertEquals(emptyList<String>(), GlslEs300Check.violations(source, "frag"))
    }
}
