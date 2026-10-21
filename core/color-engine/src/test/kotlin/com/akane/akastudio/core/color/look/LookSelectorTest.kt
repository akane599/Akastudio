package com.akane.akastudio.core.color.look

import com.akane.akastudio.core.color.api.MasterLook
import com.akane.akastudio.core.color.api.SceneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LookSelectorTest {

    private fun select(scene: SceneType, cct: Float = 5500f, meanL: Float = 0.5f, face: Boolean = false) =
        LookSelector.select(scene, cct, meanL, face)

    @Test
    fun fixedScenes() {
        for (face in listOf(false, true)) {
            assertEquals(MasterLook.MEDITERRANEAN_AZURE, select(SceneType.BEACH_SEA, face = face))
            assertEquals(MasterLook.CINESTILL_800T_NIGHT, select(SceneType.NIGHT_CITY, face = face))
            assertEquals(MasterLook.ESPRESSO_WARM_WOOD, select(SceneType.CAFE_INTERIOR, face = face))
            assertEquals(MasterLook.PORTRA_400_GOLDEN, select(SceneType.PORTRAIT, face = face))
            assertEquals(MasterLook.NORDIC_SLATE_MATTE, select(SceneType.ARCHITECTURE_AUTO, face = face))
        }
    }

    @Test
    fun forest_switchesToNordicAboveCct6800() {
        assertEquals(MasterLook.KODACHROME_64_CLASSIC, select(SceneType.NATURE_FOREST, cct = 5000f))
        assertEquals(MasterLook.KODACHROME_64_CLASSIC, select(SceneType.NATURE_FOREST, cct = 6800f))
        assertEquals(MasterLook.NORDIC_SLATE_MATTE, select(SceneType.NATURE_FOREST, cct = 6801f))
        assertEquals(MasterLook.NORDIC_SLATE_MATTE, select(SceneType.NATURE_FOREST, cct = 9000f))
    }

    @Test
    fun general_rules() {
        assertEquals(MasterLook.PORTRA_400_GOLDEN, select(SceneType.GENERAL, cct = 6500f, face = true))
        assertEquals(MasterLook.PORTRA_400_GOLDEN, select(SceneType.GENERAL, cct = 3000f, meanL = 0.1f, face = true))
        assertEquals(MasterLook.PORTRA_400_GOLDEN, select(SceneType.GENERAL, cct = 4100f, meanL = 0.5f))
        assertEquals(MasterLook.PORTRA_400_GOLDEN, select(SceneType.GENERAL, cct = 3500f, meanL = 0.30f))
        assertEquals(MasterLook.PORTRA_400_GOLDEN, select(SceneType.GENERAL, cct = 4100f, meanL = 0.1f))
        assertEquals(MasterLook.CINESTILL_800T_NIGHT, select(SceneType.GENERAL, cct = 3500f, meanL = 0.2f))
        assertEquals(MasterLook.PURE_NATURAL_PRO, select(SceneType.GENERAL, cct = 4200f, meanL = 0.5f))
        assertEquals(MasterLook.PURE_NATURAL_PRO, select(SceneType.GENERAL, cct = 6500f, meanL = 0.1f))
        assertEquals(MasterLook.PURE_NATURAL_PRO, select(SceneType.GENERAL, cct = Float.NaN, meanL = Float.NaN))
    }

    @Test
    fun neverSelectsMonochrome() {
        for (scene in SceneType.entries) {
            for (cct in floatArrayOf(2000f, 3900f, 4100f, 5000f, 6900f, 12000f)) {
                for (meanL in floatArrayOf(0.05f, 0.29f, 0.5f, 0.95f)) {
                    for (face in listOf(false, true)) {
                        assertNotEquals(MasterLook.VELVET_MONOCHROME_HP5, select(scene, cct, meanL, face))
                    }
                }
            }
        }
    }
}
