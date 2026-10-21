package com.akane.akastudio.core.color.api

/** GPU renderer. All methods are main-safe (confined to the renderer's own GL thread). */
interface StoryRenderer : AutoCloseable {
    /** Uploads an upright bitmap; RGBA_F16 and ARGB_8888 accepted; its ColorSpace is converted to linear sRGB primaries. Replaces an existing key. */
    suspend fun registerImage(key: String, bitmap: android.graphics.Bitmap)
    suspend fun unregisterImage(key: String)
    /** Renders to a window surface (TextureView/SurfaceView). Display output is sRGB-encoded 8-bit. */
    suspend fun renderToSurface(scene: RenderScene, surface: android.view.Surface, surfaceWidth: Int, surfaceHeight: Int, options: PreviewOptions = PreviewOptions())
    /** Offscreen full-quality render at the scene canvas size into premultiplied linear half-float, with no matte. */
    suspend fun renderOffscreen(scene: RenderScene): HalfFloatImage
    /** Small sRGB-encoded readback for scopes (waveform/histogram). */
    suspend fun renderPreviewPixels(scene: RenderScene, width: Int, height: Int): ImagePixels
    /** GL_MAX_TEXTURE_SIZE of the context (for full-resolution decode bounds). */
    suspend fun maxTextureSize(): Int

    /**
     * Frees GPU memory the renderer holds only to make the next frame cheaper: pooled render targets, cached grades,
     * text rasters and the cached window surface. The renderer stays fully usable (the next render rebuilds what it
     * needs) and registered images are kept. Asynchronous, queued after work already submitted, never throws; call it
     * when the UI is hidden or the system asks to trim memory.
     */
    fun trimMemory() = Unit

    /**
     * Drops the renderer's window surface for [surface], if it holds one, before the caller releases [surface]
     * (a TextureView's SurfaceTexture being destroyed). Asynchronous, queued after work already submitted.
     */
    fun releaseSurface(surface: android.view.Surface) = Unit
}
