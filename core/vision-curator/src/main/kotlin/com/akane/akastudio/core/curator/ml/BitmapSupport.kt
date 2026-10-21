package com.akane.akastudio.core.curator.ml

import android.graphics.Bitmap
import com.akane.akastudio.core.color.api.ImagePixels

/** Thin Bitmap glue for the adapters. None of these recycle the caller's bitmap. */
internal object BitmapSupport {

    /** [bitmap] itself when it is already ARGB_8888, otherwise a software ARGB_8888 copy the caller owns. */
    fun argb8888(bitmap: Bitmap): Bitmap {
        if (bitmap.config == Bitmap.Config.ARGB_8888) return bitmap
        return requireNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false)) {
            "cannot convert ${bitmap.config} bitmap to ARGB_8888"
        }
    }

    /** Reads [bitmap] into packed sRGB ARGB pixels. */
    fun pixelsOf(bitmap: Bitmap): ImagePixels {
        val source = argb8888(bitmap)
        try {
            val argb = IntArray(source.width * source.height)
            source.getPixels(argb, 0, source.width, 0, 0, source.width, source.height)
            return ImagePixels(source.width, source.height, argb)
        } finally {
            if (source !== bitmap) source.recycle()
        }
    }

    /**
     * Centre square crop of [bitmap] scaled to [edge]×[edge] with bilinear filtering, returned as packed
     * ARGB pixels (row-major).
     */
    fun centerCropPixels(bitmap: Bitmap, edge: Int): IntArray {
        val source = argb8888(bitmap)
        try {
            val side = minOf(source.width, source.height)
            val cropped = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
            try {
                val scaled = Bitmap.createScaledBitmap(cropped, edge, edge, true)
                try {
                    val out = IntArray(edge * edge)
                    scaled.getPixels(out, 0, edge, 0, 0, edge, edge)
                    return out
                } finally {
                    // Both factories may hand back their input unchanged; recycle only what was allocated here.
                    if (scaled !== cropped) scaled.recycle()
                }
            } finally {
                if (cropped !== source) cropped.recycle()
            }
        } finally {
            if (source !== bitmap) source.recycle()
        }
    }
}
