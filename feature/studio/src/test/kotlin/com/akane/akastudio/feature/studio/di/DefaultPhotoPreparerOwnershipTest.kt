package com.akane.akastudio.feature.studio.di

import com.akane.akastudio.core.color.api.ImagePixels
import com.akane.akastudio.core.curator.api.PhotoAnalysis
import com.akane.akastudio.core.ingestion.model.PhotoCandidate
import com.akane.akastudio.feature.studio.domain.PhotoOwner
import com.akane.akastudio.feature.studio.domain.TestData
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** [DefaultPhotoPreparer] registers each owner's photos under keys of its own, so owners never share a registration. */
class DefaultPhotoPreparerOwnershipTest {

    private class Image(override val sourceWidth: Int, override val sourceHeight: Int) : FullResolutionImage {
        var freed = 0
        override fun free() {
            freed++
        }
    }

    private class Backend : PreparerBackend {
        val images = mutableListOf<Image>()
        val registry = LinkedHashMap<String, Image>()
        val unregistered = mutableListOf<String>()
        val failUnregister = mutableSetOf<String>()

        override suspend fun maxTextureSize(): Int = 8192

        override suspend fun decodeFull(
            candidate: PhotoCandidate,
            maxLongEdge: Int,
            callerCancelled: () -> Boolean,
        ): FullResolutionImage = Image(candidate.widthPx, candidate.heightPx).also { images += it }

        override suspend fun register(key: String, image: FullResolutionImage) {
            registry[key] = image as Image
        }

        override suspend fun unregister(key: String) {
            unregistered += key
            if (key in failUnregister) throw IllegalStateException("unregister $key failed")
            registry.remove(key)
        }

        override suspend fun analysisPixels(candidate: PhotoCandidate, maxEdgePx: Int) = ImagePixels(1, 1, intArrayOf(0))
    }

    private val backend = Backend()
    private val preparer = DefaultPhotoPreparer(backend, horizon = { 0f })
    private val a = TestData.candidate("a")
    private val analyses: Map<String, PhotoAnalysis> = mapOf("a" to TestData.analysis("a"))
    private val retired = PhotoOwner("retired")
    private val current = PhotoOwner("current")

    private val retiredA = "${retired.id}/a"
    private val currentA = "${current.id}/a"

    @Test
    fun two_owners_preparing_the_same_photo_hold_separate_registrations() = runTest {
        val retiredPhoto = withContext(retired) { preparer.prepare(listOf(a), analyses, hero = true) }.single()
        val currentPhoto = withContext(current) { preparer.prepare(listOf(a), analyses, hero = false) }.single()
        val (old, new) = backend.images
        assertEquals("the scene refers to the owner's own registration", retiredA, retiredPhoto.imageKey)
        assertEquals(currentA, currentPhoto.imageKey)
        assertEquals("a", currentPhoto.id)
        assertEquals("the earlier owner's decode is not replaced by the later owner's", 0, old.freed)
        assertEquals(setOf(retiredA, currentA), backend.registry.keys)

        withContext(retired) { preparer.release(listOf("a")) }

        assertEquals("a release reaches only the caller's registration", listOf(retiredA), backend.unregistered)
        assertEquals(1, old.freed)
        assertSame(new, backend.registry[currentA])
        assertEquals(0, new.freed)

        withContext(current) { preparer.release(listOf("a")) }

        assertEquals(listOf(retiredA, currentA), backend.unregistered)
        assertEquals(1, new.freed)
        assertTrue(backend.registry.isEmpty())
    }

    @Test
    fun preparing_a_photo_again_under_the_same_owner_replaces_that_owners_registration() = runTest {
        withContext(current) { preparer.prepare(listOf(a), analyses, hero = true) }
        withContext(current) { preparer.prepare(listOf(a), analyses, hero = false) }
        val (old, new) = backend.images

        assertEquals("the replaced decode was freed", 1, old.freed)
        assertEquals(listOf(currentA), backend.registry.keys.toList())
        assertSame(new, backend.registry[currentA])
        assertTrue(backend.unregistered.isEmpty())
    }

    @Test
    fun calls_without_an_owner_use_the_bare_candidate_id() = runTest {
        val photo = preparer.prepare(listOf(a), analyses, hero = false).single()
        assertEquals("a", photo.imageKey)

        withContext(current) { preparer.release(listOf("a")) }
        assertTrue("an owner does not release an anonymous registration", backend.unregistered.isEmpty())

        preparer.release(listOf("a"))
        assertEquals(listOf("a"), backend.unregistered)
        assertEquals(1, backend.images.single().freed)
    }

    @Test
    fun a_failed_unregister_is_retried_by_whoever_releases_next() = runTest {
        withContext(retired) { preparer.prepare(listOf(a), analyses, hero = false) }
        backend.failUnregister += retiredA
        try {
            withContext(retired) { preparer.release(listOf("a")) }
            fail("expected the unregister failure")
        } catch (e: IllegalStateException) {
            assertEquals("unregister $retiredA failed", e.message)
        }
        backend.failUnregister.clear()

        withContext(current) { preparer.release(listOf("unknown")) }

        assertEquals(listOf(retiredA, retiredA), backend.unregistered)
        assertEquals(1, backend.images.single().freed)
        assertTrue(backend.registry.isEmpty())
    }
}
