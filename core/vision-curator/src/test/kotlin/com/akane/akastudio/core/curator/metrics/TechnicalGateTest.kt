package com.akane.akastudio.core.curator.metrics

import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.curator.api.RejectReason
import com.akane.akastudio.core.curator.api.TechnicalScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TechnicalGateTest {
    private val prominent = TestImages.centredBox(0.09f)
    private val small = TestImages.centredBox(0.01f)

    private fun technical(sharpness: Float, exposure: Float) = TechnicalScore(sharpness, exposure, 1f, 0f)

    @Test
    fun score_withoutFaces_usesFullFaceQuality() {
        val t = TechnicalGate.score(0.8f, 0.6f, emptyList())
        assertEquals(1f, t.faceQuality, 0f)
        assertEquals(0.5f * 0.8f + 0.3f * 0.6f + 0.2f, t.total, 1e-6f)
    }

    @Test
    fun score_faceQualityIsAreaWeightedOverProminentFaces() {
        val big = TestImages.face(NormRect(0f, 0f, 0.4f, 0.2f), left = 0.3f, right = 0.9f, sharpness = 0.6f) // area .08
        val medium = TestImages.face(NormRect(0.5f, 0.5f, 0.8f, 0.6f), left = 1f, right = 1f, sharpness = 1f) // area .03
        val tiny = TestImages.face(NormRect(0.8f, 0.8f, 0.9f, 0.9f), left = 0f, right = 0f, sharpness = 0f) // area .01
        val t = TechnicalGate.score(0.5f, 0.5f, listOf(big, medium, tiny))
        val qBig = 0.5f * (0.3f / 0.6f) + 0.5f * 0.6f
        val qMedium = 0.5f * 1f + 0.5f * 1f
        val expected = (0.08f * qBig + 0.03f * qMedium) / 0.11f
        assertEquals(expected, t.faceQuality, 1e-4f)
        assertEquals(0.25f + 0.15f + 0.2f * expected, t.total, 1e-4f)
    }

    @Test
    fun score_imageAreaRescalesProminence() {
        val face = TestImages.face(NormRect(0f, 0f, 0.2f, 0.2f), left = 0f, right = 0f, sharpness = 0f) // area .04
        assertEquals(0f, TechnicalGate.score(1f, 1f, listOf(face)).faceQuality, 0f)
        assertEquals(1f, TechnicalGate.score(1f, 1f, listOf(face), imageArea = 4f).faceQuality, 0f)
    }

    @Test
    fun reject_eyesClosedOnProminentFace() {
        val closed = TestImages.face(prominent, left = 0.2f, right = 0.34f)
        assertEquals(RejectReason.EYES_CLOSED, TechnicalGate.rejectReason(technical(0.9f, 0.9f), listOf(closed)))
        val oneOpen = TestImages.face(prominent, left = 0.1f, right = 0.4f)
        assertNull(TechnicalGate.rejectReason(technical(0.9f, 0.9f), listOf(oneOpen)))
    }

    @Test
    fun reject_motionBlurredProminentFaceCountsAsEyesClosed() {
        val smeared = TestImages.face(prominent, sharpness = 0.14f)
        assertEquals(RejectReason.EYES_CLOSED, TechnicalGate.rejectReason(technical(0.9f, 0.9f), listOf(smeared)))
    }

    @Test
    fun reject_blurry() {
        assertEquals(RejectReason.BLURRY, TechnicalGate.rejectReason(technical(0.17f, 0.9f), emptyList()))
        assertNull(TechnicalGate.rejectReason(technical(0.18f, 0.9f), emptyList()))
    }

    @Test
    fun reject_badExposure() {
        assertEquals(RejectReason.BAD_EXPOSURE, TechnicalGate.rejectReason(technical(0.9f, 0.14f), emptyList()))
        assertNull(TechnicalGate.rejectReason(technical(0.9f, 0.15f), emptyList()))
    }

    @Test
    fun reject_orderPrecedence() {
        val closed = listOf(TestImages.face(prominent, left = 0f, right = 0f))
        assertEquals(RejectReason.EYES_CLOSED, TechnicalGate.rejectReason(technical(0.1f, 0.1f), closed))
        assertEquals(RejectReason.BLURRY, TechnicalGate.rejectReason(technical(0.1f, 0.1f), emptyList()))
    }

    @Test
    fun reject_smallFaceWithClosedEyesIsIgnored() {
        val tinyClosed = TestImages.face(small, left = 0f, right = 0f, sharpness = 0f)
        assertNull(TechnicalGate.rejectReason(technical(0.9f, 0.9f), listOf(tinyClosed)))
    }
}
