package com.akane.akastudio.core.color.gl

/**
 * Reuses framebuffers keyed by size and format. [acquire] hands out a free match or creates one; [recycle]
 * returns it. [endFrame] deletes free framebuffers idle for more than [maxIdleFrames] frames. GL thread only.
 */
internal class FboPool(private val maxIdleFrames: Int = 2) {
    private data class Key(val width: Int, val height: Int, val format: GlFormat)
    private class Idle(val framebuffer: GlFramebuffer, val lastUsedFrame: Long)

    private val free = HashMap<Key, ArrayDeque<Idle>>()
    private val inUse = HashSet<GlFramebuffer>()
    private var frame = 0L

    fun acquire(width: Int, height: Int, format: GlFormat): GlFramebuffer {
        val framebuffer = free[Key(width, height, format)]?.removeLastOrNull()?.framebuffer
            ?: GlFramebuffer.create(width, height, format)
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

    /** Deletes every framebuffer, lent or free. */
    fun releaseAll() {
        free.values.forEach { queue -> queue.forEach { it.framebuffer.release() } }
        free.clear()
        inUse.forEach { it.release() }
        inUse.clear()
    }
}
