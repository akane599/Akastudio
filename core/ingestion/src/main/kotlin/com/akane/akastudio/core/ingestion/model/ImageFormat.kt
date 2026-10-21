package com.akane.akastudio.core.ingestion.model

enum class ImageFormat(val extensions: Set<String>) {
    JPEG(setOf("jpg", "jpeg")),
    PNG(setOf("png")),
    HEIC(setOf("heic")),
    HEIF(setOf("heif")),
    WEBP(setOf("webp")),
    DNG(setOf("dng"));

    companion object {
        private val byMimeType: Map<String, ImageFormat> = mapOf(
            "image/jpeg" to JPEG,
            "image/jpg" to JPEG,
            "image/png" to PNG,
            "image/heic" to HEIC,
            "image/heic-sequence" to HEIC,
            "image/heif" to HEIF,
            "image/heif-sequence" to HEIF,
            "image/webp" to WEBP,
            "image/x-adobe-dng" to DNG,
            "image/dng" to DNG,
        )

        /** Case-insensitive. */
        fun fromExtension(ext: String): ImageFormat? {
            val normalized = ext.lowercase()
            return entries.firstOrNull { normalized in it.extensions }
        }

        /**
         * image/jpeg (and image/jpg), image/png, image/heic (and image/heic-sequence), image/heif (and
         * image/heif-sequence), image/webp, image/x-adobe-dng, image/dng; anything else, or null, -> null.
         */
        fun fromMimeType(mime: String?): ImageFormat? = mime?.let { byMimeType[it.trim().lowercase()] }
    }
}
