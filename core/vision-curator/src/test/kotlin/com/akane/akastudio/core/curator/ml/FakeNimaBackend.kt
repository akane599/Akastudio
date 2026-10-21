package com.akane.akastudio.core.curator.ml

import java.util.Collections
import java.util.concurrent.CountDownLatch

/**
 * Scripted [NimaBackend]: returns a one-hot distribution on [scoreBin] (mean score `scoreBin + 1`), throws
 * on the run numbers listed in [failingRuns] (1-based; run 1 is the probe), or on every run when
 * [alwaysFails]. Records the thread of every run and of close.
 */
internal class FakeNimaBackend(
    val accelerator: NimaAccelerator,
    private val scoreBin: Int,
    private val failingRuns: Set<Int> = emptySet(),
    private val alwaysFails: Boolean = false,
) : NimaBackend {

    val runThreads: MutableList<Thread> = Collections.synchronizedList(mutableListOf())

    @Volatile
    var closeThread: Thread? = null

    val closed = CountDownLatch(1)

    val runs: Int get() = runThreads.size

    override fun run(input: FloatArray): FloatArray {
        check(input.size == NimaMath.INPUT_EDGE * NimaMath.INPUT_EDGE * 3) { "unexpected input size ${input.size}" }
        runThreads += Thread.currentThread()
        if (alwaysFails || runThreads.size in failingRuns) throw RuntimeException("$accelerator run ${runThreads.size} failed")
        return FloatArray(NimaMath.BIN_COUNT).also { it[scoreBin] = 1f }
    }

    override fun close() {
        closeThread = Thread.currentThread()
        closed.countDown()
    }
}

/** Compiler that records each compile's accelerator and thread and hands out backends from [factory]. */
internal class RecordingCompiler(
    private val factory: (NimaAccelerator) -> NimaBackend,
) : (NimaAccelerator) -> NimaBackend {

    val compiles: MutableList<Pair<NimaAccelerator, Thread>> = Collections.synchronizedList(mutableListOf())

    val backends: MutableList<NimaBackend> = Collections.synchronizedList(mutableListOf())

    fun count(accelerator: NimaAccelerator): Int = compiles.count { it.first == accelerator }

    override fun invoke(accelerator: NimaAccelerator): NimaBackend {
        compiles += accelerator to Thread.currentThread()
        return factory(accelerator).also { backends += it }
    }
}

/** A mid-grey [NimaMath.INPUT_EDGE]² ARGB image. */
internal fun greyPixels(): IntArray = IntArray(NimaMath.INPUT_EDGE * NimaMath.INPUT_EDGE) { 0xFF808080.toInt() }
