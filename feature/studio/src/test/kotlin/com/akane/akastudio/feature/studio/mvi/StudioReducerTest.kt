package com.akane.akastudio.feature.studio.mvi

import com.akane.akastudio.core.color.api.ImagePixels
import com.akane.akastudio.core.composer.api.CellOverride
import com.akane.akastudio.core.composer.api.StoryTemplate
import com.akane.akastudio.core.curator.api.CurationProgress
import com.akane.akastudio.core.curator.api.RejectReason
import com.akane.akastudio.core.curator.api.SceneCluster
import com.akane.akastudio.core.curator.api.StoryMode
import com.akane.akastudio.feature.studio.domain.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioReducerTest {

    private val candidates = listOf(
        TestData.candidate("a"),
        TestData.candidate("b", gainMap = true),
        TestData.candidate("c"),
        TestData.candidate("d"),
        TestData.candidate("e"),
    ).associateBy { it.id }
    private val result = TestData.result(
        chosenMode = StoryMode.COLLAGE_2,
        options = mapOf(StoryMode.COLLAGE_2 to listOf("a", "b"), StoryMode.HERO to listOf("a")),
        analyses = listOf(
            TestData.analysis("a", quality = 0.9f),
            TestData.analysis("b", quality = 0.8f),
            TestData.analysis("c", quality = 0.4f),
            TestData.analysis("d", quality = 0.7f),
            TestData.analysis("e", rejected = RejectReason.BLURRY),
        ),
        clusters = listOf(SceneCluster(0, listOf("a", "c", "d", "e"), "a"), SceneCluster(1, listOf("b"), "b")),
    )

    private fun selected() = StudioReducer.withSelection(StudioState(), result, listOf("a", "b"), candidates)

    @Test
    fun withSelection_sets_first_template_alternatives_telemetry_and_hdr_default() {
        val s = selected()

        assertEquals(StoryTemplate.DUO_CINEMATIC_SPLIT, s.template)
        assertEquals(StoryMode.COLLAGE_2, s.selection.mode)
        assertEquals(listOf("a", "b"), s.selection.photoIds)
        assertEquals("b", s.selection.slots.getValue(1).id)
        assertEquals(listOf("d", "c"), s.selection.alternatives.getValue(0))
        assertEquals(emptyList<String>(), s.selection.alternatives.getValue(1))
        assertEquals(StoryMode.COLLAGE_2, s.telemetry.finalMode)
        assertEquals(87, s.telemetry.harmonyPercent)
        assertTrue(s.export.hdrBoost)
    }

    @Test
    fun withSelection_keeps_a_fitting_template_and_respects_a_user_hdr_choice() {
        val start = StudioState(
            template = StoryTemplate.DUO_FLOATING_GALLERY,
            export = ExportUi(hdrBoost = false, hdrBoostUserSet = true),
        )
        val s = StudioReducer.withSelection(start, result, listOf("a", "b"), candidates)
        assertEquals(StoryTemplate.DUO_FLOATING_GALLERY, s.template)
        assertFalse(s.export.hdrBoost)
    }

    @Test
    fun layout_and_grade_values_are_clamped_and_non_finite_values_ignored() {
        val s = selected()
        assertEquals(48f, StudioReducer.reduce(s, StudioIntent.SetGutter(100f)).layout.gutterPx)
        assertEquals(0f, StudioReducer.reduce(s, StudioIntent.SetCornerRadius(-3f)).layout.cornerRadiusPx)
        assertEquals(1f, StudioReducer.reduce(s, StudioIntent.AdjustGrade(GradeField.CONTRAST, 4f)).grade.contrast)
        assertEquals(0f, StudioReducer.reduce(s, StudioIntent.AdjustGrade(GradeField.GRAIN, -1f)).grade.grain)
        assertSame(s, StudioReducer.reduce(s, StudioIntent.AdjustGrade(GradeField.TINT, Float.NaN)))
        assertSame(s, StudioReducer.reduce(s, StudioIntent.SetCompare(Float.POSITIVE_INFINITY)))
        assertEquals(1f, StudioReducer.reduce(s, StudioIntent.SetCompare(1.5f)).compare)
        assertNull(StudioReducer.reduce(s.copy(compare = 0.5f), StudioIntent.SetCompare(null)).compare)
    }

    @Test
    fun template_must_match_the_mode_count() {
        val s = selected()
        assertSame(s, StudioReducer.reduce(s, StudioIntent.SelectTemplate(StoryTemplate.QUAD_GRID)))
        assertEquals(
            StoryTemplate.DUO_ASYMMETRIC_EDITORIAL,
            StudioReducer.reduce(s, StudioIntent.SelectTemplate(StoryTemplate.DUO_ASYMMETRIC_EDITORIAL)).template,
        )
    }

    @Test
    fun scope_off_clears_pixels_and_overrides_set_and_clear() {
        val withPixels = selected().copy(scope = ScopeMode.HISTOGRAM, scopePixels = ImagePixels(1, 1, IntArray(1)))
        assertNull(StudioReducer.reduce(withPixels, StudioIntent.SetScope(ScopeMode.OFF)).scopePixels)

        val override = CellOverride(zoom = 1.5f)
        val set = StudioReducer.reduce(selected(), StudioIntent.SetCellOverride("a", override))
        assertEquals(override, set.overrides["a"])
        assertTrue(StudioReducer.reduce(set, StudioIntent.SetCellOverride("a", null)).overrides.isEmpty())
    }

    @Test
    fun swapSlots_reorders_ids_slots_and_alternatives() {
        val s = StudioReducer.reduce(selected(), StudioIntent.SwapSlots(0, 1))
        assertEquals(listOf("b", "a"), s.selection.photoIds)
        assertEquals("b", s.selection.slots.getValue(0).id)
        assertEquals(listOf("d", "c"), s.selection.alternatives.getValue(1))
        assertTrue(StudioReducer.sceneInputsChanged(selected(), s))
        val unchanged = selected()
        assertSame(unchanged, StudioReducer.reduce(unchanged, StudioIntent.SwapSlots(0, 5)))
    }

    @Test
    fun curation_progress_maps_to_telemetry_steps() {
        val analyzing = StudioReducer.onCuration(StudioState(), CurationProgress.Analyzing(3, 9))
        assertEquals(TelemetryStep.ANALYZING, analyzing.telemetry.step)
        assertEquals(3, analyzing.telemetry.analyzed)
        assertEquals(9, analyzing.telemetry.analyzeTotal)
        val clustered = StudioReducer.onCuration(analyzing, CurationProgress.Clustered(4, 2))
        assertEquals(TelemetryStep.DECIDING, clustered.telemetry.step)
        assertEquals(4, clustered.telemetry.clusters)
        assertEquals(2, clustered.telemetry.rejected)
    }
}
