package com.akane.akastudio.core.curator.ml

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NimaGpuFallbackTest {

    /** GPU scores 3 and fails on its 2nd run (the first real image); CPU scores 8. */
    private fun gpuFailsOnSecondRun(cpuFails: Boolean = false) = RecordingCompiler { accelerator ->
        when (accelerator) {
            NimaAccelerator.GPU -> FakeNimaBackend(accelerator, scoreBin = 2, failingRuns = setOf(2))
            NimaAccelerator.CPU -> FakeNimaBackend(accelerator, scoreBin = 7, alwaysFails = cpuFails)
        }
    }

    @Test
    fun gpuRuntimeFailureRecompilesOnCpuOnceAndRetriesTheImage() = runBlocking {
        val compiler = gpuFailsOnSecondRun()
        val scorer = LiteRtNimaScorer.create(compiler)

        assertEquals("failed image is retried on CPU and scored", 8f, scorer.scorePixels(greyPixels()))
        assertEquals(1, compiler.count(NimaAccelerator.CPU))
        val gpu = compiler.backends[0] as FakeNimaBackend
        val cpu = compiler.backends[1] as FakeNimaBackend
        assertTrue("GPU model must be closed on fallback", gpu.closed.count == 0L)
        val modelThread = compiler.compiles[0].second
        assertSame("CPU recompile on the scorer thread", modelThread, compiler.compiles[1].second)
        assertSame("GPU closed on the scorer thread", modelThread, gpu.closeThread)

        repeat(3) { assertEquals(8f, scorer.scorePixels(greyPixels())) }
        assertEquals("later runs never touch the GPU", 2, gpu.runs)
        assertEquals(4, cpu.runs)
        assertEquals("CPU compiled exactly once", 1, compiler.count(NimaAccelerator.CPU))
        cpu.runThreads.forEach { assertSame(modelThread, it) }

        scorer.close()
        assertTrue(cpu.closed.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun cpuFailureAfterFallbackYieldsNullWithoutCrashing() = runBlocking {
        val compiler = gpuFailsOnSecondRun(cpuFails = true)
        val scorer = LiteRtNimaScorer.create(compiler)

        assertNull(scorer.scorePixels(greyPixels()))
        assertNull(scorer.scorePixels(greyPixels()))
        assertEquals("stays on CPU: no second recompile", 1, compiler.count(NimaAccelerator.CPU))
        assertEquals(2, (compiler.backends[1] as FakeNimaBackend).runs)
        assertEquals(2, (compiler.backends[0] as FakeNimaBackend).runs)
        scorer.close()
    }

    @Test
    fun failedCpuRecompileYieldsNullForEveryLaterImage() = runBlocking {
        var cpuCompiles = 0
        val compiler = RecordingCompiler { accelerator ->
            when (accelerator) {
                NimaAccelerator.GPU -> FakeNimaBackend(accelerator, scoreBin = 2, failingRuns = setOf(2))
                NimaAccelerator.CPU -> {
                    cpuCompiles++
                    throw IllegalArgumentException("CPU delegate rejected the model")
                }
            }
        }
        val scorer = LiteRtNimaScorer.create(compiler)
        assertNull(scorer.scorePixels(greyPixels()))
        assertNull(scorer.scorePixels(greyPixels()))
        assertEquals(1, cpuCompiles)
        assertEquals(2, (compiler.backends[0] as FakeNimaBackend).runs)
        scorer.close()
    }

    @Test
    fun cpuOnlyScorerDoesNotRecompileOnRunFailure() = runBlocking {
        val compiler = RecordingCompiler { accelerator ->
            when (accelerator) {
                NimaAccelerator.GPU -> throw IllegalStateException("no GPU")
                NimaAccelerator.CPU -> FakeNimaBackend(accelerator, scoreBin = 5, failingRuns = setOf(2))
            }
        }
        val scorer = LiteRtNimaScorer.create(compiler)
        assertNull(scorer.scorePixels(greyPixels()))
        assertEquals(6f, scorer.scorePixels(greyPixels()))
        assertEquals(1, compiler.count(NimaAccelerator.CPU))
        scorer.close()
    }

    @Test
    fun gpuLinkageErrorAtCompileStillTriesCpu() = runBlocking {
        val compiler = RecordingCompiler { accelerator ->
            when (accelerator) {
                NimaAccelerator.GPU -> throw UnsatisfiedLinkError("libLiteRtGpuAccelerator.so not found")
                NimaAccelerator.CPU -> FakeNimaBackend(accelerator, scoreBin = 9)
            }
        }
        val scorer = LiteRtNimaScorer.create(compiler)
        assertEquals(10f, scorer.scorePixels(greyPixels()))
        assertEquals(listOf(NimaAccelerator.GPU, NimaAccelerator.CPU), compiler.compiles.map { it.first })
        scorer.close()
    }

    @Test
    fun invalidGpuOutputTriggersTheCpuRetry() = runBlocking {
        val compiler = RecordingCompiler { accelerator ->
            when (accelerator) {
                NimaAccelerator.GPU -> object : NimaBackend {
                    override val inputShape = listOf(1, NimaMath.INPUT_EDGE, NimaMath.INPUT_EDGE, 3)
                    override val outputShape = listOf(1, NimaMath.BIN_COUNT)
                    private var runs = 0
                    override fun run(input: FloatArray): FloatArray =
                        if (++runs == 1) FloatArray(NimaMath.BIN_COUNT) else FloatArray(NimaMath.BIN_COUNT) { Float.NaN }
                    override fun close() = Unit
                }
                NimaAccelerator.CPU -> FakeNimaBackend(accelerator, scoreBin = 3)
            }
        }
        val scorer = LiteRtNimaScorer.create(compiler)
        assertEquals(4f, scorer.scorePixels(greyPixels()))
        assertEquals(1, compiler.count(NimaAccelerator.CPU))
        scorer.close()
    }
}
