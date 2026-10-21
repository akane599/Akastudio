package com.akane.akastudio.core.export.pipeline

import com.akane.akastudio.core.export.api.ExportFormat
import com.akane.akastudio.core.export.api.ExportProgress
import com.akane.akastudio.core.export.api.ExportResult
import com.akane.akastudio.core.export.api.ExportStage
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.ensureActive
import java.io.FilterOutputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min

/** File naming and MIME types of exported stories. */
internal object ExportNaming {

    private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT)

    /** `AkaStudio_yyyyMMdd_HHmmss_<FORMAT>.<ext>` for the instant [epochMillis] in [zone]. */
    fun fileName(format: ExportFormat, epochMillis: Long, zone: ZoneId): String =
        "AkaStudio_${TIMESTAMP.withZone(zone).format(Instant.ofEpochMilli(epochMillis))}_${format.name}.${extension(format)}"

    fun extension(format: ExportFormat): String = when (format) {
        ExportFormat.PNG_16, ExportFormat.PNG_8 -> "png"
        ExportFormat.WEBP_LOSSLESS -> "webp"
        ExportFormat.SOCIAL_STORY_JPEG, ExportFormat.ULTRA_HDR_JPEG -> "jpg"
    }

    fun mimeType(format: ExportFormat): String = when (format) {
        ExportFormat.PNG_16, ExportFormat.PNG_8 -> "image/png"
        ExportFormat.WEBP_LOSSLESS -> "image/webp"
        ExportFormat.SOCIAL_STORY_JPEG, ExportFormat.ULTRA_HDR_JPEG -> "image/jpeg"
    }
}

/** Counts the bytes passed through to [out]. [close] only flushes: the wrapped stream belongs to the sink. */
internal class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {

    /** Bytes written so far. */
    var count: Long = 0L
        private set

    override fun write(b: Int) {
        out.write(b)
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        count += len
    }

    override fun close() = flush()
}

/**
 * Emits [ExportProgress.Stage] values from any thread into the export's unbounded producer channel and checks
 * cancellation on every call, so non-suspending encoder callbacks can report progress and stop promptly.
 */
internal class ProgressReporter(private val scope: ProducerScope<ExportProgress>) {

    /** Throws `CancellationException` once the export has been cancelled. */
    fun ensureActive() = scope.ensureActive()

    /** Checks cancellation, then emits [stage] at [fraction] (0..1). */
    fun stage(stage: ExportStage, fraction: Float) {
        ensureActive()
        scope.trySend(ExportProgress.Stage(stage, fraction)).getOrThrow()
    }

    /**
     * Reports a file the sink has already published: WRITE at 1, then [ExportProgress.Done] with [result]. Neither
     * emission checks cancellation (the file is visible whatever happens next); callers run this under
     * `NonCancellable` with no cancellation point between the sink's return and this call.
     */
    suspend fun published(result: ExportResult) {
        scope.send(ExportProgress.Stage(ExportStage.WRITE, 1f))
        scope.send(ExportProgress.Done(result))
    }
}

/**
 * Forwards to [out] in slices of at most [sliceBytes], checking cancellation and reporting WRITE as the fraction of
 * [total] bytes written after each slice. [close] only flushes: the wrapped stream belongs to the sink.
 */
internal class WriteProgressStream(
    out: OutputStream,
    private val total: Long,
    private val progress: ProgressReporter,
    private val sliceBytes: Int,
) : FilterOutputStream(out) {

    private var written = 0L

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        var offset = off
        val end = off + len
        while (offset < end) {
            progress.ensureActive()
            val length = min(sliceBytes, end - offset)
            out.write(b, offset, length)
            offset += length
            written += length
            progress.stage(ExportStage.WRITE, (written.toDouble() / total).toFloat().coerceAtMost(1f))
        }
    }

    override fun close() = flush()
}

/**
 * Row-strip progress for a [rows]-row image: every pulled row checks cancellation; a [stage] fraction is emitted each
 * time the furthest row reached crosses one of [STEPS] equal steps, so fractions only ever increase.
 */
internal class RowProgress(private val rows: Int, private val stage: ExportStage, private val progress: ProgressReporter) {

    private var reportedStep = 0

    /** Records that row [y] is about to be produced. */
    fun pull(y: Int) {
        progress.ensureActive()
        val step = ((y + 1).toLong() * STEPS / rows).toInt().coerceAtMost(STEPS)
        if (step > reportedStep) {
            reportedStep = step
            progress.stage(stage, step.toFloat() / STEPS)
        }
    }

    /** True once the last row has been pulled. */
    val complete: Boolean get() = reportedStep == STEPS

    private companion object {
        const val STEPS = 32
    }
}
