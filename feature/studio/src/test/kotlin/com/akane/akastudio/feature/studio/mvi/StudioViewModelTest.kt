package com.akane.akastudio.feature.studio.mvi

import androidx.lifecycle.ViewModelStore
import com.akane.akastudio.core.color.api.HarmonizationParams
import com.akane.akastudio.core.color.api.MasterLook
import com.akane.akastudio.core.composer.api.CanvasSize
import com.akane.akastudio.core.composer.api.StoryTemplate
import com.akane.akastudio.core.curator.api.SceneCluster
import com.akane.akastudio.core.curator.api.StoryMode
import com.akane.akastudio.core.export.api.ExportFormat
import com.akane.akastudio.core.export.api.ExportResolution
import com.akane.akastudio.core.export.api.ExportStage
import com.akane.akastudio.core.ingestion.api.IngestionException
import com.akane.akastudio.core.ingestion.api.IngestionRequest
import com.akane.akastudio.core.ingestion.model.IngestionError
import com.akane.akastudio.core.ingestion.model.IngestionEvent
import com.akane.akastudio.core.ingestion.model.IngestionTelemetry
import com.akane.akastudio.feature.studio.domain.DefaultStudioErrorMapper
import com.akane.akastudio.feature.studio.domain.FakeCurationEngine
import com.akane.akastudio.feature.studio.domain.FakeHarmonizer
import com.akane.akastudio.feature.studio.domain.FakeStudioDependencies
import com.akane.akastudio.feature.studio.domain.StudioError
import com.akane.akastudio.feature.studio.domain.StudioErrorMapper
import com.akane.akastudio.feature.studio.domain.TestData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StudioViewModelTest {

    private val a = TestData.candidate("a", captureMillis = 1_700_000_000_000L)
    private val b = TestData.candidate("b", gainMap = true)
    private val c = TestData.candidate("c")
    private val result = TestData.result(
        chosenMode = StoryMode.COLLAGE_2,
        options = mapOf(
            StoryMode.HERO to listOf("a"),
            StoryMode.COLLAGE_2 to listOf("a", "b"),
            StoryMode.COLLAGE_3 to listOf("a", "b", "c"),
        ),
        analyses = listOf(
            TestData.analysis("a", quality = 0.9f, meanL = 0.3f),
            TestData.analysis("b", quality = 0.8f, meanL = 0.5f),
            TestData.analysis("c", quality = 0.6f, meanL = 0.7f),
        ),
        clusters = listOf(SceneCluster(0, listOf("a", "c"), "a"), SceneCluster(1, listOf("b"), "b")),
    )

    private class Harness(
        val vm: StudioViewModel,
        val deps: FakeStudioDependencies,
        val phases: List<StudioPhase>,
        val effects: List<StudioEffect>,
    ) {
        val state: StudioState get() = vm.state.value
    }

    private fun TestScope.harness(errors: StudioErrorMapper = DefaultStudioErrorMapper()): Harness {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val deps = FakeStudioDependencies(dispatcher, FakeCurationEngine(result), errors)
        deps.ingestScripts += TestData.ingestion(listOf(a, b, c), skipped = 1)
        val vm = StudioViewModel(deps, CoroutineScope(SupervisorJob(backgroundScope.coroutineContext.job) + dispatcher))
        val phases = mutableListOf<StudioPhase>()
        val effects = mutableListOf<StudioEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.state.collect { if (phases.lastOrNull() != it.phase) phases += it.phase }
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        return Harness(vm, deps, phases, effects)
    }

    private fun TestScope.ready(h: Harness) {
        h.vm.onIntent(StudioIntent.ImportPhotos(listOf("u1", "u2", "u3", "u4")))
        advanceUntilIdle()
        assertEquals(StudioPhase.Ready, h.state.phase)
    }

    // region pipeline

    @Test
    fun happy_path_runs_every_phase_and_fills_telemetry() = runTest {
        val h = harness()
        ready(h)

        assertEquals(
            listOf(StudioPhase.Empty, StudioPhase.Ingesting, StudioPhase.Curating, StudioPhase.Preparing, StudioPhase.Ready),
            h.phases,
        )
        val t = h.state.telemetry
        assertEquals(4, t.sourcesTotal)
        assertEquals(4, t.photosRead)
        assertEquals(3, t.accepted)
        assertEquals(1, t.skipped)
        assertEquals(3, t.analyzed)
        assertEquals(3, t.analyzeTotal)
        assertEquals(2, t.clusters)
        assertEquals(0, t.rejected)
        assertEquals(TelemetryStep.DONE, t.step)
        assertEquals(StoryMode.COLLAGE_2, t.finalMode)
        assertEquals(87, t.harmonyPercent)

        assertEquals(StoryTemplate.forCount(2).first(), h.state.template)
        assertEquals(MasterLook.PORTRA_400_GOLDEN, h.state.grade.look)
        assertEquals(listOf("a", "b"), h.state.selection.photoIds)
        assertEquals(listOf("c"), h.state.selection.alternatives.getValue(0))
        assertEquals(listOf(listOf("a", "b") to false), h.deps.preparer.prepareCalls)
        assertEquals(listOf("key-a", "key-b"), h.state.scene!!.layers.map { it.imageKey })
        assertEquals(1L, h.state.sceneRevision)
        assertTrue("b carries a gain map", h.state.export.hdrBoost)
        assertEquals(
            IngestionRequest.PickedMedia(listOf("u1", "u2", "u3", "u4")),
            h.deps.sessions.single().requests.single(),
        )
    }

    @Test
    fun curation_auto_starts_600ms_after_the_last_import_and_imports_accumulate() = runTest {
        val h = harness()
        h.vm.onIntent(StudioIntent.ImportPhotos(listOf("u1")))
        runCurrent()
        advanceTimeBy(599)
        assertEquals(StudioPhase.Ingesting, h.state.phase)
        assertTrue(h.deps.curation.curated.isEmpty())

        val d = TestData.candidate("d")
        h.deps.ingestScripts += TestData.ingestion(listOf(d, a))
        h.vm.onIntent(StudioIntent.ImportZip("content://downloads/trip.zip", "trip.zip"))
        runCurrent()
        assertEquals(4, h.state.telemetry.accepted)
        assertEquals(6, h.state.telemetry.photosRead)
        advanceTimeBy(599)
        assertTrue("debounce restarted by the second import", h.deps.curation.curated.isEmpty())

        advanceTimeBy(2)
        assertEquals(StudioPhase.Curating, h.state.phase)
        assertEquals(listOf("a", "b", "c", "d"), h.deps.curation.curated.single().map { it.id })
        assertEquals(1, h.deps.sessions.size)
        assertEquals(2, h.deps.sessions.single().requests.size)
    }

    @Test
    fun start_curation_skips_the_debounce() = runTest {
        val h = harness()
        h.vm.onIntent(StudioIntent.ImportPhotos(listOf("u1")))
        runCurrent()
        h.vm.onIntent(StudioIntent.StartCuration)
        runCurrent()
        assertEquals(StudioPhase.Curating, h.state.phase)
        assertEquals(1, h.deps.curation.curated.size)
    }

    @Test
    fun start_curation_during_ingestion_starts_as_soon_as_ingestion_finishes() = runTest {
        val h = harness()
        h.deps.ingestScripts.clear()
        h.deps.ingestScripts += TestData.ingestion(listOf(a, b, c), stepMs = 100)
        h.vm.onIntent(StudioIntent.ImportPhotos(listOf("u1")))
        runCurrent()
        h.vm.onIntent(StudioIntent.StartCuration)
        advanceTimeBy(350)
        assertEquals(StudioPhase.Ingesting, h.state.phase)
        advanceTimeBy(60)
        assertEquals(StudioPhase.Curating, h.state.phase)
    }

    @Test
    fun import_while_curating_is_refused_with_a_busy_message() = runTest {
        val h = harness()
        h.vm.onIntent(StudioIntent.ImportPhotos(listOf("u1")))
        runCurrent()
        advanceTimeBy(601)
        assertEquals(StudioPhase.Curating, h.state.phase)
        h.vm.onIntent(StudioIntent.ImportPhotos(listOf("u2")))
        runCurrent()
        assertEquals(StudioEffect.ShowMessage(StudioMessage.Busy), h.effects.last())
        assertEquals(StudioPhase.Curating, h.state.phase)
    }

    @Test
    fun import_after_ready_starts_a_new_session() = runTest {
        val h = harness()
        ready(h)
        h.deps.ingestScripts += TestData.ingestion(listOf(a, b))
        h.vm.onIntent(StudioIntent.ImportPhotos(listOf("u9")))
        assertEquals(StudioPhase.Ingesting, h.state.phase)
        advanceUntilIdle()
        assertEquals(2, h.deps.sessions.size)
        assertEquals(1, h.deps.sessions[0].closeCount)
        assertEquals(setOf("a", "b"), h.deps.preparer.released.toSet())
        assertEquals(StudioPhase.Ready, h.state.phase)
    }

    // endregion

    // region mode and photo changes

    @Test
    fun force_mode_reprepares_only_new_photos_and_retemplates() = runTest {
        val h = harness()
        ready(h)

        h.vm.onIntent(StudioIntent.ForceMode(StoryMode.COLLAGE_3))
        advanceUntilIdle()
        assertEquals(listOf<StoryMode?>(StoryMode.COLLAGE_3), h.deps.curation.decideCalls)
        assertEquals(listOf("c") to false, h.deps.preparer.prepareCalls.last())
        assertTrue(h.deps.preparer.released.isEmpty())
        assertEquals(StoryTemplate.TRIO_BENTO, h.state.template)
        assertEquals(listOf("key-a", "key-b", "key-c"), h.state.scene!!.layers.map { it.imageKey })
        assertEquals(StoryMode.COLLAGE_3, h.state.telemetry.finalMode)
        assertEquals(StudioPhase.Ready, h.state.phase)

        h.vm.onIntent(StudioIntent.ForceMode(StoryMode.HERO))
        advanceUntilIdle()
        assertEquals(listOf("a") to true, h.deps.preparer.prepareCalls.last())
        assertEquals(listOf("b", "c"), h.deps.preparer.released)
        assertEquals(StoryTemplate.HERO_FULL_BLEED, h.state.template)
        assertEquals(HarmonizationParams.IDENTITY, h.state.scene!!.layers.single().grade.harmonization)
        assertEquals(3L, h.state.sceneRevision)
    }

    @Test
    fun swap_slots_reorders_without_preparing_and_moves_the_anchor() = runTest {
        val h = harness()
        ready(h)
        val revision = h.state.sceneRevision

        h.vm.onIntent(StudioIntent.SwapSlots(0, 1))

        assertEquals(listOf("b", "a"), h.state.selection.photoIds)
        assertEquals(listOf("key-b", "key-a"), h.state.scene!!.layers.map { it.imageKey })
        assertEquals(listOf(0.5f, 0.3f), h.deps.harmonizer.calls.last().first.map { it.meanL })
        assertEquals(revision + 1, h.state.sceneRevision)
        assertEquals(1, h.deps.preparer.prepareCalls.size)
    }

    @Test
    fun swap_candidate_prepares_the_alternative_and_releases_the_old_photo() = runTest {
        val h = harness()
        ready(h)

        h.vm.onIntent(StudioIntent.SwapCandidate(0, "c"))
        advanceUntilIdle()

        assertEquals(listOf("c", "b"), h.state.selection.photoIds)
        assertEquals(listOf("c") to false, h.deps.preparer.prepareCalls.last())
        assertEquals(listOf("a"), h.deps.preparer.released)
        assertEquals(listOf("key-c", "key-b"), h.state.scene!!.layers.map { it.imageKey })
    }

    // endregion

    // region grade, scope

    @Test
    fun grade_and_layout_changes_rebuild_the_scene_synchronously() = runTest {
        val h = harness()
        ready(h)
        val revision = h.state.sceneRevision

        h.vm.onIntent(StudioIntent.AdjustGrade(GradeField.CONTRAST, 0.5f))
        assertTrue(h.state.scene!!.layers.all { it.grade.contrast == 0.5f })
        assertEquals(revision + 1, h.state.sceneRevision)

        h.vm.onIntent(StudioIntent.SelectLook(MasterLook.MEDITERRANEAN_AZURE))
        assertTrue(h.state.scene!!.layers.all { it.grade.look == MasterLook.MEDITERRANEAN_AZURE })
        assertEquals(revision + 2, h.state.sceneRevision)

        h.vm.onIntent(StudioIntent.SetCornerRadius(16f))
        assertTrue(h.state.scene!!.layers.all { it.cornerRadiusPx == 16f })
        assertEquals(revision + 3, h.state.sceneRevision)

        h.vm.onIntent(StudioIntent.SelectTab(ControlTab.COLOR))
        assertEquals(revision + 3, h.state.sceneRevision)
    }

    @Test
    fun harmonization_strength_flows_to_per_layer_harmonization() = runTest {
        val h = harness()
        ready(h)
        assertEquals(0.8f, h.deps.harmonizer.calls.last().third)
        assertEquals(FakeHarmonizer.expected(1, 0.5f, 0.8f), h.state.scene!!.layers[1].grade.harmonization)

        h.vm.onIntent(StudioIntent.AdjustGrade(GradeField.HARMONIZATION_STRENGTH, 0.3f))

        assertEquals(0.3f, h.deps.harmonizer.calls.last().third)
        assertEquals(0, h.deps.harmonizer.calls.last().second)
        assertEquals(FakeHarmonizer.expected(1, 0.5f, 0.3f), h.state.scene!!.layers[1].grade.harmonization)
    }

    @Test
    fun scope_renders_debounced_only_while_on() = runTest {
        val h = harness()
        ready(h)
        h.vm.onIntent(StudioIntent.AdjustGrade(GradeField.TINT, 0.05f))
        advanceUntilIdle()
        assertTrue(h.deps.renderer.previewCalls.isEmpty())

        h.vm.onIntent(StudioIntent.SetScope(ScopeMode.LUMA_WAVEFORM))
        advanceTimeBy(119)
        assertTrue(h.deps.renderer.previewCalls.isEmpty())
        advanceTimeBy(2)
        assertEquals(1, h.deps.renderer.previewCalls.size)
        assertEquals(270, h.state.scopePixels!!.width)
        assertEquals(480, h.state.scopePixels!!.height)

        h.vm.onIntent(StudioIntent.AdjustGrade(GradeField.TINT, 0.1f))
        advanceTimeBy(50)
        h.vm.onIntent(StudioIntent.AdjustGrade(GradeField.TINT, 0.2f))
        advanceTimeBy(50)
        h.vm.onIntent(StudioIntent.AdjustGrade(GradeField.TINT, 0.3f))
        advanceUntilIdle()
        assertEquals(2, h.deps.renderer.previewCalls.size)
        assertTrue(h.deps.renderer.previewCalls.last().layers.all { it.grade.tint == 0.3f })

        h.vm.onIntent(StudioIntent.SetScope(ScopeMode.OFF))
        assertNull(h.state.scopePixels)
        h.vm.onIntent(StudioIntent.AdjustGrade(GradeField.TINT, 0.4f))
        advanceUntilIdle()
        assertEquals(2, h.deps.renderer.previewCalls.size)
    }

    // endregion

    // region export

    @Test
    fun export_reports_progress_and_shares_when_asked() = runTest {
        val h = harness()
        ready(h)

        h.vm.onIntent(StudioIntent.Export(share = true))
        runCurrent()
        assertEquals(StudioPhase.Exporting, h.state.phase)
        assertEquals(ExportProgressUi(ExportStage.RENDER, 0f), h.state.export.progress)
        val request = h.deps.exporter.requests.single()
        assertEquals(2160, request.scene.canvasWidth)
        assertEquals(CanvasSize.STORY_4K, h.deps.composer.requests.last().canvas)
        assertEquals(ExportFormat.SOCIAL_STORY_JPEG, request.format)
        assertEquals(ExportResolution.HD_1080, request.resolution)
        assertEquals(1_700_000_000_000L, request.metadata.captureEpochMillis)
        assertTrue(request.hdrBoost)
        assertEquals(1080, h.state.scene!!.canvasWidth)

        advanceTimeBy(101)
        assertEquals(ExportProgressUi(ExportStage.ENCODE, 0.5f), h.state.export.progress)

        advanceUntilIdle()
        assertEquals(StudioPhase.Ready, h.state.phase)
        assertNull(h.state.export.progress)
        val done = h.state.export.lastResult!!
        assertEquals(StudioEffect.ShareExport(done.uri, "image/jpeg"), h.effects.last())

        h.vm.onIntent(StudioIntent.SetExportFormat(ExportFormat.PNG_16))
        h.vm.onIntent(StudioIntent.Export(share = false))
        advanceUntilIdle()
        assertEquals(CanvasSize.STORY_1080, h.deps.composer.requests.last().canvas)
        assertEquals(StudioEffect.ShowMessage(StudioMessage.ExportSaved(h.state.export.lastResult!!)), h.effects.last())
        assertEquals(1, h.effects.count { it is StudioEffect.ShareExport })
    }

    @Test
    fun user_hdr_choice_survives_selection_changes() = runTest {
        val h = harness()
        ready(h)
        h.vm.onIntent(StudioIntent.SetHdrBoost(false))
        h.vm.onIntent(StudioIntent.ForceMode(StoryMode.COLLAGE_3))
        advanceUntilIdle()
        assertFalse(h.state.export.hdrBoost)
    }

    // endregion

    // region cancel

    @Test
    fun cancel_while_ingesting_returns_to_empty_and_closes_the_session() = runTest {
        val h = harness()
        h.deps.ingestScripts.clear()
        h.deps.ingestScripts += TestData.ingestion(listOf(a, b, c), stepMs = 1_000)
        h.vm.onIntent(StudioIntent.ImportPhotos(listOf("u1")))
        runCurrent()
        assertEquals(StudioPhase.Ingesting, h.state.phase)

        h.vm.onIntent(StudioIntent.CancelWork)
        assertEquals(StudioState(), h.state)
        advanceUntilIdle()
        assertEquals(1, h.deps.sessions.single().closeCount)
        assertTrue(h.deps.curation.curated.isEmpty())
        assertEquals(StudioState(), h.state)
    }

    @Test
    fun cancel_while_curating_returns_to_empty() = runTest {
        val h = harness()
        h.vm.onIntent(StudioIntent.ImportPhotos(listOf("u1")))
        runCurrent()
        advanceTimeBy(700)
        assertEquals(StudioPhase.Curating, h.state.phase)

        h.vm.onIntent(StudioIntent.CancelWork)
        advanceUntilIdle()
        assertEquals(StudioPhase.Empty, h.state.phase)
        assertTrue(h.deps.preparer.prepareCalls.isEmpty())
        assertEquals(1, h.deps.sessions.single().closeCount)
    }

    @Test
    fun cancel_during_initial_preparation_releases_the_batch_and_returns_to_empty() = runTest {
        val h = harness()
        h.deps.preparer.delayMs = 10_000
        h.vm.onIntent(StudioIntent.ImportPhotos(listOf("u1")))
        runCurrent()
        advanceTimeBy(1_100)
        assertEquals(StudioPhase.Preparing, h.state.phase)

        h.vm.onIntent(StudioIntent.CancelWork)
        advanceUntilIdle()
        assertEquals(StudioPhase.Empty, h.state.phase)
        assertEquals(listOf("a", "b"), h.deps.preparer.released)
        assertEquals(1, h.deps.sessions.single().closeCount)
    }

    @Test
    fun cancel_during_reprepare_restores_the_previous_story() = runTest {
        val h = harness()
        ready(h)
        val before = h.state
        h.deps.preparer.delayMs = 10_000

        h.vm.onIntent(StudioIntent.ForceMode(StoryMode.COLLAGE_3))
        runCurrent()
        assertEquals(StudioPhase.Preparing, h.state.phase)
        h.vm.onIntent(StudioIntent.CancelWork)
        advanceUntilIdle()

        assertEquals(StudioPhase.Ready, h.state.phase)
        assertEquals(before.selection, h.state.selection)
        assertEquals(before.template, h.state.template)
        assertEquals(before.curation, h.state.curation)
        assertEquals(listOf("key-a", "key-b"), h.state.scene!!.layers.map { it.imageKey })
        assertEquals(listOf("c"), h.deps.preparer.released)
        assertEquals(2, h.deps.preparer.prepareCalls.size)
        assertNull(h.state.error)
    }

    @Test
    fun cancel_while_exporting_returns_to_ready_without_a_result() = runTest {
        val h = harness()
        ready(h)
        h.vm.onIntent(StudioIntent.Export(share = true))
        runCurrent()

        h.vm.onIntent(StudioIntent.CancelWork)
        assertEquals(StudioPhase.Ready, h.state.phase)
        assertNull(h.state.export.progress)
        advanceUntilIdle()
        assertNull(h.state.export.lastResult)
        assertTrue(h.effects.none { it is StudioEffect.ShareExport })
    }

    // endregion

    // region errors

    @Test
    fun ingestion_failure_is_mapped_and_the_session_closed() = runTest {
        val h = harness()
        val error = IngestionError.InsufficientStorage(requiredBytes = 100, availableBytes = 1)
        h.deps.ingestScripts.clear()
        h.deps.ingestScripts += flow<IngestionEvent> { throw IngestionException(error) }
        h.vm.onIntent(StudioIntent.ImportPhotos(listOf("u1")))
        advanceUntilIdle()

        assertEquals(StudioPhase.Empty, h.state.phase)
        assertEquals(StudioError.Ingestion(error), h.state.error)
        assertEquals(1, h.deps.sessions.single().closeCount)

        h.vm.onIntent(StudioIntent.DismissError)
        assertNull(h.state.error)
    }

    @Test
    fun ingestion_without_candidates_reports_no_usable_photos() = runTest {
        val h = harness()
        h.deps.ingestScripts.clear()
        h.deps.ingestScripts += TestData.ingestion(emptyList(), skipped = 2)
        h.vm.onIntent(StudioIntent.ImportPhotos(listOf("u1", "u2")))
        advanceUntilIdle()
        assertEquals(StudioError.NoUsablePhotos(2), h.state.error)
        assertTrue(h.deps.curation.curated.isEmpty())
    }

    @Test
    fun failed_source_is_reported_as_a_message() = runTest {
        val h = harness()
        val error = IngestionError.ZipSlip("../evil.jpg")
        h.deps.ingestScripts.clear()
        h.deps.ingestScripts += flow {
            emit(IngestionEvent.SourceFailed("bad.zip", error, IngestionTelemetry(sourcesTotal = 1)))
            emit(IngestionEvent.CandidateReady(a, IngestionTelemetry(sourcesTotal = 2, filesRead = 1, candidatesAccepted = 1)))
            emit(IngestionEvent.Completed(listOf(a), IngestionTelemetry(sourcesTotal = 2, filesRead = 1, candidatesAccepted = 1)))
        }
        h.vm.onIntent(StudioIntent.ImportZip("content://bad", "bad.zip"))
        runCurrent()
        assertEquals(StudioEffect.ShowMessage(StudioMessage.SourceFailed("bad.zip", error)), h.effects.first())
        assertEquals(1, h.state.telemetry.accepted)
    }

    @Test
    fun curation_failure_maps_to_unknown_and_returns_to_empty() = runTest {
        val h = harness()
        h.deps.curation.failWith = RuntimeException("model missing")
        h.vm.onIntent(StudioIntent.ImportPhotos(listOf("u1")))
        advanceUntilIdle()
        assertEquals(StudioPhase.Empty, h.state.phase)
        assertEquals(StudioError.Unknown("model missing"), h.state.error)
    }

    @Test
    fun reprepare_failure_goes_through_the_mapper_and_restores_the_story() = runTest {
        class DecodeFailure(val name: String) : RuntimeException()
        val mapper = object : DefaultStudioErrorMapper() {
            override fun map(t: Throwable): StudioError =
                if (t is DecodeFailure) StudioError.Decode(t.name) else super.map(t)
        }
        val h = harness(mapper)
        ready(h)
        h.deps.preparer.failWith = DecodeFailure("c.jpg")

        h.vm.onIntent(StudioIntent.ForceMode(StoryMode.COLLAGE_3))
        advanceUntilIdle()

        assertEquals(StudioPhase.Ready, h.state.phase)
        assertEquals(StudioError.Decode("c.jpg"), h.state.error)
        assertEquals(listOf("a", "b"), h.state.selection.photoIds)
        assertEquals(listOf("c"), h.deps.preparer.released)
    }

    @Test
    fun export_failure_returns_to_ready_with_the_mapped_error() = runTest {
        val h = harness()
        ready(h)
        h.deps.exporter.failWith = IllegalStateException("disk full")
        h.vm.onIntent(StudioIntent.Export(share = true))
        advanceUntilIdle()
        assertEquals(StudioPhase.Ready, h.state.phase)
        assertEquals(StudioError.Unknown("disk full"), h.state.error)
        assertNull(h.state.export.progress)
        assertTrue(h.effects.none { it is StudioEffect.ShareExport })
    }

    // endregion

    // region lifecycle

    @Test
    fun on_cleared_releases_photos_and_closes_the_session() = runTest {
        val h = harness()
        ready(h)
        val store = ViewModelStore()
        store.getOrPut("studio") { h.vm }

        store.clear()
        advanceUntilIdle()

        assertEquals(setOf("a", "b"), h.deps.preparer.released.toSet())
        assertEquals(1, h.deps.sessions.single().closeCount)
        assertEquals("the container owns the renderer", 0, h.deps.renderer.closeCount)
    }

    @Test
    fun reset_empties_the_studio_and_releases_everything() = runTest {
        val h = harness()
        ready(h)
        h.vm.onIntent(StudioIntent.Reset)
        assertEquals(StudioState(), h.state)
        advanceUntilIdle()
        assertEquals(setOf("a", "b"), h.deps.preparer.released.toSet())
        assertEquals(1, h.deps.sessions.single().closeCount)
        assertEquals("the container owns the renderer", 0, h.deps.renderer.closeCount)
    }

    // endregion
}
