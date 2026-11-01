package com.akane.akastudio.feature.studio.mvi

import com.akane.akastudio.core.curator.api.SceneCluster
import com.akane.akastudio.core.curator.api.StoryMode
import com.akane.akastudio.feature.studio.domain.FakeCurationEngine
import com.akane.akastudio.feature.studio.domain.FakeStudioDependencies
import com.akane.akastudio.feature.studio.domain.StudioDispatchers
import com.akane.akastudio.feature.studio.domain.TestData
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The studio on a production-shaped `Dispatchers.Main.immediate`: code already running on main launches and resumes
 * main coroutines in place instead of queueing them, so a state collector on main runs inside the state write that
 * woke it, and an intent it sends re-enters the view model before the code that wrote the state has continued.
 * The other tests use a queued main dispatcher, which never shows that interleaving.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StudioImmediateMainTest {

    /**
     * A stand-in for `Dispatchers.Main.immediate` on [scheduler]: dispatched work runs as a queued task, and while
     * such a task runs (or inside [runOnMain], standing in for a main-thread callback) nothing needs a dispatch, as
     * on the main thread. Delays use the scheduler's virtual time.
     */
    @OptIn(InternalCoroutinesApi::class)
    private class ImmediateMainDispatcher(scheduler: TestCoroutineScheduler) : CoroutineDispatcher(), Delay {
        private val queue = StandardTestDispatcher(scheduler, "main")
        private val queueDelay = queue as Delay
        private var onMain = false

        fun <T> runOnMain(block: () -> T): T {
            val was = onMain
            onMain = true
            try {
                return block()
            } finally {
                onMain = was
            }
        }

        override fun isDispatchNeeded(context: CoroutineContext): Boolean = !onMain

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.dispatch(context, Runnable { runOnMain { block.run() } })
        }

        override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) =
            queueDelay.scheduleResumeAfterDelay(timeMillis, continuation)

        override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle =
            queueDelay.invokeOnTimeout(timeMillis, block, context)
    }

    private val a = TestData.candidate("a")
    private val b = TestData.candidate("b")
    private val c = TestData.candidate("c")
    private val result = TestData.result(
        chosenMode = StoryMode.COLLAGE_2,
        options = mapOf(
            StoryMode.HERO to listOf("a"),
            StoryMode.COLLAGE_2 to listOf("a", "b"),
            StoryMode.COLLAGE_3 to listOf("a", "b", "c"),
        ),
        analyses = listOf(TestData.analysis("a"), TestData.analysis("b"), TestData.analysis("c")),
        clusters = listOf(SceneCluster(0, listOf("a", "c"), "a"), SceneCluster(1, listOf("b"), "b")),
    )

    private class Harness(val vm: StudioViewModel, val deps: FakeStudioDependencies, val main: ImmediateMainDispatcher) {
        val state: StudioState get() = vm.state.value

        /** Sends [intent] as a main-thread callback (a click) would. */
        fun send(intent: StudioIntent) = main.runOnMain { vm.onIntent(intent) }
    }

    private fun TestScope.harness(): Harness {
        val main = ImmediateMainDispatcher(testScheduler)
        val dispatchers = StudioDispatchers(
            main = main,
            default = StandardTestDispatcher(testScheduler, "default"),
            io = StandardTestDispatcher(testScheduler, "io"),
        )
        val deps = FakeStudioDependencies(dispatchers, FakeCurationEngine(result))
        deps.ingestScripts += TestData.ingestion(listOf(a, b, c))
        val vm = StudioViewModel(deps, CoroutineScope(SupervisorJob(backgroundScope.coroutineContext.job) + main))
        runCurrent()
        return Harness(vm, deps, main)
    }

    /**
     * From a state collector on main, sends [intent] synchronously the first time the state matches [trigger].
     * Returns whether it has fired.
     */
    private fun TestScope.reenterOnce(h: Harness, intent: StudioIntent, trigger: (StudioState) -> Boolean): () -> Boolean {
        var fired = false
        backgroundScope.launch(h.main) {
            h.vm.state.collect {
                if (!fired && trigger(it)) {
                    fired = true
                    h.vm.onIntent(intent)
                }
            }
        }
        runCurrent()
        return { fired }
    }

    private fun TestScope.ready(h: Harness) {
        h.send(StudioIntent.ImportPhotos(listOf("u1")))
        advanceUntilIdle()
        assertEquals(StudioPhase.Ready, h.state.phase)
    }

    @Test
    fun the_pipeline_and_a_cancelled_reprepare_behave_as_on_a_queued_main_dispatcher() = runTest {
        val h = harness()
        ready(h)
        assertEquals(listOf("a", "b"), h.state.selection.photoIds)
        h.deps.preparer.delayMs = 10_000

        h.send(StudioIntent.ForceMode(StoryMode.COLLAGE_3))
        runCurrent()
        assertEquals(StudioPhase.Preparing, h.state.phase)
        h.send(StudioIntent.CancelWork)
        advanceUntilIdle()

        assertEquals(StudioPhase.Ready, h.state.phase)
        assertEquals(listOf("a", "b"), h.state.selection.photoIds)
        assertEquals(listOf("c"), h.deps.preparer.released)
        assertNull(h.state.error)
    }

    @Test
    fun a_cancel_sent_while_exporting_is_published_cancels_only_the_export() = runTest {
        val h = harness()
        ready(h)
        reenterOnce(h, StudioIntent.CancelWork) { it.phase == StudioPhase.Exporting }

        h.send(StudioIntent.Export(share = false))
        advanceUntilIdle()

        assertEquals("the story stays on screen", StudioPhase.Ready, h.state.phase)
        assertEquals(listOf("a", "b"), h.state.selection.photoIds)
        assertNull(h.state.export.lastResult)
        assertTrue("nothing was released", h.deps.preparer.released.isEmpty())
        assertEquals("the session is still open", 0, h.deps.sessions.single().closeCount)
    }

    @Test
    fun a_cancel_sent_while_a_reprepare_is_published_restores_the_previous_story() = runTest {
        val h = harness()
        ready(h)
        reenterOnce(h, StudioIntent.CancelWork) { it.phase == StudioPhase.Preparing }

        h.send(StudioIntent.ForceMode(StoryMode.COLLAGE_3))
        advanceUntilIdle()

        assertEquals("the previous story is restored", StudioPhase.Ready, h.state.phase)
        assertEquals(listOf("a", "b"), h.state.selection.photoIds)
        assertEquals(StoryMode.COLLAGE_2, h.state.selection.mode)
        assertNull(h.state.error)
        assertEquals("the session is still open", 0, h.deps.sessions.single().closeCount)
    }

    @Test
    fun curate_now_sent_while_the_first_import_is_published_waits_for_that_import() = runTest {
        val h = harness()
        val fired = reenterOnce(h, StudioIntent.StartCuration) { it.phase == StudioPhase.Ingesting }

        h.send(StudioIntent.ImportPhotos(listOf("u1")))
        assertTrue(fired())
        runCurrent()
        // Curation follows the import at once, not after the auto-curation debounce: the request was kept.
        assertEquals(StudioPhase.Curating, h.state.phase)
        assertEquals(listOf(listOf(a, b, c)), h.deps.curation.curated)
        advanceUntilIdle()

        assertNull("the import's photos are curated, not reported missing", h.state.error)
        assertEquals(StudioPhase.Ready, h.state.phase)
        assertEquals(listOf(listOf(a, b, c)), h.deps.curation.curated)
    }

    @Test
    fun a_reset_sent_as_the_story_appears_retires_it_and_releases_its_photos_once() = runTest {
        val h = harness()
        reenterOnce(h, StudioIntent.Reset) { it.phase == StudioPhase.Ready }

        h.send(StudioIntent.ImportPhotos(listOf("u1")))
        advanceUntilIdle()

        assertEquals(StudioState(), h.state)
        assertEquals(listOf("a", "b"), h.deps.preparer.released.sorted())
        assertEquals(1, h.deps.sessions.single().closeCount)
    }
}
