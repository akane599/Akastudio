package com.akane.akastudio.core.curator.ml

import com.akane.akastudio.core.color.api.SceneType
import com.akane.akastudio.core.curator.ml.SyntheticImages.beach
import com.akane.akastudio.core.curator.ml.SyntheticImages.forest
import com.akane.akastudio.core.curator.ml.SyntheticImages.paint
import com.akane.akastudio.core.curator.ml.SyntheticImages.rgb
import com.akane.akastudio.core.curator.ml.SyntheticImages.solid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeuristicSceneClassifierTest {

    @Test
    fun blueTopAndSandyBottomIsBeach() {
        assertEquals(SceneType.BEACH_SEA, HeuristicSceneClassifier.classify(beach()))
    }

    @Test
    fun blueTopWithoutSandIsNotBeach() {
        val image = paint(90, 90) { _, y -> if (y < 30) rgb(110, 175, 235) else rgb(128, 128, 128) }
        assertEquals(SceneType.GENERAL, HeuristicSceneClassifier.classify(image))
    }

    @Test
    fun darkFrameWithPointLightsIsNightCity() {
        // One light in every 10x10 block: 1% of pixels at L ~ 1.
        val image = paint(200, 100) { x, y -> if (x % 10 == 0 && y % 10 == 0) rgb(255, 250, 235) else rgb(22, 22, 34) }
        assertEquals(SceneType.NIGHT_CITY, HeuristicSceneClassifier.classify(image))
    }

    @Test
    fun darkFrameWithoutLightsIsNotNightCity() {
        assertEquals(SceneType.GENERAL, HeuristicSceneClassifier.classify(solid(100, 100, rgb(22, 22, 34))))
    }

    @Test
    fun pointLightsAreNotLostOnLargeStrideSampledImages() {
        // 1200x1200 exceeds the sample budget; 3x3 light blocks every 30 px still cover 1% of the frame.
        val image = paint(1200, 1200) { x, y ->
            if (x % 30 < 3 && y % 30 < 3) rgb(255, 255, 255) else rgb(20, 20, 28)
        }
        assertEquals(SceneType.NIGHT_CITY, HeuristicSceneClassifier.classify(image))
    }

    @Test
    fun mostlyGreenIsNatureForest() {
        assertEquals(SceneType.NATURE_FOREST, HeuristicSceneClassifier.classify(forest()))
    }

    @Test
    fun greenBelowThirtyFivePercentIsNotForest() {
        val image = paint(100, 100) { x, _ -> if (x < 30) rgb(40, 125, 45) else rgb(128, 128, 128) }
        assertEquals(SceneType.GENERAL, HeuristicSceneClassifier.classify(image))
    }

    @Test
    fun warmLowKeyIsCafeInterior() {
        val image = paint(120, 90) { x, y -> if ((x + y) % 7 == 0) rgb(150, 100, 55) else rgb(105, 68, 40) }
        assertEquals(SceneType.CAFE_INTERIOR, HeuristicSceneClassifier.classify(image))
    }

    @Test
    fun brightWarmFrameIsNotCafe() {
        assertEquals(SceneType.GENERAL, HeuristicSceneClassifier.classify(solid(50, 50, rgb(235, 190, 140))))
    }

    @Test
    fun neutralGrayIsGeneral() {
        assertEquals(SceneType.GENERAL, HeuristicSceneClassifier.classify(solid(64, 48, rgb(128, 128, 128))))
    }

    @Test
    fun neverReturnsPortraitOrArchitecture() {
        val images = listOf(beach(), forest(), solid(8, 8, rgb(200, 150, 140)), solid(1, 1, rgb(0, 0, 0)))
        for (image in images) {
            val scene = HeuristicSceneClassifier.classify(image)
            assertTrue(scene != SceneType.PORTRAIT && scene != SceneType.ARCHITECTURE_AUTO)
        }
    }

    @Test
    fun meanLMatchesOkLabLightness() {
        assertEquals(0f, HeuristicSceneClassifier.meanL(solid(4, 4, rgb(0, 0, 0))), 1e-4f)
        assertEquals(1f, HeuristicSceneClassifier.meanL(solid(4, 4, rgb(255, 255, 255))), 1e-3f)
    }
}
