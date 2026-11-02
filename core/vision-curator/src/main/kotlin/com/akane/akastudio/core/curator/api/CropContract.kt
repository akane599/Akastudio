package com.akane.akastudio.core.curator.api

/** Crop rules the curator's layout check and the collage composer's crop solver must share to agree exactly. */
object CropContract {
    /** A face box grows by this factor about its centre (then is clamped to the image) before a crop must contain it. */
    const val FACE_EXPANSION: Double = 1.15
}
