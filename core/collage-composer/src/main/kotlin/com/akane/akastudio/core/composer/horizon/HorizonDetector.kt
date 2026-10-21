package com.akane.akastudio.core.composer.horizon

import com.akane.akastudio.core.color.api.ImagePixels
import kotlin.math.abs

/**
 * Estimates how far a photo's horizon (or its architectural verticals) is tilted.
 *
 * Pipeline: Rec.601 gray (box-downscaled so the long edge is at most [MAX_ANALYSIS_EDGE]), Sobel magnitude and
 * direction, non-max suppression, hysteresis at the 90th/97th magnitude percentiles, then magnitude-weighted Hough
 * transforms restricted to ±7° of horizontal and ±7° of vertical at 0.1° and 1 px resolution. The horizontal peak
 * is used when it beats the vertical peak by more than 1.2×; otherwise the median deviation of the confident
 * vertical peaks among the top three. A peak is confident above 2.5× its family's mean accumulator value with a
 * line at least 35% of the image dimension long.
 *
 * Sign: the result is the angle that levels the image when passed as the renderer rotation, where positive degrees
 * turn the image clockwise on screen. Content that appears rotated counter-clockwise (a horizon rising to the
 * right) therefore yields a positive tilt.
 */
object HorizonDetector {
    /** Long edge of the analysis image; larger inputs are box-averaged down by an integer factor. */
    internal const val MAX_ANALYSIS_EDGE = 1024
    internal const val MIN_TILT_DEGREES = 0.2f
    internal const val MAX_TILT_DEGREES = 5f
    private const val MIN_ANALYSIS_EDGE = 16
    private const val HORIZONTAL_DOMINANCE = 1.2
    private const val VERTICAL_PEAKS = 3

    /** Tilt in degrees with `|result| <= 5`; 0 when the image is level within 0.2°, out of range or unsure. */
    fun detectTiltDegrees(pixels: ImagePixels): Float {
        val gray = GrayImage.of(pixels, MAX_ANALYSIS_EDGE)
        if (gray.width < MIN_ANALYSIS_EDGE || gray.height < MIN_ANALYSIS_EDGE) return 0f
        val edges = EdgeMap.detect(gray)
        if (edges.size == 0) return 0f
        val horizontal = HoughFamily(edges, gray.width, gray.height, normalDegrees = 90.0)
        val vertical = HoughFamily(edges, gray.width, gray.height, normalDegrees = 0.0)
        val horizontalPeak = horizontal.topPeaks(1).firstOrNull()
        val verticalPeaks = vertical.topPeaks(VERTICAL_PEAKS)
        val horizontalStrength = horizontalPeak?.value ?: 0.0
        val verticalStrength = verticalPeaks.firstOrNull()?.value ?: 0.0
        val deviation = if (horizontalPeak != null && horizontalStrength > HORIZONTAL_DOMINANCE * verticalStrength) {
            if (!horizontal.isConfident(horizontalPeak)) return 0f
            horizontalPeak.deviationDegrees
        } else {
            val confident = verticalPeaks.filter(vertical::isConfident).map { it.deviationDegrees }.sorted()
            if (confident.isEmpty()) return 0f
            median(confident)
        }
        // A line whose normal deviates by d has direction angle d (y down); turning it by -d levels it.
        val tilt = (-deviation).toFloat()
        return if (abs(tilt) < MIN_TILT_DEGREES || abs(tilt) > MAX_TILT_DEGREES) 0f else tilt
    }

    private fun median(sorted: List<Double>): Double {
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }
}
