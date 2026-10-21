package com.akane.akastudio.core.ingestion.device

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Gainmap
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.akane.akastudio.core.ingestion.api.IngestionRequest
import com.akane.akastudio.core.ingestion.api.SourceOpener
import com.akane.akastudio.core.ingestion.createIngestionSession
import com.akane.akastudio.core.ingestion.metadata.ExifAndGainMapParser
import com.akane.akastudio.core.ingestion.model.ColorSpaceTag
import com.akane.akastudio.core.ingestion.model.ImageFormat
import com.akane.akastudio.core.ingestion.model.PhotoCandidate
import com.akane.akastudio.core.ingestion.model.PhotoSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Case 4: an Ultra HDR JPEG produced by the platform encoder ([Bitmap.compress] of a bitmap carrying a [Gainmap])
 * is detected by the production parser, through both the picker (MediaStore content URI) and extracted-file paths.
 * The plain JPEG from the same encoder is the negative control.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class GainMapDeviceTest {

    private val store = QaMediaStore()
    private val dir = File(targetContext.cacheDir, "qa-gainmap").apply { mkdirs() }

    @After
    fun cleanUp() {
        dir.deleteRecursively()
        store.deleteAll()
    }

    @Test
    fun platformEncodedUltraHdr_isDetected_andPlainJpegIsNot() {
        val ultraHdr = encode(withGainmap = true)
        val plain = encode(withGainmap = false)
        qaLog("Ultra HDR JPEG ${ultraHdr.size} bytes, plain JPEG ${plain.size} bytes")
        assertPlatformDecode(ultraHdr, expectGainmap = true)
        assertPlatformDecode(plain, expectGainmap = false)

        val uris = listOf(
            store.insertImage("qa_ultrahdr.jpg", ultraHdr).toString(),
            store.insertImage("qa_plain.jpg", plain).toString(),
        )
        val candidates = createIngestionSession(targetContext).use { session ->
            session.collect(IngestionRequest.PickedMedia(uris)).completed().candidates
        }.associateBy { it.source.displayName }

        assertEquals(setOf("qa_ultrahdr.jpg", "qa_plain.jpg"), candidates.keys)
        assertUltraHdr(checkNotNull(candidates["qa_ultrahdr.jpg"]), ultraHdr.size)
        val plainCandidate = checkNotNull(candidates["qa_plain.jpg"])
        assertFalse("plain JPEG reports no gain map", plainCandidate.metadata.hasUltraHdrGainMap)
        assertEquals(ImageFormat.JPEG, plainCandidate.format)
    }

    @Test
    fun extractedFileParse_detectsUltraHdr_andPlainJpegIsNot() {
        val parser = ExifAndGainMapParser(
            opener = SourceOpener { FileInputStream((it as PhotoSource.ExtractedFile).path) },
            sizeOf = { File((it as PhotoSource.ExtractedFile).path).length() },
        )

        fun parse(name: String, bytes: ByteArray) = runBlocking {
            val file = File(dir, name).apply { writeBytes(bytes) }
            checkNotNull(parser.parse(PhotoSource.ExtractedFile(file.absolutePath, name, name))) { "rejected $name" }
        }

        val hdr = parse("hdr.jpg", encode(withGainmap = true))
        val sdr = parse("sdr.jpg", encode(withGainmap = false))

        assertTrue("Ultra HDR file reports its gain map", hdr.metadata.hasUltraHdrGainMap)
        assertEquals(WIDTH to HEIGHT, hdr.widthPx to hdr.heightPx)
        assertFalse("plain file reports none", sdr.metadata.hasUltraHdrGainMap)
    }

    private fun assertUltraHdr(candidate: PhotoCandidate, size: Int) {
        assertTrue("Ultra HDR JPEG reports its gain map", candidate.metadata.hasUltraHdrGainMap)
        assertEquals(ImageFormat.JPEG, candidate.format)
        assertEquals("primary image size, not the gain map's", WIDTH to HEIGHT, candidate.widthPx to candidate.heightPx)
        assertEquals(size.toLong(), candidate.byteSize)
        assertEquals(ColorSpaceTag.SRGB, candidate.metadata.colorSpace)
    }

    /** Fixture sanity: the platform decoder agrees on what the platform encoder wrote, including the metadata. */
    private fun assertPlatformDecode(bytes: ByteArray, expectGainmap: Boolean) {
        val decoded = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        try {
            assertEquals("platform decode hasGainmap", expectGainmap, decoded.hasGainmap())
            val gainmap = decoded.gainmap ?: return
            assertEquals(RATIO_MAX, gainmap.ratioMax[0], 0.05f)
            assertEquals(DISPLAY_RATIO_HDR, gainmap.displayRatioForFullHdr, 0.05f)
        } finally {
            decoded.recycle()
        }
    }

    private fun encode(withGainmap: Boolean): ByteArray {
        val base = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(Color.rgb(180, 140, 90)) }
        val contents = Bitmap.createBitmap(WIDTH / 2, HEIGHT / 2, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(Color.rgb(200, 200, 200)) }
        try {
            if (withGainmap) {
                base.gainmap = Gainmap(contents).apply {
                    setRatioMin(1f, 1f, 1f)
                    setRatioMax(RATIO_MAX, RATIO_MAX, RATIO_MAX)
                    setGamma(1f, 1f, 1f)
                    setEpsilonSdr(0f, 0f, 0f)
                    setEpsilonHdr(0f, 0f, 0f)
                    displayRatioForFullHdr = DISPLAY_RATIO_HDR
                    minDisplayRatioForHdrTransition = 1f
                }
            }
            return ByteArrayOutputStream().also { base.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        } finally {
            base.recycle()
            contents.recycle()
        }
    }

    private companion object {
        const val WIDTH = 256
        const val HEIGHT = 192
        const val RATIO_MAX = 4f
        const val DISPLAY_RATIO_HDR = 4f
    }
}
