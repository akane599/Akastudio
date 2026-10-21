package com.akane.akastudio.core.color.render.grade

import android.opengl.GLES20
import android.opengl.GLES30
import com.akane.akastudio.core.color.api.MasterLook
import com.akane.akastudio.core.color.gl.GlException
import com.akane.akastudio.core.color.gl.checkGlError
import com.akane.akastudio.core.color.grade.LookLutCache
import com.akane.akastudio.core.color.look.Lut3D
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * `LookLutCache` lattices as `GL_TEXTURE_3D` RGB16F textures with linear filtering and clamp-to-edge, uploaded
 * once per look on first use (x = red, y = green, z = blue, matching [Lut3D]'s red-fastest layout).
 * `grade_pixel` reads the nodes with `texelFetch` and interpolates tetrahedrally like [Lut3D.sample]: hardware
 * trilinear filtering at `(x·(N−1)+0.5)/N` measured up to 2.8/255 from the CPU on saturated colours, over the
 * 2/255 parity budget. GL thread only.
 */
internal class LookLutTextures {
    private class Pair3D(val look: Int, val skinSafe: Int, val size: Int)

    private val textures = HashMap<MasterLook, Pair3D>()

    /** Binds [look]'s full LUT to texture unit [lookUnit] and its skin-safe LUT to [skinSafeUnit]; returns the lattice size. */
    fun bind(look: MasterLook, lookUnit: Int, skinSafeUnit: Int): Int {
        val pair = textures[look] ?: upload(look).also { textures[look] = it }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + lookUnit)
        GLES20.glBindTexture(GLES30.GL_TEXTURE_3D, pair.look)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + skinSafeUnit)
        GLES20.glBindTexture(GLES30.GL_TEXTURE_3D, pair.skinSafe)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        return pair.size
    }

    fun release() {
        if (textures.isEmpty()) return
        val ids = textures.values.flatMap { listOf(it.look, it.skinSafe) }.toIntArray()
        GLES20.glDeleteTextures(ids.size, ids, 0)
        textures.clear()
    }

    private fun upload(look: MasterLook): Pair3D {
        val luts = LookLutCache.get(look)
        val first = create(luts.look)
        val second = try {
            create(luts.skinSafe)
        } catch (e: GlException) {
            GLES20.glDeleteTextures(1, intArrayOf(first), 0)
            throw e
        }
        return Pair3D(first, second, luts.look.size)
    }

    private fun create(lut: Lut3D): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val id = ids[0]
        val n = lut.size
        val data = ByteBuffer.allocateDirect(lut.data.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        data.asFloatBuffer().put(lut.data)
        GLES20.glBindTexture(GLES30.GL_TEXTURE_3D, id)
        GLES30.glTexStorage3D(GLES30.GL_TEXTURE_3D, 1, GLES30.GL_RGB16F, n, n, n)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        // (RGB, FLOAT) into RGB16F is a legal ES 3.0 upload; the driver rounds to half floats.
        GLES30.glTexSubImage3D(GLES30.GL_TEXTURE_3D, 0, 0, 0, 0, n, n, n, GLES20.GL_RGB, GLES20.GL_FLOAT, data)
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES30.GL_TEXTURE_3D, 0)
        try {
            checkGlError("3D LUT upload ${n}^3 RGB16F")
        } catch (e: GlException) {
            GLES20.glDeleteTextures(1, ids, 0)
            throw e
        }
        return id
    }
}
