package com.akane.akastudio.feature.studio

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.akane.akastudio.feature.studio.mvi.StudioState
import com.akane.akastudio.feature.studio.ui.dropzone.DropzoneContent
import com.akane.akastudio.feature.studio.ui.theme.DarkroomTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** On-device smoke: the empty Dropzone renders on the Darkroom theme and its import actions reach their callbacks. */
@RunWith(AndroidJUnit4::class)
class StudioDeviceSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun emptyDropzoneShowsImportActions() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        var photoPicks = 0
        var zipPicks = 0
        compose.setContent {
            DarkroomTheme {
                DropzoneContent(
                    state = StudioState(),
                    onIntent = {},
                    onPickPhotos = { photoPicks++ },
                    onPickZip = { zipPicks++ },
                    onRetry = {},
                )
            }
        }

        compose.onNodeWithText(resources.getString(R.string.dropzone_tagline)).assertIsDisplayed()
        compose.onNodeWithText(resources.getString(R.string.dropzone_select_photos)).assertIsDisplayed().performClick()
        compose.onNodeWithText(resources.getString(R.string.dropzone_import_zip)).assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, photoPicks)
            assertEquals(1, zipPicks)
        }
    }
}
