package com.akane.akastudio.core.color.look

import com.akane.akastudio.core.color.api.OkLab
import com.akane.akastudio.core.color.grade.WhiteBalance
import com.akane.akastudio.core.color.math.ColorMath
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Bakes a [LookSpec] into [SIZE]³ lattices over encoded sRGB (input and output encoded, output in [0, 1]).
 *
 * Full look, per node: warmth (Bradford, linear, clamped to [0, 1]) → monochrome mix (linear) →
 * tone curve per encoded channel → OKLCh hue-shift windows → global chroma → split tone → gamut fit that
 * keeps OKLab L and reduces chroma until the colour is inside linear [0, 1]³.
 *
 * Skin-safe variant: same tone curve (and the same monochrome conversion, using Rec.709 weights instead of
 * the creative channel mix so faces keep their natural brightness); warmth, hue shifts and split tone are
 * removed; the total chroma change against the node's input colour (tone-curve chroma times global chroma)
 * is halved. A monochrome look stays monochrome on skin.
 */
object LutBaker {
    /** Lattice points per axis. */
    const val SIZE = 33

    /** The full creative look. */
    fun bakeLook(spec: LookSpec): Lut3D = bake(spec, skinSafe = false)

    /** The skin-protected variant used under the skin mask. */
    fun bakeSkinSafe(spec: LookSpec): Lut3D = bake(spec, skinSafe = true)

    private fun bake(spec: LookSpec, skinSafe: Boolean): Lut3D {
        val warmth = if (!skinSafe && spec.warmthMired != 0f) {
            WhiteBalance.adaptation(miredShift = spec.warmthMired.toDouble(), greenDuv = 0.0)
        } else {
            null
        }
        val node = NodeBaker(spec, skinSafe, warmth)
        val data = FloatArray(SIZE * SIZE * SIZE * 3)
        val max = (SIZE - 1).toFloat()
        val out = FloatArray(3)
        var i = 0
        for (b in 0 until SIZE) for (g in 0 until SIZE) for (r in 0 until SIZE) {
            node.bake(r / max, g / max, b / max, out)
            data[i++] = out[0]
            data[i++] = out[1]
            data[i++] = out[2]
        }
        return Lut3D(SIZE, data)
    }

    /** Per-node transform; holds scratch buffers, so one instance per bake. */
    private class NodeBaker(
        private val spec: LookSpec,
        private val skinSafe: Boolean,
        private val warmth: FloatArray?,
    ) {
        private val lin = FloatArray(3)
        private val tmp = FloatArray(3)

        fun bake(r: Float, g: Float, b: Float, out: FloatArray) {
            lin[0] = ColorMath.srgbToLinear(r)
            lin[1] = ColorMath.srgbToLinear(g)
            lin[2] = ColorMath.srgbToLinear(b)
            val input = ColorMath.linearSrgbToOkLab(lin[0], lin[1], lin[2])

            warmth?.let { applyMatrixClamped(it, lin) }
            spec.monochrome?.let { mix ->
                val y = if (skinSafe) {
                    ColorMath.relativeLuminance(lin[0], lin[1], lin[2])
                } else {
                    mix.r * lin[0] + mix.g * lin[1] + mix.b * lin[2]
                }
                val lum = clamp01(y)
                lin[0] = lum
                lin[1] = lum
                lin[2] = lum
            }
            for (c in 0 until 3) {
                lin[c] = ColorMath.srgbToLinear(spec.tone.apply(ColorMath.linearToSrgb(lin[c])))
            }
            val toned = ColorMath.linearSrgbToOkLab(lin[0], lin[1], lin[2])
            val graded = if (skinSafe) skinSafeColour(input, toned) else creativeColour(toned)
            fitToGamut(graded.l, graded.a, graded.b, tmp)
            for (c in 0 until 3) out[c] = clamp01(ColorMath.linearToSrgb(tmp[c]))
        }

        private fun skinSafeColour(input: OkLab, toned: OkLab): OkLab {
            if (spec.monochrome != null) return OkLab(toned.l, 0f, 0f)
            val inC = hypot(input.a, input.b)
            val tonedC = hypot(toned.a, toned.b)
            val target = (inC + 0.5f * (tonedC * spec.globalChroma - inC)).coerceAtLeast(0f)
            return when {
                tonedC > HUE_EPS -> OkLab(toned.l, toned.a * target / tonedC, toned.b * target / tonedC)
                inC > HUE_EPS -> OkLab(toned.l, input.a * target / inC, input.b * target / inC)
                else -> OkLab(toned.l, 0f, 0f)
            }
        }

        private fun creativeColour(toned: OkLab): OkLab {
            var l = toned.l
            var chroma = hypot(toned.a, toned.b)
            var hue = Math.toDegrees(atan2(toned.b, toned.a).toDouble()).toFloat()
            if (spec.hueShifts.isNotEmpty() && chroma > 0f) {
                val gate = smoothstep(CHROMA_GATE_LO, CHROMA_GATE_HI, chroma)
                var dHue = 0f
                var cScale = 1f
                var dL = 0f
                for (shift in spec.hueShifts) {
                    val w = shift.weight(hue) * gate
                    dHue += w * shift.hueShiftDeg
                    cScale *= 1f + w * (shift.chromaScale - 1f)
                    dL += w * shift.lightnessShift
                }
                hue += dHue
                chroma *= cScale
                l += dL
            }
            chroma *= spec.globalChroma
            val h = Math.toRadians(hue.toDouble())
            var a = (chroma * cos(h)).toFloat()
            var b = (chroma * sin(h)).toFloat()

            val split = spec.split
            val pivot = 0.15f * split.balance
            val wSh = 1f - smoothstep(0.15f + pivot, 0.55f + pivot, l)
            val wHi = smoothstep(0.45f + pivot, 0.85f + pivot, l)
            val sh = Math.toRadians(split.shadowHueDeg.toDouble())
            val hi = Math.toRadians(split.highlightHueDeg.toDouble())
            a += (wSh * split.shadowChroma * cos(sh) + wHi * split.highlightChroma * cos(hi)).toFloat()
            b += (wSh * split.shadowChroma * sin(sh) + wHi * split.highlightChroma * sin(hi)).toFloat()
            return OkLab(l, a, b)
        }
    }

    private const val HUE_EPS = 1e-6f
    private const val CHROMA_GATE_LO = 0.01f
    private const val CHROMA_GATE_HI = 0.05f

    private fun applyMatrixClamped(m: FloatArray, rgb: FloatArray) {
        val r = rgb[0]
        val g = rgb[1]
        val b = rgb[2]
        rgb[0] = clamp01(m[0] * r + m[1] * g + m[2] * b)
        rgb[1] = clamp01(m[3] * r + m[4] * g + m[5] * b)
        rgb[2] = clamp01(m[6] * r + m[7] * g + m[8] * b)
    }
}

/**
 * Writes the linear sRGB of (L, a, b) into [out], with L clamped to [0, 1] and chroma reduced by bisection
 * (hue and L kept) until every channel lies in [0, 1].
 */
internal fun fitToGamut(l: Float, a: Float, b: Float, out: FloatArray) {
    val lc = clamp01(l)
    ColorMath.okLabToLinearSrgb(OkLab(lc, a, b), out)
    if (inUnitCube(out)) return
    var lo = 0f
    var hi = 1f
    repeat(GAMUT_ITERATIONS) {
        val mid = 0.5f * (lo + hi)
        ColorMath.okLabToLinearSrgb(OkLab(lc, a * mid, b * mid), out)
        if (inUnitCube(out)) lo = mid else hi = mid
    }
    ColorMath.okLabToLinearSrgb(OkLab(lc, a * lo, b * lo), out)
    for (c in 0 until 3) out[c] = clamp01(out[c])
}

private const val GAMUT_ITERATIONS = 20
private const val GAMUT_EPS = 1e-5f

private fun inUnitCube(rgb: FloatArray): Boolean =
    rgb[0] >= -GAMUT_EPS && rgb[0] <= 1f + GAMUT_EPS &&
        rgb[1] >= -GAMUT_EPS && rgb[1] <= 1f + GAMUT_EPS &&
        rgb[2] >= -GAMUT_EPS && rgb[2] <= 1f + GAMUT_EPS
