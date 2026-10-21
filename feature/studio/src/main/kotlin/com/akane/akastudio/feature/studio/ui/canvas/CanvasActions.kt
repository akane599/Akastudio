package com.akane.akastudio.feature.studio.ui.canvas

import com.akane.akastudio.core.composer.api.CellOverride
import com.akane.akastudio.feature.studio.mvi.StudioIntent

/** A canvas accessibility action: the non-gesture route to selection, swapping and crop zoom. */
internal enum class CanvasAction { SELECT_NEXT, SWAP_NEXT, ZOOM_IN, ZOOM_OUT, RESET_CROP }

/** What performing a [CanvasAction] does: the [intent] to send, if any, and the slot to [select] afterwards. */
internal data class CanvasActionResult(val intent: StudioIntent?, val select: Int)

/**
 * Pure logic behind the canvas's custom accessibility actions, so TalkBack and switch-access users can do what the
 * tap, long-press drag, pinch and double-tap gestures do.
 *
 * Swap and crop actions work on the selected cell, or the first cell when none is selected, and select it, so the
 * canvas announces which cell changed. They are offered only while the studio is editable; a zoom step is offered only
 * while it can still change the zoom, and Reset crop only while the cell has an override.
 */
internal object CanvasActions {
    /** The zoom factor of one Zoom in step; Zoom out divides by it. */
    const val ZOOM_STEP = 1.25f

    /** The actions to offer for [cellCount] cells with [selected] selected, in announcement order. */
    fun available(
        cellCount: Int,
        selected: Int?,
        editable: Boolean,
        photoIds: List<String>,
        overrides: Map<String, CellOverride>,
    ): List<CanvasAction> {
        if (cellCount <= 0) return emptyList()
        val actions = mutableListOf(CanvasAction.SELECT_NEXT)
        if (!editable) return actions
        if (cellCount >= 2) actions += CanvasAction.SWAP_NEXT
        val photoId = photoIds.getOrNull(target(selected, cellCount)) ?: return actions
        val override = overrides[photoId]
        val zoom = CellGestureMath.clampZoom(override?.zoom ?: CellGestureMath.MIN_ZOOM)
        if (zoom < CellGestureMath.MAX_ZOOM) actions += CanvasAction.ZOOM_IN
        if (zoom > CellGestureMath.MIN_ZOOM) actions += CanvasAction.ZOOM_OUT
        if (override != null) actions += CanvasAction.RESET_CROP
        return actions
    }

    /** The effect of [action] for [cellCount] cells with [selected] selected; null when it has nothing to act on. */
    fun perform(
        action: CanvasAction,
        cellCount: Int,
        selected: Int?,
        photoIds: List<String>,
        overrides: Map<String, CellOverride>,
    ): CanvasActionResult? {
        if (cellCount <= 0) return null
        val slot = target(selected, cellCount)
        return when (action) {
            CanvasAction.SELECT_NEXT -> CanvasActionResult(null, if (selected == null) 0 else (slot + 1) % cellCount)
            CanvasAction.SWAP_NEXT -> {
                if (cellCount < 2) return null
                val to = (slot + 1) % cellCount
                CanvasActionResult(StudioIntent.SwapSlots(slot, to), to)
            }
            CanvasAction.ZOOM_IN, CanvasAction.ZOOM_OUT -> {
                val photoId = photoIds.getOrNull(slot) ?: return null
                val factor = if (action == CanvasAction.ZOOM_IN) ZOOM_STEP else 1f / ZOOM_STEP
                // No pan, so the cell size does not matter.
                val zoomed = CellGestureMath.transform(overrides[photoId] ?: CellOverride(), factor, 0f, 0f, 0f, 0f)
                CanvasActionResult(StudioIntent.SetCellOverride(photoId, zoomed), slot)
            }
            CanvasAction.RESET_CROP -> {
                val photoId = photoIds.getOrNull(slot) ?: return null
                CanvasActionResult(StudioIntent.SetCellOverride(photoId, null), slot)
            }
        }
    }

    /** The cell the swap and crop actions act on: the selected one, else the first. */
    private fun target(selected: Int?, cellCount: Int): Int = selected?.takeIf { it in 0 until cellCount } ?: 0
}
