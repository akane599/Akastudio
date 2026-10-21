package com.akane.akastudio.feature.studio.domain

import com.akane.akastudio.core.ingestion.api.IngestionException
import com.akane.akastudio.core.ingestion.model.IngestionError

/** A user-facing studio failure. The UI formats each variant into one localized message. */
sealed interface StudioError {
    /** Ingestion of the imported sources failed as a whole. */
    data class Ingestion(val error: IngestionError) : StudioError

    /** Nothing usable survived ingestion or the curator's technical gate; [rejectedCount] were discarded. */
    data class NoUsablePhotos(val rejectedCount: Int) : StudioError

    /** The photo named [name] could not be decoded at full resolution. */
    data class Decode(val name: String) : StudioError

    /** The export failed; [reason] is a short human-readable cause. */
    data class Export(val reason: String) : StudioError

    /** Any other failure; [message] is the best available description. */
    data class Unknown(val message: String) : StudioError
}

/** Maps every studio failure to a [StudioError]. Never receives a `CancellationException`. */
fun interface StudioErrorMapper {
    fun map(t: Throwable): StudioError
}

/**
 * Handles [IngestionException] and falls back to [StudioError.Unknown]. The app container subclasses it to map
 * module-specific exceptions (curator, decoder, exporter) and delegates to `super.map` for the rest.
 */
open class DefaultStudioErrorMapper : StudioErrorMapper {
    override fun map(t: Throwable): StudioError = when (t) {
        is IngestionException -> StudioError.Ingestion(t.error)
        else -> StudioError.Unknown(describe(t))
    }

    /** The throwable's message, or its class name when it has none. */
    protected fun describe(t: Throwable): String =
        t.message?.takeIf { it.isNotBlank() } ?: t::class.java.simpleName.ifEmpty { t::class.java.name }
}
