package com.akane.akastudio.core.ingestion.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CandidateIdsTest {

    @Test
    fun stableCandidateId_matchesKnownSha256Vector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            stableCandidateId("abc"),
        )
    }

    @Test
    fun stableCandidateId_isDeterministic() {
        val identity = "content://media/picker/0/com.android.providers.media.photopicker/media/42"
        assertEquals(stableCandidateId(identity), stableCandidateId(identity))
    }

    @Test
    fun stableCandidateId_differsForDistinctInputs() {
        assertNotEquals(
            stableCandidateId("content://archive/1#IMG_0001.jpg"),
            stableCandidateId("content://archive/1#IMG_0002.jpg"),
        )
    }
}
