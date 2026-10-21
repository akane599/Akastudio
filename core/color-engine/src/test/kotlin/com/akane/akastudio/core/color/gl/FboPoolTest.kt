package com.akane.akastudio.core.color.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM checks of the pool's bookkeeping; framebuffers are bare objects (android.jar GL stubs return defaults). */
class FboPoolTest {

    private val created = ArrayList<GlFramebuffer>()
    private val pool = FboPool { width, height, format -> testFramebuffer(width, height, format).also(created::add) }

    @Test
    fun recycledFramebufferIsReusedForTheSameSizeAndFormat() {
        val first = pool.acquire(64, 32, GlFormat.RGBA16F)
        pool.recycle(first)
        assertSame(first, pool.acquire(64, 32, GlFormat.RGBA16F))
        assertNotSame(first, pool.acquire(64, 32, GlFormat.RGBA16F))
    }

    @Test
    fun releaseFreeDeletesEveryIdleFramebufferAndKeepsLentOnes() {
        val idleA = pool.acquire(64, 32, GlFormat.RGBA16F)
        val idleB = pool.acquire(16, 16, GlFormat.RGBA8)
        val lent = pool.acquire(64, 32, GlFormat.RGBA16F)
        pool.recycle(idleA)
        pool.recycle(idleB)
        assertEquals(2, pool.freeCount)

        pool.releaseFree()

        assertTrue("idle framebuffers must be deleted", idleA.isReleased && idleB.isReleased)
        assertFalse("a lent framebuffer must survive", lent.isReleased)
        assertEquals(0, pool.freeCount)
        assertEquals(1, pool.inUseCount)
        val again = pool.acquire(64, 32, GlFormat.RGBA16F)
        assertNotSame("a released framebuffer must never be handed out again", idleA, again)
        assertFalse(again.isReleased)
    }

    @Test
    fun framebufferLentDuringReleaseFreeIsPooledWhenReturned() {
        val lent = pool.acquire(8, 8, GlFormat.RGBA8)
        pool.releaseFree()
        pool.recycle(lent)
        assertEquals(1, pool.freeCount)
        assertSame(lent, pool.acquire(8, 8, GlFormat.RGBA8))
    }

    @Test
    fun endFrameStillAgesOutOnlyFramebuffersIdleLongerThanTheLimit() {
        val old = pool.acquire(8, 8, GlFormat.RGBA8)
        pool.recycle(old)
        pool.endFrame()
        pool.endFrame()
        assertFalse("idle for 2 frames is within the limit", old.isReleased)
        pool.endFrame()
        assertTrue("idle for 3 frames must be deleted", old.isReleased)
        assertEquals(0, pool.freeCount)
    }

    @Test
    fun releaseAllDeletesLentAndFreeFramebuffers() {
        val lent = pool.acquire(8, 8, GlFormat.RGBA8)
        val idle = pool.acquire(4, 4, GlFormat.RGBA8)
        pool.recycle(idle)
        pool.releaseAll()
        assertTrue(lent.isReleased && idle.isReleased)
        assertEquals(0, pool.freeCount + pool.inUseCount)
    }
}

/** A framebuffer object with no GL name behind it: release only flips its flag under the android.jar stubs. */
internal fun testFramebuffer(width: Int, height: Int, format: GlFormat): GlFramebuffer =
    GlFramebuffer(0, GlTexture.allocate(width, height, format))
