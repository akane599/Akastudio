package com.akane.akastudio.core.color.gl

import android.content.res.AssetManager

/**
 * Shared GL-thread resources of one live context, handed to layer graders when they are created.
 * Everything here dies with the context; a grader is closed and recreated after context loss.
 */
internal class GlEnvironment(
    val assets: AssetManager,
    val capabilities: GlCapabilities,
    val fboPool: FboPool,
    val quad: FullscreenQuad,
)
