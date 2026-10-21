package com.akane.akastudio.core.curator.ml

import android.content.res.AssetManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.akane.akastudio.core.curator.DeviceScenes
import com.akane.akastudio.core.curator.logTiming
import com.akane.akastudio.core.curator.logValue
import com.akane.akastudio.core.curator.timed
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real NIMA inference on LiteRT. The production scorer ([LiteRtNimaScorer.create] with the bundled asset) must
 * produce scores in [1, 10] deterministically. A second scorer runs the production threading and fallback code over
 * a recording LiteRT backend on the same model, which exposes the raw 10-bin distribution and the thread each model
 * operation ran on.
 */
@RunWith(AndroidJUnit4::class)
class NimaDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var scorer: LiteRtNimaScorer

    @Before
    fun setUp() {
        ColdLoad.ensureMeasured(context)
        scorer = LiteRtNimaScorer.create(context)
    }

    @After
    fun tearDown() {
        scorer.close()
    }

    @Test
    fun scoresAreInRangeDeterministicAndInputDependent() = runBlocking {
        val kinds = DeviceScenes.Kind.entries
        val scores = kinds.associateWith { kind ->
            val bitmap = DeviceScenes.scene(kind, seed = 11)
            val first = scorer.score(bitmap)
            assertNotNull("NIMA returned null for $kind (aesthetics model unavailable)", first)
            first!!
            assertTrue("$kind score $first outside [1, 10]", first in NimaMath.MIN_SCORE..NimaMath.MAX_SCORE)
            repeat(2) {
                val again = scorer.score(bitmap)!!
                assertEquals("repeat score of $kind", first, again, 1e-4f)
            }
            first
        }
        logValue("nima.scores", scores)

        // Negative control: a constant scorer would also be "deterministic"; distinct scenes must score differently.
        val distinct = scores.values.map { (it * 1e3f).toInt() }.toSet().size
        assertTrue("NIMA gave the same score to every scene: $scores", distinct >= kinds.size / 2)

        val timing = DeviceScenes.scene(DeviceScenes.Kind.CITY, seed = 5)
        scorer.score(timing)
        val runs = 10
        val (_, ms) = timed { repeat(runs) { scorer.score(timing) } }
        logTiming("infer.nima_aesthetic_fp16.per_image", ms / runs)
    }

    @Test
    fun distributionSumsToOneAndRunsOnTheNimaThread() = runBlocking {
        val backends = Collections.synchronizedList(mutableListOf<RecordingBackend>())
        val compileThreads = Collections.synchronizedList(mutableListOf<String>())
        val recording = LiteRtNimaScorer.create { accelerator ->
            compileThreads += Thread.currentThread().name
            RecordingBackend.compile(context.assets, accelerator).also { backends += it }
        }
        try {
            for (kind in listOf(DeviceScenes.Kind.SUNSET, DeviceScenes.Kind.TABLE, DeviceScenes.Kind.STRIPES)) {
                val bitmap = DeviceScenes.scene(kind, seed = 3)
                val score = recording.score(bitmap)
                assertNotNull("recording scorer returned null for $kind", score)
                val live = backends.last()
                val bins = checkNotNull(live.lastBins) { "no inference recorded" }
                logValue("nima.bins.$kind[${live.accelerator}]", bins.joinToString { "%.4f".format(it) })

                assertEquals(NimaMath.BIN_COUNT, bins.size)
                for (p in bins) assertTrue("bin $p outside [0, 1]", p in 0f..1f)
                assertEquals("10-bin distribution sum for $kind", 1.0, bins.sumOf { it.toDouble() }, 1e-2)
                assertEquals("score is the mean of the recorded bins", NimaMath.meanScore(bins), score!!, 1e-6f)

                // The production scorer (default compile path) scores the same image within accelerator rounding.
                val production = scorer.score(bitmap)!!
                assertEquals("production vs recording score for $kind", score, production, 0.05f)
            }
            logValue("nima.accelerators", backends.map { it.accelerator })
            assertTrue("compile ran off the scorer thread: $compileThreads", compileThreads.all { it.startsWith(THREAD_PREFIX) })
            val runThreads = backends.flatMap { it.runThreads }
            assertTrue("no inference ran", runThreads.isNotEmpty())
            assertTrue("inference ran off the scorer thread: $runThreads", runThreads.all { it.startsWith(THREAD_PREFIX) })
            // Negative control: the caller (instrumentation) thread is not the scorer thread.
            assertTrue(!Thread.currentThread().name.startsWith(THREAD_PREFIX))
        } finally {
            recording.close()
        }
    }

    /** A LiteRT [NimaBackend] identical to the production one, recording bins and threads. */
    private class RecordingBackend private constructor(
        val accelerator: NimaAccelerator,
        private val model: CompiledModel,
        private val inputs: List<TensorBuffer>,
        private val outputs: List<TensorBuffer>,
        override val inputShape: List<Int>,
        override val outputShape: List<Int>,
    ) : NimaBackend {
        val runThreads: MutableList<String> = Collections.synchronizedList(mutableListOf())

        @Volatile
        var lastBins: FloatArray? = null

        override fun run(input: FloatArray): FloatArray {
            runThreads += Thread.currentThread().name
            inputs[0].writeFloat(input)
            model.run(inputs, outputs)
            return outputs[0].readFloat().also { lastBins = it.copyOf() }
        }

        override fun close() {
            inputs.forEach { it.close() }
            outputs.forEach { it.close() }
            model.close()
        }

        companion object {
            fun compile(assets: AssetManager, accelerator: NimaAccelerator): RecordingBackend {
                val liteRt = when (accelerator) {
                    NimaAccelerator.GPU -> Accelerator.GPU
                    NimaAccelerator.CPU -> Accelerator.CPU
                }
                val model = CompiledModel.create(assets, ModelManifest.NIMA_AESTHETIC.assetPath, CompiledModel.Options(liteRt))
                try {
                    val inputShape = checkNotNull(model.getInputTensorType("keras_tensor", "serving_default").layout).dimensions
                    val outputShape = checkNotNull(model.getOutputTensorType("output_0", "serving_default").layout).dimensions
                    return RecordingBackend(
                        accelerator, model, model.createInputBuffers(), model.createOutputBuffers(), inputShape, outputShape,
                    )
                } catch (e: Throwable) {
                    model.close()
                    throw e
                }
            }
        }
    }

    private companion object {
        const val THREAD_PREFIX = "nima-litert"
    }
}
