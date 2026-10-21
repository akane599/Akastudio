package com.akane.akastudio.core.color.render

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.random.Random

class ReadbackDecoderTest {

    @Test
    fun halfFloatStripLandsOnFlippedCanvasRows() {
        // Two strip rows of one pixel, lowest framebuffer row first: they land at canvas rows 2 and 1.
        val strip = ByteBuffer.allocate(2 * 8).order(ByteOrder.nativeOrder())
        strip.asShortBuffer().put(shortArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        val dest = ShortArray(3 * 4)
        ReadbackDecoder.decodeStrip(strip, ReadbackEncoding.HALF_FLOAT, 1, 2, dest, 1, 0, 2)
        assertArrayEquals(shortArrayOf(0, 0, 0, 0, 5, 6, 7, 8, 1, 2, 3, 4), dest)
    }

    @Test
    fun floatAndLogStripsDecodeLikeTheWholeFrameHelpers() {
        val floats = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder())
        floats.asFloatBuffer().put(floatArrayOf(0.5f, 2f, -1f, 1f))
        val fromFloats = ShortArray(4)
        ReadbackDecoder.decodeStrip(floats, ReadbackEncoding.FLOAT, 1, 1, fromFloats, 1, 0, 0)
        assertArrayEquals(RenderMath.floatsToHalf(floatArrayOf(0.5f, 2f, -1f, 1f)), fromFloats)

        val bytes = byteArrayOf(40, 90, 0, 0x80.toByte())
        val fromBytes = ShortArray(4)
        ReadbackDecoder.decodeStrip(ByteBuffer.wrap(bytes), ReadbackEncoding.LOG_BYTES, 1, 1, fromBytes, 1, 0, 0)
        assertArrayEquals(legacyLogBytesToHalf(bytes), fromBytes)
    }

    /**
     * The strip path against the full-frame readback it replaced (one buffer per tile, whole-tile decode, flip,
     * blit), for every encoding, single and multi-tile plans, strip heights that do and do not divide the tile,
     * both byte orders, and random bit patterns (NaN, infinities, subnormals and zero alpha included).
     */
    @Test
    fun stripReadbackIsBitIdenticalToTheFullFrameReadback() {
        val plans = listOf(
            intArrayOf(37, 300, 37, 300), // one tile taller than one default strip
            intArrayOf(50, 41, 17, 13), // 3x4 tiles with ragged edges
            intArrayOf(8, 8, 8, 8),
            intArrayOf(5, 9, 2, 4),
        )
        val stripHeights = listOf(1, 3, 5, 13, WorkingReadback.STRIP_ROWS, Int.MAX_VALUE)
        val random = Random(20)
        for (encoding in ReadbackEncoding.entries) for (order in listOf(ByteOrder.nativeOrder(), ByteOrder.BIG_ENDIAN)) {
            for ((width, height, maxW, maxH) in plans.map { it.toList() }) {
                val tiles = RenderMath.planTiles(width, height, maxW, maxH)
                // What glReadPixels would return for each tile: lowest framebuffer row first.
                val tileBytes = tiles.map { random.nextBytes(it.width * it.height * encoding.bytesPerPixel) }
                val expected = ShortArray(width * height * 4)
                tiles.forEachIndexed { i, tile -> legacyReadTile(tileBytes[i], order, encoding, tile, expected, width) }
                for (stripRows in stripHeights) {
                    val actual = ShortArray(width * height * 4)
                    val reused = ByteBuffer.allocate(maxW * min(stripRows, maxH) * encoding.bytesPerPixel + 24).order(order)
                    tiles.forEachIndexed { i, tile -> stripReadTile(tileBytes[i], reused, encoding, tile, stripRows, actual, width) }
                    assertArrayEquals("$encoding $order ${width}x$height tiles ${maxW}x$maxH strips $stripRows", expected, actual)
                }
            }
        }
    }

    @Test
    fun rejectsStripsOutsideTheCanvas() {
        val strip = ByteBuffer.allocate(2 * 2 * 8)
        val dest = ShortArray(2 * 2 * 4)
        assertThrows(IllegalArgumentException::class.java) {
            ReadbackDecoder.decodeStrip(strip, ReadbackEncoding.HALF_FLOAT, 2, 2, dest, 2, 1, 1) // column overflow
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReadbackDecoder.decodeStrip(strip, ReadbackEncoding.HALF_FLOAT, 2, 2, dest, 2, 0, 0) // runs above row 0
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReadbackDecoder.decodeStrip(strip, ReadbackEncoding.HALF_FLOAT, 2, 2, dest, 2, 0, 2) // below the last row
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReadbackDecoder.decodeStrip(ByteBuffer.allocate(8), ReadbackEncoding.HALF_FLOAT, 2, 2, dest, 2, 0, 1) // short buffer
        }
    }

    /** Mirrors [WorkingReadback.read]: strips of [stripRows] rows, each copied into the reused (dirty) buffer. */
    private fun stripReadTile(bytes: ByteArray, reused: ByteBuffer, encoding: ReadbackEncoding, tile: Tile, stripRows: Int, dest: ShortArray, destWidth: Int) {
        val rowBytes = tile.width * encoding.bytesPerPixel
        var done = 0
        while (done < tile.height) {
            val rows = min(stripRows, tile.height - done)
            reused.clear()
            reused.put(bytes, done * rowBytes, rows * rowBytes)
            reused.position(7) // the decoder must not depend on the caller's position
            ReadbackDecoder.decodeStrip(reused, encoding, tile.width, rows, dest, destWidth, tile.x, tile.y + tile.height - 1 - done)
            done += rows
        }
    }

    /** The pre-F-20 `GlStoryRenderer.readWorkingPixels` + `blitTile`, verbatim apart from the GL call. */
    private fun legacyReadTile(bytes: ByteArray, order: ByteOrder, encoding: ReadbackEncoding, tile: Tile, dest: ShortArray, destWidth: Int) {
        val count = tile.width * tile.height * 4
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(order)
        buffer.put(bytes).rewind()
        val halves = when (encoding) {
            ReadbackEncoding.LOG_BYTES -> {
                val raw = ByteArray(count)
                buffer.get(raw)
                legacyLogBytesToHalf(raw)
            }
            ReadbackEncoding.HALF_FLOAT -> ShortArray(count).also { buffer.asShortBuffer().get(it) }
            ReadbackEncoding.FLOAT -> {
                val floats = FloatArray(count)
                buffer.asFloatBuffer().get(floats)
                RenderMath.floatsToHalf(floats)
            }
        }
        legacyFlipRows(halves, tile.width * 4, tile.height)
        legacyBlitTile(halves, tile.width, tile.height, dest, destWidth, tile.x, tile.y, 4)
    }

    /** The pre-F-20 `RenderMath.flipRows`: reverses [rows] rows of [rowLength] elements in place. */
    private fun legacyFlipRows(data: ShortArray, rowLength: Int, rows: Int) {
        val scratch = ShortArray(rowLength)
        for (top in 0 until rows / 2) {
            val bottom = rows - 1 - top
            System.arraycopy(data, top * rowLength, scratch, 0, rowLength)
            System.arraycopy(data, bottom * rowLength, data, top * rowLength, rowLength)
            System.arraycopy(scratch, 0, data, bottom * rowLength, rowLength)
        }
    }

    /** The pre-F-20 `RenderMath.blitTile`: copies a top-first tile of [channels]-wide pixels into [dest] at ([x], [y]). */
    private fun legacyBlitTile(tile: ShortArray, tileWidth: Int, tileHeight: Int, dest: ShortArray, destWidth: Int, x: Int, y: Int, channels: Int) {
        val rowLength = tileWidth * channels
        for (row in 0 until tileHeight) {
            System.arraycopy(tile, row * rowLength, dest, ((y + row) * destWidth + x) * channels, rowLength)
        }
    }

    /** The pre-F-20 `RenderMath.logBytesToHalf`, kept as an independent oracle. */
    private fun legacyLogBytesToHalf(rgba: ByteArray): ShortArray {
        val out = ShortArray(rgba.size)
        var i = 0
        while (i + 3 < rgba.size) {
            val a = (rgba[i + 3].toInt() and 0xFF) / 255f
            for (c in 0 until 3) {
                val stored = (rgba[i + c].toInt() and 0xFF) / 255f
                val linear = if (a > 0f) RenderMath.logDecode(stored / a) * a else 0f
                out[i + c] = RenderMath.floatToHalf(linear)
            }
            out[i + 3] = RenderMath.floatToHalf(a)
            i += 4
        }
        return out
    }
}
