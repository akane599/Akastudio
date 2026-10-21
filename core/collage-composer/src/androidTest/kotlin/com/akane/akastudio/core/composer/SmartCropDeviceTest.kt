package com.akane.akastudio.core.composer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.composer.DeviceRender.renderLinear
import com.akane.akastudio.core.composer.api.CanvasSize
import com.akane.akastudio.core.composer.api.ComposerPhoto
import com.akane.akastudio.core.composer.api.CompositionRequest
import com.akane.akastudio.core.composer.api.LayoutStyle
import com.akane.akastudio.core.composer.api.StoryTemplate
import com.akane.akastudio.core.composer.compose.DefaultCollageComposer
import com.akane.akastudio.core.curator.api.FaceObservation
import com.akane.akastudio.core.curator.api.SaliencyMap
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A red subject box near a source edge, marked as a face or as the salient region, must land fully inside the
 * rendered cell: every red pixel the box should produce is present and the red bounding box keeps clear of the
 * cell edges. The negative control renders the same source with no face and flat saliency, where the centred
 * crop cuts the box out entirely, so the checks can tell a kept box from a lost one.
 */
@RunWith(AndroidJUnit4::class)
class SmartCropDeviceTest {

    private val composer = DefaultCollageComposer()

    @Test
    fun faceNearLeftEdgeOfLandscapeStaysInsidePortraitHero() {
        val box = PixelBox(40, 480, 200, 660)
        val photo = DeviceRender.photo("face-left", 1600, 1200, faces = listOf(face(box, 1600, 1200)))
        val result = renderSlot(StoryTemplate.HERO_FULL_BLEED, listOf(photo), mapOf(photo.id to boxBitmap(1600, 1200, box)), 0)
        assertBoxFullyInside("face-left", result, box, 1600, 1200)
    }

    @Test
    fun salientBoxNearRightEdgeOfLandscapeStaysInsidePortraitHero() {
        val box = PixelBox(1420, 300, 1560, 440)
        val photo = DeviceRender.photo("salient-right", 1600, 1200, saliency = saliencyAt(box, 1600, 1200))
        val result = renderSlot(StoryTemplate.HERO_FULL_BLEED, listOf(photo), mapOf(photo.id to boxBitmap(1600, 1200, box)), 0)
        assertBoxFullyInside("salient-right", result, box, 1600, 1200)
    }

    @Test
    fun faceNearBottomEdgeOfPortraitStaysInsideWideCollageCell() {
        val box = PixelBox(540, 1380, 720, 1540)
        val photo = DeviceRender.photo("face-bottom", 1200, 1600, faces = listOf(face(box, 1200, 1600)))
        val filler = DeviceRender.photo("filler", 640, 480)
        val images = mapOf(
            photo.id to boxBitmap(1200, 1600, box),
            filler.id to DeviceRender.solidBitmap(640, 480, GREY),
        )
        val result = renderSlot(StoryTemplate.DUO_CINEMATIC_SPLIT, listOf(photo, filler), images, 0)
        assertBoxFullyInside("face-bottom", result, box, 1200, 1600)
    }

    @Test
    fun negativeControlWithoutFaceOrSaliencyTheCentredCropLosesTheBox() {
        val box = PixelBox(40, 480, 200, 660)
        val photo = DeviceRender.photo("no-hint", 1600, 1200)
        val result = renderSlot(StoryTemplate.HERO_FULL_BLEED, listOf(photo), mapOf(photo.id to boxBitmap(1600, 1200, box)), 0)
        val red = redPixels(result)
        DeviceRender.log("negative control: crop=${result.crop} red pixels=${red.count}")
        assertEquals("a centred crop must cut the edge box out, crop=${result.crop}", 0, red.count)
    }

    private class SlotRender(val image: LinearImage, val left: Int, val top: Int, val right: Int, val bottom: Int, val crop: NormRect)

    private data class PixelBox(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private class RedStats(val count: Int, val minX: Int, val minY: Int, val maxX: Int, val maxY: Int)

    private fun renderSlot(template: StoryTemplate, photos: List<ComposerPhoto>, images: Map<String, Bitmap>, slot: Int): SlotRender {
        val request = CompositionRequest(template, LayoutStyle(), CanvasSize.STORY_1080, photos, grades = emptyMap())
        val placement = composer.layout(request)[slot]
        assertEquals("unrotated source", 0f, placement.rotationDegrees)
        val image = DeviceRender.withRenderer(images) { it.renderLinear(composer.compose(request)) }
        val d = placement.dst
        return SlotRender(image, d.left.toInt(), d.top.toInt(), d.right.toInt(), d.bottom.toInt(), placement.crop)
    }

    private fun redPixels(r: SlotRender): RedStats {
        var count = 0
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (y in r.top until r.bottom) {
            for (x in r.left until r.right) {
                if (r.image.classify(x, y) != Swatch.RED) continue
                count++
                minX = minOf(minX, x)
                minY = minOf(minY, y)
                maxX = maxOf(maxX, x)
                maxY = maxOf(maxY, y)
            }
        }
        return RedStats(count, minX, minY, maxX, maxY)
    }

    private fun assertBoxFullyInside(label: String, r: SlotRender, box: PixelBox, srcW: Int, srcH: Int) {
        val cellW = (r.right - r.left).toFloat()
        val cellH = (r.bottom - r.top).toFloat()
        fun outX(px: Int) = r.left + (px.toFloat() / srcW - r.crop.left) / r.crop.width * cellW
        fun outY(py: Int) = r.top + (py.toFloat() / srcH - r.crop.top) / r.crop.height * cellH
        val expLeft = outX(box.left)
        val expRight = outX(box.right)
        val expTop = outY(box.top)
        val expBottom = outY(box.bottom)
        val expectedArea = (expRight - expLeft) * (expBottom - expTop)
        val red = redPixels(r)
        DeviceRender.log(
            "$label crop=${r.crop} cell=[${r.left},${r.top},${r.right},${r.bottom}] expected box=" +
                "[%.1f,%.1f,%.1f,%.1f] area=%.0f; red count=${red.count} bbox=[${red.minX},${red.minY},${red.maxX},${red.maxY}]"
                    .format(expLeft, expTop, expRight, expBottom, expectedArea),
        )
        assertTrue("$label: box rendered at all", red.count > 0)
        // The crop keeps the box inside the frame, with at least a pixel of clearance on every side.
        assertTrue("$label: box left ${red.minX} clear of cell left ${r.left}", red.minX > r.left)
        assertTrue("$label: box top ${red.minY} clear of cell top ${r.top}", red.minY > r.top)
        assertTrue("$label: box right ${red.maxX} clear of cell right ${r.right}", red.maxX < r.right - 1)
        assertTrue("$label: box bottom ${red.maxY} clear of cell bottom ${r.bottom}", red.maxY < r.bottom - 1)
        // And none of it is cut: the rendered red matches the whole box mapped through the crop.
        assertTrue("$label: red left ${red.minX} vs ${expLeft}", abs(red.minX - expLeft) <= 2f)
        assertTrue("$label: red right ${red.maxX + 1} vs ${expRight}", abs(red.maxX + 1 - expRight) <= 2f)
        assertTrue("$label: red top ${red.minY} vs ${expTop}", abs(red.minY - expTop) <= 2f)
        assertTrue("$label: red bottom ${red.maxY + 1} vs ${expBottom}", abs(red.maxY + 1 - expBottom) <= 2f)
        assertTrue("$label: red area ${red.count} >= 97% of $expectedArea", red.count >= 0.97f * expectedArea)
    }

    private fun boxBitmap(width: Int, height: Int, box: PixelBox): Bitmap {
        val bitmap = DeviceRender.solidBitmap(width, height, GREY)
        Canvas(bitmap).drawRect(
            box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat(),
            Paint().apply { color = Swatch.RED.argb },
        )
        return bitmap
    }

    private fun face(box: PixelBox, width: Int, height: Int) = FaceObservation(
        box = NormRect(box.left.toFloat() / width, box.top.toFloat() / height, box.right.toFloat() / width, box.bottom.toFloat() / height),
        leftEyeOpenness = 1f,
        rightEyeOpenness = 1f,
        sharpness = 1f,
    )

    /** Long-edge-64 saliency: 1 over cells whose centre falls in [box], near zero elsewhere. */
    private fun saliencyAt(box: PixelBox, width: Int, height: Int): SaliencyMap {
        val w = if (width >= height) 64 else 48
        val h = if (width >= height) 48 else 64
        val values = FloatArray(w * h) { i ->
            val cx = (i % w + 0.5f) / w * width
            val cy = (i / w + 0.5f) / h * height
            if (cx in box.left.toFloat()..box.right.toFloat() && cy in box.top.toFloat()..box.bottom.toFloat()) 1f else 0.01f
        }
        return SaliencyMap(w, h, values)
    }

    private companion object {
        val GREY = 0xFF808080.toInt()
    }
}
