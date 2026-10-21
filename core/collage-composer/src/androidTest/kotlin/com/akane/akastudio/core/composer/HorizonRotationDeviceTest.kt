package com.akane.akastudio.core.composer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akane.akastudio.core.color.api.ImagePixels
import com.akane.akastudio.core.composer.DeviceRender.renderLinear
import com.akane.akastudio.core.composer.api.CanvasSize
import com.akane.akastudio.core.composer.api.CompositionRequest
import com.akane.akastudio.core.composer.api.LayoutStyle
import com.akane.akastudio.core.composer.api.StoryTemplate
import com.akane.akastudio.core.composer.compose.DefaultCollageComposer
import com.akane.akastudio.core.composer.horizon.HorizonDetector
import kotlin.math.abs
import kotlin.math.tan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The SQ-16 sign convention end to end on device: a horizon rising to the right is detected as a positive
 * (clockwise) tilt, and the real GL renderer, given that tilt through the composer, draws it level to within ±1 px
 * across the 1080 px frame. Rotating the other way (the negative control) doubles the slope instead.
 */
@RunWith(AndroidJUnit4::class)
class HorizonRotationDeviceTest {

    private val composer = DefaultCollageComposer()

    @Test
    fun risingHorizonCorrectedByDetectedTiltRendersLevel() {
        val source = horizonBitmap(SOURCE_W, SOURCE_H, TILT_DEGREES)
        val analysis = Bitmap.createScaledBitmap(source, 1024, 768, true)
        val pixels = IntArray(analysis.width * analysis.height).also { analysis.getPixels(it, 0, analysis.width, 0, 0, analysis.width, analysis.height) }
        val detected = HorizonDetector.detectTiltDegrees(ImagePixels(analysis.width, analysis.height, pixels))
        DeviceRender.log("horizon drawn rising right at $TILT_DEGREES deg; detected tilt $detected deg")
        assertTrue("rising-right horizon detects a positive (clockwise) tilt, got $detected", detected > 0f)
        assertEquals("detected tilt magnitude", TILT_DEGREES, detected, 0.2f)

        val uncorrected = measure(source, 0f)
        val corrected = measure(source, detected)
        val opposite = measure(source, -detected)
        DeviceRender.log(
            "slopes px/px: uncorrected=%.5f corrected=%.5f opposite=%.5f (tan %.1f deg = %.5f); corrected max |dev|=%.3f px over %d px"
                .format(uncorrected.slope, corrected.slope, opposite.slope, TILT_DEGREES, tan(Math.toRadians(TILT_DEGREES.toDouble())), corrected.maxDeviation, corrected.span),
        )

        // Rising to the right means the line's row decreases left to right in the unrotated render.
        assertEquals("uncorrected slope is the drawn tilt", -tan(Math.toRadians(TILT_DEGREES.toDouble())), uncorrected.slope, 0.006)
        assertTrue("corrected horizon is level within ±1 px over ${corrected.span} px: ${corrected.maxDeviation}", corrected.maxDeviation <= 1.0)
        assertTrue("corrected span covers most of the frame: ${corrected.span}", corrected.span >= 1000)
        // Negative control: the opposite sign rotates the wrong way and doubles the tilt.
        val ratio = opposite.slope / uncorrected.slope
        assertTrue("opposite sign doubles the tilt: ratio $ratio", ratio in 1.8..2.2)
        assertTrue("opposite sign is far from level: ${opposite.maxDeviation} px", opposite.maxDeviation > 20.0)
        // The rotated crop is inset, so no matte shows in the frame's corners.
        for ((x, y) in listOf(1 to 1, 1078 to 1, 1 to 1918, 1078 to 1918)) {
            assertTrue("corner ($x,$y) shows the photo, not the matte: ${corrected.image.describe(x, y)}", corrected.image.luminance(x, y) > 0.5f)
        }
    }

    private class Line(val image: LinearImage, val slope: Double, val maxDeviation: Double, val span: Int)

    /** Renders the source full bleed with [tilt] and fits the dark line's centre row across the frame. */
    private fun measure(source: Bitmap, tilt: Float): Line {
        val photo = DeviceRender.photo("horizon", SOURCE_W, SOURCE_H, tiltDegrees = tilt)
        val request = CompositionRequest(StoryTemplate.HERO_FULL_BLEED, LayoutStyle(), CanvasSize.STORY_1080, listOf(photo), emptyMap())
        val image = DeviceRender.withRenderer(mapOf(photo.id to source)) { it.renderLinear(composer.compose(request)) }
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        for (x in EDGE_SKIP until image.width - EDGE_SKIP step 8) {
            var weight = 0.0
            var moment = 0.0
            for (y in 0 until image.height) {
                val darkness = (DARK_THRESHOLD - image.luminance(x, y)).toDouble()
                if (darkness > 0) {
                    weight += darkness
                    moment += darkness * y
                }
            }
            if (weight > 0) {
                xs += x.toDouble()
                ys += moment / weight
            }
        }
        assertTrue("line found in most columns (${xs.size})", xs.size > (image.width - 2 * EDGE_SKIP) / 8 - 3)
        val meanX = xs.average()
        val meanY = ys.average()
        val slope = xs.indices.sumOf { (xs[it] - meanX) * (ys[it] - meanY) } / xs.sumOf { (it - meanX) * (it - meanX) }
        val maxDeviation = ys.maxOf { abs(it - meanY) }
        return Line(image, slope, maxDeviation, (xs.last() - xs.first()).toInt())
    }

    /** White source with a 6 px black line through the centre, rising to the right at [degrees]. */
    private fun horizonBitmap(width: Int, height: Int, degrees: Float): Bitmap {
        val bitmap = DeviceRender.solidBitmap(width, height, Color.WHITE)
        val rise = tan(Math.toRadians(degrees.toDouble())).toFloat() * width / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            strokeWidth = 6f
        }
        Canvas(bitmap).drawLine(-width.toFloat(), height / 2f + 3 * rise, 2f * width, height / 2f - 3 * rise, paint)
        return bitmap
    }

    private companion object {
        const val SOURCE_W = 1600
        const val SOURCE_H = 1200
        const val TILT_DEGREES = 3f
        const val EDGE_SKIP = 16
        const val DARK_THRESHOLD = 0.5f
    }
}
