package com.akane.akastudio.core.composer.crop

import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.composer.crop.CropFixtures.expanded
import com.akane.akastudio.core.composer.crop.CropFixtures.face
import com.akane.akastudio.core.composer.crop.CropFixtures.photo
import com.akane.akastudio.core.composer.crop.CropFixtures.rotatedCropInsideImage
import com.akane.akastudio.core.composer.crop.CropFixtures.saliency
import com.akane.akastudio.core.composer.crop.CropFixtures.visibleInside
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SmartCropSolverTest {

    private fun NormRect.contains(x: Float, y: Float) = x in left..right && y in top..bottom
    private fun NormRect.aspectIn(width: Int, height: Int) = (this.width * width) / (this.height * height)

    @Test
    fun salientBlobInRightThird_isInsideTheCrop_whileTheCentreCropMissesIt() {
        val p = photo(1600, 900, saliency(1600, 900, Triple(0.8, 0.5, 3.0)))
        val slot = 9f / 16f
        val centreCropHalfWidth = 900f * slot / 2f / 1600f
        val baseline = NormRect(0.5f - centreCropHalfWidth, 0f, 0.5f + centreCropHalfWidth, 1f)
        assertFalse("baseline centre crop must miss the blob", baseline.contains(0.8f, 0.5f))

        val crop = SmartCropSolver.solve(p, slot, 0f)
        assertTrue("crop $crop contains blob centre", crop.contains(0.8f, 0.5f))
        assertEquals(slot, crop.aspectIn(1600, 900), 1e-3f)
    }

    @Test
    fun uniformSaliency_givesTheLargestCentredCrop() {
        val crop = SmartCropSolver.solve(photo(1600, 900), 1f, 0f)
        assertEquals(NormRect(350f / 1600f, 0f, 1250f / 1600f, 1f).left, crop.left, 1e-4f)
        assertEquals(1f, crop.height, 1e-5f)
        assertEquals(0.5f, (crop.left + crop.right) / 2f, 1e-4f)
    }

    @Test
    fun faceAtTheOppositeSideFromSaliency_isNeverCut() {
        val f = face(0.03f, 0.30f, 0.13f, 0.50f)
        val p = photo(1600, 900, saliency(1600, 900, Triple(0.85, 0.5, 2.0)), faces = listOf(f))
        val crop = SmartCropSolver.solve(p, 9f / 16f, 0f)
        assertTrue("crop $crop holds ${expanded(f)}", visibleInside(expanded(f), crop, 1600, 900, 0f))
    }

    /** Brute-force: does any crop over the solver's scales (1 → 0.7) and a fine centre grid show [box] whole? */
    private fun feasible(box: NormRect, width: Int, height: Int, slot: Float, rotation: Float): Boolean {
        val frame = CropFrame(width.toDouble(), height.toDouble(), rotation)
        val maxH = frame.maxCropHeight(slot.toDouble())
        for (k in 0 until 6) {
            val ch = maxH * (1.0 - k * 0.06)
            val cw = ch * slot
            val xs = frame.centreRangeX(cw, ch)
            val ys = frame.centreRangeY(cw, ch)
            for (j in 0..120) for (i in 0..120) {
                val cx = xs.start + (xs.endInclusive - xs.start) * i / 120
                val cy = ys.start + (ys.endInclusive - ys.start) * j / 120
                if (visibleInside(box, frame.toNorm(cx, cy, cw, ch), width, height, rotation)) return true
            }
        }
        return false
    }

    @Test
    fun feasibleFaces_areNeverCut_acrossRandomScenesAndRotations() {
        val random = Random(1234)
        var checked = 0
        repeat(40) { case ->
            val width = 800 + random.nextInt(1600)
            val height = 800 + random.nextInt(1600)
            val size = 0.05f + random.nextFloat() * 0.15f
            val left = random.nextFloat() * (1f - size)
            val top = random.nextFloat() * (1f - size)
            val f = face(left, top, left + size, top + size)
            val blob = Triple(random.nextDouble(), random.nextDouble(), 2.0 + random.nextDouble() * 4)
            val rotation = (random.nextFloat() * 2f - 1f) * 4f
            val slot = listOf(9f / 16f, 0.75f, 1f, 4f / 3f, 2.35f)[random.nextInt(5)]
            val p = photo(width, height, saliency(width, height, blob), faces = listOf(f), tilt = rotation)
            val crop = SmartCropSolver.solve(p, slot, rotation)
            val label = "case $case ${width}x$height slot=$slot rot=$rotation face=${f.box} crop=$crop"
            assertTrue(label, rotatedCropInsideImage(crop, width, height, rotation, tolPx = 0.05))
            if (feasible(expanded(f), width, height, slot, rotation)) {
                checked++
                assertTrue(label, visibleInside(expanded(f), crop, width, height, rotation, tolPx = 0.05))
            }
        }
        assertTrue("most random scenes must be feasible to mean anything, got $checked", checked >= 30)
    }

    @Test
    fun infeasibleFaces_keepTheLargerFaceArea() {
        // Two faces at opposite ends of a panorama cannot share a portrait crop; the bigger one wins.
        val small = face(0.02f, 0.40f, 0.06f, 0.50f)
        val big = face(0.85f, 0.30f, 0.97f, 0.60f)
        val p = photo(3000, 1000, saliency(3000, 1000, Triple(0.04, 0.45, 2.0)), faces = listOf(small, big))
        val crop = SmartCropSolver.solve(p, 9f / 16f, 0f)
        assertTrue("crop $crop holds the big face", visibleInside(expanded(big), crop, 3000, 1000, 0f))
    }

    @Test
    fun rotatedSolve_staysInsideTheRotatedImage() {
        val p = photo(1200, 1600, saliency(1200, 1600, Triple(0.1, 0.1, 3.0)), tilt = 5f)
        for (rotation in listOf(-5f, -2.5f, 0f, 3f, 5f)) {
            for (slot in listOf(9f / 16f, 1f, 2.35f)) {
                val crop = SmartCropSolver.solve(p, slot, rotation)
                assertTrue("rot=$rotation slot=$slot crop=$crop", rotatedCropInsideImage(crop, 1200, 1600, rotation))
                assertEquals(slot, crop.aspectIn(1200, 1600), 1e-3f)
            }
        }
    }

    @Test
    fun solve_isDeterministic() {
        val f = face(0.4f, 0.2f, 0.5f, 0.3f)
        val p = photo(2000, 1500, saliency(2000, 1500, Triple(0.3, 0.6, 4.0), Triple(0.7, 0.2, 2.0)), listOf(f))
        assertEquals(SmartCropSolver.solve(p, 0.8f, 2f), SmartCropSolver.solve(p, 0.8f, 2f))
    }

    @Test
    fun invalidSlotAspect_throws() {
        assertThrows(IllegalArgumentException::class.java) { SmartCropSolver.solve(photo(100, 100), 0f, 0f) }
        assertThrows(IllegalArgumentException::class.java) { SmartCropSolver.solve(photo(100, 100), Float.NaN, 0f) }
    }

    @Test
    fun saliencyIntegral_integratesFractionalRectangles() {
        val p = photo(1600, 900)
        val integral = SaliencyIntegral.of(p.saliency, 1600, 900)
        assertEquals(256.0 * 144.0, integral.total, 1e-6)
        assertEquals(integral.total / 2, integral.sum(0.0, 0.0, 800.0, 900.0), 1e-6)
        assertEquals(integral.total * 0.1 * 0.3, integral.sum(160.0, 270.0, 320.0, 540.0), 1e-6)
        assertEquals(integral.total, integral.sum(-50.0, -50.0, 5000.0, 5000.0), 1e-6)
    }
}
