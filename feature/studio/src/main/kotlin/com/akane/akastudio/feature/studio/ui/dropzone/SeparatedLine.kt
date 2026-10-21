package com.akane.akastudio.feature.studio.ui.dropzone

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle

/**
 * [items] laid out like one wrapping line of text joined by [separator] (for example "JPEG · HEIC · PNG"), except
 * that where a row wraps the separator is dropped, so a "·" never strands at the end or start of a line at large
 * font scales. Each item stays whole. Accessibility reads the joined phrase as one text.
 */
@Composable
internal fun SeparatedLine(
    items: List<String>,
    separator: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (items.size <= 1) {
        Text(text = items.firstOrNull().orEmpty(), style = style, color = color, modifier = modifier)
        return
    }
    val phrase = items.joinToString(separator)
    Layout(
        content = {
            items.forEach { Text(text = it, style = style, color = color) }
            repeat(items.size - 1) { Text(text = separator, style = style, color = color) }
        },
        modifier = modifier.clearAndSetSemantics { text = AnnotatedString(phrase) },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val words = placeables.subList(0, items.size)
        val separators = placeables.subList(items.size, placeables.size)
        val rows = wrapRows(words, separators, constraints.maxWidth)
        val width = rows.maxOf { row -> row.sumOf { it.width } }.coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = rows.sumOf { row -> row.maxOf { it.height } }.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            var y = 0
            rows.forEach { row ->
                var x = 0
                row.forEach { placeable ->
                    placeable.placeRelative(x, y)
                    x += placeable.width
                }
                y += row.maxOf { it.height }
            }
        }
    }
}

/**
 * Greedy rows: item `i` joins the current row together with the separator before it only when both fit in
 * [maxWidth]; otherwise it starts a new row and that separator is left unplaced.
 */
private fun wrapRows(words: List<Placeable>, separators: List<Placeable>, maxWidth: Int): List<List<Placeable>> {
    val rows = mutableListOf(mutableListOf(words.first()))
    var rowWidth = words.first().width
    for (i in 1 until words.size) {
        val joined = separators[i - 1].width + words[i].width
        if (rowWidth + joined <= maxWidth) {
            rows.last() += separators[i - 1]
            rows.last() += words[i]
            rowWidth += joined
        } else {
            rows += mutableListOf(words[i])
            rowWidth = words[i].width
        }
    }
    return rows
}
