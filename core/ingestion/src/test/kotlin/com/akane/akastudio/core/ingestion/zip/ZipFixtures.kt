package com.akane.akastudio.core.ingestion.zip

import com.akane.akastudio.core.ingestion.api.IngestionException
import com.akane.akastudio.core.ingestion.api.ZipExtractionEvent
import com.akane.akastudio.core.ingestion.model.IngestionError
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import org.junit.Assert.assertEquals
import org.junit.Assert.fail

const val KIB = 1024
const val MIB = 1024 * 1024

/** Free space large enough that the storage guard never trips unless a test wants it to. */
val plentyOfSpace: (File) -> Long = { Long.MAX_VALUE / 2 }

fun randomBytes(size: Int, seed: Long = size.toLong()): ByteArray = ByteArray(size).also { Random(seed).nextBytes(it) }

/**
 * Random bytes over a four-letter alphabet: DEFLATE shrinks them only about 3-4:1, far below any ratio limit, so
 * an archive of them can trip the inflated-byte caps without the ratio guard or the compressed-size cap firing.
 */
fun compressibleBytes(size: Int, seed: Long = size.toLong()): ByteArray {
    val alphabet = "ACGT".toByteArray()
    val random = Random(seed)
    return ByteArray(size) { alphabet[random.nextInt(alphabet.size)] }
}

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun sha256(file: File): String = sha256(file.readBytes())

/**
 * Builds a well-formed archive with [ZipOutputStream]. A name ending in `/` becomes a directory entry. DEFLATED
 * entries use a data descriptor, so their local header declares no size at all.
 */
fun zipOf(vararg entries: Pair<String, ByteArray>, method: Int = ZipEntry.DEFLATED): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
        for ((name, content) in entries) {
            val entry = ZipEntry(name)
            if (method == ZipEntry.STORED) {
                val crc = CRC32().apply { update(content) }
                entry.method = ZipEntry.STORED
                entry.size = content.size.toLong()
                entry.compressedSize = content.size.toLong()
                entry.crc = crc.value
            }
            zip.putNextEntry(entry)
            zip.write(content)
            zip.closeEntry()
        }
    }
    return bytes.toByteArray()
}

/**
 * Hand-written local file headers for names [ZipOutputStream] would encode differently and for headers that lie.
 * `ZipInputStream` reads local headers only, so no central directory is needed.
 */
object RawZip {
    private const val LOCAL_HEADER_SIGNATURE = 0x04034b50

    fun entry(
        name: ByteArray,
        data: ByteArray,
        method: Int = ZipEntry.STORED,
        declaredSize: Long,
        declaredCompressedSize: Long = data.size.toLong(),
        crc: Long,
        flags: Int = 0,
    ): ByteArray {
        val header = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(LOCAL_HEADER_SIGNATURE)
            .putShort(20) // version needed
            .putShort(flags.toShort())
            .putShort(method.toShort())
            .putInt(0) // dos time + date
            .putInt(crc.toInt())
            .putInt(declaredCompressedSize.toInt())
            .putInt(declaredSize.toInt())
            .putShort(name.size.toShort())
            .putShort(0) // extra length
        return header.array() + name + data
    }

    /** An honest STORED entry. Name bytes are written as given, with no UTF-8 flag. */
    fun stored(name: String, content: ByteArray): ByteArray = stored(name.toByteArray(Charsets.UTF_8), content)

    fun stored(name: ByteArray, content: ByteArray): ByteArray =
        entry(name, content, declaredSize = content.size.toLong(), crc = crc(content))

    fun deflate(content: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(content)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(64 * KIB)
        while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk))
        deflater.end()
        return out.toByteArray()
    }

    fun crc(content: ByteArray): Long = CRC32().apply { update(content) }.value
}

fun archive(vararg parts: ByteArray): ByteArray = parts.fold(ByteArray(0)) { acc, part -> acc + part }

/** Collects [flow] and returns the [IngestionError] it fails with; fails the test if it completes. */
suspend fun failureOf(flow: Flow<ZipExtractionEvent>): IngestionError {
    try {
        val events = flow.toList()
        fail("expected IngestionException, but extraction completed with $events")
    } catch (expected: IngestionException) {
        return expected.error
    }
    error("unreachable")
}

/** Every file under [dir], recursively, as paths relative to it. */
fun filesUnder(dir: File): List<String> =
    dir.walkTopDown().filter { it != dir }.map { it.relativeTo(dir).path }.sorted().toList()

/** The flattened output name the extractor gives [entryName]. */
fun outputNameOf(entryName: String, archiveIdentity: String = "archive"): String =
    ArchiveEntryRules.outputFileName(archiveIdentity, entryName, entryName.substringAfterLast('/'))

/**
 * Checks the failure contract: a failure deletes only the file being written, so every file left in [dest] must
 * be a complete, byte-identical copy of one of [entries], and nothing else may be there. Returns the names of
 * the entries that survived, in archive order.
 */
fun survivingEntries(dest: File, vararg entries: Pair<String, ByteArray>): List<String> {
    val byOutputName = entries.associateBy { outputNameOf(it.first) }
    val remaining = filesUnder(dest)
    for (file in remaining) {
        val entry = byOutputName[file] ?: throw AssertionError("unexpected file in dest: $file (all: $remaining)")
        assertEquals("${entry.first} survived complete", sha256(entry.second), sha256(File(dest, file)))
    }
    return entries.map { it.first }.filter { outputNameOf(it) in remaining }
}

/**
 * [count] empty non-final STORED deflate blocks (header byte, LEN=0, NLEN=0xFFFF): a valid deflate stream that
 * inflates to nothing, so `InflaterInputStream.read` keeps pulling input without ever returning.
 */
fun emptyDeflateBlocks(count: Int): ByteArray {
    val block = byteArrayOf(0x00, 0x00, 0x00, 0xFF.toByte(), 0xFF.toByte())
    return ByteArray(count * block.size) { block[it % block.size] }
}

/** One DEFLATED entry whose data is [emptyDeflateBlocks]: zero inflated bytes, however much is read. */
fun endlessEmptyDeflateArchive(compressedBytes: Int): ByteArray {
    val blocks = emptyDeflateBlocks(compressedBytes / 5)
    return RawZip.entry("void.jpg".toByteArray(), blocks, ZipEntry.DEFLATED, declaredSize = 0, crc = 0)
}

/** Passes reads through and counts the bytes handed out. */
class CountingSource(source: InputStream) : FilterInputStream(source) {
    var delivered = 0L
        private set

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) delivered += it }
}
