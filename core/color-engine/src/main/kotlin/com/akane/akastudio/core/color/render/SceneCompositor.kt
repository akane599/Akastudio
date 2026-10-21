package com.akane.akastudio.core.color.render

import android.graphics.RectF
import android.opengl.GLES20
import com.akane.akastudio.core.color.api.GradeParams
import com.akane.akastudio.core.color.api.Rect4f
import com.akane.akastudio.core.color.api.RenderScene
import com.akane.akastudio.core.color.api.SceneBackground
import com.akane.akastudio.core.color.gl.FboPool
import com.akane.akastudio.core.color.gl.FullscreenQuad
import com.akane.akastudio.core.color.gl.GlCapabilities
import com.akane.akastudio.core.color.gl.GlFramebuffer
import com.akane.akastudio.core.color.gl.GlTexture
import com.akane.akastudio.core.color.gl.ShaderProgram
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Background resolved for drawing: a working-domain clear colour or a blurred, dimmed texture. */
internal sealed interface PreparedBackground {
    class Solid(val workingRgba: FloatArray) : PreparedBackground
    class Frosted(val texture: GlTexture, val gain: Float) : PreparedBackground
}

/** One layer resolved for drawing; [neutral] is present only when an A/B split is rendered. */
internal class PreparedLayer(val dst: Rect4f, val uv: Mat3, val cornerRadius: Float, val graded: GlTexture, val neutral: GlTexture?)

/**
 * A scene with its target-independent work done (frosted background, grading, text rasters), ready to be
 * drawn into one or more targets (tiles). [release] returns its framebuffers to the pool.
 */
internal class PreparedScene(
    val canvasWidth: Int,
    val canvasHeight: Int,
    val background: PreparedBackground,
    val layers: List<PreparedLayer>,
    val overlays: List<RasterizedOverlay>,
    private val owned: List<GlFramebuffer>,
) {
    fun release(pool: FboPool) {
        owned.forEach(pool::recycle)
    }
}

/**
 * Composites a [RenderScene] in premultiplied linear light: background, layers in order (rounded-rect SDF
 * coverage, graded through [grader]), then text overlays. GL thread only.
 */
internal class SceneCompositor(
    private val programs: CompositorPrograms,
    private val quad: FullscreenQuad,
    private val pool: FboPool,
    private val capabilities: GlCapabilities,
    private val images: ImageRegistry,
    private val text: TextOverlayCache,
    private val grader: LayerGrader,
) {
    private val logDomain: Boolean get() = capabilities.logDomain

    /**
     * Resolves everything that does not depend on the target. [scaleX]/[scaleY] are target pixels per canvas
     * pixel (a grading resolution hint). Layers grade into [space]'s slots, so callers rendering at different sizes
     * keep their own cached grades. Binds framebuffers as a side effect; bind the target afterwards.
     */
    fun prepare(scene: RenderScene, scaleX: Float, scaleY: Float, withNeutral: Boolean, space: GradeSlotSpace): PreparedScene {
        require(scene.canvasWidth > 0 && scene.canvasHeight > 0) { "scene canvas must be positive: ${scene.canvasWidth}x${scene.canvasHeight}" }
        val owned = ArrayList<GlFramebuffer>()
        try {
            val background = prepareBackground(scene, owned)
            val layers = scene.layers.mapIndexedNotNull { index, layer ->
                if (!(layer.dst.width > 0f) || !(layer.dst.height > 0f)) return@mapIndexedNotNull null
                val source = images.texture(layer.imageKey)
                val crop = layer.crop
                val region = RectF(crop.left * source.width, crop.top * source.height, crop.right * source.width, crop.bottom * source.height)
                val outWidth = max(1, (layer.dst.width * abs(scaleX)).roundToInt())
                val outHeight = max(1, (layer.dst.height * abs(scaleY)).roundToInt())
                val graded = grader.grade(source, region, outWidth, outHeight, layer.grade, space.slot(2 * index))
                val neutral = if (withNeutral) {
                    grader.grade(source, region, outWidth, outHeight, GradeParams.NEUTRAL, space.slot(2 * index + 1))
                } else {
                    null
                }
                PreparedLayer(
                    dst = layer.dst,
                    uv = RenderMath.layerUvMatrix(crop, layer.rotationDegrees, source.width, source.height),
                    cornerRadius = layer.cornerRadiusPx,
                    graded = graded,
                    neutral = neutral,
                )
            }
            val overlays = scene.overlays.mapNotNull(text::get)
            return PreparedScene(scene.canvasWidth, scene.canvasHeight, background, layers, overlays, owned)
        } catch (e: RuntimeException) {
            owned.forEach(pool::recycle)
            throw e
        }
    }

    /**
     * Draws [prepared] into the bound target of [targetWidth]x[targetHeight] through [view]. [aaScale] is
     * target pixels per canvas pixel for edge anti-aliasing. [scissor] (GL window coordinates) limits the
     * pixels written; [splitX] (target pixels) renders each layer's neutral texture left of it.
     */
    fun draw(
        prepared: PreparedScene,
        targetWidth: Int,
        targetHeight: Int,
        view: ViewTransform,
        aaScale: Float,
        scissor: PixelRect?,
        splitX: Int?,
    ) {
        val base = scissor ?: PixelRect(0, 0, targetWidth, targetHeight)
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        setScissor(base)
        GLES20.glDisable(GLES20.GL_BLEND)
        drawBackground(prepared, targetWidth, targetHeight, view)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        for (layer in prepared.layers) {
            val neutral = layer.neutral
            if (splitX != null && neutral != null) {
                val left = base.intersect(PixelRect(0, 0, splitX, targetHeight))
                val right = base.intersect(PixelRect(splitX, 0, targetWidth - splitX, targetHeight))
                if (!left.isEmpty) {
                    setScissor(left)
                    drawLayer(layer, neutral, targetWidth, targetHeight, view, aaScale)
                }
                if (!right.isEmpty) {
                    setScissor(right)
                    drawLayer(layer, layer.graded, targetWidth, targetHeight, view, aaScale)
                }
                setScissor(base)
            } else {
                drawLayer(layer, layer.graded, targetWidth, targetHeight, view, aaScale)
            }
        }

        val overlayProgram = programs.overlay
        overlayProgram.use()
        overlayProgram.setInt(Uniforms.TEXTURE, 0)
        overlayProgram.setInt(Uniforms.DST_LOG, logDomain.toGlInt())
        for (overlay in prepared.overlays) {
            overlay.texture.bind(0)
            drawQuad(overlayProgram, overlay.dst, view, targetWidth, targetHeight, Mat3.IDENTITY)
        }

        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
    }

    /** Clears the whole bound display target to sRGB-encoded [argb] (display surfaces store encoded values). */
    fun clearDisplay(argb: Int) {
        val rgba = RenderMath.argbToEncodedRgba(argb)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glClearColor(rgba[0], rgba[1], rgba[2], rgba[3])
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    }

    /**
     * Final pass: samples the working-domain [scene] texture over [dst] (target pixels) of the bound target,
     * composites over [matteArgb] in linear light and writes clamped sRGB-encoded colour.
     */
    fun present(scene: GlTexture, dst: Rect4f, targetWidth: Int, targetHeight: Int, matteArgb: Int) {
        val matte = RenderMath.argbToLinearPremultiplied(matteArgb)
        val program = programs.present
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        program.use()
        program.setInt(Uniforms.TEXTURE, 0)
        program.setInt(Uniforms.SRC_LOG, logDomain.toGlInt())
        program.setVec3(Uniforms.MATTE, matte[0], matte[1], matte[2])
        scene.bind(0)
        drawQuad(program, dst, ViewTransform.IDENTITY, targetWidth, targetHeight, Mat3.FLIP_V)
    }

    /** Fills [dst] (target pixels) with sRGB-encoded [argb] written as is. */
    fun fillDisplay(dst: Rect4f, targetWidth: Int, targetHeight: Int, argb: Int) {
        val rgba = RenderMath.argbToEncodedRgba(argb)
        val program = programs.solid
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        program.use()
        program.setVec4(Uniforms.COLOR, rgba[0], rgba[1], rgba[2], rgba[3])
        drawQuad(program, dst, ViewTransform.IDENTITY, targetWidth, targetHeight, Mat3.IDENTITY)
    }

    private fun prepareBackground(scene: RenderScene, owned: MutableList<GlFramebuffer>): PreparedBackground =
        when (val background = scene.background) {
            is SceneBackground.Solid ->
                PreparedBackground.Solid(RenderMath.encodeWorking(RenderMath.argbToLinearPremultiplied(background.argb), logDomain))
            is SceneBackground.FrostedImage -> prepareFrosted(scene, background, owned)
        }

    /** Cover-fit downsample to about 1/8, separable Gaussian (iterated when sigma is large). */
    private fun prepareFrosted(scene: RenderScene, background: SceneBackground.FrostedImage, owned: MutableList<GlFramebuffer>): PreparedBackground {
        val image = images.texture(background.imageKey)
        val width = RenderMath.downsampledSize(scene.canvasWidth, FROSTED_DOWNSAMPLE)
        val height = RenderMath.downsampledSize(scene.canvasHeight, FROSTED_DOWNSAMPLE)
        val format = capabilities.workingFormat
        val primary = pool.acquire(width, height, format)
        owned += primary
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        primary.bind()
        val copy = programs.copy
        copy.use()
        copy.setInt(Uniforms.TEXTURE, 0)
        copy.setFloat(Uniforms.GAIN, 1f)
        copy.setInt(Uniforms.SRC_LOG, 0)
        copy.setInt(Uniforms.DST_LOG, logDomain.toGlInt())
        image.bind(0)
        val cover = RenderMath.coverCrop(image.width, image.height, scene.canvasWidth, scene.canvasHeight)
        val full = Rect4f(0f, 0f, width.toFloat(), height.toFloat())
        drawQuad(copy, full, ViewTransform.IDENTITY, width, height, RenderMath.layerUvMatrix(cover, 0f, image.width, image.height))

        // sigma = blurRadiusFraction * canvasWidth in canvas pixels, rescaled to the downsampled grid.
        val plan = RenderMath.blurPlan(background.blurRadiusFraction * width)
        if (plan.passCount > 0) {
            val scratch = pool.acquire(width, height, format)
            try {
                val blur = programs.blur
                blur.use()
                blur.setInt(Uniforms.TEXTURE, 0)
                blur.setInt(Uniforms.SRC_LOG, logDomain.toGlInt())
                blur.setInt(Uniforms.DST_LOG, logDomain.toGlInt())
                blur.setInt(Uniforms.BLUR_RADIUS, plan.radius)
                blur.setFloatArray(Uniforms.WEIGHTS, plan.weights)
                repeat(plan.passCount) {
                    blurPass(blur, primary, scratch, 1f / width, 0f, full)
                    blurPass(blur, scratch, primary, 0f, 1f / height, full)
                }
            } finally {
                pool.recycle(scratch)
            }
        }
        return PreparedBackground.Frosted(primary.texture, (1f - background.dim).coerceIn(0f, 1f))
    }

    private fun blurPass(program: ShaderProgram, source: GlFramebuffer, target: GlFramebuffer, stepX: Float, stepY: Float, full: Rect4f) {
        target.bind()
        program.setVec2(Uniforms.TEXEL_STEP, stepX, stepY)
        source.texture.bind(0)
        drawQuad(program, full, ViewTransform.IDENTITY, target.width, target.height, Mat3.FLIP_V)
    }

    private fun drawBackground(prepared: PreparedScene, targetWidth: Int, targetHeight: Int, view: ViewTransform) {
        when (val background = prepared.background) {
            is PreparedBackground.Solid -> {
                val c = background.workingRgba
                GLES20.glClearColor(c[0], c[1], c[2], c[3])
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            }
            is PreparedBackground.Frosted -> {
                GLES20.glClearColor(0f, 0f, 0f, 0f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                val copy = programs.copy
                copy.use()
                copy.setInt(Uniforms.TEXTURE, 0)
                copy.setFloat(Uniforms.GAIN, background.gain)
                copy.setInt(Uniforms.SRC_LOG, logDomain.toGlInt())
                copy.setInt(Uniforms.DST_LOG, logDomain.toGlInt())
                background.texture.bind(0)
                val canvas = Rect4f(0f, 0f, prepared.canvasWidth.toFloat(), prepared.canvasHeight.toFloat())
                drawQuad(copy, canvas, view, targetWidth, targetHeight, Mat3.FLIP_V)
            }
        }
    }

    private fun drawLayer(layer: PreparedLayer, texture: GlTexture, targetWidth: Int, targetHeight: Int, view: ViewTransform, aaScale: Float) {
        val program = programs.layer
        program.use()
        program.setInt(Uniforms.TEXTURE, 0)
        program.setVec2(Uniforms.HALF_SIZE, layer.dst.width / 2f, layer.dst.height / 2f)
        program.setFloat(Uniforms.RADIUS, layer.cornerRadius)
        program.setFloat(Uniforms.AA_SCALE, aaScale)
        program.setInt(Uniforms.DST_LOG, logDomain.toGlInt())
        texture.bind(0)
        drawQuad(program, layer.dst, view, targetWidth, targetHeight, layer.uv)
    }

    private fun drawQuad(program: ShaderProgram, dst: Rect4f, view: ViewTransform, targetWidth: Int, targetHeight: Int, uv: Mat3) {
        program.setVec4(Uniforms.DST_RECT, dst.left, dst.top, dst.right, dst.bottom)
        program.setVec4(Uniforms.VIEW, view.scaleX, view.scaleY, view.offsetX, view.offsetY)
        program.setVec2(Uniforms.TARGET_SIZE, targetWidth.toFloat(), targetHeight.toFloat())
        program.setMat3(Uniforms.UV_MATRIX, uv.values)
        quad.draw()
    }

    private fun setScissor(rect: PixelRect) {
        GLES20.glScissor(rect.x, rect.y, rect.width, rect.height)
    }

    private fun Boolean.toGlInt(): Int = if (this) 1 else 0

    private companion object {
        const val FROSTED_DOWNSAMPLE = 8
    }
}
