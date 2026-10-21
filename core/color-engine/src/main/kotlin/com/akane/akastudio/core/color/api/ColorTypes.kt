package com.akane.akastudio.core.color.api

/** Pixel-space rectangle (canvas pixels, y down). */
data class Rect4f(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** Rectangle normalized to the upright source image, each edge in [0, 1]. */
data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    companion object { val FULL = NormRect(0f, 0f, 1f, 1f) }
}

/** sRGB-encoded 8-bit packed ARGB pixels (Bitmap.getPixels layout). Pure-JVM carrier for analysis. */
class ImagePixels(val width: Int, val height: Int, val argb: IntArray) {
    init { require(width > 0 && height > 0 && argb.size == width * height) }
}

data class OkLab(val l: Float, val a: Float, val b: Float)

data class LabStats(
    val meanL: Float, val meanA: Float, val meanB: Float,
    val stdL: Float, val stdA: Float, val stdB: Float,
    val weight: Float,               // total sample weight used
)

data class PaletteSwatch(val lab: OkLab, val weight: Float)   // weights of a palette sum to 1

/** Per-image affine OKLab transfer toward the chromatic anchor: x' = scale * x + offset, per channel. */
data class HarmonizationParams(
    val scaleL: Float, val offsetL: Float,
    val scaleA: Float, val offsetA: Float,
    val scaleB: Float, val offsetB: Float,
) { companion object { val IDENTITY = HarmonizationParams(1f, 0f, 1f, 0f, 1f, 0f) } }

enum class MasterLook {
    PORTRA_400_GOLDEN, MEDITERRANEAN_AZURE, ESPRESSO_WARM_WOOD, CINESTILL_800T_NIGHT,
    NORDIC_SLATE_MATTE, KODACHROME_64_CLASSIC, VELVET_MONOCHROME_HP5, PURE_NATURAL_PRO,
}

enum class SceneType { BEACH_SEA, NIGHT_CITY, CAFE_INTERIOR, NATURE_FOREST, PORTRAIT, ARCHITECTURE_AUTO, GENERAL }

/** User + auto grade for one image. Ranges are UI-slider ranges; 0 = neutral unless noted. */
data class GradeParams(
    val look: MasterLook = MasterLook.PURE_NATURAL_PRO,
    val lookIntensity: Float = 1f,           // 0..1
    val harmonization: HarmonizationParams = HarmonizationParams.IDENTITY,
    val exposureEv: Float = 0f,              // -2..2
    val temperature: Float = 0f,             // -1..1 (cool..warm, ±60 mired from D65: −1826 K..+4162 K; WhiteBalance)
    val tint: Float = 0f,                    // -1..1 (green..magenta)
    val contrast: Float = 0f,                // -1..1
    val saturation: Float = 0f,              // -1..1 (OKLCh chroma scale 0..2)
    val halation: Float = 0f,                // 0..1, added to the look's default
    val grain: Float = 0f,                   // 0..1, added to the look's default
    val skinProtection: Float = 0.6f,        // 0..1, fraction of look/split-tone removed on skin pixels
) { companion object { val NEUTRAL = GradeParams() } }
