package com.akane.akastudio.feature.studio.mvi

import com.akane.akastudio.core.composer.api.StoryTemplate
import com.akane.akastudio.core.curator.api.CurationProgress
import com.akane.akastudio.core.curator.api.CurationResult
import com.akane.akastudio.core.export.api.ExportProgress
import com.akane.akastudio.core.ingestion.model.IngestionTelemetry
import com.akane.akastudio.core.ingestion.model.PhotoCandidate
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toPersistentMap

/**
 * Pure state transitions. [reduce] handles every intent that needs no dependency; the view model handles the
 * rest and folds pipeline results in through the other functions.
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

    /** Folds a non-terminal curation step into telemetry. */
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
            telemetry = state.telemetry.copy(
                clusters = progress.result.clusters.size,
                rejected = progress.result.rejectedIds.size,
            ),
        )
    }

    /**
     * Commits [result] with the story photos [photoIds] (slot order): selection, template (kept when its count
     * still fits, else the first template for the count), telemetry and the HDR default. Leaves the phase alone.
     */
    fun withSelection(
        state: StudioState,
        result: CurationResult,
        photoIds: List<String>,
        candidates: Map<String, PhotoCandidate>,
    ): StudioState {
        val mode = result.chosen.mode
        val slots = photoIds.indices.associateWith { i ->
            checkNotNull(candidates[photoIds[i]]) { "Unknown candidate ${photoIds[i]}" }
        }
        val selection = SelectionUi(
            mode = mode,
            photoIds = photoIds.toImmutableList(),
            slots = slots.toImmutableMap(),
            alternatives = alternatives(result, photoIds, candidates.keys),
        )
        val template = state.template?.takeIf { it.photoCount == mode.photoCount }
            ?: StoryTemplate.forCount(mode.photoCount).firstOrNull()
            ?: throw IllegalStateException("No template for ${mode.photoCount} photos")
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
