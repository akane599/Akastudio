package com.akane.akastudio.core.export.device

import android.content.ContentResolver
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.akane.akastudio.core.export.store.MediaStoreSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

/**
 * T-05: a write that fails part-way, by an I/O error or by cancellation, must leave no MediaStore row behind: neither
 * a published one nor a pending one (only visible with `QUERY_ARG_MATCH_PENDING = MATCH_INCLUDE`). Each test first
 * checks, from inside the write, that the pending row really existed, so the final absence proves the cleanup.
 */
@RunWith(AndroidJUnit4::class)
class MediaStoreFailureDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val sink = MediaStoreSink(context)
    private val runTag = "AkaStudioQA_T05_" + Random.nextInt(0x1000000).toString(16)
    private val names = mutableListOf<String>()

    @After
    fun deleteLeftovers() {
        // Only reached with rows left when a test already failed; keeps the shared device clean either way.
        for (name in names) resolver.delete(IMAGES, namedArgs(name))
    }

    @Test
    fun ioExceptionMidWrite_leavesNoPendingRow() {
        val name = displayName("io")
        val failure = IOException("disk full")
        val thrown = assertThrows(IOException::class.java) {
            sink.write(name, "image/png", null) { out ->
                out.write(ByteArray(64 * 1024) { it.toByte() })
                out.flush()
                assertPendingRowExists(name)
                throw failure
            }
        }
        assertSame("the sink rethrows the writer's failure", failure, thrown)
        assertEquals("rows left for $name (pending included)", 0, rowCount(name))
    }

    @Test
    fun cancellationMidWrite_leavesNoPendingRow() {
        val name = displayName("cancel")
        val cancel = CancellationException("export cancelled")
        val thrown = assertThrows(CancellationException::class.java) {
            sink.write(name, "image/jpeg", 1_720_000_000_000L) { out ->
                out.write(ByteArray(64 * 1024) { (it * 7).toByte() })
                out.flush()
                assertPendingRowExists(name)
                throw cancel
            }
        }
        assertSame("the sink rethrows the cancellation", cancel, thrown)
        assertEquals("rows left for $name (pending included)", 0, rowCount(name))
    }

    private fun displayName(kind: String): String = "${runTag}_$kind.${if (kind == "io") "png" else "jpg"}".also { names += it }

    private fun assertPendingRowExists(name: String) {
        val pending = pendingFlags(name)
        record("mediastore.failure.$name.pendingDuringWrite", pending)
        assertTrue("the sink inserted no pending row for $name: $pending", pending == listOf(1))
    }

    private fun rowCount(name: String): Int = pendingFlags(name).size

    /** IS_PENDING of every row named [name], pending rows included. */
    private fun pendingFlags(name: String): List<Int> {
        val flags = ArrayList<Int>()
        resolver.query(IMAGES, arrayOf(MediaStore.MediaColumns.IS_PENDING), namedArgs(name), null)?.use { c ->
            while (c.moveToNext()) flags += c.getInt(0)
        }
        return flags
    }

    private fun namedArgs(name: String) = Bundle().apply {
        putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.DISPLAY_NAME} = ?")
        putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(name))
    }

    private companion object {
        val IMAGES = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }
}
