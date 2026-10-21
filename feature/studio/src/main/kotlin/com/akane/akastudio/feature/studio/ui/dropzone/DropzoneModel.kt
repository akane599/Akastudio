package com.akane.akastudio.feature.studio.ui.dropzone

import androidx.compose.runtime.Immutable
import com.akane.akastudio.feature.studio.mvi.StudioPhase
import com.akane.akastudio.feature.studio.mvi.StudioState
import com.akane.akastudio.feature.studio.mvi.TelemetryStep
import com.akane.akastudio.feature.studio.mvi.TelemetryUi

/** The four lines of the live curation readout, in display order. */
internal enum class PipelineStep { READ, CLUSTER, FOCUS, SYNERGY }

/** How a [PipelineStep] is drawn: dim before it starts, a pulsing dot while it runs, a check mark once done. */
internal enum class StepStatus { PENDING, ACTIVE, DONE }

/** One readout line and its status. */
@Immutable
internal data class PipelineStepUi(val step: PipelineStep, val status: StepStatus)

/** Which of the source pickers the user opened last; Retry reopens it. */
internal enum class ImportSource { PHOTOS, ZIP }

/** Pure presentation rules for the Dropzone; everything here is decided from [StudioState] alone. */
internal object DropzoneModel {

    /** True while the welcome (empty) layout is shown; every other phase shows the pipeline readout. */
    fun showsWelcome(phase: StudioPhase): Boolean = phase == StudioPhase.Empty

    /**
     * Maps the telemetry step to the readout. The curator reports analysis progress first, then clusters and
     * technical rejections together, then its decision; so clustering is the running line during analysis,
     * the clustering and eye/focus lines complete together, and the synergy line runs while it decides.
     */
    fun steps(telemetry: TelemetryUi): List<PipelineStepUi> {
        val statuses = when (telemetry.step) {
            TelemetryStep.IDLE -> listOf(StepStatus.PENDING, StepStatus.PENDING, StepStatus.PENDING, StepStatus.PENDING)
            TelemetryStep.READING -> listOf(StepStatus.ACTIVE, StepStatus.PENDING, StepStatus.PENDING, StepStatus.PENDING)
            TelemetryStep.ANALYZING -> listOf(StepStatus.DONE, StepStatus.ACTIVE, StepStatus.PENDING, StepStatus.PENDING)
            TelemetryStep.DECIDING -> listOf(StepStatus.DONE, StepStatus.DONE, StepStatus.DONE, StepStatus.ACTIVE)
            TelemetryStep.PREPARING, TelemetryStep.DONE ->
                listOf(StepStatus.DONE, StepStatus.DONE, StepStatus.DONE, StepStatus.DONE)
        }
        return PipelineStep.entries.mapIndexed { i, step -> PipelineStepUi(step, statuses[i]) }
    }

    /**
     * Fraction for the thin progress line, in [0, 1]: sources read while reading, `Analyzing(done, total)` while
     * analyzing, and full once the curator has clustered. Zero when a total is not yet known.
     */
    fun progress(telemetry: TelemetryUi): Float = when (telemetry.step) {
        TelemetryStep.IDLE -> 0f
        TelemetryStep.READING -> fraction(telemetry.photosRead, telemetry.sourcesTotal)
        TelemetryStep.ANALYZING -> fraction(telemetry.analyzed, telemetry.analyzeTotal)
        TelemetryStep.DECIDING, TelemetryStep.PREPARING, TelemetryStep.DONE -> 1f
    }

    /** "+ Add more" and "Curate now" appear once photos were accepted and curation has not started. */
    fun canAddMore(state: StudioState): Boolean =
        state.phase == StudioPhase.Ingesting && state.telemetry.accepted > 0

    /** Cancel is offered while there is cancellable work in the dropzone's phases. */
    fun canCancel(phase: StudioPhase): Boolean =
        phase == StudioPhase.Ingesting || phase == StudioPhase.Curating || phase == StudioPhase.Preparing

    /** Retry reopens the last picker only when an import would be accepted; otherwise it just clears the error. */
    fun retryReopensPicker(phase: StudioPhase): Boolean =
        phase == StudioPhase.Empty || phase == StudioPhase.Ingesting

    private fun fraction(done: Int, total: Int): Float =
        if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}
