package com.akane.akastudio.core.export.process

import com.akane.akastudio.core.color.api.HalfFloatImage

/**
 * Row-strip access to [HalfFloatImage]: one row at a time is widened to Float, so no full-frame FloatArray is ever
 * materialized (a 2160x3840 RGBA frame would be 133 MB as floats, 66 MB as halves).
 */
internal object HalfRows {

    /** Decodes row [y] of [img] as interleaved RGBA floats into [out] (size >= width * 4). */
    fun read(img: HalfFloatImage, y: Int, out: FloatArray) {
        val stride = img.width * 4
        require(y in 0 until img.height) { "row $y outside 0 until ${img.height}" }
        require(out.size >= stride) { "row buffer holds ${out.size} floats, needs $stride" }
        val src = img.rgba
        val base = y * stride
        for (i in 0 until stride) out[i] = Half.bitsToFloat(src[base + i].toInt())
    }

    /** Encodes [row] (interleaved RGBA floats, size >= width * 4) into row [y] of [dst]. */
    fun write(dst: ShortArray, width: Int, y: Int, row: FloatArray) {
        val stride = width * 4
        val base = y * stride
        for (i in 0 until stride) dst[base + i] = Half.fromFloat(row[i])
    }

    /** Replaces NaN with 0 and saturates infinities to the largest finite half, so downstream math stays finite. */
    fun finite(v: Float): Float = when {
        v.isNaN() -> 0f
        v > HALF_MAX -> HALF_MAX
        v < -HALF_MAX -> -HALF_MAX
        else -> v
    }

    const val HALF_MAX = 65504f
}
