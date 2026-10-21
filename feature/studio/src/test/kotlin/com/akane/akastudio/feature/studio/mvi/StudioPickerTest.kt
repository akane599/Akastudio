package com.akane.akastudio.feature.studio.mvi

import com.akane.akastudio.core.curator.api.SceneCluster
import com.akane.akastudio.core.curator.api.StoryMode
import com.akane.akastudio.core.ingestion.api.IngestionRequest
import com.akane.akastudio.feature.studio.domain.FakeCurationEngine
import com.akane.akastudio.feature.studio.domain.FakeStudioDependencies
import com.akane.akastudio.feature.studio.domain.StudioDependencies
import com.akane.akastudio.feature.studio.domain.TestData
import com.akane.akastudio.feature.studio.domain.studioTestDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
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

    /** [documentNames] answers `StudioDependencies.documentDisplayName`; the fake's default is used when null. */
    private fun TestScope.harness(documentNames: ((String) -> String)? = null): Harness {
        val deps = FakeStudioDependencies(studioTestDispatchers(testScheduler), FakeCurationEngine(result))
        val vmDeps = documentNames?.let { names ->
            object : StudioDependencies by deps {
                override fun documentDisplayName(uri: String): String = names(uri)
            }
        } ?: deps
        val vm = StudioViewModel(vmDeps, CoroutineScope(SupervisorJob(backgroundScope.coroutineContext.job) + deps.dispatchers.main))
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

    @Test
    fun stacked_pickers_hold_auto_curation_until_the_last_one_closes() = runTest {
        val h = harness()
        readFirstBatch(h)
        h.vm.onIntent(StudioIntent.PickerOpened)
        h.vm.onIntent(StudioIntent.PickerOpened)

        h.vm.onIntent(StudioIntent.PickerClosed)
        advanceTimeBy(StudioViewModel.AUTO_CURATION_DEBOUNCE_MS * 20)
        runCurrent()
        assertEquals(StudioPhase.Ingesting, h.state.phase)
        assertTrue(h.deps.curation.curated.isEmpty())

        h.deps.ingestScripts += TestData.ingestion(listOf(c))
        h.vm.onIntent(StudioIntent.ImportPhotos(listOf("u3")))
        h.vm.onIntent(StudioIntent.PickerClosed)
        advanceUntilIdle()
        assertTrue("the second picker's photos were not refused", h.effects.isEmpty())
        assertEquals(listOf(listOf("a", "b", "c")), h.deps.curation.curated.map { batch -> batch.map { it.id } })
        assertEquals(StudioPhase.Ready, h.state.phase)
    }

    @Test
    fun stacked_picker_opened_from_the_empty_studio_still_extends_the_batch() = runTest {
        val h = harness()
        h.vm.onIntent(StudioIntent.PickerOpened)
        h.vm.onIntent(StudioIntent.PickerOpened)
        h.deps.ingestScripts += TestData.ingestion(listOf(a, b))
        h.vm.onIntent(StudioIntent.ImportPhotos(listOf("u1", "u2")))
        h.vm.onIntent(StudioIntent.PickerClosed)
        advanceTimeBy(StudioViewModel.AUTO_CURATION_DEBOUNCE_MS * 20)
        runCurrent()
        assertEquals(StudioPhase.Ingesting, h.state.phase)

        h.deps.ingestScripts += TestData.ingestion(listOf(c))
        h.vm.onIntent(StudioIntent.ImportPhotos(listOf("u3")))
        h.vm.onIntent(StudioIntent.PickerClosed)
        advanceUntilIdle()
        assertEquals(1, h.deps.sessions.size)
        assertEquals(listOf(listOf("a", "b", "c")), h.deps.curation.curated.map { batch -> batch.map { it.id } })
        assertEquals(StudioPhase.Ready, h.state.phase)
    }

    @Test
    fun zip_pick_is_queued_at_once_and_its_name_is_looked_up_off_the_intent() = runTest {
        val lookups = mutableListOf<String>()
        val h = harness(documentNames = { uri -> lookups += uri; "Trip to Lisbon.zip" })
        h.vm.onIntent(StudioIntent.PickerOpened)
        h.deps.ingestScripts += TestData.ingestion(listOf(a, b))
        h.vm.onIntent(StudioIntent.ImportZip("content://downloads/42"))
        h.vm.onIntent(StudioIntent.PickerClosed)

        assertEquals("the pick is accepted synchronously", StudioPhase.Ingesting, h.state.phase)
        assertTrue("no provider query inside onIntent", lookups.isEmpty())
        advanceUntilIdle()

        assertEquals(listOf("content://downloads/42"), lookups)
        assertEquals(
            listOf(IngestionRequest.ZipArchive("content://downloads/42", "Trip to Lisbon.zip")),
            h.deps.sessions.single().requests,
        )
        assertEquals(StudioPhase.Ready, h.state.phase)
    }

    @Test
    fun zip_with_a_known_name_skips_the_lookup() = runTest {
        val lookups = mutableListOf<String>()
        val h = harness(documentNames = { uri -> lookups += uri; "unused" })
        h.deps.ingestScripts += TestData.ingestion(listOf(a, b))
        h.vm.onIntent(StudioIntent.ImportZip("content://downloads/42", "trip.zip"))
        advanceUntilIdle()

        assertTrue(lookups.isEmpty())
        assertEquals(
            listOf(IngestionRequest.ZipArchive("content://downloads/42", "trip.zip")),
            h.deps.sessions.single().requests,
        )
    }

    @Test
    fun close_without_an_open_picker_is_ignored() = runTest {
        val h = harness()
        readFirstBatch(h)
        h.vm.onIntent(StudioIntent.PickerClosed)
        h.vm.onIntent(StudioIntent.PickerOpened)
        advanceTimeBy(StudioViewModel.AUTO_CURATION_DEBOUNCE_MS * 20)
        runCurrent()
        assertEquals(StudioPhase.Ingesting, h.state.phase)
    }
}
