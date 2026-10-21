package com.akane.akastudio.core.export

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akane.akastudio.core.export.encode.Rgba8Rows
import com.akane.akastudio.core.export.store.PlatformWebpLosslessEncoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** On-device smoke: the platform lossless WebP encoder round-trips every pixel through the device's codec. */
@RunWith(AndroidJUnit4::class)
class ExportDeviceSmokeTest {

    @Test
    fun webpLosslessRoundTripsExactPixels() {
        val width = 37
        val height = 23
        val rows = Rgba8Rows { y, out ->
            for (x in 0 until width) out[x] = (0xFF shl 24) or (x * 7 shl 16) or (y * 11 shl 8) or ((x + y) * 5 and 0xFF)
        }

        val bytes = PlatformWebpLosslessEncoder().encode(width, height, rows)
        val decoded = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "WebP did not decode" }

        assertEquals(width, decoded.width)
        assertEquals(height, decoded.height)
        val expected = IntArray(width)
        val actual = IntArray(width)
        for (y in 0 until height) {
            rows.row(y, expected)
            decoded.getPixels(actual, 0, width, 0, y, width, 1)
            assertArrayEquals("row $y", expected, actual)
        }
        decoded.recycle()
    }
}
