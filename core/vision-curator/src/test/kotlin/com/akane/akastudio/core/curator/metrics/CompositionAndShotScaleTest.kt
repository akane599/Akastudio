package com.akane.akastudio.core.curator.metrics

import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.curator.api.SaliencyMap
import com.akane.akastudio.core.curator.api.ShotScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositionAndShotScaleTest {

    @Test
    fun composition_blobAtThirdsBeatsBlobInCorner() {
        val thirds = Composition.score(TestImages.blobMap(64, 48, 1f / 3, 1f / 3, sigma = 3f))
        val corner = Composition.score(TestImages.blobMap(64, 48, 0.06f, 0.06f, sigma = 3f))
        assertTrue("thirds=$thirds corner=$corner", thirds > corner + 0.3f)
    }

    @Test
    fun composition_rangeAndDegenerateMaps() {
        val centred = Composition.score(TestImages.blobMap(64, 64, 0.5f, 0.5f, sigma = 3f))
        // Centre point weight 0.6, perfectly balanced, compact: 0.6*0.6 + 0.25 + 0.15.
        assertEquals(0.76f, centred, 0.01f)
        val empty = Composition.score(SaliencyMap(8, 8, FloatArray(64)))
        val uniform = Composition.score(SaliencyMap(8, 8, FloatArray(64) { 1f }))
        assertEquals(uniform, empty, 0.01f)
        for (s in listOf(centred, empty, uniform)) assertTrue("$s", s in 0f..1f)
    }

    @Test
    fun composition_scatteredSubjectIsPenalised() {
        val compact = Composition.score(TestImages.blobMap(64, 64, 0.5f, 0.5f, sigma = 3f))
        val scattered = Composition.score(SaliencyMap(64, 64, FloatArray(64 * 64) { 1f }))
        assertTrue("compact=$compact scattered=$scattered", compact > scattered)
    }

    private val noSubject = TestImages.fractionMap(10, 10, 0f)

    @Test
    fun shotScale_teleDetail() {
        assertEquals(ShotScale.DETAIL, ShotScaleClassifier.classify(TestImages.fractionMap(10, 10, 0.25f), listOf(face(0.1f)), 85))
        assertEquals(ShotScale.MEDIUM, ShotScaleClassifier.classify(TestImages.fractionMap(10, 10, 0.25f), listOf(face(0.1f)), 84))
    }

    @Test
    fun shotScale_detailWithoutFaces() {
        assertEquals(ShotScale.DETAIL, ShotScaleClassifier.classify(TestImages.fractionMap(10, 10, 0.45f), emptyList(), null))
        assertEquals(ShotScale.MEDIUM, ShotScaleClassifier.classify(TestImages.fractionMap(10, 10, 0.45f), listOf(face(0.01f)), null))
    }

    @Test
    fun shotScale_mediumFromFaceOrSubject() {
        assertEquals(ShotScale.MEDIUM, ShotScaleClassifier.classify(noSubject, listOf(face(0.041f)), 50))
        assertEquals(ShotScale.MEDIUM, ShotScaleClassifier.classify(TestImages.fractionMap(10, 10, 0.18f), emptyList(), 50))
    }

    @Test
    fun shotScale_wideOtherwise() {
        assertEquals(ShotScale.WIDE, ShotScaleClassifier.classify(TestImages.fractionMap(10, 10, 0.17f), listOf(face(0.03f)), 50))
        assertEquals(ShotScale.WIDE, ShotScaleClassifier.classify(noSubject, emptyList(), null))
    }

    @Test
    fun shotScale_wideLensForcesWide() {
        assertEquals(ShotScale.WIDE, ShotScaleClassifier.classify(TestImages.fractionMap(10, 10, 0.24f), listOf(face(0.2f)), 24))
        assertEquals(ShotScale.MEDIUM, ShotScaleClassifier.classify(TestImages.fractionMap(10, 10, 0.25f), listOf(face(0.2f)), 24))
        assertEquals(ShotScale.MEDIUM, ShotScaleClassifier.classify(TestImages.fractionMap(10, 10, 0.24f), listOf(face(0.2f)), 25))
    }

    private fun face(area: Float) = TestImages.face(TestImages.centredBox(area))
}
