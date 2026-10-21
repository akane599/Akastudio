package com.akane.akastudio.core.curator.metrics

import com.akane.akastudio.core.color.api.ImagePixels
import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.curator.api.FaceObservation
import com.akane.akastudio.core.curator.api.SaliencyMap
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/** Synthetic gray planes and images shared by the curator metric tests. */
internal object TestImages {

    fun plane(w: Int, h: Int, f: (x: Int, y: Int) -> Float): FloatArray =
        FloatArray(w * h) { f(it % w, it / w) }

    fun checkerboard(w: Int, h: Int, cell: Int, low: Float = 0f, high: Float = 255f): FloatArray =
        plane(w, h) { x, y -> if ((x / cell + y / cell) % 2 == 0) low else high }

    /**
     * A sign-like page: four lines of dark 2-px "glyph" strokes on a 12x16 px grid, in a 64-row band centred
     * on a white frame with 1/8 side margins.
     */
    fun textLike(w: Int, h: Int): FloatArray = plane(w, h) { x, y ->
        if (y < h / 2 - 32 || y >= h / 2 + 32 || x < w / 8 || x >= w - w / 8) return@plane 235f
        val cx = x % 12
        val cy = y % 16
        val glyph = (x / 12 * 7 + y / 16 * 3) % 5
        val ink = when {
            cy in 2..13 && cx in 1..2 -> true
            cy in 2..3 && cx in 1..9 && glyph != 0 -> true
            cy in 7..8 && cx in 1..8 && glyph % 2 == 0 -> true
            cy in 12..13 && cx in 1..9 && glyph != 3 -> true
            cx in 8..9 && cy in 2..13 && glyph == 1 -> true
            else -> false
        }
        if (ink) 20f else 235f
    }

    /** Separable box blur of the given radius with edge replication. */
    fun boxBlur(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        val size = 2 * radius + 1
        val tmp = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var acc = 0f
            for (k in -radius..radius) acc += src[y * w + (x + k).coerceIn(0, w - 1)]
            tmp[y * w + x] = acc / size
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var acc = 0f
            for (k in -radius..radius) acc += tmp[(y + k).coerceIn(0, h - 1) * w + x]
            out[y * w + x] = acc / size
        }
        return out
    }

    /** Gray plane to opaque ARGB (R = G = B). */
    fun toPixels(gray: FloatArray, w: Int, h: Int): ImagePixels = ImagePixels(w, h, IntArray(w * h) {
        val v = gray[it].roundToInt().coerceIn(0, 255)
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    })

    /** A smooth, contrast-rich "scene" with no flat regions: sum of sinusoids and two soft blobs. */
    fun scene(w: Int, h: Int): FloatArray = plane(w, h) { x, y ->
        val u = x.toFloat() / w
        val v = y.toFloat() / h
        val waves = 40f * sin(6.1f * u + 1.3f * v) + 30f * sin(2.3f * v - 4.7f * u * v + 0.4f) + 25f * sin(9.7f * v + 2.0f)
        val blobA = 70f * exp(-((u - 0.3f) * (u - 0.3f) + (v - 0.4f) * (v - 0.4f)) / 0.02f)
        val blobB = -60f * exp(-((u - 0.7f) * (u - 0.7f) + (v - 0.7f) * (v - 0.7f)) / 0.03f)
        (120f + waves + blobA + blobB + 10f * u).coerceIn(0f, 255f)
    }

    /** Saliency map with a Gaussian blob (sigma in cells) centred at normalized (cx, cy). */
    fun blobMap(w: Int, h: Int, cx: Float, cy: Float, sigma: Float): SaliencyMap = SaliencyMap(w, h, plane(w, h) { x, y ->
        val dx = x + 0.5f - cx * w
        val dy = y + 0.5f - cy * h
        exp(-(dx * dx + dy * dy) / (2 * sigma * sigma))
    })

    /** Map whose cells are 1 for the first [count] row-major cells and 0 elsewhere. */
    fun fractionMap(w: Int, h: Int, fraction: Float): SaliencyMap {
        val count = (fraction * w * h).roundToInt()
        return SaliencyMap(w, h, FloatArray(w * h) { if (it < count) 1f else 0f })
    }

    fun face(
        box: NormRect,
        left: Float = 0.9f,
        right: Float = 0.9f,
        sharpness: Float = 0.8f,
    ): FaceObservation = FaceObservation(box, left, right, sharpness)

    /** Square face box of the given area centred in the frame. */
    fun centredBox(area: Float): NormRect {
        val side = kotlin.math.sqrt(area)
        return NormRect(0.5f - side / 2, 0.5f - side / 2, 0.5f + side / 2, 0.5f + side / 2)
    }
}
