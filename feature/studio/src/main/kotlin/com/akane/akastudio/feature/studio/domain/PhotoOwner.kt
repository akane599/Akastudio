package com.akane.akastudio.feature.studio.domain

import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The party that owns renderer registrations: one per story generation, with a process-unique [id].
 *
 * Placed in the coroutine context of every [PhotoPreparer.prepare] and [PhotoPreparer.release] call, it separates a
 * photo's stable identity (its candidate id, which repeats across imports of the same photo) from the registration a
 * particular story made of it: the preparer registers an owner's photo under a key made of the owner's [id] and the
 * candidate id, so two stories (a retired one whose last registration lands late, or two live studios on one
 * container) never share a registration, and a release under one owner resolves only that owner's keys (the one
 * exception is the retry of an unregister that failed earlier, which any later release attempts, whoever owned it).
 */
class PhotoOwner(label: String = "photos") : AbstractCoroutineContextElement(PhotoOwner) {

    /** Unique for the process; part of every renderer key this owner registers. */
    val id: String = "$label#${SEQUENCE.incrementAndGet()}"

    override fun toString(): String = "PhotoOwner($id)"

    companion object Key : CoroutineContext.Key<PhotoOwner> {
        private val SEQUENCE = AtomicLong()
    }
}
