package com.akane.akastudio.core.curator.engine

import com.akane.akastudio.core.color.api.ImagePixels
import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.color.api.SceneType
import com.akane.akastudio.core.curator.ml.DetectedFace
import com.akane.akastudio.core.ingestion.model.CaptureMetadata
import com.akane.akastudio.core.ingestion.model.ImageFormat
import com.akane.akastudio.core.ingestion.model.PhotoCandidate
import com.akane.akastudio.core.ingestion.model.PhotoSource
import java.io.IOException
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** Synthetic analysis images. */
internal object EngineImages {
    const val WIDTH = 96
    const val HEIGHT = 72

    private fun grey(v: Int): Int {
        val c = v.coerceIn(0, 255)
        return (0xFF shl 24) or (c shl 16) or (c shl 8) or c
    }

    /** Sharp, well-exposed grey noise: every pixel an independent luma in 20..235, seeded. */
    fun noise(seed: Int, w: Int = WIDTH, h: Int = HEIGHT): ImagePixels {
        val random = Random(seed)
        return ImagePixels(w, h, IntArray(w * h) { grey(20 + random.nextInt(216)) })
    }

    /**
     * Grey noise with a large bright square subject: the outer frame is low-contrast noise around 70, the centre
     * square (half the frame on each side) full-range noise lifted toward white, seeded.
     */
    fun subject(seed: Int, w: Int = WIDTH, h: Int = HEIGHT): ImagePixels {
        val random = Random(seed)
        return ImagePixels(w, h, IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val inside = x >= w / 4 && x < w - w / 4 && y >= h / 4 && y < h - h / 4
            grey(if (inside) 120 + random.nextInt(116) else 20 + random.nextInt(100))
        })
    }

    /** [image] with one pixel nudged by +3 luma: a near-duplicate frame. */
    fun nudged(image: ImagePixels, index: Int): ImagePixels {
        val argb = image.argb.copyOf()
        val v = (argb[index] and 0xFF) + 3
        argb[index] = grey(v)
        return ImagePixels(image.width, image.height, argb)
    }
}

/**
 * One fake photo: the pixels the fake source serves and what the fake models answer for them.
 * [failLoad] makes the source throw an IOException for it.
 */
internal class Shot(
    val pixels: ImagePixels,
    val faces: List<DetectedFace> = emptyList(),
    val nima: Float? = 7f,
    val scene: SceneType = SceneType.GENERAL,
    val embedding: FloatArray = FloatArray(0),
    val captureMillis: Long? = null,
    val focal: Int? = null,
    val failLoad: Boolean = false,
)

/** A fake world of shots by id, with a [PixelSource] and [AnalysisModels] over them. */
internal class FakeWorld(private val shots: Map<String, Shot>) {

    private val byPixels = IdentityHashMap<ImagePixels, Shot>().apply {
        for (shot in shots.values) put(shot.pixels, shot)
    }

    val loads = AtomicInteger()

    val candidates: List<PhotoCandidate> = shots.map { (id, shot) -> candidate(id, shot) }

    val source = PixelSource { candidate ->
        loads.incrementAndGet()
        val shot = shots.getValue(candidate.id)
        if (shot.failLoad) throw IOException("cannot decode ${candidate.id}")
        AnalysisImage(bitmap = null, pixels = shot.pixels)
    }

    val models = object : AnalysisModels {
        override suspend fun faces(img: AnalysisImage) = shotOf(img).faces
        override suspend fun embed(img: AnalysisImage) = shotOf(img).embedding
        override suspend fun aesthetic(img: AnalysisImage) = shotOf(img).nima
        override suspend fun scene(img: AnalysisImage) = shotOf(img).scene
    }

    private fun shotOf(img: AnalysisImage): Shot = checkNotNull(byPixels[img.pixels]) { "unknown image" }

    companion object {
        fun candidate(id: String, shot: Shot? = null): PhotoCandidate = PhotoCandidate(
            id = id,
            source = PhotoSource.ExtractedFile(path = "/fake/$id.jpg", archiveEntryName = "$id.jpg", displayName = "$id.jpg"),
            format = ImageFormat.JPEG,
            byteSize = null,
            widthPx = shot?.pixels?.width ?: EngineImages.WIDTH,
            heightPx = shot?.pixels?.height ?: EngineImages.HEIGHT,
            metadata = CaptureMetadata.EMPTY.copy(
                captureEpochMillis = shot?.captureMillis,
                focalLength35mm = shot?.focal,
            ),
        )

        /** An open-eyed face covering [area] of the frame, centred. */
        fun face(area: Float, blink: Float = 0f): DetectedFace {
            val side = kotlin.math.sqrt(area)
            val box = NormRect(0.5f - side / 2, 0.5f - side / 2, 0.5f + side / 2, 0.5f + side / 2)
            return DetectedFace(box, leftBlink = blink, rightBlink = blink)
        }
    }
}
