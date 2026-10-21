package com.akane.akastudio.feature.studio.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.akane.akastudio.core.color.api.ImagePixels
import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.color.api.Rect4f
import com.akane.akastudio.core.color.api.RenderScene
import com.akane.akastudio.core.color.api.SceneBackground
import com.akane.akastudio.core.color.api.SceneLayer
import com.akane.akastudio.feature.studio.mvi.ScopeMode
import com.akane.akastudio.feature.studio.ui.canvas.scope.ScopeData
import com.akane.akastudio.feature.studio.ui.canvas.scope.ScopePanel
import com.akane.akastudio.feature.studio.ui.theme.DarkroomTheme
import com.android.tools.screenshot.PreviewTest
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// Rendered on the JVM: ./gradlew :feature:studio:updateDebugScreenshotTest (write refs) / validateDebugScreenshotTest (diff)

private val TrioScene = RenderScene(
    canvasWidth = 1080,
    canvasHeight = 1920,
    background = SceneBackground.Solid(0xFF121316.toInt()),
    layers = listOf(
        SceneLayer("a", Rect4f(12f, 12f, 1068f, 1100f), NormRect.FULL, cornerRadiusPx = 24f),
        SceneLayer("b", Rect4f(12f, 1112f, 534f, 1908f), NormRect.FULL, cornerRadiusPx = 24f),
        SceneLayer("c", Rect4f(546f, 1112f, 1068f, 1908f), NormRect.FULL, cornerRadiusPx = 24f),
    ),
)

@Composable
private fun CanvasShot(compare: Float?, selectedSlot: Int?) = DarkroomTheme {
    StoryCanvasLayout(
        scene = TrioScene,
        revision = 1L,
        renderer = null,
        compare = compare,
        onIntent = {},
        hitTest = { _, _ -> null },
        selectedSlot = selectedSlot,
        onSelect = {},
        photoIds = persistentListOf("a", "b", "c"),
        overrides = persistentMapOf(),
        scope = ScopeMode.OFF,
        scopePixels = null,
        modifier = Modifier.fillMaxSize(),
    )
}

/** A 270x480 synthetic "dusk" frame: a warm-to-cool sky over a darker, textured ground. */
private fun syntheticPixels(): ImagePixels {
    val w = 270
    val h = 480
    val argb = IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        val fx = x / (w - 1f)
        val fy = y / (h - 1f)
        val sky = fy < 0.55f
        val texture = 0.08f * sin(fx * 31f) * cos(fy * 23f)
        val (r, g, b) = if (sky) {
            val t = fy / 0.55f
            Triple(0.95f - 0.35f * t + 0.1f * fx, 0.62f - 0.12f * t, 0.38f + 0.35f * t - 0.1f * fx)
        } else {
            val t = (fy - 0.55f) / 0.45f
            Triple(0.32f - 0.22f * t + texture, 0.28f - 0.18f * t + texture, 0.24f - 0.16f * t + texture)
        }
        fun c(v: Float) = (v.coerceIn(0f, 1f) * 255f).roundToInt()
        (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }
    return ImagePixels(w, h, argb)
}

@Composable
private fun ScopeShot(mode: ScopeMode) = DarkroomTheme {
    val colors = DarkroomTheme.colors
    val data = remember(mode) { ScopeData.compute(syntheticPixels(), mode, colors) }
    Box(Modifier.background(colors.canvasBackdrop).padding(DarkroomTheme.spacing.l)) {
        ScopePanel(data = data, mode = mode, modifier = Modifier.width(320.dp))
    }
}

@PreviewTest @Preview(name = "placeholder-trio", widthDp = 360, heightDp = 640, showBackground = true, backgroundColor = 0xFF070809)
@Composable fun CanvasPlaceholderTrio() = CanvasShot(compare = null, selectedSlot = null)

@PreviewTest @Preview(name = "compare-handle", widthDp = 360, heightDp = 640, showBackground = true, backgroundColor = 0xFF070809)
@Composable fun CanvasCompareHandle() = CanvasShot(compare = 0.42f, selectedSlot = 1)

@PreviewTest @Preview(name = "scope-luma", widthDp = 352, showBackground = true, backgroundColor = 0xFF070809)
@Composable fun CanvasScopeLuma() = ScopeShot(ScopeMode.LUMA_WAVEFORM)

@PreviewTest @Preview(name = "scope-parade", widthDp = 352, showBackground = true, backgroundColor = 0xFF070809)
@Composable fun CanvasScopeParade() = ScopeShot(ScopeMode.RGB_PARADE)

@PreviewTest @Preview(name = "scope-histogram", widthDp = 352, showBackground = true, backgroundColor = 0xFF070809)
@Composable fun CanvasScopeHistogram() = ScopeShot(ScopeMode.HISTOGRAM)
