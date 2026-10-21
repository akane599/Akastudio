package com.akane.akastudio.core.color.grade

import com.akane.akastudio.core.color.api.OkLab
import com.akane.akastudio.core.color.look.hueDistance
import com.akane.akastudio.core.color.look.smoothstep
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Soft skin-likelihood weight in [0, 1] over OKLab, the product of three smooth windows:
 * - hue: 1 within 15° of 55°, fading to 0 at 35° away (covers ≈ 20–90°, all skin tones under daylight),
 * - chroma: rising over 0.015–0.04, falling over 0.14–0.20 (excludes neutrals and saturated oranges),
 * - lightness: rising over L 0.25–0.40, falling over 0.90–0.97.
 * The GPU shader mirrors these constants.
 */
internal object SkinMask {
    const val HUE_CENTER = 55f
    const val HUE_FLAT = 15f
    const val HUE_EDGE = 35f

    fun weight(lab: OkLab): Float {
        val c = hypot(lab.a, lab.b)
        val chroma = smoothstep(0.015f, 0.04f, c) * (1f - smoothstep(0.14f, 0.20f, c))
        if (chroma == 0f) return 0f
        val light = smoothstep(0.25f, 0.40f, lab.l) * (1f - smoothstep(0.90f, 0.97f, lab.l))
        if (light == 0f) return 0f
        val hue = Math.toDegrees(atan2(lab.b, lab.a).toDouble()).toFloat()
        val hueW = 1f - smoothstep(HUE_FLAT, HUE_EDGE, hueDistance(hue, HUE_CENTER))
        return chroma * light * hueW
    }
}
