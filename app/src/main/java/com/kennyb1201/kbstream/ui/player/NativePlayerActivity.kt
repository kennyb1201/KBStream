package com.kennyb1201.kbstream.ui.player

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import com.kennyb1201.kbstream.data.debrid.TorBoxResolve
import android.content.Intent
import android.util.TypedValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.common.ForwardingPlayer
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import com.kennyb1201.kbstream.R
import com.kennyb1201.kbstream.ui.home.looksLikeRawMediaId
import com.kennyb1201.kbstream.ui.theme.themeAccentColor
import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.data.addon.StreamBehaviorHints
import com.kennyb1201.kbstream.data.badges.StreamBadge
import com.kennyb1201.kbstream.data.download.DownloadsManager
import com.kennyb1201.kbstream.data.iptv.ChannelRecall
import com.kennyb1201.kbstream.data.iptv.EpgWriteGate
import com.kennyb1201.kbstream.data.iptv.GuideRevision
import com.kennyb1201.kbstream.data.iptv.IptvRepository
import com.kennyb1201.kbstream.data.iptv.KidsLiveTvFilter
import com.kennyb1201.kbstream.data.iptv.epgProgramChannelKey
import com.kennyb1201.kbstream.data.namedEpisodeNumber
import com.kennyb1201.kbstream.data.memory.MemoryPressure
import com.kennyb1201.kbstream.data.memory.releaseImageMemoryCache
import com.kennyb1201.kbstream.data.reporting.PlaybackEngineTrace
import com.kennyb1201.kbstream.data.reporting.Redaction
import com.kennyb1201.kbstream.data.reporting.PlaybackSessionTrace
import com.kennyb1201.kbstream.data.youtube.InnerTubeExtractor
import com.kennyb1201.kbstream.data.youtube.PlayableSource
import com.kennyb1201.kbstream.data.youtube.PoTokenTrace
import com.kennyb1201.kbstream.data.youtube.TrailerPlayerLauncher
import com.kennyb1201.kbstream.data.youtube.isYouTubeIdentityBlocked
import com.kennyb1201.kbstream.data.iptv.LiveChannelZapRegistry
import com.kennyb1201.kbstream.data.iptv.db.EpgProgramRow
import com.kennyb1201.kbstream.data.iptv.db.IptvDatabase
import com.kennyb1201.kbstream.ui.player.PickerAdapter.Companion.bindBadgeRow
import com.kennyb1201.kbstream.data.history.PlaybackHistoryWriter
import com.kennyb1201.kbstream.data.player.EpisodeScheme
import com.kennyb1201.kbstream.data.player.EpisodeSchemeStore
import com.kennyb1201.kbstream.data.player.bundledEpisodeCount
import com.kennyb1201.kbstream.data.player.LanePoolDataSourceFactory
import com.kennyb1201.kbstream.data.player.LanguageMatch
import com.kennyb1201.kbstream.data.player.PlayedLinkCache
import com.kennyb1201.kbstream.data.player.PlayerEngine
import com.kennyb1201.kbstream.data.player.StreamDiskCache
import com.kennyb1201.kbstream.data.player.StreamUserAgent
import com.kennyb1201.kbstream.data.player.fetchLanesFor
import com.kennyb1201.kbstream.domain.streamengine.StreamRanker
import com.kennyb1201.kbstream.data.youtube.TrailerPlayerPool
import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import com.kennyb1201.kbstream.data.mdblist.MdbListClient
import com.kennyb1201.kbstream.data.player.PlayerTitlePrefs
import com.kennyb1201.kbstream.data.player.PlayerTrackMemory
import com.kennyb1201.kbstream.data.simkl.SimklRepository
import com.kennyb1201.kbstream.data.trakt.TraktRepository
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.tmdb.bestLogoPath
import com.kennyb1201.kbstream.data.watched.ContinueWatchingRefreshBus
import com.kennyb1201.kbstream.data.format.DateFormats
import com.kennyb1201.kbstream.data.settings.AppPreferences
import coil3.load
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit
import com.kennyb1201.kbstream.data.runCatchingCancellable

private const val TAG = "NativePlayer"
private const val PERIODIC_SAVE_INTERVAL_MS = 5_000L
private const val MIN_RESUME_POSITION_MS = 10_000L
// ASS overlay redraw cadence: a frame per display refresh while playback runs
// (libass animates signs and karaoke), and a slow poll while paused so a seek
// or an offset nudge still lands on screen.
private const val ASS_FRAME_INTERVAL_MS = 33L
private const val ASS_IDLE_INTERVAL_MS = 250L
// Drop-in directory for fansub fonts under the app's private files dir.
private const val ASS_FONT_DIR = "fonts"
// A subtitle file is kilobytes; an unbounded readText() on a hostile or
// corrupt source (a mislabeled multi-GB file, a decompression bomb) would OOM
// the player. Anything past this cap is refused rather than read.
private const val MAX_SUBTITLE_FILE_BYTES = 8 * 1024 * 1024
// Format id stamped on the user's own sidecar subtitle track so the ASS
// track-selection pass can tell it apart from an aggressively-embedded SSA
// track: a sidecar is rendered whole-script under the SIDECAR overlay and must
// never be re-routed to the embedded streaming path.
private const val SIDECAR_TRACK_ID = "kbstream.sidecar"
// Tag for every libass overlay decision: what the selection claimed and on what
// basis, which attach gate stopped it, and whether the cue gate is suppressing
// the pipeline's flattened copy. None of this was observable before, which is
// why a selected-but-black subtitle track had to be diagnosed by elimination.
private const val TAG_ASS = "PLAYER_ASS"
// Tag for the bitmap subtitle path (PGS today): the one line that says a cue
// group arrived as images and where it was drawn. A bitmap track that shows
// nothing is otherwise indistinguishable from one that was never selected.
private const val TAG_SUB = "PLAYER_SUB"

// The stored subtitle size / position values (see
// AppPreferences.getDefaultSubtitleSize and getDefaultSubtitlePosition): 0 is
// the smallest / lowest, and the defaults are 1.
private const val SUBTITLE_SIZE_SMALL = 0
private const val SUBTITLE_SIZE_LARGE = 2
private const val SUBTITLE_POSITION_MID = 1
private const val SUBTITLE_POSITION_HIGH = 2

// How much of the view's height one position step lifts a BITMAP cue, and how
// far the two size steps scale it. The step is a fraction of the viewport
// (0.36 of the SubtitleView's height per step, about a line and a half) so it
// reads the same on a phone and a 4K panel.
private const val BITMAP_CUE_POSITION_STEP = 40f
private const val BITMAP_CUE_SCALE_SMALL = 0.85f
private const val BITMAP_CUE_SCALE_LARGE = 1.2f

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

/**
 * Shortest gap between two follow-seeks while a touch drag moves the thumb.
 *
 * A drag fires a progress change far faster than a heavy stream can settle a
 * seek, so at most one seek happens per window; the drag's release still lands
 * exactly through [PlayerChromeHost.onChromeSeekTo]. A seek per event is the
 * storm this throttle exists to prevent, not a target to approach.
 */
private const val TOUCH_FOLLOW_SEEK_MS = 150L
private val RETRY_BACKOFF_MS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)

/**
 * Attempts a source that never opened gets before the next one is tried.
 *
 * The full [MAX_RETRY_ATTEMPTS] ladder is for a source that played and then
 * broke, where a rebuild can plausibly help. A source whose connection was
 * refused, or whose request the server answered with a status, is a different
 * animal: the retry rebuilds the player with the IDENTICAL url, so the sixth
 * attempt asks the same dead endpoint the same question, and nothing in this
 * app re-resolves a stream url - only the add-on can. A field capture spent
 * the whole six attempts on one such source, which on this box is about two
 * minutes of "Reconnecting..." once the connect timeouts are counted, and
 * finished on a card whose CHANGE SOURCE button was the only way forward with
 * two more sources already ranked behind it.
 */
private const val MAX_UNOPENABLE_RETRY_ATTEMPTS = 2

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
 * Grace period before asking for a decoder again after resource exhaustion.
 * The vendor codec's release lands ~3s after ExoPlayer lets it go on this box
 * (`ACodec: forcing the release of codec`), so a rebuild inside that window
 * asks for a 4K decoder while the previous component still holds its
 * resources and fails identically.
 */
private const val DECODER_RESOURCE_RETRY_DELAY_MS = 6_000L

/**
 * The card a session parks on when the decoder pool is the reason it stopped.
 * Written once so the wedged-pool path and the no-source-left path cannot
 * drift into two different explanations of the same failure.
 */
private const val DECODER_EXHAUSTED_MESSAGE =
    "This TV has run out of video decoder resources.\n" +
        "Restart the app, or pick a 1080p source for this title."

/**
 * The resolution tier a stream's own text must claim (see
 * [StreamRanker.resolutionRank]) before an unknown-bitrate source is treated as
 * heavy enough for parallel fetch lanes: 5 is 2160p/4K.
 *
 * A tier rather than the string itself, because it is the same reading the
 * ranker and the auto-play quality ceiling use, so the lane gate cannot drift
 * from either.
 */
private const val UHD_RESOLUTION_RANK = 5

/**
 * How long a source switch leaves the box alone between releasing the old
 * player and building the next one. A switch reuses the same output Surface
 * for the new codec, and on this Realtek stack the outgoing 4K decoder's
 * buffers stay bound to that Surface past kWhatReleaseCompleted: the next
 * codec's setNativeWindowSizeFormatAndUsage then reconfigures a surface that
 * is still holding ~10 x 4K buffers, and the codec comes back
 * OMX_ErrorInsufficientResources (0x80001000) as soon as samples are
 * submitted. The DV-strip and resource-exhaustion rebuilds below already wait
 * 3s / 6s for this vendor behavior (their own comments say the release lands
 * ~3s after ExoPlayer lets the codec go); a source switch was the one rebuild
 * that waited nothing - and the field log shows a session's first failure
 * landing 4ms after the previous player was released, on a plain HDR10 HEVC
 * file the box decodes natively.
 *
 * The window is counted from the release, not from the rebuild that asks for
 * it: a second switch inside it - the viewer picking another source, or the
 * next-source ladder firing while they do - waits out the remainder instead of
 * asking for a decoder a second after the last one was handed back.
 *
 * It is [DECODER_RESOURCE_RETRY_DELAY_MS]'s 6s rather than the 3s the other
 * rebuilds use, because 3s is the vendor's own release latency rather than
 * something on top of it: the request landed exactly as the component was
 * finishing and the box answered the same 0x80001000. 6s clears it with
 * margin, which is the timing the field session showed a rebuild succeeding
 * at. The cost is splash, not black screen - [switchToSource] raises the
 * backdrop before this runs.
 */
private const val SOURCE_SWITCH_SETTLE_MS = 6_000L

// Shared with the MPV player's panel: same speeds, same labels, one list.
internal val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

// Aspect ratio modes. 0-2 map to the Media3 resize modes (see
// applyResizeMode); 3-4 pin the video frame to a fixed 16:9 / 4:3 ratio
// for misflagged streams where the embedded size doesn't match the
// actual picture (squeezed/pillarboxed).
val ASPECT_MODES = listOf("Fit", "Zoom", "Fill", "16:9", "4:3")
private const val ASPECT_MODE_FORCE_16_9 = 3
private const val ASPECT_MODE_FORCE_4_3 = 4
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
 * How long OK has to be held to open the in-player channel guide. Long enough
 * that an ordinary press never trips it, short enough that the hold does not
 * feel like the remote has stopped responding.
 */
private const val CHANNEL_GUIDE_LONG_PRESS_MS = 600L

/**
 * Programs to keep per channel for the guide overlay. Two is exactly the
 * now/next pair the rows show; the DAO's window already excludes everything
 * that has finished.
 */
private const val CHANNEL_GUIDE_ROWS_PER_CHANNEL = 2

/**
 * How often an open guide overlay checks whether the imported guide changed
 * ([GuideRevision]). The signal is a process-local counter, so the check is
 * cheap; the interval only has to be short enough that a guide that lands
 * mid-view fills in before the viewer gives up on it.
 */
private const val CHANNEL_GUIDE_WATCH_MS = 2_500L

class NativePlayerActivity : ComponentActivity(), PlayerChromeHost {

    /**
     * The MPV handoff's launch intent: this session replayed at the BACKUP
     * engine.
     *
     * [handOffToMpv] builds the handoff out of THIS activity's own intent - it
     * replays the whole session (source list, cast, badges, return-to) with the
     * stream extras replaced - so the base still names NativePlayerActivity as
     * its component. This is the one place that component is corrected;
     * launched unchanged it opened a second ExoPlayer session instead of MPV,
     * so the SWITCH press looked like it did nothing and the INFO panel came
     * back still chipped EXOPLAYER.
     *
     * The rewrite used to happen in a `startActivityForResult` override, keyed
     * off the handoff extra the intent happens to carry. Doing it here makes it
     * explicit at the one call site, and clears the deprecated override. The
     * extra stays on the intent: MPV reads it to know it is the backup engine.
     */
    internal fun mpvHandoffIntent(base: Intent): Intent =
        Intent(base).apply {
            setClass(this@NativePlayerActivity, MpvPlayerActivity::class.java)
            // Disarm this session's own Up Next countdown as playback leaves for
            // the backup engine: it is a Handler tick, so it fires whether or
            // not this surface is the one on screen. An ExoPlayer session left
            // armed behind MPV chains an episode out from under it and replaces
            // MPV's result with its own, which reads like the switch failed.
            nextUpCountdownHeld = false
            nextUpCountdownRemaining = 0
            nextUpHandoffArmed = false
            nextUpCountdownHandler.removeCallbacks(nextUpCountdownRunnable)
        }

    /**
     * Answers the two SWITCH buttons when a press cannot land.
     *
     * Both of them run into [handOffToMpv] (see `setupListeners()`), whose
     * refusal is silent by design there - the same path serves the AUTOMATIC
     * fallback, where nothing appearing on screen is correct. A press is a
     * question, though, and the buttons' own visibility gate only covers the
     * reasons true for the whole session (no libmpv, live TV, DRM). The two
     * that are true only for a moment - the first source still opening, and a
     * handoff already in flight - left the press looking exactly like a broken
     * button, which is how the handoff-intent bug read from the couch.
     *
     * `setupListeners()` sits past the tooling's edit window, so the listeners
     * are restated here instead: the same call, plus the feedback. [onResume]
     * is the hook - `bindViews()` has bound the buttons by then, and re-running
     * this only ever rewrites the same two listeners.
     */
    private fun installManualSwitchFeedback() {
        if (!::btnPlayerSwitch.isInitialized || !::btnSwitchPlayer.isInitialized) return
        val onSwitch = View.OnClickListener { switchEngineFromButton() }
        btnPlayerSwitch.setOnClickListener(onSwitch)
        btnSwitchPlayer.setOnClickListener(onSwitch)
    }

    override fun onResume() {
        super.onResume()
        installManualSwitchFeedback()
        rearmFrameRateMatch()
    }

    /**
     * Re-offers the remembered content rate when the viewer comes back to a
     * session that will not measure it again.
     *
     * Reported: a 24 fps film switched the panel to 24 Hz, leaving the player
     * (Back or Home) put it back to 60 Hz as designed, and returning to the film
     * left it at 60 Hz for the rest of the title. The rate was never lost - the
     * probe still had it - but nothing re-asked: the probe runs once, the
     * player's own report comes with the first track, and both had already
     * happened, so the panel was the only thing that needed telling again.
     *
     * Three things have to hold, and each is a real reason to do nothing:
     *
     *  - a rate was actually measured. A stream nobody could measure stays
     *    unmatchable rather than being guessed at (see [FrameRateMatch.resumeRate],
     *    which refuses a stored `NO_VALUE` the same way it refused it on arrival).
     *  - the setting is still on. It can be switched off while the player is
     *    away — with a transient stop no longer restoring the panel, the panel is
     *    still at the film rate on the way back — so the off case puts it back
     *    here before returning.
     *  - the matcher exists, which is the same "the setting was on when this
     *    session started" every other call site tests.
     *
     * Re-offering a rate the panel is already in is a no-op by
     * [FrameRateMatcher.onContentFrameRate]'s own contract, so a resume that did
     * not need a switch cannot cause one — and the mode search now says so
     * before the surface hint can be fired at the live surface.
     */
    private fun rearmFrameRateMatch() {
        if (!AppPreferences.getMatchFrameRate(this)) {
            // Switched off while we were away. A transient stop leaves the panel
            // at the film rate now, so this is the call that puts it back; the
            // matcher's own release is a no-op if it never engaged.
            if (frameRateMatcher?.hasEngaged == true) frameRateMatcher?.release()
            return
        }
        val fps = FrameRateMatch.resumeRate(lastContentFps) ?: return
        // Same tag the probe and the matcher log under, so a field capture of
        // "AFR did not re-arm" reads as one sequence.
        Log.i("PLAYER_AFR", "re-arming frame-rate match with $fps fps")
        frameRateMatcher?.onContentFrameRate(fps, FrameRateSource.RESUME)
    }

    /**
     * One press of the settings panel's dialogue-boost pads - the only stepper
     * this control has now that the bar's own pair is gone, and how the panel's
     * rows reach it: they are built in code ([PlayerPanelSection]), and this is
     * the one place that also handles the tunneling edge below.
     *
     * [PlayerAudioTuning.stepDialogueLevel] decides where the step lands (a
     * step taken from "Global" starts at the level Settings is actually on, so
     * neither pad ever drops the viewer to Off), and
     * [PlayerTrackBridge.chooseAudioDialogueBoost] stores it for this title and
     * republishes the tuning. The processor reads [PlayerAudioTuning] per
     * buffer, so that is the whole of "apply it": the next buffer is louder,
     * with no rebuild and no gap in playback.
     *
     * The level is then named on screen, because the first steps of the scale
     * are subtle by design (see [PlayerAudioTuning.centerGain]) and "did that
     * do anything?" is not a question a step should leave behind.
     */
    internal fun stepDialogueBoost(delta: Int) {
        val wasNeutral = PlayerAudioTuning.isNeutral
        val next = PlayerAudioTuning.stepDialogueLevel(
            current = PlayerTrackBridge.audioDialogueBoost,
            globalLevel = AppPreferences.getAudioDialogueBoost(this),
            delta = delta
        )
        PlayerTrackBridge.chooseAudioDialogueBoost(this, next)
        Toast.makeText(
            this,
            "Dialogue boost: " + PlayerAudioTuning.dialogueLevelText(next),
            Toast.LENGTH_SHORT
        ).show()
        restateAudioChainIfTunneling(wasNeutral)
    }

    /**
     * Rebuilds the player when a dialogue step turns the audio tuning on or off
     * during a TUNNELED session.
     *
     * The tunnel decision is taken when the sink is built (`createPlayer`): a
     * tunneled track carries the audio inside the hardware path, where the
     * sink's processors - downmix, dialogue lift, volume boost - never run. The
     * build made that call while the tuning was still neutral, so the step that
     * leaves neutral has to build the chain again, or the press would change a
     * number and nothing else: the exact silent no-op a viewer cannot tell from
     * a broken button.
     *
     * Only the EDGE is handled - the first step up, and the step back down to
     * neutral that hands the session back to the tunnel - never every press, and
     * the playhead rides across in [carryPositionMs] exactly as it does for the
     * subtitle-attach rebuild. Sessions that were never tunneled pay nothing:
     * this returns before touching the player, and `enableTunneling` is off by
     * default.
     */
    private fun restateAudioChainIfTunneling(wasNeutral: Boolean) {
        if (!enableTunneling || isLiveChannel) return
        // Tunneling is skipped outright when the FFmpeg audio decoder is
        // preferred (see createPlayer), so that session's sink IS in the chain
        // and its step is already audible.
        if (AppPreferences.getAudioDecoder(this) == AppPreferences.AUDIO_DECODER_PREFER_APP) {
            return
        }
        // Same when the decode output mode is in force: createPlayer already
        // keeps the PCM chain in the renderer path, so no rebuild is owed.
        if (PlayerAudioTuning.requiresDecode(AppPreferences.getAudioOutput(this))) {
            return
        }
        if (PlayerAudioTuning.isNeutral == wasNeutral) return
        // Frame-gated like the other carry writes: before the first frame the
        // player clock is still on the launch position, so restating it here
        // would overwrite a real carry with the frozen start.
        if (firstFrameRendered) {
            carryPositionMs = exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: carryPositionMs
        }
        recreatePlayer()
    }

    /**
     * SWITCH, from the control bar or the error card.
     *
     * [handOffToMpv] owns the decision - it is the only place that knows
     * whether this session can move at all - so the press is routed straight
     * through it. Only its refusal is answered, and with the gate that said no.
     */
    private fun switchEngineFromButton() {
        if (handOffToMpv(MpvPlayerActivity.FALLBACK_REASON_MANUAL, manual = true)) return
        Toast.makeText(this, switchBlockedReason(), Toast.LENGTH_LONG).show()
    }

    /**
     * Which of the handoff's gates stopped the press, in the player's words.
     *
     * The same conditions [handOffToMpv] checks and [canHandOffToMpv] mirrors,
     * in the order the handoff checks them, so the sentence names the gate that
     * actually said no. The last line covers the rest (a session being torn
     * down, a launch that never carried an intent).
     */
    private fun switchBlockedReason(): String = when {
        mpvHandoffStarted -> "Already switching to the MPV player\u2026"

        isLiveChannel ->
            "Live TV can't change engines \u2014 zapping stays in the native player"

        drmLicenseUrl != null ->
            "This stream is DRM-protected; only the native player can play it"

        !PlayerEngine.isMpvAvailable() -> "This device has no MPV player"

        currentUrl.isBlank() ->
            "Nothing is playing yet \u2014 try SWITCH again once it starts"

        else -> "Can't switch engines right now"
    }

    // Views
    private lateinit var playerView: KBPlayerView
    // Panel refresh-rate matching (Settings → Playback → Match Content Frame
    // Rate). Created once per activity, before the player can report a video
    // track, and null when the setting is off — which is what makes every call
    // site below a no-op rather than a check repeated at each one.
    private var frameRateMatcher: FrameRateMatcher? = null
    // The content rate this session was last told, so a return to the player can
    // re-arm the panel from it (see [rearmFrameRateMatch]). Both halves that
    // supply a rate run once per session - the probe reads the stream's HEAD,
    // and the player reports its rate with the first video track - so without
    // this a resume had nothing to re-offer and the panel stayed at whatever
    // [FrameRateMatcher.release] put back on the way out. Fixed for this
    // activity's lifetime: currentUrl is read from the intent at onCreate and a
    // source switch is a new session, so a remembered rate always describes the
    // stream that is playing.
    private var lastContentFps: Double? = null
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
    // The one shared overlay (player_chrome.xml), driven by this engine through
    // PlayerChromeHost. Its views are bound by their neutral chrome_* ids, and
    // its visibility, auto-hide and focus order live in the chrome itself.
    private var chrome: PlayerChrome? = null
    private lateinit var seekbar: SeekBar
    private lateinit var currentTime: TextView
    private lateinit var totalTime: TextView
    private lateinit var btnPlayPause: ImageView
    // The control bar's buttons are icon views, SOURCE included: they used to be
    // TextViews drawing a font glyph ("\u2672" for next, "\u266b" for audio, "CC",
    // "\u2699" ...), so their weight and optical center came from whatever font the
    // device shipped instead of from the layout, and no two of them matched.
    // SPEED and ASPECT keep their words - a rate and a mode name ARE their
    // state - in the bar's own typeface. Bound by id from bindViews(), listed
    // in the order the bar lays them out.
    private lateinit var btnNext: ImageView
    private lateinit var btnSource: ImageView
    private lateinit var btnAudio: ImageView
    private lateinit var btnSubtitle: ImageView
    private lateinit var btnSpeed: TextView
    private lateinit var btnAspect: TextView
    private lateinit var btnPlayerSwitch: ImageView
    private lateinit var btnPlayerExternal: ImageView
    private lateinit var btnSettings: ImageView
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
    private lateinit var btnInfo: ImageView
    private lateinit var infoPanel: ScrollView
    private lateinit var infoAddonIcon: ImageView
    private lateinit var infoTitle: TextView
    private lateinit var infoSource: TextView
    private lateinit var infoEngine: TextView
    private lateinit var infoFile: TextView
    private lateinit var infoVideo: TextView
    private lateinit var infoAudio: TextView
    private lateinit var infoSubtitles: TextView
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

    /** The countdown's progress bar, driven by [nextUpCountdownRunnable]. */
    private lateinit var nextUpCountdownBar: ProgressBar

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
            // WRAP_CONTENT rather than a fixed 440dp: anchored to the corner,
            // a fixed width left the card's whole right-hand side empty - the
            // still and two short lines of text need far less than 440dp, so
            // the card read as a wide box with its content huddled into the
            // left of it. Hugging its own widest line (bounded by the text
            // column's cap below) puts the card's right edge where the text
            // ends, and that empty band goes with it.
            params.width = ViewGroup.LayoutParams.WRAP_CONTENT
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            params.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
            // Low in the corner: the bottom margin is only the safe-area gap
            // now. It used to be 152dp, the height of the control bar it was
            // keeping clear of - but that bar is never on screen at the same
            // time as this card (the overlay does not come up while the card is
            // showing), so all that clearance did was hold the card a third of
            // the way up the screen. It now sits just off the bottom edge with
            // a small breathing gap, so it covers as little of the picture as
            // possible while still reading as anchored to the screen rather
            // than sliding off it. The right edge still lines up with the
            // screen's own 48dp inset, which is what makes it read as part of
            // the screen rather than dropped on top of it.
            params.setMargins(dp(24), dp(24), dp(48), dp(40))
        }
        nextUpPanel.setPadding(dp(14), dp(14), dp(14), dp(14))
        nextUpPanel.requestLayout()

        // The still keeps its 16:9 shape, at the width the smaller card leaves.
        nextUpThumb.layoutParams = LinearLayout.LayoutParams(dp(150), dp(84))

        // The text column follows the narrower card: less gap to the still,
        // smaller type, and tighter leading between the lines.
        (nextUpPanel.getChildAt(1) as? LinearLayout)?.let { column ->
            // The column stops filling the card and starts DEFINING it: with
            // the panel on wrap_content there is no leftover space for a weight
            // to hand out (and a weight there would resolve the column to
            // zero), so it goes with the fixed width and the column now sizes
            // to its own lines.
            (column.layoutParams as? LinearLayout.LayoutParams)?.let { columnParams ->
                columnParams.marginStart = dp(14)
                columnParams.width = LinearLayout.LayoutParams.WRAP_CONTENT
                columnParams.weight = 0f
            }
        }
        nextUpShowTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        nextUpEpisodeLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        nextUpEpisodeTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        nextUpCountdown.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        nextUpPanel.findViewById<TextView>(R.id.next_up_kicker)
            ?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        // The countdown's bar: same one-second runnable that writes the number
        // above it, so the two can never disagree.
        nextUpCountdownBar.max = NEXT_UP_COUNTDOWN_SECONDS
        // The two title lines are what the card's width comes from now, so they
        // are hugged but capped: a long show or episode name wraps or
        // ellipsizes (the episode title has two lines to do it in) instead of
        // stretching the card across the picture. Fresh LayoutParams because
        // the XML has them match_parent, which inside a wrap_content card would
        // measure against the whole screen.
        listOf(nextUpShowTitle, nextUpEpisodeTitle).forEach { line ->
            line.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            line.maxWidth = dp(240)
        }
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
            surfaceColor = { playerPanelSurfaceColor(this) },
            raisedColor = { playerPanelRaisedColor(this) },
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

    /**
     * This player's own audio processors, owned by the factory that installs
     * them (see [PlayerAudioProcessors]). Set in [createPlayer] and replaced
     * with it, so the audio-offset slider can never be left talking to a
     * processor no sink is carrying - and Home's hero trailer, which builds the
     * same factory, has a pair of its own.
     */
    private var audioProcessors: PlayerAudioProcessors? = null

    /**
     * Which rebuild the player field belongs to. A rebuild that has to leave
     * the box alone queues its own [createPlayer]; without this, an earlier
     * rebuild's queued create could fire *after* a later one had already built
     * a player, and the field was overwritten while the older instance stayed
     * alive, unreferenced and decoding — a second video decoder on a box that
     * hands out one 4K decode per process, which is exactly the resource it
     * then refuses to the next source.
     */
    private var playerGeneration = 0

    /**
     * Wall clock of the last player release, or 0 for "none in this activity".
     * The source-switch grace period is counted from here rather than from the
     * moment a rebuild was asked for, so two switches in quick succession do
     * not rebuild on top of each other (see [rebuildSettleRemainingMs]).
     */
    private var playerReleasedAtMs = 0L

    // State
    private val handler = Handler(Looper.getMainLooper())

    /**
     * The OkHttp client every playback source and the guide prefetcher share.
     *
     * Hoisted out of [createPlayer] so the warm connection pool (DNS + TLS +
     * TCP) survives a source switch or a rebuild instead of being thrown away
     * with each player. OkHttpClient is built to be shared and nothing here is
     * per-source - the timeouts are static - so one instance is both correct
     * and the point.
     */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30L, TimeUnit.SECONDS)
            .readTimeout(60L, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Warms the channel under the guide's focus so the next tune opens on a live
     * socket instead of a cold handshake (see [LiveChannelPrefetch]). Shares
     * [httpClient], so the warm also carries into the player the tune builds.
     */
    private val liveChannelPrefetch: LiveChannelPrefetch by lazy {
        LiveChannelPrefetch(
            client = httpClient,
            resolveHeaders = { channel -> LiveChannelPrefetchRules.headersFor(channel) },
            currentChannelId = { currentZapChannel()?.channelId },
            // Only meaningful while the guide is up on a live session: a row
            // focus anywhere else is not a tune waiting to happen.
            canFire = { isGuideShowing && isLiveChannel }
        )
    }
    // Discovers Stremio-addon subtitles and merges them into the player as
    // sidecar text tracks (see AddonSubtitleController).
    private lateinit var addonSubtitleController: AddonSubtitleController
    /**
     * True while the shared overlay is up. The chrome owns its visibility now
     * (player_chrome.xml, [PlayerChrome]); this reads it, so every existing "is
     * the overlay up?" decision keeps the same answer.
     */
    private val controlsVisible: Boolean
        get() = chrome?.isVisible == true

    // Hold-to-scrub acceleration
    private var scrubDirection = 0  // -1 = back, 1 = forward, 0 = idle
    //
    // How long the current hold has been running, which is what the shared ramp
    // reads (see [ScrubAcceleration]). Deliberately elapsed time and not a
    // per-tick multiplication: the remote's repeat events do not carry a rate
    // this engine can trust, and the ramp has to look the same on a box that
    // sends a repeat every 40 ms as on one that sends one every 100 ms.
    private var scrubHeldMs = 0L
    /**
     * The position the viewer has scrubbed TO.
     *
     * Deliberately not the player's own position. A held scrub issues seeks
     * faster than a heavy stream settles them, so reading `player.currentPosition`
     * back (what this used to do) accumulated the next step from a position the
     * player had not actually reached. Owning the target here makes the scrub
     * advance at the rate the press asked for regardless of how slowly the seek
     * lands, and gives the release a position to land on exactly.
     */
    private var scrubTargetPosMs = 0L
    /** True once a scrub has moved the target, so an ending knows to land on it. */
    private var scrubMoved = false

    /**
     * Play state captured when a scrub starts, restored when it ends.
     *
     * A scrub pauses the player first (see [beginScrubPause]). That pause is the
     * whole reason the video can follow the bar: a paused seek has no real-time
     * playback deadline to miss, so an unconditional seek on every tick does not
     * turn into the stall an unthrottled scrub of PLAYING 4K did. The flag is
     * per-scrub - a scrub that never started never resumes - and it is cleared
     * the moment it is honoured (see [endScrubPause]).
     *
     * What is captured is the viewer's INTENT (`playWhenReady`), not the
     * momentary state: `isPlaying` is false while the player is buffering
     * (`playbackState != STATE_READY`), so a scrub taken during a rebuffer or a
     * slow seek restored a paused player and the viewer had to press play - the
     * "sometimes it resumes, sometimes it does not" field report.
     */
    private var wasPlayingBeforeScrub = false

    /** Whether a scrub is in flight, so a re-entry cannot re-capture the play state. */
    private var scrubInFlight = false

    /** Uptime of the last touch-drag follow-seek, this path's own throttle. */
    private var lastFollowSeekMs = 0L

    private val scrubHandler = Handler(Looper.getMainLooper())
    private val clockHandler = Handler(Looper.getMainLooper())
    // The overlay clock's two formatters. DateTimeFormatter is immutable and
    // thread-safe (unlike the SimpleDateFormat pair this replaced, which needed
    // a main-thread-only cache keyed by pattern), so these are built once per
    // process and reused. The 24-hour toggle picks between them per tick.
    private val clock12Format by lazy { DateFormats.clock12h() }
    private val clock24Format by lazy { DateFormats.clock24h() }
    private val clockRunnable = object : Runnable {
        override fun run() {
            if (controlsVisible) {
                updateClock()
                // Live: keep the program progress honest, and roll the
                // block over when the program ends.
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
            // Advance the target the viewer is scrubbing to, and move the bar
            // with it, at the rate the press asked for.
            scrubHeldMs += ScrubAcceleration.TICK_MS
            scrubTargetPosMs =
                (scrubTargetPosMs + ScrubAcceleration.stepFor(scrubHeldMs) * scrubDirection)
                    .coerceIn(0L, duration)
            updateSeekBarPosition(scrubTargetPosMs, duration)
            // The player was paused when the scrub began (see [beginScrubPause]),
            // so this seek has no playback deadline to miss: the flush-per-seek
            // cost remains, but ExoPlayer coalesces retargeted seeks and the tick
            // is the throttle. The old STATE_READY gate is what left the picture
            // behind the bar - it skipped every tick while the decoder refilled,
            // so on slow 4K content the bar moved and the video did not, which is
            // the bug this fixes. The bar still tracks the press, and [landScrub]
            // makes the release exact.
            player.seekTo(scrubTargetPosMs)
            scrubHandler.postDelayed(this, ScrubAcceleration.TICK_MS)
        }
    }

    // Fires once a D-pad key has been held past the quick-press window, switching
    // from a fixed 10s jump into continuous accelerated scrubbing.
    private val scrubHoldStarter = Runnable {
        if (scrubDirection == 0) return@Runnable
        scrubHeldMs = 0L  // The ramp starts here
        scrubHandler.post(scrubRunnable)
    }

    private var isInPiPMode = false
    private var showSettingsPanel = false

    // The sleep timer's rows, appended to the settings panel's own column the
    // same way the track / A-V rows are; null until the panel is built.
    private var sleepTimerSection: SleepTimerSection? = null

    // The sleep timer's current output gain: 1 while no fade is running, so the
    // ordinary case writes nothing to the player at all.
    private var sleepFadeGain = 1f
    // The track / A-V rows appended to that panel; see PlayerPanelSection.
    private var settingsPanelSection: PlayerPanelSection? = null
    private var isPickerShowing = false
    private var pickerMode = PickerMode.SOURCE

    /**
     * The row that last held the D-pad inside the picker.
     *
     * Kept so a focus that escapes the panel is handed back to the row the
     * viewer was ON rather than to the top - see [reassertPickerFocus]. Cleared
     * on dismiss, since a recycled row must not be focused after the panel is
     * gone.
     */
    private var pickerLastFocus: android.view.View? = null
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
    // This session's per-title key (the show's own id, else the history row's
    // id), resolved once from the launch intent. Null for a live channel, which
    // remembers nothing - the same rule the track bridge applies.
    private var titleKey: String? = null
    // internal: PlayerPanelSection reads this — the panel's own ± offset
    // buttons change it without going through the track bridge.
    internal var subtitleOffsetMs = 0
    private var subtitleSize = 1
    private var subtitleBackground = 0

    /** Clean SDH (Settings → Subtitles): see [SdhCaptionCleaner]. */
    private var cleanSdhCaptions = false
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

    /**
     * True while the attached sidecar is drawn by THIS app instead of by the
     * engine - the auto-fetched subtitle's path (see [attachExternalSubtitle]).
     *
     * Attaching a sidecar the ordinary way rebuilds the player, because the
     * track only exists once a MediaItem carries its SubtitleConfiguration. That
     * rebuild is what made an automatically fetched subtitle cost a re-open of
     * the stream mid-playback (a second or two of buffering, right after the
     * "Subtitles: …" toast). The auto-fetch path does not rebuild: it parses the
     * file into [externalSubtitleCues] and drives rendering from the playhead,
     * the same machinery a negative offset already used, so the picture is never
     * interrupted. A rebuild for any OTHER reason (a source switch, a background
     * return, the viewer picking a track) arms the real track and clears this
     * flag, at which point the engine renders the sidecar again.
     */
    private var sidecarRenderedByApp = false

    // --- ASS (SSA) sidecars ---
    //
    // media3 does not typeset ASS (google/ExoPlayer#8435, open since 2021) and
    // its SSA parser flattens a script into plain cues, so an .ass sidecar
    // loses its signs, fonts and positioning on the ExoPlayer engine - while
    // the MPV engine, whose libmpv statically links libass, renders the same
    // file correctly. When this build carries libassjni.so the sidecar is
    // rendered by libass here instead and drawn over the video surface; when
    // it does not, every path below falls back to the flattened cues the text
    // handler already renders.

    /** Which source currently owns the libass overlay, so one cannot clobber another's detach. */
    private enum class AssOverlaySource { NONE, SIDECAR, ADDON, EMBEDDED }

    /** The script being rendered. Survives a player rebuild; only the native instance does not. */
    private var assSubtitleContent: String? = null

    /** The overlay's current owner (sidecar whole-file, addon whole-file, or embedded streaming). */
    private var assOverlaySource = AssOverlaySource.NONE

    /** The addon URL the overlay shows, when [assOverlaySource] is ADDON. */
    private var assOverlayAddonUrl: String? = null

    /**
     * The claim last written to the log, so a repeat onTracksChanged is not a
     * new line. Cleared when the overlay lets go (see [detachAssSubtitle]).
     */
    private var assClaimLogged: String? = null

    /**
     * The one libass instance for this activity, shared by the whole-script
     * paths (sidecar/addon) and the streaming renderer (embedded), so the
     * overlay tick and the sample feed drive the same native state.
     */
    private val assRenderer: AssSubtitleRenderer by lazy { AssSubtitleRenderer() }

    /**
     * True while a script load is running off the main thread (see
     * [loadAssScript]). The tick must not start a second one, and must not
     * fall back either: until the load answers, "not loaded" does not mean
     * "will not load".
     */
    private var assLoadInFlight = false

    private lateinit var subtitleAssImage: ImageView

    // Stream health
    private var streamWidth = 0
    private var streamHeight = 0

    /**
     * The zap banner's identity line ("CH 101  •  LIVE  •  SPORT") without the
     * resolution. Kept so the line can be rebuilt the moment the new stream
     * tells the player its size: the banner paints at zap time, before the new
     * channel has a video track, and the outgoing channel's resolution must
     * never be shown against it.
     */
    private var zapLabelParts: List<String> = emptyList()
    private var streamBitrate = 0

    /**
     * Bytes fetched for the video track this session, the raw half of the
     * measured bitrate the info panel falls back to when the container declares
     * none (see [measuredBitrateBps]). Reset wherever the stream's identity is.
     */
    private var measuredVideoBytes = 0L
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
    // Resolved for this session in createPlayer: true when the app is decoding
    // the audio to PCM itself (decode-vs-passthrough), so the info panel can
    // state what the receiver is — or is not — being handed. A false value
    // only means passthrough is ALLOWED; the sink decides per track.
    private var audioDecodeToPcmActive = false
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

    /**
     * This session's rebuild allowance, keyed by failure cause (see
     * RebuildBudget.kt). Past the cap the ladder stops instead of rebuilding the
     * same component again: the field session this exists for spent nine
     * rebuilds on one file, each one asking a wedged decoder pool for a decoder
     * it could not hand out.
     */
    private val rebuildBudget = RebuildBudget()

    /**
     * This session's parallel fetch lanes, when the source is heavy enough to
     * have earned them (see StreamFetchLanes.kt). Null on the ordinary path —
     * most sources read through the shared single-stream client — and released
     * with the session either way.
     */
    private var lanePool: com.kennyb1201.kbstream.data.player.LanePoolDataSourceFactory? = null

    /**
     * The one pending retry rebuild. Held as a field so [scheduleRetry] can
     * drop a queued attempt before posting a new one: two media3 errors landing
     * together used to post two runnables and double-rebuild the player.
     */
    private val retryRunnable = Runnable {
        retryAttempt++
        // Counted where the rebuild actually happens: this is the only place
        // that spends the budget [scheduleRetry] consults.
        PlaybackRecoveryRules.failureCauseKey(lastPlaybackError)
            ?.let { rebuildBudget.record(it) }
        errorMessageStr = null
        recreatePlayer()
    }
    /**
     * The ladder has rolled over instead of parking on the error card (see
     * [retryLoopRung]): this is a live channel that has spent the ladder at
     * least once and is still reconnecting. Only the overlay's wording reads
     * it, because "Reconnecting... (7/6)" is not something to show a viewer.
     */
    private var liveRetryLooping = false
    private var decoderResourceFallbackDone = false
    /**
     * True once a decoder failure has had the ladder's one meaningful retry,
     * the raw-extractor probe (see [RAW_EXTRACTOR_PROBE_ATTEMPT]).
     *
     * A SECOND decoder failure after that is the decoder rejecting the FORMAT -
     * 10-bit AVC, VP9 profile 2, AV1 with no decoder on the box, an unsupported
     * Dolby Vision profile - rather than the container being mis-identified by
     * its URL, and no rebuild changes it. See onPlayerError.
     */
    private var decoderFailureRetried = false
    /**
     * True once a container-parsing failure has had its one raw-extractor
     * probe (see [PlaybackRecoveryRules.isContainerParseFailure]). The second one hands the session
     * to the backup engine instead of rebuilding the identical extractor.
     */
    private var containerParseRetried = false
    private var retryExhausted = false
    private var errorMessageStr: String? = null

    /**
     * The failure behind the card currently on screen, and the one already
     * written into the diagnostics export. Set from the error listener and read
     * when the card is shown ([recordShownFailure]) — the card is the moment the
     * viewer saw it, and identity comparison keeps a retry ladder that fails the
     * same way six times from filling the export with it.
     */
    private var lastPlaybackError: PlaybackException? = null
    private var recordedPlaybackFailure: PlaybackException? = null
    private var manualRetryToken = 0
    private var rebufferStartedAtMs = 0L

    // Adaptive source downshift (see RebufferDownshift.kt). The tracker holds
    // the current source's mid-playback rebuffers; the latch remembers that the
    // ladder is spent so a session with no rung left stops re-counting the
    // stalls it keeps producing.
    private val rebufferDownshift = RebufferDownshiftTracker()
    private var rebufferDownshiftGivenUp = false

    // When the last seek was processed, so a seek's own buffering is not read
    // as a starved source (see [rebufferFollowsSeek]). 0 = no seek this session.
    private var lastSeekAtMs = 0L

    // Startup cost breakdown, logged once per attempt at the first frame.
    // The "Rebuffer stall" line alone cannot say whether the seconds went into
    // loading the source or into the decoder's first frame, and any buffering
    // policy change for heavy 4K sources has to be based on that split.
    private var startupTraceStartMs = 0L
    private var firstReadyAtMs = 0L

    // Live-zap trace: set when a channel change starts (see [tuneToChannel]) and
    // consumed on the next STATE_READY, so the user-perceived zap - banner to
    // picture - is measured separately from [startupTraceStartMs]. That one is
    // anchored at createPlayer(), which says nothing after a light zap like the
    // one in [lightSwitchLiveChannel], because it never rebuilds the player and
    // so never re-enters the source-ready block.
    private var zapTraceStartMs = 0L

    // Black-video watchdog: some files reach READY with audio playing but the
    // video decoder never produces a frame (silent black screen, no error).
    // Track first-frame rendering and surface an actionable notice instead of
    // letting playback sit on black.
    private var videoTrackPresent = false
    private var streamMimeType: String? = null
    private var firstFrameRendered = false
    private var firstFrameRenderedAtMs = 0L

    /**
     * Set once a launch that reused a cached debrid link has had that link
     * forgotten, so a session that walks several errors forgets at most once.
     * See [invalidateCachedLinkBeforeFirstFrame].
     */
    private var linkCacheInvalidated = false
    private var blackVideoNoticeShown = false
    // True while either per-profile 8.1 conversion (P5/P7) is active so the
    // codec badge can report "DV P7 → 8.1" instead of "→ HDR10".
    private var dvTo81Session = false

    // This session's resolved HDR10-base handling, stored where the extractors
    // factory is built from the SAME inputs it is built with (see
    // selectHdr10BaseHandling) - one source of truth, no second opinion that
    // could drift from the arm actually running.
    //
    // STRIP is the no-information default: nothing reads this before that site
    // runs, so an untouched field can only mean "no special handling", never
    // an arm the player did not pick.
    private var dvSessionHandling: Hdr10BaseHandling = Hdr10BaseHandling.STRIP

    // True while the CURRENT video track is playing through the keep-DV strip
    // arm (STRIP_RPU_KEEP_DV): the track stays declared Dolby Vision while its
    // RPU/EL is dropped, so the decoder falls back to the HDR10 base layer.
    // Read by the codec badge to annotate that fallback; the arm is
    // session-level, but only a declared-DV track takes it, which is why the
    // predicate re-checks the track's own codec.
    private var dvRpuStripKeepDvActive = false
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
    // 12s was too impatient for a high-bitrate source on a slow line: its
    // fill rate can sit below 1x for a minute without the host being dead,
    // and the old value surfaced a hard error while data was still arriving.
    // 30s matches the VOD buffer duration target, and four recoveries (was
    // two) give a genuinely slow host room to catch up before the error.
    private val stallNoProgressMs = 30_000L
    private val stallMaxRecoveries = 4
    private val stallTickMs = 2_000L

    // Live watchdog: the stall watchdog above deliberately skips live
    // channels, and the retry ladder only runs off a hard error - so a channel
    // whose server simply stopped sending had nothing watching it at all, and
    // the picture froze on the last frame with no error and no retry. Left
    // running overnight (how a live channel is actually watched: falling
    // asleep to it) that is the reported morning state. This watches the same
    // progress the VOD watchdog does and hands a quiet session to the
    // reconnect ladder, which rebuilds against the same URL - a re-tune, which
    // is what zapping away and back did by hand. See [liveWatchdogAction].
    private var liveWatchdogToken = 0
    private var liveLastProgressAtMs = 0L
    private var liveLastPositionMs = -1L
    private var liveLastBufferedMs = -1L

    // History
    private var isLiveChannel = false

    /**
     * Non-null when this session is playing a DOWNLOAD rather than a stream.
     *
     * Set from [DownloadsManager.EXTRA_DOWNLOAD_ID]. It is the whole of this
     * session's difference from a streamed one: the url is the same kind of url
     * (the one the download was taken from), and the bytes come out of the
     * download cache instead of the network - see [createPlayer]'s data source
     * and [newMediaItemBuilder]'s cache key. Everything else - title metadata,
     * resume, history, scrobbling, the end-of-episode panels - is exactly the
     * path a streamed play takes (spec §6).
     */
    private var downloadId: String? = null

    /**
     * The cache key this download's bytes were written under, for the MediaItem.
     *
     * A progressive download is cached under a key of ours (the download id) so
     * two rows for one title cannot share an entry; an adaptive one is cached by
     * url and must carry no key at all. Null therefore means "address the cache
     * by url", which is right for both a segmented stream and every streamed
     * play.
     */
    private var downloadCacheKey: String? = null

    /**
     * The YouTube video id this session is playing as a TRAILER, or null for
     * ordinary playback (see [TrailerPlayerLauncher.EXTRA_TRAILER_VIDEO_ID]).
     *
     * Only used to classify a player-visible 403: in a trailer session the
     * stream is a googlevideo URL signed for a cached watch config, so a
     * refusal is the identity being stale - not this device failing to decode
     * something - and the next resolve is only worth making with a fresh one.
     */
    private var trailerVideoId: String? = null

    /**
     * Whether this trailer session has already spent its one client-exclusion
     * re-resolve (see [refreshIdentityOnTrailerRefusal]).
     *
     * Per session, so a replacement that dies too takes today's path instead of
     * walking client after client on a two-minute clip.
     */
    private var trailerRefusalResumed = false

    // Zap banner (CH+/CH- channel info overlay) — bound in bindViews(),
    // populated by showZapBanner() during a live-channel zap.
    private var zapBanner: View? = null
    private var zapLogo: ImageView? = null
    private var zapChannelLabel: TextView? = null
    private var zapChannelName: TextView? = null

    /**
     * The channel the visible zap banner is about, by GUIDE ID.
     *
     * The id, not the name on [zapChannelName]: a lineup can hold two entries
     * with the same name (a duplicated local feed, the same network from two
     * groups), and a slow EPG read that guarded on the name could repaint the
     * banner with the OTHER entry's now/next (audit E-I7, 2026-10-27). Null
     * until a zap has raised a banner.
     */
    private var zapChannelId: String? = null
    private var zapNowTitle: TextView? = null
    private var zapNowMeta: TextView? = null
    private var zapNowProgress: ProgressBar? = null
    private var zapNowDesc: TextView? = null
    private var zapNextTitle: TextView? = null

    /**
     * Live-only program block inside the controls overlay: what is on NOW
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

    /** Overlay GUIDE button (live, with a lineup — see [updateControlsInfo]). */
    private var btnGuide: TextView? = null

    /**
     * The in-player channel guide: a browsable overlay of the zap lineup with
     * each channel's now/next. A side panel like the picker, so it shares the
     * picker's scrim and its focus hand-back on close.
     */
    private var channelGuideContainer: LinearLayout? = null
    private var channelGuideTitle: TextView? = null
    private var channelGuideList: RecyclerView? = null
    private var isGuideShowing = false
    private var channelGuideJob: kotlinx.coroutines.Job? = null

    /**
     * Re-reads the guide while the overlay is open, whenever the guide data
     * changes underneath it.
     *
     * The overlay paints once. A viewer who opens it before the guide import
     * that was already running has finished — the case where they clicked into
     * a channel straight from a guide that was still filling in — used to keep
     * that first, empty paint for as long as the overlay stayed up, so the
     * guide looked permanently blank even after the data landed a minute
     * later. Watching [GuideRevision] (bumped only by a successful import) and
     * re-reading on a change fills the rows in place instead of making the
     * viewer close and reopen the guide.
     */
    private var channelGuideWatchJob: kotlinx.coroutines.Job? = null

    /**
     * Guide channel ids this player resolved itself, keyed by lineup channel
     * id, for entries the guide screen published with no match (it had not
     * matched them to an imported guide yet). Cleared and retried whenever a
     * new guide import bumps [GuideRevision].
     */
    private val guideResolvedChannelIds = HashMap<String, String>()

    /** Lineup channels already resolved at [guideResolveRevision]. */
    private val guideMatchAttempted = HashSet<String>()
    private var guideResolveRevision = -1L

    /**
     * Used to resolve a missing guide match; reads the guide database and the
     * revision-cached snapshot, so it holds the same view the guide screen does
     * without a second copy of the matching rules.
     *
     * The SHARED instance on purpose: it is the guide screen's, whose snapshot
     * is already built - a private one would hold a second copy of every
     * channel of the same guide for as long as the player is up.
     */
    private val iptvRepository by lazy {
        IptvRepository.shared(applicationContext)
    }

    /**
     * Long-press OK opens the guide, timed here rather than read off KeyEvent
     * repeats: the first OK press raises the controls overlay and hands focus
     * to it, so the repeats that make a hold a hold never reach the surface's
     * key listener. Dispatching at the Activity sees the hold regardless of
     * which view ends up with focus.
     */
    private val channelGuideHandler = Handler(Looper.getMainLooper())
    private var guideLongPressArmed = false
    private val guideLongPressRunnable = Runnable {
        if (guideLongPressArmed) {
            guideLongPressArmed = false
            showChannelGuide()
        }
    }

    /**
     * "LIVE  •  CH 5  •  SPORTS" prefix of the block's status line. Kept
     * beside the program's own air window because the prefix describes the
     * channel (fixed for as long as it plays) while the window changes with
     * every program.
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
        if (!isDestroyed) KbViewMotion.fadeOut(zapBanner)
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

    private val zapTimeFormat by lazy { DateFormats.clock12h() }

    /** A zap banner time in the device's own 12-hour clock. */
    private fun zapTime(millis: Long): String =
        DateFormats.time(millis, zapTimeFormat)

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
     * The live previous-channel toggle: back to the channel that was playing
     * before this one, and back again on the next press. This is the channel
     * the viewer came from, which is not the previous *position* in the lineup
     * (that is [zapByOffset]) - pick A, pick B, and bounce between them.
     *
     * Returns false when there is nothing to go back to: no channel watched
     * before this one, or the one that was has since left the lineup.
     */
    private fun recallLastChannel(): Boolean {
        val targetId = ChannelRecall.target() ?: return false
        val index = LiveChannelZapRegistry.indexOfChannel(targetId)
        if (index < 0) {
            // The channel we came from is gone from the playlist: drop the
            // armed target rather than re-resolving it on every press.
            ChannelRecall.clearTarget()
            return false
        }
        val channel = LiveChannelZapRegistry.channelAt(index) ?: return false
        tuneToChannel(index, channel)
        return true
    }

    /**
     * Switch to a channel from the zap lineup and report it in the banner.
     * Shared by UP/DOWN zapping, by typed channel numbers and by the
     * previous-channel recall so all three land the user the same way.
     */
    private fun tuneToChannel(index: Int, channel: LiveChannelZapRegistry.ZapChannel) {
        // A channel with no stream URL cannot be tuned: switching to an empty
        // URL blanks the player and raises a source error. Refuse before the
        // banner and the recall state change, so the session stays on the
        // channel it is already showing.
        if (channel.streamUrl.isBlank()) {
            Log.w(TAG, "Channel ${channel.channelId} has no stream URL; ignoring zap")
            return
        }
        zapChannelIndex = index

        // Diagnostics: was this channel warmed by the guide prefetch? The hit
        // rate (warm zaps / all zaps) is the whole point of tracking it - see
        // LiveChannelPrefetch and Diagnostics.playbackLine.
        com.kennyb1201.kbstream.data.reporting.PerfTrace.record(
            "live.prefetch_warm",
            if (liveChannelPrefetch.recentlyWarmed(channel.channelId)) 1L else 0L
        )

        // The zap clock starts here, before the banner paints, so the span
        // covers everything the viewer waits through. Consumed on the next
        // STATE_READY (see createPlayerListener).
        zapTraceStartMs = System.currentTimeMillis()

        // Every live channel change comes through here, so this is where the
        // previous-channel pair is kept: the channel being left becomes the
        // recall target and this one becomes the channel playing now.
        ChannelRecall.onTuned(channel.channelId)

        // The outgoing channel's size is not this channel's. Clear it before
        // the banner paints, or a 1080p -> 720p zap would name the old
        // resolution until the new track arrives; onTracksChanged paints the
        // real one into both live overlays.
        streamWidth = 0
        streamHeight = 0
        // Same reasoning for the rate: the outgoing channel's declared bitrate
        // must not stand in for the incoming one's.
        streamBitrate = 0
        measuredVideoBytes = 0L

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
        } else {
            // Re-tuned to the channel already playing: no load, so no READY to
            // measure. Drop the clock rather than leave it to time some later,
            // unrelated READY.
            zapTraceStartMs = 0L
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
            !isGuideShowing &&
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
        zapLabelParts = buildList {
            if (channel.chno?.isNotBlank() == true) add("CH ${channel.chno}")
            add("LIVE")
            scopeLabel?.let(::add)
        }
        renderZapChannelLabel()
        zapChannelName?.text = channel.name
        zapChannelId = channel.channelId
        if (channel.logoUrl.isNullOrBlank()) {
            zapLogo?.setImageDrawable(null)
        } else {
            zapLogo?.load(channel.logoUrl)
        }

        // One key per channel and source set, shared with the overlay's
        // program block: read through the same accessor, so a channel the
        // banner has already read costs the block nothing.
        val cacheKey = zapEpgCacheKey(channel)
        val cached = zapEpgCache[cacheKey]

        // Synchronous paint with whatever we already know.
        applyZapEpg(cached)
        // Rises and fades on every zap: channel-surfing is the surface this
        // shows up on, and each hop used to hard-cut the banner in.
        KbViewMotion.fadeIn(banner)

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
            // playing URL and the channel's ID must match: the ID is what the
            // cache key above is built from, and names are NOT unique across
            // playlist groups, so comparing the name here could apply one
            // entry's now/next to the other's banner (audit E-I7, 2026-10-27).
            if (zapBanner?.visibility == View.VISIBLE &&
                zapChannelId == channel.channelId &&
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
     * overlay's live program block, so both always report the same
     * programs for the same channel.
     */
    private suspend fun resolveZapEpg(
        channel: LiveChannelZapRegistry.ZapChannel,
        cached: ZapEpgInfo?
    ): ZapEpgInfo {
        val epgSources = guideSourcesOf(channel)
        // The same resolved match the guide overlay uses: a channel clicked
        // before the guide screen matched it has no epgChannelId of its own.
        val publishedId = guideChannelIdFor(channel)
        // ...and a surface that reads one RESOLVES a missing match itself,
        // exactly as the overlay's rows do. The guide screen publishes a
        // channel's match only for the channels it has loaded - it matches them
        // in batches as they scroll past - so the banner and the overlay's
        // program block used to report "No guide data" for every OTHER channel
        // of the same lineup while the guide screen, which matches each channel
        // it is asked about, was fully populated.
        if (publishedId.isNullOrBlank() && epgSources.isNotEmpty()) {
            resolveMissingGuideMatches(listOf(channel))
        }
        val epgChannelId = guideChannelIdFor(channel)
        val justResolved = publishedId.isNullOrBlank() && !epgChannelId.isNullOrBlank()
        val now = System.currentTimeMillis()
        val isFresh = cached != null &&
            now - cached.fetchedAtMillis < ZAP_EPG_TTL_MS &&
            // An empty snapshot taken before the match was known is not an
            // answer about this channel, so it may not stand in for one.
            !(justResolved && cached.now == null)
        val noSource = epgSources.isEmpty() || epgChannelId.isNullOrBlank()
        // isNullOrBlank() above already contract-proved epgChannelId non-null
        // whenever noSource is false, so an explicit null check on the third
        // operand here was unreachable (the compiler said so). The local val is
        // what carries that smart cast into the branch below.
        val resolvedEpgChannelId = epgChannelId
        return if (isFresh || noSource) {
            cached ?: ZapEpgInfo(now = null, next = null, fetchedAtMillis = now)
        } else {
            withContext(Dispatchers.IO) {
                loadZapEpg(epgSources, resolvedEpgChannelId)
            }
        }
    }

    /**
     * Queries the guide DB for the channel's current + next program, across
     * EVERY configured source.
     *
     * A channel's programs are stored under whichever guide matched it (the
     * DAO resolves a source by URL), and the guide screen merges every source
     * for exactly this reason. Reading only the primary one is what made a
     * channel matched in a secondary guide report "No guide data" on the
     * banner while the guide screen showed its programs. Overlapping sources
     * can import the same slot twice, so the merged rows are deduped on their
     * air window.
     */
    private suspend fun loadZapEpg(epgSources: List<String>, epgChannelId: String): ZapEpgInfo {
        return try {
            val dao = IptvDatabase.getInstance(applicationContext).iptvDao()
            val now = System.currentTimeMillis()
            // The importer stores programs under a lowercased channel key
            // (epgProgramChannelKey) and these queries match it exactly, so
            // the guide channel's raw id returns nothing whenever it has an
            // uppercase letter -- a matched channel with an empty banner.
            val channelKey = epgProgramChannelKey(epgChannelId)
            val rows = ArrayList<EpgProgramRow>()
            for (source in epgSources) {
                // Full variant (not the Lite one): it returns the real
                // category/description columns, which the banner shows.
                rows += dao.getProgramsForChannelsInWindow(
                    sourceUrl = source,
                    channelIds = listOf(channelKey),
                    windowStart = now,
                    windowEnd = now + ZAP_EPG_LOOKAHEAD_MS,
                    perChannelLimit = ZAP_EPG_ROW_LIMIT
                )
            }
            val merged = rows.distinctBy { row ->
                Triple(row.channelId, row.startUtcMillis, row.endUtcMillis)
            }
            ZapEpgInfo(
                now = merged.firstOrNull { row ->
                    now >= row.startUtcMillis && now < row.endUtcMillis
                },
                next = merged.firstOrNull { row -> row.startUtcMillis >= now },
                fetchedAtMillis = now
            )
        } catch (t: Throwable) {
            Log.w(TAG, "ZAP EPG lookup failed: ${t.message}")
            ZapEpgInfo(now = null, next = null, fetchedAtMillis = System.currentTimeMillis())
        }
    }

    /** Paints one EPG snapshot into the banner views. */
    /**
     * The resolution being DECODED, named the way the Info panel names it
     * ("4K", "1080p", "480p"), or null until a video track reports its size.
     * Live channels differ here even within one provider, so this is the only
     * honest answer to "what are we showing?".
     */
    private fun streamResolutionLabel(): String? =
        normalizeResolution(streamWidth, streamHeight)
            .takeIf { it != "—" }

    /** Zap banner identity line, resolution included once it is known. */
    private fun renderZapChannelLabel() {
        if (zapLabelParts.isEmpty()) return
        zapChannelLabel?.text = buildList {
            addAll(zapLabelParts)
            streamResolutionLabel()?.let(::add)
        }.joinToString("  •  ")
    }

    /**
     * Repaints the live overlays that carry the resolution when it arrives or
     * changes (a channel switch, or an adaptive ladder step). No-ops when the
     * banner or the block is not on screen.
     */
    private fun refreshResolutionLabels() {
        if (!isLiveChannel) return
        if (zapBanner?.visibility == View.VISIBLE) renderZapChannelLabel()
        if (liveProgramBlock?.visibility == View.VISIBLE) refreshLiveProgramBlock()
    }

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
            append(zapTime(now.startUtcMillis))
            append(" – ")
            append(zapTime(now.endUtcMillis))
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
            "Next  " + zapTime(next.startUtcMillis) + "  " + next.title
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
     * prompt - owns the remote, which is when the channel keys work: UP/DOWN
     * and CH+/CH- step through the lineup, and LEFT/RIGHT recall the channel
     * watched before this one.
     * One rule, read by both the surface's key listener and the activity's
     * [onKeyDown] fallback, so a channel press can never work in one place and
     * silently do nothing in the other.
     */
    private fun liveZapKeysFree(): Boolean =
        isLiveChannel &&
            !controlsVisible &&
            !showSettingsPanel &&
            !isPickerShowing &&
            !isGuideShowing &&
            errorContainer.visibility != View.VISIBLE &&
            btnSkipIntro.visibility != View.VISIBLE &&
            LiveChannelZapRegistry.zapEnabled()

    /** Cache key a channel's now/next rows are stored under. */
    private fun zapEpgCacheKey(channel: LiveChannelZapRegistry.ZapChannel): String =
        channel.channelId + "|" + guideSourcesOf(channel).joinToString(",")

    /**
     * Paints the overlay's live program block for the channel playing now.
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
            // guide): there is no guide id to look a program up with.
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
     * One now/next snapshot into the overlay's live block: program title,
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
                "No program data"
            }
            liveProgramStatus?.text = buildList {
                add(liveProgramScope)
                streamResolutionLabel()?.let(::add)
            }.joinToString("  •  ")
            liveProgramProgress?.visibility = View.GONE
            liveProgramDesc?.visibility = View.GONE
            liveProgramDesc?.text = ""
            liveProgramNext?.text = if (info == null) "" else "No guide data for this channel"
            return
        }

        liveProgramTitle?.text = now.title
        liveProgramStatus?.text = buildList {
            add(liveProgramScope)
            streamResolutionLabel()?.let(::add)
            add(
                zapTime(now.startUtcMillis) +
                    " \u2013 " +
                    zapTime(now.endUtcMillis)
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
            "Up next  " + zapTime(next.startUtcMillis) + "  " + next.title
        } ?: if (fresh) "No guide data for what follows" else ""
    }

    /**
     * Per-second tick while the overlay is up: advance the program progress
     * bar, and roll the block over to the next program once the current one
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

        // Nothing resolved, or the program just ended. Re-read only once the
        // cached row is older than the TTL, so a channel whose guide has
        // nothing for this slot cannot turn the per-second tick into a
        // per-second query.
        if (info == null || nowMs - info.fetchedAtMillis >= ZAP_EPG_TTL_MS) {
            refreshLiveProgramBlock()
        }
    }
    private var parentId = ""
    private var parentType = ""

    /**
     * The profile this playback session belongs to, pinned at launch and
     * carried in the launch intent (see [PlaybackHistoryWriter]). Read from
     * the launch intent rather than the active profile so that switching
     * profiles during playback - PiP makes that routine - cannot make this
     * session file its progress into the other profile's Continue Watching.
     */
    private var sessionProfileId: String? = null
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

    /**
     * The add-on behind each entry of [sources], same order and same length
     * (null when unknown). Read from the `source_addons` launch extra, which
     * arrives beside `sources_json`; it is what lets [tryNextSource] skip the
     * rest of an add-on whose links are dead for this session
     * (see [SourceAddonSession]).
     */
    private var sourceAddons: List<String?> = emptyList()

    /**
     * The add-on TAB the streams picker had selected when this source was
     * pressed, or null when the launch had none (All, or no picker at all).
     * Read from the `selected_addon` launch extra, which arrives beside
     * `sources_json`, so a session that switched here from MPV carries the
     * scope of the pick it came from.
     *
     * This engine has no MANUAL "next source" entry point: all four
     * [tryNextSource] call sites are automatic recoveries, so each names the
     * merged scope explicitly and this field is deliberately not consulted for
     * them - a session that started inside one tab must still recover across
     * add-ons. The one manual press belongs to the MPV engine's error card (see
     * its own copy of this field).
     */
    private var selectedAddon: String? = null

    /**
     * Addons whose links failed to OPEN in this session (normalized names).
     *
     * Session-scoped: a new playback session starts empty. Deliberately NOT
     * persisted - [com.kennyb1201.kbstream.data.player.SourceAddonMemory] owns
     * the cross-session record; this owns the next ten seconds. Filled by the
     * pre-playback probe before the first load, and by the open-failure path
     * during playback.
     */
    private val sessionDeadAddons = mutableSetOf<String>()

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

    /*
     * One file is not one TMDB episode.
     *
     * Paw Patrol season 1 is 47 eleven-minute segments on TMDB and 26 files
     * holding two of them each; CatDog is the other way round - 20 TMDB
     * episodes, 11 files. The episode number is what every label, history row
     * and tracker push speaks, and the FILE number is what the addons resolve,
     * so the session walks two cursors and this is the mapping between them
     * (see EpisodeScheme). It is DETECTED from the file that played against
     * the episode's own TMDB runtime and remembered per show, because the
     * app's arithmetic - one file, one episode - drifted further off with
     * every file of a binge and left every second segment unmarked.
     *
     * `season`/`episode` stay TMDB numbering everywhere, which is why the
     * panels, the history row and the Up Next labels needed no change beyond
     * this. The FILE the viewer is on is not a second counter to keep: it is
     * the trailing number of [episodeStreamId], which by the id invariant is
     * always file numbering (see currentFileEpisode).
     */
    private var bingeScheme: EpisodeScheme = EpisodeScheme.ONE_TO_ONE

    /** The file detection already ran against, so it runs once per file. */
    private var schemeDetectedForFileId: String? = null

    /** The scheme store's key for this show, resolved once per session. */
    private var schemeStoreKey: String? = null

    /** The launch's own TMDB runtime for this episode, in minutes. */
    private var launchRuntimeMinutes: Int? = null

    /** TMDB's runtime for THIS episode, resolved when the launch carried none. */
    private var currentEpisodeRuntimeMinutes: Int? = null
    private var currentEpisodeRuntimePrefetched = false

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

    /**
     * True when [onStop] took this screen's player down with it.
     *
     * [onStop] here is the viewer leaving the APP, not leaving the title: on a
     * TV the remote's Home button stops this Activity while the task (and this
     * instance) survive, and the release it does is deliberate - the box hands
     * out one 4K decode per process, so a backgrounded player holding it is
     * what leaves the Home hero's pooled trailer with no decoder to prepare in.
     * What was missing is the other half: [onStart] has to know a rebuild is
     * owed, rather than reading its own first call as a launch.
     */
    private var playerTornDownAtStop = false
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

    /// True once this session has been handed to an installed external player.
    /// One handoff per session, for the same reason the MPV flag above is one:
    /// a second one would start a second player on top of the first.
    private var externalHandoffStarted = false

    /**
     * Result of an external handoff (see [handOffToExternal]). Same contract as
     * [mpvFallbackLauncher]: this activity stays in the chain and forwards what
     * the wrapper decided, so MainActivity's player-result callback still fires
     * and "next episode" keeps working across the switch.
     */
    private val externalLauncher = registerForActivityResult(
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

    /**
     * True once this session's player declared the episode over, and never
     * cleared again for the life of the activity.
     *
     * [playbackEndedHandled] is the PLAY guard, and it is deliberately cleared
     * by a seek so a viewer who rewinds the ending can press play and watch it
     * again (PB-P2-1). The fact that the EPISODE finished is not the same fact,
     * and riding on that flag is what lost it the moment the playhead moved: a
     * press that reached the controls underneath the credits panel scrubbed the
     * finished episode backwards, the seek cleared the guard, and the exit then
     * filed a RESUME row over the completion. That is the reported "it didn't
     * mark it as watched since it started over" - and it is also what put the
     * finished episode straight back on Continue Watching. This is the sticky
     * half: the completion verdict below is written in terms of it, so within
     * one session a rewatch of an episode that already ended leaves the row
     * completed. A fresh session is a fresh activity, so nothing is inherited.
     */
    private var playbackEndReached = false

    /**
     * True once a COMPLETED row for this session has been written to watch
     * history, and never cleared again for the life of the activity.
     *
     * [playbackEndReached] says the FILE ended. That is not the same fact as
     * "the completion was filed": the 90% threshold in [saveProgress] completes
     * an episode the file never actually reached the end of - a source that
     * stops short, a stream whose last minutes are missing, a viewer who
     * crossed the threshold and backed out - and the exit/pause saves that
     * followed re-decided the verdict from the playhead alone. A viewer who
     * paused six minutes from the end, resumed, watched past the threshold and
     * then parked on a rewind filed a RESUME row over the completion: the
     * episode lost its watched marker and Continue Watching offered to resume
     * the ten minutes it had already finished. Once a completion is filed the
     * episode is done for this session, whatever happens to the playhead - so
     * every later save in the session is judged as completed too and can never
     * write a resume row back over it (see [shouldRecordCompletion]'s
     * `completionFiled` arm, which the handoff and exit paths pass).
     */
    private var completionFiled = false

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
    // The next episode's synopsis, resolved from the same TMDB lookup the
    // panel's name/still already use. Carried into the handoff so the next
    // session's overlay does not open on the FINISHED episode's text.
    private var pendingNextEpisodeOverview: String? = null
    private var nextUpCountdownRemaining = 0

    // True when the Up Next panel opened while the episode still had real time
    // left: the auto-advance countdown waits for the end of the episode rather
    // than running from the moment the early panel appeared.
    private var nextUpCountdownHeld = false

    /**
     * True while the Up Next card is THIS session's end-of-episode decision,
     * i.e. a next episode was actually resolved and offered.
     *
     * The auto-advance countdown and that card's PLAY button both require it.
     * [pendingNextSeason] / [pendingNextEpisode] are only ever written by
     * [showNextUpPanel], but they used to SURVIVE into a later decision: the
     * credits card (because-you-watched) is exactly what is shown when there is
     * no next episode, and a held countdown still fired with those stale
     * fields, resolving - and trying to play - an episode that does not exist,
     * right underneath that panel.
     */
    private var nextUpHandoffArmed = false

    /**
     * Latched the first time this session hands playback to the next episode.
     *
     * The still-there confirmation (the card's PLAY NEXT) and the auto-advance
     * countdown are two triggers for the same card, and a remote's media-NEXT
     * fires alongside either. They all end in [launchNextEpisode], whose only
     * job is to hand off ONCE: without this latch a second trigger re-persisted
     * the handoff and finished again, which started the same next episode a
     * second time - the "autoplay played the same episode twice" report.
     */
    private var nextEpisodeHandoffStarted = false
    private val nextUpCountdownHandler = Handler(Looper.getMainLooper())
    private val nextUpCountdownRunnable = object : Runnable {
        override fun run() {
            if (!nextUpHandoffArmed) {
                nextUpCountdownHeld = false
                nextUpCountdownRemaining = 0
                return
            }
            // A sleep timer armed while this countdown was already running (the
            // card opens during the credits, so that is the ordinary way the two
            // meet) wins: the countdown stops here instead of handing off seconds
            // before the end the timer is waiting for. Re-checked every tick, so
            // the timer is honored whenever it is armed - up to the last second.
            if (sleepTimerBlocksAutoAdvance(SleepTimer.state.value)) {
                nextUpCountdownHeld = false
                nextUpCountdownRemaining = 0
                nextUpCountdown.text = "Stopping here - sleep timer"
                return
            }
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
                    pendingNextEpisodeRuntime,
                    pendingNextEpisodeOverview
                )
            } else {
                nextUpCountdown.text = "Playing next in $nextUpCountdownRemaining"
                // Drains as the wait shrinks; max is the full countdown.
                nextUpCountdownBar.progress = nextUpCountdownRemaining
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
    /**
     * Attaches a sidecar subtitle file.
     *
     * [rebuild] = false is the AUTO-FETCH path: the file is parsed and drawn by
     * the app (see [sidecarRenderedByApp]) instead of being armed into a new
     * MediaItem and re-opened, because that re-open stalled the picture for a
     * second or two in the middle of playback - a subtitle arriving on its own
     * must not cost a rebuffer. Nothing can be lost by not arming the track: the
     * cue pipeline was only rendering it, and this path renders the same cues
     * from the playhead (offsets included), which the negative-offset mode has
     * always done for an external file.
     */
    private fun attachExternalSubtitle(uri: Uri, rebuild: Boolean = true) {
        externalSubtitleUri = uri
        // Remembered per video (not per show: a sidecar file belongs to one
        // episode) so reopening it re-attaches automatically. Profile-scoped
        // and device-local — the URI only resolves on this device.
        PlayerTrackMemory.rememberSubtitle(
            context = this,
            key = subtitleMemoryKey(),
            uri = uri.toString()
        )
        // Set BEFORE the parse: its answer is what starts the position tick
        // (loadExternalSubtitleCues → updateFromPosition), and the tick only
        // runs when this flag or a negative offset asks it to.
        sidecarRenderedByApp = !rebuild
        loadExternalSubtitleCues(uri)
        if (!rebuild) {
            Log.i(
                "PLAYER_SUB",
                "sidecar attached without a player rebuild: drawn from the playhead, " +
                    "no interruption"
            )
            return
        }
        // Frame-gated like the other carry writes: an attach before the first
        // frame must not replace the launch position with a broken clock read.
        if (firstFrameRendered) {
            carryPositionMs = exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: carryPositionMs
        }
        recreatePlayer()
    }

    /**
     * Stops drawing a sidecar this app rendered without arming a player track
     * (see [sidecarRenderedByApp]), because the viewer has just chosen something
     * else: OFF, or one of the embedded tracks.
     *
     * The remembered [externalSubtitleUri] is deliberately NOT cleared - exactly
     * as before, the file is remembered for this video and re-armed by the next
     * player rebuild. Only the drawing stops, and it stops at once: the
     * position-driven renderer asks the cue pipeline to stand down, so leaving it
     * running would paint the old sidecar over the track that was just picked.
     */
    private fun releaseAppRenderedSidecar() {
        if (!sidecarRenderedByApp) return
        sidecarRenderedByApp = false
        externalSubtitleCues = emptyList()
        // [releasePositionDriven], not [SubtitleCueHandler.cancelPending]: that
        // one exists for teardown and every caller discards the handler right
        // after it. This handler is KEPT - the pipeline's cues take the area
        // back on the very next group.
        subtitleCueHandler?.releasePositionDriven()
        if (assOverlaySource == AssOverlaySource.SIDECAR) detachAssSubtitle()
        Log.i("PLAYER_SUB", "app-rendered sidecar released: the viewer picked a track")
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

    /**
     * Downloads the picked subtitle into cache and attaches it sidecar-style.
     *
     * The failure sentence comes ready-made from the helper: an exhausted daily
     * quota, a rejected API key and an unreachable server need different
     * answers, and this used to show "Subtitle download failed" for all three.
     */
    private fun downloadOnlineSubtitle(hit: SubtitleSearchResult) {
        Toast.makeText(this, "Loading subtitle…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            when (val result = SubtitleSearchHelper.download(this@NativePlayerActivity, hit)) {
                is SubtitleDownload.Failed ->
                    Toast.makeText(this@NativePlayerActivity, result.reason, Toast.LENGTH_LONG).show()

                is SubtitleDownload.Ready -> {
                    // A 200 that is not a subtitle - a truncated file, a
                    // plain-text limit notice - used to be attached anyway: the
                    // player rebuilt for a track that never drew, and the
                    // viewer was told nothing. Refuse it out loud and leave the
                    // running player alone.
                    if (!SubtitleSearchHelper.isUsableSubtitleBody(
                            result.body,
                            AssSubtitleRenderer.available
                        )
                    ) {
                        Toast.makeText(
                            this@NativePlayerActivity,
                            "Subtitle download failed: the file had no readable subtitles",
                            Toast.LENGTH_LONG
                        ).show()
                        return@launch
                    }
                    val uri = SubtitleSearchHelper.toCacheUri(this@NativePlayerActivity, hit, result.body)
                    attachExternalSubtitle(uri)
                }
            }
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
            val imdbId = runCatchingCancellable {
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

        // And the no-logo state for the splash itself: the item name centered on
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
            val path = runCatchingCancellable {
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
     * overlay is down. On a live channel they recall the previous channel
     * instead of seeking (see [recallLastChannel]), which is the one other
     * meaning they can carry with the overlay down. See
     * [handleSurfaceScrubKey].
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
    // androidx.core marks ComponentActivity.dispatchKeyEvent @RestrictedApi
    // ("same library group"), which an app cannot satisfy however it calls it.
    // The calls below are `super`, from an override of the same method: the
    // ordinary way to see a key before the view tree does, and the skip-prompt
    // rules in the doc above depend on being ahead of it.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Long-press OK opens the channel guide. Timed with our own Handler
        // rather than read off KeyEvent repeats, because the first OK press
        // raises the controls overlay and hands focus to it - so the repeats
        // that make a hold a hold never reach the surface's key listener.
        // Resolving it here sees the hold whichever view ends up with focus,
        // and the short press keeps its usual meaning: the guide is an
        // addition to OK, not a replacement for it. Cancelled on key up, so a
        // press that ended - or that lost its meaning because a panel opened
        // mid-hold - cannot still pop the overlay.
        if (isConfirmKey(event.keyCode)) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0 && channelGuideCanOpen()) {
                        guideLongPressArmed = true
                        channelGuideHandler.removeCallbacks(guideLongPressRunnable)
                        channelGuideHandler.postDelayed(
                            guideLongPressRunnable,
                            CHANNEL_GUIDE_LONG_PRESS_MS
                        )
                    }
                }
                KeyEvent.ACTION_UP -> {
                    channelGuideHandler.removeCallbacks(guideLongPressRunnable)
                    guideLongPressArmed = false
                }
            }
        }
        // LEFT/RIGHT seek the video directly while nothing but the video (or a
        // skip prompt) is on screen, and raise nothing while doing it. This
        // hangs off the activity rather than the video surface's key listener
        // because the focused view is not always the surface: a skip prompt
        // holds focus while it is up, and a key it does not consume is never
        // re-offered to the surface's listener from there. Only an item that
        // can actually seek takes the key - live TV and a stream without a
        // duration fall through to whatever handled them before. For a live
        // channel that is the previous-channel recall: the press reaches the
        // channel-key handlers below (the surface's listener, then [onKeyDown])
        // and toggles back, which is why it must not be swallowed here.
        val horizontal = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        // The open channel guide owns LEFT/RIGHT: they step through the guide
        // groups. Resolved here, ahead of the scrub, because the guide can be
        // up while the controls are hidden - [plainPlaybackForeground] says
        // nothing about it - and a group change must never seek the video.
        if (isGuideShowing && horizontal) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                switchChannelGuideGroup(
                    if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                )
            }
            return true
        }
        // The credits recommendations own the D-pad while they are up - every
        // press that could be a browse press - and that rule cannot sit behind
        // [plainPlaybackForeground]: the panel is drawn over the video while the
        // controls overlay underneath it can still be up, and that overlay parks
        // focus on its SEEK BAR on purpose (see [focusControls]). LEFT/RIGHT on
        // the bar scrubs the video, so a press aimed at a pick moved the
        // playhead of the episode that had just finished and started it playing
        // again from there - the reported "it keeps replaying instead of
        // stopping" - and because a seek clears [playbackEndedHandled], leaving
        // then filed the finished episode as merely resumable. So the panel
        // answers first, ahead of the scrub and the overlay: the press is handed
        // to a pick when focus is already inside the panel, and swallowed while
        // the row is still being built instead of passed down to the bar.
        //
        // The RELEASE half of a LEFT/RIGHT press belongs to the panel for the
        // same reason: with the overlay up it would reach the seek bar instead,
        // whose release handler commits the bar's own position as a seek - and a
        // seek clears [playbackEndedHandled] and nudges the playhead off the end,
        // so the last seconds of the finished episode played again. A surface
        // scrub already in flight when the panel appeared is ended here, since
        // its own release never gets through.
        if (creditsPanelForeground() && horizontal &&
            event.action != KeyEvent.ACTION_DOWN
        ) {
            if (scrubDirection != 0) stopSurfaceScrub()
            return true
        }
        // The one confirm press the panel does NOT take: a visible skip prompt
        // owns OK while the chrome is down, and a SKIP CREDITS prompt appears
        // over these recommendations by design ([creditsPanelForeground] is
        // deliberately not folded into [plainPlaybackForeground], and the prompt
        // branch below is what keeps that press). Everything else the panel
        // answers for itself.
        val skipPromptOwnsConfirm =
            btnSkipIntro.visibility == View.VISIBLE && !controlsVisible
        if (creditsPanelForeground() && event.action == KeyEvent.ACTION_DOWN &&
            (horizontal ||
                isOverlayRaisingKey(event.keyCode) ||
                (isConfirmKey(event.keyCode) && !skipPromptOwnsConfirm))
        ) {
            if (!bywUi.hasFocus()) {
                // Focus is not guaranteed to be inside the panel yet: the row
                // fills in a beat after the panel opens, and a skip prompt hands
                // focus back to the video surface when it hides. Park it on the
                // first pick so the press steps through them - and when there is
                // no pick to park on yet, still swallow the press rather than let
                // it reach the overlay's seek bar underneath.
                bywUi.focusFirst()
                return true
            }
            // Focus is on a pick: UP/DOWN have nothing above or below the row to
            // move to (raising the overlay would also take the D-pad away from
            // the row mid-panel), so they are swallowed; LEFT/RIGHT and OK belong
            // to the focused pick and pass through to it.
            if (isOverlayRaisingKey(event.keyCode)) return true
        }
        if (plainPlaybackForeground() && horizontal &&
            handleSurfaceScrubKey(event.keyCode, event)
        ) {
            return true
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

    // GestureBackNavigation: this method wires the controls overlay's Back arm,
    // which closes the overlay a focused panel sits in rather than leaving the
    // film. An OnBackPressedCallback registered here makes the same decision
    // when the press reaches the dispatcher (see below); the view-level arm
    // pre-empts it for the live chrome. Moving the chrome's Back handling onto
    // the dispatcher is a device-tested change, tracked with the API-36 pass.
    @SuppressLint("GestureBackNavigation")
    override fun onCreate(savedInstanceState: Bundle?) {
        // A phone asks for landscape before the window exists - a title plays
        // sideways even though browsing follows the device; on TV this returns
        // without touching the activity (see FormFactor). The request is
        // released in onDestroy, so Home comes back in the viewer's hand.
        com.kennyb1201.kbstream.FormFactor.applyPlaybackOrientation(this)
        super.onCreate(savedInstanceState)
        // The window behind and around the picture. The theme's static
        // colorBackground cannot follow the AMOLED toggle, so an AMOLED install
        // showed the ordinary #0A0E14 void wherever the video surface does not
        // reach - the same mismatch MainActivity fixes for its launch window,
        // and a player is the screen where a navy edge is most obvious.
        applyPlayerWindowTone(this)
        // Bulk guide (EPG) writes wait for playback to end while this activity
        // is on screen: an XMLTV import re-keying thousands of rows underneath
        // a starting player is what turned into multi-hundred-millisecond GC
        // pauses and a multi-second rebuffer stall. Cleared in onStop, with
        // onDestroy as the safety net.
        EpgWriteGate.setPlayerActive(true)

        // This Activity owns the screen, so it owns its video decoder too. The
        // Home hero's pooled trailer player survives the composition - Home's
        // ON_STOP handler pauses it rather than stopping it - and a paused
        // ExoPlayer keeps its decoder allocated. This box hands out one 4K
        // decode per process (see PlaybackRecoveryRules), so leaving
        // that one held is what turned a source switch inside the player into
        // "out of video decoder resources" while starting the same source with
        // the pool already quiet played immediately. Stopping it costs nothing
        // here: Home re-resolves and re-preps its trailer from the pool when it
        // resumes.
        TrailerPlayerPool.releaseForReuse()

        // Kids Mode: a daily limit or bedtime that lands mid-film has to
        // end the playback it is counting. The lock overlay lives in
        // MainActivity, which sits behind this Activity, so without this
        // the film simply ran to the end - and the Up Next chain then
        // started the next episode.
        com.kennyb1201.kbstream.data.sync.KidsTimeGuard.enforceLock(this)

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
        // The bulk path attaches every track at once and used to fail
        // completely silently: one dead host meant no subtitles at all and no
        // explanation, which reads as "this title has none". Parity with the
        // MPV engine's per-tap failure toast. Only the ALL-failed case speaks -
        // a partial success is already visible in the SUBTITLES picker, which
        // is where the viewer looks anyway, and a toast over a working title
        // would be noise.
        addonSubtitleController.onBulkResult = { succeeded, _ ->
            if (succeeded == 0) {
                Toast.makeText(this, "Subtitles unavailable", Toast.LENGTH_SHORT).show()
            }
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
            if (com.kennyb1201.kbstream.FormFactor.isTv) {
                // The remote's path, byte-for-byte what it was: a press on the
                // picture toggles the chrome and every other event is swallowed.
                if (event.action == android.view.MotionEvent.ACTION_UP) {
                    if (controlsVisible) hideControls() else showControls()
                }
                true
            } else {
                // Phone: the gesture layer owns the picture (tap, drag, pinch,
                // hold) and returns false only while a panel is up, so a press
                // that lands on a panel is never eaten by the video behind it.
                playerTouchGestures?.onTouch(playerView, event) ?: true
            }
        }
        playerView.setOnClickListener { if (controlsVisible) hideControls() else showControls() }
        if (!com.kennyb1201.kbstream.FormFactor.isTv) attachPhoneTouchGestures()
        playerView.post { applyPlayerPortraitStage() }
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
                    // The "Up next" card is a question, not a destination: Back
                    // waves it away and leaves the session running. It used to
                    // fall through to finish(), so the one press a viewer makes
                    // to dismiss the card exited the player instead.
                    ::nextUpPanel.isInitialized && nextUpPanel.visibility == View.VISIBLE -> {
                        dismissNextUpPanel()
                        showControls()
                        return
                    }
                    isGuideShowing -> { dismissChannelGuide(); showControls(); return }
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
                    // A focused popup button's Back bubbles to this listener, so
                    // the "Up next" card is dismissed here too. It must not be
                    // swallowed into an exit: that card is not the session.
                    if (::nextUpPanel.isInitialized && nextUpPanel.visibility == View.VISIBLE) {
                        dismissNextUpPanel(); showControls(); true
                    } else if (isGuideShowing || isPickerShowing || showSettingsPanel || controlsVisible) {
                        // Only consume Back when there's actually something to
                        // dismiss (a panel, the settings sheet, or visible
                        // controls). Otherwise let it fall through to
                        // onBackPressed so a Back press always exits the player
                        // instead of being silently swallowed.
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
        sessionProfileId = PlaybackHistoryWriter.sessionProfileId(this, intent)
        isLiveChannel = parentType == "channel"
        // Arm the live previous-channel toggle: the channel this session opens
        // on is now the last-watched one, and whatever the last session (or the
        // last zap) was on becomes the channel LEFT/RIGHT goes back to. See
        // ChannelRecall - carrying the pair across sessions is what makes the
        // toggle work when the second channel was opened from the guide, which
        // is the normal way to pick it.
        if (isLiveChannel) {
            ChannelRecall.onTuned(parentId)
        }
        season = intent.getIntExtra("season", -1).takeIf { it >= 0 }
        // A launch carrying 0 as its episode is carrying "no episode": see
        // EpisodeNumbering. Read as one, the up-next line said "Season 2
        // Episode 00".
        episode = namedEpisodeNumber(intent.getIntExtra("episode", -1))
        episodeStreamId = intent.getStringExtra("episode_stream_id")
        itemName = intent.getStringExtra("item_name")
            ?.takeIf { it.isNotBlank() }
            ?: intent.getStringExtra("display_name").orEmpty()
        trailerVideoId = intent
            .getStringExtra(TrailerPlayerLauncher.EXTRA_TRAILER_VIDEO_ID)
            ?.takeIf { it.isNotBlank() }
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
        // A handoff from MPV carries the speed that session was playing at;
        // a fresh launch carries none and keeps 1x (see EXTRA_PLAYBACK_SPEED).
        intent.getFloatExtra(EXTRA_PLAYBACK_SPEED, -1f).takeIf { it > 0f }?.let {
            playbackSpeed = it
        }
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
        // A downloaded session (spec §6). The check is HERE, before anything
        // else is set up, because the failure it guards is the feature's whole
        // point: bytes the app no longer has must never be replaced by a
        // streamed fetch of the source url. A session that cannot find them says
        // so and leaves; the record is marked so the list offers a Retry instead
        // of offering Play again.
        downloadId = intent.getStringExtra(DownloadsManager.EXTRA_DOWNLOAD_ID)
            ?.takeIf { it.isNotBlank() }
        if (downloadId != null) {
            val record = DownloadsManager.playbackRecord(this, downloadId)
            if (record == null || !DownloadsManager.isPlayable(this, downloadId)) {
                downloadId?.let { DownloadsManager.markMissing(this, it) }
                Toast.makeText(
                    this,
                    "This download is no longer on this phone",
                    Toast.LENGTH_LONG
                ).show()
                finish()
                return
            }
            downloadCacheKey = DownloadsManager.playbackCacheKey(record)
        }
        titleKey = if (isLiveChannel) {
            null
        } else {
            PlayerTitlePrefs.titleKeyFor(parentId, historyId)
        }
        // Aspect is per-title like the languages: this show's own override
        // wins, and every title without one keeps following Settings' default.
        resizeModeIndex = PlayerTitlePrefs.get(this, titleKey)?.aspectRatio
            ?.takeIf { it >= 0 }
            ?: AppPreferences.getDefaultAspectRatio(this)
        // Applied in createPlayer(); no-op until the content frame exists.
        enableTunneling = AppPreferences.getEnableTunneling(this)
        bufferMode = AppPreferences.getDefaultBufferMode(this)
        subtitleSize = AppPreferences.getDefaultSubtitleSize(this)
        subtitleBackground = AppPreferences.getDefaultSubtitleBackground(this)
        cleanSdhCaptions = AppPreferences.getCleanSdhCaptions(this)
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
        // The subtitle MODE too, not only the languages: the bridge re-applies
        // languages on every ready transition (a rebuffer is enough), and a
        // session set to Off must not have a subtitle track armed behind it.
        PlayerTrackBridge.setSubtitleMode(AppPreferences.getSubtitleMode(this))
        PlayerTrackBridge.loadFor(
            context = this,
            titleKey = titleKey,
            globalAudioLanguage = globalAudioLang,
            globalSubtitleLanguage = globalSubtitleLang
        )
        preferredAudioLang = PlayerTrackBridge.audioLanguage.ifBlank { globalAudioLang }
        preferredSubtitleLang = PlayerTrackBridge.subtitleLanguage.ifBlank { globalSubtitleLang }
        subtitleOffsetMs = PlayerTrackBridge.subtitleOffsetMs
        // The audio offset is applied by [createPlayer], which builds the pair
        // that carries it: the processors are per-player now, so there is
        // nothing here to set it on yet (see [PlayerAudioProcessors]).
        audioProcessors = null

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
                // This activity's pair, never a process-wide one: a rebuild swaps
                // both, and the slider follows the player on screen.
                bridgeSelf.get()?.audioProcessors?.delay?.setDelayMs(ms)
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

        // The sleep-timer rows go into the same panel column, as their own
        // section. The choices are read through the activity rather than
        // captured here because they can change under the section: a live
        // channel only gains "End of program" once the guide has a block end.
        sleepTimerSection = SleepTimerSection(
            activity = this,
            optionsProvider = { sleepTimerChoices() },
            onSelect = { option -> chooseSleepTimer(option) }
        ).also { section -> section.attach(settingsContainer) }

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
                        ?.let { uri ->
                            externalSubtitleUri = uri
                            // An .ass sidecar needs its libass overlay built
                            // here: the URI above only becomes a media3
                            // sidecar track, which for ASS means flattened
                            // cues, so a remembered fansub file would come
                            // back unstyled without this.
                            restoreAssSubtitle(uri)
                        }
                }
        }
        totalEpisodesInSeason = intent.getIntExtra("total_episodes_in_season", -1).takeIf { it > 0 }

        // The launch's TMDB runtime for the episode being played. The external
        // engine needs it as a duration it cannot see for itself; both in-app
        // engines read it as the scheme detector's reference (see
        // maybeDetectScheme), which is why it is read here rather than there.
        launchRuntimeMinutes = intent.getIntExtra("runtime_minutes", -1).takeIf { it > 0 }

        // Discover addon subtitles (Stremio "subtitles" resource) and merge
        // them as sidecar text tracks once the player attaches. Skipping is
        // automatic for live channels and when no video id is available.
        //
        // The session's own episode id goes along: it is the video the stream
        // was resolved with, which is what an add-on publishes its subtitles
        // against - the TMDB number is not (see addonSubtitleVideoId).
        if (!isLiveChannel) {
            addonSubtitleController.bind(
                view = playerView,
                parentId = parentId,
                parentType = parentType,
                season = season,
                episode = episode,
                episodeStreamId = episodeStreamId
            )
        }

        // Keep the currently selected stream available even when the caller did not
        // provide a complete source list.
        val selectedStream = Stream(name = "Current source", title = null, url = currentUrl, audioUrl = currentAudioUrl)
        sources = listOf(selectedStream)
        // The list exactly as the payload carried it, before a prepended current
        // source: the add-on names are resolved against it so every name stays
        // with its own row.
        var parsedSources: List<Stream> = emptyList()

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
                parsedSources = sources
                if (sources.none { it.url == currentUrl }) {
                    sources = listOf(selectedStream) + sources
                }
            } catch (e: Exception) {
                Log.w("NativePlayer", "Failed to parse sources_json", e)
            }
        }
        // The add-on for each source, parallel to sources_json. Absent or short
        // reads as "unknown" for the missing entries, which are never skipped
        // and never demoted.
        sourceAddons = addonsFor(
            streams = sources,
            parsed = parsedSources,
            addons = parseSourceAddonsJson(intent.getStringExtra("source_addons"))
        )
        // The picker's tab, when there was one. Read so the session keeps it
        // across an engine handoff; no call site below consults it (see
        // [selectedAddon]).
        selectedAddon = intent.getStringExtra("selected_addon")?.takeIf { it.isNotBlank() }

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
                if (chromeThemeMoved(this)) refillPlayerChrome(itemView)
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
                            path.startsWith("/") -> "https://image.tmdb.org/t/p/w185$path"
                            else -> "https://image.tmdb.org/t/p/w185/$path"
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
                    // The actor ticket, on disk before this activity finishes.
                    // A phone routinely recreates MainActivity while playback
                    // is up, and its in-memory Screen.Player is not what comes
                    // back - so the person page's return has to be rebuilt
                    // from what the player itself knows about this session
                    // (see ActorHandoff). Persist FIRST, exactly like
                    // NextEpisodeResult above: the result callback may run on
                    // a shell that never received these extras.
                    ActorHandoff.persist(
                        this,
                        ActorHandoff.PendingActor(
                            personId = member.id,
                            resumePositionMs = currentPosition,
                            url = currentUrl,
                            audioUrl = currentAudioUrl,
                            parentId = parentId,
                            parentType = parentType,
                            season = season,
                            episode = episode,
                            episodeStreamId = episodeStreamId,
                            episodeTitle = episodeTitle,
                            itemName = itemName,
                            itemPoster = itemPoster,
                            clearLogoUrl = clearLogoUrl,
                            backdropUrl = backdropUrl,
                            overview = overview,
                            totalEpisodesInSeason = totalEpisodesInSeason,
                            runtimeMinutes = launchRuntimeMinutes,
                            randomEpisodes = randomEpisodes,
                            streamHeaders = streamHeaders,
                            drmLicenseUrl = drmLicenseUrl,
                            drmHeaders = drmHeaders,
                            cast = castMembers
                        )
                    )
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

        initBingeScheme()
        // The stream id names the FILE; the scheme maps the file to its TMDB
        // episode(s). When the intent's s/e disagree with that mapping, the file
        // is ground truth - it is what is actually playing. Correct the fields
        // so filing, scrobbling, and next-episode all use the file's episode
        // (see PlaybackHistoryIds.correctedSessionEpisode).
        PlaybackHistoryIds.correctedSessionEpisode(
            season,
            episode,
            episodeStreamId,
            bingeScheme
        )?.let { (correctedSeason, correctedEpisode) ->
            season = correctedSeason
            episode = correctedEpisode
        }
        historyId = PlaybackHistoryIds.historyId(
            parentId = parentId,
            season = season,
            episode = episode,
            episodeStreamId = episodeStreamId
        )
        // Into the diagnostics report: what this session will file itself
        // under, next to what the id it plays says. A binge that chains into
        // the wrong episode - or files the right ones under the wrong row - is
        // this line, and nothing else in the app compares the two.
        com.kennyb1201.kbstream.data.reporting.PlaybackSessionTrace.note(
            // The stored scheme goes with it: a session's fields name a TMDB
            // episode and its id names the FILE holding it, so the comparison
            // must read the id as a file (see
            // PlaybackHistoryIds.playbackSessionLine).
            PlaybackHistoryIds.playbackSessionLine(
                season,
                episode,
                episodeStreamId,
                historyId,
                bingeScheme
            )
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
        // beginning" is honored as-is, and the player is only created after
        // the read so the load-time seek already carries the position - nothing
        // starts at 0 and jumps. A failed or empty read leaves the launch as it
        // was. The rule itself is shared with the other two engines - see
        // PlaybackResume.
        if (
            !isLiveChannel &&
            PlaybackResume.mayResumeFromHistory(
                startPositionMs = startPositionMs,
                startFromBeginning = startFromBeginning,
                historyId = historyId
            )
        ) {
            lifecycleScope.launch {
                val savedPositionMs =
                    PlaybackResume.savedPositionMs(
                        context = this@NativePlayerActivity,
                        historyId = historyId,
                        parentId = parentId,
                        parentType = parentType,
                        season = season,
                        episode = episode,
                        episodeStreamId = episodeStreamId
                    )
                if (savedPositionMs != null) {
                    Log.i(
                        TAG,
                        "resume: launch had no position, using saved " + savedPositionMs + "ms"
                    )
                    startPositionMs = savedPositionMs
                    carryPositionMs = savedPositionMs
                }
                setupIntroDb()
                probeThenCreatePlayer()
            }
        } else {
            setupIntroDb()
            probeThenCreatePlayer()
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
     * canceled so the line on screen re-times at once.
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
        val durationMs = exoPlayer?.duration ?: 0L
        val settings = AutoSkipRules.Settings(autoSkipIntros, autoSkipCredits)
        if (!AutoSkipRules.shouldAutoSkip(stamp, settings, durationMs)) return
        runCatching {
            if (autoSkippedSegments.add(AutoSkipRules.key(stamp))) {
                val target = AutoSkipRules.targetMs(stamp, introDbStamps, durationMs)
                exoPlayer?.seekTo(target)
                Log.i(
                    "INTRO_DB",
                    "auto-skipped ${stamp.type.name} ${stamp.startMs}.." +
                        "${stamp.endMs ?: "eof"} -> $target"
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

    // GestureBackNavigation: the settings sheet, picker and channel guide each
    // close on a Back press made while one of them holds focus. The registered
    // OnBackPressedCallback dismisses the same panels when the press reaches
    // the dispatcher; these view listeners pre-empt it so the focused row keeps
    // the press first. Device-tested migration, tracked with the API-36 pass.
    @SuppressLint("GestureBackNavigation")
    private fun bindViews() {
        playerView = findViewById(R.id.player_view)
        subtitleText = findViewById(R.id.custom_subtitle_text)
        subtitleAssImage = findViewById(R.id.custom_subtitle_image)
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
        // Engine-only views inflated into the shared chrome's slots, so they keep
        // the exact place they had in this layout before the overlay was unified:
        // the live program block inside the info scroll, the channel buttons
        // leading the control bar. The slots themselves are GONE in the shared
        // layout and are revealed here.
        val liveSlot = findViewById<LinearLayout>(R.id.chrome_extra_top)
        val liveBlock = layoutInflater.inflate(R.layout.player_exo_live_program, liveSlot, false)
        liveSlot.addView(liveBlock)
        liveSlot.visibility = View.VISIBLE
        liveProgramBlock = liveBlock
        liveProgramStatus = liveBlock.findViewById(R.id.live_program_status)
        liveProgramTitle = liveBlock.findViewById(R.id.live_program_title)
        liveProgramProgress = liveBlock.findViewById(R.id.live_program_progress)
        liveProgramDesc = liveBlock.findViewById(R.id.live_program_desc)
        liveProgramNext = liveBlock.findViewById(R.id.live_program_next)
        val channelSlot = findViewById<LinearLayout>(R.id.chrome_extra_controls)
        val channelBar = layoutInflater.inflate(
            R.layout.player_exo_channel_buttons,
            channelSlot,
            false
        )
        channelSlot.addView(channelBar)
        channelSlot.visibility = View.VISIBLE
        btnChannelUp = channelBar.findViewById(R.id.btn_channel_up)
        btnChannelDown = channelBar.findViewById(R.id.btn_channel_down)
        btnGuide = channelBar.findViewById(R.id.btn_guide)
        channelGuideContainer = findViewById(R.id.channel_guide_container)
        channelGuideTitle = findViewById(R.id.channel_guide_title)
        channelGuideList = findViewById(R.id.channel_guide_list)
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
        // The one shared overlay, driven by this engine through PlayerChromeHost.
        // Its views are the neutral chrome_* ids of player_chrome.xml; the bar
        // and its auto-hide timing are the shared six seconds.
        chrome = PlayerChrome(findViewById(R.id.chrome_layer), this)
        btnSkipIntro = findViewById(R.id.chrome_skip_intro)
        controlsOverlay = findViewById(R.id.chrome_root)
        playerClock = findViewById(R.id.chrome_clock)
        endsAtClock = findViewById(R.id.chrome_ends_at)
        splashContainer = findViewById(R.id.splash_container)
        splashBackdrop = findViewById(R.id.splash_backdrop)
        splashClearLogo = findViewById(R.id.splash_clear_logo)
        clearLogo = findViewById(R.id.chrome_clear_logo)
        itemNameView = findViewById(R.id.chrome_item_name)
        episodeLabel = findViewById(R.id.chrome_episode_label)
        episodeTitleView = findViewById(R.id.chrome_episode_title)
        badgeRow = findViewById(R.id.chrome_badge_row)
        overviewText = findViewById(R.id.chrome_overview)
        seekbar = findViewById(R.id.chrome_seekbar)
        currentTime = findViewById(R.id.chrome_position)
        totalTime = findViewById(R.id.chrome_duration)
        btnPlayPause = findViewById(R.id.chrome_btn_play_pause)
        btnNext = findViewById(R.id.chrome_btn_next)
        btnSource = findViewById(R.id.chrome_btn_source)
        btnPlayerSwitch = findViewById(R.id.chrome_btn_player_switch)
        btnPlayerExternal = findViewById(R.id.chrome_btn_player_external)
        btnAudio = findViewById(R.id.chrome_btn_audio)
        btnSubtitle = findViewById(R.id.chrome_btn_subtitle)
        btnSpeed = findViewById(R.id.chrome_btn_speed)
        btnAspect = findViewById(R.id.chrome_btn_aspect)
        btnInfo = findViewById(R.id.chrome_btn_info)
        infoPanel = findViewById(R.id.info_panel)
        infoAddonIcon = findViewById(R.id.info_addon_icon)
        infoTitle = findViewById(R.id.info_title)
        infoSource = findViewById(R.id.info_source)
        infoEngine = findViewById(R.id.info_engine)
        infoFile = findViewById(R.id.info_file)
        infoVideo = findViewById(R.id.info_video)
        infoAudio = findViewById(R.id.info_audio)
        // The MPV panel's own ids: the two layouts are id-for-id the same
        // panel (see MpvInfoPanelContractTest), so a row added here is a row
        // there and the names must not drift by a plural.
        infoSubtitles = findViewById(R.id.info_subtitle)
        btnSettings = findViewById(R.id.chrome_btn_settings)
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
            } else if (isPickerShowing) {
                // The picker gets the same treatment as the settings panel, and
                // for the same report: a focus that leaves the panel lands on
                // the scrim or on a control of the bar still drawn behind it,
                // where the next OK presses something the viewer cannot see
                // selected - "the picker loses focus to the overlay". The row
                // the viewer was on is the one it comes back to.
                if (newFocus != null && isDescendantOf(newFocus, pickerContainer)) {
                    // The row only: the list and the container are focusable
                    // themselves, and remembering one of them would send the
                    // D-pad back to a holder with no highlighted row.
                    if (newFocus !== pickerList && newFocus !== pickerContainer) {
                        pickerLastFocus = newFocus
                    }
                } else if (newFocus != null) {
                    reassertPickerFocus()
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
        castSection = findViewById(R.id.chrome_cast_section)
        castRow = findViewById(R.id.chrome_cast_row)
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
        nextUpCountdownBar = findViewById(R.id.next_up_countdown_bar)

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
                    if (chromeThemeMoved(this@NativePlayerActivity)) {
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

        // Channel guide overlay. Same shape as the picker: rows inflated on
        // demand, focus handed to the list, Back closes it.
        channelGuideList?.layoutManager = LinearLayoutManager(this)
        channelGuideList?.isFocusable = true
        channelGuideList?.isFocusableInTouchMode = true
        channelGuideList?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        channelGuideList?.addOnChildAttachStateChangeListener(
            object : androidx.recyclerview.widget.RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    if (chromeThemeMoved(this@NativePlayerActivity)) {
                        refillPlayerChrome(view)
                    }
                }

                override fun onChildViewDetachedFromWindow(view: View) = Unit
            }
        )
        channelGuideList?.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                dismissChannelGuide(); showControls(); true
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
        // The external engine's own gate: an app on this box to hand the
        // stream TO, and a stream it can take - it refuses live TV and a DRM
        // session exactly as the MPV backup does (see handOffToExternal).
        btnPlayerExternal.visibility =
            if (PlayerEngine.externalAvailable(this) && !isLiveChannel && drmLicenseUrl == null) {
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

    // GestureBackNavigation: the info panel closes on Back (and OK) while it
    // holds focus. The dispatcher callback handles it too; see bindViews.
    @SuppressLint("GestureBackNavigation")
    private fun setupListeners() {
        // Live channel change from the overlay (the D-pad / CH+ keys zap too,
        // but only while the overlay is hidden — see liveZapKeysFree).
        btnChannelUp?.setOnClickListener { zapByOffset(+1) }
        btnChannelDown?.setOnClickListener { zapByOffset(-1) }
        btnGuide?.setOnClickListener { showChannelGuide() }
        listOfNotNull(btnChannelUp, btnChannelDown, btnGuide).forEach { button ->
            button.setOnFocusChangeListener { _, focused ->
                if (focused) removeAutoHide() else scheduleAutoHide()
            }
        }

        // Skip intro
        btnSkipIntro.setOnFocusChangeListener { v, focused ->
            if (focused) removeAutoHide() else scheduleAutoHide()
            // Visible focus ring: without this the focused and unfocused
            // buttons look identical and a D-pad user can't tell when
            // pressing OK will actually trigger the skip. Built from the
            // theme, not the fixed XML drawables: the focused twin is a
            // layer-list the accent walk cannot rebuild, so a focused SKIP
            // INTRO kept the default brass on a chosen accent (see
            // [accentButtonBackground]).
            v.background = accentButtonBackground(this, focused)
            // The button step of the shared focus scale (KBFocusButton),
            // not the chip's 1.06: this is a button, and it was the only
            // control in the app that grew by a chip's amount.
            v.scaleX = if (focused) 1.04f else 1f
            v.scaleY = if (focused) 1.04f else 1f
        }
        // Retry
        btnRetry.setOnClickListener {
            retryAttempt = 0
            retryExhausted = false
            // An explicit retry is the viewer asking for the whole ladder
            // again, so the decoder failure that previously handed the session
            // to the backup engine starts from the top rather than handing
            // over on its first failure this time.
            decoderFailureRetried = false
            errorMessageStr = null
            // The rebuild budget is part of that ladder state and is reset with
            // it (see [RebuildBudget]). It used not to be, which is the press
            // that looked broken: the ladder started over, spent ONE rebuild,
            // hit the still-spent cap and dropped straight back onto the same
            // card.
            rebuildBudget.reset()
            // ...and the rebuild this press is about to do is COUNTED, so the
            // fresh run is still capped instead of the cap being bypassed
            // silently by pressing the button again: the ladder behind the
            // press gets what is left of the allowance, not a new one.
            PlaybackRecoveryRules.failureCauseKey(lastPlaybackError)
                ?.let { rebuildBudget.record(it) }
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

        // The shared control-bar buttons are wired once by PlayerChrome and
        // reach this engine through the PlayerChromeHost methods below - play /
        // pause, next, source, audio, subtitles, speed, aspect, switch,
        // external, info, settings and the skip prompt all land on the same
        // actions they always did. Only what is this engine's own is bound here.

        // "Up next" popup buttons
        btnNextPlay.setOnClickListener {
            // Same guard as the countdown: this card's PLAY must never hand the
            // session on from a DIFFERENT end-of-episode decision.
            if (!nextUpHandoffArmed) return@setOnClickListener
            // Manual confirmation: user is awake - restart the watchdog.
            AppPreferences.resetConsecutiveAutoplays(this)
            launchNextEpisode(
                pendingNextSeason ?: return@setOnClickListener,
                pendingNextEpisode ?: return@setOnClickListener,
                pendingNextEpisodeName,
                pendingNextEpisodeRuntime,
                pendingNextEpisodeOverview
            )
        }
        btnNextDismiss.setOnClickListener { finish() }
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
        // Scrim (dismiss panels)
        scrim.setOnClickListener { dismissAllPanels() }

        // Seekbar: the touch/scrub listener is the shared chrome's now - it
        // seeks through PlayerChromeHost.onChromeSeekTo on release, and reports
        // the drag through its own isScrubbing. What stays here is this engine's
        // own key handling, which a bare SeekBar cannot give: LEFT/RIGHT are a
        // hold-to-scrub, OK is play/pause, and UP lands on the first cast tile.
        //
        // CHAPTER TICKS: this bar draws none, and that is a platform limit, not
        // an omission (audit U-F4). The tick painter is shared
        // (ChapterSeekBar / PlayerChrome.setChapters) and the MPV engine feeds
        // it from mpv's own `chapter-list`; media3 at this build's pinned
        // version (1.9.0, held there for the FFmpeg video decoder fallback)
        // exposes no chapter list at all - it has no `Chapter` metadata entry
        // and `Player` no longer even carries `currentStaticMetadata`. Its only
        // chapter artefact is the ID3 `ChapterFrame`, which no MP4 or MKV in a
        // debrid stream ever contains. So an ExoPlayer session cannot know where
        // a file's chapters are; showing a made-up row would be worse than
        // showing none. When media3's chapter API is available at the pinned
        // version, the wiring is one call from onTracksChanged to
        // `chrome?.setChapters(...)` with the file's duration - the bar already
        // paints whatever it is given.

        // Quick-press = 10s jump; holding (past 400ms) = accelerated scrubbing.
        seekbar.setOnKeyListener { _, keyCode, event ->
            when (keyCode) {
                // OK on the bar is the primary action, the same press the
                // play/pause button makes. The bar is where the overlay lands
                // when it comes up (see focusControls), so this is the press
                // that follows the controls appearing: without it, OK on the
                // bar did nothing at all and pausing meant a DOWN into the row
                // first. One press, one toggle - a held OK would otherwise walk
                // the pause state back and forth. (The bar is the shared
                // chrome's; see PlayerChrome.)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        togglePlayPause()
                    }
                    true
                }
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
                                // Seed the owned target at the start of the scrub.
                                scrubTargetPosMs =
                                    exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L
                                scrubMoved = false
                                // Pause for the scrub, remembering what it was: the
                                // follow-seeks below then have no playback deadline
                                // to miss (see [beginScrubPause]).
                                beginScrubPause()
                                removeAutoHide()
                                // Immediate step for a quick press/release
                                stepSeekBy(10_000L * scrubDirection)
                                // If still held past the threshold, switch to fast scrubbing
                                scrubHandler.removeCallbacks(scrubHoldStarter)
                                scrubHandler.postDelayed(
                                    scrubHoldStarter,
                                    ScrubAcceleration.HOLD_START_MS
                                )
                            }
                            true
                        }
                        KeyEvent.ACTION_UP -> {
                            scrubDirection = 0
                            scrubHandler.removeCallbacks(scrubHoldStarter)
                            scrubHandler.removeCallbacks(scrubRunnable)
                            // commitSeekFromBar() is this path's exact landing
                            // (it seeks to the bar, which tracks the owned
                            // target), so the owned target is retired here
                            // rather than landed on twice.
                            scrubMoved = false
                            commitSeekFromBar()
                            // Put the play state back exactly as the scrub found
                            // it - playing resumes, paused stays paused.
                            endScrubPause()
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
            PlayerTitlePrefs.remember(
                this,
                titleKey,
                (PlayerTitlePrefs.get(this, titleKey) ?: PlayerTitlePrefs.Prefs())
                    .copy(aspectRatio = resizeModeIndex)
            )
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

    // GestureBackNavigation: this key handler consumes Back while a panel,
    // picker or the info panel is open, so the press closes what is open
    // instead of being swallowed by the panel's focused child. The dispatcher
    // callback makes the same decision; see bindViews.
    @SuppressLint("GestureBackNavigation")
    private fun setupKeyboardHandler() {
        playerView.isFocusable = true
        playerView.isClickable = true
        playerView.setOnFocusChangeListener { _, hasFocus ->
            // Never yank focus to the overlay while a panel is up — that is
            // one of the ways the settings panel loses focus. When it does move
            // it, it moves it to the overlay's primary control rather than to
            // the container, so the press that follows always has a button it
            // can actually see under it.
            if (hasFocus && controlsVisible && !showSettingsPanel && !isPickerShowing) {
                focusControlsPrimary()
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

            // While the channel guide is up it owns the remote: its rows take
            // the D-pad and Back closes it. A key reaching the surface anyway
            // (focus not yet on the list) must not zap or raise the overlay
            // under the panel the viewer is browsing.
            if (isGuideShowing) return@setOnKeyListener false

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
                    // Guarded exactly like the PLAY arm below, and for a reported
                    // Fire TV symptom: Fire OS emits spurious MEDIA_PAUSE events
                    // around HDMI-CEC and voice-remote activity, including while
                    // the startup splash is still up. Pausing then set
                    // playWhenReady=false AFTER createPlayer had set it true; the
                    // player still reached READY and painted frame 1 (so the
                    // splash came down and the controls came up) and nothing ever
                    // auto-resumed - the viewer saw "it pauses after initial load
                    // and I have to unpause". Before the first frame there is
                    // nothing on screen to pause, so the press is swallowed.
                    if (!hasPlayedOnce) true else {
                        exoPlayer?.pause(); showControls(); true
                    }
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    // Same guard as togglePlayPause(): a stray PLAY after the end
                    // must not restart the episode from the top.
                    if (playbackEndedHandled) true else {
                        exoPlayer?.play(); hideControls(); true
                    }
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
                    } else if (controlsVisible && !controlsOverlay.hasFocus()) {
                        // The overlay is up but nothing in it holds the D-pad,
                        // which is the state a closing picker leaves behind
                        // (the row that owned focus goes with its container).
                        // The press used to be read as "the surface has focus,
                        // so OK means hide the controls": it collapsed the
                        // overlay the viewer had just come back to, and
                        // playing took three presses (hide, raise, play).
                        // Hand the press to the overlay instead.
                        focusControlsPrimary()
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
                    if (liveZapKeysFree()) {
                        // Live TV: LEFT/RIGHT are the previous-channel toggle -
                        // back to the channel that was playing before this one,
                        // and back again on the next press. Either direction
                        // does the same thing, so a remote whose rocker sends
                        // left/right works whichever way it is pushed; stepping
                        // through the lineup in order is UP/DOWN's job. Held
                        // keys are ignored because every tune rebuilds the
                        // player. Gated on ACTION_DOWN because - unlike UP/DOWN
                        // - a LEFT/RIGHT release is let through above (a scrub
                        // needs its key up), and acting on both halves of the
                        // press would toggle twice.
                        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                            recallLastChannel()
                        }
                        true
                    } else if (errorContainer.visibility == View.VISIBLE) {
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
                // The remote's own previous-channel button, on the remotes
                // that have one: the same toggle as LEFT/RIGHT. Its release
                // never reaches here (only ACTION_DOWN gets past the guard at
                // the top of this listener), so the press is the whole event -
                // but a held key still must not toggle over and over.
                KeyEvent.KEYCODE_LAST_CHANNEL -> {
                    if (liveZapKeysFree()) {
                        if (event.repeatCount == 0) recallLastChannel()
                        true
                    } else {
                        false
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
        if (isGuideShowing ||
            isPickerShowing ||
            showSettingsPanel ||
            infoPanel.visibility == View.VISIBLE
        ) {
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
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                // Same guard as togglePlayPause(): a stray PLAY after the end
                // must not restart the episode from the top.
                if (playbackEndedHandled) return true
                exoPlayer?.play(); hideControls(); return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                // Same startup guard as the surface listener's arm: a spurious
                // MEDIA_PAUSE during the initial load (Fire TV emits them around
                // CEC / voice-remote events) would otherwise park the player on
                // frame 1 with nothing to resume it.
                if (hasPlayedOnce) {
                    exoPlayer?.pause(); showControls()
                }
                return true
            }
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
            // you can see where you landed. The D-pad changes channel too
            // (handled in the player view's key listener), since most TV
            // remotes have no dedicated channel keys: UP/DOWN step through the
            // lineup in order, while LEFT/RIGHT are the previous-channel
            // toggle.
            KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (isLiveChannel) { zapByOffset(+1); return true }
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (isLiveChannel) { zapByOffset(-1); return true }
            }
            // D-pad fallback. The video surface's own listener handles these
            // when it holds focus, so reaching here means focus sits somewhere
            // else (a button that hid with the overlay, the root view) — which
            // used to make changing channel from the player do nothing at all.
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
            // LEFT/RIGHT - and the remote's own previous-channel key, on the
            // remotes that have one - toggle back to the channel watched before
            // this one, the same way the surface listener handles them.
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_LAST_CHANNEL -> {
                if (liveZapKeysFree()) {
                    if ((event?.repeatCount ?: 0) == 0) recallLastChannel()
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
            val arithmeticTarget = nextEpisodeTarget()
            val rawTarget = resolveRandomChainTarget(
                this@NativePlayerActivity, randomEpisodes, arithmeticTarget,
                parentId, parentType, season, episode
            ) ?: return@launch
            // Same air-date gate as the end-of-playback panel: pressing Next
            // on an episode that has not aired yet must not resolve a stream
            // that does not exist.
            val target = airedNextEpisodeTarget(
                this@NativePlayerActivity, rawTarget, resolveParentTmdbId(), parentId,
                // The session's own season, so the gate can tell the invented
                // season-end jump from a chain inside the season playing (see
                // isSeasonBoundaryTarget)...
                sessionSeason = season,
                // ...except when the target came out of a LISTING: random mode's
                // pick is a real episode even when its season is a later one,
                // and treating it as the fabricated jump lost the panel on any
                // look-up hiccup (see [airedNextEpisodeTarget]).
                fromListing = rawTarget != arithmeticTarget
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
                pendingNextEpisodeRuntime,
                pendingNextEpisodeOverview
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
        // FLAG_IMMUTABLE has existed since API 23 and is REQUIRED from API 31;
        // the app's floor is now 24, so the old below-23 branch (bare
        // FLAG_UPDATE_CURRENT) is unreachable and gone.
        val flags =
            android.app.PendingIntent.FLAG_IMMUTABLE or
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
        return android.app.PendingIntent.getActivity(this, 1001, intent, flags)
    }

    // --- Touch layer (phones only; the TV path never reaches any of it) ------
    private var playerTouchGestures: PlayerTouchGestures? = null

    /**
     * The three gestures a phone viewer reaches for, attached only where there
     * is no remote: tap toggles the chrome, a drag seeks or sets the brightness
     * (left half) or volume (right half), a pinch walks the aspect modes and a
     * hold flips the screen. All of it is [PlayerTouchGestures]; every decision
     * is [PlayerTouchRules]; this is only what this engine answers with.
     *
     * Nothing here pauses: a seek commits through [commitSeek] without a play or
     * a pause, because the state the viewer chose before the drag is the state
     * it must end in - the same rule the D-pad scrub follows.
     */
    private fun attachPhoneTouchGestures() {
        val gestures = PlayerTouchGestures(
            this,
            playerView,
            object : PlayerTouchGestures.Callbacks {
                override fun gesturesAllowed(): Boolean =
                    !showSettingsPanel && !isPickerShowing && !isGuideShowing

                override fun toggleChrome() {
                    if (controlsVisible) hideControls() else showControls()
                }

                override fun toggleFullscreen() {
                    PlayerTouchDevice.toggleFullscreen(this@NativePlayerActivity)
                }

                override fun cycleAspect() {
                    val next = (resizeModeIndex + 1) % ASPECT_MODES.size
                    resizeModeIndex = next
                    applyAspectMode(next)
                    updateControlsInfo()
                    updateSettingsPanelState()
                    PlayerTitlePrefs.remember(
                        this@NativePlayerActivity,
                        titleKey,
                        (PlayerTitlePrefs.get(this@NativePlayerActivity, titleKey)
                            ?: PlayerTitlePrefs.Prefs()).copy(aspectRatio = next)
                    )
                    // The mode is otherwise only visible on the chrome button's
                    // label, and a PINCH is the normal way to reach this - a
                    // two-finger gesture that does not raise the chrome, so the
                    // label stayed hidden and the pinch read as a gesture that
                    // did nothing (audit U-F10, 2026-10-27). The MPV engine says
                    // the same line on the same gesture; this is the parity.
                    Toast.makeText(
                        this@NativePlayerActivity,
                        "Aspect: ${ASPECT_MODES[next]}",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun positionMs(): Long =
                    exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L

                override fun durationMs(): Long {
                    // A live channel has no end to seek towards, and the
                    // window's unset duration arrives negative.
                    if (isLiveChannel) return 0L
                    val duration = exoPlayer?.duration ?: 0L
                    return if (duration > 0L) duration else 0L
                }

                override fun showSeekHint(text: String) {
                    val hint = ensureScrubHint()
                    hint.text = text
                    hint.visibility = View.VISIBLE
                }

                override fun hideSeekHint() {
                    surfaceScrubHint?.visibility = View.GONE
                }

                override fun commitSeek(targetMs: Long) {
                    exoPlayer?.seekTo(targetMs)
                }

                override fun stepSeek(deltaMs: Long) {
                    // The ±seek BUTTON path, not a bare seekTo: it owns the
                    // live-duration no-op, the clamp to the film and the bar's
                    // bookkeeping, so a double-tap gets every guard the button
                    // beside it has.
                    stepSeekBy(deltaMs)
                }

                override fun brightnessFraction(): Float =
                    PlayerTouchDevice.brightnessFraction(this@NativePlayerActivity)

                override fun setBrightnessFraction(fraction: Float) {
                    PlayerTouchDevice.setBrightness(this@NativePlayerActivity, fraction)
                }

                override fun volumeFraction(): Float =
                    PlayerTouchDevice.volumeFraction(this@NativePlayerActivity)

                override fun setVolumeFraction(fraction: Float) {
                    PlayerTouchDevice.setVolume(this@NativePlayerActivity, fraction)
                }
            }
        )
        playerTouchGestures = gestures
        gestures.attach()
    }

    /**
     * Re-applies the portrait stage (video band on top, chrome below it).
     *
     * Called at first layout and on every rotation: orientation is declared in
     * this activity's configChanges, so a rotation does not recreate it and
     * nothing else would re-run the stage.
     */
    private fun applyPlayerPortraitStage() {
        val root = findViewById<View>(R.id.player_root) ?: return
        if (root.width == 0) {
            root.post { applyPlayerPortraitStage() }
            return
        }
        PlayerPortraitStage.apply(
            this,
            root,
            listOf(playerView, subtitleText, subtitleAssImage, p5VideoGlesView),
            findViewById(R.id.chrome_layer)
        )
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyPlayerPortraitStage()
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
     * they had - on a live channel that meaning is the previous-channel
     * recall, see [recallLastChannel].
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
                // Seed the owned target at the start of the scrub.
                scrubTargetPosMs = exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L
                scrubMoved = false
                beginScrubPause()
                stepSeekBy(10_000L * scrubDirection)
                showScrubHint()
                scrubHandler.removeCallbacks(scrubHoldStarter)
                scrubHandler.postDelayed(scrubHoldStarter, ScrubAcceleration.HOLD_START_MS)
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
        // This path has no bar to land on, so the owned target is the landing:
        // a press-and-hold past the first seek would otherwise stop wherever the
        // decoder last kept up.
        if (wasScrubbing) landScrub()
        if (wasScrubbing) endScrubPause()
        if (wasScrubbing) scheduleScrubHintHide()
        return wasScrubbing
    }

    /** Full stop: cancel scrub timers and hide the hint immediately. */
    private fun stopSurfaceScrub() {
        scrubDirection = 0
        landScrub()
        // A scrub cut short here - the credits panel took the key, the overlay
        // came up - must still put the play state back (see [endScrubPause]).
        endScrubPause()
        scrubHandler.removeCallbacks(scrubHoldStarter)
        scrubHandler.removeCallbacks(scrubRunnable)
        scrubHintHandler.removeCallbacks(scrubHintHider)
        surfaceScrubHint?.visibility = View.GONE
    }

    // --- Player Creation ---
    /**
     * The [MediaItem.Builder] for the current source, exactly as [createPlayer]
     * assembles it: the URL, its resolved mime type, the now-playing metadata
     * surfaced to system/external controllers, and any Widevine DRM config.
     *
     * Extracted so the live-zap light path hands the running player the same
     * shape a full rebuild would have produced - a channel change cannot drift
     * from a fresh load. [createPlayer] still owns everything past this point
     * (subtitles, media sources, renderers, session).
     */
    private fun newMediaItemBuilder(mimeType: String?): MediaItem.Builder {
        val builder = MediaItem.Builder().setUri(currentUrl)
        if (mimeType != null) builder.setMimeType(mimeType)
        // A downloaded progressive row must be addressed by the key its bytes
        // were written under, or the cache would be looked up by url and miss
        // (see DownloadsManager.playbackCacheKey). Null for everything else,
        // including an adaptive download, whose segments are keyed by their own
        // urls and must NOT be given a key.
        downloadCacheKey?.let { builder.setCustomCacheKey(it) }

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
        builder.setMediaMetadata(mdBuilder.build())

        // DRM: set license URL and headers on the MediaItem so ExoPlayer's
        // built-in DRM negotiation handles Widevine playback.
        if (!drmLicenseUrl.isNullOrBlank()) {
            val drmHeaders = drmHeaders
            builder.setDrmConfiguration(
                MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                    .setLicenseUri(drmLicenseUrl)
                    .apply {
                        if (drmHeaders.isNotEmpty()) {
                            setLicenseRequestHeaders(drmHeaders)
                        }
                    }
                    .build()
            )
            // Redacted: a Widevine licence URL routinely carries the token that
            // authorizes the request in its query string, and this line is
            // logged on every DRM launch (see [Redaction]).
            Log.i("PLAYER_DRM", "Widevine DRM configured: ${Redaction.url(drmLicenseUrl)}")
        }
        return builder
    }

    // DefaultTrackSelector.Parameters.Builder(Context) is deprecated in media3
    // 1.9; the no-arg Builder() is not a drop-in for the context-derived
    // defaults, so this waits for the media3 upgrade.
    /**
     * Forgets a cached debrid link whose launch failed before a single frame
     * rendered - the signature of a link that expired or was pulled. Called
     * from the error path only; the session still walks its remaining cached
     * sources (the error ladder), and only a FUTURE replay re-resolves fresh.
     */
    private fun invalidateCachedLinkBeforeFirstFrame() {
        if (firstFrameRendered || linkCacheInvalidated) return
        val cacheKey = intent.getStringExtra("played_link_key") ?: return
        linkCacheInvalidated = true
        // The session is pinned to its LAUNCH profile, so forget on THAT store:
        // a mid-playback profile switch must not spare the launching profile's
        // dead entry (SD-2).
        PlayedLinkCache.forgetForProfile(this, cacheKey, sessionProfileId)
    }

    @Suppress("DEPRECATION")
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
        // The auto-switch budget is deliberately NOT reset here. This function
        // runs again for every rebuild, and every source switch IS a rebuild
        // (see switchToSource), so resetting it here made
        // [MAX_AUTO_SOURCE_SWITCHES] unreachable: a fully dead list was walked
        // source by source with a full retry ladder each instead of stopping
        // after two switches and raising the card (audit S-F3, 2026-10-27). It
        // is reset once per session instead - see probeThenCreatePlayer.

        // Shared with the MPV engine, which used to ask as `mpv/<version>` for
        // the same stream - see StreamUserAgent.
        val agent = StreamUserAgent.resolve(streamHeaders)

        // Some addon hosts / CDNs need more than the default 20 s connect
        // timeout, especially during peak hours or on first-byte waits.
        // Give every playback attempt a slightly more generous ceiling so
        // a slow-but-valid source does not get killed before the retry
        // ladder can act. The startup / stall / black-video watchdogs still
        // bound total wait time.
        val sharedHttpFactory =
            androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(httpClient)
                .setUserAgent(agent)

        // Non-UA headers (e.g. Referer for addon hosts) every source carries,
        // read here because the lane pool below takes them at construction.
        val extraHeaders = StreamUserAgent.withoutUserAgent(streamHeaders)
            .filterValues { it.isNotBlank() }

        // Fetch lanes for a heavy source (see StreamFetchLanes.kt): a 4K remux
        // is far past what one HTTP/2 connection can carry, so it reads on
        // several independent pools while everything else keeps the app's one
        // shared, warm client and pays no extra connections. Per-playback —
        // [lanePool] goes with the session.
        val laneCount = fetchLanesFor(
            declaredBitrateBps = streamBitrate,
            claimsUhd = sources.getOrNull(currentSourceIndex)
                ?.let { StreamRanker.resolutionRank(it) >= UHD_RESOLUTION_RANK } == true,
            isLive = isLiveChannel
        )
        val laneFactory = if (laneCount > 1) {
            LanePoolDataSourceFactory(
                lanes = laneCount,
                userAgent = agent,
                headers = extraHeaders,
                laneClient = { oneLaneHttpClient() }
            ).also { pooled ->
                // A previous session's lanes go with that session.
                lanePool?.release()
                lanePool = pooled
            }
        } else {
            null
        }

        // The shared client still carries the extra headers as its defaults —
        // the lane pool already took them at construction.
        if (extraHeaders.isNotEmpty() && laneFactory == null) {
            sharedHttpFactory.setDefaultRequestProperties(extraHeaders)
        }

        // What a non-YouTube source reads through.
        val httpFactory: androidx.media3.datasource.DataSource.Factory =
            laneFactory ?: sharedHttpFactory

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

        // Disk read-ahead cache. With nothing on disk in front of the HTTP
        // source, every playback (and every seek/recovery) re-fetches the
        // head of the file from the network, so a slow host has zero headroom:
        // the moment its fill rate dips below realtime the buffer drains and
        // playback stalls. Wrapping the source in a SimpleCache lets
        // already-fetched bytes come back from disk instead. Skipped for live
        // channels (every byte is once-only) and googlevideo clips (bounded,
        // signed ranges that must not be re-served from a stale cache).
        // A downloaded session reads the DOWNLOAD cache, and its upstream is a
        // client that refuses every request: a cache miss fails the playback
        // with the same words the launch-time check uses, instead of quietly
        // fetching the source url over the network - which is exactly what would
        // make this feature work on the sofa and fail on the plane (spec §6).
        val downloadRecordForPlayback = downloadId?.let {
            DownloadsManager.playbackRecord(this, it)
        }
        val downloadSourceFactory = downloadRecordForPlayback?.let { record ->
            DownloadsManager.playbackDataSourceFactory(this, record)
        }
        val cachedFactory: androidx.media3.datasource.DataSource.Factory =
            if (downloadSourceFactory != null) {
                downloadSourceFactory
            } else if (isLiveChannel || isGooglevideoStream) {
                httpOrYoutubeFactory
            } else {
                androidx.media3.datasource.cache.CacheDataSource.Factory()
                    .setCache(StreamDiskCache.get(this))
                    .setUpstreamDataSourceFactory(httpOrYoutubeFactory)
                    .setFlags(
                        androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
                    )
            }

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
        // Sink check: the box decoding Dolby Vision is not enough — the TV has
        // to show it. A DV decoder only proves the box can decode; the platform
        // does not reliably downconvert for a non-DV sink (field: a Fire TV
        // Stick 4K Max on a non-DV TV plays native P8 as a black screen with
        // audio). In Auto, native passthrough is therefore withheld unless the
        // display reports DV too. Fail-open: a platform that reports no display
        // capabilities returns true and keeps today's behavior.
        val displaySupportsDv = DolbyVisionCompat.displaySupportsDolbyVision(this)
        val dvDisplayMismatch = dvCompatMode == AppPreferences.DV_COMPAT_AUTO &&
            deviceNativeDvSupported && !displaySupportsDv
        val dvPassthroughFailedAt = AppPreferences.getDvPassthroughFailedAt(this)
        val nativeDvSuppressed =
            dvCompatMode == AppPreferences.DV_COMPAT_AUTO &&
                dvPassthroughSuppressed(
                    failedAtMillis = dvPassthroughFailedAt,
                    nowMillis = System.currentTimeMillis()
                )
        val nativeDvSupported = deviceNativeDvSupported && !nativeDvSuppressed &&
            (dvCompatMode != AppPreferences.DV_COMPAT_AUTO || displaySupportsDv)
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
        // P7 → 8.1 still routes through the Dolby Vision pipeline (the rewritten
        // codec keeps the dvhe/dvh1 family), which black-screens a non-DV
        // display, so the conversion is gated on the sink too: with a non-DV TV
        // the display check fails and P7 falls into the existing HDR10 strip
        // path instead (unchanged code — see DolbyVisionCompatExtractorsFactory).
        val convertP7To81 = dvCompatMode == AppPreferences.DV_COMPAT_AUTO && displaySupportsDv
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
        //
        // Decode vs passthrough. A surround bitstream handed straight to the
        // receiver never reaches the app's own PCM chain, so when the output
        // mode asks for decode — explicit "Decode", or "Auto" while the
        // downmix/dialogue/volume tuning is actually doing something — prefer
        // the FFmpeg audio decoder, whose output is PCM: the sink then never
        // engages passthrough and the processors apply. Decode outranks
        // "Device decoders only" because it is the more specific request; with
        // no FFmpeg extension present media3 falls back to hardware, so the
        // worst case is the passthrough that mode had anyway.
        val audioOutputMode = AppPreferences.getAudioOutput(this)
        val decodeToPcm = PlayerAudioTuning.requiresDecode(audioOutputMode)
        // Remembered for the info panel (see buildInfoPanel).
        audioDecodeToPcmActive = decodeToPcm
        val audioExtMode = if (decodeToPcm) {
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
        } else when (audioDecoderPriority) {
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
                "displaySupportsDv=$displaySupportsDv " +
                "audioDecoder=$audioDecoderPriority " +                    "audioOutput=$audioOutputMode decodeToPcm=$decodeToPcm " +
                        "audioSeparate=${!currentAudioUrl.isNullOrBlank()}"
        )
        // The compat extractor is needed when DV conversion is on OR the
        // HDR10+ strip toggle is on — both run inside it.
        // Both branches share ONE plain factory, configured with the two TS
        // settings the field needs and Media3 does not default to. Just Player
        // ships the same pair, and they are the two upstream TS issues worth
        // working around:
        //
        //  - HDMV DTS audio streams. A DTS track that arrives through the HDMV
        //    stream descriptors is SKIPPED by the extractor's default flags, so
        //    a Blu-ray-style remux or an IPTV transport stream carrying DTS
        //    played with no sound at all, on boxes that decode DTS perfectly.
        //    This is the same class of audio the FFmpeg decoder above exists
        //    for, one descriptor layer earlier.
        //  - the timestamp search window. The default is three TS packets;
        //    captures whose first usable PES timestamp sits further in (raw
        //    cable/QAM captures, badly remuxed .ts files) never establish a
        //    start time, and then either do not start or seek to nowhere
        //    (google/ExoPlayer#8571). 1500 packets is the value Just Player
        //    ships for exactly these files.
        val plainExtractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS
            )
            .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE)
        val extractorsFactory: androidx.media3.extractor.ExtractorsFactory =
            if (!dvRewriteEnabled && !convertTo81 && !stripHdr10Plus) {
                plainExtractors
            } else {
                DolbyVisionCompatExtractorsFactory(
                    plainExtractors,
                    stripHdr10Plus = stripHdr10Plus,
                    convertAllProfiles = convertAllProfiles,
                    dvRewriteEnabled = dvRewriteEnabled,
                    convertP7To81 = convertP7To81,
                    convertP5To81 = convertP5To81,
                    nativeDvSupported = nativeDvSupported,
                    nonDvDisplayOnDvDevice = dvDisplayMismatch,
                    // The display's own capability, in EVERY mode — not the
                    // AUTO-only `dvDisplayMismatch` combination above, which is
                    // false in "Strip All" by construction. Mode 3 needs the
                    // fact itself: a DV decoder on a non-DV display is the
                    // combination the keep-DV strip exists for, and the factory
                    // is not allowed to infer it from an AUTO-mode flag.
                    displaySupportsDv = displaySupportsDv
                )
            }
        // Which arm this session is on, resolved from the exact inputs the
        // factory above was just built with. Stored rather than re-derived at
        // the track callback: a second derivation from state read later is a
        // second answer waiting to disagree with the one that is actually
        // stripping the stream.
        dvSessionHandling = selectHdr10BaseHandling(
            nativeDvSupported = nativeDvSupported,
            nonDvDisplayOnDvDevice = dvDisplayMismatch,
            convertAllProfiles = convertAllProfiles,
            displaySupportsDv = displaySupportsDv
        )
        val mediaSourceFactory = DefaultMediaSourceFactory(cachedFactory, extractorsFactory)
        // DefaultMediaSourceFactory selects HLS/DASH by URI or MIME type and
        // otherwise falls back to progressive extraction. Build that fallback
        // explicitly so direct stream endpoints (which commonly have no file
        // extension) cannot skip the custom DV extractor.
        val progressiveMediaSourceFactory =
            ProgressiveMediaSource.Factory(cachedFactory, extractorsFactory)
        Log.i(
            "PLAYER_DV",
            "Compat extractor configured=${extractorsFactory.javaClass.simpleName} " +
                "progressiveSource=${progressiveMediaSourceFactory.javaClass.simpleName}"
        )

        val mimeType =
            if (retryAttempt < RAW_EXTRACTOR_PROBE_ATTEMPT) resolveMimeType(currentUrl) else null
        val mediaItemBuilder = newMediaItemBuilder(mimeType)

        val resolvedBufferMode = if (bufferMode == 2) {
            // Auto: a LIVE channel takes the low-latency profile - it is at the
            // live edge, its playlist never ends, and a deep buffer there is
            // only latency.
            //
            // The rule used to send every HLS manifest down this path too, on
            // the theory that HLS means live. An addon's VOD HLS is not live,
            // and the low-latency profile's start gate is shorter than the
            // cushion the session then needs - so the picture appeared, the
            // buffer the gate allowed drained a second later, and playback
            // stalled once before settling (the reported "press play, video
            // starts, buffers for a second, then plays normally"). Container is
            // not liveness: a VOD file gets the balanced profile and its gate,
            // whatever its manifest looks like.
            if (isLiveChannel) 1 else 0
        } else bufferMode
        // Media3 validates minBufferMs >= bufferForPlaybackAfterRebufferMs
        // (DefaultLoadControl.Builder throws IllegalArgumentException
        // otherwise). The old IPTV config (2500/10000/1500/3000) violated
        // that and force-closed the player on every IPTV start.
        // The VOD profile's after-rebuffer threshold was 6_000, and that number
        // IS the stall a viewer sees on a slow source: the player must accumulate
        // that many SECONDS of media before it resumes, so at a fill rate of
        // ~0.5x realtime the spinner sits there for ~12s (measured: an 11.4s
        // "Rebuffer stall" on a high-bitrate file). It is 5_000: high enough that
        // a burst-gap source - one that delivers for a few seconds and then goes
        // quiet for tens of seconds - has a cushion to ride the next gap out
        // instead of stalling again a moment later. The shorter 3_000 that
        // preceded it resumed with so little in hand that the very next gap
        // caught it, which is the closely-spaced "stall cluster" a viewer
        // reports. The 12s no-progress watchdog is unaffected, because a
        // genuinely dead source never reaches this threshold at all.
        //
        // The START threshold is the third figure, and it is now higher than the
        // after-rebuffer one on purpose. Starting the moment three seconds of
        // media exist leaves a session with no cushion for the fill rate to dip
        // below realtime, which is exactly what a launch does: the TCP ramp and
        // the app's own startup bursts (hero TMDB work, a subtitle fetch, the
        // intro-database lookup) share the link, so the buffer that was just
        // filled drains about a second into playback and the viewer gets the
        // pattern this is meant to prevent — picture, a spinner, then normal
        // playback for the rest of the file. Six seconds costs a moment on a
        // fast source (the loader is already targeting fifteen) and buys three
        // more seconds of runway on a slow one.
        //
        // The min/max targets are 15/60 rather than 10/30. A low-bitrate source
        // does not stall for want of throughput - a 1 Mbps stream that stalls is
        // being delivered in bursts with 20-30s gaps between them, and 30s of
        // media only *almost* covers the gap: when it does not, the viewer gets
        // the 1-4s stall the diagnostics show. A typical player keeps 50s+, which
        // is how it rides the same gaps out. Raising the duration costs the
        // high-bitrate case nothing, because the byte cap above - not the
        // duration - is what bounds it (a 40 Mbps stream is capped at roughly
        // 13s of media either way), so the cushion is only added where it was
        // missing. At 6 Mbps, 60s is ~45MB, well under the budget.
        // The low profile's START gate is 3_000, not the 1_500 it was. A gate
        // is a promise about the runway playback gets, and 1.5s is shorter than
        // any source's jitter: the session started on it, the buffer drained a
        // second in, and the viewer got exactly the stall this profile exists
        // to avoid - on live channels, where a zap is the only thing that gate
        // was ever buying speed for. Matching the after-rebuffer figure keeps
        // the low profile's own contract (it never resumes on less than it
        // starts on) and costs a zap half a second.
        val bufferDurations = if (resolvedBufferMode == 1) intArrayOf(5_000, 10_000, 3_000, 3_000)
        else intArrayOf(15_000, 60_000, 6_000, 5_000)

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
        // has — maxMemory() honors android:largeHeap, so the budget follows the
        // device — and stop prioritizing time over size, which is exactly what
        // made targetBufferBytes ineffective. Low-bitrate streams never reach
        // the cap (IPTV's 10 s, ordinary HD), so only 4K/high-bitrate changes.
        // If the budget were ever too small for a stream, media3 logs "Target
        // buffer size reached with less than 500ms of buffered media data" and
        // keeps playing; it does not throw.
        //
        // The divisor was 8 until a real high-bitrate file showed what that
        // costs: on a 384MB-heap box the budget is 48MB, and at 40-60Mbps that
        // is 6-10 seconds of media -- so far short of the 30s the duration
        // target asks for that any dip longer than a few seconds stalls. 5
        // raised it to 76MB, but a PenguPlay-class host still stalled: its
        // ~40Mbps variant needs ~18s of media, and a 256MB-heap box had only
        // ~51MB (about 10s). 3 gives ~85MB there and ~128MB on the 384MB box,
        // still under the ~150MB unbounded buffering that caused the OOM
        // above, and the clamp (48-160MB) keeps every device bounded
        // regardless of heap. Low-bitrate streams never reach either bound.
        val maxBufferBudgetBytes =
            (Runtime.getRuntime().maxMemory() / 3)
                .coerceIn(48L * 1024 * 1024, 160L * 1024 * 1024)
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
        val renderersFactory = SplitModeRenderersFactory(
            this,
            audioExtMode,
            buildLibassTextRenderer = {
                if (!AssNative.available) {
                    null
                } else {
                    LibassSubtitleRenderer(
                        sink = AssStreamingSink(
                            renderer = assRenderer,
                            fonts = {
                                AssSubtitleSource.collectFonts(
                                    listOf(File(filesDir, ASS_FONT_DIR))
                                )
                            },
                            configPath = { AssSubtitleRenderer.installFontConfig(assets, filesDir) },
                            cacheDir = { cacheDir.absolutePath }
                        ),
                        // The first format arriving means the libass instance is
                        // up; the overlay tick can start drawing its frames.
                        onActive = { handler.post(assFrameTick) }
                    )
                }
            }
        )
        // This player's pair, and the session's offset re-applied to it: the
        // processors are built with the factory, so a rebuild has to seed the
        // new pair with the value the panel last set.
        audioProcessors = renderersFactory.audioProcessors
        renderersFactory.audioProcessors.delay.setDelayMs(PlayerTrackBridge.audioDelayMs)
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
                // The sidecar is built BEFORE the media source and travels on the
                // item the VIDEO source is created from, so every path carries it
                // - the separate-audio merge included. Built afterwards it was
                // set on a refreshed item that only the no-separate-audio
                // branches used, so a remembered or hand-picked subtitle file was
                // silently dropped on exactly the adaptive titles that need it,
                // with one `Log.w` and nothing on screen (audit E-F2,
                // 2026-10-27).
                val sidecar = externalSubtitleUri?.let { subtitleUri ->
                    MediaItem.SubtitleConfiguration.Builder(subtitleUri)
                        // Tagged so handleAssTrackSelection() never mistakes this
                        // SSA sidecar for an embedded track (see SIDECAR_TRACK_ID).
                        .setId(SIDECAR_TRACK_ID)
                        .setMimeType(resolveSubtitleMimeType(subtitleUri))
                        .setLanguage("und")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                }
                val initialMediaItem = if (sidecar != null) {
                    mediaItemBuilder.setSubtitleConfigurations(listOf(sidecar)).build()
                } else {
                    mediaItemBuilder.build()
                }
                val urlMimeType = resolveMimeType(currentUrl)
                val isManifest = mimeType == MimeTypes.APPLICATION_M3U8 ||
                    mimeType == MimeTypes.APPLICATION_MPD ||
                    mimeType == MimeTypes.APPLICATION_SS ||
                    urlMimeType == MimeTypes.APPLICATION_M3U8 ||
                    urlMimeType == MimeTypes.APPLICATION_MPD ||
                    urlMimeType == MimeTypes.APPLICATION_SS
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
                    val audioSource = ProgressiveMediaSource.Factory(cachedFactory)
                        .createMediaSource(MediaItem.fromUri(audioUrl))
                    setMediaSource(MergingMediaSource(videoSource, audioSource))
                } else if (isManifest) {
                    setMediaItem(initialMediaItem)
                } else {
                    setMediaSource(progressiveMediaSourceFactory.createMediaSource(initialMediaItem))
                }

                // The sidecar's track now exists in the timeline - merged audio
                // or not - so the ENGINE renders it: hand the subtitle area back
                // from the app-side renderer the auto-fetch path used (see
                // [sidecarRenderedByApp]). Two renderers for one file would
                // disagree about timing.
                if (sidecar != null) sidecarRenderedByApp = false

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

            // Every player this activity builds has media3's own surface vote
            // silenced, rebuilt players included (2026-10-22). This used to sit
            // inside the matcher-creation block below, so every REBUILT player
            // (sidecar subtitle, engine or audio-mode change, DV retry) skipped
            // it and kept Media3's default seamless strategy - a second,
            // un-silenced voter on the surface, on top of the rate the matcher
            // puts there. It does not depend on the matcher: only the mode count
            // does.
            player.setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)

            // One instance per activity (see FrameRateMatcher): the mode it
            // restores on the way out has to be the one from before any
            // switch, so a rebuilt player must not hand it a second one.
            if (frameRateMatcher == null && AppPreferences.getMatchFrameRate(this@NativePlayerActivity)) {
                frameRateMatcher = FrameRateMatcher(
                    this@NativePlayerActivity,
                    videoSurface = { videoOutputSurface() },
                    // The ladder's second look is dropped while playback is
                    // paused: a backstop must not re-request the surface on a
                    // stopped picture (see FrameRateMatcher).
                    isPlaybackActive = { exoPlayer?.playWhenReady == true }
                )
                // The player's own onTracksChanged report is the confirmation;
                // but an addon stream often carries no fps at all, so race a
                // probe of the stream's head alongside player prep and feed the
                // matcher the rate it would otherwise never be told. Launched
                // once per activity, with the creation above, and deliberately
                // non-blocking: playback never waits for it. Probe first → the
                // switch happens under the loading spinner; player first → that
                // wins (the matcher dedupes repeat rates); probe late → its
                // result still applies. Live channels are never probed: a live
                // playlist is 60 fps anyway and its bytes never end.
                val matcher = frameRateMatcher
                if (matcher != null && !isLiveChannel && FrameRateProbe.isProbeable(currentUrl)) {
                    val probeUrl = currentUrl
                    val probeHeaders = streamHeaders
                    lifecycleScope.launch {
                        FrameRateProbe.probe(probeUrl, probeHeaders, cacheDir)
                            ?.let { fps ->
                                // Remembered as well as offered: this is the one
                                // rate an unmeasured stream will never produce
                                // again, and it is what a resume re-arms from
                                // (see rearmFrameRateMatch).
                                lastContentFps = fps
                                matcher.onContentFrameRate(fps, FrameRateSource.PROBE)
                            }
                    }
                } else {
                    // The probe's own log lines only exist once it RUNS, so a
                    // probe that was never launched - matching off, a live
                    // channel, an unprobeable URL - is otherwise invisible in
                    // a field log. Same PLAYER_AFR tag as the probe.
                    Log.i(
                        "PLAYER_AFR",
                        "probe not launched: matcher=${matcher != null} " +
                            "live=$isLiveChannel probeable=${FrameRateProbe.isProbeable(currentUrl)}"
                    )
                }
            }
            player.addListener(createPlayerListener())
            player.addAnalyticsListener(createAnalyticsListener())
            // Aggregate quality accumulator: observes only, never affects
            // playback. Built per player, like the analytics listener above.
            player.addAnalyticsListener(createPlaybackStatsListener())
            // Subtitles render through SubtitleCueHandler (below) so the
            // size / background / offset controls actually work; Media3's
            // built-in SubtitleView cannot be styled. It starts empty and
            // hidden for that reason - and it is also the ONLY view that can
            // draw a bitmap cue (Cue.bitmap), so the handler makes it visible
            // again for a bitmap cue group and hides it for text (see
            // SubtitleCueHandler.onCues).
            playerView.subtitleView?.setCues(null)
            playerView.subtitleView?.visibility = View.GONE
            subtitleCueHandler = SubtitleCueHandler().also { player.addListener(it) }
            // An ASS sidecar outlives the player it was loaded against - only
            // the native instance was released with the old one - so the tick
            // has to be re-armed to rebuild it against this player.
            if (assSubtitleContent != null || assOverlaySource == AssOverlaySource.EMBEDDED) {
                handler.removeCallbacks(assFrameTick)
                handler.post(assFrameTick)
            }
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

            // Belt and braces for [playerGeneration]: an instance that outlived
            // its rebuild would hold a video decoder this box will not hand out
            // twice, so let it go before this player takes the field. Reaching
            // this at all means a rebuild raced another one.
            exoPlayer?.let { stale ->
                Log.w("PLAYER_REBUILD", "Releasing a player that outlived its rebuild")
                runCatching { stale.release() }
            }
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

                // A finished session stays finished here too: the transport
                // buttons a Bluetooth remote, a headset, or an assistant drives
                // through this MediaSession must not restart the episode from
                // the top. media3's own play-button handling makes that restart
                // explicit - once the player reports STATE_ENDED it calls
                // seekToDefaultPosition() and then play() - so both are refused
                // while the ended session is on screen. Only the restart is
                // blocked: an explicit seek still moves the playhead, and a
                // fresh session (back out, play again) is untouched.
                override fun play() {
                    if (playbackEndedHandled) return
                    super.play()
                }

                override fun setPlayWhenReady(playWhenReady: Boolean) {
                    if (playWhenReady && playbackEndedHandled) return
                    super.setPlayWhenReady(playWhenReady)
                }

                override fun seekToDefaultPosition() {
                    if (playbackEndedHandled) return
                    super.seekToDefaultPosition()
                }

                override fun seekToDefaultPosition(mediaItemIndex: Int) {
                    if (playbackEndedHandled) return
                    super.seekToDefaultPosition(mediaItemIndex)
                }
            }
            mediaSession =
                MediaSession.Builder(this, sessionPlayer)
                    .setId("kbstream-" + System.nanoTime() + "-" + sessionSequence.getAndIncrement())
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
     *
     * The wait is measured from the release rather than from this call, so a
     * second rebuild landing inside the window waits out what is left of it
     * instead of asking for a decoder while the last one is still going
     * ([rebuildSettleRemainingMs]). Only the newest rebuild's queued create
     * runs: an earlier one would build a second player, and the second one is
     * what the box refuses.
     */
    private fun recreatePlayer(settleMs: Long = 0L) {
        // Counted for the diagnostics report: a rebuild throws away the whole
        // read-ahead buffer, so "it stalled just after starting" is a rebuild
        // as often as it is a slow source, and the two want opposite fixes.
        com.kennyb1201.kbstream.data.reporting.PerfTrace.record("playback.rebuild", 0L)
        // Disarm any outstanding stall/black-video timers tied to the old
        // player instance; fresh ones are armed when the new session is ready.
        stallWatchdogToken++
        liveWatchdogToken++
        subtitleCueHandler?.cancelPending()
        subtitleCueHandler = null
        // Anything an earlier rebuild queued is stale once this one runs.
        val generation = ++playerGeneration
        if (settleMs > 0L) {
            // Detach first: otherwise the dying codec is still holding the
            // SurfaceView's Surface when the next codec configures onto it.
            playerView.player = null
        }
        if (exoPlayer != null) {
            exoPlayer?.release()
            exoPlayer = null
            playerReleasedAtMs = System.currentTimeMillis()
        }
        // The session's fetch lanes go with it: every pool emptied and every
        // in-flight reader cancelled, so nothing keeps a socket or a thread
        // alive behind the player that owned them (see StreamFetchLanes.kt).
        // A rebuild that needs lanes builds a fresh pool below.
        lanePool?.release()
        lanePool = null
        val wait = rebuildSettleRemainingMs(
            settleMs = settleMs,
            releasedAtMs = playerReleasedAtMs,
            nowMs = System.currentTimeMillis()
        )
        if (wait > 0L) {
            Log.i(
                "PLAYER_REBUILD",
                "Waiting ${wait}ms for the previous decoder to release its " +
                    "buffers before rebuilding the player"
            )
            handler.postDelayed(
                {
                    if (generation != playerGeneration) return@postDelayed
                    if (!isFinishing && !isDestroyed) createPlayer()
                },
                wait
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
                armLiveWatchdog()
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
                    // A channel change reaches its first READY here: the zap is
                    // over for the viewer. Recorded before the source-ready
                    // block below because the light path never rebuilt the
                    // player, so firstReadyAtMs is already spent and that block
                    // is skipped.
                    if (zapTraceStartMs > 0L) {
                        com.kennyb1201.kbstream.data.reporting.PerfTrace.record(
                            "playback.zap_ready",
                            System.currentTimeMillis() - zapTraceStartMs
                        )
                        zapTraceStartMs = 0L
                    }
                    // First READY of this attempt: the point that splits
                    // "source loaded" from "decoder painted".
                    if (firstReadyAtMs == 0L) {
                        firstReadyAtMs = System.currentTimeMillis()
                        if (startupTraceStartMs > 0L) {
                            com.kennyb1201.kbstream.data.reporting.PerfTrace.record(
                                "playback.source_ready",
                                firstReadyAtMs - startupTraceStartMs
                            )
                        }
                    }
                    if (rebufferStartedAtMs != 0L) {
                        val rebufferStartMs = rebufferStartedAtMs
                        val stalledMs = System.currentTimeMillis() - rebufferStartMs
                        Log.w("PLAYER_PERF", "Rebuffer stall: ${stalledMs}ms")
                        // Only a stall AFTER the first frame is a mid-playback
                        // rebuffer. The first buffering of a session is the
                        // initial load, which source_ready above already
                        // reports — counting it here would make every session
                        // look like it stalled.
                        if (firstFrameRendered) {
                            com.kennyb1201.kbstream.data.reporting.PerfTrace.record(
                                "playback.stall",
                                stalledMs
                            )
                            // A source that keeps stalling is handed to the
                            // next-ranked one; true means that switch already
                            // started and the rest of this READY belongs to a
                            // player being torn down (see RebufferDownshift.kt).
                            if (maybeDownshiftOnRebuffer(rebufferStartMs, stalledMs)) {
                                rebufferStartedAtMs = 0L
                                return
                            }
                        }
                        rebufferStartedAtMs = 0L
                    }
                    updateUIReady()
                    retryAttempt = 0
                    retryExhausted = false
                    liveRetryLooping = false
                    errorMessageStr = null
                    autoSelectPreferredLanguages()
                    subtitleCueHandler?.updateFromPosition()
                    armBlackVideoWatchdog()
                    armStallWatchdog()
                    armLiveWatchdog()
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
                    // A live channel never ends, and ENDED is exactly how one
                    // dies on this class of box: a plain HTTP MPEG-TS response
                    // that the provider closes (often because the connection
                    // looks idle) reads as the end of the stream, and a stalled
                    // HLS playlist that goes static does the same. No error is
                    // raised, nothing else watches it - the live keep-alive
                    // treats ENDED as out of scope - so the session parked here
                    // sits on its last frame until a channel change builds a
                    // new player. That is the "it stops after a while and only
                    // zapping brings it back" report. Re-tune through the same
                    // ladder a stall uses instead of running the end-of-title
                    // path, which is for VOD only.
                    if (isLiveChannel) {
                        if (!isFinishing && !isDestroyed &&
                            reconnectingContainer.visibility != View.VISIBLE
                        ) {
                            Log.w("PLAYER_LIVE", "Live channel reported ENDED \u2014 re-tuning")
                            scheduleRetry()
                        }
                    } else {
                        // Marked BEFORE the handoff: onStop() consults this flag
                        // so a session that reached its own end is recorded as
                        // watched even when the last save has to happen as the
                        // activity exits. Both fallback paths in
                        // detectStallEndedFallback() already set it; this is the
                        // primary one and it did not.
                        playbackEndedHandled = true
                        onPlaybackEnded()
                    }
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
                // Stamp the seek so the buffering it flushes the read-ahead
                // buffer into is not counted as a slow source by the downshift
                // counter (see RebufferDownshift.kt).
                lastSeekAtMs = System.currentTimeMillis()
                // The end-of-playback latch stops a stray play/OK at EOF from
                // replaying the episode from 0, but it also latched OUT the
                // viewer's own move: ended, seek back to a spot, press play -
                // and the guard refused, leaving a paused player at 0 with no
                // in-player way out (PB-P2-1). A seek is the viewer taking
                // control, so the latch clears.
                playbackEndedHandled = false
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
                        // Offer the panel the content's own rate, the earliest
                        // point it is known. A track that reports none carries
                        // media3's NO_VALUE, which the matcher refuses — so an
                        // unmeasured track leaves the panel alone instead of
                        // switching it to something arbitrary.
                        val reportedFrameRate = fmt.frameRate.toDouble()
                        // Remembered only when it describes content: a track that
                        // declares no rate reports media3's NO_VALUE (-1.0),
                        // which must not become the rate a resume re-offers.
                        // [FrameRateMatch.resumeRate] refuses it again on the way
                        // out of the field, so the stored value can be kept raw.
                        if (FrameRateMatch.normalise(reportedFrameRate) != null) {
                            lastContentFps = reportedFrameRate
                        }
                        frameRateMatcher?.onContentFrameRate(reportedFrameRate)
                        // A video track arriving is the field-proven "bytes are
                        // flowing" signal (2026-10-16: the Compat format
                        // emission followed the failed probe by ~3 s, and the
                        // host was slow rather than dead), so this is where a
                        // probe that lost its budget to a slow cold start gets
                        // its one second chance. Additive: the first attempt is
                        // unchanged, this only runs when it ended in a NETWORK
                        // failure, no rate is known from any source, and the URL
                        // has not already spent its retry (one per URL per
                        // process). A URL whose probe succeeded - or which was
                        // never probed, like a live channel's - is answered false
                        // and nothing happens, as before.
                        if (videoTrackPresent &&
                            lastContentFps == null &&
                            frameRateMatcher != null &&
                            FrameRateProbe.consumeNetworkFailure(currentUrl)
                        ) {
                            Log.i("PLAYER_AFR", "retrying frame-rate probe after network failure")
                            // Captured at trigger time, like the first attempt: a
                            // source switch while the retry is in flight must
                            // not make it read the new stream's head.
                            val retryUrl = currentUrl
                            val retryHeaders = streamHeaders
                            lifecycleScope.launch {
                                FrameRateProbe.probe(
                                    retryUrl,
                                    retryHeaders,
                                    cacheDir,
                                    budgetMs = 12_000L
                                )?.let { fps ->
                                    // Remembered as well as offered, exactly like
                                    // the first attempt's rate: it is what a
                                    // resume re-arms from.
                                    lastContentFps = fps
                                    frameRateMatcher?.onContentFrameRate(
                                        fps,
                                        FrameRateSource.PROBE_RETRY
                                    )
                                }
                            }
                        }
                        val codec = fmt.codecs.orEmpty()
                        val colorInfo = fmt.colorInfo
                        streamWidth = fmt.width
                        streamHeight = fmt.height
                        streamBitrate = fmt.bitrate
                        streamCodec = codec.ifBlank { null }
                        streamMimeType = fmt.sampleMimeType
                        // Both live overlays name the resolution: repaint
                        // them now that this track's size is known.
                        refreshResolutionLabels()
                        // A declared-DV track that the DV → HDR10 strip rewrote
                        // carries its original codec ("dvhe.07.06") in the
                        // format label — remember it for the codec badge.
                        val declaredDvCodec =
                            fmt.label?.takeIf { dvLabelFromCodec(it) != null }
                        streamDeclaredDvCodec = declaredDvCodec
                        // The keep-DV strip arm leaves the codec string alone,
                        // so nothing above tells the badge that this declared
                        // Dolby Vision track is actually being decoded as
                        // HDR10. Record it here, where the track's own DV
                        // identity is known: the arm is session-level, but a
                        // non-DV track in a Strip All session must not be
                        // annotated as one.
                        dvRpuStripKeepDvActive =
                            dvSessionHandling == Hdr10BaseHandling.STRIP_RPU_KEEP_DV &&
                                dvLabelFromCodec(codec) != null
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
            handleAssTrackSelection(tracks)
            // A track no renderer claims is the one failure media3 does not
            // report: the audio renderer is left disabled and the file plays on
            // in silence, with no PlaybackException for the ladder below to
            // route. The picture is supported in the shape this fires for, so
            // the session is watchable and mute rather than broken - and the
            // engine that can decode the track is the only lever left. Guarded
            // by the same "may the engine change without being asked?" setting
            // the decoder fallbacks use, and one-shot through [handOffToMpv]'s
            // own latch.
            SoftwareAudioRules.usesBackupEngine(tracks)?.let { reason ->
                Log.w(
                    "PLAYER_RETRY",
                    "no audio renderer supports this file ($reason) - " +
                        "continuing in the MPV backup engine"
                )
                PlaybackEngineTrace.note(
                    PlaybackEngineTrace.describe(
                        cause = "no audio renderer for this file",
                        detail = reason
                    )
                )
                handOffToMpv(MpvPlayerActivity.FALLBACK_REASON_DECODER)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // An error that arrives in the last 5% of the file is the episode's
            // END, not a recovery to run: file the completion and go to the
            // end-of-episode state. It has to be decided HERE, before the
            // ladder below, because the ladder's rebuild is what resurrected
            // the stale resume point in the field report (see
            // [fileErrorTailCompletion]). Below the threshold not one byte of
            // the recovery changes.
            if (fileErrorTailCompletion()) return
            // A googlevideo 403 in a trailer session is a refused client
            // identity: refresh it and leave cleanly (see the helper).
            if (refreshIdentityOnTrailerRefusal(error)) return
            // A cached link that fails to OPEN is a dead link: forget it now, so
            // the next replay resolves fresh instead of looping back into this
            // same error card. Only a failure ABOUT THE LINK counts (PB-P2-2):
            // a decoder, container or track failure says something about this
            // box, and forgetting on one of those discarded a link that was
            // alive and made the next replay re-resolve for nothing.
            if (PlaybackRecoveryRules.isLinkFailure(error)) {
                invalidateCachedLinkBeforeFirstFrame()
                // ...and mark the addon dead for the REST of this session, so
                // the advance below lands on the next source from a DIFFERENT
                // addon instead of walking the same addon's remaining links
                // (see SourceAddonSession). Independent of the persistent
                // record written just below: this set dies with the session.
                if (!isLiveChannel) {
                    SourceAddonSession.markDead(sessionDeadAddons, currentAddonName)
                }
                // The link itself is the thing that failed, so this addon is
                // the one to stop heading this title's list (see
                // SourceAddonMemory). Only a link failure counts: a decoder,
                // container or track failure says something about this box,
                // and demoting a good addon for it would put a working copy
                // behind a broken one on the next episode.
                if (!isLiveChannel) {
                    com.kennyb1201.kbstream.data.player.SourceAddonMemory.rememberFailed(
                        this@NativePlayerActivity,
                        parentId,
                        currentAddonName
                    )
                }
            }
            lastPlaybackError = error
            var msg = friendlyErrorMessage(error, hostOf(currentUrl))
            // Resource exhaustion is a different animal from "this box can't
            // decode Dolby Vision", and both the recovery and the persisted
            // verdict below depend on telling them apart.
            val resourceExhausted = PlaybackRecoveryRules.isDecoderResourceExhausted(error)
            val decoderFailure = PlaybackRecoveryRules.isDecoderError(error.errorCode)
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
            // A container the extractor cannot open is a DEMUX failure, and a
            // different animal from everything below: WMV/ASF, RealMedia and
            // every other container Media3 has no progressive extractor for
            // fail before a single track exists, so no decoder is ever asked
            // for and the decoder ladder cannot touch it — a rebuild picks
            // the same extractor and fails identically. AVI is deliberately
            // NOT in that list — media3 ships an AviExtractor — so an AVI
            // that reaches here is failing on its CODEC, which is the branch
            // after this one. It gets the same ONE
            // meaningful retry a decoder failure gets (the probe with the MIME
            // hint dropped, so the extractor sniffs the real container instead
            // of trusting a URL extension that may have lied), and a second
            // failure hands the session to the backup engine, whose libmpv
            // carries the full FFmpeg demuxer set.
            if (PlaybackRecoveryRules.isContainerParseFailure(error)) {
                errorMessageStr = msg + "\n\n" +
                    failureDiagnostic("the container could not be read")
                if (!containerParseRetried) {
                    containerParseRetried = true
                    if (retryAttempt < RAW_EXTRACTOR_PROBE_ATTEMPT) {
                        retryAttempt = RAW_EXTRACTOR_PROBE_ATTEMPT
                    }
                    scheduleRetry()
                    return
                }
                Log.w(
                    "PLAYER_RETRY",
                    "Container parse failure survived the raw-extractor probe " +
                        "(code=${error.errorCodeName}) \u2014 handing over to the MPV backup engine"
                )
                if (!handOffToMpv(MpvPlayerActivity.FALLBACK_REASON_CONTAINER)) {
                    retryExhausted = true
                    updateUIError()
                }
                return
            }
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
            // Out-of-resources wins outright, and [dvDecoderRefused] must NOT
            // be allowed to override it. It cannot: [dvPassthroughDecoderRefused]
            // looks only at whether this was a decoder failure on an active DV
            // passthrough session -- it never reads the error code -- so a plain
            // 0x80001000 (the box having no 4K decoder to hand out, which is what
            // a second decode in a process gets) satisfies it too. The old
            // `(!resourceExhausted || dvDecoderRefused)` carve-out therefore let
            // exactly the case it was written to exclude through: one transient
            // resource failure on a DV passthrough was read as "this box cannot
            // do Dolby Vision", recorded for 14 days, and it forced the strip on
            // every later DV title -- on boxes where plain passthrough plays the
            // file untouched. A resource failure is not a verdict on Dolby Vision;
            // the branch below handles it (next source, then MPV) and clears any
            // verdict a previous failure left behind.
            if (!forceDvStripForSession &&
                !resourceExhausted &&
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
                // The verdict is only safe to keep because [resourceExhausted]
                // is required to be false to reach here -- this is a DV decoder
                // refusing the FORMAT, not the box running dry. A resource
                // failure is handled below and clears the verdict instead.
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
                // A DV verdict is the one decoder failure the app REMEMBERS for
                // 14 days, so the report has to show it happened and to which
                // codec: a viewer whose Dolby Vision silently turned off for
                // every later title has this line to explain it (see
                // PlaybackEngineTrace).
                PlaybackEngineTrace.note(
                    PlaybackEngineTrace.describe(
                        cause = "Dolby Vision decoder refused the format",
                        detail = "${declaredDvCodec ?: "unknown"} — stripped to HDR10"
                    )
                )
                reconnectingContainer.visibility = View.VISIBLE
                hideBufferingSpinner()
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
            //
            // Which recovery is right depends on whether this process's pool
            // has ever worked — see DecoderPoolHealth. Do NOT walk the source
            // ladder blindly: a switch is a rebuild is a decoder request, and on
            // a pool that is already wedged each one adds pressure instead of
            // relieving it (the field session's nine-rebuild spiral).
            if (resourceExhausted && !decoderResourceFallbackDone) {
                decoderResourceFallbackDone = true
                // Recorded process-wide before anything else happens: from here
                // on a stall in this session is read as pool pressure rather
                // than a bad source, so the rebuffer downshift stops feeding the
                // spiral (see maybeDownshiftOnRebuffer).
                DecoderPoolHealth.noteExhaustion()
                // A 0x80001000 is the box being out of decoders, not a verdict on
                // Dolby Vision: the field log has the vendor DV decoder AND the
                // plain HEVC decoder returning it for the same 4K source. A
                // resource-starved session must not leave a learned DV failure
                // behind to suppress Dolby Vision for 14 days on a TV that plays it.
                if (dvLabelFromCodec(streamDeclaredDvCodec ?: streamCodec) != null) {
                    AppPreferences.clearDvPassthroughFailure(this@NativePlayerActivity)
                }
                errorMessageStr = null
                // The pool worked in this process and is now wedged by our own
                // rebuilds. A smaller source asks the SAME pool for the SAME
                // decoder, so the source ladder is skipped entirely and the
                // session goes straight to the software engine, which needs no
                // MediaCodec at all: zero source switches, zero rebuilds.
                if (DecoderPoolHealth.everRenderedFirstFrame) {
                    Log.w(
                        "PLAYER_RETRY",
                        "Decoder resources exhausted after this process already " +
                            "decoded a frame — the pool is wedged by our own " +
                            "rebuilds, so a smaller source cannot help: handing to " +
                            "the MPV engine with zero source switches and zero rebuilds"
                    )
                    PlaybackEngineTrace.note(
                        PlaybackEngineTrace.describe(
                            cause = "decoder pool exhausted after a successful decode",
                            detail = "pool wedged by this session's own rebuilds — " +
                                "handing to MPV, no source switch"
                        )
                    )
                    if (handOffToMpv(MpvPlayerActivity.FALLBACK_REASON_DECODER)) return
                    retryExhausted = true
                    errorMessageStr = DECODER_EXHAUSTED_MESSAGE
                    updateUIError()
                    return
                }
                // A fresh pool refused the first configure: this file may
                // genuinely exceed the box (or a concurrent holder has the
                // decoder), so one smaller source is legitimate. Bounded to the
                // one attempt by decoderResourceFallbackDone above.
                Log.w(
                    "PLAYER_RETRY",
                    "Decoder resources exhausted on a pool that has never decoded " +
                        "in this process — trying one smaller source"
                )
                val switching = tryNextSource(
                    delayMs = DECODER_RESOURCE_RETRY_DELAY_MS,
                    statusText = "This TV is out of video decoder resources — " +
                        "trying a smaller source…",
                    // Automatic recovery: the merged walk, across add-ons.
                    tab = null
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
                errorMessageStr = DECODER_EXHAUSTED_MESSAGE
                updateUIError()
                return
            }
            // No decoder for this codec at all: AVI's MPEG-4 ASP, VC-1/WMV,
            // Theora, 10-bit AVC on most boxes. This is the decoder failure a
            // rebuild cannot touch - every attempt below asks for the same
            // component, so the ladder can only spend its six backoffs (about a
            // minute of "Reconnecting...") to arrive back here. The backup
            // engine is the thing that CAN play these files: libmpv carries the
            // full FFmpeg decoder set, software included. That is the same
            // trade Vimu makes with its own engine, which is why those boxes
            // play files this one refuses.
            //
            // handOffToMpv still refuses live TV, a DRM session, a device
            // without libmpv, and an "ExoPlayer only" engine setting; those
            // fall through to the ladder below exactly as before.
            if (
                PlaybackRecoveryRules.isMissingDecoderFailure(error) &&
                handOffToMpv(MpvPlayerActivity.FALLBACK_REASON_DECODER)
            ) {
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
            // A decoder failure gets ONE meaningful retry out of the ladder:
            // the MIME-hint-free probe described above. That is the retry that
            // matters for a stream whose URL extension lied about its container
            // (a ".ts" that is really an MKV hands the TS extractor garbage).
            //
            // A decoder failure that SURVIVES that probe is the decoder
            // rejecting the FORMAT: 10-bit AVC, VP9 profile 2, AV1 on a box
            // with no decoder for it, an unsupported Dolby Vision profile. The
            // remaining attempts ask for the same component (16s and 30s of
            // backoff apart) and land right back here, on the error card. The
            // backup engine is the one thing that can play those files, so the
            // second failure hands the session over instead of grinding out the
            // rest of the ladder. handOffToMpv refuses live TV, a DRM session,
            // a device without libmpv and an "ExoPlayer only" engine setting,
            // and those keep the old ladder.
            if (decoderFailure) {
                // A DV session that has already spent its one strip retry has
                // no ladder step left that can help it. The raw-extractor probe
                // below addresses a URL whose extension lied about the
                // CONTAINER; a Dolby Vision decoder refusing the stream is a
                // verdict on the FORMAT, and that probe asks for the same
                // component -- which is how this box turned one DV failure into
                // a chain of identical rebuilds (Attempt 4: probing with raw
                // extractor) without ever reaching the backup engine. Hand the
                // session over instead: libmpv decodes the HDR10 base layer in
                // software, which is what an external player does with the same
                // file. handOffToMpv still refuses live TV, a DRM session and an
                // ExoPlayer-only setting, and those keep the ladder below.
                if (DvEscalation.handOffAfterStripRetry(
                        stripRetrySpent = forceDvStripForSession,
                        dvDecoderRefused = dvDecoderRefused
                    ) &&
                    handOffToMpv(MpvPlayerActivity.FALLBACK_REASON_DECODER)
                ) {
                    return
                }
                if (decoderFailureRetried) {
                    if (handOffToMpv(MpvPlayerActivity.FALLBACK_REASON_DECODER)) return
                } else {
                    decoderFailureRetried = true
                    if (retryAttempt < RAW_EXTRACTOR_PROBE_ATTEMPT) {
                        retryAttempt = RAW_EXTRACTOR_PROBE_ATTEMPT
                    }
                }
            }
            errorMessageStr = msg + "\n\n" + failureDiagnostic(failureStageLabel(error))
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

    /**
     * Session-level playback-quality stats, retained for the diagnostics export.
     *
     * The player already reports INDIVIDUAL events - dropped frames, decoder
     * init failures, rebuffer transitions - but nothing ever summarised a
     * viewing SESSION, and that summary is the question a "it keeps stuttering"
     * report actually asks: "rebuffered 14 times over 6 minutes at 2 Mbps"
     * points at the source, while any single event does not. This is media3's
     * own accumulator; it is a pure observer and changes no playback decision.
     */
    private fun createPlaybackStatsListener() =
        PlaybackStatsListener(
            /* keepHistory = */ false
        ) { _, stats ->
            val summary =
                buildString {
                    append("played ")
                    append(stats.totalPlayTimeMs / 1000)
                    append("s, rebuffers ")
                    append(stats.totalRebufferCount)
                    append(" (")
                    append(stats.totalRebufferTimeMs / 1000)
                    append("s), dropped ")
                    append(stats.totalDroppedFrames)
                    val bitrate = stats.meanVideoFormatBitrate
                    if (bitrate > 0) {
                        append(", avg ")
                        append(bitrate / 1000)
                        append(" kbps")
                    }
                }
            Log.i("PLAYER_PERF", "playback stats: $summary")
            // Retained locally (never sent): the diagnostics export on a TV has
            // no console to read, so this is the only place the numbers survive
            // a report of "it was fine then it wasn't".
            com.kennyb1201.kbstream.data.reporting.CrashReporter
                .recordEvent("player.stats", summary)
        }

    private fun createAnalyticsListener() = object : AnalyticsListener {
        /**
         * Adds up what the video track has actually fetched. This is the only
         * place the player reports those bytes, and it is what makes the info
         * panel's "Bitrate" line exist for a source whose container declares
         * no rate at all. Other track types are ignored: a title's audio and
         * its subtitles are not its video bitrate.
         */
        override fun onLoadCompleted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData
        ) {
            if (mediaLoadData.trackType != C.TRACK_TYPE_VIDEO) return
            if (loadEventInfo.bytesLoaded > 0) {
                measuredVideoBytes += loadEventInfo.bytesLoaded
            }
        }

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

    /**
     * A buffering blip must not paint the spinner.
     *
     * Mid-playback STATE_BUFFERING is usually over before it is worth an
     * indicator: a seek, a track re-selection or one frame of starvation
     * flips the state and the next READY follows a moment later. Showing the
     * spinner on the transition put a spinner over a video frame that was
     * blank for that instant - a flash in the middle of playback - so the
     * spinner is posted rather than assigned. Every place that dismisses the
     * spinner also drops the pending post (hideBufferingSpinner), so a late
     * one cannot appear after playback has already resumed.
     */
    private val bufferingSpinnerDelayMs = 400L
    private val showBufferingSpinnerRunnable =
        Runnable { bufferingSpinner.visibility = View.VISIBLE }

    private fun showBufferingSpinnerSoon() {
        handler.removeCallbacks(showBufferingSpinnerRunnable)
        handler.postDelayed(showBufferingSpinnerRunnable, bufferingSpinnerDelayMs)
    }

    private fun hideBufferingSpinner() {
        handler.removeCallbacks(showBufferingSpinnerRunnable)
        bufferingSpinner.visibility = View.GONE
    }

    private fun startPulseAnimation() {
        if (splashClearLogo.animation == null) {
            val pulse = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.clearlogo_pulse)
            splashClearLogo.startAnimation(pulse)
        }
        // The art is on screen, so the name that stood in for it goes - and only
        // then: this is also the timed fallback for a splash with NO art, where
        // the name is the only thing on screen.
        if (splashClearLogo.drawable != null) splashItemName?.visibility = View.GONE
    }

    /**
     * The splash's name text, for a splash that has no art to show YET.
     *
     * `showSplash()` used to draw the backdrop and nothing else, and the name
     * slot was only ever filled by the one-shot title-graphic policy - which
     * runs before the splash is put back up for a source switch. So every
     * switch that had to fetch its logo again showed a bare backdrop with no
     * title on it, for as long as that fetch took.
     *
     * The name is shown only while the logo slot is empty, and it is taken down
     * again the moment art lands: by [startPulseAnimation] (which runs on the
     * load, and on the layout pass that finds a drawable) and by a couple of
     * posted re-checks for the path where neither fires.
     */
    private fun showSplashTitleFallback() {
        if (itemName.isBlank() || splashClearLogo.drawable != null) return
        val title = splashItemName
            ?: findViewById<TextView>(R.id.splash_item_name)?.also { splashItemName = it }
            ?: return
        if (title.text?.toString() != itemName) title.text = itemName
        title.visibility = View.VISIBLE
        for (delayMs in longArrayOf(400L, 1200L)) {
            splashClearLogo.postDelayed({
                if (splashClearLogo.drawable != null) splashItemName?.visibility = View.GONE
            }, delayMs)
        }
    }

    private fun showSplash() {
        splashContainer.visibility = View.VISIBLE
        // The splash is the only load indicator while it is up — never stack
        // the spinner or the reconnecting banner on top of it.
        hideBufferingSpinner()
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
        showSplashTitleFallback()
    }

    private fun hideSplash() {
        splashClearLogo.clearAnimation()
        // The splash hands over to the first frame with a fade instead of a
        // cut. MainActivity paints the identical splash underneath, so the
        // only thing this fade can reveal is the video itself - which is the
        // whole point: the app went to the trouble of having no black flash on
        // the way IN and then removed this one in a single frame on the way
        // out. 200ms is longer than the chrome's because it happens once per
        // playback and is the hand-off into the content.
        KbViewMotion.fadeOut(splashContainer, 200L)
    }

    private fun updateUIBuffering() {
        // Full splash overlay (backdrop + pulsing clearlogo) for every fresh
        // source load — first launch, auto-select, manual pick, in-player
        // source switch, auto-advance (a MANUAL source switch resets the
        // hasPlayedOnce latch; an automatic mid-playback one deliberately does
        // not - see isAutoRecovery). Only mid-playback rebuffers (hasPlayedOnce
        // still true) and actor-return sessions (fromActorReturn) get the
        // small spinner, so the video is never covered once the user is
        // already watching it.
        if (!hasPlayedOnce && !fromActorReturn) {
            showSplash()
        } else {
            hideSplash()
            reconnectingContainer.visibility = View.GONE
            // Posted, not assigned: a blip must not flash the spinner (see
            // bufferingSpinnerDelayMs).
            showBufferingSpinnerSoon()
        }
    }

    private fun updateUIReady() {
        hideBufferingSpinner()
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

    /**
     * The error card's "why did this fail" line.
     *
     * "Playback failed" on its own cannot be told apart from a network drop,
     * and the recovery differs exactly by that distinction. The line names the
     * stage that gave up and the media ExoPlayer was actually handed: the
     * container it tried to open (the track's own sample MIME, falling back to
     * the URL's resolved type) and the video codec with its size — so a
     * demux failure reads as "the container could not be read" and a decode
     * failure as "no usable decoder", which is what decides whether the backup
     * engine can open the file.
     */
    private fun failureDiagnostic(stage: String): String {
        val container = streamMimeType ?: resolveMimeType(currentUrl) ?: "unknown container"
        val codec = currentCodecs ?: streamCodec
        val dims = if (streamWidth > 0 && streamHeight > 0) " ${streamWidth}x$streamHeight" else ""
        val media = buildString {
            append(container)
            if (!codec.isNullOrBlank()) append(" \u00b7 ").append(codec).append(dims)
        }
        return "Why: ExoPlayer stopped because $stage.\nMedia: $media"
    }

    /** Human-readable stage for [failureDiagnostic], keyed off the error code. */
    private fun failureStageLabel(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
            "the container could not be read"
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
            "the stream manifest could not be read"
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
            "this video has no usable decoder here"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED ->
            "the source could not be reached"
        PlaybackException.ERROR_CODE_TIMEOUT -> "the source stopped responding"
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> "the audio output failed"
        else -> error.errorCodeName
    }

    private fun updateUIError() {
        hideBufferingSpinner()
        reconnectingContainer.visibility = View.GONE
        // The pulsing "loading" splash sat BEHIND the failure card in all three
        // card-raising paths below, so a failure read as "still loading" from
        // across the room (z-order saved the card, but the animation did not
        // stop). The card means the load is over.
        hideSplash()
        errorContainer.visibility = View.VISIBLE
        errorTitle.text = if (isLiveChannel) "Channel unavailable" else "Playback failed"
        errorMessage.text = errorMessageStr.orEmpty()
        recordShownFailure()
        btnChangeSource.visibility = View.VISIBLE
        offerErrorSwitch()
        focusErrorButtons()
    }

    /**
     * Names the failure the viewer is now looking at, in the diagnostics
     * export.
     *
     * The export is the only account of a TV session there is, and before this
     * a failed source appeared nowhere in it: the card said why, on a screen the
     * user could only photograph. The host is carried because it is the question
     * the card cannot answer - an expired debrid resolve link and an indexer
     * refusing an uncached NZB both arrive as "server returned an error", and
     * only the host says which of them to chase. Host only, never the URL: a
     * debrid link carries its token in the query string.
     *
     * One entry per card, and never the same error twice.
     */
    private fun recordShownFailure() {
        val error = lastPlaybackError ?: return
        if (error === recordedPlaybackFailure) return
        recordedPlaybackFailure = error
        val host = hostOf(currentUrl)
        com.kennyb1201.kbstream.data.reporting.CrashReporter.recordEvent(
            source = "playback",
            summary = buildString {
                append(error.errorCodeName)
                httpStatusOf(error)?.let { append(" http=").append(it) }
                if (host != null) append(" host=").append(host)
                if (isLiveChannel) append(" (live)")
            }
        )
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
            // The card may have been taken down between the request and this
            // runnable (a hand-off finished, a source switched): a request
            // against a GONE container is a silent miss that leaves the D-pad
            // on playerView - whose key listener swallows every press - so the
            // card has to still be up for the move to mean anything.
            if (errorContainer.visibility != View.VISIBLE) return@post
            val target = if (btnRetry.visibility == View.VISIBLE) btnRetry else btnChangeSource
            target.requestFocus()
        }
    }

    // --- Black-video watchdog ---

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
        // Process-wide, so a later 0x80001000 in THIS or any other session can
        // tell "the pool worked and our own rebuilds wedged it" apart from "a
        // fresh pool refused this file". Only ExoPlayer reports it; MPV decodes
        // through FFmpeg and never touches the MediaCodec pool. See
        // DecoderPoolHealth.
        DecoderPoolHealth.noteFirstFrame()
        // A frame actually rendered, so THIS addon works for this title: the
        // next episode's list and the picker's own order start with it instead
        // of with whichever addon the ranker happened to put first (see
        // SourceAddonMemory). Recorded here rather than at hand-off, because
        // handing a link to the player says nothing about whether it opens -
        // which is the whole reported problem. Live channels have no next
        // episode and no addon to carry over.
        if (!isLiveChannel) {
            com.kennyb1201.kbstream.data.player.SourceAddonMemory.rememberWorked(
                this,
                parentId,
                currentAddonName
            )
        }
        // A live channel is watched FROM here: the keep-alive's clock starts on
        // the first painted frame, so a launch that legitimately takes a while
        // is the startup watchdog's business and not a stall.
        armLiveWatchdog()
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
            // Into the diagnostics report too: this split is what says whether
            // a slow start was the network or the decoder, and the report is
            // the only one of the two a viewer can read off the TV.
            com.kennyb1201.kbstream.data.reporting.PerfTrace.record(
                "playback.first_frame",
                firstFrameRenderedAtMs - firstReadyAtMs
            )
        }
        reconnectingContainer.visibility = View.GONE
        hideBufferingSpinner()
        // Live: announce the channel the moment its first frame is up, the
        // way a set-top box does — channel identity, what is on now (with its
        // air window and synopsis) and what follows. The overlay's program
        // block carries the same rows whenever the overlay is raised; this is
        // the arrival notice. Once per channel: a reconnect must not replay it.
        // ...unless the overlay is already up: that view carries the same
        // program block, and a card landing on top of it would just be noise.
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
        // old check cleared the record for any DV-labeled track, including
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
            hideBufferingSpinner()
            errorTitle.text = "Playback is taking too long to start"
            errorMessage.text =
                "The stream never became ready (buffered ${bufferedMs / 1000}s). This usually means " +
                    "the connection can't sustain the file's bitrate, the source went quiet, or the " +
                    "release has broken timestamps. Try a different source, a lower resolution, " +
                    "or a non-Dolby-Vision release." +
                    "\n\n" + failureDiagnostic("the stream never became ready")
            hideSplash()
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
            hideBufferingSpinner()
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
            hideBufferingSpinner()
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
            hideBufferingSpinner()
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

    /**
     * The surface the player is drawing into right now, or null when there is
     * none to hand - the frame-rate request rides on the surface the picture
     * goes to (see FrameRateMatcher).
     *
     * Two views matter. PlayerView's own SurfaceView is where the picture
     * normally goes; the P5 colour-correction ladder hands the player its
     * GLSurfaceView instead, and while that is up it is where the picture is.
     * The TextureView fallback has no Surface of its own to hand over at all,
     * so on that path the matcher asks for a display mode directly instead of a
     * frame rate.
     */
    private fun videoOutputSurface(): android.view.Surface? {
        if (::p5VideoGlesView.isInitialized && p5VideoGlesView.visibility == View.VISIBLE) {
            val gl = runCatching { p5VideoGlesView.holder.surface }.getOrNull()
            if (gl?.isValid == true) return gl
        }
        val holder = findVideoSurfaceView(playerView)?.holder ?: return null
        val surface = runCatching { holder.surface }.getOrNull() ?: return null
        return surface.takeIf { it.isValid }
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
            // Automatic recovery: the merged walk, across add-ons.
            if (tryNextSource(tab = null)) return
        }
        Log.w(
            "PLAYER_VIDEO",
            "Black-video notice: no first frame after surface reset + TextureView retry$codecInfo " +
                "mime=${streamMimeType ?: "?"} playing=${exoPlayer?.isPlaying} state=${exoPlayer?.playbackState}"
        )
        reconnectingContainer.visibility = View.GONE
        hideBufferingSpinner()
        errorTitle.text = "Video isn't displaying"
        errorMessage.text =
            "Playback started but no video frames are rendering$codecInfo. " +
                "Both video surfaces (SurfaceView and TextureView) were tried on this TV. " +
                (if (triedOtherSources) "Other sources were also tried automatically. " else "") +
                "If Dolby Vision playback is on, try setting it to Off for this file, or choose " +
                "a different source — a 1080p H.264 release usually plays on any device."
        hideSplash()
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
        // this. Live channels have their own handling: [armLiveWatchdog], which
        // watches the same progress but re-tunes instead of seeking past a
        // buffered edge a live stream does not have.
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

        // If media is still buffered ahead of the playhead, this is not a
        // drained-buffer network stall: seeking to the buffered edge (the old
        // recovery) would DISCARD exactly the headroom we are about to play
        // from and re-fetch it from the network, turning a merely slow source
        // into a dead one. Keep the buffer, still count the recovery so this
        // terminates, and wait another full window.
        if (bufferedMs > positionMs + 1_000) {
            stallRecoveries++
            stallLastProgressAtMs = now
            stallLastPositionMs = positionMs
            stallLastBufferedMs = bufferedMs
            Log.w(
                "PLAYER_STALL",
                "No playhead progress for ${quietMs}ms with ${bufferedMs - positionMs}ms still buffered (pos=${positionMs}ms buf=${bufferedMs}ms) — keeping the buffer (recovery $stallRecoveries/$stallMaxRecoveries)"
            )
            handler.postDelayed({ tickStallWatchdog(token) }, stallTickMs)
            return
        }

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

    /**
     * Arms the live keep-alive, and is a no-op for everything else.
     *
     * Called from the same three places the VOD watchdog is (the first rendered
     * frame, READY, and a resume), which between them mean one thing for a live
     * session: it has picture on screen and is playing. Each call restarts the
     * quiet window, so a session that has just proved it is playing is not
     * judged on progress from before it stopped. [createPlayer] clears
     * [firstFrameRendered] on every rebuild, so a re-tune arms this again on its
     * own first frame rather than inheriting the old session's clock.
     */
    private fun armLiveWatchdog() {
        // Any outstanding tick against the previous session is stale the moment
        // this runs, armed or not.
        liveWatchdogToken++
        if (!isLiveChannel) return
        if (isFinishing || isDestroyed) return
        if (!firstFrameRendered) return
        val token = liveWatchdogToken
        liveLastProgressAtMs = System.currentTimeMillis()
        liveLastPositionMs = exoPlayer?.currentPosition ?: 0L
        liveLastBufferedMs = exoPlayer?.bufferedPosition ?: 0L
        handler.postDelayed({ tickLiveWatchdog(token) }, LIVE_STALL_TICK_MS)
    }

    /**
     * One live keep-alive tick: move the window forward while data is arriving,
     * and re-tune the channel when it has stopped.
     *
     * The tick chain stops itself on a re-tune rather than re-posting: the
     * ladder owns the session from there, and a tick left running against a
     * player about to be released could ask for a second rebuild before the
     * first one lands. The next session arms a fresh chain on its first frame.
     */
    private fun tickLiveWatchdog(token: Int) {
        if (token != liveWatchdogToken) return
        val player = exoPlayer
        val positionMs = player?.currentPosition ?: 0L
        val bufferedMs = player?.bufferedPosition ?: 0L
        val now = System.currentTimeMillis()
        val action = liveWatchdogAction(
            live = isLiveChannel,
            finishing = isFinishing || isDestroyed,
            reconnecting = reconnectingContainer.visibility == View.VISIBLE,
            playWhenReady = player?.playWhenReady == true,
            playbackState = player?.playbackState ?: Player.STATE_IDLE,
            firstFrameRendered = firstFrameRendered,
            positionMs = positionMs,
            bufferedMs = bufferedMs,
            lastPositionMs = liveLastPositionMs,
            lastBufferedMs = liveLastBufferedMs,
            quietMs = now - liveLastProgressAtMs
        )
        when (action) {
            LiveWatchdogAction.IGNORE -> return
            LiveWatchdogAction.RETUNE -> {
                // Disarm before handing over, so the one path that reconnects is
                // the ladder. Its own backoff is what paces the retries from
                // here - a channel that comes back at 4am cannot be re-tuned at
                // this watchdog's cadence without rebuilding the player every
                // twenty seconds against a provider that is down.
                liveWatchdogToken++
                val quietMs = now - liveLastProgressAtMs
                Log.w(
                    "PLAYER_LIVE",
                    "No live progress for ${quietMs}ms (pos=${positionMs}ms " +
                        "buf=${bufferedMs}ms) \u2014 re-tuning the channel"
                )
                com.kennyb1201.kbstream.data.reporting.PerfTrace.record(
                    "playback.live_stall",
                    quietMs
                )
                scheduleRetry()
                return
            }
            LiveWatchdogAction.KEEP_WAITING -> {
                if (
                    liveProgressed(
                        positionMs = positionMs,
                        bufferedMs = bufferedMs,
                        lastPositionMs = liveLastPositionMs,
                        lastBufferedMs = liveLastBufferedMs
                    )
                ) {
                    liveLastProgressAtMs = now
                    liveLastPositionMs = positionMs
                    liveLastBufferedMs = bufferedMs
                }
                handler.postDelayed({ tickLiveWatchdog(token) }, LIVE_STALL_TICK_MS)
            }
        }
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
            KbViewMotion.slideFadeIn(
                infoPanel,
                24f * resources.displayMetrics.density
            )
            KbViewMotion.fadeIn(scrim)
            removeAutoHide()
            infoPanel.requestFocus()
        }
    }

    private fun hideInfoPanel() {
        KbViewMotion.slideFadeOut(infoPanel)
        if (!showSettingsPanel && !isPickerShowing) {
            KbViewMotion.fadeOut(scrim)
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
            val codecLabel = normalizeCodec(
                streamCodec,
                streamDeclaredDvCodec,
                dvTo81Session,
                dvRpuStripKeepDvActive
            )
            appendLine("Codec: ${if (codecLabel != "—") codecLabel else streamMimeType ?: "—"}")
            // Declared rate when the container states one, otherwise the rate
            // measured from the bytes actually fetched - see bitrateLabel.
            // Without the second half the line simply vanished for a source
            // that declares none, which on this box was most of them.
            bitrateLabel(
                declaredBps = streamBitrate,
                measuredBps = measuredBitrateBps(
                    measuredVideoBytes,
                    exoPlayer?.currentPosition ?: 0L
                )
            )?.let { appendLine(it) }
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
        // Decode vs passthrough, resolved for this session: the one thing a
        // viewer tuning "Audio Output" needs to confirm which side is doing the
        // decoding, and why their downmix / dialogue / volume settings do or do
        // not apply. Prepended so it reads first.
        val outputModeLabel =
            when (AppPreferences.getAudioOutput(applicationContext)) {
                PlayerAudioTuning.AUDIO_OUTPUT_PASSTHROUGH -> "Passthrough"
                PlayerAudioTuning.AUDIO_OUTPUT_DECODE -> "Decode (PCM)"
                else -> "Auto"
            }
        val outputLine =
            "Output: $outputModeLabel · " +
                (if (audioDecodeToPcmActive) "app decodes to PCM" else "passthrough allowed") +
                " · output up to ${PlayerAudioTuning.deviceMaxChannels} ch"
        val tuningLine = buildString {
            append("Tuning: downmix=")
            append(
                when (PlayerAudioTuning.downmixTarget) {
                    PlayerAudioTuning.DOWNMIX_STEREO -> "stereo"
                    PlayerAudioTuning.DOWNMIX_SURROUND -> "5.1"
                    else -> "auto"
                }
            )
            append(" · dialogue=")
            append(
                if (PlayerAudioTuning.dialogueBoost == 0) "off"
                else PlayerAudioTuning.dialogueBoost.toString()
            )
            if (PlayerAudioTuning.volumeBoostDb > 0) {
                append(" · volume=+${PlayerAudioTuning.volumeBoostDb}dB")
            }
        }
        infoAudio.text =
            (listOf(outputLine, tuningLine) + audioLines).joinToString("\n")

        // SUBTITLES block: the selected track, how it is being drawn, and the
        // offset in force. MPV's INFO panel has always carried this and Exo's
        // never did, so "what subtitle is playing and why is it 400 ms early"
        // had no answer on the engine most sessions use (audit U-F6).
        val subtitleLines = mutableListOf<String>()
        exoPlayer?.currentTracks?.groups?.forEach { group ->
            if (group.type != C.TRACK_TYPE_TEXT) return@forEach
            for (i in 0 until group.length) {
                if (!group.isTrackSelected(i)) continue
                val format = group.getTrackFormat(i)
                val parts = mutableListOf<String>()
                format.label?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
                format.language?.uppercase()?.takeIf { it.isNotBlank() }
                    ?.let { parts.add("Language: $it") }
                normalizeCodec(format.sampleMimeType).takeIf { it != "—" }
                    ?.let { parts.add("Format: $it") }
                if (format.roleFlags and C.ROLE_FLAG_CAPTION != 0) parts.add("SDH/captions")
                if (format.roleFlags and C.ROLE_FLAG_MAIN != 0) parts.add("forced")
                subtitleLines.add(parts.joinToString(" • "))
            }
        }
        val subtitleSource = when {
            // The overlay typesets its own script, so the text cues this panel
            // would otherwise describe are deliberately not drawn.
            assRenderer.active -> "Drawn by: ASS overlay (libass typesetting)"
            externalSubtitleCues.isNotEmpty() -> "Drawn by: external subtitle file"
            subtitleLines.isEmpty() -> "Off"
            else -> "Drawn by: embedded track"
        }
        val subtitleStyle = buildString {
            append("Offset: ${subtitleOffsetMs}ms")
            append(" • Size: ")
            append(
                when (AppPreferences.getDefaultSubtitleSize(applicationContext)) {
                    SUBTITLE_SIZE_SMALL -> "small"
                    SUBTITLE_SIZE_LARGE -> "large"
                    else -> "normal"
                }
            )
        }
        infoSubtitles.text =
            (subtitleLines + subtitleSource + subtitleStyle).joinToString("\n")
    }


    /**
     * Auto-select audio and subtitle tracks matching the user's preferred
     * languages. Runs once when playback reaches STATE_READY so it does
     * not fight with manual picker selections.
     */
    private var languagesAutoSelected = false

    /**
     * Set once a bitmap subtitle track has sent playback to the MPV engine (or
     * been refused one). Tracks are re-read several times per session, so the
     * engine change is attempted once per session rather than once per callback.
     */
    private var subtitleEngineFallbackTried = false

    /**
     * Set once an automatic OpenSubtitles fetch has been attempted this session.
     * Track groups are re-read several times per session, so without this the
     * fetch would re-run on every callback.
     */
    private var autoSubtitleFetchTried = false

    /** True while an automatic fetch is in flight, so a re-read cannot start a second. */
    private var autoSubtitleFetchInFlight = false

    /**
     * The video an automatic subtitle fetch belongs to: the title and the
     * episode, which is what a subtitle is FOR.
     *
     * Deliberately not the stream URL: that changes for the same episode on a
     * source switch, a rebuild or a failover, and a track fetched for the
     * episode is still the right track afterwards.
     */
    private fun videoIdentity(): String = "$itemName|${season ?: -1}|${episode ?: -1}"

    /**
     * Whether the playback a fetch was launched for is still the one on screen.
     *
     * The search and the download are network round trips, and the video can
     * be replaced while they run - the credits advance to the next episode, the
     * end panel switches episodes, a handoff re-opens this screen with new
     * extras. Attaching on arrival then puts the previous episode's subtitle on
     * the new picture, which is worse than no subtitle at all: it is wrong text
     * over the right one, and it is remembered for that video.
     */
    private fun stillSameVideo(identity: String): Boolean {
        if (videoIdentity() == identity) return true
        Log.i("PLAYER_LANG", "auto subtitle fetch: the video changed while fetching; ignoring the download")
        return false
    }

    /**
     * Set once the NEXT episode's subtitle has been asked for. The end panels
     * are raised from more than one place (the credits trigger and the real
     * end), so without this each of them would start its own fetch.
     */
    private var subtitlePrefetchStarted = false

    private fun autoSelectPreferredLanguages() {
        val player = exoPlayer ?: return
        if (languagesAutoSelected) return
        val tracks = player.currentTracks
        var changed = false

        // ── Audio ──────────────────────────────────────────────
        if (preferredAudioLang.isNotBlank()) {
            // Flat view of the audio tracks, keeping the way back to the media3
            // objects: the fallback below names a position, and re-deriving a
            // group from a flat index is how the two get out of step.
            val audioTracks = tracks.groups
                .filter { it.type == C.TRACK_TYPE_AUDIO }
                .flatMap { group -> (0 until group.length).map { i -> group to i } }
            for ((group, i) in audioTracks) {
                val fmt = group.getTrackFormat(i)
                val lang = fmt.language?.lowercase()
                if (LanguageMatch.matches(preferredAudioLang, fmt.language)) {
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
            if (!changed) {
                // Nothing matched. The muxer's DEFAULT is what opens a dual-audio
                // EN/RU release in Russian: the English track is usually the
                // untagged one and the DEFAULT flag sits on the other. Take the
                // first track NOT known to be the wrong language instead - an
                // untagged track might be the one asked for, while a `rus` track
                // certainly is not - and keep the muxer default only when every
                // track is knowably wrong.
                // The tags themselves go in the log, so the next diagnostics
                // read as a decision rather than as another silent miss.
                val tags = audioTracks.joinToString(", ") { (group, i) ->
                    group.getTrackFormat(i).language ?: "untagged"
                }
                val fallback = LanguageMatch.fallbackTrackIndex(
                    audioTracks.map { (group, i) -> group.getTrackFormat(i).language },
                    preferredAudioLang
                )
                if (fallback != null) {
                    val (group, i) = audioTracks[fallback]
                    player.trackSelectionParameters = player.trackSelectionParameters
                        .buildUpon()
                        .setOverrideForType(
                            TrackSelectionOverride(group.mediaTrackGroup, i)
                        )
                        .build()
                    changed = true
                    Log.i(
                        "PLAYER_LANG",
                        "No '$preferredAudioLang' audio track; tracks are [$tags]; avoided " +
                            "the muxer default and picked track $fallback"
                    )
                } else {
                    Log.i(
                        "PLAYER_LANG",
                        "No '$preferredAudioLang' audio track and none of [$tags] can be " +
                            "taken as it; keeping the muxer default"
                    )
                }
            }
        }

        // ── Subtitle ──────────────────────────────────────────
        // Live TV does nothing with subtitles here: a channel is a stream, not
        // an episode, so there is no title to search and no meaningful track to
        // arm. Left in, an IPTV channel whose stream carried no text track fell
        // through SubtitleTrackRules to Off, which pulled a subtitle from
        // OpenSubtitles, rebuilt the player to attach it (the ~1s rebuffer) and
        // raised the "Subtitles: ..." toast - none of it asked for, on live TV.
        // The mode is consulted before the language. Off means no subtitle is
        // ever armed on its own: the old blank preference only told media3
        // "no preference", and media3's own default selection would arm a
        // track anyway - the "it keeps turning subtitles back on" report.
        // Forced needs no language to run; On is the old language rules.
        val subtitleMode = SubtitleModeRules.normalized(AppPreferences.getSubtitleMode(this))
        if (!isLiveChannel && subtitleMode == SubtitleModeRules.OFF) {
            // Explicit Off: the text track is disabled outright, so neither
            // media3's own selection nor a re-selection after a stream rebuild
            // can arm one.
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            // LATCHED like every other terminal decision in this pass. The latch
            // is what keeps the pass from running again on the next READY, and
            // without it a rebuffer re-disabled the text track over whatever the
            // viewer had just picked by hand: subtitles that survived until the
            // first rebuffer and then vanished, with global mode Off (audit
            // E-F1, 2026-10-27). The latch resets per player build, so a new
            // source still gets its own pass.
            changed = true
        } else if (
            !isLiveChannel &&
            (subtitleMode == SubtitleModeRules.FORCED || preferredSubtitleLang.isNotBlank())
        ) {
            val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
            // Flatten to the shape SubtitleTrackRules works on, keeping the way
            // back to the media3 objects: the rules decide, this applies. They
            // exist because both failures they prevent are silent - an armed
            // track that draws nothing (a bitmap format this engine does not
            // draw), and a fallback onto the one track no renderer claims.
            // Neither raises, so neither reaches the retry ladder.
            val candidates = mutableListOf<SubtitleTrackRules.Candidate>()
            val locations = mutableListOf<Pair<Tracks.Group, Int>>()
            textGroups.forEach { group ->
                for (i in 0 until group.length) {
                    val fmt = group.getTrackFormat(i)
                    candidates += SubtitleTrackRules.Candidate(
                        language = fmt.language,
                        mimeType = fmt.sampleMimeType,
                        forced = fmt.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
                        supported = group.isTrackSupported(i),
                        // The track's own name carries the kind (SDH / signs /
                        // commentary) and the language tag never does, so the
                        // rules read it to rank SDH above a forced track - see
                        // SubtitleTrackRules.choose.
                        title = fmt.label
                    )
                    locations += group to i
                }
            }

            when (
                val choice = SubtitleTrackRules.choose(
                    candidates,
                    preferredSubtitleLang,
                    subtitleMode
                )
            ) {
                is SubtitleTrackRules.Choice.Show -> {
                    val (group, index) = locations[choice.index]
                    player.trackSelectionParameters = player.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(
                            TrackSelectionOverride(group.mediaTrackGroup, index)
                        )
                        .build()
                    changed = true
                    // The language alone cannot answer "which one did it pick?"
                    // on a file that ships EN SDH, EN regular and EN forced -
                    // every one of the three logs "en". The track index, its
                    // kind and its own label are what make the field run
                    // readable.
                    val picked = group.getTrackFormat(index)
                    Log.i(
                        "PLAYER_LANG",
                        "Auto-selected subtitle: ${picked.language ?: "untagged"} " +
                            "track=$index kind=${SubtitleKindRules.tagText(picked.label) ?: "regular"} " +
                            "label=${picked.label ?: "none"}"
                    )
                }

                is SubtitleTrackRules.Choice.NeedsMpv -> {
                    val format = locations[choice.index].let { (group, i) ->
                        group.getTrackFormat(i)
                    }
                    val label = SubtitleTrackRules.formatLabel(format.sampleMimeType)
                        ?: "bitmap"
                    // The preferred language IS on the file, but only as a
                    // bitmap subtitle track in a format this engine does not
                    // draw (see SubtitleTrackRules.bitmapRenderable - PGS draws
                    // here now, VobSub and DVB still do not). Such a track can
                    // be armed and then draws nothing - and an armed track that
                    // renders nothing is not a PlaybackException, so the
                    // decoder ladder never fires and nothing is logged
                    // anywhere. MPV demuxes and draws these formats itself, so
                    // the backup engine is the only place they exist.
                    if (!subtitleEngineFallbackTried) {
                        subtitleEngineFallbackTried = true
                        Log.w(
                            "PLAYER_LANG",
                            "preferred subtitle track is $label, which this engine " +
                                "cannot render; handing off to the MPV engine"
                        )
                        if (!handOffToMpv(MpvPlayerActivity.FALLBACK_REASON_SUBTITLE)) {
                            // Refused: live TV, a DRM session, no libmpv, or the
                            // automatic fallback is switched off. Say so, because
                            // the alternative was the original bug - a silent
                            // nothing where subtitles should be.
                            Log.w(
                                "PLAYER_LANG",
                                "$label subtitles need the MPV engine; handoff refused, " +
                                    "so subtitles stay off"
                            )
                            // Refused, not absent: the viewer still wants
                            // subtitles in this language, and a fetched text track
                            // renders where the bitmap one cannot. Same gates as
                            // the Off branch (mode, key, title, once per session)
                            // - see maybeAutoFetchSubtitle.
                            maybeAutoFetchSubtitle()
                        }
                    }
                    // Either way the bitmap track must not be left selectable:
                    // rendering nothing is exactly the failure being fixed.
                    player.trackSelectionParameters = player.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                    // And latch it: this is a decision, not a pre-pass. A
                    // rebuffer's READY must not re-run the pass and take a
                    // manual pick down with it (audit E-F1, 2026-10-27).
                    changed = true
                }

                SubtitleTrackRules.Choice.Off -> {
                    // Nothing renderable to show, so keep text off rather than
                    // letting media3's own selection arm a dead track.
                    player.trackSelectionParameters = player.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                    // Latched for the same reason as the two branches above.
                    changed = true
                    // With a preferred language set, `Off` means the file
                    // carries no subtitle track at all - the stream "has no
                    // usable subs". Pull one from OpenSubtitles rather than
                    // making the viewer search for it (see maybeAutoFetchSubtitle).
                    maybeAutoFetchSubtitle()
                }
            }
        }

        if (changed) languagesAutoSelected = true
    }

    /**
     * Pulls a subtitle from OpenSubtitles when the stream carries none the
     * engine can draw.
     *
     * The point is the missing press: a file with no subtitle track used to
     * leave the viewer to open the picker and search by hand, every episode.
     * This runs the same search and attaches the best hit, but only when the
     * viewer has actually asked for a subtitle language (see
     * [AppPreferences.getPreferredSubtitleLanguage]), set an OpenSubtitles key,
     * and left auto-fetch on - and only once per session. The language hint is
     * the same one the manual search uses, so an auto-fetched track lands in the
     * language the viewer configured.
     */
    /**
     * Asks for the next episode's subtitle now, so its own session's auto-fetch
     * starts with the file on disk instead of paying for the search and the
     * download at playback start (see [SubtitlePrefetch]).
     *
     * Gated on this session's auto-fetch having RUN at all, which is what
     * establishes that the viewer wants fetched subtitles for this show
     * (auto-fetch on, a preferred language, an OpenSubtitles key) and that this
     * episode carried no usable track of its own. Without that gate every
     * series with embedded subtitles would have a subtitle downloaded and cached
     * for every episode that nobody is ever going to attach. Best-effort: a
     * failure just leaves the auto-fetch to do exactly what it did before.
     */
    private fun prefetchNextEpisodeSubtitle(nextSeason: Int, nextEpisode: Int) {
        if (subtitlePrefetchStarted || !autoSubtitleFetchTried) return
        if (isLiveChannel) return
        val queryTitle = itemName
        if (queryTitle.isBlank()) return
        val lang = AppPreferences.getPreferredSubtitleLanguage(this)
        if (lang.isBlank()) return
        subtitlePrefetchStarted = true
        lifecycleScope.launch {
            SubtitlePrefetch.prefetchFor(
                this@NativePlayerActivity,
                title = queryTitle,
                season = nextSeason,
                episode = nextEpisode,
                language = lang
            )
        }
    }

    private fun maybeAutoFetchSubtitle() {
        // Belt and braces for the branch above (and any future caller): live
        // has no title to search, so it must never reach the network, the
        // attach/rebuild, or the toast - the same gate its sibling
        // [prefetchNextEpisodeSubtitle] already applies.
        if (isLiveChannel) return
        // Only the language mode fetches. In Forced mode a full translation is
        // exactly what the viewer did not ask for, and Off asked for nothing.
        if (SubtitleModeRules.normalized(AppPreferences.getSubtitleMode(this)) !=
            SubtitleModeRules.ON
        ) {
            return
        }
        if (autoSubtitleFetchTried || autoSubtitleFetchInFlight) return
        if (!AppPreferences.getAutoFetchSubtitles(this)) return
        if (AppPreferences.getOpensubtitlesApiKey(this).isBlank()) return
        val queryTitle = itemName
        if (queryTitle.isBlank()) return
        autoSubtitleFetchTried = true
        autoSubtitleFetchInFlight = true
        // Captured before the first request goes out and re-checked before the
        // attach (see [stillSameVideo]): everything below this line is async.
        val launchIdentity = videoIdentity()
        val lang = AppPreferences.getPreferredSubtitleLanguage(this)
        lifecycleScope.launch {
            try {
                // Fetched while the PREVIOUS episode's credits rolled: the
                // search and the download are already done, so attach what is
                // on disk and never touch the network. Every guard the rest of
                // this function applies has already been applied by the caller
                // that decided there is no usable track here.
                SubtitlePrefetch.get(
                    this@NativePlayerActivity,
                    title = queryTitle,
                    season = season,
                    episode = episode,
                    language = lang
                )?.let { hit ->
                    if (!stillSameVideo(launchIdentity)) return@launch
                    Log.i("PLAYER_LANG", "auto subtitle fetch: using prefetched ${hit.fileName}")
                    // No rebuild and no toast: an automatically fetched track is
                    // not an event the viewer needs announcing, and the rebuild
                    // it used to cost re-opened the stream mid-playback. The
                    // subtitle appearing IS the feedback.
                    attachExternalSubtitle(hit.uri, rebuild = false)
                    return@launch
                }
                val results = SubtitleSearchHelper.search(
                    this@NativePlayerActivity,
                    title = queryTitle,
                    season = season,
                    episode = episode,
                    languageHint = lang
                )
                val pick = AutoSubtitleRules.pick(results, lang) ?: return@launch
                when (val result = SubtitleSearchHelper.download(this@NativePlayerActivity, pick)) {
                    is SubtitleDownload.Failed -> Log.w(
                        "PLAYER_LANG",
                        "auto subtitle fetch came up empty: ${result.reason}"
                    )

                    is SubtitleDownload.Ready -> {
                        // Validate before the player is ever touched: a 200 with
                        // an empty/unparsable body must not rebuild the player -
                        // the attach below is what makes the picture/audio blink
                        // - nor claim success for a track that never lands. The
                        // rule is the same one the manual picker applies, so
                        // both routes agree on what a usable subtitle is.
                        if (!SubtitleSearchHelper.isUsableSubtitleBody(
                                result.body,
                                AssSubtitleRenderer.available
                            )
                        ) {
                            Log.w(
                                "PLAYER_LANG",
                                "auto subtitle fetch: download parsed to 0 cues, ignoring"
                            )
                            return@launch
                        }
                        // Last gate before the player is touched: the download
                        // belongs to the video that asked for it, not to
                        // whatever is playing now.
                        if (!stillSameVideo(launchIdentity)) return@launch
                        val uri = SubtitleSearchHelper.toCacheUri(
                            this@NativePlayerActivity,
                            pick,
                            result.body
                        )
                        // Same rule as the prefetched branch above: attach
                        // without a rebuild and without a toast, so a subtitle
                        // this app fetched for the viewer never stops the film.
                        Log.i("PLAYER_LANG", "auto subtitle fetch: attached ${pick.fileName}")
                        attachExternalSubtitle(uri, rebuild = false)
                    }
                }
            } finally {
                autoSubtitleFetchInFlight = false
            }
        }
    }

    private fun updateSeekBarPosition(posMs: Long, durationMs: Long) {
        // The shared bar and its two readouts are painted by the chrome, at its
        // own 1000-step resolution; the clock and the play/pause glyph are left
        // alone, since this position is the one the scrub OWNS - ahead of the
        // player, not the playhead the clock should report.
        chrome?.setScrubPosition(posMs, durationMs)
    }

    private fun stepSeekBy(deltaMs: Long) {
        val player = exoPlayer ?: return
        val duration = player.duration.takeIf { it > 0 } ?: return
        // Mid-scrub, step from the position we OWN rather than the player's -
        // see [scrubTargetPosMs]. On the first press of a scrub the target was
        // just seeded from the player, so this is the player's position anyway.
        val base = if (scrubMoved) scrubTargetPosMs else player.currentPosition
        val newPos = (base + deltaMs).coerceIn(0L, duration)
        scrubTargetPosMs = newPos
        scrubMoved = true
        player.seekTo(newPos)
        updateSeekBarPosition(newPos, duration)
    }

    /**
     * Begins a scrub: remembers whether the viewer was playing - so it can be put
     * back exactly, see [endScrubPause] - then pauses.
     *
     * The pause is what lets the video follow the bar. A paused seek has no
     * real-time playback deadline: the decoder flush per seek still happens, but
     * nothing is trying to play through it, so a seek on every tick neither
     * storms nor stalls the way an unthrottled scrub of a PLAYING 4K release did.
     * Live has no seekable timeline (its duration is unset) and a scrub never
     * starts there, so live playback is never paused.
     *
     * The captured state is `playWhenReady` - the user's intent - not
     * `isPlaying`. `isPlaying` is true only when `playWhenReady` AND the player
     * is READY, so capturing it dropped the resume whenever the scrub began
     * during a rebuffer or a slow seek: the viewer was watching, scrubbed, and
     * playback stayed paused until they pressed play. `playWhenReady` is what
     * [endScrubPause]'s `play()` restores, and it stays false for a viewer who
     * had explicitly paused, so that case still resumes nothing.
     */
    private fun beginScrubPause() {
        val player = exoPlayer ?: return
        if (player.duration <= 0L) return
        // Re-entry: a second press while a scrub is ALREADY in flight must not
        // re-capture the play state. The first capture paused the player, so
        // `playWhenReady` is false by now - overwriting the flag with that false
        // is what made a re-entered scrub resume nothing on release, leaving the
        // viewer paused after a scrub they made while playing.
        if (scrubInFlight) return
        scrubInFlight = true
        wasPlayingBeforeScrub = player.playWhenReady
        player.pause()
    }

    /**
     * Ends a scrub: restores the play state [beginScrubPause] captured. A scrub
     * that never started resumes nothing, and the flag is cleared here so it is
     * per-scrub. The null check keeps a released player from being touched.
     */
    private fun endScrubPause() {
        if (wasPlayingBeforeScrub) exoPlayer?.play()
        wasPlayingBeforeScrub = false
        scrubInFlight = false
    }

    /**
     * Ends a scrub by landing exactly on the position the viewer scrubbed to.
     *
     * The scrub seeks opportunistically while paused, but a slow seek may still
     * not have settled by the release, and [stepSeekBy]'s own step lands the
     * quick press. This is where the release is made good, once, so the viewer
     * lands where the bar showed rather than wherever the decoder happened to
     * keep up with.
     */
    private fun landScrub() {
        if (!scrubMoved) return
        scrubMoved = false
        exoPlayer?.seekTo(scrubTargetPosMs)
    }

    private fun commitSeekFromBar() {
        val durationMs = exoPlayer?.duration ?: 0L
        if (durationMs > 0) {
            // The shared bar's own resolution is 1000 (PlayerChrome), not the
            // 10000 this engine's private bar used to declare.
            val posMs = (seekbar.progress.toLong() * durationMs) / 1_000L
            exoPlayer?.seekTo(posMs)
        }
    }

    private fun updateClock() {
        // Honor the Settings > Interface 24-hour toggle; falls back to the
        // locale default when unchecked.
        val formatter =
            if (AppPreferences.getUse24HourClock(this)) clock24Format else clock12Format
        playerClock.text = DateFormats.now(formatter)
        val durationMs = exoPlayer?.duration ?: 0L
        val positionMs = exoPlayer?.currentPosition ?: 0L
        val remainingMs = (durationMs - positionMs).coerceAtLeast(0L)
        val endsAt = DateFormats.time(System.currentTimeMillis() + remainingMs, formatter)
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
        tintPlayPauseIcon(btnPlayPause, this)
        btnSpeed.text = "${playbackSpeed}x"
        btnAspect.text = ASPECT_MODES.getOrElse(resizeModeIndex) { "Fit" }
        // Live channels get the channel-change buttons and the NOW/NEXT
        // program block; VOD keeps the episode row instead.
        val liveVisibility = if (isLiveChannel) View.VISIBLE else View.GONE
        btnChannelUp?.visibility = liveVisibility
        btnChannelDown?.visibility = liveVisibility
        // GUIDE only where a press can land: with no lineup there is nothing to
        // browse, so the button would open onto an empty panel.
        btnGuide?.visibility =
            if (isLiveChannel && LiveChannelZapRegistry.zapEnabled()) {
                View.VISIBLE
            } else {
                View.GONE
            }
        if (isLiveChannel) {
            refreshLiveProgramBlock()
        } else {
            liveProgramBlock?.visibility = View.GONE
        }
    }

    private fun applyPillBackground(view: TextView, selected: Boolean, focused: Boolean) {
        // Built from the current theme, not the fixed XML pill drawables: those
        // hard-code @color/kb_accent, and their focused variants are
        // layer-lists the accent re-tint walk cannot rebuild, so a selected or
        // focused pill (the Up Next card focuses PLAY NEXT) stayed the default
        // brass on a chosen accent. See [applyPillLook] - the one pill look
        // all three engines share.
        applyPillLook(this, view, selected, focused)
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
                    } catch (e: Exception) {
                        // Reflective unpin of the frame's private ratio field.
                        // A failure here (a renamed field on a newer media3, an
                        // R8-stripped member) leaves the last forced ratio
                        // pinned, so the picture keeps a ratio the user just
                        // turned off - a real, visible bug worth reporting
                        // rather than swallowing.
                        com.kennyb1201.kbstream.data.reporting.CrashReporter
                            .recordNonFatal(e, mapOf("source" to "player.aspect.unpin"))
                    }
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
                    } catch (e: Exception) {
                        // The delayed re-assert is the step that makes a forced
                        // ratio survive a late layout/player transaction; a
                        // throw here is why the picture snapped back.
                        com.kennyb1201.kbstream.data.reporting.CrashReporter
                            .recordNonFatal(e, mapOf("source" to "player.aspect.reassert"))
                    }
                },
                120L
            )
        } catch (e: Exception) {
            // Top-level guard on the whole aspect pass: a throw here aborted
            // the relayout, so the mode change silently did nothing.
            com.kennyb1201.kbstream.data.reporting.CrashReporter
                .recordNonFatal(e, mapOf("source" to "player.aspect.apply"))
        }
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
        // Fill and label color both come from the shared pill look, so a
        // selected pill can no longer end up wearing the neutral pill's label
        // (this used to set the two separately, right beside a helper that set
        // only the fill).
        applyPillBackground(view, selected, view.isFocused)
        view.setOnFocusChangeListener { v, _ ->
            applyPillBackground(v as TextView, selected, v.isFocused)
    }

}

    private fun updateSettingsPanelState() {
        // The sleep-timer rows take part in the panel's own refresh, so opening
        // the panel always shows the state the timer is really in.
        sleepTimerSection?.refresh()

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
            // The settings sheet's Codec row is the same row built from the
            // same state as the info panel's, so it gets the same annotation -
            // otherwise the two diagnostic surfaces would disagree about what
            // is on screen.
            val codecLabel = normalizeCodec(
                streamCodec,
                streamDeclaredDvCodec,
                dvTo81Session,
                dvRpuStripKeepDvActive
            )
            if (codecLabel != "—") {
                settingsCodec.text = "Codec: $codecLabel"
                settingsCodec.visibility = View.VISIBLE
            }
    }

}

    // --- Controls Visibility ---
    /**
     * Parks the D-pad on the overlay's primary control.
     *
     * One definition, because two places need the same answer - handing focus
     * back when a picker or panel closes, and catching a focus that landed on
     * the video surface - and the answer is not "the overlay's first focusable
     * child". Requesting focus on the container lets the framework choose among
     * its descendants, which is not the play button, so a viewer who pressed OK
     * there was pressing a button they could not see. While a skip prompt is up
     * that prompt is the primary target (Netflix-style), and on a live channel
     * it is CH up, since pausing live television is not a thing.
     *
     * Raising the overlay is deliberately not one of the two any more: it lands
     * on the seek bar instead, so that the LEFT/RIGHT that follows scrubs rather
     * than walking the row. The chrome's own focusControls orders those two, and
     * asks this engine which of them applies (see chromeSeekBarScrubbable and
     * chromeFocusPrimary).
     */
    private fun focusControlsPrimary() {
        if (btnSkipIntro.visibility == View.VISIBLE) {
            btnSkipIntro.requestFocus()
        } else if (isLiveChannel && btnChannelUp?.visibility == View.VISIBLE) {
            btnChannelUp?.requestFocus()
        } else {
            btnPlayPause.requestFocus()
        }
    }

    /**
     * Gives the D-pad back to the overlay after a panel or picker closes.
     *
     * Silent unless the overlay is actually up and no other panel has taken
     * over in the same call: showPicker() and the info panel each dismiss one
     * panel and then focus a container of their own, and this must not fight
     * them for it.
     */
    private fun restoreControlsFocus() {
        if (!controlsVisible || showSettingsPanel || isPickerShowing) return
        if (infoPanel.visibility == View.VISIBLE) return
        focusControlsPrimary()
    }

    private fun showControls() {
        stopSurfaceScrub()

        // The shared chrome brings the overlay and its seek row up, carries the
        // clock with them, parks the D-pad on its first stop - unless a panel
        // owns the screen, which chromeMayTakeFocus answers - and starts the
        // auto-hide countdown whose fire onChromeAutoHide answers.
        chrome?.show()
        updateControlsInfo()
        updateClock()
        clockHandler.removeCallbacks(clockRunnable)
        clockHandler.post(clockRunnable)
        // Best-effort: resolve the next episode's name so the Next button's
        // handoff label (and the streams screen it opens) carries the real
        // episode title, not just S#E#. The info panel keeps its own focus and
        // its own callers, so it is left alone.
        if (infoPanel.visibility != View.VISIBLE) prefetchNextEpisodeName()
    }

    private fun hideControls() {
        // Stop any active scrubbing - and land on the target, since the last
        // ticks of a hold may have been skipped while a seek settled.
        scrubDirection = 0
        landScrub()
        // A Back press or an auto-hide can end a scrub before its release does;
        // the pause it took must not outlive it (see [endScrubPause]).
        endScrubPause()
        scrubHandler.removeCallbacks(scrubRunnable)
        scrubHandler.removeCallbacks(scrubHoldStarter)
        scrubHintHandler.removeCallbacks(scrubHintHider)
        surfaceScrubHint?.visibility = View.GONE

        // The chrome takes the overlay, its seek row and the clocks down; the
        // engine's own teardown is the rest of this method.
        chrome?.hide()
        dismissAllPanels()
        hideInfoPanel()
        hideBufferingSpinner()
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
     *
     * Bitmap cue groups are the exception, and they do not go through this
     * view at all: a PGS cue carries an authored IMAGE (Cue.bitmap), which only
     * Media3's SubtitleView can draw, so those groups are handed to it and it
     * is made visible for exactly as long as they are on screen. Text groups
     * hide it again, so the two paths never draw at once and no bitmap frame
     * can outlive its track.
     */
    private inner class SubtitleCueHandler : Player.Listener {
        private var currentCues: List<Cue> = emptyList()

        /**
         * Whether the stock SubtitleView is currently showing a bitmap frame
         * for THIS handler, so hiding it is one transition instead of a write
         * on every text cue group (PlayerView forwards every group to it).
         */
        private var bitmapCuesDrawn = false
        private val delayedShow = Runnable { renderText(currentCueText()) }
        private val positionTick = Runnable { updateFromPosition() }

        /**
         * The suppression state last logged, so a flip is one line rather than
         * one per cue. Reset with the handler, which is rebuilt per player.
         */
        private var assGateSuppressed = false

        /**
         * True when an external subtitle file is loaded AND the offset is
         * negative: the pipeline only ever emits cues at their authored
         * time, so "show earlier" needs this handler to drive rendering
         * from the playback position instead.
         */
        private val positionDriven: Boolean
            get() = externalSubtitleCues.isNotEmpty() &&
                (subtitleOffsetMs < 0 || sidecarRenderedByApp) &&
                !assOwned()

        /**
         * True when the overlay is typesetting the CURRENTLY SELECTED text
         * track; text cues must stay out of its way.
         *
         * This used to be "some script is loaded", which is a different thing:
         * an overlay still holding a script it no longer typesets (a selection
         * that moved on, an offer that turned out to draw nothing) then ate
         * every cue of the track the viewer had actually picked, silently, for
         * the whole episode. The gate is the same decision
         * [handleAssTrackSelection] makes, read again from the live selection:
         * the overlay's own claim has to cover the track selected right now.
         */
        private fun assOwned(): Boolean {
            val owned = assRenderer.active && assOverlayOwns()
            if (owned != assGateSuppressed) {
                assGateSuppressed = owned
                Log.i(
                    TAG_ASS,
                    if (owned) {
                        "cue gate suppressed: overlay typesets the selected track " +
                            "source=$assOverlaySource"
                    } else {
                        "cue gate flowing: the selected track is not the overlay's " +
                            "(source=$assOverlaySource)"
                    }
                )
            }
            return owned
        }

        override fun onCues(cueGroup: CueGroup) {
            if (assOwned()) {
                // The pipeline still emits flattened cues for an .ass sidecar
                // that is already being typeset over the surface; drawing both
                // would double the subtitle up in two different styles.
                handler.removeCallbacks(delayedShow)
                currentCues = emptyList()
                renderText("")
                clearBitmapCues()
                return
            }
            if (cueGroup.cues.any { it.bitmap != null }) {
                // A bitmap subtitle track (PGS today - see
                // SubtitleTrackRules.bitmapRenderable) arrives as authored
                // images, not text. Routed by what the group CARRIES rather
                // than by the track's declared format: the cue is the thing
                // that has to be drawn, and only one text track is ever
                // selected, so groups are homogeneous in practice and the two
                // paths never draw at once. The overlay's gate above stays
                // first: a bitmap group is not the flattened duplicate of an
                // ASS script, but if the overlay owns the selection there is
                // no bitmap group to forward either.
                handler.removeCallbacks(delayedShow)
                currentCues = emptyList()
                renderText("")
                // No offset and no size/background: an authored image has no
                // "appear earlier", and Media3 has no bitmap-delay API. These
                // settings are text-only, and these tracks drew nothing at all
                // before this path existed, so this is a gain, not a
                // regression.
                showBitmapCues(cueGroup)
                return
            }
            clearBitmapCues()
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

        /**
         * Clears the cues this handler holds and the pending work that belongs
         * to them - and nothing else.
         *
         * It deliberately does NOT re-arm the tick. [updateFromPosition]
         * removes `positionTick` and re-posts it, so the old "and re-sync
         * position mode" line below started a tick on a handler that every
         * caller is about to throw away: [recreatePlayer] and
         * [lightSwitchLiveChannel] replace it, and `onStop`/`onDestroy` null
         * it. Nothing holds a reference to a discarded handler, so nothing
         * could ever cancel the tick it posted - it kept running for the rest
         * of the session, one more per rebuild, each rendering from the shared
         * cue list into the shared text view and racing the live handler's own
         * tick. Every path that needs the tick starts it on the handler that
         * KEEPS it: the READY handler, [updateFromPosition] from the cue and
         * attach paths, and [reapplyOffset] for an offset nudge.
         */
        fun cancelPending() {
            handler.removeCallbacks(delayedShow)
            handler.removeCallbacks(positionTick)
            currentCues = emptyList()
            renderText("")
            // A rebuilt player, a live zap, or a teardown must not leave the
            // last bitmap frame on screen: the view belongs to the surface, not
            // to this handler.
            clearBitmapCues()
        }

        /**
         * Stops position-driven rendering and clears the line it drew, WITHOUT
         * abandoning this handler - the cue pipeline takes the subtitle area
         * back on the next group it emits.
         *
         * This is the counterpart of [cancelPending] for a live hand-back: a
         * sidecar the app rendered itself is released when the viewer picks a
         * track or OFF (see `releaseAppRenderedSidecar`), and nulling the handler
         * there would have dropped the pipeline's listener with it.
         */
        fun releasePositionDriven() {
            handler.removeCallbacks(positionTick)
            renderText("")
        }

        /**
         * Draws an authored bitmap cue group on Media3's own SubtitleView, which
         * is the one view in this app that can render a [Cue.bitmap].
         *
         * Setting the cues here as well as showing the view is deliberate:
         * PlayerView already forwards every cue group to this view, but that
         * forwarding carries no visibility, and stating the frame this handler
         * was handed keeps the drawn image right whatever order the two
         * listeners run in.
         */
        private fun showBitmapCues(cueGroup: CueGroup) {
            val view = playerView.subtitleView ?: return
            view.setCues(cueGroup.cues)
            // The player's own subtitle SIZE and POSITION reach a bitmap track
            // too, as far as an authored image allows: the cue's pixels are
            // fixed, so size is the view's scale and position is its vertical
            // offset. Both are applied here rather than in the settings panel
            // because this is the only place the bitmap path is drawn (audit
            // U-F14: these settings reached text cues only).
            applyBitmapCueGeometry(view)
            view.visibility = View.VISIBLE
            if (!bitmapCuesDrawn) {
                bitmapCuesDrawn = true
                Log.i(
                    TAG_SUB,
                    "bitmap cue group (${cueGroup.cues.size} cues) drawing on SubtitleView"
                )
            }
        }

        /**
         * Gives the SubtitleView back to the text path: hidden and emptied, so
         * a text track (whose cues PlayerView still forwards to it) cannot draw
         * twice and no stale bitmap frame survives a track switch.
         */
        private fun clearBitmapCues() {
            if (!bitmapCuesDrawn) return
            bitmapCuesDrawn = false
            playerView.subtitleView?.setCues(null)
            playerView.subtitleView?.visibility = View.GONE
            // Back to neutral: the text path positions itself with its own
            // gravity, and a leftover scale/offset here would move it.
            playerView.subtitleView?.apply {
                scaleX = 1f
                scaleY = 1f
                translationY = 0f
            }
        }

        /**
         * The subtitle size and position settings, applied to a BITMAP cue.
         *
         * A PGS cue is an image with its own authored resolution, so nothing
         * here can restyle it the way [renderText] restyles text: the honest
         * mapping is scale for size and a vertical offset for position, and it
         * is what the two settings can mean for an image at all. The scale is
         * anchored bottom-centre, which is where a subtitle sits, so growing it
         * pushes up from the same baseline instead of sinking off screen.
         */
        private fun applyBitmapCueGeometry(view: android.view.View) {
            val size = when (AppPreferences.getDefaultSubtitleSize(applicationContext)) {
                SUBTITLE_SIZE_SMALL -> BITMAP_CUE_SCALE_SMALL
                SUBTITLE_SIZE_LARGE -> BITMAP_CUE_SCALE_LARGE
                else -> 1f
            }
            // The position setting is an index into low / mid / high, the same
            // three the text renderer's own gravity uses.
            val liftFraction = when (AppPreferences.getDefaultSubtitlePosition(applicationContext)) {
                SUBTITLE_POSITION_HIGH -> -BITMAP_CUE_POSITION_STEP * 2f
                SUBTITLE_POSITION_MID -> -BITMAP_CUE_POSITION_STEP
                else -> 0f
            }
            val lift = liftFraction * (view.height.takeIf { it > 0 }?.toFloat() ?: 0f) / 360f
            view.scaleX = size
            view.scaleY = size
            view.pivotX = view.width / 2f
            view.pivotY = view.height.toFloat()
            view.translationY = lift
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
            if (assOwned()) return
            if (positionDriven) {
                updateFromPosition()
                return
            }
            renderText(currentCueText())
        }

        /** Re-renders with the new size/background (offset unchanged). */
        fun refreshStyle() {
            if (assOwned()) return
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
            if (assOwned() || !positionDriven) return
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
            // getOrElse, not []: subtitleSize is a SYNCED preference, so a blob
            // written by another build (a fourth size that exists there and not
            // here), a downgrade/sideload, or a corrupt value can carry an index
            // outside this list - and the throw happens on the first cue of
            // every launch, with no way out but clearing prefs. The MPV engine's
            // twin already reads its size through when/else.
            subtitleText.setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                sizes.getOrElse(subtitleSize) { 14f }
            )
            // Embedded styling (ASS BackColour, styled WebVTT) can carry its
            // own background spans on the cue text (position-driven external
            // subs pass Cue.text through raw). Strip them so the box never
            // renders — the app's Background choice must be the only thing
            // that paints behind subtitles.
            // Clean SDH runs first, on the raw cue text: it drops the speaker
            // labels and the sound descriptions from a caption track, and a cue
            // that was nothing but captions is left empty - reported as "no
            // subtitle" rather than drawn as an empty box.
            val body = if (cleanSdhCaptions) SdhCaptionCleaner.cleaned(text) else text
            if (body.isEmpty()) {
                subtitleText.visibility = View.GONE
                subtitleText.background = null
                return
            }
            val clean = withoutEmbeddedBoxes(body)
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
     * Reads a subtitle source, bounded by [MAX_SUBTITLE_FILE_BYTES]: a file
     * larger than the cap is refused (null) rather than pulled into memory, so
     * a mislabeled or hostile source cannot OOM the player. UTF-8, matching the
     * previous readText() behaviour.
     */
    private fun readSubtitleText(uri: Uri): String? =
        runCatching {
            val input = contentResolver.openInputStream(uri) ?: return@runCatching null
            input.use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_SUBTITLE_FILE_BYTES) return@runCatching null
                    out.write(buffer, 0, read)
                }
                out.toString(Charsets.UTF_8.name())
            }
        }.getOrNull()

    /**
     * Reads the picked external subtitle file off the main thread and routes
     * it to the renderer it belongs to: an ASS/SSA script goes to libass (see
     * the ASS section below) when this build has it, everything else is parsed
     * into [externalSubtitleCues].
     *
     * Parsing SRT/WebVTT locally is what makes a true negative offset
     * possible: Media3 has no subtitle-delay API and only emits cues at their
     * authored time, but with the file in hand the handler can render any cue
     * on demand.
     */
    private fun loadExternalSubtitleCues(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val text = readSubtitleText(uri)
            // Sniffed from the content, not the URI: an OpenSubtitles download
            // is a numeric file id with no extension at all.
            val assScript = text?.takeIf {
                AssSubtitleRenderer.available && AssSubtitleSource.isAssContent(it)
            }
            val parsed = if (assScript == null) {
                text?.let { SubtitleFileParser.parse(it) } ?: emptyList()
            } else {
                emptyList()
            }
            // withContext, not a nested launch(Dispatchers.Main): the IO
            // coroutine is already scoped to lifecycleScope, so handing the
            // result back on Main is the same hop - but this one the IO body
            // actually waits for, and a failure in the Main block is thrown
            // here instead of into a sibling launch nobody joins.
            withContext(Dispatchers.Main) {
                if (assScript != null) {
                    attachAssSubtitle(assScript, AssOverlaySource.SIDECAR)
                    return@withContext
                }
                detachAssSubtitle()
                externalSubtitleCues = parsed
                if (!parsed.isEmpty()) {
                    Log.i(TAG, "External subtitles parsed: ${parsed.size} cues")
                }
                subtitleCueHandler?.updateFromPosition()
            }
        }
    }

    // --- ASS sidecar rendering ------------------------------------------

    /**
     * The tick that keeps the libass overlay in step with the playhead. It is
     * also the (re)loader: a player rebuild - a background return, a source
     * switch, an engine change - frees the native instance but not the script,
     * so the next tick rebuilds the renderer rather than leaving the sidecar
     * dark for the rest of the session.
     */
    private val assFrameTick = object : Runnable {
        override fun run() {
            val renderer = assRenderer
            val script = assSubtitleContent
            if (script != null) {
                if (!renderer.active) {
                    // The load is left to [loadAssScript] and the tick waits for
                    // its answer rather than doing the work inline; a second
                    // load while one is in flight would parse the same script
                    // twice and race the first one's result.
                    if (assLoadInFlight) return
                    loadAssScript(script, renderer)
                    return
                }
            } else if (!renderer.active) {
                // No whole script and no streaming source yet: nothing to draw
                // until one arrives (see armEmbeddedAssOverlay).
                return
            }
            // The overlay owns the subtitle area only while something is still
            // attached. A load that answers after its script was detached
            // leaves an instance with no script behind it, and drawing that one
            // would cover the picture with a subtitle the viewer just removed.
            if (assOverlaySource == AssOverlaySource.NONE) return
            // Everything below is scoped to the track the overlay TYPESETS, not
            // to "a script is loaded". A selection the overlay has not claimed
            // keeps its own flattened cues and must not be painted over by a
            // script still held for another one - and a claim that comes back
            // (the same addon track selected again) re-draws from here, which is
            // why this poll keeps running rather than stopping.
            if (!assOverlayOwns()) {
                if (subtitleAssImage.visibility != View.GONE) {
                    subtitleAssImage.setImageDrawable(null)
                    subtitleAssImage.visibility = View.GONE
                }
                handler.postDelayed(this, ASS_IDLE_INTERVAL_MS)
                return
            }
            // The pipeline still emits flattened cues for the same file, and
            // they would draw behind the rendered frame: while the overlay
            // typesets the selected track it owns the subtitle area.
            externalSubtitleCues = emptyList()
            if (subtitleText.visibility != View.GONE) subtitleText.visibility = View.GONE
            drawAssFrame()
            val delay = if (exoPlayer?.isPlaying == true) ASS_FRAME_INTERVAL_MS else ASS_IDLE_INTERVAL_MS
            handler.postDelayed(this, delay)
        }
    }

    /**
     * Starts the tick for [content]. The script is loaded by the tick itself,
     * so every caller sees the same load-and-fall-back behaviour without
     * repeating it here.
     *
     * Only the whole-script paths reach this (ADDON and SIDECAR); the EMBEDDED
     * streaming path arms the overlay through [armEmbeddedAssOverlay], because
     * its events arrive over time and its renderer does own the selected track.
     */
    private fun attachAssSubtitle(
        content: String,
        source: AssOverlaySource,
        addonUrl: String? = null
    ) {
        // A script with zero Dialogue events draws nothing, ever - but libass
        // loads it happily and the overlay then counts as live, so the cue gate
        // used to swallow every cue of the real track for a script that could
        // never paint a pixel. Refuse it instead and leave the selection to the
        // flattened cues the pipeline already renders.
        val events = AssSubtitleSource.dialogueEventCount(content)
        if (events == 0) {
            Log.w(
                TAG_ASS,
                "ASS script refused: events=0 source=$source" +
                    (addonUrl?.let { " host=${logHost(it)}" } ?: "")
            )
            // Whatever this attach was replacing must not keep painting.
            if (assOverlaySource == source) detachAssSubtitle()
            return
        }
        assSubtitleContent = content
        assOverlaySource = source
        assOverlayAddonUrl = addonUrl
        Log.i(
            TAG_ASS,
            "ASS overlay attached: source=$source events=$events" +
                (addonUrl?.let { " host=${logHost(it)}" } ?: "")
        )
        handler.removeCallbacks(assFrameTick)
        handler.post(assFrameTick)
    }

    /** Creates/loads the libass renderer. False when libass will not take the script. */
    private fun startAssSubtitle(content: String, renderer: AssSubtitleRenderer): Boolean {
        // fontconfig finds the system fonts itself; these are the extras the
        // user dropped into the app's own folder, which fontconfig cannot see.
        val configPath = AssSubtitleRenderer.installFontConfig(assets, filesDir)
        val fonts = AssSubtitleSource.collectFonts(listOf(File(filesDir, ASS_FONT_DIR)))
        return renderer.load(content, fonts, configPath, cacheDir.absolutePath)
    }

    /**
     * Loads [content] into [renderer] off the main thread, and posts only the
     * result.
     *
     * The tick used to do this inline: [startAssSubtitle] installs the
     * fontconfig file and lists the fonts, and [AssSubtitleRenderer.load]
     * normalises and parses the script. A sidecar runs to a few hundred KB, so
     * that is a main-thread stall on the frame the overlay first comes up - and
     * the same work runs again on every player rebuild, which the overlay tick
     * is the reloader for. Both are I/O plus a parse and touch nothing that
     * needs the main thread; the tick is what does, so it is re-armed here once
     * the answer is known and the fall-back keeps its place in the order.
     */
    private fun loadAssScript(content: String, renderer: AssSubtitleRenderer) {
        assLoadInFlight = true
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { startAssSubtitle(content, renderer) }
            assLoadInFlight = false
            // Only fall back for the script still attached: a different one has
            // its own load, and this failure is about the text that was
            // replaced, not about the one on screen now.
            if (!loaded && assSubtitleContent === content) fallBackFromAss(content)
            handler.removeCallbacks(assFrameTick)
            handler.post(assFrameTick)
        }
    }

    /**
     * libass would not take the script: keep the sidecar usable as plain cues
     * rather than leaving the viewer with no subtitle at all.
     */
    private fun fallBackFromAss(content: String) {
        Log.w(TAG, "libass could not load the ASS sidecar; rendering plain cues instead")
        assSubtitleContent = null
        releaseAssRenderer()
        externalSubtitleCues = SubtitleFileParser.parse(content)
        subtitleCueHandler?.updateFromPosition()
    }

    /**
     * Draws the frame for the current playback position. Nothing to do until
     * the player has told us the video's size: libass lays out against that
     * rectangle, and rendering before it is known would place every line
     * against the cap rectangle instead of the video's aspect ratio.
     */
    private fun drawAssFrame() {
        val renderer = assRenderer.takeIf { it.active } ?: return
        val player = exoPlayer ?: return
        val size = player.videoSize
        if (size.width > 0 && size.height > 0) {
            val viewport = AssSubtitleSource.viewport(size.width, size.height)
            renderer.setViewport(viewport[0], viewport[1])
        }
        // The user's offset shifts ASS exactly as it shifts text cues.
        val timeMs = (player.currentPosition + subtitleOffsetMs).coerceAtLeast(0L)
        val frame = renderer.render(timeMs) ?: return
        // Coming back from hidden (a fresh attach, a rebuild, a source switch)
        // needs the draw regardless of what libass says about this tick.
        val becomingVisible = subtitleAssImage.visibility != View.VISIBLE
        subtitleAssImage.setImageBitmap(frame.bitmap)
        if (becomingVisible) subtitleAssImage.visibility = View.VISIBLE
        // Audit P5: only repaint when the picture actually changed. The buffer
        // is reused and re-rendered every tick, so this flag is the only way a
        // static cue avoids a full-frame GPU composite at 30 fps.
        if (frame.changed || becomingVisible) {
            // The frame buffer is reused across ticks, so its pixels changed
            // while the drawable did not: without this the view has no reason
            // to redraw.
            subtitleAssImage.invalidate()
        }
    }

    /** Stops the overlay and frees the native instance, keeping the script. */
    private fun releaseAssRenderer() {
        handler.removeCallbacks(assFrameTick)
        assRenderer.release()
        if (::subtitleAssImage.isInitialized) {
            subtitleAssImage.setImageDrawable(null)
            subtitleAssImage.visibility = View.GONE
        }
    }

    /** Drops the script too: a different subtitle source is being attached. */
    private fun detachAssSubtitle() {
        assSubtitleContent = null
        assOverlaySource = AssOverlaySource.NONE
        assOverlayAddonUrl = null
        assClaimLogged = null
        releaseAssRenderer()
    }

    /**
     * Arms the overlay for an embedded ASS track. There is no whole script to
     * load: the streaming renderer feeds the shared libass instance as samples
     * arrive, and the tick draws once it is active.
     */
    private fun armEmbeddedAssOverlay() {
        assOverlaySource = AssOverlaySource.EMBEDDED
        assSubtitleContent = null
        assOverlayAddonUrl = null
        handler.removeCallbacks(assFrameTick)
        handler.post(assFrameTick)
    }

    /**
     * Routes the selected addon ASS track to the libass overlay.
     *
     * The offer was already downloaded by the controller (its own cached copy),
     * so this re-reads that file and loads it as a whole script — the same path
     * a sidecar takes. Guarded so a repeated onTracksChanged for the same track
     * does not reload it.
     */
    private fun attachAddonAss(url: String) {
        if (assOverlaySource == AssOverlaySource.ADDON && assOverlayAddonUrl == url) return
        lifecycleScope.launch(Dispatchers.IO) {
            val uri = AddonSubtitleSource.download(this@NativePlayerActivity, url)
            if (uri == null) {
                Log.w(TAG_ASS, "addon ASS not attached: download failed host=${logHost(url)}")
                return@launch
            }
            val text = readSubtitleText(uri)
            if (text == null) {
                Log.w(TAG_ASS, "addon ASS not attached: download unreadable host=${logHost(url)}")
                return@launch
            }
            if (!AssSubtitleSource.isAssContent(text)) {
                Log.w(
                    TAG_ASS,
                    "addon ASS not attached: content is not ASS/SSA host=${logHost(url)}"
                )
                return@launch
            }
            withContext(Dispatchers.Main) {
                // The download is async: if the viewer switched tracks (or
                // turned subtitles off) while it was in flight, attaching now
                // would paint the wrong track's typesetting, or subtitles after
                // OFF. Only attach while this URL is still the selected track.
                if (selectedAddonAssUrl() != url) {
                    Log.w(
                        TAG_ASS,
                        "addon ASS not attached: the selection moved on " +
                            "host=${logHost(url)}"
                    )
                    return@withContext
                }
                attachAssSubtitle(text, AssOverlaySource.ADDON, url)
            }
        }
    }

    /**
     * The host of [url], for a log line: a route or a query string can carry a
     * token, and no field log is allowed to be a copy of one.
     */
    private fun logHost(url: String): String =
        runCatching { Uri.parse(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: "unknown"

    /**
     * What the SELECTED text tracks say about the libass overlay: the one
     * account both the routing ([handleAssTrackSelection]) and the cue gate
     * ([SubtitleCueHandler]) read, so the two cannot disagree about which track
     * the overlay owns. [language] and [label] are the first selected text
     * track's, for the log lines only.
     */
    private class AssSelection(
        val addonUrl: String?,
        val embedded: Boolean,
        val sidecar: Boolean,
        val language: String?,
        val label: String?
    )

    private fun assSelection(tracks: Tracks): AssSelection {
        var addonUrl: String? = null
        var embedded = false
        var sidecar = false
        var language: String? = null
        var label: String? = null
        var seenSelection = false
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until group.length) {
                if (!group.isSelected) continue
                val fmt = group.getTrackFormat(i)
                if (!seenSelection) {
                    seenSelection = true
                    language = fmt.language
                    label = fmt.label
                }
                val url = addonSubtitleController.assSourceFor(fmt)
                if (url != null) {
                    if (addonUrl == null) addonUrl = url
                    continue
                }
                // The user's own sidecar is ALSO an SSA track, and it is
                // rendered whole-script under the SIDECAR overlay. Without this
                // skip it fell into the embedded branch below, which nulls the
                // loaded script and flips to the streaming path - a fansub
                // sidecar lost its typesetting the moment its track was
                // selected. The config is tagged at its construction site.
                if (fmt.id == SIDECAR_TRACK_ID) {
                    sidecar = true
                    continue
                }
                if (fmt.sampleMimeType == MimeTypes.TEXT_SSA) embedded = true
            }
        }
        return AssSelection(addonUrl, embedded, sidecar, language, label)
    }

    /**
     * Whether the overlay's own claim covers [selection]: ADDON when the
     * selected track is the offer the overlay holds, EMBEDDED when it is the
     * streaming track, SIDECAR when it is the user's own file. Deliberately
     * says nothing about whether the renderer has finished loading.
     */
    private fun assClaimCovers(selection: AssSelection): Boolean = when (assOverlaySource) {
        AssOverlaySource.NONE -> false
        AssOverlaySource.ADDON ->
            selection.addonUrl != null && selection.addonUrl == assOverlayAddonUrl
        AssOverlaySource.SIDECAR -> selection.sidecar
        AssOverlaySource.EMBEDDED -> selection.embedded
    }

    /** Whether the overlay is typesetting the track that is selected right now. */
    private fun assOverlayClaimsSelection(): Boolean {
        val tracks = exoPlayer?.currentTracks ?: return false
        return assClaimCovers(assSelection(tracks))
    }

    /**
     * Whether the overlay owns the subtitle area, including the claim the engine
     * cannot see.
     *
     * [assOverlayClaimsSelection] reads the SELECTED TRACK, which only exists
     * when the sidecar was armed into a MediaItem - i.e. after a player rebuild.
     * The auto-fetch path attaches without one (see [attachExternalSubtitle]), so
     * its ASS script has no track to be selected and the plain claim said "not
     * mine": the frame tick hid every rendered frame and the cue gate let the
     * flattened cues through instead. A sidecar drawn by this app IS the
     * overlay's claim, whichever way it was attached.
     */
    private fun assOverlayOwns(): Boolean =
        assOverlayClaimsSelection() ||
            (sidecarRenderedByApp && assOverlaySource == AssOverlaySource.SIDECAR)

    /** The ASS source URL of the currently SELECTED text track, or null. */
    private fun selectedAddonAssUrl(): String? =
        exoPlayer?.currentTracks?.let { assSelection(it).addonUrl }

    /**
     * Routes the selected text track to the libass overlay when it is an ASS
     * source, and drops the overlay when that ASS track is deselected.
     *
     * The source guard is the point: an ADDON or EMBEDDED overlay is cleared
     * when its track goes away, but a user-picked sidecar (SIDECAR) is never
     * clobbered by a track change elsewhere.
     *
     * Every claim is logged here, with the track it is about and the basis it
     * was made on. The field diagnosis of a selected-but-black track had to be
     * done by elimination because none of it was ever written down.
     */
    private fun handleAssTrackSelection(tracks: Tracks) {
        val selection = assSelection(tracks)
        val selectedAddon = selection.addonUrl
        when {
            selectedAddon != null -> {
                logAssClaim(
                    identity = "ADDON|$selectedAddon",
                    line = "claim lang=${selection.language} label=${selection.label} " +
                        "basis=id source=ADDON host=${logHost(selectedAddon)}"
                )
                attachAddonAss(selectedAddon)
            }
            selection.embedded && AssSubtitleRenderer.available -> {
                logAssClaim(
                    identity = "EMBEDDED",
                    line = "claim lang=${selection.language} label=${selection.label} " +
                        "basis=mime source=EMBEDDED"
                )
                armEmbeddedAssOverlay()
            }
            selection.sidecar && assOverlaySource == AssOverlaySource.SIDECAR -> {
                // The sidecar's own claim: the overlay was armed when the file
                // was picked, and this is the selection it belongs to.
                logAssClaim(
                    identity = "SIDECAR",
                    line = "claim lang=${selection.language} label=${selection.label} " +
                        "basis=id source=SIDECAR"
                )
            }
            assOverlaySource == AssOverlaySource.ADDON ||
                assOverlaySource == AssOverlaySource.EMBEDDED -> detachAssSubtitle()
        }
    }

    /**
     * One line per distinct claim. onTracksChanged fires for reasons that are
     * not a selection change (an adaptive audio or video switch), and a claim
     * repeated for the same track is not news.
     */
    private fun logAssClaim(identity: String, line: String) {
        if (assClaimLogged == identity) return
        assClaimLogged = identity
        Log.i(TAG_ASS, line)
    }

    /**
     * Rebuilds the libass overlay for the sidecar remembered from a previous
     * session. Deliberately narrower than [loadExternalSubtitleCues]: the
     * remembered URI is already turned into a media3 sidecar track by
     * createPlayer(), so populating [externalSubtitleCues] here as well would
     * put a remembered SRT/WebVTT into position-driven rendering - a change
     * to the text path, which is not what restoring the ASS overlay is for.
     */
    private fun restoreAssSubtitle(uri: Uri) {
        if (!AssSubtitleRenderer.available) return
        lifecycleScope.launch(Dispatchers.IO) {
            val text = readSubtitleText(uri) ?: return@launch
            if (!AssSubtitleSource.isAssContent(text)) return@launch
            withContext(Dispatchers.Main) { attachAssSubtitle(text, AssOverlaySource.SIDECAR) }
        }
    }

    private fun togglePlayPause() {
        exoPlayer?.let {
            // A finished session stays finished: a play press after the end
            // (stray remote button, Bluetooth remote, assistant) must not
            // restart the episode from the top. Replay is an explicit seek or a
            // fresh session, not a toggle.
            if (playbackEndedHandled) return
            // playWhenReady, not isPlaying: they disagree while the player is
            // BUFFERING (playWhenReady=true, isPlaying=false). Reading isPlaying
            // there flipped playWhenReady from true to TRUE - a no-op - and took
            // the "resuming" branch, so a viewer could not pause during a stall
            // and the pause-point saveProgress never ran. The scrub code already
            // captures playWhenReady for exactly this reason.
            val wasPlaying = it.playWhenReady
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

    /**
     * What an empty picker says, per mode.
     *
     * The empty guard keeps the panel shut (a blank box with the D-pad trapped
     * on its scrim is worse than no panel), but staying silent made every one of
     * these a press that did nothing - see [showPicker] (audit U-F12,
     * 2026-10-27). Copy is per mode because "no sources" and "no audio tracks"
     * are different facts about the title, and the viewer can act on the second
     * one but not the first.
     */
    private fun emptyPickerMessage(mode: PickerMode): String = when (mode) {
        PickerMode.SOURCE -> "No other sources for this title"
        PickerMode.AUDIO -> "This file has no audio tracks to choose from"
        PickerMode.SUBTITLE -> "No subtitles in this file — use SEARCH in the subtitle row"
        PickerMode.SPEED -> "No playback speeds available here"
    }

    /**
     * Restarts the shared bar's auto-hide countdown.
     *
     * This engine's own guards - no hiding while paused (live excepted, since a
     * live channel cannot be paused and isPlaying reads false for the whole
     * buffering start), and none while a panel owns the screen - are answered by
     * chromeMayAutoHide when the countdown fires, so the rule lives in one place
     * and this is only a restart.
     */
    private fun scheduleAutoHide() {
        chrome?.restartAutoHide()
    }

    /** Cancels the pending auto-hide; the bar stays up until [scheduleAutoHide]. */
    private fun removeAutoHide() {
        chrome?.cancelAutoHide()
    }

    // --- Picker ---

    /**
     * Switches to a hash-only source by first asking TorBox for its link.
     *
     * The in-player picker lists the same rows the streams screen does, and a
     * row with no URL of its own is a torrent the app cannot open by itself
     * (audit S-F2). The streams screen resolved these already; this is the same
     * call, made from the player, so the press works here too instead of being
     * a toast that sends the viewer back a screen.
     *
     * Resolving takes a round trip, so the picker stays up until it answers: a
     * success switches and dismisses, and every failure - no key, not cached,
     * a dead request - leaves the picker where it is with the resolver's own
     * words when it has any.
     */
    private fun resolveAndSwitch(stream: Stream) {
        lifecycleScope.launch {
            val resolved = runCatching { TorBoxResolve.playableStream(this@NativePlayerActivity, stream) }
                .getOrNull()
            if (resolved == null || resolved.url.isNullOrBlank()) {
                Toast.makeText(
                    this@NativePlayerActivity,
                    "This source needs TorBox — it could not be prepared from its hash",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            // The resolved row replaces the hash row in this session's list, so
            // "next source" and the picker itself continue from a playable row
            // rather than from the hash that was just prepared.
            val index = sources.indexOfFirst { TorBoxResolve.isUnresolved(it) && it == stream }
            if (index >= 0) {
                sources = sources.toMutableList().also { it[index] = resolved }
            }
            dismissPicker()
            switchToSource(resolved)
        }
    }

    private fun showPicker(mode: PickerMode) {
        // The rows are built BEFORE anything becomes visible, and an empty list
        // abandons the whole call. The order used to be the other way round -
        // container and scrim VISIBLE, isPickerShowing up (which is what stops
        // the chrome auto-hiding, see chromeMayAutoHide) - and a player
        // released mid-transition left `currentTracks` null, so the `?: return`
        // in the AUDIO/SUBTITLE branches exited with a visible, empty, dimmed
        // picker: no adapter ever set, the posted pickerList.requestFocus()
        // never reached, and nothing but Back out of it. currentTracks == null
        // now reads as an empty track list rather than a mid-state exit, and
        // SOURCE mode gets the same empty guard MPV already has (see
        // MpvPlayerActivity.showPicker).
        val tracks = exoPlayer?.currentTracks

        val items = when (mode) {
            PickerMode.SOURCE -> {
                pickerTitle.text = "SOURCES"
                sources.map { stream ->
                    PickerItem(
                        label = stream.displayLabel(),
                        isSelected = stream.url == currentUrl,
                        badges = stream.badges,
                        onClick = {
                            // A hash row has no direct URL, so switchToSource
                            // answers `url ?: return` and the picker used to
                            // close over a player that had not changed - a press
                            // with no effect and no word about it (audit S-F2,
                            // 2026-10-27). It now does what the streams screen
                            // does with the same row: asks TorBox for the CDN
                            // link (see TorBoxResolve) and switches to THAT,
                            // which is the whole reason the row is in the list.
                            // Every refusal keeps the picker open and says why,
                            // so a failed resolve is not a press that did
                            // nothing.
                            if (stream.url.isNullOrBlank()) {
                                resolveAndSwitch(stream)
                            } else {
                                switchToSource(stream)
                                dismissPicker()
                            }
                        }
                    )
                }
            }
            PickerMode.AUDIO -> {
                pickerTitle.text = "AUDIO"
                val audioGroups = tracks?.groups.orEmpty()
                    .filter { it.type == C.TRACK_TYPE_AUDIO }
                // Position of each audio track among all of them, and the total:
                // both go into the stored signature, so a hand-picked track
                // whose tag says nothing about its language can still be found
                // in the next episode (see PlayerTrackBridge.signatureOf).
                val audioTrackCount = audioGroups.sumOf { it.length }
                val audioPrefixes = audioGroups.runningFold(0) { sum, group -> sum + group.length }
                audioGroups.flatMapIndexed { groupIdx, group ->
                    (0 until group.length).map { trackIdx ->
                        val format = group.getTrackFormat(trackIdx)
                        val audioPosition = audioPrefixes[groupIdx] + trackIdx
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
                                // Through the bridge, not the player directly: a
                                // raw write here outlived nothing - the bridge's
                                // ready-time pass re-applied the language prefs
                                // on the next rebuffer and reverted the pick.
                                PlayerTrackBridge.chooseAudioTrack(
                                    this@NativePlayerActivity,
                                    PlayerTrackBridge.signatureOf(
                                        format,
                                        audioPosition,
                                        audioTrackCount
                                    )
                                )
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
                val textGroups = tracks?.groups.orEmpty()
                    .filter { it.type == C.TRACK_TYPE_TEXT }
                val anySelected = textGroups.any { g -> (0 until g.length).any { g.isTrackSelected(it) } }
                val offItem = PickerItem(
                    label = "OFF",
                    // An app-rendered sidecar IS subtitles on, even though the
                    // engine has no selected track to point at.
                    isSelected = !anySelected && !sidecarRenderedByApp,
                    onClick = {
                        // OFF must also take down a sidecar this app is drawing
                        // itself (see [sidecarRenderedByApp]); the engine-side
                        // track selection below cannot do it, because that
                        // sidecar has no track.
                        releaseAppRenderedSidecar()
                        // Through the bridge: OFF is remembered per show, so the
                        // ready-time language pass cannot turn subtitles back on
                        // at the next rebuffer.
                        PlayerTrackBridge.chooseSubtitlesOff(this@NativePlayerActivity)
                        dismissPicker()
                    }
                )
                // The same position math as the audio rows above, for the same
                // reason: two same-language text tracks have identical
                // identities (two English SRT tracks both read "eng|srt|0"),
                // and only the flat position tells them apart - without it the
                // press collapses to the first identity match and the wrong
                // track arms (see PlayerTrackBridge.signatureOf). This
                // enumeration order matches PlayerTrackBridge.trackRefs exactly
                // - text groups in order, each group's tracks in order.
                val totalTextTracks = textGroups.sumOf { it.length }
                val textPrefixes = textGroups.runningFold(0) { sum, group -> sum + group.length }
                subtitleItems + listOf(offItem) + textGroups.flatMapIndexed { groupIdx, group ->
                    (0 until group.length).map { trackIdx ->
                        val format = group.getTrackFormat(trackIdx)
                        val position = textPrefixes[groupIdx] + trackIdx
                        // Non-null when no renderer in THIS engine can draw the
                        // track. Naming it is the fix for the rows that used to
                        // draw nothing with no explanation, and the press below
                        // uses the same answer to reach the engine that can.
                        val cannotDraw = SubtitleTrackRules.cannotDrawNote(
                            mimeType = format.sampleMimeType,
                            supported = group.isTrackSupported(trackIdx)
                        )
                        PickerItem(
                            // Language plus the kind and the format, so a PGS row
                            // says "PGS" and an SDH row says "SDH" before the
                            // press rather than after. The format badge is pure
                            // information now that PGS draws here: whether THIS
                            // engine can draw the track is cannotDraw below, and
                            // a VobSub or DVB row still says "needs MPV" there.
                            // An ASS track is typeset by libass when the build
                            // carries it, and flattened to plain cues otherwise.
                            label = listOfNotNull(
                                SubtitleTrackRules.pickerLabel(
                                    language = format.language,
                                    mimeType = format.sampleMimeType,
                                    fallback = "Track ${groupIdx + 1}",
                                    // The track's own name is where an SDH /
                                    // signs / commentary track says what it is
                                    // (the language tag never does), and the
                                    // container's forced flag is the other half
                                    // of it.
                                    title = format.label,
                                    forced = (format.selectionFlags and
                                        C.SELECTION_FLAG_FORCED) != 0
                                ),
                                cannotDraw
                            ).joinToString(" \u00b7 "),
                            isSelected = group.isTrackSelected(trackIdx),
                            onClick = {
                                dismissPicker()
                                // Picking a track hands the subtitle area back to
                                // the engine: an app-rendered sidecar would keep
                                // painting over the track just chosen (it asks
                                // the cue pipeline to stand down while it runs).
                                releaseAppRenderedSidecar()
                                if (cannotDraw != null) {
                                    // Arming it here would draw nothing and
                                    // report nothing, which is exactly the
                                    // "picked a subtitle and got no subtitle"
                                    // report. Picking it IS asking for it, so
                                    // the handoff is allowed even in the
                                    // ExoPlayer-only setting - the same rule the
                                    // SWITCH button uses (see handOffToMpv) - and
                                    // MPV demuxes and draws these formats itself.
                                    if (!handOffToMpv(
                                            MpvPlayerActivity.FALLBACK_REASON_SUBTITLE,
                                            manual = true
                                        )
                                    ) {
                                        // Refused: live TV, a DRM session, no
                                        // libmpv. Say so, rather than leave the
                                        // press looking like it did nothing.
                                        Toast.makeText(
                                            this,
                                            "This subtitle needs the MPV player, " +
                                                "which is unavailable here",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        // And do what the AUTO path does on the
                                        // same refusal: fetch a text track, which
                                        // this engine CAN draw. Without it a
                                        // refused picker handoff was the end of
                                        // the road for the viewer, while the
                                        // identical refusal at READY time fell
                                        // back to a downloaded subtitle (audit
                                        // E-F5, 2026-10-27). The fetch carries
                                        // its own gates (mode, key, title, once a
                                        // session).
                                        maybeAutoFetchSubtitle()
                                    }
                                } else {
                                    // Through the bridge for the same reason as
                                    // OFF and the audio rows: the ready-time
                                    // language pass would otherwise replace this
                                    // precise pick with the first track in the
                                    // preferred language.
                                    PlayerTrackBridge.chooseSubtitleTrack(
                                        this@NativePlayerActivity,
                                        PlayerTrackBridge.signatureOf(
                                            format,
                                            index = position,
                                            trackCount = totalTextTracks
                                        )
                                    )
                                }
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

        // An empty list is a dead end, not a panel: nothing to pick means the
        // picker stays shut rather than opening on a blank box, with the D-pad
        // left on a scrim it cannot leave (MPV's rule). It SAYS so, though: the
        // button that asked for it is visible and live, and a silent return read
        // as a button that ignored the press (audit U-F12, 2026-10-27).
        if (items.isEmpty()) {
            Toast.makeText(this, emptyPickerMessage(mode), Toast.LENGTH_SHORT).show()
            return
        }

        pickerMode = mode
        isPickerShowing = true
        dismissSettingsPanel()
        // Scrim and panel arrive together, and the panel rises into place from
        // the edge it is docked to. The container is VISIBLE in this frame (the
        // panel is only up once its rows exist - that ordering is the contract
        // this function has always kept); the fade and the slide are drawn on
        // top of it, so neither moves a row's focusable bounds and the focus
        // request below is posted exactly as before.
        pickerContainer.visibility = View.VISIBLE
        KbViewMotion.fadeIn(scrim)
        KbViewMotion.slideFadeIn(
            pickerContainer,
            24f * resources.displayMetrics.density
        )
        pickerList.adapter = PickerAdapter(items)
        pickerLastFocus = null
        pickerList.post {
            // A fast Back between the picker coming up and this runnable firing
            // (dismissPicker sets isPickerShowing false and GONEs the container)
            // used to re-park the D-pad on a container that is no longer drawn:
            // the press was swallowed with it.
            parkPickerFocus()
        }
    }

    /**
     * Hands the D-pad to the picker's first row, once that row exists.
     *
     * A bare `pickerList.requestFocus()` is not enough, and that is the rest of
     * the "the picker sometimes loses focus to the overlay" report: the rows are
     * created in the adapter's first layout pass, which runs AFTER this call
     * (a post() lands before the next traversal), and a focusable list with no
     * children answers the request ITSELF. The panel then has no highlighted row
     * at all, so the first D-pad press walks out of it onto the scrim or the
     * chrome behind it. Hence the two steps: focus the row when it is there, and
     * otherwise wait for the layout that places one.
     */
    private fun parkPickerFocus() {
        if (!isPickerShowing) return
        if (pickerList.getChildAt(0)?.requestFocus() == true) return
        pickerList.viewTreeObserver.addOnGlobalLayoutListener(
            object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    val firstRow = pickerList.getChildAt(0)
                    if (firstRow == null) {
                        // No row in this pass. Stop waiting if the panel closed
                        // underneath us; otherwise the next layout tries again.
                        if (!isPickerShowing) {
                            pickerList.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        }
                        return
                    }
                    pickerList.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    // The panel closed, or the D-pad is already inside it (the
                    // viewer moved before the first row was placed): the press
                    // that follows must not be taken off them either way.
                    if (!isPickerShowing || pickerList.findFocus() != null) return
                    firstRow.requestFocus()
                }
            }
        )
    }

    /**
     * Takes the remote away from every side panel before an end-of-episode
     * panel is raised.
     *
     * The picker and the settings panel hold the D-pad with the window-level
     * guards in bindViews: while either is open, any focus that leaves it is
     * pulled straight back inside. The end-of-episode card is raised from the
     * progress tick, so a subtitle picker the viewer happened to have open was
     * still up - and still armed - the moment the card appeared. Its own
     * `PLAY NEXT` request was answered by the guard on the spot, so the card sat
     * on screen with no way to reach it (the field report: "the next up popup
     * popped up and it wouldn't let me gain focus on the popup because it was in
     * the subtitle picker"), and its countdown could run out while they were
     * still reading the picker.
     *
     * The card is the decision now, so the panels close with it. The card parks
     * focus on PLAY NEXT itself once it is visible; anything these dismissals
     * hand back to the chrome is superseded by that.
     */
    private fun closePanelsForEndCard() {
        if (isGuideShowing) dismissChannelGuide()
        if (isPickerShowing) dismissPicker()
        if (showSettingsPanel) dismissSettingsPanel()
        if (infoPanel.visibility == View.VISIBLE) hideInfoPanel()
    }

    /**
     * Gives the D-pad back to the picker after it has escaped the panel.
     *
     * Called from the window's global focus-change listener (see bindViews), so
     * it runs synchronously inside the focus change and wins the race against a
     * posted requestFocus() from the chrome. The row the viewer was on is the
     * target; with nothing remembered inside the panel yet (the first pass after
     * it opened) it is the first row - and when neither exists yet, the park
     * that showPicker left attached still places it.
     */
    private fun reassertPickerFocus() {
        if (!isPickerShowing) return
        val remembered = pickerLastFocus
        if (remembered != null && remembered.isShown && remembered.requestFocus()) return
        pickerList.getChildAt(0)?.requestFocus()
    }

    // --- Settings Panel ---
    private fun toggleSettingsPanel() {
        if (showSettingsPanel) dismissSettingsPanel() else showSettingsPanelView()
    }

    private fun showSettingsPanelView() {
        // The flag goes up before the picker comes down so the picker's focus
        // hand-back (see dismissPicker) sees a panel taking over and leaves the
        // D-pad alone - the panel parks it on its own first row below.
        showSettingsPanel = true
        dismissPicker()
        KbViewMotion.fadeIn(scrim)
        KbViewMotion.slideFadeIn(
            settingsContainer,
            24f * resources.displayMetrics.density
        )
        settingsContainer.isFocusable = true
        settingsContainer.isFocusableInTouchMode = true
        updateSettingsPanelState()
        settingsContainer.post {
            // Same race as the picker's: Back closes the panel and hides the
            // container, and focus posted after that lands on nothing.
            if (!showSettingsPanel) return@post
            settingsBufferAuto.requestFocus()
        }
    }

    private fun dismissSettingsPanel() {
        showSettingsPanel = false
        // Focus is handed back synchronously, exactly as before - the panel
        // fading out is already unfocused, so the hand-back cannot race it.
        KbViewMotion.slideFadeOut(settingsContainer)
        if (!isPickerShowing) KbViewMotion.fadeOut(scrim)
        restoreControlsFocus()
    }

    /**
     * Closes the picker and hands the D-pad back to the controls overlay.
     *
     * The hand-back is the point. A picker row owns focus while the picker is
     * up, and the row that owned it is gone the moment the container is, so
     * without this the framework moves focus to the next focusable view it can
     * find - the video surface - where the viewer's next OK is read as "hide
     * the controls" instead of "play". MpvPlayerActivity.dismissPicker() has
     * always handed it back; this engine's now does too.
     *
     * Deliberately synchronous: a posted requestFocus() loses the race to the
     * framework's own hand-off, which is why showControls()'s post() was not
     * enough on its own (the settings panel's focus guard carries the same
     * note).
     */
    private fun dismissPicker() {
        isPickerShowing = false
        pickerLastFocus = null
        KbViewMotion.slideFadeOut(pickerContainer)
        if (!showSettingsPanel) KbViewMotion.fadeOut(scrim)
        restoreControlsFocus()
    }

    // --- Channel guide overlay (live only) ---

    /**
     * The lineup as a snapshot. The registry exposes it by index because the
     * player otherwise only ever wants "the one playing" and "the one next";
     * the guide walks the whole lineup when it plans its EPG read, so it takes
     * the published list rather than rebuilding an equal one per pass.
     */
    private fun lineupChannels(): List<LiveChannelZapRegistry.ZapChannel> =
        LiveChannelZapRegistry.snapshot()

    /** True where a press could actually open the guide. */
    private fun channelGuideCanOpen(): Boolean =
        isLiveChannel &&
            LiveChannelZapRegistry.zapEnabled() &&
            !isGuideShowing &&
            !isPickerShowing &&
            !showSettingsPanel &&
            infoPanel.visibility != View.VISIBLE &&
            errorContainer.visibility != View.VISIBLE

    /**
     * Opens the guide overlay on the channel playing now.
     *
     * The rows go up from the lineup alone (numbers and names) and the EPG read
     * fills in NOW/NEXT a beat later - the same two-step the zap banner uses,
     * because a guide that waited on the database before painting anything
     * would open onto an empty panel on a slow box.
     */
    private fun showChannelGuide() {
        if (!channelGuideCanOpen()) return
        isGuideShowing = true
        // The picker and the settings sheet are the same "one panel at a time"
        // slot; opening the guide over one of them would stack two scrims.
        dismissPicker()
        dismissSettingsPanel()
        channelGuideTitle?.text = channelGuideTitleText()
        // The GUIDE title's accent resolved @color/kb_accent at inflation, so
        // re-apply the chosen accent each time the overlay opens (the rows do
        // the same as they are created) - the outline and the title then track
        // the theme together.
        channelGuideContainer?.let { retintAccentChrome(it, this) }
        KbViewMotion.fadeIn(scrim)
        KbViewMotion.slideFadeIn(
            channelGuideContainer,
            24f * resources.displayMetrics.density
        )
        removeAutoHide()
        publishChannelGuideRows(programs = emptyMap(), loading = true)
        focusChannelGuideCurrent()
        loadChannelGuidePrograms()
        startChannelGuideWatcher()
    }

    /**
     * The overlay's header: the browsed group, and its position when there is
     * more than one to browse - LEFT/RIGHT are the only way to move between
     * them, so the count is what tells the viewer they exist.
     */
    private fun channelGuideTitleText(): String {
        val label =
            LiveChannelZapRegistry.browsingGroup()
                ?.takeIf { it.isNotBlank() }
                ?.uppercase()
                ?: return "GUIDE"
        val position = LiveChannelZapRegistry.groupPosition()
        return if (position != null && position.second > 1) {
            "GUIDE  \u2022  $label  (${position.first}/${position.second})"
        } else {
            "GUIDE  \u2022  $label"
        }
    }

    /**
     * Moves the in-guide group by [direction] and repaints.
     *
     * The rows are rebuilt from the new group's lineup (the identity pass
     * first, so the overlay never blanks) and the EPG read re-runs for it; the
     * registry's active group is what UP/DOWN and a typed channel number walk,
     * so the overlay and the zapping stay on the same list. A press with
     * nowhere to go is a no-op (see the registry's offsetGroup).
     */
    private fun switchChannelGuideGroup(direction: Int) {
        if (!isGuideShowing) return
        if (!LiveChannelZapRegistry.offsetGroup(direction)) return
        // The zap anchor is an INDEX INTO THE LINEUP, and this press just swapped
        // that lineup for another group's, so the index no longer names the
        // channel being watched. Left alone it did two things: the guide named
        // whatever now sits at that position as "playing" (and the next zap
        // moved relative to the wrong anchor), and when the new group is shorter
        // than the old index, currentZapChannel() answered null forever - it
        // only resolves while the index is `< 0` - so CH+/CH- was dead for the
        // rest of the player session without a word. Invalidating it makes the
        // next zap re-resolve from parentId/currentUrl inside the group now
        // active, or find nothing and stay put.
        zapChannelIndex = -1
        channelGuideTitle?.text = channelGuideTitleText()
        publishChannelGuideRows(programs = emptyMap(), loading = true)
        focusChannelGuideCurrent()
        loadChannelGuidePrograms()
    }

    /** Closes the guide and gives the D-pad back where it came from. */
    private fun dismissChannelGuide() {
        if (!isGuideShowing) return
        isGuideShowing = false
        liveChannelPrefetch.release()
        channelGuideJob?.cancel()
        channelGuideJob = null
        channelGuideWatchJob?.cancel()
        channelGuideWatchJob = null
        KbViewMotion.slideFadeOut(channelGuideContainer)
        if (!isPickerShowing && !showSettingsPanel) KbViewMotion.fadeOut(scrim)
        restoreControlsFocus()
    }

    /**
     * Tunes to a row and closes the guide. Re-picking the channel already
     * playing is not a channel change: a "zap" to itself would only rebuild
     * the player and flash the banner, so that press just returns to the
     * picture.
     */
    private fun tuneToChannelFromGuide(index: Int) {
        val channel = LiveChannelZapRegistry.channelAt(index) ?: return
        val alreadyPlaying = channel.channelId == currentZapChannel()?.channelId
        dismissChannelGuide()
        if (alreadyPlaying) {
            showControls()
            return
        }
        // A pick IS a channel change, so the overlay comes down with the guide:
        // the viewer wants the picture, and the zap banner names what they
        // landed on.
        hideControls()
        tuneToChannel(index, channel)
    }

    /**
     * Paints the rows. [loading] is the identity-only first pass: the NOW line
     * reads "…" rather than "No guide data", which is the difference between
     * "not read yet" and "this channel has no guide at all".
     */
    private fun publishChannelGuideRows(
        programs: Map<String, ChannelNowNext>,
        loading: Boolean
    ) {
        val list = channelGuideList ?: return
        val now = System.currentTimeMillis()
        val currentId = currentZapChannel()?.channelId
        // One row object per VISIBLE row, not per channel: the source answers a
        // position when RecyclerView binds it. Materialising the whole lineup
        // here was the in-player guide's ANR - opening it on a long IPTV group
        // built tens of thousands of rows on the main thread before the overlay
        // could take input (ANDROID-Z), and the EPG pass did it again.
        val rows: List<ChannelGuideRow> = ChannelGuideRowSource(
            count = LiveChannelZapRegistry.size()
        ) { index ->
            val channel = LiveChannelZapRegistry.channelAt(index)
                ?: return@ChannelGuideRowSource ChannelGuideRow(
                    channelNumber = null,
                    name = "",
                    nowTitle = null,
                    nowTime = null,
                    nowProgressPermille = null,
                    nextTitle = null,
                    isCurrent = false,
                    onClick = {}
                )
            val entry = guideChannelIdFor(channel)
                ?.let { programs[epgProgramChannelKey(it)] }
            // m7: the programme inside a surviving channel is unvetted text a
            // playlist author writes. On a kid-locked-live-TV profile a title
            // the signal list rejects reads as "no guide data" rather than as
            // the programme's own name; the timing bar stays, because when a
            // block runs is not content.
            val nowProgram = entry?.now?.takeIf { program ->
                !KidsLiveTvFilter.programHidden(program.title, program.description)
            }
            val nextProgram = entry?.next?.takeIf { program ->
                !KidsLiveTvFilter.programHidden(program.title, program.description)
            }
            ChannelGuideRow(
                channelNumber = channel.chno?.trim()?.takeIf { it.isNotEmpty() },
                name = channel.name,
                nowTitle = when {
                    nowProgram != null -> nowProgram.title
                    loading -> "…"
                    else -> null
                },
                nowTime = nowProgram?.let {
                    zapTime(it.startUtcMillis) +
                        " – " + zapTime(it.endUtcMillis)
                },
                nowProgressPermille = nowProgram?.let { guideProgressPermille(it, now) },
                nextTitle = nextProgram?.let {
                    "Next  " + zapTime(it.startUtcMillis) + "  " + it.title
                },
                isCurrent = channel.channelId == currentId,
                onClick = { tuneToChannelFromGuide(index) }
            )
        }
        val adapter = list.adapter as? ChannelGuideAdapter
        if (adapter == null) {
            list.adapter = ChannelGuideAdapter().also {
                // Focus the viewer rests on warms that channel's playlist before
                // the press lands (see LiveChannelPrefetch).
                it.onRowFocused = { index ->
                    liveChannelPrefetch.onChannelFocused(LiveChannelZapRegistry.channelAt(index))
                }
                it.submit(rows)
            }
        } else {
            // The EPG pass repaints the rows the viewer is already browsing. A
            // full rebind can hand the D-pad back to the list, so the row that
            // had focus takes it back - otherwise the guide would appear to
            // jump to the top the instant the guide data arrived.
            val focused = list.getFocusedChild()?.let(list::getChildAdapterPosition)
            adapter.submit(rows)
            if (focused != null && focused != RecyclerView.NO_POSITION) {
                list.post {
                    if (!isGuideShowing) return@post
                    list.findViewHolderForAdapterPosition(focused)?.itemView?.requestFocus()
                }
            }
        }
    }

    /** Elapsed fraction of a program across its own air window, in 0..1000. */
    private fun guideProgressPermille(program: EpgProgramRow, nowMillis: Long): Int {
        val span = (program.endUtcMillis - program.startUtcMillis).coerceAtLeast(1L)
        val elapsed = (nowMillis - program.startUtcMillis).coerceIn(0L, span)
        return ((elapsed * 1000L) / span).toInt()
    }

    /**
     * Reads NOW/NEXT for the whole lineup: one query per source per batch (see
     * [planGuideQueries]), bucketed by channel (see [nowNextByChannel]). A read
     * that fails leaves the identity paint standing rather than blanking the
     * panel.
     *
     * The Lite projection on purpose: these rows carry only a title and an air
     * window, so pulling every synopsis of every channel in the lineup would be
     * the largest read the app makes for text it never draws.
     */
    private fun loadChannelGuidePrograms() {
        channelGuideJob?.cancel()
        channelGuideJob = scope?.launch {
            val channels = lineupChannels()
            // Fill in any guide match the published lineup lacks before
            // planning. An entry the guide screen had not matched yet (it had
            // no imported guide to match against) carries a null epgChannelId,
            // and [planGuideQueries] skips those channels outright - which is
            // why the overlay read nothing and stayed blank.
            resolveMissingGuideMatches(channels)
            // The resolved ids are handed over as a lookup rather than copied
            // onto every entry: that copy was one more full-lineup allocation
            // per pass on the guide's open path.
            val queries = planGuideQueries(
                channels,
                guideIdOf = { channel -> guideChannelIdFor(channel) }
            )
            if (queries.isEmpty()) {
                // Nothing to read for any row (no guide configured, or nothing
                // matched yet): end the identity "…" pass rather than leaving
                // every row looking like it is still loading for good. A guide
                // import that lands later re-runs this (see the watcher).
                if (isGuideShowing) publishChannelGuideRows(programs = emptyMap(), loading = false)
                return@launch
            }
            val now = System.currentTimeMillis()
            val programs = HashMap<String, ChannelNowNext>()
            for (query in queries) {
                val rows = runCatchingCancellable {
                    withContext(Dispatchers.IO) {
                        IptvDatabase.getInstance(applicationContext).iptvDao()
                            .getProgramsForChannelsInWindowLite(
                                sourceUrl = query.sourceUrl,
                                channelIds = query.channelIds,
                                windowStart = now,
                                windowEnd = now + ZAP_EPG_LOOKAHEAD_MS,
                                perChannelLimit = CHANNEL_GUIDE_ROWS_PER_CHANNEL
                            )
                    }
                }.getOrElse { t ->
                    Log.w(TAG, "CHANNEL GUIDE EPG read failed: ${t.message}")
                    emptyList()
                }
                programs.putAll(nowNextByChannel(rows, now))
            }
            // A slow read must not repaint a guide the viewer already closed.
            if (isGuideShowing) publishChannelGuideRows(programs, loading = false)
        }
    }

    /**
     * The guide channel id to query for [channel]: the match the guide screen
     * resolved, or one this player resolved itself (see
     * [resolveMissingGuideMatches]).
     */
    private fun guideChannelIdFor(channel: LiveChannelZapRegistry.ZapChannel): String? =
        channel.epgChannelId?.takeIf { it.isNotBlank() }
            ?: guideResolvedChannelIds[channel.channelId]

    /**
     * Resolves a guide channel id for every lineup entry the published lineup
     * left unmatched, using the same matching the guide screen does (see
     * [IptvRepository.resolveGuideChannelIds]).
     *
     * The whole set is re-attempted whenever a successful import bumps
     * [GuideRevision] - the imported guide is exactly what matching was
     * missing - and each channel is attempted once per revision, so an overlay
     * left open does not re-run the match for the same entries every tick.
     */
    private suspend fun resolveMissingGuideMatches(
        channels: List<LiveChannelZapRegistry.ZapChannel>
    ) {
        val revision = GuideRevision.total()
        if (revision != guideResolveRevision) {
            guideResolvedChannelIds.clear()
            guideMatchAttempted.clear()
            // Every now/next snapshot was read before this import, so an empty
            // one is exactly what the imported guide was missing: drop them
            // rather than serve "No guide data" from the cache for a TTL after
            // the data has landed.
            zapEpgCache.clear()
            guideResolveRevision = revision
        }
        val pending = channels.filter { channel ->
            needsGuideMatch(channel) && channel.channelId !in guideMatchAttempted
        }
        if (pending.isEmpty()) return

        // One pass per configured source, in the guide screen's own order
        // (primary first): a channel may be matched in ANY of them, and a
        // channel matched in a secondary guide is exactly the one whose
        // programs a primary-only read cannot find. The FIRST source that
        // answers wins, which is the precedence the lineup matcher uses.
        val sources = pending.flatMap { channel ->
            guideSourcesOf(channel)
        }.distinct()
        if (sources.isEmpty()) return

        pending.forEach { guideMatchAttempted.add(it.channelId) }

        val resolved = HashMap<String, String>()
        var resolveFailed = false
        for (source in sources) {
            val queries = pending
                .filter { channel -> channel.channelId !in resolved }
                .mapNotNull { channel -> guideMatchQueryForSource(channel, source) }
            if (queries.isEmpty()) continue
            val batch = runCatchingCancellable {
                withContext(Dispatchers.IO) {
                    iptvRepository.resolveGuideChannelIds(queries)
                }
            }.getOrElse { t ->
                Log.w(TAG, "CHANNEL GUIDE match resolve failed: ${t.message}")
                resolveFailed = true
                emptyMap()
            }
            resolved.putAll(batch)
        }

        if (resolveFailed) {
            // Something never ran: un-mark the entries so the next pass (a
            // revision bump, or reopening the guide) retries them instead of
            // leaving them permanently unresolved for the session.
            pending.forEach { guideMatchAttempted.remove(it.channelId) }
        }
        guideResolvedChannelIds.putAll(resolved)
    }

    /**
     * Re-reads the guide whenever the imported guide changes while the overlay
     * is open, so a viewer who opened it before an in-flight import finished
     * gets the rows filled in without closing and reopening the guide.
     */
    private fun startChannelGuideWatcher() {
        channelGuideWatchJob?.cancel()
        channelGuideWatchJob = scope?.launch {
            var lastRevision = GuideRevision.total()
            while (isGuideShowing) {
                delay(CHANNEL_GUIDE_WATCH_MS)
                if (!isGuideShowing) break
                val revision = GuideRevision.total()
                if (revision != lastRevision) {
                    lastRevision = revision
                    loadChannelGuidePrograms()
                }
            }
        }
    }

    /**
     * Parks focus on the channel playing now, scrolled into view, so the guide
     * opens where the viewer already is instead of at the top of the lineup -
     * on a long playlist that is the difference between "browse" and "scroll
     * hunting for your own channel". Falls back to the list itself (which hands
     * focus to its first row) when that row has not been laid out yet.
     */
    private fun focusChannelGuideCurrent() {
        val list = channelGuideList ?: return
        // The lineup's own index, not a scan of it: this runs on the open path,
        // which is where the ANR was.
        val index = currentZapChannel()?.channelId
            ?.let(LiveChannelZapRegistry::indexOfChannel)
            ?: -1
        list.post {
            if (!isGuideShowing) return@post
            if (index >= 0) list.scrollToPosition(index)
            // One more frame: scrollToPosition only lays the target out on the
            // next pass, so the holder for [index] does not exist yet.
            list.post {
                if (!isGuideShowing) return@post
                val holder = if (index >= 0) list.findViewHolderForAdapterPosition(index) else null
                (holder?.itemView ?: list).requestFocus()
            }
        }
    }

    private fun dismissAllPanels() {
        dismissChannelGuide()
        dismissPicker()
        dismissSettingsPanel()
        becauseYouWatchedPanel.visibility = View.GONE
        exitCreditsMode()
    }

    // --- Retry ---
    private fun scheduleRetry() {
        // A source that never opened gets a shorter ladder than one that
        // played and then broke, and when even that is spent the next ranked
        // source is tried rather than a card. See
        // [MAX_UNOPENABLE_RETRY_ATTEMPTS] and [PlaybackRecoveryRules.isUnopenableSource].
        val unopenable = lastPlaybackError?.let { PlaybackRecoveryRules.isUnopenableSource(it) } == true
        val attemptLimit = if (unopenable) MAX_UNOPENABLE_RETRY_ATTEMPTS else MAX_RETRY_ATTEMPTS
        if (retryAttempt >= attemptLimit) {
            if (unopenable &&
                tryNextSource(
                    statusText = "That source won't open. Trying the next one…",
                    // Automatic recovery: the merged walk, across add-ons.
                    tab = null
                )
            ) {
                return
            }
            // A live channel never ends, so for one the spent ladder is not an
            // outcome to report: it is a provider that is down right now, on a
            // screen most often left running overnight. Roll back to the
            // ladder's longest backoff and keep reconnecting until the channel
            // answers again, rather than parking on a card nobody is awake to
            // press. VOD keeps the card it has always shown. See
            // [retryLoopRung].
            val loopRung = retryLoopRung(
                live = isLiveChannel,
                rungCount = RETRY_BACKOFF_MS.size
            )
            if (loopRung < 0) {
                retryExhausted = true
                updateUIError()
                return
            }
            Log.w(
                "PLAYER_RETRY",
                "Live channel spent the ladder (${retryAttempt} attempts) \u2014 " +
                    "restarting it at the longest backoff instead of giving up"
            )
            liveRetryLooping = true
            retryAttempt = loopRung
        }
        reconnectingContainer.visibility = View.VISIBLE
        hideBufferingSpinner()
        reconnectingText.text = if (liveRetryLooping) {
            // The counter has wrapped: "Reconnecting... (7/6)" reads as a bug.
            "Reconnecting\u2026"
        } else {
            "Reconnecting\u2026 (${retryAttempt + 1}/$attemptLimit)"
        }

        if (retryAttempt >= RAW_EXTRACTOR_PROBE_ATTEMPT) {
            Log.i("PLAYER_RETRY", "Attempt ${retryAttempt + 1}: probing with raw extractor")
        }

        // Rebuild cap (see RebuildBudget.kt). A cause that has already had its
        // rebuilds is not a cause another rebuild can fix: the ladder past that
        // point asks the same component the same question. Checked here rather
        // than at the top so a source that never OPENED still advances to the
        // next ranked source above, which is a different kind of recovery and
        // not a rebuild at all. Live channels are exempt: they have no backup
        // engine to hand to and their reconnect loop is deliberate (see
        // retryLoopRung).
        val causeKey = PlaybackRecoveryRules.failureCauseKey(lastPlaybackError)
        if (!isLiveChannel && causeKey != null && !rebuildBudget.allows(causeKey)) {
            val spent = rebuildBudget.spentFor(causeKey)
            Log.w(
                "PLAYER_RETRY",
                "Rebuild budget spent for failure cause '$causeKey' ($spent rebuilds, " +
                    "cap $MAX_REBUILDS_PER_CAUSE) — ending the ladder instead of " +
                    "rebuilding the same component again"
            )
            PlaybackEngineTrace.note(
                PlaybackEngineTrace.describe(
                    cause = "rebuild budget spent",
                    detail = "$causeKey after $spent rebuilds — ladder stopped at the cap"
                )
            )
            val decoderCause = lastPlaybackError
                ?.let { PlaybackRecoveryRules.isDecoderError(it.errorCode) } == true
            // The backup engine is the one thing left that can play the file.
            // Refused (live TV, DRM, an "ExoPlayer only" engine setting, no
            // libmpv), the card is the honest outcome.
            if (!handOffToMpv(
                    if (decoderCause) {
                        MpvPlayerActivity.FALLBACK_REASON_DECODER
                    } else {
                        MpvPlayerActivity.FALLBACK_REASON_ERROR
                    }
                )
            ) {
                retryExhausted = true
                updateUIError()
            }
            return
        }

        // One pending rebuild at a time: a second error while a retry is
        // already queued must not stack a second recreatePlayer().
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(
            retryRunnable,
            RETRY_BACKOFF_MS.getOrElse(retryAttempt) { RETRY_BACKOFF_MS.last() }
        )
    }

    // --- Playback Ended ---

    /**
     * Whether this session ever actually played something: a painted frame, or
     * a playhead past the start.
     *
     * The first-frame latch is cleared on every source load, so the position
     * carries a session that switched sources mid-file; either signal means a
     * viewer could have watched what was on screen. A source that never came
     * up has neither, and must not be treated as a finished episode.
     */
    private fun sessionHasPlayed(): Boolean =
        firstFrameRendered ||
            runCatching { exoPlayer?.currentPosition ?: 0L }.getOrDefault(0L) > 0L

    private fun onPlaybackEnded() {
        // Nothing was watched, so nothing is completed and no end-of-episode
        // card is raised. A source that never produced a frame (or moved the
        // playhead) can still report ENDED - an empty timeline, a container
        // the extractor reads as zero length, or the stall fallback reading a
        // frozen-at-zero position as a tail. Filing that as a finished episode
        // is how a run of failed add-on sources marked itself watched and
        // auto-advanced while the error card was still on screen.
        if (!sessionHasPlayed()) {
            Log.w(TAG, "ignoring end-of-playback: this session never played")
            return
        }
        // Sticky for this session: the episode reached its end, whatever
        // happens to the playhead afterwards (a rewind, a scrub, a transport
        // key) - see the field. Set before any of the work below, so a path
        // that exits early still cannot lose the fact.
        playbackEndReached = true
        scrobbleSimkl("stop", progressOverride = 100.0)
        // Saved SYNCHRONOUSLY rather than from `scope`. saveProgress reads the
        // position while the player is still alive and then does its own
        // NonCancellable database write, whereas a scope-bound call was a
        // coroutine that onStop's `scope?.cancel()` could kill before it ever
        // ran: leaving the player (BACK, or a handoff that finishes the
        // activity) right after the credits left the episode with no watch
        // marker and the resume bar exactly where the viewer had been.
        saveProgress(reason = "ended", forceCompleted = true)
        routeAfterCompletedEnd()
    }

    /**
     * Invalidates the cached YouTube watch config when a TRAILER session's
     * playback is refused with a googlevideo 403 ([isYouTubeIdentityBlocked]).
     *
     * The 2026-10-18 hero-trailer log (Onn 4K Plus, `gc0t8AfMP6k`) is the shape
     * this exists for: the stream 403'd mid-playback, the chunked source cycled
     * every client UA / range / ratebypass mode inside the player, and the one
     * retry re-resolved and 403'd again on its FIRST request. Every mode being
     * refused is not a mode mismatch - it is the client identity (the cached
     * watch config's visitor data) being refused, and re-signing with it just
     * produces another dead URL.
     *
     * The config is what the next resolve starts from, so invalidating here is
     * what gives that resolve a fresh visitor. Only a trailer session and only
     * this failure shape: a timeout, a 5xx or another host's own 403 keeps the
     * cached identity and the ordinary ladder.
     *
     * Returns true when it handled the failure, and the caller then returns
     * WITHOUT touching the recovery ladder. A trailer is ambient content, so
     * this is a CLEAN EXIT rather than a recovery: the activity finishes and the
     * viewer is back on the detail screen with the trailer row still tappable.
     * None of the ladder's outcomes would be honest here - the retry rebuilds a
     * player whose URL was just refused, the source walk hunts a different
     * addon for a YouTube trailer that has only one source, and the error card
     * says the main content failed when only the 30-second clip did.
     */
    private fun refreshIdentityOnTrailerRefusal(error: PlaybackException): Boolean {
        if (trailerVideoId == null || !error.isYouTubeIdentityBlocked()) return false
        val videoId = trailerVideoId ?: return false
        val player = exoPlayer
        // Read the death point BEFORE anything rebuilds: the resume has to pick
        // up here, so the interruption reads as a rebuffer rather than a
        // restart. Same rule the hero's error handler follows.
        val positionMs = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
        val durationMs = runCatching { player?.duration ?: C.TIME_UNSET }
            .getOrDefault(C.TIME_UNSET)
        val deadClient = TrailerPlayerLauncher.signingClient(videoId)
        val kind = PoTokenTrace.resolveKind()

        Log.w(
            "PLAYER_RETRY",
            "Trailer stream refused by YouTube (403 on googlevideo) at " +
                "${positionMs}ms of ${if (durationMs > 0L) "${durationMs}ms" else "?"}; " +
                "client=${deadClient ?: "unknown"} kind=${kind ?: "unknown"}"
        )
        // A fresh visitor does no harm to the replacement resolve, and is what
        // today's path did anyway.
        InnerTubeExtractor.invalidateConfig()

        if (trailerRefusalResumed) {
            // The replacement died on the same refusal. That is today's story,
            // and its only ending: the session ends and the row stays tappable.
            TrailerPlayerLauncher.markIdentityBlocked(videoId)
            PoTokenTrace.recordTrailerDeath(
                videoId = videoId,
                client = deadClient,
                kind = kind,
                positionMs = positionMs,
                durationMs = durationMs,
                resume = "client ${deadClient ?: "unknown"} died"
            )
            Log.w(
                "PLAYER_RETRY",
                "Trailer died again after the client-exclusion resume; closing the " +
                    "trailer session"
            )
            finish()
            return true
        }

        trailerRefusalResumed = true
        PoTokenTrace.recordTrailerDeath(
            videoId = videoId,
            client = deadClient,
            kind = kind,
            positionMs = positionMs,
            durationMs = durationMs,
            resume = "none"
        )
        // The 2026-10-22 case is a SOURCE problem, not an identity one: the
        // DIAG ladder showed this URL class serving bytes=0-0, 0-1M and
        // 0-<pos> with 206 while every offset read and every oversized from-0
        // window was refused. So one resolve is made excluding the client that
        // signed it, and the replacement is sought where the trailer died.
        lifecycleScope.launch {
            val replacement = TrailerPlayerLauncher
                .resolvePlayableUrl(videoId, excludeClient = deadClient)
                .getOrNull()
            if (isFinishing || isDestroyed) return@launch
            if (replacement == null) {
                Log.w(
                    "PLAYER_RETRY",
                    "No source from another client for this trailer; closing the " +
                        "trailer session"
                )
                PoTokenTrace.recordTrailerDeath(
                    videoId = videoId,
                    client = deadClient,
                    kind = kind,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    resume = "none: no other client"
                )
                finish()
                return@launch
            }
            PoTokenTrace.recordTrailerDeath(
                videoId = videoId,
                client = deadClient,
                kind = kind,
                positionMs = positionMs,
                durationMs = durationMs,
                resume = "client ${TrailerPlayerLauncher.signingClient(videoId) ?: "unknown"} ok"
            )
            Log.w(
                "PLAYER_RETRY",
                "Resuming the trailer at ${positionMs}ms on a replacement source " +
                    "(client=${TrailerPlayerLauncher.signingClient(videoId) ?: "unknown"})"
            )
            applyTrailerReplacement(replacement, positionMs)
        }
        return true
    }

    /**
     * Hands the running session a replacement trailer source at [positionMs].
     *
     * The same pieces [switchToSource] swaps before a rebuild - the link, its
     * separate audio track, and the headers its host expects - plus the carried
     * playhead, so the rebuild lands where the trailer died instead of at 0. It
     * goes through [recreatePlayer] because a trailer is a single small file
     * and a fresh player is the path the box already trusts for a source swap;
     * the splash is not shown for it.
     */
    private fun applyTrailerReplacement(source: PlayableSource, positionMs: Long) {
        currentUrl = when (source) {
            is PlayableSource.Muxed -> source.url
            is PlayableSource.Adaptive -> source.videoUrl
        }
        currentAudioUrl = (source as? PlayableSource.Adaptive)?.audioUrl
        // [PlayableSource] itself has no userAgent - each of its two shapes
        // carries its own, and the replacement has to keep the one its URL was
        // signed for or googlevideo refuses it.
        val trailerUserAgent = when (source) {
            is PlayableSource.Muxed -> source.userAgent
            is PlayableSource.Adaptive -> source.userAgent
        }
        streamHeaders = trailerUserAgent
            ?.takeIf { it.isNotBlank() }
            ?.let { ua -> mapOf("User-Agent" to ua) }
            ?: emptyMap()
        carryPositionMs = positionMs.coerceAtLeast(0L)
        recreatePlayer(settleMs = SOURCE_SWITCH_SETTLE_MS)
    }

    /**
     * Files the completion when a source error lands in the tail of a file the
     * viewer has effectively finished.
     *
     * The field report (Onn 4K Plus, 0.6.110): pause at 77.43%, resume, watch
     * on to 99.28%, and the Matroska tail throws `No valid varint length mask
     * found`. The episode was never marked watched, and the recovery rebuild
     * restarted playback from the 77.43% resume point (resume rows are written
     * on pause/exit, not continuously) - so the show came back to "9 min left"
     * forever. The `byw shown filed=false` assertion the Oct 13 completion fix
     * planted is what caught it: that fix covers the natural end, and a source
     * error at the tail bypassed completion filing entirely.
     *
     * Nothing new is written here. The completion goes through the ordinary
     * [saveProgress] (completed row, `completionFiled`, the tracker push, and
     * [PlaybackHistoryWriter]'s delete-and-tombstone of the superseded resume
     * rows - which is what leaves a later rebuild nothing stale to resurrect),
     * and the recovery ladder is never entered: no [scheduleRetry], no rebuild,
     * no source switch, no MPV handoff, no error card.
     *
     * The playhead and length are read SYNCHRONOUSLY, in the error handler,
     * while the player is still alive - the error-time position (99.28%), never
     * the stored resume point. The guards are [saveProgress]'s own: a live
     * channel never completes, and a session with no title/episode identity has
     * nothing to file a completion against.
     *
     * Returns true when the completion was filed, so `onPlayerError` returns
     * without touching the ladder.
     */
    private fun fileErrorTailCompletion(): Boolean {
        if (isLiveChannel || parentId.isBlank() || historyId.isBlank()) return false
        val player = exoPlayer ?: return false
        val pos = runCatching { player.currentPosition }.getOrDefault(0L).coerceAtLeast(0L)
        val rawDur = runCatching { player.duration }.getOrDefault(C.TIME_UNSET)
        val dur = if (rawDur == C.TIME_UNSET || rawDur <= 0L) null else rawDur
        // The same ratio the save below judges with, not a threshold of its
        // own: an error the save would have called "finished" is finished here.
        if (!errorAtCompletionThreshold(pos, dur ?: 0L, COMPLETION_THRESHOLD_RATIO)) return false
        Log.w(
            "PLAYER_RETRY",
            "playback error at the tail ($pos of ${dur}ms) \u2014 filing the completion " +
                "instead of rebuilding"
        )
        PlaybackEngineTrace.note(
            PlaybackEngineTrace.describe(
                cause = "source error inside the completion threshold",
                detail = "at $pos of ${dur}ms \u2014 completion filed, no rebuild"
            )
        )
        saveProgress(reason = "error-tail-completion", forceCompleted = true)
        routeAfterCompletedEnd()
        return true
    }

    /**
     * The end-of-episode state, reached after the completion has been written:
     * the sleep timer's own ending, the up-next countdown let go, the panel
     * raised.
     *
     * Shared by the natural end ([onPlaybackEnded]) and a source error that
     * landed in the tail ([fileErrorTailCompletion]) so the two cannot drift -
     * both arrive here with the completion already filed. A panel that is
     * already up (the credits trigger fired before the error, which is what the
     * field log shows) simply stays: [showEndPanels] raises it once per
     * session.
     */
    private fun routeAfterCompletedEnd() {
        // A sleep timer armed to stop at the end of this episode is honored
        // here, where the episode really is over: no card, no auto-advance,
        // just out. The completion write already made is what the history
        // keeps.
        if (sleepTimerBlocksAutoAdvance(SleepTimer.state.value)) {
            exitForSleepTimer(savePartialProgress = false)
            return
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
        // A sleep timer armed for the end of this episode suppresses the card
        // entirely: offering PLAY NEXT while the timer is about to stop the
        // session is a promise this player cannot keep.
        if (sleepTimerBlocksAutoAdvance(SleepTimer.state.value)) return
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
                    AutoSkipRules.isSkippableSegment(it, dur)
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
            val arithmeticTarget = nextEpisodeTarget()
            val target = resolveRandomChainTarget(
                this@NativePlayerActivity, randomEpisodes, arithmeticTarget,
                parentId, parentType, season, episode
            )?.let {
                airedNextEpisodeTarget(
                    this@NativePlayerActivity, it, resolveParentTmdbId(), parentId,
                    sessionSeason = season,
                    // A listing's own pick is never the invented jump (see
                    // [airedNextEpisodeTarget]).
                    fromListing = it != arithmeticTarget
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

            // The next episode's subtitle, fetched while the viewer is still on
            // this one: the search and the download happen during the credits,
            // so that episode's auto-fetch starts with the file already on disk
            // instead of finding nothing and rebuilding the player for it.
            if (target != null) prefetchNextEpisodeSubtitle(target.first, target.second)
        }
    }

    /**
     * The native player's XML panels carry fixed colors, so the AMOLED /
     * pure-black theme toggles never reached them. Re-resolve their background
     * here so the end-of-episode popups match the rest of the app.
     */
    private fun applyPlayerPanelTheme() {
        val surfaceColor = playerPanelSurfaceColor(this)
        listOf(becauseYouWatchedPanel, nextUpPanel).forEach { panel ->
            panel.background = roundedDrawable(playerPanelRaisedColor(this), 16f)
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

    /** Rounded rectangle standing in for the XML shape drawables. */
    private fun roundedDrawable(color: Int, radiusDp: Float): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radiusDp * resources.displayMetrics.density
        }

    /**
     * The chrome around the video — the control-bar buttons, RETRY, the option
     * pills, the picker rows and the panels behind them — is plain XML with
     * fixed @color/kb_surface / @color/kb_surface_raised fills and
     * @color/kb_accent strokes, so the AMOLED / pure-black toggles and the
     * global accent never reached it: a pure-black theme still painted #141A24
     * buttons, and a chosen accent still ringed the focused button in brass.
     * The pass itself is shared with the other two engines ([retintPlayerChrome])
     * and matches views by the tones their own drawables carry rather than by
     * resource id, so every button, panel, selector and pill is covered without
     * a hand-kept list. Pills already restyled by [applyPillState] are skipped
     * (their fill is no longer an XML color), as are the two end-of-episode
     * panels handled by [applyPlayerPanelTheme].
     */
    private fun applyPlayerChromeTheme() {
        if (!chromeThemeMoved(this)) return
        refillPlayerChrome(findViewById(android.R.id.content))
    }

    /**
     * [applyPlayerChromeTheme]'s walk. Also called for picker rows as they
     * attach: those are inflated on demand, long after the activity's own
     * view tree was themed.
     */
    private fun refillPlayerChrome(root: View) = retintPlayerChrome(root, this)

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
                val episodes = runCatchingCancellable {
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

    /**
     * The FILE episode the session is on now: the trailing number of
     * [episodeStreamId], which the id invariant keeps in file numbering (see
     * the field comment above).
     *
     * A session with no stream id - a legacy intent, a hand-built id - falls
     * back to the TMDB episode mapped through the detected scheme, which is
     * exact for every kind but a split episode's second file
     * (FILES_PER_EPISODE), where the id is the only thing that knows both
     * halves are one TMDB episode.
     */
    private fun currentFileEpisode(): Int? {
        val e = episode ?: return null
        episodeStreamId
            ?.substringAfterLast(':')
            ?.trim()
            ?.toIntOrNull()
            ?.takeIf { it >= 1 }
            ?.let { return it }
        return bingeScheme.fileForTmdbEpisode(e)
    }

    /**
     * Reads the show's detected scheme, so a session that has seen this show
     * before starts on the right file instead of re-learning the mapping from
     * whichever file it happens to open.
     *
     * The key is the root the session's own episode id names, not the route's
     * parent id (see [PlaybackHistoryIds.schemeStoreKey]): the season listing
     * builds every episode id, and reads the scheme back, under THAT id, so a
     * session that keyed the store by the flavor its route was opened with
     * (`tmdb:<n>` from a kids rail) stored a detection the listing could never
     * see. The tmdb id is the fallback key half, and it is not available this
     * early ([resolveParentTmdbId] suspends), so a session with no episode id
     * and a tmdb-only parent gets its key the first time [maybeDetectScheme]
     * resolves that id.
     */
    private fun initBingeScheme() {
        if (isLiveChannel) return
        schemeStoreKey = PlaybackHistoryIds.schemeStoreKey(
            parentId,
            episodeStreamId,
            resolvedParentTmdbId
        )
        bingeScheme = EpisodeSchemeStore.get(this, schemeStoreKey)
    }

    /**
     * The TMDB runtime of the episode playing now, in milliseconds, or null
     * when nothing trustworthy says.
     *
     * Three sources, in the order of how directly they describe this episode:
     * the runtime the launch carried (the Detail screen already had it), TMDB's
     * own `runtime` for this season/episode, and finally the show's average
     * `episode_run_time` - the last resort for a TMDB row that lists no runtime
     * per episode, without which the detector could never fire for such a show.
     *
     * Resolved at most once per session ([currentEpisodeRuntimePrefetched]):
     * this is reached from the position tick, and a runtime that is unknown now
     * is unknown a second later too.
     */
    private suspend fun currentEpisodeTmdbRuntimeMs(): Long? {
        launchRuntimeMinutes?.let { return it * 60_000L }
        val s = season ?: return null
        val e = episode ?: return null
        if (currentEpisodeRuntimePrefetched) {
            return currentEpisodeRuntimeMinutes?.takeIf { it > 0 }?.let { it * 60_000L }
        }
        currentEpisodeRuntimePrefetched = true
        val minutes = withContext(Dispatchers.IO) {
            val repo = TmdbRepository.getInstance(this@NativePlayerActivity)
            val tmdbId = resolveParentTmdbId()
            val fromSeason = tmdbId?.let { id ->
                runCatchingCancellable {
                    repo.getSeasonEpisodes(id, s, parentId)
                }.getOrNull()
                    ?.firstOrNull { it.episodeNumber == e }
                    ?.runtimeMinutes
                    ?.takeIf { it > 0 }
            }
            fromSeason ?: runCatchingCancellable {
                repo.fetchEnrichedMetaCached(parentId, parentType, full = false)
                    ?.episodeRunTime
                    ?.firstOrNull { it > 0 }
            }.getOrNull()
        }
        currentEpisodeRuntimeMinutes = minutes
        return minutes?.takeIf { it > 0 }?.let { it * 60_000L }
    }

    /**
     * The file name of the source playing now, for the scheme detector: the
     * launch's own row for [currentUrl], read through
     * [Stream.detectionFileName]. Null when the row named nothing (a bare
     * `stream_url`), which the detector reads as "no filename signal".
     */
    private fun detectionFileName(): String? =
        sources.firstOrNull { it.url == currentUrl }?.detectionFileName()

    /**
     * Reads the file that is playing against the episode TMDB says it is, and
     * remembers what that says about the show (see [EpisodeScheme.detect]).
     *
     * Runs from the position tick, so it is a no-op until a signal exists, and
     * at most once per file ([schemeDetectedForFileId]): the answer cannot
     * change while the same file plays, and the TMDB read behind it must not
     * repeat every second. [fileEpisodeForHandoff] calls it too, as the
     * backstop for a file watched without the tick ever seeing a duration.
     *
     * Two signals, and only one of them needs a duration. A name that says the
     * file holds `E01E02` is a statement about the file, so it is read as soon
     * as the session has one - and it skips the TMDB runtime lookup entirely,
     * since the name outranks it. Without such a name this is the old wait: a
     * duration and a runtime both have to exist before anything is committed,
     * so a tick that knows neither leaves the file undetected rather than
     * pinning it to 1:1.
     */
    private fun maybeDetectScheme() {
        if (isLiveChannel) return
        val sid = episodeStreamId ?: return
        if (schemeDetectedForFileId == sid) return
        val player = exoPlayer ?: return
        val fileMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
        val fileName = detectionFileName()
        val fromName = bundledEpisodeCount(fileName)
        if (fileMs <= 0L && fromName == null) return
        val sessionScope = scope ?: return
        sessionScope.launch {
            if (schemeStoreKey == null) {
                schemeStoreKey = PlaybackHistoryIds.schemeStoreKey(
                    parentId,
                    episodeStreamId,
                    resolveParentTmdbId()
                )
                bingeScheme = EpisodeSchemeStore.get(this@NativePlayerActivity, schemeStoreKey)
            }
            val tmdbMs = if (fromName != null) {
                0L
            } else {
                currentEpisodeTmdbRuntimeMs() ?: return@launch
            }
            // The tick that started this coroutine may have been for a file the
            // session has already left, so the guard is re-read inside.
            if (schemeDetectedForFileId == sid) return@launch
            val detected = EpisodeScheme.detect(fileMs, tmdbMs, fileName)
            bingeScheme = detected
            EpisodeSchemeStore.put(this@NativePlayerActivity, schemeStoreKey, detected)
            schemeDetectedForFileId = sid
            com.kennyb1201.kbstream.data.reporting.PlaybackSessionTrace.note(
                "scheme ${detected.encode() ?: "1:1"} for $sid " +
                    "(file=${fileMs}ms tmdb=${tmdbMs}ms " +
                    "name=${fileName?.take(80) ?: "-"})"
            )
        }
    }

    /**
     * The TMDB episodes the file that just finished covered.
     *
     * Answered from the FILE cursor ([currentFileEpisode]) through
     * [EpisodeScheme.episodesOfFileClamped], not from the session's TMDB label:
     * an episode tap / resume / random pick keeps the tapped TMDB number while
     * the stream id is file-numbered, so the two disagree for a session entered
     * at a non-first segment. Adding the factor to the label then marked the
     * wrong episodes (an E4 tap on an sp2 file holding [3,4] marked E4+E5) and
     * left the ones the file really held unmarked. One for every scheme but
     * the segmented one, which holds its own factor; the season's tail is
     * clamped so the odd extra segment cannot be pushed as an episode that does
     * not exist.
     */
    private fun coveredTmdbEpisodes(tmdbEpisode: Int): List<Int> {
        val fileE = currentFileEpisode() ?: tmdbEpisode
        return bingeScheme.episodesOfFileClamped(fileE, totalEpisodesInSeason)
    }

    /**
     * The episode to chain into, as TMDB numbering for the labels and history.
     *
     * The file cursor is advanced through the detected scheme (see
     * [EpisodeScheme.advance]), which is what makes a file holding two segments
     * move the label on by two and a file holding half an episode leave it
     * where it is. Pure and repeatable - the tick, the prefetch and the panel
     * all ask for it, so it must not consume anything.
     */
    private fun nextEpisodeTarget(): Pair<Int, Int>? {
        val s = season ?: return null
        val e = episode ?: return null
        val fileE = currentFileEpisode() ?: e
        // The label of the next FILE, not the session's label plus the factor:
        // they differ for a session entered at a non-first segment, and adding
        // the factor to the wrong label chained the rest of the binge onto the
        // wrong episodes (an E4 tap on an sp2 file advanced to E6, skipping E5
        // and every label after it). See [EpisodeScheme.labelForFile].
        val nextTmdb = bingeScheme.labelForFile(fileE + 1)
        val maxEps = totalEpisodesInSeason
        return if (maxEps != null && nextTmdb > maxEps) {
            // End of season — jump to next season episode 1
            (s + 1) to 1
        } else {
            s to nextTmdb
        }
    }

    /**
     * The FILE number for a target the labels call [targetEpisode].
     *
     * The arithmetic next episode is wherever the file cursor goes - which,
     * when two files share one TMDB episode, is the second half of the episode
     * already playing. Any other target - a random pick, PREVIOUS, the first
     * episode of the next season - is the file that HOLDS that TMDB episode, so
     * the mapper answers instead: [EpisodeScheme.advance] is asked what the
     * arithmetic next is, and only when the two agree does the cursor speak.
     */
    private fun nextFileEpisodeFor(targetSeason: Int, targetEpisode: Int): Int {
        val s = season
        val e = episode
        if (s != null && e != null && targetSeason == s) {
            val fileE = currentFileEpisode() ?: e
            val (nextFile, nextTmdb) = bingeScheme.advance(fileE, e)
            if (nextTmdb == targetEpisode) return nextFile
        }
        return bingeScheme.fileForTmdbEpisode(targetEpisode)
    }

    /**
     * Builds the stream id for the next episode. Stremio stream ids are
     * "<imdb>:<season>:<episode>", so take the prefix of the current episode's
     * id and swap in the target season/episode. Reusing the current id (as
     * before) made auto-next re-fetch and replay the SAME episode.
     *
     * [targetFileEpisode] is FILE numbering - the identity the addons resolve -
     * so the caller converts the TMDB number the labels use (see
     * [nextFileEpisodeFor]).
     */
    private fun nextStreamId(targetSeason: Int, targetFileEpisode: Int): String {
        val current = episodeStreamId.orEmpty()
        val prefix = current
            .substringBeforeLast(':')
            .substringBeforeLast(':')
        return if (prefix.isNotBlank()) {
            "$prefix:$targetSeason:$targetFileEpisode"
        } else {
            "$parentId:$targetSeason:$targetFileEpisode"
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
        // A side panel left up would keep the D-pad (see closePanelsForEndCard).
        closePanelsForEndCard()
        // This is the panel for "there is nothing left to chain to", so
        // nothing an earlier Up Next decision left behind may fire: a held
        // countdown carrying those stale pendingNext* fields used to resolve
        // and try to play a next episode that does not exist, right under this
        // panel - and the resolution it started is what the viewer then backed
        // out of, losing the finished episode's bookkeeping.
        nextUpHandoffArmed = false
        nextUpCountdownHeld = false
        nextUpCountdownRemaining = 0
        nextUpCountdownHandler.removeCallbacks(nextUpCountdownRunnable)
        pendingNextSeason = null
        pendingNextEpisode = null
        pendingNextEpisodeName = null
        pendingNextEpisodeRuntime = null
        pendingNextEpisodeOverview = null
        // The picks are fetched BEFORE the panel is raised.
        //
        // It used to open on the spot and fill in a few seconds later, because
        // the TMDB round-trip behind build() ran after show(): the field report
        // of a BYW panel that "pops up blank for 3 to 7 seconds and then fills
        // in". Nothing is raised now when there is nothing to suggest - the
        // credits simply keep playing full screen - so the panel can never be
        // seen as an empty box.
        scope?.launch {
            val plan: BywPanelPlan = withContext(Dispatchers.IO) {
                val tmdbId = resolveParentTmdbId()
                    ?: return@withContext BywPanelPlan(null, emptyList())
                BywPanelPlan(
                    // The finished title's identity, resolved with the picks: the
                    // panel's thumbs write for it, and its IMDB flavor is the one
                    // lookup this adds (bounded, so a slow TMDB cannot delay the
                    // raise - see finishedTitleFor).
                    finished = finishedTitleFor(
                        this@NativePlayerActivity,
                        tmdbId,
                        bywMediaType(parentType),
                        itemName
                    ),
                    picks = buildBecauseYouWatchedPicks(
                        this@NativePlayerActivity,
                        tmdbId,
                        bywMediaType(parentType),
                        // The profile's own ceiling (null on an ordinary profile):
                        // a kids profile's credits row is held to it, exactly like
                        // every rail behind the player.
                        activeKidsMaxAge()
                    )
                )
            }
            val picks = plan.picks

            // This session's own scope is not lifecycle-bound (it owns the clock
            // and the IntroDB read too), so a fetch that lands after the viewer
            // left must not raise anything. The panel is no longer shown before
            // the fetch, so there is no on-screen state to put back either.
            if (picks.isEmpty() || bywDismissed || isFinishing || isDestroyed) {
                return@launch
            }

            withContext(Dispatchers.Main) {
                // The panel is only ever raised for a title the session has
                // finished with, so the episode MUST already be filed as
                // watched: the two systems that stand behind "watched" - the
                // local history row and the tracker push - were told by the
                // completion this session wrote. This line is the assertion the
                // field report asked for ("BYW appeared but the episode is not
                // marked watched"): a capture showing `byw shown` with
                // `filed=false` is that inconsistency, caught the moment it
                // happens rather than weeks later on Continue Watching.
                if (!completionFiled) {
                    Log.w(
                        TAG,
                        "BYW raised without a filed completion for ${historyId} " +
                            "(s=${season ?: "-"} e=${episode ?: "-"}); the episode is " +
                            "not marked watched"
                    )
                    PlaybackSessionTrace.note(
                        "byw shown filed=false s=${season ?: "-"} e=${episode ?: "-"} id=$historyId"
                    )
                } else {
                    PlaybackSessionTrace.note(
                        "byw shown filed=true s=${season ?: "-"} e=${episode ?: "-"} id=$historyId"
                    )
                }
                bywUi.show(itemName, plan.finished)
                // Credits are rolling: shrink the video into the corner so the
                // picks own the screen while the credits keep playing.
                enterCreditsMode()
                // Filled in the same turn as the raise, so there is no frame in
                // which the panel is up with an empty row.
                bywUi.build(picks)
            }
        }
    }

    /**
     * PLAY: hand the pick back unresolved.
     *
     * Which of the two things happens next is the rule PLAY follows everywhere
     * else - auto-select on resolves and plays the best source, off opens the
     * source list - and MainActivity owns that setting. This used to resolve the
     * sources here and hand over the single top stream whatever the setting
     * said, so with auto-select off there was no way to reach the other sources.
     */
    private fun bywPlayPick(pick: BywPick, imdbId: String) {
        bywDismissed = true
        finishWithBywResult(action = "play_now", pick = pick, imdbId = imdbId)
    }

    private fun bywOpenDetails(pick: BywPick, imdbId: String) {
        bywDismissed = true
        finishWithBywResult(action = "go_details", pick = pick, imdbId = imdbId)
    }

    /** Hands the pick back to MainActivity (which owns Detail/Player nav). */
    private fun finishWithBywResult(
        action: String,
        pick: BywPick,
        imdbId: String
    ) {
        setResult(
            RESULT_OK,
            Intent().apply {
                putExtra("player_result_action", action)
                putExtra("byw_type", pick.type)
                putExtra("byw_id", imdbId)
                putExtra("byw_name", pick.name)
                putExtra("byw_poster", pick.posterUrl)
                // The pick's own backdrop is often absent (franchise and
                // credits candidates carry a poster only), while the panel
                // fetched one for its featured strip. Hand over what the strip
                // showed - the pick's own value was the whole of it before, so
                // the player's splash had nothing to draw.
                putExtra("byw_backdrop", bywUi.backdropFor(pick))
                // Never sent before: the fetched clearlogo stayed in the panel's
                // own cache, so a pick's splash could only ever show the
                // player's fallback lookup, not the strip's wordmark.
                putExtra("byw_clearlogo", bywUi.clearLogoFor(pick))
            }
        )
        mediaSession?.release()
        mediaSession = null
        finish()
    }

    private fun showNextUpPanel(targetSeason: Int, targetEpisode: Int) {
        // A side panel left up would keep the D-pad (see closePanelsForEndCard).
        closePanelsForEndCard()
        pendingNextSeason = targetSeason
        pendingNextEpisode = targetEpisode
        pendingNextEpisodeName = null
        pendingNextEpisodeOverview = null
        // This IS the decision now: only the countdown and PLAY that follow
        // from it may auto-advance.
        nextUpHandoffArmed = true

        // The session's name can be an internal id when the card that launched
        // it had no name of its own and enrichment failed (see
        // upNextPlayerDisplayName, which stops that at the sending end - this is
        // the receiving belt-and-braces, for a name that arrived by another
        // route: a persisted NextEpisodeResult, a deep link, the picker). An id
        // printed as the show title is worse than saying nothing, because the
        // "Season 4 • Episode 41" line below still says what is coming.
        if (looksLikeRawMediaId(itemName, hasArtwork = !(backdropUrl ?: itemPoster).isNullOrBlank())) {
            nextUpShowTitle.visibility = android.view.View.GONE
        } else {
            nextUpShowTitle.visibility = android.view.View.VISIBLE
            nextUpShowTitle.text = itemName
        }
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
        // The end of every episode: the panel used to appear in one frame and
        // snatch focus in the same one. It now fades up where it is, with the
        // focus request left exactly where it was - it is the last thing the
        // viewer sees of the episode, and it was the app's most abrupt.
        KbViewMotion.fadeIn(nextUpPanel)
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
                    themeAccentColor(this)
                )
                nextUpCountdownHandler.removeCallbacks(nextUpCountdownRunnable)
                return
            }
            // Once the player has declared the session over, the remaining
            // time is not a question anymore: currentPosition may already have
            // been reset, and arming a HELD countdown from it would leave the
            // auto-advance waiting for an end that already passed - the popup
            // sat there and the next episode never ran.
            armNextUpAutoAdvance(
                if (playbackEndedHandled) 0L else playerRemainingMs()
            )
        } else {
            nextUpCountdownHeld = false
            nextUpCountdownRemaining = 0
            nextUpCountdown.text = "PLAY NEXT to continue, or press BACK to dismiss"
        }

        // Best effort: fetch the next episode's name + still from TMDB so the
        // popup shows real episode details instead of just S#E#.
        scope?.launch {
            val nextEp: com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode? = withContext(Dispatchers.IO) {
                val repo = TmdbRepository.getInstance(this@NativePlayerActivity)
                val tmdbId = resolveParentTmdbId() ?: return@withContext null
                val episodes = runCatchingCancellable {
                    repo.getSeasonEpisodes(tmdbId, targetSeason, parentId)
                }.getOrNull()
                episodes?.firstOrNull { it.episodeNumber == targetEpisode }
            }
            if (nextEp != null && nextUpPanel.visibility == View.VISIBLE) {
                pendingNextEpisodeName = nextEp.name
                // Spoiler-free mode. This panel offers the episode the viewer
                // has NOT reached - the credits are still rolling over the one
                // before it - so the name it just resolved and the frame from
                // it are precisely what the mode hides. The panel keeps the
                // show's own artwork (loaded above) and its S#E# line, so it
                // still says which episode is coming without saying which one
                // it is.
                //
                // The handoff values set here stay whole: they are what the
                // next episode's own session opens with, not what is drawn on
                // this panel, and that session is playing the episode rather
                // than offering it.
                val hideNextEpisode = com.kennyb1201.kbstream.data.spoiler.SpoilerFree
                    .hidesIdentity(
                        enabled =
                            AppPreferences.getSpoilerFree(this@NativePlayerActivity),
                        // Not watched, and not started: the viewer is at the end
                        // of the episode before it. A rewatch is the only case
                        // where they have seen it, and there the cost is a
                        // number, not a reveal.
                        watched = false,
                        started = false
                    )
                nextUpEpisodeTitle.text =
                    if (hideNextEpisode) {
                        "S${targetSeason}E$targetEpisode"
                    } else {
                        nextEp.name ?: "S${targetSeason}E$targetEpisode"
                    }
                nextEp.runtimeMinutes?.takeIf { it > 0 }?.let { pendingNextEpisodeRuntime = it }
                pendingNextEpisodeOverview = nextEp.overview?.takeIf { it.isNotBlank() }
                val still = nextEp.thumbnail
                if (!hideNextEpisode && !still.isNullOrBlank()) {
                    try {
                        nextUpThumb.load(still)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    /**
     * Closes the "Up next" card without ending the session.
     *
     * Back on this card used to fall through to finish(), so the press a viewer
     * makes to wave it away exited the player instead - at the end of the very
     * episode they were watching. Dismissing disarms the handoff, so neither the
     * countdown nor the card's PLAY NEXT can advance after it is gone, stops the
     * countdown, and hides the panel. The video is left exactly where it was: the
     * ending still playing, or the credits still rolling.
     *
     * The prefetched next-episode name/runtime/overview stay in place: the manual
     * NEXT path reads them too, and the handoff is disarmed so nothing can
     * auto-advance from them.
     */
    private fun dismissNextUpPanel() {
        if (!::nextUpPanel.isInitialized || nextUpPanel.visibility != View.VISIBLE) return
        nextUpHandoffArmed = false
        nextUpCountdownHeld = false
        nextUpCountdownRemaining = 0
        nextUpCountdownHandler.removeCallbacks(nextUpCountdownRunnable)
        nextUpPanel.visibility = View.GONE
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
            // No countdown yet, so the bar has nothing to show: an empty track
            // reads as "waiting", a full one would read as "about to play".
            nextUpCountdownBar.progress = 0
            return
        }
        nextUpCountdownHeld = false
        nextUpCountdownRemaining = NEXT_UP_COUNTDOWN_SECONDS
        nextUpCountdown.text = "Playing next in $nextUpCountdownRemaining"
        nextUpCountdown.setTextColor(ContextCompat.getColor(this, R.color.kb_text_lo))
        nextUpCountdownBar.max = NEXT_UP_COUNTDOWN_SECONDS
        nextUpCountdownBar.progress = NEXT_UP_COUNTDOWN_SECONDS
        nextUpCountdownHandler.postDelayed(nextUpCountdownRunnable, 1_000L)
    }

    /** Starts the held countdown once playback has reached the end. */
    private fun maybeStartHeldNextUpCountdown(pos: Long, dur: Long) {
        if (!nextUpCountdownHeld) return
        if (dur <= 0L || dur == C.TIME_UNSET) return
        if (pos < dur - NEXT_UP_HOLD_THRESHOLD_MS) return
        armNextUpAutoAdvance(remainingMs = 0L)
    }

    /**
     * Records the episode this session is handing off FROM, before the next
     * one opens. Backing out of the player already files the episode - this is
     * the same write, moved to the moment the end-of-episode card hands
     * playback over, because that is how a binge leaves every episode but the
     * last. See the call in [launchNextEpisode] for what went wrong when the
     * write waited for onStop.
     *
     * [shouldRecordCompletion] is the exit path's own "is it finished?" rule,
     * so an episode the viewer skipped out of early stays a resume point
     * instead of being marked watched.
     */
    private fun fileEpisodeForHandoff() {
        if (isLiveChannel) return
        // Backstop for a file a viewer sat through without the position tick
        // ever seeing a duration (a short file, a very early Next): the scheme
        // has to be known BEFORE the handoff decides what to chain into.
        maybeDetectScheme()
        val player = exoPlayer ?: return
        val pos = player.currentPosition.coerceAtLeast(0L)
        val rawDur = player.duration
        val dur = if (rawDur <= 0L || rawDur == C.TIME_UNSET) 0L else rawDur
        // Reached only from launchNextEpisode, which IS an advance of the
        // session - so the rule is told so. Pressing Next in the credits,
        // before the end-of-episode countdown has raised the card, used to
        // file a resume row with a minute or two left while the "stop"
        // scrobble told the tracker the episode was watched; the tracker's
        // tick then outlived the local row (see supersededResumeRowIds). The
        // rule keeps the tail gate, so a Next pressed in the middle of an
        // episode still files a resumable position.
        val completed = shouldRecordCompletion(
            playbackEnded = playbackEndedHandled || playbackEndReached,
            endPanelsShown = endPanelsShown,
            positionMs = pos,
            durationMs = dur,
            played = sessionHasPlayed(),
            explicitAdvance = true,
            completionFiled = completionFiled
        )
        // The verdict is reported AFTER the write commits (onWritten), so the
        // trace can no longer claim "filed" for a row that was never stored.
        saveProgress(reason = "handoff", forceCompleted = completed) { ok, profile ->
            com.kennyb1201.kbstream.data.reporting.PlaybackSessionTrace.note(
                "filed s=${season ?: "-"} e=${episode ?: "-"} " +
                    "row=$historyId completed=$completed written=$ok profile=${profile ?: "-"}"
            )
        }
        scrobbleSimkl("stop", progressOverride = if (completed) 100.0 else null)
    }

    private fun launchNextEpisode(
        targetSeason: Int,
        targetEpisode: Int,
        episodeName: String? = null,
        runtimeMinutes: Int? = null,
        episodeOverview: String? = null
    ) {
        // One handoff per session: whichever trigger gets here first wins, and
        // the rest are no-ops (see [nextEpisodeHandoffStarted]).
        if (nextEpisodeHandoffStarted) return
        nextEpisodeHandoffStarted = true
        // Reaching here while a timer is armed to stop at the end of this
        // episode means the viewer pressed PLAY NEXT: an explicit press beats
        // the timer, so the timer goes with the episode it was waiting for. A
        // minutes timer is left alone - that intent is about the clock, not
        // about this episode.
        if (SleepTimer.state.value.stopsAtEndOfItem) SleepTimer.cancel()
        // File THIS episode here, not only from onStop. Every episode of a
        // binge but the last leaves through this call, and on these boxes
        // onStop can be blocked behind the handoff (the OS brings the next
        // player up first), so a session whose row and tracker "stop" both
        // rode on onStop left the finished episode with no watch marker, no
        // progress row and no completion push to the trackers - and its late
        // stop then landed AFTER the next episode's "start", which ends the
        // Simkl session the new episode had just opened, so live scrobbling
        // went quiet for the rest of the binge. saveProgress is an idempotent
        // upsert and a second stop is answered 409 (already ended), so the
        // exit path repeating either is harmless - which is why onStop skips
        // both once this has run.
        fileEpisodeForHandoff()
        // The end-of-episode decision is spent: a later countdown tick must not
        // re-fetch a target for a session that is already leaving.
        nextUpHandoffArmed = false
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
            streamId = nextStreamId(targetSeason, nextFileEpisodeFor(targetSeason, targetEpisode)),
            runtimeMinutes = runtimeMinutes,
            bingeGroup = currentBingeGroup,
            addonName = currentAddonName,
            overview = episodeOverview,
            // Random mode rides along: the handoff starts a NEW player, which
            // would otherwise read its intent as a normal (arithmetic) chain.
            randomEpisodes = randomEpisodes
        )
        // The handoff itself, into the same report: which episode this session
        // was, and which one it is chaining to. Read against the session line
        // above, a chain that stops advancing is visible as a repeated source
        // pair.
        com.kennyb1201.kbstream.data.reporting.PlaybackSessionTrace.note(
            "next: s=$targetSeason e=$targetEpisode from s=${season ?: "-"} e=${episode ?: "-"} id=${pendingNext.streamId}"
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
                putExtra("next_overview", pendingNext.overview)
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
                    // Never move the bar out from under an in-flight scrub.
                    // updateSeekBarPosition() already keeps the bar and the
                    // clock on the position being scrubbed to; this 1-second
                    // tick runs behind it and used to write the player's own
                    // (slower, still-rebuffering) position instead. The bar
                    // therefore jumped to where the viewer scrubbed and then
                    // snapped back a tick later, and the release that commits
                    // the seek (commitSeekFromBar) committed the snap - which
                    // is what read as "scrubbing forward and back barely
                    // works". Keep only the duration honest while scrubbing.
                    val scrubbing = scrubDirection != 0 || chrome?.isScrubbing == true
                    val durMs = if (dur > 0 && dur != C.TIME_UNSET) dur else 0L
                    if (!scrubbing) {
                        chrome?.setScrubPosition(pos, durMs)
                    } else if (durMs > 0L) {
                        // Keep only the duration honest while scrubbing: the bar
                        // and the position belong to the scrub's own target.
                        totalTime.text = formatDurationMillis(durMs)
                    }
                }

                // The file the viewer is watching may hold two TMDB episodes,
                // or be half of one: read that from the file's own duration
                // against the episode's TMDB runtime, once per file. Cheap and
                // non-blocking - it does nothing until both numbers exist.
                maybeDetectScheme()

                // Update play/pause button
                btnPlayPause.setImageResource(
                    if (player.isPlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play
                )
                tintPlayPauseIcon(btnPlayPause, this@NativePlayerActivity)

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

                // Holds the panel to the mode this session engaged, for a TV
                // that leaves it later (see FrameRateMatcher.verifyEngagedMode):
                // the AFR ladder only runs in the opening seconds, so without
                // this a panel that moved afterwards stayed wrong - and the
                // judder came back mid-film with nothing asking again.
                frameRateMatcher?.verifyEngagedMode()

                // Last, so a timer that fires on this tick stops a session the
                // completion checks have already finished with.
                enforceSleepTimer(player)
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

        // A frozen playhead only means "the file ended" once the session has
        // actually played: before that it is a source that never came up, and
        // latching it ended here (plus pausing it) would strand the session as
        // finished instead of leaving it to the retry/error path. The real
        // ENDED branch above is unaffected; onPlaybackEnded guards itself too.
        if (sessionHasPlayed() && (reachedDuration || posStallTicks >= 2)) {
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
            // An open-ended segment is measured against the runtime, and the
            // runtime is unknown for the first ticks of a session - so the
            // lookup simply waits for it instead of guessing an end.
            val durationMs = player.duration.takeIf { it > 0L && it != C.TIME_UNSET } ?: 0L
            val matching = if (player.isPlaying && player.playbackState == Player.STATE_READY) {
                introDbStamps.firstOrNull { stamp ->
                    AutoSkipRules.isInside(stamp, posMs) &&
                        AutoSkipRules.isSkippableSegment(stamp, durationMs)
                }
            } else null
            if (matching != activeIntroStamp) {
                activeIntroStamp = matching
                if (matching != null) {
                    btnSkipIntro.text = matching.type.buttonLabel
                    // Same entrance the shared chrome uses, so the legacy
                    // native path and PlayerChrome's setSkipIntro agree: a fade
                    // in place, never a slide. A translated pill is composited
                    // from a render node still offset by the slide, which clips
                    // a sliver off the plate's leading corner (see
                    // PlayerChrome.setSkipIntro).
                    btnSkipIntro.translationX = 0f
                    KbViewMotion.fadeIn(btnSkipIntro)
                } else {
                    val wasFocused = btnSkipIntro.isFocused
                    btnSkipIntro.translationX = 0f
                    KbViewMotion.fadeOut(btnSkipIntro)
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
    private fun saveProgress(
        reason: String,
        forceCompleted: Boolean = false,
        onWritten: ((Boolean, String?) -> Unit)? = null
    ) {
        val player = exoPlayer ?: return
        if (isLiveChannel || parentId.isBlank() || historyId.isBlank()) return

        // Capture playback position synchronously: onStop() releases the
        // player immediately after this returns, so the position has to be
        // read while the player is still alive. The Room write then runs off
        // the main thread via lifecycleScope instead of blocking it.
        val pos = player.currentPosition.coerceAtLeast(0L)
        val rawDur = player.duration
        val dur = if (rawDur == C.TIME_UNSET || rawDur <= 0L) null else rawDur
        // A missing duration used to abandon the write entirely - including
        // the completion write onPlaybackEnded issues, which is the one fact
        // the player can state without knowing how long the file was. A
        // finished episode then kept no watch marker and its old resume bar,
        // exactly as if it had never been played. A session the player itself
        // declared over is written regardless; only a MID-session save still
        // needs a real duration to mean anything.
        // Every write this session makes is judged as completed once its
        // episode has finished ([playbackEndReached]), not only the write the
        // end itself issues. A pause taken after a rewind, or the exit save,
        // used to re-decide the verdict from where the playhead happened to be
        // - so watching an episode to its end and then moving the playhead back
        // (a scrub behind the end panel, a transport key) filed a RESUME row
        // over the completion: the episode lost its watched marker and went
        // straight back onto Continue Watching. The episode DID finish, and no
        // later position can unsay it.
        //
        // [completionFiled] is the same fact arrived at the other way: the file
        // never ended, but the 90% threshold below completed the episode, and
        // that completion was written. From that moment on every save in the
        // session is completed too, so the pause/exit save that follows can no
        // longer race it and put a resume point back over the watched marker.
        val sessionCompleted = forceCompleted || playbackEndReached || completionFiled
        if (dur == null && !sessionCompleted) return
        if (pos < MIN_RESUME_POSITION_MS && !sessionCompleted) return
        // With no length the played position is the best duration we have:
        // the row is completed anyway (position reset to 0), and a non-zero
        // duration keeps it from reading as a broken card.
        val effectiveDur = dur ?: pos.coerceAtLeast(1L)
        val isCompleted =
            sessionCompleted ||
                (dur != null && pos >= (dur * COMPLETION_THRESHOLD_RATIO).toLong())
        val safePos = if (isCompleted) 0L else pos.coerceAtMost(effectiveDur)
        val now = System.currentTimeMillis()
        // Filed BEFORE the write is launched, so a second save racing this one
        // (a pause landing as the end is handled) reads the fact rather than
        // re-deciding from the playhead. Cleared only with the activity.
        if (isCompleted) completionFiled = true

        // A row filed from the credits tail is a "leaving" row even when the
        // local rule above did not call it completed: the tracker's own stop
        // can still mark the episode watched, and without a re-merge the
        // finished episode holds its Continue Watching card until a restart.
        // Ask Home to re-read the feeds exactly as a completion does. (A
        // completed row's push asks again once it resolves; duplicate requests
        // only restart the same bounded retry window.) The tail needs a REAL
        // duration, so the effective (position-as-duration) fallback is not
        // used here - an unknown length is not a tail.
        if (dur != null && isCreditsTail(pos, dur)) {
            ContinueWatchingRefreshBus.requestRefresh()
        }

        // NonCancellable: this write must land even when the activity is
        // being torn down (onStop/onDestroy cancel their scopes mid-exit).
        // Without it the upsert could be aborted partway through exiting the
        // player, leaving Continue Watching stale until the next save.
        lifecycleScope.launch(Dispatchers.IO + NonCancellable) {
            // Same title, same canonical parent id, whichever id flavor
            // launched this playback (see canonicalHistoryParentId). The row
            // goes through PlaybackHistoryWriter, which files it under the
            // profile this SESSION started on and refuses the write if the
            // user switched profiles while it was playing.
            val entry =
                WatchHistoryEntity(
                    id = historyId, parentId = canonicalHistoryParentId(), type = parentType,
                    name = itemName, episodeTitle = episodeTitle, overview = overview,
                    clearLogo = clearLogoUrl, totalEpisodesInSeason = totalEpisodesInSeason,
                    poster = itemPoster, streamUrl = currentUrl,
                    season = season, episode = episode, episodeStreamId = episodeStreamId,
                    positionMs = safePos, durationMs = effectiveDur, updatedAt = now,
                    isCompleted = isCompleted,
                    // Completed rows keep the stamp of the first completion;
                    // the writer reads it back from the row it replaces.
                    completedAt = null
                )
            val result = PlaybackHistoryWriter.write(
                this@NativePlayerActivity,
                sessionProfileId,
                entry
            )
            if (isCompleted) syncCompletedToSimkl()
            // "Delete after finishing" (spec §4.5): the completion has been
            // FILED, so a finished download may go. Gated on the file having
            // actually ENDED, or on this session closing while it is already
            // complete - never on the completion alone, because the completion
            // rule fires at 90% and deleting the bytes there would strand the
            // rest of the playback reading from a cache that no longer has them.
            if (isCompleted && downloadId != null) {
                val sessionOver = playbackEndReached || isFinishing
                if (sessionOver) {
                    // By the finished download's own id, not by the title key:
                    // an episode's media key is the SHOW's, and deleting on it
                    // took every other completed episode of that show with it
                    // (audit C2, 2026-10-27). The manager looks the record up
                    // itself and owns the toggle's own gate.
                    downloadId?.let { id ->
                        DownloadsManager.onTitleCompleted(this@NativePlayerActivity, id)
                    }
                }
            }
            onWritten?.invoke(result.ok, result.profileId)
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

        val position = carriedPositionMs()

        val launch = Intent(baseIntent).apply {
            putExtra("stream_url", currentUrl)
            putExtra("audio_url", currentAudioUrl)
            putExtra("start_position_ms", position)
            // The new session resumes where this one stopped; "from the
            // beginning" would restart the title mid-episode. The one case the
            // flag still means something is a from-the-beginning launch that
            // never played a frame (the stream died and the viewer pressed
            // SWITCH): there is no playhead to carry, and dropping the flag
            // would let the successor's own watch-history resume start the very
            // title the viewer asked to start over.
            putExtra("from_beginning", position <= 0L && startFromBeginning)
            putExtra(
                EXTRA_HEADERS,
                streamHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" }
            )
            // Pin the successor to THIS session's profile. Otherwise the MPV
            // session re-resolves whichever profile is active by the time it
            // starts, so a mid-session switch would file history and scrobble to
            // the wrong profile. External->Native forwards this the same way.
            sessionProfileId?.let {
                putExtra(PlaybackHistoryWriter.EXTRA_SESSION_PROFILE_ID, it)
            }
            putExtra(MpvPlayerActivity.EXTRA_MPV_FALLBACK, true)
            putExtra(MpvPlayerActivity.EXTRA_MPV_FALLBACK_REASON, reason)
            // Speed does not survive in PlayerTitlePrefs, so it travels in the
            // launch - otherwise the switch silently put playback back at 1x.
            putExtra(EXTRA_PLAYBACK_SPEED, playbackSpeed)
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
        // The handoff is the answer to "did the session change engines, and
        // why": a capture with this line and no decoder-failure line before it
        // is a session the viewer switched by hand, which is a different report.
        PlaybackEngineTrace.note(
            PlaybackEngineTrace.describe(
                cause = if (manual) {
                    "engine switch to MPV by hand"
                } else {
                    "engine switch to MPV ($reason)"
                },
                detail = "from ${position}ms"
            )
        )
        errorMessageStr = null
        // The card is what is on screen when this is pressed from there: the
        // reconnecting notice takes its place for the handover itself.
        errorContainer.visibility = View.GONE
        reconnectingContainer.visibility = View.VISIBLE
        hideBufferingSpinner()
        reconnectingText.text =
            if (manual) {
                "Switching to the MPV player…"
            } else {
                "Continuing in the MPV backup engine…"
            }
        // Give up this engine's read of the stream BEFORE the other one opens
        // it. Android runs the new activity's onCreate/onResume first and this
        // activity's onStop - which is what releases the player - second, so
        // the backup engine was opening the file while this one still held it.
        // On a debrid or usenet link, which commonly allows exactly one
        // connection, that is the backup failing with "this stream could not be
        // played" every time it is reached from here. stop() closes the loaders
        // without resetting the player or the position, and onStop writes no
        // history for a handoff (mpvHandoffStarted), so nothing reads it after.
        runCatching { exoPlayer?.stop() }
        mpvFallbackLauncher.launch(mpvHandoffIntent(launch))
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

    /**
     * Where this session continues from when the engine changes.
     *
     * A player with nothing loaded reports position 0, and 0 is a perfectly
     * valid position for a file that just started - so "no playhead" and "the
     * very beginning" are the same number. The handoffs used to trust it, with
     * an elvis that only covered a null player, so an engine switch pressed
     * from the error card - the case the button exists for - handed the other
     * engine 0 and restarted the title from the beginning.
     *
     * [carryPositionMs] is the playhead this session holds for exactly this
     * purpose (see its declaration and the subtitle/audio rebuilds that use
     * it), so it answers when the player itself cannot. [startPositionMs] is
     * the floor: where this session was ASKED to start, which is still the
     * right resume point for a handoff taken before anything played.
     */
    private fun carriedPositionMs(): Long {
        val own =
            runCatching {
                exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L
            }.getOrDefault(0L)

        return (if (own > 0L) own else carryPositionMs)
            .takeIf { it > 0L }
            ?: startPositionMs.coerceAtLeast(0L)
    }

    /**
     * The control bar's "play in another app" button: hand this session to an
     * installed external player.
     *
     * The third engine, and the one that gives the LEAST to the other app to
     * do: the stream URL, a title and a resume point go over, and everything
     * that makes playback a feature stays here. The wrapper that receives the
     * handoff ([com.kennyb1201.kbstream.ui.player.ExternalPlayerActivity])
     * measures the playhead while the other app is in front, writes the same
     * watch-history row this engine writes, scrobbles to the same trackers, and
     * raises the same two end-of-episode panels - so a title played externally
     * keeps its Continue Watching card and still chains into its next episode,
     * on whichever engine the settings say.
     *
     * Refused, and silently, for the two sessions it cannot take, both for the
     * same reason the MPV backup refuses them: live TV has no runtime to
     * measure and no end to chain from, and a DRM license is ours to request -
     * another app handed the URL alone could not play it.
     */
    private fun handOffToExternal(): Boolean {
        if (externalHandoffStarted) return false
        if (isLiveChannel || drmLicenseUrl != null) return false
        if (currentUrl.isBlank()) return false
        if (isFinishing || isDestroyed) return false
        if (!PlayerEngine.externalAvailable(this)) return false
        val baseIntent = intent ?: return false
        externalHandoffStarted = true

        val position = carriedPositionMs()

        // Re-play the original launch with the stream extras replaced, so the
        // wrapper inherits the whole session: the episode it is on, the poster
        // and backdrop for the end-of-episode cards, the runtime it measures
        // against, and the source list a "play in app" fall-out needs.
        val launch = Intent(baseIntent).apply {
            setClass(this@NativePlayerActivity, ExternalPlayerActivity::class.java)
            removeExtra(MpvPlayerActivity.EXTRA_MPV_FALLBACK)
            removeExtra(MpvPlayerActivity.EXTRA_MPV_FALLBACK_REASON)
            putExtra("stream_url", currentUrl)
            putExtra("start_position_ms", position)
            // Resume, never restart: the title is already part-way through.
            // Same exception as the MPV handoff above: a from-the-beginning
            // launch with no playhead yet keeps the flag, or the wrapper's own
            // watch-history resume would start the title the viewer asked to
            // start over.
            putExtra("from_beginning", position <= 0L && startFromBeginning)
            putExtra(
                EXTRA_HEADERS,
                streamHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" }
            )
            historyParentIdOverride?.let {
                putExtra(MpvPlayerActivity.EXTRA_HISTORY_PARENT_ID, it)
            }
            // m9: the third engine in a chain still belongs to the profile that
            // STARTED the session. The MPV handoff above pins it; this one used
            // to drop it, so an Exo -> external switch after a profile change
            // filed the session under the new profile.
            sessionProfileId?.let {
                putExtra(PlaybackHistoryWriter.EXTRA_SESSION_PROFILE_ID, it)
            }
        }

        Log.i(
            "PLAYER_RETRY",
            "handing playback to the external player from ${position}ms"
        )
        // Same order as [handOffToMpv], and for the same reason: this activity
        // outlives the handoff launch (the wrapper starts before this one
        // stops), so its loaders would still hold the stream the external app
        // is opening. The window is smaller across processes, but a debrid or
        // usenet link that allows ONE connection does not care which engine
        // asked - the second opener fails either way.
        runCatching { exoPlayer?.stop() }
        errorMessageStr = null
        errorContainer.visibility = View.GONE
        reconnectingContainer.visibility = View.VISIBLE
        hideBufferingSpinner()
        reconnectingText.text = "Opening in the external player\u2026"
        externalLauncher.launch(launch)
        return true
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

    /**
     * Mirror the current scrobble action to Trakt.
     *
     * The third fire-and-forget beside Simkl and MDBList: same session events,
     * its own token, and every failure is silent. A tracker that is not
     * connected (or a build without a client id) is a no-op, not an error.
     */
    private suspend fun scrobbleTrakt(action: String, progress: Double) {
        // Same TOCTOU re-check as the other two trackers: the token is the
        // ACTIVE profile's, resolved at fire time, so a session that outlived a
        // profile switch must not scrobble the departing episode to the
        // profile the viewer moved TO.
        if (!PlaybackHistoryWriter.sessionStillActive(this, sessionProfileId)) return
        val trakt = TraktRepository.getInstance(this)
        if (!trakt.isConfigured() || !trakt.hasToken() || parentId.isBlank()) return
        trakt.scrobble(
            action = action,
            parentId = parentId,
            parentType = parentType,
            season = season,
            episode = episode,
            progress = progress,
            tmdbId = resolveParentTmdbId()
        )
    }

    /** Records a finished title on Trakt, once per session. */
    private suspend fun syncCompletionToTrakt() {
        if (!PlaybackHistoryWriter.sessionStillActive(this, sessionProfileId)) return
        val trakt = TraktRepository.getInstance(this)
        if (!trakt.isConfigured() || !trakt.hasToken() || parentId.isBlank()) return
        val tmdb = resolveParentTmdbId()
        when (parentType.lowercase()) {
            "movie" -> trakt.pushWatchedMovie(parentId = parentId, tmdbId = tmdb)
            "series", "show", "tv" -> {
                val s = season
                val e = episode
                if (s != null && e != null) {
                    // Same covered-episode fan-out as Simkl and MDBList: a file
                    // covering two TMDB episodes marks both, or the second
                    // segment stays unwatched on the tracker.
                    coveredTmdbEpisodes(e).forEach { coveredEpisode ->
                        trakt.pushWatchedEpisode(
                            showId = parentId,
                            season = s,
                            episode = coveredEpisode,
                            tmdbId = tmdb
                        )
                    }
                }
            }
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
        // A tracker call resolves the ACTIVE profile's token/key at fire time,
        // so a session that outlived a profile switch must not scrobble the
        // departing episode to the profile the viewer moved TO.
        if (!PlaybackHistoryWriter.sessionStillActive(this, sessionProfileId)) {
            com.kennyb1201.kbstream.data.reporting.PlaybackSessionTrace.note(
                "tracker $action skipped: session profile departed"
            )
            return
        }
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
            var departed = false
            val ok = runCatchingCancellable {
                val simkl = SimklRepository.getInstance(applicationContext)
                val tmdbId = resolveParentTmdbId()
                // TOCTOU re-check: the guard above ran before this coroutine's
                // first suspension, and every tracker call resolves the ACTIVE
                // profile's token at CALL time. resolveParentTmdbId() can
                // suspend across a profile switch, so re-check before firing.
                if (!PlaybackHistoryWriter.sessionStillActive(applicationContext, sessionProfileId)) {
                    departed = true
                    false
                } else {
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
                }
            }.getOrDefault(false)
            if (departed) {
                com.kennyb1201.kbstream.data.reporting.PlaybackSessionTrace.note(
                    "tracker $action skipped after suspend: session profile departed"
                )
                return@launch
            }
            // Independent MDBList scrobble — same session events, separate
            // tracker. Mirrors Simkl only when a key is set.
            runCatchingCancellable { scrobbleMdbList(action, progress) }
                .onFailure { Log.w(TAG, "MDBList scrobble/$action error: ${it.message}") }
            runCatchingCancellable { scrobbleTrakt(action, progress) }
                .onFailure { Log.w(TAG, "Trakt scrobble/$action error: ${it.message}") }
            if (!ok && action == "start") {
                simklScrobbleActive = false
                Log.e(TAG, "Simkl scrobble start failed; will retry on next play")
            }
        }
    }

    private fun syncCompletedToSimkl() {
        if (simklScrobbleSent || parentId.isBlank()) return
        if (!PlaybackHistoryWriter.sessionStillActive(this, sessionProfileId)) {
            com.kennyb1201.kbstream.data.reporting.PlaybackSessionTrace.note(
                "tracker completion skipped: session profile departed"
            )
            return
        }
        simklScrobbleSent = true
        simklSyncJob?.cancel()
        simklSyncJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            var departed = false
            val ok = runCatchingCancellable {
                val simkl = SimklRepository.getInstance(applicationContext)
                val tmdbId = resolveParentTmdbId()
                // Same TOCTOU re-check as the scrobble path: the guard above ran
                // before this coroutine's first suspension, and the tracker
                // resolves the ACTIVE profile's token at CALL time.
                if (!PlaybackHistoryWriter.sessionStillActive(applicationContext, sessionProfileId)) {
                    departed = true
                    false
                } else {
                    when (parentType.lowercase()) {
                        "movie" -> simkl.pushWatchedMovie(imdbId = parentId, title = itemName, tmdbId = tmdbId)
                        "series", "show", "tv" -> {
                            val s = season; val e = episode
                            if (s != null && e != null) {
                                // One file can cover several TMDB episodes (see
                                // coveredTmdbEpisodes): every one of them is
                                // marked, or the second segment of a doubled
                                // file stays unwatched on the tracker and its
                                // own "next up" keeps offering it.
                                var pushed = true
                                coveredTmdbEpisodes(e).forEach { coveredEp ->
                                    val ok = simkl.pushWatchedEpisode(
                                        showImdbId = parentId,
                                        season = s,
                                        episode = coveredEp,
                                        title = itemName,
                                        tmdbId = tmdbId
                                    )
                                    if (!ok) pushed = false
                                }
                                pushed
                            } else {
                                false
                            }
                        }
                        else -> false
                    }
                }
            }.getOrDefault(false)
            if (departed) {
                com.kennyb1201.kbstream.data.reporting.PlaybackSessionTrace.note(
                    "tracker completion skipped after suspend: session profile departed"
                )
                return@launch
            }
            // Mirror the completion to MDBList (POST /sync/watched) so both
            // trackers record finished movies/episodes.
            runCatchingCancellable {
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
            runCatchingCancellable { syncCompletionToTrakt() }
                .onFailure { Log.w(TAG, "Trakt completion sync error: ${it.message}") }
            if (!ok) {
                simklScrobbleSent = false
                Log.e(TAG, "Simkl completion sync failed; will retry")
            }
            // The completion is now on the tracker, so the Continue Watching
            // feeds this episode's title came from are stale. Home's ON_RESUME
            // refresh races this write and can read the pre-completion feed,
            // leaving a finished title on the rail until the next resume; ask
            // for one more merge now that the push has landed.
            ContinueWatchingRefreshBus.requestRefresh()
        }
    }

    // --- Sleep timer ---------------------------------------------------------

    /**
     * The rows this session can offer. Read fresh on every second the panel is
     * up, because a live channel's "End of program" depends on guide data
     * that arrives - and rolls over to the next block - after the panel was
     * built.
     */
    internal fun sleepTimerChoices(): List<SleepTimerOption> = sleepTimerOptions(
        isLive = isLiveChannel,
        isEpisode = season != null && episode != null,
        hasProgramEnd = currentProgramEndMs() != null
    )

    /**
     * The guide's end for the program this channel is on now, or null when
     * there is none to trust.
     *
     * Only the already-resolved zap row is consulted. Asking the EPG again from
     * here would put a query behind a panel press, and the row this reads is
     * the same one the zap banner has already painted on screen - so "end of
     * program" means the end the viewer can see.
     */
    private fun currentProgramEndMs(): Long? {
        if (!isLiveChannel) return null
        val channel = currentZapChannel() ?: return null
        val now = zapEpgCache[zapEpgCacheKey(channel)]?.now ?: return null
        return now.endUtcMillis.takeIf { it > System.currentTimeMillis() }
    }

    /**
     * Arms (or clears) the timer. A fade already in progress is undone here:
     * "Off" pressed during the last twenty seconds of a timer has to bring the
     * sound back rather than silence the rest of the film.
     */
    internal fun chooseSleepTimer(option: SleepTimerOption) {
        SleepTimer.select(
            option = option,
            nowMs = System.currentTimeMillis(),
            programEndMs = currentProgramEndMs()
        )
        sleepFadeGain = 1f
        exoPlayer?.volume = 1f
        sleepTimerSection?.refresh()
    }

    /**
     * The sleep timer, driven from the 1s position tick: the last
     * [SLEEP_FADE_MS] fade to silence, then the session stops.
     *
     * Enforced from that tick rather than from a handler of its own because it
     * is already the player's once-a-second heartbeat - one clock, so the fade
     * cannot drift away from the position the timer is stopping.
     */
    private fun enforceSleepTimer(player: Player) {
        val state = SleepTimer.state.value
        if (!state.isArmed) {
            restoreSleepFade(player)
            return
        }
        // "End of episode" has no deadline to count: it is honored where the
        // episode actually ends (see onPlaybackEnded).
        if (state.stopsAtEndOfItem) return
        val remaining = (state.deadlineMs ?: return) - System.currentTimeMillis()
        val gain = sleepFadeGain(remaining)
        if (gain != sleepFadeGain) {
            sleepFadeGain = gain
            player.volume = gain
        }
        if (remaining > 0L) return
        exitForSleepTimer(savePartialProgress = true)
    }

    private fun restoreSleepFade(player: Player?) {
        if (sleepFadeGain == 1f) return
        sleepFadeGain = 1f
        player?.volume = 1f
    }

    /**
     * Leaves the player for a sleep timer that fired, or for one armed to stop
     * at the end of what was playing.
     *
     * It finishes rather than pausing: a stopped player left on screen keeps
     * the box decoding a static frame until the TV's own idle timer gives up,
     * and "we are done for tonight" is what the viewer asked for. The binge
     * chain is disarmed first, so an Up Next countdown cannot start the next
     * episode from behind a session that is already on its way out.
     *
     * [savePartialProgress] is false on the end-of-episode path, where the
     * caller has already recorded the title as finished: saving again from here
     * would overwrite that completion with the resume point it was just
     * reported as.
     */
    private fun exitForSleepTimer(savePartialProgress: Boolean) {
        SleepTimer.cancel()
        nextUpHandoffArmed = false
        nextUpCountdownHeld = false
        nextUpCountdownHandler.removeCallbacks(nextUpCountdownRunnable)
        sleepFadeGain = 1f
        exoPlayer?.let { player ->
            player.volume = 1f
            player.pause()
        }
        if (savePartialProgress) saveProgress(reason = "sleep_timer")
        mediaSession?.release()
        mediaSession = null
        android.widget.Toast
            .makeText(this, "Sleep timer - playback stopped", android.widget.Toast.LENGTH_SHORT)
            .show()
        finish()
    }

    // --- PiP ---
    /**
     * Called from onUserLeaveHint and onPause, so a Home press pops the picture
     * into the corner instead of leaving playback. The guards - finishing or
     * destroyed, Fire TV, no system feature, and the viewer's own setting - live
     * in the shared helper, so both engines enter PiP under the same rules (see
     * PipSupport).
     */
    private fun enterPipIfEnabled() {
        // The same latches [resumeAfterBackgroundReturn] reads as "handing
        // over": the successor activity starts before this one's onStop, and a
        // PiP window here would skip that onStop teardown entirely and leave a
        // stopped player floating over the session that took the screen.
        enterPipMode(
            this,
            handingOver = mpvHandoffStarted || externalHandoffStarted || nextEpisodeHandoffStarted
        )
    }


    override fun onUserLeaveHint() { super.onUserLeaveHint(); enterPipIfEnabled() }

    /**
     * Playback torn down by [onStop] comes back here.
     *
     * [shouldRebuildAfterStop] decides whether a return owes a rebuild at all,
     * and carries the why; this is what the rebuild is made of. It happens at
     * the position [onStop] carried in [carryPositionMs] - [createPlayer] seeks
     * to it as it prepares, so nothing starts at 0 and jumps. The splash comes
     * back with it, because this IS a load: a session that has to refill its
     * buffer is not the mid-playback rebuffer the small spinner exists for, and
     * [markFirstFrameRendered] takes it down again on the first painted frame.
     * [hasPlayedOnce] goes back with it, exactly as a source switch resets it,
     * so the load gets the splash rather than the spinner.
     */
    private fun resumeAfterBackgroundReturn() {
        val owesRebuild = shouldRebuildAfterStop(
            finishing = isFinishing,
            destroyed = isDestroyed,
            tornDownAtStop = playerTornDownAtStop,
            playerPresent = exoPlayer != null,
            handingOver = mpvHandoffStarted || externalHandoffStarted || nextEpisodeHandoffStarted
        )
        // Spent either way: the marker describes ONE stop, so a return that
        // could not rebuild (a handover, say) must not leave it set to fire
        // against the next one.
        playerTornDownAtStop = false
        if (!owesRebuild) return
        // This screen holds the decoder again, so the bulk background work that
        // waits on the gate (guide writes, the home catalog rebuild) goes back
        // to waiting: onStop handed the screen to whatever sits behind it.
        EpgWriteGate.setPlayerActive(true)
        Log.i(
            "PLAYER_REBUILD",
            "back on screen after a stop: rebuilding playback at ${carryPositionMs}ms"
        )
        hasPlayedOnce = false
        showSplash()
        setupIntroDb()
        recreatePlayer()
    }

    override fun onStart() {
        super.onStart()
        resumeAfterBackgroundReturn()
    }

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
        // Playback is over for this SCREEN, so the panel goes back to the mode
        // the rest of the TV interface expects - but only when this session is
        // actually ending. A stop is not always an exit: a pause that turns the
        // screen off, a brief trip away, or an engine switch (which stops this
        // activity without finishing it) all come back to the same playback.
        // Restoring there made the resume re-request the film rate — a full
        // 60 → 24 switch racing the player rebuild, where the open-time switch
        // happens before decode and is smooth (field, 2026-10-18). Leaving the
        // panel alone lets the re-arm find the best mode already engaged, which
        // is a true no-op. Deliberately not in onPause: Picture-in-Picture leaves
        // the video running, and the matched rate is still the right one there.
        // A confirm still waiting is dropped either way: a backstop posted at
        // open has no business firing while the player is not drawing.
        if (isFinishing) frameRateMatcher?.release() else frameRateMatcher?.cancelPending()
        // Release session & player early so the next NativePlayerActivity
        // doesn't collide with a stale MediaSession ID.
        handler.removeCallbacksAndMessages(null)
        nextUpCountdownHandler.removeCallbacks(nextUpCountdownRunnable)
        // These are NOT owned by `handler`, so clearing `handler` above left
        // them running: a session backgrounded with the overlay still up kept
        // its once-a-second clockRunnable re-posting itself forever - a Handler
        // message holding this Activity, plus a view write a second on a dead
        // surface. showControls() re-arms the clock on return, so stopping it
        // here costs nothing. The scrub/zap timers go the same way.
        clockHandler.removeCallbacks(clockRunnable)
        scrubHandler.removeCallbacksAndMessages(null)
        zapHandler.removeCallbacksAndMessages(null)
        // The channel-number commit is on its own handler as well, and it was
        // only ever cleared in onDestroy: a digit typed inside the 1.2 s
        // debounce window and then backgrounded fired commitChannelNumberEntry
        // -> tuneToChannel -> switchToSource -> recreatePlayer against a
        // stopped activity, rebuilding a player this onStop had just released.
        channelNumberHandler.removeCallbacks(channelNumberCommitRunnable)
        channelNumberHandler.removeCallbacks(channelNumberHudHideRunnable)
        // The guide's long-press timer lives on its own handler too: a hold
        // that outlasted the screen would otherwise fire showChannelGuide()
        // against a stopped activity.
        channelGuideHandler.removeCallbacks(guideLongPressRunnable)
        guideLongPressArmed = false
        // A guide prefetch still sitting on its debounce -- or already on the
        // wire -- has no business firing from a screen that is leaving (see
        // LiveChannelPrefetch).
        liveChannelPrefetch.release()
        scrubDirection = 0
        // Remember where playback actually was: onSaveInstanceState() can run
        // after this method (API 28+) and the player is released by then.
        if (!isLiveChannel && firstFrameRendered) {
            // Gated on the frame: before the first frame the player clock is
            // still on the launch position, so this would overwrite a real carry
            // with the frozen start (see the other carry writes).
            carryPositionMs = exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: carryPositionMs
        }
        // Save progress BEFORE canceling scope: saveProgress writes via
        // lifecycleScope, which is independent of `scope`, but ordering it
        // ahead of teardown keeps intent clear and avoids racing any
        // scope-bound work that reads history.
        // A session that reached its own end - or that was already showing its
        // end-of-episode card in the last minutes of the episode - must never
        // be recorded as merely resumable. After ENDED the saved position can
        // sit short of the completion threshold (a frozen tail, or the end
        // panel having paused the clock), and leaving from the card happens
        // before the file's last second - both left the finished episode
        // without its watch marker and with its old progress bar.
        // A session being continued in another engine - the MPV switch/fallback
        // or an installed external player - is NOT ending here. That target
        // opens the file and scrobbles its own "start", and a "stop" from this
        // engine landed right after it: Simkl ends the session on the first
        // stop, so the target's real stop (at 100%) then answered 409 "already
        // ended" and the title was never marked watched. One session, one stop
        // - sent by the engine that actually finished it.
        // Not when the end-of-episode card already filed this episode and
        // closed its tracker session ([fileEpisodeForHandoff]): that write
        // happened while the player was still alive and before the next
        // episode's scrobble "start", which is exactly what onStop cannot
        // promise here.
        if (!mpvHandoffStarted && !externalHandoffStarted && !nextEpisodeHandoffStarted) {
            val completedOnExit = shouldRecordCompletion(
                playbackEnded = playbackEndedHandled || playbackEndReached,
                endPanelsShown = endPanelsShown,
                positionMs = exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: carryPositionMs,
                durationMs = exoPlayer?.duration
                    ?.takeIf { it > 0L && it != C.TIME_UNSET }
                    ?: 0L,
                played = sessionHasPlayed(),
                completionFiled = completionFiled
            )
            // Into the session trace: the exit save is the row Continue
            // Watching (or the watched marker) reads next, and it was the one
            // write the ring never recorded - a dump showed the handoff's
            // `filed` line and then nothing about where the episode was left
            // (audit S-I11, 2026-10-27).
            PlaybackSessionTrace.note(
                "exit save: s=${season?.toString() ?: "-"} e=${episode?.toString() ?: "-"} " +
                    "completed=$completedOnExit reason=stop"
            )
            saveProgress(reason = "stop", forceCompleted = completedOnExit)
            scrobbleSimkl("stop")
        }
        // A guide left open when the player is backgrounded had its scope
        // cancelled underneath it but the overlay stayed up, so on return it
        // showed stale program rows with a dead watcher (a permanent "…").
        // Dismiss it as part of leaving this screen, before the scope goes.
        dismissChannelGuide()
        scope?.cancel()
        subtitleCueHandler?.cancelPending()
        subtitleCueHandler = null
        // The native libass instance goes with the player; the script stays so
        // the rebuilt player picks the sidecar back up.
        releaseAssRenderer()
        exoPlayer?.release()
        exoPlayer = null
        // Marked with the release, not before it: this is the stop that took
        // the screen's player with it, and [resumeAfterBackgroundReturn] is
        // what answers it when the viewer comes back to a screen that is still
        // here. Cleared as that rebuild runs, and never set on a session that
        // is merely being re-created: a new instance gets a new field.
        playerTornDownAtStop = true
        mediaSession?.release()
        mediaSession = null
    }

    override fun onDestroy() {
        // Back to browsing, in whatever orientation the phone is now held.
        // Released before the superclass tears the window down, while the
        // activity can still take the request.
        com.kennyb1201.kbstream.FormFactor.releasePlaybackOrientation(this)
        super.onDestroy()
        // Safety net for a player that never reached onStop's counterpart:
        // leaving this set would hold guide writes back forever.
        EpgWriteGate.setPlayerActive(false)
        // release() is a no-op once onStop has already put the panel back, so
        // this is only the safety net for a player destroyed without stopping.
        frameRateMatcher?.release()
        frameRateMatcher = null
        p5VideoGlesView.release()
        sleepTimerSection?.release()
        handler.removeCallbacksAndMessages(null)
        scrubHintHandler.removeCallbacksAndMessages(null)
        channelNumberHandler.removeCallbacksAndMessages(null)
        nextUpCountdownHandler.removeCallbacks(nextUpCountdownRunnable)
        clockHandler.removeCallbacks(clockRunnable)
        scrubHandler.removeCallbacksAndMessages(null)
        zapHandler.removeCallbacksAndMessages(null)
        scope?.cancel()
        subtitleCueHandler?.cancelPending()
        subtitleCueHandler = null
        releaseAssRenderer()
        exoPlayer?.release()
        exoPlayer = null
        lanePool?.release()
        lanePool = null
        mediaSession?.release()
        mediaSession = null
    }

    /**
     * One fetch lane's OkHttp stack (see StreamFetchLanes.kt).
     *
     * Deliberately NOT the shared [httpClient]: lanes exist to have separate
     * connection pools, and a lane reusing that client would sit on the same
     * HTTP/2 connection and buy nothing. Timeouts match the shared client's, so
     * a lane behaves like every other read this app does.
     */
    private fun oneLaneHttpClient(): okhttp3.OkHttpClient =
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()


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
    fun switchToSource(stream: Stream, isAutoRecovery: Boolean = false) {
        val newUrl = stream.url ?: return
        if (newUrl == currentUrl) return
        // Carry the playhead only when the old source actually rendered
        // something. A source that never produced a frame (black video,
        // endless buffering, the error card) still runs its wall clock while
        // the viewer waits, so carrying currentPosition starts the new source
        // 30-60s in. With no frame rendered, the new source starts where the
        // session was asked to start: a resume point survives, a fresh start
        // begins at 0. [firstFrameRendered] is read here BEFORE
        // [recreatePlayer] runs, so it is still the old source's latch;
        // [createPlayer] resets it per attempt and the new source re-arms it
        // on its own first frame.
        carryPositionMs = if (isLiveChannel) {
            0L
        } else if (firstFrameRendered) {
            exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L
        } else {
            // No frame yet on the source being left, so its clock cannot be
            // trusted - but the last KNOWN carry can (usually the previous
            // source's real position). `startPositionMs` is frozen at launch,
            // so falling back to it restarted a multi-source fallback at 0.
            carryPositionMs.coerceAtLeast(0L)
        }
        currentSourceLabel = stream.displayLabel()
        currentBadges = stream.badges
        currentBingeGroup = stream.bingeGroup
        resolveAddonIdentity(currentSourceLabel)
        // Into the session trace: a source switch is one of the events a binge
        // report has to explain ("the same episode played from a different
        // provider"), and only the engine ring had it - the session ring carried
        // the session line and the handoff's `filed` line and nothing between
        // (audit S-I11, 2026-10-27).
        PlaybackSessionTrace.note(
            "source switch: s=${season?.toString() ?: "-"} e=${episode?.toString() ?: "-"} " +
                "index=$currentSourceIndex/${sources.size} auto=$isAutoRecovery " +
                "label=$currentSourceLabel"
        )
        currentUrl = newUrl
        currentAudioUrl = stream.audioUrl
        // A different source is a different question, so the rebuild budget
        // starts over with it (see [RebuildBudget]). It is keyed by the FAILURE
        // cause, which is the same string on every source: two decoder-resource
        // rebuilds on the source the viewer just left used to leave the next
        // one with zero — the ladder gave up on a file it had never tried, and
        // the only lever left was the viewer pressing RETRY.
        rebuildBudget.reset()
        // The switched source brings its OWN request headers. These used to
        // stay at whatever the activity launched with, so a host gated on a
        // Referer / User-Agent answered the new request with the previous
        // source's headers - a refusal, or a throttled variant - and the
        // player ran the whole fallback ladder before giving up. Starting
        // the same source fresh (from the streams picker) used the right
        // headers and played, which is the "switching sources in the player
        // is broken" report. Read them off this stream, exactly as the
        // launch intent does (see requestHeaders on Stream).
        streamHeaders = stream.requestHeaders
        // The previous source's decoded identity does not describe this one.
        // Left behind, it only mislabelled the next failure's copy ("This
        // file's video (h264 3840x2160) can't be decoded") with the old
        // stream's codec and size; a fresh source's own track callback
        // re-establishes both.
        streamCodec = null
        streamWidth = 0
        streamHeight = 0
        streamBitrate = 0
        measuredVideoBytes = 0L
        currentSourceIndex = sources.indexOfFirst { it.url == newUrl }
        // The downshift window is per source: the one just left cannot make the
        // next one look like it is already stalling, and a source switch is a
        // fresh start for a ladder that had been given up on (see
        // RebufferDownshift.kt).
        rebufferDownshift.reset()
        rebufferDownshiftGivenUp = false
        retryAttempt = 0; retryExhausted = false; errorMessageStr = null; forceTextureViewFallback = false; languagesAutoSelected = false
        dvStripRetryDone = false; forceDvStripForSession = false
        decoderResourceFallbackDone = false
        decoderFailureRetried = false
        // Per source too: left set by a previous container-parse failure, the
        // NEXT source's own parse failure skipped its one raw-extractor probe
        // and went straight to the MPV handoff.
        containerParseRetried = false
        // Per-source Dolby Vision identity. These used to survive into the
        // next player build, so a P5 title followed by any other title kept
        // the P5 GL color path "on": the shader then applied ICtCp math to
        // HDR10 pixels (wrong colors), and for AVC nothing can render into
        // the buffer output while the player view is hidden (no video at
        // all). A fresh source starts with no DV identity and its own track
        // callback re-establishes it.
        currentCodecs = null
        streamDeclaredDvCodec = null
        // The keep-DV annotation is per track too: a P8 title's fallback label
        // surviving into the next source is the same stale-identity class of
        // bug the two lines above exist for.
        dvRpuStripKeepDvActive = false
        // A manual source switch is a fresh, actively-playing load — drop
        // the actor-return pause semantics so the new source starts
        // playing like any other switch.
        fromActorReturn = false
        actorReturnOverlayShown = true
        // A MANUAL source switch is a fresh load, not a mid-playback
        // rebuffer: reset the first-play latch so the full splash (backdrop
        // + pulsing clearlogo) shows during the load instead of the small
        // spinner. The actor-return gate still wins — those sessions keep
        // the spinner. An AUTOMATIC switch mid-show (error ladder, rebuffer
        // downshift) is not a fresh load: the viewer is already watching, so
        // keep the latch and let the small spinner + the reconnecting banner
        // tryNextSource raised carry the switch instead of covering the video
        // with the splash.
        if (!isAutoRecovery || !hasPlayedOnce) {
            hasPlayedOnce = false
            if (!fromActorReturn) {
                showSplash()
            }
        }
        dismissPicker()
        // A live channel change does not need a new player: the running
        // ExoPlayer, already bound to this Surface, can take the next channel's
        // MediaItem and re-init its single video renderer in place - the path
        // it runs for any playlist advance. [recreatePlayer] and its
        // [SOURCE_SWITCH_SETTLE_MS] wait exist only because a SECOND player
        // configuring onto a Surface the outgoing decoder still holds comes
        // back OMX_ErrorInsufficientResources on the Realtek/TCL stack; with no
        // second player there is nothing to wait out. VOD switches and the
        // error ladders still rebuild.
        if (isLiveChannel && exoPlayer != null) {
            lightSwitchLiveChannel()
        } else {
            recreatePlayer(settleMs = SOURCE_SWITCH_SETTLE_MS)
        }
    }

    /**
     * Hands the running player the current source without rebuilding it - the
     * live-zap fast path (see [switchToSource]).
     *
     * The Surface is deliberately left attached ([androidx.media3.ui.PlayerView.player]
     * is not touched): the point is that nothing is torn down. The one video
     * renderer releases its codec and re-initialises on the playback thread in
     * sequence, which is ExoPlayer's ordinary playlist path and what the rest of
     * the TV ecosystem does on these boxes. If a box ever does refuse the
     * in-place re-init, the existing decoder-failure ladder
     * ([decoderResourceFallbackDone], [decoderFailureRetried], and the
     * black-video watchdog's own rebuild) recovers it into the very rebuild a
     * zap used to always pay for - not a dead card.
     *
     * [tuneToChannel]'s banner, spinner and recall logic are left exactly as
     * they are; this only changes what happens to the player underneath them.
     */
    private fun lightSwitchLiveChannel() {
        val player = exoPlayer ?: return
        // A rebuild queued by an earlier recreatePlayer must not run on top of
        // this switch: bumping the generation makes it a no-op, exactly as
        // recreatePlayer's own bump does.
        playerGeneration++
        // The outgoing channel's watchdogs are stale the moment it is left.
        // (The startup and stall watchdogs only arm on VOD; the live watchdog
        // is the one that can be pending here.)
        stallWatchdogToken++
        liveWatchdogToken++
        // [armBlackVideoWatchdog] early-returns while [firstFrameRendered] is
        // true, and the Dolby Vision re-route gate checks the same flag. A zapped
        // channel therefore re-armed neither: a channel that came up with audio
        // but no video stayed permanently black with no recovery ladder. Reset
        // both so this channel's READY re-arms the watchdog and its first frame
        // re-sets the flag (see [markFirstFrameRendered]) and its video track is
        // re-reported (see the onTracksChanged handler that sets
        // [videoTrackPresent]).
        firstFrameRendered = false
        videoTrackPresent = false
        // The cue handler holds cues from the channel being left and is a
        // listener on the player that survives here (it does not on a rebuild).
        // Detach it and add a fresh one to the same player, mirroring how
        // createPlayer wires it.
        subtitleCueHandler?.let { cueHandler ->
            cueHandler.cancelPending()
            player.removeListener(cueHandler)
        }
        subtitleCueHandler = SubtitleCueHandler().also { player.addListener(it) }
        // The same MediaItem createPlayer() would have built for this URL,
        // handed to the running player. prepare() restarts the load; the Surface
        // is already attached, so there is no re-attach and no settle.
        val mimeType = resolveMimeType(currentUrl)
        player.setMediaItem(newMediaItemBuilder(mimeType).build(), 0L)
        player.prepare()
        player.playWhenReady = true
    }

    /**
     * Counts a finished mid-playback rebuffer and, once the current source has
     * stacked enough of them, hands the session to the next-ranked source.
     *
     * See [RebufferDownshift.kt] for why this exists. Returns true only when a
     * switch actually started, so the caller skips the rest of the READY
     * handling for a player that is already being replaced.
     */
    private fun maybeDownshiftOnRebuffer(rebufferStartMs: Long, stalledMs: Long): Boolean {
        if (rebufferDownshiftGivenUp) return false
        // A stall the seek itself caused is not evidence the source is slow.
        if (rebufferFollowsSeek(rebufferStartMs, lastSeekAtMs)) return false
        // Only a real stall counts, and only while the viewer is actually
        // playing: a paused actor-return session keeps its frame and must not be
        // yanked to another source.
        if (stalledMs < REBUFFER_DOWNSHIFT_MIN_STALL_MS) return false
        if (isLiveChannel) return false
        if (exoPlayer?.playWhenReady != true) return false
        if (reconnectingContainer.visibility == View.VISIBLE) return false
        // Pool pressure, not a slow source: a stall inside the decoder
        // exhaustion window is the box not having released a decoder yet, and a
        // switch is a rebuild is another ask for that same decoder. Hold the
        // source and let the pool recover on its own (see DecoderPoolHealth).
        if (DecoderPoolHealth.exhaustedRecently()) {
            Log.w(
                "PLAYER_STALL",
                "Rebuffer ${stalledMs}ms inside the decoder-exhaustion window — holding " +
                    "the source and letting the decoder pool recover"
            )
            return false
        }
        val now = System.currentTimeMillis()
        rebufferDownshift.record(now)
        if (!rebufferDownshift.due(now)) return false
        val stalledCount = rebufferDownshift.count(now)
        Log.w(
            "PLAYER_STALL",
            "Source rebuffered $stalledCount times in ${REBUFFER_DOWNSHIFT_WINDOW_MS / 60_000}min " +
                "— downshifting to the next source"
        )
        PlaybackEngineTrace.note(
            PlaybackEngineTrace.describe(
                cause = "source could not keep up",
                detail = "$stalledCount rebuffers, last ${stalledMs}ms, " +
                    "${currentSourceLabel ?: "unknown source"}"
            )
        )
        // Which addon this source came from, read BEFORE the switch: switching
        // re-resolves the on-screen identity to the next source's addon, and
        // the verdict belongs to the one that stalled.
        val stalledAddon = currentAddonName
        val switching = tryNextSource(
            statusText = "This source keeps stalling — trying the next one…",
            // Automatic recovery: the merged walk, across add-ons.
            tab = null
        )
        // The window is spent either way: a switch starts the next source
        // fresh, and with no rung left there is nothing more to reach for, so
        // stop re-counting the stalls this session keeps producing.
        rebufferDownshift.reset()
        if (!switching) {
            rebufferDownshiftGivenUp = true
        } else {
            // A switch actually started, so this source has failed to keep up
            // for this title - the fact the next episode's list is missing
            // (see SourceAddonMemory). The in-session behavior is untouched:
            // this only remembers the verdict for next time. No live guard is
            // needed - live channels returned above.
            com.kennyb1201.kbstream.data.player.SourceAddonMemory.rememberStalled(
                this,
                parentId,
                stalledAddon
            )
        }
        return switching
    }

    /**
     * Moves to the next ranked source. [delayMs] lets a caller leave the
     * platform time to tear its video decoder down first (checking the
     * decoder pool showed the box returning no codec at all for ~15s after a
     * failure), and [statusText] replaces the generic "Trying next source"
     * line when the reason is worth naming.
     *
     * [tab] is the SCOPE, and every call site passes it rather than leaning on a
     * default: null is the MERGED walk, which is what every automatic recovery
     * here uses so it can cross add-ons. A name confines the walk to that
     * add-on's rows (see [SourceAddonSession.tabbedNextIndex]) - the shape the
     * MPV engine's manual TRY NEXT SOURCE uses; this engine has no manual
     * advance, so nothing passes one, but a future caller that does must say so
     * out loud.
     */
    private fun tryNextSource(
        delayMs: Long = 0L,
        statusText: String? = null,
        tab: String? = null
    ): Boolean {
        if (autoSourceSwitchCount >= MAX_AUTO_SOURCE_SWITCHES) return false
        // Which source is next, not how many tries are allowed: the first
        // remaining source whose addon is not dead this session, else plain
        // order (see SourceAddonSession.nextIndex). Both bounds above and below
        // are unchanged.
        //
        // Rows this engine cannot open are STEPPED PAST rather than handed to
        // switchToSource. A hash row (P2P, no direct URL) is resolved by the
        // picker and by nothing in here, so `switchToSource`'s own
        // `stream.url ?: return` turned the walk into a silent no-op that still
        // reported success: the callers below returned believing the session had
        // moved, the "Trying next source…" banner sat over a dead player, and
        // the error card and the MPV handoff were never reached (audit S-F1,
        // 2026-10-27). Only a row this engine can actually open counts as the
        // next source, and when there is none the walk answers false so the
        // caller falls through to the card.
        var fromIndex = currentSourceIndex + 1
        var nextIndex: Int? = null
        if (tab != null) {
            // A tab-scoped advance: only this add-on's rows, stepped past when
            // this session found the add-on dead or the row carries no direct
            // URL (see SourceAddonSession.tabbedNextIndex).
            nextIndex = SourceAddonSession.tabbedNextIndex(
                addons = sourceAddons,
                fromIndex = fromIndex,
                dead = sessionDeadAddons,
                tab = tab
            ) { i ->
                val url = sources.getOrNull(i)?.url
                !url.isNullOrBlank()
            }
        } else {
            while (true) {
                val candidate = SourceAddonSession.nextIndex(
                    addons = sourceAddons,
                    fromIndex = fromIndex,
                    dead = sessionDeadAddons
                ) ?: break
                if (!sources[candidate].url.isNullOrBlank()) {
                    nextIndex = candidate
                    break
                }
                Log.w(
                    "PLAYER_VIDEO",
                    "skipping source ${candidate} with no direct URL (hash row)"
                )
                fromIndex = candidate + 1
            }
        }
        val index = nextIndex ?: return false
        val nextStream = sources[index]
        autoSourceSwitchCount++
        Log.w(
            "PLAYER_VIDEO",
            "Auto-switching to next source: ${nextStream.displayLabel()} (index=$index/${sources.size})"
        )
        reconnectingContainer.visibility = View.VISIBLE
        hideBufferingSpinner()
        reconnectingText.text = statusText ?: "Trying next source: ${nextStream.displayLabel()}…"
        if (delayMs > 0L) {
            handler.postDelayed({
                errorMessageStr = null
                switchToSource(nextStream, isAutoRecovery = true)
            }, delayMs)
        } else {
            switchToSource(nextStream, isAutoRecovery = true)
        }
        return true
    }

    /**
     * Mechanism A on this engine: probe the head of the ranked list before the
     * player is built for the first time, so the session never spends a full
     * open timeout on a link the probe already knows is dead.
     *
     * The pick's add-on is marked dead for the session, which is exactly what
     * the advance after it needs (see [SourceAddonSession]); the chosen source
     * then loads normally through [createPlayer].
     *
     * Only the INITIAL build is wrapped - a rebuild ([recreatePlayer]) is not a
     * fresh launch, and live channels have a single zap source with nothing to
     * choose between.
     *
     * On any probe infrastructure failure the top-ranked source loads as today.
     */
    private fun probeThenCreatePlayer() {
        // Session start, and the only place the auto-source-switch budget is
        // reset: [MAX_AUTO_SOURCE_SWITCHES] bounds how many sources ONE session
        // walks before it must raise the card or hand off, and this function is
        // called exactly once per session - the initial build. Rebuilds
        // (recreatePlayer, and therefore every switch) deliberately leave the
        // count alone (audit S-F3, 2026-10-27).
        autoSourceSwitchCount = 0
        // With fewer than two sources there is nothing to choose between, and
        // the probe only ever decides WHICH source to open.
        if (isLiveChannel || sources.size < 2) {
            createPlayer()
            return
        }
        val probe = SourcePlaybackProbe(sessionDeadAddons, httpClient)
        lifecycleScope.launch {
            val (candidates, addons) = probeOrder()
            // Cancellable: the probe SUSPENDS, and this launch is the
            // activity's own scope. Swallowed, a cancellation arriving
            // mid-probe was read as a probe INFRASTRUCTURE failure - `pick`
            // null - and the launch carried on down the head-source path; the
            // guard below only stands in for that when the activity happens to
            // be finishing. Rethrown, the launch dies with the scope, which is
            // what a canceled session means.
            val pick = runCatchingCancellable {
                probe.pickLiveSource(candidates, addons) { stream -> stream.requestHeaders }
            }.getOrNull()
            if (isFinishing || isDestroyed) return@launch
            // Only a source the probe actually proved live moves the session;
            // a null pick (a probe infrastructure failure) builds the player on
            // the head of the list exactly as before. The chosen initial source
            // is pinned to the head of [probeOrder], so the pick staying on it
            // means the viewer's explicit choice was seen live; the override
            // below now only fires when that pick itself was dead.
            if (pick != null && !pick.url.isNullOrBlank() && pick.url != currentUrl) {
                Log.w(TAG, "probe picked a different head source: ${pick.displayLabel()}")
                adoptInitialSource(pick)
            } else if (pick != null && pick.url == currentUrl) {
                Log.i(TAG, "probe: initial source live, keeping: ${pick.displayLabel()}")
            }
            createPlayer()
        }
    }

    /**
     * The order this launch's probe tests sources in: the session's initial
     * source first, then the rest in rank order (see [probeCandidates]). A
     * "Play manually" tap or a played-link cache hit hands the player an
     * explicit [currentUrl] plus the full ranked list; without the pin the
     * probe could override the choice with a higher-ranked source.
     */
    private fun probeOrder(): Pair<List<Stream>, List<String?>> =
        probeCandidates(sources, sourceAddons, currentUrl, currentAudioUrl, streamHeaders)

    /**
     * Makes [stream] the source this session is about to open, before any
     * player exists: the same fields [switchToSource] moves across a mid-playback
     * switch, minus everything that belongs to a player being torn down.
     */
    private fun adoptInitialSource(stream: Stream) {
        val url = stream.url ?: return
        currentUrl = url
        // Nothing has spent the budget yet at this point, but the adoption is
        // the same "the source changed" fact the switch above resets on, and it
        // is where a session that opens a DIFFERENT source than it launched
        // with (the pinned-candidate path, a picker choice) lands.
        rebuildBudget.reset()
        currentAudioUrl = stream.audioUrl
        streamHeaders = stream.requestHeaders
        currentSourceLabel = stream.displayLabel()
        currentBadges = stream.badges
        currentBingeGroup = stream.bingeGroup
        resolveAddonIdentity(currentSourceLabel)
        currentSourceIndex = sources.indexOfFirst { it.url == url }
    }

    // --- PlayerChromeHost: the shared overlay's calls into this engine --------
    //
    // A rename of the calls the control bar already made by hand: play / pause,
    // next, the pickers, aspect, settings, info, the engine switch and the skip
    // prompt all reach the same methods they always did. The chrome wires each
    // button once; nothing here is new player logic.

    override fun chromeIsPlaying(): Boolean = exoPlayer?.isPlaying == true

    override fun chromePositionMs(): Long = exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L

    override fun chromeDurationMs(): Long {
        val raw = exoPlayer?.duration ?: 0L
        return if (raw > 0L && raw != C.TIME_UNSET) raw else 0L
    }

    override fun onChromePlayPause() = togglePlayPause()

    override fun onChromeSeekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
    }

    /**
     * A touch drag on the bar begins: pause for it, exactly as the D-pad scrub
     * does, so the drag's follow-seeks have no playback deadline to miss (see
     * [beginScrubPause]).
     */
    override fun onChromeScrubStart() = beginScrubPause()

    /**
     * Follow the drag's thumb, throttled to [TOUCH_FOLLOW_SEEK_MS]. The drag
     * fires progress changes far faster than a slow stream settles a seek; only
     * the window's first one seeks, and the release's exact seek is still the
     * landing (see [onChromeSeekTo]).
     */
    override fun onChromeScrubProgress(positionMs: Long) {
        val player = exoPlayer ?: return
        if (player.duration <= 0L) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastFollowSeekMs < TOUCH_FOLLOW_SEEK_MS) return
        lastFollowSeekMs = now
        player.seekTo(positionMs)
    }

    /** The drag is over: put the play state back (see [endScrubPause]). */
    override fun onChromeScrubEnd() = endScrubPause()

    override fun onChromeNext() = advanceToNextEpisode()

    override fun onChromeOpenSourcePicker() = showPicker(PickerMode.SOURCE)

    override fun onChromeOpenAudioPicker() = showPicker(PickerMode.AUDIO)

    override fun onChromeOpenSubtitlePicker() = showPicker(PickerMode.SUBTITLE)

    override fun onChromeOpenSpeedPicker() = showPicker(PickerMode.SPEED)

    override fun onChromeOpenAspectPicker() {
        resizeModeIndex = (resizeModeIndex + 1) % ASPECT_MODES.size
        applyAspectMode(resizeModeIndex)
        btnAspect.text = ASPECT_MODES[resizeModeIndex]
        PlayerTitlePrefs.remember(
            this,
            titleKey,
            (PlayerTitlePrefs.get(this, titleKey) ?: PlayerTitlePrefs.Prefs())
                .copy(aspectRatio = resizeModeIndex)
        )
    }

    override fun onChromeOpenSettings() = toggleSettingsPanel()

    override fun onChromeOpenInfo() = toggleInfoPanel()

    override fun onChromeSwitchPlayer() = switchPlayerManually()

    override fun onChromeOpenExternal() {
        // A press is a question, so a refusal has to say why rather than look
        // like a dead button - the same rule as the SWITCH buttons (see
        // installManualSwitchFeedback).
        if (!handOffToExternal()) {
            Toast.makeText(this, "Can't open another player right now", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onChromeSkipIntro() {
        val stamp = activeIntroStamp ?: return
        val durationMs = exoPlayer?.duration ?: 0L
        // An open-ended segment ends where the file does, so its target IS the
        // runtime; with the runtime unknown there is no target to name.
        val endMs = AutoSkipRules.segmentEndMs(stamp, durationMs) ?: return
        val targetMs = if (durationMs > 0L && durationMs != C.TIME_UNSET) {
            endMs.coerceAtMost(durationMs)
        } else {
            endMs
        }
        exoPlayer?.seekTo(targetMs)
        activeIntroStamp = null
        btnSkipIntro.visibility = View.GONE
    }

    override fun chromeTitleInfo(): ChromeTitleInfo = ChromeTitleInfo(
        clearLogoUrl = clearLogoUrl,
        itemName = itemName,
        episodeLabel = season?.let { s ->
            episode?.let { e ->
                "S${s.toString().padStart(2, '0')} · E${e.toString().padStart(2, '0')}"
            }
        },
        episodeTitle = episodeTitle,
        overview = overview,
        badges = currentBadges,
        cast = castMembers
    )

    // --- the shared bar's own timing and focus policy, this engine's answers ---

    override fun chromeMayAutoHide(): Boolean {
        // Don't auto-hide when paused - keep overlay visible. Live is the
        // exception: a live channel cannot be paused, and isPlaying reads false
        // for the whole buffering start, which would leave the overlay up for the
        // entire session.
        if (!isLiveChannel && exoPlayer?.isPlaying == false) return false
        // Don't auto-hide while a panel is open: hiding the overlay mid-
        // navigation tears down the panel's focus and drops the user's spot.
        if (showSettingsPanel || isPickerShowing || isGuideShowing) return false
        return true
    }

    override fun onChromeAutoHide(): Boolean {
        // This engine's teardown is more than hiding the overlay - it ends a
        // scrub and dismisses panels - so it owns the whole response.
        hideControls()
        return true
    }

    override fun chromeMayTakeFocus(): Boolean =
        infoPanel.visibility != View.VISIBLE && !showSettingsPanel && !isPickerShowing

    override fun chromeSeekBarScrubbable(): Boolean = !isLiveChannel

    override fun chromeFocusPrimary(): Boolean {
        focusControlsPrimary()
        return true
    }

    companion object {

        private val sessionSequence = java.util.concurrent.atomic.AtomicInteger(0)

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

        /**
         * The HTTP status behind a failed open, or null when the failure carried
         * no response at all (a dropped connection has no status to report).
         * Media3 attaches it to the cause chain, not to the
         * [PlaybackException] itself.
         */
        private fun httpStatusOf(error: PlaybackException): Int? {
            var cause: Throwable? = error
            var hops = 0
            while (cause != null && hops < 8) {
                val code = androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException::class
                if (code.isInstance(cause)) {
                    return (cause as androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)
                        .responseCode
                }
                cause = cause.cause
                hops++
            }
            return null
        }

        /**
         * The host of [url], or null when it has none (a local file, or a url
         * that will not parse).
         *
         * Host only, never the whole url: a debrid resolve link carries its
         * token in the query string, and a diagnostics export is forwarded off
         * the device.
         */
        private fun hostOf(url: String?): String? = url
            ?.let { runCatching { java.net.URI(it).host }.getOrNull() }
            ?.takeIf { it.isNotBlank() }

        private fun friendlyErrorMessage(error: PlaybackException, host: String?): String = when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_TIMEOUT -> {
                // "No internet connection." was the blanket answer here, and the
                // diagnostics export has since falsified it: a capture taken
                // while this card was on screen had sync live, catalog
                // fetches still landing, and 13 sources retrieved from the very
                // add-on the stream came from. One host had failed, not the
                // television's network. Naming it is the difference between
                // "check your router" - wrong, and nothing the viewer can act
                // on - and "that source is down", which points at the one
                // button that helps.
                if (host == null) "No internet connection." else "Couldn't reach $host."
            }
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
                // The status is the diagnosis, and it is the one fact the server
                // itself handed us: 403 is a refused or expired resolve link,
                // 404/410 is a file that is gone, 451 is a rights block, 5xx is
                // the provider failing. "Server returned an error" without it
                // cannot be acted on.
                val status = httpStatusOf(error)
                if (status == null) {
                    "Server returned an error. Stream may be unavailable."
                } else {
                    "Server returned an error (HTTP $status). Stream may be unavailable."
                }
            }
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

/**
 * Parses the "Key: Value" header block carried in the launch intent.
 *
 * Stays file-private here rather than joining the other format helpers:
 * [MpvPlayerActivity] declares the same private helper, and a package-level
 * copy would be ambiguous at every call site in that file.
 */
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
