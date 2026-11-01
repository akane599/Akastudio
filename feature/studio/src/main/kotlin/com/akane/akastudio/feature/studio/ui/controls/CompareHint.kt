package com.akane.akastudio.feature.studio.ui.controls

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.akane.akastudio.feature.studio.R
import com.akane.akastudio.feature.studio.ui.theme.DarkroomTheme

/** Where the "compare hint seen" flag lives: a platform [SharedPreferences] file of the app. */
object CompareHintPrefs {
    const val FILE_NAME = "studio_hints"
    const val KEY_SEEN = "compare_hint_seen"
}

/** Whether the one-time A/B compare hint was already dismissed. */
internal interface CompareHintStore {
    fun isSeen(): Boolean
    fun markSeen()
}

private class SharedPrefsCompareHintStore(private val prefs: SharedPreferences) : CompareHintStore {
    override fun isSeen(): Boolean = prefs.getBoolean(CompareHintPrefs.KEY_SEEN, false)
    override fun markSeen() = prefs.edit { putBoolean(CompareHintPrefs.KEY_SEEN, true) }
}

/** A store held in memory only: previews and screenshots, which have no app storage to persist into. */
internal class InMemoryCompareHintStore(private var seen: Boolean = false) : CompareHintStore {
    override fun isSeen(): Boolean = seen
    override fun markSeen() {
        seen = true
    }
}

/** Overrides the hint store (screenshots); null uses the app's [CompareHintPrefs] file. */
internal val LocalCompareHintStore = staticCompositionLocalOf<CompareHintStore?> { null }

/** The provided store, else an unseen in-memory one in previews, else the app's [CompareHintPrefs] file. */
@Composable
internal fun rememberCompareHintStore(): CompareHintStore {
    val provided = LocalCompareHintStore.current
    val inspection = LocalInspectionMode.current
    val context = LocalContext.current
    return remember(provided, inspection, context) {
        provided ?: if (inspection) {
            InMemoryCompareHintStore()
        } else {
            SharedPrefsCompareHintStore(
                context.applicationContext.getSharedPreferences(CompareHintPrefs.FILE_NAME, Context.MODE_PRIVATE),
            )
        }
    }
}

/**
 * The one-time hint for the icon-only A/B compare tool: an inverse bubble whose caret points up at the tool,
 * [anchorFromEnd] from the hint's end edge. Tapping it dismisses it; screen readers hear it once when it appears.
 */
@Composable
internal fun CompareHintTooltip(anchorFromEnd: Dp, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val spacing = DarkroomTheme.spacing
    val bubble = scheme.inverseSurface
    val caretInset = (anchorFromEnd - ControlMetrics.HintCaretWidth / 2).coerceAtLeast(0.dp)
    Column(
        horizontalAlignment = Alignment.End,
        modifier = modifier
            .widthIn(max = ControlMetrics.HintMaxWidth)
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(R.string.controls_compare_hint_dismiss),
                onClick = onDismiss,
            )
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Canvas(
            Modifier
                .padding(end = caretInset)
                .size(ControlMetrics.HintCaretWidth, ControlMetrics.HintCaretHeight),
        ) {
            val caret = Path().apply {
                moveTo(size.width / 2, 0f)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(caret, bubble)
        }
        Surface(shape = MaterialTheme.shapes.small, color = bubble, contentColor = scheme.inverseOnSurface) {
            Box(Modifier.heightIn(min = ControlMetrics.TouchTarget), contentAlignment = Alignment.CenterStart) {
                Text(
                    text = stringResource(R.string.controls_compare_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = spacing.m, vertical = spacing.s),
                )
            }
        }
    }
}
