package com.akane.akastudio.feature.studio.ui.canvas

import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.akane.akastudio.core.color.api.PreviewOptions
import com.akane.akastudio.core.color.api.RenderScene
import com.akane.akastudio.core.color.api.StoryRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.conflate

private const val TAG = "StoryCanvas"

/** A live window surface and its size in pixels. A size change produces a new instance around the same [surface]. */
private class SurfaceTarget(val surface: Surface, val width: Int, val height: Int)

/**
 * Wraps the [TextureView]'s [SurfaceTexture] in a [Surface] while it exists and reports it, with its size, through
 * [onTarget]. The [Surface] is released when the texture is destroyed (the view detaching or being torn down), and
 * [onTarget] gets null first so no new render starts on it.
 */
private class SurfaceTargetListener(private val onTarget: (SurfaceTarget?) -> Unit) : TextureView.SurfaceTextureListener {
    private var surface: Surface? = null

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        surface?.release()
        val created = Surface(texture)
        surface = created
        onTarget(SurfaceTarget(created, width, height))
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        surface?.let { onTarget(SurfaceTarget(it, width, height)) }
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        onTarget(null)
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
}

/**
 * The GPU preview: a [TextureView] that [renderer] draws [scene] into.
 *
 * A new `(revision, compare)` pair is rendered at most once per frame: values are conflated while a render runs, the
 * loop waits for the next frame, then draws the latest scene and split. Render failures are logged and the loop keeps
 * going; when the surface goes away the loop stops and restarts on the next one.
 */
@Composable
internal fun PreviewSurface(
    scene: RenderScene?,
    revision: Long,
    compare: Float?,
    renderer: StoryRenderer,
    modifier: Modifier = Modifier,
) {
    var target by remember { mutableStateOf<SurfaceTarget?>(null) }
    val currentScene by rememberUpdatedState(scene)
    val currentRevision by rememberUpdatedState(revision)
    val currentCompare by rememberUpdatedState(compare)

    AndroidView(
        factory = { context ->
            TextureView(context).apply {
                isOpaque = true
                surfaceTextureListener = SurfaceTargetListener { target = it }
            }
        },
        modifier = modifier,
    )

    LaunchedEffect(target, renderer) {
        val live = target ?: return@LaunchedEffect
        snapshotFlow { currentRevision to currentCompare }
            .conflate()
            .collect {
                withFrameNanos { }
                val latest = currentScene ?: return@collect
                renderFrame(renderer, latest, live, currentCompare)
            }
    }
}

private suspend fun renderFrame(renderer: StoryRenderer, scene: RenderScene, target: SurfaceTarget, compare: Float?) {
    if (!target.surface.isValid) return
    try {
        renderer.renderToSurface(scene, target.surface, target.width, target.height, PreviewOptions(compare))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Preview render failed (${target.width}x${target.height})", e)
    }
}
