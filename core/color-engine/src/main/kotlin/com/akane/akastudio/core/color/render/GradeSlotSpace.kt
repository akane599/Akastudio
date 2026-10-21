package com.akane.akastudio.core.color.render

/** Grade slots each [GradeSlotSpace] owns: far more than the 2 per layer a scene of a few layers uses. */
internal const val SLOTS_PER_SPACE = 1000

/**
 * Disjoint [LayerGrader] slot ranges, one per renderer entry point. The live preview, the scope readback and the
 * export grade the same layers at different sizes; with shared slots each would evict the others' cached grades
 * (a scope refresh forcing a full re-grade on the next preview frame). Layer slots (2i, 2i + 1) map into the
 * caller's range, and the grain seed stays the layer slot, so the export grains exactly like the preview.
 */
internal enum class GradeSlotSpace(private val base: Int) {
    PREVIEW(0),
    SCOPE(SLOTS_PER_SPACE),
    EXPORT(2 * SLOTS_PER_SPACE),
    ;

    /** The grader slot of [layerSlot] (2i for layer i's grade, 2i + 1 for its neutral A/B half) in this space. */
    fun slot(layerSlot: Int): Int {
        require(layerSlot in 0 until SLOTS_PER_SPACE) { "layer slot $layerSlot outside 0 until $SLOTS_PER_SPACE" }
        return base + layerSlot
    }

    operator fun contains(slot: Int): Boolean = slot >= base && slot < base + SLOTS_PER_SPACE

    companion object {
        /** The layer slot of a grader [slot] in whichever space holds it: the seed of its grain pattern. */
        fun layerSlot(slot: Int): Int = slot.mod(SLOTS_PER_SPACE)
    }
}
