package com.akane.akastudio.core.curator.ml

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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
        assertEquals("nima-litert", compiler.compileThreadNames.single())
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

    @Test
    fun errorFromNativeCloseIsContainedOnTheScorerThread() {
        val uncaught = Collections.synchronizedList(mutableListOf<Pair<Thread, Throwable>>())
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error -> uncaught += thread to error }
        try {
            val compiler = RecordingCompiler {
                FakeNimaBackend(it, scoreBin = 6, closeFailure = LinkageError("native close failed"))
            }
            val scorer = LiteRtNimaScorer.create(compiler)
            val modelThread = compiler.compiles.single().second

            scorer.close()
            modelThread.join(TimeUnit.SECONDS.toMillis(5))

            assertFalse("scorer thread still alive after release", modelThread.isAlive)
            assertTrue("close never ran", (compiler.backends.single() as FakeNimaBackend).closed.count == 0L)
            assertEquals("release Error escaped to the uncaught handler", emptyList<Any>(), uncaught.filter { it.first === modelThread })
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun interruptedCreateClosesTheBackendItsCompileProducesLater() {
        val compileStarted = CountDownLatch(1)
        val finishCompile = CountDownLatch(1)
        val backend = FakeNimaBackend(NimaAccelerator.GPU, scoreBin = 6)
        val compiler = RecordingCompiler {
            compileStarted.countDown()
            finishCompile.await()
            backend
        }
        val failure = AtomicReference<Throwable?>()
        val caller = Thread { failure.set(runCatching { LiteRtNimaScorer.create(compiler) }.exceptionOrNull()) }
        caller.start()
        assertTrue("compile never started", compileStarted.await(5, TimeUnit.SECONDS))

        caller.interrupt()
        awaitInterruptConsumed(caller)
        assertEquals("backend closed before its compile finished", 1L, backend.closed.count)

        finishCompile.countDown()
        caller.join(TimeUnit.SECONDS.toMillis(5))
        assertFalse("create still blocked after the compile finished", caller.isAlive)
        assertTrue("expected InterruptedException, got ${failure.get()}", failure.get() is InterruptedException)
        assertTrue("late-compiled backend leaked", backend.closed.await(5, TimeUnit.SECONDS))
        val modelThread = compiler.compiles.single().second
        assertSame("close on a foreign thread", modelThread, backend.closeThread)
        modelThread.join(TimeUnit.SECONDS.toMillis(5))
        assertFalse("scorer thread leaked after an interrupted create", modelThread.isAlive)
    }

    @Test
    fun fatalProbeErrorClosesTheProbedBackendBeforePropagating() {
        val compiler = RecordingCompiler {
            FakeNimaBackend(it, scoreBin = 0, alwaysFails = true, runFailure = ::FatalProbeError)
        }
        val failure = runCatching { LiteRtNimaScorer.create(compiler) }.exceptionOrNull()

        assertTrue("expected FatalProbeError, got $failure", failure is FatalProbeError)
        assertEquals("a fatal error must not fall through to CPU", listOf(NimaAccelerator.GPU), compiler.compiles.map { it.first })
        assertTrue("probed backend leaked", (compiler.backends.single() as FakeNimaBackend).closed.count == 0L)
        val modelThread = compiler.compiles.single().second
        modelThread.join(TimeUnit.SECONDS.toMillis(5))
        assertFalse("scorer thread leaked after a fatal probe", modelThread.isAlive)
    }

    /**
     * Waits until [thread] has consumed its interrupt and parked again. runBlocking clears the flag with
     * Thread.interrupted(), cancels its coroutine, then parks, so a parked thread with a clear flag has
     * cancelled the create coroutine before the compile is allowed to finish.
     */
    private fun awaitInterruptConsumed(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.isInterrupted || thread.state !in PARKED) {
            check(System.nanoTime() < deadline) { "interrupt never consumed; caller is ${thread.state}" }
            Thread.yield()
        }
    }

    private companion object {
        val PARKED = setOf(Thread.State.WAITING, Thread.State.TIMED_WAITING)
    }

    /** Neither an Exception nor a LinkageError, so the scorer treats it as non-recoverable. */
    private class FatalProbeError(message: String) : Error(message)
}
