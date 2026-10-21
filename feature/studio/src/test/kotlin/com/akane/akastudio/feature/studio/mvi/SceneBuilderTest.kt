package com.akane.akastudio.feature.studio.mvi

import com.akane.akastudio.core.color.api.GradeParams
import com.akane.akastudio.core.color.api.HarmonizationParams
import com.akane.akastudio.core.color.api.MasterLook
import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.composer.api.BackgroundStyle
import com.akane.akastudio.core.composer.api.CanvasSize
import com.akane.akastudio.core.composer.api.CellOverride
import com.akane.akastudio.core.composer.api.LayoutStyle
import com.akane.akastudio.core.composer.api.StoryTemplate
import com.akane.akastudio.core.curator.api.SceneCluster
import com.akane.akastudio.core.curator.api.StoryMode
import com.akane.akastudio.feature.studio.domain.FakeComposer
import com.akane.akastudio.feature.studio.domain.FakeHarmonizer
import com.akane.akastudio.feature.studio.domain.TestData
import kotlinx.collections.immutable.persistentMapOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SceneBuilderTest {

    private val harmonizer = FakeHarmonizer()
    private val composer = FakeComposer()
    private val curation = TestData.result(
        chosenMode = StoryMode.COLLAGE_3,
        options = mapOf(StoryMode.COLLAGE_3 to listOf("a", "b", "c")),
        analyses = listOf(
            TestData.analysis("a", meanL = 0.3f),
            TestData.analysis("b", meanL = 0.5f),
            TestData.analysis("c", meanL = 0.7f),
        ),
        clusters = listOf(SceneCluster(0, listOf("a", "b", "c"), "a")),
    )
    private val grade = GradeUi(
        look = MasterLook.CINESTILL_800T_NIGHT,
        intensity = 0.7f,
        harmonizationStrength = 0.4f,
        temperature = 0.2f,
        tint = -0.1f,
        contrast = 0.3f,
        saturation = -0.4f,
        halation = 0.5f,
        grain = 0.25f,
        skinProtection = 0.9f,
    )

    private fun collageState() = StudioState(
        curation = curation,
        template = StoryTemplate.TRIO_BENTO,
        layout = LayoutStyle(gutterPx = 20f, cornerRadiusPx = 8f, background = BackgroundStyle.OffWhite),
        grade = grade,
        overrides = persistentMapOf(
            "b" to CellOverride(crop = NormRect(0.1f, 0.1f, 0.9f, 0.9f)),
            "zz" to CellOverride(zoom = 2f),
        ),
    )

    @Test
    fun collage_plans_harmonization_in_slot_order_with_anchor_zero_and_state_strength() {
        val photos = listOf("c", "a", "b").map(TestData::composerPhoto)

        val scene = SceneBuilder.build(collageState(), photos, harmonizer, composer)

        val (stats, anchor, strength) = harmonizer.calls.single()
        assertEquals(listOf(0.7f, 0.3f, 0.5f), stats.map { it.meanL })
        assertEquals(0, anchor)
        assertEquals(0.4f, strength)
        assertEquals(
            listOf(
                FakeHarmonizer.expected(0, 0.7f, 0.4f),
                FakeHarmonizer.expected(1, 0.3f, 0.4f),
                FakeHarmonizer.expected(2, 0.5f, 0.4f),
            ),
            scene.layers.map { it.grade.harmonization },
        )
    }

    @Test
    fun collage_request_carries_global_grade_layout_canvas_and_only_current_overrides() {
        val photos = listOf("a", "b", "c").map(TestData::composerPhoto)

        val scene = SceneBuilder.build(collageState(), photos, harmonizer, composer, CanvasSize.STORY_4K)

        val request = composer.requests.single()
        assertEquals(StoryTemplate.TRIO_BENTO, request.template)
        assertEquals(LayoutStyle(gutterPx = 20f, cornerRadiusPx = 8f, background = BackgroundStyle.OffWhite), request.style)
        assertEquals(CanvasSize.STORY_4K, request.canvas)
        assertEquals(photos, request.photos)
        assertEquals(setOf("b"), request.overrides.keys)
        assertEquals(2160, scene.canvasWidth)
        val b = request.grades.getValue("b")
        assertEquals(
            GradeParams(
                look = MasterLook.CINESTILL_800T_NIGHT,
                lookIntensity = 0.7f,
                harmonization = FakeHarmonizer.expected(1, 0.5f, 0.4f),
                exposureEv = 0f,
                temperature = 0.2f,
                tint = -0.1f,
                contrast = 0.3f,
                saturation = -0.4f,
                halation = 0.5f,
                grain = 0.25f,
                skinProtection = 0.9f,
            ),
            b,
        )
    }

    @Test
    fun hero_uses_identity_harmonization_without_planning() {
        val state = StudioState(template = StoryTemplate.HERO_FULL_BLEED, grade = grade)

        val scene = SceneBuilder.build(state, listOf(TestData.composerPhoto("a")), harmonizer, composer)

        assertTrue(harmonizer.calls.isEmpty())
        assertEquals(HarmonizationParams.IDENTITY, scene.layers.single().grade.harmonization)
        assertEquals(MasterLook.CINESTILL_800T_NIGHT, scene.layers.single().grade.look)
        assertEquals(CanvasSize.STORY_1080.width, scene.canvasWidth)
    }

    @Test
    fun photo_count_must_match_the_template() {
        assertBuildFails(collageState(), listOf("a", "b").map(TestData::composerPhoto), "takes 3 photos")
    }

    @Test
    fun missing_template_or_analysis_is_an_error() {
        assertBuildFails(collageState().copy(template = null), listOf("a", "b", "c").map(TestData::composerPhoto), "No template")
        assertBuildFails(collageState(), listOf("a", "b", "x").map(TestData::composerPhoto), "No analysis for photo x")
    }

    @Test
    fun planner_returning_the_wrong_count_is_an_error() {
        harmonizer.wrongSize = true
        assertBuildFails(collageState(), listOf("a", "b", "c").map(TestData::composerPhoto), "Harmonizer returned 2")
    }

    private fun assertBuildFails(state: StudioState, photos: List<com.akane.akastudio.core.composer.api.ComposerPhoto>, message: String) {
        try {
            SceneBuilder.build(state, photos, harmonizer, composer)
            fail("expected failure containing '$message'")
        } catch (e: IllegalStateException) {
            assertTrue("'${e.message}' should contain '$message'", e.message!!.contains(message))
        }
    }
}
