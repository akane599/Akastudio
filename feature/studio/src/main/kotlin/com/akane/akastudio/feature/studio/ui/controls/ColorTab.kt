package com.akane.akastudio.feature.studio.ui.controls

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import com.akane.akastudio.core.color.api.MasterLook
import com.akane.akastudio.core.color.look.LookLibrary
import com.akane.akastudio.core.color.math.ColorMath
import com.akane.akastudio.feature.studio.R
import com.akane.akastudio.feature.studio.mvi.GradeField
import com.akane.akastudio.feature.studio.mvi.StudioIntent
import com.akane.akastudio.feature.studio.mvi.StudioState
import com.akane.akastudio.feature.studio.ui.theme.DarkroomTheme

/** OKLab lightness of the shadow and highlight chips of a look's swatch pair. */
private const val SHADOW_L = 0.36f
private const val HIGHLIGHT_L = 0.84f

/**
 * Look split-tones are deliberately faint (chroma 0.006–0.03); the chips scale them up so the character reads at
 * chip size, capped well inside sRGB.
 */
private const val CHIP_CHROMA_GAIN = 3f
private const val CHIP_CHROMA_MAX = 0.09f

/**
 * Colour & atmosphere: the eight master looks (the curator's suggestion tagged "AI") and the grade sliders.
 * Every slider step sends `AdjustGrade`; the GPU re-grades only when a parameter changes.
 */
@Composable
internal fun ColorTab(state: StudioState, onIntent: (StudioIntent) -> Unit) {
    val spacing = DarkroomTheme.spacing
    val grade = state.grade
    val patterns = readoutPatterns()
    val suggested = state.curation?.suggestedLook
    Column(verticalArrangement = Arrangement.spacedBy(spacing.l)) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.s)) {
            SectionLabel(stringResource(R.string.controls_look_title), Modifier.padding(horizontal = spacing.l))
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
                contentPadding = PaddingValues(horizontal = spacing.l),
                horizontalArrangement = Arrangement.spacedBy(spacing.s),
            ) {
                items(MasterLook.entries, key = { it.name }) { look ->
                    LookTile(
                        look = look,
                        selected = look == grade.look,
                        suggested = look == suggested,
                        onClick = { onIntent(StudioIntent.SelectLook(look)) },
                    )
                }
            }
        }
        Column(
            modifier = Modifier.padding(horizontal = spacing.l),
            verticalArrangement = Arrangement.spacedBy(spacing.s),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(stringResource(R.string.controls_grade_title), Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.controls_reset_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            GradeField.entries.forEach { field ->
                val value = grade.value(field)
                ControlSlider(
                    label = stringResource(field.labelRes()),
                    readout = SliderFormat.format(SliderFormat.readout(field, value), patterns),
                    value = value,
                    range = field.min..field.max,
                    bipolar = SliderFormat.isBipolar(field),
                    resetValue = SliderFormat.defaultValue(field),
                    onValueChange = { onIntent(StudioIntent.AdjustGrade(field, it)) },
                )
            }
        }
    }
}

@Composable
private fun LookTile(look: MasterLook, selected: Boolean, suggested: Boolean, onClick: () -> Unit) {
    val spacing = DarkroomTheme.spacing
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    val (shadow, highlight) = lookChips(look)
    Column(
        modifier = Modifier
            .widthIn(min = ControlMetrics.LookTileWidth)
            .width(IntrinsicSize.Min)
            .clip(shape)
            .background(if (selected) scheme.surfaceContainerHighest else scheme.surfaceContainerLow)
            .border(
                width = if (selected) ControlMetrics.SelectedOutline else ControlMetrics.Hairline,
                color = if (selected) scheme.primary else scheme.outlineVariant,
                shape = shape,
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(spacing.s),
        verticalArrangement = Arrangement.spacedBy(spacing.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                Chip(shadow)
                Chip(highlight, Modifier.padding(start = ControlMetrics.LookSwatch - spacing.xs))
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                if (suggested) TagChip(stringResource(R.string.controls_tag_ai), emphasized = selected)
            }
        }
        Text(
            text = stringResource(look.nameRes()),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
            // Two reserved lines keep one-line names on tiles as tall as their two-line neighbours.
            minLines = 2,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Chip(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(ControlMetrics.LookSwatch)
            .clip(CircleShape)
            .background(color)
            .border(ControlMetrics.Hairline, MaterialTheme.colorScheme.surfaceContainerLow, CircleShape),
    )
}

/** The look's shadow and highlight split-tone colours from its authored spec, lifted to chip visibility. */
private fun lookChips(look: MasterLook): Pair<Color, Color> {
    val split = LookLibrary.spec(look).split
    fun chip(lightness: Float, chroma: Float, hue: Float): Color =
        ColorMath.okLchToOkLab(lightness, (chroma * CHIP_CHROMA_GAIN).coerceAtMost(CHIP_CHROMA_MAX), hue).toColor()
    return chip(SHADOW_L, split.shadowChroma, split.shadowHueDeg) to
        chip(HIGHLIGHT_L, split.highlightChroma, split.highlightHueDeg)
}

@StringRes
private fun GradeField.labelRes(): Int = when (this) {
    GradeField.INTENSITY -> R.string.controls_grade_intensity
    GradeField.HARMONIZATION_STRENGTH -> R.string.controls_grade_harmonization
    GradeField.TEMPERATURE -> R.string.controls_grade_temperature
    GradeField.TINT -> R.string.controls_grade_tint
    GradeField.CONTRAST -> R.string.controls_grade_contrast
    GradeField.SATURATION -> R.string.controls_grade_saturation
    GradeField.HALATION -> R.string.controls_grade_halation
    GradeField.GRAIN -> R.string.controls_grade_grain
    GradeField.SKIN_PROTECTION -> R.string.controls_grade_skin_protection
}

@StringRes
private fun MasterLook.nameRes(): Int = when (this) {
    MasterLook.PORTRA_400_GOLDEN -> R.string.look_portra_400_golden
    MasterLook.MEDITERRANEAN_AZURE -> R.string.look_mediterranean_azure
    MasterLook.ESPRESSO_WARM_WOOD -> R.string.look_espresso_warm_wood
    MasterLook.CINESTILL_800T_NIGHT -> R.string.look_cinestill_800t_night
    MasterLook.NORDIC_SLATE_MATTE -> R.string.look_nordic_slate_matte
    MasterLook.KODACHROME_64_CLASSIC -> R.string.look_kodachrome_64_classic
    MasterLook.VELVET_MONOCHROME_HP5 -> R.string.look_velvet_monochrome_hp5
    MasterLook.PURE_NATURAL_PRO -> R.string.look_pure_natural_pro
}
