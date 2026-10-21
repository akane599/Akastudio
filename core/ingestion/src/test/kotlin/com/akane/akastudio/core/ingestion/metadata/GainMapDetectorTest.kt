package com.akane.akastudio.core.ingestion.metadata

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GainMapDetectorTest {
    private val namespace = "http://ns.adobe.com/hdr-gain-map/1.0/"

    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    private val ultraHdrXmp = """
        <x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
        <rdf:Description xmlns:hdrgm="$namespace" hdrgm:Version="1.0"/></rdf:RDF></x:xmpmeta>
    """.trimIndent()

    private val plainXmp = """
        <x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
        <rdf:Description xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:CreatorTool="Camera"/></rdf:RDF></x:xmpmeta>
    """.trimIndent()

    /** Binary JPEG-ish noise around the packet, so the search runs over non-text bytes. */
    private fun embed(packet: String): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0, 0x7F) + ascii(packet) +
            ByteArray(64) { (it * 7).toByte() }

    @Test
    fun containsGainMapXmp_positiveUltraHdrPacket() {
        assertTrue(GainMapDetector.containsGainMapXmp(embed(ultraHdrXmp)))
    }

    @Test
    fun containsGainMapXmp_eitherMarkerAloneIsEnough() {
        assertTrue(GainMapDetector.containsGainMapXmp(embed("xmlns:hdrgm=\"$namespace\"")))
        assertTrue(GainMapDetector.containsGainMapXmp(embed("hdrgm:Version=\"1.0\"")))
    }

    @Test
    fun containsGainMapXmp_negativePlainXmp() {
        assertFalse(GainMapDetector.containsGainMapXmp(embed(plainXmp)))
    }

    @Test
    fun containsGainMapXmp_emptyArray() {
        assertFalse(GainMapDetector.containsGainMapXmp(ByteArray(0)))
    }

    @Test
    fun containsGainMapXmp_markerEndingExactlyAtBufferEnd() {
        val bytes = ByteArray(100) + ascii("hdrgm:Version")
        assertTrue(GainMapDetector.containsGainMapXmp(bytes))
        assertFalse(GainMapDetector.containsGainMapXmp(bytes.copyOf(bytes.size - 1)))
    }

    @Test
    fun readPrefix_accumulatesShortReadsAcrossAMarkerSplitAtTheChunkBoundary() {
        val marker = ascii(namespace)
        val payload = ByteArray(1000) + marker + ByteArray(1000)
        // Every read returns at most the bytes up to the middle of the marker, then the rest.
        val splitAt = 1000 + marker.size / 2
        val result = GainMapDetector.readPrefix(ChunkedStream(payload, splitAt), maxBytes = 4096)
        assertArrayEquals(payload, result)
        assertTrue(GainMapDetector.containsGainMapXmp(result))
    }

    @Test
    fun readPrefix_isBoundedToMaxBytes_andMarkerStraddlingTheLimitIsNotSeen() {
        val marker = ascii("hdrgm:Version")
        val limit = GainMapDetector.MAX_PREFIX_BYTES
        val beforeLimit = 5
        val payload = ByteArray(limit - beforeLimit) + marker + ByteArray(10)
        val result = GainMapDetector.readPrefix(ChunkedStream(payload, 8 * 1024))
        assertEquals(limit, result.size)
        assertFalse(GainMapDetector.containsGainMapXmp(result))
    }

    @Test
    fun readPrefix_markerEndingExactlyAtTheLimitIsSeen() {
        val marker = ascii("hdrgm:Version")
        val limit = GainMapDetector.MAX_PREFIX_BYTES
        val payload = ByteArray(limit - marker.size) + marker + ascii("=\"1.0\"")
        val result = GainMapDetector.readPrefix(ByteArrayInputStream(payload))
        assertEquals(limit, result.size)
        assertTrue(GainMapDetector.containsGainMapXmp(result))
    }

    @Test
    fun readPrefix_shortStreamReturnsWholeStream() {
        assertArrayEquals(ByteArray(0), GainMapDetector.readPrefix(ByteArrayInputStream(ByteArray(0))))
        assertArrayEquals(ascii("abc"), GainMapDetector.readPrefix(ByteArrayInputStream(ascii("abc"))))
    }

    /** Returns at most [chunk] bytes per read, so callers must loop. */
    private class ChunkedStream(private val data: ByteArray, private val chunk: Int) : InputStream() {
        private var pos = 0

        override fun read(): Int = if (pos < data.size) data[pos++].toInt() and 0xFF else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            val boundary = ((pos / chunk) + 1) * chunk
            val n = minOf(len, data.size - pos, boundary - pos)
            System.arraycopy(data, pos, b, off, n)
            pos += n
            return n
        }
    }
}
