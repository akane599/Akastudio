package com.akane.akastudio.feature.studio.di

import com.akane.akastudio.core.color.api.ImagePixels
import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.color.api.PaletteSwatch
import com.akane.akastudio.core.color.api.OkLab
import com.akane.akastudio.core.curator.api.FaceObservation
import com.akane.akastudio.core.curator.api.PhotoAnalysis
import com.akane.akastudio.core.ingestion.model.PhotoCandidate
import com.akane.akastudio.feature.studio.domain.TestData
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DefaultPhotoPreparerTest {

    private class FakeImage(val candidateId: String, override val sourceWidth: Int, override val sourceHeight: Int) :
        FullResolutionImage {
        var freed = 0
        override fun free() {
            freed++
        }
    }

    private class FakeBackend(var maxTexture: Int = 8192) : PreparerBackend {
        val decodeEdges = mutableListOf<Pair<String, Int>>()
        val images = mutableListOf<FakeImage>()
        val registry = LinkedHashMap<String, FakeImage>()
        val unregistered = mutableListOf<String>()
        val analysisEdges = mutableListOf<Int>()
        val failDecode = mutableSetOf<String>()
        val failRegister = mutableSetOf<String>()
        val failAnalysis = mutableSetOf<String>()
        val failUnregister = mutableSetOf<String>()

        /** How long [register] suspends before recording the registration. */
        var registerDelayMs = 0L
        var inFlight = 0
        var maxInFlight = 0

        override suspend fun maxTextureSize(): Int = maxTexture

        override suspend fun decodeFull(candidate: PhotoCandidate, maxLongEdge: Int): FullResolutionImage {
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            try {
                delay(10)
                decodeEdges += candidate.id to maxLongEdge
                if (candidate.id in failDecode) throw IOException("truncated ${candidate.id}")
                return FakeImage(candidate.id, candidate.widthPx, candidate.heightPx).also { images += it }
            } finally {
                inFlight--
            }
        }

        override suspend fun register(key: String, image: FullResolutionImage) {
            if (registerDelayMs > 0) delay(registerDelayMs)
            if (key in failRegister) throw IllegalArgumentException("too large")
            registry[key] = image as FakeImage
        }

        override suspend fun unregister(key: String) {
            unregistered += key
            registry.remove(key)
            if (key in failUnregister) throw IllegalStateException("unregister $key failed")
        }

        override suspend fun analysisPixels(candidate: PhotoCandidate, maxEdgePx: Int): ImagePixels {
            analysisEdges += maxEdgePx
            if (candidate.id in failAnalysis) throw IOException("unreadable")
            return ImagePixels(1, 1, intArrayOf(candidate.id.length))
        }
    }

    private val backend = FakeBackend()
    private val preparer = DefaultPhotoPreparer(backend, horizon = { pixels -> pixels.argb[0] * 0.5f })

    private val a = TestData.candidate("a")
    private val b = TestData.candidate("bb")
    private val face = FaceObservation(NormRect(0.1f, 0.1f, 0.3f, 0.3f), 1f, 1f, 0.9f)
    private val swatch = PaletteSwatch(OkLab(0.6f, 0.02f, 0.03f), 1f)
    private val analyses: Map<String, PhotoAnalysis> = mapOf(
        "a" to TestData.analysis("a").copy(faces = listOf(face), palette = listOf(swatch)),
        "bb" to TestData.analysis("bb"),
    )

    @Test
    fun decodeEdgeIsTheTextureLimitCappedByTheHeroOrCollageBudget() {
        assertEquals(4096, DefaultPhotoPreparer.decodeEdge(16384, hero = true))
        assertEquals(3072, DefaultPhotoPreparer.decodeEdge(16384, hero = false))
        assertEquals(2048, DefaultPhotoPreparer.decodeEdge(2048, hero = true))
        assertEquals(2048, DefaultPhotoPreparer.decodeEdge(2048, hero = false))
    }

    @Test
    fun prepareDecodesRegistersAndBuildsComposerPhotosFromTheAnalysis() = runTest {
        val photos = preparer.prepare(listOf(a, b), analyses, hero = false)

        assertEquals(listOf("a" to 3072, "bb" to 3072), backend.decodeEdges)
        assertEquals(listOf("a", "bb"), backend.registry.keys.toList())
        assertEquals(listOf("a", "bb"), photos.map { it.id })
        val first = photos[0]
        assertEquals("a", first.imageKey)
        assertEquals(4000, first.widthPx)
        assertEquals(3000, first.heightPx)
        assertSame(analyses.getValue("a").saliency, first.saliency)
        assertEquals(listOf(face), first.faces)
        assertEquals(listOf(swatch), first.palette)
        assertEquals(a.metadata, first.metadata)
        // The horizon detector reads the 1024-px analysis image of each photo.
        assertEquals(listOf(1024, 1024), backend.analysisEdges)
        assertEquals(0.5f, first.horizonTiltDegrees)
        assertEquals(1f, photos[1].horizonTiltDegrees)
    }

    @Test
    fun heroUsesTheHeroBudget() = runTest {
        preparer.prepare(listOf(a), analyses, hero = true)
        assertEquals(listOf("a" to 4096), backend.decodeEdges)
    }

    @Test
    fun registeredImagesAreFreedOnlyAfterReleaseUnregistersThem() = runTest {
        preparer.prepare(listOf(a, b), analyses, hero = false)
        assertTrue(backend.images.all { it.freed == 0 })

        preparer.release(listOf("a", "unknown"))

        assertEquals(listOf("a"), backend.unregistered)
        assertEquals(1, backend.images.single { it.candidateId == "a" }.freed)
        assertEquals(0, backend.images.single { it.candidateId == "bb" }.freed)

        preparer.release(listOf("a"))
        assertEquals(listOf("a"), backend.unregistered)
        assertEquals(1, backend.images.single { it.candidateId == "a" }.freed)
    }

    @Test
    fun releaseFreesEveryImageWhenAnUnregisterFailsAndThenRethrows() = runTest {
        preparer.prepare(listOf(a, b), analyses, hero = false)
        backend.failUnregister += "a"
        try {
            preparer.release(listOf("a", "bb"))
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("unregister a failed", e.message)
        }
        assertEquals(listOf("a", "bb"), backend.unregistered)
        assertTrue(backend.images.all { it.freed == 1 })
    }

    @Test
    fun preparingAnIdAgainFreesTheReplacedImageOnly() = runTest {
        preparer.prepare(listOf(a), analyses, hero = false)
        preparer.prepare(listOf(a), analyses, hero = true)

        val (old, new) = backend.images
        assertEquals(1, old.freed)
        assertEquals(0, new.freed)
        assertSame(new, backend.registry.getValue("a"))
        assertTrue(backend.unregistered.isEmpty())
    }

    @Test
    fun duplicateCandidatesAreDecodedOnce() = runTest {
        val photos = preparer.prepare(listOf(a, a), analyses, hero = false)
        assertEquals(2, photos.size)
        assertEquals(1, backend.decodeEdges.size)
    }

    @Test
    fun decodeFailureNamesThePhotoAndKeepsEarlierRegistrationsReleasable() = runTest {
        backend.failDecode += "bb"
        try {
            preparer.prepare(listOf(a, b), analyses, hero = false)
            fail("expected PhotoDecodeException")
        } catch (e: PhotoDecodeException) {
            assertEquals("bb.jpg", e.displayName)
            assertTrue(e.cause is IOException)
        }

        preparer.release(listOf("a", "bb"))
        assertEquals(listOf("a"), backend.unregistered)
        assertEquals(1, backend.images.single().freed)
    }

    @Test
    fun registerFailureFreesTheNewDecode() = runTest {
        backend.failRegister += "a"
        try {
            preparer.prepare(listOf(a), analyses, hero = false)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("too large", e.message)
        }
        assertEquals(1, backend.images.single().freed)
        preparer.release(listOf("a"))
        assertTrue(backend.unregistered.isEmpty())
    }

    @Test
    fun unreadableAnalysisImageLeavesThePhotoUnrotated() = runTest {
        backend.failAnalysis += "a"
        val photo = preparer.prepare(listOf(a), analyses, hero = false).single()
        assertEquals(0f, photo.horizonTiltDegrees)
    }

    @Test
    fun missingAnalysisFailsBeforeDecoding() = runTest {
        try {
            preparer.prepare(listOf(a), emptyMap(), hero = false)
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("a"))
        }
        assertTrue(backend.decodeEdges.isEmpty())
    }

    @Test
    fun decodesNeverOverlapAcrossConcurrentCalls() = runTest {
        val c = TestData.candidate("c")
        val all = analyses + ("c" to TestData.analysis("c"))
        listOf(
            async { preparer.prepare(listOf(a, b), all, hero = false) },
            async {
                yield()
                preparer.prepare(listOf(c), all, hero = false)
            },
        ).awaitAll()
        assertEquals(1, backend.maxInFlight)
        assertEquals(3, backend.registry.size)
        assertFalse(backend.images.any { it.freed > 0 })
    }
}
