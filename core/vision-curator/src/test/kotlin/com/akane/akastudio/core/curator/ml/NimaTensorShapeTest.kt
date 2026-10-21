package com.akane.akastudio.core.curator.ml

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tensor-shape contract checked on every compiled NIMA backend (initial compile and CPU recompile). */
class NimaTensorShapeTest {

    private val edge = NimaMath.INPUT_EDGE
    private val bins = NimaMath.BIN_COUNT

    /** Every accelerator compiles a backend (scoring 5) that reports [input] and [output]. */
    private fun reporting(input: List<Int>, output: List<Int>) = RecordingCompiler { accelerator ->
        FakeNimaBackend(accelerator, scoreBin = 4, inputShape = input, outputShape = output)
    }

    /** The scorer, or `null` when [LiteRtNimaScorer.create] rejected every accelerator. */
    private fun createOrNull(compiler: RecordingCompiler): LiteRtNimaScorer? = try {
        LiteRtNimaScorer.create(compiler)
    } catch (e: IllegalStateException) {
        null
    }

    private fun assertRejected(input: List<Int>, output: List<Int>) {
        val compiler = reporting(input, output)
        assertNull("input $input / output $output must be rejected", createOrNull(compiler))
        assertEquals(listOf(NimaAccelerator.GPU, NimaAccelerator.CPU), compiler.compiles.map { it.first })
        compiler.backends.forEach {
            val fake = it as FakeNimaBackend
            assertTrue("rejected backend left open", fake.closed.count == 0L)
            assertEquals("rejected backend must never run", 0, fake.runs)
        }
    }

    @Test
    fun dynamicBatchModelLoadsAndScores() = runBlocking {
        val compiler = reporting(listOf(-1, edge, edge, 3), listOf(-1, bins))
        val scorer = createOrNull(compiler)
        assertNotNull("model declaring a dynamic (-1) batch must load", scorer)
        assertEquals(listOf(NimaAccelerator.GPU), compiler.compiles.map { it.first })
        assertEquals(5f, scorer!!.scorePixels(greyPixels()))
        scorer.close()
    }

    @Test
    fun unitBatchModelLoads() {
        val scorer = createOrNull(reporting(listOf(1, edge, edge, 3), listOf(1, bins)))
        assertNotNull("model declaring batch 1 must load", scorer)
        scorer!!.close()
    }

    @Test
    fun batchOtherThanOneOrDynamicIsRejected() {
        assertRejected(listOf(2, edge, edge, 3), listOf(1, bins))
        assertRejected(listOf(0, edge, edge, 3), listOf(1, bins))
        assertRejected(listOf(1, edge, edge, 3), listOf(2, bins))
    }

    @Test
    fun wrongImageOrBinDimensionsAreRejected() {
        assertRejected(listOf(-1, edge, edge, 4), listOf(-1, bins))
        assertRejected(listOf(-1, edge, edge, 1), listOf(-1, bins))
        assertRejected(listOf(-1, edge + 1, edge, 3), listOf(-1, bins))
        assertRejected(listOf(-1, edge, edge, 3), listOf(-1, bins + 1))
    }

    @Test
    fun wrongRankIsRejected() {
        assertRejected(listOf(edge, edge, 3), listOf(-1, bins))
        assertRejected(listOf(1, 1, edge, edge, 3), listOf(-1, bins))
        assertRejected(listOf(-1, edge, edge, 3), listOf(bins))
        assertRejected(listOf(-1, edge, edge, 3), listOf(1, 1, bins))
    }

    @Test
    fun shapeRejectedOnGpuFallsBackToCpu() = runBlocking {
        val compiler = RecordingCompiler { accelerator ->
            when (accelerator) {
                NimaAccelerator.GPU -> FakeNimaBackend(accelerator, scoreBin = 1, inputShape = listOf(2, edge, edge, 3))
                NimaAccelerator.CPU -> FakeNimaBackend(accelerator, scoreBin = 7, inputShape = listOf(-1, edge, edge, 3))
            }
        }
        val scorer = LiteRtNimaScorer.create(compiler)
        assertEquals(8f, scorer.scorePixels(greyPixels()))
        assertTrue("rejected GPU backend left open", (compiler.backends[0] as FakeNimaBackend).closed.count == 0L)
        scorer.close()
    }

    @Test
    fun cpuRecompileWithWrongShapeYieldsNull() = runBlocking {
        val compiler = RecordingCompiler { accelerator ->
            when (accelerator) {
                NimaAccelerator.GPU -> FakeNimaBackend(accelerator, scoreBin = 2, failingRuns = setOf(2))
                NimaAccelerator.CPU -> FakeNimaBackend(accelerator, scoreBin = 7, outputShape = listOf(-1, bins + 1))
            }
        }
        val scorer = LiteRtNimaScorer.create(compiler)
        assertNull(scorer.scorePixels(greyPixels()))
        val cpu = compiler.backends[1] as FakeNimaBackend
        assertTrue("rejected CPU backend left open", cpu.closed.count == 0L)
        assertEquals(0, cpu.runs)
        scorer.close()
    }
}
