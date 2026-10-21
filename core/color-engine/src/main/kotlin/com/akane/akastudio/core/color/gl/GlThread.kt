package com.akane.akastudio.core.color.gl

import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.android.asCoroutineDispatcher

/** The single dedicated GL thread: a [HandlerThread] exposed as a single-threaded [CoroutineDispatcher]. */
internal class GlThread(name: String) {
    private val thread = HandlerThread(name).apply { start() }
    private val handler = Handler(thread.looper)

    /** Everything that touches EGL/GL runs here. */
    val dispatcher: CoroutineDispatcher = handler.asCoroutineDispatcher(name)

    /** Queues [block] after all work already posted; false when the thread has quit. */
    fun post(block: () -> Unit): Boolean = handler.post(block)

    /** Lets queued work finish, then ends the looper. */
    fun quitSafely() {
        thread.quitSafely()
    }
}
