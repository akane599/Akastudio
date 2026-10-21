package com.akane.akastudio.core.color.render.grade

import com.akane.akastudio.core.color.gl.GlFramebuffer

/**
 * The cached outputs of a [ShaderLayerGrader]: one framebuffer per slot and the key of the grade it holds. A slot
 * keeps its framebuffer while re-grades keep the same size. Outputs live until a size change, [release] or
 * [releaseAll]; they are not pooled. GL thread only.
 */
internal class GradeSlotOutputs<K : Any>(private val allocate: (width: Int, height: Int) -> GlFramebuffer) {
    private class Slot<K : Any>(val output: GlFramebuffer, var key: K?)

    private val slots = HashMap<Int, Slot<K>>()

    /** Slots that currently hold a framebuffer. */
    val liveSlots: Set<Int> get() = slots.keys.toSet()

    /** [slot]'s output when it holds the grade of [key], else null. */
    fun cached(slot: Int, key: K): GlFramebuffer? =
        slots[slot]?.takeIf { it.key == key && !it.output.isReleased }?.output

    /**
     * Grades [key] into [slot]'s [width] x [height] output with [render], reusing the slot's framebuffer when its size
     * matches. The slot holds no key while [render] runs, so a failed grade is redone on the next call.
     */
    fun grade(slot: Int, key: K, width: Int, height: Int, render: (GlFramebuffer) -> Unit): GlFramebuffer {
        val target = outputFor(slot, width, height)
        target.key = null
        render(target.output)
        target.key = key
        return target.output
    }

    /** Deletes the output of every slot matching [predicate]; those slots re-grade (and re-allocate) on next use. */
    fun release(predicate: (slot: Int) -> Boolean) {
        val iterator = slots.entries.iterator()
        while (iterator.hasNext()) {
            val (slot, held) = iterator.next()
            if (predicate(slot)) {
                held.output.release()
                iterator.remove()
            }
        }
    }

    fun releaseAll() = release { true }

    private fun outputFor(slot: Int, width: Int, height: Int): Slot<K> {
        val existing = slots[slot]
        if (existing != null && !existing.output.isReleased && existing.output.width == width && existing.output.height == height) {
            return existing
        }
        existing?.output?.release()
        slots.remove(slot)
        val created = Slot<K>(allocate(width, height), key = null)
        slots[slot] = created
        return created
    }
}
