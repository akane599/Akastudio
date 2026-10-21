package com.akane.akastudio.core.export.encode

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class WebpContainerTest {

    private class RiffChunk(val type: String, val payload: ByteArray)

    private fun le32(value: Int) = byteArrayOf(value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte())

    private fun chunk(type: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(type.toByteArray(Charsets.US_ASCII))
        out.write(le32(payload.size))
        out.write(payload)
        if (payload.size % 2 == 1) out.write(0)
        return out.toByteArray()
    }

    private fun riff(vararg chunks: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        body.write("WEBP".toByteArray(Charsets.US_ASCII))
        chunks.forEach { body.write(it) }
        return "RIFF".toByteArray(Charsets.US_ASCII) + le32(body.size()) + body.toByteArray()
    }

    /** VP8L payload: 0x2F, 14-bit width-1, 14-bit height-1, alpha hint, version 0, then a fake bitstream. */
    private fun vp8l(width: Int, height: Int, alpha: Boolean, fakeBytes: Int = 7): ByteArray {
        val bits = (width - 1) or ((height - 1) shl 14) or ((if (alpha) 1 else 0) shl 28)
        return byteArrayOf(0x2F) + le32(bits) + ByteArray(fakeBytes) { (0xA0 + it).toByte() }
    }

    private fun parse(webp: ByteArray): List<RiffChunk> {
        assertEquals("RIFF", String(webp, 0, 4, Charsets.US_ASCII))
        assertEquals(webp.size - 8, readLe32(webp, 4))
        assertEquals("WEBP", String(webp, 8, 4, Charsets.US_ASCII))
        val chunks = ArrayList<RiffChunk>()
        var position = 12
        while (position < webp.size) {
            val size = readLe32(webp, position + 4)
            chunks += RiffChunk(String(webp, position, 4, Charsets.US_ASCII), webp.copyOfRange(position + 8, position + 8 + size))
            position += 8 + size + (size and 1)
        }
        assertEquals(webp.size, position)
        return chunks
    }

    private fun readLe32(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or ((data[offset + 3].toInt() and 0xFF) shl 24)

    private fun readLe24(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8) or ((data[offset + 2].toInt() and 0xFF) shl 16)

    private val icc = SrgbIccProfile.bytes
    private val exif = ExifWriter.tiff(ExifFields(null, "webp", "AkaStudio", 1080, 1920))

    @Test
    fun simpleLossless_becomesVp8xWithFlagsCanvasAndOrder() {
        val image = vp8l(1080, 1920, alpha = false)
        val result = WebpContainer.withMetadata(riff(chunk("VP8L", image)), icc, exif)
        val chunks = parse(result)
        assertEquals(listOf("VP8X", "ICCP", "VP8L", "EXIF"), chunks.map { it.type })
        val vp8x = chunks[0].payload
        assertEquals(10, vp8x.size)
        assertEquals(WebpContainer.FLAG_ICC or WebpContainer.FLAG_EXIF, vp8x[0].toInt() and 0xFF)
        assertEquals(1079, readLe24(vp8x, 4))
        assertEquals(1919, readLe24(vp8x, 7))
        assertArrayEquals(icc, chunks[1].payload)
        assertArrayEquals(image, chunks[2].payload)
        assertArrayEquals(exif, chunks[3].payload)
        assertEquals(result.size - 8, readLe32(result, 4))
    }

    @Test
    fun alphaHint_setsAlphaFlag_oddChunksPadded() {
        val image = vp8l(16383, 3, alpha = true, fakeBytes = 6) // 11-byte payload: odd
        val oddIcc = ByteArray(5) { it.toByte() }
        val result = WebpContainer.withMetadata(riff(chunk("VP8L", image)), oddIcc, null)
        assertEquals(0, result.size % 2)
        val chunks = parse(result)
        assertEquals(listOf("VP8X", "ICCP", "VP8L"), chunks.map { it.type })
        assertEquals(WebpContainer.FLAG_ICC or WebpContainer.FLAG_ALPHA, chunks[0].payload[0].toInt() and 0xFF)
        assertEquals(16382, readLe24(chunks[0].payload, 4))
        assertEquals(2, readLe24(chunks[0].payload, 7))
        assertArrayEquals(image, chunks[2].payload)
    }

    @Test
    fun existingVp8x_iccAndExifReplaced_otherChunksKeptInOrder() {
        val oldVp8x = ByteArray(10).also {
            it[0] = (WebpContainer.FLAG_ICC or WebpContainer.FLAG_EXIF or WebpContainer.FLAG_XMP).toByte()
            it[4] = 99; it[7] = 49
        }
        val image = vp8l(100, 50, alpha = false)
        val xmp = "<x:xmpmeta/>".toByteArray(Charsets.US_ASCII)
        val input = riff(
            chunk("VP8X", oldVp8x),
            chunk("ICCP", ByteArray(9) { 1 }),
            chunk("VP8L", image),
            chunk("EXIF", ByteArray(4) { 2 }),
            chunk("XMP ", xmp),
        )
        val result = WebpContainer.withMetadata(input, icc, exif)
        val chunks = parse(result)
        assertEquals(listOf("VP8X", "ICCP", "VP8L", "EXIF", "XMP "), chunks.map { it.type })
        assertArrayEquals(icc, chunks[1].payload)
        assertArrayEquals(exif, chunks[3].payload)
        assertArrayEquals(xmp, chunks[4].payload)
        assertEquals(WebpContainer.FLAG_ICC or WebpContainer.FLAG_EXIF or WebpContainer.FLAG_XMP, chunks[0].payload[0].toInt() and 0xFF)
        assertEquals(99, readLe24(chunks[0].payload, 4))
        assertEquals(49, readLe24(chunks[0].payload, 7))
        assertEquals(1, chunks.count { it.type == "ICCP" })
    }

    @Test
    fun existingVp8x_nullMetadataKeepsExistingChunks_alphChunkSetsAlpha() {
        val oldVp8x = ByteArray(10).also { it[0] = WebpContainer.FLAG_ICC.toByte(); it[4] = 7; it[7] = 7 }
        val oldIcc = ByteArray(6) { 3 }
        val input = riff(
            chunk("VP8X", oldVp8x),
            chunk("ICCP", oldIcc),
            chunk("ALPH", ByteArray(3) { 4 }),
            chunk("VP8 ", ByteArray(12) { 5 }),
        )
        val chunks = parse(WebpContainer.withMetadata(input, null, exif))
        assertEquals(listOf("VP8X", "ICCP", "ALPH", "VP8 ", "EXIF"), chunks.map { it.type })
        assertArrayEquals(oldIcc, chunks[1].payload)
        assertEquals(
            WebpContainer.FLAG_ICC or WebpContainer.FLAG_ALPHA or WebpContainer.FLAG_EXIF,
            chunks[0].payload[0].toInt() and 0xFF,
        )
    }

    @Test
    fun truncatedInputs_throw() {
        val valid = riff(chunk("VP8L", vp8l(10, 10, alpha = false)))
        for (length in listOf(0, 5, 11, 12, 19, valid.size - 1)) {
            assertThrowsIae("length $length") { WebpContainer.withMetadata(valid.copyOf(length), icc, exif) }
        }
    }

    @Test
    fun malformedInputs_throwWithReason() {
        val image = vp8l(10, 10, alpha = false)
        val badSignature = image.copyOf().also { it[0] = 0x2E }
        val cases = mapOf(
            "not RIFF" to riff(chunk("VP8L", image)).also { it[3] = 'X'.code.toByte() },
            "not WEBP" to riff(chunk("VP8L", image)).also { it[11] = 'X'.code.toByte() },
            "bad VP8L signature" to riff(chunk("VP8L", badSignature)),
            "lossy simple" to riff(chunk("VP8 ", ByteArray(10))),
            "unknown first chunk" to riff(chunk("JUNK", ByteArray(4))),
            "VP8X without image" to riff(chunk("VP8X", ByteArray(10)), chunk("EXIF", ByteArray(4))),
            "short VP8X" to riff(chunk("VP8X", ByteArray(6)), chunk("VP8L", image)),
            "extra chunk in simple file" to riff(chunk("VP8L", image), chunk("EXIF", ByteArray(4))),
            "short VP8L" to riff(chunk("VP8L", byteArrayOf(0x2F, 1, 2))),
        )
        for ((name, input) in cases) assertThrowsIae(name) { WebpContainer.withMetadata(input, icc, exif) }
    }

    @Test
    fun noMetadata_stillProducesValidVp8x() {
        val chunks = parse(WebpContainer.withMetadata(riff(chunk("VP8L", vp8l(2, 2, alpha = false))), null, null))
        assertEquals(listOf("VP8X", "VP8L"), chunks.map { it.type })
        assertEquals(0, chunks[0].payload[0].toInt())
        assertFalse(chunks.any { it.type == "ICCP" || it.type == "EXIF" })
    }

    private fun assertThrowsIae(name: String, block: () -> Unit) {
        try {
            block()
        } catch (expected: IllegalArgumentException) {
            assertTrue("$name: reason present", !expected.message.isNullOrBlank())
            return
        }
        throw AssertionError("$name: expected IllegalArgumentException")
    }
}
