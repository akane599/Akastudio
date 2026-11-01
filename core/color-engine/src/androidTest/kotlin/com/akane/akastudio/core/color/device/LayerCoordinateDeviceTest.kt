package com.akane.akastudio.core.color.device

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.color.api.Rect4f
import com.akane.akastudio.core.color.gl.GlFormat
import com.akane.akastudio.core.color.gl.GlFramebuffer
import com.akane.akastudio.core.color.render.CompositorPrograms
import com.akane.akastudio.core.color.render.IdentityLayerGrader
import com.akane.akastudio.core.color.render.ImageRegistry
import com.akane.akastudio.core.color.render.PreparedBackground
import com.akane.akastudio.core.color.render.PreparedLayer
import com.akane.akastudio.core.color.render.PreparedScene
import com.akane.akastudio.core.color.render.RenderMath
import com.akane.akastudio.core.color.render.SceneCompositor
import com.akane.akastudio.core.color.render.TextOverlayCache
import com.akane.akastudio.core.color.render.ViewTransform
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LayerCoordinateDeviceTest {
    @Test
    fun scaledFractionalRoundedLayerAndCompareSplitMatchCpuSampling(): Unit = OffscreenGl.open().use { gl ->
        assumeTrue("needs colour-renderable RGBA16F", gl.capabilities.floatColorBuffer)
        fun image(reverse: Boolean) = LinearRgbImage(SIZE, SIZE, FloatArray(SIZE * SIZE * 3) { i ->
            val x = i / 3 % SIZE
            val y = i / 3 / SIZE
            roundHalf(when (i % 3) {
                0 -> if (reverse) 0.8f - x * 0.6f / (SIZE - 1) else 0.1f + x * 0.6f / (SIZE - 1)
                1 -> 0.1f + y * 0.6f / (SIZE - 1)
                else -> if (reverse) 0.2f else 0.6f
            })
        })
        val neutralImage = image(false)
        val gradedImage = image(true)
        val neutral = neutralImage.upload()
        val graded = gradedImage.upload()
        val programs = CompositorPrograms.load(testAssets)
        val images = ImageRegistry()
        val text = TextOverlayCache(gl.capabilities.maxTextureSize)
        val compositor = SceneCompositor(programs, gl.quad, gl.pool, gl.capabilities, images, text, IdentityLayerGrader)
        val dst = Rect4f(12.25f, 16.75f, 203.5f, 190.125f)
        val uv = RenderMath.layerUvMatrix(NormRect(0.1f, 0.15f, 0.9f, 0.85f), 2.5f, SIZE, SIZE)
        val layer = PreparedLayer(dst, uv, 10f, graded, neutral)
        val prepared = PreparedScene(216, 384, PreparedBackground.Solid(floatArrayOf(0f, 0f, 0f, 1f)), listOf(layer), emptyList(), emptyList())
        try {
            for ((width, height, view) in listOf(
                Triple(216, 384, ViewTransform.IDENTITY),
                Triple(137, 241, ViewTransform(137f / 216f, 241f / 384f, 0f, 0f)),
                Triple(219, 397, ViewTransform(0.73f, 0.73f, 17f, 39f)),
            )) {
                val target = GlFramebuffer.create(width, height, GlFormat.RGBA16F)
                try {
                    target.bind()
                    val split = width / 2
                    val aa = min(view.scaleX, view.scaleY)
                    compositor.draw(prepared, width, height, view, aa, null, split)
                    val actual = readFloatRgba(width, height)
                    var worst = 0.0
                    var probes = 0
                    for (unitY in listOf(0.01f, 0.1f, 0.3f, 0.5f, 0.9f, 0.99f)) {
                        for (unitX in listOf(0.01f, 0.1f, 0.3f, 0.5f, 0.9f, 0.99f)) {
                            val x = ((dst.left + dst.width * unitX) * view.scaleX + view.offsetX - 0.5f).roundToInt()
                            val y = ((dst.top + dst.height * unitY) * view.scaleY + view.offsetY - 0.5f).roundToInt()
                            val cx = (x + 0.5f - view.offsetX) / view.scaleX
                            val cy = (y + 0.5f - view.offsetY) / view.scaleY
                            val u = (cx - dst.left) / dst.width
                            val v = (cy - dst.top) / dst.height
                            val (su, sv) = uv.apply(u, v)
                            val coverage = RenderMath.sdfCoverage(
                                RenderMath.roundedRectSdf((u - 0.5f) * dst.width, (v - 0.5f) * dst.height, dst.width / 2f, dst.height / 2f, 10f), aa,
                            )
                            val source = if (x < split) neutralImage else gradedImage
                            for (c in 0..2) {
                                val expected = bilinear(source, su, sv, c) * coverage
                                val rendered = actual[((height - 1 - y) * width + x) * 4 + c]
                                worst = maxOf(worst, abs(expected - rendered))
                            }
                            probes++
                        }
                    }
                    report("layer CPU coordinates ${width}x$height view=$view split=$split probes=$probes maxLinear=$worst")
                    assertTrue("scaled/cropped/rounded/split sampling must match CPU, max=$worst", worst <= 0.002)
                } finally {
                    target.release()
                }
            }
        } finally {
            programs.release()
            neutral.release()
            graded.release()
            text.releaseAll()
            images.clear()
        }
    }

    private fun bilinear(image: LinearRgbImage, u: Float, v: Float, channel: Int): Double {
        val px = u.toDouble() * image.width - 0.5
        val py = v.toDouble() * image.height - 0.5
        val left = floor(px).toInt()
        val top = floor(py).toInt()
        val fx = px - left
        val fy = py - top
        fun texel(x: Int, y: Int) = image.rgb[(y.coerceIn(0, image.height - 1) * image.width + x.coerceIn(0, image.width - 1)) * 3 + channel].toDouble()
        val a = texel(left, top) * (1.0 - fx) + texel(left + 1, top) * fx
        val b = texel(left, top + 1) * (1.0 - fx) + texel(left + 1, top + 1) * fx
        return a * (1.0 - fy) + b * fy
    }

    private companion object {
        const val SIZE = 16
    }
}
