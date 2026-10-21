package com.akane.akastudio.feature.studio.ui.controls

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.akane.akastudio.core.color.api.OkLab
import com.akane.akastudio.core.color.api.PaletteSwatch
import com.akane.akastudio.core.color.math.ColorMath
import com.akane.akastudio.core.curator.api.PhotoAnalysis
import com.akane.akastudio.core.curator.api.RejectReason
import com.akane.akastudio.core.curator.api.ShotScale
import com.akane.akastudio.core.curator.api.StoryMode
import com.akane.akastudio.core.export.api.ExportStage
import com.akane.akastudio.feature.studio.R
import com.akane.akastudio.feature.studio.ui.theme.DarkroomTextStyles
import com.akane.akastudio.feature.studio.ui.theme.DarkroomTheme

/**
 * Component sizes of the studio controls that have no spacing or shape token. Everything else (padding, gaps,
 * radii, colours, type) comes from the Darkroom theme.
 */
internal object ControlMetrics {
    /** Minimum touch target for every interactive control. */
    val TouchTarget: Dp = 48.dp

    /** Toolbar icon glyph size. */
    val IconSize: Dp = 20.dp

    /** Hairline outline width. */
    val Hairline: Dp = 1.dp

    /** Selection outline width (selection is shown by luminance, never hue). */
    val SelectedOutline: Dp = 2.dp

    /** Palette-swatch thumbnail in the mode drawer (portrait 3:4). */
    val MiniThumbWidth: Dp = 18.dp
    val MiniThumbHeight: Dp = 24.dp

    /** Story slot card thumbnail in the curation tab (portrait 4:5). */
    val SlotThumbWidth: Dp = 72.dp
    val SlotThumbHeight: Dp = 90.dp

    /** Burst alternative thumbnail (portrait 3:4). */
    val BurstThumbWidth: Dp = 54.dp
    val BurstThumbHeight: Dp = 72.dp

    /** Rejected photo thumbnail (portrait 3:4). */
    val RejectedThumbWidth: Dp = 42.dp
    val RejectedThumbHeight: Dp = 56.dp

    /** Palette strip under a slot thumbnail. */
    val PaletteStripHeight: Dp = 6.dp

    /** Template wireframe miniature (9:16). */
    val WireframeWidth: Dp = 45.dp
    val WireframeHeight: Dp = 80.dp
    val WireframeTileWidth: Dp = 88.dp

    /** Master-look carousel tile and its swatch chips. */
    val LookTileWidth: Dp = 124.dp
    val LookSwatch: Dp = 22.dp

    /** Meter (synergy and export progress) bar thickness. */
    val MeterHeight: Dp = 3.dp

    /** Slider track, centre tick and thumb. The track matches [MeterHeight] so both boundaries read alike. */
    val TrackHeight: Dp = 3.dp
    val CenterTickHeight: Dp = 10.dp
    val ThumbSize: Dp = 14.dp

    /** Tab indicator thickness. */
    val TabIndicator: Dp = 2.dp

    /** Collapsed control panel height, its drag handle included: the tab row and the top of the active tab. */
    val PanelPeek: Dp = 240.dp

    /** The panel's drag-handle pill. */
    val HandleWidth: Dp = 32.dp
    val HandleThickness: Dp = 4.dp

    /** The first-use compare hint: its widest bubble and the caret pointing at the compare tool. */
    val HintMaxWidth: Dp = 240.dp
    val HintCaretWidth: Dp = 12.dp
    val HintCaretHeight: Dp = 6.dp
}

/**
 * Ink for non-text boundaries: the inactive slider track, the meter remainder and outlined-button borders.
 * The theme's Outline (#2C2F34) reaches only 1.4:1 on the panel (#141518) and no theme tone sits between it and
 * Tertiary, so these boundaries use Tertiary, the lowest theme tone that clears 1.8:1 (5.3:1).
 */
internal val boundaryColor: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.tertiary

/** Font scale from which compact controls switch to their large-font layout. */
internal const val LARGE_FONT_SCALE = 1.3f

/** Whether the user's font scale calls for the large-font layout of compact controls. */
@Composable
@ReadOnlyComposable
internal fun isLargeFont(): Boolean = LocalDensity.current.fontScale >= LARGE_FONT_SCALE

/** Alpha of thumbnails for rejected photos: still recognisable, clearly out of the story. */
internal const val REJECTED_ALPHA = 0.55f

/** OKLab to an opaque Compose colour (gamut-clipped sRGB). */
internal fun OkLab.toColor(): Color = Color(ColorMath.okLabToArgb(this))

/** The colour of the photo's heaviest palette swatch, or null when it has no palette. */
internal fun PhotoAnalysis.dominantColor(): Color? = palette.maxByOrNull { it.weight }?.lab?.toColor()

/** A section label: quiet, sentence case, never upper case. */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * A photo stand-in filled with its dominant palette colour. A missing analysis draws an outlined empty frame.
 */
@Composable
internal fun SwatchThumb(analysis: PhotoAnalysis?, width: Dp, height: Dp, modifier: Modifier = Modifier) {
    val shape = MaterialTheme.shapes.extraSmall
    val color = analysis?.dominantColor()
    val base = modifier.size(width = width, height = height).clip(shape)
    Box(
        modifier = if (color != null) {
            base.background(color)
        } else {
            base.border(ControlMetrics.Hairline, MaterialTheme.colorScheme.outline, shape)
        },
    )
}

/** The photo's palette as a strip, each swatch as wide as its weight. */
@Composable
internal fun PaletteStrip(palette: List<PaletteSwatch>, modifier: Modifier = Modifier) {
    val total = palette.sumOf { it.weight.toDouble() }.toFloat()
    Row(
        modifier = modifier
            .height(ControlMetrics.PaletteStripHeight)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        if (total > 0f) {
            palette.filter { it.weight > 0f }.forEach { swatch ->
                Box(
                    Modifier
                        .weight(swatch.weight / total)
                        .height(ControlMetrics.PaletteStripHeight)
                        .background(swatch.lab.toColor()),
                )
            }
        }
    }
}

/**
 * A small label chip. [emphasized] fills it with the primary (paper white) ink; otherwise it sits on the
 * highest container tone.
 */
@Composable
internal fun TagChip(text: String, modifier: Modifier = Modifier, emphasized: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val spacing = DarkroomTheme.spacing
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (emphasized) scheme.onPrimary else scheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(if (emphasized) scheme.primary else scheme.surfaceContainerHighest)
            .padding(horizontal = spacing.s - spacing.xs / 2, vertical = spacing.xs / 2),
    )
}

/**
 * A thin achromatic meter for a 0..1 [fraction]. With [progressSemantics] it is announced as a determinate
 * progress bar; otherwise it is decorative and the caller labels the value in text.
 */
@Composable
internal fun MeterBar(fraction: Float, modifier: Modifier = Modifier, progressSemantics: Boolean = false) {
    val f = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
    val colors = DarkroomTheme.colors
    val semanticsModifier = if (progressSemantics) {
        Modifier.semantics { progressBarRangeInfo = ProgressBarRangeInfo(f, 0f..1f) }
    } else {
        Modifier.clearAndSetSemantics { }
    }
    Box(
        modifier = modifier
            .then(semanticsModifier)
            .height(ControlMetrics.MeterHeight)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(boundaryColor),
    ) {
        if (f > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(f)
                    .height(ControlMetrics.MeterHeight)
                    .background(colors.sliderActive),
            )
        }
    }
}

/**
 * A single-choice segmented control. The selected segment is shown by fill and luminance only. Labels wrap to
 * at most [maxLines] lines; the default of two keeps long translations and large fonts from truncating.
 */
@Composable
internal fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    labelStyle: TextStyle = MaterialTheme.typography.labelLarge,
    maxLines: Int = 2,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(scheme.surfaceContainer)
            .border(ControlMetrics.Hairline, scheme.outlineVariant, shape)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = ControlMetrics.TouchTarget)
                    .clip(shape)
                    .background(if (selected) scheme.surfaceContainerHighest else scheme.surfaceContainer)
                    .selectable(
                        selected = selected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onSelect(index) },
                    )
                    .padding(horizontal = DarkroomTheme.spacing.xs, vertical = DarkroomTheme.spacing.xs),
            ) {
                Text(
                    text = label,
                    style = labelStyle,
                    color = when {
                        !enabled -> scheme.onSurfaceVariant.copy(alpha = DISABLED_ALPHA)
                        selected -> scheme.onSurface
                        else -> scheme.onSurfaceVariant
                    },
                    textAlign = TextAlign.Center,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A full-width switch row: the whole row toggles, the switch itself is not separately focusable. */
@Composable
internal fun SwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val spacing = DarkroomTheme.spacing
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ControlMetrics.TouchTarget)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.m),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xs / 2)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) scheme.onSurface else scheme.onSurfaceVariant,
            )
            Text(text = summary, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** A tabular-figure value readout. */
@Composable
internal fun ValueReadout(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = DarkroomTextStyles.tabular,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        modifier = modifier,
    )
}

/** Alpha for disabled text, matching Material's disabled content emphasis. */
internal const val DISABLED_ALPHA = 0.38f

/** Localized readout patterns from `strings_controls.xml`. */
@Composable
@ReadOnlyComposable
internal fun readoutPatterns(): ReadoutPatterns = ReadoutPatterns(
    percent = stringResource(R.string.controls_value_percent),
    kelvin = stringResource(R.string.controls_value_kelvin),
    pixels = stringResource(R.string.controls_value_px),
)

/** A 0..1 [fraction] as a localized whole percentage. */
@Composable
@ReadOnlyComposable
internal fun percentText(fraction: Float): String =
    SliderFormat.format(Readout(ReadoutKind.PERCENT, SliderFormat.percentOf(fraction)), readoutPatterns())

@StringRes
internal fun StoryMode.nameRes(): Int = when (this) {
    StoryMode.HERO -> R.string.mode_hero
    StoryMode.COLLAGE_2 -> R.string.mode_collage_2
    StoryMode.COLLAGE_3 -> R.string.mode_collage_3
    StoryMode.COLLAGE_4 -> R.string.mode_collage_4
}

@StringRes
internal fun ShotScale.nameRes(): Int = when (this) {
    ShotScale.WIDE -> R.string.shot_scale_wide
    ShotScale.MEDIUM -> R.string.shot_scale_medium
    ShotScale.DETAIL -> R.string.shot_scale_detail
}

@StringRes
internal fun RejectReason.labelRes(): Int = when (this) {
    RejectReason.BLURRY -> R.string.controls_reject_blurry
    RejectReason.EYES_CLOSED -> R.string.controls_reject_eyes_closed
    RejectReason.BAD_EXPOSURE -> R.string.controls_reject_exposure
    RejectReason.UNREADABLE -> R.string.controls_reject_unreadable
}

@StringRes
internal fun ExportStage.labelRes(): Int = when (this) {
    ExportStage.RENDER -> R.string.controls_stage_render
    ExportStage.RESAMPLE -> R.string.controls_stage_resample
    ExportStage.SHARPEN -> R.string.controls_stage_sharpen
    ExportStage.DITHER -> R.string.controls_stage_dither
    ExportStage.ENCODE -> R.string.controls_stage_encode
    ExportStage.WRITE -> R.string.controls_stage_write
}

/** Accessibility description for an image-only element. */
internal fun Modifier.describedAs(description: String): Modifier = semantics { contentDescription = description }
