package com.akane.akastudio.feature.studio.ui

import com.akane.akastudio.feature.studio.mvi.StudioPhase
import org.junit.Assert.assertEquals
import org.junit.Test

class StudioBackActionTest {

    private val workspacePhases = listOf(StudioPhase.Preparing, StudioPhase.Ready, StudioPhase.Exporting)

    @Test
    fun emptyStudioLetsTheActivityFinish() {
        for (sheet in listOf(false, true)) for (drawer in listOf(false, true)) {
            assertEquals(StudioBackAction.NOT_HANDLED, studioBackAction(StudioPhase.Empty, sheet, drawer))
        }
    }

    @Test
    fun ingestingAndCuratingCancelTheWork() {
        for (phase in listOf(StudioPhase.Ingesting, StudioPhase.Curating)) {
            assertEquals(StudioBackAction.CANCEL_WORK, studioBackAction(phase, exportSheetOpen = false, modeDrawerOpen = false))
            // The drawer is not on screen outside the workspace, so a stale flag never swallows BACK.
            assertEquals(StudioBackAction.CANCEL_WORK, studioBackAction(phase, exportSheetOpen = true, modeDrawerOpen = true))
        }
    }

    @Test
    fun workspaceClosesTheExportSheetFirst() {
        for (phase in workspacePhases) {
            assertEquals(
                StudioBackAction.CLOSE_EXPORT_SHEET,
                studioBackAction(phase, exportSheetOpen = true, modeDrawerOpen = true),
            )
            assertEquals(
                StudioBackAction.CLOSE_EXPORT_SHEET,
                studioBackAction(phase, exportSheetOpen = true, modeDrawerOpen = false),
            )
        }
    }

    @Test
    fun workspaceClosesTheModeDrawerBeforeAskingToDiscard() {
        for (phase in workspacePhases) {
            assertEquals(
                StudioBackAction.CLOSE_MODE_DRAWER,
                studioBackAction(phase, exportSheetOpen = false, modeDrawerOpen = true),
            )
        }
    }

    @Test
    fun workspaceWithoutOverlaysAsksBeforeDiscarding() {
        for (phase in workspacePhases) {
            assertEquals(
                StudioBackAction.CONFIRM_DISCARD,
                studioBackAction(phase, exportSheetOpen = false, modeDrawerOpen = false),
            )
        }
    }
}
