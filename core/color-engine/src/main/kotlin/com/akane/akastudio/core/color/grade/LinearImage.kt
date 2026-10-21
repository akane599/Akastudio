package com.akane.akastudio.core.color.grade

/**
 * Linear-light RGBA image: `rgba` holds `width·height·4` floats, row-major, interleaved. RGB are linear
 * sRGB primaries and may exceed 1 (HDR) or be slightly negative; alpha passes through grading untouched.
 */
class LinearImage(val width: Int, val height: Int, val rgba: FloatArray) {
    init {
        require(width > 0 && height > 0) { "image dimensions must be positive" }
        require(rgba.size.toLong() == width.toLong() * height * 4) { "rgba must hold width*height*4 floats" }
    }
}
