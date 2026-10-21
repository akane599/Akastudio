package com.akane.akastudio.studio

import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akane.akastudio.core.color.api.MasterLook
import com.akane.akastudio.core.export.api.ExportFormat
import com.akane.akastudio.feature.studio.R
import com.akane.akastudio.feature.studio.mvi.ScopeMode
import com.akane.akastudio.feature.studio.mvi.StudioPhase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Stage 5 on a real window: the scope (SQ-48), looks with A/B compare, and a Social JPEG export into MediaStore. */
@RunWith(AndroidJUnit4::class)
class StudioToolsDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val studio by lazy { StudioDeviceHarness(compose) }

    @After
    fun tearDown() = studio.close()

    /** SQ-48: the first Scope tap crashed while the panel was smaller than its labels. Cycle it, also mid-layout. */
    @Test
    fun scopeCyclesLumaParadeHistogramThreeTimesWithoutCrashing() {
        studio.curateStory()
        val cycle = listOf(ScopeMode.LUMA_WAVEFORM, ScopeMode.RGB_PARADE, ScopeMode.HISTOGRAM, ScopeMode.OFF)

        // Taps landing before the panel has settled: the clock is held so each new panel is still in its first frames.
        compose.mainClock.autoAdvance = false
        try {
            cycle.forEach { next ->
                scopeButton().performClick()
                compose.mainClock.advanceTimeByFrame()
                assertEquals(next, studio.state.scope)
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()

        repeat(3) { round ->
            cycle.forEach { next ->
                scopeButton().performClick()
                compose.waitForIdle()
                assertEquals("round $round", next, studio.state.scope)
                compose.onNode(hasContentDescription(studio.string(R.string.controls_scope, scopeName(next))))
                    .assertIsDisplayed()
                val panels = listOf(ScopeMode.LUMA_WAVEFORM, ScopeMode.RGB_PARADE, ScopeMode.HISTOGRAM)
                panels.forEach { mode ->
                    compose.onAllNodes(hasContentDescription(panelCaption(mode)))
                        .assertCountEquals(if (mode == next) 1 else 0)
                }
                if (next != ScopeMode.OFF) compose.onNode(hasContentDescription(panelCaption(next))).assertIsDisplayed()
            }
        }
        assertEquals(StudioPhase.Ready, studio.state.phase)
    }

    @Test
    fun applyingALookAndTogglingCompareChangesTheCompareState() {
        studio.curateStory()
        val before = studio.state
        val look = MasterLook.entries.first { it != before.grade.look }

        compose.onNode(hasText(studio.string(R.string.controls_tab_color)) and hasRole(Role.Tab)).performClick()
        compose.onNode(hasScrollToKeyAction()).performScrollToKey(look.name)
        lookTile(look).performClick()
        compose.waitForIdle()
        lookTile(look).assertIsSelected()
        compose.onNode(hasScrollToKeyAction()).performScrollToKey(before.grade.look.name)
        lookTile(before.grade.look).assertIsNotSelected()
        val graded = studio.state
        assertEquals(look, graded.grade.look)
        assertTrue("scene not rebuilt for the look", graded.sceneRevision > before.sceneRevision)

        val toggle = compose.onNodeWithContentDescription(studio.string(R.string.controls_compare))
        val handle = studio.string(R.string.canvas_compare_handle)
        toggle.assertIsOff()
        compose.onAllNodes(hasContentDescription(handle)).assertCountEquals(0)

        toggle.performClick()
        compose.waitForIdle()
        toggle.assertIsOn()
        compose.onNodeWithContentDescription(handle).assertExists()
        compose.onAllNodes(hasText(studio.string(R.string.canvas_compare_before))).assertCountEquals(1)
        compose.onAllNodes(hasText(studio.string(R.string.canvas_compare_after))).assertCountEquals(1)
        assertNotNull(studio.state.compare)

        toggle.performClick()
        compose.waitForIdle()
        toggle.assertIsOff()
        compose.onAllNodes(hasContentDescription(handle)).assertCountEquals(0)
        assertEquals(null, studio.state.compare)
        assertEquals(look, studio.state.grade.look)
        assertEquals(StudioPhase.Ready, studio.state.phase)
    }

    @Test
    fun socialJpegExportShowsTheSuccessSnackbarAndWritesAMediaStoreRow() {
        studio.curateStory()
        compose.onNode(hasText(studio.string(R.string.controls_tab_export)) and hasRole(Role.Tab)).performClick()
        compose.onNode(hasText(studio.string(R.string.controls_format_social)) and isSelectable())
            .performScrollTo()
            .performClick()
            .assertIsSelected()
        assertEquals(ExportFormat.SOCIAL_STORY_JPEG, studio.state.export.format)

        val saved = studio.string(R.string.studio_message_export_saved, studio.string(R.string.controls_format_social))
        var exported: Uri? = null
        try {
            compose.onNode(hasText(studio.string(R.string.controls_export_action)) and hasRole(Role.Button))
                .performScrollTo()
                .performClick()
            // Hold the clock so the snackbar's display timer cannot run out between two polls.
            compose.mainClock.autoAdvance = false
            compose.waitUntil(CURATION_TIMEOUT_MS) {
                compose.mainClock.advanceTimeByFrame()
                studio.state.export.lastResult?.let { exported = Uri.parse(it.uri) }
                compose.onAllNodes(hasText(saved)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(hasText(saved)).assertIsDisplayed()
            compose.mainClock.autoAdvance = true

            val result = checkNotNull(studio.state.export.lastResult) { "no export result" }
            exported = Uri.parse(result.uri)
            assertEquals(ExportFormat.SOCIAL_STORY_JPEG, result.format)
            assertEquals("image/jpeg", result.mimeType)
            assertEquals(StudioPhase.Ready, studio.state.phase)
            assertMediaStoreRow(checkNotNull(exported), result.width, result.height)
        } finally {
            compose.mainClock.autoAdvance = true
            exported?.let { uri -> studio.context.contentResolver.delete(uri, null, null) }
        }
        exported?.let { uri ->
            studio.context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media._ID), null, null, null).use {
                assertEquals("export row still present after delete", 0, it?.count ?: 0)
            }
        }
    }

    private fun assertMediaStoreRow(uri: Uri, width: Int, height: Int) {
        val projection = arrayOf(
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.IS_PENDING,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
        )
        val cursor = checkNotNull(studio.context.contentResolver.query(uri, projection, null, null, null)) { "query $uri" }
        cursor.use {
            assertEquals("MediaStore rows for $uri", 1, it.count)
            it.moveToFirst()
            assertEquals("image/jpeg", it.getString(0))
            assertEquals("still pending", 0, it.getInt(1))
            assertTrue("empty file", it.getLong(2) > 0)
            assertEquals(width, it.getInt(3))
            assertEquals(height, it.getInt(4))
        }
        assertNotEquals(0, width)
    }

    private fun scopeButton() = compose.onNode(
        hasContentDescription(studio.string(R.string.controls_scope, ""), substring = true) and hasRole(Role.Button),
    )

    private fun scopeName(mode: ScopeMode): String = studio.string(
        when (mode) {
            ScopeMode.OFF -> R.string.controls_scope_off
            ScopeMode.LUMA_WAVEFORM -> R.string.controls_scope_luma
            ScopeMode.RGB_PARADE -> R.string.controls_scope_rgb_parade
            ScopeMode.HISTOGRAM -> R.string.controls_scope_histogram
        },
    )

    private fun panelCaption(mode: ScopeMode): String = studio.string(
        when (mode) {
            ScopeMode.RGB_PARADE -> R.string.canvas_scope_parade
            ScopeMode.HISTOGRAM -> R.string.canvas_scope_histogram
            else -> R.string.canvas_scope_luma
        },
    )

    private fun lookTile(look: MasterLook) = compose.onNode(hasText(lookName(look)) and isSelectable())

    private fun lookName(look: MasterLook): String = studio.string(
        when (look) {
            MasterLook.PORTRA_400_GOLDEN -> R.string.look_portra_400_golden
            MasterLook.MEDITERRANEAN_AZURE -> R.string.look_mediterranean_azure
            MasterLook.ESPRESSO_WARM_WOOD -> R.string.look_espresso_warm_wood
            MasterLook.CINESTILL_800T_NIGHT -> R.string.look_cinestill_800t_night
            MasterLook.NORDIC_SLATE_MATTE -> R.string.look_nordic_slate_matte
            MasterLook.KODACHROME_64_CLASSIC -> R.string.look_kodachrome_64_classic
            MasterLook.VELVET_MONOCHROME_HP5 -> R.string.look_velvet_monochrome_hp5
            MasterLook.PURE_NATURAL_PRO -> R.string.look_pure_natural_pro
        },
    )
}
