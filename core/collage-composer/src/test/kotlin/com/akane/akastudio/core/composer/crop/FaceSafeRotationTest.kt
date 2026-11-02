package com.akane.akastudio.core.composer.crop

import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.composer.api.CanvasSize
import com.akane.akastudio.core.composer.api.ComposerPhoto
import com.akane.akastudio.core.composer.api.CompositionRequest
import com.akane.akastudio.core.composer.api.LayoutStyle
import com.akane.akastudio.core.composer.api.StoryTemplate
import com.akane.akastudio.core.composer.compose.DefaultCollageComposer
import com.akane.akastudio.core.composer.crop.CropFixtures.expanded
import com.akane.akastudio.core.composer.crop.CropFixtures.face
import com.akane.akastudio.core.composer.crop.CropFixtures.photo
import com.akane.akastudio.core.composer.crop.CropFixtures.saliency
import com.akane.akastudio.core.composer.crop.CropFixtures.visibleInside
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Faces beat the horizon: the composer straightens a detected tilt only as far as every expanded face stays in. */
class FaceSafeRotationTest {
    /** QUAD_MOSAIC's bottom cell at the default gutter. */
    private val mosaicBottom = 1056f / 903.6f

    /** 4:3 landscape with two faces at opposite edges: expanded union 0.853 wide, the upright crop keeps 0.877. */
    private fun edgePair(tilt: Float): ComposerPhoto =
        photo(
            4000,
            3000,
            saliency(4000, 3000, Triple(0.5, 0.2, 4.0)),
            listOf(face(0.03f, 0.19f, 0.28f, 0.55f), face(0.73f, 0.31f, 0.855f, 0.51f)),
            tilt,
        )

    private fun keepsFaces(
        p: ComposerPhoto,
        crop: NormRect,
        degrees: Float,
    ) = p.faces.all { visibleInside(expanded(it), crop, p.widthPx, p.heightPx, degrees, tolPx = 0.01) }

    @Test
    fun pairAtTheFeasibilityEdge_tiltIsReducedSoBothFacesStay() {
        for (tilt in listOf(5f, -5f)) {
            val p = edgePair(tilt)
            assertTrue("upright keeps both faces", keepsFaces(p, SmartCropSolver.solve(p, mosaicBottom, 0f), 0f))
            assertFalse("full straightening cuts a face", keepsFaces(p, SmartCropSolver.solve(p, mosaicBottom, tilt), tilt))

            val limited = SmartCropSolver.faceSafeRotation(p, mosaicBottom, tilt)
            assertTrue("same sign, smaller: $limited", limited * tilt >= 0f && kotlin.math.abs(limited) < kotlin.math.abs(tilt))
            assertTrue("faces kept at $limited", keepsFaces(p, SmartCropSolver.solve(p, mosaicBottom, limited), limited))
        }
    }

    @Test
    fun centredSingleFace_tiltIsAppliedUnchanged() {
        val p = photo(4000, 3000, saliency(4000, 3000, Triple(0.5, 0.5, 4.0)), listOf(face(0.45f, 0.4f, 0.55f, 0.55f)), 5f)
        assertEquals(5f, SmartCropSolver.faceSafeRotation(p, mosaicBottom, 5f))
        assertEquals(-3.5f, SmartCropSolver.faceSafeRotation(p, 0.554f, -3.5f))
    }

    @Test
    fun noFaces_zeroTilt_orFacesCutEvenUpright_keepTheTilt() {
        assertEquals(5f, SmartCropSolver.faceSafeRotation(photo(4000, 3000), mosaicBottom, 5f))
        assertEquals(0f, SmartCropSolver.faceSafeRotation(edgePair(0f), mosaicBottom, 0f))
        assertEquals(0f, SmartCropSolver.faceSafeRotation(edgePair(Float.NaN), mosaicBottom, Float.NaN))
        // QUAD_GRID's 0.554 cell cuts a face of the pair at any angle, so straightening is not given up for nothing.
        assertEquals(4f, SmartCropSolver.faceSafeRotation(edgePair(4f), 0.554f, 4f))
    }

    @Test
    fun composerLayout_rendersThePairWithTheReducedTilt_andKeepsItsFaces() {
        val pair = edgePair(5f)
        val others = List(3) { photo(3000, 4000).copy(id = "o$it", imageKey = "k$it") }
        val request =
            CompositionRequest(
                template = StoryTemplate.QUAD_MOSAIC,
                style = LayoutStyle(),
                canvas = CanvasSize.STORY_1080,
                photos = others + pair,
                grades = emptyMap(),
            )
        val placement = DefaultCollageComposer().layout(request)[3]
        assertTrue("tilt reduced: ${placement.rotationDegrees}", placement.rotationDegrees in 0f..4.99f)
        assertEquals(SmartCropSolver.faceSafeRotation(pair, placement.dst.width / placement.dst.height, 5f), placement.rotationDegrees)
        assertTrue(keepsFaces(pair, placement.crop, placement.rotationDegrees))
    }
}
