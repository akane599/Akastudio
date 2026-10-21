package com.akane.akastudio.core.curator.ml

import com.akane.akastudio.core.color.api.ImagePixels
import com.akane.akastudio.core.color.api.SceneType
import com.akane.akastudio.core.color.math.ColorMath
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Deterministic OKLab scene heuristic, used when the ImageNet classifier is unavailable or not confident.
 *
 * Rules are tested in order and the first match wins:
 * 1. [SceneType.BEACH_SEA]: more than half of the upper third is blue (OKLab b < −0.03) and more than
 *    30% of the lower third is sandy (hue 60–90°, L > 0.6).
 * 2. [SceneType.NIGHT_CITY]: mean L < 0.28 with at least 0.5% point lights (L > 0.9).
 * 3. [SceneType.NATURE_FOREST]: more than 35% of pixels are green (hue 120–160°, C > 0.05).
 * 4. [SceneType.CAFE_INTERIOR]: warm low key, mean L < 0.5 with the mean (a, b) cast at hue 40–80° and
 *    chroma > 0.04.
 * 5. [SceneType.GENERAL] otherwise.
 *
 * Hue tests on individual pixels also require chroma > 0.02, below which OKLab hue is numerically
 * meaningless. [SceneType.PORTRAIT] and [SceneType.ARCHITECTURE_AUTO] are never produced here: the
 * curation engine sets PORTRAIT from detected faces.
 */
object HeuristicSceneClassifier {

    /** Classifies [pixels] by the ordered rules above. */
    fun classify(pixels: ImagePixels): SceneType {
        val s = SceneStats.of(pixels)
        return when {
            s.upperBlueFraction > BLUE_DOMINANT && s.lowerSandFraction > SAND_PRESENT -> SceneType.BEACH_SEA
            s.meanL < NIGHT_MEAN_L && s.pointLightFraction >= POINT_LIGHT_FRACTION -> SceneType.NIGHT_CITY
            s.greenFraction > GREEN_DOMINANT -> SceneType.NATURE_FOREST
            s.meanL < CAFE_MEAN_L && s.castHue in CAFE_HUE_MIN..CAFE_HUE_MAX && s.castChroma > CAFE_CHROMA ->
                SceneType.CAFE_INTERIOR
            else -> SceneType.GENERAL
        }
    }

    /** Mean OKLab L over the same stride-sampled pixels [classify] uses. */
    internal fun meanL(pixels: ImagePixels): Float = SceneStats.of(pixels).meanL

    private const val BLUE_B = -0.03f
    private const val BLUE_DOMINANT = 0.5f
    private const val SAND_HUE_MIN = 60f
    private const val SAND_HUE_MAX = 90f
    private const val SAND_L = 0.6f
    private const val SAND_PRESENT = 0.3f
    private const val NIGHT_MEAN_L = 0.28f
    private const val POINT_LIGHT_L = 0.9f
    private const val POINT_LIGHT_FRACTION = 0.005f
    private const val GREEN_HUE_MIN = 120f
    private const val GREEN_HUE_MAX = 160f
    private const val GREEN_CHROMA = 0.05f
    private const val GREEN_DOMINANT = 0.35f
    private const val CAFE_MEAN_L = 0.5f
    private const val CAFE_HUE_MIN = 40f
    private const val CAFE_HUE_MAX = 80f
    private const val CAFE_CHROMA = 0.04f
    private const val HUE_MIN_CHROMA = 0.02f

    /** Upper bound on analysed samples; larger images are stride-sampled (never averaged, to keep point lights). */
    private const val MAX_SAMPLES = 65_536

    private class SceneStats(
        val meanL: Float,
        val upperBlueFraction: Float,
        val lowerSandFraction: Float,
        val pointLightFraction: Float,
        val greenFraction: Float,
        val castHue: Float,
        val castChroma: Float,
    ) {
        companion object {
            fun of(pixels: ImagePixels): SceneStats {
                val step = samplingStep(pixels.width, pixels.height)
                val rows = (pixels.height + step - 1) / step
                val acc = Accumulator()
                var row = 0
                var y = 0
                while (y < pixels.height) {
                    val band = when {
                        row * 3 < rows -> Band.UPPER
                        row * 3 >= rows * 2 -> Band.LOWER
                        else -> Band.MIDDLE
                    }
                    var x = 0
                    while (x < pixels.width) {
                        acc.add(pixels.argb[y * pixels.width + x], band)
                        x += step
                    }
                    row++
                    y += step
                }
                return acc.toStats()
            }

            private fun samplingStep(width: Int, height: Int): Int {
                val total = width.toLong() * height
                if (total <= MAX_SAMPLES) return 1
                return ceil(sqrt(total.toDouble() / MAX_SAMPLES)).toInt()
            }
        }
    }

    private enum class Band { UPPER, MIDDLE, LOWER }

    private class Accumulator {
        private var count = 0
        private var upper = 0
        private var lower = 0
        private var sumL = 0.0
        private var sumA = 0.0
        private var sumB = 0.0
        private var upperBlue = 0
        private var lowerSand = 0
        private var pointLights = 0
        private var green = 0

        fun add(argb: Int, band: Band) {
            val lab = ColorMath.argbToOkLab(argb)
            val chroma = hypot(lab.a, lab.b)
            val hue = hueDegrees(lab.a, lab.b)
            count++
            sumL += lab.l
            sumA += lab.a
            sumB += lab.b
            if (lab.l > POINT_LIGHT_L) pointLights++
            if (chroma > GREEN_CHROMA && hue in GREEN_HUE_MIN..GREEN_HUE_MAX) green++
            when (band) {
                Band.UPPER -> {
                    upper++
                    if (lab.b < BLUE_B) upperBlue++
                }
                Band.LOWER -> {
                    lower++
                    if (lab.l > SAND_L && chroma > HUE_MIN_CHROMA && hue in SAND_HUE_MIN..SAND_HUE_MAX) lowerSand++
                }
                Band.MIDDLE -> Unit
            }
        }

        fun toStats(): SceneStats {
            val meanA = (sumA / count).toFloat()
            val meanB = (sumB / count).toFloat()
            return SceneStats(
                meanL = (sumL / count).toFloat(),
                upperBlueFraction = fraction(upperBlue, upper),
                lowerSandFraction = fraction(lowerSand, lower),
                pointLightFraction = fraction(pointLights, count),
                greenFraction = fraction(green, count),
                castHue = hueDegrees(meanA, meanB),
                castChroma = hypot(meanA, meanB),
            )
        }

        private fun fraction(part: Int, whole: Int): Float = if (whole == 0) 0f else part.toFloat() / whole
    }

    /** OKLCh hue in [0, 360). */
    internal fun hueDegrees(a: Float, b: Float): Float {
        var h = Math.toDegrees(atan2(b.toDouble(), a.toDouble()))
        if (h < 0.0) h += 360.0
        return if (h >= 360.0) 0f else h.toFloat()
    }
}
