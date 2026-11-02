package com.akane.akastudio.core.curator.metrics

import com.akane.akastudio.core.color.api.ImagePixels
import com.akane.akastudio.core.color.api.SceneType
import com.akane.akastudio.core.curator.engine.AnalysisImage
import com.akane.akastudio.core.curator.engine.AnalysisModels
import com.akane.akastudio.core.curator.engine.PhotoAnalyzer
import com.akane.akastudio.core.curator.engine.PixelSource
import com.akane.akastudio.core.curator.metrics.ScreenCaptureDetector.SCREEN_ASPECT_THRESHOLD
import com.akane.akastudio.core.curator.metrics.ScreenCaptureDetector.ZERO_VARIANCE_THRESHOLD
import com.akane.akastudio.core.curator.ml.DetectedFace
import com.akane.akastudio.core.ingestion.model.CaptureMetadata
import com.akane.akastudio.core.ingestion.model.ImageFormat
import com.akane.akastudio.core.ingestion.model.PhotoCandidate
import com.akane.akastudio.core.ingestion.model.PhotoSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ScreenCaptureDetectorTest {

    private val noExif = CaptureMetadata.EMPTY
    private val cameraExif = CaptureMetadata.EMPTY.copy(
        focalLength35mm = 24,
        fNumber = 1.8,
        exposureTimeSeconds = 1.0 / 60,
        iso = 400,
    )

    @Test
    fun screenAspectFrameWithFlatUiBandsAndNoExifIsAScreenCapture() {
        val frame = cameraUi()
        val result = ScreenCaptureDetector.evaluate(frame, noExif)

        assertEquals(150f / 480f, result.zeroVarianceFraction, 0f)
        assertEquals(SCREEN_ASPECT_THRESHOLD, result.threshold, 0f)
        assertTrue(result.screenCapture)
        assertTrue(ScreenCaptureDetector.detect(frame, noExif))
    }

    @Test
    fun theSamePixelsWithAnyCameraExifFieldAreNotAScreenCapture() {
        val frame = cameraUi()
        val singles = listOf(
            noExif.copy(focalLength35mm = 26),
            noExif.copy(fNumber = 1.8),
            noExif.copy(exposureTimeSeconds = 0.01),
            noExif.copy(iso = 50),
            cameraExif,
        )
        for (metadata in singles) {
            assertTrue(metadata.toString(), ScreenCaptureDetector.hasCameraExif(metadata))
            assertFalse(metadata.toString(), ScreenCaptureDetector.detect(frame, metadata))
        }
        // Non-camera fields, such as a capture time or a colour space, are not camera EXIF.
        val timed = noExif.copy(captureEpochMillis = 1_760_000_000_000L, hasUltraHdrGainMap = true)
        assertFalse(ScreenCaptureDetector.hasCameraExif(timed))
        assertTrue(ScreenCaptureDetector.detect(frame, timed))
    }

    @Test
    fun aForwardedPhotoWithFlatDarkShadowsAndNoExifIsNotAScreenCapture() {
        // A messenger forward: no EXIF, the lower 60% a dark recompressed shadow at luma 12 with only ±1 LSB of
        // residual noise, the rest textured. Flat to the eye, but no block has exactly one value.
        for ((w, h) in listOf(160 to 120, 120 to 256)) {
            val random = Random(7)
            val frame = pixels(w, h) { _, y ->
                if (y >= h * 2 / 5) 12 + random.nextInt(-1, 2) else random.nextInt(40, 220)
            }
            val result = ScreenCaptureDetector.evaluate(frame, noExif)
            assertTrue("${w}x$h: ${result.zeroVarianceFraction}", result.zeroVarianceFraction < 0.01f)
            assertFalse("${w}x$h", result.screenCapture)
        }
    }

    @Test
    fun twentyPercentFlatBlocksAreBelowTheThresholdOfACameraAspect() {
        // 160x120 is 20 x 15 = 300 blocks; 60 of them flat is 0.20.
        val frame = withFlatBlocks(160, 120, flat = 60)
        val result = ScreenCaptureDetector.evaluate(frame, noExif)
        assertEquals(0.20f, result.zeroVarianceFraction, 0f)
        assertEquals(ZERO_VARIANCE_THRESHOLD, result.threshold, 0f)
        assertFalse(result.screenCapture)
    }

    @Test
    fun thresholdBoundaryIsInclusive() {
        // Camera aspect: 300 blocks, T = 0.28, so 84 flat blocks (exactly 0.28) flag and 83 do not.
        assertTrue(ScreenCaptureDetector.detect(withFlatBlocks(160, 120, flat = 84), noExif))
        assertFalse(ScreenCaptureDetector.detect(withFlatBlocks(160, 120, flat = 83), noExif))
        // Screen aspect: 120x256 is 15 x 32 = 480 blocks, T = 0.20, so 96 flag and 95 do not.
        assertTrue(ScreenCaptureDetector.detect(withFlatBlocks(120, 256, flat = 96), noExif))
        assertFalse(ScreenCaptureDetector.detect(withFlatBlocks(120, 256, flat = 95), noExif))
        // The same 96/480 = 0.20 on a camera aspect stays below 0.28: the aspect only lowers the bar.
        assertFalse(ScreenCaptureDetector.detect(withFlatBlocks(192, 160, flat = 96), noExif))
    }

    @Test
    fun aspectAloneNeverFlags() {
        val random = Random(3)
        val noisyTall = pixels(120, 256) { _, _ -> random.nextInt(256) }
        assertTrue(ScreenCaptureDetector.isScreenAspect(120, 256))
        assertFalse(ScreenCaptureDetector.detect(noisyTall, noExif))
    }

    @Test
    fun screenAspectCoversPhoneScreensInBothOrientationsButNotCameraRatios() {
        assertTrue(ScreenCaptureDetector.isScreenAspect(1080, 2340)) // 19.5:9
        assertTrue(ScreenCaptureDetector.isScreenAspect(2400, 1080)) // 20:9 landscape
        assertTrue(ScreenCaptureDetector.isScreenAspect(1440, 2951)) // the real camera-UI screenshot
        assertTrue(ScreenCaptureDetector.isScreenAspect(1080, 2160)) // 18:9
        assertFalse(ScreenCaptureDetector.isScreenAspect(3000, 4000)) // 4:3
        assertFalse(ScreenCaptureDetector.isScreenAspect(1920, 1080)) // 16:9
        assertFalse(ScreenCaptureDetector.isScreenAspect(1000, 1000))
    }

    @Test
    fun blockFractionIgnoresPartialEdgeBlocksAlphaAndTinyFrames() {
        // 12x12 holds one whole block; the noisy partial strips around it do not count.
        val random = Random(5)
        val oneBlock = pixels(12, 12) { x, y -> if (x < 8 && y < 8) 90 else random.nextInt(256) }
        assertEquals(1f, ScreenCaptureDetector.zeroVarianceBlockFraction(oneBlock.argb, 12, 12), 0f)
        // Alpha differences inside a block do not break uniformity.
        val alpha = IntArray(64) { i -> ((i * 3) shl 24) or 0x405060 }
        assertEquals(1f, ScreenCaptureDetector.zeroVarianceBlockFraction(alpha, 8, 8), 0f)
        // A single differing pixel does.
        alpha[63] = alpha[63] xor 1
        assertEquals(0f, ScreenCaptureDetector.zeroVarianceBlockFraction(alpha, 8, 8), 0f)
        // Smaller than one block: no evidence.
        val tiny = pixels(7, 30) { _, _ -> 0 }
        assertEquals(0f, ScreenCaptureDetector.zeroVarianceBlockFraction(tiny.argb, 7, 30), 0f)
        assertFalse(ScreenCaptureDetector.detect(tiny, noExif))
    }

    @Test
    fun analyzerSetsTheFlagFromTheCandidateMetadata() = runBlocking {
        val frame = cameraUi()
        val source = PixelSource { AnalysisImage(bitmap = null, pixels = frame) }
        val models = object : AnalysisModels {
            override suspend fun faces(img: AnalysisImage) = emptyList<DetectedFace>()
            override suspend fun embed(img: AnalysisImage) = FloatArray(0)
            override suspend fun aesthetic(img: AnalysisImage): Float? = 7f
            override suspend fun scene(img: AnalysisImage) = SceneType.GENERAL
        }
        val analyzer = PhotoAnalyzer(source, models)

        val screenshot = analyzer.analyze(candidate("shot", frame, noExif)).analysis
        val photo = analyzer.analyze(candidate("photo", frame, cameraExif)).analysis

        assertTrue(screenshot.screenCapture)
        assertFalse(photo.screenCapture)
        // Not a rejection: the gate result is the same for both.
        assertEquals(photo.rejectReason, screenshot.rejectReason)
        assertFalse(PhotoAnalyzer.unreadable("x").screenCapture)
    }

    /**
     * A 120x256 (about 9:19.5) camera-UI-like frame: flat black status and control bands (4 block rows at the top,
     * 4 at the bottom) and a flat grey shutter panel of 2 block rows: 150 of 480 blocks (0.3125), around a noisy
     * viewfinder.
     */
    private fun cameraUi(): ImagePixels {
        val random = Random(11)
        return pixels(120, 256) { _, y ->
            when {
                y < 32 -> 0
                y >= 224 -> 0
                y in 184 until 200 -> 128
                else -> random.nextInt(20, 236)
            }
        }
    }

    /** A noisy `w x h` frame whose first [flat] blocks in raster block order are each one flat grey. */
    private fun withFlatBlocks(w: Int, h: Int, flat: Int): ImagePixels {
        val cols = w / 8
        val random = Random(13)
        return pixels(w, h) { x, y ->
            val block = (y / 8) * cols + x / 8
            if (block < flat) 60 + block % 100 else random.nextInt(256)
        }
    }

    private fun pixels(w: Int, h: Int, luma: (x: Int, y: Int) -> Int): ImagePixels {
        val argb = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val v = luma(x, y).coerceIn(0, 255)
            argb[y * w + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return ImagePixels(w, h, argb)
    }

    private fun candidate(id: String, frame: ImagePixels, metadata: CaptureMetadata) = PhotoCandidate(
        id = id,
        source = PhotoSource.ExtractedFile(path = "/fake/$id.jpg", archiveEntryName = "$id.jpg", displayName = "$id.jpg"),
        format = ImageFormat.JPEG,
        byteSize = null,
        widthPx = frame.width,
        heightPx = frame.height,
        metadata = metadata,
    )
}
