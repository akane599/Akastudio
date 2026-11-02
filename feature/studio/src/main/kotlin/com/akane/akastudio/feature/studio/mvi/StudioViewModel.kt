package com.akane.akastudio.feature.studio.mvi

import android.util.Log
import androidx.lifecycle.ViewModel
import com.akane.akastudio.core.color.api.ImagePixels
import com.akane.akastudio.core.ingestion.api.IngestionRequest
import com.akane.akastudio.feature.studio.domain.StudioDependencies
import com.akane.akastudio.feature.studio.domain.StudioError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The studio's MVI store: [onIntent] in, [state] and one-shot [effects] out.
 *
 * The view model itself owns only what spans stories: the screen state, the effects, the system pickers' hold on
 * auto-curation and the one current [StoryGeneration]. A generation is the batch of imports that becomes one story;
 * it owns that batch's resources, jobs and phase transitions (see its documentation for the state machine). An
 * import into a Ready studio and a reset retire the current generation and the next import starts a fresh one, so
 * nothing of a story outlives it except its retirement cleanup. Dependency-free intents go through [StudioReducer]
 * here and rebuild the scene through the generation.
 *
 * Invariant: `current == null` exactly when the phase is [StudioPhase.Empty].
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

    /** Outlives [scope] so a retirement started by [onCleared] still releases photos and deletes the session folder. */
    private val retirements = CoroutineScope(SupervisorJob() + deps.dispatchers.main)

    /** The generation on screen, or null in [StudioPhase.Empty]. */
    private var current: StoryGeneration? = null

    /** Counts retirements, so a retirement's late cleanup failure can tell whether a newer one followed. */
    private var retirementCount = 0L

    /**
     * System pickers open now (see [StudioIntent.PickerOpened]); auto-curation waits while any is. Counted, so a
     * stacked second picker keeps the hold when the first one closes.
     */
    private var openPickers = 0

    /** True when an open picker is "+ Add more" (opened while reading): its result only extends this batch. */
    private var addingMore = false

    private val host = object : StoryHost {
        override val deps: StudioDependencies get() = this@StudioViewModel.deps
        override val state: MutableStateFlow<StudioState> get() = _state
        override val pickersOpen: Boolean get() = openPickers > 0
        override fun effect(effect: StudioEffect) = this@StudioViewModel.effect(effect)
        override fun apply(intent: StudioIntent) = applyPure(intent)
        override fun retire(generation: StoryGeneration, error: StudioError?) {
            if (generation === current) this@StudioViewModel.retire(error)
        }
    }

    init {
        scope.launch { runScopePipeline() }
    }

    /** Handles one user interaction. Main thread only. */
    fun onIntent(intent: StudioIntent) {
        when (intent) {
            is StudioIntent.ImportPhotos ->
                if (intent.uris.isNotEmpty()) import(IngestionRequest.PickedMedia(intent.uris.toList()))
            // A blank name is looked up when the zip's turn comes.
            is StudioIntent.ImportZip -> import(IngestionRequest.ZipArchive(intent.uri, intent.displayName.orEmpty()))
            StudioIntent.PickerOpened -> onPickerOpened()
            StudioIntent.PickerClosed -> onPickerClosed()
            StudioIntent.StartCuration -> current?.requestCuration()
            StudioIntent.CancelWork -> cancelWork()
            is StudioIntent.ForceMode -> ready()?.forceMode(intent.mode)
            is StudioIntent.SwapCandidate -> ready()?.swapCandidate(intent.slot, intent.candidateId)
            is StudioIntent.SwapSlots -> if (ready() != null) applyPure(intent)
            // Crop edits wait for Ready like swaps, but silently: a pinch sends one per frame, so no Busy per frame.
            is StudioIntent.SetCellOverride -> if (_state.value.phase == StudioPhase.Ready) applyPure(intent)
            is StudioIntent.Export -> ready()?.export(intent.share)
            StudioIntent.Reset -> retire(error = null)
            else -> applyPure(intent)
        }
    }

    override fun onCleared() {
        retire(error = null)
    }

    // region pure intents

    private fun applyPure(intent: StudioIntent) {
        val before = _state.value
        val after = StudioReducer.reduce(before, intent)
        if (after == before) return
        _state.value = after
        if (StudioReducer.sceneInputsChanged(before, after)) current?.rebuildScene()
    }

    // endregion

    // region imports and pickers

    private fun import(request: IngestionRequest) {
        when (_state.value.phase) {
            StudioPhase.Curating, StudioPhase.Preparing, StudioPhase.Exporting -> {
                busy()
                return
            }
            // Curation already ran for this batch: the new import starts a new story, unless it is an
            // "+ Add more" result, which only ever extends the batch it was picked for.
            StudioPhase.Ready -> if (addingMore) {
                busy()
                return
            } else {
                retire(error = null)
            }
            StudioPhase.Empty, StudioPhase.Ingesting -> Unit
        }
        val generation = current ?: StoryGeneration(host, scope).also { current = it }
        generation.import(request)
    }

    private fun onPickerOpened() {
        openPickers++
        addingMore = addingMore || _state.value.phase == StudioPhase.Ingesting
        current?.holdCuration()
    }

    /**
     * Runs after the picker's import, if any. Once the last open picker closes, curation follows when ingestion is
     * idle, as after any import. A close without an open (a view model recreated behind the picker) is ignored.
     */
    private fun onPickerClosed() {
        if (openPickers == 0) return
        openPickers--
        if (openPickers > 0) return
        addingMore = false
        current?.onPickersClosed()
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
        val generation = current ?: return
        val scene = _state.value.takeIf { it.sceneRevision == revision }?.scene ?: return
        try {
            val pixels: ImagePixels = deps.renderer.renderPreviewPixels(scene, SCOPE_WIDTH, SCOPE_HEIGHT)
            if (current === generation) _state.update { StudioReducer.scopeRendered(it, revision, pixels) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val latest = _state.value
            if (current === generation && latest.sceneRevision == revision && latest.scope != ScopeMode.OFF) {
                _state.update { it.copy(error = deps.errors.map(e)) }
            } else {
                Log.w(TAG, "Ignoring a scope failure for an abandoned scene", e)
            }
        }
    }

    private data class ScopeKey(val active: Boolean, val revision: Long)

    // endregion

    // region generations

    private fun cancelWork() {
        when (_state.value.phase) {
            StudioPhase.Empty, StudioPhase.Ready -> Unit
            StudioPhase.Ingesting, StudioPhase.Curating, StudioPhase.Preparing, StudioPhase.Exporting ->
                current?.cancelWork()
        }
    }

    /**
     * Retires the current generation (if any) and empties the studio synchronously, showing [error]. The
     * generation's cleanup then runs in [retirements]; its failure is shown only by the empty studio this retirement
     * left, never by a generation started since.
     */
    private fun retire(error: StudioError?) {
        val retiring = current
        current = null
        _state.value = StudioState(error = error)
        val retirement = ++retirementCount
        val cleanup = retiring?.retire() ?: return
        retirements.launch {
            try {
                cleanup()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cleanup after retiring a story failed", e)
                // Only the empty studio this retirement left may show it, not one a later retirement left.
                if (current == null && retirement == retirementCount) {
                    val shown = deps.errors.map(e)
                    _state.update { if (it.phase == StudioPhase.Empty && it.error == null) it.copy(error = shown) else it }
                }
            }
        }
    }

    /** The generation on screen in Ready; otherwise tells the user the studio is busy (silently null in Empty). */
    private fun ready(): StoryGeneration? = when (_state.value.phase) {
        StudioPhase.Ready -> current
        StudioPhase.Empty, StudioPhase.Ingesting -> null
        StudioPhase.Curating, StudioPhase.Preparing, StudioPhase.Exporting -> {
            busy()
            null
        }
    }

    private fun busy() = effect(StudioEffect.ShowMessage(StudioMessage.Busy))

    private fun effect(effect: StudioEffect) {
        effectChannel.trySend(effect)
    }

    // endregion

    internal companion object {
        const val AUTO_CURATION_DEBOUNCE_MS = 600L
        const val SCOPE_DEBOUNCE_MS = 120L
        const val SCOPE_WIDTH = 270
        const val SCOPE_HEIGHT = 480
        private const val TAG = "StudioViewModel"
    }
}
