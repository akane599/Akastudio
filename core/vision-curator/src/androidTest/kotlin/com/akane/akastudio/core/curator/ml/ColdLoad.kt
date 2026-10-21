package com.akane.akastudio.core.curator.ml

import android.content.Context
import com.akane.akastudio.core.curator.logTiming
import com.akane.akastudio.core.curator.timed
import kotlinx.coroutines.Dispatchers

/**
 * Process-cold model load times. Every model-loading test class calls [ensureMeasured] before it loads anything, so
 * whichever class the runner starts first measures each adapter's first create in this process, including the
 * one-time native library load, and logs it as `load.cold.<model>`.
 */
internal object ColdLoad {

    @Volatile
    private var measured = false

    @Synchronized
    fun ensureMeasured(context: Context) {
        if (measured) return
        measured = true
        val (faces, faceMs) = timed { MediaPipeFaceDetector.create(context, Dispatchers.Default) }
        faces.close()
        logTiming("load.cold.face_landmarker", faceMs)
        val (embedder, embedMs) = timed { MediaPipeEmbedder.create(context, Dispatchers.Default) }
        embedder.close()
        logTiming("load.cold.mobilenet_v3_small", embedMs)
        val (classifier, classifyMs) = timed { MediaPipeSceneClassifier.create(context, Dispatchers.Default) }
        classifier.close()
        logTiming("load.cold.efficientnet_lite0", classifyMs)
        val (nima, nimaMs) = timed { LiteRtNimaScorer.create(context) }
        nima.close()
        logTiming("load.cold.nima_aesthetic_fp16", nimaMs)
    }
}
