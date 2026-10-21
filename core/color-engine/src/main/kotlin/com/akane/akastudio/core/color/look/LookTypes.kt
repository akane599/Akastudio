package com.akane.akastudio.core.color.look

import com.akane.akastudio.core.color.api.MasterLook
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow

/**
 * Two-zone OKLab tint. Shadows receive the (a, b) offset of hue [shadowHueDeg] at chroma [shadowChroma],
 * highlights that of [highlightHueDeg] at [highlightChroma]. Zone weights over OKLab L are
 * `w_sh = 1 − smoothstep(0.15 + p, 0.55 + p, L)` and `w_hi = smoothstep(0.45 + p, 0.85 + p, L)` with
 * `p = 0.15·balance`, so a positive [balance] widens the shadow zone and narrows the highlight zone.
 */
data class SplitTone(
    val shadowHueDeg: Float,
    val shadowChroma: Float,
    val highlightHueDeg: Float,
    val highlightChroma: Float,
    val balance: Float,
) {
    init {
        require(shadowHueDeg.isFinite() && highlightHueDeg.isFinite()) { "split-tone hues must be finite" }
        require(shadowChroma in 0f..0.1f && highlightChroma in 0f..0.1f) { "split-tone chroma must be in [0, 0.1]" }
        require(balance in -1f..1f) { "split-tone balance must be in [-1, 1]" }
    }

    companion object {
        /** No tint in either zone. */
        val NONE = SplitTone(0f, 0f, 0f, 0f, 0f)
    }
}

/**
 * Hue-selective OKLCh adjustment under a raised-cosine window centred at [centerDeg].
 * [widthDeg] is the half-width: the weight is `0.5·(1 + cos(π·d/widthDeg))` for an angular distance
 * `d < widthDeg` and 0 beyond. Inside the window the hue rotates by [hueShiftDeg], chroma is multiplied
 * by [chromaScale] and OKLab L moves by [lightnessShift], each scaled by the weight. The weight is gated
 * by chroma so neutrals (whose hue is undefined) are untouched.
 */
data class HueShift(
    val centerDeg: Float,
    val widthDeg: Float,
    val hueShiftDeg: Float,
    val chromaScale: Float,
    val lightnessShift: Float,
) {
    init {
        require(centerDeg.isFinite() && hueShiftDeg.isFinite()) { "hue values must be finite" }
        require(widthDeg > 0f && widthDeg <= 180f) { "widthDeg must be in (0, 180]" }
        require(chromaScale in 0f..2f) { "chromaScale must be in [0, 2]" }
        require(lightnessShift in -0.2f..0.2f) { "lightnessShift must be in [-0.2, 0.2]" }
    }

    /** Window weight for hue [hueDeg], before the chroma gate. */
    internal fun weight(hueDeg: Float): Float {
        val d = hueDistance(hueDeg, centerDeg)
        return if (d >= widthDeg) 0f else (0.5 * (1.0 + cos(Math.PI * d / widthDeg))).toFloat()
    }
}

/**
 * Per-channel tone curve on encoded sRGB values in [0, 1], applied as filmic → contrast → black lift:
 *
 * - **Toe** (below 0.25): `p(x) = 0.25·(x/0.25)^(1 + toe)` blended into the identity with
 *   `smoothstep(0, 0.25, x)`, which keeps the join C1 and crushes the deepest shadows for `toe > 0`.
 * - **Shoulder** (above `s0 = 1 − shoulder`): with `u = (x − s0)/shoulder`, the roll-off target
 *   `h(u) = s0 + (whiteClip − s0)·(1 − (1 − u)²)` is blended in with `smoothstep(s0, 1, x)`. The curve
 *   joins the identity with slope 1 at `s0`, reaches [whiteClip] at 1 with slope 0 and so compresses
 *   highlights softly toward [whiteClip].
 * - **Contrast** (pivot 0.45): `0.45·(y/0.45)^(1 + contrast)` below the pivot and
 *   `1 − 0.55·((1 − y)/0.55)^(1 + contrast)` above it. Endpoints stay fixed and the slope at the pivot
 *   is `1 + contrast`.
 * - **Black lift**: `y = blackLift + (1 − blackLift)·y`, applied last so contrast cannot crush the matte floor.
 *
 * Every stage is monotone for the accepted parameter ranges.
 */
data class ToneCurve(
    val blackLift: Float,
    val toe: Float,
    val shoulder: Float,
    val contrast: Float,
    val whiteClip: Float,
) {
    init {
        require(blackLift in 0f..0.3f) { "blackLift must be in [0, 0.3]" }
        require(toe in 0f..1f) { "toe must be in [0, 1]" }
        require(shoulder in 0f..0.7f) { "shoulder must be in [0, 0.7]" }
        require(contrast in -0.9f..0.9f) { "contrast must be in [-0.9, 0.9]" }
        require(whiteClip <= 1f && whiteClip >= 1f - 0.2f * shoulder) {
            "whiteClip must be in [1 - 0.2*shoulder, 1] to keep the shoulder monotone"
        }
    }

    /** Applies the curve to one encoded channel value; input is clamped to [0, 1]. */
    internal fun apply(x: Float): Float {
        var y = clamp01(x).toDouble()
        y = toeSegment(y)
        y = shoulderSegment(y)
        y = contrastSegment(y)
        return (blackLift + (1.0 - blackLift) * y).toFloat()
    }

    private fun toeSegment(x: Double): Double {
        if (toe == 0f || x >= TOE_END) return x
        val p = TOE_END * (x / TOE_END).pow(1.0 + toe)
        val w = smoothstep(0.0, TOE_END, x)
        return p + (x - p) * w
    }

    private fun shoulderSegment(x: Double): Double {
        val s0 = 1.0 - shoulder
        if (shoulder == 0f || x <= s0) return x
        val u = (x - s0) / shoulder
        val h = s0 + (whiteClip - s0) * (1.0 - (1.0 - u) * (1.0 - u))
        val w = smoothstep(s0, 1.0, x)
        return x + (h - x) * w
    }

    private fun contrastSegment(x: Double): Double {
        if (contrast == 0f) return x
        val k = 1.0 + contrast
        return if (x < PIVOT) {
            PIVOT * (x / PIVOT).pow(k)
        } else {
            1.0 - (1.0 - PIVOT) * ((1.0 - x) / (1.0 - PIVOT)).pow(k)
        }
    }

    private companion object {
        const val TOE_END = 0.25
        const val PIVOT = 0.45
    }
}

/** Channel weights (on linear RGB) for monochrome conversion; the weights should sum to 1. */
data class MonoMix(val r: Float, val g: Float, val b: Float) {
    init {
        require(r.isFinite() && g.isFinite() && b.isFinite()) { "mono weights must be finite" }
        require(abs(r + g + b - 1f) < 1e-3f) { "mono weights must sum to 1" }
    }
}

/**
 * Complete definition of a [MasterLook].
 *
 * The LUT part (everything except halation and grain) is baked by [LutBaker] in this order:
 * warmth ([warmthMired], Bradford adaptation in linear light) → [monochrome] mix → [tone] curve per
 * encoded channel → [hueShifts] → [globalChroma] → [split] tone → L-preserving gamut fit.
 *
 * @property warmthMired positive warms (amber), negative cools (blue); applied inside the LUT.
 * @property defaultHalation look's own halation strength in [0, 1]; the user's value is added to it.
 * @property defaultGrain look's own grain strength in [0, 1]; the user's value is added to it.
 * @property grainSizePx value-noise lattice spacing in output pixels.
 * @property grainChroma colour-grain share in [0, 1]; monochrome looks use 0.
 */
data class LookSpec(
    val look: MasterLook,
    val tone: ToneCurve,
    val split: SplitTone,
    val globalChroma: Float,
    val hueShifts: List<HueShift>,
    val warmthMired: Float,
    val monochrome: MonoMix?,
    val defaultHalation: Float,
    val defaultGrain: Float,
    val grainSizePx: Float,
    val grainChroma: Float,
) {
    init {
        require(globalChroma in 0f..2f) { "globalChroma must be in [0, 2]" }
        require(warmthMired in -60f..60f) { "warmthMired must be in [-60, 60]" }
        require(defaultHalation in 0f..1f) { "defaultHalation must be in [0, 1]" }
        require(defaultGrain in 0f..1f) { "defaultGrain must be in [0, 1]" }
        require(grainSizePx in 0.5f..8f) { "grainSizePx must be in [0.5, 8]" }
        require(grainChroma in 0f..1f) { "grainChroma must be in [0, 1]" }
    }
}

/** Clamp to [0, 1]; NaN maps to 0 so LUT indexing stays in bounds. */
internal fun clamp01(x: Float): Float = if (x > 0f) (if (x < 1f) x else 1f) else 0f

/** Hermite smoothstep; edges must differ. */
internal fun smoothstep(edge0: Double, edge1: Double, x: Double): Double {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)
}

/** Float overload of [smoothstep]. */
internal fun smoothstep(edge0: Float, edge1: Float, x: Float): Float =
    smoothstep(edge0.toDouble(), edge1.toDouble(), x.toDouble()).toFloat()

/** Shortest angular distance between two hues, in [0, 180]. */
internal fun hueDistance(a: Float, b: Float): Float {
    val d = abs(a - b) % 360f
    return if (d > 180f) 360f - d else d
}
