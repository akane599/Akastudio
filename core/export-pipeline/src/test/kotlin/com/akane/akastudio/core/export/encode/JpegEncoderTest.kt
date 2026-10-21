package com.akane.akastudio.core.export.encode

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random
import javax.imageio.ImageIO
import kotlin.math.log10
import kotlin.math.sin

class JpegEncoderTest {

    private fun smooth(x: Int, y: Int): Int {
        val r = (128 + 100 * sin(x / 23.0) * sin(y / 31.0)).toInt()
        val g = (40 + x * 150 / 160 + y / 4).coerceIn(0, 255)
        val b = (200 - y * 120 / 120 + (20 * sin((x + y) / 17.0)).toInt()).coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private val noise = Random(3).let { random -> IntArray(160 * 120) { random.nextInt(97) - 48 } }

    private fun noisy(x: Int, y: Int): Int {
        val base = smooth(x, y)
        val n = noise[y * 160 + x]
        val r = (((base ushr 16) and 0xFF) + n).coerceIn(0, 255)
        val g = (((base ushr 8) and 0xFF) - n / 2).coerceIn(0, 255)
        val b = ((base and 0xFF) + n).coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun encode(
        width: Int,
        height: Int,
        quality: Int = 95,
        icc: ByteArray? = SrgbIccProfile.bytes,
        exif: ByteArray? = null,
        pixel: (Int, Int) -> Int,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        JpegEncoder.write(out, width, height, { y, row -> for (x in 0 until width) row[x] = pixel(x, y) }, quality, icc, exif)
        return out.toByteArray()
    }

    private fun psnr(jpeg: ByteArray, width: Int, height: Int, pixel: (Int, Int) -> Int): Double {
        val image = ImageIO.read(ByteArrayInputStream(jpeg))
        assertEquals(width, image.width)
        assertEquals(height, image.height)
        var squared = 0.0
        for (y in 0 until height) for (x in 0 until width) {
            val expected = pixel(x, y)
            val actual = image.getRGB(x, y)
            for (shift in intArrayOf(16, 8, 0)) {
                val d = ((expected ushr shift) and 0xFF) - ((actual ushr shift) and 0xFF)
                squared += d * d
            }
        }
        val mse = squared / (width * height * 3)
        return 10 * log10(255.0 * 255.0 / mse)
    }

    @Test
    fun smoothImage_q95_psnrAbove38() {
        val value = psnr(encode(160, 120, icc = null, pixel = ::smooth), 160, 120, ::smooth)
        assertTrue("PSNR $value", value > 38.0)
    }

    @Test
    fun noisyImage_q95_psnrAbove30() {
        val value = psnr(encode(160, 120, icc = null, pixel = ::noisy), 160, 120, ::noisy)
        assertTrue("PSNR $value", value > 30.0)
    }

    @Test
    fun withDefaultIccAndExif_decodesThroughImageIo() {
        val exif = ExifWriter.tiff(ExifFields(1_720_000_000_000L, null, "AkaStudio", 160, 120))
        val value = psnr(encode(160, 120, exif = exif, pixel = ::smooth), 160, 120, ::smooth)
        assertTrue("PSNR $value", value > 38.0)
    }

    @Test
    fun segments_inOrderWith444Sampling() {
        val exif = ExifWriter.tiff(ExifFields(1_720_000_000_000L, null, "AkaStudio", 17, 9))
        val jpeg = encode(17, 9, exif = exif, pixel = ::smooth)
        val markers = parseJpegSegments(jpeg).map { it.marker }
        assertEquals(listOf(0xE0, 0xE1, 0xE2, 0xDB, 0xC0, 0xC4, 0xDA), markers)
        val sof = parseJpegSegments(jpeg).single { it.marker == 0xC0 }.payload
        assertEquals(8, sof[0].toInt())
        assertEquals(9, ((sof[1].toInt() and 0xFF) shl 8) or (sof[2].toInt() and 0xFF))
        assertEquals(17, ((sof[3].toInt() and 0xFF) shl 8) or (sof[4].toInt() and 0xFF))
        assertEquals(3, sof[5].toInt())
        for (component in 0 until 3) assertEquals(0x11, sof[6 + component * 3 + 1].toInt())
        assertEquals(0xFF.toByte(), jpeg[jpeg.size - 2])
        assertEquals(0xD9.toByte(), jpeg[jpeg.size - 1])
    }

    @Test
    fun app2_iccChunksReassembleToProfile() {
        val jpeg = encode(8, 8, pixel = ::smooth)
        assertArrayEquals(SrgbIccProfile.bytes, reassembleIcc(jpeg, expectedChunks = 1))
    }

    @Test
    fun app2_largeIccSplitIntoSequencedChunks() {
        val icc = ByteArray(150_000) { (it * 31).toByte() }
        val jpeg = encode(8, 8, icc = icc, pixel = ::smooth)
        assertArrayEquals(icc, reassembleIcc(jpeg, expectedChunks = 3))
    }

    private fun reassembleIcc(jpeg: ByteArray, expectedChunks: Int): ByteArray {
        val chunks = parseJpegSegments(jpeg).filter { it.marker == 0xE2 }
        assertEquals(expectedChunks, chunks.size)
        val header = "ICC_PROFILE".toByteArray(Charsets.US_ASCII) + 0.toByte()
        val out = ByteArrayOutputStream()
        chunks.forEachIndexed { index, segment ->
            assertArrayEquals(header, segment.payload.copyOfRange(0, 12))
            assertEquals(index + 1, segment.payload[12].toInt() and 0xFF)
            assertEquals(expectedChunks, segment.payload[13].toInt() and 0xFF)
            assertTrue(segment.payload.size - 14 <= JpegEncoder.MAX_ICC_CHUNK)
            out.write(segment.payload, 14, segment.payload.size - 14)
        }
        return out.toByteArray()
    }

    @Test
    fun app1_tiffParsesWithSoftwareAndDateTimeOriginal() {
        val exif = ExifWriter.tiff(ExifFields(1_720_000_000_000L, "Porto", "AkaStudio 2", 16, 16))
        val app1 = parseJpegSegments(encode(16, 16, exif = exif, pixel = ::smooth)).single { it.marker == 0xE1 }.payload
        assertArrayEquals(byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0), app1.copyOfRange(0, 6))
        val reader = TestTiffReader(app1.copyOfRange(6, app1.size))
        val ifd0 = reader.readIfd(reader.ifd0Offset)
        assertEquals("AkaStudio 2", ifd0[ExifWriter.TAG_SOFTWARE]!!.ascii())
        val exifIfd = reader.readIfd(ifd0[ExifWriter.TAG_EXIF_IFD_POINTER]!!.uint().toInt())
        assertTrue(exifIfd[ExifWriter.TAG_DATE_TIME_ORIGINAL]!!.ascii().matches(Regex("""\d{4}:\d{2}:\d{2} \d{2}:\d{2}:\d{2}""")))
    }

    @Test
    fun oddSizes_decode() {
        for ((w, h) in listOf(1 to 1, 17 to 9, 9 to 17, 8 to 1)) {
            val pixel = { x: Int, y: Int -> smooth(x * 9, y * 13) }
            val jpeg = encode(w, h, pixel = pixel)
            val image = ImageIO.read(ByteArrayInputStream(jpeg))
            assertEquals(w, image.width)
            assertEquals(h, image.height)
            assertTrue("PSNR at ${w}x$h", psnr(jpeg, w, h, pixel) > 30.0)
        }
    }

    @Test
    fun extremeQualities_decode() {
        for (quality in intArrayOf(1, 50, 100)) {
            val jpeg = encode(40, 24, quality = quality, icc = null, pixel = ::noisy)
            assertEquals(40, ImageIO.read(ByteArrayInputStream(jpeg)).width)
        }
        assertTrue(psnr(encode(40, 24, quality = 100, icc = null, pixel = ::noisy), 40, 24, ::noisy) > 45.0)
    }

    @Test
    fun saturatedColours_decodeWithoutOverflow() {
        val pixel = { x: Int, y: Int -> if ((x / 2 + y / 2) % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        val jpeg = encode(32, 32, quality = 100, icc = null, pixel = pixel)
        assertTrue(psnr(jpeg, 32, 32, pixel) > 30.0)
    }

    @Test
    fun tables_quantScalingAndHuffmanSpecsConsistent() {
        assertTrue(JpegTables.scaledQuant(JpegTables.LUMINANCE_QUANT, 100).all { it == 1 })
        assertArrayEquals(JpegTables.LUMINANCE_QUANT, JpegTables.scaledQuant(JpegTables.LUMINANCE_QUANT, 50))
        assertTrue(JpegTables.scaledQuant(JpegTables.CHROMINANCE_QUANT, 1).all { it in 1..255 })
        assertEquals((0 until 64).toList(), JpegTables.ZIGZAG.sorted())
        assertEquals(162, HuffmanTable(JpegTables.AC_LUMINANCE_BITS, JpegTables.AC_LUMINANCE_VALUES).values.size)
        assertEquals(162, HuffmanTable(JpegTables.AC_CHROMINANCE_BITS, JpegTables.AC_CHROMINANCE_VALUES).values.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedExif_rejected() {
        encode(8, 8, exif = ByteArray(70_000), pixel = ::smooth)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidQuality_rejected() {
        encode(8, 8, quality = 0, pixel = ::smooth)
    }
}
