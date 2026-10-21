package com.akane.akastudio.core.color.grade

import kotlin.math.hypot

/**
 * Bradford chromatic adaptation from a slider-defined source white to D65, in linear sRGB.
 *
 * `M = sRGB_from_XYZ · Bradford⁻¹ · diag(ρD65/ρsrc) · Bradford · XYZ_from_sRGB`.
 *
 * Source white: CCT `srcK = 1e6 / (1e6/6504 − 60·temperature)` (±60 mired over the slider range). Its
 * chromaticity is D65 moved by the Planckian-locus displacement from 6504 K to `srcK` (Kim et al. 2002 cubic
 * spline, CIE 1960 uv) and then by `0.02·tint` along the locus normal toward green. Measuring the locus
 * displacement from D65, rather than placing the source on the locus itself, makes (0, 0) the exact
 * identity even though D65 sits slightly above the locus. The green-ward offset for positive tint
 * (ticket notation `Duv = −0.02·tint` with Duv measured toward magenta) makes the adaptation push
 * neutrals toward magenta. Positive temperature puts the source on the blue side and therefore warms.
 */
object WhiteBalance {

    private const val D65_CCT = 6504.0
    private const val MIRED_PER_UNIT = 60.0
    private const val DUV_PER_UNIT = 0.02

    /**
     * 3×3 row-major matrix, linear sRGB → linear sRGB. [temperature] and [tint] are clamped to [-1, 1]
     * (cool..warm, green..magenta). Non-finite input is rejected.
     */
    fun matrix(temperature: Float, tint: Float): FloatArray {
        require(temperature.isFinite() && tint.isFinite()) { "temperature and tint must be finite" }
        return adaptation(
            miredShift = temperature.coerceIn(-1f, 1f) * MIRED_PER_UNIT,
            greenDuv = tint.coerceIn(-1f, 1f) * DUV_PER_UNIT,
        )
    }

    /**
     * Same construction with a raw mired shift (positive warms) and a green-ward Duv offset; used by the
     * LUT baker for a look's `warmthMired`.
     */
    internal fun adaptation(miredShift: Double, greenDuv: Double): FloatArray {
        val srcMired = 1e6 / D65_CCT - miredShift
        val srcK = (1e6 / srcMired).coerceIn(PlanckianLocus.MIN_K, PlanckianLocus.MAX_K)

        val ref = PlanckianLocus.uv(D65_CCT)
        val src = PlanckianLocus.uv(srcK)
        val normal = PlanckianLocus.greenNormal(srcK)
        val d65 = Mat3.xyToUv(D65_X, D65_Y)
        val u = d65[0] + (src[0] - ref[0]) + normal[0] * greenDuv
        val v = d65[1] + (src[1] - ref[1]) + normal[1] * greenDuv
        val xy = Mat3.uvToXy(u, v)
        val srcXyz = doubleArrayOf(xy[0] / xy[1], 1.0, (1.0 - xy[0] - xy[1]) / xy[1])

        val rhoSrc = Mat3.apply(Mat3.BRADFORD, srcXyz)
        val rhoDst = Mat3.apply(Mat3.BRADFORD, D65_XYZ)
        val diag = Mat3.diag(rhoDst[0] / rhoSrc[0], rhoDst[1] / rhoSrc[1], rhoDst[2] / rhoSrc[2])
        val cone = Mat3.mul(Mat3.BRADFORD_INV, Mat3.mul(diag, Mat3.BRADFORD))
        val m = Mat3.mul(Mat3.XYZ_TO_SRGB, Mat3.mul(cone, Mat3.SRGB_TO_XYZ))
        return FloatArray(9) { m[it].toFloat() }
    }

    /** D65 as the XYZ of linear sRGB white, so white maps exactly to white at (0, 0). */
    private val D65_XYZ = Mat3.apply(Mat3.SRGB_TO_XYZ, doubleArrayOf(1.0, 1.0, 1.0))
    private val D65_X = D65_XYZ[0] / (D65_XYZ[0] + D65_XYZ[1] + D65_XYZ[2])
    private val D65_Y = D65_XYZ[1] / (D65_XYZ[0] + D65_XYZ[1] + D65_XYZ[2])
}

/** Kim et al. (2002) cubic-spline Planckian locus, valid 1667–25000 K. */
internal object PlanckianLocus {
    const val MIN_K = 1667.0
    const val MAX_K = 25000.0

    /** CIE 1931 xy at [kelvin] (clamped to the valid range). */
    fun xy(kelvin: Double): DoubleArray {
        val t = kelvin.coerceIn(MIN_K, MAX_K)
        val t2 = t * t
        val t3 = t2 * t
        val x = if (t <= 4000.0) {
            -0.2661239e9 / t3 - 0.2343589e6 / t2 + 0.8776956e3 / t + 0.179910
        } else {
            -3.0258469e9 / t3 + 2.1070379e6 / t2 + 0.2226347e3 / t + 0.240390
        }
        val x2 = x * x
        val x3 = x2 * x
        val y = when {
            t <= 2222.0 -> -1.1063814 * x3 - 1.34811020 * x2 + 2.18555832 * x - 0.20219683
            t <= 4000.0 -> -0.9549476 * x3 - 1.37418593 * x2 + 2.09137015 * x - 0.16748867
            else -> 3.0817580 * x3 - 5.87338670 * x2 + 3.75112997 * x - 0.37001483
        }
        return doubleArrayOf(x, y)
    }

    /** CIE 1960 uv at [kelvin]. */
    fun uv(kelvin: Double): DoubleArray {
        val p = xy(kelvin)
        return Mat3.xyToUv(p[0], p[1])
    }

    /** Unit normal to the locus in uv at [kelvin], pointing above the locus (toward green, +v). */
    fun greenNormal(kelvin: Double): DoubleArray {
        val mired = 1e6 / kelvin
        val a = uv((1e6 / (mired + 0.5)).coerceIn(MIN_K, MAX_K))
        val b = uv((1e6 / (mired - 0.5)).coerceIn(MIN_K, MAX_K))
        val du = b[0] - a[0]
        val dv = b[1] - a[1]
        val len = hypot(du, dv)
        var nu = -dv / len
        var nv = du / len
        if (nv < 0.0) {
            nu = -nu
            nv = -nv
        }
        return doubleArrayOf(nu, nv)
    }
}

/** Row-major 3×3 double matrices for the adaptation chain. */
internal object Mat3 {
    /** IEC 61966-2-1 linear sRGB → XYZ (D65). */
    val SRGB_TO_XYZ = doubleArrayOf(
        0.4124564, 0.3575761, 0.1804375,
        0.2126729, 0.7151522, 0.0721750,
        0.0193339, 0.1191920, 0.9503041,
    )
    val XYZ_TO_SRGB = inverse(SRGB_TO_XYZ)

    /** Bradford cone-response matrix. */
    val BRADFORD = doubleArrayOf(
        0.8951, 0.2664, -0.1614,
        -0.7502, 1.7135, 0.0367,
        0.0389, -0.0685, 1.0296,
    )
    val BRADFORD_INV = inverse(BRADFORD)

    fun mul(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(9) { i ->
        val r = i / 3
        val c = i % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }

    fun apply(m: DoubleArray, v: DoubleArray): DoubleArray = DoubleArray(3) { r ->
        m[r * 3] * v[0] + m[r * 3 + 1] * v[1] + m[r * 3 + 2] * v[2]
    }

    fun diag(a: Double, b: Double, c: Double): DoubleArray = doubleArrayOf(a, 0.0, 0.0, 0.0, b, 0.0, 0.0, 0.0, c)

    fun inverse(m: DoubleArray): DoubleArray {
        val c00 = m[4] * m[8] - m[5] * m[7]
        val c01 = m[5] * m[6] - m[3] * m[8]
        val c02 = m[3] * m[7] - m[4] * m[6]
        val det = m[0] * c00 + m[1] * c01 + m[2] * c02
        require(det != 0.0 && det.isFinite()) { "matrix is singular" }
        val inv = 1.0 / det
        return doubleArrayOf(
            c00 * inv, (m[2] * m[7] - m[1] * m[8]) * inv, (m[1] * m[5] - m[2] * m[4]) * inv,
            c01 * inv, (m[0] * m[8] - m[2] * m[6]) * inv, (m[2] * m[3] - m[0] * m[5]) * inv,
            c02 * inv, (m[1] * m[6] - m[0] * m[7]) * inv, (m[0] * m[4] - m[1] * m[3]) * inv,
        )
    }

    fun xyToUv(x: Double, y: Double): DoubleArray {
        val d = -2.0 * x + 12.0 * y + 3.0
        return doubleArrayOf(4.0 * x / d, 6.0 * y / d)
    }

    fun uvToXy(u: Double, v: Double): DoubleArray {
        val d = 2.0 * u - 8.0 * v + 4.0
        return doubleArrayOf(3.0 * u / d, 2.0 * v / d)
    }
}
