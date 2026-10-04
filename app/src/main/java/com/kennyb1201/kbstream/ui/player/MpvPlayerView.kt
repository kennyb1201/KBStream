package com.kennyb1201.kbstream.ui.player

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.kennyb1201.kbstream.data.player.LanguageMatch
import com.kennyb1201.kbstream.data.player.StreamUserAgent
import com.kennyb1201.kbstream.data.settings.AppPreferences
import dev.jdtech.mpv.MPVLib
import java.io.File
import java.util.Locale
import kotlin.math.pow

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
class MpvPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback,
    MPVLib.EventObserver, MPVLib.LogObserver {

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
        val channels: Int?,
        val selected: Boolean
    ) {
        /**
         * The same four fields the main player's picker lists and stores
         * ("ENG • EAC3 • 6ch"), so a track remembered in one engine reads the
         * same in the other.
         */
        val label: String
            get() = listOfNotNull(
                language?.uppercase()?.takeIf { it.isNotBlank() },
                codec?.uppercase()?.takeIf { it.isNotBlank() },
                channels?.takeIf { it > 0 }?.let { "${it}ch" }
            ).joinToString(" • ").ifBlank { title ?: "Track #$id" }

        /** `language|codec|channels`, the shape stored per title. */
        val signature: String
            get() = listOf(
                language.orEmpty().lowercase(),
                codec.orEmpty().lowercase(),
                (channels ?: 0).toString()
            ).joinToString("|")
    }

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

    /**
     * The video track's frame rate, once mpv knows one.
     *
     * Offered to whatever matches the panel to the content (see
     * [FrameRateMatcher]). Fires at most twice per file — once when it loads,
     * and again if the video output is reconfigured — because the rate is a
     * property of the file, not of playback.
     */
    var onVideoFrameRateChanged: ((Double) -> Unit)? = null

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

    /**
     * mpv's own last error-level line, if it has said anything.
     *
     * A failed load reports as "this stream could not be played" and nothing
     * else: mpv knows whether that was a 403, an unsupported protocol or a
     * refused connection, and the only place it says so is its log. Kept so the
     * notice a viewer sees can name the actual reason instead of guessing.
     */
    private var lastErrorLine: String? = null

    /**
     * The host of the stream the current load was for. Read only when the load
     * failed without mpv naming a reason, so the diagnostics tell WHICH stream
     * failed silently - the one fact a bare "no reason reported" throws away.
     */
    private var lastLoadHost: String? = null

    /**
     * mpv's recent error lines, newest last.
     *
     * The line that names why a stream will not open is logged just BEFORE the
     * generic "Failed to open <url>" that follows it, so keeping only the last
     * line kept the least informative line in the burst. Bounded, because only
     * the tail is ever read.
     */
    private val recentErrorLines = ArrayDeque<String>()

    private var durationSec: Double? = null
    private var pauseState = false
    private var cacheStall = false
    private var lastPositionMs = 0L
    private var lastDurationMs = 0L

    /** `mediacodec,mediacodec-copy` while hardware decoding is on, else `no`. */
    private var hwdecValue = HWDEC_HW

    // --- Audio tuning, mirroring the main player's AUDIO panel section ------
    //
    // The main player runs these three through its own PCM path
    // ([AudioDownmixProcessor]); here they are mpv's own properties and one
    // mpv audio filter. Same values, same option lists (they come from
    // [PlayerAudioTuning]), so the two panels cannot drift.

    /** Layout the output should carry: Auto / Stereo / 5.1. */
    private var downmixTarget = PlayerAudioTuning.DOWNMIX_AUTO

    /** Center (or phantom-center) lift: 0 off, 1 low, 2 high. */
    private var dialogueBoost = 0

    /** Extra output gain in dB, 0-15. */
    private var volumeBoostDb = 0

    /**
     * Multiplier over the boost, 0-1: the sleep timer's fade.
     *
     * Kept as its own factor because the two answer different questions - the
     * boost is how this *title* is mixed, the fade is how long is left tonight -
     * and because only one of them persists: the boost is remembered per show
     * (see PlayerTitlePrefs) while a fade left applied would open the next title
     * silent.
     */
    private var outputGain = 1f

    /**
     * Buffering profile: 0 balanced, 1 low latency.
     *
     * `cache` / `demuxer-max-bytes` are per-file mpv options, so a change made
     * while a film plays is honored by the next load rather than by this one -
     * which is what the panel row says.
     */
    private var bufferMode = 0


    /**
     * Languages this session wants, resolved by the activity (a title's
     * remembered choice, else the global Settings preference) and applied at
     * open time through `alang`/`slang`.
     */
    private var audioLanguage = ""
    private var subtitleLanguage = ""

    /**
     * The libmpv 1.0.0 handle. 1.0.0 turned [MPVLib] into an instance API -
     * `MPVLib.create()` returns the handle and every command hangs off it -
     * where 0.5.1 exposed the same native calls as a static singleton. Null
     * until [initialize] creates it; every call below runs under the
     * `initialized` guard, so the accessor exists only to keep the call sites
     * free of `!!` noise.
     */
    private var mpvInstance: MPVLib? = null
    private val mpv: MPVLib
        get() = checkNotNull(mpvInstance) { "libmpv used before initialize()" }

    /**
     * Creates the mpv instance. Returns false when the engine is unavailable
     * here (native libraries missing, or a device below the library's floor —
     * [com.kennyb1201.kbstream.data.player.PlayerEngine] checks the API level
     * before we get called).
     */
    fun initialize(): Boolean {
        if (initialized) return true
        try {
            mpvInstance = MPVLib.create(context.applicationContext)
        } catch (t: Throwable) {
            Log.e(TAG, "libmpv could not be loaded", t)
            onEngineFailed?.invoke("The MPV engine could not start on this device.")
            return false
        }

        applyOptions()
        try {
            mpv.init()
        } catch (t: Throwable) {
            Log.e(TAG, "libmpv could not initialize", t)
            onEngineFailed?.invoke("The MPV engine could not start on this device.")
            return false
        }

        mpv.addObserver(this)
        // mpv's own account of a failed open (an HTTP status, a refused
        // connection) is only ever delivered here - see [logMessage].
        mpv.addLogObserver(this)
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
            runCatching { mpv.removeObserver(this) }
            runCatching { mpv.removeLogObserver(this) }
            // Detach the surface before destroying the instance: mpv's
            // renderer must stop using the window first.
            runCatching {
                mpv.setPropertyString("vo", "null")
                mpv.detachSurface()
            }
            runCatching { mpv.destroy() }
            mpvInstance = null
        }
        initialized = false
    }

    // --- Playback control ---------------------------------------------------

    fun togglePause() {
        if (!initialized) return
        runCatching { mpv.command(arrayOf("cycle", "pause")) }
    }

    fun setPaused(paused: Boolean) {
        if (!initialized) return
        runCatching { mpv.setPropertyBoolean("pause", paused) }
    }

    fun seekTo(positionMs: Long) {
        if (!initialized) return
        val seconds = (positionMs.coerceAtLeast(0L) / 1000.0)
        // keyframes, not exact: these sources are mostly remote files whose
        // index mpv has only partially built, and an exact seek on those makes
        // the demuxer decode forward from the file start (or from nothing at
        // all). Landing on the preceding keyframe of a 10s GOP is invisible.
        runCatching { mpv.command(arrayOf("seek", seconds.toString(), "absolute+keyframes")) }
        endNotified = false
    }

    fun seekBy(deltaMs: Long) {
        if (!initialized) return
        runCatching { mpv.command(arrayOf("seek", "${deltaMs / 1000.0}", "relative+keyframes")) }
        endNotified = false
    }

    /**
     * Writes the frame mpv is showing right now to [path].
     *
     * The `video` flag keeps this app's OSD and the subtitles out of the image:
     * a scrub preview is a picture of the moment, and a subtitle line burned into
     * it would put dialogue on the card the viewer is using to find their place.
     * The format comes from the file extension - mpv goes by that for
     * `screenshot-to-file` - and it is a JPEG because every capture is the
     * file's own resolution and is deleted the moment it has been read (see
     * [MpvScrubPreviews]).
     *
     * Synchronous in practice, since mpv writes the file inside the command, but
     * the caller looks for the file rather than assuming: this call cannot await
     * the write, and a missing file has to be a quiet "no preview" rather than a
     * crash or a blank card.
     */
    fun screenshotToFile(path: String) {
        if (!initialized) return
        runCatching { mpv.command(arrayOf("screenshot-to-file", path, "video")) }
            .onFailure { Log.w(TAG, "screenshot to $path failed", it) }
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
        runCatching { mpv.setPropertyString("hwdec", hwdecValue) }
        Log.i(TAG, "hwdec set to $hwdecValue (hardware=$enabled)")
    }

    fun isHardwareDecoding(): Boolean = hwdecValue != HWDEC_SW

    /**
     * Output layout. "auto-safe" is mpv's default downmix, which folds to what
     * the device can actually carry; Stereo always folds, and 5.1 keeps six
     * channels for an AVR (the main player's own three options, one for one).
     */
    fun setDownmix(target: Int) {
        downmixTarget = target
        if (!initialized) return
        val layout = when (target) {
            PlayerAudioTuning.DOWNMIX_STEREO -> "stereo"
            PlayerAudioTuning.DOWNMIX_SURROUND -> "5.1"
            else -> "auto-safe"
        }
        runCatching { mpv.setPropertyString("audio-channels", layout) }
            .onFailure { Log.w(TAG, "audio-channels=$layout rejected", it) }
    }

    /**
     * Dialogue lift, matching what the main player's chain does: a center
     * boost on a multichannel mix, and a mid (phantom-center) boost on a
     * stereo one - the only place dialogue can live in a 2.0 track.
     *
     * Applied through mpv's `pan` filter, built from the channel count mpv
     * reports for what is playing now, because the filter has to name the
     * channels it touches. A layout this does not have a spec for (mono, or
     * anything unusual) gets no filter at all rather than a spec mpv could
     * reject - silence would be a far worse answer than a gentle mix.
     */
    fun setDialogueBoost(level: Int) {
        dialogueBoost = level.coerceIn(0, PlayerAudioTuning.DIALOGUE_MAX)
        if (initialized) applyDialogueFilter()
    }

    /**
     * Overall output gain, the counterpart of the main player's `linearGain`.
     *
     * mpv has no limiter on `volume`, so this is gain and nothing else: the
     * panel's own note says so. `volume-max` has to be raised with it, because
     * mpv's default ceiling (130) sits below every step above +2dB and would
     * silently swallow the rest.
     */
    fun setVolumeBoostDb(db: Int) {
        volumeBoostDb = db.coerceIn(0, 15)
        applyVolume()
    }

    /**
     * The sleep timer's fade, as a multiplier over the boost: 1 is "no fade",
     * and the timer walks it down to 0 over its last twenty seconds.
     */
    fun setOutputGain(fraction: Float) {
        outputGain = fraction.coerceIn(0f, 1f)
        applyVolume()
    }

    /**
     * Writes the one property mpv plays at: `volume`, boost and fade together.
     * `volume-max` is raised with the boost because mpv clips at it, and its
     * default 130 sits below every step above +2dB.
     */
    private fun applyVolume() {
        if (!initialized) return
        val boost =
            if (volumeBoostDb <= 0) VOLUME_NORMAL
            else VOLUME_NORMAL * 10.0.pow(volumeBoostDb / 20.0)
        val percent = boost * outputGain
        runCatching {
            mpv.setPropertyDouble("volume-max", VOLUME_MAX)
            mpv.setPropertyDouble("volume", percent)
        }.onFailure { Log.w(TAG, "volume=$percent rejected", it) }
    }

    /**
     * Buffering profile, from Settings' own value. Balanced keeps the 64 MB
     * cache this engine has always used; Low Latency drops the cache and the
     * read-ahead so a live channel is not sitting a buffer behind.
     */
    fun setBufferMode(mode: Int) {
        bufferMode = mode
        // Before init the value is picked up by applyOptions(); after it, mpv
        // takes the properties and honors them on the next file it opens.
        if (initialized) applyCacheOptions(mode)
    }

    /**
     * Adds a subtitle file mpv did not find in the container - the same
     * "open a file / search online" path the main player has. `select` makes it
     * the track in use, since picking one by hand means "show me this".
     */
    fun addExternalSubtitle(uri: String) {
        if (!initialized) return
        runCatching { mpv.command(arrayOf("sub-add", uri, "select")) }
            .onFailure { Log.w(TAG, "sub-add failed for $uri", it) }
    }

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
            runCatching { mpv.command(arrayOf("cycle", "sub")) }
            return "No subtitles in this file"
        }
        val currentIndex = tracks.indexOfFirst { it.selected }
        val nextIndex = currentIndex + 1
        val next = tracks.getOrNull(nextIndex)
        runCatching {
            if (next == null) {
                mpv.setPropertyString("sid", "no")
            } else {
                mpv.setPropertyInt("sid", next.id)
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
        runCatching { mpv.setPropertyInt("aid", next.id) }
        return "Audio: " + (next.title ?: next.language ?: next.codec ?: "#${next.id}")
    }

    /** mpv's own name for what it is decoding, for the diagnostics log. */
    fun loadedFileDiagnostics(): String {
        val video = getPropertyStringOrNull("video-format") ?: "audio-only"
        val hwdec = getPropertyStringOrNull("hwdec-current") ?: "none"
        return "video=$video hwdec=$hwdec"
    }

    // --- Settings the panel drives ------------------------------------------

    /**
     * The languages this session wants, in the app's own tag form ("en").
     *
     * Called before [initialize] so they land as the `alang`/`slang` options
     * the demuxer honors at open time, and again later when the panel changes
     * them, as runtime properties (which act on the next file).
     */
    fun setLanguagePreferences(audio: String?, subtitle: String?) {
        audioLanguage = preferredLanguage(audio).orEmpty()
        subtitleLanguage = preferredLanguage(subtitle).orEmpty()
        if (!initialized) return
        runCatching {
            mpv.setPropertyString("alang", audioLanguage)
            mpv.setPropertyString("slang", subtitleLanguage)
        }
    }

    /** Playback speed; 1.0 is normal. */
    fun setSpeed(speed: Double) {
        if (!initialized) return
        runCatching { mpv.setPropertyDouble("speed", speed) }
    }

    /** Audio delay in ms (`audio-delay` is kept in seconds). */
    fun setAudioDelayMs(ms: Int) {
        if (!initialized) return
        runCatching { mpv.setPropertyDouble("audio-delay", ms / 1000.0) }
    }

    /** Subtitle delay in ms (`sub-delay`). */
    fun setSubtitleDelayMs(ms: Int) {
        if (!initialized) return
        runCatching { mpv.setPropertyDouble("sub-delay", ms / 1000.0) }
    }

    /**
     * Selects the first audio track in [language]; blank hands the choice back
     * to mpv's `alang`. False when the file carries no such track, in which
     * case the current track stands - the main player keeps the current audio
     * in that case too.
     */
    fun selectAudioLanguage(language: String): Boolean {
        if (!initialized) return false
        if (language.isBlank()) {
            runCatching { mpv.setPropertyString("aid", "auto") }
            return true
        }
        val match = audioTracks().firstOrNull { LanguageMatch.matches(language, it.language) }
            ?: return false
        runCatching { mpv.setPropertyInt("aid", match.id) }
        return true
    }

    /**
     * Selects the first subtitle track in [language], or turns subtitles OFF
     * when the file carries none: a language preference must never silently
     * show the wrong track, which is what the main player does as well. Blank
     * hands the choice back to mpv's `slang`.
     */
    fun selectSubtitleLanguage(language: String): Boolean {
        if (!initialized) return false
        if (language.isBlank()) {
            runCatching { mpv.setPropertyString("sid", "auto") }
            return true
        }
        val match = subtitleTracks().firstOrNull { LanguageMatch.matches(language, it.language) }
        if (match == null) {
            clearSubtitles()
            return false
        }
        runCatching { mpv.setPropertyInt("sid", match.id) }
        return true
    }

    /** Picks one specific audio track, for the panel's "this file" list. */
    fun selectAudioTrack(id: Int) {
        if (!initialized) return
        runCatching { mpv.setPropertyInt("aid", id) }
    }

    fun selectSubtitleTrack(id: Int) {
        if (!initialized) return
        runCatching { mpv.setPropertyInt("sid", id) }
    }

    fun clearSubtitles() {
        if (!initialized) return
        runCatching { mpv.setPropertyString("sid", "no") }
    }

    /** True while a subtitle track is selected, for the panel's state. */
    fun subtitlesOn(): Boolean = subtitleTracks().any { it.selected }

    /** Signature of the audio track playing now, for the per-title memory. */
    fun selectedAudioSignature(): String =
        audioTracks().firstOrNull { it.selected }?.signature.orEmpty()

    /**
     * Applies a track signature remembered for this title
     * ("language|codec|channels"), falling back to language + channel count.
     * False when neither matches: the stored track may simply not be in this
     * episode's file, and picking a different one is worse than leaving mpv's
     * own choice alone.
     */
    fun applyRememberedAudioTrack(signature: String): Boolean {
        if (!initialized || signature.isBlank()) return false
        val tracks = audioTracks()
        val parts = signature.split('|')
        val language = parts.getOrNull(0).orEmpty()
        val channels = parts.getOrNull(2)?.toIntOrNull() ?: 0
        val match = tracks.firstOrNull { it.signature == signature }
            ?: tracks.firstOrNull {
                LanguageMatch.matches(language, it.language) &&
                    (channels <= 0 || it.channels == channels)
            }
            ?: return false
        runCatching { mpv.setPropertyInt("aid", match.id) }
        return true
    }

    /**
     * Aspect ratio, by the main player's own mode list (Fit / Zoom / Fill /
     * 16:9 / 4:3), so the button and the panel read the same in both engines.
     */
    fun setAspectMode(index: Int) {
        if (!initialized) return
        runCatching {
            when (index) {
                // Zoom: keep the ratio, fill the frame, crop the overflow.
                1 -> {
                    mpv.setPropertyString("video-aspect-override", "no")
                    mpv.setPropertyBoolean("keepaspect", true)
                    mpv.setPropertyDouble("panscan", 1.0)
                }
                // Fill: stretch to the screen, ratio be damned.
                2 -> {
                    mpv.setPropertyString("video-aspect-override", "no")
                    mpv.setPropertyBoolean("keepaspect", false)
                    mpv.setPropertyDouble("panscan", 0.0)
                }
                // Forced ratios, for streams whose flagged size is wrong.
                3 -> forceAspect("16:9")
                4 -> forceAspect("4:3")
                else -> {
                    mpv.setPropertyString("video-aspect-override", "no")
                    mpv.setPropertyBoolean("keepaspect", true)
                    mpv.setPropertyDouble("panscan", 0.0)
                }
            }
        }
    }

    private fun forceAspect(ratio: String) {
        mpv.setPropertyBoolean("keepaspect", true)
        mpv.setPropertyDouble("panscan", 0.0)
        mpv.setPropertyString("video-aspect-override", ratio)
    }

    /**
     * What mpv is playing and how, for the info button. Empty before the
     * instance exists: reading a property off a handle that is not there yet is
     * a native call, not a Kotlin one, and runCatching cannot catch that.
     */
    fun diagnostics(): String {
        if (!initialized) return ""
        val width = getPropertyIntOrNull("video-params/w") ?: 0
        val height = getPropertyIntOrNull("video-params/h") ?: 0
        val video = getPropertyStringOrNull("video-format")?.uppercase() ?: "AUDIO ONLY"
        val hwdec = getPropertyStringOrNull("hwdec-current") ?: "none"
        val audio = getPropertyStringOrNull("audio-codec")?.uppercase() ?: "—"
        return buildString {
            if (width > 0 && height > 0) append("${width}×$height  •  ")
            append(video)
            append("  •  decode: $hwdec")
            append("  •  audio: $audio")
        }
    }

    /**
     * Subtitle size / background / position, the app's three-step controls.
     * Text tracks only: an ASS track keeps the styling its author shipped (see
     * [applyOptions]).
     */
    fun applySubtitleAppearance(size: Int, background: Int, position: Int) {
        // --sub-font-size is mpv's own scale (55 is its default), so the three
        // steps are that +/-20%.
        val font = when (size) {
            0 -> 44.0
            2 -> 66.0
            else -> 55.0
        }
        styleOption("sub-font-size", font.toString())
        when (background) {
            // Semi and Solid are a fill behind the text, with no outline.
            1 -> {
                styleOption("sub-back-color", "#80000000")
                styleOption("sub-border-size", "0")
            }
            2 -> {
                styleOption("sub-back-color", "#FF000000")
                styleOption("sub-border-size", "0")
            }
            // Text: outlined text with no fill (the old DVD look).
            3 -> {
                styleOption("sub-back-color", "#00000000")
                styleOption("sub-border-size", "2.4")
            }
            // None: no fill and no outline, just the shadow mpv already draws.
            else -> {
                styleOption("sub-back-color", "#00000000")
                styleOption("sub-border-size", "0")
            }
        }
        // 100 is mpv's bottom; the app's low / mid / high are those bands.
        styleOption(
            "sub-pos",
            when (position) {
                1 -> "72"
                2 -> "50"
                else -> "100"
            }
        )
    }

    /**
     * Sets a per-load mpv option, falling back to a runtime property when the
     * option form is refused.
     *
     * `setOptionString` reports an mpv error code instead of throwing, so a
     * refusal used to be entirely silent - headers that never reached mpv
     * looked exactly like headers the host rejected, and both look like "this
     * stream could not be played". A refusal is at least named in the log; the
     * VALUE is not, because a header field can carry a Cookie or a token.
     */
    private fun setStreamOption(name: String, value: String) {
        val code = runCatching { mpv.setOptionString(name, value) }.getOrDefault(-1)
        if (code == 0) return
        runCatching { mpv.command(arrayOf("set", name, value)) }
        Log.w(TAG, "mpv refused the $name option (code $code); set it as a property instead")
    }

    /** A subtitle style option before init, the same thing as a property after. */
    private fun styleOption(name: String, value: String) {
        if (initialized) {
            runCatching { mpv.setPropertyString(name, value) }
        } else {
            runCatching { mpv.setOptionString(name, value) }
        }
    }

    // --- mpv configuration --------------------------------------------------

    /**
     * Stages the bundled Mozilla CA roots for mpv and returns their file path,
     * or null when they cannot be staged.
     *
     * libmpv on Android has no access to the system trust store, so with
     * `tls-verify=yes` and ZERO trust roots EVERY https handshake fails with
     * "unable to get local issuer certificate" - and every source this app
     * plays is an https debrid link, so switching verification on without a CA
     * bundle broke all playback. mpv's own default (no `tls-verify`) is to not
     * check, which is why the explicit option was the whole cause.
     *
     * The roots ship in assets (see `app/src/main/assets/cacert.pem`) and are
     * copied to a real file because mpv's `tls-ca-file` needs a filesystem
     * path. The copy is refreshed when the asset's size differs, so a newer
     * build's bundle replaces a stale copy on disk.
     */
    private fun prepareTlsCaFile(): String? = runCatching {
        val dest = File(context.filesDir, CA_FILE_NAME)
        val assetBytes = context.assets.open(CA_ASSET_NAME).use { it.readBytes() }
        if (!dest.isFile || dest.length() != assetBytes.size.toLong()) {
            dest.writeBytes(assetBytes)
        }
        dest.absolutePath.takeIf { dest.isFile && dest.length() > 0L }
    }.onFailure {
        Log.w(TAG, "CA roots could not be staged from assets", it)
    }.getOrNull()

    private fun applyOptions() {
        // No user config: this is a fallback engine, and an mpv.conf picked up
        // from the device would change behavior between boxes.
        mpv.setOptionString("config", "no")
        mpv.setOptionString("terminal", "no")
        mpv.setOptionString("ytdl", "no")
        // mpv's own UI is off: these controls are the app's.
        mpv.setOptionString("osc", "no")
        mpv.setOptionString("osd-level", "0")
        mpv.setOptionString("input-default-bindings", "no")
        mpv.setOptionString("input-vo-keyboard", "no")

        // Android video output. gpu (not gpu-next) is the one the reference
        // Android player ships as default and the one this native build is
        // packaged for.
        mpv.setOptionString("vo", "gpu")
        mpv.setOptionString("gpu-context", "android")
        mpv.setOptionString("opengl-es", "yes")

        // Scrub previews screenshot the frame this engine is already showing
        // (see MpvScrubPreviews), so a capture is the file's own resolution and
        // is written and deleted per preview: JPEG at a modest quality rather
        // than the default PNG, which on a 4K source is tens of megabytes of
        // flash per press for an image the card draws 480px wide.
        mpv.setOptionString("screenshot-format", "jpg")
        mpv.setOptionString("screenshot-jpeg-quality", "70")

        // Decoding: try MediaCodec zero-copy, then copy-back, then software.
        // The trailing `no` is the whole point of this engine — a file the
        // box's decoders cannot give us still plays.
        mpv.setOptionString("hwdec", HWDEC_HW)
        mpv.setOptionString("hwdec-codecs", HWDEC_CODECS)

        // Audio: MediaPlayer-style role so a TV or receiver treats this like
        // any other media session.
        mpv.setOptionString("ao", "audiotrack,opensles")
        mpv.setOptionString("audio-set-media-role", "yes")

        // Networking. A dead host should fail in half a minute instead of
        // sitting on a spinner, and TLS is verified against the BUNDLED Mozilla
        // roots: libmpv cannot read Android's trust store, so `tls-verify=yes`
        // with no `tls-ca-file` makes every https handshake fail with
        // "unable to get local issuer certificate" (see [prepareTlsCaFile]).
        // When the roots cannot be staged, verification is turned OFF instead -
        // unverified playback beats no playback.
        val caFile = prepareTlsCaFile()
        if (caFile != null) {
            mpv.setOptionString("tls-ca-file", caFile)
            mpv.setOptionString("tls-verify", "yes")
        } else {
            Log.w(TAG, "no CA bundle available; disabling mpv TLS verification")
            mpv.setOptionString("tls-verify", "no")
        }
        mpv.setOptionString("network-timeout", "30")

        // The identity the app plays as. mpv asks as `mpv/<version>` by
        // default, which the hosts this app streams from routinely refuse -
        // and because the ExoPlayer path sends the browser agent below, the
        // same stream played in the main player and failed here, presenting as
        // "this stream could not be played" on every press of Switch Player.
        // A source that names its own agent overrides this per load.
        mpv.setOptionString("user-agent", StreamUserAgent.DEFAULT)

        // Caching: mpv's defaults are sized for a desktop; 64 MB matches what
        // the reference Android player uses. Low Latency (the panel's own
        // Network buffer row) trades that for a small read-ahead instead.
        applyCacheOptions(bufferMode)

        // Playback shape. keep-open holds the last frame at EOF so the
        // activity can offer the next episode; save-position-on-quit is off
        // because watch history is the app's job, not mpv's watch_later files.
        mpv.setOptionString("keep-open", "yes")
        mpv.setOptionString("idle", "yes")
        mpv.setOptionString("force-window", "no")
        mpv.setOptionString("save-position-on-quit", "no")

        // Preferred languages, so a multi-audio/multi-subtitle file opens on
        // the right tracks without a press: what the session asked for (a
        // title's remembered choice, which [setLanguagePreferences] resolves),
        // else the global Settings preference.
        val wantedAudio = audioLanguage.ifBlank {
            preferredLanguage(AppPreferences.getPreferredAudioLanguage(context)).orEmpty()
        }
        if (wantedAudio.isNotBlank()) mpv.setOptionString("alang", wantedAudio)
        val wantedSubtitles = subtitleLanguage.ifBlank {
            preferredLanguage(AppPreferences.getPreferredSubtitleLanguage(context)).orEmpty()
        }
        if (wantedSubtitles.isNotBlank()) mpv.setOptionString("slang", wantedSubtitles)

        // Subtitle look, from the same global defaults the main player's
        // settings pane edits.
        applySubtitleAppearance(
            size = AppPreferences.getDefaultSubtitleSize(context),
            background = AppPreferences.getDefaultSubtitleBackground(context),
            position = AppPreferences.getDefaultSubtitlePosition(context)
        )

        // ASS/SSA styling is deliberately left alone: none of the options that
        // would let libass be overridden is set, which is what keeps a fansub's
        // typesetting intact. The look controls above apply to text tracks;
        // an ASS track keeps the styling its author shipped.
    }

    /**
     * The cache sizing for [bufferMode]. `cache` is a per-file option, so this
     * is what the panel's Network buffer row changes for the next title.
     *
     * Before initialize() these have to go in as OPTIONS (a property write on a
     * handle mpv has not created yet is a native call, not a Kotlin one);
     * afterwards the same values are properties.
     */
    private fun applyCacheOptions(mode: Int) {
        val lowLatency = mode == 1
        val maxBytes = if (lowLatency) LOW_LATENCY_MAX_BYTES else CACHE_MB * 1024 * 1024
        val backBytes = if (lowLatency) 0 else CACHE_MB * 1024 * 1024
        runCatching {
            if (initialized) {
                mpv.setPropertyBoolean("cache", !lowLatency)
                mpv.setPropertyInt("demuxer-max-bytes", maxBytes)
                mpv.setPropertyInt("demuxer-max-back-bytes", backBytes)
                if (lowLatency) mpv.setPropertyInt("demuxer-readahead-secs", 0)
            } else {
                mpv.setOptionString("cache", if (lowLatency) "no" else "yes")
                mpv.setOptionString("demuxer-max-bytes", maxBytes.toString())
                mpv.setOptionString("demuxer-max-back-bytes", backBytes.toString())
                if (lowLatency) mpv.setOptionString("demuxer-readahead-secs", "0")
            }
        }.onFailure { Log.w(TAG, "cache profile $mode rejected", it) }
    }

    /**
     * Channel count of the audio mpv is decoding right now, which is what the
     * dialogue filter's spec has to be built for.
     */
    private fun audioChannelCount(): Int =
        getPropertyIntOrNull("audio-params/channel-count") ?: 0

    /**
     * Pushes the dialogue spec for the current layout, or clears it when the
     * boost is off / the layout is one we have no spec for. Reads the property
     * back afterwards: a spec mpv will not accept is worth a log line, and the
     * value it settled on is the honest one to report.
     */
    private fun applyDialogueFilter() {
        val spec = dialogueFilterSpec(dialogueBoost, audioChannelCount())
        runCatching { mpv.setPropertyString("af", spec.orEmpty()) }
            .onFailure { Log.w(TAG, "af rejected: $spec", it) }
        if (spec != null) {
            val applied = getPropertyStringOrNull("af").orEmpty()
            if (applied.isBlank()) {
                Log.w(TAG, "mpv did not take the dialogue filter: $spec")
            } else {
                Log.i(TAG, "dialogue filter = $applied (" + spec + ")")
            }
        }
    }

    /**
     * The `pan` spec for [level] at [channels], or null for "no filter".
     *
     * Only the center channel is lifted on a multichannel mix, and the mid
     * component on a stereo one: `mid = (L+R)/2` is where a 2.0 track keeps its
     * voices while music beds sit in `(L-R)/2`, so lifting mid raises dialogue
     * without dragging the whole mix up - exactly what [PlayerAudioTuning.midGain]
     * and [PlayerAudioTuning.inPlaceCenterGain] express on the other engine.
     *
     * Channels are named by index (`c2` is the center in every standard
     * layout), which is what keeps this independent of whether the file
     * declares 5.1 or 5.1(side).
     */
    private fun dialogueFilterSpec(level: Int, channels: Int): String? {
        if (level <= 0) return null
        val gain = 1f + 0.35f * level
        fun gainText(value: Float): String = String.format(Locale.US, "%.4f", value)
        return when (channels) {
            // Mono has no center to lift and no second channel to fold
            // against: the whole track is already the dialogue.
            1 -> null
            2 -> {
                val same = gainText((gain + 1f) / 2f)
                val cross = gainText((gain - 1f) / 2f)
                "pan=stereo|c0=$same*c0+$cross*c1|c1=$cross*c0+$same*c1"
            }
            6 -> passThroughWithCenterLift("5.1", 6, gainText(gain))
            8 -> passThroughWithCenterLift("7.1", 8, gainText(gain))
            else -> null
        }
    }

    /** [count] channels of the [layout], all passed through bar the center. */
    private fun passThroughWithCenterLift(layout: String, count: Int, gain: String): String =
        buildString {
            append("pan=").append(layout)
            for (index in 0 until count) {
                append("|c").append(index).append('=')
                if (index == CENTER_CHANNEL_INDEX) append(gain).append('*')
                append('c').append(index)
            }
        }

    private fun observeProperties() {
        mpv.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("duration/full", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("media-title", MPVLib.MpvFormat.MPV_FORMAT_STRING)
        // Bare observation (no format): we only need to know it changed, then
        // read the parts we care about through mpv's property paths.
        mpv.observeProperty("track-list", MPVLib.MpvFormat.MPV_FORMAT_NONE)
        // The dialogue filter is built for the channel count, so it has to be
        // rebuilt whenever a new file (or another track) brings a different
        // one.
        mpv.observeProperty("audio-params/channel-count", MPVLib.MpvFormat.MPV_FORMAT_INT64)
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
        runCatching { mpv.setPropertyString("android-surface-size", "${width}x$height") }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (!initialized || released) return
        // Order matters: stop the renderer, drop forced rendering, and only
        // then let the surface go.
        runCatching {
            mpv.setPropertyString("vo", "null")
            mpv.setPropertyString("force-window", "no")
            mpv.detachSurface()
        }
    }

    private fun attachSurface() {
        val surface = holder.surface ?: return
        runCatching {
            mpv.attachSurface(surface)
            // Forces mpv to render video/subtitles into our surface even when
            // it would otherwise decide it has no window to draw into.
            mpv.setOptionString("force-window", "yes")
        }.onFailure { Log.e(TAG, "could not attach the MPV surface", it) }
    }

    private fun startPendingLoad() {
        val request = pendingLoad ?: return
        pendingLoad = null
        fileLoaded = false
        endNotified = false
        durationSec = null
        cacheStall = false
        // The previous load's mpv errors say nothing about this one, and
        // leaving one behind would name the wrong reason for a later failure.
        lastErrorLine = null
        recentErrorLines.clear()
        // The playhead is published from mpv itself from here on.
        lastPositionMs = 0L
        lastDurationMs = 0L
        pendingSeekMs = request.startPositionMs.coerceAtLeast(0L)
        lastLoadHost = runCatching {
            android.net.Uri.parse(request.url).host
        }.getOrNull()

        // The source's own User-Agent if it named one, else the app's - never
        // mpv's default. Set through the option AND as a header field, because
        // a CDN may read whichever of the two it was written against.
        val userAgent = StreamUserAgent.resolve(request.headers)
        setStreamOption("user-agent", userAgent)
        val fields = buildList {
            add("User-Agent" to userAgent)
            request.headers.forEach { (key, value) ->
                if (!key.equals("User-Agent", ignoreCase = true)) add(key to value)
            }
        }.joinToString(",") { (key, value) ->
            // mpv reads a comma-separated field list here; commas inside a
            // value are what would split one field into two, so they are
            // replaced rather than allowed to corrupt the rest of the list.
            "$key: ${value.replace(',', ';')}"
        }
        setStreamOption("http-header-fields", fields)
        // Resume position: applied by the demuxer at open time, so playback
        // starts there instead of seeking after a flash of the opening frames.
        mpv.setOptionString("start", "${request.startPositionMs.coerceAtLeast(0L) / 1000.0}")

        runCatching { mpv.command(arrayOf("loadfile", request.url, "replace")) }
            .onFailure { Log.e(TAG, "loadfile failed", it) }

        // Adaptive split stream: the video URL carries no audio, so the
        // separate track is added as an additional audio file.
        request.audioUrl?.takeIf { it.isNotBlank() }?.let { audioUrl ->
            runCatching { mpv.command(arrayOf("audio-add", audioUrl)) }
                .onFailure { Log.w(TAG, "audio-add failed", it) }
        }
    }

    // --- mpv events ---------------------------------------------------------

    override fun event(eventId: Int) {
        when (eventId) {
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                fileLoaded = true
                applyPendingSeek()
                // The container's rate is known from here, so a panel switch
                // happens before the first frame rather than over it.
                publishVideoFrameRate()
                val title = getPropertyStringOrNull("media-title")
                Log.i(
                    TAG,
                    "MPV file loaded at ${lastPositionMs}ms: ${loadedFileDiagnostics()}"
                )
                post { onFileLoaded?.invoke(title) }
            }

            MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                // A file that never opened is a failure; one that reached its
                // end is handled by eof-reached (keep-open pauses there).
                if (!fileLoaded) {
                    // mpv's own words, when it had any: "HTTP error 403",
                    // "Protocol not found" and a refused connection are the
                    // same sentence on screen and want different fixes.
                    val reason = lastErrorLine?.takeIf { it.isNotBlank() }
                    val failureDetail = MpvErrorReason.failureDetail(reason, lastLoadHost)
                    Log.w(TAG, "MPV could not open the stream: $failureDetail")
                    // Same report line the ExoPlayer ladder writes to: an MPV
                    // open that fails on a 403 or a refused connection is a
                    // playback failure the diagnostics dump has to carry, and
                    // this is the one place that knows mpv's own reason (see
                    // PlaybackEngineTrace).
                    com.kennyb1201.kbstream.data.reporting.PlaybackEngineTrace.note(
                        com.kennyb1201.kbstream.data.reporting.PlaybackEngineTrace.describe(
                            cause = "MPV could not open the stream",
                            detail = failureDetail
                        )
                    )
                    post {
                        onPlaybackError?.invoke(
                            when (reason) {
                                null -> "This stream could not be played."
                                else -> "This stream could not be played. $reason"
                            }
                        )
                    }
                }
            }

            MPVLib.MpvEvent.MPV_EVENT_VIDEO_RECONFIG -> {
                Log.i(TAG, "MPV video reconfigured: ${loadedFileDiagnostics()}")
                // Second chance: a stream whose headers carried no rate reports
                // one once the first frames have been decoded.
                publishVideoFrameRate()
            }
        }
    }

    override fun eventProperty(property: String) = Unit

    /**
     * mpv's log, kept at error and warning level.
     *
     * The one channel that says WHY a stream would not open. The library
     * requests log messages for its clients; anything below a warning is noise
     * here (and, on a stream host that dislikes the agent, an unreadable
     * flood). Warnings are kept too, because FFmpeg does not always put the
     * cause of a failed open on an error line - and the ring is read only when
     * the load failed, so a successful session's warnings are never consumed.
     */
    override fun logMessage(prefix: String, level: Int, text: String) {
        // WARN as well as ERROR: FFmpeg does not always put the cause of a
        // failed open on an ERROR line (a refused connection, an unreadable
        // header). The ring is cleared at each load and read only when that
        // load FAILED, so a successful session's warnings never reach the
        // notice; and MpvErrorReason.pick still prefers a named cause over a
        // later warning.
        if (level > MPVLib.MpvLogLevel.MPV_LOG_LEVEL_WARN) return

        val line = MpvErrorReason.format(prefix, text)
        if (line.isEmpty()) return

        recentErrorLines.addLast(line)
        while (recentErrorLines.size > MpvErrorReason.MAX_KEPT) {
            recentErrorLines.removeFirst()
        }

        // Resolved across the whole burst, not from this line: FFmpeg logs the
        // cause first and its generic "Failed to open" second (see
        // MpvErrorReason), so the last line on its own is the useless one.
        lastErrorLine = MpvErrorReason.pick(recentErrorLines)

        Log.w(TAG, "mpv: $line")
    }

    override fun eventProperty(property: String, value: Long) {
        when (property) {
            // The file's layout is known from here on: place the dialogue
            // filter against the track actually playing.
            "audio-params/channel-count" -> if (dialogueBoost > 0) applyDialogueFilter()
        }
    }

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
            mpv.command(arrayOf("seek", (resume / 1000.0).toString(), "absolute+keyframes"))
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
                channels = getPropertyIntOrNull("track-list/$index/audio-channels"),
                selected = getPropertyBooleanOrNull("track-list/$index/selected") == true
            )
        }
        return tracks
    }

    private fun getPropertyIntOrNull(name: String): Int? =
        runCatching { mpv.getPropertyInt(name) }.getOrNull()

    private fun getPropertyStringOrNull(name: String): String? =
        runCatching { mpv.getPropertyString(name) }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun getPropertyBooleanOrNull(name: String): Boolean? =
        runCatching { mpv.getPropertyBoolean(name) }.getOrNull()

    private fun getPropertyDoubleOrNull(name: String): Double? =
        runCatching { mpv.getPropertyDouble(name) }.getOrNull()

    /**
     * The video track's frame rate in fps, or null when mpv has reported none
     * (an audio-only file, or headers that have not been parsed yet).
     *
     * Three names are tried because they answer slightly different questions
     * and not every build exposes all of them: `container-fps` is what the
     * container declares, `video-params/container-fps` is the same figure read
     * off the video parameter tree, and `estimated-vf-fps` is what mpv has
     * measured while decoding. For ordinary content all three agree, which is
     * all a display match needs.
     */
    fun videoFrameRate(): Double? =
        getPropertyDoubleOrNull("container-fps")
            ?: getPropertyDoubleOrNull("video-params/container-fps")
            ?: getPropertyDoubleOrNull("estimated-vf-fps")

    /** Offers [videoFrameRate] to the observer, when it has a value yet. */
    private fun publishVideoFrameRate() {
        videoFrameRate()?.let { fps -> post { onVideoFrameRateChanged?.invoke(fps) } }
    }

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

        /**
         * Mozilla's CA roots, shipped in assets and handed to mpv as a file.
         * See [prepareTlsCaFile] for why. Refresh the bundle from
         * https://curl.se/ca/cacert.pem every few months, as CAs rotate.
         */
        const val CA_ASSET_NAME = "cacert.pem"
        const val CA_FILE_NAME = "cacert.pem"

        /** MediaCodec first, copy-back second, then software decoding. */
        const val HWDEC_HW = "mediacodec,mediacodec-copy,no"
        const val HWDEC_SW = "no"
        const val HWDEC_CODECS = "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1"

        const val CACHE_MB = 64

        /** Read-ahead cap in Low Latency mode: enough to bridge a hiccup. */
        const val LOW_LATENCY_MAX_BYTES = 8 * 1024 * 1024

        /** mpv's own 100 = unity gain. */
        const val VOLUME_NORMAL = 100.0

        /**
         * The ceiling `volume` is clipped at. Every step of the panel's boost
         * is above mpv's default 130, so this has to be raised with the gain.
         */
        const val VOLUME_MAX = 800.0

        /** FL, FR, FC, ... - the center is channel 3 in every standard layout. */
        const val CENTER_CHANNEL_INDEX = 2
    }
}
