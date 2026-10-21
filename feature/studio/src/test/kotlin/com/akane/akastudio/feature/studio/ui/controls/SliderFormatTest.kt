package com.akane.akastudio.feature.studio.ui.controls

import com.akane.akastudio.core.color.grade.WhiteBalance
import com.akane.akastudio.feature.studio.mvi.GradeField
import com.akane.akastudio.feature.studio.mvi.ScopeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SliderFormatTest {

    private val english = ReadoutPatterns(percent = "%1\$s%%", kelvin = "%1\$s K", pixels = "%1\$s px")
    private val turkish = ReadoutPatterns(percent = "%%%1\$s", kelvin = "%1\$s K", pixels = "%1\$s px")

    private fun text(field: GradeField, value: Float, patterns: ReadoutPatterns = english) =
        SliderFormat.format(SliderFormat.readout(field, value), patterns)

    @Test
    fun `bipolar fields read as signed hundredths with a typographic minus`() {
        assertEquals("+35", text(GradeField.CONTRAST, 0.35f))
        assertEquals("−12", text(GradeField.SATURATION, -0.12f))
        assertEquals("0", text(GradeField.TINT, 0f))
        assertEquals("+100", text(GradeField.TINT, 1f))
        assertEquals("−100", text(GradeField.CONTRAST, -1f))
    }

    @Test
    fun `values that round to zero have no sign`() {
        assertEquals("0", text(GradeField.CONTRAST, -0.004f))
        assertEquals("0", text(GradeField.CONTRAST, 0.004f))
    }

    @Test
    fun `unipolar fields read as whole percentages in the locale pattern`() {
        assertEquals("80%", text(GradeField.HARMONIZATION_STRENGTH, 0.8f))
        assertEquals("100%", text(GradeField.INTENSITY, 1f))
        assertEquals("0%", text(GradeField.GRAIN, 0f))
        assertEquals("%60", text(GradeField.SKIN_PROTECTION, 0.6f, turkish))
    }

    @Test
    fun `temperature reads as the engine's Kelvin shift of the source white, rounded to 10 K`() {
        // ±60 mired around D65 (6504 K): 1e6 / (1e6 / 6504 − 60·t) − 6504.
        assertEquals("+860 K", text(GradeField.TEMPERATURE, 0.3f))
        assertEquals("+4160 K", text(GradeField.TEMPERATURE, 1f))
        assertEquals("−1830 K", text(GradeField.TEMPERATURE, -1f))
        assertEquals("0 K", text(GradeField.TEMPERATURE, 0f))
        assertEquals(30, SliderFormat.kelvinShift(0.0123f))
        assertEquals(-1060, SliderFormat.kelvinShift(-0.5f))
    }

    @Test
    fun `the temperature readout tracks WhiteBalance across the slider range`() {
        for (i in -20..20) {
            val t = i / 20f
            val engine = WhiteBalance.kelvinShift(t)
            assertEquals("t=$t", engine, SliderFormat.kelvinShift(t).toDouble(), SliderFormat.KELVIN_STEP / 2.0)
        }
    }

    @Test
    fun `out of range and non-finite values are clamped or read as zero`() {
        assertEquals("100%", text(GradeField.INTENSITY, 3f))
        assertEquals("+4160 K", text(GradeField.TEMPERATURE, 9f))
        assertEquals("0", text(GradeField.CONTRAST, Float.NaN))
        assertEquals("0%", text(GradeField.INTENSITY, Float.NaN))
    }

    @Test
    fun `pixel readouts round to whole pixels`() {
        assertEquals("12 px", SliderFormat.format(SliderFormat.pixels(12.4f), english))
        assertEquals("48 px", SliderFormat.format(SliderFormat.pixels(47.6f), english))
        assertEquals("0 px", SliderFormat.format(SliderFormat.pixels(Float.POSITIVE_INFINITY), english))
    }

    @Test
    fun `bipolar fields are exactly those with a negative minimum`() {
        val bipolar = GradeField.entries.filter(SliderFormat::isBipolar).toSet()
        assertEquals(setOf(GradeField.TEMPERATURE, GradeField.TINT, GradeField.CONTRAST, GradeField.SATURATION), bipolar)
        assertFalse(SliderFormat.isBipolar(GradeField.HALATION))
    }

    @Test
    fun `reset values are the grade defaults`() {
        assertEquals(1f, SliderFormat.defaultValue(GradeField.INTENSITY))
        assertEquals(0.8f, SliderFormat.defaultValue(GradeField.HARMONIZATION_STRENGTH))
        assertEquals(0f, SliderFormat.defaultValue(GradeField.TEMPERATURE))
        assertEquals(0.6f, SliderFormat.defaultValue(GradeField.SKIN_PROTECTION))
    }

    @Test
    fun `percentOf clamps to 0 to 100`() {
        assertEquals(87, SliderFormat.percentOf(0.874f))
        assertEquals(100, SliderFormat.percentOf(1.2f))
        assertEquals(0, SliderFormat.percentOf(-0.1f))
        assertEquals(0, SliderFormat.percentOf(Float.NaN))
    }

    @Test
    fun `fractionOf maps a value into its range`() {
        assertEquals(0.5f, fractionOf(0f, -1f..1f))
        assertEquals(0.25f, fractionOf(12f, 0f..48f))
        assertEquals(1f, fractionOf(99f, 0f..48f))
        assertEquals(0f, fractionOf(1f, 2f..2f))
    }

    @Test
    fun `scope cycles off, luma, rgb parade, histogram and back to off`() {
        assertEquals(ScopeMode.LUMA_WAVEFORM, ScopeMode.OFF.next())
        assertEquals(ScopeMode.RGB_PARADE, ScopeMode.LUMA_WAVEFORM.next())
        assertEquals(ScopeMode.HISTOGRAM, ScopeMode.RGB_PARADE.next())
        assertEquals(ScopeMode.OFF, ScopeMode.HISTOGRAM.next())
        assertTrue(ScopeMode.entries.all { mode -> generateSequence(mode) { it.next() }.take(5).last() == mode })
    }
}
