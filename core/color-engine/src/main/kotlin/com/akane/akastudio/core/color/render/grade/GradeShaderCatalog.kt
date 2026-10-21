package com.akane.akastudio.core.color.render.grade

import com.akane.akastudio.core.color.render.ProgramSpec
import com.akane.akastudio.core.color.render.ShaderCatalog
import com.akane.akastudio.core.color.render.Uniforms

/** Uniform names of the `grade/grade_*` shaders (the vertex-stage ones are shared through [Uniforms]). */
internal object GradeUniformNames {
    const val LUT_LOOK = "u_lutLook"
    const val LUT_SKIN_SAFE = "u_lutSkinSafe"
    const val LUT_SIZE = "u_lutSize"
    const val HARMONIZE = "u_harmonize"
    const val HARMONIZE_SCALE = "u_harmonizeScale"
    const val HARMONIZE_OFFSET = "u_harmonizeOffset"
    const val EXPOSURE = "u_exposure"
    const val WHITE_BALANCE = "u_whiteBalance"
    const val CONTRAST = "u_contrast"
    const val LOOK_INTENSITY = "u_lookIntensity"
    const val SKIN_PROTECTION = "u_skinProtection"
    const val CHROMA_SCALE = "u_chromaScale"
    const val SRC_TEXEL = "u_srcTexel"
    const val GLOW_0 = "u_glow0"
    const val GLOW_1 = "u_glow1"
    const val GLOW_2 = "u_glow2"
    const val HALATION_AMOUNT = "u_halationAmount"
    const val GRAIN_AMOUNT = "u_grainAmount"
    const val GRAIN_SIZE = "u_grainSize"
    const val GRAIN_LUMA = "u_grainLuma"
    const val GRAIN_CHROMA = "u_grainChroma"
    const val GRAIN_SEED = "u_grainSeed"
    const val GRAIN_ORIGIN = "u_grainOrigin"
    const val GRAIN_SCALE = "u_grainScale"
}

/**
 * Programs [ShaderLayerGrader] links. The `grade/` fragments take the [GlslConstants.prelude]; resampling,
 * downsampling and blurring reuse the compositor's `copy` and `blur` fragments as they are. Pure data so the
 * JVM `GlslConstantsTest` checks names against the sources.
 */
internal object GradeShaderCatalog {
    /** Asset sub-directory of the grading fragments (kept out of the compositor's flat shader catalog). */
    const val DIR = "grade/"

    private val vertexUniforms = listOf(Uniforms.DST_RECT, Uniforms.VIEW, Uniforms.TARGET_SIZE, Uniforms.UV_MATRIX)

    /** Stages 1-6 per pixel. */
    val PIXEL = ProgramSpec(
        ShaderCatalog.QUAD_VERTEX, DIR + "grade_pixel.frag.glsl",
        vertexUniforms + listOf(
            Uniforms.TEXTURE, GradeUniformNames.LUT_LOOK, GradeUniformNames.LUT_SKIN_SAFE, GradeUniformNames.LUT_SIZE,
            GradeUniformNames.HARMONIZE, GradeUniformNames.HARMONIZE_SCALE, GradeUniformNames.HARMONIZE_OFFSET,
            GradeUniformNames.EXPOSURE, GradeUniformNames.WHITE_BALANCE, GradeUniformNames.CONTRAST,
            GradeUniformNames.LOOK_INTENSITY, GradeUniformNames.SKIN_PROTECTION, GradeUniformNames.CHROMA_SCALE,
        ),
    )

    /** Halation bright pass into the half-resolution level. */
    val BRIGHT = ProgramSpec(
        ShaderCatalog.QUAD_VERTEX, DIR + "grade_bright.frag.glsl",
        vertexUniforms + listOf(Uniforms.TEXTURE, GradeUniformNames.SRC_TEXEL),
    )

    /** Halation composite and film grain. */
    val FINISH = ProgramSpec(
        ShaderCatalog.QUAD_VERTEX, DIR + "grade_finish.frag.glsl",
        vertexUniforms + listOf(
            Uniforms.TEXTURE, GradeUniformNames.GLOW_0, GradeUniformNames.GLOW_1, GradeUniformNames.GLOW_2,
            GradeUniformNames.HALATION_AMOUNT, GradeUniformNames.GRAIN_AMOUNT, GradeUniformNames.GRAIN_SIZE,
            GradeUniformNames.GRAIN_LUMA, GradeUniformNames.GRAIN_CHROMA, GradeUniformNames.GRAIN_SEED,
            GradeUniformNames.GRAIN_ORIGIN, GradeUniformNames.GRAIN_SCALE,
        ),
    )

    /** Programs whose fragment needs the generated constants. */
    val GRADING: List<ProgramSpec> = listOf(PIXEL, BRIGHT, FINISH)
}
