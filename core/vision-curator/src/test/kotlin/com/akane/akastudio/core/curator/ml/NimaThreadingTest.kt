package com.akane.akastudio.core.curator.ml

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NimaThreadingTest {

    @Test
    fun compileProbeEveryScoreAndCloseRunOnOneScorerThread() = runBlocking {
        val compiler = RecordingCompiler { FakeNimaBackend(it, scoreBin = 6) }
        val scorer = LiteRtNimaScorer.create(compiler)
        val scores = (1..24).map {
            async(Dispatchers.Default) { scorer.scorePixels(greyPixels()) }
        }.awaitAll()
        scorer.close()

        assertEquals(listOf(NimaAccelerator.GPU), compiler.compiles.map { it.first })
        val modelThread = compiler.compiles.single().second
        assertEquals("nima-litert", modelThread.name)
        assertTrue("scorer thread must be a daemon", modelThread.isDaemon)
        assertNotSame(Thread.currentThread(), modelThread)

        assertTrue(scores.all { it == 7f })
        val backend = compiler.backends.single() as FakeNimaBackend
        assertEquals("probe run plus one run per score", 25, backend.runs)
        backend.runThreads.forEach { assertSame("run on a foreign thread", modelThread, it) }

        assertTrue("release never ran", backend.closed.await(5, TimeUnit.SECONDS))
        assertSame("close on a foreign thread", modelThread, backend.closeThread)
    }

    @Test
    fun releaseTerminatesTheScorerThread() = runBlocking {
        val compiler = RecordingCompiler { FakeNimaBackend(it, scoreBin = 4) }
        val scorer = LiteRtNimaScorer.create(compiler)
        assertEquals(5f, scorer.scorePixels(greyPixels()))
        val modelThread = compiler.compiles.single().second
        assertTrue(modelThread.isAlive)

        scorer.close()
        modelThread.join(TimeUnit.SECONDS.toMillis(5))
        assertFalse("scorer thread still alive after release", modelThread.isAlive)
        assertEquals("calls after close yield no score", null, scorer.scorePixels(greyPixels()))
    }

    @Test
    fun failedCreateStopsTheScorerThread() {
        val compiler = RecordingCompiler { FakeNimaBackend(it, scoreBin = 0, alwaysFails = true) }
        val failure = runCatching { LiteRtNimaScorer.create(compiler) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(listOf(NimaAccelerator.GPU, NimaAccelerator.CPU), compiler.compiles.map { it.first })
        compiler.backends.forEach { assertTrue("rejected backend left open", (it as FakeNimaBackend).closed.count == 0L) }
        val modelThread = compiler.compiles.first().second
        modelThread.join(TimeUnit.SECONDS.toMillis(5))
        assertFalse("scorer thread leaked after a failed create", modelThread.isAlive)
    }
}
