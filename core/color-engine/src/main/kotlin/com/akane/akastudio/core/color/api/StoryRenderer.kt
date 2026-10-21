package com.akane.akastudio.core.color.api

/** GPU renderer. All methods are main-safe (confined to the renderer's own GL thread). */
interface StoryRenderer : AutoCloseable {
    /** Uploads an upright bitmap; RGBA_F16 and ARGB_8888 accepted; its ColorSpace is converted to linear sRGB primaries. Replaces an existing key. */
    suspend fun registerImage(key: String, bitmap: android.graphics.Bitmap)
    suspend fun unregisterImage(key: String)
    /** Renders to a window surface (TextureView/SurfaceView). Display output is sRGB-encoded 8-bit. */
    suspend fun renderToSurface(scene: RenderScene, surface: android.view.Surface, surfaceWidth: Int, surfaceHeight: Int, options: PreviewOptions = PreviewOptions())
    /** Offscreen full-quality render at the scene canvas size into linear half-float. */
    suspend fun renderOffscreen(scene: RenderScene): HalfFloatImage
    /** Small sRGB-encoded readback for scopes (waveform/histogram). */
    suspend fun renderPreviewPixels(scene: RenderScene, width: Int, height: Int): ImagePixels
    /** GL_MAX_TEXTURE_SIZE of the context (for full-resolution decode bounds). */
    suspend fun maxTextureSize(): Int
}
