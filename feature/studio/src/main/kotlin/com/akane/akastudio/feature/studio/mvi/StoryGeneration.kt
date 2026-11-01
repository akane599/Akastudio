package com.akane.akastudio.feature.studio.mvi

import android.util.Log
import com.akane.akastudio.core.curator.api.CurationProgress
import com.akane.akastudio.core.curator.api.CurationResult
import com.akane.akastudio.core.curator.api.StoryMode
import com.akane.akastudio.core.export.api.ExportMetadata
import com.akane.akastudio.core.export.api.ExportProgress
import com.akane.akastudio.core.export.api.ExportRequest
import com.akane.akastudio.core.export.api.ExportResult
import com.akane.akastudio.core.ingestion.api.IngestionRequest
import com.akane.akastudio.core.ingestion.api.IngestionSession
import com.akane.akastudio.core.ingestion.model.IngestionEvent
import com.akane.akastudio.core.ingestion.model.IngestionTelemetry
import com.akane.akastudio.core.ingestion.model.PhotoCandidate
import com.akane.akastudio.feature.studio.domain.ExportCanvasPolicy
import com.akane.akastudio.feature.studio.domain.PreparedPhotoStore
import com.akane.akastudio.feature.studio.domain.StudioDependencies
import com.akane.akastudio.feature.studio.domain.StudioError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a [StoryGeneration] needs from the studio that runs it. Every call happens on the main dispatcher. */
internal interface StoryHost {
    val deps: StudioDependencies

    /** The screen state; the generation shapes it only through [StudioReducer]. */
    val state: MutableStateFlow<StudioState>

    /** True while a system picker is open: an idle ingestion then waits for the picker's photos. */
    val pickersOpen: Boolean

    fun effect(effect: StudioEffect)

    /** Applies a dependency-free intent (and rebuilds the scene when it changed what the scene shows). */
    fun apply(intent: StudioIntent)

    /** Retires [generation] if it is still the current one, leaving an empty studio that shows [error]. */
    fun retire(generation: StoryGeneration, error: StudioError?)
}

/**
 * One generation of the studio pipeline: the batch of imports that becomes one story, from the first import until
 * the studio is emptied. It is the single owner of everything that batch holds (its ingestion session and
 * candidates, its prepared photos, its running work and the last Ready state it can fall back to), and the single
 * place that moves the phase.
 *
 * ```
 * Empty ─import─► Ingesting ─idle+debounce / StartCuration─► Curating ─decided─► Preparing ─prepared─► Ready
 *                    │ cancel, failure                          │                   │ cancel, failure (no stable yet)
 *                    └──────────────────────► retire ◄──────────┴───────────────────┘
 * Ready ─ForceMode, SwapCandidate─► Preparing ─prepared─► Ready
 *                                      └─ cancel, failure ─► restore the last Ready story (re-preparing it if needed)
 * Ready ─Export─► Exporting ─done, failure, cancel─► Ready
 * ```
 *
 * Scopes: every job runs in this generation's [scope], a child of the studio's, so retiring the generation cancels
 * all of it at once; the studio's own lifetime ends it too. Within the generation, [launchWork] keeps one unit of
 * work (curation and preparation, a re-preparation, a restore, an export) running at a time: a new one starts
 * after the previous one ended. Only [cancelWork] and [retire] cancel.
 *
 * Re-entrancy: on `Dispatchers.Main.immediate` a state collector on main runs inside the state write that woke it,
 * and may send an intent from there. So a job is registered (as [ingestJob] or [work]) before the state announcing
 * it is published, and only started afterwards: an intent re-entering on that state finds the job it refers to.
 *
 * Errors: every work failure reaches [onWorkFailed] once, which maps it and decides by the work's kind: the first
 * pipeline and a restore retire the generation showing the error, a re-preparation restores the previous story
 * showing it, an export returns to Ready showing it. A failure after the generation was retired or its work was
 * cancelled is only logged: the studio has moved on and must not inherit it. Prepared photos are released by the
 * store under this generation's [com.akane.akastudio.feature.studio.domain.PhotoOwner], so a late release never
 * touches a newer generation's registrations.
 */
internal class StoryGeneration(private val host: StoryHost, parent: CoroutineScope) {

    private enum class WorkKind { INITIAL, REPREPARE, RESTORE, EXPORT }

    private class Work(val kind: WorkKind, after: Work?) {
        lateinit var job: Job

        /** True until the previous work has ended and this one begins: nothing of its own has run yet. */
        var waiting = true

        /** The work this one follows, kept until this one begins (see [tail]). */
        var after: Work? = after
    }

    /**
     * The job the next work must follow. A work cancelled while still waiting has run nothing of its own, and if it
     * was cancelled before it ever started it never joins the work before it either, so the real tail is further back.
     */
    private fun tail(): Job? {
        var w = work
        while (w != null && w.waiting && w.job.isCancelled) w = w.after
        return w?.job
    }

    private val deps: StudioDependencies get() = host.deps
    private val state: MutableStateFlow<StudioState> get() = host.state

    private val job = SupervisorJob(parent.coroutineContext.job)
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val store = PreparedPhotoStore(deps.preparer, deps.dispatchers.io)
    private val candidates = LinkedHashMap<String, PhotoCandidate>()
    private val pendingImports = ArrayDeque<IngestionRequest>()
    private var session: IngestionSession? = null
    private var ingested = IngestionTelemetry()
    private var ingestJob: Job? = null
    private var debounceJob: Job? = null
    private var curationRequested = false

    /** The latest unit of work; see [launchWork]. */
    private var work: Work? = null

    /** True while [ingestJob] has not finished, including just before it starts (see Re-entrancy above). */
    private val reading: Boolean get() = ingestJob?.isCompleted == false

    /** The last Ready state; a cancelled or failed re-preparation returns to it. */
    private var stable: StudioState? = null

    /** True once [retire] ran: nothing here may touch the studio any more. */
    var retired: Boolean = false
        private set

    // region ingestion

    /** Queues [request] into this generation's ingestion session and reads it (after any import still being read). */
    fun import(request: IngestionRequest) {
        check(!retired) { "A retired generation cannot import" }
        pendingImports.addLast(request)
        cancelDebounce()
        val started = if (reading) null else scope.launch(start = CoroutineStart.LAZY) { runIngestion() }
        started?.let { ingestJob = it }
        state.update { StudioReducer.ingesting(it) }
        started?.start()
    }

    private suspend fun runIngestion() {
        try {
            val active = openSession()
            while (true) {
                val request = pendingImports.removeFirstOrNull() ?: break
                ingested += ingestOne(active, named(request))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            host.retire(this, deps.errors.map(e))
            return
        }
        onIngestionIdle()
    }

    /** This generation's session, opened on io on first use. A session opened as the generation retires is closed. */
    private suspend fun openSession(): IngestionSession {
        session?.let { return it }
        // Opened to completion even if cancelled meanwhile, so the folder it creates always has an owner to close it.
        val created = withContext(NonCancellable) { withContext(deps.dispatchers.io) { deps.newIngestionSession() } }
        if (retired) {
            withContext(NonCancellable) { withContext(deps.dispatchers.io) { created.close() } }
            throw CancellationException("Retired while opening the ingestion session")
        }
        session = created
        return created
    }

    /** [request], with a zip's blank display name replaced by the document provider's, looked up on io. */
    private suspend fun named(request: IngestionRequest): IngestionRequest =
        if (request is IngestionRequest.ZipArchive && request.displayName.isBlank()) {
            request.copy(displayName = withContext(deps.dispatchers.io) { deps.documentDisplayName(request.uri) })
        } else {
            request
        }

    /** Collects one import into telemetry and [candidates]; returns that import's final telemetry. */
    private suspend fun ingestOne(active: IngestionSession, request: IngestionRequest): IngestionTelemetry {
        var last = IngestionTelemetry()
        active.ingest(listOf(request)).flowOn(deps.dispatchers.io).collect { event ->
            when (event) {
                is IngestionEvent.Progress -> last = event.telemetry
                is IngestionEvent.CandidateReady -> {
                    last = event.telemetry
                    candidates.putIfAbsent(event.candidate.id, event.candidate)
                }
                is IngestionEvent.Skipped -> last = event.telemetry
                is IngestionEvent.SourceFailed -> {
                    last = event.telemetry
                    host.effect(StudioEffect.ShowMessage(StudioMessage.SourceFailed(event.displayName, event.error)))
                }
                is IngestionEvent.Completed -> {
                    last = event.telemetry
                    event.candidates.forEach { candidates.putIfAbsent(it.id, it) }
                }
            }
            state.update { StudioReducer.onIngestion(it, ingested + last, candidates.size) }
        }
        return last
    }

    private fun onIngestionIdle() {
        // An open picker may still add photos to this batch; its close re-arms curation.
        if (host.pickersOpen) return
        if (curationRequested) {
            curationRequested = false
            startCuration()
        } else {
            debounceJob = scope.launch {
                delay(StudioViewModel.AUTO_CURATION_DEBOUNCE_MS)
                debounceJob = null
                startCuration()
            }
        }
    }

    /** A picker opened: auto-curation waits for its result. */
    fun holdCuration() = cancelDebounce()

    /** The last open picker closed: when ingestion is idle, curation follows as after any import. */
    fun onPickersClosed() {
        if (state.value.phase != StudioPhase.Ingesting) return
        if (!reading && debounceJob == null) onIngestionIdle()
    }

    /** Curate now: at once when ingestion is idle, else as soon as the imports being read are in. */
    fun requestCuration() {
        if (state.value.phase != StudioPhase.Ingesting) return
        if (reading) {
            curationRequested = true
        } else {
            cancelDebounce()
            startCuration()
        }
    }

    private fun cancelDebounce() {
        debounceJob?.cancel()
        debounceJob = null
    }

    // endregion

    // region curation and preparation

    private fun startCuration() {
        val list = candidates.values.toList()
        if (list.isEmpty()) {
            host.retire(this, StudioError.NoUsablePhotos(rejectedCount = ingested.skipped))
            return
        }
        launchWork(WorkKind.INITIAL, enter = { StudioReducer.curating(it, list.size) }) {
            val result = curate(list)
            state.update { StudioReducer.decided(it, result, candidates) }
            prepareAndShow()
        }
    }

    private suspend fun curate(list: List<PhotoCandidate>): CurationResult {
        var decided: CurationResult? = null
        deps.curation.curate(list).flowOn(deps.dispatchers.default).collect { progress ->
            state.update { StudioReducer.onCuration(it, progress) }
            if (progress is CurationProgress.Decided) decided = progress.result
        }
        return decided ?: throw IllegalStateException("Curation finished without a decision")
    }

    /** Prepares the current selection, builds the scene and lands in [StudioPhase.Ready]. */
    private suspend fun prepareAndShow() {
        val s = state.value
        val ids = s.selection.photoIds
        val chosen = ids.map { id -> checkNotNull(candidates[id]) { "Unknown candidate $id" } }
        store.acquire(chosen, s.curation?.analyses.orEmpty(), hero = s.selection.mode == StoryMode.HERO)
        val latest = state.value
        val scene = SceneBuilder.build(latest, store.photos(ids), deps.harmonizer, deps.composer)
        showReady(StudioReducer.ready(latest, scene))
        try {
            store.retainOnly(ids)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report(e)
        }
    }

    private fun showReady(ready: StudioState) {
        state.value = ready
        stable = ready
    }

    /** Re-decides the story for [mode] and prepares it; the same mode just closes the drawer. */
    fun forceMode(mode: StoryMode) {
        val s = state.value
        val result = s.curation ?: return
        if (mode == s.selection.mode) {
            state.update { it.copy(modeDrawerOpen = false) }
            return
        }
        reprepare {
            val decided = withContext(deps.dispatchers.default) { deps.curation.decide(result, mode) }
            val transition: (StudioState) -> StudioState = { current ->
                StudioReducer.withSelection(current, decided, decided.chosen.photoIds, candidates)
            }
            transition
        }
    }

    /** Puts [candidateId] in [slot]: a reorder when it is already in the story, else a prepare of the alternative. */
    fun swapCandidate(slot: Int, candidateId: String) {
        val s = state.value
        val ids = s.selection.photoIds
        if (slot !in ids.indices || ids[slot] == candidateId) return
        val existing = ids.indexOf(candidateId)
        if (existing >= 0) {
            host.apply(StudioIntent.SwapSlots(slot, existing))
            return
        }
        val result = s.curation ?: return
        val analysis = result.analyses[candidateId]
        if (candidateId !in candidates || analysis == null || analysis.rejectReason != null) return
        val newIds = ids.toMutableList().apply { this[slot] = candidateId }
        reprepare { { current -> StudioReducer.withSelection(current, result, newIds, candidates) } }
    }

    /**
     * Leaves Ready for Preparing, applies the new selection [select] computes and prepares it; a failure restores
     * [stable]. [select] may suspend off the main thread; the transition it returns is applied to the state current
     * when it resumes, so edits made meanwhile are kept.
     */
    private fun reprepare(select: suspend () -> (StudioState) -> StudioState) {
        launchWork(WorkKind.REPREPARE, enter = StudioReducer::preparing) {
            val transition = select()
            state.update { transition(it).copy(phase = StudioPhase.Preparing) }
            prepareAndShow()
        }
    }

    /**
     * Returns to the last Ready story showing [error], re-preparing its photos when the abandoned work invalidated
     * them. Runs as its own work after the abandoned one has ended, since that one still cleans up its batch.
     * Without a stable story (or when the restore itself fails or is cancelled) the generation retires.
     */
    private fun restore(error: StudioError?) {
        val target = stable
        if (target == null) {
            host.retire(this, error)
            return
        }
        launchWork(WorkKind.RESTORE) {
            val merged = StudioReducer.restored(state.value, target, error)
            if (store.covers(target.selection.photoIds, hero = target.selection.mode == StoryMode.HERO)) {
                state.value = merged.copy(phase = StudioPhase.Ready)
                rebuildScene()
            } else {
                state.value = merged.copy(phase = StudioPhase.Preparing)
                prepareAndShow()
            }
        }
    }

    /** Synchronous rebuild of the preview scene when the story is on screen; bumps [StudioState.sceneRevision]. */
    fun rebuildScene() {
        val s = state.value
        if (s.phase != StudioPhase.Ready && s.phase != StudioPhase.Exporting) return
        val ids = s.selection.photoIds
        if (s.template == null || !store.covers(ids, s.selection.mode == StoryMode.HERO)) return
        try {
            val scene = SceneBuilder.build(s, store.photos(ids), deps.harmonizer, deps.composer)
            val next = StudioReducer.withScene(s, scene)
            state.value = next
            if (next.phase == StudioPhase.Ready) stable = next
        } catch (e: Exception) {
            report(e)
        }
    }

    // endregion

    // region export

    /** Exports the story on screen; [share] opens the share sheet when it is done. */
    fun export(share: Boolean) {
        launchWork(WorkKind.EXPORT, enter = { StudioReducer.exporting(it, share) }) {
            val result = runExport()
            state.update { StudioReducer.exportFinished(it, result) }
            stable = state.value
            host.effect(
                if (share) {
                    StudioEffect.ShareExport(result.uri, result.mimeType)
                } else {
                    StudioEffect.ShowMessage(StudioMessage.ExportSaved(result))
                },
            )
        }
    }

    private suspend fun runExport(): ExportResult {
        val s = state.value
        val canvas = ExportCanvasPolicy.canvasFor(s.export.format, s.export.resolution)
        val scene = SceneBuilder.build(s, store.photos(s.selection.photoIds), deps.harmonizer, deps.composer, canvas)
        val request = ExportRequest(
            scene = scene,
            format = s.export.format,
            resolution = s.export.resolution,
            metadata = ExportMetadata(
                captureEpochMillis = s.selection.slots[0]?.metadata?.captureEpochMillis,
                description = null,
            ),
            hdrBoost = s.export.hdrBoost,
        )
        var result: ExportResult? = null
        deps.exporter.export(request).flowOn(deps.dispatchers.default).collect { progress ->
            when (progress) {
                is ExportProgress.Stage -> state.update { StudioReducer.onExportStage(it, progress) }
                is ExportProgress.Done -> result = progress.result
            }
        }
        return result ?: throw IllegalStateException("Export finished without a result")
    }

    // endregion

    // region work, cancellation and retirement

    /**
     * Cancels what runs now, by what it is: reading or the first pipeline (nothing to go back to) and a restore
     * that re-prepares retire the generation; a re-preparation returns to the previous story; an export returns to
     * Ready. A restore still waiting for the cancelled or failed work before it is already that return, so a cancel
     * meanwhile (a repeated tap) leaves it to finish.
     */
    fun cancelWork() {
        val running = work?.takeIf { !it.job.isCompleted && !it.job.isCancelled }
        if (running == null) {
            host.retire(this, error = null)
            return
        }
        when (running.kind) {
            WorkKind.INITIAL -> host.retire(this, error = null)
            WorkKind.RESTORE -> if (!running.waiting) host.retire(this, error = null)
            WorkKind.REPREPARE -> {
                running.job.cancel()
                restore(error = null)
            }
            WorkKind.EXPORT -> {
                running.job.cancel()
                state.update { StudioReducer.exportCancelled(it) }
                stable = state.value
            }
        }
    }

    /**
     * Runs [block] as the next unit of work, after the previous one has ended (it is never cancelled here: a
     * finished one may still be releasing photos, and [cancelWork] decides about a running one). Its failure goes
     * to [onWorkFailed], unless the work was cancelled or the generation retired meanwhile. [enter] is the phase
     * transition announcing the work; it is published once the work is registered, before the work starts.
     */
    private fun launchWork(kind: WorkKind, enter: ((StudioState) -> StudioState)? = null, block: suspend () -> Unit) {
        val previous = tail()
        val next = Work(kind, after = work)
        next.job = scope.launch(start = CoroutineStart.LAZY) {
            // Waited out even when cancelled meanwhile: a cancelled waiter that ran on would overlap the previous
            // work's tail (a release, a render) instead of following it, and so would whatever is launched after it.
            if (previous != null && previous !== coroutineContext.job) withContext(NonCancellable) { previous.join() }
            ensureActive()
            next.waiting = false
            next.after = null
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isActive && !retired) {
                    onWorkFailed(kind, e)
                } else {
                    Log.w(TAG, "Ignoring a failure of abandoned $kind work", e)
                }
            }
        }
        work = next
        if (enter != null) state.update(enter)
        next.job.start()
    }

    /** The one place a work failure is mapped and turned into a transition. */
    private fun onWorkFailed(kind: WorkKind, e: Exception) {
        val error = deps.errors.map(e)
        when (kind) {
            WorkKind.INITIAL, WorkKind.RESTORE -> host.retire(this, error)
            WorkKind.REPREPARE -> restore(error)
            WorkKind.EXPORT -> {
                state.update { StudioReducer.exportFailed(it, error) }
                // Layout edits made while exporting are part of the story now.
                stable = state.value
            }
        }
    }

    /**
     * Shows a failure that leaves the story on screen (a scene rebuild, a trailing release), unless an error is
     * already shown: the first failure is the one that explains the state, so a follow-on failure never replaces it.
     */
    private fun report(e: Exception) {
        if (retired) {
            Log.w(TAG, "Ignoring a failure of a retired generation", e)
            return
        }
        val shown = deps.errors.map(e)
        state.update { if (it.error == null) it.copy(error = shown) else it }
        if (state.value.error != shown) Log.w(TAG, "Not shown over the error already on screen", e)
    }

    /**
     * Ends the generation: cancels every job and detaches its resources synchronously. The returned cleanup waits
     * for the cancelled jobs to finish (a decode in flight completes, and its batch is released by its own work),
     * then releases the prepared photos and closes the ingestion session off the main thread. The host runs it in a
     * scope that outlives the studio, so a cleared view model still cleans up.
     */
    fun retire(): suspend () -> Unit {
        check(!retired) { "Already retired" }
        retired = true
        job.cancel()
        pendingImports.clear()
        val dropped = store.detachAll()
        val oldSession = session
        session = null
        return {
            job.join()
            // Both steps always run; the first failure is the one thrown, a second rides along as suppressed.
            var failure: Exception? = null
            try {
                store.release(dropped)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
            }
            if (oldSession != null) {
                try {
                    withContext(deps.dispatchers.io) { oldSession.close() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (failure == null) failure = e else failure.addSuppressed(e)
                }
            }
            failure?.let { throw it }
        }
    }

    // endregion

    private companion object {
        const val TAG = "StoryGeneration"

        operator fun IngestionTelemetry.plus(other: IngestionTelemetry) = IngestionTelemetry(
            sourcesTotal = sourcesTotal + other.sourcesTotal,
            filesRead = filesRead + other.filesRead,
            candidatesAccepted = candidatesAccepted + other.candidatesAccepted,
            skipped = skipped + other.skipped,
            bytesExtracted = bytesExtracted + other.bytesExtracted,
        )
    }
}
