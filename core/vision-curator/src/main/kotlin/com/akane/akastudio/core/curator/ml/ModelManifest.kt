package com.akane.akastudio.core.curator.ml

/**
 * Pinned on-device model assets under `assets/models/`. Hashes and sizes are the verified downloads
 * recorded in `models/MODELS.md`; ModelAssetsTest re-hashes the committed files against this table.
 */
internal object ModelManifest {

    /** One pinned asset. [assetPath] is relative to the APK assets root. */
    data class Entry(val fileName: String, val sha256: String, val sizeBytes: Long) {
        val assetPath: String get() = "$DIRECTORY/$fileName"
    }

    const val DIRECTORY = "models"

    val FACE_LANDMARKER = Entry(
        fileName = "face_landmarker.task",
        sha256 = "64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff",
        sizeBytes = 3_758_596,
    )

    val IMAGE_EMBEDDER = Entry(
        fileName = "mobilenet_v3_small.tflite",
        sha256 = "bbbb4c51a55a53905af1daec995ca1aae355046f8839bb8c9f5ce9271394bc40",
        sizeBytes = 4_117_670,
    )

    val IMAGE_CLASSIFIER = Entry(
        fileName = "efficientnet_lite0.tflite",
        sha256 = "6c7ab0a6e5dcbf38a8c33b960996a55a3b4300b36a018c4545801de3a3c8bde0",
        sizeBytes = 18_582_189,
    )

    val NIMA_AESTHETIC = Entry(
        fileName = "nima_aesthetic_fp16.tflite",
        sha256 = "a5051a0fcced735682735e3e0fd58ee54c83ed664282a003f52235b3dbcb9320",
        sizeBytes = 6_448_632,
    )

    val ALL: List<Entry> = listOf(FACE_LANDMARKER, IMAGE_EMBEDDER, IMAGE_CLASSIFIER, NIMA_AESTHETIC)
}
