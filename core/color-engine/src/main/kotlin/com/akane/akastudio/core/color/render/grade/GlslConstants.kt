package com.akane.akastudio.core.color.render.grade

import com.akane.akastudio.core.color.grade.Halation
import com.akane.akastudio.core.color.grade.Mat3
import kotlin.math.sqrt

/**
 * The colour constants of the GPU grading shaders, generated from the Kotlin sources so each matrix and
 * threshold is written down once. [prelude] is a block of GLSL `const` declarations that
 * `ShaderProgram.load(..., fragmentPrelude)` inserts after the `#version` line of every `grade/grade_*` shader.
 *
 * Bradford and sRGB/XYZ matrices come straight from `grade.Mat3`; halation and grain constants from
 * `grade.Halation` / `grade.FilmGrain`. The OKLab matrices and the skin-window bounds are private literals
 * inside `ColorMath` and `SkinMask`, which this module must not change, so they are restated here and
 * `GlslConstantsTest` proves them identical to the CPU code (OKLab by bit-exact conversions, the skin window
 * by reading the private constants).
 *
 * Matrices are stored row-major (`out[r] = Σ m[r·3 + c]·in[c]`) and emitted column-major as GLSL `mat3`
 * expects, so `NAME * v` in GLSL equals the Kotlin row-major product.
 */
internal object GlslConstants {

    /** Linear sRGB → LMS (Ottosson 2020 M1). */
    val OKLAB_M1: DoubleArray = doubleArrayOf(
        0.4122214708, 0.5363325363, 0.0514459929,
        0.2119034982, 0.6806995451, 0.1073969566,
        0.0883024619, 0.2817188376, 0.6299787005,
    )

    /** Cube-rooted LMS → OKLab (Ottosson 2020 M2). */
    val OKLAB_M2: DoubleArray = doubleArrayOf(
        0.2104542553, 0.7936177850, -0.0040720468,
        1.9779984951, -2.4285922050, 0.4505937099,
        0.0259040371, 0.7827717662, -0.8086757660,
    )

    /** OKLab → cube-rooted LMS (inverse of [OKLAB_M2]). */
    val OKLAB_M2_INV: DoubleArray = doubleArrayOf(
        1.0, 0.3963377774, 0.2158037573,
        1.0, -0.1055613458, -0.0638541728,
        1.0, -0.0894841775, -1.2914855480,
    )

    /** LMS → linear sRGB (inverse of [OKLAB_M1]). */
    val OKLAB_M1_INV: DoubleArray = doubleArrayOf(
        4.0767416621, -3.3077115913, 0.2309699292,
        -1.2684380046, 2.6097574011, -0.3413193965,
        -0.0041960863, -0.7034186147, 1.7076147010,
    )

    /** Rec.709 luminance weights, as in `ColorMath.relativeLuminance`. */
    val LUMA_709: FloatArray = floatArrayOf(0.2126f, 0.7152f, 0.0722f)

    /** `SkinMask` window: lightness, chroma and hue bands with their feathers (hue in degrees). */
    const val SKIN_L_LO = 0.35f
    const val SKIN_L_HI = 0.88f
    const val SKIN_L_FEATHER = 0.05f
    const val SKIN_C_LO = 0.02f
    const val SKIN_C_HI = 0.14f
    const val SKIN_C_FEATHER = 0.015f
    const val SKIN_HUE_LO_DEG = 32f
    const val SKIN_HUE_HI_DEG = 68f
    const val SKIN_HUE_FEATHER_DEG = 6f
    const val SKIN_HUE_CENTRE_DEG = (SKIN_HUE_LO_DEG + SKIN_HUE_HI_DEG) / 2f
    const val SKIN_HUE_HALF_WIDTH_DEG = (SKIN_HUE_HI_DEG - SKIN_HUE_LO_DEG) / 2f

    /** `FilmGrain`'s scale from a sum of four centred uniforms to unit variance. */
    val GRAIN_UNIT_VARIANCE: Float = sqrt(3f)

    /** Row-major 3×3 matrices emitted as `const mat3`, in emission order. */
    val matrices: Map<String, DoubleArray> = linkedMapOf(
        "OKLAB_M1" to OKLAB_M1,
        "OKLAB_M2" to OKLAB_M2,
        "OKLAB_M2_INV" to OKLAB_M2_INV,
        "OKLAB_M1_INV" to OKLAB_M1_INV,
        "BRADFORD" to Mat3.BRADFORD,
        "BRADFORD_INV" to Mat3.BRADFORD_INV,
        "SRGB_TO_XYZ" to Mat3.SRGB_TO_XYZ,
        "XYZ_TO_SRGB" to Mat3.XYZ_TO_SRGB,
    )

    /** Three-component vectors emitted as `const vec3`. */
    val vectors: Map<String, FloatArray> = linkedMapOf(
        "LUMA_709" to LUMA_709,
        "HALATION_TINT" to Halation.TINT,
        "HALATION_WEIGHTS" to Halation.WEIGHTS,
    )

    /** Scalars emitted as `const float`. */
    val scalars: Map<String, Float> = linkedMapOf(
        "SKIN_L_LO" to SKIN_L_LO,
        "SKIN_L_HI" to SKIN_L_HI,
        "SKIN_L_FEATHER" to SKIN_L_FEATHER,
        "SKIN_C_LO" to SKIN_C_LO,
        "SKIN_C_HI" to SKIN_C_HI,
        "SKIN_C_FEATHER" to SKIN_C_FEATHER,
        "SKIN_HUE_CENTRE_DEG" to SKIN_HUE_CENTRE_DEG,
        "SKIN_HUE_HALF_WIDTH_DEG" to SKIN_HUE_HALF_WIDTH_DEG,
        "SKIN_HUE_FEATHER_DEG" to SKIN_HUE_FEATHER_DEG,
        "HALATION_THRESHOLD" to Halation.THRESHOLD,
        "HALATION_KNEE" to Halation.KNEE,
        "GRAIN_UNIT_VARIANCE" to GRAIN_UNIT_VARIANCE,
    )

    /**
     * GLSL declarations for every constant above. Starts with its own `precision` statements because it is
     * inserted before the shader's own, and fragment shaders have no default float precision.
     */
    val prelude: String by lazy { emit() }

    /** A float as a GLSL ES 3.00 literal that parses back to exactly [value] (no suffix, always a point or exponent). */
    fun literal(value: Float): String {
        require(value.isFinite()) { "GLSL constants must be finite: $value" }
        return value.toString()
    }

    /** Column-major element order of a row-major 3×3 matrix. */
    fun columnMajor(rowMajor: DoubleArray): FloatArray {
        require(rowMajor.size == 9) { "a 3x3 matrix has 9 elements" }
        return FloatArray(9) { i -> rowMajor[(i % 3) * 3 + i / 3].toFloat() }
    }

    private fun emit(): String = buildString {
        append("// Generated by GlslConstants (render/grade); do not edit in the shader sources.\n")
        append("precision highp float;\n")
        append("precision highp int;\n")
        for ((name, m) in matrices) {
            append("const mat3 ").append(name).append(" = mat3(")
            append(columnMajor(m).joinToString(", ") { literal(it) }).append(");\n")
        }
        for ((name, v) in vectors) {
            require(v.size == 3) { "$name must have 3 components" }
            append("const vec3 ").append(name).append(" = vec3(")
            append(v.joinToString(", ") { literal(it) }).append(");\n")
        }
        for ((name, s) in scalars) {
            append("const float ").append(name).append(" = ").append(literal(s)).append(";\n")
        }
    }
}
