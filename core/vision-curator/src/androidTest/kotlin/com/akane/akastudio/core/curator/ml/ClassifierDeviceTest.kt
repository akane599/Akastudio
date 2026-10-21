package com.akane.akastudio.core.curator.ml

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.akane.akastudio.core.color.api.SceneType
import com.akane.akastudio.core.curator.DeviceScenes
import com.akane.akastudio.core.curator.logTiming
import com.akane.akastudio.core.curator.logValue
import com.akane.akastudio.core.curator.timed
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageclassifier.ImageClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real EfficientNet-Lite0 classification with the production options, and the production scene adapter on top. */
@RunWith(AndroidJUnit4::class)
class ClassifierDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun measureColdLoad() = ColdLoad.ensureMeasured(context)

    @Test
    fun topKLabelsHaveScoresInUnitRange() {
        val options = ImageClassifier.ImageClassifierOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(ModelManifest.IMAGE_CLASSIFIER.assetPath).build())
            .setRunningMode(RunningMode.IMAGE)
            .setMaxResults(MediaPipeSceneClassifier.MAX_RESULTS)
            .setScoreThreshold(MediaPipeSceneClassifier.SCORE_THRESHOLD)
            .build()
        val classifier = ImageClassifier.createFromOptions(context, options)
        var labelled = 0
        try {
            for (kind in DeviceScenes.Kind.entries) {
                val bitmap = DeviceScenes.scene(kind, seed = 2)
                val categories = classifier.classify(BitmapImageBuilder(bitmap).build())
                    .classificationResult().classifications().first().categories()
                val labels = categories.map { it.categoryName() to it.score() }
                logValue("classify.$kind", labels.joinToString { "%s=%.3f".format(it.first, it.second) })
                assertTrue("$kind: ${labels.size} labels exceed top-${MediaPipeSceneClassifier.MAX_RESULTS}", labels.size <= MediaPipeSceneClassifier.MAX_RESULTS)
                for ((name, score) in labels) {
                    assertTrue("$kind: blank label", name.isNotBlank())
                    assertTrue("$kind: $name score $score outside [0, 1]", score in 0f..1f)
                    assertTrue("$kind: $name score $score below the threshold", score >= MediaPipeSceneClassifier.SCORE_THRESHOLD)
                }
                val scores = labels.map { it.second }
                assertTrue("$kind: labels not ranked by score: $scores", scores == scores.sortedDescending())
                if (labels.isNotEmpty()) labelled++
            }
            // A classifier that never clears the threshold would vacuously pass the loop above.
            assertTrue("only $labelled of ${DeviceScenes.Kind.entries.size} scenes got any label", labelled >= DeviceScenes.Kind.entries.size / 2)

            val bitmap = DeviceScenes.scene(DeviceScenes.Kind.FLOWERS, seed = 2)
            classifier.classify(BitmapImageBuilder(bitmap).build())
            val runs = 10
            val (_, ms) = timed { repeat(runs) { classifier.classify(BitmapImageBuilder(bitmap).build()) } }
            logTiming("infer.efficientnet_lite0.per_image", ms / runs)
        } finally {
            classifier.close()
        }
    }

    @Test
    fun sceneAdapterNeverReturnsPortrait() {
        val adapter = MediaPipeSceneClassifier.create(context, Dispatchers.Default)
        try {
            for (kind in DeviceScenes.Kind.entries) {
                val bitmap = DeviceScenes.scene(kind, seed = 2)
                val scene = runBlocking { adapter.classify(bitmap, BitmapSupport.pixelsOf(bitmap)) }
                logValue("scene.$kind", scene)
                assertNotEquals("$kind", SceneType.PORTRAIT, scene)
            }
        } finally {
            adapter.close()
        }
    }
}
