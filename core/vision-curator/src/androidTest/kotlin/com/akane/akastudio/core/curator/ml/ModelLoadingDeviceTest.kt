package com.akane.akastudio.core.curator.ml

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.akane.akastudio.core.curator.DeviceScenes
import com.akane.akastudio.core.curator.logTiming
import com.akane.akastudio.core.curator.logValue
import com.akane.akastudio.core.curator.timed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every bundled model loads from the APK assets through the production adapters, and the production
 * factory assembles the full on-device set with no slot degraded to its fallback.
 */
@RunWith(AndroidJUnit4::class)
class ModelLoadingDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun measureColdLoad() = ColdLoad.ensureMeasured(context)

    @Test
    fun eachModelLoadsFromAssetsAndTimesItsLoad() {
        // Warm reloads: native libraries are already loaded, so this is the per-create model cost.
        repeat(2) { pass ->
            val label = "warm${pass + 1}"
            val (faces, faceMs) = timed { MediaPipeFaceDetector.create(context, Dispatchers.Default) }
            faces.close()
            logTiming("load.$label.face_landmarker", faceMs)

            val (embedder, embedMs) = timed { MediaPipeEmbedder.create(context, Dispatchers.Default) }
            assertEquals("mobilenet_v3_small embedder dimension", 1024, embedder.dimension)
            embedder.close()
            logTiming("load.$label.mobilenet_v3_small", embedMs)

            val (classifier, classifyMs) = timed { MediaPipeSceneClassifier.create(context, Dispatchers.Default) }
            classifier.close()
            logTiming("load.$label.efficientnet_lite0", classifyMs)

            val (nima, nimaMs) = timed { LiteRtNimaScorer.create(context) }
            nima.close()
            logTiming("load.$label.nima_aesthetic_fp16", nimaMs)
        }
    }

    @Test
    fun factoryLoadsEveryRealModelWithNoFallback() {
        val (models, factoryMs) = timed { CuratorModelsFactory.create(context) }
        logTiming("load.factory.all_four", factoryMs)
        models.use {
            assertTrue("no slot may run on its fallback, degraded=${it.degraded}", it.degraded.isEmpty())
            assertTrue("faces: ${it.faces.javaClass.name}", it.faces is MediaPipeFaceDetector)
            assertTrue("embedder: ${it.embedder.javaClass.name}", it.embedder is MediaPipeEmbedder)
            assertTrue("aesthetics: ${it.aesthetics.javaClass.name}", it.aesthetics is LiteRtNimaScorer)
            assertTrue("scenes: ${it.scenes.javaClass.name}", it.scenes is MediaPipeSceneClassifier)
            assertEquals(1024, it.embedder.dimension)

            // The aesthetics slot must be the live LiteRT model: a real score, not the "unavailable" null.
            val bitmap = DeviceScenes.scene(DeviceScenes.Kind.FLOWERS, seed = 1)
            val score = runBlocking { it.aesthetics.score(bitmap) }
            logValue("factory.nima.flowers", score)
            assertNotNull("the NIMA scorer returned null (aesthetics model unavailable)", score)
        }
    }
}
