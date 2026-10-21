package com.akane.akastudio.core.color.gl

/**
 * Reuses framebuffers keyed by size and format. [acquire] hands out a free match or creates one with [allocate];
 * [recycle] returns it. [endFrame] deletes free framebuffers idle for more than [maxIdleFrames] frames, and
 * [releaseFree] deletes every free one at once (after an export, on a memory trim). GL thread only.
 */
internal class FboPool(
    private val maxIdleFrames: Int = 2,
    private val allocate: (width: Int, height: Int, format: GlFormat) -> GlFramebuffer = GlFramebuffer::create,
) {
    private data class Key(val width: Int, val height: Int, val format: GlFormat)
    private class Idle(val framebuffer: GlFramebuffer, val lastUsedFrame: Long)

    private val free = HashMap<Key, ArrayDeque<Idle>>()
    private val inUse = HashSet<GlFramebuffer>()
    private var frame = 0L

    fun acquire(width: Int, height: Int, format: GlFormat): GlFramebuffer {
        val framebuffer = free[Key(width, height, format)]?.removeLastOrNull()?.framebuffer
            ?: allocate(width, height, format)
        inUse += framebuffer
        return framebuffer
    }

    /** Returns [framebuffer] for reuse; ignores framebuffers this pool does not currently lend. */
    fun recycle(framebuffer: GlFramebuffer) {
        if (!inUse.remove(framebuffer)) return
        free.getOrPut(Key(framebuffer.width, framebuffer.height, framebuffer.format)) { ArrayDeque() }
            .addLast(Idle(framebuffer, frame))
    }

    fun endFrame() {
        frame++
        val iterator = free.values.iterator()
        while (iterator.hasNext()) {
            val queue = iterator.next()
            queue.removeAll { idle ->
                val expired = frame - idle.lastUsedFrame > maxIdleFrames
                if (expired) idle.framebuffer.release()
                expired
            }
            if (queue.isEmpty()) iterator.remove()
        }
    }

    /** Framebuffers waiting in the pool for reuse. */
    val freeCount: Int get() = free.values.sumOf { it.size }

    /** Framebuffers currently lent out. */
    val inUseCount: Int get() = inUse.size

    /**
     * Deletes every free framebuffer now instead of waiting for frames to age them out: nothing else ages them
     * once rendering stops. Lent framebuffers are untouched and are pooled again when recycled.
     */
    fun releaseFree() {
        free.values.forEach { queue -> queue.forEach { it.framebuffer.release() } }
        free.clear()
    }

    /** Deletes every framebuffer, lent or free. */
    fun releaseAll() {
        free.values.forEach { queue -> queue.forEach { it.framebuffer.release() } }
        free.clear()
        inUse.forEach { it.release() }
        inUse.clear()
    }
}
