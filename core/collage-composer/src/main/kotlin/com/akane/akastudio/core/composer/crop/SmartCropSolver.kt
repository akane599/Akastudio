package com.akane.akastudio.core.composer.crop

import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.composer.api.ComposerPhoto
import com.akane.akastudio.core.curator.api.FaceObservation
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Saliency-maximizing, face-safe crop of one photo for a slot.
 *
 * The crop has the slot's aspect in source pixels, fits inside the source after the renderer's horizon rotation
 * (see [CropFrame] for the sign), and maximizes `∫S/∫S_total + 0.15·(area/maxArea)` over the saliency
 * summed-area table. Every face box, expanded by 15% and clamped to the image, stays inside the crop whenever any
 * searched crop allows it; otherwise the crop covering the most face area wins, then saliency. The search is
 * deterministic: 6 scales from the largest valid size down to 0.70×, a 24×24 grid of centres per scale plus the
 * image-centred and face-centred positions, then two 3×3 refinements at half the previous step.
 */
object SmartCropSolver {
    internal const val FACE_EXPANSION = 1.15
    private const val SCALE_STEPS = 6
    private const val MIN_SCALE = 0.70
    private const val GRID_STEPS = 24
    private const val SIZE_WEIGHT = 0.15
    private const val REFINE_PASSES = 2

    /**
     * Returns the normalized crop of [photo] for a slot of [slotAspect] (width/height) when the source is rotated by
     * [rotationDegrees] about the crop centre.
     *
     * @throws IllegalArgumentException when [slotAspect] is not positive and finite or the photo has no pixels.
     */
    fun solve(photo: ComposerPhoto, slotAspect: Float, rotationDegrees: Float): NormRect {
        require(slotAspect.isFinite() && slotAspect > 0f) { "slot aspect must be positive and finite, got $slotAspect" }
        require(photo.widthPx > 0 && photo.heightPx > 0) { "photo ${photo.id} has no pixels" }
        val frame = CropFrame(photo.widthPx.toDouble(), photo.heightPx.toDouble(), rotationDegrees)
        val search = CropSearch(
            frame = frame,
            aspect = slotAspect.toDouble(),
            saliency = SaliencyIntegral.of(photo.saliency, photo.widthPx, photo.heightPx),
            faces = faceBoxes(photo.faces, frame),
        )
        val best = search.run()
        return frame.toNorm(best.cx, best.cy, best.cw, best.ch)
    }

    /** Face boxes in source pixels, expanded by [FACE_EXPANSION] about their centre and clamped to the image. */
    internal fun faceBoxes(faces: List<FaceObservation>, frame: CropFrame): List<FaceBox> = faces.mapNotNull { face ->
        val box = face.box
        val coords = floatArrayOf(box.left, box.top, box.right, box.bottom)
        if (coords.any { !it.isFinite() }) return@mapNotNull null
        val l = min(box.left, box.right).toDouble().coerceIn(0.0, 1.0) * frame.width
        val r = max(box.left, box.right).toDouble().coerceIn(0.0, 1.0) * frame.width
        val t = min(box.top, box.bottom).toDouble().coerceIn(0.0, 1.0) * frame.height
        val b = max(box.top, box.bottom).toDouble().coerceIn(0.0, 1.0) * frame.height
        val halfW = (r - l) * FACE_EXPANSION / 2
        val halfH = (b - t) * FACE_EXPANSION / 2
        val cx = (l + r) / 2
        val cy = (t + b) / 2
        val left = (cx - halfW).coerceAtLeast(0.0)
        val right = (cx + halfW).coerceAtMost(frame.width)
        val top = (cy - halfH).coerceAtLeast(0.0)
        val bottom = (cy + halfH).coerceAtMost(frame.height)
        if (right - left <= 0.0 || bottom - top <= 0.0) null else FaceBox(left, top, right, bottom)
    }

    /** Axis-aligned face rectangle in source pixels. */
    internal data class FaceBox(val left: Double, val top: Double, val right: Double, val bottom: Double) {
        val centreX: Double get() = (left + right) / 2
        val centreY: Double get() = (top + bottom) / 2
        val width: Double get() = right - left
        val height: Double get() = bottom - top
    }

    /** A scored crop centred at ([cx], [cy]) with size [cw]×[ch]. */
    internal data class Candidate(
        val cx: Double, val cy: Double, val cw: Double, val ch: Double,
        val facesInside: Boolean,
        val faceCoverage: Double,
        val score: Double,
        val centreDistance: Double,
    )

    private class CropSearch(
        private val frame: CropFrame,
        private val aspect: Double,
        private val saliency: SaliencyIntegral,
        private val faces: List<FaceBox>,
    ) {
        private val total = saliency.total
        private val tolerance = 1e-6 * max(frame.width, frame.height)

        fun run(): Candidate {
            val maxHeight = frame.maxCropHeight(aspect)
            var best: Candidate? = null
            for (step in 0 until SCALE_STEPS) {
                val scale = 1.0 - step * (1.0 - MIN_SCALE) / (SCALE_STEPS - 1)
                val candidate = searchScale(maxHeight * scale, scale)
                if (best == null || isBetter(candidate, best)) best = candidate
            }
            return checkNotNull(best)
        }

        private fun searchScale(ch: Double, scale: Double): Candidate {
            val cw = ch * aspect
            val xs = frame.centreRangeX(cw, ch)
            val ys = frame.centreRangeY(cw, ch)
            var stepX = (xs.endInclusive - xs.start) / (GRID_STEPS - 1)
            var stepY = (ys.endInclusive - ys.start) / (GRID_STEPS - 1)
            var best = evaluate(xs.start, ys.start, cw, ch, scale)
            for (j in 0 until GRID_STEPS) {
                for (i in 0 until GRID_STEPS) {
                    val c = evaluate(xs.start + i * stepX, ys.start + j * stepY, cw, ch, scale)
                    if (isBetter(c, best)) best = c
                }
            }
            val centred = evaluate(frame.width / 2, frame.height / 2, cw, ch, scale)
            if (isBetter(centred, best)) best = centred
            if (faces.isNotEmpty()) {
                val c = faceCentred(cw, ch, scale)
                if (isBetter(c, best)) best = c
            }
            repeat(REFINE_PASSES) {
                stepX /= 2
                stepY /= 2
                val origin = best
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val c = evaluate(origin.cx + dx * stepX, origin.cy + dy * stepY, cw, ch, scale)
                        if (isBetter(c, best)) best = c
                    }
                }
            }
            return best
        }

        /** The position centring the union of the rotated face boxes, clamped to the valid region. */
        private fun faceCentred(cw: Double, ch: Double, scale: Double): Candidate {
            var minX = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY
            var maxY = Double.NEGATIVE_INFINITY
            for (face in faces) {
                val rx = frame.cos * face.centreX - frame.sin * face.centreY
                val ry = frame.sin * face.centreX + frame.cos * face.centreY
                val hx = halfX(face)
                val hy = halfY(face)
                minX = min(minX, rx - hx)
                maxX = max(maxX, rx + hx)
                minY = min(minY, ry - hy)
                maxY = max(maxY, ry + hy)
            }
            val rcx = (minX + maxX) / 2
            val rcy = (minY + maxY) / 2
            // Back to source coordinates through the inverse (transposed) rotation.
            val cx = frame.cos * rcx + frame.sin * rcy
            val cy = -frame.sin * rcx + frame.cos * rcy
            return evaluate(cx, cy, cw, ch, scale)
        }

        private fun evaluate(rawCx: Double, rawCy: Double, cw: Double, ch: Double, scale: Double): Candidate {
            val cx = rawCx.coerceIn(frame.centreRangeX(cw, ch))
            val cy = rawCy.coerceIn(frame.centreRangeY(cw, ch))
            val mass = saliency.sum(cx - cw / 2, cy - ch / 2, cx + cw / 2, cy + ch / 2)
            val salient = if (total > 0.0) mass / total else 0.0
            var inside = true
            var coverage = 0.0
            for (face in faces) {
                val dx = face.centreX - cx
                val dy = face.centreY - cy
                val rx = frame.cos * dx - frame.sin * dy
                val ry = frame.sin * dx + frame.cos * dy
                val hx = halfX(face)
                val hy = halfY(face)
                if (abs(rx) + hx > cw / 2 + tolerance || abs(ry) + hy > ch / 2 + tolerance) inside = false
                val ox = overlap(rx - hx, rx + hx, -cw / 2, cw / 2)
                val oy = overlap(ry - hy, ry + hy, -ch / 2, ch / 2)
                coverage += ox * oy * (face.width * face.height) / (4 * hx * hy)
            }
            val ddx = cx - frame.width / 2
            val ddy = cy - frame.height / 2
            return Candidate(
                cx, cy, cw, ch,
                facesInside = inside,
                faceCoverage = coverage,
                score = salient + SIZE_WEIGHT * scale * scale,
                centreDistance = ddx * ddx + ddy * ddy,
            )
        }

        /** Half extents of a face's bounding box after the renderer rotation. */
        private fun halfX(face: FaceBox) = (face.width * abs(frame.cos) + face.height * abs(frame.sin)) / 2
        private fun halfY(face: FaceBox) = (face.width * abs(frame.sin) + face.height * abs(frame.cos)) / 2

        private fun overlap(a0: Double, a1: Double, b0: Double, b1: Double) = max(0.0, min(a1, b1) - max(a0, b0))

        /**
         * Lexicographic preference: all faces inside; then (when faces are cut) more covered face area; then a higher
         * score; then a crop closer to the image centre. Exact ties keep the earlier candidate.
         */
        private fun isBetter(a: Candidate, b: Candidate): Boolean {
            if (a.facesInside != b.facesInside) return a.facesInside
            if (!a.facesInside) {
                val diff = a.faceCoverage - b.faceCoverage
                if (abs(diff) > EPSILON * max(1.0, abs(b.faceCoverage))) return diff > 0
            }
            val diff = a.score - b.score
            if (abs(diff) > EPSILON) return diff > 0
            return a.centreDistance < b.centreDistance - EPSILON
        }

        private companion object {
            const val EPSILON = 1e-9
        }
    }
}
