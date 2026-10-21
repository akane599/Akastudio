package com.akane.akastudio.core.curator.ml

import com.akane.akastudio.core.color.api.NormRect
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdapterSupportTest {

    @Test
    fun landmarkBoxIsMinMaxClampedToUnitSquare() {
        val box = FaceGeometry.landmarkBox(floatArrayOf(0.2f, -0.1f, 0.5f), floatArrayOf(0.3f, 0.9f, 1.2f))
        assertEquals(NormRect(0f, 0.3f, 0.5f, 1f), box)
    }

    @Test
    fun blendshapeIsLookedUpByName() {
        val scores = listOf("eyeBlinkRight" to 0.8f, "eyeBlinkLeft" to 0.1f, "jawOpen" to 0.5f)
        assertEquals(0.1f, FaceGeometry.blendshape(scores, FaceGeometry.EYE_BLINK_LEFT), 0f)
        assertEquals(0.8f, FaceGeometry.blendshape(scores, FaceGeometry.EYE_BLINK_RIGHT), 0f)
        assertEquals(0f, FaceGeometry.blendshape(emptyList(), FaceGeometry.EYE_BLINK_LEFT), 0f)
        assertEquals(1f, FaceGeometry.blendshape(listOf("eyeBlinkLeft" to 1.3f), FaceGeometry.EYE_BLINK_LEFT), 0f)
    }

    @Test
    fun callsAreSerialized() = runBlocking {
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val resource = SerializedResource(Any(), Dispatchers.Default) {}
        (1..32).map {
            async(Dispatchers.Default) {
                resource.withResource {
                    maxActive.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                    Thread.sleep(1)
                    active.decrementAndGet()
                }
            }
        }.awaitAll()
        assertEquals(1, maxActive.get())
    }

    @Test
    fun closeDuringACallReleasesOnceAfterTheCallEnds() = runBlocking {
        val releases = AtomicInteger()
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val resource = SerializedResource(Any(), Dispatchers.IO) { releases.incrementAndGet() }
        val call = async(Dispatchers.IO) {
            resource.withResource {
                entered.countDown()
                check(proceed.await(5, TimeUnit.SECONDS)) { "test gate timed out" }
            }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        resource.close()
        assertEquals("release is deferred while a call holds the model", 0, releases.get())
        proceed.countDown()
        call.await()
        assertEquals(1, releases.get())
        resource.close()
        assertEquals(1, releases.get())
    }

    @Test
    fun closeWithoutCallsReleasesImmediately() {
        val releases = AtomicInteger()
        SerializedResource(Any(), Dispatchers.Default) { releases.incrementAndGet() }.close()
        assertEquals(1, releases.get())
    }

    @Test
    fun callsAfterCloseFail() = runBlocking {
        val resource = SerializedResource(Any(), Dispatchers.Default) {}
        resource.close()
        val failure = runCatching { resource.withResource { } }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }
}
