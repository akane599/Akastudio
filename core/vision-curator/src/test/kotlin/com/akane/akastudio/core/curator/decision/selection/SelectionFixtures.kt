package com.akane.akastudio.core.curator.decision.selection

import com.akane.akastudio.core.color.api.LabStats
import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.color.api.OkLab
import com.akane.akastudio.core.color.api.PaletteSwatch
import com.akane.akastudio.core.color.api.SceneType
import com.akane.akastudio.core.curator.api.FaceObservation
import com.akane.akastudio.core.curator.api.PhotoAnalysis
import com.akane.akastudio.core.curator.api.RejectReason
import com.akane.akastudio.core.curator.api.SaliencyMap
import com.akane.akastudio.core.curator.api.ShotScale
import com.akane.akastudio.core.curator.api.TechnicalScore
import kotlin.math.sqrt

/**
 * Builders for selection regression tests, with numbers drawn from the 2026-11-02 night-out device trace
 * (`docs/stabilization/evidence/curation-20261102/`, 28 photos, measured on an SM-S938B).
 *
 * Measured distributions the fixtures stay inside:
 *
 * | relation | cosine | min(pHash, dHash) distance | time apart |
 * |---|---|---|---|
 * | reframed table shots, one moment | 0.40-0.82, median 0.70 | 16-34 | 4-28 s |
 * | seated series, one moment | 0.42-0.90, median 0.59 | 16-34 | <= 28 s span |
 * | different moments | <= 0.58, median 0.24 | >= 20 | minutes to hours |
 * | one capture, HEIC and JPG | 0.9995 | 0 / 0 | 0 s |
 *
 * Q: table 0.68-0.76, series 0.69-0.73, selfies and portraits 0.53-0.66, blurry 0.49-0.50, screenshot 0.63.
 * Series face boxes cover 0.019-0.028 of the frame, and one series frame has no detected face.
 *
 * Fixtures avoid the 0.40-0.50 same-moment band and the 0.35-0.58 cross-moment band, where the real embedder cannot
 * separate "same moment, reframed" from "different moment" without capture times (the plan review's known limit). The
 * tests protect behaviour in the clear regions, not the provisional ramp ends.
 */
internal object SelectionFixtures {
    /** 2026-10-17 00:00 in an arbitrary fixed zone; only differences matter. */
    const val DAY0: Long = 1_792_195_200_000L
    const val SECOND: Long = 1_000L
    const val MINUTE: Long = 60 * SECOND
    const val HOUR: Long = 60 * MINUTE
    const val DAY: Long = 24 * HOUR

    fun at(
        h: Int,
        m: Int,
        s: Int,
        day: Int = 0,
    ): Long = DAY0 + day * DAY + ((h * 60L + m) * 60L + s) * SECOND

    // ---- embeddings with exact cosines ----

    /**
     * Unit vectors in an orthonormal basis, built so pairwise cosines are exact. [fresh] takes a new basis axis;
     * [blend] returns `c·base + sqrt(1 - c²)·fresh()`, whose cosine with the unit vector [base] is exactly `c` and
     * whose cosine with any other vector `v` is `c·cos(base, v)` (the new axis is orthogonal to everything so far).
     *
     * Typical use: a venue vector, moments blended from it at `a` (moment-to-moment cosine `a²`), frames blended from
     * their moment at `b_i` (same-moment cosine `b_i·b_j`, cross-moment cosine `b_i·b_j·a²`).
     */
    class EmbeddingSpace(
        private val dims: Int = 128,
    ) {
        private var nextAxis = 0

        fun fresh(): DoubleArray {
            check(nextAxis < dims) { "embedding space exhausted ($dims axes)" }
            return DoubleArray(dims).also { it[nextAxis++] = 1.0 }
        }

        fun blend(
            base: DoubleArray,
            cosine: Double,
        ): DoubleArray {
            require(cosine in -1.0..1.0) { "cosine out of range: $cosine" }
            val axis = fresh()
            val s = sqrt(1.0 - cosine * cosine)
            return DoubleArray(dims) { cosine * base[it] + s * axis[it] }
        }

        /** The linear combination `Σ weight·vector` (vectors from this space; pass orthonormal axes for exact cosines). */
        fun combine(vararg terms: Pair<DoubleArray, Double>): DoubleArray =
            DoubleArray(dims) { k -> terms.sumOf { (v, w) -> w * v[k] } }
    }

    fun toEmbedding(v: DoubleArray): FloatArray {
        val n = sqrt(v.sumOf { it * it })
        return FloatArray(v.size) { (v[it] / n).toFloat() }
    }

    fun cosine(
        a: FloatArray,
        b: FloatArray,
    ): Double {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i]
            na += a[i].toDouble() * a[i]
            nb += b[i].toDouble() * b[i]
        }
        return dot / sqrt(na * nb)
    }

    // ---- hashes with exact Hamming distances ----

    /** Row [row] (0..31) of the 32x32 Sylvester-Hadamard matrix as 32 bits: two distinct rows differ in exactly 16. */
    private fun hadamard32(row: Int): Long {
        require(row in 0 until 32) { "hadamard row out of range: $row" }
        var bits = 0L
        for (k in 0 until 32) if (Integer.bitCount(row and k) % 2 == 1) bits = bits or (1L shl k)
        return bits
    }

    /**
     * 64-bit hashes with exact distances. High half: the moment's Hadamard row, with a frame-specific block of
     * `flip` bits flipped (blocks are disjoint within a moment). Low half: a Hadamard row unique to the photo.
     *
     * - Two frames of one moment: `16 + flip_i + flip_j` (e.g. 20-26).
     * - Two photos of different moments: at least `32 - flip_i - flip_j` (>= 26 with flips <= 3).
     * - [copy] reuses a hash unchanged (distance 0, a format copy).
     *
     * dHash is the pHash rotated by 23 bits, which preserves every distance.
     */
    class HashSpace {
        private var nextMomentRow = 1
        private var nextPhotoRow = 1
        private val nextFlipBit = HashMap<Int, Int>()

        fun moment(): Int = nextMomentRow++.also { require(it < 32) { "too many moments" } }

        fun hash(
            moment: Int,
            flip: Int,
        ): Long {
            val photoRow = nextPhotoRow++
            require(photoRow < 32) { "too many photos in one hash space" }
            val from = nextFlipBit[moment] ?: 0
            require(from + flip <= 32) { "moment $moment has no room for $flip more flip bits" }
            nextFlipBit[moment] = from + flip
            var high = hadamard32(moment)
            for (b in from until from + flip) high = high xor (1L shl b)
            return (high shl 32) or hadamard32(photoRow)
        }

        companion object {
            fun dHashOf(pHash: Long): Long = java.lang.Long.rotateLeft(pHash, 23)
        }
    }

    // ---- palettes (OKLab, five swatches), shaped like the trace's ----

    /** Table under cool string lights: warm mid tones, a blue accent. CCT about 7500 K. */
    fun tablePalette(jitter: Float = 0f): List<PaletteSwatch> =
        listOf(
            PaletteSwatch(OkLab(0.55f, 0.04f, 0.06f + jitter), 0.29f),
            PaletteSwatch(OkLab(0.38f, 0.05f, 0.03f), 0.24f),
            PaletteSwatch(OkLab(0.70f, 0.04f + jitter, 0.00f), 0.20f),
            PaletteSwatch(OkLab(0.20f, 0.02f, 0.00f), 0.16f),
            PaletteSwatch(OkLab(0.51f, 0.05f, -0.16f), 0.11f),
        )

    /** People at the bar: dark room, skin and blue light. CCT 5800-7700 K. */
    fun peoplePalette(jitter: Float = 0f): List<PaletteSwatch> =
        listOf(
            PaletteSwatch(OkLab(0.28f, 0.02f, -0.05f), 0.31f),
            PaletteSwatch(OkLab(0.55f, 0.05f + jitter, 0.02f), 0.29f),
            PaletteSwatch(OkLab(0.48f, -0.01f, -0.20f), 0.15f),
            PaletteSwatch(OkLab(0.85f, 0.01f, 0.02f + jitter), 0.14f),
            PaletteSwatch(OkLab(0.63f, 0.01f, -0.15f), 0.11f),
        )

    /** Seated series: dark, neutral, a blue accent. CCT 8400-10100 K. */
    fun seriesPalette(jitter: Float = 0f): List<PaletteSwatch> =
        listOf(
            PaletteSwatch(OkLab(0.20f, 0.01f, -0.01f), 0.31f),
            PaletteSwatch(OkLab(0.37f, 0.02f, -0.02f + jitter), 0.24f),
            PaletteSwatch(OkLab(0.55f, 0.03f + jitter, 0.03f), 0.17f),
            PaletteSwatch(OkLab(0.71f, 0.01f, -0.02f), 0.15f),
            PaletteSwatch(OkLab(0.54f, 0.01f, -0.19f), 0.14f),
        )

    /** Warm selfie light. CCT about 5900 K. */
    fun selfiePalette(jitter: Float = 0f): List<PaletteSwatch> =
        listOf(
            PaletteSwatch(OkLab(0.65f, 0.01f, 0.08f + jitter), 0.31f),
            PaletteSwatch(OkLab(0.53f, 0.04f, 0.05f), 0.26f),
            PaletteSwatch(OkLab(0.39f, 0.04f + jitter, 0.03f), 0.19f),
            PaletteSwatch(OkLab(0.24f, 0.02f, 0.01f), 0.17f),
            PaletteSwatch(OkLab(0.51f, 0.07f, -0.10f), 0.07f),
        )

    // ---- faces and saliency ----

    /** A face box centred at ([cx], [cy]) covering [area] of the frame, roughly square in pixels of a 3:4 photo. */
    fun face(
        cx: Float,
        cy: Float,
        area: Float,
        eyes: Float = 0.85f,
        sharpness: Float = 0.8f,
    ): FaceObservation {
        val w = sqrt(area * 0.75f)
        val h = area / w
        return FaceObservation(NormRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2), eyes, eyes, sharpness)
    }

    /** A uniform long-edge-64 saliency map with the photo's aspect (3:4 portrait or 4:3 landscape). */
    fun saliency(landscape: Boolean = false): SaliencyMap {
        val (w, h) = if (landscape) 64 to 48 else 48 to 64
        return SaliencyMap(w, h, FloatArray(w * h) { 0.5f })
    }

    // ---- analyses ----

    @Suppress("LongParameterList")
    fun photo(
        id: String,
        quality: Float,
        embedding: FloatArray,
        pHash: Long,
        palette: List<PaletteSwatch>,
        cct: Float,
        scale: ShotScale = ShotScale.WIDE,
        aesthetic: Float = quality - 0.2f,
        faces: List<FaceObservation> = emptyList(),
        landscape: Boolean = false,
        sharpness: Float = 0.95f,
        meanL: Float = 0.43f,
        reject: RejectReason? = null,
        screenCapture: Boolean = false,
        dHash: Long = HashSpace.dHashOf(pHash),
    ): PhotoAnalysis {
        val faceQuality = if (faces.isEmpty()) 1f else faces.minOf { it.sharpness }
        return PhotoAnalysis(
            candidateId = id,
            technical =
                TechnicalScore(
                    sharpness = sharpness,
                    exposure = 1f,
                    faceQuality = faceQuality,
                    total = (sharpness + 1f + faceQuality) / 3f,
                ),
            aesthetic = aesthetic,
            composition = 0.62f,
            quality = quality,
            embedding = embedding,
            dHash = dHash,
            pHash = pHash,
            palette = palette,
            labStats = LabStats(meanL, 0.03f, 0.0f, 0.19f, 0.03f, 0.08f, 1f),
            cctKelvin = cct,
            shotScale = scale,
            scene = SceneType.GENERAL,
            faces = faces,
            saliency = saliency(landscape),
            rejectReason = reject,
            screenCapture = screenCapture,
        )
    }

    /**
     * A test set: the photos, their capture times, and the moment each photo belongs to (ground truth the engine
     * never sees). [day] marks the calendar day for multi-day sets.
     */
    class PhotoSet(
        val photos: List<PhotoAnalysis>,
        val times: Map<String, Long?>,
        val momentOf: Map<String, String>,
        val day: Map<String, Int> = emptyMap(),
    ) {
        fun withoutTimes(): PhotoSet = PhotoSet(photos, photos.associate { it.candidateId to null }, momentOf, day)

        fun byId(id: String): PhotoAnalysis = photos.single { it.candidateId == id }

        fun moments(ids: List<String>): List<String> = ids.map { momentOf.getValue(it) }

        /** Moments that occur more than once in [ids]. */
        fun repeatedMoments(ids: List<String>): Set<String> =
            moments(ids)
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
    }

    /** Collects photos for a [PhotoSet]. */
    class SetBuilder {
        private val photos = mutableListOf<PhotoAnalysis>()
        private val times = LinkedHashMap<String, Long?>()
        private val momentOf = LinkedHashMap<String, String>()
        private val day = LinkedHashMap<String, Int>()

        fun add(
            photo: PhotoAnalysis,
            time: Long?,
            moment: String,
            onDay: Int = 0,
        ) {
            photos += photo
            times[photo.candidateId] = time
            momentOf[photo.candidateId] = moment
            day[photo.candidateId] = onDay
        }

        fun build(): PhotoSet = PhotoSet(photos.toList(), times.toMap(), momentOf.toMap(), day.toMap())
    }
}
