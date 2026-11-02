package com.akane.akastudio.core.curator.metrics

import com.akane.akastudio.core.color.api.ImagePixels
import com.akane.akastudio.core.ingestion.model.CaptureMetadata

/**
 * Conservative screen-capture detector: a frame is a screen capture when it carries no camera EXIF **and** a large
 * share of its 8x8 blocks are exactly uniform, the flat UI bars and panels a screen renders.
 *
 * - **No camera EXIF**: focal length, f-number, exposure time and ISO all absent. A camera always writes some of them;
 *   screenshots write none.
 * - **Zero-variance blocks**: the fraction of whole, non-overlapping 8x8 blocks of the analysis raster whose 64
 *   pixels have the identical RGB value (alpha ignored). Zero variance, not "low variance": a photo forwarded through
 *   a messenger also has no EXIF, and its recompressed shadows are flat but still vary by a code value or two.
 * - **Aspect hint**: a device-screen aspect (long/short edge at least [SCREEN_ASPECT_MIN], 18:9 and taller) only
 *   lowers the block threshold from [ZERO_VARIANCE_THRESHOLD] to [SCREEN_ASPECT_THRESHOLD]; it never flags on its own.
 *
 * Calibration (2026-11-02, the user's 28-photo set, 512 px long edge): the Samsung camera-UI screenshot measures
 * 0.24-0.28 depending on the resampling filter (about 0.27 for the decoder's subsample-then-bilinear path); the 27
 * camera photos measure at most 0.004, and simulated messenger forwards of them (1600 px, JPEG q60, no EXIF) at
 * most 0.017.
 *
 * Known limit: a forwarded photo with no EXIF whose shadows are crushed to exactly black over more than the threshold
 * share of the frame is flagged. The flag only keeps a frame out of automatic picks; it never rejects it.
 */
object ScreenCaptureDetector {
    /** Side of the square blocks tested for zero variance, in analysis pixels. */
    const val BLOCK = 8

    /** Zero-variance block fraction from which a frame without camera EXIF is a screen capture. */
    const val ZERO_VARIANCE_THRESHOLD = 0.28f

    /** The lower threshold used when the frame also has a device-screen aspect ([isScreenAspect]). */
    const val SCREEN_ASPECT_THRESHOLD = 0.20f

    /** Long/short edge ratio from which the frame has a phone-screen aspect (18:9 = 2.0, 19.5:9 ≈ 2.17, 20:9 ≈ 2.22). */
    const val SCREEN_ASPECT_MIN = 1.9f

    /**
     * One evaluation.
     * @property cameraExif true when the capture metadata holds any camera exposure field ([hasCameraExif]).
     * @property zeroVarianceFraction fraction of whole 8x8 blocks with a single RGB value, in 0..1; 0 when the frame
     *   is smaller than one block.
     * @property threshold the block threshold that applied to this frame's aspect.
     */
    data class Result(val cameraExif: Boolean, val zeroVarianceFraction: Float, val threshold: Float) {
        /** True when there is no camera EXIF and [zeroVarianceFraction] is at least [threshold]. */
        val screenCapture: Boolean get() = !cameraExif && zeroVarianceFraction >= threshold
    }

    /** True for a frame without camera EXIF whose zero-variance block fraction reaches its aspect's threshold. */
    fun detect(pixels: ImagePixels, metadata: CaptureMetadata): Boolean = evaluate(pixels, metadata).screenCapture

    /** The full [Result] for [pixels] under [metadata]; the block scan is skipped (fraction 0) when EXIF is present. */
    fun evaluate(pixels: ImagePixels, metadata: CaptureMetadata): Result {
        val cameraExif = hasCameraExif(metadata)
        val fraction = if (cameraExif) 0f else zeroVarianceBlockFraction(pixels.argb, pixels.width, pixels.height)
        return Result(cameraExif, fraction, threshold(pixels.width, pixels.height))
    }

    /** True when any of focal length, f-number, exposure time or ISO is present. */
    fun hasCameraExif(metadata: CaptureMetadata): Boolean =
        metadata.focalLength35mm != null ||
            metadata.fNumber != null ||
            metadata.exposureTimeSeconds != null ||
            metadata.iso != null

    /** True when the long edge is at least [SCREEN_ASPECT_MIN] times the short edge, in either orientation. */
    fun isScreenAspect(width: Int, height: Int): Boolean {
        require(width > 0 && height > 0) { "size must be positive: ${width}x$height" }
        return maxOf(width, height).toFloat() >= SCREEN_ASPECT_MIN * minOf(width, height)
    }

    /** [SCREEN_ASPECT_THRESHOLD] for a screen aspect, else [ZERO_VARIANCE_THRESHOLD]. */
    fun threshold(width: Int, height: Int): Float =
        if (isScreenAspect(width, height)) SCREEN_ASPECT_THRESHOLD else ZERO_VARIANCE_THRESHOLD

    /**
     * Fraction of the whole, non-overlapping [BLOCK]x[BLOCK] blocks of the row-major `width x height` ARGB raster,
     * anchored at the top-left, whose pixels all share one RGB value. Partial blocks at the right and bottom edges are
     * ignored; a raster smaller than one block gives 0.
     */
    fun zeroVarianceBlockFraction(argb: IntArray, width: Int, height: Int): Float {
        require(width > 0 && height > 0 && argb.size == width * height) { "bad raster: ${width}x$height, ${argb.size}" }
        val cols = width / BLOCK
        val rows = height / BLOCK
        if (cols == 0 || rows == 0) return 0f
        var uniform = 0
        for (by in 0 until rows) {
            for (bx in 0 until cols) {
                if (isUniform(argb, width, bx * BLOCK, by * BLOCK)) uniform++
            }
        }
        // Float division is correctly rounded, so k / n lands exactly on a threshold literal it equals.
        return uniform.toFloat() / (cols * rows).toFloat()
    }

    private fun isUniform(argb: IntArray, width: Int, x0: Int, y0: Int): Boolean {
        val first = argb[y0 * width + x0] and RGB_MASK
        for (y in y0 until y0 + BLOCK) {
            val row = y * width
            for (x in x0 until x0 + BLOCK) {
                if (argb[row + x] and RGB_MASK != first) return false
            }
        }
        return true
    }

    private const val RGB_MASK = 0x00FFFFFF
}
