package com.akane.akastudio.feature.studio.ui.canvas

import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.composer.api.CellOverride
import com.akane.akastudio.feature.studio.mvi.StudioIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanvasActionsTest {

    private val ids = listOf("a", "b", "c")

    private fun available(selected: Int?, editable: Boolean = true, overrides: Map<String, CellOverride> = emptyMap()) =
        CanvasActions.available(ids.size, selected, editable, ids, overrides)

    private fun perform(action: CanvasAction, selected: Int?, overrides: Map<String, CellOverride> = emptyMap()) =
        CanvasActions.perform(action, ids.size, selected, ids, overrides)

    @Test
    fun `no cells offers nothing`() {
        assertEquals(emptyList<CanvasAction>(), CanvasActions.available(0, null, true, emptyList(), emptyMap()))
        assertNull(CanvasActions.perform(CanvasAction.SELECT_NEXT, 0, null, emptyList(), emptyMap()))
    }

    @Test
    fun `a locked studio offers only selection`() {
        val overrides = mapOf("a" to CellOverride(zoom = 2f))
        assertEquals(listOf(CanvasAction.SELECT_NEXT), available(selected = 0, editable = false, overrides = overrides))
    }

    @Test
    fun `an unedited cell offers zoom in but not zoom out or reset`() {
        assertEquals(
            listOf(CanvasAction.SELECT_NEXT, CanvasAction.SWAP_NEXT, CanvasAction.ZOOM_IN),
            available(selected = 1),
        )
    }

    @Test
    fun `a zoomed cell offers every action`() {
        assertEquals(
            listOf(
                CanvasAction.SELECT_NEXT,
                CanvasAction.SWAP_NEXT,
                CanvasAction.ZOOM_IN,
                CanvasAction.ZOOM_OUT,
                CanvasAction.RESET_CROP,
            ),
            available(selected = 1, overrides = mapOf("b" to CellOverride(zoom = 2f))),
        )
    }

    @Test
    fun `a fully zoomed cell offers no further zoom in`() {
        val actions = available(selected = 0, overrides = mapOf("a" to CellOverride(zoom = CellGestureMath.MAX_ZOOM)))
        assertEquals(
            listOf(CanvasAction.SELECT_NEXT, CanvasAction.SWAP_NEXT, CanvasAction.ZOOM_OUT, CanvasAction.RESET_CROP),
            actions,
        )
    }

    @Test
    fun `a single cell offers no swap`() {
        assertEquals(
            listOf(CanvasAction.SELECT_NEXT, CanvasAction.ZOOM_IN),
            CanvasActions.available(1, null, true, listOf("a"), emptyMap()),
        )
        assertNull(CanvasActions.perform(CanvasAction.SWAP_NEXT, 1, 0, listOf("a"), emptyMap()))
    }

    @Test
    fun `select next starts at the first cell and wraps`() {
        assertEquals(CanvasActionResult(null, 0), perform(CanvasAction.SELECT_NEXT, selected = null))
        assertEquals(CanvasActionResult(null, 2), perform(CanvasAction.SELECT_NEXT, selected = 1))
        assertEquals(CanvasActionResult(null, 0), perform(CanvasAction.SELECT_NEXT, selected = 2))
    }

    @Test
    fun `swap next moves the selected cell and follows it`() {
        val fromFirst = perform(CanvasAction.SWAP_NEXT, selected = null)
        assertEquals(CanvasActionResult(StudioIntent.SwapSlots(0, 1), 1), fromFirst)
        val wrapped = perform(CanvasAction.SWAP_NEXT, selected = 2)
        assertEquals(CanvasActionResult(StudioIntent.SwapSlots(2, 0), 0), wrapped)
    }

    @Test
    fun `zoom in steps the selected cell and keeps its pan`() {
        val base = CellOverride(zoom = 2f, panX = 0.3f, panY = -0.2f)
        val result = perform(CanvasAction.ZOOM_IN, selected = 1, overrides = mapOf("b" to base))
        val zoomed = CellOverride(zoom = 2.5f, panX = 0.3f, panY = -0.2f)
        assertEquals(CanvasActionResult(StudioIntent.SetCellOverride("b", zoomed), 1), result)
    }

    @Test
    fun `zoom out clamps at the solved crop`() {
        val result = perform(CanvasAction.ZOOM_OUT, selected = 0, overrides = mapOf("a" to CellOverride(zoom = 1.1f)))
        assertEquals(CanvasActionResult(StudioIntent.SetCellOverride("a", CellOverride(zoom = 1f)), 0), result)
    }

    @Test
    fun `zoom in without an override starts from the solved crop`() {
        val result = perform(CanvasAction.ZOOM_IN, selected = null)
        assertEquals(CanvasActionResult(StudioIntent.SetCellOverride("a", CellOverride(zoom = 1.25f)), 0), result)
    }

    @Test
    fun `zoom drops an explicit crop`() {
        val crop = NormRect(0.1f, 0.1f, 0.9f, 0.9f)
        val result = perform(CanvasAction.ZOOM_IN, selected = 0, overrides = mapOf("a" to CellOverride(crop = crop)))
        assertEquals(StudioIntent.SetCellOverride("a", CellOverride(zoom = 1.25f)), result?.intent)
    }

    @Test
    fun `reset crop clears the selected cell override`() {
        val result = perform(CanvasAction.RESET_CROP, selected = 2, overrides = mapOf("c" to CellOverride(zoom = 3f)))
        assertEquals(CanvasActionResult(StudioIntent.SetCellOverride("c", null), 2), result)
    }

    @Test
    fun `a stale selection falls back to the first cell`() {
        val result = perform(CanvasAction.RESET_CROP, selected = 7)
        assertEquals(CanvasActionResult(StudioIntent.SetCellOverride("a", null), 0), result)
    }
}
