package com.akane.akastudio.core.color.device

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akane.akastudio.core.color.analysis.ColorAnalysis
import com.akane.akastudio.core.color.api.GradeParams
import com.akane.akastudio.core.color.api.HarmonizationParams
import com.akane.akastudio.core.color.api.ImagePixels
import com.akane.akastudio.core.color.api.LabStats
import com.akane.akastudio.core.color.api.MasterLook
import com.akane.akastudio.core.color.api.OkLab
import com.akane.akastudio.core.color.harmonize.Harmonizer
import com.akane.akastudio.core.color.math.ColorMath
import com.akane.akastudio.core.color.render.BitmapUpload
import com.akane.akastudio.core.color.render.grade.GradeVariant
import com.akane.akastudio.core.color.render.grade.GradingParityFixtures
import com.akane.akastudio.core.color.render.grade.ShaderLayerGrader
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 3 case 3: cross-image OKLab harmonization on the GPU. Three synthetic scenes with different casts go
 * through the production upload path (opaque ARGB_8888 → SRGB8_ALPHA8 texture) and the RGBA16F grader with
 * `Harmonizer.forCollage` params; the result must match the CPU reference and pull the set together.
 */
@RunWith(AndroidJUnit4::class)
class HarmonizationDeviceTest {

    private class Scene(val name: String, val argb: IntArray)

    private class Outcome(val stats: List<LabStats>, val worstError: CodeError)

    @Test
    fun gpuHarmonizationMatchesCpuAndReducesCrossImageDeltaE() {
        val scenes = scenes()
        val inputStats = scenes.map { ColorAnalysis.labStats(ImagePixels(SIZE, SIZE, it.argb)) }
        val params = Harmonizer.forCollage(inputStats, anchorIndex = 0, strength = 1f)
        params.forEachIndexed { i, p -> report("harmonize ${scenes[i].name}: input ${describe(inputStats[i])} params $p") }

        val harmonized = renderSet(scenes, params)
        val before = crossImageDeltaE(inputStats)
        val after = crossImageDeltaE(harmonized.stats)
        val toAnchorBefore = (1 until scenes.size).map { deltaE(inputStats[it], inputStats[0]) }
        val toAnchorAfter = (1 until scenes.size).map { deltaE(harmonized.stats[it], harmonized.stats[0]) }
        report(
            "harmonize: GPU vs CPU worst ${harmonized.worstError}; mean pairwise ΔE_ok %.4f -> %.4f (%.0f%%); to anchor %s -> %s".format(
                before, after, 100f * after / before, toAnchorBefore.map { "%.4f".format(it) }, toAnchorAfter.map { "%.4f".format(it) },
            ),
        )

        assertTrue("GPU harmonization must match the CPU within ${TOLERANCE_CODES}/255: ${harmonized.worstError}", harmonized.worstError.max <= TOLERANCE_CODES)
        assertTrue("harmonization must reduce mean cross-image ΔE ($before -> $after)", after < before * 0.8f)
        for (i in toAnchorBefore.indices) {
            assertTrue("${scenes[i + 1].name} must move toward the anchor (${toAnchorBefore[i]} -> ${toAnchorAfter[i]})", toAnchorAfter[i] < toAnchorBefore[i])
        }
    }

    /** Negative control: strength 0 gives identity params, so the same GPU path and metric must not report a reduction. */
    @Test
    fun zeroStrengthLeavesCrossImageDeltaEUnchanged() {
        val scenes = scenes()
        val inputStats = scenes.map { ColorAnalysis.labStats(ImagePixels(SIZE, SIZE, it.argb)) }
        val params = Harmonizer.forCollage(inputStats, anchorIndex = 0, strength = 0f)
        assertTrue(params.all { it == HarmonizationParams.IDENTITY })
        val outcome = renderSet(scenes, params)
        val before = crossImageDeltaE(inputStats)
        val after = crossImageDeltaE(outcome.stats)
        report("harmonize control (strength 0): mean pairwise ΔE_ok %.4f -> %.4f, GPU vs CPU ${outcome.worstError}".format(before, after))
        assertTrue("identity params must leave ΔE unchanged ($before -> $after)", abs(after - before) <= before * 0.02f)
    }

    /** Grades every scene on the GPU with its params; returns output OKLab stats and the worst GPU↔CPU error. */
    private fun renderSet(scenes: List<Scene>, params: List<HarmonizationParams>): Outcome = OffscreenGl.open().use { gl ->
        val grader = ShaderLayerGrader(gl.environment(), GradeVariant.FLOAT, filmEffects = false)
        try {
            var worst = CodeError(0f, 0f, -1)
            val stats = scenes.mapIndexed { i, scene ->
                val grade = GradeParams(look = MasterLook.PURE_NATURAL_PRO, lookIntensity = 0f, harmonization = params[i])
                val bitmap = srgbBitmap(SIZE, SIZE, scene.argb)
                val texture = BitmapUpload.upload(bitmap, gl.capabilities)
                try {
                    val gpu = gpuGrade(grader, texture, grade, i)
                    val input = LinearRgbImage(SIZE, SIZE, FloatArray(SIZE * SIZE * 3) { codeToLinear(channel(scene.argb[it / 3], it % 3)) })
                    val error = encodedError(gpu, cpuExpectedEncoded(input, grade))
                    report("harmonize ${scene.name}: GPU vs CPU $error")
                    if (error.max > worst.max) worst = error
                    ColorAnalysis.labStats(ImagePixels(SIZE, SIZE, toArgb(gpu)))
                } finally {
                    texture.release()
                    bitmap.recycle()
                }
            }
            Outcome(stats, worst)
        } finally {
            grader.close()
        }
    }

    private fun toArgb(rgba: FloatArray): IntArray = IntArray(rgba.size / 4) { i ->
        val code = IntArray(3) { c -> Math.round(GradingParityFixtures.encodeClamped(rgba[i * 4 + c]) * 255f) }
        argb(code[0], code[1], code[2])
    }

    private fun crossImageDeltaE(stats: List<LabStats>): Float {
        var sum = 0f
        var pairs = 0
        for (i in stats.indices) for (j in i + 1 until stats.size) {
            sum += deltaE(stats[i], stats[j])
            pairs++
        }
        return sum / pairs
    }

    private fun deltaE(x: LabStats, y: LabStats): Float {
        val dl = x.meanL - y.meanL
        val da = x.meanA - y.meanA
        val db = x.meanB - y.meanB
        return sqrt(dl * dl + da * da + db * db)
    }

    private fun describe(s: LabStats) = "L %.3f±%.3f a %.3f±%.3f b %.3f±%.3f".format(s.meanL, s.stdL, s.meanA, s.stdA, s.meanB, s.stdB)

    private companion object {
        const val SIZE = 64

        /** Golden anchor, cool blue cast, darker green cast: each a lightness ramp with a chroma ripple. */
        fun scenes(): List<Scene> = listOf(
            scene("golden-anchor", baseL = 0.68f, baseA = 0.025f, baseB = 0.06f),
            scene("cool-blue", baseL = 0.58f, baseA = -0.02f, baseB = -0.05f),
            scene("green-dim", baseL = 0.48f, baseA = -0.045f, baseB = 0.025f),
        )

        fun scene(name: String, baseL: Float, baseA: Float, baseB: Float): Scene {
            val argb = IntArray(SIZE * SIZE)
            for (y in 0 until SIZE) for (x in 0 until SIZE) {
                val l = baseL + 0.24f * (x / (SIZE - 1f) - 0.5f)
                val a = baseA + 0.02f * sin(y * 0.3f)
                val b = baseB + 0.03f * (y / (SIZE - 1f) - 0.5f)
                argb[y * SIZE + x] = ColorMath.okLabToArgb(OkLab(l, a, b))
            }
            return Scene(name, argb)
        }
    }
}
