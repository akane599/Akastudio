package com.akane.akastudio.feature.studio.di

import com.akane.akastudio.core.color.api.GradeParams
import com.akane.akastudio.core.composer.api.CanvasSize
import com.akane.akastudio.core.composer.api.CompositionRequest
import com.akane.akastudio.core.composer.api.LayoutStyle
import com.akane.akastudio.core.composer.api.StoryTemplate
import com.akane.akastudio.core.composer.compose.DefaultCollageComposer
import com.akane.akastudio.core.curator.api.StoryMode
import com.akane.akastudio.feature.studio.domain.TestData
import com.akane.akastudio.feature.studio.mvi.SelectionUi
import com.akane.akastudio.feature.studio.mvi.StudioPhase
import com.akane.akastudio.feature.studio.mvi.StudioState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanvasHitTestTest {

    private val composer = DefaultCollageComposer()
    private val ids = listOf("a", "b", "c")
    private val candidates = ids.map { TestData.candidate(it) }
    private val analyses = ids.map { TestData.analysis(it) }
    private val layout = LayoutStyle(gutterPx = 24f)

    /** The scene the studio would show: composed from the same candidates the state holds. */
    private val scene = composer.compose(
        CompositionRequest(
            template = StoryTemplate.TRIO_BENTO,
            style = layout,
            canvas = CanvasSize.STORY_1080,
            photos = candidates.map { TestData.composerPhoto(it.id).copy(imageKey = it.id) },
            grades = ids.associateWith { GradeParams.NEUTRAL },
        ),
    )

    private val ready = StudioState(
        phase = StudioPhase.Ready,
        curation = TestData.result(
            StoryMode.COLLAGE_3,
            mapOf(StoryMode.COLLAGE_3 to ids),
            analyses,
            emptyList(),
        ),
        selection = SelectionUi(
            mode = StoryMode.COLLAGE_3,
            photoIds = persistentListOf("a", "b", "c"),
            slots = persistentMapOf(0 to candidates[0], 1 to candidates[1], 2 to candidates[2]),
        ),
        template = StoryTemplate.TRIO_BENTO,
        layout = layout,
        scene = scene,
    )

    @Test
    fun eachCellCentreHitsItsSlot() {
        scene.layers.forEachIndexed { slot, layer ->
            val x = (layer.dst.left + layer.dst.right) / 2f
            val y = (layer.dst.top + layer.dst.bottom) / 2f
            assertEquals(slot, CanvasHitTest.slotAt(ready, composer, x, y))
        }
    }

    @Test
    fun outsideTheCanvasHitsNothing() {
        assertNull(CanvasHitTest.slotAt(ready, composer, -10f, -10f))
        assertNull(CanvasHitTest.slotAt(ready, composer, 2000f, 4000f))
        assertNull(CanvasHitTest.slotAt(ready, composer, Float.NaN, 10f))
    }

    @Test
    fun noStoryMeansNoHit() {
        assertNull(CanvasHitTest.slotAt(StudioState(), composer, 540f, 960f))
        assertNull(CanvasHitTest.slotAt(ready.copy(scene = null), composer, 540f, 960f))
        assertNull(CanvasHitTest.slotAt(ready.copy(curation = null), composer, 540f, 960f))
        assertNull(CanvasHitTest.request(ready.copy(selection = ready.selection.copy(slots = persistentMapOf()))))
    }

    @Test
    fun requestUsesTheCandidateDimensionsTheSceneWasComposedWith() {
        val request = CanvasHitTest.request(ready)!!
        assertEquals(CanvasSize.STORY_1080, request.canvas)
        assertEquals(ids, request.photos.map { it.id })
        assertEquals(listOf(4000, 4000, 4000), request.photos.map { it.widthPx })
        assertEquals(listOf(3000, 3000, 3000), request.photos.map { it.heightPx })
        assertEquals(composer.layout(request).map { it.dst }, scene.layers.map { it.dst })
    }
}
