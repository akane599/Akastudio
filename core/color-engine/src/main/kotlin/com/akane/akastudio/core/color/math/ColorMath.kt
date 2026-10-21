package com.akane.akastudio.core.color.math

import com.akane.akastudio.core.color.api.OkLab
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

/** Pure-JVM colour conversions. Arithmetic runs in double precision and results are narrowed to Float. */
object ColorMath {

    /** Linear value of each 8-bit sRGB code. Shared read-only within the module; never write to it. */
    internal val srgb8ToLinear = FloatArray(256) { srgbToLinear(it / 255f) }

    /** IEC 61966-2-1 piecewise decode, sign-preserving for out-of-range input. */
    fun srgbToLinear(c: Float): Float {
        val a = abs(c.toDouble())
        val lin = if (a <= 0.04045) a / 12.92 else ((a + 0.055) / 1.055).pow(2.4)
        return (sign(c) * lin).toFloat()
    }

    /** Inverse of [srgbToLinear], sign-preserving. */
    fun linearToSrgb(c: Float): Float {
        val a = abs(c.toDouble())
        val enc = if (a <= 0.0031308) a * 12.92 else 1.055 * a.pow(1.0 / 2.4) - 0.055
        return (sign(c) * enc).toFloat()
    }

    /** Björn Ottosson 2020 matrices (M1, cube root, M2). Input is linear sRGB. */
    fun linearSrgbToOkLab(r: Float, g: Float, b: Float): OkLab {
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return OkLab(
            l = (0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s).toFloat(),
            a = (1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s).toFloat(),
            b = (0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s).toFloat(),
        )
    }

    /** Writes linear sRGB (unclipped) into [out] (size >= 3) and returns it. */
    fun okLabToLinearSrgb(lab: OkLab, out: FloatArray = FloatArray(3)): FloatArray {
        require(out.size >= 3) { "out must hold 3 channels" }
        val lp = lab.l + 0.3963377774 * lab.a + 0.2158037573 * lab.b
        val mp = lab.l - 0.1055613458 * lab.a - 0.0638541728 * lab.b
        val sp = lab.l - 0.0894841775 * lab.a - 1.2914855480 * lab.b
        val l = lp * lp * lp
        val m = mp * mp * mp
        val s = sp * sp * sp
        out[0] = (4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s).toFloat()
        out[1] = (-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s).toFloat()
        out[2] = (-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s).toFloat()
        return out
    }

    /** 8-bit sRGB (alpha ignored) to OKLab through the cached linear LUT. */
    fun argbToOkLab(argb: Int): OkLab = linearSrgbToOkLab(
        srgb8ToLinear[(argb ushr 16) and 0xFF],
        srgb8ToLinear[(argb ushr 8) and 0xFF],
        srgb8ToLinear[argb and 0xFF],
    )

    /** Clips linear to [0, 1] (gamut clamp), encodes to 8-bit sRGB, alpha 0xFF. */
    fun okLabToArgb(lab: OkLab): Int {
        val lin = okLabToLinearSrgb(lab)
        val r = encode8(lin[0])
        val g = encode8(lin[1])
        val b = encode8(lin[2])
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Returns [L, C, hueDegrees in [0, 360)]. */
    fun okLabToOkLch(lab: OkLab): FloatArray {
        val c = hypot(lab.a.toDouble(), lab.b.toDouble())
        var h = Math.toDegrees(atan2(lab.b.toDouble(), lab.a.toDouble()))
        if (h < 0.0) h += 360.0
        val hue = h.toFloat()
        return floatArrayOf(lab.l, c.toFloat(), if (hue >= 360f) 0f else hue)
    }

    fun okLchToOkLab(l: Float, c: Float, hDeg: Float): OkLab {
        val h = Math.toRadians(hDeg.toDouble())
        return OkLab(l, (c * cos(h)).toFloat(), (c * sin(h)).toFloat())
    }

    /** Rec.709 relative luminance Y of linear sRGB. */
    fun relativeLuminance(rLin: Float, gLin: Float, bLin: Float): Float =
        0.2126f * rLin + 0.7152f * gLin + 0.0722f * bLin

    private fun encode8(linear: Float): Int =
        (linearToSrgb(linear.coerceIn(0f, 1f)) * 255f).roundToInt().coerceIn(0, 255)
}
