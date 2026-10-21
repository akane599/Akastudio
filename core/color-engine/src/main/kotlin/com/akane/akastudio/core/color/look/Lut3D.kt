package com.akane.akastudio.core.color.look

/**
 * Cubic RGB lattice over encoded sRGB in [0, 1].
 *
 * [data] holds `size³·3` floats, node (r, g, b) at `((b·size + g)·size + r)·3`, red varying fastest
 * (the Hald CLUT and GL 3D-texture order). Node `i` along an axis sits at `i/(size − 1)`.
 */
class Lut3D(val size: Int, val data: FloatArray) {

    init {
        require(size >= 2) { "LUT size must be at least 2" }
        require(data.size == size * size * size * 3) { "LUT data must hold size^3 * 3 floats" }
    }

    /**
     * Tetrahedral interpolation at (r, g, b), each clamped to [0, 1] (NaN reads as 0). Writes 3 channels
     * into [out]. Exact on functions linear in the input, so an identity LUT reproduces its input.
     */
    fun sample(r: Float, g: Float, b: Float, out: FloatArray) {
        require(out.size >= 3) { "out must hold 3 channels" }
        val max = size - 1
        val fr = clamp01(r) * max
        val fg = clamp01(g) * max
        val fb = clamp01(b) * max
        val ir = fr.toInt().coerceAtMost(max - 1)
        val ig = fg.toInt().coerceAtMost(max - 1)
        val ib = fb.toInt().coerceAtMost(max - 1)
        val tr = fr - ir
        val tg = fg - ig
        val tb = fb - ib

        val sr = 3
        val sg = size * 3
        val sb = size * size * 3
        val base = ib * sb + ig * sg + ir * sr

        // Walk from the cell origin to the far corner along the axes in decreasing-fraction order.
        val first: Int
        val second: Int
        val f1: Float
        val f2: Float
        val f3: Float
        if (tr >= tg) {
            if (tg >= tb) { first = sr; second = sg; f1 = tr; f2 = tg; f3 = tb }
            else if (tr >= tb) { first = sr; second = sb; f1 = tr; f2 = tb; f3 = tg }
            else { first = sb; second = sr; f1 = tb; f2 = tr; f3 = tg }
        } else {
            if (tr >= tb) { first = sg; second = sr; f1 = tg; f2 = tr; f3 = tb }
            else if (tg >= tb) { first = sg; second = sb; f1 = tg; f2 = tb; f3 = tr }
            else { first = sb; second = sg; f1 = tb; f2 = tg; f3 = tr }
        }
        val v0 = base
        val v1 = base + first
        val v2 = v1 + second
        val v3 = base + sr + sg + sb
        val w0 = 1f - f1
        val w1 = f1 - f2
        val w2 = f2 - f3
        for (c in 0 until 3) {
            out[c] = w0 * data[v0 + c] + w1 * data[v1 + c] + w2 * data[v2 + c] + f3 * data[v3 + c]
        }
    }

    internal companion object {
        /** LUT whose output equals its input. */
        fun identity(size: Int): Lut3D {
            val data = FloatArray(size * size * size * 3)
            val max = (size - 1).toFloat()
            var i = 0
            for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
                data[i++] = r / max
                data[i++] = g / max
                data[i++] = b / max
            }
            return Lut3D(size, data)
        }
    }
}
