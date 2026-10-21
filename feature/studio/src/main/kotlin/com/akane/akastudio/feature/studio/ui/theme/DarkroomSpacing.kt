package com.akane.akastudio.feature.studio.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The Darkroom spacing scale; every studio padding and gap references one of these steps. */
@Immutable
data class DarkroomSpacing(
    val xs: Dp = 4.dp,
    val s: Dp = 8.dp,
    val m: Dp = 12.dp,
    val l: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
)

/** Darkroom spacing; provided by [DarkroomTheme]. */
val LocalDarkroomSpacing = staticCompositionLocalOf { DarkroomSpacing() }
