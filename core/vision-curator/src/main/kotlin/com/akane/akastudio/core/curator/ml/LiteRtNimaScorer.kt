package com.akane.akastudio.core.curator.ml

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import com.google.ai.edge.litert.TensorType
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking

/** Accelerator a [NimaBackend] is compiled for, in preference order. */
internal enum class NimaAccelerator { GPU, CPU }

/**
 * One compiled NIMA model with its I/O buffers. Not thread-safe, and bound to the thread that compiled it:
 * LiteRT's GPU (OpenGL) path must run on the thread that initialised it.
 */
internal interface NimaBackend {
    /** Runs one inference on [input] (NHWC 1×224×224×3 floats in [−1, 1]) and returns the raw output values. */
    fun run(input: FloatArray): FloatArray

    fun close()
}

/**
 * NIMA aesthetic scorer on the LiteRT CompiledModel API (GPU first, CPU fallback).
 *
 * Input is the centre square of the bitmap scaled bilinearly to 224×224, NHWC float in [−1, 1]; the output
 * is the 10-bin score distribution, reduced by [NimaMath.meanScore]. Any per-image failure yields `null`.
 *
 * Every model operation (compile, probe run, inference, release) runs on one private daemon thread, so a
 * GPU model is always run on the thread that created it. When a GPU model fails at run time it is closed
 * and the model is recompiled on CPU on that same thread; the failed image is retried once and the scorer
 * stays on CPU from then on.
 */
internal class LiteRtNimaScorer private constructor(
    private val session: SerializedResource<Engine>,
) : AestheticScorer, AutoCloseable {

    override suspend fun score(bitmap: Bitmap): Float? = scoreOrNull {
        BitmapSupport.centerCropPixels(bitmap, NimaMath.INPUT_EDGE)
    }

    /** Scores a centre-cropped [NimaMath.INPUT_EDGE]² ARGB image; `null` when inference is unavailable or fails. */
    internal suspend fun scorePixels(argb: IntArray): Float? = scoreOrNull { argb }

    private suspend inline fun scoreOrNull(pixels: () -> IntArray): Float? = try {
        val argb = pixels()
        session.withResource { it.score(argb) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "NIMA inference failed; aesthetic score unavailable", e)
        null
    }

    /** Releases the model on its own thread, then stops that thread. Never blocks. */
    override fun close() = session.close()

    /**
     * The live backend and its fallback state. Confined to the scorer thread: only touched inside
     * [SerializedResource.withResource] and by the release task, all of which run on that thread.
     */
    internal class Engine(
        private var backend: NimaBackend?,
        private var accelerator: NimaAccelerator,
        private val compile: (NimaAccelerator) -> NimaBackend,
    ) {
        private val input = FloatArray(INPUT_FLOATS)

        /**
         * Mean score of [argb], or `null` once no backend is left. Throws when the image's inference fails;
         * on GPU, a failure (including an output that is not a valid distribution) first triggers the CPU retry.
         */
        fun score(argb: IntArray): Float? {
            NimaMath.preprocess(argb, input)
            val current = backend ?: return null
            return try {
                infer(current)
            } catch (e: Exception) {
                if (accelerator != NimaAccelerator.GPU) throw e
                val cpu = fallBackToCpu(e) ?: return null
                infer(cpu)
            }
        }

        private fun infer(backend: NimaBackend): Float = NimaMath.meanScore(runChecked(backend, input))

        /** Closes the GPU model and recompiles on CPU; `null` (and no backend from then on) when that fails. */
        private fun fallBackToCpu(cause: Exception): NimaBackend? {
            Log.w(TAG, "NIMA GPU inference failed; recompiling on CPU for the rest of this session", cause)
            accelerator = NimaAccelerator.CPU
            val gpu = backend
            backend = null
            gpu?.let(::closeLogged)
            val cpu = try {
                compile(NimaAccelerator.CPU)
            } catch (e: Throwable) {
                if (!isRecoverable(e)) throw e
                Log.w(TAG, "NIMA CPU recompile failed; aesthetic scores unavailable", e)
                return null
            }
            backend = cpu
            return cpu
        }

        fun close() {
            val current = backend
            backend = null
            current?.close()
        }
    }

    companion object {
        private const val TAG = "NimaScorer"
        private const val THREAD_NAME = "nima-litert"

        /** Signature and tensor names read from the pinned model's flatbuffer. */
        private const val SIGNATURE = "serving_default"
        private const val INPUT_NAME = "keras_tensor"
        private const val OUTPUT_NAME = "output_0"

        private val INPUT_SHAPE = listOf(1, NimaMath.INPUT_EDGE, NimaMath.INPUT_EDGE, 3)
        private const val INPUT_FLOATS = NimaMath.INPUT_EDGE * NimaMath.INPUT_EDGE * 3

        /** Compiles the pinned model (GPU, then CPU) and verifies its tensor contract; throws when nothing usable loads. */
        fun create(context: Context): LiteRtNimaScorer {
            val assets = context.assets
            return create { accelerator -> LiteRtBackend.compile(assets, accelerator) }
        }

        /**
         * Starts the scorer thread and, on it, compiles and probes a backend per accelerator in [NimaAccelerator]
         * order until one works. Throws [IllegalStateException] (with the thread stopped) when none does.
         */
        internal fun create(compile: (NimaAccelerator) -> NimaBackend): LiteRtNimaScorer {
            val dispatcher = Executors.newSingleThreadExecutor { r ->
                Thread(r, THREAD_NAME).apply { isDaemon = true }
            }.asCoroutineDispatcher()
            val compiled = AtomicReference<Engine?>()
            val engine = try {
                runBlocking(dispatcher) { compileFirstUsable(compile).also(compiled::set) }
            } catch (e: Throwable) {
                // An interrupted wait cancels the coroutine, but the compile is not cancellable: it runs to the end
                // on the scorer thread and its engine is dropped with the cancelled result. The executor is FIFO,
                // so this task runs after that compile and releases whatever engine it produced.
                dispatcher.executor.execute { compiled.get()?.let(::closeEngineLogged) }
                dispatcher.close()
                throw e
            }
            return LiteRtNimaScorer(SerializedResource(engine, dispatcher) { releaseOn(dispatcher, it) })
        }

        private fun compileFirstUsable(compile: (NimaAccelerator) -> NimaBackend): Engine {
            var lastFailure: Throwable? = null
            for (accelerator in NimaAccelerator.entries) {
                val backend = try {
                    compile(accelerator)
                } catch (e: Throwable) {
                    if (!isRecoverable(e)) throw e
                    Log.i(TAG, "NIMA $accelerator compile failed", e)
                    lastFailure = e
                    continue
                }
                try {
                    // Probe run: proves the compiled graph executes and yields exactly 10 bins.
                    runChecked(backend, FloatArray(INPUT_FLOATS))
                    return Engine(backend, accelerator, compile)
                } catch (e: Throwable) {
                    closeLogged(backend)
                    if (!isRecoverable(e)) throw e
                    Log.i(TAG, "NIMA $accelerator probe run rejected", e)
                    lastFailure = e
                }
            }
            throw IllegalStateException("NIMA model unavailable", lastFailure)
        }

        /** Queues the engine's release on the scorer thread, then shuts that thread down once the release ran. */
        private fun releaseOn(dispatcher: ExecutorCoroutineDispatcher, engine: Engine) {
            dispatcher.executor.execute {
                try {
                    closeEngineLogged(engine)
                } finally {
                    dispatcher.close()
                }
            }
        }

        /** Never throws: an Error escaping a task on the daemon scorer thread would kill the process. */
        private fun closeEngineLogged(engine: Engine) {
            try {
                engine.close()
            } catch (t: Throwable) {
                Log.w(TAG, "NIMA model release failed", t)
            }
        }

        private fun runChecked(backend: NimaBackend, input: FloatArray): FloatArray {
            val bins = backend.run(input)
            check(bins.size == NimaMath.BIN_COUNT) { "output has ${bins.size} values, expected ${NimaMath.BIN_COUNT}" }
            return bins
        }

        private fun closeLogged(backend: NimaBackend) {
            try {
                backend.close()
            } catch (e: Exception) {
                Log.w(TAG, "NIMA backend close failed", e)
            }
        }

        /**
         * Native runtimes fail with exceptions and with linkage errors (UnsatisfiedLinkError for a missing
         * delegate library); both mean "try the next accelerator". Other errors propagate.
         */
        private fun isRecoverable(e: Throwable): Boolean = e is Exception || e is LinkageError
    }

    /** [NimaBackend] over a LiteRT [CompiledModel] and its reusable single input and output buffers. */
    private class LiteRtBackend(
        private val model: CompiledModel,
        private val inputs: List<TensorBuffer>,
        private val outputs: List<TensorBuffer>,
    ) : NimaBackend {

        override fun run(input: FloatArray): FloatArray {
            inputs[0].writeFloat(input)
            model.run(inputs, outputs)
            return outputs[0].readFloat()
        }

        override fun close() {
            inputs.forEach { it.close() }
            outputs.forEach { it.close() }
            model.close()
        }

        companion object {
            /** Compiles the model for [accelerator] and verifies its tensor contract; closes everything it opened on failure. */
            fun compile(assets: AssetManager, accelerator: NimaAccelerator): NimaBackend {
                val model = CompiledModel.create(
                    assets,
                    ModelManifest.NIMA_AESTHETIC.assetPath,
                    CompiledModel.Options(accelerator.toLiteRt()),
                )
                var inputs: List<TensorBuffer> = emptyList()
                var outputs: List<TensorBuffer> = emptyList()
                try {
                    verifyTensor("input", model.getInputTensorType(INPUT_NAME, SIGNATURE), INPUT_SHAPE)
                    verifyTensor("output", model.getOutputTensorType(OUTPUT_NAME, SIGNATURE), null)
                    inputs = model.createInputBuffers()
                    outputs = model.createOutputBuffers()
                    check(inputs.size == 1 && outputs.size == 1) {
                        "expected 1 input and 1 output buffer, got ${inputs.size} and ${outputs.size}"
                    }
                    return LiteRtBackend(model, inputs, outputs)
                } catch (e: Throwable) {
                    for (closeable in inputs + outputs) {
                        try {
                            closeable.close()
                        } catch (suppressed: Exception) {
                            e.addSuppressed(suppressed)
                        }
                    }
                    try {
                        model.close()
                    } catch (suppressed: Exception) {
                        e.addSuppressed(suppressed)
                    }
                    throw e
                }
            }

            private fun NimaAccelerator.toLiteRt(): Accelerator = when (this) {
                NimaAccelerator.GPU -> Accelerator.GPU
                NimaAccelerator.CPU -> Accelerator.CPU
            }

            /** Element type must be FLOAT; shape must equal [expectedShape], or (output) hold exactly 10 values. */
            private fun verifyTensor(role: String, type: TensorType, expectedShape: List<Int>?) {
                check(type.elementType == TensorType.ElementType.FLOAT) { "$role tensor is ${type.elementType}, expected FLOAT" }
                val dims = checkNotNull(type.layout) { "$role tensor reports no layout" }.dimensions
                if (expectedShape != null) {
                    check(dims == expectedShape) { "$role shape $dims, expected $expectedShape" }
                } else {
                    val count = dims.fold(1L) { acc, d -> acc * d }
                    check(dims.isNotEmpty() && dims.all { it > 0 } && count == NimaMath.BIN_COUNT.toLong()) {
                        "$role shape $dims does not hold ${NimaMath.BIN_COUNT} bins"
                    }
                }
            }
        }
    }
}
