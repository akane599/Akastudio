package com.akane.akastudio.core.color.render.grade

import com.akane.akastudio.core.color.look.LutBaker
import com.akane.akastudio.core.color.render.GraderCapabilities
import com.akane.akastudio.core.color.render.GraderChoice
import com.akane.akastudio.core.color.render.chooseGrader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraderSelectionTest {

    @Test
    fun floatBuffersAndTexture3DSelectTheFullShader() {
        assertEquals(GraderChoice.SHADER_FLOAT, chooseGrader(GraderCapabilities(floatColorBuffer = true, texture3D = true)))
    }

    @Test
    fun texture3DWithoutFloatBuffersSelectsTheReducedShader() {
        assertEquals(GraderChoice.SHADER_REDUCED, chooseGrader(GraderCapabilities(floatColorBuffer = false, texture3D = true)))
    }

    @Test
    fun withoutTexture3DTheIdentityGraderIsTheLastResort() {
        assertEquals(GraderChoice.IDENTITY, chooseGrader(GraderCapabilities(floatColorBuffer = true, texture3D = false)))
        assertEquals(GraderChoice.IDENTITY, chooseGrader(GraderCapabilities(floatColorBuffer = false, texture3D = false)))
    }

    @Test
    fun texture3DNeedsRoomForTheLookLattice() {
        assertFalse(GraderCapabilities.of(floatColorBuffer = true, max3DTextureSize = 0).texture3D)
        assertFalse(GraderCapabilities.of(floatColorBuffer = true, max3DTextureSize = LutBaker.SIZE - 1).texture3D)
        assertTrue(GraderCapabilities.of(floatColorBuffer = true, max3DTextureSize = LutBaker.SIZE).texture3D)
        assertTrue(GraderCapabilities.of(floatColorBuffer = false, max3DTextureSize = 256).texture3D)
    }

    @Test
    fun variantsStoreFloatOrSrgbEightBit() {
        assertEquals(com.akane.akastudio.core.color.gl.GlFormat.RGBA16F, GradeVariant.FLOAT.format)
        assertEquals(com.akane.akastudio.core.color.gl.GlFormat.SRGB8_ALPHA8, GradeVariant.REDUCED.format)
    }
}
