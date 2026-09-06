package com.kennyb1201.kbstream.ui.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.kennyb1201.kbstream.R
import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.tv.TvLauncherPublisher
import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import com.kennyb1201.kbstream.data.simkl.SimklRepository
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.ui.settings.AppPreferences
import coil3.load
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayList
import java.util.concurrent.TimeUnit

private const val TAG = "NativePlayer"
private const val PERIODIC_SAVE_INTERVAL_MS = 5_000L
private const val MIN_RESUME_POSITION_MS = 10_000L
private const val COMPLETION_THRESHOLD_RATIO = 0.95f
private const val EXTRA_HEADERS = "stream_headers"
private const val EXTRA_DRM_LICENSE_URL = "drm_license_url"
private const val EXTRA_DRM_HEADERS = "drm_headers"
private const val MAX_RETRY_ATTEMPTS = 6
private val RETRY_BACKOFF_MS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
private val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private const val CONTROLS_HIDE_DELAY_MS = 6_000L
private const val NEXT_UP_COUNTDOWN_SECONDS = 5

/**
 * Renderer policy used by the explicit FFmpeg-only setting. Media3's
 * EXTENSION_RENDERER_MODE_PREFER only changes ordering; it still creates the
 * hardware MediaCodec renderer. This factory deliberately creates only the
 * bundled FFmpeg video renderer, so hardware cannot win track selection.
 */
private class FfmpegOnlyRenderersFactory(
    context: Context,
    audioExtMode: Int
) : DefaultRenderersFactory(context) {
    init {
        // This factory replaces the VIDEO renderer below with the experimental
        // FFmpeg software renderer; audio keeps the platform renderer plus the
        // FFmpeg extension at the priority-driven position (fallback behind
        // MediaCodec, or preferred ahead of it). Without the FFmpeg audio
        // renderer, DTS / DTS-HD / TrueHD tracks — which the Fire TV stick's
        // MediaCodec can't decode — play video with no sound.
        setExtensionRendererMode(audioExtMode)
        setEnableDecoderFallback(true)
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        try {
            val rendererClass = Class.forName(
                "androidx.media3.decoder.ffmpeg.ExperimentalFfmpegVideoRenderer"
            )
            val constructor = rendererClass.getConstructor(
                Long::class.javaPrimitiveType!!,
                Handler::class.java,
                VideoRendererEventListener::class.java,
                Int::class.javaPrimitiveType!!
            )
            out.add(
                constructor.newInstance(
                    allowedVideoJoiningTimeMs,
                    eventHandler,
                    eventListener,
                    DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY
                ) as Renderer
            )
            Log.i("PLAYER_DV", "FFmpeg-only renderer installed; hardware video renderer excluded")
        } catch (e: ClassNotFoundException) {
            throw IllegalStateException("FFmpeg-only was selected but the FFmpeg video renderer is unavailable", e)
        } catch (e: Exception) {
            throw IllegalStateException("Could not create the FFmpeg-only video renderer", e)
        }
    }
}

/**
 * Decouples the video and audio extension policies, which stock
 * DefaultRenderersFactory ties to a single extensionRendererMode. Audio gets
 * the FFmpeg audio extension at the position set by the audio decoder
 * priority (OFF / fallback / preferred). The video extension mode is passed
 * per call site: ON keeps the FFmpeg software video renderer available as a
 * fallback behind MediaCodec ("Prefer device" video); OFF keeps video
 * strictly hardware (used when an FFmpeg-software session is bypassed by its
 * own guards — live IPTV and DV->HDR10-rewritten streams).
 */
private class SplitModeRenderersFactory(
    context: Context,
    private val videoExtMode: Int,
    audioExtMode: Int
) : DefaultRenderersFactory(context) {
    init {
        setExtensionRendererMode(audioExtMode)
        setEnableDecoderFallback(true)
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        super.buildVideoRenderers(
            context,
            videoExtMode,
            mediaCodecSelector,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            allowedVideoJoiningTimeMs,
            out
        )
    }
}


class NativePlayerActivity : ComponentActivity() {

    // Views
    private lateinit var playerView: PlayerView
    private lateinit var p5VideoGlesView: P5VideoGlesView
    // Whether P5 color correction via GLES is currently active
    private lateinit var liveBadge: TextView
    private lateinit var bufferingSpinner: ProgressBar
    private lateinit var reconnectingContainer: LinearLayout
    private lateinit var reconnectingText: TextView
    private lateinit var errorContainer: LinearLayout
    private lateinit var errorTitle: TextView
    private lateinit var errorMessage: TextView
    private lateinit var btnRetry: TextView
    private lateinit var btnChangeSource: TextView
    private lateinit var btnSkipIntro: TextView
    private lateinit var controlsOverlay: LinearLayout
    private lateinit var playerClock: TextView
    private lateinit var endsAtClock: TextView
    private lateinit var splashContainer: View
    private lateinit var splashBackdrop: ImageView
    private lateinit var splashClearLogo: ImageView
    private lateinit var clearLogo: ImageView
    private lateinit var itemNameView: TextView
    private lateinit var episodeLabel: TextView
    private lateinit var episodeTitleView: TextView
    private lateinit var sourceLabel: TextView
    private lateinit var streamHealth: TextView
    private lateinit var overviewText: TextView
    private lateinit var seekbarRow: LinearLayout
    private lateinit var seekbar: SeekBar
    private lateinit var currentTime: TextView
    private lateinit var totalTime: TextView
    private lateinit var btnPlayPause: ImageView
    private lateinit var btnNext: TextView
    private lateinit var btnSource: TextView
    private lateinit var btnAudio: TextView
    private lateinit var btnSubtitle: TextView
    private lateinit var btnSpeed: TextView
    private lateinit var btnAspect: TextView
    private lateinit var btnSettings: TextView
    private lateinit var pickerContainer: LinearLayout
    private lateinit var pickerTitle: TextView
    private lateinit var settingsContainer: ScrollView
    private lateinit var scrim: View
    private lateinit var settingsBufferAuto: TextView
    private lateinit var settingsBufferBalanced: TextView
    private lateinit var settingsBufferLow: TextView
    private lateinit var btnTunneling: TextView
    private lateinit var btnAutoplay: TextView
    private lateinit var settingsResolution: TextView
    private lateinit var settingsBitrate: TextView
    private lateinit var settingsCodec: TextView
    private lateinit var settingsSpeedAspect: TextView
    private lateinit var pickerList: RecyclerView
    private lateinit var btnSubSmall: TextView
    private lateinit var btnSubNormal: TextView
    private lateinit var btnSubLarge: TextView
    private lateinit var btnSubBgNone: TextView
    private lateinit var btnSubBgSemi: TextView
    private lateinit var btnSubBgSolid: TextView
    private lateinit var btnOffsetMinus: TextView
    private lateinit var subtitleOffsetValue: TextView
    private lateinit var btnOffsetPlus: TextView
    private lateinit var castSection: LinearLayout
    private lateinit var castRow: LinearLayout
    private lateinit var nextUpPanel: LinearLayout
    private lateinit var nextUpThumb: ImageView
    private lateinit var nextUpShowTitle: TextView
    private lateinit var nextUpEpisodeLabel: TextView
    private lateinit var nextUpEpisodeTitle: TextView
    private lateinit var btnNextPlay: TextView
    private lateinit var btnNextDismiss: TextView
    private lateinit var nextUpCountdown: TextView

    // Player
    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    // State
    private val handler = Handler(Looper.getMainLooper())
    private var controlsVisible = false

    // Hold-to-scrub acceleration
    private var scrubDirection = 0  // -1 = back, 1 = forward, 0 = idle
    private var scrubStepMs = 0L
    private val scrubHandler = Handler(Looper.getMainLooper())
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            if (controlsVisible) {
                updateClock()
                clockHandler.postDelayed(this, 1000)
            }
        }
    }
    private val scrubRunnable = object : Runnable {
        override fun run() {
            if (scrubDirection == 0) return
            val player = exoPlayer ?: return
            val duration = player.duration.takeIf { it > 0 } ?: return
            val newPos = (player.currentPosition + scrubStepMs * scrubDirection)
                .coerceIn(0L, duration)
            player.seekTo(newPos)
            updateSeekBarPosition(newPos, duration)
            // Accelerate: increase step each tick, cap at 30s
            scrubStepMs = (scrubStepMs + scrubStepMs / 2 + 200L).coerceAtMost(30_000L)
            scrubHandler.postDelayed(this, 80L)
        }
    }

    // Fires once a D-pad key has been held past the quick-press window, switching
    // from a fixed 10s jump into continuous accelerated scrubbing.
    private val scrubHoldStarter = Runnable {
        if (scrubDirection == 0) return@Runnable
        scrubStepMs = 1_000L  // Start accelerating from 1s
        scrubHandler.post(scrubRunnable)
    }

    private var isInPiPMode = false
    private var showSettingsPanel = false
    private var isPickerShowing = false
    private var pickerMode = PickerMode.SOURCE
    private enum class PickerMode { SOURCE, AUDIO, SUBTITLE, SPEED }

    // Playback state
    private var currentUrl = ""
    private var currentAudioUrl: String? = null
    private var currentSourceLabel = "Source 1"
    private var carryPositionMs = 0L
    private var playbackSpeed = 1f
    private var resizeModeIndex = 0
    private var subtitleOffsetMs = 0
    private var subtitleSize = 1
    private var subtitleBackground = 0

    // Stream health
    private var streamWidth = 0
    private var streamHeight = 0
    private var streamBitrate = 0
    private var streamCodec: String? = null
    // Original declared codec of the current video track (e.g. "dvhe.07.06")
    // before any DV→HDR10 rewrite. Used for P5 detection to select correction path.
    private var currentCodecs: String? = null
    // When P5 content is detected and DV conversion is enabled, force FFmpeg
    // for pixel-level ICtCp→HDR10 color conversion.
    private var forceP5SoftwareDecode = false
    // When the black-video watchdog tries TextureView as an automatic fallback
    // after SurfaceView fails (stage 1.5 in the recovery ladder).
    private var forceTextureViewFallback = false
    // The TextureView installed by that fallback (Media3 1.9's PlayerView has
    // no public setSurfaceType, so the internal surface view is swapped via
    // reflection). Kept across player rebuilds so every session routes the
    // player to the same view.
    private var fallbackTextureView: android.view.TextureView? = null
    // Whether P5 color correction via GLES is currently active
    private var p5GlesActive = false
    // One-shot latch: P5 is only known after onTracksChanged delivers the
    // declared (pre-rewrite) codec, which is after the player was built. When
    // that happens before the first frame, rebuild once so the GLES/FFmpeg
    // color path engages from the start.
    private var p5ReroutePending = false
    // Original declared DV codec (e.g. "dvhe.07.06") of the current video
    // track when the DV → HDR10 strip rewrote it; null for everything else.
    // Surfaced in the codec badge so the exact DV profile stays visible
    // after the rewrite (the codec string itself reads as plain HEVC then).
    private var streamDeclaredDvCodec: String? = null

    // Retry
    private var retryAttempt = 0
    private var retryExhausted = false
    private var errorMessageStr: String? = null
    private var forceSoftwareDecoder = false
    // Black-video recovery in the reverse direction: a "Prefer app (FFmpeg)"
    // session (video decoder = FFmpeg) whose renderer never presents a frame
    // is rebuilt with
    // the hardware decoder. Session latch mirroring forceSoftwareDecoder so
    // createPlayer cannot silently undo the watchdog's decision.
    private var forceHardwareDecoder = false
    private var manualRetryToken = 0
    private var rebufferStartedAtMs = 0L

    // Black-video watchdog: some files reach READY with audio playing but the
    // video decoder never produces a frame (silent black screen, no error).
    // Track first-frame rendering and surface an actionable notice instead of
    // letting playback sit on black.
    private var videoTrackPresent = false
    private var streamMimeType: String? = null
    private var firstFrameRendered = false
    private var firstFrameRenderedAtMs = 0L
    private var blackVideoNoticeShown = false
    private var blackVideoSwRetried = false
    // Whether an FFmpeg-only session has already been rebuilt with the
    // hardware decoder (Stage 2 in reverse). Kept alongside the software
    // marker so a second silent failure shows the notice instead of looping.
    private var blackVideoHwRetried = false
    // The current session runs the FFmpeg-only video renderer by persisted
    // choice (video decoder = FFmpeg). Such sessions pre-empt themselves for
    // formats
    // software decode cannot keep up with (4K / 10-bit): as soon as the track
    // format is known the session swaps to the hardware decoder instead of
    // sitting on black until the watchdog fires.
    private var ffmpegOnlySession = false
    private var ffmpegSessionSwappedToHw = false
    // True while the DV setting is "8.1" (P5/P7 converted to Profile 8.1) so
    // the codec badge can report "DV P7 → 8.1" instead of "→ HDR10".
    private var dvTo81Session = false
    // One surface bounce is allowed per player attempt: flipping the
    // SurfaceView's visibility destroys/recreates its native surface and
    // Media3 re-queues output, which recovers the silent "decoder outputs
    // frames but the window lost them" case on some boxes (logcat: 'Could not
    // find corresponding native window for surface') before paying for a full
    // software-decoder rebuild.
    private var blackVideoSurfaceRetried = false
    private var blackVideoWatchdogToken = 0
    private val blackVideoWatchdogMs = 3_000L
    private val blackVideoSurfaceRecheckMs = 2_000L
    private val blackVideoSwTimeoutMs = 12_000L

    // Startup watchdog: the black-video and stall watchdogs are only armed
    // from READY / isPlaying, so a session that never leaves BUFFERING (no
    // first frame ever — a surface whose native window was lost, or a source
    // that goes quiet after the first bytes) previously sat on the splash
    // screen forever with no recovery. This one ticks from prepare() and
    // hands the session to the black-video recovery ladder once it is clear
    // nothing is going to start.
    private var startupWatchdogToken = 0
    private var startupStartedAtMs = 0L
    private var startupLastProgressAtMs = 0L
    private var startupLastPositionMs = -1L
    private var startupLastBufferedMs = -1L
    private var startupSawData = false
    private val startupWatchdogTickMs = 5_000L
    // Before the first byte arrives, be patient: NNTP first-byte waits up to
    // ~90s are legitimate. Once ANY data has arrived, 20s of total silence
    // means the pipe died and the ladder should act.
    private val startupQuietNoDataMs = 90_000L
    private val startupQuietAfterDataMs = 20_000L
    // Absolute cap: even a connection that keeps streaming but never reaches
    // READY must surface an outcome eventually (a 4K file the connection
    // can't sustain would otherwise buffer forever).
    private val startupAbsoluteCapMs = 150_000L

    // Stall watchdog: a server that stops sending mid-stream leaves the
    // player stuck in BUFFERING forever — no error fires, the connection just
    // goes quiet. Track forward progress (position OR buffered position) and
    // after a quiet period recover by seeking just past the buffered edge,
    // forcing a fresh ranged read. Two recoveries, then the retry/error path.
    private var stallWatchdogToken = 0
    private var stallRecoveries = 0
    private var stallLastProgressAtMs = 0L
    private var stallLastPositionMs = -1L
    private var stallLastBufferedMs = -1L
    private val stallNoProgressMs = 12_000L
    private val stallMaxRecoveries = 2
    private val stallTickMs = 2_000L

    // History
    private var isLiveChannel = false
    private var parentId = ""
    private var parentType = ""
    private var season: Int? = null
    private var episode: Int? = null
    private var episodeStreamId: String? = null

    // Lazily-resolved TMDB id for the current parent (any id flavor: imdb,
    // tmdb:, tvdb:, bare numeric). Needed so Simkl scrobbling/history work
    // for TVDB-sourced titles, which Simkl can only match via TMDB.
    private var resolvedParentTmdbId: Int? = null

    private var itemName = ""
    private var itemPoster: String? = null
    private var clearLogoUrl: String? = null
    private var backdropUrl: String? = null
    private var overview: String? = null
    private var sources: List<Stream> = emptyList()
    private var currentSourceIndex = -1
    private var autoSourceSwitchCount = 0
    private val MAX_AUTO_SOURCE_SWITCHES = 2
    private var castMembers: List<PlayerCastMember> = emptyList()
    private var totalEpisodesInSeason: Int? = null
    private var streamHeaders = emptyMap<String, String>()
    private var drmLicenseUrl: String? = null
    private var drmHeaders = emptyMap<String, String>()
    private var externalSubtitleUri: Uri? = null
    private var startPositionMs = 0L
    private var fromActorReturn = false

    /// True once playback has actually started during this player session.
    /// Gates the full splash overlay: it only appears on the very first load,
    /// never on mid-playback rebuffers or when returning from the actor overlay.
    private var hasPlayedOnce = false
    private var historyId = ""
    private var simklScrobbleSent = false
    private var simklSyncJob: kotlinx.coroutines.Job? = null
    private var simklScrobbleActive = false
    private var simklScrobblePaused = false
    private var simklScrobbleJob: kotlinx.coroutines.Job? = null

    // IntroDB
    private var introDbStamps = emptyList<IntroDbStamp>()
    private var activeIntroStamp: IntroDbStamp? = null

    // Settings prefs
    private var enableTunneling = false
    private var bufferMode = 0
    private var autoPlayNext = false

    // Playback-ended fallback state (see detectStallEndedFallback): some
    // sources never emit STATE_ENDED, and these fields de-duplicate the
    // completion path across the real listener and the poller fallback.
    private var playbackEndedHandled = false
    private var lastPolledPos = -1L
    private var posStallTicks = 0
    private var episodeTitle: String? = null
    private var preferredAudioLang = ""
    private var preferredSubtitleLang = ""

    // "Up next" popup state
    private var pendingNextSeason: Int? = null
    private var pendingNextEpisode: Int? = null
    private var pendingNextEpisodeName: String? = null
    private var pendingNextEpisodeRuntime: Int? = null
    private var nextUpCountdownRemaining = 0
    private val nextUpCountdownHandler = Handler(Looper.getMainLooper())
    private val nextUpCountdownRunnable = object : Runnable {
        override fun run() {
            nextUpCountdownRemaining--
            if (nextUpCountdownRemaining <= 0) {
                launchNextEpisode(
                    pendingNextSeason ?: return,
                    pendingNextEpisode ?: return,
                    pendingNextEpisodeName,
                    pendingNextEpisodeRuntime
                )
            } else {
                nextUpCountdown.text = "Playing next in $nextUpCountdownRemaining"
                nextUpCountdownHandler.postDelayed(this, 1_000L)
            }
        }
    }

    // Scopes
    private var scope: CoroutineScope? = null

    private val externalSubtitlePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some providers do not offer persistable permissions; the current
                // playback session can still use the granted URI permission.
            }
            externalSubtitleUri = uri
            carryPositionMs = exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L
            recreatePlayer()
    }

}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Hide system bars
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContentView(R.layout.activity_player)
        bindViews()
        playerView.post {
            if (playerView is android.view.SurfaceView) {
                playerView.visibility = android.view.View.INVISIBLE
                playerView.post { playerView.visibility = android.view.View.VISIBLE }
            }
        }
        findViewById<View>(R.id.player_root).setOnClickListener {
            if (controlsVisible) hideControls() else showControls()
        }
        playerView.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                if (controlsVisible) hideControls() else showControls()
            }
            true
        }
        playerView.setOnClickListener { if (controlsVisible) hideControls() else showControls() }
        controlsOverlay.isFocusable = true
        controlsOverlay.isFocusableInTouchMode = true
        controlsOverlay.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        controlsOverlay.isClickable = true
        controlsOverlay.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    // Only consume Back when there's actually something to
                    // dismiss (a panel, the settings sheet, or visible
                    // controls). Otherwise let it fall through to
                    // onBackPressed so a Back press always exits the player
                    // instead of being silently swallowed.
                    if (isPickerShowing || showSettingsPanel || controlsVisible) {
                        dismissAllPanels(); hideControls(); true
                    } else {
                        false
                    }
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { true }
                else -> false
            }
        }

        // Extract intent data
        currentUrl = intent.getStringExtra("stream_url").orEmpty()
        if (currentUrl.isBlank()) { finish(); return }
        currentAudioUrl = intent.getStringExtra("audio_url")
        parentId = intent.getStringExtra("parent_id").orEmpty()
        parentType = intent.getStringExtra("parent_type").orEmpty()
        isLiveChannel = parentType == "channel"
        season = intent.getIntExtra("season", -1).takeIf { it >= 0 }
        episode = intent.getIntExtra("episode", -1).takeIf { it >= 0 }
        episodeStreamId = intent.getStringExtra("episode_stream_id")
        itemName = intent.getStringExtra("item_name")
            ?.takeIf { it.isNotBlank() }
            ?: intent.getStringExtra("display_name").orEmpty()
        itemPoster = intent.getStringExtra("item_poster")
        clearLogoUrl = intent.getStringExtra("clear_logo_url")
        backdropUrl = intent.getStringExtra("backdrop_url")
        overview = intent.getStringExtra("item_overview")
        episodeTitle = intent.getStringExtra("episode_title")
        startPositionMs = intent.getLongExtra("start_position_ms", 0L)
        fromActorReturn = intent.getBooleanExtra("from_actor_return", false)
        carryPositionMs = startPositionMs
        streamHeaders = parseHeaders(intent.getStringExtra(EXTRA_HEADERS).orEmpty())
        drmLicenseUrl = intent.getStringExtra(EXTRA_DRM_LICENSE_URL)
        drmHeaders = parseHeaders(intent.getStringExtra(EXTRA_DRM_HEADERS).orEmpty())
        resizeModeIndex = AppPreferences.getDefaultAspectRatio(this)
        enableTunneling = AppPreferences.getEnableTunneling(this)
        bufferMode = AppPreferences.getDefaultBufferMode(this)
        autoPlayNext = AppPreferences.getAutoPlayNext(this)
        preferredAudioLang = AppPreferences.getPreferredAudioLanguage(this)
        preferredSubtitleLang = AppPreferences.getPreferredSubtitleLanguage(this)
        totalEpisodesInSeason = intent.getIntExtra("total_episodes_in_season", -1).takeIf { it > 0 }

        // Keep the currently selected stream available even when the caller did not
        // provide a complete source list.
        val selectedStream = Stream(name = "Current source", title = null, url = currentUrl, audioUrl = currentAudioUrl)
        sources = listOf(selectedStream)

        // Parse sources from JSON
        val sourcesJson = intent.getStringExtra("sources_json")
        if (!sourcesJson.isNullOrBlank()) {
            try {
                val arr = JSONArray(sourcesJson)
                sources = (0 until arr.length()).mapNotNull { i ->
                    val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                    Stream(
                        name = obj.optString("name", null),
                        title = obj.optString("title", null),
                        description = obj.optString("description", null),
                        url = obj.optString("url", null),
                        audioUrl = obj.optString("audioUrl", null),
                        infoHash = obj.optString("infoHash", null),
                        fileIdx = obj.optInt("fileIdx", -1).takeIf { it >= 0 }
                    )
                }.filter { !it.url.isNullOrBlank() }
                if (sources.none { it.url == currentUrl }) {
                    sources = listOf(selectedStream) + sources
                }
            } catch (e: Exception) {
                Log.w("NativePlayer", "Failed to parse sources_json", e)
            }
        }

        // Parse cast from JSON
        val castJson = intent.getStringExtra("cast_json")
        if (!castJson.isNullOrBlank()) {
            try {
                val arr = JSONArray(castJson)
                castMembers = (0 until arr.length()).mapNotNull { i ->
                    val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                    PlayerCastMember(
                        id = obj.optInt("id", 0),
                        name = obj.optString("name", ""),
                        character = obj.optString("character", null),
                        profilePath = obj.optString("profilePath", null)
                    )
                }.filter { it.name.isNotBlank() }
            } catch (e: Exception) {
                Log.w("NativePlayer", "Failed to parse cast_json", e)
            }
        }

        // Populate cast row
        if (castMembers.isNotEmpty()) {
            castSection.visibility = View.VISIBLE
            castRow.removeAllViews()
            castMembers.forEach { member ->
                val itemView = layoutInflater.inflate(R.layout.cast_member_item, castRow, false)
                val nameText = itemView.findViewById<TextView>(R.id.cast_member_name)
                val charText = itemView.findViewById<TextView>(R.id.cast_member_character)
                val profileImage = itemView.findViewById<ImageView>(R.id.cast_member_image)

                nameText.text = member.name
                if (member.character.isNullOrBlank()) {
                    charText.visibility = View.GONE
                } else {
                    charText.text = member.character
                    charText.visibility = View.VISIBLE
                }
                        val profileUrl = member.profilePath
                    ?.trim()
                    ?.let { path ->
                        when {
                            path.startsWith("http://") || path.startsWith("https://") -> path
                            path.startsWith("/") -> "https://image.tmdb.org/t/p/original$path"
                            else -> "https://image.tmdb.org/t/p/original/$path"
                        }
                    }
                if (!profileUrl.isNullOrBlank()) {
                    try {
                        profileImage.load(profileUrl)
                    } catch (e: Exception) {
                        Log.w("NativePlayer", "Failed to load cast image: $profileUrl", e)
                        profileImage.setImageResource(R.drawable.ic_cast_placeholder)
                    }
                } else {
                    profileImage.setImageResource(R.drawable.ic_cast_placeholder)
                }

                itemView.isClickable = true
                itemView.isFocusable = true
                itemView.isFocusableInTouchMode = true
                itemView.setOnClickListener {
                    // Save current position before navigating away
                    val currentPosition = exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: carryPositionMs
                    setResult(RESULT_OK, Intent().apply {
                        putExtra("player_result_action", "navigate_actor")
                        putExtra("actor_person_id", member.id)
                        putExtra("actor_resume_position_ms", currentPosition)
                    })
                    finish()
                }

                castRow.addView(itemView)
            }
        }

        historyId = when {
            !episodeStreamId.isNullOrBlank() -> episodeStreamId!!
            season != null && episode != null -> "$parentId:${season}:${episode}"
            else -> parentId
        }

        currentSourceLabel = sources.firstOrNull { it.url == currentUrl }?.displayLabel()
            ?: sources.firstOrNull()?.displayLabel()
            ?: "Current source"
        currentSourceIndex = sources.indexOfFirst { it.url == currentUrl }

        // Now that season/episode are parsed, set dynamic visibility
        btnNext.visibility = if (season != null && episode != null) View.VISIBLE else View.GONE

        updateHeaderInfo()
        updateControlsInfo()

        setupListeners()
        setupKeyboardHandler()
        setupIntroDb()
        createPlayer()
    }

    private fun bindViews() {
        playerView = findViewById(R.id.player_view)
        p5VideoGlesView = findViewById(R.id.p5_video_gles_view)
        liveBadge = findViewById(R.id.live_badge)
        bufferingSpinner = findViewById(R.id.buffering_spinner)
        reconnectingContainer = findViewById(R.id.reconnecting_container)
        reconnectingText = findViewById(R.id.reconnecting_text)
        errorContainer = findViewById(R.id.error_container)
        errorTitle = findViewById(R.id.error_title)
        errorMessage = findViewById(R.id.error_message)
        btnRetry = findViewById(R.id.btn_retry)
        btnChangeSource = findViewById(R.id.btn_change_source)
        btnSkipIntro = findViewById(R.id.btn_skip_intro)
        controlsOverlay = findViewById(R.id.controls_overlay)
        playerClock = findViewById(R.id.player_clock)
        endsAtClock = findViewById(R.id.ends_at_clock)
        splashContainer = findViewById(R.id.splash_container)
        splashBackdrop = findViewById(R.id.splash_backdrop)
        splashClearLogo = findViewById(R.id.splash_clear_logo)
        clearLogo = findViewById(R.id.clear_logo)
        itemNameView = findViewById(R.id.item_name)
        episodeLabel = findViewById(R.id.episode_label)
        episodeTitleView = findViewById(R.id.episode_title)
        sourceLabel = findViewById(R.id.source_label)
        streamHealth = findViewById(R.id.stream_health)
        overviewText = findViewById(R.id.overview_text)
        seekbarRow = findViewById(R.id.seekbar_row)
        seekbar = findViewById(R.id.seekbar)
        currentTime = findViewById(R.id.current_time)
        totalTime = findViewById(R.id.total_time)
        btnPlayPause = findViewById(R.id.btn_play_pause)
        btnNext = findViewById(R.id.btn_next)
        btnSource = findViewById(R.id.btn_source)
        btnAudio = findViewById(R.id.btn_audio)
        btnSubtitle = findViewById(R.id.btn_subtitle)
        btnSpeed = findViewById(R.id.btn_speed)
        btnAspect = findViewById(R.id.btn_aspect)
        btnSettings = findViewById(R.id.btn_settings)
        pickerContainer = findViewById(R.id.picker_container)
        pickerTitle = findViewById(R.id.picker_title)
        settingsContainer = findViewById(R.id.settings_container)
        settingsContainer.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    dismissSettingsPanel(); showControls(); true
                }
                else -> false
            }
        }
        // Global focus listener: if settings panel is open and focus escapes, snap back
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            if (showSettingsPanel && newFocus != null && !isDescendantOf(newFocus, settingsContainer)) {
                // Focus left the settings panel — snap back to first focusable inside it
                settingsContainer.post {
                    val inner = settingsContainer.getChildAt(0) as? android.view.ViewGroup
                    if (inner != null) {
                        for (i in 0 until inner.childCount) {
                            val v = inner.getChildAt(i)
                            if (v.isFocusable) {
                                v.requestFocus()
                                break
                            }
                        }
                    }
                }
            }
        }

        scrim = findViewById(R.id.scrim)
        settingsBufferAuto = findViewById(R.id.btn_buffer_auto)
        settingsBufferBalanced = findViewById(R.id.btn_buffer_balanced)
        settingsBufferLow = findViewById(R.id.btn_buffer_low)
        btnTunneling = findViewById(R.id.btn_tunneling)
        btnAutoplay = findViewById(R.id.btn_autoplay)
        settingsResolution = findViewById(R.id.settings_resolution)
        settingsBitrate = findViewById(R.id.settings_bitrate)
        settingsCodec = findViewById(R.id.settings_codec)
        settingsSpeedAspect = findViewById(R.id.settings_speed_aspect)
        pickerList = findViewById(R.id.picker_list)
        btnSubSmall = findViewById(R.id.btn_sub_small)
        btnSubNormal = findViewById(R.id.btn_sub_normal)
        btnSubLarge = findViewById(R.id.btn_sub_large)
        btnSubBgNone = findViewById(R.id.btn_sub_bg_none)
        btnSubBgSemi = findViewById(R.id.btn_sub_bg_semi)
        btnSubBgSolid = findViewById(R.id.btn_sub_bg_solid)
        btnOffsetMinus = findViewById(R.id.btn_offset_minus)
        subtitleOffsetValue = findViewById(R.id.subtitle_offset_value)
        btnOffsetPlus = findViewById(R.id.btn_offset_plus)
        castSection = findViewById(R.id.cast_section)
        castRow = findViewById(R.id.cast_row)
        nextUpPanel = findViewById(R.id.next_up_panel)
        nextUpThumb = findViewById(R.id.next_up_thumb)
        nextUpShowTitle = findViewById(R.id.next_up_show_title)
        nextUpEpisodeLabel = findViewById(R.id.next_up_episode_label)
        nextUpEpisodeTitle = findViewById(R.id.next_up_episode_title)
        btnNextPlay = findViewById(R.id.btn_next_play)
        btnNextDismiss = findViewById(R.id.btn_next_dismiss)
        nextUpCountdown = findViewById(R.id.next_up_countdown)

        applyPillState(btnNextPlay, true)
        applyPillState(btnNextDismiss, false)

        pickerList.layoutManager = LinearLayoutManager(this)
        pickerList.isFocusable = true
        pickerList.isFocusableInTouchMode = true
        pickerList.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        pickerList.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                dismissPicker(); showControls(); true
            } else false
        }

        // Static UI
        liveBadge.visibility = if (isLiveChannel) View.VISIBLE else View.GONE
        btnSource.visibility = View.VISIBLE
        sourceLabel.text = "Source: $currentSourceLabel"

        // Populate header info
        updateHeaderInfo()
        updateSettingsPanelState()
    }

    private fun setupListeners() {
        // Play/Pause
        btnPlayPause.setOnClickListener { togglePlayPause() }

        // Skip intro
        btnSkipIntro.setOnFocusChangeListener { _, focused ->
            if (focused) removeAutoHide() else scheduleAutoHide()
        }
        btnSkipIntro.setOnClickListener {
            val stamp = activeIntroStamp ?: return@setOnClickListener
            val durationMs = exoPlayer?.duration ?: 0L
            val targetMs = if (durationMs > 0L && durationMs != C.TIME_UNSET) {
                stamp.endMs.coerceAtMost(durationMs)
            } else stamp.endMs
            exoPlayer?.seekTo(targetMs)
            activeIntroStamp = null
            btnSkipIntro.visibility = View.GONE
        }

        // Retry
        btnRetry.setOnClickListener {
            retryAttempt = 0
            retryExhausted = false
            errorMessageStr = null
            manualRetryToken++
            recreatePlayer()
        }

        // Change source
        btnChangeSource.setOnClickListener {
            showPicker(PickerMode.SOURCE)
        }

        // Overlay control buttons
        btnNext.setOnClickListener {
            val target = nextEpisodeTarget() ?: return@setOnClickListener
            launchNextEpisode(target.first, target.second)
        }
        btnSource.setOnClickListener { showPicker(PickerMode.SOURCE) }

        // "Up next" popup buttons
        btnNextPlay.setOnClickListener {
            launchNextEpisode(
                pendingNextSeason ?: return@setOnClickListener,
                pendingNextEpisode ?: return@setOnClickListener,
                pendingNextEpisodeName,
                pendingNextEpisodeRuntime
            )
        }
        btnNextDismiss.setOnClickListener { finish() }
        btnSource.setOnFocusChangeListener { _, focused -> if (focused) removeAutoHide() else scheduleAutoHide() }
        btnAudio.setOnClickListener { showPicker(PickerMode.AUDIO) }
        btnAudio.setOnFocusChangeListener { _, focused -> if (focused) removeAutoHide() else scheduleAutoHide() }
        btnSubtitle.setOnClickListener { showPicker(PickerMode.SUBTITLE) }
        btnSpeed.setOnClickListener { showPicker(PickerMode.SPEED) }
        btnAspect.setOnClickListener {
            resizeModeIndex = (resizeModeIndex + 1) % 3
            applyResizeMode(when (resizeModeIndex) {                    1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            })
            val labels = listOf("Fit", "Zoom", "Fill")
            btnAspect.text = labels[resizeModeIndex]
            AppPreferences.setDefaultAspectRatio(this, resizeModeIndex)
            scheduleAutoHide()
        }
        btnAspect.setOnFocusChangeListener { _, focused -> if (focused) removeAutoHide() else scheduleAutoHide() }
        btnSettings.setOnClickListener { toggleSettingsPanel() }
        btnSettings.setOnFocusChangeListener { _, focused -> if (focused) removeAutoHide() else scheduleAutoHide() }

        // Scrim (dismiss panels)
        scrim.setOnClickListener { dismissAllPanels() }

        // Seekbar
        seekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val durationMs = exoPlayer?.duration ?: 0L
                    val posMs = (progress.toLong() * durationMs) / 10_000L
                    currentTime.text = formatMillis(posMs)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {
                removeAutoHide()
            }
            override fun onStopTrackingTouch(sb: SeekBar) {
                val durationMs = exoPlayer?.duration ?: 0L
                val posMs = (sb.progress.toLong() * durationMs) / 10_000L
                exoPlayer?.seekTo(posMs)
                scheduleAutoHide()
            }
        })

        // Quick-press = 10s jump; holding (past 400ms) = accelerated scrubbing.
        seekbar.setOnKeyListener { _, keyCode, event ->
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    when (event.action) {
                        KeyEvent.ACTION_DOWN -> {
                            if (event.repeatCount == 0 && scrubDirection == 0) {
                                scrubDirection = if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                                removeAutoHide()
                                // Immediate step for a quick press/release
                                stepSeekBy(10_000L * scrubDirection)
                                // If still held past the threshold, switch to fast scrubbing
                                scrubHandler.removeCallbacks(scrubHoldStarter)
                                scrubHandler.postDelayed(scrubHoldStarter, 400L)
                            }
                            true
                        }
                        KeyEvent.ACTION_UP -> {
                            scrubDirection = 0
                            scrubHandler.removeCallbacks(scrubHoldStarter)
                            scrubHandler.removeCallbacks(scrubRunnable)
                            commitSeekFromBar()
                            scheduleAutoHide()
                            true
                        }
                        else -> false
                    }
                }
                else -> false
            }
        }

        // Settings toggles
        settingsBufferAuto.setOnClickListener {
            bufferMode = 2
            AppPreferences.setDefaultBufferMode(this, 2)
            updateSettingsPanelState()
        }
        settingsBufferBalanced.setOnClickListener {
            bufferMode = 0
            AppPreferences.setDefaultBufferMode(this, 0)
            updateSettingsPanelState()
        }
        settingsBufferLow.setOnClickListener {
            bufferMode = 1
            AppPreferences.setDefaultBufferMode(this, 1)
            updateSettingsPanelState()
        }
        btnTunneling.setOnClickListener {
            enableTunneling = !enableTunneling
            AppPreferences.setEnableTunneling(this, enableTunneling)
            updateSettingsPanelState()
            recreatePlayer()
        }
        btnAutoplay.setOnClickListener {
            autoPlayNext = !autoPlayNext
            AppPreferences.setAutoPlayNext(this, autoPlayNext)
            updateSettingsPanelState()
        }

        // Subtitle size
        btnSubSmall.setOnClickListener {
            subtitleSize = 0; AppPreferences.setDefaultSubtitleSize(this, 0)
            updateSubtitleSettings()
            applySubtitleStyle()
        }
        btnSubNormal.setOnClickListener {
            subtitleSize = 1; AppPreferences.setDefaultSubtitleSize(this, 1)
            updateSubtitleSettings()
            applySubtitleStyle()
        }
        btnSubLarge.setOnClickListener {
            subtitleSize = 2; AppPreferences.setDefaultSubtitleSize(this, 2)
            updateSubtitleSettings()
            applySubtitleStyle()
        }

        // Subtitle background
        btnSubBgNone.setOnClickListener {
            subtitleBackground = 0; AppPreferences.setDefaultSubtitleBackground(this, 0)
            updateSubtitleSettings()
            applySubtitleStyle()
        }
        btnSubBgSemi.setOnClickListener {
            subtitleBackground = 1; AppPreferences.setDefaultSubtitleBackground(this, 1)
            updateSubtitleSettings()
            applySubtitleStyle()
        }
        btnSubBgSolid.setOnClickListener {
            subtitleBackground = 2; AppPreferences.setDefaultSubtitleBackground(this, 2)
            updateSubtitleSettings()
            applySubtitleStyle()
        }

        // Subtitle offset
        btnOffsetMinus.setOnClickListener {
            subtitleOffsetMs = (subtitleOffsetMs - 500).coerceAtLeast(-5000)
            subtitleOffsetValue.text = "${subtitleOffsetMs}ms"
        }
        btnOffsetPlus.setOnClickListener {
            subtitleOffsetMs = (subtitleOffsetMs + 500).coerceAtMost(5000)
            subtitleOffsetValue.text = "${subtitleOffsetMs}ms"
    }

}

    /**
     * Switches the PlayerView's internal video surface to a TextureView (or
     * back to a SurfaceView) at runtime. Media3 1.9's PlayerView only reads
     * app:surface_type when it is constructed — there is no public
     * setSurfaceType() — so the private surfaceView field's view is swapped
     * inside the content frame instead, before the player is attached.
     * PlayerView.setPlayer then routes the player to the new surface via the
     * public setVideoTextureView / setVideoSurfaceView calls.
     *
     * This is the black-video watchdog's TextureView fallback: on some boxes
     * the SurfaceView's native window is lost (logcat: 'Could not find
     * corresponding native window for surface') — the decoder produces frames
     * but output goes nowhere. TextureView renders through the view hierarchy
     * instead of a separate native window, which is why other apps play the
     * same file on the same TV.
     */
    private fun switchPlayerViewSurface(wantTexture: Boolean) {
        val currentIsTexture = try {
            val f = playerView.javaClass.getDeclaredField("surfaceView")
            f.isAccessible = true
            (f.get(playerView) as? android.view.View) is android.view.TextureView
        } catch (e: Exception) {
            Log.w("PLAYER_VIDEO", "Could not read PlayerView surface type", e)
            false
        }
        if (currentIsTexture == wantTexture) return

        try {
            // Detach the stale player first: PlayerView.setPlayer(null) clears
            // the old surface from it while the field still points at the
            // outgoing view.
            val attached = playerView.player
            playerView.player = null

            val clazz = playerView.javaClass
            val surfaceField = clazz.getDeclaredField("surfaceView")
            surfaceField.isAccessible = true
            val oldSurface = surfaceField.get(playerView) as? android.view.View
            val contentFrame = findPlayerViewContentFrame()
            if (contentFrame == null) {
                Log.w("PLAYER_VIDEO", "Surface switch: PlayerView content frame not found")
                if (attached != null) playerView.player = attached
                return
            }

            val newSurface: android.view.View = if (wantTexture) {
                android.view.TextureView(this)
            } else {
                android.view.SurfaceView(this).apply {
                    // Mirror PlayerView's own construction: on Android 14+ the
                    // surface lifecycle follows attachment so the surface is
                    // not torn down when the view is briefly detached.
                    if (Build.VERSION.SDK_INT >= 34) {
                        setSurfaceLifecycle(android.view.SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT)
                    }
                }
            }
            newSurface.layoutParams = oldSurface?.layoutParams
                ?: android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
            // PlayerView's own internal surface is clickable=false so touch
            // events bubble to the activity's playerView listeners; keep that.
            newSurface.isClickable = false

            oldSurface?.let { contentFrame.removeView(it) }
            contentFrame.addView(newSurface, 0)
            try {
                surfaceField.set(playerView, newSurface)
            } catch (e: Exception) {
                // Final-field write refused (exotic ART): the explicit
                // setVideoTextureView re-assert in createPlayer still routes
                // the player to the new surface (last call wins).
                Log.w("PLAYER_VIDEO", "Could not swap PlayerView surface field", e)
            }
            if (wantTexture) {
                fallbackTextureView = newSurface as android.view.TextureView
            } else {
                fallbackTextureView = null
            }
            Log.i(
                "PLAYER_VIDEO",
                "Switched PlayerView video surface to ${if (wantTexture) "TextureView" else "SurfaceView"}"
            )
        } catch (e: Exception) {
            Log.w("PLAYER_VIDEO", "Surface switch failed", e)
        }
    }

    private fun findPlayerViewContentFrame(): android.view.ViewGroup? {
        fun walk(v: android.view.View?): android.view.ViewGroup? {
            if (v == null) return null
            if (v is AspectRatioFrameLayout) return v
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) {
                    walk(v.getChildAt(i))?.let { return it }
                }
            }
            return null
        }
        return walk(playerView)
    }

    private fun setupKeyboardHandler() {
        playerView.isFocusable = true
        playerView.isClickable = true
        playerView.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && controlsVisible) controlsOverlay.requestFocus()
        }
        playerView.isFocusableInTouchMode = true
        playerView.requestFocus()
        playerView.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    exoPlayer?.pause(); showControls(); true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    exoPlayer?.play(); hideControls(); true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    togglePlayPause(); true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_SELECT -> {
                    if (errorContainer.visibility == View.VISIBLE) {
                        focusErrorButtons()
                        true
                    } else {
                        if (!controlsVisible) showControls() else hideControls()
                        true
                    }
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (errorContainer.visibility == View.VISIBLE) {
                        focusErrorButtons()
                        true
                    } else {
                        showControls()
                        controlsOverlay.requestFocus()
                        true
                    }
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (isPickerShowing || showSettingsPanel) { dismissAllPanels(); true } else false
                }
                else -> false
            }
    }

}

    // --- Player Creation ---
    private fun createPlayer() {
        // Fresh attempt at the current URL: reset per-attempt state so the
        // black-video watchdog can re-arm and report at most once per attempt.
        videoTrackPresent = false
        firstFrameRendered = false
        blackVideoNoticeShown = false
        p5ReroutePending = false
        // The surface reset is cheap and valid on every attempt (including the
        // software one); only the software retry itself is once-per-session.
        blackVideoSurfaceRetried = false
        // blackVideoWatchdogToken++ # delayed to allow native window recovery
        ffmpegOnlySession = false
        ffmpegSessionSwappedToHw = false
        // Reset the black-video software-decoder retry only for fresh attempts
        // (new stream / source switch). The software retry itself must keep its
        // marker so a second silent failure shows the notice instead of looping.
        if (!forceSoftwareDecoder) blackVideoSwRetried = false
        // Same rule for the reverse retry (FFmpeg-only session -> hardware).
        if (!forceHardwareDecoder) blackVideoHwRetried = false
        autoSourceSwitchCount = 0

        val agent = streamHeaders["User-Agent"] ?: streamHeaders["user-agent"]
            ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
        // Some addon hosts / CDNs need more than the default 20 s connect
        // timeout, especially during peak hours or on first-byte waits.
        // Give every playback attempt a slightly more generous ceiling so
        // a slow-but-valid source does not get killed before the retry
        // ladder can act. The startup / stall / black-video watchdogs still
        // bound total wait time.
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(30L, TimeUnit.SECONDS)
            .readTimeout(60L, TimeUnit.SECONDS)
            .build()
        val httpFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent(agent)

        val extraHeaders = streamHeaders
            .filterKeys { !it.equals("User-Agent", ignoreCase = true) }
            .filterValues { it.isNotBlank() }
        if (extraHeaders.isNotEmpty()) httpFactory.setDefaultRequestProperties(extraHeaders)

        // Dolby Vision handling (Settings → Playback): in the Auto mode only
        // dual-layer Profile 7 (remuxes) is rewritten to HDR10 on the fly — the
        // other profiles pass through untouched so a Dolby-Vision display plays
        // them as real DV. "All DV" rewrites every profile (4/5/7/8) for
        // displays without Dolby Vision (P5 has no HDR10 base, so it is
        // force-decoded as plain HEVC — best effort, colors may be off). "8.1"
        // converts P5 and P7 to Profile 8.1 in the bitstream (RPU metadata per
        // dovi_tool convert mode 2, EL dropped, single-layer VPS, dvhe/dvh1.08)
        // for Dolby Vision displays that accept 8.1 but not Blu-ray P7 or
        // ICtCp P5; P4/P8 pass through as native DV. Off plays DV exactly as
        // provided. HDR10+ (ST 2094-40) stripping is an independent toggle
        // that composes with any DV mode; with DV = Off it strips HDR10+ only
        // and never touches DV.
        val dvCompatMode = AppPreferences.getDvCompatMode(this)
        val stripHdr10Plus = AppPreferences.getStripHdr10Plus(this)
        val videoDecoder = AppPreferences.getVideoDecoder(this)
        val audioDecoderPriority = AppPreferences.getAudioDecoder(this)
        val dvRewriteEnabled = dvCompatMode != AppPreferences.DV_COMPAT_OFF
        val convertAllProfiles = dvCompatMode == AppPreferences.DV_COMPAT_ALL
        // "8.1" mode: convert declared P5/P7 streams to Profile 8.1 instead of
        // stripping to HDR10 (see DolbyVisionCompatExtractorsFactory). The P5
        // GLES/FFmpeg color path below stays engaged — the pixels remain ICtCp
        // after the bitstream rewrite, so the color converter is what makes a
        // P5 → 8.1 stream look right on the display.
        val convertTo81 = dvCompatMode == AppPreferences.DV_COMPAT_TO_81
        dvTo81Session = convertTo81 // badge: "DV P7 → 8.1"
        // P5 (single-layer ICtCp) content needs color conversion for correct HDR colors.
        // When P5 is detected and DV conversion is enabled, we have two options:
        // 1. Force FFmpeg software decoder (true pixel-level conversion, slower)
        // 2. Use GLSurfaceView with ICtCp→PQ shader (GPU-accelerated, faster for 4K)
        // The hardware decoder would output ICtCp pixel values that the display interprets as Rec.2020 PQ
        // (giving wrong colors). Reset each attempt so the setting only applies to the
        // current stream.
        val p5Content = currentCodecs?.let { DolbyVisionCompat.isP5Profile(it) } ?: false
        val userWantsFfmpeg = videoDecoder == AppPreferences.VIDEO_DECODER_FFMPEG
        // Decide which video path to use for P5 content:
        // - P5VideoGlesView: P5 + no FFmpeg + GLES available (GPU color conversion)
        // - FFmpeg: P5 + user selected software decoder, or GLES unavailable
        // - PlayerView: everything else
        val useP5GlesView = p5Content &&
            !forceSoftwareDecoder &&
            !userWantsFfmpeg &&
            P5ColorShader.hasGles3()
        if (useP5GlesView && !p5GlesActive) {
            Log.i(
                "PLAYER_DV",
                "P5 content detected, no FFmpeg — activating GLSurfaceView color correction"
            )
            playerView.visibility = View.GONE
            p5VideoGlesView.visibility = View.VISIBLE
            p5GlesActive = true
        } else if (!useP5GlesView && p5GlesActive) {
            p5VideoGlesView.release()
            p5VideoGlesView.visibility = View.GONE
            playerView.visibility = View.VISIBLE
            p5GlesActive = false
        }
        // Force the FFmpeg fallback only when the GLES path can't run (user
        // picked software, or the device lacks GLES). The previous version
        // forced software for every P5, which made the GLES branch above dead
        // code — hardware P5 without either correction renders ICtCp pixels as
        // Rec.2020 PQ (green tint).
        if (p5Content && dvRewriteEnabled && !forceSoftwareDecoder && !useP5GlesView) {
            forceP5SoftwareDecode = true
            forceSoftwareDecoder = true
            Log.i(
                "PLAYER_DV",
                "P5 (ICtCp) content detected — forcing FFmpeg software decoder " +
                    "for ICtCp→HDR10 color conversion"
            )
        }
        // Audio extension mode follows the independent audio decoder priority
        // (Nuvio-style): 0 = device only (no FFmpeg at all), 1 = FFmpeg
        // fallback behind MediaCodec, 2 = prefer FFmpeg — decoding DTS/TrueHD
        // ahead of MediaCodec passthrough, which is silent on TVs without a
        // DTS-capable sink.
        val audioExtMode = when (audioDecoderPriority) {
            AppPreferences.AUDIO_DECODER_DEVICE_ONLY ->
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
            AppPreferences.AUDIO_DECODER_PREFER_APP ->
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            else -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
        }
        // Keep the automatic fallback latch separate from the persisted mode.            // A retry sets forceSoftwareDecoder before recreatePlayer(); overwriting
        // it here would silently switch that retry back to MediaCodec.
        //
        // The Video Decoder = "FFmpeg (software)" choice uses the software
        // renderer as a VOD compatibility fallback for DV material the
        // hardware decoder cannot take as-is (green tint). It is bypassed
        // where it cannot help: live IPTV is plain H.264/HEVC HLS that the
        // TV's hardware decoder handles reliably, and when the DV → HEVC
        // rewrite is active the output stream is plain HEVC built precisely
        // so the hardware decoder can play it. The experimental FFmpeg
        // renderer has also produced black-video-with-audio on this TV, so the
        // black-video watchdog can flip such a session to hardware
        // (forceHardwareDecoder). An explicit software retry
        // (forceSoftwareDecoder) still wins so the Auto recovery keeps working.
        val softwareDecoderActive =
            forceSoftwareDecoder ||
                (videoDecoder == AppPreferences.VIDEO_DECODER_FFMPEG &&
                    !isLiveChannel && !dvRewriteEnabled && !forceHardwareDecoder)
        // Remember that this session is FFmpeg-video BY CHOICE (not a
        // forceSoftwareDecoder retry) so onTracksChanged can pre-empt it for
        // formats the software renderer cannot decode in real time.
        ffmpegOnlySession = videoDecoder == AppPreferences.VIDEO_DECODER_FFMPEG &&
            !isLiveChannel && !dvRewriteEnabled && !forceHardwareDecoder
        Log.i(
            "PLAYER_DV",
            "DV settings mode=$dvCompatMode rewriteEnabled=$dvRewriteEnabled " +
                "allProfiles=$convertAllProfiles to81=$convertTo81 stripHdr10Plus=$stripHdr10Plus " +
                "videoDecoder=$videoDecoder audioDecoder=$audioDecoderPriority " +
                "forceSoftware=$softwareDecoderActive " +
                "audioSeparate=${!currentAudioUrl.isNullOrBlank()}"
        )
        // The compat extractor is needed when DV conversion is on OR the
        // HDR10+ strip toggle is on — both run inside it.
        val extractorsFactory: androidx.media3.extractor.ExtractorsFactory =
            if (!dvRewriteEnabled && !stripHdr10Plus) {
                DefaultExtractorsFactory()
            } else {
                DolbyVisionCompatExtractorsFactory(
                    DefaultExtractorsFactory(),
                    stripHdr10Plus = stripHdr10Plus,
                    convertAllProfiles = convertAllProfiles,
                    dvRewriteEnabled = dvRewriteEnabled,
                    convertTo81 = convertTo81
                )
            }
        val mediaSourceFactory = DefaultMediaSourceFactory(httpFactory, extractorsFactory)
        // DefaultMediaSourceFactory selects HLS/DASH by URI or MIME type and
        // otherwise falls back to progressive extraction. Build that fallback
        // explicitly so direct stream endpoints (which commonly have no file
        // extension) cannot skip the custom DV extractor.
        val progressiveMediaSourceFactory =
            ProgressiveMediaSource.Factory(httpFactory, extractorsFactory)
        Log.i(
            "PLAYER_DV",
            "Compat extractor configured=${extractorsFactory.javaClass.simpleName} " +
                "progressiveSource=${progressiveMediaSourceFactory.javaClass.simpleName}"
        )

        val mimeType = if (retryAttempt < 3) resolveMimeType(currentUrl) else null
        val mediaItemBuilder = MediaItem.Builder().setUri(currentUrl)
        if (mimeType != null) mediaItemBuilder.setMimeType(mimeType)

        // DRM: set license URL and headers on the MediaItem so ExoPlayer's
        // built-in DRM negotiation handles Widevine playback.
        if (!drmLicenseUrl.isNullOrBlank()) {
            val drmHeaders = drmHeaders
            mediaItemBuilder.setDrmConfiguration(
                MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                    .setLicenseUri(drmLicenseUrl)
                    .apply {
                        if (drmHeaders.isNotEmpty()) {
                            setLicenseRequestHeaders(drmHeaders)
                        }
                    }
                    .build()
            )
            Log.i("PLAYER_DRM", "Widevine DRM configured: $drmLicenseUrl")
        }

        val resolvedBufferMode = if (bufferMode == 2) {
            // Auto: detect IPTV from parentType or .m3u8 URL
            if (isLiveChannel || currentUrl.lowercase().endsWith(".m3u8")) 1 else 0
        } else bufferMode
        // Media3 validates minBufferMs >= bufferForPlaybackAfterRebufferMs
        // (DefaultLoadControl.Builder throws IllegalArgumentException
        // otherwise). The old IPTV config (2500/10000/1500/3000) violated
        // that and force-closed the player on every IPTV start.
        val bufferDurations = if (resolvedBufferMode == 1) intArrayOf(5_000, 10_000, 1_500, 3_000)
        else intArrayOf(10_000, 30_000, 3_000, 6_000)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(bufferDurations[0], bufferDurations[1], bufferDurations[2], bufferDurations[3])
            .setPrioritizeTimeOverSizeThresholds(true).build()

        // Video and audio are independent. Software video only runs when the
        // video decoder says FFmpeg AND its guards permit it; otherwise video
        // extension mode is ON ("Prefer device": FFmpeg fallback behind
        // hardware) or OFF (an FFmpeg session bypassed for live/DV-rewrite).
        val renderersFactory = if (softwareDecoderActive) {
            FfmpegOnlyRenderersFactory(this, audioExtMode)
        } else {
            val videoExtMode =
                if (videoDecoder == AppPreferences.VIDEO_DECODER_PREFER_DEVICE) {
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                } else {
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
                }
            SplitModeRenderersFactory(this, videoExtMode, audioExtMode)
        }
        Log.i(
            "PLAYER_DV",
            "Renderer policy videoDecoder=$videoDecoder audioDecoder=$audioDecoderPriority " +
                "forceSoftware=$softwareDecoderActive ffmpegOnly=$softwareDecoderActive"
        )

        val player = ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setLivePlaybackSpeedControl(
                androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl.Builder()
                    .setFallbackMinPlaybackSpeed(0.97f).setFallbackMaxPlaybackSpeed(1.03f)
                    .setMinUpdateIntervalMs(100).setProportionalControlFactor(0.1f).build()
            ).build().apply {
                val audioAttrs = AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build()
                setAudioAttributes(audioAttrs, true)

                val audioUrl = currentAudioUrl
                val initialMediaItem = mediaItemBuilder.build()
                val urlMimeType = resolveMimeType(currentUrl)
                val isManifest = mimeType == MimeTypes.APPLICATION_M3U8 ||
                    mimeType == MimeTypes.APPLICATION_MPD ||
                    urlMimeType == MimeTypes.APPLICATION_M3U8 ||
                    urlMimeType == MimeTypes.APPLICATION_MPD
                Log.i(
                    "PLAYER_DV",
                    "Media source path=${if (isManifest) "manifest" else "progressive"} " +
                        "mime=${mimeType ?: urlMimeType ?: "unknown"}"
                )
                if (!audioUrl.isNullOrBlank()) {
                    val videoSource = if (isManifest) {
                        mediaSourceFactory.createMediaSource(initialMediaItem)
                    } else {
                        progressiveMediaSourceFactory.createMediaSource(initialMediaItem)
                    }
                    val audioSource = ProgressiveMediaSource.Factory(httpFactory)
                        .createMediaSource(MediaItem.fromUri(audioUrl))
                    setMediaSource(MergingMediaSource(videoSource, audioSource))
                } else if (isManifest) {
                    setMediaItem(initialMediaItem)
                } else {
                    setMediaSource(progressiveMediaSourceFactory.createMediaSource(initialMediaItem))
                }

                externalSubtitleUri?.let { subtitleUri ->
                    val subtitle = MediaItem.SubtitleConfiguration.Builder(subtitleUri)
                        .setMimeType(resolveSubtitleMimeType(subtitleUri))
                        .setLanguage("und")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                    val currentItem = mediaItemBuilder
                        .setSubtitleConfigurations(listOf(subtitle))
                        .build()
                    if (audioUrl.isNullOrBlank()) {
                        if (isManifest) {
                            setMediaItem(currentItem)
                        } else {
                            setMediaSource(progressiveMediaSourceFactory.createMediaSource(currentItem))
                        }
                    } else {
                        Log.w(TAG, "External subtitles are not merged with a separate audio source")
                    }
                }

                if (!isLiveChannel && carryPositionMs > 0L) seekTo(carryPositionMs)
                setPlaybackSpeed(playbackSpeed)
                playWhenReady = true
                // Tunneled playback is skipped on live channels: HLS live
                // manifests (discontinuities, rolling window) are the classic
                // tunnel black-video-with-audio case on Fire TV/Android TV.
                // It is also skipped when the FFmpeg audio decoder is
                // preferred (audioDecoder = Prefer app): tunneled audio needs
                // a MediaCodec decoder inside the hardware tunnel, so a
                // software-decoded PCM track gets created with FLAG_HW_AV_SYNC
                // and AudioFlinger refuses it (createTrack error -38,
                // "Cannot create AudioTrack"). Same rule Nuvio uses.
                if (enableTunneling && !softwareDecoderActive && !isLiveChannel &&
                    audioExtMode != DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                ) {
                    trackSelectionParameters = androidx.media3.exoplayer.trackselection.DefaultTrackSelector
                        .Parameters.Builder(this@NativePlayerActivity)
                        .setTunnelingEnabled(true).build()
                    Log.i("PLAYER_TUNNEL", "Tunneled via TrackSelector")
                }
                playWhenReady = true
            }

            player.addListener(createPlayerListener())
            player.addAnalyticsListener(createAnalyticsListener())
            armStartupWatchdog()

            playbackEndedHandled = false
            lastPolledPos = -1L
            posStallTicks = 0

            exoPlayer = player
            if (p5GlesActive) {
                p5VideoGlesView.setPlayer(player)
            } else {
                // Apply the surface type BEFORE attaching the player: the
                // black-video watchdog's TextureView fallback swaps the
                // PlayerView's internal surface view here (Media3 1.9 reads
                // app:surface_type only at construction, so there is no public
                // setSurfaceType to call).
                switchPlayerViewSurface(forceTextureViewFallback)
                playerView.player = player
                // Belt-and-braces: re-assert the fallback surface directly on
                // the player so the TextureView still wins even if the
                // internal-field swap above silently failed (setVideoTextureView
                // runs after PlayerView's routing; the last surface set wins).
                if (forceTextureViewFallback) {
                    fallbackTextureView?.let { player.setVideoTextureView(it) }
                }
                applyResizeMode(when (resizeModeIndex) {
                    1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                })
                playerView.post { player.prepare() }
            }

            mediaSession?.release()
            // media3 keys sessions by their session ID in a process-wide static map. The real fix
            // for "Session ID must be unique" is giving every session a unique id, so a freshly
            // launched player can coexist with a previous one whose session hasn't been released
            // yet (the old synchronous-release approach was racy and still crashed on this path).
            mediaSession =
                MediaSession.Builder(this, player)
                    .setId("kbstream-" + System.nanoTime() + "-" + sessionSequence++)
                    .build()

        // Start position polling
        startPositionPolling()
        // Start intro stamp polling
        startIntroStampPolling()
    }

    private fun recreatePlayer() {
        // Disarm any outstanding stall/black-video timers tied to the old
        // player instance; fresh ones are armed when the new session is ready.
        stallWatchdogToken++
        exoPlayer?.release()
        exoPlayer = null
        createPlayer()
    }

    private fun createPlayerListener() = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                hasPlayedOnce = true
                hideSplash()
                armStallWatchdog()
                if (controlsVisible && !showSettingsPanel && !isPickerShowing) {
                    scheduleAutoHide()
                }
                scrobbleSimkl("start")
                if (enableTunneling && !firstFrameRendered && exoPlayer?.currentPosition ?: 0L < 500L) {
                    val pos = exoPlayer?.currentPosition ?: 0L
                    exoPlayer?.seekTo(pos + 100L)
                }
            } else {
                scrobbleSimkl("pause")
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    rebufferStartedAtMs = System.currentTimeMillis()
                    updateUIBuffering()
                }
                Player.STATE_READY -> {
                    if (rebufferStartedAtMs != 0L) {
                        val stalledMs = System.currentTimeMillis() - rebufferStartedAtMs
                        Log.w("PLAYER_PERF", "Rebuffer stall: ${stalledMs}ms (sw=$forceSoftwareDecoder)")
                        rebufferStartedAtMs = 0L
                    }
                    updateUIReady()
                    retryAttempt = 0
                    retryExhausted = false
                    errorMessageStr = null
                    autoSelectPreferredLanguages()
                    armBlackVideoWatchdog()
                    armStallWatchdog()
                }
                Player.STATE_ENDED -> {
                    onPlaybackEnded()
                }
            }
        }

        override fun onRenderedFirstFrame() {
            if (firstFrameRendered) return
            firstFrameRendered = true
            firstFrameRenderedAtMs = System.currentTimeMillis()
            reconnectingContainer.visibility = View.GONE
            bufferingSpinner.visibility = View.GONE
        }

        override fun onTracksChanged(tracks: Tracks) {
            for (group in tracks.groups) {
                for (i in 0 until group.length) {
                    val fmt = group.getTrackFormat(i)
                    if (group.type == C.TRACK_TYPE_VIDEO) {
                        videoTrackPresent = true
                        val codec = fmt.codecs.orEmpty()
                        val colorInfo = fmt.colorInfo
                        streamWidth = fmt.width
                        streamHeight = fmt.height
                        streamBitrate = fmt.bitrate
                        streamCodec = codec.ifBlank { null }
                        streamMimeType = fmt.sampleMimeType
                        // A declared-DV track that the DV → HDR10 strip rewrote
                        // carries its original codec ("dvhe.07.06") in the
                        // format label — remember it for the codec badge.
                        val declaredDvCodec =
                            fmt.label?.takeIf { dvLabelFromCodec(it) != null }
                        streamDeclaredDvCodec = declaredDvCodec
                        // P5 detection keys off the DECLARED codec, not the
                        // rewritten codecs string: the extractor already turned
                        // dvhe.05.06 into hvc1.2.4 by the time the track arrives,
                        // and isP5Profile() never matches the rewritten one. That
                        // left P5 undetected (no GLES view, no FFmpeg force) and
                        // hardware decode rendered ICtCp as Rec.2020 PQ.
                        currentCodecs = declaredDvCodec ?: codec.ifBlank { null }
                        // P5 is only known now (the rewriter delivers the declared
                        // codec in the label), which is after createPlayer() ran.
                        // If the player was built before P5 was detected and has
                        // not rendered a frame yet, rebuild once so the GLES (or
                        // FFmpeg fallback) color path engages from the start.
                        if (declaredDvCodec != null &&
                            DolbyVisionCompat.isP5Profile(declaredDvCodec) &&
                            !p5GlesActive && !forceSoftwareDecoder &&
                            !firstFrameRendered && !p5ReroutePending
                        ) {
                            p5ReroutePending = true
                            Log.i(
                                "PLAYER_DV",
                                "P5 content detected after player start — rebuilding " +
                                    "player to activate color correction"
                            )
                            handler.post {
                                if (!p5GlesActive && !forceSoftwareDecoder && !firstFrameRendered) {
                                    recreatePlayer()
                                } else {
                                    // State changed meanwhile (watchdog recovery,
                                    // surface reset, frame rendered) — nothing to do.
                                    p5ReroutePending = false
                                }
                            }
                        }
                        Log.i(
                            "PLAYER_CODEC",
                            "video codec=$codec mime=${fmt.sampleMimeType} " +
                                "${fmt.width}x${fmt.height} color=${colorInfo?.colorTransfer ?: -1}" +
                                (streamDeclaredDvCodec?.let { " rewrittenFrom=$it" } ?: "")
                        )
                        preemptHeavyFfmpegSession(fmt)
                    }
                }
            }
            updateStreamHealthDisplay()
        }

        override fun onPlayerError(error: PlaybackException) {
            var msg = friendlyErrorMessage(error)
            if (isDecoderError(error.errorCode)) {
                val codec = streamCodec
                if (!codec.isNullOrBlank()) {
                    val dims = if (streamWidth > 0) " ${streamWidth}x$streamHeight" else ""
                    msg += "\nThis file's video ($codec$dims) can't be decoded on this TV."
                }
            }
            errorMessageStr = msg + if (forceSoftwareDecoder) " (software decoder)" else ""
            if (isLikelyRetryable(error)) {
                scheduleRetry()
            } else {
                retryExhausted = true
                updateUIError()
            }
    }

}

    private fun createAnalyticsListener() = object : AnalyticsListener {
        override fun onDroppedVideoFrames(
            eventTime: AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long
        ) {
            if (droppedFrames > 0) {
                Log.w("PLAYER_PERF", "Dropped $droppedFrames frames over ${elapsedMs}ms (sw=$forceSoftwareDecoder)")
            }
    }

}

    // --- UI Updates ---
    private fun startPulseAnimation() {
        if (splashClearLogo.animation == null) {
            val pulse = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.clearlogo_pulse)
            splashClearLogo.startAnimation(pulse)
        }
    }

    private fun showSplash() {
        splashContainer.visibility = View.VISIBLE
        bufferingSpinner.visibility = View.GONE
        // If clear logo is already loaded, start pulse immediately.
        // Otherwise, start it once Coil finishes loading.
        if (splashClearLogo.drawable != null) {
            startPulseAnimation()
        } else {
            splashClearLogo.viewTreeObserver.addOnGlobalLayoutListener(
                object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        if (splashClearLogo.drawable != null) {
                            splashClearLogo.viewTreeObserver.removeOnGlobalLayoutListener(this)
                            startPulseAnimation()
                        }
                    }
                }
            )
            // Fallback: also start animation after a short delay in case layout listener doesn't fire
            splashClearLogo.postDelayed({ startPulseAnimation() }, 500)
        }
    }

    private fun hideSplash() {
        splashClearLogo.clearAnimation()
        splashContainer.visibility = View.GONE
    }

    private fun updateUIBuffering() {
        // Full splash overlay (backdrop + pulsing clearlogo) ONLY for the very
        // first load of a session — including items resuming a saved position.
        // Never for mid-playback rebuffers (hasPlayedOnce) or when returning
        // from the actor page (fromActorReturn); those get the small spinner
        // so the video is never covered once the user has been watching.
        if (!hasPlayedOnce && !fromActorReturn) {
            showSplash()
        } else {
            hideSplash()
            reconnectingContainer.visibility = View.GONE
            bufferingSpinner.visibility = View.VISIBLE
        }
    }

    private fun updateUIReady() {
        bufferingSpinner.visibility = View.GONE
        reconnectingContainer.visibility = View.GONE
        errorContainer.visibility = View.GONE
        hideSplash()
        updateControlsInfo()
    }

    private fun updateUIError() {
        bufferingSpinner.visibility = View.GONE
        reconnectingContainer.visibility = View.GONE
        errorContainer.visibility = View.VISIBLE
        errorTitle.text = if (isLiveChannel) "Channel unavailable" else "Playback failed"
        errorMessage.text = errorMessageStr.orEmpty()
        btnChangeSource.visibility = View.VISIBLE
        focusErrorButtons()
    }

    /**
     * Move D-pad focus onto the error card's buttons. Without this the focus
     * stays parked on playerView, whose key listener reroutes every arrow/OK
     * press into the (hidden) controls overlay, so the remote can never reach
     * RETRY / CHANGE SOURCE.
     */
    private fun focusErrorButtons() {
        errorContainer.post {
            val target = if (btnRetry.visibility == View.VISIBLE) btnRetry else btnChangeSource
            target.requestFocus()
        }
    }

    // --- Black-video watchdog ---

    /**
     * An FFmpeg-only session (persisted video decoder = FFmpeg) that tries to
     * software-decode a stream it cannot keep up with produces audio with no
     * first frame — the exact black-screen-with-audio failure seen on 4K /
     * 10-bit HEVC. When the track format reveals such a stream, rebuild with
     * the hardware decoder immediately instead of waiting out the black-video
     * watchdog (which would reach the same swap ~8s later).
     */
    private fun preemptHeavyFfmpegSession(fmt: Format) {
        if (!ffmpegOnlySession || ffmpegSessionSwappedToHw) return
        val w = fmt.width
        val h = fmt.height
        if (w <= 0 || h <= 0) return
        val pixels = w.toLong() * h.toLong()
        // media3 ColorInfo exposes lumaBitdepth (Format.NO_VALUE when
        // unknown); unknown or 8-bit color info falls back to 8, so only
        // true 10-bit+ (HEVC Main10, DV base layers) triggers the
        // bit-depth guard.
        val colorLumaDepth = fmt.colorInfo?.lumaBitdepth ?: Format.NO_VALUE
        val bitDepth = if (colorLumaDepth >= 10) colorLumaDepth else 8
        // 1080p-class 8-bit is roughly the ceiling for the software renderer
        // on a Fire TV Stick-class CPU; anything bigger — or 10-bit — goes to
        // the hardware decoder (MediaCodec handles 10-bit HEVC natively).
        val tooHeavy = pixels > 1_920L * 1_080L ||
            (pixels >= 1_920L * 1_080L && bitDepth > 8)
        if (!tooHeavy) return
        ffmpegSessionSwappedToHw = true
        Log.w(
            "PLAYER_VIDEO",
            "FFmpeg-only can't keep up with ${w}x$h bitDepth=$bitDepth " +
                "mime=${streamMimeType ?: "?"} — switching to hardware decoder"
        )
        reconnectingContainer.visibility = View.VISIBLE
        bufferingSpinner.visibility = View.GONE
        reconnectingText.text = "Too heavy for software decoding — switching to hardware…"
        // Invalidate any pending watchdog work; this rebuild supersedes it.
        // blackVideoWatchdogToken++ # delayed to allow native window recovery
        handler.postDelayed(
            {
                if (blackVideoNoticeShown) return@postDelayed
                    if (firstFrameRendered && System.currentTimeMillis() - firstFrameRenderedAtMs > 2000L) return@postDelayed
                forceHardwareDecoder = true
                errorMessageStr = null
                recreatePlayer()
            },
            300L
        )
    }

    private fun isDecoderError(errorCode: Int): Boolean =
        errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
            errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES

    private fun armStartupWatchdog() {
        // VOD only: live channels have their own recovery paths and an HLS
        // playlist legitimately sits in BUFFERING while it loads — that must
        // not trip this.
        if (isLiveChannel) return
        if (errorContainer.visibility == View.VISIBLE) return
        val token = ++startupWatchdogToken
        startupStartedAtMs = System.currentTimeMillis()
        startupLastProgressAtMs = startupStartedAtMs
        startupLastPositionMs = -1L
        startupLastBufferedMs = -1L
        startupSawData = false
        handler.postDelayed({ tickStartupWatchdog(token) }, startupWatchdogTickMs)
    }

    private fun tickStartupWatchdog(token: Int) {
        if (token != startupWatchdogToken) return
        if (errorContainer.visibility == View.VISIBLE) return
        // Session succeeded, or the normal watchdogs own it from here.
        if (firstFrameRendered || blackVideoNoticeShown) return
        val player = exoPlayer ?: return
        val state = player.playbackState
        if (state == Player.STATE_READY || state == Player.STATE_ENDED) return
        // Audio-only content has no video start to wait on.
        if (player.currentTracks.groups.isNotEmpty() && !videoTrackPresent) return

        val now = System.currentTimeMillis()
        val positionMs = player.currentPosition
        val bufferedMs = player.bufferedPosition
        if (bufferedMs > 0 || positionMs > 0) startupSawData = true

        if (startupLastPositionMs >= 0L &&
            (positionMs > startupLastPositionMs + 500 || bufferedMs > startupLastBufferedMs + 500)
        ) {
            startupLastProgressAtMs = now
        }
        startupLastPositionMs = positionMs
        startupLastBufferedMs = bufferedMs

        val quietMs = if (startupSawData) startupQuietAfterDataMs else startupQuietNoDataMs

        // A video track exists and nothing has progressed — run the black-video
        // recovery ladder (surface bounce -> decoder rebuild -> notice). The
        // ladder normally requires READY + playing; the startup path skips that
        // gate because this session never got that far.
        if (now - startupLastProgressAtMs >= quietMs && videoTrackPresent) {
            Log.w(
                "PLAYER_VIDEO",
                "Startup watchdog: no first frame after ${now - startupLastProgressAtMs}ms " +
                    "quiet (data=$startupSawData state=$state) — running recovery ladder"
            )
            handleBlackVideoTimeout(blackVideoWatchdogToken, requirePlaying = false)
            return
        }

        // Absolute deadline: never leave the user on an eternal buffering
        // splash, even when data is flowing but READY is unreachable.
        if (now - startupStartedAtMs >= startupAbsoluteCapMs) {
            startupWatchdogToken++
            blackVideoNoticeShown = true
            Log.w(
                "PLAYER_VIDEO",
                "Startup watchdog: no READY after ${now - startupStartedAtMs}ms " +
                    "(pos=${positionMs}ms buf=${bufferedMs}ms) — showing timeout notice"
            )
            reconnectingContainer.visibility = View.GONE
            bufferingSpinner.visibility = View.GONE
            errorTitle.text = "Playback is taking too long to start"
            errorMessage.text =
                "The stream never became ready (buffered ${bufferedMs / 1000}s). This usually means " +
                    "the connection can't sustain the file's bitrate, the source went quiet, or the " +
                    "release has broken timestamps. Try a different source, a lower resolution, " +
                    "or a non-Dolby-Vision release."
            errorContainer.visibility = View.VISIBLE
            btnChangeSource.visibility = View.VISIBLE
            focusErrorButtons()
            return
        }

        handler.postDelayed({ tickStartupWatchdog(token) }, startupWatchdogTickMs)
    }

    private fun armBlackVideoWatchdog() {
        // Only meaningful when a video track exists and hasn't rendered yet.
        if (blackVideoNoticeShown || firstFrameRendered || !videoTrackPresent) return
        // Live channels count too: IPTV no longer tunnels or forces FFmpeg by
        // default, so a READY player with audio but no first frame is a real
        // no-video failure (black screen with audio) and gets the same
        // surface-bounce -> software -> notice recovery ladder as VOD.
        // Audio-only channels are already filtered by !videoTrackPresent.
        val token = ++blackVideoWatchdogToken
        handler.postDelayed({ handleBlackVideoTimeout(token) }, blackVideoWatchdogMs)
    }

    /**
     * Recovery ladder for "READY with audio but no first video frame":
     *  1. surface bounce — destroy/recreate the SurfaceView's native surface
     *     (fixes the lost-native-window case where the decoder IS producing
     *     frames but output goes nowhere),
     *  2. software decoder rebuild — for content the TV's hardware decoder
     *     accepts but cannot actually present (DV-family / HEVC-10 quirks),
     *  3. explicit notice so the user is never left staring at a silent stall.
     */
    private fun handleBlackVideoTimeout(token: Int, requirePlaying: Boolean = true) {
        if (token != blackVideoWatchdogToken) return
        if (blackVideoNoticeShown) return
        if (firstFrameRendered && System.currentTimeMillis() - firstFrameRenderedAtMs > 2000L) return
        // buffering start or rebuffer must not trip the notice. The startup
        // watchdog bypasses this gate — a session that never reached READY
        // needs the same recovery ladder, not an eternal splash.
        if (requirePlaying) {
            if (exoPlayer?.isPlaying != true) return
            if (exoPlayer?.playbackState != Player.STATE_READY) return
        }
        if (errorContainer.visibility == View.VISIBLE) return

        val codecInfo = streamCodec?.let { codec ->
            if (streamWidth > 0) " ($codec ${streamWidth}x$streamHeight)" else " ($codec)"
        } ?: ""

        // Stage 1: bounce the video surface. On some boxes a frame never
        // reaches the display because the player's native window was lost
        // (logcat: 'Could not find corresponding native window for surface') —
        // decoding is fine, output is going nowhere. Flipping the SurfaceView's
        // visibility destroys and recreates its native surface; PlayerView then
        // hands the fresh surface back to the player and the video renderer
        // restarts output. Cheaper than a rebuild, and it also covers the
        // software attempt that follows it. With the TextureView fallback
        // active the SurfaceView is hidden, so bouncing it is pointless —
        // skip straight to the software rebuild.
        if (!blackVideoSurfaceRetried && !forceTextureViewFallback) {
            blackVideoSurfaceRetried = true
            Log.w(
                "PLAYER_VIDEO",
                "Black video: no first frame after ${blackVideoWatchdogMs}ms$codecInfo " +
                    "mime=${streamMimeType ?: "?"} — resetting video surface"
            )
            reconnectingContainer.visibility = View.VISIBLE
            bufferingSpinner.visibility = View.GONE
            reconnectingText.text = "Video isn't displaying — resetting video surface…"
            val surfaceView = findVideoSurfaceView(playerView)
            handler.postDelayed(
                {
                    if (token != blackVideoWatchdogToken) return@postDelayed
                    if (blackVideoNoticeShown) return@postDelayed
                    if (firstFrameRendered && System.currentTimeMillis() - firstFrameRenderedAtMs > 2000L) return@postDelayed
                    if (surfaceView == null) {
                        // No SurfaceView to bounce (unexpected layout) — skip
                        // straight to the software-decoder stage.
                        handler.postDelayed({ handleBlackVideoTimeout(token, requirePlaying) }, 0L)
                        return@postDelayed
                    }
                    surfaceView.visibility = View.INVISIBLE
                    surfaceView.postDelayed(
                        {
                            if (token != blackVideoWatchdogToken) return@postDelayed
                            surfaceView.visibility = View.VISIBLE
                            handler.postDelayed({ handleBlackVideoTimeout(token, requirePlaying) }, blackVideoSurfaceRecheckMs)
                        },
                        250L
                    )
                },
                100L
            )
            return
        }

        // Stage 1.5: the surface bounce didn't help — rebuild with a TextureView.
        // "Could not find corresponding native window for surface" means the
        // SurfaceView's native window was lost: the decoder IS producing frames
        // but output goes nowhere. TextureView renders through the view hierarchy
        // (no separate native window), which is why other apps play the same file
        // on this TV. Much cheaper than the software rebuild, and it keeps
        // hardware decoding (4K HDR stays smooth).
        if (!forceTextureViewFallback) {
            forceTextureViewFallback = true
            Log.w(
                "PLAYER_VIDEO",
                "Black video: no first frame (surface reset tried)$codecInfo — retrying with TextureView"
            )
            reconnectingContainer.visibility = View.VISIBLE
            bufferingSpinner.visibility = View.GONE
            reconnectingText.text = "Video isn't displaying — switching to TextureView…"
            handler.postDelayed(
                {
                    if (token != blackVideoWatchdogToken) return@postDelayed
                    if (blackVideoNoticeShown) return@postDelayed
                    if (firstFrameRendered && System.currentTimeMillis() - firstFrameRenderedAtMs > 2000L) return@postDelayed
                    errorMessageStr = null
                    recreatePlayer()
                },
                500L
            )
            return
        }

        // Stage 2: swap renderer families. Auto sessions started on hardware,
        // so the retry rebuilds with the software decoder (plays many files
        // whose video the TV's hardware decoder accepts but cannot actually
        // present). A session started FFmpeg-only by persisted choice retries
        // with the hardware decoder instead — the experimental FFmpeg renderer
        // can produce no frames at all, and a "software retry" would rebuild
        // the identical renderer (a no-op that always ends in the notice).
        // Snapshot the persisted mode here too (createPlayer keeps its own
        // local copy); a mid-session settings change only matters from the
        // next player rebuild anyway.
        val ffmpegOnlyByChoice =
            AppPreferences.getVideoDecoder(this) == AppPreferences.VIDEO_DECODER_FFMPEG &&
                !forceSoftwareDecoder
        if (ffmpegOnlyByChoice) {
            if (blackVideoHwRetried) {
                // The hardware retry already ran and produced nothing — both
                // renderer families failed, surface the notice.
                showBlackVideoNotice()
                return
            }
            blackVideoHwRetried = true
            Log.w(
                "PLAYER_VIDEO",
                "Black video: no first frame (surface reset tried)$codecInfo " +
                    "mime=${streamMimeType ?: "?"} — retrying with hardware decoder"
            )
            reconnectingContainer.visibility = View.VISIBLE
            bufferingSpinner.visibility = View.GONE
            reconnectingText.text = "Video isn't displaying — switching to hardware decoding…"
            handler.postDelayed(
                {
                    if (token != blackVideoWatchdogToken) return@postDelayed
                    if (blackVideoNoticeShown) return@postDelayed
                    if (firstFrameRendered && System.currentTimeMillis() - firstFrameRenderedAtMs > 2000L) return@postDelayed
                    forceHardwareDecoder = true
                    errorMessageStr = null
                    recreatePlayer()
                },
                500L
            )
            // Absolute deadline for the hardware attempt: if nothing rendered,
            // surface the notice instead of leaving a silent stall.
            handler.postDelayed(
                {
                    if (token != blackVideoWatchdogToken) return@postDelayed
                    if (blackVideoNoticeShown) return@postDelayed
                    if (firstFrameRendered && System.currentTimeMillis() - firstFrameRenderedAtMs > 2000L) return@postDelayed
                    if (errorContainer.visibility == View.VISIBLE) return@postDelayed
                    showBlackVideoNotice()
                },
                blackVideoSwTimeoutMs
            )
            return
        }

        // Stage 2 (Auto): first occurrence with the hardware decoder — rebuild
        // with the software decoder.
        if (!blackVideoSwRetried && !forceSoftwareDecoder) {
            blackVideoSwRetried = true
            Log.w(
                "PLAYER_VIDEO",
                "Black video: no first frame (surface reset tried)$codecInfo — retrying with software decoder"
            )
            reconnectingContainer.visibility = View.VISIBLE
            bufferingSpinner.visibility = View.GONE
            reconnectingText.text = "Video isn't displaying — switching to software decoding…"
            handler.postDelayed(
                {
                    if (token != blackVideoWatchdogToken) return@postDelayed
                    if (blackVideoNoticeShown) return@postDelayed
                    if (firstFrameRendered && System.currentTimeMillis() - firstFrameRenderedAtMs > 2000L) return@postDelayed
                    forceSoftwareDecoder = true
                    errorMessageStr = null
                    recreatePlayer()
                },
                500L
            )
            // Absolute deadline for the software attempt: if nothing rendered
            // (or the decoder can't even keep up to READY), surface the notice
            // instead of leaving a silent stall.
            handler.postDelayed(
                {
                    if (token != blackVideoWatchdogToken) return@postDelayed
                    if (blackVideoNoticeShown) return@postDelayed
                    if (firstFrameRendered && System.currentTimeMillis() - firstFrameRenderedAtMs > 2000L) return@postDelayed
                    if (errorContainer.visibility == View.VISIBLE) return@postDelayed
                    showBlackVideoNotice()
                },
                blackVideoSwTimeoutMs
            )
            return
        }

        // Stage 3: hardware + software both produced nothing — surface the notice.
        showBlackVideoNotice()
    }

    private fun findVideoSurfaceView(root: View): android.view.SurfaceView? {
        if (root is android.view.SurfaceView) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findVideoSurfaceView(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun showBlackVideoNotice() {
        blackVideoNoticeShown = true
        val codecInfo = streamCodec?.let { codec ->
            if (streamWidth > 0) " ($codec ${streamWidth}x$streamHeight)" else " ($codec)"
        } ?: ""
        val triedOtherSources = autoSourceSwitchCount > 0
        if (!triedOtherSources && sources.size > 1) {
            if (tryNextSource()) return
        }
        Log.w(
            "PLAYER_VIDEO",
            "Black-video notice: no first frame after surface reset + TextureView retry + software retry$codecInfo " +
                "mime=${streamMimeType ?: "?"} playing=${exoPlayer?.isPlaying} state=${exoPlayer?.playbackState}"
        )
        reconnectingContainer.visibility = View.GONE
        bufferingSpinner.visibility = View.GONE
        errorTitle.text = "Video isn't displaying"
        errorMessage.text =
            "Playback started but no video frames are rendering$codecInfo. " +
                "Both video surfaces (SurfaceView and TextureView) plus the hardware and " +
                "software decoders were tried on this TV. " +
                (if (triedOtherSources) "Other sources were also tried automatically. " else "") +
                "If Dolby Vision playback is on, try setting it to Off for this file, or choose " +
                "a different source — a 1080p H.264 release usually plays on any device."
        errorContainer.visibility = View.VISIBLE
        btnChangeSource.visibility = View.VISIBLE
        focusErrorButtons()
    }

    // --- Stall watchdog ---

    private fun armStallWatchdog() {
        // Only once real playback has begun (first frame rendered) — the slow
        // NNTP first-byte wait (up to ~90s) is legitimate and must never trip
        // this. Live channels have their own handling.
        if (isLiveChannel) return
        if (!firstFrameRendered) return
        if (errorContainer.visibility == View.VISIBLE) return
        val token = ++stallWatchdogToken
        stallRecoveries = 0
        stallLastProgressAtMs = System.currentTimeMillis()
        stallLastPositionMs = exoPlayer?.currentPosition ?: 0L
        stallLastBufferedMs = exoPlayer?.bufferedPosition ?: 0L
        handler.postDelayed({ tickStallWatchdog(token) }, stallTickMs)
    }

    private fun tickStallWatchdog(token: Int) {
        if (token != stallWatchdogToken) return
        if (errorContainer.visibility == View.VISIBLE) return
        val player = exoPlayer ?: return
        // User paused: stop watching. Re-armed on resume (onIsPlayingChanged).
        if (!player.playWhenReady) return
        val state = player.playbackState
        if (state != Player.STATE_READY && state != Player.STATE_BUFFERING) return
        if (!firstFrameRendered) return

        val positionMs = player.currentPosition
        val bufferedMs = player.bufferedPosition
        val now = System.currentTimeMillis()

        // Any forward motion — playhead OR buffer — means data is flowing.
        if (positionMs > stallLastPositionMs + 500 || bufferedMs > stallLastBufferedMs + 500) {
            stallLastProgressAtMs = now
            stallLastPositionMs = positionMs
            stallLastBufferedMs = bufferedMs
            handler.postDelayed({ tickStallWatchdog(token) }, stallTickMs)
            return
        }

        if (now - stallLastProgressAtMs < stallNoProgressMs) {
            handler.postDelayed({ tickStallWatchdog(token) }, stallTickMs)
            return
        }

        // Quiet for the full threshold: recover by forcing a fresh read.
        if (stallRecoveries >= stallMaxRecoveries) {
            stallWatchdogToken++
            Log.w(
                "PLAYER_STALL",
                "No data for ${now - stallLastProgressAtMs}ms (pos=${positionMs}ms buf=${bufferedMs}ms) — giving up after $stallRecoveries recoveries"
            )
            errorMessageStr =
                "The stream stopped sending data. This can be a dead source — choose a different one or retry."
            // Two seek-recoveries already failed, so a full player rebuild is
            // unlikely to help a dead source; show the actionable error.
            retryExhausted = true
            updateUIError()
            return
        }

        // Seek just past the buffered edge so the media source opens a new
        // connection beyond the dead region. When nothing is buffered ahead
        // (buffer fully drained), seek a little past the playhead instead — a
        // seek to the exact current position would be a no-op.
        val durationMs = player.duration
        if (durationMs > 0 && bufferedMs + 500 >= durationMs - 500) {
            // The whole file is already buffered, so this is not a network
            // stall and no seek can force a fresh read. Completion of a
            // frozen tail is the position poller's job — disarm here so a
            // perfectly buffered ending never surfaces a network error.
            stallWatchdogToken++
            return
        }
        stallRecoveries++
        stallLastProgressAtMs = now
        stallLastPositionMs = positionMs
        stallLastBufferedMs = bufferedMs
        val targetMs = if (bufferedMs > positionMs) bufferedMs + 250 else positionMs + 250
        Log.w(
            "PLAYER_STALL",
            "No data for ${now - stallLastProgressAtMs}ms (pos=${positionMs}ms buf=${bufferedMs}ms) — seeking to ${targetMs}ms to force a fresh read (recovery $stallRecoveries/$stallMaxRecoveries)"
        )
        player.seekTo(targetMs)
        handler.postDelayed({ tickStallWatchdog(token) }, stallTickMs)
    }

    private fun updateHeaderInfo() {
        // Resolve clear logo URL from multiple sources
        val resolvedLogoUrl = clearLogoUrl?.takeIf { it.isNotBlank() }?.let { rawUrl ->
            if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) rawUrl
            else "https://image.tmdb.org/t/p/w780${if (rawUrl.startsWith("/")) rawUrl else "/$rawUrl"}"
        } ?: itemPoster?.takeIf { it.isNotBlank() }?.let { rawPoster ->
            if (rawPoster.startsWith("http://") || rawPoster.startsWith("https://")) rawPoster
            else if (rawPoster.startsWith("/")) "https://image.tmdb.org/t/p/w500$rawPoster"
            else null
        }

        if (resolvedLogoUrl != null) {
            clearLogo.load(resolvedLogoUrl)
            clearLogo.visibility = View.VISIBLE
            itemNameView.visibility = View.GONE
            // Also load into splash overlay
            splashClearLogo.load(resolvedLogoUrl)
        } else if (itemName.isNotBlank()) {
            clearLogo.visibility = View.GONE
            itemNameView.text = itemName
            itemNameView.visibility = View.VISIBLE
        }

        // Load backdrop into splash overlay. Only a real backdrop goes
        // full-screen here — the portrait poster is never stretched into the
        // loading splash (a blown-up poster reads as a zoomed, wrong backdrop).
        // Items without widescreen art keep the dark splash + pulsing logo,
        // matching the pre-player "Finding sources" overlay.
        val resolvedBackdropUrl = backdropUrl?.takeIf { it.isNotBlank() }?.let { rawUrl ->
            if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) rawUrl
            else "https://image.tmdb.org/t/p/w1280${if (rawUrl.startsWith("/")) rawUrl else "/$rawUrl"}"
        }
        if (resolvedBackdropUrl != null) {
            splashBackdrop.visibility = View.VISIBLE
            splashBackdrop.load(resolvedBackdropUrl)
            // Show splash initially before video plays on every first load —
            // including items resuming a saved position. Only skip it when
            // returning from the actor page (fromActorReturn), where the
            // small spinner is enough.
            if (!fromActorReturn) {
                showSplash()
            }
        } else {
            // No widescreen art: clear any previous frame and show the dark
            // splash (pulsing clearlogo) so no portrait/stale image flashes.
            splashBackdrop.visibility = View.GONE
            splashBackdrop.setImageDrawable(null)
            if (!fromActorReturn) {
                showSplash()
            }
        }
        if (season != null && episode != null) {
            episodeLabel.text = "S${season.toString().padStart(2, '0')} · E${episode.toString().padStart(2, '0')}"
            episodeLabel.visibility = View.VISIBLE
            episodeTitle?.takeIf { it.isNotBlank() }?.let {
                episodeTitleView.text = it
                episodeTitleView.visibility = View.VISIBLE
            }
        }
        overview?.takeIf { it.isNotBlank() }?.let {
            overviewText.text = it
            overviewText.visibility = View.VISIBLE
        }
        sourceLabel.visibility = View.VISIBLE
        sourceLabel.text = "Source: $currentSourceLabel"
    }

    private fun updateStreamHealthDisplay() {
        if (streamWidth > 0 && streamHeight > 0) {
            streamHealth.visibility = View.VISIBLE
            streamHealth.text = buildString {
                append(normalizeResolution(streamWidth, streamHeight))
                if (streamBitrate > 0) append(" • ${streamBitrate / 1_000} kbps")
                val codecLabel = normalizeCodec(streamCodec, streamDeclaredDvCodec, dvTo81Session)
                if (codecLabel != "—") append(" • $codecLabel")
            }
    }

}

    /**
     * Auto-select audio and subtitle tracks matching the user's preferred
     * languages. Runs once when playback reaches STATE_READY so it does
     * not fight with manual picker selections.
     */
    private var languagesAutoSelected = false

    private fun autoSelectPreferredLanguages() {
        val player = exoPlayer ?: return
        if (languagesAutoSelected) return
        val tracks = player.currentTracks
        var changed = false

        // ── Audio ──────────────────────────────────────────────
        if (preferredAudioLang.isNotBlank()) {
            val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            for (group in audioGroups) {
                for (i in 0 until group.length) {
                    val fmt = group.getTrackFormat(i)
                    val lang = fmt.language?.lowercase()
                    if (lang == preferredAudioLang.lowercase()) {
                        player.trackSelectionParameters = player.trackSelectionParameters
                            .buildUpon()
                            .setOverrideForType(
                                TrackSelectionOverride(group.mediaTrackGroup, i)
                            )
                            .build()
                        changed = true
                        Log.i("PLAYER_LANG", "Auto-selected audio: $lang")
                        break
                    }
                }
                if (changed) break
            }
        }

        // ── Subtitle ──────────────────────────────────────────
        if (preferredSubtitleLang.isNotBlank()) {
            val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
            var found = false
            for (group in textGroups) {
                for (i in 0 until group.length) {
                    val fmt = group.getTrackFormat(i)
                    val lang = fmt.language?.lowercase()
                    if (lang == preferredSubtitleLang.lowercase()) {
                        player.trackSelectionParameters = player.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(
                                TrackSelectionOverride(group.mediaTrackGroup, i)
                            )
                            .build()
                        found = true
                        changed = true
                        Log.i("PLAYER_LANG", "Auto-selected subtitle: $lang")
                        break
                    }
                }
                if (found) break
            }
            // If no matching subtitle track found, disable subtitles
            if (!found) {
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            }
        }

        if (changed) languagesAutoSelected = true
    }

    private fun updateSeekBarPosition(posMs: Long, durationMs: Long) {
        if (durationMs > 0) {
            seekbar.progress = ((posMs * 10_000L) / durationMs).toInt().coerceIn(0, 10_000)
            currentTime.text = formatMillis(posMs)
            totalTime.text = formatMillis(durationMs)
    }

}

    private fun stepSeekBy(deltaMs: Long) {
        val player = exoPlayer ?: return
        val duration = player.duration.takeIf { it > 0 } ?: return
        val newPos = (player.currentPosition + deltaMs).coerceIn(0L, duration)
        player.seekTo(newPos)
        updateSeekBarPosition(newPos, duration)
    }

    private fun commitSeekFromBar() {
        val durationMs = exoPlayer?.duration ?: 0L
        if (durationMs > 0) {
            val posMs = (seekbar.progress.toLong() * durationMs) / 10_000L
            exoPlayer?.seekTo(posMs)
        }
    }

    private fun updateClock() {
        val now = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
            .format(java.util.Date())
        playerClock.text = now
        val durationMs = exoPlayer?.duration ?: 0L
        val positionMs = exoPlayer?.currentPosition ?: 0L
        val remainingMs = (durationMs - positionMs).coerceAtLeast(0L)
        val endsAt = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
            .format(java.util.Date(System.currentTimeMillis() + remainingMs))
        endsAtClock.text = "Ends at $endsAt"
    }

    private fun updateControlsInfo() {
        sourceLabel.text = "Source: $currentSourceLabel"
        btnPlayPause.setImageResource(
            if (exoPlayer?.isPlaying == true) R.drawable.ic_player_pause else R.drawable.ic_player_play
        )
        btnSpeed.text = "${playbackSpeed}x"
        val aspectLabels = listOf("Fit", "Zoom", "Fill")
        btnAspect.text = aspectLabels.getOrElse(resizeModeIndex) { "Fit" }
        updateStreamHealthDisplay()
    }

    private fun pillBg(selected: Boolean, focused: Boolean): Int = when {
        selected && focused -> R.drawable.pill_chip_selected_focused_bg
        selected -> R.drawable.pill_chip_selected_bg
        focused -> R.drawable.pill_chip_focused_bg
        else -> R.drawable.pill_chip_bg
    }

    private fun applyResizeMode(mode: Int) {
        try {
            // 1) Set via PlayerView public API
            playerView.resizeMode = mode

            // 2) Walk up from the TextureView / SurfaceView to the
            //    internal AspectRatioFrameLayout and set it there too
            fun applyToViewTree(v: android.view.View?) {
                if (v == null) return
                if (v is AspectRatioFrameLayout) {
                    v.resizeMode = mode
                    v.requestLayout()
                }
                if (v is android.view.ViewGroup) {
                    for (i in 0 until v.childCount) applyToViewTree(v.getChildAt(i))
                }
            }
            applyToViewTree(playerView)

            // 3) Invoke the private updateTextureViewSize() method which
            //    actually applies the matrix transform to the TextureView
            try {
                val m = playerView.javaClass.getDeclaredMethod("updateTextureViewSize")
                m.isAccessible = true
                m.invoke(playerView)
            } catch (_: Exception) {}

            // 4) Force relayout on everything
            playerView.requestLayout()
            playerView.invalidate()

            // 5) Re-assert after the next layout pass, in case a pending
            //    redraw/player transaction reset the transform.
            handler.postDelayed(
                {
                    if (isDestroyed || isFinishing) return@postDelayed
                    try {
                        val m = playerView.javaClass.getDeclaredMethod("updateTextureViewSize")
                        m.isAccessible = true
                        m.invoke(playerView)
                        playerView.requestLayout()
                        playerView.invalidate()
                    } catch (_: Exception) {}
                },
                120L
            )
        } catch (_: Exception) {}
    }

    private fun applyPillState(view: TextView, selected: Boolean) {
        view.setBackgroundResource(pillBg(selected, view.isFocused))
        view.setTextColor(if (selected) getColor(R.color.kb_void) else getColor(R.color.kb_text_hi))
        view.setOnFocusChangeListener { v, _ ->
            val tv = v as TextView
            tv.setBackgroundResource(pillBg(selected, tv.isFocused))
            tv.setTextColor(if (selected) getColor(R.color.kb_void) else getColor(R.color.kb_text_hi))
    }

}

    private fun updateSettingsPanelState() {
        applyPillState(settingsBufferAuto, bufferMode == 2)
        applyPillState(settingsBufferBalanced, bufferMode == 0)
        applyPillState(settingsBufferLow, bufferMode == 1)

        btnTunneling.text = if (enableTunneling) "ON" else "OFF"
        applyPillState(btnTunneling, enableTunneling)

        btnAutoplay.text = if (autoPlayNext) "ON" else "OFF"
        applyPillState(btnAutoplay, autoPlayNext)

        val resizeModeLabels = listOf("Fit", "Zoom", "Fill")
        settingsSpeedAspect.text = "Speed: ${playbackSpeed}x • Aspect: ${resizeModeLabels.getOrElse(resizeModeIndex) { "Fit" }}"
        settingsSpeedAspect.visibility = View.VISIBLE

        updateSubtitleSettings()

        if (streamWidth > 0 && streamHeight > 0) {
            settingsResolution.text = "Resolution: ${normalizeResolution(streamWidth, streamHeight)}"
            settingsResolution.visibility = View.VISIBLE
            if (streamBitrate > 0) {
                settingsBitrate.text = "Bitrate: ${streamBitrate / 1_000} kbps"
                settingsBitrate.visibility = View.VISIBLE
            }
            val codecLabel = normalizeCodec(streamCodec, streamDeclaredDvCodec, dvTo81Session)
            if (codecLabel != "—") {
                settingsCodec.text = "Codec: $codecLabel"
                settingsCodec.visibility = View.VISIBLE
            }
    }

}

    // --- Controls Visibility ---
    private fun showControls() {
        controlsVisible = true
        controlsOverlay.visibility = View.VISIBLE
        seekbarRow.visibility = View.VISIBLE
        updateControlsInfo()
        updateClock()
        playerClock.visibility = View.VISIBLE
        endsAtClock.visibility = View.VISIBLE
        clockHandler.removeCallbacks(clockRunnable)
        clockHandler.post(clockRunnable)
        controlsOverlay.post { btnPlayPause.requestFocus() }
        scheduleAutoHide()
    }

    private fun hideControls() {
        // Stop any active scrubbing
        scrubDirection = 0
        scrubHandler.removeCallbacks(scrubRunnable)

        controlsVisible = false
        controlsOverlay.visibility = View.GONE
        seekbarRow.visibility = View.GONE
        dismissAllPanels()
        bufferingSpinner.visibility = View.GONE
        if (exoPlayer?.isPlaying == true) {
            hideSplash()
        }

}

    private fun updateSubtitleSettings() {
        listOf(btnSubSmall to 0, btnSubNormal to 1, btnSubLarge to 2).forEach { (btn, idx) ->
            applyPillState(btn, subtitleSize == idx)
        }
        listOf(btnSubBgNone to 0, btnSubBgSemi to 1, btnSubBgSolid to 2).forEach { (btn, idx) ->
            applyPillState(btn, subtitleBackground == idx)
        }
        subtitleOffsetValue.text = "${subtitleOffsetMs}ms"

        // Focus styling for offset +/- buttons
        listOf(btnOffsetMinus, btnOffsetPlus).forEach { btn ->
            btn.setOnFocusChangeListener { v, focused ->
                val tv = v as TextView
                tv.setBackgroundResource(pillBg(false, focused))
            }
        }
    }

    private fun applySubtitleStyle() {
        val view = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_subtitle)
        if (view is TextView) {
            val sizes = listOf(0.8f, 1f, 1.3f)
            view.textSize = 14 * sizes[subtitleSize]
            when (subtitleBackground) {
                1 -> { view.setBackgroundColor(0x80000000.toInt()); view.setPadding(16, 4, 16, 4) }
                2 -> { view.setBackgroundColor(0xE5000000.toInt()); view.setPadding(16, 4, 16, 4) }
                else -> { view.setBackgroundColor(0); view.setPadding(0, 0, 0, 0) }
            }
        }
    }

    private fun togglePlayPause() {
        exoPlayer?.let {
            val wasPlaying = it.isPlaying
            it.playWhenReady = !wasPlaying
            if (wasPlaying) {
                // Pausing — keep overlay visible
                showControls()
                removeAutoHide()
            } else {
                // Resuming — hide overlay instantly
                hideControls()
            }
        }
    }

    private val autoHideRunnable = Runnable { hideControls() }

    private fun scheduleAutoHide() {
        handler.removeCallbacks(autoHideRunnable)
        // Don't auto-hide when paused — keep overlay visible
        if (exoPlayer?.isPlaying == false) return
        handler.postDelayed(autoHideRunnable, CONTROLS_HIDE_DELAY_MS)
    }

    private fun removeAutoHide() {
        handler.removeCallbacks(autoHideRunnable)
    }

    // --- Picker ---
    private fun showPicker(mode: PickerMode) {
        pickerMode = mode
        isPickerShowing = true
        dismissSettingsPanel()
        pickerContainer.visibility = View.VISIBLE
        scrim.visibility = View.VISIBLE

        val items = when (mode) {
            PickerMode.SOURCE -> {
                pickerTitle.text = "SOURCES"
                sources.map { stream ->
                    PickerItem(
                        label = stream.displayLabel(),
                        isSelected = stream.url == currentUrl,
                        onClick = { switchToSource(stream); dismissPicker() }
                    )
                }
            }
            PickerMode.AUDIO -> {
                pickerTitle.text = "AUDIO"
                val tracks = exoPlayer?.currentTracks ?: return
                val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                audioGroups.flatMapIndexed { groupIdx, group ->
                    (0 until group.length).map { trackIdx ->
                        val format = group.getTrackFormat(trackIdx)
                        PickerItem(
                            label = format.language?.uppercase() ?: "Track ${groupIdx + 1}",
                            isSelected = group.isTrackSelected(trackIdx),
                            onClick = {
                                exoPlayer?.let { player ->
                                    player.trackSelectionParameters = player.trackSelectionParameters
                                        .buildUpon()
                                        .setOverrideForType(
                                            TrackSelectionOverride(group.mediaTrackGroup, trackIdx)
                                        )
                                        .build()
                                }
                                dismissPicker()
                            }
                        )
                    }
                }
            }
            PickerMode.SUBTITLE -> {
                pickerTitle.text = "SUBTITLES"
                val openFileItem = PickerItem(
                    label = "OPEN SUBTITLE FILE",
                    isSelected = externalSubtitleUri != null,
                    onClick = {
                        externalSubtitlePicker.launch(arrayOf("text/plain", "text/*", "application/octet-stream"))
                        dismissPicker()
                    }
                )
                val tracks = exoPlayer?.currentTracks ?: return
                val subtitleItems = listOf(openFileItem)
                val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                val anySelected = textGroups.any { g -> (0 until g.length).any { g.isTrackSelected(it) } }
                val offItem = PickerItem(
                    label = "OFF",
                    isSelected = !anySelected,
                    onClick = {
                        exoPlayer?.let { player ->
                            player.trackSelectionParameters = player.trackSelectionParameters
                                .buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                .build()
                        }
                        dismissPicker()
                    }
                )
                subtitleItems + listOf(offItem) + textGroups.flatMapIndexed { groupIdx, group ->
                    (0 until group.length).map { trackIdx ->
                        val format = group.getTrackFormat(trackIdx)
                        PickerItem(
                            label = format.language?.uppercase() ?: "Track ${groupIdx + 1}",
                            isSelected = group.isTrackSelected(trackIdx),
                            onClick = {
                                exoPlayer?.let { player ->
                                    player.trackSelectionParameters = player.trackSelectionParameters
                                        .buildUpon()
                                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                        .setOverrideForType(
                                            TrackSelectionOverride(group.mediaTrackGroup, trackIdx)
                                        )
                                        .build()
                                }
                                dismissPicker()
                            }
                        )
                    }
                }
            }
            PickerMode.SPEED -> {
                pickerTitle.text = "SPEED"
                SPEED_OPTIONS.map { speed ->
                    PickerItem(
                        label = "${speed}x",
                        isSelected = speed == playbackSpeed,
                        onClick = {
                            playbackSpeed = speed
                            exoPlayer?.setPlaybackSpeed(speed)
                            updateControlsInfo()
                            dismissPicker()
                        }
                    )
                }
            }
        }

        pickerList.adapter = PickerAdapter(items)
        pickerList.post { pickerList.requestFocus() }
    }

    // --- Settings Panel ---
    private fun toggleSettingsPanel() {
        if (showSettingsPanel) dismissSettingsPanel() else showSettingsPanelView()
    }

    private fun showSettingsPanelView() {
        dismissPicker()
        showSettingsPanel = true
        settingsContainer.visibility = View.VISIBLE
        scrim.visibility = View.VISIBLE
        settingsContainer.isFocusable = true
        settingsContainer.isFocusableInTouchMode = true
        updateSettingsPanelState()
        settingsContainer.post { settingsBufferAuto.requestFocus() }
    }

    private fun dismissSettingsPanel() {
        showSettingsPanel = false
        settingsContainer.visibility = View.GONE
        if (!isPickerShowing) scrim.visibility = View.GONE
    }

    private fun dismissPicker() {
        isPickerShowing = false
        pickerContainer.visibility = View.GONE
        if (!showSettingsPanel) scrim.visibility = View.GONE
    }

    private fun dismissAllPanels() {
        dismissPicker()
        dismissSettingsPanel()
    }

    // --- Retry ---
    private fun scheduleRetry() {
        if (retryAttempt >= MAX_RETRY_ATTEMPTS) {
            retryExhausted = true
            updateUIError()
            return
        }
        reconnectingContainer.visibility = View.VISIBLE
        bufferingSpinner.visibility = View.GONE
        reconnectingText.text = "Reconnecting... (${retryAttempt + 1}/$MAX_RETRY_ATTEMPTS)"

        if (retryAttempt == 2 && !forceSoftwareDecoder) {
            forceSoftwareDecoder = true
            Log.i("PLAYER_RETRY", "Attempt ${retryAttempt + 1}: forcing software decoder")
        } else if (retryAttempt >= 3) {
            Log.i("PLAYER_RETRY", "Attempt ${retryAttempt + 1}: probing with raw extractor")
        }

        handler.postDelayed({
            retryAttempt++
            errorMessageStr = null
            recreatePlayer()
        }, RETRY_BACKOFF_MS.getOrElse(retryAttempt) { RETRY_BACKOFF_MS.last() })
    }

    // --- Playback Ended ---
    private fun onPlaybackEnded() {
        scrobbleSimkl("stop", progressOverride = 100.0)
        scope?.launch {
            saveProgress(reason = "ended", forceCompleted = true)
            // Series episodes show the "Up next" popup; movies/live just end.
            val target = nextEpisodeTarget()
            if (target != null) {
                showNextUpPanel(target.first, target.second)
            }
        }
    }

    // --- Up Next ---
    private fun nextEpisodeTarget(): Pair<Int, Int>? {
        val s = season ?: return null
        val e = episode ?: return null
        val nextEp = e + 1
        val maxEps = totalEpisodesInSeason
        return if (maxEps != null && nextEp > maxEps) {
            // End of season — jump to next season episode 1
            (s + 1) to 1
        } else {
            s to nextEp
        }
    }

    /**
     * Builds the stream id for the next episode. Stremio stream ids are
     * "<imdb>:<season>:<episode>", so take the prefix of the current episode's
     * id and swap in the target season/episode. Reusing the current id (as
     * before) made auto-next re-fetch and replay the SAME episode.
     */
    private fun nextStreamId(targetSeason: Int, targetEpisode: Int): String {
        val current = episodeStreamId.orEmpty()
        val prefix = current
            .substringBeforeLast(':')
            .substringBeforeLast(':')
        return if (prefix.isNotBlank()) {
            "$prefix:$targetSeason:$targetEpisode"
        } else {
            "$parentId:$targetSeason:$targetEpisode"
        }
    }

    private fun showNextUpPanel(targetSeason: Int, targetEpisode: Int) {
        pendingNextSeason = targetSeason
        pendingNextEpisode = targetEpisode
        pendingNextEpisodeName = null

        nextUpShowTitle.text = itemName
        nextUpEpisodeLabel.text = "Season $targetSeason • Episode $targetEpisode"
        nextUpEpisodeTitle.text = "S${targetSeason}E$targetEpisode"
        nextUpCountdown.text = ""

        // Thumbnail: the show's artwork first, swapped for the episode still
        // once TMDB returns it.
        val initialThumb = backdropUrl ?: itemPoster
        if (!initialThumb.isNullOrBlank()) {
            try {
                nextUpThumb.load(initialThumb)
            } catch (_: Exception) {
            }
        } else {
            nextUpThumb.setImageDrawable(null)
        }

        nextUpPanel.visibility = View.VISIBLE
        btnNextPlay.requestFocus()

        if (autoPlayNext) {
            nextUpCountdownRemaining = NEXT_UP_COUNTDOWN_SECONDS
            nextUpCountdown.text = "Playing next in $nextUpCountdownRemaining"
            nextUpCountdownHandler.removeCallbacks(nextUpCountdownRunnable)
            nextUpCountdownHandler.postDelayed(nextUpCountdownRunnable, 1_000L)
        } else {
            nextUpCountdownRemaining = 0
            nextUpCountdown.text = "PLAY NEXT to continue, or press BACK to exit"
        }

        // Best effort: fetch the next episode's name + still from TMDB so the
        // popup shows real episode details instead of just S#E#.
        scope?.launch {
            val nextEp: com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode? = withContext(Dispatchers.IO) {
                val repo = TmdbRepository(this@NativePlayerActivity)
                val tmdbId = resolveParentTmdbId() ?: return@withContext null
                val episodes = runCatching {
                    repo.getSeasonEpisodes(tmdbId, targetSeason, parentId)
                }.getOrNull()
                episodes?.firstOrNull { it.episodeNumber == targetEpisode }
            }
            if (nextEp != null && nextUpPanel.visibility == View.VISIBLE) {
                pendingNextEpisodeName = nextEp.name
                nextUpEpisodeTitle.text = nextEp.name ?: "S${targetSeason}E$targetEpisode"
                nextEp.runtimeMinutes?.takeIf { it > 0 }?.let { pendingNextEpisodeRuntime = it }
                val still = nextEp.thumbnail
                if (!still.isNullOrBlank()) {
                    try {
                        nextUpThumb.load(still)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private fun launchNextEpisode(
        targetSeason: Int,
        targetEpisode: Int,
        episodeName: String? = null,
        runtimeMinutes: Int? = null
    ) {
        nextUpCountdownHandler.removeCallbacks(nextUpCountdownRunnable)
        val label = buildString {
            append("S${targetSeason}E$targetEpisode")
            if (!episodeName.isNullOrBlank()) append(" • $episodeName")
        }
        NextEpisodeResult.pendingNextEpisode = NextEpisodeResult.PendingNext(
            season = targetSeason,
            episode = targetEpisode,
            title = label,
            streamId = nextStreamId(targetSeason, targetEpisode),
            runtimeMinutes = runtimeMinutes
        )
        // Release the media session synchronously so it is unregistered from the
        // process-wide session map before the next player activity builds its own
        // (both would otherwise collide with "Session ID must be unique"). The
        // player itself stays alive so onStop's progress save still runs.
        mediaSession?.release()
        mediaSession = null
        finish()
    }

    // --- Position Polling ---
    private val positionRunnable = object : Runnable {
        override fun run() {
            exoPlayer?.let { player ->
                val pos = player.currentPosition.coerceAtLeast(0L)
                val dur = player.duration

                if (controlsVisible) {
                    val progress = if (dur > 0 && dur != C.TIME_UNSET) {
                        ((pos * 10_000L) / dur).toInt().coerceIn(0, 10_000)
                    } else 0
                    seekbar.progress = progress
                    currentTime.text = formatMillis(pos)
                    if (dur > 0 && dur != C.TIME_UNSET) {
                        totalTime.text = formatMillis(dur)
                    }
                }

                // Update play/pause button
                btnPlayPause.setImageResource(
                    if (player.isPlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play
                )

                // Some sources (broken HLS tails, streams with wrong or unset
                // durations) never emit STATE_ENDED: the picture goes black but
                // the clock keeps counting and auto-next never triggers. Detect
                // that state here so completion is handled exactly like a real
                // ENDED event.
                detectStallEndedFallback(
                    player,
                    pos,
                    dur
                )
            }
            handler.postDelayed(this, 1_000L)
    }

}

    private fun startPositionPolling() {
        handler.removeCallbacks(positionRunnable)
        handler.post(positionRunnable)
    }

    /**
     * Completion fallback for media that never fires [Player.STATE_ENDED].
     * Triggered from the 1s position poller:
     *
     * - position has reached the declared duration, or
     * - the player reports isPlaying but the position has not advanced for
     *   two consecutive ticks (2s) — a frozen tail, not a buffering pause
     *   (rebuffers flip isPlaying off, which resets the stall counter).
     *
     * Stops the clock and routes through onPlaybackEnded so the Up Next popup
     * and auto-next chain run exactly as they would after a real ENDED event.
     */
    private fun detectStallEndedFallback(
        player: Player,
        pos: Long,
        dur: Long
    ) {
        if (playbackEndedHandled || isLiveChannel) return

        if (player.playbackState == Player.STATE_ENDED) {
            // Belt-and-braces: the listener normally handles this; only act if
            // it somehow missed the event.
            playbackEndedHandled = true
            onPlaybackEnded()
            return
        }

        if (!player.isPlaying) {
            // Paused or rebuffering — never a completion signal.
            lastPolledPos = -1L
            posStallTicks = 0
            return
        }

        val reachedDuration =
            dur > 0 && dur != C.TIME_UNSET &&
                pos >= dur - 1_000L

        val advanced = pos > lastPolledPos
        lastPolledPos = pos

        if (reachedDuration || !advanced) {
            posStallTicks++
        } else {
            posStallTicks = 0
        }

        if (reachedDuration || posStallTicks >= 2) {
            playbackEndedHandled = true
            // Freeze the clock: without this the counter keeps climbing on a
            // black frame while ENDED never arrives.
            player.pause()
            onPlaybackEnded()
        }
    }

    // --- Intro Stamp Polling ---
    private val introStampRunnable = object : Runnable {
        override fun run() {
            val player = exoPlayer ?: run {
                handler.postDelayed(this, 750L)
                return
            }
            val posMs = player.currentPosition
            val matching = introDbStamps.firstOrNull { stamp ->
                posMs >= stamp.startMs && posMs < stamp.endMs &&
                    stamp.endMs > stamp.startMs && stamp.endMs - stamp.startMs <= 10 * 60 * 1000L
            }
            if (matching != activeIntroStamp) {
                activeIntroStamp = matching
                if (matching != null) {
                    btnSkipIntro.text = matching.type.buttonLabel
                    btnSkipIntro.visibility = View.VISIBLE
                } else {
                    btnSkipIntro.visibility = View.GONE
                }
            }
            handler.postDelayed(this, 750L)
    }

}

    private fun startIntroStampPolling() {
        handler.removeCallbacks(introStampRunnable)
        if (!isLiveChannel) handler.post(introStampRunnable)
    }

    // --- History ---
    private fun saveProgress(reason: String, forceCompleted: Boolean = false) {
        val player = exoPlayer ?: return
        if (isLiveChannel || parentId.isBlank() || historyId.isBlank()) return

        // Capture playback position synchronously: onStop() releases the
        // player immediately after this returns, so the position has to be
        // read while the player is still alive. The Room write then runs off
        // the main thread via lifecycleScope instead of blocking it.
        val pos = player.currentPosition.coerceAtLeast(0L)
        val rawDur = player.duration
        val dur = if (rawDur == C.TIME_UNSET || rawDur <= 0L) null else rawDur
        if (dur == null) return
        if (pos < MIN_RESUME_POSITION_MS && !forceCompleted) return
        val isCompleted = forceCompleted || pos >= (dur * COMPLETION_THRESHOLD_RATIO).toLong()
        val safePos = if (isCompleted) 0L else pos.coerceAtMost(dur)
        val now = System.currentTimeMillis()

        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val dao = WatchHistoryDatabase.getInstance(this@NativePlayerActivity).watchHistoryDao()
                val existing = dao.getById(historyId)
                val entry = WatchHistoryEntity(
                    id = historyId, parentId = parentId, type = parentType,
                    name = itemName, episodeTitle = episodeTitle, overview = overview,
                    clearLogo = clearLogoUrl, totalEpisodesInSeason = totalEpisodesInSeason,
                    poster = itemPoster, streamUrl = currentUrl,
                    season = season, episode = episode, episodeStreamId = episodeStreamId,
                    positionMs = safePos, durationMs = dur, updatedAt = now,
                    isCompleted = isCompleted,
                    completedAt = if (isCompleted) existing?.completedAt ?: now else null
                )
                dao.upsert(entry)
                // Mirror the in-app Continue Watching rail to the TV
                // launcher (Watch Next) so in-progress titles show up on
                // the home screen. Self-healing full reconcile: finished or
                // removed titles drop off automatically.
                TvLauncherPublisher.sync(
                    this@NativePlayerActivity,
                    dao.getAll()
                )
            }
            if (isCompleted) syncCompletedToSimkl()
        }
    }

    /**
     * Resolves (once, lazily) the TMDB id for the current parent regardless
     * of the raw id flavor (imdb / tmdb: / tvdb: / bare numeric). Simkl can
     * only match shows/movies by imdb or tmdb id, so TVDB-sourced titles
     * scrobble via this resolved id instead of being silently dropped.
     */
    private suspend fun resolveParentTmdbId(): Int? {
        if (resolvedParentTmdbId == null && parentId.isNotBlank()) {
            resolvedParentTmdbId = withContext(Dispatchers.IO) {
                runCatching {
                    TmdbRepository(this@NativePlayerActivity)
                        .fetchEnrichedMetaCached(parentId, parentType)
                        ?.id
                }.getOrNull()
            }
        }
        return resolvedParentTmdbId
    }

    private fun scrobbleSimkl(action: String, progressOverride: Double? = null) {
        if (isLiveChannel || parentId.isBlank()) return
        if (action == "start" && simklScrobbleActive && !simklScrobblePaused) return
        if (action == "pause" && !simklScrobbleActive) return
        val player = exoPlayer ?: return
        val pos = player.currentPosition.coerceAtLeast(0L)
        val dur = player.duration
        val progress = progressOverride ?: if (dur > 0 && dur != C.TIME_UNSET) {
            ((pos.toDouble() / dur.toDouble()) * 100.0).coerceIn(0.0, 100.0)
        } else 0.0
        when (action) {
            "start" -> {
                simklScrobbleActive = true
                simklScrobblePaused = false
            }
            "pause" -> simklScrobblePaused = true
            "stop" -> {
                simklScrobbleActive = false
                simklScrobblePaused = false
            }
            else -> {}
        }
        simklScrobbleJob?.cancel()
        simklScrobbleJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val ok = runCatching {
                val simkl = SimklRepository.getInstance(this@NativePlayerActivity)
                val tmdbId = resolveParentTmdbId()
                simkl.scrobble(
                    action = action,
                    parentId = parentId,
                    parentType = parentType,
                    season = season,
                    episode = episode,
                    title = itemName,
                    progress = progress,
                    tmdbId = tmdbId
                )
            }.getOrDefault(false)
            if (!ok && action == "start") {
                simklScrobbleActive = false
                Log.e(TAG, "Simkl scrobble start failed; will retry on next play")
            }
        }
    }

    private fun syncCompletedToSimkl() {
        if (simklScrobbleSent || parentId.isBlank()) return
        simklScrobbleSent = true
        simklSyncJob?.cancel()
        simklSyncJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val ok = runCatching {
                val simkl = SimklRepository.getInstance(this@NativePlayerActivity)
                val tmdbId = resolveParentTmdbId()
                when (parentType.lowercase()) {
                    "movie" -> simkl.pushWatchedMovie(imdbId = parentId, title = itemName, tmdbId = tmdbId)
                    "series", "show", "tv" -> {
                        val s = season; val e = episode
                        if (s != null && e != null) {
                            simkl.pushWatchedEpisode(showImdbId = parentId, season = s, episode = e, title = itemName, tmdbId = tmdbId)
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            }.getOrDefault(false)
            if (!ok) {
                simklScrobbleSent = false
                Log.e(TAG, "Simkl completion sync failed; will retry")
            }
        }
    }

    // --- PiP ---
    private fun enterPipIfEnabled() {
        // Never enter PiP while the activity is finishing/destroyed (e.g. during a
        // Back press) - on TVs that dumps the user to the launcher instead of the
        // previous app screen. Only enter PiP for a genuine user-leave (Home/recent).
        if (isFinishing || isDestroyed) return
        // Fire TV OS does not display PiP windows for third-party apps, and
        // attempting PiP during navigation is what dumps users to the launcher.
        // Skip PiP entirely on Amazon devices.
        if (android.os.Build.MANUFACTURER.equals("Amazon", ignoreCase = true)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && AppPreferences.getEnablePip(this)) {
            try {
                enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
            } catch (e: Exception) { Log.w("PLAYER_PIP", "PiP failed", e) }
    }

}

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // If the loading splash (pulsing clearlogo) is still up, the stream
        // hasn't started playing yet. Treat Back as an immediate exit request
        // instead of routing it through the controls/panel handling - a user
        // stuck on the splash must always be able to leave with one press.
        if (::splashContainer.isInitialized && splashContainer.visibility == View.VISIBLE) {
            super.onBackPressed()
            return
        }
        when {
            isPickerShowing -> { dismissPicker(); showControls(); return }
            showSettingsPanel -> { dismissSettingsPanel(); showControls(); return }
            controlsVisible -> { hideControls(); return }
        }
        super.onBackPressed()
    }

    override fun onUserLeaveHint() { super.onUserLeaveHint(); enterPipIfEnabled() }

    override fun onPause() {
        super.onPause()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isInPictureInPictureMode && AppPreferences.getEnablePip(this)) {
            enterPipIfEnabled()
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPiPMode = isInPictureInPictureMode
        if (isInPictureInPictureMode) hideControls()
    }

    // --- Lifecycle ---
    override fun onStop() {
        super.onStop()
        // Release session & player early so the next NativePlayerActivity
        // doesn't collide with a stale MediaSession ID.
        handler.removeCallbacksAndMessages(null)
        nextUpCountdownHandler.removeCallbacks(nextUpCountdownRunnable)
        scope?.cancel()
        saveProgress(reason = "stop")
        scrobbleSimkl("stop")
        exoPlayer?.release()
        exoPlayer = null
        mediaSession?.release()
        mediaSession = null
    }

    override fun onDestroy() {
        super.onDestroy()
        p5VideoGlesView.release()
        handler.removeCallbacksAndMessages(null)
        nextUpCountdownHandler.removeCallbacks(nextUpCountdownRunnable)
        scope?.cancel()
        exoPlayer?.release()
        exoPlayer = null
        mediaSession?.release()
        mediaSession = null
    }


    // --- IntroDb ---
    private fun setupIntroDb() {
        val handler = CoroutineExceptionHandler { _, t -> Log.w("INTRO_DB", "Failed", t) }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + handler)
        scope?.launch {
            if (!isLiveChannel) {
                introDbStamps = withContext(Dispatchers.IO) { fetchIntroDbStamps(parentId, season, episode) }
            }
        }
    }

    // --- Source Switching ---
    fun switchToSource(stream: Stream) {
        val newUrl = stream.url ?: return
        if (newUrl == currentUrl) return
        carryPositionMs = if (isLiveChannel) 0L else exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L
        currentSourceLabel = stream.displayLabel()
        currentUrl = newUrl
        currentAudioUrl = stream.audioUrl
        currentSourceIndex = sources.indexOfFirst { it.url == newUrl }
        retryAttempt = 0; retryExhausted = false; errorMessageStr = null; forceSoftwareDecoder = false; forceHardwareDecoder = false; forceTextureViewFallback = false; languagesAutoSelected = false
        dismissPicker()
        recreatePlayer()
    }

    private fun tryNextSource(): Boolean {
        if (autoSourceSwitchCount >= MAX_AUTO_SOURCE_SWITCHES) return false
        val nextIndex = currentSourceIndex + 1
        if (nextIndex >= sources.size) return false
        val nextStream = sources[nextIndex]
        autoSourceSwitchCount++
        Log.w(
            "PLAYER_VIDEO",
            "Auto-switching to next source: ${nextStream.displayLabel()} (index=$nextIndex/${sources.size})"
        )
        reconnectingContainer.visibility = View.VISIBLE
        bufferingSpinner.visibility = View.GONE
        reconnectingText.text = "Trying next source: ${nextStream.displayLabel()}…"
        switchToSource(nextStream)
        return true
    }

    companion object {

        private var sessionSequence = 0

        private fun isLikelyRetryable(error: PlaybackException): Boolean = when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_TIMEOUT,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            PlaybackException.ERROR_CODE_DRM_UNSPECIFIED -> true
            else -> false
        }

        private fun friendlyErrorMessage(error: PlaybackException): String = when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_TIMEOUT -> "No internet connection."
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Server returned an error. Stream may be unavailable."
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "Video format not supported."
            PlaybackException.ERROR_CODE_DRM_UNSPECIFIED,
            PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED -> "DRM license error."
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> "Audio playback failed. Try restarting."
            else -> error.message?.takeIf { it.isNotBlank() } ?: error.errorCodeName
        }
    }

    private fun isDescendantOf(view: android.view.View, parent: android.view.View): Boolean {
        var current: android.view.View? = view
        while (current != null) {
            if (current === parent) return true
            current = current.parent as? android.view.View
        }
        return false
    }
}

// --- Utility Functions (shared with Compose path) ---

private fun resolveMimeType(url: String): String? {
    val lower = url.lowercase()
    val path = lower.substringBefore('?').substringBefore('#')
    return when {
        ".m3u8" in path -> MimeTypes.APPLICATION_M3U8
        ".mpd" in path -> MimeTypes.APPLICATION_MPD
        ".mp4" in path || ".m4v" in path -> MimeTypes.VIDEO_MP4
        ".mkv" in path || ".webm" in path -> MimeTypes.VIDEO_MATROSKA
        ".ts" in path -> MimeTypes.VIDEO_MP2T
        ".flv" in path -> MimeTypes.VIDEO_FLV
        ".mov" in path -> MimeTypes.VIDEO_MP4
        ".aac" in path -> MimeTypes.AUDIO_AAC
        ".mp3" in path -> MimeTypes.AUDIO_MPEG
        ".flac" in path -> MimeTypes.AUDIO_FLAC
        ".opus" in path -> MimeTypes.AUDIO_OPUS
        ".ogg" in path -> MimeTypes.AUDIO_OGG
        ".wav" in path -> MimeTypes.AUDIO_WAV
        else -> null
    }
}

private fun formatMillis(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds)
    else String.format("%02d:%02d", minutes, seconds)
}

private fun Stream.displayLabel(): String = listOfNotNull(
    name?.takeIf { it.isNotBlank() },
    title?.takeIf { it.isNotBlank() },
    description?.takeIf { it.isNotBlank() }
).distinct().joinToString(" • ").ifBlank {
    url?.substringAfterLast('/').orEmpty().substringBefore('?').takeIf { it.isNotBlank() }
        ?: "Current source"
}

private fun resolveSubtitleMimeType(uri: Uri): String {
    val name = uri.lastPathSegment.orEmpty().lowercase()
    return when {
        name.endsWith(".vtt") -> MimeTypes.TEXT_VTT
        name.endsWith(".ass") || name.endsWith(".ssa") -> MimeTypes.TEXT_SSA
        else -> MimeTypes.APPLICATION_SUBRIP
    }
}

private fun parseHeaders(raw: String): Map<String, String> {
    if (raw.isBlank()) return emptyMap()
    return raw.lineSequence().mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isBlank()) return@mapNotNull null
        val sep = trimmed.indexOf(':')
        if (sep <= 0) return@mapNotNull null
        val key = trimmed.substring(0, sep).trim()
        val value = trimmed.substring(sep + 1).trim()
        if (key.isBlank() || value.isBlank()) null else key to value
    }.toMap()
}

/**
 * Cast member data class used by both native and compose player paths.
 */
data class PlayerCastMember(
    val id: Int,
    val name: String,
    val character: String?,
    val profilePath: String?
)

/**
 * Normalize raw pixel height into a human-friendly label:
 * 2160 → "4K", 1080 → "1080p", 720 → "720p", etc.
 */
internal fun normalizeResolution(width: Int, height: Int): String {
    if (width <= 0 && height <= 0) return "—"
    val maxDim = maxOf(width, height)
    return when {
        maxDim >= 3840 -> "4K"
        maxDim >= 2560 -> "1440p"
        maxDim >= 1920 -> "1080p"
        maxDim >= 1280 -> "720p"
        maxDim >= 854  -> "480p"
        maxDim >= 640  -> "360p"
        else           -> "${maxDim}p"
    }
}

private val DV_PROFILE_CODEC = Regex("(?i)^(dvhe|dvh1|dvav|dva1)\\.(\\d+)")

/**
 * Extracts an exact Dolby Vision profile label from a codec string:
 * "dvhe.07.06" → "DV P7", "dvh1.05.06" → "DV P5", "dvhe.08.06" → "DV P8".
 * Non-DV strings return null so the generic family labels stay with normalizeCodec.
 */
internal fun dvLabelFromCodec(codec: String?): String? {
    if (codec.isNullOrBlank()) return null
    val profile = DV_PROFILE_CODEC.find(codec.trim())?.groupValues?.get(2)
    if (profile != null) {
        val number = profile.toIntOrNull() ?: profile.trimStart('0')
        return "DV P$number"
    }
    val lower = codec.trim().lowercase()
    return if (
        lower.startsWith("dvhe") || lower.startsWith("dvh1") ||
        lower.startsWith("dvav") || lower.startsWith("dva1") ||
        lower.startsWith("dv") || lower.contains("dolby vision")
    ) "Dolby Vision" else null
}

/**
 * Normalize raw codec string into a friendly label:
 * "hev1.1.6.L150" → "H.265", "avc1.640028" → "H.264",
 * "vp09.00" → "VP9", "av01" → "AV1".
 */
internal fun normalizeCodec(
    codec: String?,
    dvOriginalCodec: String? = null,
    convertedTo81: Boolean = false
): String {
    // Declared Dolby Vision that the DV → HDR10 strip rewrote to plain HEVC:
    // surface the original profile so the badge still shows what the file was.
    dvLabelFromCodec(dvOriginalCodec)?.let {
        return if (convertedTo81) "$it → 8.1" else "$it → HDR10"
    }
    if (codec.isNullOrBlank()) return "—"
    dvLabelFromCodec(codec)?.let { return it }
    val lower = codec.lowercase()
    return when {
        lower.contains("dolby vision") -> "Dolby Vision"
        lower.startsWith("avc") || lower.contains("h264") || lower.contains("h.264") -> "H.264"
        lower.startsWith("hev") || lower.startsWith("hvc") || lower.contains("h265") || lower.contains("h.265") -> "H.265"
        lower.startsWith("vp09") || lower.startsWith("vp9") -> "VP9"
        lower.startsWith("vp08") || lower.startsWith("vp8") -> "VP8"
        lower.startsWith("av01") || lower.startsWith("av1") -> "AV1"
        lower.startsWith("mp4a") || lower.startsWith("mp3") || lower.contains("aac") -> "AAC"
        lower.contains("ac-3") || lower.contains("ac3") -> "AC-3"
        lower.contains("ec-3") || lower.contains("eac3") -> "EAC3"
        lower.contains("opus") -> "Opus"
        lower.contains("vorbis") -> "Vorbis"
        lower.contains("flac") -> "FLAC"
        lower.contains("video/h264") || lower.contains("video/avc") -> "H.264"
        lower.contains("video/hevc") || lower.contains("video/h265") -> "H.265"
        lower.contains("video/vp9") -> "VP9"
        lower.contains("video/av01") || lower.contains("video/av1") -> "AV1"
        else -> codec.uppercase()
    }
}
