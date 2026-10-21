package com.akane.akastudio.feature.studio.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Darkroom radii encode hierarchy: controls are small, cards medium, panels large and sheets the
 * roundest. `extraLarge` equals the sheet-top radius so Material bottom sheets inherit it.
 */
internal val DarkroomShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

/** Shapes with no Material 3 role. */
object DarkroomShapeTokens {
    /** Top corners of a bottom sheet; the bottom edge stays square against the screen edge. */
    val sheetTop: RoundedCornerShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
}
