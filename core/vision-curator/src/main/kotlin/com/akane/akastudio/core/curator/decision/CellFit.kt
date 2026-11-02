package com.akane.akastudio.core.curator.decision

import com.akane.akastudio.core.curator.api.CropContract
import com.akane.akastudio.core.curator.api.FaceObservation
import com.akane.akastudio.core.curator.api.PhotoAnalysis
import com.akane.akastudio.core.curator.api.SaliencyMap
import java.lang.ref.WeakReference
import kotlin.math.max
import kotlin.math.min

/**
 * Crop-feasibility [LayoutFitter] that mirrors the collage composer's `SmartCropSolver` at rotation 0.
 *
 * The composer crops every [CellShape.Aspect] cell with the largest rectangle of that aspect inside the upright photo,
 * scaled by 0.70..1.0, and ranks "every expanded face inside" above everything else. A cell is **infeasible** for a
 * photo when either check fails:
 *
 * 1. **Faces (exact).** The union of all face boxes, each clamped to the image, expanded by [faceExpansion] about its
 *    centre and clamped again (the solver's rule, over all faces: the solver does not filter by prominence), must fit
 *    inside the largest crop of the cell aspect. When it fits, the solver's face-centred candidate contains it, so the
 *    solver keeps every face; when it does not, no smaller scale can, so the solver cuts a face. The host may render
 *    the cell at any aspect in [CellShape.Aspect.minWidthOverHeight]..[CellShape.Aspect.maxWidthOverHeight] (the
 *    studio's gutter slider moves QUAD_GRID from 0.554 to about 0.527); that range is the gutter allowance, so no
 *    margin is added for it. The photo aspect α is only known from the saliency map, whose long edge is exact and
 *    whose short edge was rounded to whole cells, so the true α lies in an interval of half a cell either side (about
 *    ±1% for 4:3). Feasibility needs `U_w ≤ c/α` and `U_h ≤ α/c` (each capped at 1), so it is checked at the two
 *    worst corners: largest α with `min·(1 - aspectMargin)`, and smallest α with `max·(1 + aspectMargin)`. The small
 *    [aspectMargin] (default 0.005) only absorbs the analysis decode's own rounding against the full-resolution
 *    size the composer sees.
 * 2. **Subject share (floor).** The best scale-1.0 crop of the nominal aspect that still contains the face union
 *    must keep at least [subjectShareFloor] (default 0.5, provisional; 0 disables) of the photo's saliency mass (the
 *    64 px map read as piecewise constant).
 *    This is an upper bound on what the solver keeps (a smaller scale never keeps more mass than some full-size
 *    window around it), so the floor only rejects cells where even the best full-size crop loses the subject. It
 *    cannot reject a full-height crop that keeps a salient ceiling: with the whole height kept, the share depends on
 *    width only.
 *
 * [CellShape.Uncropped] cells always fit.
 *
 * For a set of photos, `photos[0]` stays in slot 0 (the colour anchor) and every permutation of slots 1..m-1 is
 * tried in every catalogue layout of that count. Among fully feasible assignments the best is the one with the
 * largest minimum kept area (`min(c/α, α/c)` per cell, [matteKeptArea] for an uncropped cell), then the largest
 * mean kept area, then the largest mean subject share, then catalogue and permutation order. The geometric terms
 * lead because saliency is unreliable here (salient string lights). [LayoutFit.score] is the winner's mean subject
 * share, for diagnostics only.
 *
 * The check is at rotation 0. The composer straightens a detected horizon tilt (at most 5°) only as far as every
 * expanded face stays inside the crop (`SmartCropSolver.faceSafeRotation`), so faces this check accepts are kept.
 *
 * Per-(photo, cell) results are memoised in a bounded LRU of [cacheCapacity] entries keyed by candidate id and
 * cell shape; an entry only hits when the photo's saliency map is the same instance (held weakly) and its faces are
 * equal, so a reused id with new analysis data is re-evaluated. `cacheCapacity = 0` disables the memo. Thread-safe.
 */
class CellFit(
    private val aspectMargin: Float = DEFAULT_ASPECT_MARGIN,
    private val subjectShareFloor: Float = DEFAULT_SUBJECT_SHARE_FLOOR,
    private val faceExpansion: Double = FACE_EXPANSION,
    private val matteKeptArea: Float = DEFAULT_MATTE_KEPT_AREA,
    private val cacheCapacity: Int = DEFAULT_CACHE_CAPACITY,
) : LayoutFitter {
    init {
        require(aspectMargin.isFinite() && aspectMargin >= 0f && aspectMargin < 0.5f) {
            "aspectMargin must be in [0, 0.5): $aspectMargin"
        }
        require(subjectShareFloor.isFinite() && subjectShareFloor in 0f..1f) {
            "subjectShareFloor must be in [0, 1]: $subjectShareFloor"
        }
        require(faceExpansion.isFinite() && faceExpansion >= 1.0) { "faceExpansion must be >= 1: $faceExpansion" }
        require(matteKeptArea.isFinite() && matteKeptArea in 0f..1f) { "matteKeptArea must be in [0, 1]: $matteKeptArea" }
        require(cacheCapacity >= 0) { "cacheCapacity must be non-negative: $cacheCapacity" }
    }

    private val memo = Memo(cacheCapacity)

    override fun fit(
        photos: List<PhotoAnalysis>,
        catalog: LayoutCatalog,
    ): LayoutFit? {
        if (photos.isEmpty()) return null
        val layouts = catalog.forCount(photos.size)
        if (layouts.isEmpty()) return null
        val local = HashMap<Pair<Int, CellShape.Aspect>, CellEval>()

        fun eval(
            photoIndex: Int,
            cell: CellShape,
        ): CellEval =
            when (cell) {
                CellShape.Uncropped -> {
                    CellEval(feasible = true, keptArea = matteKeptArea, subjectShare = 1f)
                }

                is CellShape.Aspect -> {
                    local.getOrPut(photoIndex to cell) { cached(photos[photoIndex], cell) }
                }
            }

        val permutations = tailPermutations(photos.size)
        var best: Candidate? = null
        for (layout in layouts) {
            for (order in permutations) {
                // order[slot] = index into photos; order[0] == 0 always.
                var minArea = Float.POSITIVE_INFINITY
                var areaSum = 0f
                var shareSum = 0f
                var feasible = true
                for (slot in order.indices) {
                    val e = eval(order[slot], layout.cells[slot])
                    if (!e.feasible) {
                        feasible = false
                        break
                    }
                    minArea = min(minArea, e.keptArea)
                    areaSum += e.keptArea
                    shareSum += e.subjectShare
                }
                if (!feasible) continue
                val n = order.size
                val candidate = Candidate(layout.id, order, minArea, areaSum / n, shareSum / n)
                val current = best
                if (current == null || candidate.isBetterThan(current)) best = candidate
            }
        }
        return best?.let { c -> LayoutFit(c.layoutId, c.order.map { photos[it].candidateId }, c.meanShare) }
    }

    /** Feasibility, kept area and subject share of [photo] in [cell]; memoised. */
    private fun cached(
        photo: PhotoAnalysis,
        cell: CellShape.Aspect,
    ): CellEval = memo.get(photo, cell) ?: evaluate(photo.saliency, photo.faces, cell).also { memo.put(photo, cell, it) }

    /** [evaluate] for a cell with a single aspect. */
    internal fun evaluate(
        saliency: SaliencyMap,
        faces: List<FaceObservation>,
        aspect: Double,
    ): CellEval = evaluate(saliency, faces, CellShape.Aspect(aspect.toFloat()))

    internal fun evaluate(
        saliency: SaliencyMap,
        faces: List<FaceObservation>,
        cell: CellShape.Aspect,
    ): CellEval {
        val imageAspect = saliency.width.toDouble() / saliency.height
        val union = faceUnion(faces, faceExpansion)
        val aspect = cell.widthOverHeight.toDouble()
        val keptArea = min(aspect / imageAspect, imageAspect / aspect).toFloat()
        val margin = aspectMargin.toDouble()
        val (aspectLo, aspectHi) = imageAspectBounds(saliency)
        val facesOk =
            union == null || (
                unionFits(union, aspectHi, cell.minWidthOverHeight * (1 - margin)) &&
                    unionFits(union, aspectLo, cell.maxWidthOverHeight * (1 + margin))
            )
        if (!facesOk) return CellEval(feasible = false, keptArea = keptArea, subjectShare = 0f)
        val share = subjectShare(saliency, imageAspect, aspect, union)
        return CellEval(feasible = share + SHARE_EPSILON >= subjectShareFloor, keptArea = keptArea, subjectShare = share)
    }

    /** One (photo, cell) verdict. */
    internal data class CellEval(
        val feasible: Boolean,
        val keptArea: Float,
        val subjectShare: Float,
    )

    private class Candidate(
        val layoutId: String,
        val order: IntArray,
        val minArea: Float,
        val meanArea: Float,
        val meanShare: Float,
    ) {
        /** Strictly better; exact ties keep the earlier (catalogue, then permutation order) candidate. */
        fun isBetterThan(other: Candidate): Boolean {
            if (minArea > other.minArea + RANK_EPSILON) return true
            if (minArea < other.minArea - RANK_EPSILON) return false
            if (meanArea > other.meanArea + RANK_EPSILON) return true
            if (meanArea < other.meanArea - RANK_EPSILON) return false
            return meanShare > other.meanShare + RANK_EPSILON
        }
    }

    /** Bounded LRU of per-(photo, aspect) results; see the class KDoc for the hit rule. */
    private class Memo(
        private val capacity: Int,
    ) {
        private data class Key(
            val candidateId: String,
            val cell: CellShape.Aspect,
        )

        private class Entry(
            val saliency: WeakReference<SaliencyMap>,
            val faces: List<FaceObservation>,
            val eval: CellEval,
        )

        private val entries =
            object : LinkedHashMap<Key, Entry>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Entry>?): Boolean = size > capacity
            }

        fun get(
            photo: PhotoAnalysis,
            cell: CellShape.Aspect,
        ): CellEval? {
            if (capacity == 0) return null
            val entry = synchronized(entries) { entries[Key(photo.candidateId, cell)] } ?: return null
            return if (entry.saliency.get() === photo.saliency && entry.faces == photo.faces) entry.eval else null
        }

        fun put(
            photo: PhotoAnalysis,
            cell: CellShape.Aspect,
            eval: CellEval,
        ) {
            if (capacity == 0) return
            val entry = Entry(WeakReference(photo.saliency), photo.faces, eval)
            synchronized(entries) { entries[Key(photo.candidateId, cell)] = entry }
        }
    }

    /** Normalized union of the expanded face boxes, `[left, top, right, bottom]`. */
    internal class Union(
        val left: Double,
        val top: Double,
        val right: Double,
        val bottom: Double,
    ) {
        val width: Double get() = right - left
        val height: Double get() = bottom - top
    }

    companion object {
        /** [CropContract.FACE_EXPANSION], the composer's `SmartCropSolver` rule; kept here for callers of [facesFit]. */
        const val FACE_EXPANSION: Double = CropContract.FACE_EXPANSION

        /** Analysis-decode rounding only; the gutter range is explicit in the cells and the map rounding is exact. */
        const val DEFAULT_ASPECT_MARGIN: Float = 0.005f

        /**
         * Provisional: calibrated on one real set (the 2026-11-02 night-out trace); the real-set acceptance run decides
         * whether it is too strict. 0 disables the floor.
         */
        const val DEFAULT_SUBJECT_SHARE_FLOOR: Float = 0.5f

        /** Ranking value of an uncropped (matte) cell: a cropped cell keeping more of the photo than this wins. */
        const val DEFAULT_MATTE_KEPT_AREA: Float = 0.5f

        /** 24 pool photos × about 20 distinct catalogue aspects, with headroom. */
        const val DEFAULT_CACHE_CAPACITY: Int = 1024

        private const val RANK_EPSILON = 1e-6f
        private const val SHARE_EPSILON = 1e-6f

        /** The solver's containment tolerance, `1e-6 · max(width, height)` pixels. */
        private const val FIT_TOLERANCE = 1e-6

        /**
         * True when every face in [faces], expanded by [expansion] the way `SmartCropSolver` does, fits inside the
         * largest crop of [cellAspect] (width/height) of an upright image of [imageAspect] (width/height), with no
         * margin. With no usable face, always true. At rotation 0 this equals "the solver keeps every face".
         */
        fun facesFit(
            faces: List<FaceObservation>,
            imageAspect: Double,
            cellAspect: Double,
            expansion: Double = FACE_EXPANSION,
        ): Boolean {
            require(imageAspect.isFinite() && imageAspect > 0.0) { "imageAspect must be positive: $imageAspect" }
            require(cellAspect.isFinite() && cellAspect > 0.0) { "cellAspect must be positive: $cellAspect" }
            val union = faceUnion(faces, expansion) ?: return true
            return unionFits(union, imageAspect, cellAspect)
        }

        /** Mirrors `SmartCropSolver.faceBoxes` in normalized coordinates; null when no face survives. */
        internal fun faceUnion(
            faces: List<FaceObservation>,
            expansion: Double,
        ): Union? {
            var l = Double.POSITIVE_INFINITY
            var t = Double.POSITIVE_INFINITY
            var r = Double.NEGATIVE_INFINITY
            var b = Double.NEGATIVE_INFINITY
            var any = false
            for (face in faces) {
                val box = face.box
                if (!box.left.isFinite() || !box.top.isFinite() || !box.right.isFinite() || !box.bottom.isFinite()) continue
                val x0 = min(box.left, box.right).toDouble().coerceIn(0.0, 1.0)
                val x1 = max(box.left, box.right).toDouble().coerceIn(0.0, 1.0)
                val y0 = min(box.top, box.bottom).toDouble().coerceIn(0.0, 1.0)
                val y1 = max(box.top, box.bottom).toDouble().coerceIn(0.0, 1.0)
                val halfW = (x1 - x0) * expansion / 2
                val halfH = (y1 - y0) * expansion / 2
                val cx = (x0 + x1) / 2
                val cy = (y0 + y1) / 2
                val left = (cx - halfW).coerceAtLeast(0.0)
                val right = (cx + halfW).coerceAtMost(1.0)
                val top = (cy - halfH).coerceAtLeast(0.0)
                val bottom = (cy + halfH).coerceAtMost(1.0)
                if (right - left <= 0.0 || bottom - top <= 0.0) continue
                any = true
                l = min(l, left)
                t = min(t, top)
                r = max(r, right)
                b = max(b, bottom)
            }
            return if (any) Union(l, t, r, b) else null
        }

        /**
         * Interval holding the true width/height of the photo behind [map]: the map's long edge is exact (64) and its
         * short edge was rounded to whole cells (`Math.round`), so the true short edge is within half a cell. A square
         * map can come from either orientation.
         */
        internal fun imageAspectBounds(map: SaliencyMap): Pair<Double, Double> {
            val w = map.width.toDouble()
            val h = map.height.toDouble()
            return when {
                w > h -> w / (h + 0.5) to w / max(h - 0.5, 0.5)
                h > w -> max(w - 0.5, 0.5) / h to (w + 0.5) / h
                else -> max(w - 0.5, 0.5) / w to w / max(w - 0.5, 0.5)
            }
        }

        /** Normalized `(width, height)` of the largest crop of [cellAspect] inside an image of [imageAspect]. */
        private fun largestCrop(
            imageAspect: Double,
            cellAspect: Double,
        ): Pair<Double, Double> = if (cellAspect <= imageAspect) (cellAspect / imageAspect) to 1.0 else 1.0 to (imageAspect / cellAspect)

        private fun unionFits(
            union: Union,
            imageAspect: Double,
            cellAspect: Double,
        ): Boolean {
            val (w, h) = largestCrop(imageAspect, cellAspect)
            // The solver's pixel tolerance, 1e-6·max(W, H), expressed per normalized axis.
            val tolX = FIT_TOLERANCE * max(1.0, 1.0 / imageAspect)
            val tolY = FIT_TOLERANCE * max(1.0, imageAspect)
            return union.width <= w + tolX && union.height <= h + tolY
        }

        /**
         * Largest saliency fraction kept by a scale-1.0 crop of [cellAspect] whose window contains [union] (any
         * window when null). The crop spans one image axis fully and slides along the other; the kept mass is
         * piecewise linear in the position, so its maximum sits at a window edge on a map-cell boundary or at an
         * end of the allowed range. A map with no mass keeps everything (1).
         */
        internal fun subjectShare(
            saliency: SaliencyMap,
            imageAspect: Double,
            cellAspect: Double,
            union: Union?,
        ): Float {
            val (w, h) = largestCrop(imageAspect, cellAspect)
            val horizontal = w < 1.0
            val cells = if (horizontal) saliency.width else saliency.height
            val mass = DoubleArray(cells)
            var total = 0.0
            for (y in 0 until saliency.height) {
                for (x in 0 until saliency.width) {
                    val v = saliency.values[y * saliency.width + x]
                    if (!v.isFinite() || v <= 0f) continue
                    mass[if (horizontal) x else y] += v.toDouble()
                    total += v
                }
            }
            if (total <= 0.0) return 1f
            val length = if (horizontal) w else h
            if (length >= 1.0) return 1f
            val prefix = DoubleArray(cells + 1)
            for (i in 0 until cells) prefix[i + 1] = prefix[i] + mass[i]

            fun cumulative(p: Double): Double {
                val g = (p * cells).coerceIn(0.0, cells.toDouble())
                val i = min(g.toInt(), cells - 1)
                return prefix[i] + mass[i] * (g - i)
            }
            var lo = 0.0
            var hi = 1.0 - length
            if (union != null) {
                val uLo = if (horizontal) union.left else union.top
                val uHi = if (horizontal) union.right else union.bottom
                lo = max(lo, uHi - length)
                hi = min(hi, uLo)
                if (lo > hi) {
                    val mid = (lo + hi) / 2
                    lo = mid
                    hi = mid
                }
            }
            var bestMass = 0.0

            fun consider(p: Double) {
                if (p < lo || p > hi) return
                bestMass = max(bestMass, cumulative(p + length) - cumulative(p))
            }
            consider(lo)
            consider(hi)
            for (k in 0..cells) {
                val edge = k.toDouble() / cells
                consider(edge)
                consider(edge - length)
            }
            return (bestMass / total).coerceIn(0.0, 1.0).toFloat()
        }

        /** Every slot order of `0 until m` with index 0 pinned to slot 0, in lexicographic order. */
        internal fun tailPermutations(m: Int): List<IntArray> {
            val out = ArrayList<IntArray>()
            val current = IntArray(m)
            val used = BooleanArray(m)
            used[0] = true

            fun place(slot: Int) {
                if (slot == m) {
                    out += current.copyOf()
                    return
                }
                for (i in 1 until m) {
                    if (used[i]) continue
                    used[i] = true
                    current[slot] = i
                    place(slot + 1)
                    used[i] = false
                }
            }
            place(1)
            return out
        }
    }
}
