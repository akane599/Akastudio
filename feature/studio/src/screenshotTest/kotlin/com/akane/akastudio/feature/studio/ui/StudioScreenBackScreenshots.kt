package com.akane.akastudio.feature.studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.akane.akastudio.feature.studio.ui.theme.DarkroomTheme
import com.android.tools.screenshot.PreviewTest

// Rendered on the JVM: ./gradlew :feature:studio:updateDebugScreenshotTest (write refs) / validateDebugScreenshotTest (diff)

/** The discard dialog's card over the dimmed canvas backdrop, drawn inline because a dialog opens its own window. */
@Composable
private fun DiscardDialog(exporting: Boolean) = DarkroomTheme {
    Box(
        Modifier
            .fillMaxSize()
            .background(DarkroomTheme.colors.canvasBackdrop)
            .padding(DarkroomTheme.spacing.xl),
        contentAlignment = Alignment.Center,
    ) {
        DiscardStoryCard(exporting = exporting, onKeepEditing = {}, onDiscard = {})
    }
}

@PreviewTest @Preview(name = "discard-story", widthDp = 360, heightDp = 400, showBackground = true, backgroundColor = 0xFF070809)
@Composable fun DiscardStoryShot() = DiscardDialog(exporting = false)

@PreviewTest @Preview(name = "discard-story-tr", widthDp = 360, heightDp = 400, locale = "tr", showBackground = true, backgroundColor = 0xFF070809)
@Composable fun DiscardStoryTurkishShot() = DiscardDialog(exporting = false)

@PreviewTest @Preview(name = "discard-story-exporting", widthDp = 360, heightDp = 400, showBackground = true, backgroundColor = 0xFF070809)
@Composable fun DiscardStoryExportingShot() = DiscardDialog(exporting = true)
