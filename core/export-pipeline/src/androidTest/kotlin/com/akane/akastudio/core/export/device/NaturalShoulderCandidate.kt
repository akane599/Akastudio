package com.akane.akastudio.core.export.device

import com.akane.akastudio.core.color.api.HalfFloatImage
import com.akane.akastudio.core.color.math.ColorMath
import com.akane.akastudio.core.export.process.GainMapResult
import com.akane.akastudio.core.export.process.Half
import com.akane.akastudio.core.export.process.HalfRows
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Test-only M1 option C. No eligibility or production tone-curve change. */
internal object NaturalShoulderCandidate {
    const val KNEE = 0.6f
    private const val EPSILON = 1f / 64f

    fun compute(target: HalfFloatImage): GainMapResult {
        var peak = 0f
        for (p in target.rgba.indices step 4) for (c in 0..2) peak = max(peak, channel(target.rgba[p + c]))
        val w = target.width
        val h = target.height
        val gw = (w + 3) / 4
        val gh = (h + 3) / 4
        val base = target.rgba.copyOf()
        val logs = FloatArray(gw * gh)
        val sumHdr = DoubleArray(gw)
        val sumBase = DoubleArray(gw)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = (y * w + x) * 4
                val r = channel(target.rgba[p])
                val g = channel(target.rgba[p + 1])
                val b = channel(target.rgba[p + 2])
                val m = max(r, max(g, b))
                val factor = factor(m, peak)
                base[p] = Half.fromFloat(r * factor)
                base[p + 1] = Half.fromFloat(g * factor)
                base[p + 2] = Half.fromFloat(b * factor)
                val luminance = ColorMath.relativeLuminance(r, g, b)
                sumHdr[x / 4] += luminance.toDouble()
                // Match A's precision policy: gain is measured on ideal factor before half storage rounding.
                sumBase[x / 4] += (luminance * factor).toDouble()
            }
            if ((y + 1) % 4 == 0 || y == h - 1) {
                for (gx in 0 until gw) {
                    val count = (min(w, (gx + 1) * 4) - gx * 4) * (y % 4 + 1)
                    logs[(y / 4) * gw + gx] = (ln((sumHdr[gx] / count + EPSILON) / (sumBase[gx] / count + EPSILON)) / ln(2.0)).toFloat()
                }
                sumHdr.fill(0.0)
                sumBase.fill(0.0)
            }
        }
        val maxLog = logs.max()
        var minLog = max(0f, logs.min())
        if (maxLog - minLog < 1e-3f) minLog = 0f
        val noGain = maxLog <= 0.01f
        val codes = ByteArray(logs.size) { if (noGain) 0 else (255f * ((logs[it] - minLog) / (maxLog - minLog)).coerceIn(0f, 1f)).roundToInt().toByte() }
        return GainMapResult(HalfFloatImage(w, h, base), codes, gw, gh,
            if (noGain) 1f else 2f.pow(minLog), if (noGain) 1f else 2f.pow(maxLog), 1f,
            EPSILON, EPSILON, 1f, if (noGain) 1f else 2f.pow(maxLog))
    }

    internal fun factor(maxChannel: Float, peak: Float): Float {
        if (peak <= 1f || maxChannel <= KNEE) return 1f
        val t = maxChannel - KNEE
        val length = peak - KNEE
        val a = 1f - KNEE
        val mapped = KNEE + a * t / (a + t * (1f - a / length))
        return (mapped / maxChannel).coerceAtMost(1f)
    }

    private fun channel(half: Short) = max(0f, HalfRows.finite(Half.toFloat(half)))
}
