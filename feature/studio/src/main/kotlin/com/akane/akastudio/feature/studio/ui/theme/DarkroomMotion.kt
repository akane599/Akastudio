package com.akane.akastudio.feature.studio.ui.theme

import android.animation.ValueAnimator
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalInspectionMode

/** Material 3 emphasized-decelerate curve: fast start, long gentle settle. */
private val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)

/**
 * The Darkroom motion set: one standard transition for things that enter or change, and one quick
 * transition for direct feedback (press, toggle, value tick).
 *
 * When [reducedMotion] is true (the user turned animations off in system settings) both specs snap
 * to their target instead of animating.
 */
@Immutable
data class DarkroomMotion(
    val standardDurationMillis: Int = 220,
    val quickDurationMillis: Int = 120,
    val easing: Easing = EmphasizedDecelerate,
    val reducedMotion: Boolean = false,
) {
    /** Spec for entering, expanding and content changes. */
    fun <T> standard(): FiniteAnimationSpec<T> =
        if (reducedMotion) snap() else tween(durationMillis = standardDurationMillis, easing = easing)

    /** Spec for immediate feedback to a touch. */
    fun <T> quick(): FiniteAnimationSpec<T> =
        if (reducedMotion) snap() else tween(durationMillis = quickDurationMillis, easing = easing)
}

/** Darkroom motion; provided by [DarkroomTheme]. */
val LocalDarkroomMotion = staticCompositionLocalOf { DarkroomMotion() }

/**
 * Reads the system animator duration scale through [ValueAnimator.areAnimatorsEnabled], which is
 * false when the user set "Animator duration scale" to off. Previews and screenshot renders keep
 * full motion, since they have no system setting to honour.
 */
@Composable
internal fun systemDarkroomMotion(): DarkroomMotion {
    val animatorsEnabled = LocalInspectionMode.current || ValueAnimator.areAnimatorsEnabled()
    return DarkroomMotion(reducedMotion = !animatorsEnabled)
}
