package com.akane.akastudio.feature.studio.ui.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.akane.akastudio.feature.studio.mvi.ControlTab
import com.akane.akastudio.feature.studio.mvi.StudioState
import com.akane.akastudio.feature.studio.ui.theme.DarkroomShapeTokens
import com.akane.akastudio.feature.studio.ui.theme.DarkroomTheme
import com.android.tools.screenshot.PreviewTest

// Rendered on the JVM: ./gradlew :feature:studio:updateDebugScreenshotTest (write refs) / validateDebugScreenshotTest (diff)

private const val API_33 = 33
private const val API_34 = 34

@Composable
private fun TopBarWithDrawer(state: StudioState) = DarkroomTheme {
    Column(
        Modifier
            .fillMaxSize()
            .background(DarkroomTheme.colors.canvasBackdrop),
    ) {
        StudioTopBar(state = state, onIntent = {}, onOpenExport = {})
        ModeDrawer(state = state, onIntent = {})
    }
}

@Composable
private fun Panel(state: StudioState, sdkInt: Int = API_34) = DarkroomTheme {
    ControlPanel(state = state, onIntent = {}, sdkInt = sdkInt, modifier = Modifier.fillMaxSize())
}

/** The modal export sheet's surface, handle and body, drawn inline because a modal sheet opens its own window. */
@Composable
private fun SheetSurface(state: StudioState, sdkInt: Int) = DarkroomTheme {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, DarkroomShapeTokens.sheetTop)
            .padding(bottom = DarkroomTheme.spacing.l),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ExportSheetDragHandle()
        ExportSheetBody(state = state, onIntent = {}, sdkInt = sdkInt)
    }
}

@PreviewTest @Preview(name = "mode-drawer-rejected", widthDp = 360, heightDp = 720, showBackground = true, backgroundColor = 0xFF070809)
@Composable fun ModeDrawerRejectedShot() = TopBarWithDrawer(ControlsFixtures.state(drawerOpen = true))

@PreviewTest @Preview(name = "tab-curation", widthDp = 360, heightDp = 460, showBackground = true, backgroundColor = 0xFF141518)
@Composable fun CurationTabShot() = Panel(ControlsFixtures.state(ControlTab.CURATION))

@PreviewTest @Preview(name = "tab-layout", widthDp = 360, heightDp = 640, showBackground = true, backgroundColor = 0xFF141518)
@Composable fun LayoutTabShot() = Panel(ControlsFixtures.state(ControlTab.LAYOUT))

@PreviewTest @Preview(name = "tab-color", widthDp = 360, heightDp = 1040, showBackground = true, backgroundColor = 0xFF141518)
@Composable fun ColorTabShot() = Panel(ControlsFixtures.state(ControlTab.COLOR))

@PreviewTest @Preview(name = "tab-export", widthDp = 360, heightDp = 760, showBackground = true, backgroundColor = 0xFF141518)
@Composable fun ExportTabShot() = Panel(ControlsFixtures.state(ControlTab.EXPORT))

@PreviewTest @Preview(name = "export-sheet-exporting-api33", widthDp = 360, heightDp = 720, showBackground = true, backgroundColor = 0xFF070809)
@Composable fun ExportSheetExportingShot() = SheetSurface(ControlsFixtures.exporting(), API_33)

@PreviewTest @Preview(name = "mode-drawer-tr", widthDp = 360, heightDp = 720, locale = "tr", showBackground = true, backgroundColor = 0xFF070809)
@Composable fun ModeDrawerTurkishShot() = TopBarWithDrawer(ControlsFixtures.state(drawerOpen = true))

@PreviewTest @Preview(name = "tab-color-tr", widthDp = 360, heightDp = 1040, locale = "tr", showBackground = true, backgroundColor = 0xFF141518)
@Composable fun ColorTabTurkishShot() = Panel(ControlsFixtures.state(ControlTab.COLOR))

@PreviewTest @Preview(name = "export-sheet-exporting-tr", widthDp = 360, heightDp = 720, locale = "tr", showBackground = true, backgroundColor = 0xFF070809)
@Composable fun ExportSheetExportingTurkishShot() = SheetSurface(ControlsFixtures.exporting(), API_34)

@PreviewTest @Preview(name = "tab-curation-large-font", widthDp = 360, heightDp = 640, fontScale = 1.5f, showBackground = true, backgroundColor = 0xFF141518)
@Composable fun CurationTabLargeFontShot() = Panel(ControlsFixtures.state(ControlTab.CURATION))

@PreviewTest @Preview(name = "tab-color-large-font", widthDp = 360, heightDp = 1400, fontScale = 1.5f, showBackground = true, backgroundColor = 0xFF141518)
@Composable fun ColorTabLargeFontShot() = Panel(ControlsFixtures.state(ControlTab.COLOR))

@PreviewTest @Preview(name = "mode-drawer-large-font", widthDp = 360, heightDp = 900, fontScale = 1.5f, showBackground = true, backgroundColor = 0xFF070809)
@Composable fun ModeDrawerLargeFontShot() = TopBarWithDrawer(ControlsFixtures.state(drawerOpen = true))
