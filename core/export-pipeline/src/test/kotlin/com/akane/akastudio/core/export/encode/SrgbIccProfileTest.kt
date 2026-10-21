package com.akane.akastudio.core.export.encode

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.color.ColorSpace
import java.awt.color.ICC_ColorSpace
import java.awt.color.ICC_Profile
import java.nio.ByteBuffer
import kotlin.math.abs

class SrgbIccProfileTest {

    private val bytes = SrgbIccProfile.bytes
    private val buffer = ByteBuffer.wrap(bytes)

    @Test
    fun javaAcceptsProfile_asRgbDisplayProfile() {
        val profile = ICC_Profile.getInstance(bytes)
        assertEquals(ColorSpace.TYPE_RGB, profile.colorSpaceType)
        assertEquals(ICC_Profile.CLASS_DISPLAY, profile.profileClass)
        assertEquals(bytes.size, buffer.getInt(0))
        assertEquals(2, profile.majorVersion)
        assertEquals(1, profile.minorVersion shr 4)
    }

    @Test
    fun header_fieldsMatchIccV21Display() {
        assertEquals(0x02100000, buffer.getInt(8))
        assertEquals("mntr", sig(12))
        assertEquals("RGB ", sig(16))
        assertEquals("XYZ ", sig(20))
        assertEquals("acsp", sig(36))
        assertEquals(0, buffer.getInt(64)) // perceptual
        assertEquals(0x0000F6D6, buffer.getInt(68))
        assertEquals(0x00010000, buffer.getInt(72))
        assertEquals(0x0000D32D, buffer.getInt(76))
        assertTrue(bytes.copyOfRange(84, 100).all { it == 0.toByte() }) // profile ID zero
    }

    @Test
    fun tagTable_offsetsAlignedAndInBounds() {
        val count = buffer.getInt(128)
        val signatures = (0 until count).map { i ->
            val at = 132 + i * 12
            val offset = buffer.getInt(at + 4)
            val size = buffer.getInt(at + 8)
            assertEquals("offset of ${sig(at)}", 0, offset % 4)
            assertTrue("tag ${sig(at)} in bounds", offset >= 132 + count * 12 && offset + size <= bytes.size)
            sig(at)
        }
        assertEquals(setOf("desc", "cprt", "wtpt", "rXYZ", "gXYZ", "bXYZ", "rTRC", "gTRC", "bTRC"), signatures.toSet())
        assertEquals(0, bytes.size % 4)
    }

    @Test
    fun description_andCurveContent() {
        val profile = ICC_Profile.getInstance(bytes)
        val desc = profile.getData(ICC_Profile.icSigProfileDescriptionTag)
        val asciiLength = ByteBuffer.wrap(desc).getInt(8)
        assertEquals("sRGB IEC61966-2.1", String(desc, 12, asciiLength - 1, Charsets.US_ASCII))
        val trc = ByteBuffer.wrap(profile.getData(ICC_Profile.icSigRedTRCTag))
        assertEquals("curv", String(profile.getData(ICC_Profile.icSigRedTRCTag), 0, 4, Charsets.US_ASCII))
        assertEquals(1024, trc.getInt(8))
        assertEquals(0, trc.getShort(12).toInt() and 0xFFFF)
        assertEquals(65535, trc.getShort(12 + 2 * 1023).toInt() and 0xFFFF)
    }

    @Test
    fun colorants_sumToD50() {
        val m = IccProfileBuilder.srgbToD50Xyz()
        for (row in 0 until 3) assertEquals(IccProfileBuilder.D50[row], m[row].sum(), 1e-9)
    }

    @Test
    fun toCieXyz_matchesJavaBuiltInSrgb() {
        val ours = ICC_ColorSpace(ICC_Profile.getInstance(bytes))
        val reference = ColorSpace.getInstance(ColorSpace.CS_sRGB)
        val random = java.util.Random(17)
        val colors = listOf(
            floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f), floatArrayOf(1f, 0f, 0f),
            floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f), floatArrayOf(0.5f, 0.5f, 0.5f),
        ) + List(14) { floatArrayOf(random.nextFloat(), random.nextFloat(), random.nextFloat()) }
        assertEquals(20, colors.size)
        for (rgb in colors) {
            val a = ours.toCIEXYZ(rgb)
            val b = reference.toCIEXYZ(rgb)
            for (c in 0 until 3) assertTrue("${rgb.toList()} ch$c: ${a[c]} vs ${b[c]}", abs(a[c] - b[c]) < 0.01f)
        }
    }

    @Test
    fun bytes_cachedContentButDefensivelyCopied() {
        val first = SrgbIccProfile.bytes
        val second = SrgbIccProfile.bytes
        assertNotSame(first, second)
        assertArrayEquals(first, second)
        first[0] = 0x7F
        assertArrayEquals(second, SrgbIccProfile.bytes)
    }

    private fun sig(offset: Int): String = String(bytes, offset, 4, Charsets.US_ASCII)
}
