package com.akane.akastudio.core.composer

import android.content.Context
import android.graphics.Bitmap
import android.util.Half
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.akane.akastudio.core.color.api.HalfFloatImage
import com.akane.akastudio.core.color.api.RenderScene
import com.akane.akastudio.core.color.api.StoryRenderer
import com.akane.akastudio.core.color.render.createStoryRenderer
import com.akane.akastudio.core.composer.api.ComposerPhoto
import com.akane.akastudio.core.curator.api.FaceObservation
import com.akane.akastudio.core.curator.api.SaliencyMap
import com.akane.akastudio.core.ingestion.model.CaptureMetadata
import kotlinx.coroutines.runBlocking

/** Shared helpers for the composer device suite: real Bitmaps through the real GL renderer, read back linear. */
internal object DeviceRender {
    const val TAG = "SQ60"

    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    fun log(message: String) {
        Log.i(TAG, message)
    }

    fun solidBitmap(width: Int, height: Int, argb: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(argb) }

    fun uniformSaliency(width: Int = 64, height: Int = 48) = SaliencyMap(width, height, FloatArray(width * height) { 1f })

    fun photo(
        id: String,
        width: Int,
        height: Int,
        saliency: SaliencyMap = uniformSaliency(if (width >= height) 64 else 48, if (width >= height) 48 else 64),
        faces: List<FaceObservation> = emptyList(),
        tiltDegrees: Float = 0f,
    ) = ComposerPhoto(
        id = id,
        imageKey = id,
        widthPx = width,
        heightPx = height,
        saliency = saliency,
        faces = faces,
        palette = emptyList(),
        horizonTiltDegrees = tiltDegrees,
        metadata = CaptureMetadata.EMPTY,
    )

    /** Opens a real GL renderer with [images] registered, runs [block], and closes it. */
    fun <T> withRenderer(images: Map<String, Bitmap>, block: suspend (StoryRenderer) -> T): T = runBlocking {
        createStoryRenderer(context).use { renderer ->
            images.forEach { (key, bitmap) -> renderer.registerImage(key, bitmap) }
            block(renderer)
        }
    }

    suspend fun StoryRenderer.renderLinear(scene: RenderScene): LinearImage = LinearImage.of(renderOffscreen(scene))
}

/** Linear-light RGB of an offscreen render, row-major, top row first. */
internal class LinearImage(val width: Int, val height: Int, private val rgb: FloatArray) {

    fun r(x: Int, y: Int) = rgb[(y * width + x) * 3]
    fun g(x: Int, y: Int) = rgb[(y * width + x) * 3 + 1]
    fun b(x: Int, y: Int) = rgb[(y * width + x) * 3 + 2]

    /** Rec.709 relative luminance of the linear pixel. */
    fun luminance(x: Int, y: Int) = 0.2126f * r(x, y) + 0.7152f * g(x, y) + 0.0722f * b(x, y)

    fun classify(x: Int, y: Int): Swatch = Swatch.classify(r(x, y), g(x, y), b(x, y))

    fun describe(x: Int, y: Int) = "(%.3f, %.3f, %.3f)".format(r(x, y), g(x, y), b(x, y))

    companion object {
        fun of(image: HalfFloatImage): LinearImage {
            val pixels = image.width * image.height
            val rgb = FloatArray(pixels * 3)
            for (i in 0 until pixels) {
                val alpha = Half.toFloat(image.rgba[i * 4 + 3]).takeIf { it > 0f } ?: 1f
                for (c in 0 until 3) rgb[i * 3 + c] = Half.toFloat(image.rgba[i * 4 + c]) / alpha
            }
            return LinearImage(image.width, image.height, rgb)
        }
    }
}

/** Colour classes of the colour-coded sources and the Anthracite matte, judged in linear light. */
internal enum class Swatch(val argb: Int) {
    RED(0xFFFF0000.toInt()),
    GREEN(0xFF00FF00.toInt()),
    BLUE(0xFF0000FF.toInt()),
    YELLOW(0xFFFFFF00.toInt()),
    BACKGROUND(0xFF121316.toInt()),
    OTHER(0);

    companion object {
        val SOURCES = listOf(RED, GREEN, BLUE, YELLOW)

        private const val HIGH = 0.35f
        private const val LOW = 0.12f
        private const val DARK = 0.03f

        fun classify(r: Float, g: Float, b: Float): Swatch = when {
            maxOf(r, g, b) < DARK -> BACKGROUND
            r > HIGH && g < LOW && b < LOW -> RED
            g > HIGH && r < LOW && b < LOW -> GREEN
            b > HIGH && r < LOW && g < LOW -> BLUE
            r > HIGH && g > HIGH && b < LOW -> YELLOW
            else -> OTHER
        }
    }
}
