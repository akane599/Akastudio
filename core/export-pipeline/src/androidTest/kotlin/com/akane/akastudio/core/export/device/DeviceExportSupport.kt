package com.akane.akastudio.core.export.device

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.Log
import com.akane.akastudio.core.color.api.HalfFloatImage
import com.akane.akastudio.core.color.api.ImagePixels
import com.akane.akastudio.core.color.api.PreviewOptions
import com.akane.akastudio.core.color.api.RenderScene
import com.akane.akastudio.core.color.api.SceneBackground
import com.akane.akastudio.core.color.api.StoryRenderer
import com.akane.akastudio.core.export.api.ExportFormat
import com.akane.akastudio.core.export.api.ExportMetadata
import com.akane.akastudio.core.export.api.ExportPlan
import com.akane.akastudio.core.export.api.ExportProgress
import com.akane.akastudio.core.export.api.ExportRequest
import com.akane.akastudio.core.export.api.ExportResolution
import com.akane.akastudio.core.export.api.ExportResult
import com.akane.akastudio.core.export.api.StoryExporter
import com.akane.akastudio.core.export.pipeline.EncodedSink
import com.akane.akastudio.core.export.process.Half
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

/** Logcat tag of the measurements these device tests record (kept in the connected-test logcat output). */
internal const val QA_TAG = "AkaQA"

/** Records one measurement as `key=value` under [QA_TAG]. */
internal fun record(key: String, value: Any?) {
    Log.i(QA_TAG, "$key=$value")
}

/** Fills the linear-light RGB of pixel ([x], [y]) of a [w] x [h] frame into `out[0..2]`. */
internal fun interface Paint {
    fun paint(x: Int, y: Int, w: Int, h: Int, out: FloatArray)
}

/** Opaque gradient; the top eighth reaches [peak] (linear) so a peak above 1 gives the frame HDR headroom. */
internal fun gradient(peak: Float) = Paint { x, y, w, h, out ->
    val base = x.toFloat() / w
    val highlight = y < h / 8
    out[0] = if (highlight) peak * base else base
    out[1] = if (highlight) peak else y.toFloat() / h
    out[2] = 0.25f + 0.5f * ((x + y) % 64) / 64f
}

/** Neutral vertical step edge: [low] left of column [edgeX], [high] from it on (linear light). */
internal fun step(low: Float, high: Float, edgeX: Int) = Paint { x, _, _, _, out ->
    val v = if (x < edgeX) low else high
    out[0] = v
    out[1] = v
    out[2] = v
}

/**
 * Offscreen-only [StoryRenderer] returning a synthetic opaque frame at the scene canvas size. The export pipeline is
 * the system under test, so the GPU renderer (covered by the color-engine device suite) is replaced by [paint].
 */
internal class SyntheticRenderer(private val paint: Paint) : StoryRenderer {

    /** Canvas sizes of every offscreen render, in call order. */
    val canvases: MutableList<Pair<Int, Int>> = CopyOnWriteArrayList()

    /** The most recent frame handed to the exporter (kept only when [keepLast]). */
    @Volatile var last: HalfFloatImage? = null

    var keepLast: Boolean = false

    override suspend fun renderOffscreen(scene: RenderScene): HalfFloatImage {
        val image = render(scene.canvasWidth, scene.canvasHeight)
        canvases += scene.canvasWidth to scene.canvasHeight
        if (keepLast) last = image
        return image
    }

    fun render(w: Int, h: Int): HalfFloatImage {
        val data = ShortArray(w * h * 4)
        val px = FloatArray(3)
        val one = Half.fromFloat(1f)
        var o = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                paint.paint(x, y, w, h, px)
                data[o] = Half.fromFloat(px[0])
                data[o + 1] = Half.fromFloat(px[1])
                data[o + 2] = Half.fromFloat(px[2])
                data[o + 3] = one
                o += 4
            }
        }
        return HalfFloatImage(w, h, data)
    }

    override suspend fun registerImage(key: String, bitmap: Bitmap) = unused()
    override suspend fun unregisterImage(key: String) = unused()
    override suspend fun renderToSurface(
        scene: RenderScene, surface: android.view.Surface, surfaceWidth: Int, surfaceHeight: Int, options: PreviewOptions,
    ) = unused()
    override suspend fun renderPreviewPixels(scene: RenderScene, width: Int, height: Int): ImagePixels = unused()
    override suspend fun maxTextureSize(): Int = unused()
    override fun close() = Unit

    private fun unused(): Nothing = throw UnsupportedOperationException("the exporter only renders offscreen")
}

/** One file an [EncodedSink] received. */
internal class SinkFile(val displayName: String, val mimeType: String, val dateTakenMillis: Long?, val bytes: ByteArray)

/** In-memory [EncodedSink] keeping every published file, for decoding the exact bytes the exporter produced. */
internal class MemorySink : EncodedSink {
    val files: MutableList<SinkFile> = CopyOnWriteArrayList()

    override fun write(displayName: String, mimeType: String, dateTakenMillis: Long?, block: (OutputStream) -> Unit): String {
        val out = ByteArrayOutputStream()
        out.use(block)
        files += SinkFile(displayName, mimeType, dateTakenMillis, out.toByteArray())
        return "memory://$displayName"
    }
}

/** Capture time written into every test export's EXIF and MediaStore row (2026-10-21T10:00:00Z). */
internal const val CAPTURE_MILLIS = 1_792_576_800_000L

/** A request whose scene canvas is [ExportPlan.renderSize] for [format] at [resolution]. */
internal fun exportRequest(format: ExportFormat, resolution: ExportResolution, hdrBoost: Boolean = false): ExportRequest {
    val (w, h) = ExportPlan.renderSize(format, resolution)
    return ExportRequest(
        scene = RenderScene(w, h, SceneBackground.Solid(0xFF000000.toInt()), emptyList()),
        format = format,
        resolution = resolution,
        metadata = ExportMetadata(captureEpochMillis = CAPTURE_MILLIS, description = "AkaStudio device QA"),
        hdrBoost = hdrBoost,
    )
}

/** Runs [request] to completion and returns the single [ExportProgress.Done] result. */
internal fun StoryExporter.exportBlocking(request: ExportRequest): ExportResult = runBlocking {
    export(request).toList().filterIsInstance<ExportProgress.Done>().single().result
}

/** Software-allocated (pixel-readable) [ImageDecoder] decode of [bytes]. */
internal fun decodeWithImageDecoder(bytes: ByteArray): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, _, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }

/** All ARGB pixels of an ARGB_8888 [bitmap], row-major. */
internal fun Bitmap.argbPixels(): IntArray = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

/**
 * Returns a copy of the ICC [profile] whose tag-table entries [first] and [second] point at each other's data, e.g.
 * `rXYZ`/`gXYZ` swaps the red and green primaries. Our profile carries no profile ID, so the copy stays valid ICC.
 */
internal fun swapIccTags(profile: ByteArray, first: String, second: String): ByteArray {
    val copy = profile.copyOf()
    val buffer = ByteBuffer.wrap(copy)
    val count = buffer.getInt(128)
    fun entry(signature: String): Int = (0 until count).map { 132 + it * 12 }.single {
        String(copy, it, 4, Charsets.US_ASCII) == signature
    }
    val a = entry(first)
    val b = entry(second)
    for (i in 4 until 12) {
        val t = copy[a + i]
        copy[a + i] = copy[b + i]
        copy[b + i] = t
    }
    return copy
}
