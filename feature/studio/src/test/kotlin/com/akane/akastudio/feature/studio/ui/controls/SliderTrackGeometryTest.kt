package com.akane.akastudio.feature.studio.ui.controls

import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The drawn fill must run from zero towards the native thumb, which Material3 mirrors under RTL. */
class SliderTrackGeometryTest {

    private val width = 400f
    private val bipolar = -1f..1f
    private val unipolar = 0f..1f

    /** Where the native Slider centres its thumb for [fraction], ignoring the thumb inset. */
    private fun nativeThumbX(fraction: Float, direction: LayoutDirection) =
        if (direction == LayoutDirection.Rtl) width * (1f - fraction) else width * fraction

    private fun endpoints(
        value: Float,
        range: ClosedFloatingPointRange<Float>,
        isBipolar: Boolean,
        direction: LayoutDirection,
    ): Pair<Float, Float> {
        val zero = if (isBipolar) fractionOf(0f, range) else 0f
        return trackX(width, zero, direction) to trackX(width, fractionOf(value, range), direction)
    }

    private fun assertFillReachesThumb(
        value: Float,
        range: ClosedFloatingPointRange<Float>,
        isBipolar: Boolean,
        direction: LayoutDirection,
    ) {
        val (from, to) = endpoints(value, range, isBipolar, direction)
        val thumb = nativeThumbX(fractionOf(value, range), direction)
        val zeroX = when {
            isBipolar -> width / 2f
            direction == LayoutDirection.Rtl -> width
            else -> 0f
        }
        assertEquals("fill starts at zero ($direction, $value)", zeroX, from, 1e-3f)
        assertEquals("fill ends under the thumb ($direction, $value)", thumb, to, 1e-3f)
        assertTrue("fill lies on the thumb's side of zero ($direction, $value)", (to - from) * (thumb - zeroX) > 0f)
    }

    @Test
    fun `ltr endpoints stay exactly width times fraction`() {
        assertEquals(0f to 320f, endpoints(0.8f, unipolar, isBipolar = false, LayoutDirection.Ltr))
        assertEquals(200f to 300f, endpoints(0.5f, bipolar, isBipolar = true, LayoutDirection.Ltr))
        assertEquals(200f to 100f, endpoints(-0.5f, bipolar, isBipolar = true, LayoutDirection.Ltr))
    }

    @Test
    fun `rtl unipolar fill grows from the right edge to the thumb`() {
        val (from, to) = endpoints(0.8f, unipolar, isBipolar = false, LayoutDirection.Rtl)
        assertEquals(400f, from, 1e-3f)
        assertEquals(80f, to, 1e-3f)
        assertFillReachesThumb(0.8f, unipolar, isBipolar = false, LayoutDirection.Rtl)
    }

    @Test
    fun `rtl bipolar positive fill runs left of zero towards the thumb`() {
        val (from, to) = endpoints(0.5f, bipolar, isBipolar = true, LayoutDirection.Rtl)
        assertEquals(200f, from, 1e-3f)
        assertEquals(100f, to, 1e-3f)
        assertFillReachesThumb(0.5f, bipolar, isBipolar = true, LayoutDirection.Rtl)
    }

    @Test
    fun `rtl bipolar negative fill runs right of zero towards the thumb`() {
        val (from, to) = endpoints(-0.5f, bipolar, isBipolar = true, LayoutDirection.Rtl)
        assertEquals(200f, from, 1e-3f)
        assertEquals(300f, to, 1e-3f)
        assertFillReachesThumb(-0.5f, bipolar, isBipolar = true, LayoutDirection.Rtl)
    }

    @Test
    fun `fill stays on the thumb side in both directions`() {
        for (direction in LayoutDirection.entries) {
            assertFillReachesThumb(0.8f, unipolar, isBipolar = false, direction)
            assertFillReachesThumb(0.5f, bipolar, isBipolar = true, direction)
            assertFillReachesThumb(-0.5f, bipolar, isBipolar = true, direction)
        }
    }
}
