package com.akane.akastudio.core.color.device

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akane.akastudio.core.color.api.GradeParams
import com.akane.akastudio.core.color.api.MasterLook
import com.akane.akastudio.core.color.grade.LookLutCache
import com.akane.akastudio.core.color.grade.LookLuts
import com.akane.akastudio.core.color.look.Lut3D
import com.akane.akastudio.core.color.math.ColorMath
import com.akane.akastudio.core.color.render.grade.GradeVariant
import com.akane.akastudio.core.color.render.grade.GradingParityFixtures
import com.akane.akastudio.core.color.render.grade.ShaderLayerGrader
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 3 case 2: GPU `ShaderLayerGrader` (RGBA16F, stages 1–6) against the `CpuGradingPipeline` reference
 * on a colour chart plus gradients, for every look LUT, the neutral default and the identity grade.
 */
@RunWith(AndroidJUnit4::class)
class GradingParityDeviceTest {

    @Test
    fun everyLookNeutralAndIdentityMatchTheCpuWithinTwoCodes() {
        OffscreenGl.open().use { gl ->
            assertTrue("parity needs RGBA16F targets", gl.capabilities.floatColorBuffer)
            val image = chartAndGradients()
            val grader = ShaderLayerGrader(gl.environment(), GradeVariant.FLOAT, filmEffects = false)
            val source = image.upload()
            try {
                val failures = ArrayList<String>()
                cases().forEachIndexed { slot, (name, params) ->
                    val error = encodedError(gpuGrade(grader, source, params, slot), cpuExpectedEncoded(image, params))
                    report("parity $name: $error")
                    if (error.max > TOLERANCE_CODES) failures += "$name $error"
                }
                assertTrue("GPU grading exceeds ${TOLERANCE_CODES}/255: $failures", failures.isEmpty())
            } finally {
                source.release()
                grader.close()
            }
        }
    }

    @Test
    fun identityGradeIsReallyTheIdentityOnTheCpuReference() {
        val image = chartAndGradients()
        val expected = cpuExpectedEncoded(image, IDENTITY)
        var worst = 0f
        for (i in image.rgb.indices) worst = maxOf(worst, kotlin.math.abs(expected[i] - ColorMath.linearToSrgb(image.rgb[i])) * 255f)
        report("identity reference: max deviation from input %.4f/255".format(worst))
        assertTrue("lookIntensity 0 with neutral sliders must reproduce the input, got $worst/255", worst <= 0.5f)
    }

    /** Negative control: the same comparison must flag a GPU render checked against a deliberately wrong LUT. */
    @Test
    fun comparisonDetectsADeliberatelyWrongLut() {
        OffscreenGl.open().use { gl ->
            val image = chartAndGradients()
            val params = GradeParams(look = MasterLook.KODACHROME_64_CLASSIC)
            val grader = ShaderLayerGrader(gl.environment(), GradeVariant.FLOAT, filmEffects = false)
            val source = image.upload()
            try {
                val gpu = gpuGrade(grader, source, params, 0)
                val real = LookLutCache.get(params.look)
                val correct = encodedError(gpu, cpuExpectedEncoded(image, params, real))
                val shifted = encodedError(gpu, cpuExpectedEncoded(image, params, LookLuts(offset(real.look, SHIFT), offset(real.skinSafe, SHIFT))))
                val swapped = encodedError(gpu, cpuExpectedEncoded(image, params, LookLutCache.get(MasterLook.MEDITERRANEAN_AZURE)))
                report("negative control: correct LUT $correct; LUT +${SHIFT * 255}/255 $shifted; Azure LUT $swapped")
                assertTrue("correct LUT must pass: $correct", correct.max <= TOLERANCE_CODES)
                assertTrue("a LUT off by ${SHIFT * 255}/255 must fail the 2/255 check: $shifted", shifted.max > TOLERANCE_CODES)
                assertTrue("another look's LUT must fail the 2/255 check: $swapped", swapped.max > TOLERANCE_CODES)
            } finally {
                source.release()
                grader.close()
            }
        }
    }

    private fun cases(): List<Pair<String, GradeParams>> = buildList {
        add("identity" to IDENTITY)
        add("neutral" to GradeParams.NEUTRAL)
        MasterLook.entries.forEach { add("look-${it.name}" to GradeParams(look = it)) }
        GradingParityFixtures.CASES.forEach { add("fixture-${it.name}" to it.params) }
    }

    /** Every lattice node moved by [delta] (clamped to [0, 1]). */
    private fun offset(lut: Lut3D, delta: Float): Lut3D =
        Lut3D(lut.size, FloatArray(lut.data.size) { (lut.data[it] + delta).coerceIn(0f, 1f) })

    private companion object {
        /** No look, no sliders: stages 1–6 reduce to the identity. */
        val IDENTITY = GradeParams(lookIntensity = 0f)

        const val SHIFT = 4f / 255f
        const val WIDTH = 64

        /**
         * 64 × 12 image: rows 0–3 are the 256 chart colours of `GradingParityFixtures` (cube, greys, skin
         * tones), rows 4–11 are 64-step encoded ramps of grey, R, G, B, C, M, Y and an OKLCh skin-hue ramp.
         */
        fun chartAndGradients(): LinearRgbImage {
            val chart = GradingParityFixtures.gridLinear()
            val rampDirections = listOf(
                floatArrayOf(1f, 1f, 1f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f),
                floatArrayOf(0f, 1f, 1f), floatArrayOf(1f, 0f, 1f), floatArrayOf(1f, 1f, 0f),
            )
            val rows = GradingParityFixtures.COLOR_COUNT / WIDTH + rampDirections.size + 1
            val rgb = FloatArray(WIDTH * rows * 3)
            chart.copyInto(rgb)
            var p = chart.size
            for (direction in rampDirections) {
                for (x in 0 until WIDTH) {
                    val t = x / (WIDTH - 1f)
                    for (c in 0 until 3) rgb[p++] = roundHalf(ColorMath.srgbToLinear(t * direction[c]))
                }
            }
            for (x in 0 until WIDTH) {
                val skin = ColorMath.okLabToArgb(ColorMath.okLchToOkLab(0.35f + 0.55f * x / (WIDTH - 1f), 0.07f, 50f))
                for (c in 0 until 3) rgb[p++] = codeToLinear(channel(skin, c))
            }
            check(p == rgb.size)
            return LinearRgbImage(WIDTH, rows, rgb)
        }
    }
}
