package com.akane.akastudio.core.color.render

import android.graphics.Bitmap
import android.util.Log
import com.akane.akastudio.core.color.gl.GlCapabilities
import com.akane.akastudio.core.color.gl.GlTexture

/**
 * Registered images: key -> retained Bitmap + its texture. The Bitmap reference is kept until
 * [unregister] so a lost context can be re-uploaded; the caller owns recycling after unregistering.
 * An entry whose re-upload failed keeps no texture and is uploaded again on its next [texture] call.
 * GL thread only.
 */
internal class ImageRegistry {
    private class Entry(val bitmap: Bitmap, var texture: GlTexture?)

    private val entries = LinkedHashMap<String, Entry>()

    /**
     * Capabilities of the context the registry last uploaded into. The owner calls [reuploadAll] for every new
     * context before any [texture] call, so this is the current context's whenever [texture] reads it.
     */
    private var capabilities: GlCapabilities? = null

    /** Uploads [bitmap] under [key], replacing (and freeing) any previous texture for that key. */
    fun register(key: String, bitmap: Bitmap, capabilities: GlCapabilities) {
        val texture = BitmapUpload.upload(bitmap, capabilities)
        this.capabilities = capabilities
        entries.put(key, Entry(bitmap, texture))?.texture?.release()
    }

    fun unregister(key: String) {
        entries.remove(key)?.texture?.release()
    }

    /**
     * Texture for [key], uploading it first when an earlier re-upload failed; throws [IllegalArgumentException]
     * when the key is not registered, and the upload's own exception when the retry fails too.
     */
    fun texture(key: String): GlTexture {
        val entry = entries[key] ?: throw notRegistered(key)
        entry.texture?.let { return it }
        if (entry.bitmap.isRecycled) {
            Log.w(TAG, "dropping '$key': bitmap recycled before unregisterImage")
            entries.remove(key)
            throw notRegistered(key)
        }
        val current = checkNotNull(capabilities) { "no GL context has been created to upload '$key' into" }
        return BitmapUpload.upload(entry.bitmap, current).also { entry.texture = it }
    }

    /** Deletes every texture but keeps the Bitmaps (context teardown before a re-upload). */
    fun releaseTextures() {
        entries.values.forEach { entry ->
            entry.texture?.release()
            entry.texture = null
        }
    }

    /**
     * Re-uploads every retained Bitmap into the current context. Entries whose Bitmap was recycled while
     * still registered (a caller contract violation) are dropped with a warning, so later renders using
     * that key fail with "not registered" instead of sampling garbage. An entry that fails to upload is
     * left without a texture and retried by [texture], so one failure neither aborts the rest nor loses
     * that image for the session.
     */
    fun reuploadAll(capabilities: GlCapabilities) {
        this.capabilities = capabilities
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val (key, entry) = iterator.next()
            if (entry.bitmap.isRecycled) {
                Log.w(TAG, "dropping '$key': bitmap recycled before unregisterImage")
                iterator.remove()
                continue
            }
            entry.texture = try {
                BitmapUpload.upload(entry.bitmap, capabilities)
            } catch (e: RuntimeException) {
                Log.w(TAG, "re-upload of '$key' failed; retrying when it is next rendered", e)
                null
            }
        }
    }

    /** Deletes every texture and forgets every Bitmap. */
    fun clear() {
        releaseTextures()
        entries.clear()
    }

    private fun notRegistered(key: String) =
        IllegalArgumentException("image '$key' is not registered with the StoryRenderer")

    private companion object {
        const val TAG = "ImageRegistry"
    }
}
