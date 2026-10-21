package com.akane.akastudio.feature.studio.ui.controls

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.akane.akastudio.core.composer.api.BackgroundStyle
import com.akane.akastudio.core.composer.api.LayoutStyle
import com.akane.akastudio.core.composer.api.StoryTemplate
import com.akane.akastudio.feature.studio.R
import com.akane.akastudio.feature.studio.mvi.StudioIntent
import com.akane.akastudio.feature.studio.mvi.StudioReducer
import com.akane.akastudio.feature.studio.mvi.StudioState
import com.akane.akastudio.feature.studio.ui.theme.DarkroomTheme

/** Background choices in display order. */
private val BACKGROUNDS: List<BackgroundStyle> = listOf(
    BackgroundStyle.OffWhite,
    BackgroundStyle.Anthracite,
    BackgroundStyle.PaletteDerived,
    BackgroundStyle.FrostedGlass,
)

/**
 * Layout: the template carousel for the current mode's photo count, gutter and corner-radius sliders, the
 * canvas background and the EXIF caption switch.
 */
@Composable
internal fun LayoutTab(state: StudioState, onIntent: (StudioIntent) -> Unit) {
    val spacing = DarkroomTheme.spacing
    val layout = state.layout
    val defaults = LayoutStyle()
    val patterns = readoutPatterns()
    val templates = StoryTemplate.forCount(state.selection.mode?.photoCount ?: 0)
    Column(verticalArrangement = Arrangement.spacedBy(spacing.l)) {
        if (templates.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.s)) {
                SectionLabel(stringResource(R.string.controls_template_title), Modifier.padding(horizontal = spacing.l))
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectableGroup(),
                    contentPadding = PaddingValues(horizontal = spacing.l),
                    horizontalArrangement = Arrangement.spacedBy(spacing.s),
                ) {
                    items(templates, key = { it.name }) { template ->
                        TemplateTile(
                            template = template,
                            selected = template == state.template,
                            onClick = { onIntent(StudioIntent.SelectTemplate(template)) },
                        )
                    }
                }
            }
        }
        Column(
            modifier = Modifier.padding(horizontal = spacing.l),
            verticalArrangement = Arrangement.spacedBy(spacing.m),
        ) {
            ControlSlider(
                label = stringResource(R.string.controls_gutter),
                readout = SliderFormat.format(SliderFormat.pixels(layout.gutterPx), patterns),
                value = layout.gutterPx,
                range = 0f..StudioReducer.MAX_GUTTER_PX,
                bipolar = false,
                resetValue = defaults.gutterPx,
                onValueChange = { onIntent(StudioIntent.SetGutter(it)) },
            )
            ControlSlider(
                label = stringResource(R.string.controls_corner_radius),
                readout = SliderFormat.format(SliderFormat.pixels(layout.cornerRadiusPx), patterns),
                value = layout.cornerRadiusPx,
                range = 0f..StudioReducer.MAX_CORNER_RADIUS_PX,
                bipolar = false,
                resetValue = defaults.cornerRadiusPx,
                onValueChange = { onIntent(StudioIntent.SetCornerRadius(it)) },
            )
            Column(verticalArrangement = Arrangement.spacedBy(spacing.s)) {
                SectionLabel(stringResource(R.string.controls_background))
                SegmentedControl(
                    options = BACKGROUNDS.map { stringResource(it.labelRes()) },
                    selectedIndex = BACKGROUNDS.indexOf(layout.background),
                    onSelect = { onIntent(StudioIntent.SetBackground(BACKGROUNDS[it])) },
                    // Four segments share the row: one compact line each, wrapping only at large font sizes.
                    labelStyle = MaterialTheme.typography.labelMedium,
                    maxLines = if (isLargeFont()) 2 else 1,
                )
            }
            SwitchRow(
                title = stringResource(R.string.controls_exif_caption),
                summary = stringResource(R.string.controls_exif_caption_summary),
                checked = layout.showExifCaption,
                onCheckedChange = { onIntent(StudioIntent.SetExifCaption(it)) },
            )
        }
    }
}

@Composable
private fun TemplateTile(template: StoryTemplate, selected: Boolean, onClick: () -> Unit) {
    val spacing = DarkroomTheme.spacing
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    Column(
        modifier = Modifier
            .width(ControlMetrics.WireframeTileWidth)
            .clip(shape)
            .background(if (selected) scheme.surfaceContainerHighest else scheme.surfaceContainerLow)
            .border(
                width = if (selected) ControlMetrics.SelectedOutline else ControlMetrics.Hairline,
                color = if (selected) scheme.primary else scheme.outlineVariant,
                shape = shape,
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(spacing.s),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.s),
    ) {
        Wireframe(template, selected)
        Text(
            text = stringResource(template.nameRes()),
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            // Two reserved lines keep every template tile the same height whether its name wraps or not.
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The template's fixed miniature: a 9:16 frame with its cells; selection raises the cells' luminance. */
@Composable
private fun Wireframe(template: StoryTemplate, selected: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val frame = scheme.surfaceContainerLowest
    val cell = if (selected) scheme.secondary else scheme.outline
    val cells = TemplateWireframes.cells(template)
    Canvas(Modifier.size(ControlMetrics.WireframeWidth, ControlMetrics.WireframeHeight)) {
        val radius = CornerRadius(ControlMetrics.Hairline.toPx() * 2)
        drawRoundRect(frame, cornerRadius = radius)
        cells.forEach { r ->
            drawRoundRect(
                color = cell,
                topLeft = Offset(r.left * size.width, r.top * size.height),
                size = Size((r.right - r.left) * size.width, (r.bottom - r.top) * size.height),
                cornerRadius = radius,
            )
        }
    }
}

@StringRes
private fun BackgroundStyle.labelRes(): Int = when (this) {
    BackgroundStyle.OffWhite -> R.string.controls_background_off_white
    BackgroundStyle.Anthracite -> R.string.controls_background_anthracite
    BackgroundStyle.PaletteDerived -> R.string.controls_background_palette
    BackgroundStyle.FrostedGlass -> R.string.controls_background_frosted
}

@StringRes
private fun StoryTemplate.nameRes(): Int = when (this) {
    StoryTemplate.HERO_FULL_BLEED -> R.string.template_hero_full_bleed
    StoryTemplate.HERO_GALLERY_MATTE -> R.string.template_hero_gallery_matte
    StoryTemplate.DUO_CINEMATIC_SPLIT -> R.string.template_duo_cinematic_split
    StoryTemplate.DUO_ASYMMETRIC_EDITORIAL -> R.string.template_duo_asymmetric_editorial
    StoryTemplate.DUO_FLOATING_GALLERY -> R.string.template_duo_floating_gallery
    StoryTemplate.TRIO_BENTO -> R.string.template_trio_bento
    StoryTemplate.TRIO_CINEMA_STRIPS -> R.string.template_trio_cinema_strips
    StoryTemplate.TRIO_MAGAZINE -> R.string.template_trio_magazine
    StoryTemplate.QUAD_GRID -> R.string.template_quad_grid
    StoryTemplate.QUAD_HERO_FILMSTRIP -> R.string.template_quad_hero_filmstrip
    StoryTemplate.QUAD_MOSAIC -> R.string.template_quad_mosaic
}
