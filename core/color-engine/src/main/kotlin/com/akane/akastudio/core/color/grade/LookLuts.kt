package com.akane.akastudio.core.color.grade

import com.akane.akastudio.core.color.api.MasterLook
import com.akane.akastudio.core.color.look.LookLibrary
import com.akane.akastudio.core.color.look.Lut3D
import com.akane.akastudio.core.color.look.LutBaker

/** The pair of baked lattices a look needs: the full look and its skin-safe variant. */
data class LookLuts(val look: Lut3D, val skinSafe: Lut3D)

/** Process-wide, thread-safe lazy bake of every [MasterLook]'s [LookLuts]; each look bakes at most once. */
object LookLutCache {
    private val entries: Map<MasterLook, Lazy<LookLuts>> = MasterLook.entries.associateWith { look ->
        lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            val spec = LookLibrary.spec(look)
            LookLuts(look = LutBaker.bakeLook(spec), skinSafe = LutBaker.bakeSkinSafe(spec))
        }
    }

    /** Baked LUTs for [look], baking on first use. */
    fun get(look: MasterLook): LookLuts = entries.getValue(look).value
}
