package com.akane.akastudio.core.ingestion.pipeline

import com.akane.akastudio.core.ingestion.model.IngestionTelemetry
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class TelemetryAccumulatorTest {

    @Test
    fun record_publishesSnapshotAfterApplyingTheChange() = runTest {
        val accumulator = TelemetryAccumulator()

        val first = accumulator.record({ it.copy(sourcesTotal = it.sourcesTotal + 2) }) { it }
        val second = accumulator.record({ it.copy(bytesExtracted = it.bytesExtracted + 7) }) { it }

        assertEquals(IngestionTelemetry(sourcesTotal = 2), first)
        assertEquals(IngestionTelemetry(sourcesTotal = 2, bytesExtracted = 7), second)
    }

    @Test
    fun concurrentRecords_publishInApplyOrder() = runTest {
        val accumulator = TelemetryAccumulator()
        val published = mutableListOf<Int>()

        repeat(20) {
            launch {
                accumulator.record({ it.copy(filesRead = it.filesRead + 1) }) { snapshot ->
                    yield() // a suspending publisher must not let another change interleave
                    published += snapshot.filesRead
                }
            }
        }
        testScheduler.advanceUntilIdle()

        assertEquals((1..20).toList(), published)
    }
}
