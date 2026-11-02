package com.akane.akastudio.feature.studio.mvi

import com.akane.akastudio.core.color.api.ImagePixels
import com.akane.akastudio.core.color.api.RenderScene
import com.akane.akastudio.core.color.api.StoryRenderer
import com.akane.akastudio.core.curator.api.SceneCluster
import com.akane.akastudio.core.curator.api.StoryMode
import com.akane.akastudio.feature.studio.domain.FakeCurationEngine
import com.akane.akastudio.feature.studio.domain.FakeStudioDependencies
import com.akane.akastudio.feature.studio.domain.StudioDependencies
import com.akane.akastudio.feature.studio.domain.StudioError
import com.akane.akastudio.feature.studio.domain.TestData
import com.akane.akastudio.feature.studio.domain.studioTestDispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Scope rendering outlives a reset; its readback and failures must still belong to the scene that requested it. */
@OptIn(ExperimentalCoroutinesApi::class)
class StudioScopeOwnershipTest {

    private class Readback(val scene: RenderScene) {
        val result = CompletableDeferred<ImagePixels>()
        fun finish(value: Int) = result.complete(ImagePixels(1, 1, intArrayOf(value)))
    }

    private class GatedRenderer(delegate: StoryRenderer) : StoryRenderer by delegate {
        val readbacks = mutableListOf<Readback>()

        override suspend fun renderPreviewPixels(scene: RenderScene, width: Int, height: Int): ImagePixels =
            Readback(scene).also { readbacks += it }.result.await()
    }

    private class Harness(val vm: StudioViewModel, val deps: FakeStudioDependencies, val renderer: GatedRenderer) {
        val state: StudioState get() = vm.state.value
    }

    private fun TestScope.harness(): Harness {
        val result = TestData.result(
            chosenMode = StoryMode.HERO,
            options = mapOf(StoryMode.HERO to listOf("a")),
            analyses = listOf(TestData.analysis("a")),
            clusters = listOf(SceneCluster(0, listOf("a"), "a")),
        )
        val base = FakeStudioDependencies(studioTestDispatchers(testScheduler), FakeCurationEngine(result))
        val renderer = GatedRenderer(base.renderer)
        val deps = object : StudioDependencies by base {
            override val renderer: StoryRenderer = renderer
        }
        val vm = StudioViewModel(
            deps,
            CoroutineScope(SupervisorJob(backgroundScope.coroutineContext.job) + deps.dispatchers.main),
        )
        return Harness(vm, base, renderer)
    }

    private fun TestScope.ready(h: Harness) {
        h.deps.ingestScripts += TestData.ingestion(listOf(TestData.candidate("a")))
        h.vm.onIntent(StudioIntent.ImportPhotos(listOf("content://media/a")))
        advanceUntilIdle()
        assertEquals(StudioPhase.Ready, h.state.phase)
    }

    private fun TestScope.enableScope(h: Harness) {
        h.vm.onIntent(StudioIntent.SetScope(ScopeMode.LUMA_WAVEFORM))
        advanceTimeBy(121)
        runCurrent()
    }

    private fun TestScope.replaceWhileRendering(h: Harness) {
        val revision = h.state.sceneRevision
        h.vm.onIntent(StudioIntent.Reset)
        ready(h)
        enableScope(h)
        assertEquals("replacement repeats the old revision", revision, h.state.sceneRevision)
        assertEquals("old render is still running", 1, h.renderer.readbacks.size)
    }

    @Test
    fun a_retired_storys_scope_pixels_are_dropped_even_when_the_new_story_repeats_its_revision() = runTest {
        val h = harness()
        ready(h)
        enableScope(h)
        assertEquals(1, h.renderer.readbacks.size)
        replaceWhileRendering(h)

        h.renderer.readbacks[0].finish(1)
        runCurrent()

        assertNull("the replacement never shows retired pixels", h.state.scopePixels)
        assertEquals("the replacement still gets its own render", 2, h.renderer.readbacks.size)
        h.renderer.readbacks[1].finish(2)
        advanceUntilIdle()
        assertEquals(2, h.state.scopePixels!!.argb[0])
    }

    @Test
    fun a_retired_storys_scope_failure_is_not_shown_by_its_replacement() = runTest {
        val h = harness()
        ready(h)
        enableScope(h)
        replaceWhileRendering(h)

        h.renderer.readbacks[0].result.completeExceptionally(IllegalStateException("old context lost"))
        runCurrent()

        assertNull("the old error belongs to the retired story", h.state.error)
        assertEquals(StudioPhase.Ready, h.state.phase)
        assertEquals(2, h.renderer.readbacks.size)
        h.renderer.readbacks[1].finish(2)
        advanceUntilIdle()
        assertEquals(2, h.state.scopePixels!!.argb[0])
    }

    @Test
    fun a_superseded_scenes_scope_failure_is_not_shown_over_the_newer_scene() = runTest {
        val h = harness()
        ready(h)
        enableScope(h)
        h.vm.onIntent(StudioIntent.AdjustGrade(GradeField.TINT, 0.2f))
        advanceTimeBy(121)
        runCurrent()

        h.renderer.readbacks[0].result.completeExceptionally(IllegalStateException("old scene failed"))
        runCurrent()

        assertNull(h.state.error)
        assertEquals(2, h.renderer.readbacks.size)
        assertEquals(0.2f, h.renderer.readbacks[1].scene.layers[0].grade.tint)
        h.renderer.readbacks[1].finish(2)
        advanceUntilIdle()
        assertEquals(2, h.state.scopePixels!!.argb[0])
    }

    @Test
    fun a_pending_scope_failure_is_ignored_after_the_scope_is_turned_off() = runTest {
        val h = harness()
        ready(h)
        enableScope(h)
        h.vm.onIntent(StudioIntent.SetScope(ScopeMode.OFF))
        runCurrent()

        h.renderer.readbacks[0].result.completeExceptionally(IllegalStateException("disabled scope failed"))
        advanceUntilIdle()

        assertNull(h.state.error)
        assertNull(h.state.scopePixels)
        assertEquals(1, h.renderer.readbacks.size)
    }

    @Test
    fun a_scope_failure_for_the_active_scene_is_still_reported() = runTest {
        val h = harness()
        ready(h)
        enableScope(h)

        h.renderer.readbacks[0].result.completeExceptionally(IllegalStateException("active context lost"))
        advanceUntilIdle()

        assertEquals(StudioError.Unknown("active context lost"), h.state.error)
        assertEquals(StudioPhase.Ready, h.state.phase)
        assertNull(h.state.scopePixels)
    }
}
