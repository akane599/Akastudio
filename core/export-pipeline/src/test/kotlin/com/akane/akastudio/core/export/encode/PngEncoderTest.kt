package com.akane.akastudio.core.export.encode

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Random
import javax.imageio.ImageIO

class PngEncoderTest {

    private val exif = ExifWriter.tiff(ExifFields(1_700_000_000_000L, "png", "AkaStudio", 33, 21))

    private fun argb(x: Int, y: Int): Int {
        val r = (x * 7 + y * 3) and 0xFF
        val g = (x * 13 xor y * 5) and 0xFF
        val b = (x + y * 11) and 0xFF
        val a = (x * 29 + y * 17 + 40) and 0xFF
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun encode8(width: Int, height: Int, alpha: Boolean, exif: ByteArray? = null, pixel: (Int, Int) -> Int = ::argb): ByteArray {
        val out = ByteArrayOutputStream()
        PngEncoder.write8(out, width, height, alpha, { y, row -> for (x in 0 until width) row[x] = pixel(x, y) }, exif)
        return out.toByteArray()
    }

    @Test
    fun rgb8_decodesToExactPixels() {
        val png = encode8(33, 21, alpha = false, exif = exif)
        val image = ImageIO.read(ByteArrayInputStream(png))
        assertEquals(33, image.width)
        assertEquals(21, image.height)
        assertFalse(image.colorModel.hasAlpha())
        for (y in 0 until 21) for (x in 0 until 33) {
            assertEquals("($x,$y)", argb(x, y) and 0xFFFFFF, image.getRGB(x, y) and 0xFFFFFF)
        }
        assertEquals(2, parsePngChunks(png).first().data[9].toInt())
    }

    @Test
    fun rgba8_decodesToExactPixels() {
        val png = encode8(33, 21, alpha = true)
        val image = ImageIO.read(ByteArrayInputStream(png))
        assertTrue(image.colorModel.hasAlpha())
        val raster = image.raster
        for (y in 0 until 21) for (x in 0 until 33) {
            val expected = argb(x, y)
            assertEquals((expected ushr 16) and 0xFF, raster.getSample(x, y, 0))
            assertEquals((expected ushr 8) and 0xFF, raster.getSample(x, y, 1))
            assertEquals(expected and 0xFF, raster.getSample(x, y, 2))
            assertEquals(expected ushr 24, raster.getSample(x, y, 3))
        }
    }

    @Test
    fun rgba16_decodesToExactSamples() {
        val width = 19
        val height = 11
        fun sample(x: Int, y: Int, c: Int): Int = (x * 3451 + y * 1201 + c * 16411 + x * y * 97) and 0xFFFF
        val out = ByteArrayOutputStream()
        PngEncoder.write16(out, width, height, { y, row ->
            for (x in 0 until width) for (c in 0 until 4) row[x * 4 + c] = sample(x, y, c).toShort()
        }, exif)
        val png = out.toByteArray()
        val ihdr = parsePngChunks(png).first()
        assertEquals(16, ihdr.data[8].toInt())
        assertEquals(6, ihdr.data[9].toInt())
        val raster = ImageIO.read(ByteArrayInputStream(png)).raster
        assertEquals(16, raster.sampleModel.getSampleSize(0))
        for (y in 0 until height) for (x in 0 until width) for (c in 0 until 4) {
            assertEquals("($x,$y,$c)", sample(x, y, c), raster.getSample(x, y, c))
        }
    }

    @Test
    fun chunks_crcValidAndColourSpaceDeclaredBeforeIdat() {
        val png = encode8(33, 21, alpha = true, exif = exif)
        val chunks = parsePngChunks(png)
        for (chunk in chunks) assertEquals("CRC of ${chunk.type}", chunk.computedCrc, chunk.storedCrc)
        val types = chunks.map { it.type }
        assertEquals("IHDR", types.first())
        assertEquals("IEND", types.last())
        val firstIdat = types.indexOf("IDAT")
        for (required in listOf("sRGB", "gAMA", "cHRM", "eXIf")) {
            val index = types.indexOf(required)
            assertTrue("$required present before IDAT", index in 1 until firstIdat)
        }
        assertFalse(types.contains("iCCP"))
        assertEquals(0, chunks.single { it.type == "sRGB" }.data.single().toInt())
        assertEquals(45455, ByteBuffer.wrap(chunks.single { it.type == "gAMA" }.data).int)
        val chrm = ByteBuffer.wrap(chunks.single { it.type == "cHRM" }.data)
        assertEquals(listOf(31270, 32900, 64000, 33000, 30000, 60000, 15000, 6000), List(8) { chrm.int })
        assertArrayEquals(exif, chunks.single { it.type == "eXIf" }.data)
    }

    @Test
    fun noExif_omitsChunk() {
        assertFalse(parsePngChunks(encode8(4, 4, alpha = false)).any { it.type == "eXIf" })
    }

    @Test
    fun idat_splitIntoChunksOfAtMost64KiB() {
        val random = Random(5)
        val noise = IntArray(300 * 300) { random.nextInt() }
        val png = encode8(300, 300, alpha = true) { x, y -> noise[y * 300 + x] }
        val idats = parsePngChunks(png).filter { it.type == "IDAT" }
        assertTrue("expected several IDAT chunks, got ${idats.size}", idats.size > 1)
        assertTrue(idats.all { it.data.size <= PngEncoder.MAX_IDAT_BYTES })
        val image = ImageIO.read(ByteArrayInputStream(png))
        assertEquals(noise[123 * 300 + 77], image.getRGB(77, 123))
    }

    @Test
    fun adaptiveFilter_beatsFilterNoneOnGradient() {
        val width = 256
        val height = 128
        val rows = Rgba8Rows { y, row ->
            for (x in 0 until width) {
                val r = (x * 255 / (width - 1))
                val g = (y * 255 / (height - 1))
                val b = ((x + y) * 255 / (width + height - 2))
                row[x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val adaptive = ByteArrayOutputStream().also { PngEncoder.write8(it, width, height, false, rows, null) }.toByteArray()
        val none = ByteArrayOutputStream().also { PngEncoder.write8(it, width, height, false, rows, null, 6, forcedFilter = 0) }.toByteArray()
        assertTrue("adaptive ${adaptive.size} < none ${none.size}", adaptive.size < none.size)
        val image = ImageIO.read(ByteArrayInputStream(adaptive))
        assertEquals(0xFFFFFF and rows.let { val r = IntArray(width); it.row(77, r); r[200] }, image.getRGB(200, 77) and 0xFFFFFF)
    }

    @Test
    fun filters_reconstructExactly() {
        val bpp = 4
        val random = Random(9)
        val previous = ByteArray(40).also { random.nextBytes(it) }
        val current = ByteArray(40).also { random.nextBytes(it) }
        for (type in 0..4) {
            val filtered = ByteArray(41)
            PngEncoder.applyFilter(type, current, previous, bpp, filtered)
            assertEquals(type, filtered[0].toInt())
            val restored = ByteArray(40)
            for (i in 0 until 40) {
                val a = if (i >= bpp) restored[i - bpp].toInt() and 0xFF else 0
                val b = previous[i].toInt() and 0xFF
                val c = if (i >= bpp) previous[i - bpp].toInt() and 0xFF else 0
                val predictor = when (type) {
                    0 -> 0
                    1 -> a
                    2 -> b
                    3 -> (a + b) / 2
                    else -> {
                        val p = a + b - c
                        val pa = kotlin.math.abs(p - a)
                        val pb = kotlin.math.abs(p - b)
                        val pc = kotlin.math.abs(p - c)
                        if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                    }
                }
                restored[i] = (filtered[i + 1] + predictor).toByte()
            }
            assertArrayEquals("filter $type", current, restored)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidDimensions_rejected() {
        PngEncoder.write8(ByteArrayOutputStream(), 0, 5, false, { _, _ -> }, null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidLevel_rejected() {
        PngEncoder.write8(ByteArrayOutputStream(), 1, 1, false, { _, _ -> }, null, level = 10)
    }
}
