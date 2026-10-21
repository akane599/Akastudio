package com.akane.akastudio.feature.studio.mvi

import com.akane.akastudio.core.curator.api.SceneCluster
import com.akane.akastudio.core.curator.api.StoryMode
import com.akane.akastudio.core.ingestion.api.IngestionRequest
import com.akane.akastudio.feature.studio.domain.FakeCurationEngine
import com.akane.akastudio.feature.studio.domain.FakeStudioDependencies
import com.akane.akastudio.feature.studio.domain.TestData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
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
import org.junit.Assert.assertTrue
import org.junit.Test

/** "+ Add more" while photos are read: an open picker holds auto-curation, and its result joins the batch (F-05). */
@OptIn(ExperimentalCoroutinesApi::class)
class StudioPickerTest {

    private val a = TestData.candidate("a")
    private val b = TestData.candidate("b")
    private val c = TestData.candidate("c")
    private val result = TestData.result(
        chosenMode = StoryMode.COLLAGE_2,
        options = mapOf(StoryMode.HERO to listOf("a"), StoryMode.COLLAGE_2 to listOf("a", "b")),
        analyses = listOf(TestData.analysis("a", quality = 0.9f), TestData.analysis("b"), TestData.analysis("c")),
        clusters = listOf(SceneCluster(0, listOf("a", "b", "c"), "a")),
    )

    private class Harness(val vm: StudioViewModel, val deps: FakeStudioDependencies, val effects: List<StudioEffect>) {
        val state: StudioState get() = vm.state.value
    }

    private fun TestScope.harness(): Harness {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val deps = FakeStudioDependencies(dispatcher, FakeCurationEngine(result))
        val vm = StudioViewModel(deps, CoroutineScope(SupervisorJob(backgroundScope.coroutineContext.job) + dispatcher))
        val effects = mutableListOf<StudioEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        return Harness(vm, deps, effects)
    }

    /** Imports `a, b` and lets the reading finish; the 600 ms auto-curation debounce has not elapsed yet. */
    private fun TestScope.readFirstBatch(h: Harness) {
        h.deps.ingestScripts += TestData.ingestion(listOf(a, b))
        h.vm.onIntent(StudioIntent.ImportPhotos(listOf("u1", "u2")))
        runCurrent()
        assertEquals(StudioPhase.Ingesting, h.state.phase)
    }

    @Test
    fun add_more_picker_holds_auto_curation_and_its_photos_join_the_batch() = runTest {
        val h = harness()
        readFirstBatch(h)

        h.vm.onIntent(StudioIntent.PickerOpened)
        advanceTimeBy(StudioViewModel.AUTO_CURATION_DEBOUNCE_MS * 20)
        runCurrent()
        assertEquals(StudioPhase.Ingesting, h.state.phase)
        assertTrue(h.deps.curation.curated.isEmpty())

        h.deps.ingestScripts += TestData.ingestion(listOf(c))
        h.vm.onIntent(StudioIntent.ImportPhotos(listOf("u3")))
        h.vm.onIntent(StudioIntent.PickerClosed)
        advanceUntilIdle()

        assertTrue("the picked photos were not refused", h.effects.isEmpty())
        assertEquals(1, h.deps.sessions.size)
        assertEquals(
            listOf(IngestionRequest.PickedMedia(listOf("u1", "u2")), IngestionRequest.PickedMedia(listOf("u3"))),
            h.deps.sessions.single().requests,
        )
        assertEquals(listOf(listOf("a", "b", "c")), h.deps.curation.curated.map { batch -> batch.map { it.id } })
        assertEquals(StudioPhase.Ready, h.state.phase)
    }

    @Test
    fun cancelled_picker_rearms_the_debounce() = runTest {
        val h = harness()
        readFirstBatch(h)
        h.vm.onIntent(StudioIntent.PickerOpened)
        advanceTimeBy(StudioViewModel.AUTO_CURATION_DEBOUNCE_MS * 20)

        h.vm.onIntent(StudioIntent.PickerClosed)
        advanceTimeBy(StudioViewModel.AUTO_CURATION_DEBOUNCE_MS - 1)
        assertEquals(StudioPhase.Ingesting, h.state.phase)
        advanceTimeBy(2)
        assertEquals(StudioPhase.Curating, h.state.phase)
        assertEquals(listOf(listOf("a", "b")), h.deps.curation.curated.map { batch -> batch.map { it.id } })
    }

    @Test
    fun curate_now_requested_before_the_picker_waits_for_its_result() = runTest {
        val h = harness()
        h.deps.ingestScripts += TestData.ingestion(listOf(a, b), stepMs = 100)
        h.vm.onIntent(StudioIntent.ImportPhotos(listOf("u1", "u2")))
        runCurrent()
        h.vm.onIntent(StudioIntent.StartCuration)
        h.vm.onIntent(StudioIntent.PickerOpened)
        advanceTimeBy(10_000)
        assertEquals(StudioPhase.Ingesting, h.state.phase)

        h.deps.ingestScripts += TestData.ingestion(listOf(c))
        h.vm.onIntent(StudioIntent.ImportPhotos(listOf("u3")))
        h.vm.onIntent(StudioIntent.PickerClosed)
        runCurrent()

        assertEquals(StudioPhase.Curating, h.state.phase)
        assertEquals(listOf(listOf("a", "b", "c")), h.deps.curation.curated.map { batch -> batch.map { it.id } })
    }

    @Test
    fun add_more_result_after_curation_never_replaces_the_story() = runTest {
        val h = harness()
        readFirstBatch(h)
        h.vm.onIntent(StudioIntent.PickerOpened)
        h.vm.onIntent(StudioIntent.StartCuration)
        advanceUntilIdle()
        assertEquals(StudioPhase.Ready, h.state.phase)
        val story = h.state.selection.photoIds

        h.vm.onIntent(StudioIntent.ImportPhotos(listOf("u9")))
        h.vm.onIntent(StudioIntent.PickerClosed)
        advanceUntilIdle()

        assertEquals(StudioPhase.Ready, h.state.phase)
        assertEquals(story, h.state.selection.photoIds)
        assertEquals(1, h.deps.sessions.size)
        assertEquals(0, h.deps.sessions.single().closeCount)
        assertEquals(listOf<StudioEffect>(StudioEffect.ShowMessage(StudioMessage.Busy)), h.effects)
    }

    @Test
    fun picker_opened_from_the_empty_studio_starts_the_first_batch() = runTest {
        val h = harness()
        h.vm.onIntent(StudioIntent.PickerOpened)
        h.deps.ingestScripts += TestData.ingestion(listOf(a, b))
        h.vm.onIntent(StudioIntent.ImportPhotos(listOf("u1", "u2")))
        h.vm.onIntent(StudioIntent.PickerClosed)
        advanceUntilIdle()
        assertEquals(StudioPhase.Ready, h.state.phase)
    }
}
