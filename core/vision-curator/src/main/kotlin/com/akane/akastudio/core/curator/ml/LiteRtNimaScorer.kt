package com.akane.akastudio.core.curator.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import com.google.ai.edge.litert.TensorType
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher

/**
 * NIMA aesthetic scorer on the LiteRT CompiledModel API (GPU first, CPU fallback).
 *
 * Input is the centre square of the bitmap scaled bilinearly to 224×224, NHWC float in [−1, 1]; the output
 * is the 10-bin score distribution, reduced by [NimaMath.meanScore]. Any per-image failure yields `null`.
 */
internal class LiteRtNimaScorer private constructor(
    private val session: SerializedResource<Session>,
) : AestheticScorer, AutoCloseable {

    /** Compiled model plus its reusable I/O buffers; touched only under the [SerializedResource] lock. */
    internal class Session(
        val model: CompiledModel,
        val inputs: List<TensorBuffer>,
        val outputs: List<TensorBuffer>,
    ) {
        val input = FloatArray(INPUT_FLOATS)

        fun close() {
            inputs.forEach { it.close() }
            outputs.forEach { it.close() }
            model.close()
        }
    }

    override suspend fun score(bitmap: Bitmap): Float? = try {
        session.withResource { s ->
            NimaMath.preprocess(BitmapSupport.centerCropPixels(bitmap, NimaMath.INPUT_EDGE), s.input)
            s.inputs[0].writeFloat(s.input)
            s.model.run(s.inputs, s.outputs)
            NimaMath.meanScore(s.outputs[0].readFloat())
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "NIMA inference failed; aesthetic score unavailable", e)
        null
    }

    override fun close() = session.close()

    companion object {
        private const val TAG = "NimaScorer"

        /** Signature and tensor names read from the pinned model's flatbuffer. */
        private const val SIGNATURE = "serving_default"
        private const val INPUT_NAME = "keras_tensor"
        private const val OUTPUT_NAME = "output_0"

        private val INPUT_SHAPE = listOf(1, NimaMath.INPUT_EDGE, NimaMath.INPUT_EDGE, 3)
        private const val INPUT_FLOATS = NimaMath.INPUT_EDGE * NimaMath.INPUT_EDGE * 3

        /** Compiles the model (GPU, then CPU) and verifies its tensor contract; throws when nothing usable loads. */
        fun create(context: Context, dispatcher: CoroutineDispatcher): LiteRtNimaScorer {
            val assets = context.assets
            val path = ModelManifest.NIMA_AESTHETIC.assetPath
            var lastFailure: Exception? = null
            for (accelerator in listOf(Accelerator.GPU, Accelerator.CPU)) {
                val model = try {
                    CompiledModel.create(assets, path, CompiledModel.Options(accelerator))
                } catch (e: Exception) {
                    Log.i(TAG, "NIMA $accelerator compile failed", e)
                    lastFailure = e
                    continue
                }
                try {
                    val session = openSession(model)
                    return LiteRtNimaScorer(SerializedResource(session, dispatcher) { it.close() })
                } catch (e: Exception) {
                    model.close()
                    Log.i(TAG, "NIMA $accelerator session rejected", e)
                    lastFailure = e
                }
            }
            throw IllegalStateException("NIMA model unavailable", lastFailure)
        }

        private fun openSession(model: CompiledModel): Session {
            verifyTensor("input", model.getInputTensorType(INPUT_NAME, SIGNATURE), INPUT_SHAPE)
            verifyTensor("output", model.getOutputTensorType(OUTPUT_NAME, SIGNATURE), null)
            val inputs = model.createInputBuffers()
            val outputs = try {
                model.createOutputBuffers()
            } catch (e: Exception) {
                inputs.forEach { it.close() }
                throw e
            }
            val session = Session(model, inputs, outputs)
            try {
                check(inputs.size == 1 && outputs.size == 1) {
                    "expected 1 input and 1 output buffer, got ${inputs.size} and ${outputs.size}"
                }
                // Probe run: proves the compiled graph executes and yields exactly 10 bins.
                inputs[0].writeFloat(session.input)
                model.run(inputs, outputs)
                val bins = outputs[0].readFloat()
                check(bins.size == NimaMath.BIN_COUNT) { "output has ${bins.size} values, expected ${NimaMath.BIN_COUNT}" }
                return session
            } catch (e: Exception) {
                inputs.forEach { it.close() }
                outputs.forEach { it.close() }
                throw e
            }
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
