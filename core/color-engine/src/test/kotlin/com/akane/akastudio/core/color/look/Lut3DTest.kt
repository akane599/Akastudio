package com.akane.akastudio.core.color.look

import com.akane.akastudio.core.color.api.MasterLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.random.Random

class Lut3DTest {

    @Test
    fun identityLut_reproducesInputs() {
        val out = FloatArray(3)
        for (size in intArrayOf(2, 17, LutBaker.SIZE)) {
            val lut = Lut3D.identity(size)
            val rnd = Random(size)
            repeat(5000) {
                val r = rnd.nextFloat()
                val g = rnd.nextFloat()
                val b = rnd.nextFloat()
                lut.sample(r, g, b, out)
                assertEquals(r, out[0], 1e-6f)
                assertEquals(g, out[1], 1e-6f)
                assertEquals(b, out[2], 1e-6f)
            }
            lut.sample(1f, 0f, 1f, out)
            assertEquals(1f, out[0], 1e-6f)
            assertEquals(0f, out[1], 1e-6f)
            assertEquals(1f, out[2], 1e-6f)
        }
    }

    @Test
    fun tetrahedral_isExactOnAffineFunctions() {
        val size = 9
        val max = (size - 1).toFloat()
        val data = FloatArray(size * size * size * 3)
        var i = 0
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            val x = r / max
            val y = g / max
            val z = b / max
            data[i++] = 0.2f * x + 0.5f * y - 0.1f * z + 0.1f
            data[i++] = 0.7f * z + 0.05f
            data[i++] = 0.3f * x + 0.3f * y + 0.3f * z
        }
        val lut = Lut3D(size, data)
        val out = FloatArray(3)
        val rnd = Random(7)
        repeat(2000) {
            val x = rnd.nextFloat()
            val y = rnd.nextFloat()
            val z = rnd.nextFloat()
            lut.sample(x, y, z, out)
            assertEquals(0.2f * x + 0.5f * y - 0.1f * z + 0.1f, out[0], 1e-6f)
            assertEquals(0.7f * z + 0.05f, out[1], 1e-6f)
            assertEquals(0.3f * x + 0.3f * y + 0.3f * z, out[2], 1e-6f)
        }
    }

    @Test
    fun sample_clampsOutOfRangeAndNaN() {
        val lut = Lut3D.identity(5)
        val out = FloatArray(3)
        lut.sample(-0.5f, 1.7f, Float.NaN, out)
        assertEquals(0f, out[0], 0f)
        assertEquals(1f, out[1], 1e-6f)
        assertEquals(0f, out[2], 0f)
    }

    @Test
    fun constructor_rejectsBadShape() {
        assertThrows(IllegalArgumentException::class.java) { Lut3D(1, FloatArray(3)) }
        assertThrows(IllegalArgumentException::class.java) { Lut3D(4, FloatArray(10)) }
    }

    @Test
    fun haldLevel8_is512Square() {
        val image = HaldClut.encode(Lut3D.identity(LutBaker.SIZE))
        assertEquals(512, image.width)
        assertEquals(512, image.height)
        assertEquals(64, HaldClut.decode(image).size)
    }

    @Test
    fun hald_roundTripWithinOneCode() {
        val luts = listOf(
            Lut3D.identity(LutBaker.SIZE),
            LutBaker.bakeLook(LookLibrary.spec(MasterLook.CINESTILL_800T_NIGHT)),
        )
        val a = FloatArray(3)
        val b = FloatArray(3)
        for (lut in luts) {
            val decoded = HaldClut.decode(HaldClut.encode(lut, level = 8))
            val n = decoded.size
            val max = (n - 1).toFloat()
            // On the decoded lattice nodes the only error is 8-bit quantisation.
            for (bi in 0 until n step 3) for (gi in 0 until n step 3) for (ri in 0 until n step 3) {
                lut.sample(ri / max, gi / max, bi / max, a)
                decoded.sample(ri / max, gi / max, bi / max, b)
                for (c in 0 until 3) assertEquals(a[c], b[c], 1f / 255f)
            }
        }
        // Identity round trip holds anywhere since both lattices are exact on it.
        val identity = HaldClut.decode(HaldClut.encode(Lut3D.identity(LutBaker.SIZE), level = 4))
        val rnd = Random(3)
        repeat(2000) {
            val x = rnd.nextFloat()
            val y = rnd.nextFloat()
            val z = rnd.nextFloat()
            identity.sample(x, y, z, b)
            assertEquals(x, b[0], 1f / 255f)
            assertEquals(y, b[1], 1f / 255f)
            assertEquals(z, b[2], 1f / 255f)
        }
    }

    @Test
    fun haldDecode_rejectsNonHaldSizes() {
        val bad = com.akane.akastudio.core.color.api.ImagePixels(100, 100, IntArray(100 * 100))
        assertThrows(IllegalArgumentException::class.java) { HaldClut.decode(bad) }
        assertThrows(IllegalArgumentException::class.java) { HaldClut.encode(Lut3D.identity(2), level = 1) }
    }
}
