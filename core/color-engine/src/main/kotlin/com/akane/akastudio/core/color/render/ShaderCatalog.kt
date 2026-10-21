package com.akane.akastudio.core.color.render

/** One linked program: asset file names under `assets/shaders` and the uniforms the Kotlin side sets. */
internal data class ProgramSpec(val vertex: String, val fragment: String, val uniforms: List<String>)

/** Uniform names shared by the Kotlin compositor and the GLSL sources. */
internal object Uniforms {
    const val DST_RECT = "u_dstRect"
    const val VIEW = "u_view"
    const val TARGET_SIZE = "u_targetSize"
    const val UV_MATRIX = "u_uvMatrix"
    const val TEXTURE = "u_tex"
    const val HALF_SIZE = "u_halfSize"
    const val RADIUS = "u_radius"
    const val AA_SCALE = "u_aaScale"
    const val SRC_LOG = "u_srcLog"
    const val DST_LOG = "u_dstLog"
    const val GAIN = "u_gain"
    const val TEXEL_STEP = "u_texelStep"
    const val WEIGHTS = "u_weights"
    const val BLUR_RADIUS = "u_blurRadius"
    const val MATTE = "u_matte"
    const val COLOR = "u_color"
}

/** Every program the compositor links. Pure data so JVM tests can check it against the shader sources. */
internal object ShaderCatalog {
    const val QUAD_VERTEX = "quad.vert.glsl"

    private val vertexUniforms = listOf(Uniforms.DST_RECT, Uniforms.VIEW, Uniforms.TARGET_SIZE, Uniforms.UV_MATRIX)

    /** Image layer: rounded-rect SDF coverage over a linear texture. */
    val LAYER = ProgramSpec(
        QUAD_VERTEX, "layer.frag.glsl",
        vertexUniforms + listOf(Uniforms.TEXTURE, Uniforms.HALF_SIZE, Uniforms.RADIUS, Uniforms.AA_SCALE, Uniforms.DST_LOG),
    )

    /** Resampling copy with a gain on colour (cover-fit downsample, frosted upscale + dim). */
    val COPY = ProgramSpec(
        QUAD_VERTEX, "copy.frag.glsl",
        vertexUniforms + listOf(Uniforms.TEXTURE, Uniforms.GAIN, Uniforms.SRC_LOG, Uniforms.DST_LOG),
    )

    /** One direction of the separable Gaussian. */
    val BLUR = ProgramSpec(
        QUAD_VERTEX, "blur.frag.glsl",
        vertexUniforms + listOf(Uniforms.TEXTURE, Uniforms.TEXEL_STEP, Uniforms.WEIGHTS, Uniforms.BLUR_RADIUS, Uniforms.SRC_LOG, Uniforms.DST_LOG),
    )

    /** Rasterized text: premultiplied sRGB bitmap composited in linear light. */
    val OVERLAY = ProgramSpec(
        QUAD_VERTEX, "overlay.frag.glsl",
        vertexUniforms + listOf(Uniforms.TEXTURE, Uniforms.DST_LOG),
    )

    /** Final pass: composite over the matte and sRGB-encode (IEC 61966-2-1), clamped. */
    val PRESENT = ProgramSpec(
        QUAD_VERTEX, "present.frag.glsl",
        vertexUniforms + listOf(Uniforms.TEXTURE, Uniforms.SRC_LOG, Uniforms.MATTE),
    )

    /** Flat colour written as is (display-space divider line). */
    val SOLID = ProgramSpec(QUAD_VERTEX, "solid.frag.glsl", vertexUniforms + listOf(Uniforms.COLOR))

    val ALL: List<ProgramSpec> = listOf(LAYER, COPY, BLUR, OVERLAY, PRESENT, SOLID)
}
