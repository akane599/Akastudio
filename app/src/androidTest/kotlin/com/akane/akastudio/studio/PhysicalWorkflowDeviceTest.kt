package com.akane.akastudio.studio

import android.app.ActivityOptions
import android.content.ContentValues
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.akane.akastudio.AkaStudioApp
import com.akane.akastudio.MainActivity
import com.akane.akastudio.core.color.api.MasterLook
import com.akane.akastudio.core.export.api.ExportFormat
import com.akane.akastudio.core.export.api.ExportResolution
import com.akane.akastudio.feature.studio.mvi.StudioIntent
import com.akane.akastudio.feature.studio.mvi.StudioPhase
import com.akane.akastudio.feature.studio.mvi.StudioViewModel
import com.akane.akastudio.feature.studio.ui.studioViewModelFactory
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real MediaStore/models/GPU/export and Activity lifecycle; picker-result intents enter the real ViewModel. */
@RunWith(AndroidJUnit4::class)
class PhysicalWorkflowDeviceTest {
    @Test
    fun importEditExportRecreateDiscardAndReimport() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val displayId = InstrumentationRegistry.getArguments().getString("qaDisplayId")?.toInt() ?: 0
        val run = "physical_${System.currentTimeMillis()}"
        val photos = mutableListOf<Uri>()
        var exportUri: Uri? = null
        val scenario = ActivityScenario.launch<MainActivity>(
            Intent(context, MainActivity::class.java),
            ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle(),
        )
        fun vm(): StudioViewModel {
            var result: StudioViewModel? = null
            scenario.onActivity {
                assertEquals("actual workflow display", displayId, it.display?.displayId)
                val container = (it.application as AkaStudioApp).container
                result = ViewModelProvider(it, studioViewModelFactory(container))[StudioViewModel::class.java]
            }
            return checkNotNull(result)
        }
        fun send(intent: StudioIntent) {
            val model = vm()
            scenario.onActivity { model.onIntent(intent) }
        }
        fun awaitReady() = awaitCondition("physical workflow Ready", CURATION_TIMEOUT_MS) {
            val state = vm().state.value
            if (state.phase == StudioPhase.Empty && state.error != null) throw AssertionError("Curation failed: ${state.error}")
            state.phase == StudioPhase.Ready
        }
        try {
            photos += QaPhotos.insert(context, run, 8)
            send(StudioIntent.ImportPhotos(photos.map(Uri::toString)))
            awaitReady()
            val originalVm = vm()
            val original = originalVm.state.value
            val look = MasterLook.entries.first { it != original.grade.look }
            send(StudioIntent.SelectLook(look))
            assertEquals(look, vm().state.value.grade.look)
            assertTrue(vm().state.value.sceneRevision > original.sceneRevision)

            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.recreate()
            assertSame("ViewModel survives recreation", originalVm, vm())
            assertSame("curation survives recreation", original.curation, vm().state.value.curation)
            assertEquals(look, vm().state.value.grade.look)

            send(StudioIntent.SetExportFormat(ExportFormat.SOCIAL_STORY_JPEG))
            send(StudioIntent.SetExportResolution(ExportResolution.HD_1080))
            send(StudioIntent.Export(share = false))
            awaitCondition("physical workflow export", CURATION_TIMEOUT_MS) {
                val state = vm().state.value
                state.export.lastResult?.let { exportUri = Uri.parse(it.uri) }
                if (state.error != null) throw AssertionError("Export failed: ${state.error}")
                exportUri != null && state.phase == StudioPhase.Ready
            }
            val uri = checkNotNull(exportUri)
            // Move only this newly created test row into the QA media namespace.
            context.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AkaStudioQA/$run/")
            }, null, null)
            val directory = File(context.filesDir, "akadroidqa/$run").apply { check(mkdirs()) }
            val file = File(directory, "workflow_social.jpg")
            checkNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            val bitmap = checkNotNull(BitmapFactory.decodeFile(file.absolutePath))
            try {
                assertEquals(1080 to 1920, bitmap.width to bitmap.height)
                assertTrue(file.length() > 0)
            } finally {
                bitmap.recycle()
            }
            Log.i("AkaDeviceQA", "workflow export=$file display=$displayId look=$look")

            send(StudioIntent.Reset)
            awaitCondition("discard to Empty", UI_TIMEOUT_MS) { vm().state.value.phase == StudioPhase.Empty }
            assertEquals(null, vm().state.value.curation)
            send(StudioIntent.ImportPhotos(photos.map(Uri::toString)))
            awaitReady()
            assertEquals(8, vm().state.value.telemetry.photosRead)
            Log.i("AkaDeviceQA", "workflow PASS import/curate/edit/export/background/recreate/discard/reimport display=$displayId")
        } finally {
            try {
                scenario.close()
            } finally {
                exportUri?.let { context.contentResolver.delete(it, null, null) }
                QaPhotos.delete(context, run, photos)
            }
        }
    }
}
