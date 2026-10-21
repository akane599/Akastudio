package com.akane.akastudio.core.color.device

import android.opengl.GLES20
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akane.akastudio.core.color.gl.GlException
import com.akane.akastudio.core.color.gl.GlFormat
import com.akane.akastudio.core.color.gl.GlTexture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SQ-64: a GL error left pending by an earlier, unrelated call (in the field, a present to a window surface
 * that is no longer available leaves GL_INVALID_OPERATION queued) must not fail the next texture allocation.
 */
@RunWith(AndroidJUnit4::class)
class GlTextureStaleErrorDeviceTest {

    @Test
    fun allocateSucceedsWhenAnEarlierCallLeftAnInvalidOperationPending() {
        OffscreenGl.open().use {
            val name = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
            try {
                // The injection itself is checked once, so a driver that tolerates it cannot make this test vacuous.
                injectInvalidOperation(name)
                assertEquals("re-binding a 2D texture name as a cube map must raise GL_INVALID_OPERATION", GLES20.GL_INVALID_OPERATION, GLES20.glGetError())
                assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError())

                injectInvalidOperation(name)
                val texture = GlTexture.allocate(64, 32, GlFormat.RGBA16F, levels = 3)
                try {
                    assertTrue("allocation must yield a texture name", texture.id != 0)
                    assertEquals("the stale error must not survive the allocation either", GLES20.GL_NO_ERROR, GLES20.glGetError())
                } finally {
                    texture.release()
                }
            } finally {
                GLES20.glDeleteTextures(1, intArrayOf(name), 0)
            }
        }
    }

    @Test
    fun allocateStillReportsAnErrorRaisedByItsOwnStorageCall() {
        OffscreenGl.open().use {
            val maxSize = IntArray(1).also { GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, it, 0) }[0]
            val error = assertThrows(GlException::class.java) { GlTexture.allocate(maxSize + 1, 1, GlFormat.RGBA8) }
            assertTrue("the failure must name glTexStorage2D: ${error.message}", error.message.orEmpty().startsWith("glTexStorage2D"))
            assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError())
        }
    }

    /** Binds [name] as a 2D texture, then as a cube map: the second bind is GL_INVALID_OPERATION and changes nothing. */
    private fun injectInvalidOperation(name: Int) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, name)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_CUBE_MAP, name)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }
}
