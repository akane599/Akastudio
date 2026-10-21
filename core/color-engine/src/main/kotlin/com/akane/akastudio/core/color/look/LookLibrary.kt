package com.akane.akastudio.core.color.look

import com.akane.akastudio.core.color.api.MasterLook

/**
 * The eight authored master looks. Hues are OKLCh degrees (skin ≈ 40–65°, sand ≈ 70°, foliage ≈ 140–150°,
 * sea/sky ≈ 230–260°). All values are deliberately subtle: the LUT is also blended by the user's
 * `lookIntensity` and protected on skin.
 */
object LookLibrary {

    /** Returns the immutable spec of [look]. */
    fun spec(look: MasterLook): LookSpec = when (look) {
        MasterLook.PORTRA_400_GOLDEN -> PORTRA_400_GOLDEN
        MasterLook.MEDITERRANEAN_AZURE -> MEDITERRANEAN_AZURE
        MasterLook.ESPRESSO_WARM_WOOD -> ESPRESSO_WARM_WOOD
        MasterLook.CINESTILL_800T_NIGHT -> CINESTILL_800T_NIGHT
        MasterLook.NORDIC_SLATE_MATTE -> NORDIC_SLATE_MATTE
        MasterLook.KODACHROME_64_CLASSIC -> KODACHROME_64_CLASSIC
        MasterLook.VELVET_MONOCHROME_HP5 -> VELVET_MONOCHROME_HP5
        MasterLook.PURE_NATURAL_PRO -> PURE_NATURAL_PRO
    }

    /**
     * Portra 400, golden hour. Soft 0.35 shoulder into a 0.985 white, gentle toe, contrast +0.06.
     * Golden mids/highlights (highlight hue 75°, C 0.025) over faintly cool shadows (225°, C 0.006).
     * Creamy skin: the 30–60° band turns 3° toward yellow, loses 4% chroma and lifts L by 0.01.
     * Overall chroma 0.96, +6 mired warmth. Grain 0.25 at 1.4 px (colour share 0.35), halation 0.05.
     */
    private val PORTRA_400_GOLDEN = LookSpec(
        look = MasterLook.PORTRA_400_GOLDEN,
        tone = ToneCurve(blackLift = 0.015f, toe = 0.05f, shoulder = 0.35f, contrast = 0.06f, whiteClip = 0.985f),
        split = SplitTone(shadowHueDeg = 225f, shadowChroma = 0.006f, highlightHueDeg = 75f, highlightChroma = 0.025f, balance = 0f),
        globalChroma = 0.96f,
        hueShifts = listOf(
            HueShift(centerDeg = 45f, widthDeg = 30f, hueShiftDeg = 3f, chromaScale = 0.96f, lightnessShift = 0.01f),
        ),
        warmthMired = 6f,
        monochrome = null,
        defaultHalation = 0.05f,
        defaultGrain = 0.25f,
        grainSizePx = 1.4f,
        grainChroma = 0.35f,
    )

    /**
     * Mediterranean azure. Blues around 240° deepen (chroma ×1.18, L −0.03); sand and stone around 70°
     * warm up (chroma ×1.08, L +0.02). Contrast +0.10 with a light 0.2 shoulder; whites stay clean
     * (no highlight tint, no warmth); shadows carry a trace of 235° at C 0.006. Chroma 1.04.
     * Grain 0.08 at 1.2 px (colour share 0.3), no halation.
     */
    private val MEDITERRANEAN_AZURE = LookSpec(
        look = MasterLook.MEDITERRANEAN_AZURE,
        tone = ToneCurve(blackLift = 0f, toe = 0.04f, shoulder = 0.2f, contrast = 0.10f, whiteClip = 1f),
        split = SplitTone(shadowHueDeg = 235f, shadowChroma = 0.006f, highlightHueDeg = 0f, highlightChroma = 0f, balance = 0f),
        globalChroma = 1.04f,
        hueShifts = listOf(
            HueShift(centerDeg = 240f, widthDeg = 40f, hueShiftDeg = 0f, chromaScale = 1.18f, lightnessShift = -0.03f),
            HueShift(centerDeg = 70f, widthDeg = 30f, hueShiftDeg = 0f, chromaScale = 1.08f, lightnessShift = 0.02f),
        ),
        warmthMired = 0f,
        monochrome = null,
        defaultHalation = 0f,
        defaultGrain = 0.08f,
        grainSizePx = 1.2f,
        grainChroma = 0.3f,
    )

    /**
     * Espresso and warm wood. Rich brown shadows (55°, C 0.03) under faint amber highlights (80°, C 0.012),
     * balance +0.1 toward the shadows. Toe 0.12 slightly crushes blacks; contrast +0.10, shoulder 0.25
     * into a 0.98 white. Wood tones around 60° gain 6% chroma, turn 2° toward red and darken by 0.01;
     * blues around 240° are tamed to 85% chroma. Amber warmth +12 mired. Grain 0.18 at 1.5 px, halation 0.08.
     */
    private val ESPRESSO_WARM_WOOD = LookSpec(
        look = MasterLook.ESPRESSO_WARM_WOOD,
        tone = ToneCurve(blackLift = 0f, toe = 0.12f, shoulder = 0.25f, contrast = 0.10f, whiteClip = 0.98f),
        split = SplitTone(shadowHueDeg = 55f, shadowChroma = 0.03f, highlightHueDeg = 80f, highlightChroma = 0.012f, balance = 0.1f),
        globalChroma = 1f,
        hueShifts = listOf(
            HueShift(centerDeg = 60f, widthDeg = 35f, hueShiftDeg = -2f, chromaScale = 1.06f, lightnessShift = -0.01f),
            HueShift(centerDeg = 240f, widthDeg = 40f, hueShiftDeg = 0f, chromaScale = 0.85f, lightnessShift = 0f),
        ),
        warmthMired = 12f,
        monochrome = null,
        defaultHalation = 0.08f,
        defaultGrain = 0.18f,
        grainSizePx = 1.5f,
        grainChroma = 0.4f,
    )

    /**
     * CineStill 800T at night. Tungsten balance cools whites by −20 mired; teal shadows (200°, C 0.022)
     * against amber highlights (70°, C 0.02), balance −0.1 toward the highlights. Contrast +0.12, toe 0.06,
     * a faint 0.01 lift and a 0.3 shoulder into a 0.98 white. Reds around 30° gain 6% chroma.
     * Strong default halation 0.55; grain 0.35 at 1.8 px with colour share 0.5.
     */
    private val CINESTILL_800T_NIGHT = LookSpec(
        look = MasterLook.CINESTILL_800T_NIGHT,
        tone = ToneCurve(blackLift = 0.01f, toe = 0.06f, shoulder = 0.3f, contrast = 0.12f, whiteClip = 0.98f),
        split = SplitTone(shadowHueDeg = 200f, shadowChroma = 0.022f, highlightHueDeg = 70f, highlightChroma = 0.02f, balance = -0.1f),
        globalChroma = 1f,
        hueShifts = listOf(
            HueShift(centerDeg = 30f, widthDeg = 30f, hueShiftDeg = 0f, chromaScale = 1.06f, lightnessShift = 0f),
        ),
        warmthMired = -20f,
        monochrome = null,
        defaultHalation = 0.55f,
        defaultGrain = 0.35f,
        grainSizePx = 1.8f,
        grainChroma = 0.5f,
    )

    /**
     * Nordic slate matte. Overall chroma 0.78; greens around 150° turn silvery (chroma ×0.7, 8° toward
     * cyan, L +0.01). Deep contrast +0.25 on a 0.04 black lift and a 0.2 shoulder into a 0.97 white for the
     * matte finish. Cool slate shadows (230°, C 0.008), barely warm highlights (90°, C 0.004), −4 mired.
     * Grain 0.10 at 1.2 px (colour share 0.3), no halation.
     */
    private val NORDIC_SLATE_MATTE = LookSpec(
        look = MasterLook.NORDIC_SLATE_MATTE,
        tone = ToneCurve(blackLift = 0.04f, toe = 0f, shoulder = 0.2f, contrast = 0.25f, whiteClip = 0.97f),
        split = SplitTone(shadowHueDeg = 230f, shadowChroma = 0.008f, highlightHueDeg = 90f, highlightChroma = 0.004f, balance = 0f),
        globalChroma = 0.78f,
        hueShifts = listOf(
            HueShift(centerDeg = 150f, widthDeg = 40f, hueShiftDeg = 8f, chromaScale = 0.7f, lightnessShift = 0.01f),
        ),
        warmthMired = -4f,
        monochrome = null,
        defaultHalation = 0f,
        defaultGrain = 0.10f,
        grainSizePx = 1.2f,
        grainChroma = 0.3f,
    )

    /**
     * Kodachrome 64. Rich primaries: reds around 25° chroma ×1.12 (L −0.01), blues around 250° chroma ×1.10
     * (L −0.01), greens around 140° turn 4° toward yellow at 98% chroma. Micro-contrast +0.18 with a small
     * toe and a 0.15 shoulder; chroma 1.05; +4 mired; trace split (250°/80°). Grain 0.12 at 1.1 px, halation 0.03.
     */
    private val KODACHROME_64_CLASSIC = LookSpec(
        look = MasterLook.KODACHROME_64_CLASSIC,
        tone = ToneCurve(blackLift = 0f, toe = 0.05f, shoulder = 0.15f, contrast = 0.18f, whiteClip = 1f),
        split = SplitTone(shadowHueDeg = 250f, shadowChroma = 0.006f, highlightHueDeg = 80f, highlightChroma = 0.008f, balance = 0f),
        globalChroma = 1.05f,
        hueShifts = listOf(
            HueShift(centerDeg = 25f, widthDeg = 30f, hueShiftDeg = 0f, chromaScale = 1.12f, lightnessShift = -0.01f),
            HueShift(centerDeg = 250f, widthDeg = 35f, hueShiftDeg = 0f, chromaScale = 1.10f, lightnessShift = -0.01f),
            HueShift(centerDeg = 140f, widthDeg = 35f, hueShiftDeg = -4f, chromaScale = 0.98f, lightnessShift = 0f),
        ),
        warmthMired = 4f,
        monochrome = null,
        defaultHalation = 0.03f,
        defaultGrain = 0.12f,
        grainSizePx = 1.1f,
        grainChroma = 0.4f,
    )

    /**
     * Ilford HP5 velvet monochrome. Luminance from the (0.30, 0.59, 0.11) mix, then a regional S-curve:
     * contrast +0.35 around the 0.45 pivot, toe 0.08, shoulder 0.2 into a 0.99 white. No tint.
     * Grain 0.5 at 1.6 px, colour share 0 (pure luminance grain), no halation.
     */
    private val VELVET_MONOCHROME_HP5 = LookSpec(
        look = MasterLook.VELVET_MONOCHROME_HP5,
        tone = ToneCurve(blackLift = 0f, toe = 0.08f, shoulder = 0.2f, contrast = 0.35f, whiteClip = 0.99f),
        split = SplitTone.NONE,
        globalChroma = 0f,
        hueShifts = emptyList(),
        warmthMired = 0f,
        monochrome = MonoMix(0.30f, 0.59f, 0.11f),
        defaultHalation = 0f,
        defaultGrain = 0.5f,
        grainSizePx = 1.6f,
        grainChroma = 0f,
    )

    /**
     * Pure natural. Near-identity: contrast +0.05 and a 0.1 shoulder into a full white, nothing else.
     * No split tone, no hue shifts, no warmth, no grain, no halation.
     */
    private val PURE_NATURAL_PRO = LookSpec(
        look = MasterLook.PURE_NATURAL_PRO,
        tone = ToneCurve(blackLift = 0f, toe = 0f, shoulder = 0.1f, contrast = 0.05f, whiteClip = 1f),
        split = SplitTone.NONE,
        globalChroma = 1f,
        hueShifts = emptyList(),
        warmthMired = 0f,
        monochrome = null,
        defaultHalation = 0f,
        defaultGrain = 0f,
        grainSizePx = 1f,
        grainChroma = 0f,
    )
}
