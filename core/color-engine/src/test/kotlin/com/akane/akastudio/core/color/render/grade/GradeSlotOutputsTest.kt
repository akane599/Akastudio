package com.akane.akastudio.core.color.render.grade

import com.akane.akastudio.core.color.gl.GlFormat
import com.akane.akastudio.core.color.gl.testFramebuffer
import com.akane.akastudio.core.color.render.GradeSlotSpace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GradeSlotOutputsTest {

    private var renders = 0
    private val outputs = GradeSlotOutputs<String> { width, height -> testFramebuffer(width, height, GlFormat.RGBA16F) }

    private fun grade(slot: Int, key: String, width: Int = 64, height: Int = 64) =
        outputs.cached(slot, key) ?: outputs.grade(slot, key, width, height) { renders++ }

    @Test
    fun sameKeyIsServedFromTheCache() {
        val first = grade(0, "a")
        assertSame(first, grade(0, "a"))
        assertEquals(1, renders)
    }

    @Test
    fun newKeyOfTheSameSizeReusesTheFramebuffer() {
        val first = grade(0, "a")
        assertSame(first, grade(0, "b"))
        assertEquals(2, renders)
        assertFalse(first.isReleased)
    }

    @Test
    fun sizeChangeReplacesTheFramebuffer() {
        val first = grade(0, "a")
        val resized = grade(0, "a at 32 px", width = 32)
        assertNotSame(first, resized)
        assertTrue(first.isReleased)
    }

    @Test
    fun failedRenderLeavesNoCachedKey() {
        runCatching { outputs.grade(0, "a", 64, 64) { error("lost context") } }
        assertNull(outputs.cached(0, "a"))
    }

    @Test
    fun releaseDropsOnlyMatchingSlots() {
        val preview = grade(GradeSlotSpace.PREVIEW.slot(0), "a")
        val exportGrade = grade(GradeSlotSpace.EXPORT.slot(0), "a", width = 960)
        val exportNeutral = grade(GradeSlotSpace.EXPORT.slot(1), "n", width = 960)

        outputs.release { it in GradeSlotSpace.EXPORT }

        assertTrue("export outputs must be deleted", exportGrade.isReleased && exportNeutral.isReleased)
        assertFalse("the preview output must survive", preview.isReleased)
        assertEquals(setOf(GradeSlotSpace.PREVIEW.slot(0)), outputs.liveSlots)
        assertSame(preview, grade(GradeSlotSpace.PREVIEW.slot(0), "a"))
        assertNull(outputs.cached(GradeSlotSpace.EXPORT.slot(0), "a"))
    }

    @Test
    fun releasedSlotReallocatesOnNextGrade() {
        val first = grade(3, "a")
        outputs.releaseAll()
        assertTrue(first.isReleased)
        assertTrue(outputs.liveSlots.isEmpty())
        val again = grade(3, "a")
        assertNotSame(first, again)
        assertFalse(again.isReleased)
        assertEquals(2, renders)
    }
}
