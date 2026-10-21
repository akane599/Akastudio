package com.akane.akastudio.core.curator.ml

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns a non-thread-safe native model and runs every call on it one at a time.
 *
 * [close] never blocks: when a call is in flight the release is deferred to that call's exit. The
 * close flag is written before the lock is probed and every call probes it after unlocking, so at least
 * one side always observes the other and the resource is released exactly once.
 */
internal class SerializedResource<T : Any>(
    private val resource: T,
    private val dispatcher: CoroutineDispatcher,
    private val release: (T) -> Unit,
) : AutoCloseable {

    private val mutex = Mutex()

    @Volatile
    private var closeRequested = false

    /** Guarded by [mutex]. */
    private var released = false

    /** Runs [block] on [dispatcher] with exclusive access. Throws [IllegalStateException] once closed. */
    suspend fun <R> withResource(block: (T) -> R): R {
        try {
            return mutex.withLock {
                check(!closeRequested) { "model is closed" }
                withContext(dispatcher) { block(resource) }
            }
        } finally {
            releaseIfRequested()
        }
    }

    override fun close() {
        closeRequested = true
        releaseIfRequested()
    }

    private fun releaseIfRequested() {
        if (!closeRequested || !mutex.tryLock()) return
        try {
            if (!released) {
                released = true
                release(resource)
            }
        } finally {
            mutex.unlock()
        }
    }
}
