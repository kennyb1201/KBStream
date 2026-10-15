package com.kennyb1201.kbstream.ui.player

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.kennyb1201.kbstream.data.player.LanguageMatch
import com.kennyb1201.kbstream.data.player.StreamUserAgent
import com.kennyb1201.kbstream.data.reporting.Redaction
import com.kennyb1201.kbstream.data.settings.AppPreferences
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
 *    pre-initialize; afterwards the same call is a runtime property change),
 *    except the pair the reference player sets AFTER it — `force-window=no`
 *    and `idle=once` — which land between init() and the holder callback so
 *    that nothing can force a window before a surface exists and mpv cannot
 *    quit before the first loadfile arrives;
 *  - property observers are registered before init() too, matching the
 *    reference mpv-android player;
 *  - the video surface is attached from the SurfaceHolder callback — and, when
 *    the holder already has a live surface by the time [initialize] registers
 *    it, from [ensureSurfaceAttachedIfAlreadyAvailable], because a holder does
 *    not replay a create that already happened (NuvioTV's engine covers the
 *    same case the same way) — and every attach publishes the window's size as
 *    mpv's `android-surface-size` before `force-window=yes` can create the
 *    output, because the size update the holder would otherwise send never
 *    arrives on that path either (see [publishAttachedSurfaceSize]) — and the
 *    view's own layout publishes the same property for the one attach that can
 *    reach the output with no size at all ([onSizeChanged]) — and the
 *    video output is cycled with it in the reference player's order
 *    (`BaseMPVView` in mpv-android, the view Nuvio's working engine is built
 *    on): attach the surface, publish the size and `force-window=yes`, then
 *    either load the pending file (a FIRST creation, which is what
 *    [surfaceCreatedOnce] says — not whether a load is waiting, because the
 *    surface arrives before [load] does here — and the output is already
 *    configured as the option, so it is left alone) or `vo=<resolved output>`
 *    (recreation) — and on teardown `vo=null`, `force-window=no`,
 *    detachSurface, in that order. `vo=null` tears the output down while its
 *    surface still exists, and the property set on the next attach is what
 *    re-initializes the output against the new one; with neither, mpv keeps
 *    decoding while every frame goes to a surface that is gone — audio over a
 *    black screen, the frame count still climbing (see [surfaceCreated] and
 *    [surfaceDestroyed]). The `vo` OPTION in [applyOptions] is still what
 *    chooses the output, once, before init();
 *  - loadfile() is deferred until the surface is ATTACHED ([surfaceAttached]),
 *    not merely until the holder has one: a load that runs while mpv has no
 *    `wid` leaves mpv with a video track it never gets an output for, which on
 *    this stack is audio over a black screen with the decoder still running
 *    (`Both surface and native_window are NULL` — see [load]).
 *
 * mpv's callbacks arrive on its own event thread. Everything this class
 * forwards is posted to the main thread, because every listener updates views.
 */
class MpvPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback,
    MpvEventObserver, MpvLogObserver {

    /*
     * The surface's place in the window's composition, raised at construction.
     *
     * It has to be HERE, not in [initialize]: `setZOrderMediaOverlay` is read
     * when the Surface is created, and this view is inflated with the Activity
     * - its surface can be up before [initialize] is even called, which is the
     * case that method covers with [ensureSurfaceAttachedIfAlreadyAvailable].
     * A call made after the create is a no-op.
     *
     * Why the flag at all. The Fire TV report is a picture the engine fully
     * believes it is showing - mpv holds the window (`wid` non-zero), the
     * GPU-backed output is open, frames are decoded and reported "shown" - with
     * nothing on the panel, and it is black with software decoding too, so
     * neither the decoder nor hwdec is the variable. What is left is where the
     * surface lands: at the DEFAULT z-order this view's surface goes in the
     * window's behind-the-window `MEDIA` layer, under a window that paints
     * opaque black edge to edge here (`mpv_root`, the load splash and the chrome
     * all do). `setZOrderMediaOverlay(true)` moves it to the `MEDIA_OVERLAY`
     * layer - above the window's own media surfaces, still BEHIND the window
     * itself - so the splash and the chrome keep painting over the picture.
     * That is the point of this one and not `setZOrderOnTop`, which would put
     * the video above this app's own controls instead.
     */
    init {
        setZOrderMediaOverlay(true)
    }

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
     * A stream that HAD loaded ended well short of its end (see
     * [StreamEndRules.Verdict.DIED_MID_STREAM]).
     *
     * Deliberately separate from [onPlaybackError], which is the OPEN-failure
     * path: that one means this source's link is what failed, so the Activity
     * demotes the add-on and walks to the next source. A stream that played and
     * then died says nothing about the link - it is a dropped segment or an
     * exhausted reconnect - and must not cost a working add-on its place in the
     * ranked list.
     */
    var onStreamDied: ((String) -> Unit)? = null

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
     * True after the FIRST [surfaceCreated], which is what separates the first
     * attach from a recreation.
     *
     * Deliberately not `pendingLoad != null`. This view is created with the
     * Activity, so its surface arrives during startup and `load()` — the only
     * writer of [pendingLoad] — runs after it; on a first creation the pending
     * load is therefore still null, and a test on it reads first creation as
     * recreation. That is the `Set property: vo="gpu"` before `start-file` in
     * the field log of build 0.6.71 (sha c350dced).
     *
     * Not reset by [release()]: release is one-way — the Activity never
     * re-initializes a released view — and a NEW MpvPlayerView starts with this
     * flag false.
     */
    private var surfaceCreatedOnce = false

    /**
     * True while mpv holds this view's surface: [attachSurface] has run and no
     * [surfaceDestroyed] (or [release]) has taken it back.
     *
     * This, not `holder.surface.isValid`, is what [load] waits on. `isValid`
     * describes the Surface; it says nothing about whether mpv was ever handed
     * it, and the two come apart exactly when the attach is skipped — which is
     * what a holder that does not replay `surfaceCreated` produces. A load that
     * runs then opens the file against an mpv with `wid` still 0, and on the
     * `vo=mediacodec_embed` path mpv passes FFmpeg a null surface (`Both
     * surface and native_window are NULL`): frames decode (`Got First Frame
     * Ready`), nothing reaches the screen.
     */
    private var surfaceAttached = false

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
     * True when the load now in flight is REPLACING a file that had loaded.
     *
     * Set by [startPendingLoad] from [fileLoaded] before it clears it, and
     * cleared when the replacement loads. It is what separates "the file we
     * asked to unload ended" (our own `loadfile replace`, no failure) from "the
     * file we asked for never opened" (a real open failure) - both arrive as
     * END_FILE with `fileLoaded` false, which is why the old handler read a
     * source switch as an unplayable source.
     */
    private var replacingPreviousFile = false

    /**
     * True once [stopPlayback] has issued mpv's own `stop`, for an engine
     * handoff. Ends a LOADED file, so it is the second thing that has to read as
     * "we asked for this" rather than "the stream died".
     */
    private var stoppingDeliberately = false

    /**
     * mpv's own last error-level line, if it has said anything.
     *
     * A failed load reports as "this stream could not be played" and nothing
     * else: mpv knows whether that was a 403, an unsupported protocol or a
     * refused connection, and the only place it says so is its log. Kept so the
     * notice a viewer sees can name the actual reason instead of guessing.
     */
    // Volatile: written from the mpv event thread (logMessage) and the main
    // thread (startPendingLoad), read on the main thread when a failure notice
    // is built.
    @Volatile
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

    /**
     * Serialises [recentErrorLines]: mpv's log callback APPENDS on the event
     * thread while [startPendingLoad] CLEARS on the main thread, and a
     * concurrent head/tail mutation corrupts the deque — an
     * ArrayIndexOutOfBoundsException on mpv's event thread kills the process.
     */
    private val recentErrorLinesLock = Any()

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
     * Off / forced only / on, from Settings (see [SubtitleModeRules]). Applied
     * at open time: Off opens with subtitles disabled, Forced asks mpv for
     * forced (signs / foreign-dialogue) cues only, and On follows
     * [subtitleLanguage] through `slang`.
     */
    private var subtitleMode = SubtitleModeRules.DEFAULT

    /*
     * Every call below goes through [MpvBackend], the one file that names the
     * MPV library this app links - see `MpvEngineApi.kt`. There is deliberately
     * no handle field and no accessor in this class: the library's `create()`
     * returns Unit against a singleton, and the seam keeps exactly that shape.
     * Lifetime is the same either way - [initialize] creates and [release]
     * destroys, and the `initialized`/`released` guards keep a call off an
     * engine that is not up.
     */

    /**
     * Creates the mpv instance. Returns false when the engine is unavailable
     * here (native libraries missing, or a device below the library's floor —
     * [com.kennyb1201.kbstream.data.player.PlayerEngine] checks the API level
     * before we get called).
     */
    fun initialize(): Boolean {
        if (initialized) return true
        try {
            MpvBackend.create(context.applicationContext)
        } catch (t: Throwable) {
            Log.e(TAG, "libmpv could not be loaded", t)
            onEngineFailed?.invoke("The MPV engine could not start on this device.")
            return false
        }

        // applyOptions(), init() and the post-init pair run together: create()
        // above has already stood up the native instance, and if ANY of them
        // throws we must free it - otherwise every retry leaks another native
        // instance, and the box runs out of them.
        try {
            applyOptions()
            MpvBackend.init()
            // The reference player's post-init pair, in its order and its
            // place (`BaseMPVView.initialize`: init(), postInitOptions(), then
            // these two, then the holder callback). `force-window=no` because
            // forcing a window before a surface exists would mess up the video
            // output's own init, and `idle=once` so mpv stays up with nothing
            // to play until the first loadfile arrives. After init() so nothing
            // above can override them, and before the holder callback below,
            // which is the first moment a surface can appear.
            MpvBackend.setOptionString("force-window", "no")
            MpvBackend.setOptionString("idle", "once")
        } catch (t: Throwable) {
            Log.e(TAG, "libmpv could not initialize", t)
            runCatching { MpvBackend.destroy() }
            onEngineFailed?.invoke("The MPV engine could not start on this device.")
            return false
        }

        MpvBackend.addObserver(this)
        // mpv's own account of a failed open (an HTTP status, a refused
        // connection) is only ever delivered here - see [logMessage].
        MpvBackend.addLogObserver(this)
        observeProperties()
        // `initialized` FIRST, and the holder callback LAST.
        //
        // The order is load-bearing. `holder.addCallback` can deliver a
        // `surfaceCreated` synchronously when the holder already has a surface,
        // and [surfaceCreated] opens with an `initialized` guard (it has to:
        // MpvBackend would throw against an instance that is not up). Setting
        // the flag after the registration drops exactly that callback, and a
        // dropped create is a surface mpv is never handed.
        initialized = true
        // LAST, deliberately: a surface can arrive the moment this is
        // registered, and [surfaceCreated] attaches it from there (and, on a
        // recreation, re-opens the video output) - everything above has to be
        // in place by then.
        holder.addCallback(this)
        // ...and the case the callback cannot cover: a surface that is ALREADY
        // up when this runs, for which the holder delivers no create at all
        // (see the helper). NuvioTV's engine calls the same thing in the same
        // place.
        ensureSurfaceAttachedIfAlreadyAvailable()

        // Which MPV library this build linked, once per session (see the MPV
        // library note in app/build.gradle.kts), named here so a field log says
        // which binary produced it. The `vo` is on the line beside it because
        // the two go together: this library's whole point is `gpu-next` +
        // libplacebo with `-Ddovi`, so a capture has to show that the resolved
        // output is the one it was built for.
        val startupVo = videoOutput()
        Log.i(
            TAG,
            "MPV engine up · library=$MPV_LIBRARY vo=${startupVo.vo} " +
                "gpuContext=${startupVo.gpuContext ?: "none"} gpuApi=${startupVo.gpuApi ?: "none"}"
        )
        return true
    }

    /**
     * Plays [request]. Safe to call before the surface exists — the load is
     * held back until the surface is ATTACHED ([surfaceAttached]), which happens
     * in [surfaceCreated] or, for a surface the holder already had, at
     * [initialize] (see the class comment).
     */
    fun load(request: LoadRequest) {
        if (!initialized || released) return
        pendingLoad = request
        // The ATTACH, not the holder's own view of the Surface. `isValid`
        // describes the Surface and says nothing about mpv having been handed
        // it (see [surfaceAttached]); waiting on it here is what let the file
        // open against an mpv with `wid` still 0. This is the one condition
        // that makes "mpv has the surface whenever a file opens" hold on every
        // path.
        if (surfaceAttached) startPendingLoad()
    }

    /**
     * Ends the current file and gives its decoder back, WITHOUT tearing this
     * view down: the engine handoff's half of the switch (see
     * `MpvPlayerActivity.switchToExoPlayer`).
     *
     * Why it exists: mpv holds a real MediaCodec on the field boxes (the GPU
     * path is `vo=mediacodec_embed` with `--hwdec=mediacodec`, see
     * [MpvVideoOutput]), the decoder pool is one deep there, and this activity is
     * not stopped until the successor is fully up — so mpv kept decoding over
     * the successor's startup, playing two streams at once for a moment and, the
     * part that actually breaks playback, still holding the one decoder the
     * successor had to allocate. The opposite direction already releases its
     * engine first (see `NativePlayerActivity.handOffToMpv`).
     *
     * Deliberately not [release]: the Activity finishes the handoff whatever the
     * successor returns, but a released view cannot be re-initialized, and this
     * one may still be handed another file (a picker choice, a source retry) if
     * it does not.
     */
    fun stopPlayback() {
        if (!initialized) return
        stoppingDeliberately = true
        runCatching { MpvBackend.command("stop") }
            .onFailure { Log.w(TAG, "stop for the engine handoff failed", it) }
        fileLoaded = false
        endNotified = false
        cacheStall = false
        pauseState = false
        lastPositionMs = 0L
        lastDurationMs = 0L
    }

    fun release() {
        if (released) return
        released = true
        // The reference player's destroy() order: stop the surface callbacks
        // FIRST, so no attach can arrive against an mpv instance that is about
        // to be destroyed.
        holder.removeCallback(this)
        if (initialized) {
            // destroy() clears the library's own observer lists too, but these
            // explicit removals stay: harmless, and they keep this class's
            // teardown legible next to the create side above.
            runCatching { MpvBackend.removeObserver(this) }
            runCatching { MpvBackend.removeLogObserver(this) }
            // Detach the surface before destroying the instance: mpv's
            // renderer must stop using the window first.
            runCatching {
                MpvBackend.setPropertyString("vo", "null")
                MpvBackend.detachSurface()
            }
            // mpv holds no surface from here on, so a load cannot be started
            // against this instance without one.
            surfaceAttached = false
            runCatching { MpvBackend.destroy() }
        }
        initialized = false
    }

    // --- Playback control ---------------------------------------------------

    fun togglePause() {
        if (!initialized) return
        runCatching { MpvBackend.command("cycle", "pause") }
    }

    fun setPaused(paused: Boolean) {
        if (!initialized) return
        runCatching { MpvBackend.setPropertyBoolean("pause", paused) }
    }

    fun seekTo(positionMs: Long) {
        if (!initialized) return
        val seconds = (positionMs.coerceAtLeast(0L) / 1000.0)
        // keyframes, not exact: these sources are mostly remote files whose
        // index mpv has only partially built, and an exact seek on those makes
        // the demuxer decode forward from the file start (or from nothing at
        // all). Landing on the preceding keyframe of a 10s GOP is invisible.
        runCatching { MpvBackend.command("seek", seconds.toString(), "absolute+keyframes") }
        endNotified = false
    }

    fun seekBy(deltaMs: Long) {
        if (!initialized) return
        runCatching { MpvBackend.command("seek", "${deltaMs / 1000.0}", "relative+keyframes") }
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
        runCatching { MpvBackend.setPropertyString("hwdec", hwdecValue) }
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
        runCatching { MpvBackend.setPropertyString("audio-channels", layout) }
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
            MpvBackend.setPropertyDouble("volume-max", VOLUME_MAX)
            MpvBackend.setPropertyDouble("volume", percent)
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
        runCatching { MpvBackend.command("sub-add", uri, "select") }
            .onFailure { Log.w(TAG, "sub-add failed for $uri", it) }
    }

    /** Audio tracks mpv found in the file. */
    fun audioTracks(): List<Track> = tracksOfType("audio")

    /** Subtitle tracks mpv found in the file (embedded ASS/SSA included). */
    fun subtitleTracks(): List<Track> = tracksOfType("sub")

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
            MpvBackend.setPropertyString("alang", audioLanguage)
            MpvBackend.setPropertyString("slang", subtitleLanguage)
        }
    }

    /**
     * The subtitle mode for the next load (see [SubtitleModeRules]). Set before
     * [initialize] so it lands in the open-time options; applied as a runtime
     * property too, so a later change acts on the next file the way `slang`
     * does.
     */
    fun setSubtitleMode(mode: Int) {
        subtitleMode = SubtitleModeRules.normalized(mode)
        if (!initialized) return
        runCatching {
            when (subtitleMode) {
                SubtitleModeRules.OFF -> MpvBackend.setPropertyString("sid", "no")
                SubtitleModeRules.FORCED -> {
                    // Both of the other modes have something to undo: OFF left
                    // `sid` deselected, and a FORCED mode with no track selected
                    // shows nothing. "auto" is mpv's own default selection.
                    MpvBackend.setPropertyString("sid", "auto")
                    MpvBackend.setPropertyString("sub-forced-only", "yes")
                }
                else -> {
                    // ON has to undo BOTH: the forced-only flag AND OFF's
                    // `sid=no`. Restoring only the flag left subtitles
                    // deselected, so a runtime OFF -> ON came back silent while
                    // the open-time option path looked identical - the same
                    // mode, two behaviours. "auto" is mpv's default selection.
                    MpvBackend.setPropertyString("sub-forced-only", "no")
                    MpvBackend.setPropertyString("sid", "auto")
                }
            }
        }
    }

    /** Playback speed; 1.0 is normal. */
    fun setSpeed(speed: Double) {
        if (!initialized) return
        runCatching { MpvBackend.setPropertyDouble("speed", speed) }
    }

    /** Audio delay in ms (`audio-delay` is kept in seconds). */
    fun setAudioDelayMs(ms: Int) {
        if (!initialized) return
        runCatching { MpvBackend.setPropertyDouble("audio-delay", ms / 1000.0) }
    }

    /** Subtitle delay in ms (`sub-delay`). */
    fun setSubtitleDelayMs(ms: Int) {
        if (!initialized) return
        runCatching { MpvBackend.setPropertyDouble("sub-delay", ms / 1000.0) }
    }

    /**
     * Selects the first audio track in [language]; blank hands the choice back
     * to mpv's `alang`.
     *
     * When the file carries no such track, the first track not KNOWN to be the
     * wrong language is taken instead, and only a file whose every track names
     * another language leaves mpv's own choice standing (false, as before).
     * This is the same rule the ExoPlayer engine applies
     * ([LanguageMatch.fallbackTrackIndex]): an untagged track might be the one
     * asked for, while a `rus` track certainly is not.
     *
     * It matters here because mpv's own `alang` match has the identical hole -
     * a track with no language tag never matches — so on a dual-audio EN/RU
     * release the open-time pass leaves whichever track the muxer flagged
     * DEFAULT (the Russian one) playing, and the preference looks ignored. The
     * open-time fast path is deliberately untouched; this runs once the track
     * list exists, from the activity's applyRememberedTracks.
     */
    fun selectAudioLanguage(language: String): Boolean {
        if (!initialized) return false
        if (language.isBlank()) {
            runCatching { MpvBackend.setPropertyString("aid", "auto") }
            return true
        }
        val tracks = audioTracks()
        val match = tracks.firstOrNull { LanguageMatch.matches(language, it.language) }
        if (match != null) {
            runCatching { MpvBackend.setPropertyInt("aid", match.id) }
            return true
        }
        val fallback = LanguageMatch.fallbackTrackIndex(tracks.map { it.language }, language)
        if (fallback == null) {
            // Every track names a language, so nothing is knowable and mpv's
            // own choice is as good as any.
            Log.i(
                TAG,
                "no '$language' audio track in [" +
                    tracks.joinToString(", ") { it.language ?: "untagged" } +
                    "]; keeping mpv's choice"
            )
            return false
        }
        val chosen = tracks[fallback]
        runCatching { MpvBackend.setPropertyInt("aid", chosen.id) }
        Log.i(
            TAG,
            "no '$language' audio track; picked track ${chosen.id} (" +
                (chosen.language ?: "untagged") + ")"
        )
        return true
    }

    /**
     * Selects the subtitle track in [language], or turns subtitles OFF when
     * the file carries none: a language preference must never silently show
     * the wrong track, which is what the main player does as well. Blank hands
     * the choice back to mpv's `slang`.
     *
     * Among the language matches the kind is ranked before first-wins - SDH,
     * then a regular translation, then a forced / signs track - so a file
     * whose first English track is forced no longer gets forced subtitles when
     * an SDH or regular track sits later in the list. It is the same order
     * [SubtitleTrackRules.choose] applies on the ExoPlayer engine, read here
     * from the title because MPV's [Track] has no forced flag; the last
     * `firstOrNull()` preserves the old first-match behavior when no title
     * names a kind. Explicit picks ([selectSubtitleTrack]) and the blank-
     * language `auto` path are untouched.
     */
    fun selectSubtitleLanguage(language: String): Boolean {
        if (!initialized) return false
        if (language.isBlank()) {
            runCatching { MpvBackend.setPropertyString("sid", "auto") }
            return true
        }
        val matches = subtitleTracks().filter { LanguageMatch.matches(language, it.language) }
        val match = rankedSubtitleMatch(matches)
        if (match == null) {
            clearSubtitles()
            return false
        }
        runCatching { MpvBackend.setPropertyInt("sid", match.id) }
        return true
    }

    /** Picks one specific audio track, for the panel's "this file" list. */
    fun selectAudioTrack(id: Int) {
        if (!initialized) return
        runCatching { MpvBackend.setPropertyInt("aid", id) }
    }

    fun selectSubtitleTrack(id: Int) {
        if (!initialized) return
        runCatching { MpvBackend.setPropertyInt("sid", id) }
    }

    fun clearSubtitles() {
        if (!initialized) return
        runCatching { MpvBackend.setPropertyString("sid", "no") }
    }

    /** True while a subtitle track is selected, for the panel's state. */
    fun subtitlesOn(): Boolean = subtitleTracks().any { it.selected }

    /**
     * Applies a track signature remembered for this title
     * ("language|codec|channels"), falling back to language + channel count.
     * False when neither matches: the stored track may simply not be in this
     * episode's file.
     *
     * The false is a signal, not a verdict on what to play: the caller takes
     * over the choice with the language preference (see the activity's
     * applyRememberedTracks), because leaving mpv's own choice here is the
     * muxer's DEFAULT - the Russian track on a dual-audio EN/RU release.
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
        runCatching { MpvBackend.setPropertyInt("aid", match.id) }
        return true
    }

    /**
     * Applies a subtitle signature remembered for this title
     * ("language|codec|0"). Falls back to the first track in the signature's
     * language, so a track that is not in this episode's file still lands on a
     * track in the language the viewer chose. False when neither matches,
     * leaving mpv's own choice alone.
     */
    fun applyRememberedSubtitleTrack(signature: String): Boolean {
        if (!initialized || signature.isBlank()) return false
        val tracks = subtitleTracks()
        val language = signature.split('|').getOrNull(0).orEmpty()
        val match = tracks.firstOrNull { it.signature == signature }
            ?: tracks.firstOrNull { LanguageMatch.matches(language, it.language) }
            ?: return false
        runCatching { MpvBackend.setPropertyInt("sid", match.id) }
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
                    MpvBackend.setPropertyString("video-aspect-override", "no")
                    MpvBackend.setPropertyBoolean("keepaspect", true)
                    MpvBackend.setPropertyDouble("panscan", 1.0)
                }
                // Fill: stretch to the screen, ratio be damned.
                2 -> {
                    MpvBackend.setPropertyString("video-aspect-override", "no")
                    MpvBackend.setPropertyBoolean("keepaspect", false)
                    MpvBackend.setPropertyDouble("panscan", 0.0)
                }
                // Forced ratios, for streams whose flagged size is wrong.
                3 -> forceAspect("16:9")
                4 -> forceAspect("4:3")
                else -> {
                    MpvBackend.setPropertyString("video-aspect-override", "no")
                    MpvBackend.setPropertyBoolean("keepaspect", true)
                    MpvBackend.setPropertyDouble("panscan", 0.0)
                }
            }
        }
    }

    private fun forceAspect(ratio: String) {
        MpvBackend.setPropertyBoolean("keepaspect", true)
        MpvBackend.setPropertyDouble("panscan", 0.0)
        MpvBackend.setPropertyString("video-aspect-override", ratio)
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
        // mpv's own video-bitrate, in bits per second (mpv's manual). It
        // updates per keyframe and can read as 0 until the first one arrives,
        // so it is appended only once it means something. The info readout
        // named the resolution and the codec but not the rate, which is the
        // number a viewer comparing two sources for the same film is looking
        // for.
        val bitrate = getPropertyIntOrNull("video-bitrate") ?: 0
        return buildString {
            if (width > 0 && height > 0) append("${width}×$height  •  ")
            append(video)
            if (bitrate > 0) append("  •  ${bitrate / 1_000} kbps")
            append("  •  decode: $hwdec")
            append("  •  audio: $audio")
        }
    }

    /**
     * One file's technical readout, as mpv reports it: the raw material for the
     * INFO panel's VIDEO and AUDIO rows (see [fileReport] and [MpvInfoLines]).
     *
     * Every field is nullable on purpose. mpv answers "no" to a property of a
     * component the file does not have at all — an audio-only stream has no
     * `video-format`, a stream with no audio has no channel count — and the
     * panel has to tell "this file has no video" apart from "this build could
     * not ask", which is exactly the difference between the value being null
     * and the read failing. [MpvInfoLines] renders either as a dash rather than
     * as the word "null".
     */
    data class FileReport(
        /** `video-format`: mpv's own name for the codec ("h264", "hevc"). */
        val videoFormat: String?,
        /** `video-params/w`, in pixels; 0 while mpv has not decoded a frame. */
        val width: Int,
        /** `video-params/h`, in pixels; 0 while mpv has not decoded a frame. */
        val height: Int,
        /** `hwdec-current`: the decoder actually in use ("mediacodec"). */
        val hwdec: String?,
        /** `video-params/pixelformat` ("yuv420p10"). */
        val pixelFormat: String?,
        /** `video-params/primaries`: the colour space, HDR's `bt.2020`. */
        val primaries: String?,
        /** `video-params/gamma`: the transfer function, `pq` for HDR10. */
        val gamma: String?,
        /** `video-params/sig-peak`: the HDR peak, in nits. */
        val sigPeak: Double?,
        /** `video-bitrate`, bits per second; 0 until mpv has a keyframe to read. */
        val videoBitrate: Int,
        /** `audio-codec`: what mpv is decoding the audio with ("eac3"). */
        val audioCodec: String?,
        /** `audio-params/channel-count`. */
        val audioChannels: Int?,
        /** `audio-params/samplerate`, in Hz. */
        val audioSampleRate: Int?,
        /** `audio-bitrate`, bits per second; null when mpv reports none. */
        val audioBitrate: Int?,
        /**
         * The selected subtitle track's label as mpv lists it, or null when
         * nothing is selected. Read from the track list rather than from `sid`:
         * the id names the track, and the panel has to name it to the viewer.
         */
        val subtitle: String?
    )

    /**
     * Reads this file's technical properties off mpv, or null before the
     * instance exists — reading a property off a handle that is not there yet
     * is a native call, not a Kotlin one, and `runCatching` cannot catch that
     * (the same reason [diagnostics] returns empty rather than a report).
     */
    fun fileReport(): FileReport? {
        if (!initialized) return null
        val selectedSubtitle = subtitleTracks().firstOrNull { it.selected }
        return FileReport(
            videoFormat = getPropertyStringOrNull("video-format"),
            width = getPropertyIntOrNull("video-params/w") ?: 0,
            height = getPropertyIntOrNull("video-params/h") ?: 0,
            hwdec = getPropertyStringOrNull("hwdec-current"),
            pixelFormat = getPropertyStringOrNull("video-params/pixelformat"),
            primaries = getPropertyStringOrNull("video-params/primaries"),
            gamma = getPropertyStringOrNull("video-params/gamma"),
            sigPeak = getPropertyDoubleOrNull("video-params/sig-peak"),
            videoBitrate = getPropertyIntOrNull("video-bitrate") ?: 0,
            // The field-proven property first: `audio-codec` is what this
            // engine's own field logs have reported since the first MPV build,
            // with mpv's documented `audio-codec-name` as the fallback for a
            // build where the deprecated alias has been dropped.
            audioCodec = getPropertyStringOrNull("audio-codec")
                ?: getPropertyStringOrNull("audio-codec-name"),
            audioChannels = getPropertyIntOrNull("audio-params/channel-count"),
            audioSampleRate = getPropertyIntOrNull("audio-params/samplerate"),
            audioBitrate = getPropertyIntOrNull("audio-bitrate"),
            subtitle = selectedSubtitle?.label
        )
    }

    /**
     * Subtitle size / background / position, the app's three-step controls.
     * Text tracks only: an ASS track keeps the styling its author shipped (see
     * [applyOptions]).
     */
    fun applySubtitleAppearance(size: Int, background: Int, position: Int) {
        // --sub-font-size is mpv's own scale (55 is its default), so the three
        // steps are that +/-20% - the same neutral the main player's 14sp step
        // sits at, so a title does not change size when it is handed over.
        val font = when (size) {
            0 -> 44.0
            2 -> 66.0
            else -> 55.0
        }
        styleOption("sub-font-size", font.toString())
        // The three fills are the main player's three, to the byte
        // (NativePlayerActivity.renderText paints 0x80000000, 0xE5000000 and
        // 0xB3000000): the same subtitle in the same app cannot come out 90%
        // black in one engine and opaque in the other, and "Text" is a 70%
        // plate rather than the outlined-text look it used to be - the outline
        // is a DVD-era look the app never uses anywhere else.
        when (background) {
            // Semi and Solid are a fill behind the text, with no outline.
            1 -> {
                styleOption("sub-back-color", "#80000000")
                styleOption("sub-border-size", "0")
            }
            2 -> {
                styleOption("sub-back-color", "#E5000000")
                styleOption("sub-border-size", "0")
            }
            // Text: the tight glyph-hugging plate, not an outline.
            3 -> {
                styleOption("sub-back-color", "#B3000000")
                styleOption("sub-border-size", "0")
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
        val code = runCatching { MpvBackend.setOptionString(name, value) }.getOrDefault(-1)
        if (code == 0) return
        runCatching { MpvBackend.command("set", name, value) }
        Log.w(TAG, "mpv refused the $name option (code $code); set it as a property instead")
    }

    /** A subtitle style option before init, the same thing as a property after. */
    private fun styleOption(name: String, value: String) {
        if (initialized) {
            runCatching { MpvBackend.setPropertyString(name, value) }
        } else {
            runCatching { MpvBackend.setOptionString(name, value) }
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
    private fun prepareTlsCaFile(): String? {
        // Already staged this process: reuse it rather than re-reading the
        // asset on the main thread for every view that initializes.
        stagedCaPath?.let { path -> if (File(path).isFile) return path }
        return runCatching {
            val dest = File(context.filesDir, CA_FILE_NAME)
            val assetBytes = context.assets.open(CA_ASSET_NAME).use { it.readBytes() }
            if (!dest.isFile || dest.length() != assetBytes.size.toLong()) {
                dest.writeBytes(assetBytes)
            }
            dest.absolutePath.takeIf { dest.isFile && dest.length() > 0L }
        }.onFailure {
            Log.w(TAG, "CA roots could not be staged from assets", it)
        }.getOrNull()?.also { stagedCaPath = it }
    }

    /**
     * The video output this session opens with — the stored mode resolved
     * against this device (see [MpvVideoOutput]).
     *
     * Two readers, one value. [applyOptions] sets it as the OPTION before init()
     * — the session's configuration — and [surfaceCreated] re-opens it as a
     * PROPERTY when it is re-creating (no pending load), because
     * [surfaceDestroyed] nulls the output on the way out. Both are the same
     * resolution on purpose: a cycle that re-opened a different output than the
     * session configured would be a second, silent mode change.
     */
    private fun videoOutput(): MpvVideoOutput.Settings = MpvVideoOutput.resolve(
        AppPreferences.getMpvVoMode(context),
        Build.MANUFACTURER,
        Build.MODEL,
        Build.DEVICE
    )

    private fun applyOptions() {
        // No user config: this is a fallback engine, and an mpv.conf picked up
        // from the device would change behavior between boxes.
        MpvBackend.setOptionString("config", "no")
        MpvBackend.setOptionString("terminal", "no")
        MpvBackend.setOptionString("ytdl", "no")
        // mpv's own UI is off: these controls are the app's.
        MpvBackend.setOptionString("osc", "no")
        MpvBackend.setOptionString("osd-level", "0")
        MpvBackend.setOptionString("input-default-bindings", "no")
        MpvBackend.setOptionString("input-vo-keyboard", "no")

        // Playback speed over scaler quality, from mpv's own built-in profile -
        // bilinear scaling, no dithering, no downscaling correction, no sigmoid
        // upscaling, no HDR peak computation: all of it GPU work this class of
        // box cannot spare on a 4K release (the profile the reference Android
        // player ships with). It names no `vo`, no GPU context and no decoder,
        // so the device-resolved output below still decides those. Set before
        // them because a profile applies the options it names at the moment it
        // is set, and this one only ever fills in knobs nothing here sets.
        MpvBackend.setOptionString("profile", "fast")

        // Android video output, resolved for THIS device and the stored
        // override rather than pinned (see [MpvVideoOutput]): the 6.51
        // library swap fixed black screen on one box and introduced it on
        // another, so which `vo` and which GPU settings work is a property of
        // the box. The GPU path is `gpu-next` - the output the AAR this build
        // links is built for, whose libplacebo carries `-Ddovi=enabled` and
        // therefore performs the Dolby Vision reshape `vo=gpu` does not. The
        // MediaCodec path deliberately sets no `gpu-*` option at all - there is
        // no GPU context to name when the decoder writes to the Surface
        // directly, and naming one is the mismatch the stalled boxes choke on.
        val vo = videoOutput()
        MpvBackend.setOptionString("vo", vo.vo)
        vo.gpuContext?.let { MpvBackend.setOptionString("gpu-context", it) }
        // Only name the API when the resolver resolved one. When null the GPU
        // path's gpuApi is left unset, so mpv resolves gpu-api on its own from
        // the context — leave it on the default rather than handing mpv a value
        // it could override away from what the driver picks. The guard already
        // makes this correct: the new OPENGL settings surface no gpuApi, so
        // neither applyOptions nor attachSurface sets gpu-api.
        vo.gpuApi?.let { MpvBackend.setOptionString("gpu-api", it) }
        if (vo.openglEs) MpvBackend.setOptionString("opengl-es", "yes")
        // The field test's line: which mode was stored, which device it was
        // resolved on, and what that came out as.
        Log.i(
            TAG,
            "MPV vo mode=${AppPreferences.getMpvVoMode(context)} device=${Build.MODEL} " +
                "vo=${vo.vo} gpuApi=${vo.gpuApi ?: "none"} openglEs=${vo.openglEs}"
        )

        // Decoding: try MediaCodec zero-copy, then copy-back, then software.
        // The trailing `no` is the whole point of this engine — a file the
        // box's decoders cannot give us still plays.
        MpvBackend.setOptionString("hwdec", HWDEC_HW)
        // `all`, not an allowlist. An allowlist only ever narrows the ATTEMPT
        // set: a codec the box's MediaCodec exposes but the list omits (VP9
        // Profile 2 10-bit, AV1 film grain, VC-1, extra DV profiles, codecs
        // added later) never got a hardware attempt at all. The `hwdec` chain
        // above is unchanged, so `all` widens only what is TRIED — a codec the
        // box cannot do in hardware still falls through mediacodec →
        // mediacodec-copy → software → `no`, which is the same failure mode
        // every codec had before. Mpv∞ sets this too.
        MpvBackend.setOptionString("hwdec-codecs", "all")

        // Audio: MediaPlayer-style role so a TV or receiver treats this like
        // any other media session.
        MpvBackend.setOptionString("ao", "audiotrack,opensles")
        MpvBackend.setOptionString("audio-set-media-role", "yes")

        // Networking. A dead host should fail in half a minute instead of
        // sitting on a spinner, and TLS is verified against the BUNDLED Mozilla
        // roots: libmpv cannot read Android's trust store, so `tls-verify=yes`
        // with no `tls-ca-file` makes every https handshake fail with
        // "unable to get local issuer certificate" (see [prepareTlsCaFile]).
        // When the roots cannot be staged, verification is turned OFF instead -
        // unverified playback beats no playback.
        val caFile = prepareTlsCaFile()
        if (caFile != null) {
            MpvBackend.setOptionString("tls-ca-file", caFile)
            MpvBackend.setOptionString("tls-verify", "yes")
        } else {
            Log.w(TAG, "no CA bundle available; disabling mpv TLS verification")
            MpvBackend.setOptionString("tls-verify", "no")
        }
        MpvBackend.setOptionString("network-timeout", "30")

        // The identity the app plays as. mpv asks as `mpv/<version>` by
        // default, which the hosts this app streams from routinely refuse -
        // and because the ExoPlayer path sends the browser agent below, the
        // same stream played in the main player and failed here, presenting as
        // "this stream could not be played" on every press of Switch Player.
        // A source that names its own agent overrides this per load.
        MpvBackend.setOptionString("user-agent", StreamUserAgent.DEFAULT)

        // Caching: mpv's defaults are sized for a desktop; 64 MB matches what
        // the reference Android player uses. Low Latency (the panel's own
        // Network buffer row) trades that for a small read-ahead instead.
        applyCacheOptions(bufferMode)

        // Buffering resilience (Mpv∞'s proven recipe). Without these, a single
        // dropped connection or a failed seek-reopen stalls network playback
        // permanently - the spinner that never ends - and a marginal network
        // stutters instead of riding through. None of it makes a source faster
        // than the bitrate it needs; it is the TRANSIENT stalls it changes.
        //
        // When the cache runs thin, pause briefly and refill instead of
        // stuttering through it. In low-latency mode above `cache=no`, so there
        // is nothing to pause on and live behavior is as it was.
        MpvBackend.setOptionString("cache-pause", "yes")
        MpvBackend.setOptionString("cache-pause-wait", "2")
        // HLS: adapt the variant to the network instead of pinning the heaviest
        // and stalling when it cannot keep up.
        MpvBackend.setOptionString("hls-bitrate", "no")
        // Recover boundedly from transient HTTP/TLS disconnects.
        // demuxer-lavf-o covers demuxer-internal opens (HLS/DASH segments) ...
        MpvBackend.setOptionString(
            "demuxer-lavf-o",
            "http_persistent=0,reconnect=1,reconnect_on_network_error=1," +
                "reconnect_streamed=1,reconnect_delay_max=5,reconnect_max_retries=5," +
                "reconnect_delay_total_max=20"
        )
        // ... and stream-lavf-o covers the primary http(s) open, without which
        // one dropped connection or failed seek-reopen stalls network playback
        // forever. `reconnect_max_retries` with the delay caps is what keeps
        // recovery BOUNDED: a truly dead source still fails - and still reports
        // why through the mpv log observer - instead of retrying forever, and a
        // legitimate VOD EOF is untouched (this is reconnect-on-error, not
        // reconnect-at-eof).
        MpvBackend.setOptionString(
            "stream-lavf-o",
            "reconnect=1,reconnect_on_network_error=1,reconnect_on_http_error=5xx," +
                "reconnect_streamed=1,reconnect_delay_max=5,reconnect_max_retries=5," +
                "reconnect_delay_total_max=20"
        )
        // When the renderer cannot keep up, drop the late frames instead of
        // building up long-term jitter. `vo` only drops frames the video output
        // was already going to miss - it is never pre-emptive.
        MpvBackend.setOptionString("framedrop", "vo")

        // Playback shape. keep-open holds the last frame at EOF so the
        // activity can offer the next episode; save-position-on-quit is off
        // because watch history is the app's job, not mpv's watch_later files.
        //
        // `idle` and `force-window` are deliberately NOT here: they are the
        // pair the reference player sets after init() and before the holder
        // callback (see [initialize]), so neither is in force while the engine
        // is still coming up.
        MpvBackend.setOptionString("keep-open", "yes")
        MpvBackend.setOptionString("save-position-on-quit", "no")

        // Preferred languages, so a multi-audio/multi-subtitle file opens on
        // the right tracks without a press: what the session asked for (a
        // title's remembered choice, which [setLanguagePreferences] resolves),
        // else the global Settings preference.
        val wantedAudio = audioLanguage.ifBlank {
            preferredLanguage(AppPreferences.getPreferredAudioLanguage(context)).orEmpty()
        }
        if (wantedAudio.isNotBlank()) MpvBackend.setOptionString("alang", wantedAudio)
        val wantedSubtitles = subtitleLanguage.ifBlank {
            preferredLanguage(AppPreferences.getPreferredSubtitleLanguage(context)).orEmpty()
        }
        when (SubtitleModeRules.normalized(subtitleMode)) {
            // Off: mpv opens with the subtitle track deselected. Left to its
            // own selection it would arm a default track - the same "cannot
            // actually turn subtitles off" the main player had.
            SubtitleModeRules.OFF -> MpvBackend.setOptionString("sid", "no")

            // Forced: only the signs / foreign-dialogue cues, whichever track
            // carries them. runCatching guards an mpv build without the option
            // (it is ignored rather than failing the file).
            SubtitleModeRules.FORCED -> {
                runCatching { MpvBackend.setOptionString("sub-forced-only", "yes") }
                if (wantedSubtitles.isNotBlank()) MpvBackend.setOptionString("slang", wantedSubtitles)
            }

            else -> if (wantedSubtitles.isNotBlank()) MpvBackend.setOptionString("slang", wantedSubtitles)
        }

        // Subtitle look, from the same global defaults the main player's
        // settings pane edits.
        applySubtitleAppearance(
            size = AppPreferences.getDefaultSubtitleSize(context),
            background = AppPreferences.getDefaultSubtitleBackground(context),
            position = AppPreferences.getDefaultSubtitlePosition(context)
        )

        // Clean SDH: mpv's own equivalent of the main player's caption filter,
        // and the only way this engine gets one - libmpv draws its own cues, so
        // the app has no text to filter on this side. It drops the speaker
        // labels and the bracketed sound descriptions, which is exactly what
        // the setting says. An OPTION, set before init(), because a subtitle
        // filter is applied as a track is loaded: turning it on afterwards
        // would leave the track already on screen unfiltered. runCatching
        // guards an mpv build without the filter (it is ignored rather than
        // failing the file), the same way sub-forced-only is guarded above.
        if (AppPreferences.getCleanSdhCaptions(context)) {
            runCatching { MpvBackend.setOptionString("sub-filter-sdh", "yes") }
        }

        // ASS/SSA tracks render exactly as the fansub authored them — karaoke,
        // signs, positioning, fonts. This pins the guarantee the app already
        // documents rather than relying on KBStream happening to set no
        // --sub-ass-* option: `scale` (mpv's default) would silently restyle an
        // ASS track the moment any future --sub-ass-* option is added, whereas
        // `no` means "as specified by the subtitle scripts, without overrides"
        // — the definition of libass-perfect. The appearance controls above are
        // --sub-* (not --sub-ass-*) options, so they never reached ASS tracks
        // anyway: nothing a viewer sees changes for ASS, and text (SRT/VTT)
        // subtitles keep full appearance control.
        MpvBackend.setOptionString("sub-ass-override", "no")
        // Complex text shaping (HarfBuzz) for Arabic, Indic, and other complex
        // scripts — the default when libass is built with HarfBuzz, pinned so a
        // build without it fails loudly at the option check instead of silently
        // shaping wrong.
        MpvBackend.setOptionString("ass-shaper", "complex")
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
                MpvBackend.setPropertyBoolean("cache", !lowLatency)
                MpvBackend.setPropertyInt("demuxer-max-bytes", maxBytes)
                MpvBackend.setPropertyInt("demuxer-max-back-bytes", backBytes)
                if (lowLatency) MpvBackend.setPropertyInt("demuxer-readahead-secs", 0)
            } else {
                MpvBackend.setOptionString("cache", if (lowLatency) "no" else "yes")
                MpvBackend.setOptionString("demuxer-max-bytes", maxBytes.toString())
                MpvBackend.setOptionString("demuxer-max-back-bytes", backBytes.toString())
                if (lowLatency) MpvBackend.setOptionString("demuxer-readahead-secs", "0")
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
        runCatching { MpvBackend.setPropertyString("af", spec.orEmpty()) }
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
        MpvBackend.observeProperty("time-pos", MpvFormat.MPV_FORMAT_DOUBLE)
        MpvBackend.observeProperty("duration", MpvFormat.MPV_FORMAT_DOUBLE)
        MpvBackend.observeProperty("duration/full", MpvFormat.MPV_FORMAT_DOUBLE)
        MpvBackend.observeProperty("pause", MpvFormat.MPV_FORMAT_FLAG)
        MpvBackend.observeProperty("paused-for-cache", MpvFormat.MPV_FORMAT_FLAG)
        MpvBackend.observeProperty("eof-reached", MpvFormat.MPV_FORMAT_FLAG)
        MpvBackend.observeProperty("media-title", MpvFormat.MPV_FORMAT_STRING)
        // Bare observation (no format): we only need to know it changed, then
        // read the parts we care about through mpv's property paths.
        MpvBackend.observeProperty("track-list", MpvFormat.MPV_FORMAT_NONE)
        // The dialogue filter is built for the channel count, so it has to be
        // rebuilt whenever a new file (or another track) brings a different
        // one.
        MpvBackend.observeProperty("audio-params/channel-count", MpvFormat.MPV_FORMAT_INT64)
    }

    // --- Surface lifecycle --------------------------------------------------

    /**
     * Attaches the surface the holder ALREADY has — the create the callback
     * cannot cover.
     *
     * This view is created with the Activity, so on a box whose surface is up
     * before [initialize] runs, `SurfaceHolder` delivers no `surfaceCreated`
     * for it: a holder does not replay a create that already happened. Without
     * this, mpv is never handed that surface at all — `wid` stays 0 — and the
     * file opens against it: on the `vo=mediacodec_embed` path FFmpeg is given
     * a null surface (`Both surface and native_window are NULL`), the decoder
     * still runs (`Got First Frame Ready`) and every frame goes nowhere.
     * NuvioTV's engine covers the same case with the same call in the same
     * place (`NuvioMpvSurfaceView.ensureSurfaceAttachedIfAlreadyAvailable`),
     * behind the same `isValid` test.
     *
     * The `isValid` test is the only evidence available here that the surface
     * is backed, and it is NOT what made an attach from here look wrong before:
     * the line that reasoning rested on ("Could not find corresponding native
     * window for surface") is not mpv's. It is absent from the shipped
     * `mpvlib-1.1.2.aar`'s libmpv and its FFmpeg, and it appears in the logcat
     * of Fire OS apps that use neither — it is the platform's, and it says
     * nothing about this attach. What mpv itself logs when it cannot resolve a
     * window is "Failed to create ANativeWindow", from `vo_android_init`.
     *
     * Re-uses the callback's own attach path rather than attaching here, so
     * there is exactly one sequence: attach, publish `android-surface-size`,
     * `force-window=yes`, then the first-creation branch. This IS the first
     * creation, so that branch is the right one — and any file already waiting
     * goes out with it. The size in that sequence is the one thing this helper
     * adds beyond the callback's own case: the holder will not send the
     * `surfaceChanged` the reference player publishes it from either, and a GPU
     * output with no size queues frames that never reach the display (see
     * [publishAttachedSurfaceSize]).
     */
    private fun ensureSurfaceAttachedIfAlreadyAvailable() {
        if (!initialized || released || surfaceAttached) return
        val liveHolder = holder
        if (liveHolder.surface?.isValid != true) return
        runCatching { surfaceCreated(liveHolder) }
            .onFailure { Log.w(TAG, "could not attach the surface the holder already had", it) }
    }

    /**
     * Publishes the size of the surface mpv was just handed, as
     * `android-surface-size`.
     *
     * mpv's Android GPU contexts (`gpu-context=android`, in both the OpenGL
     * and the Vulkan implementation the bundled libmpv carries) size their
     * EGL / Vulkan window surface from this property. They cannot read it off
     * the window: `ANativeWindow_getWidth/Height` is not answered for a
     * `SurfaceView`'s window until a buffer has been queued, so the embedding
     * app is the only thing that knows the size. The reference player
     * publishes it from `surfaceChanged` alone (`BaseMPVView.surfaceChanged`),
     * which is enough there because `surfaceChanged` follows every
     * `surfaceCreated` — but NOT for a surface the holder already had:
     * [ensureSurfaceAttachedIfAlreadyAvailable] attaches one whose
     * `surfaceChanged` will never arrive, because a `SurfaceHolder` replays no
     * callback at all for a surface that came up before the callback was
     * registered. That path hands the GPU output a window with no size, and the
     * picture never reaches the display while `hwdec` keeps decoding — a
     * `vo=gpu` failure on a box whose MediaCodec-direct output
     * (`mediacodec_embed`) works, because that one renders no window of its own
     * and so never asks for a size. That asymmetry is this fix's whole shape.
     *
     * Called from [attachSurface], so every attach path publishes it — and
     * BEFORE the `force-window=yes` that can create the video output, so the
     * output reads the size at its own init instead of after a first frame.
     * [surfaceChanged] keeps updating it for sizes that change later.
     *
     * The surface's own frame is preferred to the view's measured size (they
     * agree for this full-bleed SurfaceView, and the frame is the one actually
     * attached). When neither is known yet — a frame that is not laid out and a
     * view that has not been measured — nothing is published here, because a
     * wrong size would be worse than none: the layout that follows publishes it
     * instead, through [onSizeChanged]. That second publisher is what makes the
     * already-attached case whole, because `surfaceChanged` is not one this
     * surface can ever produce (see [publishAndroidSurfaceSize]).
     *
     * A note on the log line this file's field reports keep quoting:
     * `updateAcquireFence: Did not find frame` is the platform's — not mpv's,
     * and not SurfaceFlinger's. It is `libgui`'s
     * `ProducerFrameEventHistory::updateAcquireFence`
     * (`frameworks/native/libs/gui/FrameTimestamps.cpp`), which runs in this
     * process because the app is the producer of this view's buffer queue, and
     * it fires when the producer queues a frame its own frame-time history has
     * no record for. It is absent from every native library in
     * `mpvlib-1.1.2.aar` (libmpv, libplayer, libavcodec), exactly as "Could not
     * find corresponding native window for surface" is — and it says frames
     * ARE being queued, not that the window was lost, which is why no fix can
     * be read off it.
     */
    private fun publishAttachedSurfaceSize() {
        val frame = holder.surfaceFrame
        val fromFrame = frame.width() > 0 && frame.height() > 0
        val w = if (fromFrame) frame.width() else width
        val h = if (fromFrame) frame.height() else height
        if (w <= 0 || h <= 0) {
            Log.i(
                TAG,
                "android-surface-size unknown at attach " +
                    "(frame=${frame.width()}x${frame.height()} view=${width}x$height) — leaving it " +
                    "to the view's own size change"
            )
            return
        }
        publishAndroidSurfaceSize(w, h, if (fromFrame) "attach/frame" else "attach/view")
    }

    /**
     * [publishAttachedSurfaceSize] with the size already known — one writer for
     * the property, so the attach, the holder's own size change and the view's
     * layout cannot drift, and neither callback has anything of its own to
     * spell.
     */
    private fun publishAndroidSurfaceSize(w: Int, h: Int, source: String) {
        if (!initialized || released) return
        runCatching { MpvBackend.setPropertyString("android-surface-size", "${w}x$h") }
            .onSuccess { Log.i(TAG, "android-surface-size=${w}x$h ($source)") }
            .onFailure { Log.w(TAG, "android-surface-size=${w}x$h rejected", it) }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (!initialized || released) return
        attachSurface()
        // The two halves of the reference player's branch. Which one runs is
        // decided by whether this is the FIRST create ([surfaceCreatedOnce]),
        // not by whether a load is waiting: the surface is created during
        // activity startup, before [load] runs, so on a first creation there is
        // no pending load to read. That test read a first creation as a
        // recreation and re-set the output on it — the `vo="gpu"` line ahead
        // of `start-file` in the field log — which is exactly the re-init this
        // branch exists to avoid.
        //
        // A first creation attaches, forces rendering and starts the load if
        // one is waiting — and does NOT re-set the output, because
        // [applyOptions] already configured it as the OPTION before init().
        // Setting the same output again is not a no-op: it tears the video
        // output down and re-initializes it, and on the first attach that
        // re-initialization races the surface mpv has only just been handed,
        // which is the black screen this branch exists to remove.
        //
        // Every later creation follows [surfaceDestroyed], which nulled the
        // output on the way out. This set is then what re-initializes it against
        // the new surface — without it the output still points at the surface
        // that was destroyed (or at nothing), and mpv decodes with nowhere to
        // present: the track list and `video-format` populate, the decoder
        // renders, and every frame is thrown away, which reads as "audio plays
        // over a black screen" rather than "playback failed".
        if (!surfaceCreatedOnce) {
            // First creation: attach, force-window, load. Do NOT re-set VO —
            // it was already configured via options.
            surfaceCreatedOnce = true
            if (pendingLoad != null) startPendingLoad()
        } else {
            // Recreation (surface destroyed/recreated): re-enable the VO.
            runCatching { MpvBackend.setPropertyString("vo", videoOutput().vo) }
            if (pendingLoad != null) startPendingLoad()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (!initialized || released) return
        // mpv sizes its output from this, not from the Surface itself — and the
        // attach publishes the same property for the surface the holder already
        // had, for which this callback never arrives (see
        // [publishAttachedSurfaceSize]).
        publishAndroidSurfaceSize(width, height, "surfaceChanged")
    }

    /**
     * The size update that a surface the holder ALREADY had can still get.
     *
     * [publishAttachedSurfaceSize] can only publish a size it knows, and the one
     * case it cannot is the attach [ensureSurfaceAttachedIfAlreadyAvailable]
     * makes during [initialize]: the view is registered with the holder before
     * the Activity has laid it out, so its frame is empty and its measured size
     * is zero, and there is nothing to publish. The `surfaceChanged` the
     * reference player relies on will not rescue that either — a SurfaceHolder
     * replays no callback at all for a surface that came up before the callback
     * was registered, so no `surfaceChanged` is ever delivered for this surface
     * and the GPU context would keep a window it was never told the size of.
     * That is the same `vo=gpu` black screen (decoder rendering, every frame
     * going nowhere) on the one path that reaches it without a size.
     *
     * The layout that follows is the one event left, and this is where it
     * arrives: the view's own size change. It is published through the same
     * single writer as the attach and the holder callback, so the three cannot
     * describe different sizes, and it is skipped while mpv holds no surface —
     * before the attach there is no window for the size to describe, and the
     * attach publishes it from the frame once there is one.
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!initialized || released || !surfaceAttached) return
        if (w <= 0 || h <= 0) return
        publishAndroidSurfaceSize(w, h, "onSizeChanged")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (!initialized || released) return
        // Order matters, and it is the reference player's: null the VO first —
        // which tears the video output down while its surface still exists —
        // then drop forced rendering, then let the surface go. Detaching with
        // the output still open is what leaves the next attach with nothing to
        // re-initialize against, and the surface it would have used is already
        // dead by then.
        runCatching {
            MpvBackend.setPropertyString("vo", "null")
            MpvBackend.setPropertyString("force-window", "no")
            MpvBackend.detachSurface()
        }
        // mpv holds no surface from here on, so nothing may load until the next
        // create attaches one (see [surfaceAttached]).
        surfaceAttached = false
    }

    private fun attachSurface() {
        val surface = holder.surface ?: return
        runCatching {
            MpvBackend.attachSurface(surface)
            // mpv holds this surface from the call above (it is `wid` on the
            // native side), and that is what [load] gates on. Marked in the
            // same guarded block as the call it describes: an attach that threw
            // must not leave mpv recorded as holding a surface it never got.
            surfaceAttached = true
            // The `vo` reset deliberately does NOT live here: this helper only
            // attaches the surface and asks for forced rendering, so the order
            // stays legible as the reference player's — attach and
            // force-window here, then whatever [surfaceCreated] branches to
            // once this returns (the pending load on a first creation, the
            // output property on a recreation). Tearing the output down and
            // re-opening it are the two halves of the same cycle and are pinned
            // together in MpvSurfaceCycleContractTest.
            //
            // The window's size, BEFORE force-window can create the video
            // output: the GPU contexts size their EGL / Vulkan surface from
            // this property and have no other way to learn it, and on the path
            // where the holder already had the surface no `surfaceChanged`
            // will ever deliver it (see [publishAttachedSurfaceSize]).
            publishAttachedSurfaceSize()
            // Forces mpv to render video/subtitles into our surface even when
            // it would otherwise decide it has no window to draw into.
            MpvBackend.setOptionString("force-window", "yes")
            // The diagnostic that splits the remaining failure in two, read
            // back from mpv itself rather than assumed from the call above:
            //
            //  - `wid=0`: the attach did not stick. mpv holds no native
            //    window, so nothing can render and the fault is in the
            //    library's attach, not in any option this app sets.
            //  - `wid!=0`: mpv has the window, so the window is there and the
            //    video output is what is failing to put frames into it - which
            //    is a `vo`/`gpu-*` question, not an attach one.
            //
            // Those two readings need opposite next steps, and before this line
            // the field log could not tell them apart: an attach that silently
            // did nothing and an attach that worked into a video output that
            // renders nothing look identical from the app side (decoder
            // running, audio playing, no picture). Diagnostic only - nothing
            // here changes what the attach does.
            logWidAfterAttach()
        }.onFailure { Log.e(TAG, "could not attach the MPV surface", it) }
    }

    /**
     * Logs mpv's own `wid` - the native window handle it holds - after an
     * attach.
     *
     * Read as a STRING (see [MpvBackend.getWid]): `wid` is a pointer, and the
     * library's int getter returns a Java `Integer`, so a handle that truncated
     * to 0 would report a broken attach about a working one. `null` is a third
     * answer, distinct from 0 - it means mpv would not answer for the property
     * at all - so the line says which of the three it is instead of collapsing
     * them into a falsy value.
     */
    private fun logWidAfterAttach() {
        val wid = runCatching { MpvBackend.getWid() }
            .onFailure { Log.w(TAG, "could not read mpv wid after attach", it) }
            .getOrNull()
        when {
            wid == null -> Log.i(TAG, "MPV wid after attach: unavailable (mpv did not answer)")
            wid == "0" || wid.isBlank() ->
                Log.w(
                    TAG,
                    "MPV wid after attach: $wid (0 = the attach did not stick; mpv holds no " +
                        "window, so this is the library's attach, not the video output)"
                )
            else ->
                Log.i(
                    TAG,
                    "MPV wid after attach: $wid (mpv holds the window; the video output is " +
                        "what renders into it)"
                )
        }
    }

    private fun startPendingLoad() {
        val request = pendingLoad ?: return
        pendingLoad = null
        // Read BEFORE the flag is cleared: the outgoing file's END_FILE is
        // classified from this (see [replacingPreviousFile]).
        replacingPreviousFile = fileLoaded
        fileLoaded = false
        endNotified = false
        durationSec = null
        cacheStall = false
        // The previous load's mpv errors say nothing about this one, and
        // leaving one behind would name the wrong reason for a later failure.
        lastErrorLine = null
        synchronized(recentErrorLinesLock) { recentErrorLines.clear() }
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
        MpvBackend.setOptionString("start", "${request.startPositionMs.coerceAtLeast(0L) / 1000.0}")

        runCatching { MpvBackend.command("loadfile", request.url, "replace") }
            .onFailure { Log.e(TAG, "loadfile failed", it) }

        // Adaptive split stream: the video URL carries no audio, so the
        // separate track is added as an additional audio file.
        request.audioUrl?.takeIf { it.isNotBlank() }?.let { audioUrl ->
            runCatching { MpvBackend.command("audio-add", audioUrl) }
                .onFailure { Log.w(TAG, "audio-add failed", it) }
        }
    }

    // --- mpv events ---------------------------------------------------------

    override fun event(eventId: Int) {
        // The event id is the whole signal here: the property values this class
        // acts on arrive through the typed `eventProperty` overloads below, and
        // the node the library also hands `event` was never read. [MpvBackend]
        // drops it at the seam, so this override names only what it uses.
        when (eventId) {
            MpvEvent.MPV_EVENT_FILE_LOADED -> {
                fileLoaded = true
                // The replacement is the file now: the previous one's END_FILE
                // has already arrived (mpv unloads before it opens the next).
                replacingPreviousFile = false
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

            MpvEvent.MPV_EVENT_END_FILE -> {
                // Four different facts arrive as this one event (see
                // [StreamEndRules] for why mpv's own end-file reason cannot be
                // read here, and what each verdict means). Before this, only
                // the "never opened" case was handled: a stream that loaded and
                // then died mid-playback fell out of the bottom of the branch,
                // leaving the last frame frozen on screen with keep-open and no
                // callback, no log line and no card.
                val verdict = StreamEndRules.verdict(
                    fileLoaded = fileLoaded,
                    ourOwnStop = replacingPreviousFile || stoppingDeliberately,
                    released = released,
                    eofReached = endNotified,
                    positionMs = lastPositionMs,
                    durationMs = lastDurationMs
                )
                // Whatever the verdict, the replacement/stop decision is spent
                // by now: the outgoing file has ended.
                replacingPreviousFile = false
                stoppingDeliberately = false
                when (verdict) {
                    StreamEndRules.Verdict.IGNORE -> Unit

                    StreamEndRules.Verdict.ENDED_NORMALLY -> Log.i(
                        TAG,
                        "MPV file ended at ${lastPositionMs}ms of ${lastDurationMs}ms: " +
                            loadedFileDiagnostics()
                    )

                    StreamEndRules.Verdict.DIED_MID_STREAM -> {
                        // mpv's own words when it has any - a segment that
                        // started answering 403 and a reconnect that ran out of
                        // attempts are the same event on screen and want
                        // different fixes. Same reader the open-failure path
                        // uses.
                        val reason = lastErrorLine?.takeIf { it.isNotBlank() }
                        val where = "${lastPositionMs / 1000}s of ${lastDurationMs / 1000}s"
                        Log.w(
                            TAG,
                            "MPV stream ended mid-playback at $where: " +
                                MpvErrorReason.failureDetail(reason, lastLoadHost)
                        )
                        // The report ring the ExoPlayer ladder and the open
                        // failure both write to: a session that froze with no
                        // card leaves nothing else behind (see
                        // PlaybackEngineTrace).
                        com.kennyb1201.kbstream.data.reporting.PlaybackEngineTrace.note(
                            com.kennyb1201.kbstream.data.reporting.PlaybackEngineTrace.describe(
                                cause = "MPV stream ended mid-playback",
                                detail = "at $where: " +
                                    MpvErrorReason.failureDetail(reason, lastLoadHost)
                            )
                        )
                        // The viewer gets the same treatment the open failure
                        // does - a card, not a frozen frame - but through
                        // [onStreamDied], which does NOT demote this add-on or
                        // walk to the next source: the link was fine.
                        post {
                            onStreamDied?.invoke(
                                when (reason) {
                                    null -> "Playback stopped unexpectedly."
                                    else -> "Playback stopped unexpectedly. $reason"
                                }
                            )
                        }
                    }

                    StreamEndRules.Verdict.OPEN_FAILED -> {
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
            }

            MpvEvent.MPV_EVENT_VIDEO_RECONFIG -> {
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
        if (level > MpvLogLevel.MPV_LOG_LEVEL_WARN) return

        val line = MpvErrorReason.format(prefix, text)
        if (line.isEmpty()) return

        // The deque is shared with the main thread (a load clears it in
        // [startPendingLoad]), so append + trim + read happen under the lock.
        lastErrorLine = synchronized(recentErrorLinesLock) {
            recentErrorLines.addLast(line)
            while (recentErrorLines.size > MpvErrorReason.MAX_KEPT) {
                recentErrorLines.removeFirst()
            }
            // Resolved across the whole burst, not from this line: FFmpeg logs
            // the cause first and its generic "Failed to open" second (see
            // MpvErrorReason), so the last line on its own is the useless one.
            MpvErrorReason.pick(recentErrorLines)
        }

        // Scrubbed on the way to logcat, not only on the way to Sentry: mpv
        // logs the URL it could not open, and a debrid/stream URL carries its
        // signature in the query string (and sometimes its credentials in the
        // user-info). [Redaction] masks the shapes a credential takes - the
        // sensitive field names, URL user-info, bare bearer tokens - and leaves
        // the rest of the line readable, which is what makes this channel worth
        // keeping at all. Best effort by construction: a signature carried in a
        // path segment, with no field name to key on, is not something a
        // pattern can find.
        Log.w(TAG, "mpv: ${Redaction.text(line)}")
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
            MpvBackend.command("seek", (resume / 1000.0).toString(), "absolute+keyframes")
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
        runCatching { MpvBackend.getPropertyInt(name) }.getOrNull()

    private fun getPropertyStringOrNull(name: String): String? =
        runCatching { MpvBackend.getPropertyString(name) }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun getPropertyBooleanOrNull(name: String): Boolean? =
        runCatching { MpvBackend.getPropertyBoolean(name) }.getOrNull()

    private fun getPropertyDoubleOrNull(name: String): Double? =
        runCatching { MpvBackend.getPropertyDouble(name) }.getOrNull()

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
     * The chapters mpv found in the open file, via its own indexed property
     * paths - the same shape the track list is read in (`chapters` for the
     * count, `chapter-list/<i>/time` in seconds, `chapter-list/<i>/title`).
     *
     * Empty for a file with no chapters, for a file that has not opened, and
     * for a build that does not expose the properties: `chapters` missing or 0
     * both read as "no markers" rather than as an error.
     *
     * Called by the player when the file loads; MpvPlayerView keeps no chapter
     * state of its own, so there is nothing to clear when the file unloads.
     */
    fun readChapters(): List<ChapterMark> {
        if (!initialized || released) return emptyList()
        val count = getPropertyIntOrNull("chapters") ?: return emptyList()
        if (count <= 0) return emptyList()
        return (0 until count).mapNotNull { index ->
            val time = getPropertyDoubleOrNull("chapter-list/$index/time")
                ?: return@mapNotNull null
            ChapterMark(
                timeMs = (time * 1000.0).toLong(),
                title = getPropertyStringOrNull("chapter-list/$index/title")
            )
        }
    }

    /**
     * Steps mpv's current chapter by [delta] (-1 previous, +1 next), the same
     * mpv command path the rest of the view drives. A file with no chapters
     * simply does nothing.
     */
    fun addChapter(delta: Int) {
        if (!initialized || released) return
        runCatching { MpvBackend.command("add", "chapter", delta.toString()) }
            .onFailure { Log.w(TAG, "chapter step failed", it) }
    }

    /**
     * mpv wants a language list ("en,eng"), the app stores a BCP-47 tag
     * ("en-US"). Only the primary subtag is passed: a region match is mpv's
     * fallback anyway, and passing "en-US" makes mpv look for the literal
     * tag in the track's metadata, which almost never matches.
     */
    private fun preferredLanguage(tag: String?): String? =
        tag?.substringBefore('-')?.substringBefore('_')?.trim()?.takeIf { it.isNotBlank() }

    /**
     * The kind ranking the automatic subtitle pick applies, as pure functions
     * so [MpvSubtitleKindRankingTest] can pin it without a player (this engine
     * has no unit harness - see the repo's other MPV contracts).
     */
    internal companion object {
        const val TAG = "PLAYER_MPV"

        /**
         * Whether this subtitle track's own TITLE names it SDH - the same
         * detection the ExoPlayer path uses for its SDH bucket
         * ([SubtitleKindRules], whose SDH tags are the literal "SDH" plus
         * "hi", "cc", "deaf", "captions" and "hearing impaired"). MPV's
         * `Track` carries no forced flag, so the title is the only kind
         * evidence there is.
         */
        fun isSdhTitle(track: Track): Boolean =
            SubtitleKindRules.of(track.title).contains(SubtitleKindRules.SDH)

        /**
         * A full, non-forced translation: its title names neither SDH nor the
         * forced / signs kind. An untitled track counts as regular, exactly as
         * the ExoPlayer untagged bucket ranks an unnamed track.
         */
        fun isRegularTitle(track: Track): Boolean {
            val kinds = SubtitleKindRules.of(track.title)
            return !kinds.contains(SubtitleKindRules.SDH) &&
                !kinds.contains(SubtitleKindRules.FORCED)
        }

        /**
         * The most complete of [matches] (the tracks already known to match
         * the language): SDH first, then a regular translation, then whatever
         * came first - the same order ExoPlayer picks with, so the two engines
         * agree on a file that carries several tracks in one language.
         */
        fun rankedSubtitleMatch(matches: List<Track>): Track? =
            matches.firstOrNull { isSdhTitle(it) }
                ?: matches.firstOrNull { isRegularTitle(it) }
                ?: matches.firstOrNull()

        /**
         * The MPV library this build links, for the once-per-session start
         * line. There is exactly one (see the MPV library note in
         * app/build.gradle.kts); it is named so a field log still says which
         * binary produced it.
         */
        const val MPV_LIBRARY = "fortunasxp/mpv-android-libdovi mpv-android-lib-libdovi.aar"

        /** How close to the resume point counts as "already there". */
        const val RESUME_TOLERANCE_MS = 5_000L

        /**
         * Mozilla's CA roots, shipped in assets and handed to mpv as a file.
         * See [prepareTlsCaFile] for why. Refresh the bundle from
         * https://curl.se/ca/cacert.pem every few months, as CAs rotate.
         */
        const val CA_ASSET_NAME = "cacert.pem"
        const val CA_FILE_NAME = "cacert.pem"

        /**
         * The staged path once the roots have been written, so a later view in
         * the same process reuses the file instead of re-reading the ~190 KB
         * asset off the main thread on every init.
         */
        @Volatile
        private var stagedCaPath: String? = null

        /**
         * MediaCodec first, copy-back second, then software decoding.
         *
         * Paired with `hwdec-codecs=all` in [applyOptions]: the chain is what
         * decides the FALLBACK order, and the codec allowlist constant this
         * used to sit beside has been retired so the two cannot drift.
         */
        const val HWDEC_HW = "mediacodec,mediacodec-copy,no"
        const val HWDEC_SW = "no"

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
