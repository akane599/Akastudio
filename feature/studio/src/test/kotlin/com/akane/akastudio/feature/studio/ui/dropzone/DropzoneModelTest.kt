package com.akane.akastudio.feature.studio.ui.dropzone

import com.akane.akastudio.feature.studio.mvi.StudioPhase
import com.akane.akastudio.feature.studio.mvi.StudioState
import com.akane.akastudio.feature.studio.mvi.TelemetryStep
import com.akane.akastudio.feature.studio.mvi.TelemetryUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DropzoneModelTest {

    private fun statuses(step: TelemetryStep): List<StepStatus> =
        DropzoneModel.steps(TelemetryUi(step = step)).map { it.status }

    @Test
    fun `steps are always the four pipeline lines in display order`() {
        TelemetryStep.entries.forEach { step ->
            assertEquals(PipelineStep.entries.toList(), DropzoneModel.steps(TelemetryUi(step = step)).map { it.step })
        }
    }

    @Test
    fun `idle shows every step pending`() {
        assertEquals(List(4) { StepStatus.PENDING }, statuses(TelemetryStep.IDLE))
    }

    @Test
    fun `reading runs the read step only`() {
        assertEquals(
            listOf(StepStatus.ACTIVE, StepStatus.PENDING, StepStatus.PENDING, StepStatus.PENDING),
            statuses(TelemetryStep.READING),
        )
    }

    @Test
    fun `analyzing completes reading and runs clustering`() {
        assertEquals(
            listOf(StepStatus.DONE, StepStatus.ACTIVE, StepStatus.PENDING, StepStatus.PENDING),
            statuses(TelemetryStep.ANALYZING),
        )
    }

    @Test
    fun `deciding completes clustering and the focus check together and runs the synergy matrix`() {
        assertEquals(
            listOf(StepStatus.DONE, StepStatus.DONE, StepStatus.DONE, StepStatus.ACTIVE),
            statuses(TelemetryStep.DECIDING),
        )
    }

    @Test
    fun `preparing and done show every step complete`() {
        assertEquals(List(4) { StepStatus.DONE }, statuses(TelemetryStep.PREPARING))
        assertEquals(List(4) { StepStatus.DONE }, statuses(TelemetryStep.DONE))
    }

    @Test
    fun `at most one step is active at a time`() {
        TelemetryStep.entries.forEach { step ->
            assertTrue(step.name, statuses(step).count { it == StepStatus.ACTIVE } <= 1)
        }
    }

    @Test
    fun `progress follows sources read while reading`() {
        val t = TelemetryUi(step = TelemetryStep.READING, photosRead = 10, sourcesTotal = 40)
        assertEquals(0.25f, DropzoneModel.progress(t), 1e-6f)
    }

    @Test
    fun `progress follows Analyzing done over total while analyzing`() {
        val t = TelemetryUi(step = TelemetryStep.ANALYZING, analyzed = 18, analyzeTotal = 30)
        assertEquals(0.6f, DropzoneModel.progress(t), 1e-6f)
    }

    @Test
    fun `progress is zero when a total is unknown and clamped when counts overshoot`() {
        assertEquals(0f, DropzoneModel.progress(TelemetryUi(step = TelemetryStep.ANALYZING, analyzed = 5)), 0f)
        assertEquals(0f, DropzoneModel.progress(TelemetryUi(step = TelemetryStep.READING, photosRead = 3)), 0f)
        val over = TelemetryUi(step = TelemetryStep.READING, photosRead = 12, sourcesTotal = 10)
        assertEquals(1f, DropzoneModel.progress(over), 0f)
    }

    @Test
    fun `progress is idle-empty and full from deciding on`() {
        assertEquals(0f, DropzoneModel.progress(TelemetryUi(step = TelemetryStep.IDLE)), 0f)
        listOf(TelemetryStep.DECIDING, TelemetryStep.PREPARING, TelemetryStep.DONE).forEach {
            assertEquals(it.name, 1f, DropzoneModel.progress(TelemetryUi(step = it)), 0f)
        }
    }

    @Test
    fun `add more and curate now need accepted photos while still ingesting`() {
        val ingesting = StudioState(phase = StudioPhase.Ingesting, telemetry = TelemetryUi(accepted = 3))
        assertTrue(DropzoneModel.canAddMore(ingesting))
        assertFalse(DropzoneModel.canAddMore(ingesting.copy(telemetry = TelemetryUi(accepted = 0))))
        assertFalse(DropzoneModel.canAddMore(ingesting.copy(phase = StudioPhase.Curating)))
        assertFalse(DropzoneModel.canAddMore(ingesting.copy(phase = StudioPhase.Empty)))
    }

    @Test
    fun `cancel is offered only for the dropzone's running work`() {
        assertTrue(DropzoneModel.canCancel(StudioPhase.Ingesting))
        assertTrue(DropzoneModel.canCancel(StudioPhase.Curating))
        assertTrue(DropzoneModel.canCancel(StudioPhase.Preparing))
        assertFalse(DropzoneModel.canCancel(StudioPhase.Empty))
        assertFalse(DropzoneModel.canCancel(StudioPhase.Ready))
        assertFalse(DropzoneModel.canCancel(StudioPhase.Exporting))
    }

    @Test
    fun `retry reopens a picker only where an import is accepted`() {
        assertTrue(DropzoneModel.retryReopensPicker(StudioPhase.Empty))
        assertTrue(DropzoneModel.retryReopensPicker(StudioPhase.Ingesting))
        assertFalse(DropzoneModel.retryReopensPicker(StudioPhase.Curating))
        assertFalse(DropzoneModel.retryReopensPicker(StudioPhase.Preparing))
        assertFalse(DropzoneModel.retryReopensPicker(StudioPhase.Ready))
        assertFalse(DropzoneModel.retryReopensPicker(StudioPhase.Exporting))
    }

    @Test
    fun `welcome shows only in the empty phase`() {
        assertTrue(DropzoneModel.showsWelcome(StudioPhase.Empty))
        listOf(StudioPhase.Ingesting, StudioPhase.Curating, StudioPhase.Preparing, StudioPhase.Ready, StudioPhase.Exporting)
            .forEach { assertFalse(it.toString(), DropzoneModel.showsWelcome(it)) }
    }
}
