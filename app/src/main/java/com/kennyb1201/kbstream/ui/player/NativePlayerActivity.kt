package com.kennyb1201.kbstream.ui.player

import android.app.PictureInPictureParams
import android.content.ActivityNotFoundException
import android.content.Context
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
import android.widget.Toast
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import android.content.Intent
import android.util.TypedValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.text.Cue
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.common.ForwardingPlayer
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.kennyb1201.kbstream.R
import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.data.addon.StreamBehaviorHints
import com.kennyb1201.kbstream.data.badges.StreamBadge
import com.kennyb1201.kbstream.data.iptv.EpgWriteGate
import com.kennyb1201.kbstream.data.memory.MemoryPressure
import com.kennyb1201.kbstream.data.memory.releaseImageMemoryCache
import com.kennyb1201.kbstream.data.iptv.LiveChannelZapRegistry
import com.kennyb1201.kbstream.data.iptv.db.EpgProgramRow
import com.kennyb1201.kbstream.data.iptv.db.IptvDatabase
import com.kennyb1201.kbstream.ui.player.PickerAdapter.Companion.bindBadgeRow
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.player.PlayerEngine
import com.kennyb1201.kbstream.data.tv.TvLauncherPublisher
import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import com.kennyb1201.kbstream.data.mdblist.MdbListClient
import com.kennyb1201.kbstream.data.player.PlayerTitlePrefs
import com.kennyb1201.kbstream.data.player.PlayerTrackMemory
import com.kennyb1201.kbstream.data.simkl.SimklRepository
import com.kennyb1201.kbstream.data.tmdb.TmdbPersonCredit
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.tmdb.UNSCRIPTED_TV_GENRES
import com.kennyb1201.kbstream.data.tmdb.bestLogoPath
import com.kennyb1201.kbstream.data.tmdb.displayCardMeta
import com.kennyb1201.kbstream.data.tmdb.displayDescription
import com.kennyb1201.kbstream.data.tmdb.displayMetaLine
import com.kennyb1201.kbstream.data.tmdb.keepRecommendedGenre
import com.kennyb1201.kbstream.data.tmdb.list
import com.kennyb1201.kbstream.ui.settings.AppPreferences
import com.kennyb1201.kbstream.ui.streams.StreamsViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import coil3.load
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "NativePlayer"
private const val PERIODIC_SAVE_INTERVAL_MS = 5_000L
private const val MIN_RESUME_POSITION_MS = 10_000L

/**
 * Saved-state key for the live playhead. When the system recreates this
 * activity (app backgrounded, process killed) it hands back the ORIGINAL
 * launch intent, whose start position says where playback *started* — not
 * where it got to. See [NativePlayerActivity.onSaveInstanceState].
 */
private const val STATE_PLAYER_POSITION_MS = "player_position_ms"
private const val COMPLETION_THRESHOLD_RATIO = 0.95f
private const val EXTRA_HEADERS = "stream_headers"
private const val EXTRA_DRM_LICENSE_URL = "drm_license_url"
private const val EXTRA_DRM_HEADERS = "drm_headers"
private const val MAX_RETRY_ATTEMPTS = 6
private val RETRY_BACKOFF_MS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)

/**
 * Attempt index at which the retry ladder stops trusting the inferred MIME
 * type and lets the extractor sniff the container itself (see [createPlayer]).
 * Attempts before it rebuild an otherwise identical player, which is pointless
 * after a decoder error, so that is where a decoder failure jumps to.
 */
private const val RAW_EXTRACTOR_PROBE_ATTEMPT = 3

/**
 * Grace period before rebuilding after a Dolby Vision decoder failure: the
 * vendor decoder on the affected TCL/Realtek boxes needs seconds to release
 * (ACodec force-releases it), and a rebuild inside that window cannot get a
 * new decoder at all.
 */
private const val DV_STRIP_REBUILD_DELAY_MS = 3_000L

/**
 * The platform's own "no decoder resources available" code
 * (OMX_ErrorInsufficientResources, 0x80001000). Realtek/TCL boxes surface it
 * as a MediaCodec.CodecException when the video decoder cannot be given its
 * buffers. It is not a bad-bitstream error: once one video decoder on the
 * process has failed this way, the box returns it for EVERY later decoder —
 * Dolby Vision and plain HEVC alike — until the process restarts.
 */
private val OMX_ERROR_INSUFFICIENT_RESOURCES = 0x80001000.toInt()

/**
 * Grace period before asking for a decoder again after resource exhaustion.
 * The vendor codec's release lands ~3s after ExoPlayer lets it go on this box
 * (`ACodec: forcing the release of codec`), so a rebuild inside that window
 * asks for a 4K decoder while the previous component still holds its
 * resources and fails identically.
 */
private const val DECODER_RESOURCE_RETRY_DELAY_MS = 6_000L

/**
 * How long a source switch leaves the box alone between releasing the old
 * player and building the next one. A switch reuses the same output Surface
 * for the new codec, and on this Realtek stack the outgoing 4K decoder's
 * buffers stay bound to that Surface past kWhatReleaseCompleted: the next
 * codec's setNativeWindowSizeFormatAndUsage then reconfigures a surface that
 * is still holding ~10 x 4K buffers, and the codec comes back
 * OMX_ErrorInsufficientResources (0x80001000) as soon as samples are
 * submitted. The DV-strip and resource-exhaustion rebuilds below already wait
 * 3s / 6s for this vendor behaviour (their own comments say the release lands
 * ~3s after ExoPlayer lets the codec go); a source switch was the one rebuild
 * that waited nothing - and the field log shows a session's first failure
 * landing 4ms after the previous player was released, on a plain HDR10 HEVC
 * file the box decodes natively.
 */
private const val SOURCE_SWITCH_SETTLE_MS = 3_000L// Shared with the MPV player's panel: same speeds, same labels, one list.
internal val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

// Aspect ratio modes. 0-2 map to the Media3 resize modes (see
// applyResizeMode); 3-4 pin the video frame to a fixed 16:9 / 4:3 ratio
// for misflagged streams where the embedded size doesn't match the
// actual picture (squeezed/pillarboxed).
val ASPECT_MODES = listOf("Fit", "Zoom", "Fill", "16:9", "4:3")
private const val ASPECT_MODE_FORCE_16_9 = 3
private const val ASPECT_MODE_FORCE_4_3 = 4
private const val CONTROLS_HIDE_DELAY_MS = 6_000L
// Shared with the MPV player, which raises the same card with the same timing.
internal const val NEXT_UP_COUNTDOWN_SECONDS = 5

// The Up Next panel now opens before the episode ends, so its auto-advance
// countdown is held until this close to the end. A touch longer than
// [NEXT_UP_COUNTDOWN_SECONDS] keeps the handoff on the end of the episode
// instead of cutting the last minute off it.
internal const val NEXT_UP_HOLD_THRESHOLD_MS = 6_000L

// How early the Up Next / Because-you-watched panel appears when the source
// carries no credits marker is a SETTING now: the "pop-up point" rows in
// Settings → Playback, as a percentage of the runtime, so it scales with the
// title instead of sitting at a fixed 75 seconds - which meant a different
// moment for a sitcom than for a film. Both players read it through the same
// two AppPreferences accessors, so the engines cannot disagree about when a
// title's credits count as rolling.

// When IntroDB does carry a credits row, open this long *before* it: the panel
// is then settled on screen as the credits start instead of appearing with
// them (the marker is the first frame of the credits, not an early warning).
// Shared with the MPV engine, which reads the same IntroDB rows, so the two
// cannot disagree about where a title's credits begin.
internal const val END_PANEL_CREDITS_LEAD_MS = 12_000L

// Floor for the credits-marker trigger: even when a crowd-sourced credits row
// points way back, never raise the panel more than "end minus this" - a bad
// row must not interrupt the last minutes of the episode. Shared with MPV for
// the same reason as the lead above.
internal const val END_PANEL_MIN_REMAINING_MS = 15_000L

// How long a duplicate of a confirm press that skipped a segment keeps being
// absorbed as that press's own trailing event - see [dispatchKeyEvent].
//
// Sized to cover a select press held long enough to autorepeat (the first
// repeat lands ~500 ms in) as well as an IR remote's fast double-fire. The
// cost is that a deliberate second press inside the window does nothing; a
// press after it is an ordinary controls-overlay toggle again.
private const val SKIP_CONFIRM_GRACE_MS = 1_200L

// Zap banner: how long the channel-info overlay stays on screen after the
// last CH+/CH− press, and how much EPG lookahead a single lookup loads.
private const val ZAP_BANNER_VISIBLE_MS = 5_000L

/**
 * How long a typed channel number waits for more digits before it tunes —
 * the same pause the guide uses, so typing a number feels identical in both
 * places.
 */
private const val CHANNEL_NUMBER_COMMIT_MS = 1_200L

/** How long "NO CHANNEL 12" stays up before the entry HUD hides itself. */
private const val CHANNEL_NUMBER_ERROR_MS = 1_800L
private const val ZAP_EPG_LOOKAHEAD_MS = 6L * 60L * 60L * 1000L
/**
 * The tag on the INFO screen's engine chip, so it is built exactly once per
 * session (see [NativePlayerActivity.applyInfoScreenChrome]).
 */
private const val INFO_ENGINE_CHIP_TAG = "info_engine_chip"

/** One press of the settings panel's own subtitle-offset pads, in ms. */
private const val SUBTITLE_OFFSET_STEP_MS = 500

/**
 * The fill of `@drawable/circle_avatar_bg`, the oval behind a cast card's
 * headshot (see [NativePlayerActivity.refillPlayerChromeView]).
 */
private val AVATAR_PLACEHOLDER_FILL: Int = 0xFF1D2530.toInt()

/**
 * What that chip reads. This activity IS the ExoPlayer engine - the MPV engine
 * has its own activity and its own readout, which names MPV - so whenever this
 * info screen is on display, ExoPlayer drew the picture. The name is what the
 * decoder rows below it cannot say: they name the decoders, not the engine.
 */
private const val INFO_ENGINE_CHIP = "EXOPLAYER"

private const val ZAP_EPG_ROW_LIMIT = 4
// Repaint a cached banner instantly, but still re-query the guide if the
// snapshot is older than this — otherwise the NOW row and progress bar
// slowly drift out of sync during a long viewing session.
private const val ZAP_EPG_TTL_MS = 60_000L

/**
 * Decouples the video and audio extension policies, which stock
 * DefaultRenderersFactory ties to a single extensionRendererMode. Audio gets
 * the FFmpeg audio extension at the position set by the audio decoder
 * priority (OFF / fallback / preferred) — that extension is the reason the
 * FFmpeg decoder package is bundled at all (DTS / DTS-HD / TrueHD tracks,
 * which the Fire TV stick's MediaCodec can't decode, play with no sound
 * without it). Video is strictly hardware: the bundled FFmpeg build ships
 * AUDIO decoders only (flac alac pcm mp3 aac ac3 eac3 dca mlp truehd — see
 * jellyfin-androidx-media build.sh), so its video renderer claims no format
 * and an FFmpeg-video session can only ever produce black video with audio.
 */
private class SplitModeRenderersFactory(
    context: Context,
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
        // P5 (ICtCp) sessions: prepend the raw-plane renderer AHEAD of the
        // stock MediaCodec video renderer. It reports FORMAT_HANDLED for HEVC
        // only while enabled, so non-P5 sessions are unaffected. Winning the
        // tie-break requires being earlier in the renderer list.
        if (P5PlaneVideoRenderer.enabled) {
            out.add(
                P5PlaneVideoRenderer(
                    allowedVideoJoiningTimeMs,
                    eventHandler,
                    eventListener,
                    DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY
                )
            )
            Log.i("PLAYER_DV", "P5 plane renderer prepended (raw-plane ICtCp path)")
        }
        // No video extensions: the bundled FFmpeg has no video decoders, so
        // its video renderer can never claim a track. Hardware only.
        super.buildVideoRenderers(
            context,
            EXTENSION_RENDERER_MODE_OFF,
            mediaCodecSelector,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            allowedVideoJoiningTimeMs,
            out
        )
    }

    /**
     * Installs the A/V sync offset processor and the downmix / dialogue
     * enhancer. Media3 has no audio offset API, so the shift rides in the PCM
     * stream (see [AudioDelayProcessor]). Both are attached unconditionally — at
     * their defaults they are pure pass-throughs — so moving a slider or picking
     * a downmix layout takes effect without rebuilding the player, and so no
     * audio path can ever silently lose the correction.
     *
     * Order matters: the delay runs first (it only inserts or drops leading
     * silence), then [AudioDownmixProcessor] folds the channels, applies the
     * dialogue/volume gain and limits the result.
     *
     * The sink is wrapped in [LiveDownmixAudioSink] so a *layout* change (the one
     * knob the processors cannot apply from inside the sample stream, because the
     * channel count is baked into the AudioTrack at configure time) is heard
     * while the film keeps playing instead of on the next stream start.
     */
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink {
        // "Auto" folds a multichannel stream down to what this output can
        // actually carry, so the fold needs to know the answer. Resolved per
        // sink (one player build) and logged, because it is the one input to
        // the downmix that comes from the device rather than the user.
        PlayerAudioTuning.deviceMaxChannels = resolveDeviceMaxChannels(context)
        Log.i(
            "PLAYER_DOWNMIX",
            "output carries up to ${PlayerAudioTuning.deviceMaxChannels} channels; " +
                "Auto folds multichannel down to it"
        )

        return LiveDownmixAudioSink(
            DefaultAudioSink.Builder(context)
                .setAudioProcessors(
                    arrayOf(
                        AudioDelayProcessor.instance,
                        AudioDownmixProcessor.instance
                    )
                )
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .build()
        )
    }
}

/**
 * How many channels this device's audio output can carry — the number the
 * downmix's "Auto" setting folds down to (see
 * [AudioDownmix.desiredOutputChannels]): stereo folds a 5.1/7.1 film down, which
 * is the TV-speaker case the dialogue lift exists for, while a six-channel
 * output keeps 5.1 and only trims 7.1 to it.
 *
 * Read from the HDMI audio-plug broadcast, whose extra is the sink's own EDID
 * channel count — the number the platform itself consults before handing an app
 * surround audio. [androidx.media3.exoplayer.audio.AudioCapabilities] reports
 * the same thing but falls back to a placeholder of 10 when the device says
 * nothing, and 10 folds nothing down, so it cannot answer this question.
 *
 * Anything absent or implausible falls back to stereo on purpose: a viewer who
 * really has six channels is one press from the 5.1 pill, while the opposite
 * mistake (keeping 5.1 on a stereo output) silently leaves dialogue under the
 * score — exactly what this feature is here to fix.
 */
@Suppress("DEPRECATION") // Sticky-broadcast query; the flags overload takes no null receiver.
private fun resolveDeviceMaxChannels(context: Context): Int {
    val fromSink = runCatching {
        context
            .registerReceiver(
                null,
                android.content.IntentFilter(
                    android.media.AudioManager.ACTION_HDMI_AUDIO_PLUG
                )
            )
            ?.getIntExtra(
                android.media.AudioManager.EXTRA_MAX_CHANNEL_COUNT,
                0
            )
            ?: 0
    }.getOrDefault(0)

    return if (fromSink in 3..8) fromSink else 2
}


class NativePlayerActivity : ComponentActivity() {

    // Views
    private lateinit var playerView: KBPlayerView
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
    private lateinit var btnSwitchPlayer: TextView
    private lateinit var btnSkipIntro: TextView
    private lateinit var controlsOverlay: LinearLayout
    private lateinit var playerClock: TextView
    private lateinit var endsAtClock: TextView
    private lateinit var splashContainer: View
    private lateinit var splashBackdrop: ImageView
    private lateinit var splashClearLogo: ImageView
    // Title text inside the splash, shown when the item has no clear logo.
    // Resolved lazily rather than in bindViews(): the splash's own binding sits
    // past the tooling's edit window.
    private var splashItemName: TextView? = null
    /** Set while showSplash() has a pending "wait for the clear logo" hook, so
     *  repeated source loads do not stack layout listeners on splashClearLogo. */
    private var splashPulseWaitAttached = false
    /** See enforceTitleGraphicPolicy: the follow-up passes are posted once. */
    private var titleGraphicRechecksPosted = false
    /** See resolveClearLogoFromTmdb: one lookup per player session. */
    private var clearLogoLookupStarted = false
    private lateinit var clearLogo: ImageView
    private lateinit var itemNameView: TextView
    private lateinit var episodeLabel: TextView
    private lateinit var episodeTitleView: TextView
    private lateinit var badgeRow: LinearLayout
    private lateinit var overviewText: TextView
    private lateinit var seekbarRow: LinearLayout
    private lateinit var seekbar: SeekBar
    private lateinit var currentTime: TextView
    private lateinit var totalTime: TextView
    private lateinit var btnPlayPause: ImageView
    private lateinit var btnNext: TextView
    private lateinit var btnSource: ImageView
    private lateinit var btnPlayerSwitch: TextView
    private lateinit var btnAudio: TextView
    private lateinit var btnSubtitle: TextView
    private lateinit var btnSpeed: TextView
    private lateinit var btnAspect: TextView
    private lateinit var btnSettings: TextView
    private lateinit var pickerContainer: LinearLayout
    private lateinit var pickerTitle: TextView
    // internal: PlayerPanelSection appends the track / A-V rows to this panel.
    internal lateinit var settingsContainer: ScrollView
    private lateinit var scrim: View
    private lateinit var settingsBufferAuto: TextView
    private lateinit var settingsBufferBalanced: TextView
    private lateinit var settingsBufferLow: TextView
    private lateinit var btnTunneling: TextView
    private lateinit var btnAutoplay: TextView
    private lateinit var btnAspectFit: TextView
    private lateinit var btnAspectZoom: TextView
    private lateinit var btnAspectFill: TextView
    private lateinit var btnAspect169: TextView
    private lateinit var btnAspect43: TextView
    private lateinit var settingsResolution: TextView
    private lateinit var settingsBitrate: TextView
    private lateinit var settingsCodec: TextView
    private lateinit var settingsSpeedAspect: TextView
    private lateinit var btnInfo: TextView
    private lateinit var infoPanel: ScrollView
    private lateinit var infoAddonIcon: ImageView
    private lateinit var infoTitle: TextView
    private lateinit var infoSource: TextView
    private lateinit var infoEngine: TextView
    private lateinit var infoFile: TextView
    private lateinit var infoVideo: TextView
    private lateinit var infoAudio: TextView
    private lateinit var pickerList: RecyclerView
    private lateinit var btnSubSmall: TextView
    private lateinit var btnSubNormal: TextView
    private lateinit var btnSubLarge: TextView
    private lateinit var btnSubBgNone: TextView
    private lateinit var btnSubBgSemi: TextView
    private lateinit var btnSubBgSolid: TextView
    private lateinit var btnSubBgText: TextView
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

    /**
     * Turns the end-of-episode Up Next card into a small one in the bottom-right
     * corner.
     *
     * `activity_player.xml` defines it 640dp wide, centered, with a 288x162
     * still - a full-screen takeover that buries the credits. That tail of the
     * layout sits past the tooling's edit window (the same reason the settings
     * panel's language and track rows are built in code), so the card is
     * restyled here instead: narrower box, smaller still, tighter leading, and
     * a bottom-right gravity. Nothing is hidden - thumbnail, show title,
     * season/episode label, episode title, PLAY NEXT / EXIT and the countdown
     * all stay, just smaller - so no information is lost.
     *
     * Called from [prepareEndOfEpisodePanels], which runs after `bindViews()`
     * has inflated the card and bound these fields. Idempotent, so an activity
     * recreate simply restates it.
     */
    private fun compactNextUpCard() {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        fun tighten(view: View, topDp: Int) {
            (view.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(topDp)
        }

        (nextUpPanel.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { params ->
            params.width = dp(440)
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            params.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
            // Sits clear of the control bar when it is up (seekbar row, time
            // row, button row and the overlay's own padding), so it never
            // covers the playback buttons; the right edge lines up with that
            // bar's own 48dp inset, which is what makes it read as part of the
            // screen rather than dropped on top of it.
            params.setMargins(dp(24), dp(24), dp(48), dp(152))
        }
        nextUpPanel.setPadding(dp(14), dp(14), dp(14), dp(14))
        nextUpPanel.requestLayout()

        // The still keeps its 16:9 shape, at the width the smaller card leaves.
        nextUpThumb.layoutParams = LinearLayout.LayoutParams(dp(150), dp(84))

        // The text column follows the narrower card: less gap to the still,
        // smaller type, and tighter leading between the lines.
        (nextUpPanel.getChildAt(1) as? LinearLayout)?.let { column ->
            (column.layoutParams as? LinearLayout.LayoutParams)?.marginStart = dp(14)
        }
        nextUpShowTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        nextUpEpisodeLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        nextUpEpisodeTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        nextUpCountdown.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        nextUpPanel.findViewById<TextView>(R.id.next_up_kicker)
            ?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        tighten(nextUpShowTitle, 2)
        tighten(nextUpEpisodeLabel, 3)
        tighten(nextUpEpisodeTitle, 2)
        tighten(nextUpCountdown, 6)
        // Episode names are longer than the narrower column, so they wrap to a
        // second line rather than getting cut off.
        nextUpEpisodeTitle.maxLines = 2

        listOf(btnNextPlay, btnNextDismiss).forEach { button ->
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            button.setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        (btnNextPlay.parent as? LinearLayout)?.let { row ->
            tighten(row, 10)
            (btnNextDismiss.layoutParams as? LinearLayout.LayoutParams)?.marginStart = dp(8)
        }
    }

    /**
     * Finishing touches for both end-of-episode panels - the Up Next card and
     * the because-you-watched credits recommendations. Both live in the tail of
     * `activity_player.xml` (and of this file's row builders) that the tooling's
     * edit window does not reach, so they are applied from code.
     *
     * The Up Next card is restyled once, by [compactNextUpCard]. The credits
     * panel's own finishing touches - rounded artwork, the featured strip and
     * the pill focus rules - live with the panel UI in BecauseYouWatched.kt,
     * shared with the MPV engine, so both engines' rows cannot drift apart.
     *
     * Called once from [PlayerPanelSection.attach], after `bindViews()`.
     */
    internal fun prepareEndOfEpisodePanels() {
        compactNextUpCard()
        // The INFO screen's own chrome comes with it: same hook, same reason
        // (see [applyInfoScreenChrome]).
        applyInfoScreenChrome()
        // The pick row's builder, shared with the MPV engine: focus sits on the
        // PLAY / DETAILS pills only - never the card - and both pills drive the
        // featured strip, so stepping through the row updates the info under it.
        // Built here rather than in bindViews because the credits panel's own
        // fields are bound in this file's XML tail, and this runs right after
        // them (PlayerPanelSection.attach, at the end of onCreate).
        bywUi = BecauseYouWatchedUi(
            host = this,
            panel = becauseYouWatchedPanel,
            title = bywTitle,
            row = bywRow,
            surfaceColor = { panelSurfaceColor() },
            raisedColor = { panelRaisedColor() },
            applyPill = { pill, selected, focused ->
                applyPillBackground(pill, selected, focused)
            },
            scope = { scope },
            onPlay = { pick, imdbId -> bywPlayPick(pick, imdbId) },
            onDetails = { pick, imdbId -> bywOpenDetails(pick, imdbId) }
        )
    }

    /**
     * The INFO screen: the panel the control bar's INFO button brings up.
     *
     * Both of the things it has to do are applied in code because the panel
     * itself sits in this layout's XML tail (past the tooling's edit window),
     * and because neither value can be a fixed XML one anyway:
     *
     *  - The FILL. The drawable it shipped with was translucent (#CC10141B), so
     *    over the overlay's own gradient the codec lines had the video showing
     *    through them; [infoPanelDrawable] gives the panel an opaque fill that
     *    still follows the AMOLED / pure-black toggles.
     *  - The ENGINE NAME. [buildInfoPanel] fills the row under the title with
     *    the decoder configuration ("Engine: Hardware video decoder • FFmpeg
     *    audio fallback"), which never said which ENGINE was playing the file -
     *    the one thing worth knowing after a handoff between the two engines.
     *    A chip beside the title says it outright.
     *
     * Called once per session from [prepareEndOfEpisodePanels], the point just
     * after `bindViews()` has bound this panel's views.
     */
    private fun applyInfoScreenChrome() {
        infoPanel.background = infoPanelDrawable(this)

        val header = infoTitle.parent as? LinearLayout ?: return
        if (header.findViewWithTag<TextView>(INFO_ENGINE_CHIP_TAG) != null) return

        header.addView(
            TextView(this).apply {
                tag = INFO_ENGINE_CHIP_TAG
                text = INFO_ENGINE_CHIP
                // The panels' own chip look, but never focusable: this screen
                // is read, not navigated.
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
                runCatching {
                    typeface = androidx.core.content.res.ResourcesCompat.getFont(
                        this@NativePlayerActivity,
                        R.font.oswald_semibold
                    )
                }
                setTextColor(getColor(R.color.kb_text_hi))
                setBackgroundResource(R.drawable.pill_chip_bg)
                val padH = (8 * resources.displayMetrics.density).toInt()
                val padV = (3 * resources.displayMetrics.density).toInt()
                setPadding(padH, padV, padH, padV)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = (10 * resources.displayMetrics.density).toInt()
                }
            }
        )
    }

    // Because-you-watched (end-credits recommendations)
    private lateinit var becauseYouWatchedPanel: LinearLayout
    private lateinit var bywTitle: TextView
    private lateinit var bywRow: LinearLayout

    /**
     * The pick row itself: built, themed and focused by the shared panel UI, so
     * the credits recommendations look and drive the same in both engines.
     */
    private lateinit var bywUi: BecauseYouWatchedUi
    private var bywDismissed = false

    // Player
    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    // State
    private val handler = Handler(Looper.getMainLooper())
    // Discovers Stremio-addon subtitles and merges them into the player as
    // sidecar text tracks (see AddonSubtitleController).
    private lateinit var addonSubtitleController: AddonSubtitleController
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
                // Live: keep the programme progress honest, and roll the
                // block over when the programme ends.
                tickLiveProgramBlock()
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
    // The track / A-V rows appended to that panel; see PlayerPanelSection.
    private var settingsPanelSection: PlayerPanelSection? = null
    private var isPickerShowing = false
    private var pickerMode = PickerMode.SOURCE
    private enum class PickerMode { SOURCE, AUDIO, SUBTITLE, SPEED }

    // Playback state
    private var currentUrl = ""
    private var currentAudioUrl: String? = null
    private var currentSourceLabel = "Source 1"
    private var currentBadges: List<StreamBadge> = emptyList()

    // Source identity for the info panel: addon display name + logo URL are
    // resolved once from the installed-addons registry by matching the active
    // source label against addon names (cheap, no intent plumbing changes).
    private var currentAddonName: String? = null

    // Stremio bingeGroup of the active stream (behaviorHints.bingeGroup).
    // Persisted into the next-episode handoff so the next episode's resolver
    // can prefer/reuse the same group — see BingeGroupResolver.
    private var currentBingeGroup: String? = null
    private var currentAddonLogoUrl: String? = null
    private var carryPositionMs = 0L
    private var playbackSpeed = 1f
    private var resizeModeIndex = 0
    // internal: PlayerPanelSection reads this — the panel's own ± offset
    // buttons change it without going through the track bridge.
    internal var subtitleOffsetMs = 0
    private var subtitleSize = 1
    private var subtitleBackground = 0
    // 0 = low (the placement every earlier build used), 1 = mid, 2 = high.
    // Applied as a translation on the cue view, which the renderer never
    // rewrites — so it holds for the whole session.
    private var subtitlePosition = 0
    private lateinit var subtitleText: TextView
    private var subtitleCueHandler: SubtitleCueHandler? = null
    // Parsed cues of the user-loaded external subtitle file. When present
    // with a negative offset, rendering switches to position-driven mode
    // (SubtitleCueHandler.updateFromPosition) so subs can appear early.
    private var externalSubtitleCues: List<SubtitleFileParser.TimedCue> = emptyList()

    // Stream health
    private var streamWidth = 0
    private var streamHeight = 0
    private var streamBitrate = 0
    private var streamCodec: String? = null
    // Original declared codec of the current video track (e.g. "dvhe.07.06")
    // before any DV→HDR10 rewrite. Used for P5 detection to select correction path.
    private var currentCodecs: String? = null
    // When the black-video watchdog tries TextureView as an automatic fallback
    // after SurfaceView fails (stage 1.5 in the recovery ladder).
    private var forceTextureViewFallback = false
    // One-shot per session: stage 2.5 of the black-video watchdog rebuilds
    // the player once with every Dolby Vision conversion forced to "Strip
    // All" after the normal path produced no first frame. Fire TV sticks
    // advertise a DV decoder yet their DV pipeline does not reliably
    // downconvert for non-DV displays — the decoder accepts the track,
    // outputs zero frames, and no error fires. This mirrors what other
    // apps' players do: same file, same URL, just presented to the decoder
    // as plain HDR10/HEVC. Never loop the retry.
    private var dvStripRetryDone = false

    /**
     * Whether the session currently on screen actually used Dolby Vision
     * passthrough. Read by the learned-failure clearing in
     * [markFirstFrameRendered]: a playback that ran with passthrough
     * *suppressed* says nothing about whether passthrough works.
     */
    private var dvPassthroughActive = false
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
    private var decoderResourceFallbackDone = false
    private var retryExhausted = false
    private var errorMessageStr: String? = null
    private var manualRetryToken = 0
    private var rebufferStartedAtMs = 0L

    // Startup cost breakdown, logged once per attempt at the first frame.
    // The "Rebuffer stall" line alone cannot say whether the seconds went into
    // loading the source or into the decoder's first frame, and any buffering
    // policy change for heavy 4K sources has to be based on that split.
    private var startupTraceStartMs = 0L
    private var firstReadyAtMs = 0L

    // Black-video watchdog: some files reach READY with audio playing but the
    // video decoder never produces a frame (silent black screen, no error).
    // Track first-frame rendering and surface an actionable notice instead of
    // letting playback sit on black.
    private var videoTrackPresent = false
    private var streamMimeType: String? = null
    private var firstFrameRendered = false
    private var firstFrameRenderedAtMs = 0L
    private var blackVideoNoticeShown = false
    // True while either per-profile 8.1 conversion (P5/P7) is active so the
    // codec badge can report "DV P7 → 8.1" instead of "→ HDR10".
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

    /** How often the heap probe logs while playback is moving. */
    private val HEAP_LOG_INTERVAL_MS = 10_000L
    private var stallRecoveries = 0
    private var stallLastProgressAtMs = 0L
    private var stallLastPositionMs = -1L
    private var stallLastBufferedMs = -1L
    private val stallNoProgressMs = 12_000L
    private val stallMaxRecoveries = 2
    private val stallTickMs = 2_000L

    // History
    private var isLiveChannel = false

    // Zap banner (CH+/CH- channel info overlay) — bound in bindViews(),
    // populated by showZapBanner() during a live-channel zap.
    private var zapBanner: View? = null
    private var zapLogo: ImageView? = null
    private var zapChannelLabel: TextView? = null
    private var zapChannelName: TextView? = null
    private var zapNowTitle: TextView? = null
    private var zapNowMeta: TextView? = null
    private var zapNowProgress: ProgressBar? = null
    private var zapNowDesc: TextView? = null
    private var zapNextTitle: TextView? = null

    /**
     * Live-only programme block inside the controls overlay: what is on NOW
     * (title, air window, elapsed progress, synopsis) and what is next, from
     * the same guide rows the zap banner reads. Gone for VOD, where the
     * episode row carries instead.
     */
    private var liveProgramBlock: View? = null
    private var liveProgramStatus: TextView? = null
    private var liveProgramTitle: TextView? = null
    private var liveProgramProgress: ProgressBar? = null
    private var liveProgramDesc: TextView? = null
    private var liveProgramNext: TextView? = null

    /**
     * True once the arrival banner for this live channel has been shown, so a
     * re-ready (reconnect, retry) cannot re-announce the same channel.
     */
    private var zapBannerInitialShown = false

    /** Overlay CH ▲ / CH ▼ buttons (live only — see [updateControlsInfo]). */
    private var btnChannelUp: TextView? = null
    private var btnChannelDown: TextView? = null

    /**
     * "LIVE  •  CH 5  •  SPORTS" prefix of the block's status line. Kept
     * beside the programme's own air window because the prefix describes the
     * channel (fixed for as long as it plays) while the window changes with
     * every programme.
     */
    private var liveProgramScope = "LIVE"

    /**
     * Channel-number entry HUD and its pending commits. Typing digits while
     * watching live TV tunes directly, the way a set-top box does, so this
     * lives alongside the zap state.
     */
    private var channelNumberHud: TextView? = null
    private var channelNumberEntry = ""
    private val channelNumberHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val channelNumberCommitRunnable = Runnable { commitChannelNumberEntry() }
    private val channelNumberHudHideRunnable = Runnable {
        // Defensive: mirrors the zap banner's rule — a queued callback can
        // outlive the activity, and touching a detached view is pointless.
        if (!isDestroyed) channelNumberHud?.visibility = View.GONE
    }
    private val zapHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val zapBannerHideRunnable = Runnable {
        // Defensive: after onDestroy the callback can still sit in the
        // looper queue for a few seconds; touching the detached view is
        // harmless but pointless, so skip it once the activity is gone.
        if (!isDestroyed) zapBanner?.visibility = View.GONE
    }

    /**
     * Now/next programs for one channel, resolved from the imported guide
     * (same Room DB the guide screen reads). Null fields = no EPG.
     * [fetchedAtMillis] drives freshness: entries older than the TTL are
     * repainted instantly (no flash) but refreshed async in the background.
     */
    private data class ZapEpgInfo(
        val now: EpgProgramRow?,
        val next: EpgProgramRow?,
        val fetchedAtMillis: Long = 0L
    )

    // Zap state: which lineup entry is playing, and the per-channel EPG row
    // cache so repeated CH presses render instantly. (channelId, epgUrl).
    private var zapChannelIndex = -1
    private val zapEpgCache = HashMap<String, ZapEpgInfo>()
    private var zapEpgCacheLimit = 64

    private val zapTimeFormat by lazy {
        SimpleDateFormat("h:mm a", Locale.getDefault())
    }

    /**
     * The lineup entry playing right now, or null when the guide never
     * published a lineup this session (a channel opened from outside the
     * guide) or the playing URL is not in it. Also establishes the zap anchor
     * once per session, from the id (or stream URL) the activity was launched
     * with.
     */
    private fun currentZapChannel(): LiveChannelZapRegistry.ZapChannel? {
        if (zapChannelIndex < 0) {
            zapChannelIndex = LiveChannelZapRegistry.indexOfChannel(parentId)
                .takeIf { it >= 0 }
                ?: LiveChannelZapRegistry.indexOfStreamUrl(currentUrl)
        }
        return LiveChannelZapRegistry.channelAt(zapChannelIndex)
    }

    /** Move to the channel [delta] positions away in the guide lineup. */
    private fun zapByOffset(delta: Int) {
        // One channel is not a lineup: nothing to move to, and a "zap" to
        // itself would only flash the banner.
        if (!LiveChannelZapRegistry.zapEnabled()) return
        if (currentZapChannel() == null) return

        val targetIndex = LiveChannelZapRegistry.indexOfChannel(
            LiveChannelZapRegistry.offsetChannel(zapChannelIndex, delta)?.channelId ?: return
        )
        if (targetIndex < 0) return
        val target = LiveChannelZapRegistry.channelAt(targetIndex) ?: return
        tuneToChannel(targetIndex, target)
    }

    /**
     * Switch to a channel from the zap lineup and report it in the banner.
     * Shared by UP/DOWN zapping and by typed channel numbers so both land the
     * user the same way.
     */
    private fun tuneToChannel(index: Int, channel: LiveChannelZapRegistry.ZapChannel) {
        zapChannelIndex = index

        // Always show the banner immediately with cached/known info — the
        // EPG row fills in async a moment later. Rapid-fire zapping re-shows
        // it and re-reads the (now cached) row.
        showZapBanner(channel)

        streamHeaders = channel.headers
        if (channel.streamUrl != currentUrl) {
            val zapStream = Stream(
                name = channel.name,
                title = channel.name,
                url = channel.streamUrl,
                audioUrl = null
            )
            // Replace the source list with the zapped channel only — keeps
            // currentSourceIndex valid so the auto-retry ladder recovers
            // THIS channel instead of silently falling back to the old one.
            sources = listOf(zapStream)
            switchToSource(zapStream)
            // A zap is a channel CHANGE, but the splash art (backdrop and
            // clearlogo) belongs to the channel this session started on, so
            // switchToSource's fresh-load splash would sit there naming the
            // channel the user just left. It has just reset the first-play
            // latch and raised that splash; put the latch back and take the
            // splash down instead, so the channel change shows the small
            // spinner while the zap banner carries the new channel.
            hasPlayedOnce = true
            hideSplash()
            bufferingSpinner.visibility = View.VISIBLE
        }
    }

    /**
     * Digits typed on the remote, accumulated until they stop or OK confirms
     * them. Gated to live playback with an overlay-free screen: everywhere
     * else the D-pad belongs to whatever panel is up.
     */
    /** OK in its several spellings across TV remotes and gamepads. */
    private fun isConfirmKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_BUTTON_SELECT

    private fun channelNumberEntryAllowed(): Boolean =
        isLiveChannel &&
            !controlsVisible &&
            !showSettingsPanel &&
            !isPickerShowing &&
            infoPanel.visibility != View.VISIBLE &&
            errorContainer.visibility != View.VISIBLE &&
            btnSkipIntro.visibility != View.VISIBLE &&
            LiveChannelZapRegistry.zapEnabled()

    private fun appendChannelNumberDigit(digit: Int) {
        val next = com.kennyb1201.kbstream.data.iptv.ChannelNumberEntry.push(
            channelNumberEntry, digit
        )
        if (next == channelNumberEntry) return
        channelNumberEntry = next
        channelNumberHud?.text = "CH $channelNumberEntry"
        channelNumberHud?.visibility = View.VISIBLE
        channelNumberHandler.removeCallbacks(channelNumberCommitRunnable)
        channelNumberHandler.postDelayed(channelNumberCommitRunnable, CHANNEL_NUMBER_COMMIT_MS)
    }

    private fun clearChannelNumberEntry() {
        channelNumberHandler.removeCallbacks(channelNumberCommitRunnable)
        channelNumberHandler.removeCallbacks(channelNumberHudHideRunnable)
        channelNumberEntry = ""
        channelNumberHud?.visibility = View.GONE
    }

    /**
     * Tune to whatever the typed number means, or say so. The number is
     * resolved against the same lineup UP/DOWN walks, so a number outside the
     * group being browsed is reported as absent rather than silently jumping
     * to another group.
     */
    private fun commitChannelNumberEntry() {
        val entry = channelNumberEntry
        if (entry.isEmpty()) return
        channelNumberHandler.removeCallbacks(channelNumberCommitRunnable)
        channelNumberEntry = ""

        val index = LiveChannelZapRegistry.indexOfChannelNumber(entry)
        val target = LiveChannelZapRegistry.channelAt(index)
        if (target == null) {
            channelNumberHud?.text = "NO CHANNEL $entry"
            channelNumberHud?.visibility = View.VISIBLE
            channelNumberHandler.removeCallbacks(channelNumberHudHideRunnable)
            channelNumberHandler.postDelayed(channelNumberHudHideRunnable, CHANNEL_NUMBER_ERROR_MS)
            return
        }
        channelNumberHud?.visibility = View.GONE
        tuneToChannel(index, target)
    }

    /**
     * Transient channel-info banner: channel identity + the NOW program
     * (with a progress bar) and NEXT. EPG data comes from the same Room DB
     * the guide uses, keyed off the channel's resolved guide id.
     */
    private fun showZapBanner(channel: LiveChannelZapRegistry.ZapChannel) {
        val banner = zapBanner ?: return

        // The lineup is the group the guide was browsing, not the whole
        // playlist, so name that group: it explains where UP/DOWN is moving
        // through (and why a channel that lives elsewhere won't come up).
        val scopeLabel = LiveChannelZapRegistry.browsingGroup()
            ?.takeIf { it.isNotBlank() && !it.equals("All", ignoreCase = true) }
            ?.uppercase()
        zapChannelLabel?.text = buildList {
            if (channel.chno?.isNotBlank() == true) add("CH ${channel.chno}")
            add("LIVE")
            scopeLabel?.let(::add)
        }.joinToString("  •  ")
        zapChannelName?.text = channel.name
        if (channel.logoUrl.isNullOrBlank()) {
            zapLogo?.setImageDrawable(null)
        } else {
            zapLogo?.load(channel.logoUrl)
        }

        val epgUrl = channel.epgUrl?.trim().orEmpty()
        val cacheKey = channel.channelId + "|" + epgUrl
        val cached = zapEpgCache[cacheKey]

        // Synchronous paint with whatever we already know.
        applyZapEpg(cached)
        banner.visibility = View.VISIBLE

        // Reset the auto-hide timer so rapid zapping never hides the banner
        // mid-press.
        zapHandler.removeCallbacks(zapBannerHideRunnable)
        zapHandler.postDelayed(zapBannerHideRunnable, ZAP_BANNER_VISIBLE_MS)

        scope?.launch {
            val info = resolveZapEpg(channel, cached)
            zapEpgCache[cacheKey] = info
            trimZapEpgCache()
            // Only apply if the banner is still showing THIS channel — a
            // slow DB read must never overwrite a newer zap. Both the
            // playing URL and the channel name must match (names are not
            // guaranteed unique across playlist groups).
            if (zapBanner?.visibility == View.VISIBLE &&
                zapChannelName?.text?.toString() == channel.name &&
                currentUrl == channel.streamUrl
            ) {
                applyZapEpg(info)
            }
        }
    }

    /**
     * The channel's now/next rows: the cached snapshot while it is fresh (or
     * when the channel has no guide to read at all), otherwise one read of the
     * same Room table the guide screen uses. Shared by the zap banner and the
     * overlay's live programme block, so both always report the same
     * programmes for the same channel.
     */
    private suspend fun resolveZapEpg(
        channel: LiveChannelZapRegistry.ZapChannel,
        cached: ZapEpgInfo?
    ): ZapEpgInfo {
        val epgUrl = channel.epgUrl?.trim().orEmpty()
        val epgChannelId = channel.epgChannelId
        val now = System.currentTimeMillis()
        val isFresh = cached != null && now - cached.fetchedAtMillis < ZAP_EPG_TTL_MS
        val noSource = epgUrl.isBlank() || epgChannelId.isNullOrBlank()
        return if (isFresh || noSource) {
            cached ?: ZapEpgInfo(now = null, next = null, fetchedAtMillis = now)
        } else {
            withContext(Dispatchers.IO) {
                loadZapEpg(epgUrl, epgChannelId!!)
            }
        }
    }

    /** Queries the guide DB for the channel's current + next program. */
    private suspend fun loadZapEpg(epgUrl: String, epgChannelId: String): ZapEpgInfo {
        return try {
            val dao = IptvDatabase.getInstance(applicationContext).iptvDao()
            val now = System.currentTimeMillis()
            // Full variant (not the Lite one): it returns the real
            // category/description columns, which the banner shows.
            val rows = dao.getProgramsForChannelsInWindow(
                sourceUrl = epgUrl,
                channelIds = listOf(epgChannelId),
                windowStart = now,
                windowEnd = now + ZAP_EPG_LOOKAHEAD_MS,
                perChannelLimit = ZAP_EPG_ROW_LIMIT
            )
            ZapEpgInfo(
                now = rows.firstOrNull { row ->
                    now >= row.startUtcMillis && now < row.endUtcMillis
                },
                next = rows.firstOrNull { row -> row.startUtcMillis >= now },
                fetchedAtMillis = now
            )
        } catch (t: Throwable) {
            Log.w(TAG, "ZAP EPG lookup failed: ${t.message}")
            ZapEpgInfo(now = null, next = null, fetchedAtMillis = System.currentTimeMillis())
        }
    }

    /** Paints one EPG snapshot into the banner views. */
    private fun applyZapEpg(info: ZapEpgInfo?) {
        val now = info?.now
        if (now == null) {
            // Distinguish "still loading" from "this channel has no guide":
            // the async lookup paints the real state within a beat.
            zapNowTitle?.text = if (info == null) "…" else "No guide data"
            zapNowMeta?.text = ""
            zapNowProgress?.visibility = View.GONE
            zapNowDesc?.visibility = View.GONE
            zapNextTitle?.text = ""
            return
        }

        zapNowTitle?.text = now.title
        zapNowMeta?.text = buildString {
            append(zapTimeFormat.format(Date(now.startUtcMillis)))
            append(" – ")
            append(zapTimeFormat.format(Date(now.endUtcMillis)))
            now.category?.takeIf { it.isNotBlank() }?.let { append("  •  ").append(it) }
        }

        val span = (now.endUtcMillis - now.startUtcMillis).coerceAtLeast(1L)
        val elapsed = (System.currentTimeMillis() - now.startUtcMillis)
            .coerceIn(0L, span)
        val synopsis = now.description?.trim().orEmpty()
        zapNowDesc?.text = synopsis
        zapNowDesc?.visibility = if (synopsis.isEmpty()) View.GONE else View.VISIBLE

        zapNowProgress?.visibility = View.VISIBLE
        zapNowProgress?.max = 1000
        zapNowProgress?.progress = ((elapsed * 1000L) / span).toInt()

        zapNextTitle?.text = info?.next?.let { next ->
            "Next  " + zapTimeFormat.format(Date(next.startUtcMillis)) + "  " + next.title
        } ?: ""
    }

    private fun trimZapEpgCache() {
        val overflow = zapEpgCache.size - zapEpgCacheLimit
        if (overflow > 0) {
            zapEpgCache.keys.take(overflow).forEach(zapEpgCache::remove)
        }
    }

    /**
     * True while the video surface - not a panel, picker, error card or skip
     * prompt - owns the remote, which is when UP/DOWN and CH+/CH- may zap.
     * One rule, read by both the surface's key listener and the activity's
     * [onKeyDown] fallback, so a channel press can never work in one place and
     * silently do nothing in the other.
     */
    private fun liveZapKeysFree(): Boolean =
        isLiveChannel &&
            !controlsVisible &&
            !showSettingsPanel &&
            !isPickerShowing &&
            errorContainer.visibility != View.VISIBLE &&
            btnSkipIntro.visibility != View.VISIBLE &&
            LiveChannelZapRegistry.zapEnabled()

    /** Cache key a channel's now/next rows are stored under. */
    private fun zapEpgCacheKey(channel: LiveChannelZapRegistry.ZapChannel): String =
        channel.channelId + "|" + channel.epgUrl?.trim().orEmpty()

    /**
     * Paints the overlay's live programme block for the channel playing now.
     * Opening a channel, zapping and raising the overlay all come through
     * here, so the block always describes the CURRENT channel. Guide rows are
     * read off the resolved now/next cache, so a repeat costs no query.
     */
    private fun refreshLiveProgramBlock() {
        val block = liveProgramBlock ?: return
        if (!isLiveChannel) {
            block.visibility = View.GONE
            return
        }
        val channel = currentZapChannel()
        if (channel == null) {
            // No lineup this session (a channel opened from outside the
            // guide): there is no guide id to look a programme up with.
            block.visibility = View.GONE
            return
        }

        val scopeLabel = LiveChannelZapRegistry.browsingGroup()
            ?.takeIf { it.isNotBlank() && !it.equals("All", ignoreCase = true) }
            ?.uppercase()
        liveProgramScope = buildList {
            add("LIVE")
            channel.chno?.takeIf { it.isNotBlank() }?.let { add("CH $it") }
            scopeLabel?.let(::add)
        }.joinToString("  \u2022  ")

        val cacheKey = zapEpgCacheKey(channel)
        val cached = zapEpgCache[cacheKey]
        // Instant paint with what we already know, then re-read if stale.
        applyLiveEpg(cached, channel.name)
        block.visibility = View.VISIBLE

        scope?.launch {
            val info = resolveZapEpg(channel, cached)
            zapEpgCache[cacheKey] = info
            trimZapEpgCache()
            // A slow read must never repaint the block for a channel the user
            // has already zapped away from.
            if (liveProgramBlock?.visibility == View.VISIBLE &&
                isLiveChannel &&
                currentZapChannel()?.channelId == channel.channelId
            ) {
                applyLiveEpg(info, channel.name, fresh = true)
            }
        }
    }

    /**
     * One now/next snapshot into the overlay's live block: programme title,
     * its air window (start and end), a progress bar for how far in we are,
     * the synopsis, and what follows.
     */
    private fun applyLiveEpg(
        info: ZapEpgInfo?,
        channelName: String,
        fresh: Boolean = false
    ) {
        val now = info?.now
        if (now == null) {
            // "…" only reads as loading while a read is still in flight;
            // otherwise the channel genuinely has no guide rows.
            liveProgramTitle?.text = if (info == null) {
                "\u2026"
            } else if (channelName.isNotBlank()) {
                channelName
            } else {
                "No programme data"
            }
            liveProgramStatus?.text = liveProgramScope
            liveProgramProgress?.visibility = View.GONE
            liveProgramDesc?.visibility = View.GONE
            liveProgramDesc?.text = ""
            liveProgramNext?.text = if (info == null) "" else "No guide data for this channel"
            return
        }

        liveProgramTitle?.text = now.title
        liveProgramStatus?.text = buildList {
            add(liveProgramScope)
            add(
                zapTimeFormat.format(Date(now.startUtcMillis)) +
                    " \u2013 " +
                    zapTimeFormat.format(Date(now.endUtcMillis))
            )
            now.category?.takeIf { it.isNotBlank() }?.let(::add)
        }.joinToString("  \u2022  ")

        val span = (now.endUtcMillis - now.startUtcMillis).coerceAtLeast(1L)
        val elapsed = (System.currentTimeMillis() - now.startUtcMillis)
            .coerceIn(0L, span)
        liveProgramProgress?.visibility = View.VISIBLE
        liveProgramProgress?.max = 1000
        liveProgramProgress?.progress = ((elapsed * 1000L) / span).toInt()

        val synopsis = now.description?.trim().orEmpty()
        liveProgramDesc?.text = synopsis
        liveProgramDesc?.visibility = if (synopsis.isEmpty()) View.GONE else View.VISIBLE

        liveProgramNext?.text = info.next?.let { next ->
            "Up next  " + zapTimeFormat.format(Date(next.startUtcMillis)) + "  " + next.title
        } ?: if (fresh) "No guide data for what follows" else ""
    }

    /**
     * Per-second tick while the overlay is up: advance the programme progress
     * bar, and roll the block over to the next programme once the current one
     * ends (the rows are re-read, never guessed from the clock).
     */
    private fun tickLiveProgramBlock() {
        if (!isLiveChannel || liveProgramBlock?.visibility != View.VISIBLE) return
        val channel = currentZapChannel() ?: return
        val info = zapEpgCache[zapEpgCacheKey(channel)]
        val now = info?.now
        val nowMs = System.currentTimeMillis()

        if (now != null && nowMs < now.endUtcMillis) {
            val span = (now.endUtcMillis - now.startUtcMillis).coerceAtLeast(1L)
            liveProgramProgress?.progress =
                (((nowMs - now.startUtcMillis).coerceIn(0L, span) * 1000L) / span).toInt()
            return
        }

        // Nothing resolved, or the programme just ended. Re-read only once the
        // cached row is older than the TTL, so a channel whose guide has
        // nothing for this slot cannot turn the per-second tick into a
        // per-second query.
        if (info == null || nowMs - info.fetchedAtMillis >= ZAP_EPG_TTL_MS) {
            refreshLiveProgramBlock()
        }
    }
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
    /*
     * Episode count of the season being played — the season's FULL list, not
     * just the aired ones, so `nextEpisodeTarget()` (below, past the tooling's
     * edit window) can tell an end-of-season episode apart from a mid-season
     * one. It carries no air dates, which is why the end-of-playback chain
     * needs the air-date gate in [airedNextEpisodeTarget] instead of trusting
     * the next episode number on its own.
     */
    private var totalEpisodesInSeason: Int? = null
    private var streamHeaders = emptyMap<String, String>()
    private var drmLicenseUrl: String? = null
    private var drmHeaders = emptyMap<String, String>()
    private var externalSubtitleUri: Uri? = null
    // Online subtitle search (OpenSubtitles): pending query results, loading
    // flag for the picker, and the resolved uri being applied right now.
    private var onlineSubResults: List<SubtitleSearchResult> = emptyList()
    private var onlineSubLoading = false
    private var startPositionMs = 0L

    /**
     * The launch explicitly asked for the beginning (Home's long-press "Play
     * from Beginning"). Position 0 is otherwise read as "this launch carries no
     * resume information", which is answered from the watch history.
     */
    private var startFromBeginning = false

    /**
     * Opened by the detail page's Random button: the player chains into random
     * aired episodes of the show instead of the arithmetic next one, for as
     * long as this playback - and the handoffs it spawns - continues.
     */
    private var randomEpisodes = false
    private var fromActorReturn = false

    /// Set when the paused-overlay for an actor-return session has been
    /// shown once, so STATE_READY never re-triggers it mid-session.
    private var actorReturnOverlayShown = false

    /// True once the first video frame has actually rendered during this
    /// player session (set by markFirstFrameRendered). Gates the full splash
    /// overlay: it appears on every fresh source load (each switchToSource
    /// resets it), but never on mid-playback rebuffers or when returning from
    /// the actor overlay (fromActorReturn).
    private var hasPlayedOnce = false
    private var historyId = ""

    /// Cached canonical id for the playback-history row (see
    /// [canonicalHistoryParentId]); null until first resolved.
    private var historyParentIdOverride: String? = null
    private var simklScrobbleSent = false
    private var simklSyncJob: kotlinx.coroutines.Job? = null
    private var simklScrobbleActive = false
    private var simklScrobblePaused = false
    private var simklScrobbleJob: kotlinx.coroutines.Job? = null

    /// True once this session has been handed to the MPV backup engine. One
    /// handoff per session: a second one would start a second player on top of
    /// the first.
    private var mpvHandoffStarted = false

    /**
     * Result of an MPV handoff (see [handOffToMpv]).
     *
     * Started FOR RESULT rather than with startActivity so this activity stays
     * in the chain and forwards what MPV decides: MainActivity's player-result
     * callback then fires exactly as it would have for a normal exit, which is
     * what keeps "next episode" working after a mid-title engine switch.
     */
    private val mpvFallbackLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (!isFinishing && !isDestroyed) {
            setResult(result.resultCode, result.data)
            finish()
        }
    }

    // IntroDB
    private var introDbStamps = emptyList<IntroDbStamp>()

    // Auto-skip (Settings > Playback). The intro poller only ever *offers* a
    // segment; these decide whether it is taken without a press.
    private var autoSkipIntros = false
    private var autoSkipCredits = false

    // Segments already auto-skipped this session, so deliberately seeking back
    // into an intro is never fought.
    private val autoSkippedSegments = HashSet<String>()

    /// Uptime until which a duplicate of the confirm press that skipped a
    /// segment is still absorbed as that press's own trailing event.
    private var skipConfirmGraceUntilMs = 0L
    private var activeIntroStampBacking: IntroDbStamp? = null

    /**
     * The segment currently on screen. A property rather than a plain field
     * because its setter is the one funnel every offer passes through (the
     * poller assigns it), which is what makes auto-skip possible without
     * touching the polling code itself.
     */
    private var activeIntroStamp: IntroDbStamp?
        get() = activeIntroStampBacking
        set(value) {
            activeIntroStampBacking = value
            if (value != null) onIntroStampOffered(value)
        }

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

    // Earliest-end trigger: the Up Next / Because-you-watched panel now opens
    // during the end credits (or shortly before the end) instead of waiting
    // for STATE_ENDED, which many streams never fire. Set once the panel is
    // shown so the real end event does not show it a second time.
    private var endPanelsShown = false

    // True while the because-you-watched panel is up over a shrunk (corner)
    // video, so the credits keep playing in the corner.
    private var creditsModeActive = false

    // The panel's layout params from before credits mode (width/gravity picked
    // at runtime), so leaving credits mode restores the XML sizing exactly.
    private var creditsModePanelParams: android.widget.FrameLayout.LayoutParams? = null
    private var episodeTitle: String? = null
    private var preferredAudioLang = ""
    private var preferredSubtitleLang = ""

    // "Up next" popup state
    private var pendingNextSeason: Int? = null
    private var pendingNextEpisode: Int? = null
    private var pendingNextEpisodeName: String? = null
    // Dedupes the overlay's best-effort next-episode-name prefetch so
    // showControls() doesn't hit TMDB on every auto-hide cycle.
    private var overlayNextPrefetchKey: String? = null
    private var pendingNextEpisodeRuntime: Int? = null
    private var nextUpCountdownRemaining = 0

    // True when the Up Next panel opened while the episode still had real time
    // left: the auto-advance countdown waits for the end of the episode rather
    // than running from the moment the early panel appeared.
    private var nextUpCountdownHeld = false
    private val nextUpCountdownHandler = Handler(Looper.getMainLooper())
    private val nextUpCountdownRunnable = object : Runnable {
        override fun run() {
            nextUpCountdownRemaining--
            if (nextUpCountdownRemaining <= 0) {
                // The countdown fired unattended: count this as an
                // auto-advanced episode for the "Are you still there?"
                // binge watchdog.
                if (AppPreferences.getStillTherePrompt(this@NativePlayerActivity)) {
                    val count = AppPreferences.getConsecutiveAutoplays(this@NativePlayerActivity) + 1
                    AppPreferences.setConsecutiveAutoplays(this@NativePlayerActivity, count)
                }
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

    // Subtitle file import. TV ROMs (Fire TV, some Google TVs) ship without
    // the system DocumentsUI picker, so SAF can throw
    // ActivityNotFoundException at launch time. Fall back to a generic
    // GET_CONTENT picker before giving up with an explanatory toast.
    private fun launchExternalSubtitlePicker() {
        val mimeTypes = arrayOf("text/plain", "text/*", "application/octet-stream")
        try {
            externalSubtitlePicker.launch(mimeTypes)
        } catch (_: ActivityNotFoundException) {
            try {
                externalSubtitleGetContentPicker.launch("*/*")
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(
                    this,
                    "No file picker on this device — use the URL import instead",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private val externalSubtitlePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        handleExternalSubtitleUri(uri)
    }

    private val externalSubtitleGetContentPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        handleExternalSubtitleUri(uri)
    }

    private fun handleExternalSubtitleUri(uri: Uri?) {
        if (uri == null) return
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Some providers do not offer persistable permissions; the current
            // playback session can still use the granted URI permission.
        }
        attachExternalSubtitle(uri)
    }

    /**
     * Identity of the video the remembered subtitle belongs to. Shows key on
     * show + season + episode (a sidecar file is authored for one episode);
     * everything else falls back to the history item id.
     */
    private fun subtitleMemoryKey(): String? =
        PlayerTrackMemory.keyFor(
            parentId = parentId,
            mediaId = historyId,
            season = season,
            episode = episode
        )

    /**
     * Shared apply path for every subtitle source: file picker, URL import,
     * and OpenSubtitles downloads (cache file). Rebuilds the player so the
     * sidecar subtitle config attaches, preserving position.
     */
    private fun attachExternalSubtitle(uri: Uri) {
        externalSubtitleUri = uri
        // Remembered per video (not per show: a sidecar file belongs to one
        // episode) so reopening it re-attaches automatically. Profile-scoped
        // and device-local — the URI only resolves on this device.
        PlayerTrackMemory.rememberSubtitle(
            context = this,
            key = subtitleMemoryKey(),
            uri = uri.toString()
        )
        loadExternalSubtitleCues(uri)
        carryPositionMs = exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L
        recreatePlayer()
    }

    /**
     * Queries OpenSubtitles for the playing item, then re-opens the subtitle
     * picker with the results as rows. Empty result set surfaces a toast.
     */
    private fun startOnlineSubtitleSearch() {
        if (onlineSubLoading) return
        val queryTitle = itemName
        if (AppPreferences.getOpensubtitlesApiKey(this).isBlank()) {
            Toast.makeText(this, "Add an OpenSubtitles API key in Settings", Toast.LENGTH_LONG).show()
            return
        }
        if (queryTitle.isBlank()) {
            Toast.makeText(this, "No title available to search with", Toast.LENGTH_LONG).show()
            return
        }
        onlineSubLoading = true
        val lang = AppPreferences.getPreferredSubtitleLanguage(this)
        lifecycleScope.launch {
            val results = SubtitleSearchHelper.search(
                this@NativePlayerActivity,
                title = queryTitle,
                season = season,
                episode = episode,
                languageHint = lang
            )
            onlineSubLoading = false
            onlineSubResults = results
            if (results.isEmpty()) {
                Toast.makeText(this@NativePlayerActivity, "No subtitles found", Toast.LENGTH_SHORT).show()
            } else {
                showPicker(PickerMode.SUBTITLE)
            }
        }
    }

    /** Downloads the picked subtitle into cache and attaches it sidecar-style. */
    private fun downloadOnlineSubtitle(hit: SubtitleSearchResult) {
        Toast.makeText(this, "Loading subtitle…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val body = SubtitleSearchHelper.download(this@NativePlayerActivity, hit)
            if (body.isNullOrBlank()) {
                Toast.makeText(this@NativePlayerActivity, "Subtitle download failed", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val uri = SubtitleSearchHelper.toCacheUri(this@NativePlayerActivity, hit, body)
            attachExternalSubtitle(uri)
        }
    }

    /**
     * Subtitle placement chosen from the player panel: the same pref the
     * Settings pane writes, moved live on the cue view.
     */
    internal fun subtitlePositionApplied(position: Int) {
        subtitlePosition = position
        applySubtitlePosition()
    }

    /**
     * IntroDB is keyed by IMDb id, but a title opened from a TMDB id carries
     * only the numeric one — and the segment fetcher has no Context with which
     * to resolve it. So the lookup runs here at playback start and the fetcher
     * picks the answer up, falling back to the id it was given if it is slow.
     */
    private fun startIntroDbImdbHint() {
        IntroDbHints.begin()
        val numericId = parentId.trim().toLongOrNull()
        if (numericId == null) {
            // Already an IMDb id: nothing to resolve.
            IntroDbHints.publish(null)
            return
        }
        val type = if (parentType.lowercase() == "movie") "movie" else "series"
        val tmdbId = numericId.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        lifecycleScope.launch {
            val imdbId = runCatching {
                withContext(Dispatchers.IO) {
                    TmdbRepository.getInstance(this@NativePlayerActivity)
                        .resolveImdbId(tmdbId, type)
                }
            }.getOrNull()
            IntroDbHints.publish(imdbId)
        }
    }

    /**
     * Keeps the poster out of the two title-graphic slots.
     *
     * With no clear logo art, updateHeaderInfo() falls back to [itemPoster].
     * That is a PORTRAIT image being put where a wide title graphic belongs:
     * in the controls header it stands in for the clearlogo, and in the splash
     * it is drawn into the 240x80 logo slot, which PULSES (1.0 -> 1.08,
     * 0.7 -> 1.0 alpha) — so a portrait poster ends up pulsing where the title
     * should be, which is what reads as a poster flashing on the loading
     * splash. Both slots already have a designed no-logo state (the item name),
     * and that is exactly what the header shows when a logo is absent, so the
     * poster fallback is undone here instead of being relied on.
     *
     * Deliberately narrow: it only acts when there is no clear logo at all, so
     * a real logo is never touched. Called again from a bounds listener on each
     * affected view, because the poster arrives asynchronously from the image
     * loader.
     */
    private fun enforceTitleGraphicPolicy() {
        if (!clearLogoUrl.isNullOrBlank()) {
            // Real logo art: the title text has no business showing.
            splashItemName?.visibility = View.GONE
            return
        }
        // Nothing arrived with the launch. Before settling for the name text,
        // look for the art the launch did not carry — this is the difference
        // between the same show showing its clearlogo or a poster.
        resolveClearLogoFromTmdb()
        if (itemName.isBlank()) return
        if (clearLogo.drawable != null) {
            clearLogo.setImageDrawable(null)
            clearLogo.visibility = View.GONE
        }
        if (splashClearLogo.drawable != null) {
            splashClearLogo.setImageDrawable(null)
            splashClearLogo.visibility = View.GONE
        }
        // The same treatment updateHeaderInfo() gives the no-logo case, guarded
        // so a repeated pass asks for no layout of its own.
        if (itemNameView.text?.toString() != itemName) itemNameView.text = itemName
        if (itemNameView.visibility != View.VISIBLE) itemNameView.visibility = View.VISIBLE

        // The poster can also land WITHOUT resizing its view (an image whose
        // aspect happens to match the slot), so a bounds change is not a
        // guaranteed signal. Re-assert a few times across the splash's
        // lifetime instead of trusting any single one. Posted once per session.
        if (!titleGraphicRechecksPosted) {
            titleGraphicRechecksPosted = true
            for (delayMs in longArrayOf(150L, 500L, 1200L)) {
                window.decorView.postDelayed({ enforceTitleGraphicPolicy() }, delayMs)
            }
        }

        // And the no-logo state for the splash itself: the item name centred on
        // the backdrop, exactly how the pre-player "Finding sources" splash
        // shows a title it has no logo art for.
        val title = splashItemName ?: findViewById<TextView>(R.id.splash_item_name)?.also {
            splashItemName = it
        }
        if (title != null) {
            if (title.text?.toString() != itemName) title.text = itemName
            if (title.visibility != View.VISIBLE) title.visibility = View.VISIBLE
        }
    }

    /**
     * Fetches the title's logo art when the launch handed the player none.
     *
     * Which logo a playback gets depends on WHERE it was started: the detail
     * page resolves the title's own TMDB logo art, while the streams picker
     * passes only the addon meta's `logo` — and plenty of metas carry none. So
     * the same show shows its clearlogo when played from the detail page and
     * the poster when a stream is picked again afterwards; the two launches
     * differ only in what they carry, not in what art exists.
     *
     * This is the cached lookup, so a replay of a title whose detail page was
     * just open resolves from memory (or disk) rather than the network.
     */
    private fun resolveClearLogoFromTmdb() {
        if (!clearLogoUrl.isNullOrBlank() || clearLogoLookupStarted) return
        if (isLiveChannel || parentId.isBlank()) return
        val type = parentType.lowercase()
        if (type != "movie" && type != "series") return
        clearLogoLookupStarted = true
        lifecycleScope.launch {
            val path = runCatching {
                withContext(Dispatchers.IO) {
                    TmdbRepository.getInstance(this@NativePlayerActivity)
                        .fetchEnrichedMetaCached(parentId, type)
                }
            }.getOrNull()?.bestLogoPath()?.takeIf { it.isNotBlank() }
            if (path == null) return@launch
            val url = TmdbRepository.LOGO_BASE + path
            // Recorded first: the policy pass early-returns once a logo exists,
            // so the loads below are what put it on screen.
            clearLogoUrl = url
            applyClearLogo(url)
        }
    }

    /**
     * Puts a logo into both title slots in place of the name-text fallback.
     * The image loader delivers it asynchronously, which is exactly why the
     * policy pass runs more than once.
     */
    private fun applyClearLogo(url: String) {
        clearLogo.load(url)
        clearLogo.visibility = View.VISIBLE
        splashClearLogo.load(url)
        splashClearLogo.visibility = View.VISIBLE
        itemNameView.visibility = View.GONE
        splashItemName?.visibility = View.GONE
    }

    /**
     * True when nothing but the video (or a skip prompt) owns the screen: no
     * controls overlay, panel, picker, error card or next-up popup. The
     * confirm-key handling in [dispatchKeyEvent] bails out on a false here, so
     * those UIs keep OK for their own buttons.
     */
    private fun plainPlaybackForeground(): Boolean =
        !controlsVisible &&
            !showSettingsPanel &&
            !isPickerShowing &&
            errorContainer.visibility != View.VISIBLE &&
            infoPanel.visibility != View.VISIBLE &&
            !(::nextUpPanel.isInitialized && nextUpPanel.visibility == View.VISIBLE)

    /**
     * True while the credits "Because you watched" panel owns the screen.
     *
     * Deliberately NOT folded into [plainPlaybackForeground]: that predicate
     * also decides whether OK activates a skip prompt, and a "Skip Credits"
     * prompt appearing over the recommendations must keep that press. What the
     * panel does own is LEFT/RIGHT - its picks take focus precisely so the user
     * can step between them - so [handleSurfaceScrubKey], the one function every
     * direct scrub goes through, asks this before seeking the credits playing
     * in the corner, and [dispatchKeyEvent] asks it before letting the press
     * fall through to the view tree.
     */
    private fun creditsPanelForeground(): Boolean =
        ::becauseYouWatchedPanel.isInitialized &&
            becauseYouWatchedPanel.visibility == View.VISIBLE

    /**
     * The D-pad keys that raise the controls overlay. Swallowed while a skip
     * prompt is up - see [dispatchKeyEvent] - because a skippable segment is
     * the prompt's alone. Nothing else loses anything by it: UP/DOWN only ever
     * reach the overlay or the live channel zap (and a live channel never
     * offers a prompt). LEFT/RIGHT are deliberately NOT in this set: they seek
     * the video directly and raise nothing - not even the prompt - while the
     * overlay is down. See [handleSurfaceScrubKey].
     */
    private fun isOverlayRaisingKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN

    /**
     * While a segment is skippable, the controls overlay cannot be raised and
     * OK can only do one thing: skip.
     *
     * The player's own confirm handling sits on the video surface's key
     * listener, which picks between "activate the skip prompt" and "toggle the
     * controls overlay" by reading the prompt's visibility as the press
     * arrives. The press that skips hides the prompt while doing so, so every
     * event after it in the same press - a held key's autorepeat, or the
     * trailing half of an IR remote's double-fire - was read as "no prompt
     * up" and toggled the overlay on over the video the user had just skipped
     * into. Focus then sat on the overlay's play/pause button, so the next OK
     * press toggled playback instead of dismissing it and Back was the only
     * way out of the overlay. The same handler is what opened the overlay on
     * UP/DOWN/LEFT/RIGHT, and it read the visible prompt as "the user is
     * reaching for the skip button", so it opened the overlay *and* parked
     * focus on the prompt.
     *
     * Resolving both here - once, before the view tree sees them - is what
     * makes a skippable segment prompt-only. Media keys are deliberately left
     * alone: a pause is a deliberate request and pausing needs the overlay.
     * The guard keeps every panel, picker and popup working normally.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // LEFT/RIGHT seek the video directly while nothing but the video (or a
        // skip prompt) is on screen, and raise nothing while doing it. This
        // hangs off the activity rather than the video surface's key listener
        // because the focused view is not always the surface: a skip prompt
        // holds focus while it is up, and a key it does not consume is never
        // re-offered to the surface's listener from there. Only an item that
        // can actually seek takes the key - live TV and a stream without a
        // duration fall through to whatever handled them before.
        val horizontal = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        if (plainPlaybackForeground() && horizontal) {
            if (creditsPanelForeground()) {
                // The credits recommendations own LEFT/RIGHT for as long as
                // they are up, so the press must never reach the scrub - which
                // declines it for the same reason, see [handleSurfaceScrubKey].
                // Focus is not guaranteed to be inside the panel yet: the row
                // fills in a beat after the panel opens, and a skip prompt hands
                // focus back to the video surface when it hides. Park it on the
                // first pick so the press steps through them instead of
                // scrubbing the credits they are recommending over.
                if (event.action == KeyEvent.ACTION_DOWN &&
                    !bywUi.hasFocus() &&
                    bywUi.focusFirst()
                ) {
                    return true
                }
            } else if (handleSurfaceScrubKey(event.keyCode, event)) {
                return true
            }
        }
        if (plainPlaybackForeground()) {
            if (btnSkipIntro.visibility == View.VISIBLE) {
                if (isConfirmKey(event.keyCode)) {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        // One press, one skip: autorepeat must not skip twice,
                        // and the grace window is armed off the press that did
                        // skip.
                        if (event.repeatCount == 0) btnSkipIntro.performClick()
                        skipConfirmGraceUntilMs =
                            android.os.SystemClock.uptimeMillis() + SKIP_CONFIRM_GRACE_MS
                    }
                    return true
                }
                if (isOverlayRaisingKey(event.keyCode)) return true
            } else if (
                isConfirmKey(event.keyCode) &&
                android.os.SystemClock.uptimeMillis() < skipConfirmGraceUntilMs
            ) {
                // The prompt is gone - either the press above consumed it, or
                // the segment ended on its own. Absorb what is left of that
                // press instead of letting it become an overlay toggle.
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Bulk guide (EPG) writes wait for playback to end while this activity
        // is on screen: an XMLTV import re-keying thousands of rows underneath
        // a starting player is what turned into multi-hundred-millisecond GC
        // pauses and a multi-second rebuffer stall. Cleared in onStop, with
        // onDestroy as the safety net.
        EpgWriteGate.setPlayerActive(true)

        // Playback is the memory peak of the whole app: media3's sample buffer
        // and the codec's native allocations land on top of whatever browsing
        // left resident. Free that headroom up front instead of hoping the
        // system asks in time — decoded artwork and the EPG snapshot are both
        // rebuildable, and the player loads whatever art it needs itself.
        runCatching {
            releaseImageMemoryCache(this)
            MemoryPressure.releaseBrowsingCaches()
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Hide system bars
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContentView(R.layout.activity_player)
        bindViews()
        // An overlay that was already up when a segment became skippable comes
        // down with the prompt's arrival, so a skip segment never has one on
        // screen - whether it was opened before the segment or through the key
        // paths this class does not own (a touch tap on the video). A panel,
        // picker or info view is left alone: those are deliberate, and their
        // owner tears them down.
        btnSkipIntro.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (btnSkipIntro.visibility == View.VISIBLE &&
                controlsVisible &&
                !showSettingsPanel &&
                !isPickerShowing &&
                infoPanel.visibility != View.VISIBLE
            ) {
                hideControls()
            }
        }
        addonSubtitleController = AddonSubtitleController(this) {
            // Separate-audio sessions cannot be rebuilt via setMediaItem
            // without losing the merged audio track, so addon subtitle
            // tracks are skipped there (embedded subs still work).
            !currentAudioUrl.isNullOrBlank()
        }
        // Fire TV workaround: the native window can be lost during activity
        // startup before ExoPlayer initializes, so the decoder configures its
        // output surface against a dead window and never produces a frame.
        // Flipping the video SurfaceView's visibility destroys and recreates
        // its native surface with the correct window ID before the player is
        // built. playerView is a PlayerView (FrameLayout) — the real
        // SurfaceView is a CHILD, so the original `playerView is SurfaceView`
        // test was never true and this bounce never actually ran.
        playerView.post {
            val surfaceView = findVideoSurfaceView(playerView)
            if (surfaceView != null) {
                surfaceView.visibility = android.view.View.INVISIBLE
                surfaceView.post { surfaceView.visibility = android.view.View.VISIBLE }
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
        // Back handling. The deprecated onBackPressed() override is replaced by
        // this OnBackPressedDispatcher callback (same behavior): it runs first,
        // and disabling it before re-dispatching lets Back fall through to the
        // Activity's default (finish).
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // If the loading splash (pulsing clearlogo) is still up, the
                // stream hasn't started playing yet. Treat Back as an immediate
                // exit request instead of routing it through the controls/panel
                // handling - a user stuck on the splash must always be able to
                // leave with one press.
                if (::splashContainer.isInitialized &&
                    splashContainer.visibility == View.VISIBLE
                ) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    return
                }
                when {
                    // A typed number is a pending action, so Back cancels it
                    // before Back means "leave the channel".
                    channelNumberEntry.isNotEmpty() -> { clearChannelNumberEntry(); return }
                    isPickerShowing -> { dismissPicker(); showControls(); return }
                    showSettingsPanel -> { dismissSettingsPanel(); showControls(); return }
                    infoPanel.visibility == View.VISIBLE -> { hideInfoPanel(); showControls(); return }
                    controlsVisible -> { hideControls(); return }
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })
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
        // A system-recreated activity comes back with its original launch
        // intent, and that intent's start position only says where playback
        // STARTED (0 when the title was started from the beginning). The
        // activity's own saved state carries the playhead, so a restored
        // playback picks up mid-episode instead of restarting the title.
        val restoredPositionMs =
            savedInstanceState?.getLong(STATE_PLAYER_POSITION_MS, 0L) ?: 0L
        if (restoredPositionMs > startPositionMs) startPositionMs = restoredPositionMs
        fromActorReturn = intent.getBooleanExtra("from_actor_return", false)
        startFromBeginning = intent.getBooleanExtra("from_beginning", false)
        randomEpisodes = intent.getBooleanExtra("random_episodes", false)
        // A launch handed over by another engine carries the parent id that
        // engine already canonicalized, so both sessions write the same
        // Continue Watching row instead of one card per id flavor.
        historyParentIdOverride = intent
            .getStringExtra(MpvPlayerActivity.EXTRA_HISTORY_PARENT_ID)
            ?.takeIf { it.isNotBlank() }
        carryPositionMs = startPositionMs
        streamHeaders = parseHeaders(intent.getStringExtra(EXTRA_HEADERS).orEmpty())
        drmLicenseUrl = intent.getStringExtra(EXTRA_DRM_LICENSE_URL)
        drmHeaders = parseHeaders(intent.getStringExtra(EXTRA_DRM_HEADERS).orEmpty())
        resizeModeIndex = AppPreferences.getDefaultAspectRatio(this)
        // Applied in createPlayer(); no-op until the content frame exists.
        enableTunneling = AppPreferences.getEnableTunneling(this)
        bufferMode = AppPreferences.getDefaultBufferMode(this)
        subtitleSize = AppPreferences.getDefaultSubtitleSize(this)
        subtitleBackground = AppPreferences.getDefaultSubtitleBackground(this)
        subtitlePosition = AppPreferences.getDefaultSubtitlePosition(this)
        autoPlayNext = AppPreferences.getAutoPlayNext(this)
        autoSkipIntros = AppPreferences.getAutoSkipIntro(this)
        autoSkipCredits = AppPreferences.getAutoSkipCredits(this)
        // bindViews() already ran, so the cue view exists; placement can only
        // be applied once the pref above is known.
        applySubtitlePosition()
        val globalAudioLang = AppPreferences.getPreferredAudioLanguage(this)
        val globalSubtitleLang = AppPreferences.getPreferredSubtitleLanguage(this)
        preferredAudioLang = globalAudioLang
        preferredSubtitleLang = globalSubtitleLang

        // Per-show memory: this title's own language / A-V offset choices beat
        // the global defaults, so binging a series does not mean re-picking
        // tracks or re-tuning the offsets on every episode. Live channels have
        // no title key, so nothing is remembered for them (the panel still
        // applies for the session).
        PlayerTrackBridge.setGlobalLanguages(globalAudioLang, globalSubtitleLang)
        PlayerTrackBridge.loadFor(
            context = this,
            titleKey =
                if (isLiveChannel) null
                else PlayerTitlePrefs.titleKeyFor(parentId, historyId),
            globalAudioLanguage = globalAudioLang,
            globalSubtitleLanguage = globalSubtitleLang
        )
        preferredAudioLang = PlayerTrackBridge.audioLanguage.ifBlank { globalAudioLang }
        preferredSubtitleLang = PlayerTrackBridge.subtitleLanguage.ifBlank { globalSubtitleLang }
        subtitleOffsetMs = PlayerTrackBridge.subtitleOffsetMs
        AudioDelayProcessor.instance.setDelayMs(PlayerTrackBridge.audioDelayMs)

        // The playback panel lives in its own file and drives the running
        // player through these appliers (weak refs: a finished activity must
        // never be kept alive by the bridge singleton).
        val bridgeSelf = java.lang.ref.WeakReference(this)
        PlayerTrackBridge.register(
            applyAudioLanguage = { code ->
                bridgeSelf.get()?.applyChosenAudioLanguage(code)
            },
            applySubtitleLanguage = { code ->
                bridgeSelf.get()?.applyChosenSubtitleLanguage(code)
            },
            applyAudioDelay = { ms ->
                AudioDelayProcessor.instance.setDelayMs(ms)
            },
            applySubtitleOffset = { ms ->
                bridgeSelf.get()?.applyChosenSubtitleOffset(ms)
            },
            // Lets the panel list the file's real audio tracks and override
            // directly; resolved lazily because the player is built later.
            playerProvider = { bridgeSelf.get()?.exoPlayer }
        )

        // The panel's own rows sit past the tooling's edit window, so the
        // language / track / A-V controls are appended to it in code. They are
        // re-rendered whenever the panel becomes visible — showing it changes
        // its bounds, which is exactly what this listener fires on.
        settingsPanelSection = PlayerPanelSection(this, settingsContainer).also { section ->
            section.attach()
            settingsContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                if (settingsContainer.visibility == View.VISIBLE) section.refresh()
            }
        }

        // Bring back the subtitle attached to THIS video last time. Only the
        // URI is seeded here: createPlayer() turns it into the sidecar
        // SubtitleConfiguration, and setting it early is what makes the
        // restored track appear without a second player rebuild.
        if (!isLiveChannel) {
            PlayerTrackMemory.rememberedSubtitle(this, subtitleMemoryKey())
                ?.uri
                ?.let { remembered ->
                    runCatching { Uri.parse(remembered) }
                        .getOrNull()
                        ?.let { externalSubtitleUri = it }
                }
        }
        totalEpisodesInSeason = intent.getIntExtra("total_episodes_in_season", -1).takeIf { it > 0 }

        // Discover addon subtitles (Stremio "subtitles" resource) and merge
        // them as sidecar text tracks once the player attaches. Skipping is
        // automatic for live channels and when no video id is available.
        if (!isLiveChannel) {
            addonSubtitleController.bind(
                view = playerView,
                parentId = parentId,
                parentType = parentType,
                season = season,
                episode = episode
            )
        }

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
                        // optString(key, "").ifBlank { null }: JSONObject's
                        // fallback parameter is @NonNull, so a null fallback
                        // makes Kotlin infer a non-null result while a missing
                        // key / JSON null actually yields null.
                        name = obj.optString("name", "").ifBlank { null },
                        title = obj.optString("title", "").ifBlank { null },
                        description = obj.optString("description", "").ifBlank { null },
                        url = obj.optString("url", "").ifBlank { null },
                        audioUrl = obj.optString("audioUrl", "").ifBlank { null },
                        infoHash = obj.optString("infoHash", "").ifBlank { null },
                        fileIdx = obj.optInt("fileIdx", -1).takeIf { it >= 0 },
                        behaviorHints = StreamBehaviorHints(
                            bingeGroup = obj.optString("bingeGroup", "").ifBlank { null }
                        ),
                        badges = parseStreamBadges(obj.optJSONArray("badges"))
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
                        character = obj.optString("character", "").ifBlank { null },
                        profilePath = obj.optString("profilePath", "").ifBlank { null }
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
                // The band is filled a beat after the activity's own chrome was
                // themed, so each card is themed as it lands - the same reason
                // the picker's rows retint when they attach.
                if (AppPreferences.getAmoledBlack(this)) refillPlayerChrome(itemView)
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

        historyId = PlaybackHistoryIds.historyId(
            parentId = parentId,
            season = season,
            episode = episode,
            episodeStreamId = episodeStreamId
        )

        sources.firstOrNull { it.url == currentUrl }?.let { first ->
            currentSourceLabel = first.displayLabel()
            currentBadges = first.badges
            currentBingeGroup = first.bingeGroup
        }
        resolveAddonIdentity(currentSourceLabel)
            ?: sources.firstOrNull()?.displayLabel()
            ?: "Current source"
        currentSourceIndex = sources.indexOfFirst { it.url == currentUrl }

        // Now that season/episode are parsed, set dynamic visibility
        btnNext.visibility = if (season != null && episode != null) View.VISIBLE else View.GONE

        updateHeaderInfo()
        // A poster is not a title graphic: see enforceTitleGraphicPolicy.
        enforceTitleGraphicPolicy()
        // Re-assert it as the images land. The poster comes from the image
        // loader asynchronously, and adjustViewBounds means a landed image
        // always resizes its view — so a bounds change is exactly the signal
        // that something was just drawn into one of these slots.
        clearLogo.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            enforceTitleGraphicPolicy()
        }
        splashClearLogo.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            enforceTitleGraphicPolicy()
        }
        updateControlsInfo()

        setupListeners()
        setupKeyboardHandler()
        // Before setupIntroDb(): the fetch waits briefly for this hint.
        startIntroDbImdbHint()
        // Nothing in this launch asked to resume, so ask the watch history: a
        // launch carrying no position of its own (a source picked from the
        // picker, a rebuilt or restored player) would otherwise replay a
        // partially watched title from the beginning. An explicit "from the
        // beginning" is honoured as-is, and the player is only created after
        // the read so the load-time seek already carries the position - nothing
        // starts at 0 and jumps. A failed or empty read leaves the launch as it
        // was.
        if (!isLiveChannel && startPositionMs <= 0L && !startFromBeginning &&
            historyId.isNotBlank()
        ) {
            lifecycleScope.launch {
                val savedPositionMs = runCatching {
                    withContext(Dispatchers.IO) {
                        WatchHistoryDatabase.getInstanceScoped(this@NativePlayerActivity)
                            .watchHistoryDao()
                            .getById(historyId)
                            ?.takeIf { !it.isCompleted && it.positionMs > 0L }
                            ?.positionMs
                    }
                }.getOrNull()
                if (savedPositionMs != null) {
                    Log.i(
                        TAG,
                        "resume: launch had no position, using saved " + savedPositionMs + "ms"
                    )
                    startPositionMs = savedPositionMs
                    carryPositionMs = savedPositionMs
                }
                setupIntroDb()
                createPlayer()
            }
        } else {
            setupIntroDb()
            createPlayer()
        }
    }

    /**
     * Audio language chosen in the playback panel. Blank is "Auto": drop our
     * own override and let the stream's default track win.
     */
    private fun applyChosenAudioLanguage(code: String) {
        preferredAudioLang = code
        val player = exoPlayer ?: return
        if (code.isBlank()) {
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .build()
            return
        }
        PlayerTrackBridge.applyLanguage(player, C.TRACK_TYPE_AUDIO, code)
    }

    /** Subtitle language chosen in the panel (blank = subtitles off). */
    private fun applyChosenSubtitleLanguage(code: String) {
        preferredSubtitleLang = code
        PlayerTrackBridge.applyLanguage(exoPlayer, C.TRACK_TYPE_TEXT, code)
    }

    /** Subtitle offset chosen in the panel: same path as the +/- buttons. */
    private fun applyChosenSubtitleOffset(ms: Int) {
        subtitleOffsetMs = ms
        runCatching { subtitleOffsetValue.text = "${ms}ms" }
    }

    /**
     * One press of the panel's own subtitle-offset pads.
     *
     * The pads used to move [subtitleOffsetMs] directly, which meant an offset
     * tuned in the player was forgotten the moment the title was left while the
     * same value set on the MPV side was remembered. Both go through
     * [PlayerTrackBridge] now - it clamps, applies the change to the cue handler
     * and remembers it for the show - and the pending delayed render is
     * cancelled so the line on screen re-times at once.
     */
    private fun nudgeSubtitleOffset(stepMs: Int) {
        PlayerTrackBridge.chooseSubtitleOffset(this, subtitleOffsetMs + stepMs)
        subtitleCueHandler?.reapplyOffset()
    }

    /**
     * Auto-skip: the only place the playhead moves without a press. Called from
     * the [activeIntroStamp] setter every time the poller offers a segment.
     *
     * The poller makes the skip button visible right after that assignment, so
     * the hide is posted: a posted runnable runs once the poller's current pass
     * has finished, which means it wins and the button never flashes.
     */
    private fun onIntroStampOffered(stamp: IntroDbStamp) {
        val settings = AutoSkipRules.Settings(autoSkipIntros, autoSkipCredits)
        if (!AutoSkipRules.shouldAutoSkip(stamp, settings)) return
        runCatching {
            if (autoSkippedSegments.add(AutoSkipRules.key(stamp))) {
                val target = AutoSkipRules.targetMs(
                    stamp,
                    introDbStamps,
                    exoPlayer?.duration ?: 0L
                )
                exoPlayer?.seekTo(target)
                Log.i(
                    "INTRO_DB",
                    "auto-skipped ${stamp.type.name} ${stamp.startMs}..${stamp.endMs} -> $target"
                )
            }
        }.onFailure { Log.w("INTRO_DB", "auto-skip failed", it) }
        handler.post {
            btnSkipIntro.visibility = View.GONE
            if (btnSkipIntro.isFocused) playerView.requestFocus()
        }
    }

    /**
     * Vertical placement of the cue renderer (0 = low, 1 = mid, 2 = high).
     * SubtitleCueHandler rewrites the cue view's text, background and padding
     * on every cue but never its translation, so one pass is enough.
     */
    private fun applySubtitlePosition() {
        val lift = when (subtitlePosition) {
            1 -> -56
            2 -> -112
            else -> 0
        }
        subtitleText.translationY = lift * resources.displayMetrics.density
    }

    private fun bindViews() {
        playerView = findViewById(R.id.player_view)
        subtitleText = findViewById(R.id.custom_subtitle_text)
        p5VideoGlesView = findViewById(R.id.p5_video_gles_view)
        liveBadge = findViewById(R.id.live_badge)
        zapBanner = findViewById(R.id.zap_banner)
        zapLogo = findViewById(R.id.zap_logo)
        zapChannelLabel = findViewById(R.id.zap_channel_label)
        zapChannelName = findViewById(R.id.zap_channel_name)
        zapNowTitle = findViewById(R.id.zap_now_title)
        zapNowMeta = findViewById(R.id.zap_now_meta)
        zapNowProgress = findViewById(R.id.zap_now_progress)
        zapNowDesc = findViewById(R.id.zap_now_desc)
        zapNextTitle = findViewById(R.id.zap_next_title)
        liveProgramBlock = findViewById(R.id.live_program_block)
        liveProgramStatus = findViewById(R.id.live_program_status)
        liveProgramTitle = findViewById(R.id.live_program_title)
        liveProgramProgress = findViewById(R.id.live_program_progress)
        liveProgramDesc = findViewById(R.id.live_program_desc)
        liveProgramNext = findViewById(R.id.live_program_next)
        btnChannelUp = findViewById(R.id.btn_channel_up)
        btnChannelDown = findViewById(R.id.btn_channel_down)
        channelNumberHud = findViewById(R.id.channel_number_hud)
        bufferingSpinner = findViewById(R.id.buffering_spinner)
        reconnectingContainer = findViewById(R.id.reconnecting_container)
        reconnectingText = findViewById(R.id.reconnecting_text)
        errorContainer = findViewById(R.id.error_container)
        errorTitle = findViewById(R.id.error_title)
        errorMessage = findViewById(R.id.error_message)
        btnRetry = findViewById(R.id.btn_retry)
        btnChangeSource = findViewById(R.id.btn_change_source)
        btnSwitchPlayer = findViewById(R.id.btn_switch_player)
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
        badgeRow = findViewById(R.id.badge_row)
        overviewText = findViewById(R.id.overview_text)
        seekbarRow = findViewById(R.id.seekbar_row)
        seekbar = findViewById(R.id.seekbar)
        currentTime = findViewById(R.id.current_time)
        totalTime = findViewById(R.id.total_time)
        btnPlayPause = findViewById(R.id.btn_play_pause)
        btnNext = findViewById(R.id.btn_next)
        btnSource = findViewById(R.id.btn_source)
        btnPlayerSwitch = findViewById(R.id.btn_player_switch)
        btnAudio = findViewById(R.id.btn_audio)
        btnSubtitle = findViewById(R.id.btn_subtitle)
        btnSpeed = findViewById(R.id.btn_speed)
        btnAspect = findViewById(R.id.btn_aspect)
        btnInfo = findViewById(R.id.btn_info)
        infoPanel = findViewById(R.id.info_panel)
        infoAddonIcon = findViewById(R.id.info_addon_icon)
        infoTitle = findViewById(R.id.info_title)
        infoSource = findViewById(R.id.info_source)
        infoEngine = findViewById(R.id.info_engine)
        infoFile = findViewById(R.id.info_file)
        infoVideo = findViewById(R.id.info_video)
        infoAudio = findViewById(R.id.info_audio)
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
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    // Consume directional presses that would escape the panel.
                    // Edge rows (e.g. Offset −) have no in-panel neighbor in
                    // some direction; without this clamp the event falls
                    // through to the root focus search, lands on the video
                    // surface, and the controls overlay steals focus.
                    val direction = when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
                        KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
                        KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
                        else -> View.FOCUS_DOWN
                    }
                    val src = settingsContainer.findFocus() ?: settingsContainer
                    val next = src.focusSearch(direction)
                    next == null || !isDescendantOf(next, settingsContainer)
                }
                else -> false
            }
        }
        // Track the last focused view inside the settings panel so a stolen
        // focus can be restored to WHERE the user was, not to the top.
        var settingsLastFocus: android.view.View? = null
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { oldFocus, newFocus ->
            if (showSettingsPanel) {
                if (newFocus != null && isDescendantOf(newFocus, settingsContainer)) {
                    settingsLastFocus = newFocus
                } else if (newFocus != null) {
                    // Focus escaped the panel — re-assert synchronously (no
                    // post()) so we win the race against any pending
                    // requestFocus() from showControls()/auto-hide, and
                    // restore the user's actual position inside the panel.
                    (settingsLastFocus ?: settingsBufferAuto).requestFocus()
                }
            }
        }

        scrim = findViewById(R.id.scrim)
        settingsBufferAuto = findViewById(R.id.btn_buffer_auto)
        settingsBufferBalanced = findViewById(R.id.btn_buffer_balanced)
        settingsBufferLow = findViewById(R.id.btn_buffer_low)
        btnTunneling = findViewById(R.id.btn_tunneling)
        btnAutoplay = findViewById(R.id.btn_autoplay)
        btnAspectFit = findViewById(R.id.btn_aspect_fit)
        btnAspectZoom = findViewById(R.id.btn_aspect_zoom)
        btnAspectFill = findViewById(R.id.btn_aspect_fill)
        btnAspect169 = findViewById(R.id.btn_aspect_169)
        btnAspect43 = findViewById(R.id.btn_aspect_43)
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
        btnSubBgText = findViewById(R.id.btn_sub_bg_text)
        btnOffsetMinus = findViewById(R.id.btn_offset_minus)
        subtitleOffsetValue = findViewById(R.id.subtitle_offset_value)
        btnOffsetPlus = findViewById(R.id.btn_offset_plus)
        castSection = findViewById(R.id.cast_section)
        castRow = findViewById(R.id.cast_row)
        becauseYouWatchedPanel = findViewById(R.id.because_you_watched_panel)
        bywTitle = findViewById(R.id.byw_title)
        bywRow = findViewById(R.id.byw_row)
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
        // Picker rows are inflated on demand, long after the chrome above was
        // themed, so each one is retinted as it attaches (a recycled row keeps
        // whatever background it was themed with).
        pickerList.addOnChildAttachStateChangeListener(
            object : androidx.recyclerview.widget.RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    if (AppPreferences.getAmoledBlack(this@NativePlayerActivity)) {
                        refillPlayerChrome(view)
                    }
                }

                override fun onChildViewDetachedFromWindow(view: View) = Unit
            }
        )
        pickerList.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                dismissPicker(); showControls(); true
            } else false
        }

        // Static UI
        liveBadge.visibility = if (isLiveChannel) View.VISIBLE else View.GONE
        btnSource.visibility = View.VISIBLE
        // SWITCH is offered only where a press can actually land: this device
        // has libmpv at all, and the backup takes this kind of session (it
        // refuses live TV and DRM outright). Shown anywhere else it would be a
        // button whose only outcome is "nothing happened".
        btnPlayerSwitch.visibility =
            if (PlayerEngine.isMpvAvailable() && !isLiveChannel && drmLicenseUrl == null) {
                View.VISIBLE
            } else {
                View.GONE
            }
        renderSourceBadges()

        // Populate header info
        updateHeaderInfo()
        updateSettingsPanelState()
        // Match the end-of-episode popups to the AMOLED / pure-black toggles.
        applyPlayerPanelTheme()
        // ... and the rest of the chrome: control-bar buttons, RETRY, the
        // option pills and the panels behind them.
        applyPlayerChromeTheme()
    }

    private fun setupListeners() {
        // Play/Pause
        btnPlayPause.setOnClickListener { togglePlayPause() }

        // Live channel change from the overlay (the D-pad / CH+ keys zap too,
        // but only while the overlay is hidden — see liveZapKeysFree).
        btnChannelUp?.setOnClickListener { zapByOffset(+1) }
        btnChannelDown?.setOnClickListener { zapByOffset(-1) }
        listOfNotNull(btnChannelUp, btnChannelDown).forEach { button ->
            button.setOnFocusChangeListener { _, focused ->
                if (focused) removeAutoHide() else scheduleAutoHide()
            }
        }

        // Skip intro
        btnSkipIntro.setOnFocusChangeListener { v, focused ->
            if (focused) removeAutoHide() else scheduleAutoHide()
            // Visible focus ring: without this the focused and unfocused
            // buttons look identical and a D-pad user can't tell when
            // pressing OK will actually trigger the skip.
            v.setBackgroundResource(
                if (focused) R.drawable.button_accent_bg_focused
                else R.drawable.button_accent_bg
            )
            v.scaleX = if (focused) 1.06f else 1f
            v.scaleY = if (focused) 1.06f else 1f
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

        // Switch player, from the error card: the same press the control bar's
        // SWITCH makes (see switchPlayerManually), offered where the failure card
        // is the only thing on screen.
        btnSwitchPlayer.setOnClickListener { switchPlayerManually() }
        btnSwitchPlayer.setOnFocusChangeListener { _, focused ->
            if (focused) removeAutoHide() else scheduleAutoHide()
        }

        // Overlay control buttons
        btnNext.setOnClickListener { advanceToNextEpisode() }
        btnSource.setOnClickListener { showPicker(PickerMode.SOURCE) }
        btnPlayerSwitch.setOnClickListener { switchPlayerManually() }
        btnPlayerSwitch.setOnFocusChangeListener { _, focused ->
            if (focused) removeAutoHide() else scheduleAutoHide()
        }

        // "Up next" popup buttons
        btnNextPlay.setOnClickListener {
            // Manual confirmation: user is awake - restart the watchdog.
            AppPreferences.resetConsecutiveAutoplays(this)
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
            resizeModeIndex = (resizeModeIndex + 1) % ASPECT_MODES.size
            applyAspectMode(resizeModeIndex)
            btnAspect.text = ASPECT_MODES[resizeModeIndex]
            AppPreferences.setDefaultAspectRatio(this, resizeModeIndex)
            scheduleAutoHide()
        }
        btnAspect.setOnFocusChangeListener { _, focused -> if (focused) removeAutoHide() else scheduleAutoHide() }
        btnInfo.setOnClickListener { toggleInfoPanel() }
        btnInfo.setOnFocusChangeListener { _, focused -> if (focused) removeAutoHide() else scheduleAutoHide() }
        infoPanel.setOnKeyListener { _, keyCode, event ->
            // OK/Back close the panel; D-pad is swallowed so focus can't
            // escape to the video surface while it's up.
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> { hideInfoPanel(); showControls(); true }
                else -> true
            }
        }
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
                // Up from the seekbar lands on the FIRST cast member.
                // Without this, the default focus search picks whichever
                // actor the proximity algorithm guesses, which feels
                // random. Only claims the key while cast items exist.
                KeyEvent.KEYCODE_DPAD_UP -> {
                    val firstCast = (0 until castRow.childCount)
                        .map { castRow.getChildAt(it) }
                        .firstOrNull {
                            it.isFocusable && it.visibility == View.VISIBLE
                        }
                    if (firstCast == null) {
                        false
                    } else {
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            firstCast.requestFocus()
                        }
                        true
                    }
                }
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
        // The binge-group and still-there switches used to live here too. They
        // belong to Settings → Playback and only there: the in-player panel has
        // no business carrying a second copy of a setting that is not about the
        // title on screen. What stays here is what IS about this title -
        // languages, tracks, A/V sync, the audio knobs, the aspect ratio.

        // Aspect ratio: direct pill selection (also keeps the top-bar
        // button label in sync via updateControlsInfo).
        val aspectClick = View.OnClickListener { v ->
            resizeModeIndex = when (v.id) {
                R.id.btn_aspect_zoom -> 1
                R.id.btn_aspect_fill -> 2
                R.id.btn_aspect_169 -> ASPECT_MODE_FORCE_16_9
                R.id.btn_aspect_43 -> ASPECT_MODE_FORCE_4_3
                else -> 0
            }
            applyAspectMode(resizeModeIndex)
            updateControlsInfo()
            updateSettingsPanelState()
            AppPreferences.setDefaultAspectRatio(this, resizeModeIndex)
        }
        btnAspectFit.setOnClickListener(aspectClick)
        btnAspectZoom.setOnClickListener(aspectClick)
        btnAspectFill.setOnClickListener(aspectClick)
        btnAspect169.setOnClickListener(aspectClick)
        btnAspect43.setOnClickListener(aspectClick)

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
        btnSubBgText.setOnClickListener {
            subtitleBackground = 3; AppPreferences.setDefaultSubtitleBackground(this, 3)
            updateSubtitleSettings()
            applySubtitleStyle()
        }

        // Subtitle offset: through the bridge, so an offset dialled in here is
        // remembered for this show - the same path the MPV engine's panel and
        // the global default write.
        btnOffsetMinus.setOnClickListener { nudgeSubtitleOffset(-SUBTITLE_OFFSET_STEP_MS) }
        btnOffsetPlus.setOnClickListener { nudgeSubtitleOffset(SUBTITLE_OFFSET_STEP_MS) }
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
            val f = findPlayerViewSurfaceField()
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

            val surfaceField = findPlayerViewSurfaceField()
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

    /**
     * Locates PlayerView's private "surfaceView" field, walking up the class
     * hierarchy so it still resolves when playerView is a PlayerView
     * subclass (KBPlayerView declares no fields of its own).
     */
    private fun findPlayerViewSurfaceField(): java.lang.reflect.Field {
        var clazz: Class<*>? = playerView.javaClass
        while (clazz != null) {
            try {
                val f = clazz.getDeclaredField("surfaceView")
                f.isAccessible = true
                return f
            } catch (_: NoSuchFieldException) {
                clazz = clazz.superclass
            }
        }
        throw NoSuchFieldException("surfaceView")
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
            // Never yank focus to the overlay while a panel is up — that is
            // one of the ways the settings panel loses focus.
            if (hasFocus && controlsVisible && !showSettingsPanel && !isPickerShowing) {
                controlsOverlay.requestFocus()
            }
        }
        playerView.isFocusableInTouchMode = true
        playerView.requestFocus()
        playerView.setOnKeyListener { _, keyCode, event ->
            // Scrub-release must get through: when the overlay is hidden,
            // LEFT/RIGHT end their hold on ACTION_UP (see the branch below).
            val scrubRelease = !controlsVisible &&
                (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) &&
                event.action != KeyEvent.ACTION_DOWN
            if (event.action != KeyEvent.ACTION_DOWN && !scrubRelease) return@setOnKeyListener false

            // Channel-number entry: digits typed with the overlay hidden tune
            // straight to a channel, and OK confirms a partly typed number
            // instead of opening the overlay.
            if (channelNumberEntryAllowed()) {
                val digit = com.kennyb1201.kbstream.data.iptv.ChannelNumberEntry.digitFor(keyCode)
                if (digit != null) {
                    // One press is one digit; a held key would fill all four.
                    if (event.repeatCount == 0) appendChannelNumberDigit(digit)
                    return@setOnKeyListener true
                }
                if (channelNumberEntry.isNotEmpty() && isConfirmKey(keyCode)) {
                    commitChannelNumberEntry()
                    return@setOnKeyListener true
                }
            }

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
                    } else if (btnSkipIntro.visibility == View.VISIBLE && !controlsVisible) {
                        // A skip prompt (intro/recap/outro) is up and the
                        // overlay is hidden: OK activates the skip directly
                        // instead of popping the controls overlay over it.
                        btnSkipIntro.performClick()
                        true
                    } else {
                        if (!controlsVisible) showControls() else hideControls()
                        true
                    }
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    // Live TV behaves like a set-top box: UP/DOWN change
                    // channels and the info bar reports where you landed.
                    // OK still opens the overlay on a live channel, and with
                    // no lineup to zap through this falls back to the old
                    // "UP/DOWN reveals the controls" behavior.
                    val liveZap = liveZapKeysFree()
                    when {
                        // Held keys are ignored: every zap tears down and
                        // restarts playback, so a repeat storm would sprint
                        // through the lineup without ever settling.
                        liveZap ->
                            if (event.repeatCount == 0) {
                                zapByOffset(
                                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP) +1 else -1
                                )
                            }

                        errorContainer.visibility == View.VISIBLE ->
                            focusErrorButtons()

                        else -> {
                            showControls()
                            // A visible skip prompt beats the overlay: send
                            // the first D-pad press straight to the button so
                            // it can actually be reached and confirmed with OK.
                            if (btnSkipIntro.visibility == View.VISIBLE) {
                                btnSkipIntro.requestFocus()
                            } else {
                                controlsOverlay.requestFocus()
                            }
                        }
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (errorContainer.visibility == View.VISIBLE) {
                        focusErrorButtons()
                        true
                    } else if (controlsVisible) {
                        // The overlay is up, so it owns LEFT/RIGHT: these move
                        // focus inside it (the skip prompt is its first stop
                        // when the prompt is up, and a visible seek bar is the
                        // overlay's own scrub target). Seeking straight off the
                        // surface belongs to the overlay-down case below.
                        showControls()
                        if (btnSkipIntro.visibility == View.VISIBLE) {
                            btnSkipIntro.requestFocus()
                        } else {
                            controlsOverlay.requestFocus()
                        }
                        true
                    } else {
                        // Overlay down: LEFT/RIGHT seek the video directly and
                        // raise nothing — quick press = 10s jump, hold =
                        // accelerated scrubbing, bubble = where you landed.
                        // The activity's key dispatch runs this first, so the
                        // press behaves the same whichever view holds focus;
                        // this stays as the surface's own fallback. It is also
                        // where the press is declined while the credits
                        // recommendations are up, so that rule lives in one
                        // place - see handleSurfaceScrubKey.
                        handleSurfaceScrubKey(keyCode, event)
                    }
                }
                KeyEvent.KEYCODE_BACK -> {
                    when {
                        isPickerShowing || showSettingsPanel -> { dismissAllPanels(); true }
                        infoPanel.visibility == View.VISIBLE -> { hideInfoPanel(); showControls(); true }
                        else -> false
                    }
                }
                else -> false
            }
    }

}

    /**
     * Activity-level fallback for media transport keys. External remote apps
     * (BT remote buttons, phone remote apps) deliver play/pause/next as
     * KeyEvent media codes; the PlayerView key listener only sees them when
     * the video surface holds focus, so handle them here too. Not consumed
     * when a panel is up so Back-driven dismiss flows stay intact.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isPickerShowing || showSettingsPanel || infoPanel.visibility == View.VISIBLE) {
            return super.onKeyDown(keyCode, event)
        }
        // Channel-number entry, handled here too: external remotes can deliver
        // digits while something other than the video surface holds focus.
        if (channelNumberEntryAllowed()) {
            val digit = com.kennyb1201.kbstream.data.iptv.ChannelNumberEntry.digitFor(keyCode)
            if (digit != null) {
                if ((event?.repeatCount ?: 0) == 0) appendChannelNumberDigit(digit)
                return true
            }
            if (channelNumberEntry.isNotEmpty() && isConfirmKey(keyCode)) {
                commitChannelNumberEntry()
                return true
            }
        }
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> { exoPlayer?.play(); hideControls(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { exoPlayer?.pause(); showControls(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlayPause(); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { advanceToNextEpisode(); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                val p = exoPlayer ?: return true
                if (p.currentPosition > 5000) p.seekTo(0) else restartEpisode()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { exoPlayer?.seekForward(); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { exoPlayer?.seekBack(); return true }
            KeyEvent.KEYCODE_MEDIA_STOP -> { finish(); return true }
            // CH+/CH- channel zapping — live channels only. Banner shows
            // channel identity + NOW (title, air time, synopsis) and NEXT so
            // you can see where you landed. D-pad UP/DOWN zap too (handled
            // in the player view's key listener), since most TV remotes have
            // no dedicated channel keys.
            KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (isLiveChannel) { zapByOffset(+1); return true }
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (isLiveChannel) { zapByOffset(-1); return true }
            }
            // D-pad fallback. The video surface's own listener zaps when it
            // holds focus, so reaching here means focus sits somewhere else
            // (a button that hid with the overlay, the root view) — which used
            // to make changing channel from the player do nothing at all.
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (liveZapKeysFree()) {
                    // Held keys are ignored: every zap rebuilds the player, so
                    // a repeat storm would sprint through the lineup.
                    if ((event?.repeatCount ?: 0) == 0) {
                        zapByOffset(if (keyCode == KeyEvent.KEYCODE_DPAD_UP) +1 else -1)
                    }
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Shared "jump to the next episode" path for the overlay button and media NEXT. */
    private fun advanceToNextEpisode() {
        scope?.launch {
            // Random mode picks its own target (see resolveRandomChainTarget),
            // so the arithmetic next episode only applies outside it.
            val rawTarget = resolveRandomChainTarget(
                this@NativePlayerActivity, randomEpisodes, nextEpisodeTarget(),
                parentId, parentType, season, episode
            ) ?: return@launch
            // Same air-date gate as the end-of-playback panel: pressing Next
            // on an episode that has not aired yet must not resolve a stream
            // that does not exist.
            val target = airedNextEpisodeTarget(
                this@NativePlayerActivity, rawTarget, resolveParentTmdbId(), parentId
            )
            if (target == null) {
                // The next episode exists but is not out: say so rather than
                // leaving the Next button looking broken.
                Toast.makeText(
                    this@NativePlayerActivity,
                    "Next episode hasn't aired yet",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            // Manual skip: user is actively watching - restart the watchdog.
            AppPreferences.resetConsecutiveAutoplays(this@NativePlayerActivity)
            launchNextEpisode(
                target.first,
                target.second,
                pendingNextEpisodeName,
                pendingNextEpisodeRuntime
            )
        }
    }

    /** Media PREVIOUS at the very start of an episode restarts it instead of seeking to 0. */
    private fun restartEpisode() {
        val s = season ?: return
        val e = episode ?: return
        if (e <= 1) return
        launchNextEpisode(s, e - 1)
    }

    /** Opens the player when a remote app taps the now-playing card. */
    private fun pendingIntentForSession(): android.app.PendingIntent {
        val intent = Intent(this, NativePlayerActivity::class.java).apply {
            putExtras(this@NativePlayerActivity.intent)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= 23)
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        else android.app.PendingIntent.FLAG_UPDATE_CURRENT
        return android.app.PendingIntent.getActivity(this, 1001, intent, flags)
    }

    // --- Overlay-less surface scrubbing (LEFT/RIGHT with controls hidden) ---
    private var surfaceScrubHint: TextView? = null
    private val scrubHintHandler = Handler(Looper.getMainLooper())
    private val scrubHintHider = Runnable { surfaceScrubHint?.visibility = View.GONE }

    private fun ensureScrubHint(): TextView {
        surfaceScrubHint?.let { return it }
        val density = resources.displayMetrics.density
        val tv = TextView(this).apply {
            textSize = 15f
            setTextColor(android.graphics.Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xCC14181E.toInt())
                cornerRadius = 24f * density
            }
            setPadding((18f * density).toInt(), (8f * density).toInt(), (18f * density).toInt(), (8f * density).toInt())
            visibility = View.GONE
            elevation = 12f * density
        }
        val content = findViewById<ViewGroup>(android.R.id.content)
        content.addView(
            tv,
            android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = (56f * density).toInt() }
        )
        surfaceScrubHint = tv
        return tv
    }

    /** Shows the "+10s / position" bubble while surface scrubbing is active. */
    private fun showScrubHint() {
        if (scrubDirection == 0) return
        val tv = ensureScrubHint()
        val player = exoPlayer
        val pos = player?.currentPosition ?: 0L
        val dur = player?.duration?.takeIf { it > 0 }
        tv.text = buildString {
            append(if (scrubDirection > 0) "\u25B6\u25B6  " else "\u25C0\u25C0  ")
            append(formatMillis(pos))
            if (dur != null) append(" / ").append(formatMillis(dur))
        }
        tv.visibility = View.VISIBLE
        scrubHintHandler.removeCallbacks(scrubHintHider)
    }

    private fun scheduleScrubHintHide() {
        scrubHintHandler.removeCallbacks(scrubHintHider)
        scrubHintHandler.postDelayed(scrubHintHider, 700L)
    }

    /**
     * One LEFT/RIGHT press against the video surface, overlay down: a quick
     * press jumps 10 seconds, holding past the threshold switches to
     * accelerated scrubbing, and the bubble reports the new position. Returns
     * true when the press was consumed, which is the caller's cue to swallow
     * it - so LEFT/RIGHT never reach the overlay while the overlay is down.
     *
     * The one case not consumed is an item that cannot seek (live TV, or a
     * stream that reports no duration); those presses keep whatever meaning
     * they had.
     */
    private fun handleSurfaceScrubKey(keyCode: Int, event: KeyEvent): Boolean {
        // The credits "Because you watched" panel owns LEFT/RIGHT for as long as
        // it is up: its picks take focus precisely so the user can step between
        // them, and every direct scrub - the activity's key dispatch and the
        // video surface's own listener - arrives here, so the rule lives at this
        // one choke point instead of at each call site. Only the press is
        // declined; a release still lands so a scrub that was already in flight
        // when the panel appeared is cleaned up.
        if (event.action == KeyEvent.ACTION_DOWN && creditsPanelForeground()) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            val hasDuration = exoPlayer?.duration?.takeIf { it > 0 } != null
            if (!hasDuration) return false
            if (event.repeatCount == 0 && scrubDirection == 0) {
                scrubDirection = if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                stepSeekBy(10_000L * scrubDirection)
                showScrubHint()
                scrubHandler.removeCallbacks(scrubHoldStarter)
                scrubHandler.postDelayed(scrubHoldStarter, 400L)
            } else if (scrubDirection != 0) {
                showScrubHint()
            }
            return true
        }
        // ACTION_UP / ACTION_CANCEL: stop scrubbing and fade the bubble out.
        val wasScrubbing = scrubDirection != 0
        scrubDirection = 0
        scrubHandler.removeCallbacks(scrubHoldStarter)
        scrubHandler.removeCallbacks(scrubRunnable)
        if (wasScrubbing) scheduleScrubHintHide()
        return wasScrubbing
    }

    /** Full stop: cancel scrub timers and hide the hint immediately. */
    private fun stopSurfaceScrub() {
        scrubDirection = 0
        scrubHandler.removeCallbacks(scrubHoldStarter)
        scrubHandler.removeCallbacks(scrubRunnable)
        scrubHintHandler.removeCallbacks(scrubHintHider)
        surfaceScrubHint?.visibility = View.GONE
    }

    // --- Player Creation ---
    private fun createPlayer() {
        // Fresh attempt at the current URL: reset per-attempt state so the
        // black-video watchdog can re-arm and report at most once per attempt.
        videoTrackPresent = false
        firstFrameRendered = false
        startupTraceStartMs = System.currentTimeMillis()
        firstReadyAtMs = 0L
        blackVideoNoticeShown = false
        // Invalidate any black-video recovery scheduled for the previous
        // player instance. Without this, a watchdog armed on the old player
        // (e.g. the P5 re-route rebuild that fires right after READY) keeps
        // running and bounces the fresh player's surface seconds after it
        // starts — the "resetting video surface" flash on working streams.
        blackVideoWatchdogToken++
        p5ReroutePending = false
        // The surface reset is cheap and valid on every attempt (including the
        // software one); only the software retry itself is once-per-session.
        blackVideoSurfaceRetried = false
        // blackVideoWatchdogToken++ # delayed to allow native window recovery
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

        // Trailer playback from TrailerPlayerLauncher hands us googlevideo
        // signed URLs. Those 403 on open-ended/unbounded requests unless the
        // request carries the signing client's User-Agent and uses bounded
        // ranges — YoutubeChunkedDataSourceFactory implements both (with a
        // UA fallback ladder), so it wraps googlevideo streams instead of the
        // plain OkHttp source. Everything else keeps the addon stack.
        val isGooglevideoStream =
            currentUrl.contains("googlevideo.com") ||
                currentAudioUrl?.contains("googlevideo.com") == true
        val httpOrYoutubeFactory: androidx.media3.datasource.DataSource.Factory =
            if (isGooglevideoStream) {
                Log.i(
                    "PLAYER_DV",
                    "googlevideo stream detected; using YouTube chunked source"
                )
                com.kennyb1201.kbstream.data.youtube.YoutubeChunkedDataSourceFactory(
                    userAgentHint = agent.takeUnless { it.startsWith("Mozilla") }
                )
            } else {
                httpFactory
            }

        val extraHeaders = streamHeaders
            .filterKeys { !it.equals("User-Agent", ignoreCase = true) }
            .filterValues { it.isNotBlank() }
        if (extraHeaders.isNotEmpty()) httpFactory.setDefaultRequestProperties(extraHeaders)
        // Non-UA headers (e.g. Referer for addon hosts) must also reach
        // googlevideo streams served through the chunked YouTube source.
        if (extraHeaders.isNotEmpty()) {
            com.kennyb1201.kbstream.data.youtube.YoutubeChunkedDataSourceFactory
                .defaultRequestProperties = extraHeaders
        }

        // Dolby Vision handling (Settings → Playback): the "P7 → 8.1" mode
        // (Auto) rewrites declared/sniffed dual-layer Profile 7 (Blu-ray
        // remuxes) to Profile 8.1 on the fly so Dolby-Vision displays that
        // reject P7 can play them — every other profile passes through
        // untouched as real DV. "Strip All" rewrites every profile (4/5/7/8)
        // for displays without Dolby Vision (P5 has no HDR10 base, so it is
        // force-decoded as plain HEVC — best effort, colors may be off). The
        // P5 → 8.1 toggle adds P5 (ICtCp) to the 8.1 rewrite for Dolby Vision
        // displays that accept 8.1 but not ICtCp P5. P4/P8 are never
        // 8.1-converted and follow the mode (stripped in Strip All, otherwise
        // native DV). "None" plays DV exactly as provided. HDR10+
        // (ST 2094-40) stripping is an independent toggle that composes with
        // any DV mode; with DV = None it strips HDR10+ only and never touches
        // DV.
        // Session-level DV override: the black-video watchdog's stage 2.5
        // sets this when a DV stream produced no frames on this device — the
        // rebuilt player then behaves as if the user had selected "Strip
        // All", without changing the saved preference.
        val dvCompatMode = if (forceDvStripForSession) {
            AppPreferences.DV_COMPAT_ALL
        } else {
            AppPreferences.getDvCompatMode(this)
        }
        val stripHdr10Plus = AppPreferences.getStripHdr10Plus(this)
        val audioDecoderPriority = AppPreferences.getAudioDecoder(this)
        // True when the platform advertises a Dolby Vision decoder. On such
        // devices single-layer DV (P4/P8) normally passes through as native
        // Dolby Vision — the compat extractor does not strip it to hvc1
        // (mutating it is what makes MTK-class HEVC decoders stall with zero
        // output frames, and the platform downconverts DV for non-DV sinks).
        // Exception: "Strip All" mode overrides this and always strips P4/P8
        // to HDR10, because a DV-capable box (Fire TV Stick) does not imply a
        // DV-capable display — the user picks Strip All precisely for TVs that
        // black-screen on the DV passthrough.
        // Learned capability of THIS box. A device can advertise
        // video/dolby-vision and still hard-fail the DV decoder on the first
        // frame (TCL/Realtek: OMX_ErrorInsufficientResources, 0x80001000), so
        // the player records that failure and stops choosing passthrough for
        // this box: without it, every DV title pays the failed attempt, the
        // error banner and a full player rebuild before landing on the same
        // strip it could have used from the start. Only the auto mode consults
        // it — "None" is the user explicitly asking for pass-through — and the
        // record expires / is cleared when the DV mode changes, so Dolby
        // Vision can come back on its own.
        val deviceNativeDvSupported = DolbyVisionCompat.supportsNativeDolbyVision()
        val dvPassthroughFailedAt = AppPreferences.getDvPassthroughFailedAt(this)
        val nativeDvSuppressed =
            dvCompatMode == AppPreferences.DV_COMPAT_AUTO &&
                dvPassthroughSuppressed(
                    failedAtMillis = dvPassthroughFailedAt,
                    nowMillis = System.currentTimeMillis()
                )
        val nativeDvSupported = deviceNativeDvSupported && !nativeDvSuppressed
        dvPassthroughActive = nativeDvSupported
        if (nativeDvSuppressed) {
            Log.i(
                "PLAYER_DV",
                "Native DV passthrough suppressed — this device's DV decoder failed at " +
                    "$dvPassthroughFailedAt (deviceNativeDv=$deviceNativeDvSupported)"
            )
        }
        val dvRewriteEnabled = dvCompatMode != AppPreferences.DV_COMPAT_OFF
        val convertAllProfiles = dvCompatMode == AppPreferences.DV_COMPAT_ALL
        // Per-profile 8.1 conversion: the "P7 → 8.1" mode (Auto) always
        // rewrites declared P7 streams to Profile 8.1 in the bitstream (RPU
        // metadata per dovi_tool convert mode 2, EL dropped, single-layer VPS,
        // dvhe/dvh1.08). Profile 5 (single-layer ICtCp, no HDR10 base) is a
        // separate case: it is never relabeled, only stripped and color
        // corrected, and only while a P5 conversion is active ("P5 → HDR10",
        // or automatically on a device with no Dolby Vision decoder). Both
        // are ignored in "Strip All" (every profile 4/5/7/8 → HDR10/HEVC for
        // TVs without Dolby Vision) and "None" (pure pass-through) — see
        // DolbyVisionCompatExtractorsFactory. The P5 GLES color path below
        // therefore runs exactly while a P5 conversion does; it has no
        // switch of its own any more.
        val convertP7To81 = dvCompatMode == AppPreferences.DV_COMPAT_AUTO
        val convertP5To81 = dvCompatMode == AppPreferences.DV_COMPAT_AUTO &&
            AppPreferences.getConvertP5To81(this)
        val convertTo81 = convertP7To81 || convertP5To81
        dvTo81Session = convertTo81 // badge: "DV P7 → 8.1"
        val p5Content = currentCodecs?.let { DolbyVisionCompat.isP5Profile(it) } ?: false
        // P5 (single-layer ICtCp) content needs color conversion for correct
        // colors. The raw-plane GLES path is the only path that actually
        // works: the hardware Surface path applies an automatic dataspace
        // conversion before the shader could ever sample real ICtCp values
        // (green tint / washed out), and the bundled FFmpeg video renderer is
        // a no-op (its build ships audio decoders only). P5PlaneVideoRenderer
        // instead decodes in MediaCodec buffer mode — raw planes, no
        // conversion — and the GL shader does the ICtCp math on the GPU.
        // Reset each attempt so the setting only applies to the current stream.
        // Effective state of the P5 GLES path:
        // - Strip All rewrites P5's RPU away and ships raw ICtCp pixels to
        //   the display — the GLES path is REQUIRED there, so it is the one
        //   mode that turns it on automatically.
        // - Every other mode runs it exactly while a P5 conversion does
        //   (AppPreferences.getConvertP5To81): that conversion is what strips
        //   the ICtCp track this shader converts. Nothing turns it on
        //   silently.
        //
        // forceTextureViewFallback is deliberately NOT consulted: it only
        // decides playerView's own surface type, and this path never renders
        // through playerView (its GLSurfaceView takes the player's surface
        // view slot instead). Gating on it meant a source that had taken the
        // black-video watchdog's TextureView fallback — which is immediately
        // followed by that watchdog forcing a DV strip — played stripped P5
        // with raw ICtCp planes and no correction: green and purple.
        val useP5GlesView = p5Content &&
            p5GlesPathWanted()
        if (useP5GlesView && !p5GlesActive) {
            Log.i(
                "PLAYER_DV",
                "P5 content detected — activating GLSurfaceView raw-plane color correction"
            )
            playerView.visibility = View.GONE
            p5VideoGlesView.visibility = View.VISIBLE
            p5GlesActive = true
        } else if (!useP5GlesView && p5GlesActive) {
            p5VideoGlesView.onFirstFrameRendered = null
            p5VideoGlesView.release()
            p5VideoGlesView.visibility = View.GONE
            playerView.visibility = View.VISIBLE
            p5GlesActive = false
        } else if (p5Content) {
            // P5 present with no conversion active: the stream plays through
            // the device's own Dolby Vision decoder (passthrough). Logged for a
            // device test — a green/purple picture in this branch means the
            // platform DV pipeline mangled the ICtCp frames.
            Log.i(
                "PLAYER_DV",
                "P5 content present — playing natively as Dolby Vision " +
                    "(no conversion active: mode=$dvCompatMode nativeDv=$nativeDvSupported)"
            )
        }
        // Audio extension mode follows the independent audio decoder priority
        // (KB-style): 0 = device only (no FFmpeg at all), 1 = FFmpeg
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
        Log.i(
            "PLAYER_DV",
            "DV settings mode=$dvCompatMode rewriteEnabled=$dvRewriteEnabled " +
                "allProfiles=$convertAllProfiles to81=$convertTo81 " +
                "(p7=$convertP7To81 p5=$convertP5To81) stripHdr10Plus=$stripHdr10Plus " +
                "nativeDv=$nativeDvSupported deviceNativeDv=$deviceNativeDvSupported " +
                "audioDecoder=$audioDecoderPriority " +
                "audioSeparate=${!currentAudioUrl.isNullOrBlank()}"
        )
        // The compat extractor is needed when DV conversion is on OR the
        // HDR10+ strip toggle is on — both run inside it.
        val extractorsFactory: androidx.media3.extractor.ExtractorsFactory =
            if (!dvRewriteEnabled && !convertTo81 && !stripHdr10Plus) {
                DefaultExtractorsFactory()
            } else {
                DolbyVisionCompatExtractorsFactory(
                    DefaultExtractorsFactory(),
                    stripHdr10Plus = stripHdr10Plus,
                    convertAllProfiles = convertAllProfiles,
                    dvRewriteEnabled = dvRewriteEnabled,
                    convertP7To81 = convertP7To81,
                    convertP5To81 = convertP5To81,
                    nativeDvSupported = nativeDvSupported
                )
            }
        val mediaSourceFactory = DefaultMediaSourceFactory(httpOrYoutubeFactory, extractorsFactory)
        // DefaultMediaSourceFactory selects HLS/DASH by URI or MIME type and
        // otherwise falls back to progressive extraction. Build that fallback
        // explicitly so direct stream endpoints (which commonly have no file
        // extension) cannot skip the custom DV extractor.
        val progressiveMediaSourceFactory =
            ProgressiveMediaSource.Factory(httpOrYoutubeFactory, extractorsFactory)
        Log.i(
            "PLAYER_DV",
            "Compat extractor configured=${extractorsFactory.javaClass.simpleName} " +
                "progressiveSource=${progressiveMediaSourceFactory.javaClass.simpleName}"
        )

        val mimeType =
            if (retryAttempt < RAW_EXTRACTOR_PROBE_ATTEMPT) resolveMimeType(currentUrl) else null
        val mediaItemBuilder = MediaItem.Builder().setUri(currentUrl)
        if (mimeType != null) mediaItemBuilder.setMimeType(mimeType)

        // Surface now-playing info to system/external media controllers
        // (Android TV launcher, phone remote apps, BT headset displays).
        val mdBuilder = MediaMetadata.Builder()
        val episodeLabel = if (season != null && episode != null) {
            buildString {
                append("S").append(season).append("E").append(episode)
                if (!episodeTitle.isNullOrBlank()) append(" \u2022 ").append(episodeTitle)
            }
        } else null
        mdBuilder.setTitle(if (!episodeLabel.isNullOrBlank()) episodeLabel else itemName)
            .setArtist(itemName.takeIf { episodeLabel != null })
            .setAlbumTitle(itemName.takeIf { it.isNotBlank() })
        itemPoster?.let { mdBuilder.setArtworkUri(Uri.parse(it)) }
        overview?.let { if (it.isNotBlank()) mdBuilder.setDescription(it) }
        mediaItemBuilder.setMediaMetadata(mdBuilder.build())

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

        // Media3 buffers sample data as JAVA-HEAP byte[] blocks (DefaultAllocator
        // uses a plain `newarray byte`, never native/direct buffers), so a
        // duration-only target is a memory bomb on high-bitrate video: 30 s of a
        // 4K Dolby Vision release at ~40 Mbps is over 150 MB of LIVE heap, more
        // than the whole Java heap of the TV boxes this runs on (192 MB growth
        // limit on the TCL/Realtek class of device). With "prioritize time over
        // size" the player kept buffering the full 30 s regardless of bytes, the
        // heap pinned at its cap, GC ran flat out (0% free, ~430 ms per
        // collection, every allocation blocking) and playback died with
        // OutOfMemoryError. That OOM then surfaced as a fatal "Cannot create an
        // instance of class <ViewModel>" the moment Back recomposed Home.
        //
        // So cap the buffered BYTES to a slice of the heap this process actually
        // has — maxMemory() honours android:largeHeap, so the budget follows the
        // device — and stop prioritizing time over size, which is exactly what
        // made targetBufferBytes ineffective. Low-bitrate streams never reach
        // the cap (IPTV's 10 s, ordinary HD), so only 4K/high-bitrate changes.
        // If the budget were ever too small for a stream, media3 logs "Target
        // buffer size reached with less than 500ms of buffered media data" and
        // keeps playing; it does not throw.
        val maxBufferBudgetBytes =
            (Runtime.getRuntime().maxMemory() / 8)
                .coerceIn(24L * 1024 * 1024, 96L * 1024 * 1024)
                .toInt()
        Log.i(
            "PLAYER_PERF",
            "Buffer budget: ${maxBufferBudgetBytes / (1024 * 1024)}MB of " +
                "${Runtime.getRuntime().maxMemory() / (1024 * 1024)}MB heap, " +
                "maxBuffer=${bufferDurations[1]}ms"
        )

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(bufferDurations[0], bufferDurations[1], bufferDurations[2], bufferDurations[3])
            .setTargetBufferBytes(maxBufferBudgetBytes)
            .setPrioritizeTimeOverSizeThresholds(false).build()

        // Video and audio are independent. Software video only runs when the
        // video decoder says FFmpeg AND its guards permit it; otherwise video
        // extension mode is ON ("Prefer device": FFmpeg fallback behind
        // hardware) or OFF (an FFmpeg session bypassed for live/DV-rewrite).
        // Gate the raw-plane renderer for THIS session before the player (and
        // its renderers) are built.
        P5PlaneVideoRenderer.enabled = p5GlesActive
        val renderersFactory = SplitModeRenderersFactory(this, audioExtMode)
        Log.i(
            "PLAYER_DV",
            "Renderer policy audioDecoder=$audioDecoderPriority (video always hardware)"
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
                    val audioSource = ProgressiveMediaSource.Factory(httpOrYoutubeFactory)
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
                // Returning from the actor screen lands back on the player
                // with the saved position: resume PAUSED with the controls
                // overlay up instead of auto-playing the video.
                playWhenReady = !fromActorReturn
                // Tunneled playback is skipped on live channels: HLS live
                // manifests (discontinuities, rolling window) are the classic
                // tunnel black-video-with-audio case on Fire TV/Android TV.
                // It is also skipped when the FFmpeg audio decoder is
                // preferred (audioDecoder = Prefer app): tunneled audio needs
                // a MediaCodec decoder inside the hardware tunnel, so a
                // software-decoded PCM track gets created with FLAG_HW_AV_SYNC
                // and AudioFlinger refuses it (createTrack error -38,
                // "Cannot create AudioTrack"). Same rule KB uses.
                //
                // It is also skipped whenever the audio tuning is active: a
                // tunneled track bypasses the audio sink's processors entirely,
                // so the downmix / dialogue enhancer / volume boost would be
                // silently ignored and the user would hear the untouched mix
                // after having explicitly asked for it. Tuning wins.
                val audioTuningActive = !PlayerAudioTuning.isNeutral
                if (enableTunneling && !isLiveChannel && !audioTuningActive &&
                    audioExtMode != DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                ) {
                    trackSelectionParameters = androidx.media3.exoplayer.trackselection.DefaultTrackSelector
                        .Parameters.Builder(this@NativePlayerActivity)
                        .setTunnelingEnabled(true).build()
                    Log.i("PLAYER_TUNNEL", "Tunneled via TrackSelector")
                } else if (enableTunneling && audioTuningActive) {
                    Log.i(
                        "PLAYER_TUNNEL",
                        "Tunneling skipped: audio tuning active (downmix=" +
                            PlayerAudioTuning.downmixTarget +
                            " dialogue=" + PlayerAudioTuning.dialogueBoost +
                            " volume=+" + PlayerAudioTuning.volumeBoostDb + "dB)"
                    )
                }
                playWhenReady = !fromActorReturn
            }

            player.addListener(createPlayerListener())
            player.addAnalyticsListener(createAnalyticsListener())
            // Subtitles render through SubtitleCueHandler (below) so the
            // size / background / offset controls actually work; empty and
            // hide Media3's built-in SubtitleView, which cannot be styled.
            playerView.subtitleView?.setCues(null)
            playerView.subtitleView?.visibility = View.GONE
            subtitleCueHandler = SubtitleCueHandler().also { player.addListener(it) }
            armStartupWatchdog()

            playbackEndedHandled = false
            lastPolledPos = -1L
            posStallTicks = 0
            // Auto-selection is "once per PLAYER", not once per activity: the
            // track overrides live on the player instance, so a rebuilt one
            // (sidecar subtitle attached, engine or audio mode changed, DV
            // rewrite retry) starts from the file's own defaults. Leaving the
            // latch set skipped the preferred-language pass on that new
            // instance, and a file whose subtitle track carries no DEFAULT flag
            // then selected nothing at all — the remembered language stayed
            // highlighted in the panel while no cue ever arrived.
            // switchToSource() resets the same latch on its own path.
            languagesAutoSelected = false

            exoPlayer = player
            if (p5GlesActive) {
                // The GL view draws decoder buffers directly — Media3's
                // surface-based onRenderedFirstFrame never fires on this
                // path, which is exactly why the black-video watchdog used
                // to "recover" streams that were playing perfectly. The
                // view reports its first real content frame instead.
                p5VideoGlesView.clearFirstFrame()
                p5VideoGlesView.onFirstFrameRendered = {
                    runOnUiThread { markFirstFrameRendered() }
                }
                // P5 raw-plane path: the view implements
                // VideoDecoderOutputBufferRenderer, so setVideoSurfaceView
                // routes the view itself (not a Surface) to the video
                // renderer - it delivers raw YUV planes to the view and
                // the shader does the ICtCp conversion. No decoder Surface,
                // hence no automatic dataspace conversion in the way.
                player.setVideoSurfaceView(p5VideoGlesView)
                playerView.post { player.prepare() }
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
                applyAspectMode(resizeModeIndex)
                playerView.post { player.prepare() }
            }

            mediaSession?.release()
            // media3 keys sessions by their session ID in a process-wide static map. The real fix
            // for "Session ID must be unique" is giving every session a unique id, so a freshly
            // launched player can coexist with a previous one whose session hasn't been released
            // yet (the old synchronous-release approach was racy and still crashed on this path).
            // Advertise next/previous through a ForwardingPlayer so external
            // remote apps (and the system) enable their transport buttons;
            // next jumps to the next episode, previous restarts/rewinds.
            // `base` is captured explicitly so the overrides never depend on
            // how ForwardingPlayer exposes its wrapped player.
            val base = player
            val sessionPlayer = object : ForwardingPlayer(base) {
                override fun getAvailableCommands(): Player.Commands =
                    super.getAvailableCommands().buildUpon()
                        .add(Player.COMMAND_SEEK_TO_NEXT)
                        .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                        .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                        .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                        .build()

                override fun isCommandAvailable(command: Int): Boolean =
                    when (command) {
                        Player.COMMAND_SEEK_TO_NEXT,
                        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_PREVIOUS,
                        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> true
                        else -> super.isCommandAvailable(command)
                    }

                override fun seekToNext() { advanceToNextEpisode() }
                override fun seekToNextMediaItem() { advanceToNextEpisode() }
                override fun seekToPrevious() {
                    if (base.currentPosition > 5000) base.seekTo(0) else restartEpisode()
                }
                override fun seekToPreviousMediaItem() { seekToPrevious() }
            }
            mediaSession =
                MediaSession.Builder(this, sessionPlayer)
                    .setId("kbstream-" + System.nanoTime() + "-" + sessionSequence++)
                    .setSessionActivity(pendingIntentForSession())
                    .build()

        // Start position polling
        startPositionPolling()
        // Start intro stamp polling
        startIntroStampPolling()
    }

    /**
     * Rebuilds the player for the current source. [settleMs] is the
     * source-switch path: detach the shared output Surface from the outgoing
     * player, release it, and only then build the next one, leaving the vendor
     * decoder [settleMs] to hand its 4K buffers back (see
     * [SOURCE_SWITCH_SETTLE_MS]). Every other caller keeps the immediate
     * rebuild by passing nothing.
     */
    private fun recreatePlayer(settleMs: Long = 0L) {
        // Disarm any outstanding stall/black-video timers tied to the old
        // player instance; fresh ones are armed when the new session is ready.
        stallWatchdogToken++
        subtitleCueHandler?.cancelPending()
        subtitleCueHandler = null
        val settling = settleMs > 0L && exoPlayer != null
        if (settling) {
            // Detach first: otherwise the dying codec is still holding the
            // SurfaceView's Surface when the next codec configures onto it.
            playerView.player = null
        }
        exoPlayer?.release()
        exoPlayer = null
        if (settling) {
            Log.i(
                "PLAYER_REBUILD",
                "Source switch: waiting ${settleMs}ms for the previous decoder to " +
                    "release its buffers before rebuilding the player"
            )
            handler.postDelayed(
                { if (!isFinishing && !isDestroyed) createPlayer() },
                settleMs
            )
        } else {
            createPlayer()
        }
    }

    private fun createPlayerListener() = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                // Splash dismissal and the hasPlayedOnce latch live in
                // markFirstFrameRendered(): audio can start before the
                // decoder paints frame 1, so this callback fires too early
                // and left a black screen + yellow spinner in that gap.
                // Actual playback resumed: the actor-return pause session
                // is over, so later buffering rebuffers use normal paths.
                fromActorReturn = false
                armStallWatchdog()
                if (controlsVisible && !showSettingsPanel && !isPickerShowing) {
                    scheduleAutoHide()
                }
                scrobbleSimkl("start")
                // Nudge a tunneled stream that renders nothing on its own. The
                // elvis used to sit outside the comparison, which made the
                // right-hand side dead code and hid what is actually tested.
                if (enableTunneling && !firstFrameRendered &&
                    (exoPlayer?.currentPosition ?: 0L) < 500L
                ) {
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
                    // First READY of this attempt: the point that splits
                    // "source loaded" from "decoder painted".
                    if (firstReadyAtMs == 0L) firstReadyAtMs = System.currentTimeMillis()
                    if (rebufferStartedAtMs != 0L) {
                        val stalledMs = System.currentTimeMillis() - rebufferStartedAtMs
                        Log.w("PLAYER_PERF", "Rebuffer stall: ${stalledMs}ms")
                        rebufferStartedAtMs = 0L
                    }
                    updateUIReady()
                    retryAttempt = 0
                    retryExhausted = false
                    errorMessageStr = null
                    autoSelectPreferredLanguages()
                    subtitleCueHandler?.updateFromPosition()
                    armBlackVideoWatchdog()
                    armStallWatchdog()
                    // Actor-return session: the surface is prepared and
                    // paused (frame at the resume position on screen) —
                    // bring up the controls overlay exactly as if the user
                    // had just paused, instead of silently auto-playing.
                    if (fromActorReturn && !actorReturnOverlayShown) {
                        actorReturnOverlayShown = true
                        exoPlayer?.playWhenReady = false
                        showControls()
                    }
                }
                Player.STATE_ENDED -> {
                    onPlaybackEnded()
                }
            }
        }

        /**
         * A seek (and every other discontinuity) moves the playhead in one
         * jump and FLUSHES the read-ahead buffer, which leaves both of the
         * progress trackers looking at baselines that describe the position
         * the session just left — so a seek used to read as a dead source:
         *
         *  - [tickStallWatchdog] only counts a tick as progress when the
         *    position OR the buffered position has grown by 500ms. A backward
         *    seek makes both of those numbers smaller, and the buffer the seek
         *    just dropped was the one it measured, so the tick before that
         *    buffer refills sees "no progress". Its quiet-window stamp was also
         *    older than the seek, so the FIRST tick after a seek could already
         *    be past the 12s threshold and fire a recovery seek into the middle
         *    of a perfectly normal post-seek rebuffer. That recovery seek
         *    flushed the buffer again, the second one burned out the retry
         *    budget, and the session landed on "The stream stopped sending
         *    data" for a minute or more — the reported "seeking sometimes
         *    makes the playback fail".
         *  - [detectStallEndedFallback] reads "the position did not advance"
         *    as a frozen tail, which a backward seek also looks like for a
         *    tick.
         *
         * Both are re-baselined here. A seek is a new place to start reading
         * from — not evidence that the stream died — so it gets a fresh quiet
         * window, and the explicit-seek reasons also clear the recovery budget
         * those earlier false stalls spent.
         */
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            val positionMs = newPosition.positionMs
                .takeIf { it != C.TIME_UNSET }
                ?: (exoPlayer?.currentPosition ?: 0L)
            stallLastProgressAtMs = System.currentTimeMillis()
            stallLastPositionMs = positionMs
            stallLastBufferedMs = exoPlayer?.bufferedPosition ?: positionMs
            if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
            ) {
                stallRecoveries = 0
            }
            // Unknown, not the pre-seek position: the next poller tick must
            // not count the jump itself as a frozen position.
            lastPolledPos = -1L
            posStallTicks = 0
        }

        override fun onRenderedFirstFrame() {
            markFirstFrameRendered()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            // The user's forced 16:9 / 4:3 choice must survive the stream
            // announcing its own (possibly misflagged) ratio.
            applyForcedAspect()
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
                            p5GlesPathWanted() &&
                            !p5GlesActive &&
                            !firstFrameRendered && !p5ReroutePending
                        ) {
                            p5ReroutePending = true
                            Log.i(
                                "PLAYER_DV",
                                "P5 content detected after player start — rebuilding " +
                                    "player to activate color correction"
                            )
                            handler.post {
                                if (!p5GlesActive && !firstFrameRendered) {
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
                    }
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            var msg = friendlyErrorMessage(error)
            // Resource exhaustion is a different animal from "this box can't
            // decode Dolby Vision", and both the recovery and the persisted
            // verdict below depend on telling them apart.
            val resourceExhausted = isDecoderResourceExhausted(error)
            val decoderFailure = isDecoderError(error.errorCode)
            val declaredDvCodec = streamDeclaredDvCodec ?: streamCodec
            // A DV passthrough session whose DV decoder refuses the first frame
            // reports the platform's own out-of-resources code (see
            // [dvPassthroughDecoderRefused]) — tell that apart from the box
            // genuinely running out of decoders before choosing a recovery, or
            // a DV-capable TV is sent to the next-source hunt instead of the
            // HDR10 strip that plays.
            val dvDecoderRefused = dvPassthroughDecoderRefused(
                isDecoderFailure = decoderFailure,
                dvPassthroughActive = dvPassthroughActive,
                declaredDvCodec = declaredDvCodec,
                alreadyStripped = forceDvStripForSession
            )
            if (decoderFailure) {
                val codec = streamCodec
                if (!codec.isNullOrBlank()) {
                    val dims = if (streamWidth > 0) " ${streamWidth}x$streamHeight" else ""
                    msg += "\nThis file's video ($codec$dims) can't be decoded on this TV."
                }
            }
            // Dolby Vision decoder hard failure. Some DV-capable boxes (TCL /
            // Realtek: OMX.realtek.video.dvhe.st.decoder) advertise
            // video/dolby-vision, so Media3 reports format_supported=YES, and
            // then the DV decoder errors out the moment the first frame is
            // submitted with OMX_ErrorInsufficientResources (0x80001000).
            // Falling through to scheduleRetry() rebuilt an identical player,
            // so all six retries failed the same way - and the black-video
            // watchdog could never help, because this is a hard ERROR rather
            // than a silent no-output. Take the watchdog's stage-2.5 recovery
            // directly: one rebuild with Dolby Vision forced to "Strip All"
            // for this session, which hands the stream to the ordinary HEVC
            // decoder as plain HDR10. An explicit "None" (pure pass-through)
            // is the user's own choice and is left alone.
            if (!forceDvStripForSession &&
                (!resourceExhausted || dvDecoderRefused) &&
                decoderFailure &&
                dvLabelFromCodec(declaredDvCodec) != null &&
                AppPreferences.getDvCompatMode(this@NativePlayerActivity) !=
                AppPreferences.DV_COMPAT_OFF
            ) {
                dvStripRetryDone = true
                forceDvStripForSession = true
                // Remember it for this device so the next DV title starts
                // stripped instead of paying this failure and rebuild again —
                // but only when the decoder genuinely cannot play Dolby
                // Vision. Resource exhaustion is the box being out of
                // decoders: it hits the second 4K decode in a process, and the
                // plain HEVC decoder too, and one field session shows a DV
                // decode succeeding ~25s later with no setting changed.
                // Recording that as a capability verdict stripped Dolby Vision
                // off every title for the next 14 days on a TV that had just
                // played it — which is the "DV is struggling" state.
                //
                // Reaching this branch with [resourceExhausted] set is only
                // possible through [dvDecoderRefused], which has already
                // established that the failure was the vendor DV decoder
                // refusing a DV passthrough session (not the box running dry),
                // so the verdict is safe to keep there too.
                AppPreferences.setDvPassthroughFailedAt(
                    this@NativePlayerActivity,
                    System.currentTimeMillis()
                )
                errorMessageStr = null
                Log.w(
                    "PLAYER_DV",
                    "Dolby Vision decoder failure (${streamDeclaredDvCodec ?: streamCodec}) — " +
                        "retrying once with Dolby Vision stripped to HDR10"
                )
                reconnectingContainer.visibility = View.VISIBLE
                bufferingSpinner.visibility = View.GONE
                reconnectingText.text = "This TV can't play Dolby Vision here — switching to HDR10…"
                // Not the usual 500ms: this box's DV decoder does not stop
                // inside ACodec's window — the log shows `forcing the release
                // of codec` landing ~3s after release, so a rebuild at 500ms
                // asked for a new 4K decoder while the old component was still
                // holding its resources and got OMX_ErrorInsufficientResources
                // back. Both attempts that did play in that session started
                // after several seconds of quiet.
                handler.postDelayed(
                    {
                        errorMessageStr = null
                        recreatePlayer()
                    },
                    DV_STRIP_REBUILD_DELAY_MS
                )
                return
            }
            // Resource exhaustion: the box has no video decoder to hand us
            // right now. Retrying this file cannot fix that — the DV-strip
            // path above asks for the same component, and the raw-extractor
            // probes below do too. The field log shows all of them coming back
            // OMX_ErrorInsufficientResources (0x80001000), four rebuilds and
            // ~30s of black screen ending exactly where the first attempt did.
            // Do the one thing that can help instead: the next ranked source,
            // which is normally the smaller one this box can still decode.
            if (resourceExhausted && !decoderResourceFallbackDone) {
                decoderResourceFallbackDone = true
                // A 0x80001000 is the box being out of decoders, not a verdict on
                // Dolby Vision: the field log has the vendor DV decoder AND the
                // plain HEVC decoder returning it for the same 4K source. A
                // resource-starved session must not leave a learned DV failure
                // behind to suppress Dolby Vision for 14 days on a TV that plays it.
                if (dvLabelFromCodec(streamDeclaredDvCodec ?: streamCodec) != null) {
                    AppPreferences.clearDvPassthroughFailure(this@NativePlayerActivity)
                }
                errorMessageStr = null
                val switching = tryNextSource(
                    delayMs = DECODER_RESOURCE_RETRY_DELAY_MS,
                    statusText = "This TV is out of video decoder resources — " +
                        "trying a smaller source…"
                )
                if (switching) return
                Log.w(
                    "PLAYER_RETRY",
                    "Decoder resources exhausted with no source left to try — failing fast " +
                        "instead of the six-attempt rebuild ladder, which cannot get a decoder back"
                )
                // Nothing left to try on this box: every remaining source
                // needs a decoder it will not hand out, and mpv's decoder
                // path can fall back to software instead. Take that.
                if (handOffToMpv(MpvPlayerActivity.FALLBACK_REASON_DECODER)) return
                retryExhausted = true
                errorMessageStr =
                    "This TV has run out of video decoder resources.\n" +
                        "Restart the app, or pick a 1080p source for this title."
                updateUIError()
                return
            }
            // A decoder error is not an IO error. Rebuilding here produced an
            // identical decoder on an identical device, and the field log shows
            // the cost: after the vendor DV decoder failed, each retry started a
            // new OMX component ~1s later while the previous one was still
            // tearing down (`forcing the release of codec`, ~3s on this box), and
            // every attempt came back OMX_ErrorInsufficientResources (0x80001000).
            // The ladder's later attempts do different work — they drop the MIME
            // hint so the extractor sniffs the container itself — so skip straight
            // to those, which also gives the vendor codec time to finish releasing
            // before it is asked for a component again.
            if (isDecoderError(error.errorCode) && retryAttempt < RAW_EXTRACTOR_PROBE_ATTEMPT) {
                retryAttempt = RAW_EXTRACTOR_PROBE_ATTEMPT
            }
            errorMessageStr = msg
            if (isLikelyRetryable(error)) {
                scheduleRetry()
            } else if (!handOffToMpv(MpvPlayerActivity.FALLBACK_REASON_ERROR)) {
                // Reached only when the backup engine is switched off,
                // unavailable on this device, or this launch cannot be
                // handed over (live TV, DRM): the stream really is
                // unplayable here.
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
                Log.w("PLAYER_PERF", "Dropped $droppedFrames frames over ${elapsedMs}ms")
                maybeLogHeap("dropped", force = true)
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
        // The splash is the only load indicator while it is up — never stack
        // the spinner or the reconnecting banner on top of it.
        bufferingSpinner.visibility = View.GONE
        reconnectingContainer.visibility = View.GONE
        // If clear logo is already loaded, start pulse immediately.
        // Otherwise, start it once Coil finishes loading.
        if (splashClearLogo.drawable != null) {
            startPulseAnimation()
        } else if (!splashPulseWaitAttached) {
            splashPulseWaitAttached = true
            splashClearLogo.viewTreeObserver.addOnGlobalLayoutListener(
                object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        if (splashClearLogo.drawable != null) {
                            splashClearLogo.viewTreeObserver.removeOnGlobalLayoutListener(this)
                            splashPulseWaitAttached = false
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
        // Full splash overlay (backdrop + pulsing clearlogo) for every fresh
        // source load — first launch, auto-select, manual pick, in-player
        // source switch, auto-advance (each source switch resets the
        // hasPlayedOnce latch). Only mid-playback rebuffers (hasPlayedOnce
        // still true) and actor-return sessions (fromActorReturn) get the
        // small spinner, so the video is never covered once the user is
        // already watching it.
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
        // STATE_READY only means "buffered enough to start" - decoder init
        // and the first paint still come after. Hold the splash until the
        // first frame renders (markFirstFrameRendered dismisses it) so that
        // gap is splash-covered instead of black. Audio-only streams have
        // no video frame to wait for and dismiss immediately.
        if (firstFrameRendered || !videoTrackPresent) {
            hideSplash()
        }
        updateControlsInfo()
    }

    private fun updateUIError() {
        bufferingSpinner.visibility = View.GONE
        reconnectingContainer.visibility = View.GONE
        errorContainer.visibility = View.VISIBLE
        errorTitle.text = if (isLiveChannel) "Channel unavailable" else "Playback failed"
        errorMessage.text = errorMessageStr.orEmpty()
        btnChangeSource.visibility = View.VISIBLE
        offerErrorSwitch()
        focusErrorButtons()
    }

    /**
     * True when the error card's SWITCH PLAYER button would actually land.
     *
     * The conditions the handoff itself checks, and the ones behind the control
     * bar's own SWITCH (see handOffToMpv): a device with libmpv at all, a session
     * the backup engine accepts (it refuses live TV and DRM outright), a stream
     * to hand over, and no handoff already in flight. Anywhere else the button
     * would be one whose only outcome is "nothing happened", so the card hides it
     * rather than leaving it dead.
     */
    private fun canHandOffToMpv(): Boolean =
        PlayerEngine.isMpvAvailable() &&
            !isLiveChannel &&
            drmLicenseUrl == null &&
            currentUrl.isNotBlank() &&
            !mpvHandoffStarted

    /**
     * Offers SWITCH PLAYER on the error card, where a press can land.
     *
     * Every path that raises the card calls this: the startup and black-video
     * watchdogs never asked for a handoff at all, which is the gap this button
     * fills, and the playback-failure path can be reached with the automatic
     * fallback switched off in Settings.
     */
    private fun offerErrorSwitch() {
        btnSwitchPlayer.visibility = if (canHandOffToMpv()) View.VISIBLE else View.GONE
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

    private fun isDecoderError(errorCode: Int): Boolean =
        errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
            errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
            // A box whose DV decoder refuses the profile can report this one.
            // Missing it here sends a declared-DV stream down the plain retry
            // ladder, which rebuilds the identical decoder — the loop this set
            // exists to prevent. Widening is safe: the DV-strip branch still
            // requires a DV codec AND a DV mode other than None.
            errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED

    /**
     * True when the decoder failed because the box could not give it
     * resources, rather than because the bitstream was bad. The distinction
     * matters: a bad bitstream is worth retrying differently, while
     * OMX_ErrorInsufficientResources means no decoder on this process will
     * succeed, so the only useful move is a different (smaller) source.
     */
    private fun isDecoderResourceExhausted(error: Throwable?): Boolean {
        var cause = error
        while (cause != null) {
            if (cause is android.media.MediaCodec.CodecException &&
                cause.errorCode == OMX_ERROR_INSUFFICIENT_RESOURCES
            ) {
                Log.w(
                    "PLAYER_RETRY",
                    "Decoder resource exhaustion (0x80001000, recoverable=" +
                        "${cause.isRecoverable} transient=${cause.isTransient})"
                )
                return true
            }
            // Media3 wraps the platform error and minified builds can bury the
            // concrete type, so the platform's own code in the message chain
            // is the reliable fallback.
            if (cause.message?.contains("0x80001000", ignoreCase = true) == true) {
                return true
            }
            cause = cause.cause
        }
        return false
    }

    /**
     * Single source of truth for "video output works". Called from Media3's
     * onRenderedFirstFrame (surface paths) and from P5VideoGlesView's first
     * real content draw (raw-plane path). The black-video recovery ladder
     * checks this at every stage: frame rendered → stop.
     */
    private fun markFirstFrameRendered() {
        if (firstFrameRendered) return
        firstFrameRendered = true
        firstFrameRenderedAtMs = System.currentTimeMillis()
        // One actionable startup line. The right-hand gap is the decoder/GPU,
        // the left-hand one is the network + extractor (the DV strip rewrites
        // every sample on the way through) + buffer fill.
        if (startupTraceStartMs > 0L && firstReadyAtMs > 0L) {
            Log.w(
                "PLAYER_PERF",
                "Startup source→ready=${firstReadyAtMs - startupTraceStartMs}ms " +
                    "ready→firstFrame=${firstFrameRenderedAtMs - firstReadyAtMs}ms " +
                    "total=${firstFrameRenderedAtMs - startupTraceStartMs}ms " +
                    "codec=${streamCodec ?: "?"} ${streamWidth}x$streamHeight " +
                    "bitrate=${streamBitrate} mime=${streamMimeType ?: "?"}"
            )
        }
        reconnectingContainer.visibility = View.GONE
        bufferingSpinner.visibility = View.GONE
        // Live: announce the channel the moment its first frame is up, the
        // way a set-top box does — channel identity, what is on now (with its
        // air window and synopsis) and what follows. The overlay's programme
        // block carries the same rows whenever the overlay is raised; this is
        // the arrival notice. Once per channel: a reconnect must not replay it.
        // ...unless the overlay is already up: that view carries the same
        // programme block, and a card landing on top of it would just be noise.
        if (isLiveChannel && !zapBannerInitialShown && !controlsVisible) {
            zapBannerInitialShown = true
            currentZapChannel()?.let { showZapBanner(it) }
        }
        // The first rendered frame is the moment the splash goes away: video
        // is now visibly on screen underneath it. STATE_READY and isPlaying
        // both fire BEFORE the decoder paints, so they must not dismiss the
        // splash — that is what exposed a black screen + yellow spinner in
        // the READY-to-first-frame gap.
        if (splashContainer.visibility == View.VISIBLE) {
            hideSplash()
        }
        // The "player has started" latch also belongs to the first frame:
        // setting it on isPlaying turned any post-audio buffering into the
        // small-spinner branch instead of the full splash.
        hasPlayedOnce = true
        // Dolby Vision passthrough that actually renders is proof this box can
        // do it, so forget any recorded failure. A track that still carries the
        // dvhe/dvh1 label arrived as Dolby Vision (the extractor did not strip
        // it); a stripped one arrives as plain hvc1 and correctly proves
        // nothing.
        //
        // It only counts when passthrough was ENABLED for this session. The
        // old check cleared the record for any DV-labelled track, including
        // playbacks that ran with passthrough suppressed — the one case where
        // that playback proves nothing. The field log has the loop it made:
        // "suppressed" at 16:49:38 → played fine → record cleared by that very
        // playback → the next stream attempted passthrough at 16:49:50 →
        // OMX_ErrorInsufficientResources → strip → play. Every DV title paid a
        // failed attempt and a rebuild before landing on the path that works.
        if (dvPassthroughActive && dvLabelFromCodec(streamCodec) != null) {
            AppPreferences.clearDvPassthroughFailure(this)
        }
    }

    /**
     * Effective state of the P5 raw-plane GLES color path, independent of
     * whether P5 content is currently detected:
     *  - Strip All: the one automatic engagement — stripping the RPU is
     *    what leaves ICtCp pixels for the display, so the shader is the
     *    color fix.
     *  - A P5 conversion is the only other one: the "P5 → HDR10" switch, or
     *    a device with no Dolby Vision decoder to play Profile 5. There is no
     *    separate color-path switch any more — as a standalone toggle it had
     *    nothing stripped to convert whenever P5 was left as Dolby Vision.
     */
    private fun p5GlesPathWanted(): Boolean {
        if (!P5ColorShader.hasGles3()) return false
        // The watchdog's session override forces "Strip All", which auto-
        // enables the GLES color path for P5 content — mirror that here so
        // the stripped-P5 session gets correct colors, not raw ICtCp.
        if (forceDvStripForSession) return true
        return AppPreferences.getDvCompatMode(this) == AppPreferences.DV_COMPAT_ALL ||
            AppPreferences.getP5GlesCorrection(this)
    }

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
            offerErrorSwitch()
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
        // surface-bounce -> TextureView -> notice recovery ladder as VOD.
        // Audio-only channels are already filtered by !videoTrackPresent.
        val token = ++blackVideoWatchdogToken
        handler.postDelayed({ handleBlackVideoTimeout(token) }, blackVideoWatchdogMs)
    }

    /**
     * Recovery ladder for "READY with audio but no first video frame":
     *  1. surface bounce — destroy/recreate the SurfaceView's native surface
     *     (fixes the lost-native-window case where the decoder IS producing
     *     frames but output goes nowhere),
     *  2. TextureView rebuild — renders through the view hierarchy when the
     *     SurfaceView's native window is lost (hardware decoding stays on),
     *  3. DV strip retry — one rebuild with Dolby Vision forced to "Strip
     *     All" for this session: Fire TV-class boxes advertise a DV decoder
     *     yet silently output zero frames for DV passthrough on non-DV
     *     displays (no error ever fires). Same file, same URL, presented to
     *     the decoder as plain HDR10/HEVC — what other players already do.
     *  4. explicit notice so the user is never left staring at a silent stall.
     */
    private fun handleBlackVideoTimeout(token: Int, requirePlaying: Boolean = true) {
        if (token != blackVideoWatchdogToken) return
        if (blackVideoNoticeShown) return
        // A rendered first frame means video output works — the recovery
        // ladder is pointless and destructive (it has bounce-rebuilt the
        // surface on streams that were already playing fine, e.g. DV P5
        // sessions whose GLES path reports frames late). One truth, checked
        // at every stage: frame rendered -> stop.
        if (firstFrameRendered) return
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
        // TextureView rebuild that follows it. With the TextureView fallback
        // active the SurfaceView is hidden, so bouncing it is pointless —
        // skip straight to the TextureView rebuild.
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
                    if (firstFrameRendered) return@postDelayed
                    if (surfaceView == null) {
                        // No SurfaceView to bounce (unexpected layout) — skip
                        // straight to the TextureView stage.
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
                    if (firstFrameRendered) return@postDelayed
                    errorMessageStr = null
                    recreatePlayer()
                },
                500L
            )
            return
        }

        // Stage 2: video is always hardware now — the bundled FFmpeg ships
        // audio decoders only, so there is no software video decoder left
        // Stage 2.5 (before giving up): DV-capable boxes whose DV pipeline
        // never outputs a frame on non-DV displays (Fire TV Stick class). One
        // rebuild with DV forced to "Strip All" — same URL, the stream is
        // presented as plain HDR10/HEVC, exactly what other players do. The
        // one-shot [forceDvStripForSession] override makes the DV mode read as
        // STRIP-ALL for this session without touching the user's setting.
        if (!dvStripRetryDone &&
            AppPreferences.getDvCompatMode(this) != AppPreferences.DV_COMPAT_ALL
        ) {
            dvStripRetryDone = true
            Log.w(
                "PLAYER_VIDEO",
                "Black video: no first frame after surface + TextureView retries$codecInfo — " +
                    "retrying once with Dolby Vision stripped to HDR10"
            )
            reconnectingContainer.visibility = View.VISIBLE
            bufferingSpinner.visibility = View.GONE
            reconnectingText.text = "Video isn't displaying — trying Dolby Vision compatibility mode…"
            handler.postDelayed(
                {
                    if (token != blackVideoWatchdogToken) return@postDelayed
                    if (blackVideoNoticeShown) return@postDelayed
                    if (firstFrameRendered) return@postDelayed
                    errorMessageStr = null
                    forceDvStripForSession = true
                    recreatePlayer()
                },
                500L
            )
            return
        }

        // to swap to. Surface the actionable notice.
        showBlackVideoNotice()
    }

    /**
     * One-shot session override for the black-video watchdog's DV stage:
     * when set, DV conversion behaves as "Strip All" for this playback
     * session regardless of the user's saved DV mode. Used on Fire TV-class
     * boxes that advertise a DV decoder but silently output zero frames for
     * DV passthrough on non-DV displays. Reset in [switchToSource] and on
     * activity create.
     */
    private var forceDvStripForSession = false

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
        // Belt-and-braces: a frame rendered at any point means output works.
        if (firstFrameRendered) return
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
            "Black-video notice: no first frame after surface reset + TextureView retry$codecInfo " +
                "mime=${streamMimeType ?: "?"} playing=${exoPlayer?.isPlaying} state=${exoPlayer?.playbackState}"
        )
        reconnectingContainer.visibility = View.GONE
        bufferingSpinner.visibility = View.GONE
        errorTitle.text = "Video isn't displaying"
        errorMessage.text =
            "Playback started but no video frames are rendering$codecInfo. " +
                "Both video surfaces (SurfaceView and TextureView) were tried on this TV. " +
                (if (triedOtherSources) "Other sources were also tried automatically. " else "") +
                "If Dolby Vision playback is on, try setting it to Off for this file, or choose " +
                "a different source — a 1080p H.264 release usually plays on any device."
        errorContainer.visibility = View.VISIBLE
        btnChangeSource.visibility = View.VISIBLE
        offerErrorSwitch()
        focusErrorButtons()
    }

    // --- Stall watchdog ---

    /** Last PLAYER_PERF heap line, so the probe stays a curve, not a flood. */
    private var lastHeapLogAtMs = 0L

    /**
     * Java-heap probe. The player's own buffering lives on this heap (media3's
     * DefaultAllocator hands out byte[] blocks and sizes the video target at
     * 125 MB by default), so when playback runs out of memory this line is what
     * says whether the footprint flattened out (buffering) or kept climbing
     * (something retained). GC count comes along because a climbing
     * gc-count-per-second is thrash: the playhead starves and frames drop even
     * though the stream is fine.
     */
    private fun maybeLogHeap(reason: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastHeapLogAtMs < HEAP_LOG_INTERVAL_MS) return
        lastHeapLogAtMs = now
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        val maxMb = rt.maxMemory() / (1024 * 1024)
        val gcCount = runCatching {
            android.os.Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull()
        }.getOrNull()
        Log.i(
            "PLAYER_PERF",
            "Heap ($reason): ${usedMb}MB used / ${maxMb}MB max" +
                (gcCount?.let { " gcCount=$it" } ?: "")
        )
    }

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
            maybeLogHeap("playing")
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
        // Measure the quiet window BEFORE resetting the progress stamp: the
        // log used to print it after the reset and so always read
        // "No data for 0ms", which made every stall look like a zero-second
        // hiccup and hid how long the source had actually gone silent.
        val quietMs = now - stallLastProgressAtMs
        stallRecoveries++
        stallLastProgressAtMs = now
        stallLastPositionMs = positionMs
        stallLastBufferedMs = bufferedMs
        val targetMs = if (bufferedMs > positionMs) bufferedMs + 250 else positionMs + 250
        Log.w(
            "PLAYER_STALL",
            "No data for ${quietMs}ms (pos=${positionMs}ms buf=${bufferedMs}ms) — seeking to ${targetMs}ms to force a fresh read (recovery $stallRecoveries/$stallMaxRecoveries)"
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
        renderSourceBadges()
    }

    /**
     * Resolves the active source's addon display name + logo URL by matching
     * [label] against installed addons. Torrent/metadata-only streams and
     * live channels simply leave both null (panel hides the icon row).
     */
    private fun resolveAddonIdentity(label: String?) {
        val name = label?.trim().orEmpty()
        if (name.isEmpty()) {
            currentAddonName = null
            currentAddonLogoUrl = null
            return
        }
        val addon = try {
            com.kennyb1201.kbstream.data.addon.AddonManager
                .getInstance(applicationContext)
                .getEnabledAddons()
                .firstOrNull { it.displayName.equals(name, ignoreCase = true) }
        } catch (_: Exception) {
            null
        }
        currentAddonName = addon?.displayName ?: name
        currentAddonLogoUrl = addon?.logo
    }

    /** Human label for the playback engine currently in use. */
    private fun engineLabel(): String {
        val parts = mutableListOf("Hardware video decoder")
        parts.appendAudioEngineLabel()
        if (p5GlesActive) parts.add("GLES raw-plane color corrector (P5 DV)")
        if (enableTunneling) parts.add("Tunneled")
        if (forceTextureViewFallback) parts.add("TextureView surface")
        return parts.joinToString(" • ")
    }

    private fun MutableList<String>.appendAudioEngineLabel() {
        when (AppPreferences.getAudioDecoder(applicationContext)) {
            AppPreferences.AUDIO_DECODER_DEVICE_ONLY -> add("Hardware audio")
            AppPreferences.AUDIO_DECODER_PREFER_APP -> add("FFmpeg audio preferred")
            else -> add("FFmpeg audio fallback")
        }
    }

    private fun toggleInfoPanel() {
        if (infoPanel.visibility == View.VISIBLE) {
            hideInfoPanel()
            showControls()
        } else {
            dismissPicker()
            dismissSettingsPanel()
            buildInfoPanel()
            infoPanel.visibility = View.VISIBLE
            scrim.visibility = View.VISIBLE
            removeAutoHide()
            infoPanel.requestFocus()
        }
    }

    private fun hideInfoPanel() {
        infoPanel.visibility = View.GONE
        if (!showSettingsPanel && !isPickerShowing) {
            scrim.visibility = View.GONE
        }
    }

    /** Fills every info row from the live player + stream state. */
    private fun buildInfoPanel() {
        // Source row + addon icon
        infoSource.text = "Source: ${currentAddonName ?: currentSourceLabel}"
        val logo = currentAddonLogoUrl
        if (!logo.isNullOrBlank()) {
            infoAddonIcon.visibility = View.VISIBLE
            infoAddonIcon.load(logo)
        } else {
            infoAddonIcon.visibility = View.GONE
            infoAddonIcon.setImageDrawable(null)
        }
        infoEngine.text = "Engine: ${engineLabel()}"

        // File row: filename for http(s) sources, torrent name otherwise
        val fileLabel = currentUrl?.let { url ->
            val path = url.substringBefore('?').substringAfterLast('/')
            if (path.contains('%')) java.net.URLDecoder.decode(path, "UTF-8") else path
        }?.takeIf { it.isNotBlank() && it.contains('.') }
            ?: currentSourceLabel
        infoFile.text = "File: $fileLabel"

        // VIDEO block
        infoVideo.text = buildString {
            if (streamWidth > 0 && streamHeight > 0) {
                appendLine("Resolution: ${streamWidth}×${streamHeight} (${normalizeResolution(streamWidth, streamHeight)})")
            } else {
                appendLine("Resolution: —")
            }
            val codecLabel = normalizeCodec(streamCodec, streamDeclaredDvCodec, dvTo81Session)
            appendLine("Codec: ${if (codecLabel != "—") codecLabel else streamMimeType ?: "—"}")
            if (streamBitrate > 0) appendLine("Bitrate: ${streamBitrate / 1_000} kbps")
            exoPlayer?.videoSize?.pixelWidthHeightRatio?.takeIf { it != 1f }?.let {
                appendLine("Pixel ratio: $it")
            }
            if (p5GlesActive) appendLine("Dolby Vision P5 — GL color correction active")
        }.trimEnd()

        // AUDIO block: every selected audio track's format details
        val audioLines = mutableListOf<String>()
        exoPlayer?.currentTracks?.groups?.forEach { group ->
            for (i in 0 until group.length) {
                if (group.type == C.TRACK_TYPE_AUDIO && group.isTrackSelected(i)) {
                    val f = group.getTrackFormat(i)
                    val parts = mutableListOf<String>()
                    f.language?.uppercase()?.takeIf { it.isNotBlank() }
                        ?.let { parts.add("Language: $it") }
                    // Same MIME-type fallback as the picker's labels: an audio
                    // track's Format.codecs is empty for most containers, and
                    // "Codec: —" is no answer to "what is this stream".
                    normalizeCodec(f.codecs?.ifBlank { null } ?: f.sampleMimeType)
                        .takeIf { it != "—" }?.let { parts.add("Codec: $it") }
                    if (f.channelCount > 0) parts.add("Channels: ${f.channelCount}")
                    if (f.sampleRate > 0) parts.add("Sample rate: ${f.sampleRate} Hz")
                    if (f.bitrate > 0) parts.add("Bitrate: ${f.bitrate / 1_000} kbps")
                    if (parts.isNotEmpty()) audioLines.add(parts.joinToString(" • "))
                }
            }
        }
        infoAudio.text = if (audioLines.isEmpty()) "—" else audioLines.joinToString("\n")
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
        // Honor the Settings > Interface 24-hour toggle; falls back to the
        // locale default when unchecked.
        val clockPattern = if (
            com.kennyb1201.kbstream.ui.settings.AppPreferences.getUse24HourClock(this)
        ) "HH:mm" else "h:mm a"
        val now = java.text.SimpleDateFormat(clockPattern, java.util.Locale.getDefault())
            .format(java.util.Date())
        playerClock.text = now
        val durationMs = exoPlayer?.duration ?: 0L
        val positionMs = exoPlayer?.currentPosition ?: 0L
        val remainingMs = (durationMs - positionMs).coerceAtLeast(0L)
        val endsAt = java.text.SimpleDateFormat(clockPattern, java.util.Locale.getDefault())
            .format(java.util.Date(System.currentTimeMillis() + remainingMs))
        endsAtClock.text = "Ends at $endsAt"
    }

    /** Renders the current source's badge chips. */
    private fun renderSourceBadges() {
        PickerAdapter.bindBadgeRow(badgeRow, currentBadges)
    }

    private fun updateControlsInfo() {
        renderSourceBadges()
        btnPlayPause.setImageResource(
            if (exoPlayer?.isPlaying == true) R.drawable.ic_player_pause else R.drawable.ic_player_play
        )
        btnSpeed.text = "${playbackSpeed}x"
        btnAspect.text = ASPECT_MODES.getOrElse(resizeModeIndex) { "Fit" }
        // Live channels get the channel-change buttons and the NOW/NEXT
        // programme block; VOD keeps the episode row instead.
        val liveVisibility = if (isLiveChannel) View.VISIBLE else View.GONE
        btnChannelUp?.visibility = liveVisibility
        btnChannelDown?.visibility = liveVisibility
        if (isLiveChannel) {
            refreshLiveProgramBlock()
        } else {
            liveProgramBlock?.visibility = View.GONE
        }
    }

    private fun applyPillBackground(view: TextView, selected: Boolean, focused: Boolean) {
        view.background = when {
            selected && focused ->
                ContextCompat.getDrawable(this, R.drawable.pill_chip_selected_focused_bg)
            selected -> ContextCompat.getDrawable(this, R.drawable.pill_chip_selected_bg)
            focused -> ContextCompat.getDrawable(this, R.drawable.pill_chip_focused_bg)
            else -> roundedDrawable(panelSurfaceColor(), 6f)
        }
    }

    /**
     * Single source of truth for turning an ASPECT_MODES index into the
     * base Media3 resize mode and applying it. Forced-ratio modes (16:9,
     * 4:3) use RESIZE_MODE_FIT as the base; applyResizeMode pins the
     * content frame's ratio on top.
     */
    private fun applyAspectMode(index: Int) {
        applyResizeMode(
            when (index) {
                1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        )
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

            // 3) Force-ratio modes (16:9 / 4:3): the stock resize modes
            //    always trust the stream's embedded video size, which is
            //    wrong for misflagged streams. AspectRatioFrameLayout has
            //    no public "force ratio" API, so pin its videoAspectRatio
            //    field and re-assert it on every layout pass — the frame
            //    then scales the surface to OUR ratio instead of the
            //    stream's. Modes 0-2 clear the pin so the stream ratio
            //    wins again.
            val contentFrame = findPlayerViewContentFrame()
            if (contentFrame is AspectRatioFrameLayout) {
                forceAspectOnFrame = contentFrame
                forceAspectValue = when (mode) {
                    ASPECT_MODE_FORCE_16_9 -> 16f / 9f
                    ASPECT_MODE_FORCE_4_3 -> 4f / 3f
                    else -> 0f
                }
                if (forceAspectValue <= 0f) {
                    // Unpinning: restore the frame to the stream's real
                    // ratio NOW — the field would otherwise keep the last
                    // forced value until the next video-size change. The
                    // pin itself clears so later reasserts are no-ops and
                    // the stream ratio rules again.
                    val vs = exoPlayer?.videoSize
                    try {
                        val f = AspectRatioFrameLayout::class.java
                            .getDeclaredField("videoAspectRatio")
                        f.isAccessible = true
                        f.setFloat(
                            contentFrame,
                            if (vs != null && vs.width > 0 && vs.height > 0)
                                vs.width.toFloat() / vs.height.toFloat()
                            else 0f
                        )
                    } catch (_: Exception) {}
                }
            } else {
                forceAspectOnFrame = null
                forceAspectValue = 0f
            }

            // 4) Force relayout so the new ratio/measure takes effect now.
            contentFrame?.requestLayout()
            playerView.requestLayout()
            playerView.invalidate()

            // 5) Re-assert after the next layout pass, in case a pending
            //    redraw/player transaction reset the transform.
            handler.postDelayed(
                {
                    if (isDestroyed || isFinishing) return@postDelayed
                    try {
                        applyForcedAspect()
                        playerView.requestLayout()
                        playerView.invalidate()
                    } catch (_: Exception) {}
                },
                120L
            )
        } catch (_: Exception) {}
    }

    /** Content frame currently pinned to a forced ratio (null = none). */
    private var forceAspectOnFrame: AspectRatioFrameLayout? = null

    /** Ratio to pin, or 0 to follow the stream (stock behavior). */
    private var forceAspectValue = 0f

    /**
     * Re-applies the active forced ratio. Called after layout passes and
     * video-size changes so a stream's embedded ratio can never override
     * the user's 16:9 / 4:3 choice.
     */
    private fun applyForcedAspect() {
        val frame = forceAspectOnFrame ?: return
        if (forceAspectValue <= 0f) return
        try {
            val f = AspectRatioFrameLayout::class.java.getDeclaredField("videoAspectRatio")
            f.isAccessible = true
            f.setFloat(frame, forceAspectValue)
            frame.requestLayout()
        } catch (_: Exception) {
            // Field renamed in a future Media3: forced modes silently stop
            // working, stock modes unaffected.
            forceAspectValue = 0f
        }
    }

    private fun applyPillState(view: TextView, selected: Boolean) {
        applyPillBackground(view, selected, view.isFocused)
        view.setTextColor(if (selected) getColor(R.color.kb_void) else getColor(R.color.kb_text_hi))
        view.setOnFocusChangeListener { v, _ ->
            val tv = v as TextView
            applyPillBackground(tv, selected, tv.isFocused)
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

        applyPillState(btnAspectFit, resizeModeIndex == 0)
        applyPillState(btnAspectZoom, resizeModeIndex == 1)
        applyPillState(btnAspectFill, resizeModeIndex == 2)
        applyPillState(btnAspect169, resizeModeIndex == ASPECT_MODE_FORCE_16_9)
        applyPillState(btnAspect43, resizeModeIndex == ASPECT_MODE_FORCE_4_3)

        val resizeModeLabels = ASPECT_MODES
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
        stopSurfaceScrub()

        controlsVisible = true
        controlsOverlay.visibility = View.VISIBLE
        seekbarRow.visibility = View.VISIBLE
        updateControlsInfo()
        updateClock()
        playerClock.visibility = View.VISIBLE
        endsAtClock.visibility = View.VISIBLE
        clockHandler.removeCallbacks(clockRunnable)
        clockHandler.post(clockRunnable)
        // If a panel is open, the panel owns focus — do not steal it.
        if (infoPanel.visibility == View.VISIBLE) {
            // Info panel keeps its own focus; just keep controls visible.
            scheduleAutoHide()
            return
        }
        if (!showSettingsPanel && !isPickerShowing) {
            controlsOverlay.post {
                // While a skip prompt is up it is the primary target —
                // park focus there (Netflix-style) instead of play/pause
                // so the button is always one OK press away.
                if (btnSkipIntro.visibility == View.VISIBLE) {
                    btnSkipIntro.requestFocus()
                } else if (isLiveChannel && btnChannelUp?.visibility == View.VISIBLE) {
                    // Live: changing channel is the primary action in the
                    // overlay, so open on CH up rather than play/pause
                    // (pausing live television is not a thing).
                    btnChannelUp?.requestFocus()
                } else {
                    btnPlayPause.requestFocus()
                }
            }
        }
        scheduleAutoHide()
        // Best-effort: resolve the next episode's name so the Next button's
        // handoff label (and the streams screen it opens) carries the real
        // episode title, not just S#E#.
        prefetchNextEpisodeName()
    }

    private fun hideControls() {
        // Stop any active scrubbing
        scrubDirection = 0
        scrubHandler.removeCallbacks(scrubRunnable)
        scrubHandler.removeCallbacks(scrubHoldStarter)
        scrubHintHandler.removeCallbacks(scrubHintHider)
        surfaceScrubHint?.visibility = View.GONE

        controlsVisible = false
        controlsOverlay.visibility = View.GONE
        seekbarRow.visibility = View.GONE
        dismissAllPanels()
        hideInfoPanel()
        bufferingSpinner.visibility = View.GONE
        // Same rule as updateUIReady: only drop the splash once the first
        // frame is actually up (or the stream has no video track) - audio
        // can be playing while the decoder is still painting frame 1.
        if (exoPlayer?.isPlaying == true && (firstFrameRendered || !videoTrackPresent)) {
            hideSplash()
        }

}

    private fun updateSubtitleSettings() {
        listOf(btnSubSmall to 0, btnSubNormal to 1, btnSubLarge to 2).forEach { (btn, idx) ->
            applyPillState(btn, subtitleSize == idx)
        }
        listOf(btnSubBgNone to 0, btnSubBgSemi to 1, btnSubBgSolid to 2, btnSubBgText to 3).forEach { (btn, idx) ->
            applyPillState(btn, subtitleBackground == idx)
        }
        subtitleOffsetValue.text = "${subtitleOffsetMs}ms"

        // Focus styling for offset +/- buttons
        listOf(btnOffsetMinus, btnOffsetPlus).forEach { btn ->
            btn.setOnFocusChangeListener { v, focused ->
                val tv = v as TextView
                applyPillBackground(tv, false, focused)
            }
        }
    }

    private fun applySubtitleStyle() {
        // Cues are rendered by SubtitleCueHandler; re-rendering applies the
        // new size/background to whatever is on screen now.
        subtitleCueHandler?.refreshStyle()
    }

    /**
     * Renders text cues into [subtitleText] instead of Media3's built-in
     * SubtitleView. That view cannot take the pill background (its
     * exo_subtitle child is a SubtitleView container, not a TextView, so the
     * old reflection approach always no-op'd), and Media3 has no
     * subtitle-delay API at all — so cues are intercepted here: size and
     * background come from the player/global settings, and a positive offset
     * delays each cue block's appearance. A negative offset shows cues
     * immediately (text cannot appear in the past).
     */
    private inner class SubtitleCueHandler : Player.Listener {
        private var currentCues: List<Cue> = emptyList()
        private val delayedShow = Runnable { renderText(currentCueText()) }
        private val positionTick = Runnable { updateFromPosition() }

        /**
         * True when an external subtitle file is loaded AND the offset is
         * negative: the pipeline only ever emits cues at their authored
         * time, so "show earlier" needs this handler to drive rendering
         * from the playback position instead.
         */
        private val positionDriven: Boolean
            get() = externalSubtitleCues.isNotEmpty() && subtitleOffsetMs < 0

        override fun onCues(cueGroup: CueGroup) {
            if (positionDriven) {
                // Position-driven mode: suppress pipeline rendering entirely.
                handler.removeCallbacks(delayedShow)
                currentCues = emptyList()
                renderText("")
                updateFromPosition()
                return
            }
            currentCues = cueGroup.cues
            handler.removeCallbacks(delayedShow)
            if (subtitleOffsetMs <= 0) {
                renderText(currentCueText())
            } else {
                handler.postDelayed(delayedShow, subtitleOffsetMs.toLong())
            }
        }

        /** Drops pending work, clears the text, and re-syncs position mode. */
        fun cancelPending() {
            handler.removeCallbacks(delayedShow)
            handler.removeCallbacks(positionTick)
            currentCues = emptyList()
            renderText("")
            if (positionDriven) updateFromPosition()
        }

        /**
         * Re-times the line already on screen after an offset change.
         *
         * The offset pads used to come through [cancelPending], which exists
         * for teardown: it drops the cue text it is holding. Nothing is left
         * to re-render from after that, so a nudge during a long line blanked
         * the subtitles until the pipeline's NEXT cue group arrived — the
         * "selected but not showing" report — and a nudge that crossed back
         * into pipeline mode had to wait for a cue Media3 had already emitted.
         */
        fun reapplyOffset() {
            handler.removeCallbacks(delayedShow)
            if (positionDriven) {
                updateFromPosition()
                return
            }
            renderText(currentCueText())
        }

        /** Re-renders with the new size/background (offset unchanged). */
        fun refreshStyle() {
            if (positionDriven) {
                updateFromPosition()
                return
            }
            handler.removeCallbacks(delayedShow)
            renderText(currentCueText())
        }

        /**
         * Position-driven rendering: shows the cue covering
         * (position + offset) and re-checks at every 500 ms tick, so seeks
         * and offset changes re-sync quickly while cue boundaries stay
         * visually exact.
         */
        fun updateFromPosition() {
            handler.removeCallbacks(positionTick)
            if (!positionDriven) return
            val player = exoPlayer ?: return
            val pos = player.currentPosition + subtitleOffsetMs.toLong()
            val cue = externalSubtitleCues.firstOrNull { pos >= it.startMs && pos < it.endMs }
            renderText(cue?.text.orEmpty())
            val nextBoundary = cue?.endMs
                ?: externalSubtitleCues.firstOrNull { it.startMs > pos }?.startMs
                ?: (pos + 500L)
            val delay = (nextBoundary - pos).coerceIn(1L, 500L)
            handler.postDelayed(positionTick, delay)
        }

        private fun currentCueText(): String =
            currentCues.mapNotNull { it.text }.joinToString("\n").trim()

        private fun renderText(text: String) {
            if (text.isEmpty()) {
                subtitleText.visibility = View.GONE
                subtitleText.background = null
                return
            }
            val sizes = listOf(11f, 14f, 18f)
            subtitleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizes[subtitleSize])
            // Embedded styling (ASS BackColour, styled WebVTT) can carry its
            // own background spans on the cue text (position-driven external
            // subs pass Cue.text through raw). Strip them so the box never
            // renders — the app's Background choice must be the only thing
            // that paints behind subtitles.
            val clean = withoutEmbeddedBoxes(text)
            when (subtitleBackground) {
                1 -> renderStrip(clean, 0x80000000.toInt(), padded = true)
                2 -> renderStrip(clean, 0xE5000000.toInt(), padded = true)
                3 -> renderStrip(clean, 0xB3000000.toInt(), padded = false)
                else -> {
                    subtitleText.text = clean
                    subtitleText.background = null
                    subtitleText.setPadding(0, 0, 0, 0)
                }
            }
            subtitleText.visibility = View.VISIBLE
        }

        /**
         * Removes background-painting spans (ASS BackColour, styled WebVTT)
         * from cue text so no stream can paint its own box behind subtitles.
         * Non-background styling (italics, speaker colors) is preserved.
         */
        private fun withoutEmbeddedBoxes(text: String): CharSequence {
            return runCatching {
                val spanned = android.text.SpannableString(text)
                var removed = false
                for (span in spanned.getSpans(0, spanned.length, Object::class.java)) {
                    if (span is android.text.style.BackgroundColorSpan ||
                        span is android.text.style.LineBackgroundSpan
                    ) {
                        spanned.removeSpan(span)
                        removed = true
                    }
                }
                if (removed) spanned else text
            }.getOrDefault(text)
        }

        /**
         * Glyph-hugging strip background: the color paints behind the glyphs
         * themselves (per wrapped row), never as one wide view-level box that
         * grows with the longest line and leaves ragged text edges. [padded]
         * adds a non-breaking-space cushion on each authored line's ends so
         * the strip reads as a soft rectangle; the unpadded variant is the
         * tight letters-only KB look.
         */
        private fun renderStrip(text: CharSequence, color: Int, padded: Boolean) {
            subtitleText.background = null
            subtitleText.setPadding(0, 0, 0, 0)
            if (!padded) {
                val spannable = android.text.SpannableString(text)
                spannable.setSpan(
                    android.text.style.BackgroundColorSpan(color),
                    0,
                    spannable.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                subtitleText.text = spannable
                return
            }
            val pad = "\u00A0"
            val out = android.text.SpannableStringBuilder()
            text.toString().lines().forEachIndexed { index, line ->
                if (index > 0) out.append("\n")
                if (line.isEmpty()) return@forEachIndexed
                val start = out.length
                out.append(pad).append(line).append(pad)
                out.setSpan(
                    android.text.style.BackgroundColorSpan(color),
                    start,
                    out.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            subtitleText.text = out
        }
    }

    /**
     * Reads and parses the picked external subtitle file (SRT / WebVTT)
     * into [externalSubtitleCues] off the main thread. Parsing locally is
     * what makes a true negative offset possible: Media3 has no
     * subtitle-delay API and only emits cues at their authored time, but
     * with the file in hand the handler can render any cue on demand.
     */
    private fun loadExternalSubtitleCues(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val parsed = runCatching {
                contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
            }.getOrNull()?.let { SubtitleFileParser.parse(it) } ?: emptyList()
            launch(Dispatchers.Main) {
                externalSubtitleCues = parsed
                if (!parsed.isEmpty()) {
                    Log.i(TAG, "External subtitles parsed: ${parsed.size} cues")
                }
                subtitleCueHandler?.updateFromPosition()
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
                // Persist the pause point right away so the item jumps to
                // the top of Continue Watching even if the player is torn
                // down before onStop() (process death, force close).
                saveProgress(reason = "pause")
            } else {
                // Resuming — hide overlay instantly
                hideControls()
            }
        }
    }

    private val autoHideRunnable = Runnable { hideControls() }

    private fun scheduleAutoHide() {
        handler.removeCallbacks(autoHideRunnable)
        // Don't auto-hide when paused — keep overlay visible. Live is the
        // exception: a live channel cannot be paused, and isPlaying reads
        // false for the whole buffering start, which left the overlay up for
        // the entire session. That is also what made UP/DOWN look broken on
        // live TV — they navigate the visible overlay instead of zapping, so
        // the channel never changed.
        if (!isLiveChannel && exoPlayer?.isPlaying == false) return
        // Don't auto-hide while a panel is open: hiding the overlay mid-
        // navigation tears down the panel's focus and drops the user's spot.
        if (showSettingsPanel || isPickerShowing) return
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
                        badges = stream.badges,
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
                        // Language AND format, via the same label the settings
                        // panel's track rows use: one language is often listed
                        // several times in one file (5.1 vs stereo, dub vs
                        // commentary), and the codec, channels and bitrate are
                        // what separate those rows. "Track" means the format
                        // carried neither a language nor a codec.
                        PickerItem(
                            label = audioTrackLabel(format)
                                .takeIf { it != "Track" } ?: "Track ${groupIdx + 1}",
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
                        launchExternalSubtitlePicker()
                        dismissPicker()
                    }
                )
                // Online search entry: only when a key is configured. Shows
                // results inline on re-open; a result row downloads and
                // attaches through the shared sidecar path.
                val subsKeyPresent = AppPreferences.getOpensubtitlesApiKey(this).isNotBlank()
                val searchItem = if (subsKeyPresent) PickerItem(
                    label = "SEARCH SUBTITLES ONLINE…",
                    onClick = {
                        dismissPicker()
                        startOnlineSubtitleSearch()
                    }
                ) else null
                // Resolved results from the last search render as rows above
                // the embedded tracks; picking one downloads and attaches it.
                val onlineRows = onlineSubResults.map { hit ->
                    PickerItem(
                        label = "${hit.language.uppercase()} · ${hit.fileName} · ${hit.downloads}↓",
                        onClick = {
                            dismissPicker()
                            downloadOnlineSubtitle(hit)
                        }
                    )
                }
                val subtitleItems = listOfNotNull(searchItem, openFileItem) + onlineRows
                val tracks = exoPlayer?.currentTracks ?: return
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
        becauseYouWatchedPanel.visibility = View.GONE
        exitCreditsMode()
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

        if (retryAttempt >= RAW_EXTRACTOR_PROBE_ATTEMPT) {
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
        }
        // The panel is normally already up from maybeTriggerEndPanels (it opens
        // during the credits) with its auto-advance countdown held because the
        // episode was not over yet. Playback is over now, so let it run.
        if (nextUpCountdownHeld) armNextUpAutoAdvance(remainingMs = 0L)
        showEndPanels()
    }

    /**
     * Raises the end-of-episode panel as the credits roll instead of waiting
     * for playback to fully end, at the point the user set for the panel this
     * session will raise (Settings → Playback: Next Episode Popup Point /
     * Because You Watched Point, a percentage of the runtime).
     *
     * A title that carries a credits marker (IntroDB) never uses that point:
     * the marker is a fact about the file, pulled
     * [END_PANEL_CREDITS_LEAD_MS] early so the panel is already up as the
     * credits start. A marker
     * pointing implausibly early is clamped to [END_PANEL_MIN_REMAINING_MS]
     * before the end.
     */
    private fun maybeTriggerEndPanels(pos: Long, dur: Long) {
        if (endPanelsShown || playbackEndedHandled || isLiveChannel) return
        if (dur <= 0L || dur == C.TIME_UNSET) return
        // Both panels switched off: nothing to raise, so playback runs to its
        // own end instead of this trigger cutting the last minutes off with
        // nothing to show for them.
        if (!AppPreferences.getNextEpisodePopup(this) &&
            !AppPreferences.getBecauseYouWatched(this)
        ) return
        val creditsStart = introDbStamps
            .filter {
                (it.type == IntroDbMarkerType.Credits ||
                    it.type == IntroDbMarkerType.Outro) &&
                    AutoSkipRules.isSkippableSegment(it)
            }
            .minByOrNull { it.startMs }
            ?.startMs
        val triggerAt = (if (creditsStart != null) {
            (creditsStart - END_PANEL_CREDITS_LEAD_MS)
                .coerceAtMost(dur - END_PANEL_MIN_REMAINING_MS)
        } else {
            endPanelPercentTriggerMs(dur)
        }).coerceAtLeast(0L)
        // A non-positive trigger means the clip is shorter than the point (or a
        // bad marker sits at 0): leave it to onPlaybackEnded instead of
        // popping the panel the moment playback starts.
        if (triggerAt <= 0L) return
        if (pos < triggerAt) return
        showEndPanels()
    }

    /**
     * The panel's point when the title carries no credits marker, in
     * milliseconds into the file: the user's setting (Settings → Playback,
     * Next Episode Popup Point / Because You Watched Point) as a percentage of
     * the runtime, movable in half-percent steps. The point is picked for the
     * panel this session will actually raise - the Up Next card for a series
     * episode, the credits recommendations for anything else - so setting one
     * panel's point earlier cannot drag the other's earlier too.
     *
     * A title that DOES carry a credits marker never reaches this: its marker
     * is a fact about the file and beats any percentage, which is what makes
     * the panel land as the credits start.
     */
    private fun endPanelPercentTriggerMs(dur: Long): Long {
        val isEpisode = season != null && episode != null
        val point = if (isEpisode && AppPreferences.getNextEpisodePopup(this)) {
            AppPreferences.getNextEpisodePopupPointTenths(this)
        } else {
            AppPreferences.getBecauseYouWatchedPointTenths(this)
        }
        return (dur - dur * (1_000 - point) / 1_000L).coerceAtLeast(0L)
    }

    /**
     * Decides which end-of-episode panel to raise - the Up Next popup when a
     * next episode exists, the because-you-watched credits recommendations
     * when none does - and shows it exactly once per session.
     */
    private fun showEndPanels() {
        if (endPanelsShown || isLiveChannel) return
        endPanelsShown = true
        scope?.launch {
            // Series episodes show the "Up next" popup; anything without a
            // next episode (movies, finished finales) gets the
            // because-you-watched credits recommendations instead.
            // An episode that has not aired yet counts as "no next episode":
            // offering it here meant autoplay/PREV resolved streams for an
            // episode that does not exist, instead of recommending something.
            // Random mode answers with another random aired episode - which is
            // also why the air-date gate below is a formality there.
            val target = resolveRandomChainTarget(
                this@NativePlayerActivity, randomEpisodes, nextEpisodeTarget(),
                parentId, parentType, season, episode
            )?.let {
                airedNextEpisodeTarget(
                    this@NativePlayerActivity, it, resolveParentTmdbId(), parentId
                )
            }
            when {
                target != null && AppPreferences.getNextEpisodePopup(this@NativePlayerActivity) ->
                    showNextUpPanel(target.first, target.second)

                target == null &&
                    AppPreferences.getBecauseYouWatched(this@NativePlayerActivity) ->
                    showBecauseYouWatchedPanel()

                // Otherwise that panel is switched off in Settings, or there is
                // nothing to suggest: nothing is raised, and the credits play
                // out. endPanelsShown stays set, so the file's own end does not
                // try the same decision again.
            }
        }
    }

    /**
     * The native player's XML panels carry fixed colors, so the AMOLED /
     * pure-black theme toggles never reached them. Re-resolve their background
     * here so the end-of-episode popups match the rest of the app.
     */
    private fun applyPlayerPanelTheme() {
        val surfaceColor = panelSurfaceColor()
        listOf(becauseYouWatchedPanel, nextUpPanel).forEach { panel ->
            panel.background = roundedDrawable(panelRaisedColor(), 16f)
        }
        // Everything sitting on the panel has its own fill: the next
        // episode's still, the frames the posters load into, the
        // because-you-watched featured backdrop, and the neutral pills.
        // Left alone they keep the XML's fixed @color/kb_surface, which is
        // what made the popups ignore the AMOLED / pure-black toggles.
        nextUpThumb.setBackgroundColor(surfaceColor)
        applyPillBackground(btnNextDismiss, selected = false, focused = btnNextDismiss.isFocused)
        // The credits panel re-tints its own artwork and pills through the
        // shared panel UI.
        if (::bywUi.isInitialized) bywUi.applyTheme()
    }

    /** AMOLED-aware stand-in for @color/kb_surface (card / artwork fills). */
    private fun panelSurfaceColor(): Int {
        val amoled = AppPreferences.getAmoledBlack(this)
        return when {
            amoled && AppPreferences.getPureBlackSurface(this) -> 0xFF000000.toInt()
            amoled -> 0xFF06080B.toInt()
            else -> getColor(R.color.kb_surface)
        }
    }

    /** AMOLED-aware stand-in for @color/kb_surface_raised (panel fills). */
    private fun panelRaisedColor(): Int {
        val amoled = AppPreferences.getAmoledBlack(this)
        return when {
            amoled && AppPreferences.getPureBlackSurface(this) -> 0xFF050505.toInt()
            amoled -> 0xFF0D1117.toInt()
            else -> getColor(R.color.kb_surface_raised)
        }
    }

    /** Rounded rectangle standing in for the XML shape drawables. */
    private fun roundedDrawable(color: Int, radiusDp: Float): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radiusDp * resources.displayMetrics.density
        }

    /**
     * AMOLED-aware stand-in for one XML chrome fill: the same corner radius
     * and (for the ripple drawables) the same accent press ripple, but the
     * fill follows the theme toggles.
     */
    private fun themedChromeBackground(
        fill: Int,
        radiusPx: Float,
        rippled: Boolean
    ): android.graphics.drawable.Drawable {
        val body = android.graphics.drawable.GradientDrawable().apply {
            setShape(android.graphics.drawable.GradientDrawable.RECTANGLE)
            setColor(fill)
            cornerRadius = radiusPx
        }
        if (!rippled) return body
        val mask = android.graphics.drawable.GradientDrawable().apply {
            setShape(android.graphics.drawable.GradientDrawable.RECTANGLE)
            setColor(0xFF000000.toInt())
            cornerRadius = radiusPx
        }
        return android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(getColor(R.color.kb_accent)),
            body,
            mask
        )
    }

    /** The cast card's avatar circle, at the theme's own artwork fill. */
    private fun themedAvatarBackground(fill: Int): android.graphics.drawable.Drawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(fill)
        }

    /**
     * The chrome around the video — the control-bar buttons, RETRY, the option
     * pills, the picker rows and the panels behind them — is plain XML with
     * fixed @color/kb_surface / @color/kb_surface_raised fills, so the AMOLED /
     * pure-black toggles never reached it: a pure-black theme still painted
     * #141A24 buttons. Views are matched by the fill color their own background
     * carries rather than by resource id, so every button and pill is covered
     * without a hand-kept list, and each one keeps its own corner radius and
     * press ripple. Pills already restyled by [applyPillState] are skipped
     * (their fill is no longer an XML color), as are the two end-of-episode
     * panels handled by [applyPlayerPanelTheme].
     */
    private fun applyPlayerChromeTheme() {
        // Without AMOLED the XML fills are already exactly right.
        if (!AppPreferences.getAmoledBlack(this)) return
        // getColor()/getCornerRadius() on a drawable are API 24+; older
        // devices simply keep the (dark, not pure-black) XML fills.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) return
        refillPlayerChrome(findViewById(android.R.id.content))
    }

    /**
     * [applyPlayerChromeTheme]'s walk. Also called for picker rows as they
     * attach: those are inflated on demand, long after the activity's own
     * view tree was themed.
     */
    private fun refillPlayerChrome(root: View) {
        refillPlayerChromeView(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                refillPlayerChrome(root.getChildAt(index))
            }
        }
    }

    /** Retints one view when its background is one of the XML chrome fills. */
    private fun refillPlayerChromeView(view: View) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) return
        val background = view.background ?: return

        /*
         * A RippleDrawable IS a LayerDrawable, and layer 0 is only its
         * CONTENT while it actually has one: real-world ripples exist with no
         * layer at all (or a mask only), and LayerDrawable.getDrawable(0)
         * throws IndexOutOfBoundsException on those. This pass runs from
         * onCreate's bindViews, so that exception force-closed the player on
         * launch whenever AMOLED was on - Sentry ANDROID-D, TCL Smart TV,
         * one frame after the activity started. Look the content layer up
         * instead of assuming it, and treat a ripple without one as "not a
         * chrome fill".
         */
        val ripple =
            background as? android.graphics.drawable.RippleDrawable
        val rippled = ripple != null

        val content =
            if (ripple == null) {
                background
            } else {
                if (ripple.numberOfLayers <= 0) return

                val contentIndex =
                    (0 until ripple.numberOfLayers).firstOrNull { index ->
                        runCatching {
                            ripple.getId(index) == android.R.id.content
                        }.getOrDefault(false)
                    }

                // XML ripples usually leave their single item untagged, so
                // fall back to the first layer when nothing is marked as the
                // content. A mask-only ripple then resolves to its mask,
                // whose fill matches neither chrome color below, so nothing
                // changes for it.
                runCatching {
                    ripple.getDrawable(contentIndex ?: 0)
                }.getOrNull() ?: return
            }

        val shape = content as? android.graphics.drawable.GradientDrawable ?: return
        val fill = shape.color?.defaultColor ?: return
        val replacement = when {
            fill == getColor(R.color.kb_surface) ->
                themedChromeBackground(panelSurfaceColor(), shape.cornerRadius, rippled)

            fill == getColor(R.color.kb_surface_raised) ->
                themedChromeBackground(panelRaisedColor(), shape.cornerRadius, false)

            // Not one of the chrome fills but the same problem: the cast card's
            // avatar circle is a fixed #FF1D2530 oval, so a pure-black theme
            // still drew grey circles behind every headshot - and the circle is
            // all that shows for the cast members TMDB has no photo for.
            fill == AVATAR_PLACEHOLDER_FILL -> themedAvatarBackground(panelSurfaceColor())

            else -> null
        }
        replacement?.let { view.background = it }
    }

    /**
     * The because-you-watched panel opens while the end credits are rolling:
     * shrink the video (the credits themselves) into the TOP-RIGHT corner so the
     * recommendations get the screen, and spread the panel across it.
     *
     * The video takes a notch out of the panel's own fill rather than sitting on
     * top of it - it is a SurfaceView, so a panel painted over that corner would
     * hide the credits. The header stops short of the notch (which keeps the
     * header the width it had when the panel sat to the left of a bottom-right
     * video), while the pick row below it gets the full width, so more of the
     * posters fit before the row has to scroll.
     */
    private fun enterCreditsMode() {
        if (creditsModeActive) return
        creditsModeActive = true
        val density = resources.displayMetrics.density
        val screenW = resources.displayMetrics.widthPixels
        val pipW = (screenW * 0.32f).toInt()
        val pipH = (pipW * 9 / 16)
        val margin = (24 * density).toInt()
        listOf<View>(playerView, p5VideoGlesView).forEach { v ->
            (v.layoutParams as android.widget.FrameLayout.LayoutParams).apply {
                width = pipW
                height = pipH
                gravity = android.view.Gravity.TOP or android.view.Gravity.END
                setMargins(margin, margin, margin, margin)
            }
            v.requestLayout()
        }
        // The notch is the video plus the gap the panel used to leave between
        // itself and the video: exactly the width the header gives up.
        val inset = pipW + margin
        creditsModePanelParams =
            becauseYouWatchedPanel.layoutParams as android.widget.FrameLayout.LayoutParams
        becauseYouWatchedPanel.layoutParams = android.widget.FrameLayout.LayoutParams(
            screenW - margin * 2,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setMargins(margin, margin, margin, margin)
        }
        bywUi.setCreditsLayout(inset, pipH)
        becauseYouWatchedPanel.requestLayout()
    }

    /** Restores the video to full screen and the panel to its XML box. */
    private fun exitCreditsMode() {
        if (!creditsModeActive) return
        creditsModeActive = false
        bywUi.setCreditsLayout(0, 0)
        listOf<View>(playerView, p5VideoGlesView).forEach { v ->
            (v.layoutParams as android.widget.FrameLayout.LayoutParams).apply {
                width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                setMargins(0, 0, 0, 0)
            }
            v.requestLayout()
        }
        creditsModePanelParams?.let { saved ->
            becauseYouWatchedPanel.layoutParams = saved
            creditsModePanelParams = null
            becauseYouWatchedPanel.requestLayout()
        }
    }

    // --- Up Next ---
    /**
     * Best-effort TMDB fetch of the next episode's name/runtime so the
     * overlay's Next button can hand off a real title. Runs once per target;
     * the up-next panel does its own (matching) fetch when it appears.
     */
    private fun prefetchNextEpisodeName() {
        if (season == null) return // movies have no next episode
        scope?.launch {
            // Resolved inside the coroutine: in random mode the target comes
            // from a TMDB lookup, not from arithmetic.
            val target = resolveRandomChainTarget(
                this@NativePlayerActivity, randomEpisodes, nextEpisodeTarget(),
                parentId, parentType, season, episode
            ) ?: return@launch
            val key = "${target.first}:${target.second}"
            if (overlayNextPrefetchKey == key) return@launch
            overlayNextPrefetchKey = key
            val nextEp: com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode? = withContext(Dispatchers.IO) {
                val repo = TmdbRepository.getInstance(this@NativePlayerActivity)
                val tmdbId = resolveParentTmdbId() ?: return@withContext null
                val episodes = runCatching {
                    repo.getSeasonEpisodes(tmdbId, target.first, parentId)
                }.getOrNull()
                episodes?.firstOrNull { it.episodeNumber == target.second }
            }
            // An unaired next episode is not advertised on the overlay: a
            // name/runtime there describes an episode the Next button cannot
            // play (the air-date gate refuses it anyway).
            if (nextEp != null && !isUnaired(nextEp.airDate) &&
                overlayNextPrefetchKey == key
            ) {
                nextEp.name?.takeIf { it.isNotBlank() }?.let { pendingNextEpisodeName = it }
                nextEp.runtimeMinutes?.takeIf { it > 0 }?.let { pendingNextEpisodeRuntime = it }
            }
        }
    }

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

    // --- Because You Watched (end credits) ---

    /**
     * Shows the "Because you watched" panel during the end credits: TMDB
     * recommendations for the title that just finished, each with PLAY
     * (auto-resolve top stream, straight into the next playback) and DETAILS
     * (deep-link into the catalog detail screen). Runs only when there is no
     * next episode to chain (movies, or a finished finale); the Up Next panel
     * owns the series flow.
     *
     * The row itself - cards, featured strip, focus rules - is the shared
     * [BecauseYouWatchedUi], the same one the MPV engine raises, and the lineup
     * comes from the shared [buildBecauseYouWatchedPicks]: one implementation,
     * so the two engines' credits rows cannot drift apart.
     */
    private fun showBecauseYouWatchedPanel() {
        if (bywDismissed || isLiveChannel) return
        bywUi.show(itemName)
        // Credits are rolling: shrink the video into the corner so the picks
        // own the screen while the credits keep playing.
        enterCreditsMode()

        scope?.launch {
            val picks: List<BywPick> = withContext(Dispatchers.IO) {
                val tmdbId = resolveParentTmdbId() ?: return@withContext emptyList()
                buildBecauseYouWatchedPicks(
                    this@NativePlayerActivity,
                    tmdbId,
                    bywMediaType(parentType)
                )
            }

            if (picks.isEmpty() || !bywUi.isVisible) {
                // Nothing to recommend: put the video back full screen.
                bywUi.hide()
                exitCreditsMode()
                return@launch
            }

            withContext(Dispatchers.Main) {
                bywUi.build(picks)
            }
        }
    }

    /** PLAY: resolve streams for the pick and hand the top one back. */
    private fun bywPlayPick(pick: BywPick, imdbId: String) {
        val ctx = this
        scope?.launch {
            val vm = StreamsViewModel(application = ctx.application)
            val streams = withContext(Dispatchers.IO) {
                runCatching {
                    vm.resolve(pick.type, imdbId)
                }.getOrNull()
            }.orEmpty()

            val top = streams.firstOrNull { !it.url.isNullOrBlank() }
            bywDismissed = true
            finishWithBywResult(
                action = if (top != null) "play_now" else "go_details",
                pick = pick,
                imdbId = imdbId,
                streamUrl = top?.url,
                streamName = top?.name ?: top?.title
            )
        }
    }

    private fun bywOpenDetails(pick: BywPick, imdbId: String) {
        bywDismissed = true
        finishWithBywResult(
            action = "go_details",
            pick = pick,
            imdbId = imdbId,
            streamUrl = null,
            streamName = null
        )
    }

    /** Hands the pick back to MainActivity (which owns Detail/Player nav). */
    private fun finishWithBywResult(
        action: String,
        pick: BywPick,
        imdbId: String,
        streamUrl: String?,
        streamName: String?
    ) {
        setResult(
            RESULT_OK,
            Intent().apply {
                putExtra("player_result_action", action)
                putExtra("byw_type", pick.type)
                putExtra("byw_id", imdbId)
                putExtra("byw_name", pick.name)
                putExtra("byw_poster", pick.posterUrl)
                putExtra("byw_backdrop", pick.backdropUrl)
                putExtra("byw_stream_url", streamUrl)
                putExtra("byw_stream_name", streamName)
            }
        )
        mediaSession?.release()
        mediaSession = null
        finish()
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

        applyPlayerPanelTheme()
        nextUpPanel.visibility = View.VISIBLE
        btnNextPlay.requestFocus()

        if (autoPlayNext) {
            val threshold = AppPreferences.getStillThereEpisodes(this).toInt()
            val autoAdvanced = AppPreferences.getConsecutiveAutoplays(this)
            if (AppPreferences.getStillTherePrompt(this) && autoAdvanced >= threshold) {
                // Binge watchdog: enough unattended episodes have played in a
                // row - hold here and make the user confirm they're awake.
                // Pressing PLAY NEXT resets the counter and continues.
                nextUpCountdownHeld = false
                nextUpCountdownRemaining = 0
                nextUpCountdown.text = "Are you still there? Press PLAY NEXT to continue"
                nextUpCountdown.setTextColor(
                    androidx.core.content.ContextCompat.getColor(this, R.color.kb_accent)
                )
                nextUpCountdownHandler.removeCallbacks(nextUpCountdownRunnable)
                return
            }
            armNextUpAutoAdvance(playerRemainingMs())
        } else {
            nextUpCountdownHeld = false
            nextUpCountdownRemaining = 0
            nextUpCountdown.text = "PLAY NEXT to continue, or press BACK to exit"
        }

        // Best effort: fetch the next episode's name + still from TMDB so the
        // popup shows real episode details instead of just S#E#.
        scope?.launch {
            val nextEp: com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode? = withContext(Dispatchers.IO) {
                val repo = TmdbRepository.getInstance(this@NativePlayerActivity)
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

    /** Milliseconds left in the episode, or -1 when the duration is unknown. */
    private fun playerRemainingMs(): Long {
        val player = exoPlayer ?: return -1L
        val dur = player.duration
        if (dur <= 0L || dur == C.TIME_UNSET) return -1L
        return (dur - player.currentPosition).coerceAtLeast(0L)
    }

    /**
     * Arms the auto-advance countdown for the Up Next panel. The panel opens
     * before the episode is over (see [maybeTriggerEndPanels]), so counting
     * down from the moment it appears would cut the ending: while real time is
     * left the countdown is held and the panel only says what happens next,
     * then [maybeStartHeldNextUpCountdown] runs it at the end.
     */
    private fun armNextUpAutoAdvance(remainingMs: Long) {
        nextUpCountdownHandler.removeCallbacks(nextUpCountdownRunnable)
        if (remainingMs > NEXT_UP_HOLD_THRESHOLD_MS) {
            nextUpCountdownHeld = true
            nextUpCountdown.text = "Playing next when this episode ends"
            nextUpCountdown.setTextColor(ContextCompat.getColor(this, R.color.kb_text_lo))
            return
        }
        nextUpCountdownHeld = false
        nextUpCountdownRemaining = NEXT_UP_COUNTDOWN_SECONDS
        nextUpCountdown.text = "Playing next in $nextUpCountdownRemaining"
        nextUpCountdown.setTextColor(ContextCompat.getColor(this, R.color.kb_text_lo))
        nextUpCountdownHandler.postDelayed(nextUpCountdownRunnable, 1_000L)
    }

    /** Starts the held countdown once playback has reached the end. */
    private fun maybeStartHeldNextUpCountdown(pos: Long, dur: Long) {
        if (!nextUpCountdownHeld) return
        if (dur <= 0L || dur == C.TIME_UNSET) return
        if (pos < dur - NEXT_UP_HOLD_THRESHOLD_MS) return
        armNextUpAutoAdvance(remainingMs = 0L)
    }

    private fun launchNextEpisode(
        targetSeason: Int,
        targetEpisode: Int,
        episodeName: String? = null,
        runtimeMinutes: Int? = null
    ) {
        nextUpCountdownHeld = false
        nextUpCountdownHandler.removeCallbacks(nextUpCountdownRunnable)
        val label = buildString {
            append("S${targetSeason}E$targetEpisode")
            if (!episodeName.isNullOrBlank()) append(" • $episodeName")
        }
        val pendingNext = NextEpisodeResult.PendingNext(
            season = targetSeason,
            episode = targetEpisode,
            title = label,
            streamId = nextStreamId(targetSeason, targetEpisode),
            runtimeMinutes = runtimeMinutes,
            bingeGroup = currentBingeGroup,
            addonName = currentAddonName,
            // Random mode rides along: the handoff starts a NEW player, which
            // would otherwise read its intent as a normal (arithmetic) chain.
            randomEpisodes = randomEpisodes
        )
        // Persist FIRST: on Fire TV the OS frequently kills the backgrounded
        // MainActivity during 4K playback, so the result callback later runs
        // on a RECREATED activity that never received the result intent. The
        // persisted copy is what lets that callback still route to the next
        // episode instead of falling through to the Home branch.
        NextEpisodeResult.persist(this, pendingNext)
        // Classic result extras too: the primary handoff path when
        // MainActivity survives and reads them directly.
        setResult(
            RESULT_OK,
            Intent().apply {
                putExtra("player_result_action", "next_episode")
                putExtra("next_episode", pendingNext.episode)
                putExtra("next_season", pendingNext.season)
                putExtra("next_title", pendingNext.title)
                putExtra("next_stream_id", pendingNext.streamId)
                putExtra("next_binge_group", pendingNext.bingeGroup)
                putExtra("next_addon_name", pendingNext.addonName)
                putExtra("next_random", pendingNext.randomEpisodes)
            }
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
                // Fire the end-of-episode panel as the credits roll, not only
                // when playback finally reports ENDED.
                maybeTriggerEndPanels(pos, dur)
                maybeStartHeldNextUpCountdown(pos, dur)
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
            // Only offer a skip while the video is ACTUALLY playing. Stamps
            // that start at 0 (recaps especially) would otherwise match
            // during the loading splash, when currentPosition is still 0 --
            // the prompt must never appear before the first frame renders.
            val matching = if (player.isPlaying && player.playbackState == Player.STATE_READY) {
                introDbStamps.firstOrNull { stamp ->
                    posMs >= stamp.startMs && posMs < stamp.endMs &&
                        stamp.endMs > stamp.startMs && stamp.endMs - stamp.startMs <= 10 * 60 * 1000L
                }
            } else null
            if (matching != activeIntroStamp) {
                activeIntroStamp = matching
                if (matching != null) {
                    btnSkipIntro.text = matching.type.buttonLabel
                    btnSkipIntro.visibility = View.VISIBLE
                } else {
                    val wasFocused = btnSkipIntro.isFocused
                    btnSkipIntro.visibility = View.GONE
                    // If focus was parked on the button when it hid (e.g.
                    // the user paused mid-intro), pull it back to the
                    // surface so no ghost focus target remains.
                    if (wasFocused) playerView.requestFocus()
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

        // NonCancellable: this write must land even when the activity is
        // being torn down (onStop/onDestroy cancel their scopes mid-exit).
        // Without it the upsert could be aborted partway through exiting the
        // player, leaving Continue Watching stale until the next save.
        lifecycleScope.launch(Dispatchers.IO + NonCancellable) {
            runCatching {
                val dao = WatchHistoryDatabase.getInstanceScoped(this@NativePlayerActivity).watchHistoryDao()
                val existing = dao.getById(historyId)
                // Same title, same canonical parent id, whichever id flavor
                // launched this playback (see canonicalHistoryParentId).
                val entryParentId = canonicalHistoryParentId()
                val entry = WatchHistoryEntity(
                    id = historyId, parentId = entryParentId, type = parentType,
                    name = itemName, episodeTitle = episodeTitle, overview = overview,
                    clearLogo = clearLogoUrl, totalEpisodesInSeason = totalEpisodesInSeason,
                    poster = itemPoster, streamUrl = currentUrl,
                    season = season, episode = episode, episodeStreamId = episodeStreamId,
                    positionMs = safePos, durationMs = dur, updatedAt = now,
                    isCompleted = isCompleted,
                    completedAt = if (isCompleted) existing?.completedAt ?: now else null
                )
                dao.upsert(entry)
                com.kennyb1201.kbstream.data.sync.SupabaseSync.enqueueHistory(entry)
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
    /**
     * The parent id the playback-history row should be stored under.
     *
     * A title is reachable as "tt..." (add-on catalogs, Continue Watching)
     * and as "tmdb:<n>" (TMDB search rows, the kids rails), and a history row
     * was written under whichever flavor started playback. Continue Watching
     * groups rows by parentId in SQL, so the SAME title ended up as TWO
     * cards - one per flavor - with progress on only one of them. Rows are
     * canonicalized to the IMDB id when it can be resolved; anything
     * unresolvable (no TMDB key, a timeout) stays as the route's own id.
     */
    /**
     * Hands this session over to the MPV backup engine (Settings → Playback
     * engine, and the default: ExoPlayer, fall back when it cannot play).
     *
     * Returns false when that must not happen, so the caller falls through to
     * its own error handling:
     *
     *  - the user chose "ExoPlayer only", or this device has no libmpv
     *    (below Android 8 — see PlayerEngine); [manual] bypasses the setting
     *    only, since a press on the control bar's SWITCH button is the viewer
     *    asking for the change;
     *  - LIVE TV has its own flow (guide, EPG write gates, zapping UI) that
     *    this player does not duplicate;
     *  - a DRM session cannot move: MPV has no Widevine path, so handing it
     *    over would turn "this box cannot decode it" into "this never plays".
     *
     * Everything else travels with the handoff: the source actually playing
     * (which may differ from the launch intent after an in-player switch),
     * its headers, the separate audio track, the playhead, and the canonical
     * parent id the watch-history row is keyed by.
     */
    private fun handOffToMpv(reason: String, manual: Boolean = false): Boolean {
        if (mpvHandoffStarted) return false
        if (isLiveChannel || drmLicenseUrl != null) return false
        if (currentUrl.isBlank()) return false
        if (isFinishing || isDestroyed) return false
        // [mpvFallbackEnabled] answers "may the engine change WITHOUT being
        // asked?" - the automatic handoff on a dead decoder. A press on the
        // control bar's SWITCH button IS the asking, so it is allowed even
        // when the setting is ExoPlayer-only; the guards above still apply,
        // because the backup cannot open live TV or a DRM session either.
        if (!manual && !PlayerEngine.mpvFallbackEnabled(this)) return false
        // Re-play the ORIGINAL launch (source list, cast, badges, return-to)
        // with the stream-specific extras replaced below, so the backup
        // engine inherits the whole context of this session.
        val baseIntent = intent ?: return false
        mpvHandoffStarted = true

        val position = runCatching {
            exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: carryPositionMs
        }.getOrDefault(carryPositionMs)

        val launch = Intent(baseIntent).apply {
            putExtra("stream_url", currentUrl)
            putExtra("audio_url", currentAudioUrl)
            putExtra("start_position_ms", position)
            // The new session resumes where this one stopped; "from the
            // beginning" would restart the title mid-episode.
            putExtra("from_beginning", false)
            putExtra(
                EXTRA_HEADERS,
                streamHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" }
            )
            putExtra(MpvPlayerActivity.EXTRA_MPV_FALLBACK, true)
            putExtra(MpvPlayerActivity.EXTRA_MPV_FALLBACK_REASON, reason)
            // Only when it has already been resolved: otherwise the MPV
            // session resolves it with the same rules (PlaybackHistoryIds).
            historyParentIdOverride?.let {
                putExtra(MpvPlayerActivity.EXTRA_HISTORY_PARENT_ID, it)
            }
        }

        Log.w(
            "PLAYER_RETRY",
            "handing playback to the MPV backup engine ($reason) from ${position}ms"
        )
        errorMessageStr = null
        // The card is what is on screen when this is pressed from there: the
        // reconnecting notice takes its place for the handover itself.
        errorContainer.visibility = View.GONE
        reconnectingContainer.visibility = View.VISIBLE
        bufferingSpinner.visibility = View.GONE
        reconnectingText.text =
            if (manual) {
                "Switching to the MPV player…"
            } else {
                "Continuing in the MPV backup engine…"
            }
        mpvFallbackLauncher.launch(launch)
        return true
    }

    /**
     * The control bar's SWITCH button: move this session to the other engine
     * on purpose, exactly as the automatic fallback would.
     *
     * Everything expensive is shared with [handOffToMpv] - the original launch
     * replayed with the stream extras replaced, the carried position, the
     * result forwarded back to whoever started the player - which is what keeps
     * next-episode, watch history and scrobbling working across a hand switch
     * just as they do across an automatic one. Only the "why" differs, and the
     * wording with it.
     */
    private fun switchPlayerManually() {
        handOffToMpv(MpvPlayerActivity.FALLBACK_REASON_MANUAL, manual = true)
    }

    private suspend fun canonicalHistoryParentId(): String {
        historyParentIdOverride?.let { return it }

        // Shared with the MPV engine (PlaybackHistoryIds): both write this
        // row, so both must derive its id the same way.
        val resolved = PlaybackHistoryIds.canonicalParentId(
            context = this,
            rawParentId = parentId,
            parentType = parentType,
            resolvedTmdbId = resolveParentTmdbId()
        )

        historyParentIdOverride = resolved
        return resolved
    }

    private suspend fun resolveParentTmdbId(): Int? {
        if (resolvedParentTmdbId == null && parentId.isNotBlank()) {
            resolvedParentTmdbId = PlaybackHistoryIds.resolveTmdbId(
                context = this,
                parentId = parentId,
                parentType = parentType
            )
        }
        return resolvedParentTmdbId
    }

    /**
     * Mirror the current scrobble action to MDBList. Its API is
     * session-based: start/pause/stop, with pause & stop marking the item
     * watched at >= 80% progress server-side. Runs alongside the Simkl
     * scrobble; failures are logged, never thrown.
     */
    private suspend fun scrobbleMdbList(action: String, progress: Double) {
        if (MdbListClient.apiKey(this).isBlank() || parentId.isBlank()) return
        val imdbId = parentId.takeIf { it.startsWith("tt") }
        val tmdbId = resolveParentTmdbId()
        val isMovie = parentType.lowercase() == "movie"
        when (action) {
            "start" -> MdbListClient.scrobbleStart(
                this, isMovie, imdbId, tmdbId, season, episode, progress
            )
            "pause" -> MdbListClient.scrobblePause(
                this, isMovie, imdbId, tmdbId, season, episode, progress
            )
            "stop" -> MdbListClient.scrobbleStop(
                this, isMovie, imdbId, tmdbId, season, episode, progress
            )
        }
    }

    private fun scrobbleSimkl(action: String, progressOverride: Double? = null) {
        if (isLiveChannel || parentId.isBlank()) return
        if (action == "start" && simklScrobbleActive && !simklScrobblePaused) return
        // The mirror below updates MDBList as well, so a pause is only
        // skippable when there is no tracker to tell: bailing out here left an
        // MDBList session open ("live" on the dashboard) for the rest of a
        // paused playback whenever Simkl had no session - Simkl not
        // configured, or a start that failed.
        if (action == "pause" && !simklScrobbleActive && !MdbListClient.isConfigured(this)) return
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
            // Independent MDBList scrobble — same session events, separate
            // tracker. Mirrors Simkl only when a key is set.
            runCatching { scrobbleMdbList(action, progress) }
                .onFailure { Log.w(TAG, "MDBList scrobble/$action error: ${it.message}") }
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
            // Mirror the completion to MDBList (POST /sync/watched) so both
            // trackers record finished movies/episodes.
            runCatching {
                if (MdbListClient.apiKey(this@NativePlayerActivity).isNotBlank()) {
                    val isMovie = parentType.lowercase() == "movie"
                    MdbListClient.pushWatched(
                        this@NativePlayerActivity,
                        mediaType = if (isMovie) "movie" else "episode",
                        imdbId = parentId.takeIf { it.startsWith("tt") },
                        tmdbId = resolveParentTmdbId(),
                        season = season,
                        episode = episode
                    )
                }
            }.onFailure { Log.w(TAG, "MDBList completion sync error: ${it.message}") }
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
    /**
     * Carries the playhead across a system recreate (backgrounded app, killed
     * process). The intent the activity is restored with is the one it was
     * launched with, so without this a restored playback restarts the title
     * from the beginning. A position of 0 is deliberately not stored: a title
     * legitimately started "from the beginning" must not become a resume.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (isLiveChannel) return
        val pos = exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: carryPositionMs
        if (pos > 0L) outState.putLong(STATE_PLAYER_POSITION_MS, pos)
    }

    override fun onStop() {
        super.onStop()
        EpgWriteGate.setPlayerActive(false)
        // Release session & player early so the next NativePlayerActivity
        // doesn't collide with a stale MediaSession ID.
        handler.removeCallbacksAndMessages(null)
        nextUpCountdownHandler.removeCallbacks(nextUpCountdownRunnable)
        // Remember where playback actually was: onSaveInstanceState() can run
        // after this method (API 28+) and the player is released by then.
        if (!isLiveChannel) {
            carryPositionMs = exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: carryPositionMs
        }
        // Save progress BEFORE cancelling scope: saveProgress writes via
        // lifecycleScope, which is independent of `scope`, but ordering it
        // ahead of teardown keeps intent clear and avoids racing any
        // scope-bound work that reads history.
        saveProgress(reason = "stop")
        scope?.cancel()
        scrobbleSimkl("stop")
        subtitleCueHandler?.cancelPending()
        subtitleCueHandler = null
        exoPlayer?.release()
        exoPlayer = null
        mediaSession?.release()
        mediaSession = null
    }

    override fun onDestroy() {
        super.onDestroy()
        // Safety net for a player that never reached onStop's counterpart:
        // leaving this set would hold guide writes back forever.
        EpgWriteGate.setPlayerActive(false)
        p5VideoGlesView.release()
        handler.removeCallbacksAndMessages(null)
        scrubHintHandler.removeCallbacksAndMessages(null)
        channelNumberHandler.removeCallbacksAndMessages(null)
        nextUpCountdownHandler.removeCallbacks(nextUpCountdownRunnable)
        scope?.cancel()
        subtitleCueHandler?.cancelPending()
        subtitleCueHandler = null
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
        currentBadges = stream.badges
        currentBingeGroup = stream.bingeGroup
        resolveAddonIdentity(currentSourceLabel)
        currentUrl = newUrl
        currentAudioUrl = stream.audioUrl
        currentSourceIndex = sources.indexOfFirst { it.url == newUrl }
        retryAttempt = 0; retryExhausted = false; errorMessageStr = null; forceTextureViewFallback = false; languagesAutoSelected = false
        dvStripRetryDone = false; forceDvStripForSession = false
        decoderResourceFallbackDone = false
        // Per-source Dolby Vision identity. These used to survive into the
        // next player build, so a P5 title followed by any other title kept
        // the P5 GL color path "on": the shader then applied ICtCp math to
        // HDR10 pixels (wrong colors), and for AVC nothing can render into
        // the buffer output while the player view is hidden (no video at
        // all). A fresh source starts with no DV identity and its own track
        // callback re-establishes it.
        currentCodecs = null
        streamDeclaredDvCodec = null
        // A manual source switch is a fresh, actively-playing load — drop
        // the actor-return pause semantics so the new source starts
        // playing like any other switch.
        fromActorReturn = false
        actorReturnOverlayShown = true
        // A source switch is a fresh load, not a mid-playback rebuffer: reset
        // the first-play latch so the full splash (backdrop + pulsing
        // clearlogo) shows during the load instead of the small spinner. The
        // actor-return gate still wins — those sessions keep the spinner.
        hasPlayedOnce = false
        if (!fromActorReturn) {
            showSplash()
        }
        dismissPicker()
        recreatePlayer(settleMs = SOURCE_SWITCH_SETTLE_MS)
    }

    /**
     * Moves to the next ranked source. [delayMs] lets a caller leave the
     * platform time to tear its video decoder down first (checking the
     * decoder pool showed the box returning no codec at all for ~15s after a
     * failure), and [statusText] replaces the generic "Trying next source"
     * line when the reason is worth naming.
     */
    private fun tryNextSource(delayMs: Long = 0L, statusText: String? = null): Boolean {
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
        reconnectingText.text = statusText ?: "Trying next source: ${nextStream.displayLabel()}…"
        if (delayMs > 0L) {
            handler.postDelayed({
                errorMessageStr = null
                switchToSource(nextStream)
            }, delayMs)
        } else {
            switchToSource(nextStream)
        }
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

/** Badge chips for one stream, from the sources_json payload. */
private fun parseStreamBadges(array: org.json.JSONArray?): List<StreamBadge> {
    if (array == null || array.length() == 0) return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        val obj = array.optJSONObject(i) ?: return@mapNotNull null
        val name = obj.optString("name", "")
        val imageURL = obj.optString("imageURL", "")
        if (name.isBlank() && imageURL.isBlank()) return@mapNotNull null
        StreamBadge(
            name = name,
            imageURL = imageURL,
            tagColor = obj.optString("tagColor", ""),
            tagStyle = obj.optString("tagStyle", ""),
            textColor = obj.optString("textColor", ""),
            borderColor = obj.optString("borderColor", "")
        )
    }
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

/**
 * Whether a decoder failure on a Dolby Vision passthrough session is the
 * vendor DV decoder refusing the profile, rather than the box being out of
 * decoders.
 *
 * Some DV-capable boxes (TCL / Realtek: OMX.realtek.video.dvhe.st.decoder)
 * advertise video/dolby-vision, so Media3 reports the format as supported, and
 * then hard-fail the moment the first frame is submitted — reporting that
 * refusal with the platform's own out-of-resources code
 * (OMX_ErrorInsufficientResources, 0x80001000). By code alone it is
 * indistinguishable from genuine resource exhaustion, which every later
 * decoder on the process returns.
 *
 * The discriminator is the session: genuine exhaustion is a property of the
 * box that only shows up once a decoder has already failed, while the DV
 * refusal is the FIRST decode, with passthrough actually enabled and a
 * declared-DV track on screen. Treating that first failure as exhaustion is
 * what sent a DV-capable TV to "out of video decoder resources" and the
 * next-source hunt instead of the HDR10 strip that plays.
 */
internal fun dvPassthroughDecoderRefused(
    isDecoderFailure: Boolean,
    dvPassthroughActive: Boolean,
    declaredDvCodec: String?,
    alreadyStripped: Boolean
): Boolean = isDecoderFailure &&
    dvPassthroughActive &&
    !alreadyStripped &&
    dvLabelFromCodec(declaredDvCodec) != null

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
        lower.contains("mp4a") || lower.startsWith("mp3") || lower.contains("aac") -> "AAC"
        // E-AC3 first: "audio/eac3" contains "ac3", so testing AC-3 here
        // first labelled every E-AC3 track as AC-3 — the one difference a
        // viewer choosing between two Dolby tracks is looking for.
        lower.contains("eac3") || lower.contains("ec-3") -> "EAC3"
        lower.contains("ac-3") || lower.contains("ac3") -> "AC-3"
        lower.contains("opus") -> "Opus"
        lower.contains("vorbis") -> "Vorbis"
        lower.contains("flac") -> "FLAC"
        // Audio reached through its MIME type rather than a codec string (see
        // audioTrackLabel): container audio tracks leave Format.codecs empty,
        // so these names come from "audio/<something>" instead.
        lower == "audio/mpeg" || lower == "audio/mp3" -> "MP3"
        lower == "audio/mpeg-l1" -> "MP1"
        lower == "audio/mpeg-l2" -> "MP2"
        lower == "audio/raw" -> "PCM"
        lower == "audio/alac" -> "ALAC"
        lower.contains("dts.hd") || lower.contains("dts-hd") || lower.contains("dtshd") -> "DTS-HD"
        lower.contains("dts") -> "DTS"
        lower.contains("true-hd") || lower.contains("truehd") || lower.contains("mlp") -> "TrueHD"
        lower.contains("ac-4") || lower.contains("ac4") -> "AC-4"
        lower.contains("video/h264") || lower.contains("video/avc") -> "H.264"
        lower.contains("video/hevc") || lower.contains("video/h265") -> "H.265"
        lower.contains("video/vp9") -> "VP9"
        lower.contains("video/av01") || lower.contains("video/av1") -> "AV1"
        else -> codec.uppercase()
    }
}
