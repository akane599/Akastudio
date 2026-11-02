package com.akane.akastudio.core.composer.crop

import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.composer.api.CanvasSize
import com.akane.akastudio.core.composer.api.LayoutStyle
import com.akane.akastudio.core.composer.api.StoryTemplate
import com.akane.akastudio.core.composer.crop.CropFixtures.face
import com.akane.akastudio.core.composer.crop.CropFixtures.photo
import com.akane.akastudio.core.composer.crop.CropFixtures.saliency
import com.akane.akastudio.core.composer.layout.TemplateGeometry
import com.akane.akastudio.core.curator.api.FaceObservation
import com.akane.akastudio.core.curator.decision.CellFit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The curator's closed-form face check ([CellFit.facesFit]) must agree exactly with what [SmartCropSolver] renders at
 * rotation 0: feasible ⇔ the solved crop contains every 15%-expanded face box. This holds because the solver ranks
 * "all faces inside" first and evaluates a crop centred on the face union (clamped into the image) at every scale,
 * including the largest; when the union does not fit the largest crop, no crop of that aspect can hold it.
 */
class CellFitAgreementTest {
    /** Every cropped cell aspect of every template at the gutters a user can pick. */
    private val templateAspects: List<Float> =
        listOf(0f, 12f, 24f, 48f)
            .flatMap { gutter ->
                StoryTemplate.entries.filter { it != StoryTemplate.HERO_GALLERY_MATTE }.flatMap { template ->
                    TemplateGeometry
                        .cells(template, CanvasSize.STORY_1080, LayoutStyle(gutterPx = gutter), List(template.photoCount) { 1f })
                        .map { it.width / it.height }
                }
            }.distinct()

    private val sizes = listOf(4000 to 3000, 3000 to 4000, 2000 to 2000, 1920 to 1080, 1080 to 1920)

    /** True when [crop] holds every expanded face box the solver itself protects, within float precision. */
    private fun keepsAllFaces(
        crop: NormRect,
        faces: List<FaceObservation>,
        width: Int,
        height: Int,
    ): Boolean {
        val tolPx = 0.01
        return SmartCropSolver.faceBoxes(faces, CropFrame(width.toDouble(), height.toDouble(), 0f)).all { box ->
            box.left >= crop.left * width.toDouble() - tolPx &&
                box.right <= crop.right * width.toDouble() + tolPx &&
                box.top >= crop.top * height.toDouble() - tolPx &&
                box.bottom <= crop.bottom * height.toDouble() + tolPx
        }
    }

    @Test
    fun randomizedFacesAndAspects_feasibilityEqualsTheSolversFaceContainment() {
        val random = Random(20261102)
        var feasibleCount = 0
        var infeasibleCount = 0
        repeat(120) { trial ->
            val (w, h) = sizes[random.nextInt(sizes.size)]
            val faces =
                List(1 + random.nextInt(3)) {
                    val fw = 0.03f + random.nextFloat() * 0.32f
                    val fh = 0.03f + random.nextFloat() * 0.32f
                    // Centres may sit near or past an edge so clamping is exercised.
                    val cx = -0.05f + random.nextFloat() * 1.1f
                    val cy = -0.05f + random.nextFloat() * 1.1f
                    face(cx - fw / 2, cy - fh / 2, cx + fw / 2, cy + fh / 2)
                }
            // Saliency away from the faces, so the solver's score pulls against keeping them.
            val sal =
                saliency(
                    w,
                    h,
                    Triple(random.nextDouble(), random.nextDouble(), 2.0 + random.nextDouble() * 6),
                    Triple(random.nextDouble(), random.nextDouble(), 2.0 + random.nextDouble() * 6),
                )
            val p = photo(w, h, sal, faces)
            for (aspect in templateAspects) {
                val feasible = CellFit.facesFit(faces, w.toDouble() / h, aspect.toDouble())
                val crop = SmartCropSolver.solve(p, aspect, 0f)
                assertEquals(
                    "trial $trial ${w}x$h aspect $aspect faces ${faces.map { it.box }}",
                    feasible,
                    keepsAllFaces(crop, faces, w, h),
                )
                if (feasible) feasibleCount++ else infeasibleCount++
            }
        }
        assertTrue("both outcomes must be exercised: $feasibleCount / $infeasibleCount", feasibleCount > 200 && infeasibleCount > 200)
    }

    @Test
    fun twoFaceLandscape_curatorSaysInfeasibleInQuadGrid_andTheSolverIndeedCutsAFace() {
        val faces = listOf(face(0.10f, 0.35f, 0.22f, 0.55f), face(0.78f, 0.35f, 0.90f, 0.55f))
        val p = photo(4000, 3000, saliency(4000, 3000, Triple(0.5, 0.15, 4.0)), faces)
        val quad = 0.554f
        assertFalse(CellFit.facesFit(faces, 4000.0 / 3000, quad.toDouble()))
        assertFalse("the solver must cut a face", keepsAllFaces(SmartCropSolver.solve(p, quad, 0f), faces, 4000, 3000))

        val cinematic = 1.121f
        assertTrue(CellFit.facesFit(faces, 4000.0 / 3000, cinematic.toDouble()))
        assertTrue(keepsAllFaces(SmartCropSolver.solve(p, cinematic, 0f), faces, 4000, 3000))
    }

    @Test
    fun solverExpansion_isTheCuratorsConstant() {
        assertEquals(CellFit.FACE_EXPANSION, SmartCropSolver.FACE_EXPANSION, 0.0)
    }
}
