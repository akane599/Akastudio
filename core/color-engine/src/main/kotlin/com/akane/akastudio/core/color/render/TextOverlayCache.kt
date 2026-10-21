package com.akane.akastudio.core.color.render

import android.graphics.Bitmap
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
 * Rasterizes [TextOverlay]s with Android [Paint]/[Canvas] at canvas resolution and caches the textures by
 * overlay value. Entries not used during a frame are freed at [endFrame]. GL thread only.
 */
internal class TextOverlayCache(private val maxTextureSize: Int) {
    private val cache = HashMap<TextOverlay, RasterizedOverlay>()
    private val usedThisFrame = HashSet<TextOverlay>()

    /** Texture for [overlay], or null when it draws nothing (empty text, zero size or fully transparent). */
    fun get(overlay: TextOverlay): RasterizedOverlay? {
        if (overlay.text.isEmpty() || !(overlay.sizePx > 0f) || (overlay.argb ushr 24) == 0) return null
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
        val metrics = paint.fontMetrics
        val pad = ceil(overlay.sizePx * PAD_EM).toInt() + 2
        val placement = RenderMath.overlayPlacement(
            anchorX = overlay.anchorX,
            baselineY = overlay.baselineY,
            textWidth = paint.measureText(overlay.text),
            fontTop = min(metrics.top, metrics.ascent),
            fontBottom = max(metrics.bottom, metrics.descent),
            align = overlay.align,
            pad = pad,
        )
        // Oversized runs are rasterized at a reduced scale and stretched back over the same destination.
        val scale = min(1f, min(maxTextureSize.toFloat() / placement.width, maxTextureSize.toFloat() / placement.height))
        val bitmapWidth = max(1, min(maxTextureSize, ceil(placement.width * scale).toInt()))
        val bitmapHeight = max(1, min(maxTextureSize, ceil(placement.height * scale).toInt()))
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.scale(scale, scale)
            canvas.drawText(overlay.text, placement.originX, placement.baselineY, paint)
            val buffer = ByteBuffer.allocateDirect(bitmap.byteCount).order(ByteOrder.nativeOrder())
            bitmap.copyPixelsToBuffer(buffer)
            buffer.rewind()
            val texture = GlTexture.allocate(bitmapWidth, bitmapHeight, GlFormat.RGBA8, RenderMath.mipLevelCount(bitmapWidth, bitmapHeight))
            try {
                texture.uploadLevel0(buffer)
            } catch (e: RuntimeException) {
                texture.release()
                throw e
            }
            val dst = Rect4f(
                placement.left.toFloat(),
                placement.top.toFloat(),
                (placement.left + placement.width).toFloat(),
                (placement.top + placement.height).toFloat(),
            )
            return RasterizedOverlay(texture, dst)
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        /** Padding around the run, in ems, so italic overhangs and anti-aliasing are not clipped. */
        const val PAD_EM = 0.15f
    }
}
