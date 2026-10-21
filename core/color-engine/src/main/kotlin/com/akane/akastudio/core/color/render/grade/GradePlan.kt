package com.akane.akastudio.core.color.render.grade

import com.akane.akastudio.core.color.api.GradeParams
import com.akane.akastudio.core.color.api.HarmonizationParams
import com.akane.akastudio.core.color.api.MasterLook
import com.akane.akastudio.core.color.grade.FilmGrain
import com.akane.akastudio.core.color.grade.Halation
import com.akane.akastudio.core.color.grade.WhiteBalance
import com.akane.akastudio.core.color.look.LookLibrary
import com.akane.akastudio.core.color.render.PixelRect
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * [GradeParams] resolved into shader uniforms with exactly `CpuGradingPipeline`'s validation and clamping:
 * non-finite inputs throw [IllegalArgumentException]; `lookIntensity`, `skinProtection`, `halation`, `grain`
 * clamp to [0, 1] and `contrast`, `saturation` to [-1, 1]. The look's default halation and grain are added
 * before clamping, as the CPU [com.akane.akastudio.core.color.grade.CpuGradingPipeline.grade] does.
 */
internal class GradeUniforms private constructor(
    val look: MasterLook,
    /** False when the harmonization is [HarmonizationParams.IDENTITY]; the CPU skips the stage then. */
    val harmonize: Boolean,
    /** OKLab (L, a, b) scale and offset of stage 1. */
    val harmonizeScale: FloatArray,
    val harmonizeOffset: FloatArray,
    /** Linear multiplier `2^exposureEv`. */
    val exposure: Float,
    /** [WhiteBalance.matrix] in column-major order for `glUniformMatrix3fv`. */
    val whiteBalance: FloatArray,
    val contrast: Float,
    val lookIntensity: Float,
    val skinProtection: Float,
    /** OKLCh chroma multiplier `max(0, 1 + saturation)`. */
    val chromaScale: Float,
    /** Additive halation gain (look default + slider, clamped, times [Halation.STRENGTH]); 0 = off. */
    val halationAmount: Float,
    /** Grain amount (look default + slider, clamped); 0 = off. */
    val grainAmount: Float,
    /** Lattice spacing in output pixels. */
    val grainSizePx: Float,
    /** OKLab L noise gain `amount·FilmGrain.STRENGTH`. */
    val grainLuma: Float,
    /** OKLab a/b noise gain `grainLuma·FilmGrain.COLOR_RATIO·grainChroma`. */
    val grainChromaGain: Float,
) {
    /** Whether any stage after the per-pixel pass has work to do. */
    val hasFilmEffects: Boolean get() = halationAmount > 0f || grainAmount > 0f

    companion object {
        fun from(params: GradeParams): GradeUniforms {
            val spec = LookLibrary.spec(params.look)
            val h = params.harmonization
            listOf(h.scaleL, h.offsetL, h.scaleA, h.offsetA, h.scaleB, h.offsetB).forEach { finite(it, "harmonization") }
            val wb = WhiteBalance.matrix(params.temperature, params.tint)
            val halation = finite(params.halation, "halation").coerceIn(0f, 1f)
            val grain = finite(params.grain, "grain").coerceIn(0f, 1f)
            val grainAmount = (spec.defaultGrain + grain).coerceIn(0f, 1f)
            val grainLuma = grainAmount * FilmGrain.STRENGTH
            return GradeUniforms(
                look = params.look,
                harmonize = h != HarmonizationParams.IDENTITY,
                harmonizeScale = floatArrayOf(h.scaleL, h.scaleA, h.scaleB),
                harmonizeOffset = floatArrayOf(h.offsetL, h.offsetA, h.offsetB),
                exposure = 2.0.pow(finite(params.exposureEv, "exposureEv").toDouble()).toFloat(),
                whiteBalance = FloatArray(9) { i -> wb[(i % 3) * 3 + i / 3] },
                contrast = finite(params.contrast, "contrast").coerceIn(-1f, 1f),
                lookIntensity = finite(params.lookIntensity, "lookIntensity").coerceIn(0f, 1f),
                skinProtection = finite(params.skinProtection, "skinProtection").coerceIn(0f, 1f),
                chromaScale = max(0f, 1f + finite(params.saturation, "saturation").coerceIn(-1f, 1f)),
                halationAmount = (spec.defaultHalation + halation).coerceIn(0f, 1f) * Halation.STRENGTH,
                grainAmount = grainAmount,
                grainSizePx = spec.grainSizePx,
                grainLuma = grainLuma,
                grainChromaGain = grainLuma * FilmGrain.COLOR_RATIO * spec.grainChroma,
            )
        }

        private fun finite(v: Float, name: String): Float {
            require(v.isFinite()) { "$name must be finite" }
            return v
        }
    }
}

/**
 * Geometry of one layer grade. The working texture covers the WHOLE source (the `LayerGrader` coordinate
 * contract: the compositor samples the result with the source's own normalized coordinates, and a rotated
 * crop reaches outside the region), at a density that maps the crop region onto `outWidth × outHeight`
 * texels. Texel row 0 is the top of the source.
 *
 * @property width working texture size covering the full source.
 * @property regionLeft crop region in working texels (fractional).
 * @property scissor working texels that can be sampled by any rotation of the crop about its centre (its
 *   circumscribed circle plus a bilinear margin), clipped to the texture; only these are graded.
 * @property outPerTexelX output pixels per working texel (1 unless the density was capped).
 * @property halationSigmaX base halation σ (`Halation.SIGMA_FRACTION` of the output's short edge) in
 *   working texels per axis.
 */
internal data class GradePlan(
    val width: Int,
    val height: Int,
    val regionLeft: Float,
    val regionTop: Float,
    val regionWidth: Float,
    val regionHeight: Float,
    val scissor: PixelRect,
    val outPerTexelX: Float,
    val outPerTexelY: Float,
    val halationSigmaX: Float,
    val halationSigmaY: Float,
)

/** One level of the halation half-resolution chain: its size and the extra blur σ, in its own texels. */
internal data class HalationLevel(val width: Int, val height: Int, val blurSigmaX: Float, val blurSigmaY: Float)

/** Pure planning for [ShaderLayerGrader]. */
internal object GradePlanner {
    /**
     * Pixel budget of one working texture when the requested density would exceed it (a deep zoom into a
     * large source). Never applied below the output size itself or below the source's native density.
     */
    const val MAX_WORKING_PIXELS = 8L * 1024 * 1024

    /** Bilinear footprint margin added around the scissor, in working texels. */
    private const val SCISSOR_MARGIN = 2

    /**
     * Plans a grade of the source region ([left], [top], [right], [bottom] in source texels; clipped to the
     * source, a degenerate region means the whole source) at [outWidth] × [outHeight] output pixels.
     */
    fun plan(
        sourceWidth: Int,
        sourceHeight: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        outWidth: Int,
        outHeight: Int,
        maxTargetWidth: Int,
        maxTargetHeight: Int,
    ): GradePlan {
        require(sourceWidth > 0 && sourceHeight > 0) { "source must be non-empty" }
        require(outWidth > 0 && outHeight > 0) { "output must be non-empty" }
        require(maxTargetWidth > 0 && maxTargetHeight > 0) { "target limits must be positive" }
        val sw = sourceWidth.toDouble()
        val sh = sourceHeight.toDouble()
        var l = left.toDouble().coerceIn(0.0, sw)
        var t = top.toDouble().coerceIn(0.0, sh)
        var r = right.toDouble().coerceIn(0.0, sw)
        var b = bottom.toDouble().coerceIn(0.0, sh)
        if (!(r - l > 0.0) || !(b - t > 0.0)) {
            l = 0.0; t = 0.0; r = sw; b = sh
        }
        val rw = r - l
        val rh = b - t

        // Requested density: the region lands on exactly outWidth x outHeight texels.
        val dx = outWidth / rw
        val dy = outHeight / rh
        val fullW = sw * dx
        val fullH = sh * dy
        val budget = max(MAX_WORKING_PIXELS.toDouble(), outWidth.toDouble() * outHeight)
        val budgetScale = min(1.0, sqrt(budget / (fullW * fullH)))
        val nativeFloor = max(min(1.0, 1.0 / dx), min(1.0, 1.0 / dy))
        val scale = min(max(budgetScale, nativeFloor), min(maxTargetWidth / fullW, maxTargetHeight / fullH))
        val width = (fullW * scale).roundToInt().coerceIn(1, maxTargetWidth)
        val height = (fullH * scale).roundToInt().coerceIn(1, maxTargetHeight)

        val kx = width / sw
        val ky = height / sh
        val regionLeft = l * kx
        val regionTop = t * ky
        val regionWidth = rw * kx
        val regionHeight = rh * ky

        // Any rotation about the crop centre stays inside the circumscribed circle (rotation is in source px).
        val radius = hypot(rw, rh) / 2.0
        val cx = (l + r) / 2.0
        val cy = (t + b) / 2.0
        val x0 = (floor((cx - radius) * kx).toInt() - SCISSOR_MARGIN).coerceIn(0, width)
        val y0 = (floor((cy - radius) * ky).toInt() - SCISSOR_MARGIN).coerceIn(0, height)
        val x1 = (ceil((cx + radius) * kx).toInt() + SCISSOR_MARGIN).coerceIn(0, width)
        val y1 = (ceil((cy + radius) * ky).toInt() + SCISSOR_MARGIN).coerceIn(0, height)

        val outPerTexelX = (outWidth / regionWidth).toFloat()
        val outPerTexelY = (outHeight / regionHeight).toFloat()
        val sigmaOut = Halation.sigma(outWidth, outHeight)
        return GradePlan(
            width = width,
            height = height,
            regionLeft = regionLeft.toFloat(),
            regionTop = regionTop.toFloat(),
            regionWidth = regionWidth.toFloat(),
            regionHeight = regionHeight.toFloat(),
            scissor = PixelRect(x0, y0, x1 - x0, y1 - y0),
            outPerTexelX = outPerTexelX,
            outPerTexelY = outPerTexelY,
            halationSigmaX = sigmaOut / outPerTexelX,
            halationSigmaY = sigmaOut / outPerTexelY,
        )
    }

    /**
     * The halation chain for [plan]: level i has half the size of level i − 1 (level 0 half of the working
     * texture) and holds the bright pass blurred to `Halation.SCALES[i]·σ`. Level 0 blurs the bright pass by
     * σ; each later level is downsampled from the previous blurred level and adds
     * `sqrt(scale_i² − scale_{i−1}²)·σ` (Gaussians compose in variance). σ values are in that level's texels.
     */
    fun halationLevels(plan: GradePlan): List<HalationLevel> {
        val levels = ArrayList<HalationLevel>(Halation.SCALES.size)
        var w = plan.width
        var h = plan.height
        var previous = 0f
        for (scale in Halation.SCALES) {
            w = half(w)
            h = half(h)
            val add = sqrt(scale * scale - previous * previous)
            levels += HalationLevel(
                width = w,
                height = h,
                blurSigmaX = add * plan.halationSigmaX * w / plan.width,
                blurSigmaY = add * plan.halationSigmaY * h / plan.height,
            )
            previous = scale
        }
        return levels
    }

    private fun half(size: Int): Int = max(1, (size + 1) / 2)
}
