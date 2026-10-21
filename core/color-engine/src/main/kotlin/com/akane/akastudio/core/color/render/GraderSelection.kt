package com.akane.akastudio.core.color.render

import com.akane.akastudio.core.color.gl.GlEnvironment

/**
 * The one place that decides which [LayerGrader] the renderer uses. Called on the GL thread each time a
 * context is (re)created. The GPU-grading ticket swaps the returned implementation here.
 */
@Suppress("UNUSED_PARAMETER")
internal fun defaultLayerGrader(environment: GlEnvironment): LayerGrader = IdentityLayerGrader
