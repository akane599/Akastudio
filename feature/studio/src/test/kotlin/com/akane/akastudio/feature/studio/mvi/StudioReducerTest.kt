package com.akane.akastudio.feature.studio.mvi

import com.akane.akastudio.core.color.api.ImagePixels
import com.akane.akastudio.core.composer.api.CellOverride
import com.akane.akastudio.core.composer.api.StoryTemplate
import com.akane.akastudio.core.curator.api.CurationProgress
import com.akane.akastudio.core.curator.api.CurationResult
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

    @Test
    fun decided_records_the_ai_mode_and_a_forced_selection_keeps_it() {
        val decided = StudioReducer.onCuration(StudioState(), CurationProgress.Decided(result))
        assertEquals(StoryMode.COLLAGE_2, decided.aiRecommendedMode)
        val aiSelected = StudioReducer.withSelection(decided, result, listOf("a", "b"), candidates)
        assertTrue(aiSelected.isAiPick)

        // What CurationEngine.decide returns for a forced mode: the note now names the forced mode too.
        val forced = result.copy(
            chosen = result.alternatives.getValue(StoryMode.HERO),
            note = result.note.copy(chosenMode = StoryMode.HERO),
        )
        val userSelected = StudioReducer.withSelection(aiSelected, forced, listOf("a"), candidates)
        assertEquals(StoryMode.HERO, userSelected.activeMode)
        assertEquals(StoryMode.COLLAGE_2, userSelected.aiRecommendedMode)
        assertEquals(StoryMode.COLLAGE_2, userSelected.aiMode)
        assertFalse(userSelected.isAiPick)
    }

    @Test
    fun ai_pick_needs_a_curation_and_falls_back_to_its_choice_without_a_recorded_mode() {
        assertFalse(StudioState().isAiPick)
        assertNull(StudioState().aiMode)
        val legacy = selected()
        assertNull(legacy.aiRecommendedMode)
        assertEquals(StoryMode.COLLAGE_2, legacy.aiMode)
        assertTrue(legacy.isAiPick)
    }

    // region curator layout adoption

    /** [this] with the curator's layout ids per offered mode; the chosen option follows its mode's entry. */
    private fun CurationResult.withLayouts(vararg ids: Pair<StoryMode, String?>): CurationResult {
        val byMode = ids.toMap()
        val alternatives = alternatives.mapValues { (mode, option) ->
            if (mode in byMode) option.copy(layoutId = byMode[mode]) else option
        }
        return copy(chosen = alternatives.getValue(chosen.mode), alternatives = alternatives)
    }

    @Test
    fun decided_adopts_the_curators_layout() {
        val laid = result.withLayouts(StoryMode.COLLAGE_2 to StoryTemplate.DUO_FLOATING_GALLERY.name)
        val s = StudioReducer.decided(StudioState(), laid, candidates)
        assertEquals(StoryTemplate.DUO_FLOATING_GALLERY, s.template)
        assertEquals(StudioPhase.Preparing, s.phase)
    }

    @Test
    fun decided_without_a_layout_id_uses_the_first_template_for_the_count() {
        val s = StudioReducer.decided(StudioState(), result, candidates)
        assertEquals(StoryTemplate.DUO_CINEMATIC_SPLIT, s.template)
    }

    @Test
    fun decided_with_an_unknown_layout_id_falls_back_without_throwing() {
        val laid = result.withLayouts(StoryMode.COLLAGE_2 to "NOT_A_TEMPLATE")
        assertEquals(StoryTemplate.DUO_CINEMATIC_SPLIT, StudioReducer.decided(StudioState(), laid, candidates).template)
        // Names are matched exactly: a differently cased id is unknown too.
        val lower = result.withLayouts(StoryMode.COLLAGE_2 to "duo_floating_gallery")
        assertEquals(StoryTemplate.DUO_CINEMATIC_SPLIT, StudioReducer.decided(StudioState(), lower, candidates).template)
    }

    @Test
    fun a_layout_id_for_another_photo_count_is_ignored() {
        val laid = result.withLayouts(StoryMode.COLLAGE_2 to StoryTemplate.QUAD_GRID.name)
        assertEquals(StoryTemplate.DUO_CINEMATIC_SPLIT, StudioReducer.decided(StudioState(), laid, candidates).template)
        val option = laid.chosen
        assertEquals(StoryTemplate.DUO_ASYMMETRIC_EDITORIAL, StudioReducer.templateFor(option, StoryTemplate.DUO_ASYMMETRIC_EDITORIAL))
    }

    @Test
    fun a_previous_storys_template_does_not_leak_into_a_new_story() {
        val stale = StudioState(template = StoryTemplate.DUO_ASYMMETRIC_EDITORIAL)
        assertEquals(StoryTemplate.DUO_CINEMATIC_SPLIT, StudioReducer.decided(stale, result, candidates).template)
        val laid = result.withLayouts(StoryMode.COLLAGE_2 to StoryTemplate.DUO_FLOATING_GALLERY.name)
        assertEquals(StoryTemplate.DUO_FLOATING_GALLERY, StudioReducer.decided(stale, laid, candidates).template)
    }

    @Test
    fun a_forced_mode_adopts_the_offered_options_layout() {
        val laid = result.withLayouts(
            StoryMode.COLLAGE_2 to StoryTemplate.DUO_ASYMMETRIC_EDITORIAL.name,
            StoryMode.HERO to StoryTemplate.HERO_GALLERY_MATTE.name,
        )
        val ready = StudioReducer.decided(StudioState(), laid, candidates)
        val forced = laid.copy(chosen = laid.alternatives.getValue(StoryMode.HERO))
        val s = StudioReducer.withForcedMode(ready, forced, candidates)
        assertEquals(StoryMode.HERO, s.selection.mode)
        assertEquals(listOf("a"), s.selection.photoIds)
        assertEquals(StoryTemplate.HERO_GALLERY_MATTE, s.template)

        // Without a layout for the forced mode, the legacy choice applies.
        val unlaid = result.copy(chosen = result.alternatives.getValue(StoryMode.HERO))
        assertEquals(StoryTemplate.HERO_FULL_BLEED, StudioReducer.withForcedMode(ready, unlaid, candidates).template)
    }

    @Test
    fun a_photo_swap_keeps_the_template_even_when_the_curator_named_another() {
        val laid = result.withLayouts(StoryMode.COLLAGE_2 to StoryTemplate.DUO_ASYMMETRIC_EDITORIAL.name)
        val ready = StudioReducer.decided(StudioState(), laid, candidates)
        val userPicked = StudioReducer.reduce(ready, StudioIntent.SelectTemplate(StoryTemplate.DUO_FLOATING_GALLERY))
        val swapped = StudioReducer.withSelection(userPicked, laid, listOf("d", "b"), candidates)
        assertEquals(listOf("d", "b"), swapped.selection.photoIds)
        assertEquals(StoryTemplate.DUO_FLOATING_GALLERY, swapped.template)
    }

    // endregion
}
