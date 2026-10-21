package com.akane.akastudio.feature.studio.ui.controls

import com.akane.akastudio.core.color.grade.WhiteBalance
import com.akane.akastudio.feature.studio.mvi.GradeField
import com.akane.akastudio.feature.studio.mvi.GradeUi
import com.akane.akastudio.feature.studio.mvi.ScopeMode
import java.util.Locale
import kotlin.math.roundToInt

/** How a slider value is read out. */
internal enum class ReadoutKind {
    /** Unipolar 0..1 shown as a whole percentage: `80%`. */
    PERCENT,

    /** Bipolar -1..1 shown as a signed whole number of hundredths: `+35`, `−12`, `0`. */
    SIGNED,

    /** Temperature shown as the signed Kelvin shift of the engine's source white from D65: `+860 K`. */
    KELVIN_SHIFT,

    /** Layout pixels in 1080-wide units: `12 px`. */
    PIXELS,
}

/** A slider readout before localization: its [kind] and the rounded whole [number] to print. */
internal data class Readout(val kind: ReadoutKind, val number: Int)

/**
 * Localized patterns for [formatReadout], each a `String.format` pattern with one `%1$s` argument that receives
 * the already-signed number text (for example `"%1$s%%"` in English and `"%%%1$s"` in Turkish).
 */
internal data class ReadoutPatterns(val percent: String, val kelvin: String, val pixels: String)

/** Pure slider value formatting shared by the colour and layout tabs. */
internal object SliderFormat {

    /** Kelvin readouts are rounded to this step so the value does not flicker by single degrees while dragging. */
    const val KELVIN_STEP = 10

    /** U+2212 MINUS SIGN: the typographic minus, the same width as the plus sign in tabular figures. */
    const val MINUS = "−"

    private const val PLUS = "+"
    private const val HUNDRED = 100f

    /** The default value a grade slider resets to on double-tap. */
    fun defaultValue(field: GradeField): Float = GradeUi().value(field)

    /** True when [field] ranges below zero, so its track is centred on zero. */
    fun isBipolar(field: GradeField): Boolean = field.min < 0f

    /** The readout of [field] at [value]; non-finite values read as zero. */
    fun readout(field: GradeField, value: Float): Readout {
        val v = if (value.isFinite()) value.coerceIn(field.min, field.max) else 0f
        return when {
            field == GradeField.TEMPERATURE -> Readout(ReadoutKind.KELVIN_SHIFT, kelvinShift(v))
            isBipolar(field) -> Readout(ReadoutKind.SIGNED, (v * HUNDRED).roundToInt())
            else -> Readout(ReadoutKind.PERCENT, (v * HUNDRED).roundToInt())
        }
    }

    /** The readout of a pixel slider at [px]; non-finite values read as zero. */
    fun pixels(px: Float): Readout = Readout(ReadoutKind.PIXELS, if (px.isFinite()) px.roundToInt() else 0)

    /**
     * Temperature [value] in -1..1 as the Kelvin shift the engine applies ([WhiteBalance.kelvinShift]), rounded
     * to [KELVIN_STEP]. The shift is asymmetric (±60 mired): +1 reads +4160 K and −1 reads −1830 K.
     */
    fun kelvinShift(value: Float): Int {
        val kelvin = WhiteBalance.kelvinShift(if (value.isFinite()) value else 0f)
        return (kelvin / KELVIN_STEP).roundToInt() * KELVIN_STEP
    }

    /** [number] with an explicit sign: `+35`, `−12` (U+2212), and a bare `0`. */
    fun signed(number: Int): String = when {
        number > 0 -> "$PLUS$number"
        number < 0 -> "$MINUS${-number}"
        else -> "0"
    }

    /** The localized text of [readout]. Signed and Kelvin readouts carry an explicit sign; percent and pixels do not. */
    fun format(readout: Readout, patterns: ReadoutPatterns): String = when (readout.kind) {
        ReadoutKind.PERCENT -> pattern(patterns.percent, readout.number.toString())
        ReadoutKind.SIGNED -> signed(readout.number)
        ReadoutKind.KELVIN_SHIFT -> pattern(patterns.kelvin, signed(readout.number))
        ReadoutKind.PIXELS -> pattern(patterns.pixels, readout.number.toString())
    }

    /** A 0..1 [fraction] as a whole percentage number, clamped to 0..100. */
    fun percentOf(fraction: Float): Int =
        if (fraction.isFinite()) (fraction.coerceIn(0f, 1f) * HUNDRED).roundToInt() else 0

    private fun pattern(pattern: String, number: String): String = String.format(Locale.ROOT, pattern, number)
}

/** The scope the top bar's scope button switches to next: OFF → LUMA → RGB parade → HISTOGRAM → OFF. */
internal fun ScopeMode.next(): ScopeMode = ScopeMode.entries[(ordinal + 1) % ScopeMode.entries.size]
