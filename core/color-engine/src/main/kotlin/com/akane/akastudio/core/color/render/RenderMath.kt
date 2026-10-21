package com.akane.akastudio.core.color.render

import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.color.api.OverlayAlign
import com.akane.akastudio.core.color.math.ColorMath
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Integer placement of scaled content inside a box: top-left corner, size, and the uniform scale used. */
internal data class LetterboxFit(val x: Int, val y: Int, val width: Int, val height: Int, val scale: Float)

/** Integer pixel rectangle (x, y = lower corner in the coordinate system of the caller). */
internal data class PixelRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun intersect(other: PixelRect): PixelRect {
        val left = max(x, other.x)
        val bottom = max(y, other.y)
        val right = min(x + width, other.x + other.width)
        val top = min(y + height, other.y + other.height)
        return PixelRect(left, bottom, max(0, right - left), max(0, top - bottom))
    }
}

/** Canvas-pixel to target-pixel mapping: target = canvas * scale + offset, per axis. */
internal data class ViewTransform(val scaleX: Float, val scaleY: Float, val offsetX: Float, val offsetY: Float) {
    companion object { val IDENTITY = ViewTransform(1f, 1f, 0f, 0f) }
}

/** Column-major 3x3 matrix (the layout `glUniformMatrix3fv` expects with transpose = false). */
internal class Mat3(val values: FloatArray) {
    init { require(values.size == 9) }

    /** Applies the matrix to the point (x, y, 1) and returns (x', y'). */
    fun apply(x: Float, y: Float): Pair<Float, Float> =
        (values[0] * x + values[3] * y + values[6]) to (values[1] * x + values[4] * y + values[7])

    companion object {
        val IDENTITY = Mat3(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f))

        /** (u, v) -> (u, 1 - v): samples a framebuffer texture, whose t = 0 row is the bottom of the canvas. */
        val FLIP_V = Mat3(floatArrayOf(1f, 0f, 0f, 0f, -1f, 0f, 0f, 1f, 1f))
    }
}

/** One rectangle of a tile plan, in top-left-origin canvas pixels. */
internal data class Tile(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * Separable Gaussian schedule: [passCount] repetitions of a horizontal plus vertical pass with standard
 * deviation [passSigma]; variances add, so the composite sigma is passSigma * sqrt(passCount).
 * [weights] holds the normalized taps from the centre (index 0) outwards, [radius] + 1 entries.
 */
internal class BlurPlan(val passCount: Int, val passSigma: Float, val radius: Int, val weights: FloatArray)

/** Bitmap and canvas placement of a rasterized text overlay. All values in canvas pixels. */
internal data class OverlayPlacement(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    /** x of the text origin (left edge of the run) inside the bitmap. */
    val originX: Float,
    /** y of the baseline inside the bitmap. */
    val baselineY: Float,
)

/**
 * Pure render math shared by the GL runtime and mirrored in the GLSL sources under `assets/shaders`.
 *
 * Conventions: canvas pixels are top-left origin, y down. Registered image textures are top-first
 * (texture t = 0 is the top row). Framebuffer textures are bottom-first, which [Mat3.FLIP_V] corrects.
 */
internal object RenderMath {

    /** Largest blur tap offset one shader pass supports; matches `MAX_RADIUS` in `blur.frag.glsl`. */
    const val MAX_BLUR_RADIUS = 32

    /** Log-domain fallback constants; mirrored as `LOG_K`/`LOG_MAX` in the GLSL sources. */
    const val LOG_K = 64f
    const val LOG_MAX = 16f
    private val logNorm = log2(1.0 + LOG_K.toDouble() * LOG_MAX)

    /** Aspect-fits [contentWidth]x[contentHeight] inside [boxWidth]x[boxHeight], centred (rounded to whole pixels). */
    fun letterbox(contentWidth: Int, contentHeight: Int, boxWidth: Int, boxHeight: Int): LetterboxFit {
        require(contentWidth > 0 && contentHeight > 0 && boxWidth > 0 && boxHeight > 0)
        val scale = min(boxWidth.toDouble() / contentWidth, boxHeight.toDouble() / contentHeight)
        val width = (contentWidth * scale).roundToInt().coerceIn(1, boxWidth)
        val height = (contentHeight * scale).roundToInt().coerceIn(1, boxHeight)
        return LetterboxFit((boxWidth - width) / 2, (boxHeight - height) / 2, width, height, scale.toFloat())
    }

    /**
     * Unit-quad (u, v) in [0, 1]^2 across the layer destination -> normalized source texture coordinates.
     *
     * The destination shows [crop] of a [sourceWidth]x[sourceHeight] image, with the source rotated by
     * [rotationDegrees] about the crop centre (positive = clockwise as displayed, y down). The rotation runs
     * in source-pixel space so non-square images are not sheared, then the result is normalized.
     */
    fun layerUvMatrix(crop: NormRect, rotationDegrees: Float, sourceWidth: Int, sourceHeight: Int): Mat3 {
        val w = sourceWidth.toDouble()
        val h = sourceHeight.toDouble()
        val cx = (crop.left + crop.right) / 2.0 * w
        val cy = (crop.top + crop.bottom) / 2.0 * h
        val cw = (crop.right - crop.left) * w
        val ch = (crop.bottom - crop.top) * h
        val theta = Math.toRadians(rotationDegrees.toDouble())
        val c = cos(theta)
        val s = sin(theta)
        // sample = centre + R(-theta) * ((u - 0.5) * cw, (v - 0.5) * ch), then divided by the source size.
        val su = c * cw / w
        val sv = s * ch / w
        val s0 = (cx - c * cw / 2 - s * ch / 2) / w
        val tu = -s * cw / h
        val tv = c * ch / h
        val t0 = (cy + s * cw / 2 - c * ch / 2) / h
        return Mat3(floatArrayOf(su.toFloat(), tu.toFloat(), 0f, sv.toFloat(), tv.toFloat(), 0f, s0.toFloat(), t0.toFloat(), 1f))
    }

    /** Centred normalized crop of a source that cover-fills a destination of the given aspect. */
    fun coverCrop(sourceWidth: Int, sourceHeight: Int, destWidth: Int, destHeight: Int): NormRect {
        require(sourceWidth > 0 && sourceHeight > 0 && destWidth > 0 && destHeight > 0)
        val sourceAspect = sourceWidth.toDouble() / sourceHeight
        val destAspect = destWidth.toDouble() / destHeight
        return if (sourceAspect > destAspect) {
            val fraction = destAspect / sourceAspect
            val inset = ((1 - fraction) / 2).toFloat()
            NormRect(inset, 0f, 1f - inset, 1f)
        } else {
            val fraction = sourceAspect / destAspect
            val inset = ((1 - fraction) / 2).toFloat()
            NormRect(0f, inset, 1f, 1f - inset)
        }
    }

    /**
     * Signed distance from ([px], [py]) (relative to the rectangle centre) to a rounded rectangle with half
     * extents [halfWidth]x[halfHeight] and corner [radius], clamped to the half extents. Negative inside.
     */
    fun roundedRectSdf(px: Float, py: Float, halfWidth: Float, halfHeight: Float, radius: Float): Float {
        val r = radius.coerceIn(0f, max(0f, min(halfWidth, halfHeight)))
        val qx = abs(px) - halfWidth + r
        val qy = abs(py) - halfHeight + r
        val outside = hypot(max(qx, 0f), max(qy, 0f))
        val inside = min(max(qx, qy), 0f)
        return outside + inside - r
    }

    /** 1-px analytic anti-aliasing: coverage of a pixel whose centre is [distance] canvas px from the edge. */
    fun sdfCoverage(distance: Float, pixelsPerCanvasPixel: Float): Float =
        (0.5f - distance * pixelsPerCanvasPixel).coerceIn(0f, 1f)

    fun srgbDecode(encoded: Float): Float = ColorMath.srgbToLinear(encoded)

    fun srgbEncode(linear: Float): Float = ColorMath.linearToSrgb(linear)

    /** sRGB-encoded packed ARGB -> linear premultiplied RGBA. */
    fun argbToLinearPremultiplied(argb: Int): FloatArray {
        val a = ((argb ushr 24) and 0xFF) / 255f
        return floatArrayOf(
            srgbDecode(((argb ushr 16) and 0xFF) / 255f) * a,
            srgbDecode(((argb ushr 8) and 0xFF) / 255f) * a,
            srgbDecode((argb and 0xFF) / 255f) * a,
            a,
        )
    }

    /** sRGB-encoded packed ARGB -> its encoded RGBA channels in [0, 1] (for clears of display surfaces). */
    fun argbToEncodedRgba(argb: Int): FloatArray = floatArrayOf(
        ((argb ushr 16) and 0xFF) / 255f,
        ((argb ushr 8) and 0xFF) / 255f,
        (argb and 0xFF) / 255f,
        ((argb ushr 24) and 0xFF) / 255f,
    )

    /** Log encoding of linear light for RGBA8 fallback targets: [0, LOG_MAX] -> [0, 1], negatives clamp to 0. */
    fun logEncode(linear: Float): Float {
        val x = linear.coerceIn(0f, LOG_MAX).toDouble()
        return (log2(1.0 + LOG_K * x) / logNorm).toFloat()
    }

    /** Inverse of [logEncode]. */
    fun logDecode(encoded: Float): Float =
        ((Math.pow(2.0, encoded.toDouble() * logNorm) - 1.0) / LOG_K).toFloat()

    /**
     * Premultiplied linear RGBA -> what is stored in a working target. Half-float targets store it as is;
     * log targets store the log of the unpremultiplied colour, re-premultiplied (mirrors `encodeTarget`).
     */
    fun encodeWorking(premultiplied: FloatArray, logDomain: Boolean): FloatArray {
        if (!logDomain) return premultiplied.copyOf()
        val a = premultiplied[3]
        if (a <= 0f) return FloatArray(4)
        return floatArrayOf(
            logEncode(premultiplied[0] / a) * a,
            logEncode(premultiplied[1] / a) * a,
            logEncode(premultiplied[2] / a) * a,
            a,
        )
    }

    /** IEEE 754 binary16 bits -> Float. Exact for every pattern, including subnormals, infinities and NaN payloads. */
    fun halfToFloat(half: Short): Float {
        val h = half.toInt() and 0xFFFF
        val sign = (h ushr 15) and 1
        val exponent = (h ushr 10) and 0x1F
        val mantissa = h and 0x3FF
        return when (exponent) {
            0 -> {
                val magnitude = mantissa * HALF_MIN_SUBNORMAL
                if (sign == 1) -magnitude else magnitude
            }
            0x1F -> Float.fromBits((sign shl 31) or 0x7F800000 or (mantissa shl 13))
            else -> Float.fromBits((sign shl 31) or ((exponent - 15 + 127) shl 23) or (mantissa shl 13))
        }
    }

    /** Float -> IEEE 754 binary16 bits, rounding to nearest, ties to even; overflow gives infinity, NaN stays NaN. */
    fun floatToHalf(value: Float): Short {
        val bits = value.toRawBits()
        val sign = (bits ushr 16) and 0x8000
        val exponent = (bits ushr 23) and 0xFF
        val mantissa = bits and 0x7FFFFF
        if (exponent == 0xFF) {
            if (mantissa == 0) return (sign or 0x7C00).toShort()
            val payload = (mantissa ushr 13).let { if (it == 0) 0x200 else it }
            return (sign or 0x7C00 or payload).toShort()
        }
        val halfExponent = exponent - 127 + 15
        if (halfExponent <= 0) {
            // Subnormal half (or zero): count of 2^-24 units = significand >> (14 - halfExponent).
            val shift = 14 - halfExponent
            if (shift >= 25 || exponent == 0) return sign.toShort()
            val rounded = shiftRightRoundEven(mantissa or 0x800000, shift)
            return (sign or rounded).toShort()
        }
        if (halfExponent >= 0x1F) return (sign or 0x7C00).toShort()
        var h = (halfExponent shl 10) or (mantissa ushr 13)
        val remainder = mantissa and 0x1FFF
        if (remainder > 0x1000 || (remainder == 0x1000 && (h and 1) == 1)) h++
        if (h >= 0x7C00) h = 0x7C00
        return (sign or h).toShort()
    }

    private fun shiftRightRoundEven(value: Int, shift: Int): Int {
        val quotient = value ushr shift
        val remainder = value and ((1 shl shift) - 1)
        val half = 1 shl (shift - 1)
        return if (remainder > half || (remainder == half && (quotient and 1) == 1)) quotient + 1 else quotient
    }

    /** Reverses the order of [rows] rows of [rowLength] elements in place (bottom-first GL readback -> top-first). */
    fun flipRows(data: ShortArray, rowLength: Int, rows: Int) {
        require(data.size >= rowLength * rows)
        val scratch = ShortArray(rowLength)
        for (top in 0 until rows / 2) {
            val bottom = rows - 1 - top
            System.arraycopy(data, top * rowLength, scratch, 0, rowLength)
            System.arraycopy(data, bottom * rowLength, data, top * rowLength, rowLength)
            System.arraycopy(scratch, 0, data, bottom * rowLength, rowLength)
        }
    }

    /** Bottom-first RGBA8 bytes (glReadPixels order) -> top-first packed ARGB ints. */
    fun rgbaBottomUpToArgb(rgba: ByteArray, width: Int, height: Int): IntArray {
        require(rgba.size >= width * height * 4)
        val out = IntArray(width * height)
        for (y in 0 until height) {
            val srcRow = (height - 1 - y) * width * 4
            val dstRow = y * width
            for (x in 0 until width) {
                val i = srcRow + x * 4
                val r = rgba[i].toInt() and 0xFF
                val g = rgba[i + 1].toInt() and 0xFF
                val b = rgba[i + 2].toInt() and 0xFF
                val a = rgba[i + 3].toInt() and 0xFF
                out[dstRow + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    /** Float RGBA -> half bits. */
    fun floatsToHalf(values: FloatArray): ShortArray = ShortArray(values.size) { floatToHalf(values[it]) }

    /** Log-encoded premultiplied RGBA8 bytes (fallback targets) -> linear premultiplied half bits. */
    fun logBytesToHalf(rgba: ByteArray): ShortArray {
        val out = ShortArray(rgba.size)
        var i = 0
        while (i + 3 < rgba.size) {
            val a = (rgba[i + 3].toInt() and 0xFF) / 255f
            for (c in 0 until 3) {
                val stored = (rgba[i + c].toInt() and 0xFF) / 255f
                val linear = if (a > 0f) logDecode(stored / a) * a else 0f
                out[i + c] = floatToHalf(linear)
            }
            out[i + 3] = floatToHalf(a)
            i += 4
        }
        return out
    }

    /** Copies a top-first [tileWidth]x[tileHeight] block of [channels]-wide pixels into [dest] at ([x], [y]). */
    fun blitTile(tile: ShortArray, tileWidth: Int, tileHeight: Int, dest: ShortArray, destWidth: Int, x: Int, y: Int, channels: Int) {
        val rowLength = tileWidth * channels
        for (row in 0 until tileHeight) {
            System.arraycopy(tile, row * rowLength, dest, ((y + row) * destWidth + x) * channels, rowLength)
        }
    }

    /** Row-major grid of tiles no larger than [maxTileWidth]x[maxTileHeight] that covers the canvas exactly once. */
    fun planTiles(width: Int, height: Int, maxTileWidth: Int, maxTileHeight: Int): List<Tile> {
        require(width > 0 && height > 0 && maxTileWidth > 0 && maxTileHeight > 0)
        val columns = (width + maxTileWidth - 1) / maxTileWidth
        val rows = (height + maxTileHeight - 1) / maxTileHeight
        val tileWidth = (width + columns - 1) / columns
        val tileHeight = (height + rows - 1) / rows
        val tiles = ArrayList<Tile>(columns * rows)
        var y = 0
        while (y < height) {
            val h = min(tileHeight, height - y)
            var x = 0
            while (x < width) {
                val w = min(tileWidth, width - x)
                tiles += Tile(x, y, w, h)
                x += w
            }
            y += h
        }
        return tiles
    }

    /** Normalized Gaussian taps for offsets 0..[radius] (each off-centre tap is used twice, mirrored). */
    fun gaussianWeights(sigma: Float, radius: Int): FloatArray {
        require(sigma > 0f && radius >= 0)
        val raw = DoubleArray(radius + 1) { exp(-(it.toDouble() * it) / (2.0 * sigma * sigma)) }
        val total = raw[0] + 2.0 * raw.drop(1).sum()
        return FloatArray(radius + 1) { (raw[it] / total).toFloat() }
    }

    /**
     * Splits a Gaussian of [sigma] texels into equal passes whose 3-sigma support fits [maxRadius] taps.
     * Returns a zero-pass plan when [sigma] is not positive.
     */
    fun blurPlan(sigma: Float, maxRadius: Int = MAX_BLUR_RADIUS): BlurPlan {
        require(maxRadius >= 1)
        if (!(sigma > 0f)) return BlurPlan(0, 0f, 0, floatArrayOf(1f))
        val maxPassSigma = maxRadius / 3.0
        val passes = max(1, ceil((sigma / maxPassSigma) * (sigma / maxPassSigma)).toInt())
        val passSigma = (sigma / sqrt(passes.toDouble())).toFloat()
        val radius = ceil(3.0 * passSigma).toInt().coerceIn(1, maxRadius)
        return BlurPlan(passes, passSigma, radius, gaussianWeights(passSigma, radius))
    }

    /** Number of mip levels for a full chain down to 1x1. */
    fun mipLevelCount(width: Int, height: Int): Int {
        require(width > 0 && height > 0)
        return 32 - Integer.numberOfLeadingZeros(max(width, height))
    }

    /** Size of a downsampled grid: about 1/[factor] of each canvas side, at least 1. */
    fun downsampledSize(size: Int, factor: Int): Int = max(1, (size + factor - 1) / factor)

    /**
     * Places a text run of [textWidth] around [anchorX] per [align], with its baseline at [baselineY];
     * [fontTop] (negative) and [fontBottom] are the font's extreme extents relative to the baseline.
     * The bitmap is snapped to whole canvas pixels and padded by [pad] on every side.
     */
    fun overlayPlacement(
        anchorX: Float,
        baselineY: Float,
        textWidth: Float,
        fontTop: Float,
        fontBottom: Float,
        align: OverlayAlign,
        pad: Int,
    ): OverlayPlacement {
        val runLeft = when (align) {
            OverlayAlign.START -> anchorX
            OverlayAlign.CENTER -> anchorX - textWidth / 2f
            OverlayAlign.END -> anchorX - textWidth
        }
        val left = floor(runLeft - pad).toInt()
        val top = floor(baselineY + fontTop - pad).toInt()
        val right = ceil(runLeft + textWidth + pad).toInt()
        val bottom = ceil(baselineY + fontBottom + pad).toInt()
        return OverlayPlacement(
            left = left,
            top = top,
            width = max(1, right - left),
            height = max(1, bottom - top),
            originX = runLeft - left,
            baselineY = baselineY - top,
        )
    }

    private fun log2(x: Double): Double = ln(x) / LN2

    private const val LN2 = 0.6931471805599453
    private const val HALF_MIN_SUBNORMAL = 5.9604645E-8f // 2^-24, exact in Float
}
