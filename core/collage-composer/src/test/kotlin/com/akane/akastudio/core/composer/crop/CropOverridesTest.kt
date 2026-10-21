package com.akane.akastudio.core.composer.crop

import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.composer.api.CellOverride
import com.akane.akastudio.core.composer.crop.CropFixtures.rotatedCropInsideImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CropOverridesTest {

    private val solved = NormRect(0.25f, 0.1f, 0.75f, 0.9f)

    private fun assertRect(expected: NormRect, actual: NormRect, tol: Float = 1e-5f) {
        assertEquals("left of $actual", expected.left, actual.left, tol)
        assertEquals("top of $actual", expected.top, actual.top, tol)
        assertEquals("right of $actual", expected.right, actual.right, tol)
        assertEquals("bottom of $actual", expected.bottom, actual.bottom, tol)
    }

    @Test
    fun noOverride_keepsTheSolvedCrop() {
        assertSame(solved, CropOverrides.apply(1000, 1000, solved, null, 3f))
    }

    @Test
    fun explicitCrop_winsAndIsClampedToTheImage() {
        val out = CropOverrides.apply(1000, 1000, solved, CellOverride(crop = NormRect(-0.5f, -0.2f, 1.4f, 0.9f)), 0f)
        assertRect(NormRect(0f, 0f, 1f, 0.9f), out)
        val reversed = CropOverrides.apply(1000, 1000, solved, CellOverride(crop = NormRect(0.6f, 0.7f, 0.2f, 0.3f)), 0f)
        assertRect(NormRect(0.2f, 0.3f, 0.6f, 0.7f), reversed)
    }

    @Test
    fun explicitCrop_isShrunkAndMovedIntoTheRotatedValidRegion() {
        val out = CropOverrides.apply(1600, 1200, solved, CellOverride(crop = NormRect.FULL), 5f)
        assertTrue("$out fits the rotated source", rotatedCropInsideImage(out, 1600, 1200, 5f))
        assertEquals("aspect kept", 1600f / 1200f, (out.width * 1600) / (out.height * 1200), 1e-3f)
        val corner = CropOverrides.apply(1600, 1200, solved, CellOverride(crop = NormRect(0f, 0f, 0.3f, 0.3f)), -4f)
        assertTrue("$corner fits the rotated source", rotatedCropInsideImage(corner, 1600, 1200, -4f))
    }

    @Test
    fun degenerateOrNonFiniteExplicitCrop_fallsBackToZoomAndPan() {
        val zeroWidth = CropOverrides.apply(1000, 1000, solved, CellOverride(crop = NormRect(0.5f, 0.1f, 0.5f, 0.9f)), 0f)
        assertRect(solved, zeroWidth)
        val nan = CropOverrides.apply(1000, 1000, solved, CellOverride(crop = NormRect(Float.NaN, 0f, 1f, 1f), zoom = 2f), 0f)
        assertRect(NormRect(0.375f, 0.3f, 0.625f, 0.7f), nan)
    }

    @Test
    fun zoomBelowOne_isIgnored_andZoomShrinksAboutTheCentre() {
        assertRect(solved, CropOverrides.apply(1000, 1000, solved, CellOverride(zoom = 0.5f), 0f))
        assertRect(solved, CropOverrides.apply(1000, 1000, solved, CellOverride(zoom = Float.NaN), 0f))
        assertRect(NormRect(0.375f, 0.3f, 0.625f, 0.7f), CropOverrides.apply(1000, 1000, solved, CellOverride(zoom = 2f), 0f))
    }

    @Test
    fun pan_movesByTheFractionOfAvailableSlack_andClampsBeyondOne() {
        val full = CropOverrides.apply(1000, 1000, solved, CellOverride(panX = 1f, panY = -1f), 0f)
        assertRect(NormRect(0.5f, 0f, 1f, 0.8f), full)
        assertRect(full, CropOverrides.apply(1000, 1000, solved, CellOverride(panX = 5f, panY = -9f), 0f))
        val half = CropOverrides.apply(1000, 1000, solved, CellOverride(panX = -0.5f), 0f)
        assertRect(NormRect(0.125f, 0.1f, 0.625f, 0.9f), half)
        assertRect(solved, CropOverrides.apply(1000, 1000, solved, CellOverride(panX = Float.NaN), 0f))
    }

    @Test
    fun zoomAndPan_underRotation_stayInsideTheRotatedImage() {
        val p = CropFixtures.photo(1600, 1200, tilt = 4f)
        val base = SmartCropSolver.solve(p, 0.8f, 4f)
        for (panX in listOf(-1f, -0.3f, 0f, 0.7f, 1f)) for (panY in listOf(-1f, 0f, 1f)) for (zoom in listOf(1f, 1.5f, 3f)) {
            val out = CropOverrides.apply(1600, 1200, base, CellOverride(zoom = zoom, panX = panX, panY = panY), 4f)
            assertTrue("zoom=$zoom pan=$panX,$panY -> $out", rotatedCropInsideImage(out, 1600, 1200, 4f))
            assertEquals(base.width / zoom, out.width, 1e-4f)
        }
    }
}
