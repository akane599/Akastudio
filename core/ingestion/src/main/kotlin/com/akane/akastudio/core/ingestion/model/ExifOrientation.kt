package com.akane.akastudio.core.ingestion.model

enum class ExifOrientation(val exifValue: Int, val rotationDegrees: Int, val mirrored: Boolean) {
    NORMAL(1, 0, false),
    FLIP_HORIZONTAL(2, 0, true),
    ROTATE_180(3, 180, false),
    FLIP_VERTICAL(4, 180, true),
    TRANSPOSE(5, 90, true),
    ROTATE_90(6, 90, false),
    TRANSVERSE(7, 270, true),
    ROTATE_270(8, 270, false);

    val swapsDimensions: Boolean get() = rotationDegrees == 90 || rotationDegrees == 270

    companion object {
        /** Unknown/0 -> [NORMAL]. */
        fun fromExif(value: Int): ExifOrientation = entries.firstOrNull { it.exifValue == value } ?: NORMAL
    }
}
