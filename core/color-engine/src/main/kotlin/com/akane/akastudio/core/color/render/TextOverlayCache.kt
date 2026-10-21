package com.akane.akastudio.core.color.render

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.akane.akastudio.core.color.api.OverlayFont
import com.akane.akastudio.core.color.api.Rect4f
import com.akane.akastudio.core.color.api.TextOverlay
import com.akane.akastudio.core.color.gl.GlFormat
import com.akane.akastudio.core.color.gl.GlTexture
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** A rasterized overlay: its texture (top-first, premultiplied sRGB RGBA8) and canvas-pixel destination. */
internal class RasterizedOverlay(val texture: GlTexture, val dst: Rect4f)

/**
 * Rasterizes [TextOverlay]s with Android [Paint]/[Canvas] at canvas resolution (see [rasterizeBitmap]) and caches the
 * textures by overlay value. Entries not used during a frame are freed at [endFrame]. GL thread only.
 */
internal class TextOverlayCache(private val maxTextureSize: Int) {
    private val cache = HashMap<TextOverlay, RasterizedOverlay>()
    private val usedThisFrame = HashSet<TextOverlay>()

    /** Texture for [overlay], or null when it draws nothing (empty text, zero size or fully transparent). */
    fun get(overlay: TextOverlay): RasterizedOverlay? {
        if (!draws(overlay)) return null
        usedThisFrame += overlay
        return cache.getOrPut(overlay) { rasterize(overlay) }
    }

    fun endFrame() {
        val iterator = cache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in usedThisFrame) {
                entry.value.texture.release()
                iterator.remove()
            }
        }
        usedThisFrame.clear()
    }

    fun releaseAll() {
        cache.values.forEach { it.texture.release() }
        cache.clear()
        usedThisFrame.clear()
    }

    private fun rasterize(overlay: TextOverlay): RasterizedOverlay {
        val raster = rasterizeBitmap(overlay, maxTextureSize)
        val bitmap = raster.bitmap
        try {
            val buffer = ByteBuffer.allocateDirect(bitmap.byteCount).order(ByteOrder.nativeOrder())
            bitmap.copyPixelsToBuffer(buffer)
            buffer.rewind()
            val texture = GlTexture.allocate(bitmap.width, bitmap.height, GlFormat.RGBA8, RenderMath.mipLevelCount(bitmap.width, bitmap.height))
            try {
                texture.uploadLevel0(buffer)
            } catch (e: RuntimeException) {
                texture.release()
                throw e
            }
            return RasterizedOverlay(texture, raster.dst)
        } finally {
            bitmap.recycle()
        }
    }

    internal companion object {
        /** Padding around the run, in ems, so italic overhangs and anti-aliasing are not clipped. */
        private const val PAD_EM = 0.15f

        /** Passes of [RenderMath.fittedTextSize]; the second absorbs hinting that keeps width from scaling exactly. */
        private const val FIT_PASSES = 2

        /** Whether [overlay] draws anything: non-empty text, a positive size and visible ink. */
        fun draws(overlay: TextOverlay): Boolean =
            overlay.text.isNotEmpty() && overlay.sizePx > 0f && (overlay.argb ushr 24) != 0

        /**
         * Rasterizes [overlay] into a premultiplied ARGB_8888 bitmap (top row first) and its canvas-pixel
         * destination. The text is shrunk to fit [TextOverlay.maxWidthPx]; a visible [TextOverlay.haloArgb] is drawn
         * first as a blurred stroke of [RenderMath.HALO_STROKE_EM] around the glyphs, inside the padding. Runs larger
         * than [maxTextureSize] are rasterized at a reduced scale and stretched back over the same destination. The
         * caller owns (and recycles) the bitmap. Any thread; requires [draws].
         */
        fun rasterizeBitmap(overlay: TextOverlay, maxTextureSize: Int): OverlayRaster {
            require(draws(overlay)) { "overlay draws nothing" }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                typeface = when (overlay.font) {
                    OverlayFont.SANS -> Typeface.SANS_SERIF
                    OverlayFont.SERIF -> Typeface.SERIF
                    OverlayFont.MONO -> Typeface.MONOSPACE
                }
                textSize = overlay.sizePx
                letterSpacing = overlay.letterSpacingEm
                color = overlay.argb
                textAlign = Paint.Align.LEFT
            }
            var textWidth = paint.measureText(overlay.text)
            for (pass in 0 until FIT_PASSES) {
                val fitted = RenderMath.fittedTextSize(paint.textSize, textWidth, overlay.maxWidthPx)
                if (!(fitted < paint.textSize) || !(fitted > 0f)) break
                paint.textSize = fitted
                textWidth = paint.measureText(overlay.text)
            }
            val halo = (overlay.haloArgb ushr 24) != 0
            val metrics = paint.fontMetrics
            val haloExtent = if (halo) RenderMath.haloExtentPx(paint.textSize) else 0f
            val pad = ceil(paint.textSize * PAD_EM + haloExtent).toInt() + 2
            val placement = RenderMath.overlayPlacement(
                anchorX = overlay.anchorX,
                baselineY = overlay.baselineY,
                textWidth = textWidth,
                fontTop = min(metrics.top, metrics.ascent),
                fontBottom = max(metrics.bottom, metrics.descent),
                align = overlay.align,
                pad = pad,
            )
            val scale = min(1f, min(maxTextureSize.toFloat() / placement.width, maxTextureSize.toFloat() / placement.height))
            val bitmapWidth = max(1, min(maxTextureSize, ceil(placement.width * scale).toInt()))
            val bitmapHeight = max(1, min(maxTextureSize, ceil(placement.height * scale).toInt()))
            val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.scale(scale, scale)
            if (halo) {
                val haloPaint = Paint(paint).apply {
                    color = overlay.haloArgb
                    style = Paint.Style.STROKE
                    strokeWidth = paint.textSize * RenderMath.HALO_STROKE_EM
                    strokeJoin = Paint.Join.ROUND
                    maskFilter = BlurMaskFilter(paint.textSize * RenderMath.HALO_BLUR_EM, BlurMaskFilter.Blur.NORMAL)
                }
                canvas.drawText(overlay.text, placement.originX, placement.baselineY, haloPaint)
            }
            canvas.drawText(overlay.text, placement.originX, placement.baselineY, paint)
            val dst = Rect4f(
                placement.left.toFloat(),
                placement.top.toFloat(),
                (placement.left + placement.width).toFloat(),
                (placement.top + placement.height).toFloat(),
            )
            return OverlayRaster(bitmap, dst)
        }
    }
}

/** A text run rasterized to [bitmap] (premultiplied ARGB_8888, top row first) covering [dst] in canvas pixels. */
internal class OverlayRaster(val bitmap: Bitmap, val dst: Rect4f)
