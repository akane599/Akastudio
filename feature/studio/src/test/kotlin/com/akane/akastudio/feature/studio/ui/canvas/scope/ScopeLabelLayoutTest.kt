package com.akane.akastudio.feature.studio.ui.canvas.scope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScopeLabelLayoutTest {

    private val eps = 1e-5f
    private val label = 16f

    @Test
    fun `zero high plot skips the label instead of throwing`() {
        assertNull(ScopeLabelLayout.start(centre = 0f, extent = label, available = 0f))
    }

    @Test
    fun `one pixel plot skips the label instead of throwing`() {
        assertNull(ScopeLabelLayout.start(centre = 1f, extent = label, available = 1f))
    }

    @Test
    fun `plot one pixel shorter than the label skips it`() {
        val height = label - 1f
        assertNull(ScopeLabelLayout.start(centre = height, extent = label, available = height))
    }

    @Test
    fun `negative room from an over-subtracted plot skips the label`() {
        assertNull(ScopeLabelLayout.start(centre = 0f, extent = label, available = -5f))
    }

    @Test
    fun `plot exactly as high as the label pins it at zero`() {
        assertEquals(0f, ScopeLabelLayout.start(centre = label / 2f, extent = label, available = label)!!, eps)
        assertEquals(0f, ScopeLabelLayout.start(centre = label, extent = label, available = label)!!, eps)
    }

    @Test
    fun `normal plot centres mid labels and keeps edge labels inside`() {
        val height = 120f
        // 100 % tick at the top: centred would start at -8, clamped to 0.
        assertEquals(0f, ScopeLabelLayout.start(centre = 0f, extent = label, available = height)!!, eps)
        // 50 % tick: centred on 60.
        assertEquals(52f, ScopeLabelLayout.start(centre = 60f, extent = label, available = height)!!, eps)
        // 0 % tick at the bottom: centred would overflow to 128, clamped to 104.
        assertEquals(104f, ScopeLabelLayout.start(centre = height, extent = label, available = height)!!, eps)
    }

    @Test
    fun `narrow histogram axis skips code labels and wide one keeps them inside`() {
        assertNull(ScopeLabelLayout.start(centre = 0f, extent = 24f, available = 10f))
        assertEquals(0f, ScopeLabelLayout.start(centre = 0f, extent = 24f, available = 200f)!!, eps)
        assertEquals(176f, ScopeLabelLayout.start(centre = 200f, extent = 24f, available = 200f)!!, eps)
    }
}
