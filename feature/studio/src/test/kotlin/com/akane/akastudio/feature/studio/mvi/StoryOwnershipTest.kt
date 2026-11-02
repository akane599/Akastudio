package com.akane.akastudio.feature.studio.mvi

import com.akane.akastudio.core.color.api.ImagePixels
import com.akane.akastudio.core.curator.api.SceneCluster
import com.akane.akastudio.core.curator.api.StoryMode
import com.akane.akastudio.core.ingestion.model.PhotoCandidate
import com.akane.akastudio.feature.studio.di.DefaultPhotoPreparer
import com.akane.akastudio.feature.studio.di.FullResolutionImage
import com.akane.akastudio.feature.studio.di.PreparerBackend
import com.akane.akastudio.feature.studio.domain.FakeCurationEngine
import com.akane.akastudio.feature.studio.domain.FakeStudioDependencies
import com.akane.akastudio.feature.studio.domain.PhotoPreparer
import com.akane.akastudio.feature.studio.domain.StudioDependencies
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ownership of renderer registrations across story generations, run against the production [DefaultPhotoPreparer]
 * (its mutex and registry) with a backend whose decode can be held open. Candidate ids repeat when the same photos
 * are imported again, so a retired generation's late cleanup and a replacement generation's preparation meet on
 * the same keys.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StoryOwnershipTest {

    private class TrackedImage(val id: String) : FullResolutionImage {
        override val sourceWidth = 4000
        override val sourceHeight = 3000
        var freed = 0
        override fun free() {
            freed++
        }
    }

    /** Decodes synchronously, except the next decode once [blockNextDecode] is set, which waits for [finishDecode]. */
    private class GatedBackend : PreparerBackend {
        /** Registered images by renderer key (`<owner>/<candidate id>`). */
        val registry = LinkedHashMap<String, TrackedImage>()

        /** Candidate ids with at least one registration, in any owner's name. */
        val registeredIds: Set<String> get() = registry.keys.mapTo(LinkedHashSet()) { it.substringAfterLast('/') }

        fun isRegistered(image: TrackedImage): Boolean = registry.values.any { it === image }

        /** Decode/register/unregister events by candidate id (the owner part of the key is dropped). */
        val events = mutableListOf<String>()
        val images = mutableListOf<TrackedImage>()
        var blockNextDecode = false
        val blocked = CompletableDeferred<Unit>()
        val finishDecode = CompletableDeferred<Unit>()
        var blockNextRegister = false
        val registerBlocked = CompletableDeferred<Unit>()
        val finishRegister = CompletableDeferred<Unit>()
        var blockNextUnregister = false
        val unregisterBlocked = CompletableDeferred<Unit>()
        val finishUnregister = CompletableDeferred<Unit>()
        val failUnregisterOnce = mutableSetOf<String>()

        override suspend fun maxTextureSize(): Int = 8192

        override suspend fun decodeFull(
            candidate: PhotoCandidate,
            maxLongEdge: Int,
            callerCancelled: () -> Boolean,
        ): FullResolutionImage {
            if (blockNextDecode) {
                blockNextDecode = false
                blocked.complete(Unit)
                finishDecode.await()
            }
            events += "decode:${candidate.id}"
            return TrackedImage(candidate.id).also { images += it }
        }

        override suspend fun register(key: String, image: FullResolutionImage) {
            if (blockNextRegister) {
                blockNextRegister = false
                registerBlocked.complete(Unit)
                finishRegister.await()
            }
            registry[key] = image as TrackedImage
            events += "register:${key.substringAfterLast('/')}"
        }

        override suspend fun unregister(key: String) {
            if (blockNextUnregister) {
                blockNextUnregister = false
                unregisterBlocked.complete(Unit)
                finishUnregister.await()
            }
            events += "unregister:${key.substringAfterLast('/')}"
            if (failUnregisterOnce.remove(key.substringAfterLast('/'))) throw IllegalStateException("EGL teardown failed")
            registry.remove(key)
        }

        override suspend fun analysisPixels(candidate: PhotoCandidate, maxEdgePx: Int) = ImagePixels(1, 1, intArrayOf(0))
    }

    private val a = TestData.candidate("a")
    private val b = TestData.candidate("b")
    private val result = TestData.result(
        chosenMode = StoryMode.COLLAGE_2,
        options = mapOf(StoryMode.COLLAGE_2 to listOf("a", "b"), StoryMode.HERO to listOf("a")),
        analyses = listOf(TestData.analysis("a"), TestData.analysis("b")),
        clusters = listOf(SceneCluster(0, listOf("a"), "a"), SceneCluster(1, listOf("b"), "b")),
    )

    private class Studio(val vm: StudioViewModel, val deps: FakeStudioDependencies) {
        val state: StudioState get() = vm.state.value
    }

    private fun TestScope.studio(preparer: PhotoPreparer): Studio {
        val base = FakeStudioDependencies(studioTestDispatchers(testScheduler), FakeCurationEngine(result))
        val deps = object : StudioDependencies by base {
            override val preparer: PhotoPreparer = preparer
        }
        val vm = StudioViewModel(deps, CoroutineScope(SupervisorJob(backgroundScope.coroutineContext.job) + deps.dispatchers.main))
        return Studio(vm, base)
    }

    private fun TestScope.importAB(s: Studio) {
        s.deps.ingestScripts += TestData.ingestion(listOf(a, b))
        s.vm.onIntent(StudioIntent.ImportPhotos(listOf("content://media/a", "content://media/b")))
    }

    /** Lands [s] in Ready on a,b. */
    private fun TestScope.ready(s: Studio, backend: GatedBackend) {
        importAB(s)
        advanceUntilIdle()
        assertEquals(StudioPhase.Ready, s.state.phase)
        assertEquals(setOf("a", "b"), backend.registeredIds)
    }

    /** Lands [s] in Ready on a,b and holds its hero re-preparation open inside the backend's decode. */
    private fun TestScope.readyThenHoldReprepare(s: Studio, backend: GatedBackend) {
        ready(s, backend)
        backend.blockNextDecode = true
        s.vm.onIntent(StudioIntent.ForceMode(StoryMode.HERO))
        runCurrent()
        assertTrue("the hero decode of a must be in flight", backend.blocked.isCompleted)
    }

    /** Lands [s] in Ready on a,b and holds its hero re-preparation open inside the renderer's register (after the decode). */
    private fun TestScope.readyThenHoldRegister(s: Studio, backend: GatedBackend) {
        ready(s, backend)
        backend.blockNextRegister = true
        s.vm.onIntent(StudioIntent.ForceMode(StoryMode.HERO))
        runCurrent()
        assertTrue("the hero register of a must be in flight", backend.registerBlocked.isCompleted)
    }

    @Test
    fun a_retired_generations_late_release_leaves_the_replacement_storys_photos_registered() = runTest {
        val backend = GatedBackend()
        val s = studio(DefaultPhotoPreparer(backend, horizon = { 0f }))
        readyThenHoldReprepare(s, backend)

        // Discard while the decode is held, and import the same photos again: the replacement queues on the mutex.
        s.vm.onIntent(StudioIntent.Reset)
        importAB(s)
        advanceTimeBy(1_200)
        runCurrent()
        assertEquals(StudioPhase.Preparing, s.state.phase)

        backend.finishDecode.complete(Unit)
        advanceUntilIdle()

        assertEquals(StudioPhase.Ready, s.state.phase)
        assertEquals(listOf("a", "b"), s.state.selection.photoIds)
        assertEquals("only the story on screen is registered", 2, backend.registry.size)
        // Decodes: a, b (first story), a again (the held hero decode), then a, b for the replacement.
        assertEquals(listOf("a", "b", "a", "a", "b"), backend.images.map { it.id })
        val replacement = backend.images.takeLast(2)
        replacement.forEach { assertTrue("the registration is the replacement's decode", backend.isRegistered(it)) }
        assertTrue("the replacement's pixels are still held", replacement.all { it.freed == 0 })
        assertTrue("the first story's decodes were freed", backend.images.take(3).all { it.freed == 1 })
        assertEquals(
            "the retired story unregistered its own two keys (the held decode replaced its a) and nothing else",
            listOf("unregister:a", "unregister:b"),
            backend.events.filter { it.startsWith("unregister:") },
        )
        assertNull(s.state.error)
        assertEquals(1, s.deps.sessions[0].closeCount)
        assertEquals(0, s.deps.sessions[1].closeCount)

        s.vm.onIntent(StudioIntent.Reset)
        advanceUntilIdle()
        assertTrue("the replacement generation releases its own photos", backend.registry.isEmpty())
    }

    @Test
    fun a_failed_cleanup_of_retired_work_does_not_reset_the_replacement_import() = runTest {
        val backend = GatedBackend()
        val s = studio(DefaultPhotoPreparer(backend, horizon = { 0f }))
        readyThenHoldReprepare(s, backend)

        s.vm.onIntent(StudioIntent.Reset)
        s.deps.ingestScripts += TestData.ingestion(listOf(a, b), stepMs = 5_000)
        s.vm.onIntent(StudioIntent.ImportPhotos(listOf("content://media/a", "content://media/b")))
        runCurrent()
        assertEquals(StudioPhase.Ingesting, s.state.phase)
        assertEquals(2, s.deps.sessions.size)

        // The held decode finishes, the cancelled batch releases a, and that unregister fails on the renderer.
        backend.failUnregisterOnce += "a"
        backend.finishDecode.complete(Unit)
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals("the replacement import is still being read", StudioPhase.Ingesting, s.state.phase)
        assertNull("the prior story's cleanup failure is not shown", s.state.error)
        assertEquals("the replacement session was not closed", 0, s.deps.sessions[1].closeCount)
        assertEquals(1, s.deps.sessions[0].closeCount)

        advanceUntilIdle()
        assertEquals(StudioPhase.Ready, s.state.phase)
        assertEquals(setOf("a", "b"), backend.registeredIds)
        assertEquals("the prior story's failed unregister of a was retried by its retirement's release", 2, backend.events.count { it == "unregister:a" })
        assertEquals("only the replacement's registrations remain", 2, backend.registry.size)
        assertNull(s.state.error)
    }

    @Test
    fun two_studios_sharing_one_preparer_do_not_release_each_others_photos() = runTest {
        val backend = GatedBackend()
        val preparer = DefaultPhotoPreparer(backend, horizon = { 0f })
        val first = studio(preparer)
        readyThenHoldReprepare(first, backend)

        // The first studio is cleared while its decode is held; a second one imports the same photos meanwhile.
        first.vm.onIntent(StudioIntent.Reset)
        val second = studio(preparer)
        importAB(second)
        advanceTimeBy(1_200)
        runCurrent()
        assertEquals(StudioPhase.Preparing, second.state.phase)

        backend.finishDecode.complete(Unit)
        advanceUntilIdle()

        assertEquals(StudioPhase.Ready, second.state.phase)
        assertEquals(setOf("a", "b"), backend.registeredIds)
        assertEquals(2, backend.registry.size)
        assertEquals(listOf("a", "b", "a", "a", "b"), backend.images.map { it.id })
        val replacement = backend.images.takeLast(2)
        replacement.forEach { assertTrue("the second studio's decode stays registered", backend.isRegistered(it)) }
        assertTrue(replacement.all { it.freed == 0 })
        assertTrue("the first studio's decodes were freed", backend.images.take(3).all { it.freed == 1 })
        assertEquals(StudioPhase.Empty, first.state.phase)
        assertNull(first.state.error)
    }

    @Test
    fun two_live_studios_on_one_preparer_show_the_same_photos_from_separate_registrations() = runTest {
        val backend = GatedBackend()
        val preparer = DefaultPhotoPreparer(backend, horizon = { 0f })
        val first = studio(preparer)
        ready(first, backend)
        val firstImages = backend.images.toList()
        val second = studio(preparer)
        importAB(second)
        advanceUntilIdle()

        assertEquals(StudioPhase.Ready, first.state.phase)
        assertEquals(StudioPhase.Ready, second.state.phase)
        assertEquals("each studio holds its own registration of a and b", 4, backend.registry.size)
        assertEquals(setOf("a", "b"), backend.registeredIds)
        assertTrue("the first studio's decodes were not replaced", firstImages.all { it.freed == 0 && backend.isRegistered(it) })
        val secondImages = backend.images.takeLast(2)
        assertTrue(secondImages.all { it.freed == 0 && backend.isRegistered(it) })
        val firstKeys = first.state.scene!!.layers.map { it.imageKey }
        val secondKeys = second.state.scene!!.layers.map { it.imageKey }
        assertEquals("each scene refers to its own studio's registrations", emptySet<String>(), firstKeys.intersect(secondKeys.toSet()))
        assertEquals(backend.registry.keys, (firstKeys + secondKeys).toSet())

        // Discarding the first studio takes only its registrations with it.
        first.vm.onIntent(StudioIntent.Reset)
        advanceUntilIdle()

        assertEquals(StudioPhase.Empty, first.state.phase)
        assertNull(first.state.error)
        assertEquals(StudioPhase.Ready, second.state.phase)
        assertEquals(secondKeys.toSet(), backend.registry.keys)
        assertTrue(firstImages.all { it.freed == 1 })
        assertTrue(secondImages.all { it.freed == 0 && backend.isRegistered(it) })
        assertEquals(2, backend.events.count { it.startsWith("unregister:") })

        second.vm.onIntent(StudioIntent.Reset)
        advanceUntilIdle()
        assertTrue(backend.registry.isEmpty())
        assertTrue(secondImages.all { it.freed == 1 })
    }

    @Test
    fun a_registration_completing_after_its_story_was_discarded_is_released_by_that_story_alone() = runTest {
        val backend = GatedBackend()
        val s = studio(DefaultPhotoPreparer(backend, horizon = { 0f }))
        readyThenHoldRegister(s, backend)

        // Discard while the GL register is held (it runs under NonCancellable), and import the same photos again.
        s.vm.onIntent(StudioIntent.Reset)
        assertEquals(StudioPhase.Empty, s.state.phase)
        importAB(s)
        advanceTimeBy(1_200)
        runCurrent()
        assertEquals("the replacement waits on the preparer for the held register", StudioPhase.Preparing, s.state.phase)
        val eventsBefore = backend.events.size

        backend.finishRegister.complete(Unit)
        advanceUntilIdle()

        assertEquals(StudioPhase.Ready, s.state.phase)
        assertNull(s.state.error)
        val after = backend.events.drop(eventsBefore)
        // The held register lands under the discarded story's key (replacing that story's earlier a); the
        // replacement's prepare, queued on the preparer, runs next and registers a and b under its own keys. The
        // discarded story's late release then unregisters only its own keys, after the replacement is on screen.
        assertEquals(
            listOf("register:a", "decode:a", "register:a", "decode:b", "register:b", "unregister:a", "unregister:b"),
            after,
        )
        assertEquals(setOf("a", "b"), backend.registeredIds)
        assertEquals(2, backend.registry.size)
        val replacement = backend.images.takeLast(2)
        replacement.forEach { assertTrue("the renderer holds the replacement's decodes", backend.isRegistered(it)) }
        assertTrue(replacement.all { it.freed == 0 })
        assertTrue("every decode of the discarded story was freed, after its unregister", backend.images.take(3).all { it.freed == 1 })
        assertEquals(1, s.deps.sessions[0].closeCount)
        assertEquals(0, s.deps.sessions[1].closeCount)
    }

    @Test
    fun a_retired_storys_release_held_in_the_renderer_does_not_touch_the_replacement_prepared_after_it() = runTest {
        val backend = GatedBackend()
        val s = studio(DefaultPhotoPreparer(backend, horizon = { 0f }))
        ready(s, backend)
        val first = backend.images.toList()

        // Reset: the retirement's release reaches the renderer and is held inside unregister(a).
        backend.blockNextUnregister = true
        s.vm.onIntent(StudioIntent.Reset)
        runCurrent()
        assertTrue("the retirement's unregister must be in flight", backend.unregisterBlocked.isCompleted)
        assertEquals(StudioPhase.Empty, s.state.phase)

        // The same photos are imported again meanwhile; their preparation queues behind the held release.
        importAB(s)
        advanceTimeBy(1_200)
        runCurrent()
        assertEquals(StudioPhase.Preparing, s.state.phase)
        assertEquals("nothing was decoded for the replacement yet", 2, backend.images.size)

        backend.finishUnregister.complete(Unit)
        advanceUntilIdle()

        assertEquals(StudioPhase.Ready, s.state.phase)
        assertNull(s.state.error)
        assertEquals(listOf("a", "b", "a", "b"), backend.images.map { it.id })
        assertTrue("the retired story's decodes were freed once", first.all { it.freed == 1 })
        val replacement = backend.images.takeLast(2)
        replacement.forEach { assertTrue("the replacement's decode is what the renderer holds", backend.isRegistered(it)) }
        assertTrue(replacement.all { it.freed == 0 })
        assertEquals(2, backend.registry.size)
        assertEquals("the retired story unregistered only its two images", 2, backend.events.count { it.startsWith("unregister:") })
        assertEquals(1, s.deps.sessions[0].closeCount)
        assertEquals(0, s.deps.sessions[1].closeCount)

        s.vm.onIntent(StudioIntent.Reset)
        advanceUntilIdle()
        assertTrue(backend.registry.isEmpty())
        assertTrue(replacement.all { it.freed == 1 })
    }
}
