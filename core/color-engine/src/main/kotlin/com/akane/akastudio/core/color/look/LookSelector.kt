package com.akane.akastudio.core.color.look

import com.akane.akastudio.core.color.api.MasterLook
import com.akane.akastudio.core.color.api.SceneType

/**
 * Automatic look choice from scene analysis. Never returns [MasterLook.VELVET_MONOCHROME_HP5]; monochrome
 * is a deliberate user choice.
 */
object LookSelector {
    private const val FOREST_COOL_K = 6800f
    private const val GENERAL_WARM_K = 4200f
    private const val NIGHT_WARM_K = 4000f
    private const val NIGHT_MEAN_L = 0.30f

    /**
     * Picks the look for [scene], estimated scene white [cctKelvin] and mean OKLab lightness [meanL].
     * For [SceneType.GENERAL] a prominent face wins (Portra), then a dark tungsten scene
     * (meanL < 0.30 and cct < 4000 K → CineStill), then any warm scene (cct < 4200 K → Portra);
     * everything else is Pure Natural. Non-finite measurements fail every threshold.
     */
    fun select(scene: SceneType, cctKelvin: Float, meanL: Float, hasProminentFace: Boolean): MasterLook = when (scene) {
        SceneType.BEACH_SEA -> MasterLook.MEDITERRANEAN_AZURE
        SceneType.NIGHT_CITY -> MasterLook.CINESTILL_800T_NIGHT
        SceneType.CAFE_INTERIOR -> MasterLook.ESPRESSO_WARM_WOOD
        SceneType.PORTRAIT -> MasterLook.PORTRA_400_GOLDEN
        SceneType.ARCHITECTURE_AUTO -> MasterLook.NORDIC_SLATE_MATTE
        SceneType.NATURE_FOREST ->
            if (cctKelvin > FOREST_COOL_K) MasterLook.NORDIC_SLATE_MATTE else MasterLook.KODACHROME_64_CLASSIC
        SceneType.GENERAL -> when {
            hasProminentFace -> MasterLook.PORTRA_400_GOLDEN
            meanL < NIGHT_MEAN_L && cctKelvin < NIGHT_WARM_K -> MasterLook.CINESTILL_800T_NIGHT
            cctKelvin < GENERAL_WARM_K -> MasterLook.PORTRA_400_GOLDEN
            else -> MasterLook.PURE_NATURAL_PRO
        }
    }
}
