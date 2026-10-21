package com.akane.akastudio.core.curator.ml

import com.akane.akastudio.core.color.api.SceneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageNetSceneMappingTest {

    private val bright = 0.6f
    private val dark = 0.2f

    @Test
    fun tableHitsMapToTheirScenes() {
        assertEquals(SceneType.BEACH_SEA, ImageNetSceneMapping.map(listOf("seashore" to 0.6f), bright))
        assertEquals(SceneType.CAFE_INTERIOR, ImageNetSceneMapping.map(listOf("coffee mug" to 0.4f), bright))
        assertEquals(SceneType.NATURE_FOREST, ImageNetSceneMapping.map(listOf("alp" to 0.3f), bright))
        assertEquals(SceneType.ARCHITECTURE_AUTO, ImageNetSceneMapping.map(listOf("triumphal arch" to 0.5f), bright))
        assertEquals(SceneType.ARCHITECTURE_AUTO, ImageNetSceneMapping.map(listOf("sports car" to 0.5f), bright))
    }

    @Test
    fun scoresAreSummedPerScene() {
        val labels = listOf("cup" to 0.08f, "espresso" to 0.09f, "seashore" to 0.12f)
        assertEquals(SceneType.CAFE_INTERIOR, ImageNetSceneMapping.map(labels, bright))
    }

    @Test
    fun highestSummedSceneWins() {
        val labels = listOf("church" to 0.3f, "seashore" to 0.2f, "breakwater" to 0.2f)
        assertEquals(SceneType.BEACH_SEA, ImageNetSceneMapping.map(labels, bright))
    }

    @Test
    fun belowThresholdAbstains() {
        assertNull(ImageNetSceneMapping.map(listOf("cup" to 0.14f), bright))
        assertEquals(SceneType.CAFE_INTERIOR, ImageNetSceneMapping.map(listOf("cup" to 0.15f), bright))
    }

    @Test
    fun unknownLabelsAbstain() {
        assertNull(ImageNetSceneMapping.map(listOf("tabby" to 0.9f, "golden retriever" to 0.05f), bright))
        assertNull(ImageNetSceneMapping.map(emptyList(), bright))
    }

    @Test
    fun nightLabelsNeedADarkImage() {
        val labels = listOf("traffic light" to 0.4f, "streetcar" to 0.2f)
        assertEquals(SceneType.NIGHT_CITY, ImageNetSceneMapping.map(labels, dark))
        assertEquals(SceneType.GENERAL, ImageNetSceneMapping.map(labels, bright))
        assertEquals(SceneType.GENERAL, ImageNetSceneMapping.map(labels, ImageNetSceneMapping.NIGHT_MEAN_L))
    }

    @Test
    fun onlyTheTopFiveLabelsCount() {
        val labels = listOf(
            "tabby" to 0.2f, "tiger cat" to 0.2f, "egyptian cat" to 0.2f, "lynx" to 0.18f, "persian cat" to 0.17f,
            "seashore" to 0.16f,
        )
        assertNull(ImageNetSceneMapping.map(labels, bright))
        assertEquals(SceneType.BEACH_SEA, ImageNetSceneMapping.map(labels.dropLast(2) + ("seashore" to 0.16f), bright))
    }

    @Test
    fun matchingIsExactAndCaseInsensitive() {
        assertEquals(SceneType.ARCHITECTURE_AUTO, ImageNetSceneMapping.map(listOf("  Sports Car " to 0.5f), bright))
        assertNull(ImageNetSceneMapping.map(listOf("cabbage butterfly" to 0.9f), bright))
    }
}
