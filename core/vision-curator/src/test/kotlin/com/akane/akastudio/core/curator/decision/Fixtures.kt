package com.akane.akastudio.core.curator.decision

import com.akane.akastudio.core.color.api.LabStats
import com.akane.akastudio.core.color.api.OkLab
import com.akane.akastudio.core.color.api.PaletteSwatch
import com.akane.akastudio.core.color.api.SceneType
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
        faces = emptyList(),
        saliency = SaliencyMap(1, 1, floatArrayOf(1f)),
        rejectReason = reject,
    )

    /** Flips the lowest [bits] bits of [hash]. */
    fun flip(hash: Long, bits: Int): Long = hash xor (if (bits == 64) -1L else (1L shl bits) - 1)
}
