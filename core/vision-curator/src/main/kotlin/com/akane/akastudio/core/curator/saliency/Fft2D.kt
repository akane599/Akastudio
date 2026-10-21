package com.akane.akastudio.core.curator.saliency

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** In-place radix-2 complex FFT on row-major power-of-two grids. The inverse is scaled by `1/(w*h)`. */
internal object Fft2D {

    fun isPowerOfTwo(n: Int): Boolean = n > 0 && (n and (n - 1)) == 0

    /** Smallest power of two at or above [n] (n >= 1). */
    fun nextPowerOfTwo(n: Int): Int {
        require(n in 1..(1 shl 30))
        var p = 1
        while (p < n) p = p shl 1
        return p
    }

    fun forward(re: DoubleArray, im: DoubleArray, w: Int, h: Int) = transform(re, im, w, h, inverse = false)

    fun inverse(re: DoubleArray, im: DoubleArray, w: Int, h: Int) {
        transform(re, im, w, h, inverse = true)
        val scale = 1.0 / (w.toDouble() * h)
        for (i in re.indices) {
            re[i] *= scale
            im[i] *= scale
        }
    }

    private fun transform(re: DoubleArray, im: DoubleArray, w: Int, h: Int, inverse: Boolean) {
        require(isPowerOfTwo(w) && isPowerOfTwo(h)) { "grid $w x $h is not power-of-two" }
        require(re.size == w * h && im.size == w * h) { "buffers must be $w x $h" }
        val rowRe = DoubleArray(w)
        val rowIm = DoubleArray(w)
        for (y in 0 until h) {
            System.arraycopy(re, y * w, rowRe, 0, w)
            System.arraycopy(im, y * w, rowIm, 0, w)
            fft1d(rowRe, rowIm, inverse)
            System.arraycopy(rowRe, 0, re, y * w, w)
            System.arraycopy(rowIm, 0, im, y * w, w)
        }
        val colRe = DoubleArray(h)
        val colIm = DoubleArray(h)
        for (x in 0 until w) {
            for (y in 0 until h) {
                colRe[y] = re[y * w + x]
                colIm[y] = im[y * w + x]
            }
            fft1d(colRe, colIm, inverse)
            for (y in 0 until h) {
                re[y * w + x] = colRe[y]
                im[y * w + x] = colIm[y]
            }
        }
    }

    /** Iterative Cooley-Tukey; unscaled in both directions. */
    private fun fft1d(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        if (n <= 1) return
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        val sign = if (inverse) 1.0 else -1.0
        var len = 2
        while (len <= n) {
            val angle = sign * 2.0 * PI / len
            val stepRe = cos(angle)
            val stepIm = sin(angle)
            val half = len / 2
            var start = 0
            while (start < n) {
                var wRe = 1.0
                var wIm = 0.0
                for (k in 0 until half) {
                    val a = start + k
                    val b = a + half
                    val xr = re[b] * wRe - im[b] * wIm
                    val xi = re[b] * wIm + im[b] * wRe
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                    val nextRe = wRe * stepRe - wIm * stepIm
                    wIm = wRe * stepIm + wIm * stepRe
                    wRe = nextRe
                }
                start += len
            }
            len = len shl 1
        }
    }
}
