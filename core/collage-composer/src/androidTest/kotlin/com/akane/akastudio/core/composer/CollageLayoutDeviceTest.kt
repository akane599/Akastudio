package com.akane.akastudio.core.composer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akane.akastudio.core.composer.DeviceRender.renderLinear
import com.akane.akastudio.core.composer.api.CanvasSize
import com.akane.akastudio.core.composer.api.CompositionRequest
import com.akane.akastudio.core.composer.api.LayoutStyle
import com.akane.akastudio.core.composer.api.StoryTemplate
import com.akane.akastudio.core.composer.compose.DefaultCollageComposer
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every template composed by [DefaultCollageComposer] and rendered by the real GL renderer at 1080×1920: each cell
 * shows its own colour-coded source, the matte shows between cells, and the gutters and margins measured on the
 * rendered pixels match the [com.akane.akastudio.core.composer.layout.TemplateGeometry] spec.
 */
@RunWith(AndroidJUnit4::class)
class CollageLayoutDeviceTest {

    private val composer = DefaultCollageComposer()

    /** Landscape and portrait sources alternate so cells crop both ways. */
    private val sources = Swatch.SOURCES.mapIndexed { i, swatch ->
        val (w, h) = if (i % 2 == 0) 640 to 480 else 480 to 640
        Triple("src-${swatch.name}", swatch, DeviceRender.photo("src-${swatch.name}", w, h))
    }

    private val bitmaps = sources.associate { (key, swatch, photo) ->
        key to DeviceRender.solidBitmap(photo.widthPx, photo.heightPx, swatch.argb)
    }

    private fun request(template: StoryTemplate, style: LayoutStyle = LayoutStyle()) = CompositionRequest(
        template = template,
        style = style,
        canvas = CanvasSize.STORY_1080,
        photos = sources.take(template.photoCount).map { it.third },
        grades = emptyMap(),
    )

    @Test
    fun everyTemplateRendersEachCellFromItsOwnSource() = DeviceRender.withRenderer(bitmaps) { renderer ->
        for (template in StoryTemplate.entries) {
            val request = request(template)
            val image = renderer.renderLinear(composer.compose(request))
            assertEquals("$template width", 1080, image.width)
            assertEquals("$template height", 1920, image.height)

            composer.layout(request).forEachIndexed { slot, cell ->
                val expected = sources[slot].second
                val d = cell.dst
                // Centre plus the four quarter points of the cell.
                for ((fx, fy) in listOf(0.5f to 0.5f, 0.25f to 0.25f, 0.75f to 0.25f, 0.25f to 0.75f, 0.75f to 0.75f)) {
                    val x = (d.left + fx * d.width).toInt()
                    val y = (d.top + fy * d.height).toInt()
                    assertEquals(
                        "$template slot $slot at ($x,$y) ${image.describe(x, y)}",
                        expected,
                        image.classify(x, y),
                    )
                }
                // 3 px inside each edge is the source; 3 px outside is the matte unless the edge is the canvas edge.
                val midX = ((d.left + d.right) / 2).toInt()
                val midY = ((d.top + d.bottom) / 2).toInt()
                val edges = listOf(
                    Triple(d.left.toInt() + 3, midY, d.left.toInt() - 3 to midY),
                    Triple(d.right.toInt() - 4, midY, d.right.toInt() + 3 to midY),
                    Triple(midX, d.top.toInt() + 3, midX to d.top.toInt() - 3),
                    Triple(midX, d.bottom.toInt() - 4, midX to d.bottom.toInt() + 3),
                )
                for ((ix, iy, outside) in edges) {
                    assertEquals("$template slot $slot inside edge ($ix,$iy) ${image.describe(ix, iy)}", expected, image.classify(ix, iy))
                    val (ox, oy) = outside
                    if (ox in 0 until image.width && oy in 0 until image.height) {
                        assertEquals(
                            "$template slot $slot outside edge ($ox,$oy) ${image.describe(ox, oy)}",
                            Swatch.BACKGROUND,
                            image.classify(ox, oy),
                        )
                    }
                }
            }
            DeviceRender.log("$template rendered; slot centres OK")
        }
    }

    @Test
    fun quadGridGuttersAndMarginsMatchTheSpecAtTwoGutterWidths() = DeviceRender.withRenderer(bitmaps) { renderer ->
        for (g in listOf(12, 24)) {
            val image = renderer.renderLinear(composer.compose(request(StoryTemplate.QUAD_GRID, LayoutStyle(gutterPx = g.toFloat()))))
            // Spec: outer edges inset by g, shared edges by g/2 each side, on the 1080×1920 base.
            val half = g / 2
            assertRuns(
                "QUAD_GRID g=$g row 480",
                runsAlongRow(image, 480),
                listOf(
                    Run(Swatch.BACKGROUND, 0, g),
                    Run(Swatch.RED, g, 540 - half),
                    Run(Swatch.BACKGROUND, 540 - half, 540 + half),
                    Run(Swatch.GREEN, 540 + half, 1080 - g),
                    Run(Swatch.BACKGROUND, 1080 - g, 1080),
                ),
            )
            assertRuns(
                "QUAD_GRID g=$g column 810",
                runsAlongColumn(image, 810),
                listOf(
                    Run(Swatch.BACKGROUND, 0, g),
                    Run(Swatch.GREEN, g, 960 - half),
                    Run(Swatch.BACKGROUND, 960 - half, 960 + half),
                    Run(Swatch.YELLOW, 960 + half, 1920 - g),
                    Run(Swatch.BACKGROUND, 1920 - g, 1920),
                ),
            )
        }
    }

    @Test
    fun duoCinematicSplitAndFullBleedHeroMatchTheSpec() = DeviceRender.withRenderer(bitmaps) { renderer ->
        val duo = renderer.renderLinear(composer.compose(request(StoryTemplate.DUO_CINEMATIC_SPLIT)))
        assertRuns(
            "DUO_CINEMATIC_SPLIT column 540",
            runsAlongColumn(duo, 540),
            listOf(
                Run(Swatch.BACKGROUND, 0, 12),
                Run(Swatch.RED, 12, 954),
                Run(Swatch.BACKGROUND, 954, 966),
                Run(Swatch.GREEN, 966, 1908),
                Run(Swatch.BACKGROUND, 1908, 1920),
            ),
        )
        assertRuns("DUO_CINEMATIC_SPLIT row 480", runsAlongRow(duo, 480), listOf(Run(Swatch.BACKGROUND, 0, 12), Run(Swatch.RED, 12, 1068), Run(Swatch.BACKGROUND, 1068, 1080)))

        // Full bleed ignores the gutter: the photo covers the whole canvas.
        val hero = renderer.renderLinear(composer.compose(request(StoryTemplate.HERO_FULL_BLEED, LayoutStyle(gutterPx = 48f))))
        assertRuns("HERO_FULL_BLEED row 0", runsAlongRow(hero, 0), listOf(Run(Swatch.RED, 0, 1080)))
        assertRuns("HERO_FULL_BLEED column 1079", runsAlongColumn(hero, 1079), listOf(Run(Swatch.RED, 0, 1920)))
    }

    private data class Run(val swatch: Swatch, val start: Int, val end: Int)

    private fun runsAlongRow(image: LinearImage, y: Int) = runs(image.width) { image.classify(it, y) }

    private fun runsAlongColumn(image: LinearImage, x: Int) = runs(image.height) { image.classify(x, it) }

    /** Runs of equal class; runs of up to [AA_PIXELS] pixels are anti-aliased edge transitions and dropped. */
    private fun runs(length: Int, classAt: (Int) -> Swatch): List<Run> {
        val all = ArrayList<Run>()
        var start = 0
        for (i in 1..length) {
            if (i == length || classAt(i) != classAt(start)) {
                all += Run(classAt(start), start, i)
                start = i
            }
        }
        return all.filter { it.end - it.start > AA_PIXELS }
    }

    private fun assertRuns(label: String, actual: List<Run>, expected: List<Run>) {
        if (actual.map { it.swatch } != expected.map { it.swatch }) fail("$label: expected $expected, got $actual")
        DeviceRender.log("$label runs $actual")
        actual.zip(expected).forEach { (a, e) ->
            assertTrue("$label: $a starts within 1 px of ${e.start} (all: $actual)", abs(a.start - e.start) <= 1)
            assertTrue("$label: $a ends within 1 px of ${e.end} (all: $actual)", abs(a.end - e.end) <= 1)
        }
    }

    private companion object {
        const val AA_PIXELS = 2
    }
}
