package com.akane.akastudio.core.composer.compose

import com.akane.akastudio.core.color.api.GradeParams
import com.akane.akastudio.core.color.api.MasterLook
import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.composer.api.CanvasSize
import com.akane.akastudio.core.composer.api.CellOverride
import com.akane.akastudio.core.composer.api.ComposerPhoto
import com.akane.akastudio.core.composer.api.CompositionRequest
import com.akane.akastudio.core.composer.api.LayoutStyle
import com.akane.akastudio.core.composer.api.StoryTemplate
import com.akane.akastudio.core.composer.crop.SmartCropSolver
import com.akane.akastudio.core.curator.api.FaceObservation
import com.akane.akastudio.core.curator.api.SaliencyMap
import com.akane.akastudio.core.ingestion.model.CaptureMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SolvedCropCacheTest {

    private var solves = 0
    private val countingSolver: CropSolver = { photo, aspect, rotation ->
        solves++
        SmartCropSolver.solve(photo, aspect, rotation)
    }

    private fun photo(index: Int, tilt: Float = 0f, faces: List<FaceObservation> = emptyList(), saliency: SaliencyMap? = null) =
        ComposerPhoto(
            id = "id-$index",
            imageKey = "key-$index",
            widthPx = 1600,
            heightPx = 1200,
            // A salient blob off-centre per photo, so every slot's crop is non-trivial.
            saliency = saliency ?: SaliencyMap(64, 48, FloatArray(64 * 48) { i -> if (i % 64 in (index * 12)..(index * 12 + 10)) 1f else 0.05f }),
            faces = faces,
            palette = emptyList(),
            horizonTiltDegrees = tilt,
            metadata = CaptureMetadata.EMPTY,
        )

    private val photos = List(4) { photo(it, tilt = it * 0.75f) }

    private fun request(
        template: StoryTemplate = StoryTemplate.QUAD_MOSAIC,
        photos: List<ComposerPhoto> = this.photos.take(template.photoCount),
        grades: Map<String, GradeParams> = emptyMap(),
        overrides: Map<String, CellOverride> = emptyMap(),
        canvas: CanvasSize = CanvasSize.STORY_1080,
    ) = CompositionRequest(template, LayoutStyle(gutterPx = 16f), canvas, photos, grades, overrides)

    @Test
    fun gradeOnlyChange_doesNotSolveAgain_andKeepsTheScene() {
        val composer = DefaultCollageComposer(countingSolver)
        val first = composer.compose(request())
        assertEquals(4, solves)

        val graded = mapOf("id-0" to GradeParams(look = MasterLook.PORTRA_400_GOLDEN), "id-2" to GradeParams(contrast = 0.4f))
        val second = composer.compose(request(grades = graded))
        assertEquals("a grade-only rebuild must not re-run the solver", 4, solves)
        assertEquals(first.layers.map { it.crop }, second.layers.map { it.crop })
        assertEquals(first.layers.map { it.dst }, second.layers.map { it.dst })

        // A pan/zoom override is applied on top of the cached solve, so it does not search again either.
        composer.compose(request(overrides = mapOf("id-1" to CellOverride(zoom = 1.6f, panX = 0.2f))))
        assertEquals(4, solves)
    }

    @Test
    fun cachedCrops_equalFreshOnes_acrossTemplatesAndCanvases() {
        val cached = DefaultCollageComposer()
        val warm = StoryTemplate.entries.flatMap { t -> CanvasSize.entries.map { c -> request(t, canvas = c) } }
        warm.forEach { cached.layout(it) }
        val overrides = mapOf("id-0" to CellOverride(zoom = 1.3f, panY = -0.1f))
        for (req in warm + warm.map { it.copy(overrides = overrides) }) {
            assertEquals("${req.template} ${req.canvas}", DefaultCollageComposer().layout(req), cached.layout(req))
        }
    }

    @Test
    fun anySolverInputChange_solvesAgain() {
        val composer = DefaultCollageComposer(countingSolver)
        composer.layout(request(StoryTemplate.HERO_FULL_BLEED))
        assertEquals(1, solves)

        composer.layout(request(StoryTemplate.HERO_GALLERY_MATTE))           // slot aspect
        assertEquals(2, solves)
        composer.layout(request(StoryTemplate.HERO_FULL_BLEED, photos = listOf(photo(0, tilt = 2f))))   // rotation
        assertEquals(3, solves)
        val face = FaceObservation(NormRect(0.6f, 0.2f, 0.8f, 0.5f), 1f, 1f, 1f)
        composer.layout(request(StoryTemplate.HERO_FULL_BLEED, photos = listOf(photo(0, faces = listOf(face)))))   // faces
        assertEquals(4, solves)
        val newSaliency = SaliencyMap(64, 48, photos[0].saliency.values.copyOf())
        composer.layout(request(StoryTemplate.HERO_FULL_BLEED, photos = listOf(photo(0, saliency = newSaliency))))  // saliency
        assertEquals(5, solves)
        composer.layout(request(StoryTemplate.HERO_FULL_BLEED, photos = listOf(photo(0).copy(id = "other"))))  // photo id
        assertEquals(6, solves)

        composer.layout(request(StoryTemplate.HERO_FULL_BLEED))   // the original inputs are still cached
        assertEquals(6, solves)
    }

    @Test
    fun capacity_evictsTheLeastRecentlyUsedEntry() {
        val cache = SolvedCropCache(countingSolver, capacity = 2)
        val p = photos[0]
        cache.solve(p, 1f, 0f)
        cache.solve(p, 2f, 0f)
        cache.solve(p, 1f, 0f)          // hit; aspect 2 is now the eldest
        assertEquals(2, solves)
        cache.solve(p, 0.5f, 0f)        // evicts aspect 2
        cache.solve(p, 1f, 0f)          // still cached
        assertEquals(3, solves)
        cache.solve(p, 2f, 0f)          // evicted, solves again
        assertEquals(4, solves)
        assertThrows(IllegalArgumentException::class.java) { SolvedCropCache(countingSolver, capacity = 0) }
    }

    @Test
    fun solverFailure_isNotCached() {
        val composer = DefaultCollageComposer(countingSolver)
        assertThrows(IllegalArgumentException::class.java) { SolvedCropCache(countingSolver).solve(photos[0], Float.NaN, 0f) }
        assertEquals(1, solves)
        composer.layout(request(StoryTemplate.HERO_FULL_BLEED))
        assertEquals(2, solves)
    }
}
