package com.akane.akastudio.core.color.device

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akane.akastudio.core.color.api.OverlayFont
import com.akane.akastudio.core.color.api.TextOverlay
import com.akane.akastudio.core.color.render.OverlayRaster
import com.akane.akastudio.core.color.render.TextOverlayCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * F-23 / T-06: [TextOverlayCache] rasterizes with the platform text stack, so these run on a device. They pin the
 * halo (dark ink around light glyphs, inside the padded bounds), fit-to-width, and the per-frame cache lifecycle.
 */
@RunWith(AndroidJUnit4::class)
class TextOverlayCacheDeviceTest {

    private val caption = TextOverlay(
        text = "24mm · f/1.8 · 1/250s · ISO 64",
        anchorX = 540f,
        baselineY = 1800f,
        sizePx = 26f,
        argb = 0xFFD9D6CF.toInt(),
        letterSpacingEm = 0.12f,
        font = OverlayFont.MONO,
    )

    private inline fun <T> raster(overlay: TextOverlay, block: (OverlayRaster) -> T): T {
        val raster = TextOverlayCache.rasterizeBitmap(overlay, maxTextureSize = 4096)
        try {
            return block(raster)
        } finally {
            raster.bitmap.recycle()
        }
    }

    @Test
    fun halo_putsDarkInkAroundTheGlyphs_withoutClippingIt() {
        val plain = raster(caption) { darkOpaquePixels(it.bitmap) }
        assertEquals("light ink alone has no dark pixels", 0, plain)

        raster(caption.copy(haloArgb = 0xB3000000.toInt())) { haloed ->
            val dark = darkOpaquePixels(haloed.bitmap)
            report("caption halo: $dark dark pixels in ${haloed.bitmap.width}x${haloed.bitmap.height}")
            assertTrue("the halo must darken pixels next to the glyphs, got $dark", dark > 200)
            assertEquals("the halo's blur tail must fit inside the padding", 0, edgeInk(haloed.bitmap))
            raster(caption) { assertTrue("the halo pads the bounds", haloed.dst.width > it.dst.width) }
        }
    }

    @Test
    fun maxWidth_shrinksAWideRunToFit_andLeavesANarrowOneAlone() {
        val natural = raster(caption) { it.dst }
        val limit = 300f
        val fitted = raster(caption.copy(maxWidthPx = limit)) { it.dst }
        assertTrue("natural run ${natural.width} must exceed the limit for the test to mean anything", natural.width > limit + 40f)
        // The destination is the run plus a fixed padding on each side (about 0.15 em + 2 px).
        assertTrue("fitted ${fitted.width} must be about the limit", fitted.width <= limit + 2 * (0.15f * 26f + 3f) + 4f)
        assertTrue("shrinking keeps the run centred on its anchor", abs((fitted.left + fitted.right) / 2f - 540f) <= 1.5f)
        assertTrue("and keeps the baseline", fitted.bottom <= natural.bottom)

        val roomy = raster(caption.copy(maxWidthPx = 2000f)) { it.dst }
        assertEquals(natural, roomy)
    }

    @Test
    fun cache_reusesTexturesWithinAFrame_freesUnusedOnesAtEndFrame_andSkipsInvisibleText() {
        OffscreenGl.open().use { gl ->
            val cache = TextOverlayCache(gl.capabilities.maxTextureSize)
            try {
                assertNull(cache.get(caption.copy(text = "")))
                assertNull(cache.get(caption.copy(sizePx = 0f)))
                assertNull(cache.get(caption.copy(argb = 0x00FFFFFF)))

                val first = checkNotNull(cache.get(caption))
                assertSame(first, cache.get(caption))
                cache.endFrame()
                assertFalse("used this frame, so kept", first.texture.isReleased)

                val haloed = checkNotNull(cache.get(caption.copy(haloArgb = 0xB3000000.toInt())))
                assertTrue("a halo is a different raster", haloed !== first)
                cache.endFrame()
                assertTrue("not used this frame, so freed", first.texture.isReleased)
                assertFalse(haloed.texture.isReleased)
            } finally {
                cache.releaseAll()
            }
        }
    }

    /** Pixels with substantial coverage whose (unpremultiplied, as [Bitmap.getPixels] returns) colour is near black. */
    private fun darkOpaquePixels(bitmap: Bitmap): Int = pixels(bitmap).count { p ->
        Color.alpha(p) >= 96 && maxOf(Color.red(p), Color.green(p), Color.blue(p)) < 64
    }

    /** Pixels with any coverage on the bitmap's outermost rows and columns. */
    private fun edgeInk(bitmap: Bitmap): Int {
        val w = bitmap.width
        val h = bitmap.height
        return pixels(bitmap).withIndex().count { (i, p) ->
            val x = i % w
            val y = i / w
            (x == 0 || y == 0 || x == w - 1 || y == h - 1) && Color.alpha(p) > 0
        }
    }

    private fun pixels(bitmap: Bitmap): IntArray =
        IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
}
