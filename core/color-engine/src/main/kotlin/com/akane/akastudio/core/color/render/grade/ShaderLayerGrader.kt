package com.akane.akastudio.core.color.render.grade

import android.graphics.RectF
import android.opengl.GLES20
import com.akane.akastudio.core.color.api.GradeParams
import com.akane.akastudio.core.color.gl.FboPool
import com.akane.akastudio.core.color.gl.GlCapabilities
import com.akane.akastudio.core.color.gl.GlEnvironment
import com.akane.akastudio.core.color.gl.GlFormat
import com.akane.akastudio.core.color.gl.GlFramebuffer
import com.akane.akastudio.core.color.gl.GlTexture
import com.akane.akastudio.core.color.gl.ShaderProgram
import com.akane.akastudio.core.color.gl.checkGlError
import com.akane.akastudio.core.color.render.LayerGrader
import com.akane.akastudio.core.color.render.Mat3
import com.akane.akastudio.core.color.render.PixelRect
import com.akane.akastudio.core.color.render.ProgramSpec
import com.akane.akastudio.core.color.render.RenderMath
import com.akane.akastudio.core.color.render.ShaderCatalog
import com.akane.akastudio.core.color.render.Uniforms

/** Storage of every texture a [ShaderLayerGrader] renders. */
internal enum class GradeVariant(val format: GlFormat) {
    /** Full quality: linear RGBA16F, HDR headroom and negative values kept. */
    FLOAT(GlFormat.RGBA16F),

    /**
     * Reduced quality for contexts without float colour buffers: 8-bit sRGB-encoded storage (the sampler
     * decodes to linear, so the compositor's contract is unchanged). Light is clamped to [0, 1] at every
     * stored step, so HDR highlights clip before exposure and halation sees no light above diffuse white.
     */
    REDUCED(GlFormat.SRGB8_ALPHA8),
}

/**
 * GPU port of `CpuGradingPipeline` behind the [LayerGrader] seam. Per layer, on the GL thread:
 * 1. the source is resampled (mip/trilinear by the source's own sampler) into a working texture covering
 *    the whole source at the density that maps the crop region onto `outWidth × outHeight` ([GradePlanner]);
 * 2. `grade_pixel` runs stages 1–6 (harmonize, exposure, white balance, contrast, look LUT pair with skin
 *    mask and intensity, saturation) exactly in the CPU order, with the look LUTs as 3D textures sampled
 *    tetrahedrally like the CPU ([LookLutTextures]);
 * 3. halation: a bright pass at half resolution, then the 1σ/2σ/4σ Gaussians on a halving FBO chain;
 * 4. `grade_finish` adds the tinted glow and the CPU-identical hash grain in output-pixel space.
 *
 * Only texels reachable by any rotation of the crop (the plan's scissor) are graded; the rest is
 * transparent. Each slot keeps one cached output and re-grades only when `(source, region, size, params)`
 * changes, so slider drags cost one grade and static frames cost nothing. [filmEffects] = false skips
 * stages 7–8 (the parity probe compares stages 1–6 only).
 *
 * Construction compiles the programs and throws `GlException` on failure. GL thread only.
 */
internal class ShaderLayerGrader(
    environment: GlEnvironment,
    private val variant: GradeVariant,
    private val filmEffects: Boolean = true,
) : LayerGrader {

    private data class SlotKey(
        val source: GlTexture,
        val sourceId: Int,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val outWidth: Int,
        val outHeight: Int,
        val params: GradeParams,
    )

    private class Slot(val output: GlFramebuffer, var key: SlotKey?)

    private val capabilities: GlCapabilities = environment.capabilities
    private val pool: FboPool = environment.fboPool
    private val quad = environment.quad
    private val luts = LookLutTextures()
    private val slots = HashMap<Int, Slot>()

    private val resample: ShaderProgram
    private val blur: ShaderProgram
    private val pixel: ShaderProgram
    private val bright: ShaderProgram
    private val finish: ShaderProgram

    init {
        val loaded = ArrayList<ShaderProgram>()
        try {
            resample = ShaderProgram.load(environment.assets, ShaderCatalog.COPY.vertex, ShaderCatalog.COPY.fragment).also(loaded::add)
            blur = ShaderProgram.load(environment.assets, ShaderCatalog.BLUR.vertex, ShaderCatalog.BLUR.fragment).also(loaded::add)
            pixel = loadGrading(environment, GradeShaderCatalog.PIXEL).also(loaded::add)
            bright = loadGrading(environment, GradeShaderCatalog.BRIGHT).also(loaded::add)
            finish = loadGrading(environment, GradeShaderCatalog.FINISH).also(loaded::add)
        } catch (e: RuntimeException) {
            loaded.forEach { it.release() }
            throw e
        }
    }

    override fun grade(source: GlTexture, region: RectF, outWidth: Int, outHeight: Int, params: GradeParams, slot: Int): GlTexture =
        gradeInto(source, region, outWidth, outHeight, params, slot).texture

    /** [grade], returning the slot's framebuffer (the parity probe reads it back). */
    fun gradeInto(source: GlTexture, region: RectF, outWidth: Int, outHeight: Int, params: GradeParams, slot: Int): GlFramebuffer {
        val key = SlotKey(source, source.id, region.left, region.top, region.right, region.bottom, outWidth, outHeight, params)
        val cached = slots[slot]
        if (cached != null && cached.key == key && !cached.output.isReleased) return cached.output

        val uniforms = GradeUniforms.from(params)
        val plan = GradePlanner.plan(
            source.width, source.height, region.left, region.top, region.right, region.bottom,
            outWidth, outHeight, capabilities.maxTargetWidth, capabilities.maxTargetHeight,
        )
        val output = outputFor(slot, plan)
        output.key = null
        render(source, plan, uniforms, slot, output.output)
        output.key = key
        return output.output
    }

    override fun close() {
        slots.values.forEach { it.output.release() }
        slots.clear()
        luts.release()
        listOf(resample, blur, pixel, bright, finish).forEach { it.release() }
    }

    private fun outputFor(slot: Int, plan: GradePlan): Slot {
        val existing = slots[slot]
        if (existing != null && !existing.output.isReleased &&
            existing.output.width == plan.width && existing.output.height == plan.height
        ) {
            return existing
        }
        existing?.output?.release()
        slots.remove(slot)
        val created = Slot(GlFramebuffer.create(plan.width, plan.height, variant.format), key = null)
        slots[slot] = created
        return created
    }

    private fun render(source: GlTexture, plan: GradePlan, uniforms: GradeUniforms, slot: Int, output: GlFramebuffer) {
        val post = filmEffects && uniforms.hasFilmEffects
        val transient = ArrayList<GlFramebuffer>(4)
        GLES20.glDisable(GLES20.GL_BLEND)
        try {
            // 1. Source -> working texture (whole source at the planned density).
            val working = pool.acquire(plan.width, plan.height, variant.format).also(transient::add)
            beginPass(working, plan.scissor)
            resample.use()
            resample.setInt(Uniforms.TEXTURE, 0)
            resample.setFloat(Uniforms.GAIN, 1f)
            resample.setInt(Uniforms.SRC_LOG, 0)
            resample.setInt(Uniforms.DST_LOG, 0)
            source.bind(0)
            drawFull(resample, plan.width, plan.height)

            // 2. Stages 1-6.
            val graded = if (post) pool.acquire(plan.width, plan.height, variant.format).also(transient::add) else output
            beginPass(graded, plan.scissor)
            pixel.use()
            setPixelUniforms(uniforms)
            working.texture.bind(0)
            drawFull(pixel, plan.width, plan.height)

            if (post) {
                // 3. Halation chain.
                val glow = if (uniforms.halationAmount > 0f) halation(graded.texture, plan, transient) else null
                // 4. Halation composite and grain.
                beginPass(output, plan.scissor)
                finish.use()
                setFinishUniforms(uniforms, plan, slot, glow != null)
                graded.texture.bind(0)
                for (i in 0 until GLOW_LEVELS) (glow?.get(i) ?: graded.texture).bind(GLOW_UNIT + i)
                drawFull(finish, plan.width, plan.height)
            }
            checkGlError("layer grade ${plan.width}x${plan.height} ${variant.name}")
        } finally {
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            transient.forEach(pool::recycle)
        }
    }

    /** Bright pass and blurred halving chain; returns the three blurred level textures (owned by [transient]). */
    private fun halation(graded: GlTexture, plan: GradePlan, transient: MutableList<GlFramebuffer>): List<GlTexture> {
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        val results = ArrayList<GlTexture>(GLOW_LEVELS)
        var previous: GlTexture = graded
        GradePlanner.halationLevels(plan).forEachIndexed { index, level ->
            val target = pool.acquire(level.width, level.height, variant.format).also(transient::add)
            target.bind()
            if (index == 0) {
                bright.use()
                bright.setInt(Uniforms.TEXTURE, 0)
                bright.setVec2(GradeUniformNames.SRC_TEXEL, 1f / plan.width, 1f / plan.height)
                previous.bind(0)
                drawFull(bright, level.width, level.height)
            } else {
                resample.use()
                resample.setInt(Uniforms.TEXTURE, 0)
                resample.setFloat(Uniforms.GAIN, 1f)
                resample.setInt(Uniforms.SRC_LOG, 0)
                resample.setInt(Uniforms.DST_LOG, 0)
                previous.bind(0)
                drawFull(resample, level.width, level.height)
            }
            val scratch = pool.acquire(level.width, level.height, variant.format).also(transient::add)
            val blurred = blurInPlace(target, scratch, level)
            results += blurred.texture
            previous = blurred.texture
        }
        return results
    }

    /** Separable Gaussian of [level]'s σ per axis, ping-ponging between [a] and [b]; returns the holder. */
    private fun blurInPlace(a: GlFramebuffer, b: GlFramebuffer, level: HalationLevel): GlFramebuffer {
        val planX = RenderMath.blurPlan(level.blurSigmaX)
        val planY = RenderMath.blurPlan(level.blurSigmaY)
        var current = a
        var other = b
        blur.use()
        blur.setInt(Uniforms.TEXTURE, 0)
        blur.setInt(Uniforms.SRC_LOG, 0)
        blur.setInt(Uniforms.DST_LOG, 0)
        for ((plan, horizontal) in listOf(planX to true, planY to false)) {
            if (plan.passCount == 0) continue
            blur.setInt(Uniforms.BLUR_RADIUS, plan.radius)
            blur.setFloatArray(Uniforms.WEIGHTS, plan.weights)
            blur.setVec2(
                Uniforms.TEXEL_STEP,
                if (horizontal) 1f / level.width else 0f,
                if (horizontal) 0f else 1f / level.height,
            )
            repeat(plan.passCount) {
                other.bind()
                current.texture.bind(0)
                drawFull(blur, level.width, level.height)
                val swap = current
                current = other
                other = swap
            }
        }
        return current
    }

    private fun setPixelUniforms(u: GradeUniforms) {
        pixel.setInt(Uniforms.TEXTURE, 0)
        val lutSize = luts.bind(u.look, LUT_LOOK_UNIT, LUT_SKIN_SAFE_UNIT)
        pixel.setInt(GradeUniformNames.LUT_LOOK, LUT_LOOK_UNIT)
        pixel.setInt(GradeUniformNames.LUT_SKIN_SAFE, LUT_SKIN_SAFE_UNIT)
        pixel.setFloat(GradeUniformNames.LUT_SIZE, lutSize.toFloat())
        pixel.setInt(GradeUniformNames.HARMONIZE, if (u.harmonize) 1 else 0)
        pixel.setVec3(GradeUniformNames.HARMONIZE_SCALE, u.harmonizeScale[0], u.harmonizeScale[1], u.harmonizeScale[2])
        pixel.setVec3(GradeUniformNames.HARMONIZE_OFFSET, u.harmonizeOffset[0], u.harmonizeOffset[1], u.harmonizeOffset[2])
        pixel.setFloat(GradeUniformNames.EXPOSURE, u.exposure)
        pixel.setMat3(GradeUniformNames.WHITE_BALANCE, u.whiteBalance)
        pixel.setFloat(GradeUniformNames.CONTRAST, u.contrast)
        pixel.setFloat(GradeUniformNames.LOOK_INTENSITY, u.lookIntensity)
        pixel.setFloat(GradeUniformNames.SKIN_PROTECTION, u.skinProtection)
        pixel.setFloat(GradeUniformNames.CHROMA_SCALE, u.chromaScale)
    }

    private fun setFinishUniforms(u: GradeUniforms, plan: GradePlan, slot: Int, hasGlow: Boolean) {
        finish.setInt(Uniforms.TEXTURE, 0)
        finish.setInt(GradeUniformNames.GLOW_0, GLOW_UNIT)
        finish.setInt(GradeUniformNames.GLOW_1, GLOW_UNIT + 1)
        finish.setInt(GradeUniformNames.GLOW_2, GLOW_UNIT + 2)
        finish.setFloat(GradeUniformNames.HALATION_AMOUNT, if (hasGlow) u.halationAmount else 0f)
        finish.setFloat(GradeUniformNames.GRAIN_AMOUNT, u.grainAmount)
        finish.setFloat(GradeUniformNames.GRAIN_SIZE, u.grainSizePx)
        finish.setFloat(GradeUniformNames.GRAIN_LUMA, u.grainLuma)
        finish.setFloat(GradeUniformNames.GRAIN_CHROMA, u.grainChromaGain)
        finish.setInt(GradeUniformNames.GRAIN_SEED, slot)
        finish.setVec2(GradeUniformNames.GRAIN_ORIGIN, plan.regionLeft, plan.regionTop)
        finish.setVec2(GradeUniformNames.GRAIN_SCALE, plan.outPerTexelX, plan.outPerTexelY)
    }

    /** Binds [target], clears it to transparent and limits the following draw to [scissor]. */
    private fun beginPass(target: GlFramebuffer, scissor: PixelRect) {
        target.bind()
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(scissor.x, scissor.y, scissor.width, scissor.height)
    }

    /**
     * Covers the whole bound [width] × [height] target. Every grading texture shares the source's normalized
     * space with t = 0 at the top row, which is framebuffer row 0, hence [Mat3.FLIP_V].
     */
    private fun drawFull(program: ShaderProgram, width: Int, height: Int) {
        program.setVec4(Uniforms.DST_RECT, 0f, 0f, width.toFloat(), height.toFloat())
        program.setVec4(Uniforms.VIEW, 1f, 1f, 0f, 0f)
        program.setVec2(Uniforms.TARGET_SIZE, width.toFloat(), height.toFloat())
        program.setMat3(Uniforms.UV_MATRIX, Mat3.FLIP_V.values)
        quad.draw()
    }

    private companion object {
        const val LUT_LOOK_UNIT = 1
        const val LUT_SKIN_SAFE_UNIT = 2
        const val GLOW_UNIT = 1
        const val GLOW_LEVELS = 3

        fun loadGrading(environment: GlEnvironment, spec: ProgramSpec): ShaderProgram =
            ShaderProgram.load(environment.assets, spec.vertex, spec.fragment, GlslConstants.prelude)
    }
}
