package com.akane.akastudio.feature.studio.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The Darkroom palette: a neutral near-black ground so the UI never biases colour perception.
 * Every value here is achromatic or warm-neutral; emphasis comes from luminance, never hue.
 * Screens read these through [DarkroomTheme] or `MaterialTheme.colorScheme`, never directly.
 */
internal object DarkroomPalette {
    val Background = Color(0xFF0B0C0E)
    val Surface = Color(0xFF121316)
    val SurfaceContainerLowest = Color(0xFF0E0F11)
    val SurfaceContainerLow = Color(0xFF141518)
    val SurfaceContainer = Color(0xFF17191C)
    val SurfaceContainerHigh = Color(0xFF1D1F23)
    val SurfaceContainerHighest = Color(0xFF24272B)
    val Outline = Color(0xFF2C2F34)
    val OutlineVariant = Color(0xFF212328)
    val PaperWhite = Color(0xFFE8E6E1)
    val OnSurfaceVariant = Color(0xFF9C9A94)
    val Secondary = Color(0xFFB9B5AC)
    val Tertiary = Color(0xFF8E8A82)
    val Error = Color(0xFFD98E80)
    val ErrorContainer = Color(0xFF3A2422)
    val OnErrorContainer = Color(0xFFEBC6BE)
    val InversePrimary = Color(0xFF3A3C40)
    val Scrim = Color(0x99000000)

    val CanvasBackdrop = Color(0xFF070809)
    val Divider = Color(0xFF1A1C1F)
    val ScopeLuma = Color(0xFFD8D6D0)
    val ScopeRed = Color(0xFFC46A5E)
    val ScopeGreen = Color(0xFF6FA36B)
    val ScopeBlue = Color(0xFF5F84C2)
}

/**
 * The forced Darkroom Material 3 scheme. Every role is set explicitly: a role left unset would fall
 * back to Material's baseline purple, which this product never shows.
 */
internal val DarkroomColorScheme: ColorScheme = darkColorScheme(
    primary = DarkroomPalette.PaperWhite,
    onPrimary = DarkroomPalette.Background,
    primaryContainer = DarkroomPalette.SurfaceContainerHighest,
    onPrimaryContainer = DarkroomPalette.PaperWhite,
    inversePrimary = DarkroomPalette.InversePrimary,
    secondary = DarkroomPalette.Secondary,
    onSecondary = DarkroomPalette.Background,
    secondaryContainer = DarkroomPalette.Outline,
    onSecondaryContainer = DarkroomPalette.PaperWhite,
    tertiary = DarkroomPalette.Tertiary,
    onTertiary = DarkroomPalette.Background,
    tertiaryContainer = DarkroomPalette.SurfaceContainerHigh,
    onTertiaryContainer = DarkroomPalette.Secondary,
    background = DarkroomPalette.Background,
    onBackground = DarkroomPalette.PaperWhite,
    surface = DarkroomPalette.Surface,
    onSurface = DarkroomPalette.PaperWhite,
    surfaceVariant = DarkroomPalette.SurfaceContainerHigh,
    onSurfaceVariant = DarkroomPalette.OnSurfaceVariant,
    surfaceTint = DarkroomPalette.PaperWhite,
    inverseSurface = DarkroomPalette.PaperWhite,
    inverseOnSurface = DarkroomPalette.Surface,
    error = DarkroomPalette.Error,
    onError = DarkroomPalette.Background,
    errorContainer = DarkroomPalette.ErrorContainer,
    onErrorContainer = DarkroomPalette.OnErrorContainer,
    outline = DarkroomPalette.Outline,
    outlineVariant = DarkroomPalette.OutlineVariant,
    scrim = DarkroomPalette.Scrim,
    surfaceBright = DarkroomPalette.Outline,
    surfaceDim = DarkroomPalette.Background,
    surfaceContainerLowest = DarkroomPalette.SurfaceContainerLowest,
    surfaceContainerLow = DarkroomPalette.SurfaceContainerLow,
    surfaceContainer = DarkroomPalette.SurfaceContainer,
    surfaceContainerHigh = DarkroomPalette.SurfaceContainerHigh,
    surfaceContainerHighest = DarkroomPalette.SurfaceContainerHighest,
)

/**
 * Darkroom colours that have no Material 3 role.
 *
 * The scope colours ([scopeR], [scopeG], [scopeB]) are the only chromatic tokens in the product and
 * are reserved for data visualisation (histograms, waveforms, vectorscopes); never use them for
 * emphasis or decoration.
 */
@Immutable
data class DarkroomColors(
    /** Ground behind the story canvas, one step darker than the app background. */
    val canvasBackdrop: Color = DarkroomPalette.CanvasBackdrop,
    /** Hairline separator between control groups. */
    val divider: Color = DarkroomPalette.Divider,
    /** Inactive part of a slider track. */
    val sliderTrack: Color = DarkroomPalette.Outline,
    /** Active part of a slider track and its thumb. */
    val sliderActive: Color = DarkroomPalette.PaperWhite,
    /** Luminance trace in scopes. */
    val scopeLuma: Color = DarkroomPalette.ScopeLuma,
    /** Muted red channel trace in scopes. */
    val scopeR: Color = DarkroomPalette.ScopeRed,
    /** Muted green channel trace in scopes. */
    val scopeG: Color = DarkroomPalette.ScopeGreen,
    /** Muted blue channel trace in scopes. */
    val scopeB: Color = DarkroomPalette.ScopeBlue,
)

/** Extended Darkroom colours; provided by [DarkroomTheme]. */
val LocalDarkroomColors = staticCompositionLocalOf { DarkroomColors() }
