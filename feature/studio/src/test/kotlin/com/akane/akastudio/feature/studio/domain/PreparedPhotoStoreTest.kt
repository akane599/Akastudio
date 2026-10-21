package com.akane.akastudio.feature.studio.domain

import com.akane.akastudio.feature.studio.domain.TestData.candidate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PreparedPhotoStoreTest {

    private val a = candidate("a")
    private val b = candidate("b")
    private val c = candidate("c")

    @Test
    fun acquire_prepares_only_missing_and_returns_in_candidate_order() = runTest {
        val preparer = FakePreparer(delayMs = 0)
        val store = PreparedPhotoStore(preparer, StandardTestDispatcher(testScheduler))

        store.acquire(listOf(a, b), emptyMap(), hero = false)
        val photos = store.acquire(listOf(c, b, a), emptyMap(), hero = false)

        assertEquals(listOf(listOf("a", "b") to false, listOf("c") to false), preparer.prepareCalls)
        assertEquals(listOf("c", "b", "a"), photos.map { it.id })
        assertTrue(store.covers(listOf("a", "b", "c"), hero = false))
        assertTrue(preparer.released.isEmpty())
    }

    @Test
    fun acquire_reprepares_when_the_hero_budget_changes() = runTest {
        val preparer = FakePreparer(delayMs = 0)
        val store = PreparedPhotoStore(preparer, StandardTestDispatcher(testScheduler))

        store.acquire(listOf(a, b), emptyMap(), hero = false)
        store.acquire(listOf(a), emptyMap(), hero = true)

        assertEquals(listOf("a") to true, preparer.prepareCalls.last())
        assertTrue(store.covers(listOf("a"), hero = true))
        assertFalse(store.covers(listOf("a"), hero = false))
    }

    @Test
    fun failed_batch_is_released_and_forgotten() = runTest {
        val preparer = FakePreparer(delayMs = 0)
        val store = PreparedPhotoStore(preparer, StandardTestDispatcher(testScheduler))
        store.acquire(listOf(a), emptyMap(), hero = false)
        preparer.failWith = IllegalStateException("decode failed")

        try {
            store.acquire(listOf(a, b, c), emptyMap(), hero = false)
            fail("expected the preparer failure")
        } catch (e: IllegalStateException) {
            assertEquals("decode failed", e.message)
        }

        assertEquals(listOf("b", "c"), preparer.released)
        assertTrue(store.covers(listOf("a"), hero = false))
        assertFalse(store.covers(listOf("b"), hero = false))
    }

    @Test
    fun cancelled_batch_is_released() = runTest {
        val preparer = FakePreparer(delayMs = 1_000)
        val store = PreparedPhotoStore(preparer, StandardTestDispatcher(testScheduler))

        val job = launch { store.acquire(listOf(a, b), emptyMap(), hero = false) }
        runCurrent()
        job.cancel()
        advanceUntilIdle()

        assertEquals(listOf("a", "b"), preparer.released)
        assertTrue(store.detachAll().isEmpty())
    }

    @Test
    fun mismatched_preparer_result_is_rejected_and_released() = runTest {
        val preparer = FakePreparer(delayMs = 0).apply { dropFromResult = "b" }
        val store = PreparedPhotoStore(preparer, StandardTestDispatcher(testScheduler))

        try {
            store.acquire(listOf(a, b), emptyMap(), hero = false)
            fail("expected the mismatch to be rejected")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Preparer returned"))
        }
        assertEquals(setOf("a", "b"), preparer.released.toSet())
        assertTrue(store.detachAll().isEmpty())
    }

    @Test
    fun retainOnly_releases_unused_and_detachAll_empties() = runTest {
        val preparer = FakePreparer(delayMs = 0)
        val store = PreparedPhotoStore(preparer, StandardTestDispatcher(testScheduler))
        store.acquire(listOf(a, b, c), emptyMap(), hero = false)

        store.retainOnly(listOf("b"))
        assertEquals(listOf("a", "c"), preparer.released)
        assertTrue(store.covers(listOf("b"), hero = false))

        assertEquals(listOf("b"), store.detachAll())
        assertTrue(store.detachAll().isEmpty())
        assertEquals("detachAll only forgets; the caller releases", listOf("a", "c"), preparer.released)
    }
}
