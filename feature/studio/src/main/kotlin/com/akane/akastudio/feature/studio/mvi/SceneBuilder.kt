package com.akane.akastudio.feature.studio.mvi

import com.akane.akastudio.core.color.api.GradeParams
import com.akane.akastudio.core.color.api.HarmonizationParams
import com.akane.akastudio.core.color.api.RenderScene
import com.akane.akastudio.core.composer.api.CanvasSize
import com.akane.akastudio.core.composer.api.CollageComposer
import com.akane.akastudio.core.composer.api.ComposerPhoto
import com.akane.akastudio.core.composer.api.CompositionRequest
import com.akane.akastudio.feature.studio.domain.HarmonizationPlanner

/**
 * Pure scene construction: the state's global grade plus per-photo harmonization, composed by the
 * [CollageComposer]. Cheap enough to run synchronously on every grade or layout change.
 */
object SceneBuilder {

    /**
     * Builds the scene for [state] with [photos] in slot order at [canvas].
     *
     * A single photo (hero) gets [HarmonizationParams.IDENTITY]. A collage gets
     * `harmonizer.plan(labStats in slot order, anchorIndex = 0, strength = grade.harmonizationStrength)`.
     *
     * @throws IllegalStateException when no template is set, the photo count does not match it, a collage photo has
     *   no analysis in `state.curation`, or the planner returns the wrong number of params.
     */
    fun build(
        state: StudioState,
        photos: List<ComposerPhoto>,
        harmonizer: HarmonizationPlanner,
        composer: CollageComposer,
        canvas: CanvasSize = CanvasSize.STORY_1080,
    ): RenderScene = composer.compose(request(state, photos, harmonizer, canvas))

    /** The [CompositionRequest] [build] hands to the composer. */
    fun request(
        state: StudioState,
        photos: List<ComposerPhoto>,
        harmonizer: HarmonizationPlanner,
        canvas: CanvasSize = CanvasSize.STORY_1080,
    ): CompositionRequest {
        val template = checkNotNull(state.template) { "No template selected" }
        check(photos.size == template.photoCount) {
            "Template $template takes ${template.photoCount} photos, got ${photos.size}"
        }
        val harmonization = harmonizationFor(state, photos, harmonizer)
        val grades = photos.indices.associate { i -> photos[i].id to gradeParams(state.grade, harmonization[i]) }
        val ids = photos.mapTo(HashSet()) { it.id }
        return CompositionRequest(
            template = template,
            style = state.layout,
            canvas = canvas,
            photos = photos,
            grades = grades,
            overrides = state.overrides.filterKeys { it in ids },
        )
    }

    /** Maps the global [grade] to renderer [GradeParams] carrying [harmonization]. Exposure stays neutral. */
    fun gradeParams(grade: GradeUi, harmonization: HarmonizationParams): GradeParams = GradeParams(
        look = grade.look,
        lookIntensity = grade.intensity,
        harmonization = harmonization,
        exposureEv = 0f,
        temperature = grade.temperature,
        tint = grade.tint,
        contrast = grade.contrast,
        saturation = grade.saturation,
        halation = grade.halation,
        grain = grade.grain,
        skinProtection = grade.skinProtection,
    )

    private fun harmonizationFor(
        state: StudioState,
        photos: List<ComposerPhoto>,
        harmonizer: HarmonizationPlanner,
    ): List<HarmonizationParams> {
        if (photos.size == 1) return listOf(HarmonizationParams.IDENTITY)
        val analyses = checkNotNull(state.curation) { "A collage needs a curation result" }.analyses
        val stats = photos.map { photo ->
            checkNotNull(analyses[photo.id]) { "No analysis for photo ${photo.id}" }.labStats
        }
        val planned = harmonizer.plan(stats, 0, state.grade.harmonizationStrength)
        check(planned.size == photos.size) {
            "Harmonizer returned ${planned.size} params for ${photos.size} photos"
        }
        return planned
    }
}
