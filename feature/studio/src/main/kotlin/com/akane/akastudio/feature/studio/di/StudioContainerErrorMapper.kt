package com.akane.akastudio.feature.studio.di

import com.akane.akastudio.core.curator.engine.NoUsablePhotosException
import com.akane.akastudio.feature.studio.domain.DefaultStudioErrorMapper
import com.akane.akastudio.feature.studio.domain.StudioError

/**
 * The container's error mapper: the curator's "nothing usable" verdict becomes [StudioError.NoUsablePhotos] and a
 * failed full-resolution decode ([PhotoDecodeException]) becomes [StudioError.Decode]; everything else goes through
 * [DefaultStudioErrorMapper].
 */
internal class StudioContainerErrorMapper : DefaultStudioErrorMapper() {
    override fun map(t: Throwable): StudioError = when (t) {
        is NoUsablePhotosException -> StudioError.NoUsablePhotos(t.rejectedCount)
        is PhotoDecodeException -> StudioError.Decode(t.displayName)
        else -> super.map(t)
    }
}
