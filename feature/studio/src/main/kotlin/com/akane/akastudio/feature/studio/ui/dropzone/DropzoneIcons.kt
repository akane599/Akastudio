package com.akane.akastudio.feature.studio.ui.dropzone

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

private const val VIEWPORT = 24f
private const val STROKE = 1.5f

/**
 * The Dropzone's line icons, drawn on a 24-unit grid with one 1.5-unit round stroke so they read as one set.
 * The stroke colour is a placeholder: `Icon` tints every path with its own colour.
 */
internal object DropzoneIcons {

    /** Two stacked frames, the front one holding a horizon and a sun. */
    val Photos: ImageVector by lazy {
        lineIcon(
            "Dropzone.Photos",
            "M5 8h12a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-9a2 2 0 0 1 2-2z",
            "M7 5h12a2 2 0 0 1 2 2v9",
            "M3.5 18.5l4.5-4.5 3.5 3.5 2-2 4.5 4.5",
            "M15.5 12a1.5 1.5 0 1 1-3 0a1.5 1.5 0 1 1 3 0z",
        )
    }

    /** An archive with zipper teeth and a pull tab. */
    val Archive: ImageVector by lazy {
        lineIcon(
            "Dropzone.Archive",
            "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z",
            "M10.5 4.5h1.5M12 6.5h1.5M10.5 8.5h1.5M12 10.5h1.5",
            "M10.5 12.5h3v4a1 1 0 0 1-1 1h-1a1 1 0 0 1-1-1z",
        )
    }

    /** A check mark for a completed pipeline step. */
    val Check: ImageVector by lazy {
        lineIcon("Dropzone.Check", "M5 12.5l4.5 4.5L19 7.5")
    }

    private fun lineIcon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = VIEWPORT.dp,
            defaultHeight = VIEWPORT.dp,
            viewportWidth = VIEWPORT,
            viewportHeight = VIEWPORT,
        ).apply {
            paths.forEach { data ->
                addPath(
                    pathData = addPathNodes(data),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = STROKE,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
}
