package com.akane.akastudio.feature.studio.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import com.akane.akastudio.feature.studio.mvi.StudioState
import com.akane.akastudio.feature.studio.ui.controls.ControlsFixtures
import com.akane.akastudio.feature.studio.ui.controls.InMemoryCompareHintStore
import com.akane.akastudio.feature.studio.ui.controls.LocalCompareHintStore
import com.akane.akastudio.feature.studio.ui.theme.DarkroomTheme
import com.android.tools.screenshot.PreviewTest

// Rendered on the JVM: ./gradlew :feature:studio:updateDebugScreenshotTest (write refs) / validateDebugScreenshotTest (diff)

/** The whole workspace with a placeholder canvas: the panel [expanded] or peeking, the compare hint [hintSeen] or not. */
@Composable
private fun Workspace(state: StudioState, expanded: Boolean, hintSeen: Boolean) = DarkroomTheme {
    CompositionLocalProvider(LocalCompareHintStore provides InMemoryCompareHintStore(seen = hintSeen)) {
        StudioScreenContent(
            state = state,
            onIntent = {},
            renderer = null,
            hitTest = { _, _ -> null },
            panelInitiallyExpanded = expanded,
        )
    }
}

/** First Ready: compare is off, the panel peeks and the one-time hint points at the compare tool. */
private fun firstReady(): StudioState = ControlsFixtures.state().copy(compare = null)

@PreviewTest @Preview(name = "workspace-collapsed-hint", widthDp = 360, heightDp = 760, showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable fun WorkspaceCollapsedHintShot() = Workspace(firstReady(), expanded = false, hintSeen = false)

@PreviewTest @Preview(name = "workspace-collapsed-hint-tr", widthDp = 360, heightDp = 760, locale = "tr", showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable fun WorkspaceCollapsedHintTurkishShot() = Workspace(firstReady(), expanded = false, hintSeen = false)

/** Expanded with compare on: the panel pushes the canvas up and the A/B split handle stays fully in view. */
@PreviewTest @Preview(name = "workspace-expanded-compare", widthDp = 360, heightDp = 760, showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable fun WorkspaceExpandedCompareShot() = Workspace(ControlsFixtures.state(), expanded = true, hintSeen = true)

@PreviewTest @Preview(name = "workspace-collapsed-compare", widthDp = 360, heightDp = 760, showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable fun WorkspaceCollapsedCompareShot() = Workspace(ControlsFixtures.state(), expanded = false, hintSeen = true)
