package com.akane.akastudio.core.curator.engine

import android.util.Log
import com.akane.akastudio.core.curator.api.CurationEngine
import com.akane.akastudio.core.curator.api.CurationProgress
import com.akane.akastudio.core.curator.api.CurationResult
import com.akane.akastudio.core.curator.api.PhotoAnalysis
import com.akane.akastudio.core.curator.api.StoryMode
import com.akane.akastudio.core.curator.decision.DecisionConfig
import com.akane.akastudio.core.curator.decision.DecisionEngine
import com.akane.akastudio.core.ingestion.model.PhotoCandidate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * Thrown by [DefaultCurationEngine.curate] after its `Clustered` emission when every candidate was rejected at the
 * technical gate (blurry, eyes closed, bad exposure or unreadable), so no story can be made. The UI owns the message.
 *
 * @property rejectedCount the number of rejected candidates, which is every candidate.
 */
class NoUsablePhotosException(val rejectedCount: Int) :
    IllegalStateException("all $rejectedCount photos were rejected; no usable photo remains")

/**
 * The production [CurationEngine]: analyzes each candidate (at most [parallelism] at a time, on [dispatcher]), then
 * clusters and decides with [DecisionEngine] under [config].
 *
 * [curate] emits `Analyzing(0, n)`, then `Analyzing(k, n)` after each finished analysis, then
 * `Clustered(clusterCount, rejectedCount)`, then exactly one `Decided`. A candidate whose pixels cannot be loaded is
 * rejected as UNREADABLE and still counts toward progress. When every candidate is rejected the flow throws
 * [NoUsablePhotosException] right after `Clustered(0, n)`.
 *
 * The aesthetic formula is all-or-nothing per curation: when NIMA gave no score for any readable candidate, every
 * analysis uses the no-NIMA formula (see [PhotoAnalyzer.uniformAesthetic]), and the downgrade is logged with counts.
 *
 * Capture times are kept per candidate id so [decide] can rebuild the decision for a mode switch.
 */
class DefaultCurationEngine(
    private val pixels: PixelSource,
    private val models: AnalysisModels,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val parallelism: Int = 3,
    private val config: DecisionConfig = DecisionConfig(),
) : CurationEngine {

    init {
        require(parallelism >= 1) { "parallelism must be at least 1: $parallelism" }
    }

    private val analyzer = PhotoAnalyzer(pixels, models)

    /** Known capture epoch millis by candidate id; an id with an unknown capture time has no entry. */
    private val captureTimes = ConcurrentHashMap<String, Long>()

    /**
     * Cold flow over [candidates]. Collection throws IllegalArgumentException for an empty list or duplicate ids,
     * and [NoUsablePhotosException] when every candidate is rejected. Cancelling the collector cancels every
     * in-flight analysis before the flow completes.
     */
    override fun curate(candidates: List<PhotoCandidate>): Flow<CurationProgress> = flow {
        require(candidates.isNotEmpty()) { "candidates must not be empty" }
        require(candidates.mapTo(HashSet()) { it.id }.size == candidates.size) { "candidate ids must be distinct" }
        val total = candidates.size
        rememberCaptureTimes(candidates)
        emit(CurationProgress.Analyzing(0, total))

        val analyzed = coroutineScope {
            val gate = Semaphore(parallelism)
            val finished = Channel<Unit>(Channel.UNLIMITED)
            val jobs = candidates.map { candidate ->
                async(dispatcher) {
                    gate.withPermit { analyzer.analyze(candidate) }.also { finished.send(Unit) }
                }
            }
            for (done in 1..total) {
                finished.receive()
                emit(CurationProgress.Analyzing(done, total))
            }
            val uniform = PhotoAnalyzer.uniformAesthetic(jobs.awaitAll())
            if (uniform.rescored > 0) {
                Log.w(
                    TAG,
                    "NIMA unavailable for ${uniform.nimaMissing} of $total photos; " +
                        "rescored ${uniform.rescored} NIMA-scored photos with the no-NIMA aesthetic",
                )
            }
            uniform.analyses
        }
        val analyses = LinkedHashMap<String, PhotoAnalysis>(total * 2).apply {
            for (analysis in analyzed) put(analysis.candidateId, analysis)
        }

        val rejectedCount = analyzed.count { it.rejectReason != null }
        if (rejectedCount == total) {
            emit(CurationProgress.Clustered(clusterCount = 0, rejectedCount = rejectedCount))
            throw NoUsablePhotosException(rejectedCount)
        }
        val decision = DecisionEngine.decide(analyzed, captureTimesOf(analyses.keys), config)
        emit(CurationProgress.Clustered(decision.clusters.size, decision.rejectedIds.size))
        emit(CurationProgress.Decided(CurationResults.build(decision, analyses)))
    }

    /**
     * Re-decides [result] for [forcedMode] (null = automatic) with [DecisionEngine.redecide], keeping its analyses,
     * clusters and rejections, and rebuilds the note and suggested look for the new chosen option.
     */
    override fun decide(result: CurationResult, forcedMode: StoryMode?): CurationResult {
        val decision = DecisionEngine.redecide(
            previous = CurationResults.decisionOf(result),
            analyses = result.analyses.values.toList(),
            captureTimes = captureTimesOf(result.analyses.keys),
            forcedMode = forcedMode,
            config = config,
        )
        return CurationResults.build(decision, result.analyses)
    }

    private fun rememberCaptureTimes(candidates: List<PhotoCandidate>) {
        for (candidate in candidates) {
            val time = candidate.metadata.captureEpochMillis
            if (time == null) captureTimes.remove(candidate.id) else captureTimes[candidate.id] = time
        }
    }

    private fun captureTimesOf(ids: Collection<String>): Map<String, Long?> = ids.associateWith { captureTimes[it] }

    private companion object {
        const val TAG = "CurationEngine"
    }
}
