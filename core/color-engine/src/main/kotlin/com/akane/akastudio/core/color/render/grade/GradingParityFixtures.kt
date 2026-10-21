package com.akane.akastudio.core.color.render.grade

import com.akane.akastudio.core.color.api.GradeParams
import com.akane.akastudio.core.color.api.HarmonizationParams
import com.akane.akastudio.core.color.api.MasterLook
import com.akane.akastudio.core.color.grade.CpuGradingPipeline
import com.akane.akastudio.core.color.grade.LookLutCache
import com.akane.akastudio.core.color.math.ColorMath
import com.akane.akastudio.core.color.render.RenderMath

/** One parity case: a name and the grade under test. */
internal data class ParityCase(val name: String, val params: GradeParams)

/**
 * Expected CPU output of one case: for each grid colour, the stage 1–6 result of `CpuGradingPipeline.gradePixel`
 * clamped to [0, 1] and sRGB-encoded (`expectedEncoded[i·3 + c]`).
 */
internal class ParityExpectation(val case: ParityCase, val expectedEncoded: FloatArray)

/**
 * The CPU side of the GPU grading precision contract: within the SDR range, [ShaderLayerGrader] stages 1–6
 * must match `CpuGradingPipeline` to ≤ [TOLERANCE_CODES]/255 after the sRGB encode (no halation or grain).
 *
 * The grid is [GRID_SIZE]² = 256 sRGB colours laid out row-major (row 0 = top of the 16×16 test image):
 * the 6³ cube on codes 0, 51, …, 255, 16 neutral greys (codes 8, 24, …, 248, off the cube) and 24 skin
 * tones (OKLCh L 0.5–0.8, C 0.05–0.11, h 40°/55°) so the skin mask is exercised. Linear inputs are rounded through half floats, which is what
 * the GPU sees in its RGBA16F source texture. Everything is deterministic; [table] renders it as text.
 * `GradingParityProbe` renders the same grid on a device and compares against [expectations].
 */
internal object GradingParityFixtures {
    const val GRID_SIZE = 16
    const val COLOR_COUNT = GRID_SIZE * GRID_SIZE
    const val TOLERANCE_CODES = 2f

    private val CUBE_CODES = intArrayOf(0, 51, 102, 153, 204, 255)
    private val SKIN_L = floatArrayOf(0.5f, 0.6f, 0.7f, 0.8f)
    private val SKIN_C = floatArrayOf(0.05f, 0.08f, 0.11f)
    private val SKIN_H = floatArrayOf(40f, 55f)
    private const val GREY_COUNT = 16
    private const val GREY_FIRST = 8
    private const val GREY_STEP = 16

    /** Six representative grades: one per major look family plus extreme sliders. */
    val CASES: List<ParityCase> = listOf(
        ParityCase("portra-default", GradeParams(look = MasterLook.PORTRA_400_GOLDEN)),
        ParityCase(
            "azure-harmonized",
            GradeParams(
                look = MasterLook.MEDITERRANEAN_AZURE,
                lookIntensity = 0.8f,
                harmonization = HarmonizationParams(1.05f, -0.02f, 0.9f, 0.01f, 1.1f, -0.015f),
                exposureEv = 0.3f,
            ),
        ),
        ParityCase(
            "cinestill-white-balance",
            GradeParams(look = MasterLook.CINESTILL_800T_NIGHT, temperature = -0.6f, tint = 0.4f, contrast = 0.3f),
        ),
        ParityCase(
            "hp5-monochrome-contrast",
            GradeParams(look = MasterLook.VELVET_MONOCHROME_HP5, contrast = 0.7f, exposureEv = -0.4f, skinProtection = 0f),
        ),
        ParityCase(
            "kodachrome-saturation-skin",
            GradeParams(look = MasterLook.KODACHROME_64_CLASSIC, saturation = 0.5f, skinProtection = 1f, lookIntensity = 0.6f),
        ),
        ParityCase(
            "espresso-extreme-sliders",
            GradeParams(
                look = MasterLook.ESPRESSO_WARM_WOOD,
                exposureEv = 2f,
                temperature = 1f,
                tint = -1f,
                contrast = -1f,
                saturation = 1f,
                skinProtection = 0.3f,
            ),
        ),
    )

    /** The 256 grid colours as opaque 8-bit sRGB ARGB, row-major. */
    fun gridArgb(): IntArray {
        val out = IntArray(COLOR_COUNT)
        var i = 0
        for (r in CUBE_CODES) for (g in CUBE_CODES) for (b in CUBE_CODES) out[i++] = argb(r, g, b)
        for (k in 0 until GREY_COUNT) {
            val v = GREY_FIRST + k * GREY_STEP
            out[i++] = argb(v, v, v)
        }
        for (l in SKIN_L) for (c in SKIN_C) for (h in SKIN_H) out[i++] = ColorMath.okLabToArgb(ColorMath.okLchToOkLab(l, c, h))
        check(i == COLOR_COUNT) { "grid must hold $COLOR_COUNT colours, built $i" }
        return out
    }

    /** Linear RGB of the grid (3 floats per colour), each rounded to the nearest half float. */
    fun gridLinear(): FloatArray {
        val grid = gridArgb()
        return FloatArray(COLOR_COUNT * 3) { j ->
            val code = (grid[j / 3] ushr (16 - 8 * (j % 3))) and 0xFF
            RenderMath.halfToFloat(RenderMath.floatToHalf(ColorMath.srgbToLinear(code / 255f)))
        }
    }

    /** Expected encoded outputs of every [CASES] entry. */
    fun expectations(): List<ParityExpectation> {
        val input = gridLinear()
        return CASES.map { case ->
            val luts = LookLutCache.get(case.params.look)
            val px = FloatArray(3)
            val expected = FloatArray(COLOR_COUNT * 3)
            for (i in 0 until COLOR_COUNT) {
                px[0] = input[i * 3]
                px[1] = input[i * 3 + 1]
                px[2] = input[i * 3 + 2]
                CpuGradingPipeline.gradePixel(px, case.params, luts, px)
                for (c in 0 until 3) expected[i * 3 + c] = encodeClamped(px[c])
            }
            ParityExpectation(case, expected)
        }
    }

    /** The sRGB code value [linear] shows as on an SDR display: clamped to [0, 1], then encoded. */
    fun encodeClamped(linear: Float): Float {
        val clamped = if (linear > 0f) (if (linear < 1f) linear else 1f) else 0f
        return ColorMath.linearToSrgb(clamped)
    }

    /** Deterministic text table: one line per grid colour and case (input code, expected encoded RGB). */
    fun table(expectations: List<ParityExpectation> = expectations()): String = buildString {
        val grid = gridArgb()
        append("# GradingParityFixtures: ").append(COLOR_COUNT).append(" colours x ").append(expectations.size)
            .append(" cases, expected sRGB-encoded stages 1-6, tolerance ").append(TOLERANCE_CODES).append("/255\n")
        for (e in expectations) {
            append("## ").append(e.case.name).append(' ').append(e.case.params).append('\n')
            for (i in 0 until COLOR_COUNT) {
                append(String.format(java.util.Locale.ROOT, "%3d #%06X", i, grid[i] and 0xFFFFFF))
                for (c in 0 until 3) append(String.format(java.util.Locale.ROOT, " %.6f", e.expectedEncoded[i * 3 + c]))
                append('\n')
            }
        }
    }

    private fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
