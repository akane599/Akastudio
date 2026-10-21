package com.akane.akastudio.feature.studio.ui.controls

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import com.akane.akastudio.feature.studio.mvi.ScopeMode

private const val VIEWPORT = 24f
private const val STROKE = 1.6f

/**
 * The studio's single icon set: thin 1.6-unit strokes on a 24-unit grid with round caps, drawn here so the
 * module needs no icon library. `Icon` tints them, so the ink colour below is only a placeholder for the tint.
 */
internal object ControlIcons {

    /** Disclosure chevron of the mode chip. */
    val ChevronDown: ImageVector by lazy {
        icon("chevron_down") {
            stroke {
                moveTo(7f, 10f)
                lineTo(12f, 15f)
                lineTo(17f, 10f)
            }
        }
    }

    /** Selection check of the mode drawer's chosen row. */
    val Check: ImageVector by lazy {
        icon("check") {
            stroke {
                moveTo(5f, 12.5f)
                lineTo(10f, 17.5f)
                lineTo(19f, 7.5f)
            }
        }
    }

    /** Before/after split: a frame with the left half filled. */
    val Compare: ImageVector by lazy {
        icon("compare") {
            stroke {
                moveTo(5f, 5f)
                lineTo(19f, 5f)
                lineTo(19f, 19f)
                lineTo(5f, 19f)
                close()
                moveTo(12f, 3f)
                lineTo(12f, 21f)
            }
            fill {
                moveTo(5f, 5f)
                lineTo(12f, 5f)
                lineTo(12f, 19f)
                lineTo(5f, 19f)
                close()
            }
        }
    }

    /** Luma waveform: one trace over a baseline. */
    val ScopeLuma: ImageVector by lazy {
        icon("scope_luma") {
            stroke {
                moveTo(3f, 15f)
                lineTo(6.5f, 10f)
                lineTo(9.5f, 13f)
                lineTo(12.5f, 6.5f)
                lineTo(15.5f, 12f)
                lineTo(18f, 9f)
                lineTo(21f, 13f)
                moveTo(3f, 19.5f)
                lineTo(21f, 19.5f)
            }
        }
    }

    /** RGB parade: three side-by-side channel columns. */
    val ScopeParade: ImageVector by lazy {
        icon("scope_parade") {
            stroke {
                moveTo(4f, 6f)
                lineTo(4f, 18f)
                moveTo(7f, 9f)
                lineTo(7f, 18f)
                moveTo(10.5f, 5f)
                lineTo(10.5f, 18f)
                moveTo(13.5f, 10f)
                lineTo(13.5f, 18f)
                moveTo(17f, 8f)
                lineTo(17f, 18f)
                moveTo(20f, 12f)
                lineTo(20f, 18f)
            }
        }
    }

    /** Histogram: filled bars rising to a peak. */
    val ScopeHistogram: ImageVector by lazy {
        icon("scope_histogram") {
            fill {
                bar(3.5f, 15f)
                bar(7.5f, 10f)
                bar(11.5f, 6f)
                bar(15.5f, 11f)
                bar(19.5f, 14.5f)
            }
            stroke {
                moveTo(3f, 20f)
                lineTo(21f, 20f)
            }
        }
    }

    /** Export: an arrow rising out of a tray. */
    val Export: ImageVector by lazy {
        icon("export") {
            stroke {
                moveTo(12f, 15f)
                lineTo(12f, 4f)
                moveTo(8f, 8f)
                lineTo(12f, 4f)
                lineTo(16f, 8f)
                moveTo(5f, 13f)
                lineTo(5f, 19f)
                lineTo(19f, 19f)
                lineTo(19f, 13f)
            }
        }
    }

    /** The glyph of the scope button for [mode]; OFF shows the luma glyph unfilled. */
    fun scope(mode: ScopeMode): ImageVector = when (mode) {
        ScopeMode.OFF, ScopeMode.LUMA_WAVEFORM -> ScopeLuma
        ScopeMode.RGB_PARADE -> ScopeParade
        ScopeMode.HISTOGRAM -> ScopeHistogram
    }
}

private const val BAR_WIDTH = 2.4f
private const val BAR_BASE = 19f

private fun PathBuilder.bar(left: Float, top: Float) {
    moveTo(left, top)
    lineTo(left + BAR_WIDTH, top)
    lineTo(left + BAR_WIDTH, BAR_BASE)
    lineTo(left, BAR_BASE)
    close()
}

private class IconScope(val builder: ImageVector.Builder) {
    fun stroke(block: PathBuilder.() -> Unit) {
        builder.path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = STROKE,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }

    fun fill(block: PathBuilder.() -> Unit) {
        builder.path(fill = SolidColor(Color.Black), pathBuilder = block)
    }
}

private fun icon(name: String, block: IconScope.() -> Unit): ImageVector {
    val builder = ImageVector.Builder(
        name = name,
        defaultWidth = ControlMetrics.IconSize,
        defaultHeight = ControlMetrics.IconSize,
        viewportWidth = VIEWPORT,
        viewportHeight = VIEWPORT,
    )
    IconScope(builder).block()
    return builder.build()
}
