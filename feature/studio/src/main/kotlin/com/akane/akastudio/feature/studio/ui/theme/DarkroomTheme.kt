package com.akane.akastudio.feature.studio.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/**
 * The studio's only theme. The Darkroom scheme is forced: there is no light variant and no dynamic
 * colour, because the UI must never tint the photos the user is judging.
 *
 * Provides Material 3 colour, type and shape tokens plus [LocalDarkroomColors],
 * [LocalDarkroomSpacing] and [LocalDarkroomMotion]; read them through the [DarkroomTheme] object.
 */
@Composable
fun DarkroomTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalDarkroomColors provides DarkroomColors(),
        LocalDarkroomSpacing provides DarkroomSpacing(),
        LocalDarkroomMotion provides systemDarkroomMotion(),
    ) {
        MaterialTheme(
            colorScheme = DarkroomColorScheme,
            typography = DarkroomTypography,
            shapes = DarkroomShapes,
            content = content,
        )
    }
}

/** Accessors for the Darkroom tokens that have no Material 3 role. */
object DarkroomTheme {
    /** Extended colours: canvas backdrop, divider, slider and scope colours. */
    val colors: DarkroomColors
        @Composable @ReadOnlyComposable
        get() = LocalDarkroomColors.current

    /** Spacing scale. */
    val spacing: DarkroomSpacing
        @Composable @ReadOnlyComposable
        get() = LocalDarkroomSpacing.current

    /** Motion specs, already adjusted for the system reduced-motion setting. */
    val motion: DarkroomMotion
        @Composable @ReadOnlyComposable
        get() = LocalDarkroomMotion.current
}
