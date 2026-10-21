package com.akane.akastudio.core.export.encode

/**
 * Row source keeps memory bounded: rows are pulled top to bottom.
 *
 * Each call fills [out] (exactly `width` entries) with the pixels of row [y] as ARGB-packed ints
 * (`0xAARRGGBB`, sRGB-encoded, not premultiplied).
 */
fun interface Rgba8Rows {
    /** Writes row [y] into [out]. */
    fun row(y: Int, out: IntArray)
}

/**
 * Row source keeps memory bounded: rows are pulled top to bottom.
 *
 * Each call fills [out] (exactly `4 * width` entries) with R, G, B, A samples of row [y]: sRGB-encoded
 * unsigned 16-bit values stored in [Short] (read back with `toInt() and 0xFFFF`), not premultiplied.
 */
fun interface Rgba16Rows {
    /** Writes row [y] into [out]. */
    fun row(y: Int, out: ShortArray)
}
