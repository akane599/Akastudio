package com.akane.akastudio.core.curator.ml

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.akane.akastudio.core.curator.DeviceScenes
import com.akane.akastudio.core.curator.logTiming
import com.akane.akastudio.core.curator.logValue
import com.akane.akastudio.core.curator.timed
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real MediaPipe FaceLandmarker on device-drawn images. */
@RunWith(AndroidJUnit4::class)
class FaceLandmarkerDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun measureColdLoad() = ColdLoad.ensureMeasured(context)

    @Test
    fun facelessScenesYieldNoFacesWithoutThrowing() {
        // The raw task proves no exception is thrown: the adapter would swallow one into an empty list.
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(ModelManifest.FACE_LANDMARKER.assetPath).build())
            .setRunningMode(RunningMode.IMAGE)
            .setNumFaces(MediaPipeFaceDetector.MAX_FACES)
            .setOutputFaceBlendshapes(true)
            .build()
        val landmarker = FaceLandmarker.createFromOptions(context, options)
        try {
            for (kind in DeviceScenes.Kind.entries) {
                val result = landmarker.detect(BitmapImageBuilder(DeviceScenes.scene(kind, seed = 4)).build())
                assertEquals("raw landmarker faces on $kind", 0, result.faceLandmarks().size)
            }
        } finally {
            landmarker.close()
        }

        val detector = MediaPipeFaceDetector.create(context, Dispatchers.Default)
        try {
            for (kind in DeviceScenes.Kind.entries) {
                val faces = runBlocking { detector.detect(DeviceScenes.scene(kind, seed = 4)) }
                assertEquals("adapter faces on $kind", emptyList<DetectedFace>(), faces)
            }
            val bitmap = DeviceScenes.scene(DeviceScenes.Kind.BEACH, seed = 4)
            runBlocking { detector.detect(bitmap) }
            val runs = 10
            val (_, ms) = timed { repeat(runs) { runBlocking { detector.detect(bitmap) } } }
            logTiming("infer.face_landmarker.per_image", ms / runs)
        } finally {
            detector.close()
        }
    }

    @Test
    fun drawnPortraitYieldsOneOpenEyedFaceOverTheHead() {
        val detector = MediaPipeFaceDetector.create(context, Dispatchers.Default)
        try {
            val faces = runBlocking { detector.detect(DeviceScenes.face(seed = 9)) }
            logValue("face.portrait", faces)
            assertTrue("expected at least one face on the drawn portrait, got $faces", faces.isNotEmpty())
            val face = faces.first()
            // The head ellipse spans x 0.23..0.77 and y 0.17..0.69 of the frame; its centre is (0.5, 0.43).
            assertTrue("face box ${face.box} misses the head centre", face.box.left < 0.5f && face.box.right > 0.5f)
            assertTrue("face box ${face.box} misses the head centre", face.box.top < 0.43f && face.box.bottom > 0.43f)
            assertTrue("drawn eyes are open: blink ${face.leftBlink}/${face.rightBlink}", face.leftBlink < 0.5f && face.rightBlink < 0.5f)
        } finally {
            detector.close()
        }
    }
}
