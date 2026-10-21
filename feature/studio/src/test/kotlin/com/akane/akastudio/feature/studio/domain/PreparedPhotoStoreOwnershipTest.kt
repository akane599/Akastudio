package com.akane.akastudio.feature.studio.domain

import com.akane.akastudio.core.composer.api.ComposerPhoto
import com.akane.akastudio.core.curator.api.PhotoAnalysis
import com.akane.akastudio.core.ingestion.model.PhotoCandidate
import com.akane.akastudio.feature.studio.domain.TestData.candidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The store's error propagation and the [PhotoOwner] it puts on every preparer call. */
@OptIn(ExperimentalCoroutinesApi::class)
class PreparedPhotoStoreOwnershipTest {

    private class OwnerRecordingPreparer : PhotoPreparer {
        val prepareOwners = mutableListOf<PhotoOwner?>()
        val releaseOwners = mutableListOf<PhotoOwner?>()
        val released = mutableListOf<String>()
        var prepareDelayMs = 0L
        var prepareFailWith: Exception? = null
        var releaseFailWith: Exception? = null

        override suspend fun prepare(
            candidates: List<PhotoCandidate>,
            analyses: Map<String, PhotoAnalysis>,
            hero: Boolean,
        ): List<ComposerPhoto> {
            prepareOwners += currentCoroutineContext()[PhotoOwner]
            if (prepareDelayMs > 0) delay(prepareDelayMs)
            prepareFailWith?.let { throw it }
            return candidates.map { TestData.composerPhoto(it.id) }
        }

        override suspend fun release(ids: Collection<String>) {
            releaseOwners += currentCoroutineContext()[PhotoOwner]
            released += ids
            releaseFailWith?.let { throw it }
        }
    }

    private val a = candidate("a")
    private val b = candidate("b")

    @Test
    fun a_cancelled_acquire_stays_a_cancellation_when_the_cleanup_release_fails() = runTest {
        val preparer = OwnerRecordingPreparer().apply {
            prepareDelayMs = 1_000
            releaseFailWith = IllegalStateException("EGL lost")
        }
        val store = PreparedPhotoStore(preparer, StandardTestDispatcher(testScheduler))
        var thrown: Throwable? = null

        val job = launch {
            try {
                store.acquire(listOf(a, b), emptyMap(), hero = false)
            } catch (t: Throwable) {
                thrown = t
                throw t
            }
        }
        runCurrent()
        job.cancel()
        advanceUntilIdle()

        assertTrue(job.isCancelled)
        assertTrue("the caller sees its cancellation, not the cleanup failure", thrown is CancellationException)
        assertEquals(listOf("a", "b"), preparer.released)
        assertEquals("the cleanup failure rides along as suppressed", listOf("EGL lost"), thrown!!.suppressed.map { it.message })
        assertTrue(store.detachAll().isEmpty())
    }

    @Test
    fun a_failed_acquire_keeps_its_own_failure_and_attaches_the_cleanup_failure() = runTest {
        val preparer = OwnerRecordingPreparer().apply {
            prepareFailWith = IllegalStateException("decode failed")
            releaseFailWith = IllegalStateException("EGL lost")
        }
        val store = PreparedPhotoStore(preparer, StandardTestDispatcher(testScheduler))

        try {
            store.acquire(listOf(a), emptyMap(), hero = false)
            fail("expected the preparer failure")
        } catch (e: IllegalStateException) {
            assertEquals("decode failed", e.message)
            assertEquals(listOf("EGL lost"), e.suppressed.map { it.message })
        }
        assertEquals(listOf("a"), preparer.released)
    }

    @Test
    fun every_preparer_call_carries_the_stores_owner() = runTest {
        val preparer = OwnerRecordingPreparer()
        val io = StandardTestDispatcher(testScheduler)
        val store = PreparedPhotoStore(preparer, io)
        val other = PreparedPhotoStore(preparer, io)
        assertNotSame("each store is its own owner", store.owner, other.owner)

        store.acquire(listOf(a, b), emptyMap(), hero = false)
        store.retainOnly(listOf("a"))
        store.release(listOf("a"))
        other.acquire(listOf(a), emptyMap(), hero = false)

        assertEquals(listOf(store.owner, other.owner), preparer.prepareOwners)
        assertEquals(listOf(store.owner, store.owner), preparer.releaseOwners)
        assertSame(store.owner, preparer.releaseOwners.first())
    }

    @Test
    fun release_of_nothing_never_reaches_the_preparer() = runTest {
        val preparer = OwnerRecordingPreparer()
        val store = PreparedPhotoStore(preparer, StandardTestDispatcher(testScheduler))
        store.release(emptyList())
        store.retainOnly(emptyList())
        assertTrue(preparer.releaseOwners.isEmpty())
    }
}
