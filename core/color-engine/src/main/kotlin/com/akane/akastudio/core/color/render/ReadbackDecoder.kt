package com.akane.akastudio.core.color.render

import java.nio.ByteBuffer

/** Client layout in which a working target's pixels come back from `glReadPixels`. */
internal enum class ReadbackEncoding(val bytesPerPixel: Int) {
    /** (RGBA, UNSIGNED_BYTE) of a log-domain RGBA8 target, decoded to linear light. */
    LOG_BYTES(4),

    /** (RGBA, HALF_FLOAT), when the implementation offers it: copied as is. */
    HALF_FLOAT(8),

    /** (RGBA, FLOAT), always legal for float targets: rounded to half floats. */
    FLOAT(16),
}

/**
 * Pure decode of one `glReadPixels` strip into a top-first half-float canvas. Strips arrive lowest row first,
 * so each strip row is written straight into its flipped canvas row: no full-frame staging copy and no
 * separate flip pass. Bit-identical to decoding the whole region at once and flipping it afterwards.
 */
internal object ReadbackDecoder {

    private const val CHANNELS = 4

    /**
     * Decodes [rows] rows of [width] RGBA pixels, tightly packed in [strip] from index 0 in [encoding]
     * (lowest framebuffer row first, in the buffer's byte order), into [dest], a top-first canvas
     * [destWidth] pixels wide: strip row j lands at canvas row [destRowOfFirst] - j, starting at column [destX].
     */
    fun decodeStrip(
        strip: ByteBuffer,
        encoding: ReadbackEncoding,
        width: Int,
        rows: Int,
        dest: ShortArray,
        destWidth: Int,
        destX: Int,
        destRowOfFirst: Int,
    ) {
        require(width > 0 && rows > 0) { "strip must be non-empty: ${width}x$rows" }
        require(strip.capacity() >= width * rows * encoding.bytesPerPixel) { "strip buffer too small for ${width}x$rows $encoding" }
        require(destX >= 0 && destX + width <= destWidth) { "columns $destX+$width outside canvas width $destWidth" }
        require(destRowOfFirst - rows + 1 >= 0 && (destRowOfFirst + 1) * destWidth * CHANNELS <= dest.size) {
            "rows ${destRowOfFirst - rows + 1}..$destRowOfFirst outside the canvas"
        }
        // A private view: absolute indexing from 0 whatever the caller's position, in the caller's byte order.
        val view = strip.duplicate().order(strip.order())
        view.clear()
        val rowLength = width * CHANNELS
        when (encoding) {
            ReadbackEncoding.HALF_FLOAT -> {
                val halves = view.asShortBuffer()
                for (j in 0 until rows) {
                    halves.position(j * rowLength)
                    halves.get(dest, destOffset(destRowOfFirst - j, destWidth, destX), rowLength)
                }
            }
            ReadbackEncoding.FLOAT -> {
                val floats = view.asFloatBuffer()
                for (j in 0 until rows) {
                    val src = j * rowLength
                    val dst = destOffset(destRowOfFirst - j, destWidth, destX)
                    for (i in 0 until rowLength) dest[dst + i] = RenderMath.floatToHalf(floats.get(src + i))
                }
            }
            ReadbackEncoding.LOG_BYTES -> {
                for (j in 0 until rows) {
                    val src = j * rowLength
                    val dst = destOffset(destRowOfFirst - j, destWidth, destX)
                    var i = 0
                    while (i < rowLength) {
                        val alpha = view.get(src + i + 3).toInt() and 0xFF
                        for (c in 0 until 3) dest[dst + i + c] = RenderMath.logChannelToHalf(view.get(src + i + c).toInt() and 0xFF, alpha)
                        dest[dst + i + 3] = RenderMath.floatToHalf(alpha / 255f)
                        i += CHANNELS
                    }
                }
            }
        }
    }

    private fun destOffset(row: Int, destWidth: Int, destX: Int): Int = (row * destWidth + destX) * CHANNELS
}
