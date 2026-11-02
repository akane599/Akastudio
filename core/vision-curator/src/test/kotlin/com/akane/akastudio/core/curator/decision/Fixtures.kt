package com.akane.akastudio.core.curator.decision

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
import kotlin.random.Random

/**
 * Test builders. Defaults give every photo its own random embedding (128-d, so unrelated photos have cosine near 0)
 * and its own random 64-bit hashes (expected Hamming distance 32), all seeded by id so fixtures are reproducible,
 * and a shared neutral grey palette so colour terms are perfect unless a test overrides them.
 */
internal object Fixtures {

    val GREY: List<PaletteSwatch> = solid(0.5f, 0f, 0f)

    fun solid(l: Float, a: Float, b: Float): List<PaletteSwatch> = listOf(PaletteSwatch(OkLab(l, a, b), 1f))

    fun randomHash(id: String, salt: Int): Long = Random(id.hashCode() * 31 + salt).nextLong()

    fun randomEmbedding(id: String, dims: Int = 128): FloatArray {
        val random = Random(id.hashCode() * 17 + 5)
        val v = FloatArray(dims) { (random.nextDouble() * 2 - 1).toFloat() }
        val norm = sqrt(v.sumOf { it.toDouble() * it }).toFloat()
        return FloatArray(dims) { v[it] / norm }
    }

    fun analysis(
        id: String,
        quality: Float = 0.7f,
        aesthetic: Float = 0.5f,
        embedding: FloatArray = randomEmbedding(id),
        dHash: Long = randomHash(id, 1),
        pHash: Long = randomHash(id, 2),
        palette: List<PaletteSwatch> = GREY,
        cct: Float = 5500f,
        meanL: Float = 0.5f,
        scale: ShotScale = ShotScale.WIDE,
        reject: RejectReason? = null,
        faces: List<FaceObservation> = emptyList(),
        screenCapture: Boolean = false,
    ): PhotoAnalysis = PhotoAnalysis(
        candidateId = id,
        technical = TechnicalScore(sharpness = 0.8f, exposure = 0.8f, faceQuality = 1f, total = 0.8f),
        aesthetic = aesthetic,
        composition = 0.5f,
        quality = quality,
        embedding = embedding,
        dHash = dHash,
        pHash = pHash,
        palette = palette,
        labStats = LabStats(meanL, 0f, 0f, 0.1f, 0.02f, 0.02f, 1f),
        cctKelvin = cct,
        shotScale = scale,
        scene = SceneType.GENERAL,
        faces = faces,
        saliency = SaliencyMap(1, 1, floatArrayOf(1f)),
        rejectReason = reject,
        screenCapture = screenCapture,
    )

    /** An open-eyed, sharp face covering [area] of the frame (a square box), centred at ([cx], [cy]). */
    fun face(cx: Float = 0.5f, cy: Float = 0.5f, area: Float = 0.06f): FaceObservation {
        val half = sqrt(area) / 2f
        return FaceObservation(
            box = NormRect(cx - half, cy - half, cx + half, cy + half),
            leftEyeOpenness = 1f,
            rightEyeOpenness = 1f,
            sharpness = 0.8f,
        )
    }

    /**
     * A unit vector whose cosine with the unit vector [base] is exactly [cosine] (up to float rounding): [base] mixed
     * with a random direction orthogonal to it, seeded by [seed]. Use it to place photos at measured cosine bands.
     */
    fun embeddingAt(base: FloatArray, cosine: Float, seed: String): FloatArray {
        require(cosine in -1f..1f) { "cosine must be in -1..1: $cosine" }
        val random = randomEmbedding(seed, base.size)
        val dot = base.indices.sumOf { base[it].toDouble() * random[it] }
        val orthogonal = DoubleArray(base.size) { random[it] - dot * base[it] }
        val norm = sqrt(orthogonal.sumOf { it * it })
        val sine = sqrt(1.0 - cosine.toDouble() * cosine)
        return FloatArray(base.size) { (cosine * base[it] + sine * orthogonal[it] / norm).toFloat() }
    }

    /**
     * Unit vectors (padded with zeros to [dims]) whose pairwise cosines are exactly [cosines], a symmetric positive
     * definite matrix with a unit diagonal, by Cholesky decomposition. Use it to rebuild measured cosine matrices.
     */
    fun embeddingsWithCosines(cosines: List<List<Double>>, dims: Int = 128): List<FloatArray> {
        val n = cosines.size
        require(n <= dims && cosines.all { it.size == n }) { "need a square matrix of at most $dims rows" }
        val l = Array(n) { DoubleArray(n) }
        for (i in 0 until n) {
            for (j in 0..i) {
                val sum = (0 until j).sumOf { l[i][it] * l[j][it] }
                if (i == j) {
                    val d = cosines[i][i] - sum
                    require(d > 0.0) { "cosine matrix is not positive definite at row $i" }
                    l[i][i] = sqrt(d)
                } else {
                    l[i][j] = (cosines[i][j] - sum) / l[j][j]
                }
            }
        }
        return List(n) { i -> FloatArray(dims) { k -> if (k < n) l[i][k].toFloat() else 0f } }
    }

    /** A hash at exactly [distance] Hamming bits from [hash] (its lowest [distance] bits flipped). */
    fun atDistance(hash: Long, distance: Int): Long = flip(hash, distance)

    /** Flips the lowest [bits] bits of [hash]. */
    fun flip(hash: Long, bits: Int): Long = hash xor (if (bits == 64) -1L else (1L shl bits) - 1)
}
