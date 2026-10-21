package com.akane.akastudio.core.color.render

import android.opengl.GLES20
import android.opengl.GLES30
import com.akane.akastudio.core.color.gl.checkGlError
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Reads regions of the bound working target into a top-first linear premultiplied half-float canvas, at most
 * [stripRows] rows per `glReadPixels` into one reused direct buffer, decoding each strip straight into its
 * flipped canvas rows ([ReadbackDecoder]). The canvas is then the only full-frame allocation of an export.
 *
 * Uses (RGBA, HALF_FLOAT) when the implementation offers it, else the always-legal (RGBA, FLOAT) for float
 * targets; log-domain RGBA8 targets are read as bytes and decoded. The choice is made on the first read (the
 * implementation's preferred read format depends on the bound target) unless [forcedEncoding] is set.
 * GL thread only; one instance per render.
 */
internal class WorkingReadback(
    private val logDomain: Boolean,
    private val stripRows: Int = STRIP_ROWS,
    private val forcedEncoding: ReadbackEncoding? = null,
) {
    init {
        require(stripRows > 0) { "strip height must be positive: $stripRows" }
    }

    private var encoding: ReadbackEncoding? = null
    private var buffer: ByteBuffer? = null

    /**
     * Reads [region] (framebuffer pixels, lower-left origin) of the bound target into [dest], a top-first canvas
     * [destWidth] pixels wide, with the region's top-left pixel at canvas ([destX], [destY]).
     */
    fun read(region: PixelRect, dest: ShortArray, destWidth: Int, destX: Int, destY: Int) {
        if (region.isEmpty) return
        val encoding = encoding ?: (forcedEncoding ?: chooseEncoding()).also { encoding = it }
        val rowsPerStrip = min(stripRows, region.height)
        val bytes = region.width.toLong() * rowsPerStrip * encoding.bytesPerPixel
        require(bytes <= Int.MAX_VALUE) { "readback strip ${region.width}x$rowsPerStrip too large" }
        val strip = buffer?.takeIf { it.capacity() >= bytes }
            ?: ByteBuffer.allocateDirect(bytes.toInt()).order(ByteOrder.nativeOrder()).also { buffer = it }
        val type = when (encoding) {
            ReadbackEncoding.LOG_BYTES -> GLES20.GL_UNSIGNED_BYTE
            ReadbackEncoding.HALF_FLOAT -> GLES30.GL_HALF_FLOAT
            ReadbackEncoding.FLOAT -> GLES20.GL_FLOAT
        }
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
        var done = 0
        while (done < region.height) {
            val rows = min(rowsPerStrip, region.height - done)
            strip.clear()
            GLES20.glReadPixels(region.x, region.y + done, region.width, rows, GLES20.GL_RGBA, type, strip)
            checkGlError("$encoding glReadPixels")
            // Framebuffer row region.y + done is the region's (height - 1 - done)-th row from the top.
            ReadbackDecoder.decodeStrip(strip, encoding, region.width, rows, dest, destWidth, destX, destY + region.height - 1 - done)
            done += rows
        }
    }

    private fun chooseEncoding(): ReadbackEncoding = when {
        logDomain -> ReadbackEncoding.LOG_BYTES
        implementationReadsHalfFloat() -> ReadbackEncoding.HALF_FLOAT
        else -> ReadbackEncoding.FLOAT
    }

    private fun implementationReadsHalfFloat(): Boolean {
        val format = IntArray(1)
        val type = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_IMPLEMENTATION_COLOR_READ_FORMAT, format, 0)
        GLES20.glGetIntegerv(GLES20.GL_IMPLEMENTATION_COLOR_READ_TYPE, type, 0)
        return format[0] == GLES20.GL_RGBA && type[0] == GLES30.GL_HALF_FLOAT
    }

    companion object {
        /** About 256 rows: 4.4 MB of half floats (8.8 MB of floats) per strip at 2160 px wide. */
        const val STRIP_ROWS = 256
    }
}

/**
 * Device-test seam for [GlStoryRenderer.renderOffscreen]: forces smaller tiles, a read encoding or a strip
 * height so the multi-tile and FLOAT readback paths can run on any device. Null fields keep production values.
 */
internal data class OffscreenReadbackOverrides(
    val maxTileSize: Int? = null,
    val encoding: ReadbackEncoding? = null,
    val stripRows: Int? = null,
)
