package com.akane.akastudio.feature.studio.di

import com.akane.akastudio.core.curator.engine.NoUsablePhotosException
import com.akane.akastudio.core.ingestion.api.IngestionException
import com.akane.akastudio.core.ingestion.model.IngestionError
import com.akane.akastudio.feature.studio.domain.StudioError
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class StudioContainerErrorMapperTest {

    private val mapper = StudioContainerErrorMapper()

    @Test
    fun curatorRejectingEveryPhotoMapsToNoUsablePhotos() {
        assertEquals(StudioError.NoUsablePhotos(7), mapper.map(NoUsablePhotosException(7)))
    }

    @Test
    fun fullResolutionDecodeFailureMapsToDecodeWithThePhotoName() {
        val cause = IngestionException(IngestionError.Io("truncated"))
        assertEquals(StudioError.Decode("IMG_0042.HEIC"), mapper.map(PhotoDecodeException("IMG_0042.HEIC", cause)))
    }

    @Test
    fun otherFailuresFallBackToTheDefaultMapping() {
        val io = IngestionError.Io("disk")
        assertEquals(StudioError.Ingestion(io), mapper.map(IngestionException(io)))
        assertEquals(StudioError.Unknown("boom"), mapper.map(IOException("boom")))
    }
}
