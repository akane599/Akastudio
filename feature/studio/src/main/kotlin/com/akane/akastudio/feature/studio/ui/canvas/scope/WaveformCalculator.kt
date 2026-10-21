package com.akane.akastudio.feature.studio.ui.canvas.scope

import com.akane.akastudio.core.color.api.ImagePixels

/**
 * A scope density map: for each of [columns] horizontal buckets, a histogram over [levels] signal levels.
 * [counts] is row-major by level: `counts[level * columns + column]`, level 0 being black.
 */
internal class DensityMap(val columns: Int, val levels: Int, val counts: IntArray) {
    init {
        require(columns > 0 && levels > 0) { "columns and levels must be positive" }
        require(counts.size == columns * levels) { "counts must hold columns * levels entries" }
    }

    /** The number of samples at [level] in [column]. */
    operator fun get(column: Int, level: Int): Int = counts[level * columns + column]

    /** The number of samples in [column] across every level. */
    fun columnTotal(column: Int): Int = (0 until levels).sumOf { this[column, it] }
}

/** One [DensityMap] per channel, drawn side by side as an RGB parade. */
internal class ParadeMaps(val red: DensityMap, val green: DensityMap, val blue: DensityMap)

/** 256-bin histograms of the encoded red, green and blue values and of Rec.709 luma. */
internal class ChannelHistogram(val red: IntArray, val green: IntArray, val blue: IntArray, val luma: IntArray) {
    init {
        require(listOf(red, green, blue, luma).all { it.size == BINS }) { "each histogram has $BINS bins" }
    }

    companion object {
        /** Bins per channel. */
        const val BINS = 256
    }
}

/**
 * Pure video-scope calculators over sRGB-encoded 8-bit [ImagePixels]. Like a broadcast scope, luma is computed on the
 * encoded values with Rec.709 weights (`Y' = 0.2126 R' + 0.7152 G' + 0.0722 B'`). A value `v` in `[0, 1]` falls in
 * level `floor(v * levels)`, with 1.0 in the top level; pixel column `x` of a `w`-wide image falls in bucket
 * `floor(x * columns / w)`.
 */
internal object WaveformCalculator {
    /** Rec.709 red luma weight. */
    const val KR = 0.2126f

    /** Rec.709 green luma weight. */
    const val KG = 0.7152f

    /** Rec.709 blue luma weight. */
    const val KB = 0.0722f

    private const val MAX_CODE = 255f

    /** Luma waveform: per column bucket, the distribution of Rec.709 luma over [levels] levels. */
    fun luma(pixels: ImagePixels, columns: Int = DEFAULT_COLUMNS, levels: Int = DEFAULT_LEVELS): DensityMap {
        val counts = newCounts(columns, levels)
        forEachPixel(pixels, columns) { column, argb ->
            val level = levelOf(lumaOf(argb), levels)
            counts[level * columns + column]++
        }
        return DensityMap(columns, levels, counts)
    }

    /** RGB parade: one waveform per encoded channel, each [columns] buckets wide. */
    fun rgbParade(pixels: ImagePixels, columns: Int = DEFAULT_COLUMNS, levels: Int = DEFAULT_LEVELS): ParadeMaps {
        val red = newCounts(columns, levels)
        val green = newCounts(columns, levels)
        val blue = newCounts(columns, levels)
        val lut = IntArray(ChannelHistogram.BINS) { levelOf(it / MAX_CODE, levels) }
        forEachPixel(pixels, columns) { column, argb ->
            red[lut[(argb shr RED_SHIFT) and BYTE] * columns + column]++
            green[lut[(argb shr GREEN_SHIFT) and BYTE] * columns + column]++
            blue[lut[argb and BYTE] * columns + column]++
        }
        return ParadeMaps(
            DensityMap(columns, levels, red),
            DensityMap(columns, levels, green),
            DensityMap(columns, levels, blue),
        )
    }

    /** Per-channel histograms of the encoded values plus a luma histogram (luma rounded to the nearest code). */
    fun histogram(pixels: ImagePixels): ChannelHistogram {
        val red = IntArray(ChannelHistogram.BINS)
        val green = IntArray(ChannelHistogram.BINS)
        val blue = IntArray(ChannelHistogram.BINS)
        val luma = IntArray(ChannelHistogram.BINS)
        for (argb in pixels.argb) {
            red[(argb shr RED_SHIFT) and BYTE]++
            green[(argb shr GREEN_SHIFT) and BYTE]++
            blue[argb and BYTE]++
            luma[(lumaOf(argb) * MAX_CODE + HALF).toInt().coerceIn(0, BYTE)]++
        }
        return ChannelHistogram(red, green, blue, luma)
    }

    /** Rec.709 luma of an encoded ARGB pixel, in `[0, 1]`. */
    fun lumaOf(argb: Int): Float {
        val r = (argb shr RED_SHIFT) and BYTE
        val g = (argb shr GREEN_SHIFT) and BYTE
        val b = argb and BYTE
        return (KR * r + KG * g + KB * b) / MAX_CODE
    }

    /** The level of [value] in `[0, 1]` among [levels] equal-width levels; out-of-range values clamp. */
    fun levelOf(value: Float, levels: Int): Int =
        if (value.isFinite()) (value * levels).toInt().coerceIn(0, levels - 1) else 0

    private inline fun forEachPixel(pixels: ImagePixels, columns: Int, action: (column: Int, argb: Int) -> Unit) {
        val width = pixels.width
        val bucketOf = IntArray(width) { x -> (x.toLong() * columns / width).toInt() }
        val argb = pixels.argb
        for (y in 0 until pixels.height) {
            val row = y * width
            for (x in 0 until width) action(bucketOf[x], argb[row + x])
        }
    }

    private fun newCounts(columns: Int, levels: Int): IntArray {
        require(columns > 0 && levels > 0) { "columns and levels must be positive (got $columns x $levels)" }
        return IntArray(columns * levels)
    }

    /** Default column buckets per waveform. */
    const val DEFAULT_COLUMNS = 128

    /** Default levels per waveform (one per IRE percent). */
    const val DEFAULT_LEVELS = 100

    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val BYTE = 0xFF
    private const val HALF = 0.5f
}
