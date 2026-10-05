package com.kennyb1201.kbstream.ui.player

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.util.Log
import java.io.File

/**
 * Owns one libass instance for the ExoPlayer session: creates it, feeds it a
 * normalised.ass script, and renders a frame per playback tick.
 *
 * Two threads reach this instance: the main thread renders a frame per tick
 * ([render]), while ExoPlayer's playback thread feeds embedded-track data
 * through [LibassStreamingSink] ([create] / [processCodecPrivate] /
 * [processChunk] / [flushEvents]). libass is not thread-safe, and a seek on the
 * playback thread can `release()` and re-create the instance underneath a main
 * thread that is mid-`render`, so every native call is serialised under this
 * object's monitor. [handle] and [loaded] are volatile as well, so the
 * lockless [active] read never sees a torn or stale instance. It reports
 * failure by returning null/false rather than throwing so a missing or broken
 * library can never take down playback.
 */
internal class AssSubtitleRenderer {

    @Volatile
    private var handle = 0L

    @Volatile
    private var loaded = false

    /**
     * The frame buffer, reused across ticks.
     *
     * Reuse matters: a fresh ARGB_8888 bitmap per frame would be ~8 MB of
     * allocation at 30 fps. Reuse is safe for drawing because the native side
     * fills it through AndroidBitmap_lockPixels/unlockPixels, and unlock is
     * what tells the framework the pixels changed - the same path the
     * framework's own bitmap mutation uses.
     */
    private var frame: Bitmap? = null
    private var width = 0
    private var height = 0

    /** True when a script is loaded and frames can be drawn. */
    val active: Boolean get() = loaded && handle != 0L

    /**
     * Loads [content], replacing anything already loaded. [fonts] are
     * attached to libass by hand (fontconfig cannot see app-private storage);
     * the system fonts are found by fontconfig through [configPath] instead.
     */
    @Synchronized
    fun load(content: String, fonts: List<File>, configPath: String?, cacheDir: String?): Boolean {
        if (!AssNative.available) return false
        val normalized = AssSubtitleSource.normalize(content)
        if (!AssSubtitleSource.isAssContent(normalized)) {
            Log.w(TAG, "sidecar is not an ASS/SSA script; leaving it to the text renderer")
            return false
        }

        if (!createInstance(fonts, configPath, cacheDir)) return false

        loaded = AssNative.nativeLoadTrack(handle, normalized)
        if (!loaded) {
            Log.w(TAG, "libass rejected the script")
            release()
            return false
        }
        Log.i(TAG, "ASS script loaded: ${normalized.length} chars")
        return true
    }

    /**
     * Creates the native instance for the STREAMING path (an embedded track fed
     * header-then-events), without loading a whole script. [active] becomes true
     * on success so the overlay tick runs from here, before the first events
     * have arrived — the frames it draws are simply empty until they do.
     */
    @Synchronized
    fun create(fonts: List<File>, configPath: String?, cacheDir: String?): Boolean {
        if (!createInstance(fonts, configPath, cacheDir)) return false
        loaded = true
        return true
    }

    /** Shared body of [load] and [create]: allocate the instance and attach fonts. */
    private fun createInstance(
        fonts: List<File>,
        configPath: String?,
        cacheDir: String?
    ): Boolean {
        if (!AssNative.available) return false
        release()
        val created = AssNative.nativeCreate(configPath, cacheDir)
        if (created == 0L) {
            Log.w(TAG, "libass renderer could not be created")
            return false
        }
        handle = created

        for (font in fonts) {
            runCatching { AssNative.nativeAddFont(handle, font.name, font.readBytes()) }
                .onFailure { Log.w(TAG, "could not attach font ${font.name}", it) }
        }
        if (fonts.isNotEmpty()) Log.i(TAG, "attached ${fonts.size} font(s) to libass")
        return true
    }

    /**
     * Feeds the header block of a streaming track. Returns whether libass took
     * it; the empty track is created on the native side by this first call.
     */
    @Synchronized
    fun processCodecPrivate(data: ByteArray): Boolean =
        active && runCatching { AssNative.nativeProcessCodecPrivate(handle, data) }
            .getOrDefault(false)

    /** Appends one event block to a streaming track. No-op when inactive. */
    @Synchronized
    fun processChunk(data: ByteArray, timeMs: Long, durationMs: Long) {
        if (!active || data.isEmpty()) return
        runCatching { AssNative.nativeProcessChunk(handle, data, timeMs, durationMs) }
            .onFailure { Log.w(TAG, "could not append an ASS event", it) }
    }

    /** Drops every accumulated event (for a seek). Styles are unaffected. */
    @Synchronized
    fun flushEvents() {
        if (handle == 0L) return
        runCatching { AssNative.nativeFlushEvents(handle) }
            .onFailure { Log.w(TAG, "could not flush ASS events", it) }
    }

    /**
     * Sets the layout rectangle libass renders against. Reallocates the frame
     * buffer when the size actually changes, so a 4K title does not render
     * every subtitle frame at 4K.
     */
    @Synchronized
    fun setViewport(width: Int, height: Int) {
        if (handle == 0L || width <= 0 || height <= 0) return
        if (this.width == width && this.height == height) return
        this.width = width
        this.height = height
        frame = null
        AssNative.nativeSetViewport(handle, width, height)
    }

    /**
     * Draws the frame for [timeMs] and returns the buffer to display, or null
     * when there is nothing to draw. The buffer is cleared for every frame,
     * so a tick with no active subtitle returns a fully transparent image
     * rather than leaving the previous line on screen.
     */
    @Synchronized
    fun render(timeMs: Long): Bitmap? {
        if (!active || width <= 0 || height <= 0) return null
        val buffer = frame ?: Bitmap
            .createBitmap(width, height, Bitmap.Config.ARGB_8888)
            .also { frame = it }
        return runCatching {
            AssNative.nativeRenderFrame(handle, timeMs, buffer)
            buffer
        }.onFailure {
            Log.w(TAG, "ASS frame render failed; dropping the renderer", it)
            release()
        }.getOrNull()
    }

    /**
     * Frees the native instance. The frame buffer is dropped rather than
     * recycled: the ImageView may still be holding it, and drawing a recycled
     * bitmap throws.
     */
    @Synchronized
    fun release() {
        if (handle != 0L && AssNative.available) {
            runCatching { AssNative.nativeDestroy(handle) }
                .onFailure { Log.w(TAG, "could not destroy the libass renderer", it) }
        }
        handle = 0L
        loaded = false
        width = 0
        height = 0
        frame = null
    }

    companion object {
        private const val TAG = "AssRenderer"

        /** Whether this build carries libassjni.so at all. */
        val available: Boolean get() = AssNative.available

        /**
         * Copies the bundled fontconfig file into app storage and returns its
         * path, or null when it cannot be written.
         *
         * It has to be copied out of assets: fontconfig opens the file by
         * path, and a path inside the APK is not openable. Rewriting only
         * when the content differs keeps a release's bundled config from
         * being shadowed by an older copy left in app storage.
         */
        fun installFontConfig(assets: AssetManager, filesDir: File): String? = runCatching {
            val target = File(filesDir, "fontconfig/fonts.conf")
            val bundled = assets.open(FONTCONFIG_ASSET).use { it.readBytes() }
                .toString(Charsets.UTF_8)
            if (!target.isFile || target.readText() != bundled) {
                target.parentFile?.mkdirs()
                target.writeText(bundled)
            }
            target.absolutePath
        }.onFailure {
            Log.w(TAG, "could not install the fontconfig file", it)
        }.getOrNull()

        private const val FONTCONFIG_ASSET = "fontconfig/fonts.conf"
    }
}
