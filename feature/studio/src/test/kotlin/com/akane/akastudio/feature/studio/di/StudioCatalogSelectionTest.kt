package com.akane.akastudio.feature.studio.di

import com.akane.akastudio.core.color.api.LabStats
import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.color.api.OkLab
import com.akane.akastudio.core.color.api.PaletteSwatch
import com.akane.akastudio.core.color.api.SceneType
import com.akane.akastudio.core.composer.api.StoryTemplate
import com.akane.akastudio.core.curator.api.FaceObservation
import com.akane.akastudio.core.curator.api.PhotoAnalysis
import com.akane.akastudio.core.curator.api.SaliencyMap
import com.akane.akastudio.core.curator.api.ShotScale
import com.akane.akastudio.core.curator.api.StoryMode
import com.akane.akastudio.core.curator.api.TechnicalScore
import com.akane.akastudio.core.curator.decision.CellFit
import com.akane.akastudio.core.curator.decision.CellShape
import com.akane.akastudio.core.curator.decision.DecisionConfig
import com.akane.akastudio.core.curator.decision.DecisionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Selection with the configuration that ships: the studio's catalogue and [CellFit]. A good landscape photo of two
 * people at opposite edges (expanded face union about 0.85 of the width, Q at 0.86 of the best) only fits QUAD_MOSAIC's
 * wide bottom cell; it must still reach the chosen story.
 */
class StudioCatalogSelectionTest {
    private fun embedding(seed: Int): FloatArray {
        val random = Random(seed)
        val v = FloatArray(128) { (random.nextDouble() * 2 - 1).toFloat() }
        val norm = sqrt(v.sumOf { it.toDouble() * it }).toFloat()
        return FloatArray(v.size) { v[it] / norm }
    }

    private fun face(
        l: Float,
        t: Float,
        r: Float,
        b: Float,
    ) = FaceObservation(NormRect(l, t, r, b), 1f, 1f, 0.9f)

    private fun analysis(
        id: String,
        seed: Int,
        quality: Float,
        aesthetic: Float,
        landscape: Boolean,
        faces: List<FaceObservation>,
    ): PhotoAnalysis {
        val (w, h) = if (landscape) 64 to 48 else 48 to 64
        val random = Random(seed * 7 + 3)
        return PhotoAnalysis(
            candidateId = id,
            technical = TechnicalScore(sharpness = 0.85f, exposure = 0.9f, faceQuality = 0.9f, total = 0.87f),
            aesthetic = aesthetic,
            composition = 0.6f,
            quality = quality,
            embedding = embedding(seed),
            dHash = random.nextLong(),
            pHash = random.nextLong(),
            palette = listOf(PaletteSwatch(OkLab(0.5f, 0.02f, 0.03f), 1f)),
            labStats = LabStats(0.5f, 0.02f, 0.03f, 0.15f, 0.03f, 0.03f, 1f),
            cctKelvin = 4200f,
            shotScale = ShotScale.MEDIUM,
            scene = SceneType.GENERAL,
            faces = faces,
            saliency = SaliencyMap(w, h, FloatArray(w * h) { 0.5f }),
            rejectReason = null,
        )
    }

    /** Three strong portraits of different moments plus the landscape pair; capture times 20 minutes apart. */
    private val photos =
        listOf(
            analysis("anchor", 1, quality = 0.76f, aesthetic = 0.62f, landscape = false, faces = listOf(face(0.35f, 0.25f, 0.65f, 0.45f))),
            analysis("solo", 2, quality = 0.74f, aesthetic = 0.55f, landscape = false, faces = listOf(face(0.4f, 0.2f, 0.62f, 0.38f))),
            analysis("street", 3, quality = 0.72f, aesthetic = 0.52f, landscape = false, faces = emptyList()),
            analysis(
                "pair",
                4,
                quality = 0.655f,
                aesthetic = 0.45f,
                landscape = true,
                faces = listOf(face(0.03f, 0.19f, 0.28f, 0.55f), face(0.73f, 0.31f, 0.855f, 0.51f)),
            ),
        )
    private val times: Map<String, Long?> = photos.mapIndexed { i, p -> p.candidateId to 1_792_260_000_000L + i * 20 * 60_000L }.toMap()

    private fun config(fitter: CellFit) = DecisionConfig(layoutCatalog = studioLayoutCatalog(), layoutFitter = fitter)

    @Test
    fun shippedConfig_choosesTheQuadMosaicWithThePairInItsWideCell_andTheAnchorInSlotZero() {
        val chosen = DecisionEngine.decide(photos, times, config(CellFit())).chosen
        assertEquals(StoryMode.COLLAGE_4, chosen.mode)
        assertEquals(StoryTemplate.QUAD_MOSAIC.name, chosen.layoutId)
        assertEquals("anchor", chosen.photoIds.first())
        val slot = chosen.photoIds.indexOf("pair")
        assertTrue("pair in ${chosen.photoIds}", slot > 0)
        val cell = studioLayoutCatalog().layouts.single { it.id == chosen.layoutId }.cells[slot] as CellShape.Aspect
        assertTrue("pair's cell aspect ${cell.widthOverHeight}", cell.widthOverHeight >= 1.1f)
    }

    @Test
    fun theOldFivePercentMarginOnTopOfTheGutterRange_wouldLoseThePair() {
        val decision = DecisionEngine.decide(photos, times, config(CellFit(aspectMargin = 0.05f)))
        assertFalse("pair in ${decision.chosen.photoIds}", "pair" in decision.chosen.photoIds)
    }
}
