package com.akane.akastudio.feature.studio.mvi

import androidx.lifecycle.ViewModel
import com.akane.akastudio.core.color.api.ImagePixels
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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The studio's MVI store: [onIntent] in, [state] and one-shot [effects] out.
 *
 * Pipeline: imports accumulate into one ingestion session; 600 ms after the last import finishes (or at once on
 * [StudioIntent.StartCuration]) the curator runs; its decision is prepared at full resolution and composed into a
 * scene, and the phase becomes [StudioPhase.Ready]. Grade and layout changes rebuild the scene synchronously.
 *
 * Threading: [onIntent] must be called on the main thread, and [scope] must run on `deps.dispatchers.main`; every
 * mutable field is confined there. The renderer belongs to the container and is never closed here.
 *
 * @param scope The view model's coroutine scope (becomes `viewModelScope`); tests pass one on a test dispatcher.
 */
class StudioViewModel(
    private val deps: StudioDependencies,
    private val scope: CoroutineScope,
) : ViewModel(scope) {

    /** Production constructor: a supervisor scope on `deps.dispatchers.main`. */
    constructor(deps: StudioDependencies) : this(deps, CoroutineScope(SupervisorJob() + deps.dispatchers.main))

    private val _state = MutableStateFlow(StudioState())

    /** The current screen state. */
    val state: StateFlow<StudioState> = _state.asStateFlow()

    private val effectChannel = Channel<StudioEffect>(Channel.BUFFERED)

    /** One-shot effects; each is delivered to exactly one collector. */
    val effects: Flow<StudioEffect> = effectChannel.receiveAsFlow()

    /** Outlives [scope] so resets and [onCleared] can still release photos and delete the session folder. */
    private val cleanupScope = CoroutineScope(SupervisorJob() + deps.dispatchers.main)

    private val store = PreparedPhotoStore(deps.preparer, deps.dispatchers.io)
    private val candidates = LinkedHashMap<String, PhotoCandidate>()
    private val pendingImports = ArrayDeque<IngestionRequest>()
    private var session: IngestionSession? = null
    private var ingestedTelemetry = IngestionTelemetry()
    private var ingestJob: Job? = null
    private var debounceJob: Job? = null
    private var startRequested = false

    /** The single running unit of work (curation, preparation or export). */
    private var work: Job? = null

    /** The last [StudioPhase.Ready] state; a cancelled or failed re-preparation returns to it. */
    private var stable: StudioState? = null

    /** True while re-preparing [stable] after a cancelled or failed re-preparation. */
    private var restoring = false

    init {
        scope.launch { runScopePipeline() }
    }

    /** Handles one user interaction. Main thread only. */
    fun onIntent(intent: StudioIntent) {
        when (intent) {
            is StudioIntent.ImportPhotos ->
                if (intent.uris.isNotEmpty()) import(IngestionRequest.PickedMedia(intent.uris.toList()))
            is StudioIntent.ImportZip -> import(IngestionRequest.ZipArchive(intent.uri, intent.displayName))
            StudioIntent.StartCuration -> requestCuration()
            StudioIntent.CancelWork -> cancelWork()
            is StudioIntent.ForceMode -> forceMode(intent.mode)
            is StudioIntent.SwapCandidate -> swapCandidate(intent.slot, intent.candidateId)
            is StudioIntent.SwapSlots -> if (requireReady()) applyPure(intent)
            is StudioIntent.Export -> export(intent.share)
            StudioIntent.Reset -> resetToEmpty(error = null)
            else -> applyPure(intent)
        }
    }

    override fun onCleared() {
        resetToEmpty(error = null)
    }

    // region pure intents and scene

    private fun applyPure(intent: StudioIntent) {
        val before = _state.value
        val after = StudioReducer.reduce(before, intent)
        if (after == before) return
        _state.value = after
        if (StudioReducer.sceneInputsChanged(before, after)) rebuildScene()
    }

    /** Synchronous rebuild of the preview scene when the story is on screen; bumps [StudioState.sceneRevision]. */
    private fun rebuildScene() {
        val s = _state.value
        if (s.phase != StudioPhase.Ready && s.phase != StudioPhase.Exporting) return
        val ids = s.selection.photoIds
        if (s.template == null || !store.covers(ids, s.selection.mode == StoryMode.HERO)) return
        try {
            val scene = SceneBuilder.build(s, store.photos(ids), deps.harmonizer, deps.composer)
            val next = s.copy(scene = scene, sceneRevision = s.sceneRevision + 1)
            _state.value = next
            if (next.phase == StudioPhase.Ready) stable = next
        } catch (e: Exception) {
            _state.update { it.copy(error = deps.errors.map(e)) }
        }
    }

    // endregion

    // region ingestion

    private fun import(request: IngestionRequest) {
        when (_state.value.phase) {
            StudioPhase.Curating, StudioPhase.Preparing, StudioPhase.Exporting -> {
                effect(StudioEffect.ShowMessage(StudioMessage.Busy))
                return
            }
            // Curation already ran for this batch: the new import starts a new story.
            StudioPhase.Ready -> resetToEmpty(error = null)
            StudioPhase.Empty, StudioPhase.Ingesting -> Unit
        }
        pendingImports.addLast(request)
        debounceJob?.cancel()
        debounceJob = null
        _state.update {
            it.copy(phase = StudioPhase.Ingesting, error = null, telemetry = it.telemetry.copy(step = TelemetryStep.READING))
        }
        if (ingestJob?.isActive != true) ingestJob = scope.launch { runIngestion() }
    }

    private suspend fun runIngestion() {
        try {
            val active = session ?: withContext(deps.dispatchers.io) { deps.newIngestionSession() }.also { session = it }
            while (true) {
                val request = pendingImports.removeFirstOrNull() ?: break
                ingestedTelemetry += ingestOne(active, request)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            resetToEmpty(deps.errors.map(e))
            return
        }
        onIngestionIdle()
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
                    effect(StudioEffect.ShowMessage(StudioMessage.SourceFailed(event.displayName, event.error)))
                }
                is IngestionEvent.Completed -> {
                    last = event.telemetry
                    event.candidates.forEach { candidates.putIfAbsent(it.id, it) }
                }
            }
            _state.update { StudioReducer.onIngestion(it, ingestedTelemetry + last, candidates.size) }
        }
        return last
    }

    private fun onIngestionIdle() {
        if (startRequested) {
            startRequested = false
            startCuration()
        } else {
            debounceJob = scope.launch {
                delay(AUTO_CURATION_DEBOUNCE_MS)
                debounceJob = null
                startCuration()
            }
        }
    }

    private fun requestCuration() {
        if (_state.value.phase != StudioPhase.Ingesting) return
        if (ingestJob?.isActive == true) {
            startRequested = true
        } else {
            debounceJob?.cancel()
            debounceJob = null
            startCuration()
        }
    }

    // endregion

    // region curation and preparation

    private fun startCuration() {
        val list = candidates.values.toList()
        if (list.isEmpty()) {
            resetToEmpty(StudioError.NoUsablePhotos(rejectedCount = ingestedTelemetry.skipped))
            return
        }
        _state.update {
            it.copy(
                phase = StudioPhase.Curating,
                telemetry = it.telemetry.copy(step = TelemetryStep.ANALYZING, analyzed = 0, analyzeTotal = list.size),
            )
        }
        launchWork {
            try {
                val result = curate(list)
                _state.update { s ->
                    StudioReducer.withSelection(s, result, result.chosen.photoIds, candidates).let {
                        it.copy(
                            phase = StudioPhase.Preparing,
                            grade = it.grade.copy(look = result.suggestedLook),
                            telemetry = it.telemetry.copy(step = TelemetryStep.PREPARING),
                        )
                    }
                }
                prepareAndShow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                resetToEmpty(deps.errors.map(e))
            }
        }
    }

    private suspend fun curate(list: List<PhotoCandidate>): CurationResult {
        var decided: CurationResult? = null
        deps.curation.curate(list).flowOn(deps.dispatchers.default).collect { progress ->
            _state.update { StudioReducer.onCuration(it, progress) }
            if (progress is CurationProgress.Decided) decided = progress.result
        }
        return decided ?: throw IllegalStateException("Curation finished without a decision")
    }

    /** Prepares the current selection, builds the scene and lands in [StudioPhase.Ready]. */
    private suspend fun prepareAndShow() {
        val s = _state.value
        val ids = s.selection.photoIds
        val chosen = ids.map { id -> checkNotNull(candidates[id]) { "Unknown candidate $id" } }
        store.acquire(chosen, s.curation?.analyses.orEmpty(), hero = s.selection.mode == StoryMode.HERO)
        val latest = _state.value
        val scene = SceneBuilder.build(latest, store.photos(ids), deps.harmonizer, deps.composer)
        val ready = latest.copy(
            phase = StudioPhase.Ready,
            scene = scene,
            sceneRevision = latest.sceneRevision + 1,
            telemetry = latest.telemetry.copy(step = TelemetryStep.DONE),
        )
        _state.value = ready
        stable = ready
        try {
            store.retainOnly(ids)
        } catch (e: Exception) {
            _state.update { it.copy(error = deps.errors.map(e)) }
        }
    }

    private fun forceMode(mode: StoryMode) {
        if (!requireReady()) return
        val s = _state.value
        val result = s.curation ?: return
        if (mode == s.selection.mode) {
            _state.update { it.copy(modeDrawerOpen = false) }
            return
        }
        reprepare { current ->
            val decided = withContext(deps.dispatchers.default) { deps.curation.decide(result, mode) }
            StudioReducer.withSelection(current, decided, decided.chosen.photoIds, candidates)
        }
    }

    private fun swapCandidate(slot: Int, candidateId: String) {
        if (!requireReady()) return
        val s = _state.value
        val ids = s.selection.photoIds
        if (slot !in ids.indices || ids[slot] == candidateId) return
        val existing = ids.indexOf(candidateId)
        if (existing >= 0) {
            applyPure(StudioIntent.SwapSlots(slot, existing))
            return
        }
        val result = s.curation ?: return
        val analysis = result.analyses[candidateId]
        if (candidateId !in candidates || analysis == null || analysis.rejectReason != null) return
        val newIds = ids.toMutableList().apply { this[slot] = candidateId }
        reprepare { current -> StudioReducer.withSelection(current, result, newIds, candidates) }
    }

    /** Leaves Ready for Preparing, applies [select] (a new selection) and prepares it; failures restore [stable]. */
    private fun reprepare(select: suspend (StudioState) -> StudioState) {
        _state.update {
            it.copy(
                phase = StudioPhase.Preparing,
                modeDrawerOpen = false,
                telemetry = it.telemetry.copy(step = TelemetryStep.PREPARING),
            )
        }
        launchWork {
            try {
                val next = select(_state.value)
                _state.value = next.copy(phase = StudioPhase.Preparing)
                prepareAndShow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                restoreStable(deps.errors.map(e))
            }
        }
    }

    /** Returns to the last Ready selection, re-preparing it when the cancelled work invalidated its photos. */
    private suspend fun restoreStable(error: StudioError?) {
        val target = stable
        if (target == null || restoring) {
            resetToEmpty(error)
            return
        }
        val current = _state.value
        val merged = current.copy(
            curation = target.curation,
            selection = target.selection,
            template = target.template,
            telemetry = target.telemetry,
            export = if (current.export.hdrBoostUserSet) {
                current.export
            } else {
                current.export.copy(hdrBoost = target.export.hdrBoost)
            },
            error = error,
        )
        if (store.covers(target.selection.photoIds, target.selection.mode == StoryMode.HERO)) {
            _state.value = merged.copy(phase = StudioPhase.Ready)
            rebuildScene()
            return
        }
        _state.value = merged.copy(phase = StudioPhase.Preparing)
        restoring = true
        try {
            prepareAndShow()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            resetToEmpty(deps.errors.map(e))
        } finally {
            restoring = false
        }
    }

    // endregion

    // region export

    private fun export(share: Boolean) {
        if (!requireReady()) return
        _state.update {
            it.copy(phase = StudioPhase.Exporting, export = it.export.copy(progress = null, sharing = share))
        }
        launchWork {
            try {
                val result = runExport()
                _state.update {
                    it.copy(
                        phase = StudioPhase.Ready,
                        export = it.export.copy(progress = null, lastResult = result, sharing = false),
                    )
                }
                stable = _state.value
                effect(
                    if (share) {
                        StudioEffect.ShareExport(result.uri, result.mimeType)
                    } else {
                        StudioEffect.ShowMessage(StudioMessage.ExportSaved(result))
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        phase = StudioPhase.Ready,
                        export = it.export.copy(progress = null, sharing = false),
                        error = deps.errors.map(e),
                    )
                }
            }
        }
    }

    private suspend fun runExport(): ExportResult {
        val s = _state.value
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
                is ExportProgress.Stage -> _state.update { StudioReducer.onExportStage(it, progress) }
                is ExportProgress.Done -> result = progress.result
            }
        }
        return result ?: throw IllegalStateException("Export finished without a result")
    }

    // endregion

    // region scope

    @OptIn(FlowPreview::class)
    private suspend fun runScopePipeline() {
        _state
            .map { ScopeKey(active = it.scope != ScopeMode.OFF && it.scene != null, revision = it.sceneRevision) }
            .distinctUntilChanged()
            .debounce(SCOPE_DEBOUNCE_MS)
            .conflate()
            .collect { key -> if (key.active) renderScope(key.revision) }
    }

    private suspend fun renderScope(revision: Long) {
        val scene = _state.value.takeIf { it.sceneRevision == revision }?.scene ?: return
        try {
            val pixels: ImagePixels = deps.renderer.renderPreviewPixels(scene, SCOPE_WIDTH, SCOPE_HEIGHT)
            _state.update {
                if (it.sceneRevision == revision && it.scope != ScopeMode.OFF) it.copy(scopePixels = pixels) else it
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = deps.errors.map(e)) }
        }
    }

    private data class ScopeKey(val active: Boolean, val revision: Long)

    // endregion

    // region work and lifecycle

    private fun cancelWork() {
        when (_state.value.phase) {
            StudioPhase.Empty, StudioPhase.Ready -> Unit
            StudioPhase.Ingesting, StudioPhase.Curating -> resetToEmpty(error = null)
            StudioPhase.Preparing ->
                if (stable == null || restoring) resetToEmpty(error = null) else launchWork { restoreStable(error = null) }
            StudioPhase.Exporting -> {
                work?.cancel()
                work = null
                _state.update {
                    it.copy(phase = StudioPhase.Ready, export = it.export.copy(progress = null, sharing = false))
                }
            }
        }
    }

    /** Runs [block] as the new [work] after cancelling and joining the previous one. */
    private fun launchWork(block: suspend () -> Unit) {
        val previous = work
        work = scope.launch {
            previous?.cancelAndJoinUnlessSelf()
            block()
        }
    }

    /**
     * Cancels everything, empties the studio synchronously (state shows [error]), then, once the cancelled jobs have
     * finished, releases the prepared photos and closes the ingestion session off the main thread.
     */
    private fun resetToEmpty(error: StudioError?) {
        val cancelled = listOfNotNull(work, ingestJob, debounceJob)
        cancelled.forEach { it.cancel() }
        work = null
        ingestJob = null
        debounceJob = null
        pendingImports.clear()
        startRequested = false
        restoring = false
        stable = null
        candidates.clear()
        ingestedTelemetry = IngestionTelemetry()
        val oldSession = session
        session = null
        val dropped = store.detachAll()
        _state.value = StudioState(error = error)
        cleanupScope.launch {
            cancelled.forEach { it.join() }
            try {
                withContext(deps.dispatchers.io) {
                    if (dropped.isNotEmpty()) deps.preparer.release(dropped)
                    oldSession?.close()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { if (it.error == null) it.copy(error = deps.errors.map(e)) else it }
            }
        }
    }

    /** True in Ready; otherwise tells the user the studio is busy (silently false when there is no story yet). */
    private fun requireReady(): Boolean = when (_state.value.phase) {
        StudioPhase.Ready -> true
        StudioPhase.Empty, StudioPhase.Ingesting -> false
        StudioPhase.Curating, StudioPhase.Preparing, StudioPhase.Exporting -> {
            effect(StudioEffect.ShowMessage(StudioMessage.Busy))
            false
        }
    }

    private fun effect(effect: StudioEffect) {
        effectChannel.trySend(effect)
    }

    private suspend fun Job.cancelAndJoinUnlessSelf() {
        if (this == currentCoroutineContext()[Job]) return
        cancel()
        join()
    }

    // endregion

    internal companion object {
        const val AUTO_CURATION_DEBOUNCE_MS = 600L
        const val SCOPE_DEBOUNCE_MS = 120L
        const val SCOPE_WIDTH = 270
        const val SCOPE_HEIGHT = 480

        private operator fun IngestionTelemetry.plus(other: IngestionTelemetry) = IngestionTelemetry(
            sourcesTotal = sourcesTotal + other.sourcesTotal,
            filesRead = filesRead + other.filesRead,
            candidatesAccepted = candidatesAccepted + other.candidatesAccepted,
            skipped = skipped + other.skipped,
            bytesExtracted = bytesExtracted + other.bytesExtracted,
        )
    }
}
