package com.akane.akastudio.core.color.render

import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import com.akane.akastudio.core.color.gl.GlEnvironment
import com.akane.akastudio.core.color.look.LutBaker
import com.akane.akastudio.core.color.render.grade.GradeVariant
import com.akane.akastudio.core.color.render.grade.ShaderLayerGrader

/** What the context offers GPU grading. Pure data so the choice is JVM-testable. */
internal data class GraderCapabilities(
    /** RGBA16F is colour-renderable (`GlCapabilities.floatColorBuffer`). */
    val floatColorBuffer: Boolean,
    /** 3D textures can hold a look lattice (`GL_MAX_3D_TEXTURE_SIZE` ≥ [LutBaker.SIZE]). */
    val texture3D: Boolean,
) {
    companion object {
        fun of(floatColorBuffer: Boolean, max3DTextureSize: Int): GraderCapabilities =
            GraderCapabilities(floatColorBuffer, texture3D = max3DTextureSize >= LutBaker.SIZE)
    }
}

/** Which [LayerGrader] a context gets. */
internal enum class GraderChoice {
    /** [ShaderLayerGrader] on RGBA16F targets. */
    SHADER_FLOAT,

    /** [ShaderLayerGrader] on 8-bit sRGB targets (no float colour buffers). */
    SHADER_REDUCED,

    /** [IdentityLayerGrader]: no 3D textures, layers ship ungraded. */
    IDENTITY,
}

/** Float targets and 3D textures → full shader; 3D textures only → reduced shader; otherwise identity. */
internal fun chooseGrader(capabilities: GraderCapabilities): GraderChoice = when {
    !capabilities.texture3D -> GraderChoice.IDENTITY
    capabilities.floatColorBuffer -> GraderChoice.SHADER_FLOAT
    else -> GraderChoice.SHADER_REDUCED
}

/**
 * The one place that decides which [LayerGrader] the renderer uses. Called on the GL thread each time a
 * context is (re)created. Falls back to [IdentityLayerGrader] with a logged warning when the context lacks
 * 3D textures or the grading programs fail to build, so a weak driver still renders.
 */
internal fun defaultLayerGrader(environment: GlEnvironment): LayerGrader {
    val max3D = IntArray(1)
    GLES20.glGetIntegerv(GLES30.GL_MAX_3D_TEXTURE_SIZE, max3D, 0)
    val capabilities = GraderCapabilities.of(environment.capabilities.floatColorBuffer, max3D[0])
    val variant = when (chooseGrader(capabilities)) {
        GraderChoice.SHADER_FLOAT -> GradeVariant.FLOAT
        GraderChoice.SHADER_REDUCED -> GradeVariant.REDUCED.also {
            Log.w(TAG, "no float colour buffers; grading on 8-bit sRGB targets (HDR highlights clip)")
        }
        GraderChoice.IDENTITY -> {
            Log.w(TAG, "GL_MAX_3D_TEXTURE_SIZE ${max3D[0]} < ${LutBaker.SIZE}; layers render ungraded")
            return IdentityLayerGrader
        }
    }
    return try {
        ShaderLayerGrader(environment, variant)
    } catch (e: RuntimeException) {
        Log.w(TAG, "shader grader unavailable; layers render ungraded", e)
        IdentityLayerGrader
    }
}

private const val TAG = "GraderSelection"
