package com.akane.akastudio.core.export.device

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akane.akastudio.core.color.api.HalfFloatImage
import com.akane.akastudio.core.export.process.Dither
import com.akane.akastudio.core.export.process.Half
import com.akane.akastudio.core.export.store.PlatformWebpLosslessEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * AUD-08: a translucent pixel survives lossless WebP through the platform encoder with its straight colour.
 *
 * The frame holds premultiplied linear light; [Dither.toStraightSrgb8Row] unpremultiplies it for `Bitmap.setPixels`,
 * which expects straight colour. Decoding with `inPremultiplied = false` reads the straight colour the file stores.
 */
@RunWith(AndroidJUnit4::class)
class WebpTransparencyDeviceTest {

    @Test
    fun losslessWebpRoundTripKeepsStraightAlphaColour() {
        // Left half: the auditor's half-transparent white under frosted gain 0.75, premultiplied (0.375 x3, 0.5).
        // Right half: an opaque control of the same straight colour 0.75.
        val image = frame { x -> if (x < WIDTH / 2) 0.5f else 1f }
        val webp = PlatformWebpLosslessEncoder().encode(WIDTH, HEIGHT) { y, row -> Dither.toStraightSrgb8Row(image, y, row) }

        val options = BitmapFactory.Options().apply { inPremultiplied = false }
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(webp, 0, webp.size, options)) { "BitmapFactory could not decode our WebP" }
        try {
            assertEquals(WIDTH, bitmap.width)
            assertEquals(HEIGHT, bitmap.height)
            assertTrue("decoded bitmap lost its alpha", bitmap.hasAlpha())
            val expected = IntArray(WIDTH)
            val actual = IntArray(WIDTH)
            var worst = 0
            for (y in 0 until HEIGHT) {
                Dither.toStraightSrgb8Row(image, y, expected)
                bitmap.getPixels(actual, 0, WIDTH, 0, y, WIDTH, 1)
                for (x in 0 until WIDTH) {
                    assertEquals("alpha ($x,$y)", expected[x] ushr 24, actual[x] ushr 24)
                    for (shift in intArrayOf(16, 8, 0)) {
                        val stored = (actual[x] ushr shift) and 0xFF
                        worst = maxOf(worst, abs(stored - ((expected[x] ushr shift) and 0xFF)))
                        // Straight 0.75 encodes to 224.6; re-applying alpha to the premultiplied colour would give ~165.
                        if (x < WIDTH / 2) assertTrue("translucent ($x,$y) shift $shift stored $stored", stored in 222..227)
                    }
                }
            }
            record("webpAlpha.maxStraightCodeError", worst)
            // The bitmap is non-premultiplied, but the encoder may still round through 8-bit premultiplied colour, so
            // alpha 128 is allowed about one straight code of drift.
            assertTrue("straight colour drifted by $worst codes", worst <= MAX_PREMULTIPLY_LOSS)
        } finally {
            bitmap.recycle()
        }
    }

    /** A frame of straight linear grey 0.75 premultiplied by the per-column alpha [alphaAt]. */
    private fun frame(alphaAt: (x: Int) -> Float): HalfFloatImage {
        val data = ShortArray(WIDTH * HEIGHT * 4)
        for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
            val a = alphaAt(x)
            val premultiplied = 0.75f * a
            val o = (y * WIDTH + x) * 4
            data[o] = Half.fromFloat(premultiplied)
            data[o + 1] = Half.fromFloat(premultiplied)
            data[o + 2] = Half.fromFloat(premultiplied)
            data[o + 3] = Half.fromFloat(a)
        }
        return HalfFloatImage(WIDTH, HEIGHT, data)
    }

    private companion object {
        const val WIDTH = 64
        const val HEIGHT = 64
        const val MAX_PREMULTIPLY_LOSS = 2
    }
}
