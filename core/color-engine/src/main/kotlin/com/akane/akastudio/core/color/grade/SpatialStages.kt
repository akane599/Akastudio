package com.akane.akastudio.core.color.grade

import com.akane.akastudio.core.color.api.OkLab
import com.akane.akastudio.core.color.look.clamp01
import com.akane.akastudio.core.color.math.ColorMath
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Stage 7, film halation: red-orange glow bled from bright areas.
 *
 * Bright pass `k = e/(0.25 + e)` with `e = max(0, Y − 0.75)` (a soft knee) times the pixel colour, blurred
 * by three separable Gaussians at σ, 2σ and 4σ (σ = 1.2% of the short edge, kernels truncated at 3σ, edges
 * clamped) mixed 0.5/0.3/0.2, tinted (1.0, 0.28, 0.10) and added scaled by `amount`.
 */
internal object Halation {
    const val THRESHOLD = 0.75f
    const val KNEE = 0.25f
    const val SIGMA_FRACTION = 0.012f
    const val STRENGTH = 0.6f
    val TINT = floatArrayOf(1.0f, 0.28f, 0.10f)
    val SCALES = floatArrayOf(1f, 2f, 4f)
    val WEIGHTS = floatArrayOf(0.5f, 0.3f, 0.2f)

    /** Base blur σ in pixels for a [width] × [height] image. */
    fun sigma(width: Int, height: Int): Float = SIGMA_FRACTION * min(width, height)

    /** Adds halation in place to interleaved RGBA [rgba]; [amount] already includes [STRENGTH]. */
    fun apply(rgba: FloatArray, width: Int, height: Int, amount: Float) {
        val count = width * height
        val bright = FloatArray(count * 3)
        var any = false
        for (i in 0 until count) {
            val r = max(0f, rgba[i * 4])
            val g = max(0f, rgba[i * 4 + 1])
            val b = max(0f, rgba[i * 4 + 2])
            val e = max(0f, ColorMath.relativeLuminance(r, g, b) - THRESHOLD)
            if (e > 0f) {
                val k = e / (KNEE + e)
                bright[i * 3] = k * r
                bright[i * 3 + 1] = k * g
                bright[i * 3 + 2] = k * b
                any = true
            }
        }
        if (!any) return
        val glow = FloatArray(count * 3)
        val base = sigma(width, height)
        for (s in SCALES.indices) {
            val blurred = GaussianBlur.blur(bright, width, height, base * SCALES[s])
            val w = WEIGHTS[s]
            for (j in glow.indices) glow[j] += w * blurred[j]
        }
        for (i in 0 until count) {
            for (c in 0 until 3) rgba[i * 4 + c] += amount * TINT[c] * glow[i * 3 + c]
        }
    }
}

/** Separable, edge-clamped Gaussian over interleaved 3-channel data, truncated at 3σ. */
internal object GaussianBlur {
    fun blur(src: FloatArray, width: Int, height: Int, sigma: Float): FloatArray {
        val kernel = kernel(sigma)
        val radius = kernel.size / 2
        val tmp = FloatArray(src.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var r = 0f
                var g = 0f
                var b = 0f
                for (k in -radius..radius) {
                    val sx = (x + k).coerceIn(0, width - 1)
                    val w = kernel[k + radius]
                    val p = (row + sx) * 3
                    r += w * src[p]
                    g += w * src[p + 1]
                    b += w * src[p + 2]
                }
                val o = (row + x) * 3
                tmp[o] = r
                tmp[o + 1] = g
                tmp[o + 2] = b
            }
        }
        val out = FloatArray(src.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var r = 0f
                var g = 0f
                var b = 0f
                for (k in -radius..radius) {
                    val sy = (y + k).coerceIn(0, height - 1)
                    val w = kernel[k + radius]
                    val p = (sy * width + x) * 3
                    r += w * tmp[p]
                    g += w * tmp[p + 1]
                    b += w * tmp[p + 2]
                }
                val o = (y * width + x) * 3
                out[o] = r
                out[o + 1] = g
                out[o + 2] = b
            }
        }
        return out
    }

    /** Normalised 1-D kernel of radius `max(1, ceil(3σ))`. */
    fun kernel(sigma: Float): FloatArray {
        require(sigma > 0f && sigma.isFinite()) { "sigma must be positive" }
        val radius = max(1, ceil(3.0 * sigma).toInt())
        val k = FloatArray(radius * 2 + 1)
        val denom = 2.0 * sigma * sigma
        var sum = 0.0
        for (i in -radius..radius) {
            val w = exp(-(i * i) / denom)
            k[i + radius] = w.toFloat()
            sum += w
        }
        for (i in k.indices) k[i] = (k[i] / sum).toFloat()
        return k
    }
}

/**
 * Stage 8, film grain. Deterministic value noise: each lattice node at spacing `grainSizePx` gets an
 * approximately Gaussian value (sum of four PCG-hash uniforms, centred and scaled to unit variance) per
 * channel and seed; pixels interpolate the lattice bilinearly. Applied in OKLab with a mid-tone envelope:
 * `L' = L + gL·amount·0.08·4L(1−L)` and `a/b' = a/b + ga/gb·amount·0.08·0.5·grainChroma·4L(1−L)`
 * (L clamped to [0, 1] inside the envelope).
 */
internal object FilmGrain {
    const val STRENGTH = 0.08f
    const val COLOR_RATIO = 0.5f
    private const val CHANNEL_L = 0
    private const val CHANNEL_A = 1
    private const val CHANNEL_B = 2
    private val UNIT_VARIANCE = sqrt(3f)
    private const val INV_2_24 = 1f / 16777216f

    /** PCG-RXS-M-XS 32-bit output hash; Int arithmetic wraps exactly like GLSL `uint`. */
    fun pcg(v: Int): Int {
        val state = v * 747796405 + -1403630843 // 2891336453u
        val word = ((state ushr ((state ushr 28) + 4)) xor state) * 277803737
        return (word ushr 22) xor word
    }

    /** Approximately standard-normal lattice value at node (ix, iy). */
    fun lattice(ix: Int, iy: Int, seed: Int, channel: Int): Float {
        val h0 = pcg(ix + pcg(iy + pcg(seed + pcg(channel))))
        val h1 = pcg(h0)
        val h2 = pcg(h1)
        val h3 = pcg(h2)
        val sum = uniform(h0) + uniform(h1) + uniform(h2) + uniform(h3)
        return (sum - 2f) * UNIT_VARIANCE
    }

    /** Bilinear value noise at pixel (x, y); lattice nodes sit on pixel centres every [size] pixels. */
    fun noise(x: Int, y: Int, size: Float, seed: Int, channel: Int): Float {
        val fx = (x + 0.5f) / size - 0.5f
        val fy = (y + 0.5f) / size - 0.5f
        val x0 = floor(fx)
        val y0 = floor(fy)
        val tx = fx - x0
        val ty = fy - y0
        val ix = x0.toInt()
        val iy = y0.toInt()
        val n00 = lattice(ix, iy, seed, channel)
        val n10 = lattice(ix + 1, iy, seed, channel)
        val n01 = lattice(ix, iy + 1, seed, channel)
        val n11 = lattice(ix + 1, iy + 1, seed, channel)
        val top = n00 + (n10 - n00) * tx
        val bottom = n01 + (n11 - n01) * tx
        return top + (bottom - top) * ty
    }

    /** Adds grain in place to interleaved RGBA [rgba]. */
    fun apply(
        rgba: FloatArray,
        width: Int,
        height: Int,
        amount: Float,
        sizePx: Float,
        grainChroma: Float,
        seed: Int,
    ) {
        val lumaScale = amount * STRENGTH
        val chromaScale = lumaScale * COLOR_RATIO * grainChroma
        val lin = FloatArray(3)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = (y * width + x) * 4
                val lab = ColorMath.linearSrgbToOkLab(rgba[p], rgba[p + 1], rgba[p + 2])
                val lc = clamp01(lab.l)
                val envelope = 4f * lc * (1f - lc)
                if (envelope == 0f) continue
                val l = lab.l + noise(x, y, sizePx, seed, CHANNEL_L) * lumaScale * envelope
                var a = lab.a
                var b = lab.b
                if (chromaScale > 0f) {
                    a += noise(x, y, sizePx, seed, CHANNEL_A) * chromaScale * envelope
                    b += noise(x, y, sizePx, seed, CHANNEL_B) * chromaScale * envelope
                }
                ColorMath.okLabToLinearSrgb(OkLab(l, a, b), lin)
                rgba[p] = lin[0]
                rgba[p + 1] = lin[1]
                rgba[p + 2] = lin[2]
            }
        }
    }

    private fun uniform(h: Int): Float = (h ushr 8) * INV_2_24
}
