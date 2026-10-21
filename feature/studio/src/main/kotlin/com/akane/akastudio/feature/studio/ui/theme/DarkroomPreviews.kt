package com.akane.akastudio.feature.studio.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import java.util.Locale

private const val SWATCHES_PER_ROW = 4
private const val RGB_MASK = 0xFFFFFF

private data class Swatch(val name: String, val color: Color)

private data class TypeSample(val name: String, val spec: String, val style: TextStyle, val text: String)

/** Every Darkroom colour token, grouped by role, with its hex value in the mono readout style. */
@Composable
internal fun DarkroomSwatchSheet(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val extended = DarkroomTheme.colors
    val spacing = DarkroomTheme.spacing
    val groups = listOf(
        "Ground" to listOf(
            Swatch("background", scheme.background),
            Swatch("surface", scheme.surface),
            Swatch("container", scheme.surfaceContainer),
            Swatch("high", scheme.surfaceContainerHigh),
            Swatch("highest", scheme.surfaceContainerHighest),
            Swatch("backdrop", extended.canvasBackdrop),
            Swatch("outline", scheme.outline),
            Swatch("outlineVar", scheme.outlineVariant),
        ),
        "Content" to listOf(
            Swatch("onSurface", scheme.onSurface),
            Swatch("onSurfVar", scheme.onSurfaceVariant),
            Swatch("primary", scheme.primary),
            Swatch("secondary", scheme.secondary),
            Swatch("tertiary", scheme.tertiary),
            Swatch("error", scheme.error),
            Swatch("errorCont", scheme.errorContainer),
            Swatch("divider", extended.divider),
        ),
        "Controls and scopes" to listOf(
            Swatch("sliderTrack", extended.sliderTrack),
            Swatch("sliderActive", extended.sliderActive),
            Swatch("scopeLuma", extended.scopeLuma),
            Swatch("scopeR", extended.scopeR),
            Swatch("scopeG", extended.scopeG),
            Swatch("scopeB", extended.scopeB),
        ),
    )
    Column(
        modifier = modifier
            .background(scheme.background)
            .padding(spacing.l),
        verticalArrangement = Arrangement.spacedBy(spacing.xl),
    ) {
        groups.forEach { (title, swatches) ->
            Column(verticalArrangement = Arrangement.spacedBy(spacing.m)) {
                SectionLabel(title)
                swatches.chunked(SWATCHES_PER_ROW).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.m)) {
                        row.forEach { SwatchChip(it) }
                    }
                }
            }
        }
    }
}

/** The Darkroom type scale, each style labelled with its size, line height and weight. */
@Composable
internal fun DarkroomTypeScale(modifier: Modifier = Modifier) {
    val type = MaterialTheme.typography
    val spacing = DarkroomTheme.spacing
    val samples = listOf(
        TypeSample("displaySmall", "34/40", type.displaySmall, "Golden hour"),
        TypeSample("headlineSmall", "22/28", type.headlineSmall, "Chosen from 24 photos"),
        TypeSample("titleMedium", "16/22 semibold", type.titleMedium, "Portra 400 Golden"),
        TypeSample("bodyMedium", "14/20", type.bodyMedium, "Three frames across three scales in warm tones."),
        TypeSample("labelMedium", "12/16 +0.6", type.labelMedium, "SECTION LABEL"),
        TypeSample("labelSmall", "11/14", type.labelSmall, "Secondary caption"),
        TypeSample("tabular", "12/16 tnum", DarkroomTextStyles.tabular, "+0.35  -12  1/250"),
        TypeSample("mono", "12/16 monospace", DarkroomTextStyles.mono, "f/1.8  1/250s  ISO 200  26mm"),
    )
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(spacing.l),
        verticalArrangement = Arrangement.spacedBy(spacing.l),
    ) {
        samples.forEach { sample ->
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Text(
                    text = "${sample.name}  ${sample.spec}",
                    style = DarkroomTextStyles.mono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = sample.text, style = sample.style, color = MaterialTheme.colorScheme.onBackground)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(Locale.ROOT),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SwatchChip(swatch: Swatch) {
    val spacing = DarkroomTheme.spacing
    val chipSize = spacing.xxl * 2
    Column(
        modifier = Modifier.width(chipSize),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Box(
            modifier = Modifier
                .size(chipSize)
                .background(swatch.color, MaterialTheme.shapes.small)
                .border(Dp.Hairline, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small),
        )
        Text(
            text = swatch.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Text(
            text = String.format(Locale.ROOT, "#%06X", swatch.color.toArgb() and RGB_MASK),
            style = DarkroomTextStyles.mono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Preview(name = "Darkroom swatches", widthDp = 360, showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable
private fun DarkroomSwatchSheetPreview() = DarkroomTheme {
    DarkroomSwatchSheet(Modifier.fillMaxWidth())
}

@Preview(name = "Darkroom type scale", widthDp = 360, showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable
private fun DarkroomTypeScalePreview() = DarkroomTheme {
    DarkroomTypeScale(Modifier.fillMaxWidth())
}

@Preview(name = "Darkroom spacing", widthDp = 360, showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable
private fun DarkroomSpacingPreview() = DarkroomTheme {
    val spacing = DarkroomTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(spacing.l),
        verticalArrangement = Arrangement.spacedBy(spacing.s),
    ) {
        listOf(
            "xs" to spacing.xs, "s" to spacing.s, "m" to spacing.m,
            "l" to spacing.l, "xl" to spacing.xl, "xxl" to spacing.xxl,
        ).forEach { (name, size) ->
            Row {
                Text(
                    text = name,
                    style = DarkroomTextStyles.mono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(spacing.xxl),
                )
                Spacer(Modifier.width(spacing.s))
                Box(
                    Modifier
                        .size(width = size, height = spacing.l)
                        .background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraSmall),
                )
            }
        }
    }
}
