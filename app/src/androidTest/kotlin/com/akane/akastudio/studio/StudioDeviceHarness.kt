package com.akane.akastudio.studio

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
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
import java.io.Closeable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse

/** Curation runs the real on-device models over full photos; the redroid emulator renders GL on SwiftShader. */
internal const val CURATION_TIMEOUT_MS = 300_000L
internal const val UI_TIMEOUT_MS = 30_000L

/**
 * Drives the real studio: [MainActivity] on the real `AkaStudioApp` container, fed photos the way the picker result
 * is (`StudioIntent.ImportPhotos` with content uris, sent to the activity's own [StudioViewModel]).
 *
 * The activity is launched with a plain explicit intent. Photos inserted for the run are deleted on [close].
 */
internal class StudioDeviceHarness(private val compose: ComposeTestRule) : Closeable {

    val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    val context: Context = instrumentation.targetContext
    private val runId = "${System.currentTimeMillis()}"
    private val photos = mutableListOf<Uri>()
    private var launched: ActivityScenario<MainActivity>? = null

    val scenario: ActivityScenario<MainActivity> get() = checkNotNull(launched) { "launch() first" }

    /** The activity's lifecycle state as ActivityScenario last observed it. */
    val activityState: Lifecycle.State get() = scenario.state

    fun launch(): ActivityScenario<MainActivity> =
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).also { launched = it }

    /** The activity's own studio view model: the instance `StudioRoute` obtained from the activity's store. */
    fun viewModel(): StudioViewModel {
        var vm: StudioViewModel? = null
        scenario.onActivity { activity ->
            val container = (activity.application as AkaStudioApp).container
            vm = ViewModelProvider(activity, studioViewModelFactory(container))[StudioViewModel::class.java]
        }
        return checkNotNull(vm)
    }

    val state: StudioState get() = viewModel().state.value

    /** Sends [intent] on the main thread, as the UI would. */
    fun send(intent: StudioIntent) {
        val vm = viewModel()
        scenario.onActivity { vm.onIntent(intent) }
    }

    /** Inserts [count] photos into MediaStore and delivers them exactly as the photo picker's result callback does. */
    fun importPhotos(count: Int) {
        val uris = QaPhotos.insert(context, runId, count)
        photos += uris
        send(StudioIntent.ImportPhotos(uris.map(Uri::toString)))
    }

    /** Waits for [StudioPhase.Ready]; fails fast if the studio fell back to Empty with an error. */
    fun awaitReady(timeoutMs: Long = CURATION_TIMEOUT_MS) {
        val vm = viewModel()
        awaitCondition("studio Ready", timeoutMs) {
            val s = vm.state.value
            if (s.phase == StudioPhase.Empty && s.error != null) throw AssertionError("Studio returned to Empty: ${s.error}")
            s.phase == StudioPhase.Ready
        }
        compose.waitForIdle()
    }

    /** Launches the studio, imports [count] photos and waits for the curated story; returns the curator's mode. */
    fun curateStory(count: Int = 8): StoryMode {
        if (launched == null) launch()
        importPhotos(count)
        awaitReady()
        return checkNotNull(state.curation) { "Ready without a curation" }.chosen.mode
    }

    /** A real system BACK key press, injected into the focused window. */
    fun pressBack() {
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        instrumentation.waitForIdleSync()
    }

    /** Asserts the activity is still in front and was not asked to finish (BACK was handled in the studio). */
    fun assertStillResumed(afterWhat: String) {
        var finishing = true
        runCatching { scenario.onActivity { finishing = it.isFinishing } }
        assertFalse("$afterWhat finished the activity", finishing)
        assertEquals("$afterWhat left the activity", Lifecycle.State.RESUMED, activityState)
    }

    fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    fun modeName(mode: StoryMode): String = string(
        when (mode) {
            StoryMode.HERO -> R.string.mode_hero
            StoryMode.COLLAGE_2 -> R.string.mode_collage_2
            StoryMode.COLLAGE_3 -> R.string.mode_collage_3
            StoryMode.COLLAGE_4 -> R.string.mode_collage_4
        },
    )

    fun aiPickLabel(mode: StoryMode): String = string(R.string.controls_mode_ai_pick, modeName(mode))

    fun userPickLabel(mode: StoryMode): String = string(R.string.controls_mode_user_pick, modeName(mode))

    /** The top-bar mode chip announcing [label] (its accessibility label is always the full sentence). */
    fun modeChip(label: String): SemanticsMatcher = hasContentDescription(label) and hasRole(Role.Button)

    override fun close() {
        try {
            launched?.close()
        } finally {
            QaPhotos.delete(context, photos)
        }
    }
}

/** Polls [condition] off the main thread until it holds or [timeoutMs] passes. */
internal fun awaitCondition(what: String, timeoutMs: Long, condition: () -> Boolean) {
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    while (!condition()) {
        if (SystemClock.uptimeMillis() > deadline) throw AssertionError("Timed out after $timeoutMs ms waiting for $what")
        Thread.sleep(50)
    }
}

internal fun hasRole(role: Role): SemanticsMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

internal fun hasStateDescription(value: String): SemanticsMatcher =
    SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

internal fun isSelectable(): SemanticsMatcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)

/**
 * The strings this node and its descendants actually draw, read from their text layout modifiers.
 *
 * The mode chip clears its children's semantics (screen readers hear one full label), so what the chip *shows* is
 * not in the semantics tree; this reads it from the layout tree instead. Call on an unmerged-tree node.
 */
internal fun SemanticsNode.drawnTexts(): List<String> {
    val own = layoutInfo.getModifierInfo().mapNotNull { textOf(it.modifier) }
    return own + children.flatMap { it.drawnTexts() }
}

/** The text of a foundation text element (`Text*Element`), or null for any other modifier. */
private fun textOf(modifier: Modifier): String? {
    val type = modifier.javaClass
    if (!type.name.startsWith("androidx.compose.foundation.text.")) return null
    val field = type.declaredFields.firstOrNull { it.name == "text" } ?: return null
    field.isAccessible = true
    return when (val value = field.get(modifier)) {
        is String -> value
        is AnnotatedString -> value.text
        else -> null
    }
}

/** True when the two rectangles share any area (touching edges do not count). */
internal fun Rect.overlapsArea(other: Rect): Boolean =
    left < other.right && other.left < right && top < other.bottom && other.top < bottom
