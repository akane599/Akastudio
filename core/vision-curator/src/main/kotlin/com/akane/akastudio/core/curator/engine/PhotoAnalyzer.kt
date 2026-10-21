package com.akane.akastudio.core.curator.engine

import com.akane.akastudio.core.color.analysis.ColorAnalysis
import com.akane.akastudio.core.color.api.LabStats
import com.akane.akastudio.core.color.api.OkLab
import com.akane.akastudio.core.color.api.PaletteSwatch
import com.akane.akastudio.core.color.api.SceneType
import com.akane.akastudio.core.color.math.ColorMath
import com.akane.akastudio.core.curator.api.FaceObservation
import com.akane.akastudio.core.curator.api.PhotoAnalysis
import com.akane.akastudio.core.curator.api.RejectReason
import com.akane.akastudio.core.curator.api.SaliencyMap
import com.akane.akastudio.core.curator.api.ShotScale
import com.akane.akastudio.core.curator.api.TechnicalScore
import com.akane.akastudio.core.curator.hash.PerceptualHash
import com.akane.akastudio.core.curator.metrics.Composition
import com.akane.akastudio.core.curator.metrics.Exposure
import com.akane.akastudio.core.curator.metrics.Luma
import com.akane.akastudio.core.curator.metrics.Sharpness
import com.akane.akastudio.core.curator.metrics.ShotScaleClassifier
import com.akane.akastudio.core.curator.metrics.TechnicalGate
import com.akane.akastudio.core.curator.saliency.SpectralResidualSaliency
import com.akane.akastudio.core.ingestion.model.PhotoCandidate
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.cancellation.CancellationException

/**
 * Analyzes one candidate end to end: pixels, faces, saliency, technical gate, hashes, embedding, colour, aesthetic,
 * quality, scene and shot scale. Cooperative: it checks for cancellation between stages.
 */
internal class PhotoAnalyzer(private val pixels: PixelSource, private val models: AnalysisModels) {

    /** One finished [analysis]; [nimaMissing] is true when its image was readable but NIMA gave no score. */
    class AnalyzedPhoto(val analysis: PhotoAnalysis, val nimaMissing: Boolean)

    /**
     * The analyses of one curation under a single aesthetic formula: [nimaMissing] photos lacked a NIMA score, and
     * [rescored] NIMA-scored photos were moved to the no-NIMA formula because of them.
     */
    class UniformAesthetic(val analyses: List<PhotoAnalysis>, val nimaMissing: Int, val rescored: Int)

    suspend fun analyze(candidate: PhotoCandidate): AnalyzedPhoto {
        val image = try {
            pixels.load(candidate)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return AnalyzedPhoto(unreadable(candidate.id), nimaMissing = false)
        }
        try {
            return analyzeImage(candidate, image)
        } finally {
            image.bitmap?.recycle()
        }
    }

    private suspend fun analyzeImage(candidate: PhotoCandidate, image: AnalysisImage): AnalyzedPhoto {
        val px = image.pixels
        val gray = Luma.of(px)
        val faces = models.faces(image).map { face ->
            FaceObservation(
                box = face.box,
                leftEyeOpenness = (1f - face.leftBlink).coerceIn(0f, 1f),
                rightEyeOpenness = (1f - face.rightBlink).coerceIn(0f, 1f),
                sharpness = Sharpness.faceSharpness(gray, px.width, px.height, face.box),
            )
        }
        checkActive()

        val saliency = SpectralResidualSaliency.withFaces(SpectralResidualSaliency.compute(px), faces)
        checkActive()

        val subjectWeights = Sharpness.subjectWeights(px.width, px.height, Sharpness.subjectBox(faces, saliency))
        val sharpness = Sharpness.score(Sharpness.laplacianVariance(gray, px.width, px.height, subjectWeights))
        val exposure = Exposure.evaluate(gray)
        val technical = TechnicalGate.score(sharpness, exposure.score, faces)
        val rejectReason = TechnicalGate.rejectReason(technical, faces, exposure)
        checkActive()

        val dHash = PerceptualHash.dHash(px)
        val pHash = PerceptualHash.pHash(px)
        val embedding = models.embed(image)
        checkActive()

        val color = ColorAnalysis.analyze(px)
        checkActive()

        val composition = Composition.score(saliency)
        val nimaMean = models.aesthetic(image)
        val aesthetic = aestheticScore(nimaMean, composition, color.palette)
        val prominentFace = faces.any(::isProminent)
        val modelScene = models.scene(image)
        val scene = if (prominentFace && modelScene == SceneType.GENERAL) SceneType.PORTRAIT else modelScene

        val analysis = PhotoAnalysis(
            candidateId = candidate.id,
            technical = technical,
            aesthetic = aesthetic,
            composition = composition,
            quality = quality(technical.total, aesthetic),
            embedding = embedding,
            dHash = dHash,
            pHash = pHash,
            palette = color.palette,
            labStats = color.labStats,
            cctKelvin = color.cctKelvin,
            shotScale = ShotScaleClassifier.classify(saliency, faces, candidate.metadata.focalLength35mm),
            scene = scene,
            faces = faces,
            saliency = saliency,
            rejectReason = rejectReason,
        )
        return AnalyzedPhoto(analysis, nimaMissing = nimaMean == null || !nimaMean.isFinite())
    }

    private suspend fun checkActive() = currentCoroutineContext().ensureActive()

    internal companion object {
        /** Face box area, as a fraction of the frame, from which a face makes the photo a portrait. */
        const val PROMINENT_FACE_AREA = 0.06f

        private const val NIMA_OFFSET = 3.0f
        private const val NIMA_SPAN = 4.0f
        private const val NIMA_WEIGHT = 0.7f
        private const val NIMA_COMPOSITION_WEIGHT = 0.3f
        private const val COMPOSITION_WEIGHT = 0.6f
        private const val COLORFULNESS_WEIGHT = 0.4f
        private const val FULL_CHROMA = 0.12f
        private const val TECHNICAL_WEIGHT = 0.45f
        private const val AESTHETIC_WEIGHT = 0.55f
        private const val NEUTRAL_KELVIN = 6500f
        private const val PALETTE_SIZE = 5

        /** True when [face]'s box covers at least [PROMINENT_FACE_AREA] of the frame. */
        fun isProminent(face: FaceObservation): Boolean =
            face.box.width.coerceAtLeast(0f) * face.box.height.coerceAtLeast(0f) >= PROMINENT_FACE_AREA

        /**
         * A: with a NIMA mean m, `0.7 * clamp((m - 3) / 4) + 0.3 * composition`; without one,
         * `0.6 * composition + 0.4 * colorfulness`.
         */
        fun aestheticScore(nimaMean: Float?, composition: Float, palette: List<PaletteSwatch>): Float {
            val c = composition.coerceIn(0f, 1f)
            val a = if (nimaMean != null && nimaMean.isFinite()) {
                val nima = ((nimaMean - NIMA_OFFSET) / NIMA_SPAN).coerceIn(0f, 1f)
                NIMA_WEIGHT * nima + NIMA_COMPOSITION_WEIGHT * c
            } else {
                COMPOSITION_WEIGHT * c + COLORFULNESS_WEIGHT * colorfulness(palette)
            }
            return a.coerceIn(0f, 1f)
        }

        /** `clamp(mean OKLCh chroma / 0.12)` over [palette], each swatch weighted by its share; 0 for no weight. */
        fun colorfulness(palette: List<PaletteSwatch>): Float {
            var weight = 0.0
            var chroma = 0.0
            for (swatch in palette) {
                val w = swatch.weight.toDouble().coerceAtLeast(0.0)
                weight += w
                chroma += w * ColorMath.okLabToOkLch(swatch.lab)[1]
            }
            if (weight <= 0.0) return 0f
            return ((chroma / weight) / FULL_CHROMA).toFloat().coerceIn(0f, 1f)
        }

        /** `Q = 0.45 * T + 0.55 * A`. */
        fun quality(technical: Float, aesthetic: Float): Float =
            (TECHNICAL_WEIGHT * technical + AESTHETIC_WEIGHT * aesthetic).coerceIn(0f, 1f)

        /**
         * One aesthetic formula per curation. When any photo in [photos] lacked a NIMA score, every analysis is
         * rescored with the no-NIMA formula of [aestheticScore] (from its composition and palette) and its Q
         * recomputed, so a per-image NIMA failure never ranks photos on two different scales. Otherwise the analyses
         * are returned unchanged. Unreadable photos never ran the model and do not count as missing.
         */
        fun uniformAesthetic(photos: List<AnalyzedPhoto>): UniformAesthetic {
            val missing = photos.count { it.nimaMissing }
            if (missing == 0) return UniformAesthetic(photos.map { it.analysis }, nimaMissing = 0, rescored = 0)
            var rescored = 0
            val analyses = photos.map { photo ->
                val a = photo.analysis
                if (photo.nimaMissing || a.rejectReason == RejectReason.UNREADABLE) return@map a
                rescored++
                val fallback = aestheticScore(null, a.composition, a.palette)
                a.copy(aesthetic = fallback, quality = quality(a.technical.total, fallback))
            }
            return UniformAesthetic(analyses, nimaMissing = missing, rescored = rescored)
        }

        /**
         * The analysis of a candidate whose pixels could not be loaded: rejected as UNREADABLE with zero scores, no
         * faces, an empty embedding, zero hashes, neutral grey colour statistics at 6500 K and a five-swatch grey
         * palette of equal weights.
         */
        fun unreadable(id: String): PhotoAnalysis {
            val grey = OkLab(0.5f, 0f, 0f)
            return PhotoAnalysis(
                candidateId = id,
                technical = TechnicalScore(sharpness = 0f, exposure = 0f, faceQuality = 1f, total = 0f),
                aesthetic = 0f,
                composition = 0f,
                quality = 0f,
                embedding = FloatArray(0),
                dHash = 0L,
                pHash = 0L,
                palette = List(PALETTE_SIZE) { PaletteSwatch(grey, 1f / PALETTE_SIZE) },
                labStats = LabStats(grey.l, grey.a, grey.b, 0f, 0f, 0f, 0f),
                cctKelvin = NEUTRAL_KELVIN,
                shotScale = ShotScale.WIDE,
                scene = SceneType.GENERAL,
                faces = emptyList(),
                saliency = SaliencyMap(1, 1, floatArrayOf(0f)),
                rejectReason = RejectReason.UNREADABLE,
            )
        }
    }
}
