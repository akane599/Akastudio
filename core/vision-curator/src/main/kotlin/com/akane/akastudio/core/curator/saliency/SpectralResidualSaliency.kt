package com.akane.akastudio.core.curator.saliency

import com.akane.akastudio.core.color.api.ImagePixels
import com.akane.akastudio.core.curator.api.FaceObservation
import com.akane.akastudio.core.curator.api.SaliencyMap
import com.akane.akastudio.core.curator.metrics.AreaResample
import com.akane.akastudio.core.curator.metrics.Luma
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin

/**
 * Spectral-residual saliency (Hou & Zhang, CVPR 2007) on the luma plane, plus a face prior.
 * Maps keep the image aspect and are normalized to [0,1].
 */
object SpectralResidualSaliency {
    private const val BLUR_SIGMA = 2.5
    private const val FACE_FEATHER = 0.15f

    /** Guards the log of a vanishing spectral amplitude. */
    private const val AMPLITUDE_EPSILON = 1e-9

    /** Luma range (0..255 scale) below which the downscaled plane counts as having no contrast. */
    private const val FLAT_RANGE = 1e-3f

    /**
     * Saliency of [pixels]: luma area-averaged to [longEdge] on the long side, padded (edge replicated) to a
     * power-of-two grid, `R = A - mean3x3(A)` on the log amplitude A, squared magnitude of the inverse FFT of
     * `exp(R + i*phase)` cropped back, Gaussian blur (sigma 2.5 cells), then min-max normalized. A map with no
     * contrast at all is all zeros.
     */
    fun compute(pixels: ImagePixels, longEdge: Int = 64): SaliencyMap {
        require(longEdge > 0) { "longEdge must be positive" }
        val (w, h) = AreaResample.fitLongEdge(pixels.width, pixels.height, longEdge)
        val luma = AreaResample.resize(Luma.of(pixels), pixels.width, pixels.height, w, h)
        // A flat plane has only a DC term; its "residual" would be pure rounding noise.
        if (luma.max() - luma.min() < FLAT_RANGE) return SaliencyMap(w, h, FloatArray(w * h))
        val pw = Fft2D.nextPowerOfTwo(w)
        val ph = Fft2D.nextPowerOfTwo(h)
        val re = DoubleArray(pw * ph)
        val im = DoubleArray(pw * ph)
        for (y in 0 until ph) {
            val sy = minOf(y, h - 1)
            for (x in 0 until pw) re[y * pw + x] = luma[sy * w + minOf(x, w - 1)].toDouble()
        }
        Fft2D.forward(re, im, pw, ph)
        val logAmplitude = DoubleArray(pw * ph) { ln(hypot(re[it], im[it]) + AMPLITUDE_EPSILON) }
        val phase = DoubleArray(pw * ph) { atan2(im[it], re[it]) }
        val residual = circularMean3x3(logAmplitude, pw, ph)
        for (i in residual.indices) {
            val magnitude = exp(logAmplitude[i] - residual[i])
            re[i] = magnitude * cos(phase[i])
            im[i] = magnitude * sin(phase[i])
        }
        Fft2D.inverse(re, im, pw, ph)
        val energy = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * pw + x
                energy[y * w + x] = (re[i] * re[i] + im[i] * im[i]).toFloat()
            }
        }
        return SaliencyMap(w, h, normalize(gaussianBlur(energy, w, h, BLUR_SIGMA)))
    }

    /**
     * `max(map, clamp(boost * mask))` where the mask is 1 inside each face box and falls off linearly to 0
     * across a margin of 15% of the box size on each axis.
     */
    fun withFaces(map: SaliencyMap, faces: List<FaceObservation>, boost: Float = 1f): SaliencyMap {
        if (faces.isEmpty() || boost <= 0f) return SaliencyMap(map.width, map.height, map.values.copyOf())
        val out = map.values.copyOf()
        for (face in faces) {
            val box = face.box
            val marginX = maxOf(FACE_FEATHER * box.width, 1e-6f)
            val marginY = maxOf(FACE_FEATHER * box.height, 1e-6f)
            for (y in 0 until map.height) {
                val v = (y + 0.5f) / map.height
                val dy = maxOf(box.top - v, 0f, v - box.bottom) / marginY
                for (x in 0 until map.width) {
                    val u = (x + 0.5f) / map.width
                    val dx = maxOf(box.left - u, 0f, u - box.right) / marginX
                    val mask = (1f - maxOf(dx, dy)).coerceIn(0f, 1f)
                    val boosted = (boost * mask).coerceIn(0f, 1f)
                    val i = y * map.width + x
                    if (boosted > out[i]) out[i] = boosted
                }
            }
        }
        return SaliencyMap(map.width, map.height, out)
    }

    /** 3x3 box mean with wrap-around: the spectrum is periodic. */
    private fun circularMean3x3(src: DoubleArray, w: Int, h: Int): DoubleArray = DoubleArray(w * h) { i ->
        val x = i % w
        val y = i / w
        var sum = 0.0
        for (dy in -1..1) {
            val row = ((y + dy + h) % h) * w
            for (dx in -1..1) sum += src[row + (x + dx + w) % w]
        }
        sum / 9.0
    }

    /** Separable Gaussian blur with edge replication; radius `ceil(3 * sigma)`. */
    private fun gaussianBlur(src: FloatArray, w: Int, h: Int, sigma: Double): FloatArray {
        val radius = ceil(3 * sigma).toInt()
        val kernel = DoubleArray(2 * radius + 1) { exp(-((it - radius) * (it - radius)) / (2 * sigma * sigma)) }
        val norm = kernel.sum()
        for (k in kernel.indices) kernel[k] /= norm
        val tmp = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var acc = 0.0
                for (k in kernel.indices) acc += kernel[k] * src[y * w + (x + k - radius).coerceIn(0, w - 1)]
                tmp[y * w + x] = acc.toFloat()
            }
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var acc = 0.0
                for (k in kernel.indices) acc += kernel[k] * tmp[(y + k - radius).coerceIn(0, h - 1) * w + x]
                out[y * w + x] = acc.toFloat()
            }
        }
        return out
    }

    private fun normalize(values: FloatArray): FloatArray {
        val min = values.min()
        val range = values.max() - min
        if (!(range > 1e-12f)) return FloatArray(values.size)
        return FloatArray(values.size) { ((values[it] - min) / range).coerceIn(0f, 1f) }
    }
}
