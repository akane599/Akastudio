package com.akane.akastudio.core.export.device

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorSpace
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akane.akastudio.core.export.api.ExportFormat
import com.akane.akastudio.core.export.api.ExportResolution
import com.akane.akastudio.core.export.encode.JpegEncoder
import com.akane.akastudio.core.export.encode.PngEncoder
import com.akane.akastudio.core.export.encode.Rgba8Rows
import com.akane.akastudio.core.export.encode.SrgbIccProfile
import com.akane.akastudio.core.export.encode.WebpContainer
import com.akane.akastudio.core.export.pipeline.ExportProcessor
import com.akane.akastudio.core.export.process.Dither
import com.akane.akastudio.core.export.store.PlatformWebpLosslessEncoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Our PNG16, 4:4:4 JPEG and ICC-carrying lossless WebP files judged by the platform decoders. */
@RunWith(AndroidJUnit4::class)
class PlatformDecodeDeviceTest {

    private val srgb = ColorSpace.get(ColorSpace.Named.SRGB)
    private val extendedSrgb = ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB)

    // ---- 1. PNG16 -----------------------------------------------------------------------------------------------

    @Test
    fun png16RampDecodesAsHalfFloatWithMoreThan256Levels() {
        val width = 4096
        val png = ByteArrayOutputStream().also { out ->
            PngEncoder.write16(out, width, RAMP_ROWS, { _, row ->
                for (x in 0 until width) {
                    val code = (x * 65535L / (width - 1)).toInt().toShort()
                    row[4 * x] = code
                    row[4 * x + 1] = code
                    row[4 * x + 2] = code
                    row[4 * x + 3] = 0xFFFF.toShort()
                }
            }, exif = null)
        }.toByteArray()

        val bitmap = decodeWithImageDecoder(png)
        record("png16.config", bitmap.config)
        record("png16.colorSpace", bitmap.colorSpace?.name)
        assertEquals(width, bitmap.width)
        assertEquals(RAMP_ROWS, bitmap.height)
        assertEquals("16-bit PNG must decode to half floats", Bitmap.Config.RGBA_F16, bitmap.config)
        // sRGB-encoded F16 decodes as EXTENDED_SRGB: the sRGB primaries and transfer function, unclamped range.
        assertTrue("png16 colour space ${bitmap.colorSpace}", bitmap.colorSpace == extendedSrgb || bitmap.colorSpace == srgb)

        val red = halfRedRow(bitmap, y = 1)
        val levels = red.toSet().size
        record("png16.distinctLevels", levels)
        assertTrue("16-bit ramp kept only $levels levels", levels > 256)
        for (x in 1 until width) assertTrue("ramp not monotone at $x", red[x] >= red[x - 1])
        assertEquals(0f, red.first(), 1e-6f)
        assertEquals(1f, red.last(), 1e-3f)

        // Negative control: the same ramp through our 8-bit PNG has at most 256 levels, so the count tells them apart.
        val png8 = ByteArrayOutputStream().also { out ->
            PngEncoder.write8(out, width, RAMP_ROWS, alpha = false, rows = { _, row ->
                for (x in 0 until width) row[x] = Color.rgb(x * 255 / (width - 1), 0, 0)
            }, exif = null)
        }.toByteArray()
        val bitmap8 = decodeWithImageDecoder(png8)
        assertEquals(Bitmap.Config.ARGB_8888, bitmap8.config)
        val levels8 = bitmap8.argbPixels().copyOfRange(width, 2 * width).map { Color.red(it) }.toSet().size
        record("png8.distinctLevels", levels8)
        assertTrue("8-bit control kept $levels8 levels", levels8 in 2..256)
        bitmap.recycle()
        bitmap8.recycle()
    }

    // ---- 2. JPEG 4:4:4 ------------------------------------------------------------------------------------------

    @Test
    fun jpeg444DecodesOnBothDecodersAsSrgbAndKeepsOnePixelChroma() {
        val jpeg = ByteArrayOutputStream().also { JpegEncoder.write(it, CHECKER, CHECKER, checker, quality = 95, icc = SrgbIccProfile.bytes, exifTiff = null) }
            .toByteArray()

        val viaFactory = checkNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)) { "BitmapFactory could not decode our JPEG" }
        val viaDecoder = decodeWithImageDecoder(jpeg)
        for ((name, bitmap) in listOf("BitmapFactory" to viaFactory, "ImageDecoder" to viaDecoder)) {
            record("jpeg444.$name.colorSpace", bitmap.colorSpace?.name)
            assertEquals(name, CHECKER, bitmap.width)
            assertEquals(name, CHECKER, bitmap.height)
            assertEquals("$name colour space", srgb, bitmap.colorSpace)
            val margin = checkerMargin(bitmap)
            record("jpeg444.$name.minChromaMargin", margin)
            assertTrue("$name: a checker pixel lost its own chroma (min R-B margin $margin)", margin >= MIN_CHROMA_MARGIN)
        }

        // Negative control: the platform's 4:2:0 JPEG of the same checker averages chroma over 2x2 blocks.
        val source = Bitmap.createBitmap(CHECKER, CHECKER, Bitmap.Config.ARGB_8888)
        val row = IntArray(CHECKER)
        for (y in 0 until CHECKER) {
            checker.row(y, row)
            source.setPixels(row, 0, CHECKER, 0, y, CHECKER, 1)
        }
        val platformJpeg = ByteArrayOutputStream().also { source.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
        val platform = checkNotNull(BitmapFactory.decodeByteArray(platformJpeg, 0, platformJpeg.size))
        val platformMargin = checkerMargin(platform)
        record("jpeg420.platform.minChromaMargin", platformMargin)
        assertTrue("4:2:0 control unexpectedly kept per-pixel chroma (margin $platformMargin)", platformMargin < MIN_CHROMA_MARGIN)
        listOf(viaFactory, viaDecoder, source, platform).forEach(Bitmap::recycle)
    }

    @Test
    fun jpegIccProfileIsParsedByThePlatform() {
        val ours = decodeWithImageDecoder(jpegWithIcc(SrgbIccProfile.bytes))
        val swapped = decodeWithImageDecoder(jpegWithIcc(swapIccTags(SrgbIccProfile.bytes, "rXYZ", "gXYZ")))
        record("jpegIcc.ours", ours.colorSpace?.name)
        record("jpegIcc.swappedPrimaries", swapped.colorSpace)
        assertEquals(srgb, ours.colorSpace)
        // Control: the same profile with red and green primaries swapped must change the decoded colour space, which
        // only happens when the platform actually reads our APP2 ICC chunk (an absent or rejected ICC decodes as sRGB).
        assertNotEquals("swapped-primaries profile was ignored", srgb, swapped.colorSpace)
        ours.recycle()
        swapped.recycle()
    }

    // ---- 3. WebP lossless ---------------------------------------------------------------------------------------

    @Test
    fun webpExportDecodesToTheExactDitheredPixelsWithItsIcc() {
        val renderer = SyntheticRenderer(gradient(peak = 1f)).apply { keepLast = true }
        val sink = MemorySink()
        val exporter = ExportProcessor(renderer, sink, ultraHdr = null, webpLossless = PlatformWebpLosslessEncoder())
        val result = exporter.exportBlocking(exportRequest(ExportFormat.WEBP_LOSSLESS, ExportResolution.HD_1080))
        val webp = sink.files.single().bytes
        assertEquals("image/webp", result.mimeType)
        assertEquals(webp.size.toLong(), result.byteSize)
        assertEquals("RIFF", String(webp, 0, 4, Charsets.US_ASCII))
        assertEquals("VP8X", String(webp, 12, 4, Charsets.US_ASCII))

        val image = checkNotNull(renderer.last)
        val expected = IntArray(image.width)
        for ((name, bitmap) in listOf(
            "BitmapFactory" to checkNotNull(BitmapFactory.decodeByteArray(webp, 0, webp.size)),
            "ImageDecoder" to decodeWithImageDecoder(webp),
        )) {
            record("webp.$name.colorSpace", bitmap.colorSpace?.name)
            assertEquals(name, Bitmap.Config.ARGB_8888, bitmap.config)
            assertEquals(srgb, bitmap.colorSpace)
            assertEquals(1080, bitmap.width)
            assertEquals(1920, bitmap.height)
            val actual = IntArray(bitmap.width)
            for (y in 0 until bitmap.height) {
                Dither.toSrgb8Row(image, y, expected)
                bitmap.getPixels(actual, 0, bitmap.width, 0, y, bitmap.width, 1)
                assertArrayEquals("$name row $y", expected, actual)
            }
            bitmap.recycle()
        }

        // Control: the container's ICCP chunk is what the decoder reads (swapped primaries change the colour space).
        val lossless = PlatformWebpLosslessEncoder().encode(8, 8) { _, out -> out.fill(0xFF336699.toInt()) }
        val swapped = WebpContainer.withMetadata(lossless, swapIccTags(SrgbIccProfile.bytes, "rXYZ", "gXYZ"), null)
        val swappedBitmap = decodeWithImageDecoder(swapped)
        record("webpIcc.swappedPrimaries", swappedBitmap.colorSpace)
        assertNotEquals("swapped-primaries ICCP was ignored", srgb, swappedBitmap.colorSpace)
        swappedBitmap.recycle()
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private fun jpegWithIcc(icc: ByteArray): ByteArray = ByteArrayOutputStream().also {
        JpegEncoder.write(it, 16, 16, { _, row -> row.fill(0xFF336699.toInt()) }, quality = 95, icc = icc, exifTiff = null)
    }.toByteArray()

    /** Half-float red channel of row [y] of an RGBA_F16 bitmap. */
    private fun halfRedRow(bitmap: Bitmap, y: Int): FloatArray {
        val buffer = ByteBuffer.allocate(bitmap.byteCount).order(ByteOrder.nativeOrder())
        bitmap.copyPixelsToBuffer(buffer)
        buffer.rewind()
        val shorts = buffer.asShortBuffer()
        val stride = bitmap.rowBytes / 2
        return FloatArray(bitmap.width) { x -> android.util.Half.toFloat(shorts.get(y * stride + 4 * x)) }
    }

    /**
     * Smallest signed chroma margin over the checker: `R - B` on red cells, `B - R` on blue cells. A pixel that kept
     * its own colour scores near 255; chroma averaged with its neighbours scores near 0.
     */
    private fun checkerMargin(bitmap: Bitmap): Int {
        val pixels = bitmap.argbPixels()
        var min = Int.MAX_VALUE
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val p = pixels[y * bitmap.width + x]
            val margin = if (isRed(x, y)) Color.red(p) - Color.blue(p) else Color.blue(p) - Color.red(p)
            if (margin < min) min = margin
        }
        return min
    }

    private companion object {
        const val RAMP_ROWS = 4
        const val CHECKER = 64

        /** Red and blue differ by 255 in R - B; this keeps a wide margin for JPEG error and still rejects averaging. */
        const val MIN_CHROMA_MARGIN = 128

        fun isRed(x: Int, y: Int) = (x + y) % 2 == 0

        val checker = Rgba8Rows { y, out ->
            for (x in out.indices) out[x] = if (isRed(x, y)) 0xFFFF0000.toInt() else 0xFF0000FF.toInt()
        }
    }
}
