package com.akane.akastudio.core.export.process

/**
 * IEEE 754 binary16 ("half") conversions.
 *
 * [toFloat] is exact for every half value (normals, subnormals, ±0, ±infinity and NaN payloads) and is served from a
 * 65 536-entry table. [fromFloat] rounds to nearest, ties to even, produces subnormals below 2^-14, overflows to
 * infinity at 65 520 and keeps NaN payload bits that fit in the half mantissa.
 */
object Half {

    private const val SIGN_MASK = 0x8000
    private const val HALF_EXP_ALL_ONES = 0x7C00

    /** 2^-24, the smallest positive half subnormal. */
    private val SUBNORMAL_UNIT = Float.fromBits(0x33800000)

    /** Float bit patterns (absolute value) bounding the half ranges. */
    private const val FLOAT_INF_BITS = 0x7F800000
    private const val FLOAT_HALF_OVERFLOW_BITS = 0x477FF000   // 65520: rounds (ties to even) to infinity
    private const val FLOAT_MIN_HALF_NORMAL_BITS = 0x38800000 // 2^-14
    private const val FLOAT_HALF_UNDERFLOW_BITS = 0x33000000  // 2^-25: at or below rounds to zero

    private val TABLE = FloatArray(1 shl 16) { decode(it) }

    /** Exact float value of the half bit pattern [h]. */
    fun toFloat(h: Short): Float = TABLE[h.toInt() and 0xFFFF]

    /** Nearest half (ties to even) to [f]. */
    fun fromFloat(f: Float): Short = encode(f).toShort()

    /** Table lookup by unsigned 16-bit pattern, for hot loops that already hold the bits as an Int. */
    internal fun bitsToFloat(bits: Int): Float = TABLE[bits and 0xFFFF]

    private fun decode(bits: Int): Float {
        val sign = (bits and SIGN_MASK) shl 16
        val exp = (bits ushr 10) and 0x1F
        val mant = bits and 0x3FF
        return when (exp) {
            0 -> {
                val magnitude = mant * SUBNORMAL_UNIT
                if (sign != 0) -magnitude else magnitude
            }
            0x1F -> Float.fromBits(sign or FLOAT_INF_BITS or (mant shl 13))
            else -> Float.fromBits(sign or ((exp + 112) shl 23) or (mant shl 13))
        }
    }

    private fun encode(f: Float): Int {
        val bits = f.toRawBits()
        val sign = (bits ushr 16) and SIGN_MASK
        val abs = bits and 0x7FFFFFFF
        return when {
            abs > FLOAT_INF_BITS -> {
                val payload = (abs ushr 13) and 0x3FF
                sign or HALF_EXP_ALL_ONES or (if (payload == 0) 0x200 else payload)
            }
            abs >= FLOAT_HALF_OVERFLOW_BITS -> sign or HALF_EXP_ALL_ONES
            abs >= FLOAT_MIN_HALF_NORMAL_BITS -> sign or encodeNormal(abs)
            abs >= FLOAT_HALF_UNDERFLOW_BITS -> sign or encodeSubnormal(abs)
            else -> sign
        }
    }

    /** [abs] lies in [2^-14, 65520); a mantissa carry correctly bumps the exponent. */
    private fun encodeNormal(abs: Int): Int {
        val halfExp = (abs ushr 23) - 112
        val mant = abs and 0x7FFFFF
        val truncated = (halfExp shl 10) or (mant ushr 13)
        return roundToEven(truncated, mant and 0x1FFF, 0x1000)
    }

    /** [abs] lies in [2^-25, 2^-14); the result counts units of 2^-24 and may round up to the smallest normal. */
    private fun encodeSubnormal(abs: Int): Int {
        val mant = (abs and 0x7FFFFF) or 0x800000
        val shift = 126 - (abs ushr 23)
        val truncated = mant ushr shift
        return roundToEven(truncated, mant and ((1 shl shift) - 1), 1 shl (shift - 1))
    }

    private fun roundToEven(truncated: Int, remainder: Int, halfway: Int): Int =
        if (remainder > halfway || (remainder == halfway && (truncated and 1) == 1)) truncated + 1 else truncated
}
