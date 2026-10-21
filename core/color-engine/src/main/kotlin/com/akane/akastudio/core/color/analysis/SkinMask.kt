package com.akane.akastudio.core.color.analysis

import com.akane.akastudio.core.color.api.OkLab
import com.akane.akastudio.core.color.math.ColorMath
import kotlin.math.abs

/**
 * Smooth skin-likelihood mask in OKLCh.
 *
 * The mask is the product of three soft bands, `m = sL · sC · sH`, where each band is
 * `s(x; lo, hi, f) = smoothstep(lo − f, lo, x) · (1 − smoothstep(hi, hi + f, x))`:
 * lightness in [0.35, 0.88] (feather 0.05), chroma in [0.02, 0.14] (feather 0.015) and hue in
 * [32°, 68°] (feather 6°, measured on the hue circle).
 */
object SkinMask {
    private const val L_LO = 0.35f
    private const val L_HI = 0.88f
    private const val L_FEATHER = 0.05f
    private const val C_LO = 0.02f
    private const val C_HI = 0.14f
    private const val C_FEATHER = 0.015f
    private const val HUE_LO_DEG = 32f
    private const val HUE_HI_DEG = 68f
    private const val HUE_FEATHER_DEG = 6f
    private const val HUE_CENTRE_DEG = (HUE_LO_DEG + HUE_HI_DEG) / 2f
    private const val HUE_HALF_WIDTH_DEG = (HUE_HI_DEG - HUE_LO_DEG) / 2f

    /** Smooth skin-likelihood in [0,1] for one OKLab colour. */
    fun weight(lab: OkLab): Float {
        val lch = ColorMath.okLabToOkLch(lab)
        val sL = band(lch[0], L_LO, L_HI, L_FEATHER)
        if (sL == 0f) return 0f
        val sC = band(lch[1], C_LO, C_HI, C_FEATHER)
        if (sC == 0f) return 0f
        return sL * sC * hueBand(lch[2])
    }

    /** Smooth skin-likelihood in [0,1] for the OKLab colour ([l], [a], [b]). */
    fun weight(l: Float, a: Float, b: Float): Float = weight(OkLab(l, a, b))

    /**
     * The hue band is symmetric about its centre, so it is evaluated on the circular distance to the
     * centre: `1 − smoothstep(halfWidth, halfWidth + f, d)`, identical to `s(h; lo, hi, f)` on the circle.
     */
    private fun hueBand(hueDeg: Float): Float {
        val raw = abs(hueDeg - HUE_CENTRE_DEG) % 360f
        val distance = if (raw > 180f) 360f - raw else raw
        return 1f - smoothstep(HUE_HALF_WIDTH_DEG, HUE_HALF_WIDTH_DEG + HUE_FEATHER_DEG, distance)
    }

    private fun band(x: Float, lo: Float, hi: Float, feather: Float): Float =
        smoothstep(lo - feather, lo, x) * (1f - smoothstep(hi, hi + feather, x))

    internal fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
