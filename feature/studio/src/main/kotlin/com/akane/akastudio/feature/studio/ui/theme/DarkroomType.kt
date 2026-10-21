package com.akane.akastudio.feature.studio.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private const val TABULAR_NUMBERS = "tnum"

/**
 * The Darkroom type scale on the platform sans. The styles the studio uses are set explicitly;
 * the remaining Material roles keep their defaults on the same family.
 */
internal val DarkroomTypography: Typography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Normal,
            fontSize = 34.sp,
            lineHeight = 40.sp,
            letterSpacing = 0.sp,
        ),
        headlineSmall = headlineSmall.copy(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Normal,
            fontSize = 22.sp,
            lineHeight = 28.sp,
            letterSpacing = 0.sp,
        ),
        titleMedium = titleMedium.copy(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            lineHeight = 22.sp,
            letterSpacing = 0.1.sp,
        ),
        bodyMedium = bodyMedium.copy(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.2.sp,
        ),
        labelMedium = labelMedium.copy(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.6.sp,
        ),
        labelSmall = labelSmall.copy(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            letterSpacing = 0.4.sp,
        ),
    )
}

/**
 * Darkroom text styles with no Material 3 role.
 *
 * Use [mono] for EXIF and technical readouts, and [tabular] (or [tabularNumbers] on any style) for
 * slider values and telemetry counters so digits keep a fixed width while they change.
 */
object DarkroomTextStyles {
    /** Monospace readout style for EXIF and technical values. */
    val mono: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = TABULAR_NUMBERS,
    )

    /** `labelMedium`-sized sans with tabular figures, for slider values and counters. */
    val tabular: TextStyle = DarkroomTypography.labelMedium.copy(
        letterSpacing = 0.sp,
        fontFeatureSettings = TABULAR_NUMBERS,
    )
}

/** Returns this style with tabular (fixed-width) figures. */
fun TextStyle.tabularNumbers(): TextStyle = copy(fontFeatureSettings = TABULAR_NUMBERS)
