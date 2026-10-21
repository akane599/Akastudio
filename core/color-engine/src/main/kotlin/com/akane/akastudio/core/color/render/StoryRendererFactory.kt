package com.akane.akastudio.core.color.render

import android.content.Context
import com.akane.akastudio.core.color.api.StoryRenderer

/**
 * Creates the OpenGL ES 3 [StoryRenderer]. It owns one dedicated GL thread; call [StoryRenderer.close]
 * when done. The grader comes from [defaultLayerGrader], so the GPU-grading implementation can be swapped
 * without touching callers.
 */
fun createStoryRenderer(context: Context): StoryRenderer =
    GlStoryRenderer(context.applicationContext.assets, graderFactory = ::defaultLayerGrader)
