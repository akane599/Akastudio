package com.akane.akastudio.core.color.grade

import com.akane.akastudio.core.color.analysis.SkinMask
import com.akane.akastudio.core.color.api.GradeParams
import com.akane.akastudio.core.color.api.HarmonizationParams
import com.akane.akastudio.core.color.api.OkLab
import com.akane.akastudio.core.color.harmonize.Harmonizer
import com.akane.akastudio.core.color.look.LookLibrary
import com.akane.akastudio.core.color.look.clamp01
import com.akane.akastudio.core.color.math.ColorMath
import kotlin.math.max
import kotlin.math.pow

/**
 * CPU reference grading pipeline: the numeric ground truth the GPU renderer must match.
 *
 * Per pixel, linear sRGB in and out, in this exact order:
 * 1. harmonize: OKLab affine `x' = scale·x + offset` per channel from [GradeParams.harmonization];
 * 2. exposure: `× 2^exposureEv`;
 * 3. white balance: [WhiteBalance.matrix] (`temperature`, `tint`);
 * 4. contrast: OKLab `L' = L + c·(s(L) − L)` with `s(L) = L²(3 − 2L)` on L clamped to [0, 1] (both L terms
 *    inside the bracket are the clamped value, so HDR lightness above 1 is untouched); a and b unchanged;
 * 5. look: the colour is divided by `m = max(1, maxChannel)`, encoded to sRGB, the full and skin-safe LUTs
 *    are sampled at the [0, 1]-clamped code value and mixed by `analysis.SkinMask (the single source of truth for the skin window; OKLab of the step-4 colour) ·
 *    skinProtection`, the result is mixed with the (unclamped) encoded input by `lookIntensity`, decoded
 *    and multiplied by `m`, so HDR highlights keep their headroom;
 * 6. saturation: OKLCh chroma `× max(0, 1 + saturation)`.
 *
 * [grade] then adds halation (stage 7) and grain (stage 8) over the whole image.
 *
 * Slider inputs are clamped to their documented ranges (`lookIntensity`, `skinProtection`, `halation`,
 * `grain` to [0, 1]; `contrast`, `saturation`, `temperature`, `tint` to [-1, 1]); non-finite values are rejected.
 */
object CpuGradingPipeline {

    /** Stages 1–6 on one linear RGB pixel ([rgbLinear] and [out] hold at least 3 floats; they may alias). */
    fun gradePixel(rgbLinear: FloatArray, grade: GradeParams, luts: LookLuts, out: FloatArray) {
        require(rgbLinear.size >= 3) { "rgbLinear must hold 3 channels" }
        require(out.size >= 3) { "out must hold 3 channels" }
        PixelGrader(grade, luts).grade(rgbLinear[0], rgbLinear[1], rgbLinear[2], out)
    }

    /** Full grade of [image] (stages 1–8) into a new image; [seed] selects the grain pattern. */
    fun grade(image: LinearImage, grade: GradeParams, seed: Int = 0): LinearImage {
        val spec = LookLibrary.spec(grade.look)
        val grader = PixelGrader(grade, LookLutCache.get(grade.look))
        val src = image.rgba
        val dst = FloatArray(src.size)
        val px = FloatArray(3)
        for (i in 0 until image.width * image.height) {
            val p = i * 4
            grader.grade(src[p], src[p + 1], src[p + 2], px)
            dst[p] = px[0]
            dst[p + 1] = px[1]
            dst[p + 2] = px[2]
            dst[p + 3] = src[p + 3]
        }

        val halation = (spec.defaultHalation + grader.halation).coerceIn(0f, 1f) * Halation.STRENGTH
        if (halation > 0f) Halation.apply(dst, image.width, image.height, halation)

        val grain = (spec.defaultGrain + grader.grain).coerceIn(0f, 1f)
        if (grain > 0f) {
            FilmGrain.apply(dst, image.width, image.height, grain, spec.grainSizePx, spec.grainChroma, seed)
        }
        return LinearImage(image.width, image.height, dst)
    }
}

/** [GradeParams] validated, clamped and pre-computed once for a run of pixels. Not thread-safe. */
internal class PixelGrader(grade: GradeParams, private val luts: LookLuts) {
    private val harmonization: HarmonizationParams? = grade.harmonization.takeUnless { it == HarmonizationParams.IDENTITY }
    private val exposure: Float
    private val wb: FloatArray
    private val contrast: Float = finite(grade.contrast, "contrast").coerceIn(-1f, 1f)
    private val intensity: Float = finite(grade.lookIntensity, "lookIntensity").coerceIn(0f, 1f)
    private val skinProtection: Float = finite(grade.skinProtection, "skinProtection").coerceIn(0f, 1f)
    private val chromaScale: Float = max(0f, 1f + finite(grade.saturation, "saturation").coerceIn(-1f, 1f))
    val halation: Float = finite(grade.halation, "halation").coerceIn(0f, 1f)
    val grain: Float = finite(grade.grain, "grain").coerceIn(0f, 1f)

    private val full = FloatArray(3)
    private val safe = FloatArray(3)
    private val lin = FloatArray(3)

    init {
        harmonization?.let { h ->
            listOf(h.scaleL, h.offsetL, h.scaleA, h.offsetA, h.scaleB, h.offsetB).forEach { finite(it, "harmonization") }
        }
        exposure = 2.0.pow(finite(grade.exposureEv, "exposureEv").toDouble()).toFloat()
        wb = WhiteBalance.matrix(grade.temperature, grade.tint)
    }

    fun grade(r0: Float, g0: Float, b0: Float, out: FloatArray) {
        var r = r0
        var g = g0
        var b = b0

        // 1. Harmonize.
        harmonization?.let { h ->
            ColorMath.okLabToLinearSrgb(Harmonizer.apply(ColorMath.linearSrgbToOkLab(r, g, b), h), lin)
            r = lin[0]; g = lin[1]; b = lin[2]
        }

        // 2. Exposure.
        r *= exposure; g *= exposure; b *= exposure

        // 3. White balance.
        val wr = wb[0] * r + wb[1] * g + wb[2] * b
        val wg = wb[3] * r + wb[4] * g + wb[5] * b
        val wbb = wb[6] * r + wb[7] * g + wb[8] * b
        r = wr; g = wg; b = wbb

        // 4. Contrast on OKLab L.
        var stage4Lab: OkLab? = null
        if (contrast != 0f) {
            val lab = ColorMath.linearSrgbToOkLab(r, g, b)
            val lc = clamp01(lab.l)
            val s = lc * lc * (3f - 2f * lc)
            val adjusted = OkLab(lab.l + contrast * (s - lc), lab.a, lab.b)
            ColorMath.okLabToLinearSrgb(adjusted, lin)
            r = lin[0]; g = lin[1]; b = lin[2]
            stage4Lab = adjusted
        }

        // 5. Look LUT.
        if (intensity > 0f) {
            val mask = SkinMask.weight(stage4Lab ?: ColorMath.linearSrgbToOkLab(r, g, b)) * skinProtection
            val m = max(1f, max(r, max(g, b)))
            val er = ColorMath.linearToSrgb(r / m)
            val eg = ColorMath.linearToSrgb(g / m)
            val eb = ColorMath.linearToSrgb(b / m)
            luts.look.sample(er, eg, eb, full)
            luts.skinSafe.sample(er, eg, eb, safe)
            val lr = full[0] + (safe[0] - full[0]) * mask
            val lg = full[1] + (safe[1] - full[1]) * mask
            val lb = full[2] + (safe[2] - full[2]) * mask
            r = ColorMath.srgbToLinear(er + (lr - er) * intensity) * m
            g = ColorMath.srgbToLinear(eg + (lg - eg) * intensity) * m
            b = ColorMath.srgbToLinear(eb + (lb - eb) * intensity) * m
        }

        // 6. Saturation.
        if (chromaScale != 1f) {
            val lab = ColorMath.linearSrgbToOkLab(r, g, b)
            ColorMath.okLabToLinearSrgb(OkLab(lab.l, lab.a * chromaScale, lab.b * chromaScale), lin)
            r = lin[0]; g = lin[1]; b = lin[2]
        }

        out[0] = r
        out[1] = g
        out[2] = b
    }

    private companion object {
        fun finite(v: Float, name: String): Float {
            require(v.isFinite()) { "$name must be finite" }
            return v
        }
    }
}
