package com.akane.akastudio.core.curator.ml

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SerializedResourceTest {

    private class ReleaseFailed : RuntimeException("release failed")

    /** Starts a call that blocks inside the resource, closes the resource, then lets the call finish. */
    private fun <R> closeDuringCall(resource: SerializedResource<Any>, body: () -> R): Result<R> = runBlocking {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val call = async(Dispatchers.IO) {
            runCatching {
                resource.withResource {
                    entered.countDown()
                    check(proceed.await(5, TimeUnit.SECONDS)) { "test gate timed out" }
                    body()
                }
            }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        resource.close()
        proceed.countDown()
        call.await()
    }

    @Test
    fun throwingDeferredReleaseDoesNotMaskTheResult() {
        val releases = AtomicInteger()
        val resource = SerializedResource(Any(), Dispatchers.IO) {
            releases.incrementAndGet()
            throw ReleaseFailed()
        }
        val result = closeDuringCall(resource) { 42 }
        assertEquals(42, result.getOrThrow())
        assertEquals("release ran exactly once", 1, releases.get())
    }

    @Test
    fun throwingDeferredReleaseDoesNotReplaceTheCallsException() {
        val resource = SerializedResource(Any(), Dispatchers.IO) { throw ReleaseFailed() }
        val result = closeDuringCall(resource) { throw IllegalArgumentException("inference failed") }
        val failure = result.exceptionOrNull()
        assertTrue("got $failure", failure is IllegalArgumentException)
    }

    @Test
    fun throwingImmediateReleasePropagatesToClose() {
        val resource = SerializedResource(Any(), Dispatchers.IO) { throw ReleaseFailed() }
        val failure = runCatching { resource.close() }.exceptionOrNull()
        assertTrue("got $failure", failure is ReleaseFailed)
    }
}
