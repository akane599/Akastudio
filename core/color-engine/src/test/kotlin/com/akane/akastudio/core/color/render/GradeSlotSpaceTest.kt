package com.akane.akastudio.core.color.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GradeSlotSpaceTest {

    private val layerSlots = 0 until 2 * MAX_LAYERS

    @Test
    fun spacesNeverShareASlot() {
        val owners = HashMap<Int, GradeSlotSpace>()
        for (space in GradeSlotSpace.entries) {
            for (layerSlot in layerSlots) {
                val previous = owners.put(space.slot(layerSlot), space)
                assertEquals("slot of $space layer slot $layerSlot already used by $previous", null, previous)
            }
        }
    }

    @Test
    fun eachSlotBelongsOnlyToItsOwnSpace() {
        for (space in GradeSlotSpace.entries) {
            for (layerSlot in layerSlots) {
                val slot = space.slot(layerSlot)
                for (other in GradeSlotSpace.entries) {
                    assertEquals("$slot in $other", other == space, slot in other)
                }
            }
        }
    }

    @Test
    fun previewSlotsAreTheLayerSlots() {
        for (layerSlot in layerSlots) assertEquals(layerSlot, GradeSlotSpace.PREVIEW.slot(layerSlot))
    }

    /** The grain seed: the same layer grains identically in the preview, the scope and the export. */
    @Test
    fun layerSlotIsRecoveredFromEverySpace() {
        for (space in GradeSlotSpace.entries) {
            for (layerSlot in layerSlots) assertEquals(layerSlot, GradeSlotSpace.layerSlot(space.slot(layerSlot)))
        }
    }

    @Test
    fun layerSlotsOutsideTheRangeAreRejected() {
        for (bad in listOf(-1, SLOTS_PER_SPACE)) {
            val result = runCatching { GradeSlotSpace.SCOPE.slot(bad) }
            assertTrue("layer slot $bad must be rejected", result.exceptionOrNull() is IllegalArgumentException)
        }
        assertFalse(-1 in GradeSlotSpace.PREVIEW)
    }

    private companion object {
        /** More layers than any template places (a collage has at most four). */
        const val MAX_LAYERS = 16
    }
}
