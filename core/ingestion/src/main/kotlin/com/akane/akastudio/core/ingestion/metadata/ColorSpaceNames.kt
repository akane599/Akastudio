package com.akane.akastudio.core.ingestion.metadata

import com.akane.akastudio.core.ingestion.model.ColorSpaceTag

/** Maps `android.graphics.ColorSpace.name` values to [ColorSpaceTag] without touching the Android class. */
internal object ColorSpaceNames {
    /** `ColorSpace.get(ColorSpace.Named.SRGB).name`. */
    const val SRGB = "sRGB IEC61966-2.1"

    /** `ColorSpace.get(ColorSpace.Named.LINEAR_SRGB).name`. */
    const val LINEAR_SRGB = "sRGB IEC61966-2.1 (Linear)"

    /** `ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB).name`. */
    const val EXTENDED_SRGB = "scRGB-nl IEC 61966-2-2:2003"

    /** `ColorSpace.get(ColorSpace.Named.DISPLAY_P3).name`. */
    const val DISPLAY_P3 = "Display P3"

    /** `ColorSpace.get(ColorSpace.Named.ADOBE_RGB).name`. */
    const val ADOBE_RGB = "Adobe RGB (1998)"

    fun toTag(name: String?): ColorSpaceTag = when (name) {
        null -> ColorSpaceTag.UNKNOWN
        SRGB, LINEAR_SRGB, EXTENDED_SRGB -> ColorSpaceTag.SRGB
        DISPLAY_P3 -> ColorSpaceTag.DISPLAY_P3
        ADOBE_RGB -> ColorSpaceTag.ADOBE_RGB
        else -> ColorSpaceTag.OTHER
    }
}
