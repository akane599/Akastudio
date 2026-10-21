package com.akane.akastudio.feature.studio.di

import com.akane.akastudio.core.composer.api.CanvasSize
import com.akane.akastudio.core.composer.api.CollageComposer
import com.akane.akastudio.core.composer.api.ComposerPhoto
import com.akane.akastudio.core.composer.api.CompositionRequest
import com.akane.akastudio.feature.studio.mvi.StudioState

/**
 * Canvas hit testing through [CollageComposer.hitTest] for the composition currently on screen. Cell geometry
 * depends only on the template, the layout style, the canvas and each photo's upright aspect, which the state holds
 * (the preparer gives the composer the same candidate dimensions), so no prepared photo is needed.
 */
internal object CanvasHitTest {

    /** The slot under ([xPx], [yPx]) in canvas pixels of the state's scene, or null (also when no story exists). */
    fun slotAt(state: StudioState, composer: CollageComposer, xPx: Float, yPx: Float): Int? {
        val request = request(state) ?: return null
        return try {
            composer.hitTest(request, xPx, yPx)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** The geometry-only [CompositionRequest] matching the state's scene, or null when there is no story. */
    fun request(state: StudioState): CompositionRequest? {
        val template = state.template ?: return null
        val scene = state.scene ?: return null
        val canvas = CanvasSize.entries.firstOrNull {
            it.width == scene.canvasWidth && it.height == scene.canvasHeight
        } ?: return null
        val analyses = state.curation?.analyses ?: return null
        val ids = state.selection.photoIds
        if (ids.size != template.photoCount) return null
        val photos = ids.mapIndexed { slot, id ->
            val candidate = state.selection.slots[slot]?.takeIf { it.id == id } ?: return null
            val analysis = analyses[id] ?: return null
            if (candidate.widthPx <= 0 || candidate.heightPx <= 0) return null
            ComposerPhoto(
                id = id,
                imageKey = id,
                widthPx = candidate.widthPx,
                heightPx = candidate.heightPx,
                saliency = analysis.saliency,
                faces = analysis.faces,
                palette = analysis.palette,
                horizonTiltDegrees = 0f,
                metadata = candidate.metadata,
            )
        }
        return CompositionRequest(
            template = template,
            style = state.layout,
            canvas = canvas,
            photos = photos,
            grades = emptyMap(),
            overrides = state.overrides.filterKeys { it in ids },
        )
    }
}
