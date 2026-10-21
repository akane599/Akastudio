package com.akane.akastudio.core.ingestion.metadata

import java.io.InputStream

/** Detects the Ultra HDR / Adobe gain-map XMP markers in a bounded file prefix. */
internal object GainMapDetector {
    const val MAX_PREFIX_BYTES: Int = 256 * 1024

    private val NAMESPACE = "http://ns.adobe.com/hdr-gain-map/1.0/".toByteArray(Charsets.US_ASCII)
    private val VERSION_ATTRIBUTE = "hdrgm:Version".toByteArray(Charsets.US_ASCII)

    /** Byte search over [prefix] for the gain-map namespace or the `hdrgm:Version` attribute. */
    fun containsGainMapXmp(prefix: ByteArray): Boolean =
        indexOf(prefix, NAMESPACE) >= 0 || indexOf(prefix, VERSION_ATTRIBUTE) >= 0

    /** Reads at most [maxBytes] from [stream] with a bounded loop; short reads are accumulated. */
    fun readPrefix(stream: InputStream, maxBytes: Int = MAX_PREFIX_BYTES): ByteArray {
        val buffer = ByteArray(maxBytes)
        var filled = 0
        while (filled < maxBytes) {
            val read = stream.read(buffer, filled, maxBytes - filled)
            if (read < 0) break
            filled += read
        }
        return if (filled == maxBytes) buffer else buffer.copyOf(filled)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        val last = haystack.size - needle.size
        var i = 0
        while (i <= last) {
            if (haystack[i] == needle[0] && matchesAt(haystack, needle, i)) return i
            i++
        }
        return -1
    }

    private fun matchesAt(haystack: ByteArray, needle: ByteArray, start: Int): Boolean {
        for (j in 1 until needle.size) {
            if (haystack[start + j] != needle[j]) return false
        }
        return true
    }
}
