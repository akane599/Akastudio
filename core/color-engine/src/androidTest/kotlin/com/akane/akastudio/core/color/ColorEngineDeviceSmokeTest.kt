package com.akane.akastudio.core.color

import android.opengl.GLES30
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akane.akastudio.core.color.gl.EglCore
import com.akane.akastudio.core.color.gl.GlCapabilities
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** On-device smoke: the engine's own EglCore brings up an OpenGL ES 3 context the renderer can query. */
@RunWith(AndroidJUnit4::class)
class ColorEngineDeviceSmokeTest {

    @Test
    fun eglCoreCreatesGles3Context() {
        val egl = EglCore()
        try {
            egl.initialize()

            val version = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAJOR_VERSION, version, 0)
            val versionString = GLES30.glGetString(GLES30.GL_VERSION).orEmpty()
            assertTrue("GLES major ${version[0]} ($versionString) must be >= 3", version[0] >= 3)

            val capabilities = GlCapabilities.query()
            assertTrue("max texture size ${capabilities.maxTextureSize}", capabilities.maxTextureSize >= 2048)
            assertTrue("max target width ${capabilities.maxTargetWidth}", capabilities.maxTargetWidth >= 2048)
        } finally {
            egl.release()
        }
    }
}
