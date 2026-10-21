package com.akane.akastudio.core.ingestion.model

/** Each subclass implements [message] as a short human-readable string. */
sealed interface IngestionError {
    val message: String

    data class ZipSlip(val entryName: String) : IngestionError {
        override val message: String get() = "Archive entry escapes the extraction folder: $entryName"
    }

    data class ZipBomb(val reason: String) : IngestionError {
        override val message: String get() = "Archive looks like a decompression bomb: $reason"
    }

    data class LimitExceeded(val reason: String) : IngestionError {
        override val message: String get() = "Archive exceeds an ingestion limit: $reason"
    }

    data class InvalidArchive(val reason: String) : IngestionError {
        override val message: String get() = "Archive is not a valid zip: $reason"
    }

    data class InsufficientStorage(val requiredBytes: Long, val availableBytes: Long) : IngestionError {
        override val message: String
            get() = "Not enough free storage: need $requiredBytes bytes, $availableBytes available"
    }

    data class Io(val reason: String) : IngestionError {
        override val message: String get() = "Read error: $reason"
    }
}
