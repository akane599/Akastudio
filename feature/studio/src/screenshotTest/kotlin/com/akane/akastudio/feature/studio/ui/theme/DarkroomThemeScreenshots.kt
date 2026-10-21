package com.akane.akastudio.feature.studio.ui.theme

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest

// Rendered on the JVM: ./gradlew :feature:studio:updateDebugScreenshotTest (write refs) / validateDebugScreenshotTest (diff)

@PreviewTest @Preview(name = "swatches", widthDp = 360, showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable fun DarkroomSwatches() = DarkroomTheme { DarkroomSwatchSheet(Modifier.fillMaxWidth()) }

@PreviewTest @Preview(name = "type-scale", widthDp = 360, showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable fun DarkroomTypeScaleShot() = DarkroomTheme { DarkroomTypeScale(Modifier.fillMaxWidth()) }

@PreviewTest @Preview(name = "type-scale-large-font", widthDp = 360, fontScale = 1.5f, showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable fun DarkroomTypeScaleLargeFont() = DarkroomTheme { DarkroomTypeScale(Modifier.fillMaxWidth()) }
