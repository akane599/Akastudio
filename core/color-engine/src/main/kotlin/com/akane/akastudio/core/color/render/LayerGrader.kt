package com.akane.akastudio.core.color.render

import android.graphics.RectF
import com.akane.akastudio.core.color.api.GradeParams
import com.akane.akastudio.core.color.gl.GlTexture

/**
 * Per-layer grading stage of the compositor. Created on the GL thread by the factory chosen in
 * [defaultLayerGrader]; closed on the GL thread when the renderer closes or its context is lost.
 */
internal interface LayerGrader : AutoCloseable {
    /**
     * GL thread only. Returns a linear RGBA16F texture to sample for this layer, already graded;
     * `region` = source crop in texels (pre-rotation bounds). May return `source` itself. Result valid until
     * the next call with the same `slot`.
     *
     * Coordinate contract: the compositor samples the result with the same normalized coordinates it would
     * use for [source] (t = 0 is the top row, the full [0, 1] range spans the whole source image), because a
     * rotated crop samples slightly outside [region]. A grader may use any resolution; [outWidth] x
     * [outHeight] is the layer's size in target pixels, a hint for the texel density that is actually seen.
     * Slots: layer i of a scene uses slot 2i for its own grade and 2i + 1 for the neutral A/B half.
     */
    fun grade(source: GlTexture, region: RectF, outWidth: Int, outHeight: Int, params: GradeParams, slot: Int): GlTexture
}

/** Ships ungraded layers: every grade is the source texture itself. */
internal object IdentityLayerGrader : LayerGrader {
    override fun grade(source: GlTexture, region: RectF, outWidth: Int, outHeight: Int, params: GradeParams, slot: Int): GlTexture = source

    override fun close() = Unit
}
