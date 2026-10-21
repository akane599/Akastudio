package com.akane.akastudio.feature.studio.ui.dropzone

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest

// Rendered on the JVM: ./gradlew :feature:studio:updateDebugScreenshotTest (write refs) / validateDebugScreenshotTest (diff)

@PreviewTest @Preview(name = "empty", widthDp = 360, heightDp = 760, showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable fun DropzoneEmpty() = DropzonePreviewHost(DropzoneSamples.empty)

@PreviewTest @Preview(name = "ingesting", widthDp = 360, heightDp = 760, showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable fun DropzoneIngesting() = DropzonePreviewHost(DropzoneSamples.ingesting)

@PreviewTest @Preview(name = "curating", widthDp = 360, heightDp = 760, showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable fun DropzoneCurating() = DropzonePreviewHost(DropzoneSamples.curating)

@PreviewTest @Preview(name = "decided", widthDp = 360, heightDp = 760, showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable fun DropzoneDecided() = DropzonePreviewHost(DropzoneSamples.decided)

@PreviewTest @Preview(name = "error", widthDp = 360, heightDp = 760, showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable fun DropzoneError() = DropzonePreviewHost(DropzoneSamples.error)

@PreviewTest @Preview(name = "no-usable-photos", widthDp = 360, heightDp = 760, showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable fun DropzoneNoUsablePhotos() = DropzonePreviewHost(DropzoneSamples.noUsablePhotos)

@PreviewTest @Preview(name = "no-usable-photos-tr", locale = "tr", widthDp = 360, heightDp = 760, showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable fun DropzoneNoUsablePhotosTurkish() = DropzonePreviewHost(DropzoneSamples.noUsablePhotos)

@PreviewTest @Preview(name = "decided-tr", locale = "tr", widthDp = 360, heightDp = 760, showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable fun DropzoneDecidedTurkish() = DropzonePreviewHost(DropzoneSamples.decided)

@PreviewTest @Preview(name = "empty-tr", locale = "tr", widthDp = 360, heightDp = 760, showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable fun DropzoneEmptyTurkish() = DropzonePreviewHost(DropzoneSamples.empty)

@PreviewTest @Preview(name = "ingesting-large-font", fontScale = 2f, widthDp = 360, heightDp = 760, showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable fun DropzoneIngestingLargeFont() = DropzonePreviewHost(DropzoneSamples.ingesting)

@PreviewTest @Preview(name = "empty-large-font", fontScale = 2f, widthDp = 360, heightDp = 760, showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable fun DropzoneEmptyLargeFont() = DropzonePreviewHost(DropzoneSamples.empty)
