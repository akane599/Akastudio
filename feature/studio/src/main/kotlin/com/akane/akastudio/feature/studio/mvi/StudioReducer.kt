package com.akane.akastudio.feature.studio.mvi

import com.akane.akastudio.core.color.api.ImagePixels
import com.akane.akastudio.core.color.api.RenderScene
import com.akane.akastudio.core.composer.api.StoryTemplate
import com.akane.akastudio.core.curator.api.CurationOption
import com.akane.akastudio.core.curator.api.CurationProgress
import com.akane.akastudio.core.curator.api.CurationResult
import com.akane.akastudio.core.export.api.ExportProgress
import com.akane.akastudio.core.export.api.ExportResult
import com.akane.akastudio.core.ingestion.model.IngestionTelemetry
import com.akane.akastudio.core.ingestion.model.PhotoCandidate
import com.akane.akastudio.feature.studio.domain.StudioError
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toPersistentMap

/**
 * Pure state transitions. [reduce] handles every intent that needs no dependency. Every other function shapes one
 * step of the pipeline's state machine; `StoryGeneration` only sequences them and never edits the state by hand, so
 * each phase has exactly one place that defines what the screen shows in it.
 */
internal object StudioReducer {

    const val MAX_GUTTER_PX = 48f
    const val MAX_CORNER_RADIUS_PX = 64f

    /** Applies a dependency-free intent. Intents that need work (imports, mode changes, export...) return [state]. */
    fun reduce(state: StudioState, intent: StudioIntent): StudioState = when (intent) {
        is StudioIntent.SetCellOverride -> state.copy(
            overrides = if (intent.override == null) {
                state.overrides.toPersistentMap().remove(intent.photoId)
            } else {
                state.overrides.toPersistentMap().put(intent.photoId, intent.override)
            },
        )
        is StudioIntent.SelectTemplate ->
            if (intent.template.photoCount == state.selection.mode?.photoCount) state.copy(template = intent.template) else state
        is StudioIntent.SetGutter -> intent.px.finiteOrNull()?.let {
            state.copy(layout = state.layout.copy(gutterPx = it.coerceIn(0f, MAX_GUTTER_PX)))
        } ?: state
        is StudioIntent.SetCornerRadius -> intent.px.finiteOrNull()?.let {
            state.copy(layout = state.layout.copy(cornerRadiusPx = it.coerceIn(0f, MAX_CORNER_RADIUS_PX)))
        } ?: state
        is StudioIntent.SetBackground -> state.copy(layout = state.layout.copy(background = intent.style))
        is StudioIntent.SetExifCaption -> state.copy(layout = state.layout.copy(showExifCaption = intent.show))
        is StudioIntent.SelectLook -> state.copy(grade = state.grade.copy(look = intent.look))
        is StudioIntent.AdjustGrade -> intent.value.finiteOrNull()?.let {
            state.copy(grade = state.grade.with(intent.field, it))
        } ?: state
        is StudioIntent.SetCompare -> when (val split = intent.split) {
            null -> state.copy(compare = null)
            else -> split.finiteOrNull()?.let { state.copy(compare = it.coerceIn(0f, 1f)) } ?: state
        }
        is StudioIntent.SetScope -> state.copy(
            scope = intent.mode,
            scopePixels = if (intent.mode == ScopeMode.OFF) null else state.scopePixels,
        )
        is StudioIntent.SelectTab -> state.copy(tab = intent.tab)
        StudioIntent.ToggleModeDrawer -> state.copy(modeDrawerOpen = !state.modeDrawerOpen)
        is StudioIntent.SetExportFormat -> state.copy(export = state.export.copy(format = intent.format))
        is StudioIntent.SetExportResolution -> state.copy(export = state.export.copy(resolution = intent.resolution))
        is StudioIntent.SetHdrBoost ->
            state.copy(export = state.export.copy(hdrBoost = intent.enabled, hdrBoostUserSet = true))
        StudioIntent.DismissError -> state.copy(error = null)
        is StudioIntent.SwapSlots -> swapSlots(state, intent.from, intent.to)
        is StudioIntent.ImportPhotos, is StudioIntent.ImportZip, StudioIntent.StartCuration, StudioIntent.CancelWork,
        is StudioIntent.ForceMode, is StudioIntent.SwapCandidate, is StudioIntent.Export, StudioIntent.Reset,
        StudioIntent.PickerOpened, StudioIntent.PickerClosed,
        -> state
    }

    /** True when the change from [before] to [after] alters what [SceneBuilder] would build. */
    fun sceneInputsChanged(before: StudioState, after: StudioState): Boolean =
        before.template != after.template ||
            before.layout != after.layout ||
            before.overrides != after.overrides ||
            before.grade != after.grade ||
            before.selection.photoIds != after.selection.photoIds

    /** Shows ingestion telemetry ([telemetry] is already accumulated across the session's imports). */
    fun onIngestion(state: StudioState, telemetry: IngestionTelemetry, accepted: Int): StudioState = state.copy(
        telemetry = state.telemetry.copy(
            sourcesTotal = telemetry.sourcesTotal,
            photosRead = telemetry.filesRead,
            accepted = accepted,
            skipped = telemetry.skipped,
        ),
    )

    /**
     * Folds a curation step into telemetry. [CurationProgress.Decided] also records the curator's own pick as
     * [StudioState.aiRecommendedMode], replacing any earlier run's; forced modes never pass through here.
     */
    fun onCuration(state: StudioState, progress: CurationProgress): StudioState = when (progress) {
        is CurationProgress.Analyzing -> state.copy(
            telemetry = state.telemetry.copy(
                step = TelemetryStep.ANALYZING,
                analyzed = progress.done,
                analyzeTotal = progress.total,
            ),
        )
        is CurationProgress.Clustered -> state.copy(
            telemetry = state.telemetry.copy(
                step = TelemetryStep.DECIDING,
                clusters = progress.clusterCount,
                rejected = progress.rejectedCount,
            ),
        )
        is CurationProgress.Decided -> state.copy(
            aiRecommendedMode = progress.result.chosen.mode,
            telemetry = state.telemetry.copy(
                clusters = progress.result.clusters.size,
                rejected = progress.result.rejectedIds.size,
            ),
        )
    }

    /**
     * Commits [result] with the story photos [photoIds] (slot order) for the same story: the current template is kept
     * when its count still fits (a photo swap never changes the layout), else [templateFor] picks one. Also sets the
     * selection, telemetry and the HDR default. Leaves the phase alone.
     */
    fun withSelection(
        state: StudioState,
        result: CurationResult,
        photoIds: List<String>,
        candidates: Map<String, PhotoCandidate>,
    ): StudioState {
        val kept = state.template?.takeIf { it.photoCount == result.chosen.mode.photoCount }
        return select(state, result, photoIds, candidates, kept ?: templateFor(result.chosen, current = null))
    }

    /**
     * Commits [result] after the user forced its mode: the photo count changed, so the curator's layout for the
     * offered option is adopted (see [templateFor]); the current template only survives when no layout is known and
     * it still fits.
     */
    fun withForcedMode(
        state: StudioState,
        result: CurationResult,
        candidates: Map<String, PhotoCandidate>,
    ): StudioState =
        select(state, result, result.chosen.photoIds, candidates, templateFor(result.chosen, current = state.template))

    /**
     * The template for [option]: the curator's [CurationOption.layoutId] when it names a [StoryTemplate] for the
     * option's photo count, else [current] when it fits that count, else the first template for the count. An
     * unknown or mismatched id is ignored rather than trusted, since the curator's catalogue is opaque to the studio.
     */
    fun templateFor(option: CurationOption, current: StoryTemplate?): StoryTemplate {
        val count = option.mode.photoCount
        return option.layoutId
            ?.let { id -> StoryTemplate.entries.firstOrNull { it.name == id } }
            ?.takeIf { it.photoCount == count }
            ?: current?.takeIf { it.photoCount == count }
            ?: StoryTemplate.forCount(count).firstOrNull()
            ?: throw IllegalStateException("No template for $count photos")
    }

    private fun select(
        state: StudioState,
        result: CurationResult,
        photoIds: List<String>,
        candidates: Map<String, PhotoCandidate>,
        template: StoryTemplate,
    ): StudioState {
        val mode = result.chosen.mode
        check(template.photoCount == mode.photoCount) { "$template does not fit ${mode.photoCount} photos" }
        val slots = photoIds.indices.associateWith { i ->
            checkNotNull(candidates[photoIds[i]]) { "Unknown candidate ${photoIds[i]}" }
        }
        val selection = SelectionUi(
            mode = mode,
            photoIds = photoIds.toImmutableList(),
            slots = slots.toImmutableMap(),
            alternatives = alternatives(result, photoIds, candidates.keys),
        )
        val hasGainMap = slots.values.any { it.metadata.hasUltraHdrGainMap }
        return state.copy(
            curation = result,
            selection = selection,
            template = template,
            telemetry = state.telemetry.copy(finalMode = mode, harmonyPercent = result.note.harmonyPercent),
            export = if (state.export.hdrBoostUserSet) state.export else state.export.copy(hdrBoost = hasGainMap),
        )
    }

    /** Shows a running export stage. */
    fun onExportStage(state: StudioState, stage: ExportProgress.Stage): StudioState = state.copy(
        export = state.export.copy(progress = ExportProgressUi(stage.name, stage.fraction.coerceIn(0f, 1f))),
    )

    // region phase transitions

    /** Sources are being read; a shown error is cleared by the new import. */
    fun ingesting(state: StudioState): StudioState =
        state.copy(
            phase = StudioPhase.Ingesting,
            error = null,
            telemetry = state.telemetry.copy(step = TelemetryStep.READING),
        )

    /** The curator starts over [total] candidates. */
    fun curating(state: StudioState, total: Int): StudioState = state.copy(
        phase = StudioPhase.Curating,
        telemetry = state.telemetry.copy(step = TelemetryStep.ANALYZING, analyzed = 0, analyzeTotal = total),
    )

    /**
     * The curator decided a new story: [result]'s choice becomes the selection with the curator's layout (see
     * [templateFor]; a template left in [state] belongs to no story yet and is never carried over), its look the
     * grade, and preparation begins.
     */
    fun decided(state: StudioState, result: CurationResult, candidates: Map<String, PhotoCandidate>): StudioState =
        select(state, result, result.chosen.photoIds, candidates, templateFor(result.chosen, current = null)).let {
            it.copy(
                phase = StudioPhase.Preparing,
                grade = it.grade.copy(look = result.suggestedLook),
                telemetry = it.telemetry.copy(step = TelemetryStep.PREPARING),
            )
        }

    /** A Ready story leaves for re-preparation (mode or photo change, or a restore after one failed). */
    fun preparing(state: StudioState): StudioState = state.copy(
        phase = StudioPhase.Preparing,
        modeDrawerOpen = false,
        telemetry = state.telemetry.copy(step = TelemetryStep.PREPARING),
    )

    /** Preparation finished: [scene] goes on screen and the story is editable. */
    fun ready(state: StudioState, scene: RenderScene): StudioState =
        withScene(state, scene).copy(
            phase = StudioPhase.Ready,
            telemetry = state.telemetry.copy(step = TelemetryStep.DONE),
        )

    /** A rebuilt [scene] for the story on screen; bumps [StudioState.sceneRevision]. */
    fun withScene(state: StudioState, scene: RenderScene): StudioState =
        state.copy(scene = scene, sceneRevision = state.sceneRevision + 1)

    /**
     * [current] carrying [target]'s story again (the last Ready state) after a cancelled or failed re-preparation,
     * showing [error]. Edits made since (grade, layout, export settings) are kept; the HDR default follows the story
     * unless the user set it. The caller picks the phase: Ready when the photos are still prepared, else Preparing.
     */
    fun restored(current: StudioState, target: StudioState, error: StudioError?): StudioState = current.copy(
        curation = target.curation,
        aiRecommendedMode = target.aiRecommendedMode,
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

    /** An export starts; [share] opens the share sheet when it is done. */
    fun exporting(state: StudioState, share: Boolean): StudioState =
        state.copy(phase = StudioPhase.Exporting, export = state.export.copy(progress = null, sharing = share))

    /** The export produced [result]. */
    fun exportFinished(state: StudioState, result: ExportResult): StudioState =
        exportEnded(state).let { it.copy(export = it.export.copy(lastResult = result)) }

    /** The export was cancelled; the story is editable again and nothing is shown. */
    fun exportCancelled(state: StudioState): StudioState = exportEnded(state)

    /** The export failed with [error]; a failure the mapper has no variant for is shown as [StudioError.Export]. */
    fun exportFailed(state: StudioState, error: StudioError): StudioState = exportEnded(state).copy(
        error = if (error is StudioError.Unknown) StudioError.Export(error.message) else error,
    )

    private fun exportEnded(state: StudioState): StudioState =
        state.copy(phase = StudioPhase.Ready, export = state.export.copy(progress = null, sharing = false))

    /** A scope readback for [revision]; dropped when the scene moved on or the scope was turned off meanwhile. */
    fun scopeRendered(state: StudioState, revision: Long, pixels: ImagePixels): StudioState =
        if (state.sceneRevision == revision && state.scope != ScopeMode.OFF) state.copy(scopePixels = pixels) else state

    // endregion

    private fun swapSlots(state: StudioState, from: Int, to: Int): StudioState {
        val ids = state.selection.photoIds
        if (from == to || from !in ids.indices || to !in ids.indices) return state
        val reordered = ids.toMutableList().apply { this[from] = ids[to]; this[to] = ids[from] }
        val slots = state.selection.slots
        val alternatives = state.selection.alternatives
        return state.copy(
            selection = state.selection.copy(
                photoIds = reordered.toImmutableList(),
                slots = swapKeys(slots, from, to),
                alternatives = swapKeys(alternatives, from, to),
            ),
        )
    }

    private fun <V> swapKeys(map: Map<Int, V>, a: Int, b: Int) = map.toPersistentMap().let { m ->
        val va = map[a]
        val vb = map[b]
        var out = m.remove(a).remove(b)
        if (vb != null) out = out.put(a, vb)
        if (va != null) out = out.put(b, va)
        out
    }

    /** Per slot: usable members of the slot photo's cluster, best quality first, excluding the story's photos. */
    private fun alternatives(
        result: CurationResult,
        photoIds: List<String>,
        known: Set<String>,
    ) = photoIds.indices.associateWith { i ->
        val id = photoIds[i]
        val cluster = result.clusters.firstOrNull { id in it.memberIds }
        cluster?.memberIds.orEmpty()
            .filter { member ->
                val analysis = result.analyses[member]
                member !in photoIds && member in known && analysis != null && analysis.rejectReason == null
            }
            .sortedByDescending { result.analyses.getValue(it).quality }
            .toImmutableList()
    }.toImmutableMap()

    private fun Float.finiteOrNull(): Float? = takeIf { it.isFinite() }
}
