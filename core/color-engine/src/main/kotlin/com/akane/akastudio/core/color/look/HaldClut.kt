package com.akane.akastudio.core.color.look

import com.akane.akastudio.core.color.api.ImagePixels
import kotlin.math.cbrt
import kotlin.math.roundToInt

/**
 * Hald CLUT image codec. A level-`L` Hald image is `L³ × L³` pixels holding an `L²`-point cube, pixel index
 * `r + g·L² + b·L⁴` in row-major order (red fastest), each channel 8-bit sRGB-encoded.
 */
object HaldClut {
    private const val MIN_LEVEL = 2
    private const val MAX_LEVEL = 16

    /** Resamples [lut] (tetrahedral) onto a level-[level] cube and packs it as opaque ARGB. */
    fun encode(lut: Lut3D, level: Int = 8): ImagePixels {
        require(level in MIN_LEVEL..MAX_LEVEL) { "Hald level must be in [$MIN_LEVEL, $MAX_LEVEL]" }
        val n = level * level
        val side = n * level
        val max = (n - 1).toFloat()
        val argb = IntArray(side * side)
        val px = FloatArray(3)
        var i = 0
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            lut.sample(r / max, g / max, b / max, px)
            argb[i++] = (0xFF shl 24) or (quantize(px[0]) shl 16) or (quantize(px[1]) shl 8) or quantize(px[2])
        }
        return ImagePixels(side, side, argb)
    }

    /** Reads a Hald image back into a `L²`-point [Lut3D]; alpha is ignored. */
    fun decode(image: ImagePixels): Lut3D {
        require(image.width == image.height) { "Hald image must be square" }
        val level = cbrt(image.width.toDouble()).roundToInt()
        require(level in MIN_LEVEL..MAX_LEVEL && level * level * level == image.width) {
            "Hald image side must be level^3 for a level in [$MIN_LEVEL, $MAX_LEVEL]"
        }
        val n = level * level
        val data = FloatArray(n * n * n * 3)
        for (i in 0 until n * n * n) {
            val p = image.argb[i]
            data[i * 3] = ((p ushr 16) and 0xFF) / 255f
            data[i * 3 + 1] = ((p ushr 8) and 0xFF) / 255f
            data[i * 3 + 2] = (p and 0xFF) / 255f
        }
        return Lut3D(n, data)
    }

    private fun quantize(v: Float): Int = (clamp01(v) * 255f).roundToInt().coerceIn(0, 255)
}
