package com.akane.akastudio.core.composer.crop

import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.composer.api.ComposerPhoto
import com.akane.akastudio.core.curator.api.FaceObservation
import com.akane.akastudio.core.curator.api.SaliencyMap
import com.akane.akastudio.core.ingestion.model.CaptureMetadata
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

internal object CropFixtures {

    /** A saliency map with the source aspect (long edge 64) holding Gaussian blobs at normalized centres. */
    fun saliency(width: Int, height: Int, vararg blobs: Triple<Double, Double, Double>): SaliencyMap {
        val (mw, mh) = if (width >= height) 64 to max(1, (64.0 * height / width).roundToInt())
        else max(1, (64.0 * width / height).roundToInt()) to 64
        val values = FloatArray(mw * mh)
        for (y in 0 until mh) for (x in 0 until mw) {
            var v = 0.0
            for ((bx, by, sigma) in blobs) {
                val dx = ((x + 0.5) / mw - bx) * mw
                val dy = ((y + 0.5) / mh - by) * mh
                v += exp(-(dx * dx + dy * dy) / (2 * sigma * sigma))
            }
            values[y * mw + x] = v.coerceAtMost(1.0).toFloat()
        }
        return SaliencyMap(mw, mh, values)
    }

    fun uniformSaliency(width: Int, height: Int): SaliencyMap {
        val map = saliency(width, height)
        return SaliencyMap(map.width, map.height, FloatArray(map.width * map.height) { 1f })
    }

    fun face(left: Float, top: Float, right: Float, bottom: Float) =
        FaceObservation(NormRect(left, top, right, bottom), 1f, 1f, 1f)

    fun photo(
        width: Int,
        height: Int,
        saliency: SaliencyMap = uniformSaliency(width, height),
        faces: List<FaceObservation> = emptyList(),
        tilt: Float = 0f,
    ) = ComposerPhoto(
        id = "p",
        imageKey = "key-p",
        widthPx = width,
        heightPx = height,
        saliency = saliency,
        faces = faces,
        palette = emptyList(),
        horizonTiltDegrees = tilt,
        metadata = CaptureMetadata.EMPTY,
    )

    /** The 15%-expanded face box in normalized coordinates, clamped to the image. */
    fun expanded(face: FaceObservation): NormRect {
        val b = face.box
        val hw = b.width * 1.15f / 2
        val hh = b.height * 1.15f / 2
        val cx = (b.left + b.right) / 2
        val cy = (b.top + b.bottom) / 2
        return NormRect((cx - hw).coerceAtLeast(0f), (cy - hh).coerceAtLeast(0f), (cx + hw).coerceAtMost(1f), (cy + hh).coerceAtMost(1f))
    }

    /**
     * True when the source rectangle [inner] (normalized) is visible inside [crop] once the renderer rotates the
     * source by [degrees] (clockwise on screen) about the crop centre: every corner, mapped through
     * `R = [[cos, -sin], [sin, cos]]` relative to the crop centre, lands inside the crop's half extents.
     */
    fun visibleInside(inner: NormRect, crop: NormRect, width: Int, height: Int, degrees: Float, tolPx: Double = 1e-3): Boolean {
        val t = Math.toRadians(degrees.toDouble())
        val c = cos(t)
        val s = sin(t)
        val ccx = (crop.left + crop.right) / 2.0 * width
        val ccy = (crop.top + crop.bottom) / 2.0 * height
        val hw = crop.width / 2.0 * width
        val hh = crop.height / 2.0 * height
        val xs = doubleArrayOf(inner.left * width.toDouble(), inner.right * width.toDouble())
        val ys = doubleArrayOf(inner.top * height.toDouble(), inner.bottom * height.toDouble())
        for (x in xs) for (y in ys) {
            val dx = x - ccx
            val dy = y - ccy
            val rx = c * dx - s * dy
            val ry = s * dx + c * dy
            if (abs(rx) > hw + tolPx || abs(ry) > hh + tolPx) return false
        }
        return true
    }

    /** True when every corner of [crop], rotated by [degrees] about its centre, lies inside the source image. */
    fun rotatedCropInsideImage(crop: NormRect, width: Int, height: Int, degrees: Float, tolPx: Double = 1e-3): Boolean {
        val t = Math.toRadians(degrees.toDouble())
        val c = cos(t)
        val s = sin(t)
        val ccx = (crop.left + crop.right) / 2.0 * width
        val ccy = (crop.top + crop.bottom) / 2.0 * height
        val hw = crop.width / 2.0 * width
        val hh = crop.height / 2.0 * height
        for (sx in listOf(-1.0, 1.0)) for (sy in listOf(-1.0, 1.0)) {
            // Output corner offset back to the source through the inverse rotation.
            val ox = sx * hw
            val oy = sy * hh
            val x = ccx + c * ox + s * oy
            val y = ccy - s * ox + c * oy
            if (x < -tolPx || x > width + tolPx || y < -tolPx || y > height + tolPx) return false
        }
        return crop.left >= 0f && crop.top >= 0f && crop.right <= 1f && crop.bottom <= 1f
    }
}
