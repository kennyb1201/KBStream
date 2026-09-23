package com.kennyb1201.kbstream.ui.player

import android.content.Context
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.kennyb1201.kbstream.ui.settings.AppPreferences
import dev.jdtech.mpv.MPVLib

/**
 * The backup engine's picture: a SurfaceView that libmpv renders into.
 *
 * This is a driver, not a player UI — [MpvPlayerActivity] owns the controls,
 * the watch history and the scrobbling, exactly like the main player. What
 * lives here is mpv's own lifecycle, which is order-sensitive:
 *
 *  - options are set BEFORE init() (mpv_set_option_string only accepts them
 *    pre-initialize; afterwards the same call is a runtime property change);
 *  - property observers are registered before init() too, matching the
 *    reference mpv-android player;
 *  - the video surface is attached from the SurfaceHolder callback, and mpv is
 *    told `force-window=yes` only while that surface exists — this is what
 *    makes mpv render frames and OSD into it, and what has to be undone
 *    (`vo=null`, `force-window=no`, detachSurface) before the surface goes
 *    away, or the renderer keeps using a dead window;
 *  - loadfile() is deferred until the surface is up. Loading first can leave
 *    mpv with a video track it never gets an output for, which on this stack
 *    means audio playing over a black screen.
 *
 * mpv's callbacks arrive on its own event thread. Everything this class
 * forwards is posted to the main thread, because every listener updates views.
 */
class MpvPlayerView(context: Context) : SurfaceView(context), SurfaceHolder.Callback,
    MPVLib.EventObserver {

    /** A stream to play, with everything mpv needs to open it. */
    data class LoadRequest(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        /** Separate audio track URL (adaptive split streams), if any. */
        val audioUrl: String? = null,
        val startPositionMs: Long = 0L
    )

    /** One audio or subtitle track as mpv reports it. */
    data class Track(
        val id: Int,
        val type: String,
        val language: String?,
        val title: String?,
        val codec: String?,
        val selected: Boolean
    )

    /** libmpv could not be loaded/created — the engine is unusable here. */
    var onEngineFailed: ((String) -> Unit)? = null

    /** Demuxer opened the file; [mediaTitle] is mpv's idea of its name. */
    var onFileLoaded: ((mediaTitle: String?) -> Unit)? = null

    /** (positionMs, durationMs) — durationMs is 0 while still unknown. */
    var onProgress: ((Long, Long) -> Unit)? = null

    var onPausedChanged: ((Boolean) -> Unit)? = null

    var onBufferingChanged: ((Boolean) -> Unit)? = null

    /** Playback reached the end of the file (keep-open holds the last frame). */
    var onEnded: (() -> Unit)? = null

    /** The file never opened, or mpv gave up on it. */
    var onPlaybackError: ((String) -> Unit)? = null

    private var initialized = false
    private var released = false

    /** Set by [load] before the surface exists; consumed by [surfaceCreated]. */
    private var pendingLoad: LoadRequest? = null

    /**
     * Resume position still to be applied after the file opens.
     *
     * The `start` option below is the fast path (the demuxer opens at that
     * position, with no flash of the opening frames), but it is a per-file
     * option that some network streams ignore. This is the fallback: a plain
     * seek once mpv reports the file loaded, issued only when the playhead did
     * not already land there.
     */
    private var pendingSeekMs = 0L

    private var fileLoaded = false
    private var endNotified = false
    private var durationSec: Double? = null
    private var pauseState = false
    private var cacheStall = false
    private var lastPositionMs = 0L
    private var lastDurationMs = 0L

    /** `mediacodec,mediacodec-copy` while hardware decoding is on, else `no`. */
    private var hwdecValue = HWDEC_HW

    /**
     * Creates the mpv instance. Returns false when the engine is unavailable
     * here (native libraries missing, or a device below the library's floor —
     * [com.kennyb1201.kbstream.data.player.PlayerEngine] checks the API level
     * before we get called).
     */
    fun initialize(): Boolean {
        if (initialized) return true
        try {
            MPVLib.create(context.applicationContext)
        } catch (t: Throwable) {
            Log.e(TAG, "libmpv could not be loaded", t)
            onEngineFailed?.invoke("The MPV engine could not start on this device.")
            return false
        }

        applyOptions()
        try {
            MPVLib.init()
        } catch (t: Throwable) {
            Log.e(TAG, "libmpv could not initialize", t)
            onEngineFailed?.invoke("The MPV engine could not start on this device.")
            return false
        }

        MPVLib.addObserver(this)
        observeProperties()
        holder.addCallback(this)
        initialized = true

        // The view may already be attached (a recreate, or a surface handed to
        // us before onCreate finished): attach right away instead of waiting
        // for a callback that will never come.
        val existing = holder.surface
        if (existing != null && existing.isValid) attachSurface()
        return true
    }

    /**
     * Plays [request]. Safe to call before the surface exists — the load is
     * held back until [surfaceCreated] (see the class comment).
     */
    fun load(request: LoadRequest) {
        if (!initialized || released) return
        pendingLoad = request
        if (holder.surface?.isValid == true) startPendingLoad()
    }

    fun release() {
        if (released) return
        released = true
        holder.removeCallback(this)
        if (initialized) {
            runCatching { MPVLib.removeObserver(this) }
            // Detach the surface before destroying the instance: mpv's
            // renderer must stop using the window first.
            runCatching {
                MPVLib.setPropertyString("vo", "null")
                MPVLib.detachSurface()
            }
            runCatching { MPVLib.destroy() }
        }
        initialized = false
    }

    // --- Playback control ---------------------------------------------------

    fun togglePause() {
        if (!initialized) return
        runCatching { MPVLib.command(arrayOf("cycle", "pause")) }
    }

    fun setPaused(paused: Boolean) {
        if (!initialized) return
        runCatching { MPVLib.setPropertyBoolean("pause", paused) }
    }

    fun seekTo(positionMs: Long) {
        if (!initialized) return
        val seconds = (positionMs.coerceAtLeast(0L) / 1000.0)
        // keyframes, not exact: these sources are mostly remote files whose
        // index mpv has only partially built, and an exact seek on those makes
        // the demuxer decode forward from the file start (or from nothing at
        // all). Landing on the preceding keyframe of a 10s GOP is invisible.
        runCatching { MPVLib.command(arrayOf("seek", seconds.toString(), "absolute+keyframes")) }
        endNotified = false
    }

    fun seekBy(deltaMs: Long) {
        if (!initialized) return
        runCatching { MPVLib.command(arrayOf("seek", "${deltaMs / 1000.0}", "relative+keyframes")) }
        endNotified = false
    }

    /** Current playhead in ms, from the last observed value. */
    fun positionMs(): Long = lastPositionMs

    fun durationMs(): Long = lastDurationMs

    fun isPaused(): Boolean = pauseState

    /**
     * Turns hardware decoding off (or back on) mid-file. This is the escape
     * hatch for the exact case that sends a stream here: a box that hands out
     * no more decoders only starts playing again once nothing is asking
     * MediaCodec for one.
     */
    fun setHardwareDecoding(enabled: Boolean) {
        if (!initialized) return
        hwdecValue = if (enabled) HWDEC_HW else HWDEC_SW
        runCatching { MPVLib.setPropertyString("hwdec", hwdecValue) }
        Log.i(TAG, "hwdec set to $hwdecValue (hardware=$enabled)")
    }

    fun isHardwareDecoding(): Boolean = hwdecValue != HWDEC_SW

    /** Audio tracks mpv found in the file. */
    fun audioTracks(): List<Track> = tracksOfType("audio")

    /** Subtitle tracks mpv found in the file (embedded ASS/SSA included). */
    fun subtitleTracks(): List<Track> = tracksOfType("sub")

    /**
     * Cycles to the next subtitle track, ending on "no subtitles" so the
     * button can turn them off again. Returns the new state's label.
     */
    fun cycleSubtitleTrack(): String {
        if (!initialized) return "Subtitles"
        val tracks = subtitleTracks()
        if (tracks.isEmpty()) {
            // Nothing to cycle through in this file. Still issue the command:
            // mpv answers it by turning off an external track, which is what
            // the button means when mpv found no embedded subtitles.
            runCatching { MPVLib.command(arrayOf("cycle", "sub")) }
            return "No subtitles in this file"
        }
        val currentIndex = tracks.indexOfFirst { it.selected }
        val nextIndex = currentIndex + 1
        val next = tracks.getOrNull(nextIndex)
        runCatching {
            if (next == null) {
                MPVLib.setPropertyString("sid", "no")
            } else {
                MPVLib.setPropertyInt("sid", next.id)
            }
        }
        return when (next) {
            null -> "Subtitles off"
            else -> "Subtitles: " + (next.title ?: next.language ?: next.codec ?: "#${next.id}")
        }
    }

    fun cycleAudioTrack(): String {
        if (!initialized) return "Audio"
        val tracks = audioTracks()
        if (tracks.size < 2) return "Only one audio track"
        val currentIndex = tracks.indexOfFirst { it.selected }
        val next = tracks[(currentIndex + 1).mod(tracks.size)]
        runCatching { MPVLib.setPropertyInt("aid", next.id) }
        return "Audio: " + (next.title ?: next.language ?: next.codec ?: "#${next.id}")
    }

    /** mpv's own name for what it is decoding, for the diagnostics log. */
    fun loadedFileDiagnostics(): String {
        val video = getPropertyStringOrNull("video-format") ?: "audio-only"
        val hwdec = getPropertyStringOrNull("hwdec-current") ?: "none"
        return "video=$video hwdec=$hwdec"
    }

    // --- mpv configuration --------------------------------------------------

    private fun applyOptions() {
        // No user config: this is a fallback engine, and an mpv.conf picked up
        // from the device would change behavior between boxes.
        MPVLib.setOptionString("config", "no")
        MPVLib.setOptionString("terminal", "no")
        MPVLib.setOptionString("ytdl", "no")
        // mpv's own UI is off: these controls are the app's.
        MPVLib.setOptionString("osc", "no")
        MPVLib.setOptionString("osd-level", "0")
        MPVLib.setOptionString("input-default-bindings", "no")
        MPVLib.setOptionString("input-vo-keyboard", "no")

        // Android video output. gpu (not gpu-next) is the one the reference
        // Android player ships as default and the one this native build is
        // packaged for.
        MPVLib.setOptionString("vo", "gpu")
        MPVLib.setOptionString("gpu-context", "android")
        MPVLib.setOptionString("opengl-es", "yes")

        // Decoding: try MediaCodec zero-copy, then copy-back, then software.
        // The trailing `no` is the whole point of this engine — a file the
        // box's decoders cannot give us still plays.
        MPVLib.setOptionString("hwdec", HWDEC_HW)
        MPVLib.setOptionString("hwdec-codecs", HWDEC_CODECS)

        // Audio: MediaPlayer-style role so a TV or receiver treats this like
        // any other media session.
        MPVLib.setOptionString("ao", "audiotrack,opensles")
        MPVLib.setOptionString("audio-set-media-role", "yes")

        // Networking. A dead host should fail in half a minute instead of
        // sitting on a spinner, and TLS is verified.
        MPVLib.setOptionString("tls-verify", "yes")
        MPVLib.setOptionString("network-timeout", "30")

        // Caching: mpv's defaults are sized for a desktop; 64 MB matches what
        // the reference Android player uses.
        MPVLib.setOptionString("cache", "yes")
        MPVLib.setOptionString("demuxer-max-bytes", "${CACHE_MB * 1024 * 1024}")
        MPVLib.setOptionString("demuxer-max-back-bytes", "${CACHE_MB * 1024 * 1024}")

        // Playback shape. keep-open holds the last frame at EOF so the
        // activity can offer the next episode; save-position-on-quit is off
        // because watch history is the app's job, not mpv's watch_later files.
        MPVLib.setOptionString("keep-open", "yes")
        MPVLib.setOptionString("idle", "yes")
        MPVLib.setOptionString("force-window", "no")
        MPVLib.setOptionString("save-position-on-quit", "no")

        // Preferred languages, so a multi-audio/multi-subtitle file opens on
        // the right tracks without a press. ASS styling is deliberately left
        // alone: no subtitle style option is set anywhere, which is what keeps
        // a fansub's typesetting intact.
        preferredLanguage(AppPreferences.getPreferredAudioLanguage(context))
            ?.let { MPVLib.setOptionString("alang", it) }
        preferredLanguage(AppPreferences.getPreferredSubtitleLanguage(context))
            ?.let { MPVLib.setOptionString("slang", it) }
    }

    private fun observeProperties() {
        MPVLib.observeProperty("time-pos", MPVLib.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("duration", MPVLib.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("duration/full", MPVLib.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("pause", MPVLib.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("paused-for-cache", MPVLib.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("eof-reached", MPVLib.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("media-title", MPVLib.MPV_FORMAT_STRING)
        // Bare observation (no format): we only need to know it changed, then
        // read the parts we care about through mpv's property paths.
        MPVLib.observeProperty("track-list", MPVLib.MPV_FORMAT_NONE)
    }

    // --- Surface lifecycle --------------------------------------------------

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (!initialized || released) return
        attachSurface()
        if (pendingLoad != null) startPendingLoad()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (!initialized || released) return
        // mpv sizes its output from this, not from the Surface itself.
        runCatching { MPVLib.setPropertyString("android-surface-size", "${width}x$height") }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (!initialized || released) return
        // Order matters: stop the renderer, drop forced rendering, and only
        // then let the surface go.
        runCatching {
            MPVLib.setPropertyString("vo", "null")
            MPVLib.setPropertyString("force-window", "no")
            MPVLib.detachSurface()
        }
    }

    private fun attachSurface() {
        val surface = holder.surface ?: return
        runCatching {
            MPVLib.attachSurface(surface)
            // Forces mpv to render video/subtitles into our surface even when
            // it would otherwise decide it has no window to draw into.
            MPVLib.setOptionString("force-window", "yes")
        }.onFailure { Log.e(TAG, "could not attach the MPV surface", it) }
    }

    private fun startPendingLoad() {
        val request = pendingLoad ?: return
        pendingLoad = null
        fileLoaded = false
        endNotified = false
        durationSec = null
        cacheStall = false
        // The playhead is published from mpv itself from here on.
        lastPositionMs = 0L
        lastDurationMs = 0L
        pendingSeekMs = request.startPositionMs.coerceAtLeast(0L)

        if (request.headers.isNotEmpty()) {
            val fields = request.headers.entries.joinToString(",") { (key, value) ->
                "$key: ${value.replace(',', ';')}"
            }
            // mpv reads a comma-separated field list here; commas inside a
            // value are what would split one field into two, so they are
            // replaced rather than allowed to corrupt the rest of the list.
            MPVLib.setOptionString("http-header-fields", fields)
        }
        // Resume position: applied by the demuxer at open time, so playback
        // starts there instead of seeking after a flash of the opening frames.
        MPVLib.setOptionString("start", "${request.startPositionMs.coerceAtLeast(0L) / 1000.0}")

        runCatching { MPVLib.command(arrayOf("loadfile", request.url, "replace")) }
            .onFailure { Log.e(TAG, "loadfile failed", it) }

        // Adaptive split stream: the video URL carries no audio, so the
        // separate track is added as an additional audio file.
        request.audioUrl?.takeIf { it.isNotBlank() }?.let { audioUrl ->
            runCatching { MPVLib.command(arrayOf("audio-add", audioUrl)) }
                .onFailure { Log.w(TAG, "audio-add failed", it) }
        }
    }

    // --- mpv events ---------------------------------------------------------

    override fun event(eventId: Int) {
        when (eventId) {
            MPVLib.MPV_EVENT_FILE_LOADED -> {
                fileLoaded = true
                applyPendingSeek()
                val title = getPropertyStringOrNull("media-title")
                Log.i(
                    TAG,
                    "MPV file loaded at ${lastPositionMs}ms: ${loadedFileDiagnostics()}"
                )
                post { onFileLoaded?.invoke(title) }
            }

            MPVLib.MPV_EVENT_END_FILE -> {
                // A file that never opened is a failure; one that reached its
                // end is handled by eof-reached (keep-open pauses there).
                if (!fileLoaded) {
                    Log.w(TAG, "MPV could not open the stream")
                    post { onPlaybackError?.invoke("This stream could not be played.") }
                }
            }

            MPVLib.MPV_EVENT_VIDEO_RECONFIG -> {
                Log.i(TAG, "MPV video reconfigured: ${loadedFileDiagnostics()}")
            }
        }
    }

    override fun eventProperty(property: String) = Unit

    override fun eventProperty(property: String, value: Long) = Unit

    override fun eventProperty(property: String, value: Double) {
        when (property) {
            "time-pos" -> {
                lastPositionMs = (value * 1000.0).toLong().coerceAtLeast(0L)
                publishProgress()
            }

            "duration", "duration/full" -> {
                durationSec = value
                lastDurationMs = if (value > 0.0) (value * 1000.0).toLong() else 0L
                publishProgress()
            }
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        when (property) {
            "pause" -> {
                pauseState = value
                post { onPausedChanged?.invoke(value) }
            }

            "paused-for-cache" -> {
                // A pause for cache is not buffering if it is the end-of-file
                // pause, and the spinner must not come back over a held frame.
                val buffering = value && !endNotified
                if (buffering != cacheStall) {
                    cacheStall = buffering
                    post { onBufferingChanged?.invoke(buffering) }
                }
            }

            "eof-reached" -> {
                if (value && !endNotified) {
                    endNotified = true
                    post { onEnded?.invoke() }
                } else if (!value) {
                    // A seek back into the file re-arms the end-of-file offer.
                    endNotified = false
                }
            }
        }
    }

    override fun eventProperty(property: String, value: String) {
        if (property == "media-title") {
            Log.i(TAG, "MPV media-title: $value")
        }
    }

    // --- Helpers ------------------------------------------------------------

    /**
     * Lands the playhead on the resume position when the `start` option did not
     * (see [pendingSeekMs]). The tolerance keeps a correct `start` from paying
     * for a redundant seek — and, on a stream whose index is still being built,
     * from paying for a slow one.
     */
    private fun applyPendingSeek() {
        val resume = pendingSeekMs
        pendingSeekMs = 0L
        if (resume <= 0L) return
        if (kotlin.math.abs(lastPositionMs - resume) <= RESUME_TOLERANCE_MS) return
        Log.i(TAG, "applying resume position ${resume}ms after load")
        runCatching {
            MPVLib.command(arrayOf("seek", (resume / 1000.0).toString(), "absolute+keyframes"))
        }.onFailure { Log.w(TAG, "resume seek failed", it) }
    }

    private fun publishProgress() {
        val position = lastPositionMs
        val duration = lastDurationMs
        post { onProgress?.invoke(position, duration) }
    }

    /**
     * Reads mpv's track list through property paths (`track-list/count`,
     * `track-list/<i>/<field>`). track-list itself is a NODE and cannot be
     * read through this binding, but its indexed paths can.
     */
    private fun tracksOfType(type: String): List<Track> {
        if (!initialized || released) return emptyList()
        val count = getPropertyIntOrNull("track-list/count") ?: return emptyList()
        val tracks = ArrayList<Track>(count)
        for (index in 0 until count) {
            val trackType = getPropertyStringOrNull("track-list/$index/type") ?: continue
            if (trackType != type) continue
            tracks += Track(
                id = getPropertyIntOrNull("track-list/$index/id") ?: continue,
                type = trackType,
                language = getPropertyStringOrNull("track-list/$index/lang"),
                title = getPropertyStringOrNull("track-list/$index/title"),
                codec = getPropertyStringOrNull("track-list/$index/codec"),
                selected = getPropertyBooleanOrNull("track-list/$index/selected") == true
            )
        }
        return tracks
    }

    private fun getPropertyIntOrNull(name: String): Int? =
        runCatching { MPVLib.getPropertyInt(name) }.getOrNull()

    private fun getPropertyStringOrNull(name: String): String? =
        runCatching { MPVLib.getPropertyString(name) }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun getPropertyBooleanOrNull(name: String): Boolean? =
        runCatching { MPVLib.getPropertyBoolean(name) }.getOrNull()

    /**
     * mpv wants a language list ("en,eng"), the app stores a BCP-47 tag
     * ("en-US"). Only the primary subtag is passed: a region match is mpv's
     * fallback anyway, and passing "en-US" makes mpv look for the literal
     * tag in the track's metadata, which almost never matches.
     */
    private fun preferredLanguage(tag: String?): String? =
        tag?.substringBefore('-')?.substringBefore('_')?.trim()?.takeIf { it.isNotBlank() }

    private companion object {
        const val TAG = "PLAYER_MPV"

        /** How close to the resume point counts as "already there". */
        const val RESUME_TOLERANCE_MS = 5_000L

        /** MediaCodec first, copy-back second, then software decoding. */
        const val HWDEC_HW = "mediacodec,mediacodec-copy,no"
        const val HWDEC_SW = "no"
        const val HWDEC_CODECS = "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1"

        const val CACHE_MB = 64
    }
}
