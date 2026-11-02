package com.akane.akastudio.studio

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.akane.akastudio.AkaStudioApp
import com.akane.akastudio.MainActivity
import com.akane.akastudio.core.curator.api.StoryMode
import com.akane.akastudio.feature.studio.R
import com.akane.akastudio.feature.studio.mvi.StudioIntent
import com.akane.akastudio.feature.studio.mvi.StudioPhase
import com.akane.akastudio.feature.studio.mvi.StudioState
import com.akane.akastudio.feature.studio.mvi.StudioViewModel
import com.akane.akastudio.feature.studio.ui.studioViewModelFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real window/lifecycle coverage using public platform input and accessibility, without Espresso synchronization. */
@RunWith(AndroidJUnit4::class)
class StudioPlatformLifecycleDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val displayId = InstrumentationRegistry.getArguments().getString("qaDisplayId")?.toInt() ?: 0
    private val runId = "platform_${System.currentTimeMillis()}"
    private val photos = mutableListOf<Uri>()
    private var launched: ActivityScenario<MainActivity>? = null
    private val scenario: ActivityScenario<MainActivity> get() = checkNotNull(launched)

    @After
    fun tearDown() {
        try {
            launched?.close()
        } finally {
            QaPhotos.delete(context, runId, photos)
        }
    }

    @Test
    fun systemBackInEmptyFinishesTheActivity() {
        launch()
        awaitVisible(string(R.string.dropzone_tagline))
        assertEquals(StudioPhase.Empty, viewModel().state.value.phase)
        assertStillResumed()

        systemBack()

        await("activity destroyed after BACK in Empty") { scenario.state == Lifecycle.State.DESTROYED }
    }

    @Test
    fun systemBackInReadyKeepsThenDiscardsThroughTheDialog() {
        val chip = curateStory()
        val before = viewModel().state.value
        val title = string(R.string.studio_discard_title)

        systemBack()
        awaitVisible(title)
        assertStillResumed()
        assertStoryKept(before)
        clickVisible(string(R.string.studio_discard_keep))
        awaitAbsent(title)
        awaitVisible(chip)
        assertStoryKept(before)
        assertStillResumed()

        systemBack()
        awaitVisible(title)
        clickVisible(string(R.string.studio_discard_confirm))
        await("Empty after the discard button") { viewModel().state.value.phase == StudioPhase.Empty }
        awaitAbsent(title)
        awaitVisible(string(R.string.dropzone_tagline))
        awaitVisible(string(R.string.dropzone_select_photos))
        awaitAbsent(chip)
        assertEquals(null, viewModel().state.value.curation)
        assertStillResumed()
    }

    @Test
    fun systemBackClosesTheModeDrawerBeforeAskingToDiscard() {
        val chip = curateStory()
        val before = viewModel().state.value
        val drawerTitle = string(R.string.controls_drawer_title)
        clickVisible(chip)
        await("mode drawer open") { viewModel().state.value.modeDrawerOpen }
        awaitVisible(drawerTitle)

        systemBack()

        await("mode drawer closed") { !viewModel().state.value.modeDrawerOpen }
        awaitAbsent(drawerTitle)
        awaitVisible(chip)
        awaitAbsent(string(R.string.studio_discard_title))
        assertStoryKept(before)
        assertStillResumed()

        // A second BACK proves the first did not just leave the discard UI unobserved.
        systemBack()
        awaitVisible(string(R.string.studio_discard_title))
        clickVisible(string(R.string.studio_discard_keep))
        awaitAbsent(string(R.string.studio_discard_title))
        assertStoryKept(before)
        assertStillResumed()
    }

    @Test
    fun orientationChangesRecreateTheActivityAndRetainTheReadyStory() {
        val chip = curateStory()
        val vm = viewModel()
        val before = vm.state.value
        var initialOrientation = Configuration.ORIENTATION_UNDEFINED
        scenario.onActivity { initialOrientation = it.resources.configuration.orientation }
        val portrait = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT to Configuration.ORIENTATION_PORTRAIT
        val landscape = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE to Configuration.ORIENTATION_LANDSCAPE
        val orientations = if (initialOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            listOf(portrait, landscape)
        } else {
            listOf(landscape, portrait)
        }

        orientations.forEach { (requested, expected) ->
            rotate(requested, expected)
            assertSame("view model changed on rotation", vm, viewModel())
            awaitVisible(chip)
            assertStoryKept(before)
            assertStillResumed()
        }
    }

    private fun launch() {
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        launched = ActivityScenario.launch(
            Intent(context, MainActivity::class.java),
            ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle(),
        )
        scenario.onActivity {
            Log.i("AkaDeviceQA", "lifecycle requestedDisplay=$displayId actualDisplay=${it.display?.displayId}")
            assertEquals("actual lifecycle activity display", displayId, it.display?.displayId)
        }
    }

    private fun viewModel(): StudioViewModel {
        var vm: StudioViewModel? = null
        scenario.onActivity { activity ->
            val container = (activity.application as AkaStudioApp).container
            vm = ViewModelProvider(activity, studioViewModelFactory(container))[StudioViewModel::class.java]
        }
        return checkNotNull(vm)
    }

    /** Same real MediaStore photos and picker-result intent as the existing Compose device harness. */
    private fun curateStory(): String {
        launch()
        photos += QaPhotos.insert(context, runId, 8)
        val vm = viewModel()
        scenario.onActivity { vm.onIntent(StudioIntent.ImportPhotos(photos.map(Uri::toString))) }
        await("studio Ready", CURATION_TIMEOUT_MS) {
            val state = vm.state.value
            if (state.phase == StudioPhase.Empty && state.error != null) {
                throw AssertionError("Studio returned to Empty: ${state.error}")
            }
            state.phase == StudioPhase.Ready
        }
        val mode = checkNotNull(vm.state.value.curation).chosen.mode
        val modeName = string(
            when (mode) {
                StoryMode.HERO -> R.string.mode_hero
                StoryMode.COLLAGE_2 -> R.string.mode_collage_2
                StoryMode.COLLAGE_3 -> R.string.mode_collage_3
                StoryMode.COLLAGE_4 -> R.string.mode_collage_4
            },
        )
        return string(R.string.controls_mode_ai_pick, modeName).also { awaitVisible(it) }
    }

    /** Shell input traverses Android's real BACK dispatch; it does not call the activity dispatcher directly. */
    private fun systemBack() {
        instrumentation.waitForIdleSync()
        val output = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("input -d $displayId keyevent KEYCODE_BACK"),
        ).use { it.readBytes().toString(Charsets.UTF_8) }
        assertTrue("BACK input command reported: $output", output.isBlank())
        instrumentation.waitForIdleSync()
    }

    private fun rotate(requested: Int, expected: Int) {
        var previous: Activity? = null
        scenario.onActivity { previous = it; it.requestedOrientation = requested }
        var current: Activity? = null
        await("new resumed activity in orientation $expected") {
            // ActivityScenario temporarily refuses access while recreation is in progress.
            val observed = runCatching {
                scenario.onActivity { current = it }
            }.isSuccess
            observed && current !== previous && current?.resources?.configuration?.orientation == expected &&
                scenario.state == Lifecycle.State.RESUMED
        }
        assertNotSame("activity was not recreated", previous, current)
    }

    private fun assertStoryKept(before: StudioState) {
        val after = viewModel().state.value
        assertEquals(StudioPhase.Ready, after.phase)
        assertSame("curation replaced", before.curation, after.curation)
        assertEquals(before.selection.photoIds, after.selection.photoIds)
        assertEquals(before.selection.mode, after.selection.mode)
        assertEquals(before.grade, after.grade)
        assertEquals(before.template, after.template)
    }

    private fun assertStillResumed() {
        assertEquals(Lifecycle.State.RESUMED, scenario.state)
        scenario.onActivity { assertFalse("activity is finishing", it.isFinishing) }
    }

    private fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun awaitVisible(label: String): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        try {
            await("visible accessibility node: $label") {
                found = appRoots().firstNotNullOfOrNull { findVisible(it, label) }
                found != null
            }
        } catch (failure: AssertionError) {
            throw AssertionError("${failure.message}\n${accessibilitySnapshot()}", failure)
        }
        return checkNotNull(found)
    }

    private fun accessibilitySnapshot(): String = buildString {
        appendLine("AkaStudio accessibility snapshot, display=$displayId")
        instrumentation.uiAutomation.windowsOnAllDisplays[displayId].orEmpty().forEach { window ->
            val root = window.root ?: return@forEach
            if (root.packageName?.toString() != context.packageName) return@forEach
            appendLine("window id=${window.id} type=${window.type} active=${window.isActive} focused=${window.isFocused}")
            fun appendNode(node: AccessibilityNodeInfo, depth: Int) {
                val bounds = Rect().also(node::getBoundsInScreen)
                appendLine(
                    "${"  ".repeat(depth)}class=${node.className} visible=${node.isVisibleToUser} " +
                        "bounds=$bounds text=${node.text} description=${node.contentDescription} " +
                        "clickable=${node.isClickable} children=${node.childCount}",
                )
                for (index in 0 until node.childCount) {
                    node.getChild(index)?.let { appendNode(it, depth + 1) }
                }
            }
            appendNode(root, 0)
        }
    }

    private fun awaitAbsent(label: String) {
        await("accessibility node absent: $label") {
            // A missing/foreign root is not proof that the app removed a node.
            val roots = appRoots()
            roots.isNotEmpty() && roots.none { findVisible(it, label) != null }
        }
    }

    private fun clickVisible(label: String) {
        var node = awaitVisible(label)
        while (!node.isClickable) {
            node = checkNotNull(node.parent) { "No clickable accessibility ancestor for $label" }
        }
        assertTrue("Accessibility click failed: $label", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun appRoots(): List<AccessibilityNodeInfo> {
        val automation = instrumentation.uiAutomation
        // Observe the current window tree after drawer/dialog transitions, rather than a cached prior tree.
        if (Build.VERSION.SDK_INT >= 34 && !automation.clearCache()) {
            Log.w("AkaDeviceQA", "Accessibility cache was unavailable for clearing")
        }
        return automation.windowsOnAllDisplays[displayId]
            ?.asSequence()
            ?.mapNotNull { it.root }
            ?.filter { it.packageName?.toString() == context.packageName }
            ?.toList()
            .orEmpty()
    }

    private fun findVisible(root: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        if (root.isVisibleToUser && (root.text?.toString() == label || root.contentDescription?.toString() == label)) {
            return root
        }
        for (index in 0 until root.childCount) {
            val child = root.getChild(index) ?: continue
            findVisible(child, label)?.let { return it }
        }
        return null
    }

    private fun await(what: String, timeoutMs: Long = UI_TIMEOUT_MS, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!condition()) {
            if (SystemClock.uptimeMillis() > deadline) throw AssertionError("Timed out after $timeoutMs ms: $what")
            SystemClock.sleep(50)
        }
    }

    private companion object {
        const val UI_TIMEOUT_MS = 30_000L
        const val CURATION_TIMEOUT_MS = 300_000L
    }
}
