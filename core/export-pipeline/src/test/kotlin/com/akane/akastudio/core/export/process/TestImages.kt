package com.akane.akastudio.core.export.process

import com.akane.akastudio.core.color.api.HalfFloatImage

/** Builds a half image from a per-pixel RGBA generator. */
internal fun halfImage(width: Int, height: Int, pixel: (x: Int, y: Int) -> FloatArray): HalfFloatImage {
    val data = ShortArray(width * height * 4)
    for (y in 0 until height) for (x in 0 until width) {
        val v = pixel(x, y)
        val o = (y * width + x) * 4
        for (c in 0 until 4) data[o + c] = Half.fromFloat(v[c])
    }
    return HalfFloatImage(width, height, data)
}

internal fun gray(v: Float, a: Float = 1f) = floatArrayOf(v, v, v, a)

/** Channel [c] of pixel ([x], [y]) as float. */
internal fun HalfFloatImage.at(x: Int, y: Int, c: Int): Float = Half.toFloat(rgba[(y * width + x) * 4 + c])
