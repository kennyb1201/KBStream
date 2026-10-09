package com.kennyb1201.kbstream.ui.player

import android.annotation.SuppressLint
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import coil3.load
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kennyb1201.kbstream.R
import com.kennyb1201.kbstream.ui.home.looksLikeRawMediaId
import com.kennyb1201.kbstream.ui.theme.themeAccentColor
import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.data.addon.SubtitleEntry
import com.kennyb1201.kbstream.data.badges.StreamBadge
import com.kennyb1201.kbstream.data.cache.DiskSweep
import com.kennyb1201.kbstream.data.device.DeviceCapability
import com.kennyb1201.kbstream.data.namedEpisodeNumber
import com.kennyb1201.kbstream.data.player.EpisodeScheme
import com.kennyb1201.kbstream.data.player.EpisodeSchemeStore
import com.kennyb1201.kbstream.data.player.ExternalPlayer
import com.kennyb1201.kbstream.data.player.LanguageMatch
import com.kennyb1201.kbstream.data.player.PlayedLinkCache
import com.kennyb1201.kbstream.data.player.PlayerEngine
import com.kennyb1201.kbstream.data.player.PlayerTitlePrefs
import com.kennyb1201.kbstream.data.player.PlayerTrackMemory
import com.kennyb1201.kbstream.data.history.PlaybackHistoryWriter
import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import com.kennyb1201.kbstream.data.iptv.EpgWriteGate
import com.kennyb1201.kbstream.data.memory.MemoryPressure
import com.kennyb1201.kbstream.data.memory.releaseImageMemoryCache
import com.kennyb1201.kbstream.data.mdblist.MdbListClient
import com.kennyb1201.kbstream.data.simkl.SimklRepository
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.domain.streamengine.StreamRanker
import kotlinx.coroutines.withContext
import com.kennyb1201.kbstream.data.watched.ContinueWatchingRefreshBus
import com.kennyb1201.kbstream.data.youtube.TrailerPlayerPool
import com.kennyb1201.kbstream.data.settings.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import com.kennyb1201.kbstream.data.runCatchingCancellable

/**
 * Ceiling on an addon subtitle read back for validation. A subtitle is
 * kilobytes, so a download past this is mislabeled content and is refused
 * rather than pulled into memory (the main player's own file read cap).
 */
private const val MAX_ADDON_SUBTITLE_BYTES = 8 * 1024 * 1024

/**
 * The MPV backup engine, as a playable activity.
 *
 * It takes the same launch extras as [NativePlayerActivity] on purpose: an
 * ExoPlayer session that hits a wall it cannot come back from (no decoder
 * resources left on the box, a codec MediaCodec has no decoder for) hands its
 * own intent here with the playhead moved into "start_position_ms", so the
 * title continues instead of erroring out. The Settings "Player engine" choice
 * can also open this directly, which is why the resume/watch-history/scrobble
 * half of the player is implemented here and not skipped.
 *
 * What is deliberately NOT here, because it belongs to ExoPlayer or to the
 * box: the Dolby Vision compat layer and the P5 GPU correction (mpv has no DV
 * path), and the live-TV chrome (mpv has no IPTV path). Everything else is
 * mirrored rather than approximated: the overlaid chrome is the main player's
 * (clear logo or name, episode row, synopsis, the source badge chips, the cast
 * band, the seekbar with position/duration, play/pause, next episode, SOURCES,
 * switch player, audio, subtitles, speed, aspect, stream info, skip-intro, and
 * the settings side panel), the source list is ordered by the same StreamRanker
 * under the same Settings switch, the audio tuning chain and a now-playing
 * media session are built here too, and the focus / auto-hide rules are the
 * same, so a session that lands here does not feel like a different app. ±10s
 * seeking is the D-pad while the overlay is down, and the hardware/software
 * decoding switch lives in that settings panel.
 *
 * The picker is the main player's picker: the ranked list of sources arrives
 * with the launch extras ([parseSourcesJson]), so SOURCES re-opens mpv on the
 * chosen URL at the playhead instead of throwing the title back, and AUDIO /
 * SUBTITLES name every track mpv found rather than stepping through them
 * blind. Same panel, same rows, same labels and badge chips.
 *
 * Two things the engines genuinely share rather than mirror: the per-title
 * memory (languages, A/V offsets, chosen track - kept under the main player's
 * own key, so a title remembers itself whichever engine played it last) and the
 * end-of-playback panels, which are the same Up Next card and the same
 * Because-you-watched row, built by the same shared code.
 */
class MpvPlayerActivity : ComponentActivity(), PlayerChromeHost, PlayerChromeCastHost {

    /**
     * The switch-back's launch intent: this session replayed at the MAIN engine.
     *
     * [switchToExoPlayer] builds the switch-back out of THIS activity's own
     * intent, so the base still names MpvPlayerActivity as its component. This
     * is the one place that component is corrected; launched unchanged it
     * opened a second MPV session instead of ExoPlayer, which made the control
     * bar's SWITCH look dead from this side too.
     *
     * The rewrite used to happen in a `startActivityForResult` override. Doing
     * it here makes it explicit at the one call site, and clears the deprecated
     * override. The external-player handoff shares [exoSwitchLauncher] but sets
     * its own component, so it is deliberately not routed through here.
     */
    internal fun exoSwitchIntent(base: Intent): Intent =
        Intent(base).apply {
            setClass(this@MpvPlayerActivity, NativePlayerActivity::class.java)
            // Disarm this session's own Up Next countdown as playback leaves: it
            // is a Handler tick, so it fires whether or not this surface is the
            // one on screen. Left armed behind ExoPlayer it would chain an
            // episode out from under it and replace ExoPlayer's result with its
            // own, which reads exactly like the switch did nothing.
            cancelNextUpAutoAdvance()
        }

    /**
     * Answers this engine's two SWITCH buttons when a press cannot land.
     *
     * The mirror of the main player's guard (see
     * NativePlayerActivity.switchEngineFromButton): [switchToExoPlayer] returns
     * without a word when the session cannot move, and the two moments that
     * happens - a stream still opening, a switch already in flight - are
     * exactly the ones a viewer presses through. Both the failure card's button
     * and the control bar's come through here, so the press is never silent.
     */
    private fun switchToExoPlayerFromButton() {
        val blocked = when {
            playerSwitchStarted -> "Already switching to the ExoPlayer engine\u2026"

            currentUrl.isBlank() ->
                "Nothing is playing yet \u2014 try SWITCH again once it starts"

            intent == null || isFinishing || isDestroyed ->
                "Can't switch engines right now"

            else -> null
        }
        if (blocked != null) {
            showToast(blocked, 4_000L)
            return
        }
        switchToExoPlayer()
    }

    /**
     * One press of the settings panel's dialogue-boost pads - the only stepper
     * this control has now that the bar's own pair is gone.
     *
     * The mirror of the main player's step (see
     * NativePlayerActivity.stepDialogueBoost): the same scale, the same
     * "Global"-aware step, and the level named on screen rather than left for
     * the viewer to guess at. mpv applies the lift through its own audio filter,
     * so [chooseDialogueBoost] re-issues it and the change is heard within the
     * buffer - this engine has no tunnel to work around, and nothing here has to
     * rebuild the player.
     */
    internal fun stepDialogueBoost(delta: Int) {
        val next = PlayerAudioTuning.stepDialogueLevel(
            current = dialogueBoostOverride(),
            globalLevel = AppPreferences.getAudioDialogueBoost(this),
            delta = delta
        )
        chooseDialogueBoost(next)
        showToast("Dialogue boost: " + PlayerAudioTuning.dialogueLevelText(next), 2_500L)
    }

    private var surface: MpvPlayerView? = null
    // Panel refresh-rate matching (Settings → Playback → Match Content Frame
    // Rate), created the first time mpv reports a rate and null when the
    // setting is off — which is what makes the call sites no-ops.
    private var frameRateMatcher: FrameRateMatcher? = null
    private var loadingContainer: View? = null
    private var loadingTitle: TextView? = null
    private var loadingSubtitle: TextView? = null
    private var errorContainer: View? = null
    private var errorText: TextView? = null
    private var errorHint: TextView? = null
    private var errorSwitchButton: TextView? = null
    private var errorNextSourceButton: TextView? = null
    private var bufferingView: View? = null
    private var loadingBackdropView: ImageView? = null
    private var loadingLogoView: ImageView? = null
    // The one shared overlay (player_chrome.xml).
    private var chrome: PlayerChrome? = null
    // The overlay clock's own 1 Hz tick: the shared chrome paints the clock and
    // the readouts, so this just nudges it while the bar is up (mpv's progress
    // callback is roughly once a second, but a paused session reports nothing
    // and the clock still has to move).
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            if (chrome?.isVisible != true) return
            chrome?.refreshProgress()
            clockHandler.postDelayed(this, 1000L)
        }
    }
    // The shared bar, kept only so this engine's own KEY handling can seek it;
    // the chrome owns its value and its tap/scrub listener. The chapter strip is
    // MPV-only: mpv reports the file's chapters natively, while ExoPlayer cannot
    // read them at all.
    private var seekBar: SeekBar? = null
    private var chapterRow: View? = null
    private var chapterNow: TextView? = null
    private var chapterMarks: List<ChapterMark> = emptyList()
    private var playPauseButton: ImageView? = null
    // Icon buttons, like the main player's bar (see NativePlayerActivity): this
    // one used to draw "⏭" and "⇄" as font glyphs while SOURCE beside them was a
    // vector, so the two players' bars did not even match each other. SPEED and
    // ASPECT keep their words - a rate and a mode name ARE their state.
    private var playerSwitchButton: ImageView? = null
    private var externalButton: ImageView? = null
    private var speedButton: TextView? = null
    private var aspectButton: TextView? = null
    private var settingsContainer: View? = null
    private var settingsSection: MpvSettingsSection? = null

    // The sleep timer's rows, appended to the same panel column after the
    // engine's own sections; null until the panel is built.
    private var sleepTimerSection: SleepTimerSection? = null

    // The sleep timer's current fade, a multiplier over the volume boost: 1
    // while no fade is running, so the ordinary case writes nothing to mpv.
    private var sleepFadeGain = 1f

    // True between PiP entry and exit: the window is in the corner and playback
    // is deliberately still running (see the PiP block near onDestroy).
    private var isInPiPMode = false

    // --- Picker: the same side panel the main player opens -------------------
    private var pickerContainer: View? = null
    private var pickerTitle: TextView? = null
    private var pickerList: RecyclerView? = null


    /**
     * The list the picker shows, mirroring the main player's [PickerMode].
     * Each one is a list here rather than a cycle: AUDIO and SUBTITLES name
     * every track mpv found instead of stepping through them blind, which is
     * what the same two buttons do in the main player.
     */
    private enum class PickerMode { SOURCE, AUDIO, SUBTITLE, SPEED }

    private var pickerOpen = false

    /** The ranked source list this session was launched with. */
    private var sources: List<Stream> = emptyList()

    /**
     * The add-on behind each entry of [sources], same order and same length
     * (null when unknown). It rides the launch extras beside `sources_json`
     * (`source_addons`), and it is what lets [nextSourceOrNull] skip the rest
     * of an add-on whose links are dead for this session
     * (see [SourceAddonSession]).
     */
    private var sourceAddons: List<String?> = emptyList()

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

    /**
     * The HTTP client the pre-playback probe uses.
     *
     * libmpv does its own networking, so this Activity has no client for
     * playback - this one exists only for the cheap ranged GET that checks the
     * head of the source list before the engine is asked to open it (see
     * [SourcePlaybackProbe]). Built lazily, so a session with nothing to
     * choose between never pays for it.
     */
    private val probeHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30L, TimeUnit.SECONDS)
            .readTimeout(60L, TimeUnit.SECONDS)
            .build()
    }

    /** Label of the source playing now, for the SOURCES picker's selected row. */
    private var currentSourceLabel: String? = null

    /** Adaptive source downshift on repeated stalls (see RebufferDownshift.kt). */
    private val rebufferDownshift = RebufferDownshiftTracker()

    /** Set once the ranked list is spent: stop re-counting stalls this session. */
    private var rebufferDownshiftGivenUp = false

    /** When the current mid-playback buffer began, or 0 while not buffering. */
    private var bufferingStartedAtMs = 0L

    /** End of the last user seek, so its own buffering is not counted as a stall. */
    private var lastSeekAtMs = 0L

    /** The playing source's badge chips, for the row the main player shows. */
    private var currentBadges: List<StreamBadge> = emptyList()

    /**
     * Stremio bingeGroup of the active stream (behaviorHints.bingeGroup).
     *
     * Persisted into the next-episode handoff so the next episode's resolver
     * can prefer/reuse the same group - exactly as the main player does (see
     * BingeGroupResolver). Without it a binge that passed through this engine
     * lost its provider continuity and re-picked from the raw rank.
     */
    private var currentBingeGroup: String? = null

    /**
     * Best-effort addon display name of the active source, the reuse tier of
     * [BingeGroupResolver.orderedForNextEpisode]. Resolved from the installed
     * addons registry by matching the source label, as the main player does.
     */
    private var currentAddonName: String? = null

    /** The cast band's members, from the cast_json extra. */
    private var castMembers: List<PlayerCastMember> = emptyList()

    /**
     * Result of a manual handoff back to ExoPlayer (see [switchToExoPlayer]).
     *
     * Started FOR RESULT rather than with startActivity so this activity stays
     * in the chain and forwards whatever the other engine decides: whoever
     * started the player then gets its result exactly as if this session had
     * been that engine all along, which is what keeps "next episode" (and the
     * watch-history handoff behind it) working across the switch.
     */
    private val exoSwitchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (!isFinishing && !isDestroyed) {
            setResult(result.resultCode, result.data)
            finish()
        }
    }

    /** One handoff at a time: a second press must not stack a second ExoPlayer. */
    private var playerSwitchStarted = false

    /**
     * True while a side panel is up - the settings box or the picker (see
     * [showPicker]) - so BACK closes it first, the chrome stops auto-hiding,
     * and the focus system owns the D-pad. [pickerOpen] says which one it is.
     */
    private var settingsOpen = false

    /** True while the seekbar is being dragged, so progress cannot fight it. */
    // Scrubbing lives with the shared bar now; this reads it so the skip
    // prompt and the progress tick still leave a drag alone.
    private val scrubbing: Boolean
        get() = chrome?.isScrubbing == true

    // --- Overlay-less hold-to-scrub ---------------------------------------
    //
    // LEFT/RIGHT with the overlay down seeks this engine's video, and holding it
    // keeps seeking faster (see [ScrubAcceleration]). A quick tap keeps the
    // shape it always had - one ten-second step, and the overlay comes up so the
    // bar can be used for the rest - but a press held past
    // [ScrubAcceleration.HOLD_START_MS] walks the playhead at the shared ramp's
    // rate and leaves the overlay down: raising it mid-hold would hand the D-pad
    // to the seek bar in the middle of the scrub the viewer is asking for.
    //
    // [mpvScrubTargetMs] is the position the viewer has scrubbed TO, owned here
    // rather than read back from mpv: every tick asks for a seek, and mpv's own
    // reported position lags them by the time the seeks take to land.
    private var mpvScrubDirection = 0  // -1 = back, 1 = forward, 0 = idle
    private var mpvScrubTargetMs = 0L
    private var mpvScrubHeldMs = 0L
    private var mpvScrubExtended = false
    private val mpvScrubHandler = Handler(Looper.getMainLooper())
    private val mpvScrubRunnable = object : Runnable {
        override fun run() {
            if (mpvScrubDirection == 0) return
            val view = surface ?: return
            val duration = view.durationMs().takeIf { it > 0 } ?: return
            mpvScrubHeldMs += ScrubAcceleration.TICK_MS
            mpvScrubTargetMs =
                (mpvScrubTargetMs + ScrubAcceleration.stepFor(mpvScrubHeldMs) * mpvScrubDirection)
                    .coerceIn(0L, duration)
            view.seekTo(mpvScrubTargetMs)
            mpvScrubHandler.postDelayed(this, ScrubAcceleration.TICK_MS)
        }
    }
    private val mpvScrubHoldStarter = Runnable {
        if (mpvScrubDirection == 0) return@Runnable
        mpvScrubExtended = true
        mpvScrubHeldMs = 0L  // The ramp starts here
        mpvScrubHandler.post(mpvScrubRunnable)
    }

    // --- Audio tuning: the main player's AUDIO section, on this engine -----
    //
    // Same three knobs, same option lists ([PlayerAudioTuning]), same storage:
    // -1 means "follow the global Settings value", anything else is remembered
    // for this title alone under the main player's own key.
    private var audioDownmix = -1
    private var audioDialogueBoost = -1
    private var audioVolumeBoostDb = -1

    // --- Session media: the system's own now-playing surface ---------------
    //
    // The main player builds a media3 `MediaSession` over its ExoPlayer; mpv is
    // not a media3 `Player`, so the same feature is built here on the platform
    // session API instead - which is what media3's own session is a wrapper for
    // anyway. What the viewer gets is the part that matters and the reason the
    // main player has one at all: a now-playing card other apps and the system
    // can see, working transport buttons outside this app, and a tap on that
    // card that comes back to the player it was raised for.
    private var mediaSession: MpvMediaSession? = null

    /** Request code for the session's tap-to-open pending intent. */
    private val mediaSessionRequestCode = 1002

    /**
     * What the device's subtitle picker may return. The two named types are
     * SubRip and WebVTT (what OpenSubtitles and most exporters produce); the
     * trailing wildcard is the escape hatch for a box that reports a subtitle
     * as plain text or with no type at all, which is why the main player's
     * picker has the same fallback.
     */
    private val subtitleMimeTypes = arrayOf(
        "application/x-subrip",
        "application/x-subtitle-vtt",
        "text/vtt",
        "text/plain",
        "*/*"
    )

    // --- External subtitles: the other half of the main player's SUBTITLES
    // picker ---------------------------------------------------------------
    private var externalSubtitleUri: Uri? = null
    private var externalSubtitleName: String? = null

    /**
     * True once the per-video remembered subtitle has been consulted. One
     * restore per session, so a source switch that follows a deliberate clear
     * does not put the file back.
     */
    private var subtitleMemoryRestored = false

    /** Addon-published subtitle offers for this video, shown in the picker. */
    private var addonSubtitleOffers: List<SubtitleEntry> = emptyList()
    private var addonSubtitleFetchJob: Job? = null
    private val addonSubtitleDownloads = mutableMapOf<String, Uri>()

    /** The picker mode on screen, so late addon offers can repaint it. */
    private var currentPickerMode: PickerMode? = null

    /** Results of the last online search, rendered as rows while they stand. */
    private var onlineSubResults: List<SubtitleSearchResult> = emptyList()
    private var onlineSubLoading = false

    /**
     * Set once an automatic OpenSubtitles fetch has been attempted this
     * session, so the file-loaded callback cannot start a second one. The
     * main player has the same pair for the same reason (see
     * [NativePlayerActivity.maybeAutoFetchSubtitle]).
     */
    private var autoSubtitleFetchTried = false
    private var autoSubtitleFetchInFlight = false

    /**
     * Set once the NEXT episode's subtitle has been asked for. The end panels
     * are raised from more than one place (the credits trigger and the real end
     * of file), so without this each of them would start its own fetch.
     */
    private var subtitlePrefetchStarted = false

    /**
     * The device's own file picker, for a subtitle mpv has no way to know
     * about. The picked document is copied into the app cache before mpv sees
     * it, so what mpv is handed is always a plain file path rather than a
     * content:// URI it may have no protocol for.
     */
    private val externalSubtitlePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) attachExternalSubtitle(uri)
    }

    // --- Playback shape, and what this title remembers ----------------------
    private var playbackSpeed = 1f
    private var resizeModeIndex = 0
    private var subtitleSize = 1
    private var subtitleBackground = 0
    private var subtitlePosition = 0

    /** The main player's per-title key, so both engines recall the same thing. */
    private var titleKey: String? = null
    private var audioLanguage = ""
    private var subtitleLanguage = ""
    private var audioDelayMs = 0
    private var subtitleOffsetMs = 0
    private var audioTrackSignature = ""
    /**
     * Explicit per-show "no subtitles" (the picker's OFF), remembered like the
     * main player's bridge does: a blank subtitle language means "Auto, follow
     * the global MODE", so the refusal has to be its own fact or the next file
     * open re-arms a track the viewer turned off.
     */
    private var subtitlesOff = false
    /** One specific subtitle track in this show's files ("language|codec|0"). */
    private var subtitleTrackSignature = ""

    // --- End of episode, mirroring the main player ---------------------------
    private var nextUpPanel: LinearLayout? = null
    private var nextUpThumb: ImageView? = null
    private var nextUpShowTitle: TextView? = null
    private var nextUpEpisodeLabel: TextView? = null
    private var nextUpEpisodeTitle: TextView? = null
    private var nextUpCountdown: TextView? = null
    private var btnNextPlay: TextView? = null
    private var btnNextDismiss: TextView? = null

    /** True once the end-of-episode card has been raised (once per session). */
    private var endPanelsShown = false
    private var pendingNextSeason: Int? = null
    private var pendingNextEpisode: Int? = null
    private var pendingNextEpisodeName: String? = null
    // The next episode's synopsis, from the same TMDB lookup as the name and
    // still. Carried into the handoff so the next session's overlay does not
    // open on the FINISHED episode's text.
    private var pendingNextEpisodeOverview: String? = null
    private var nextUpCountdownRemaining = 0
    private var nextUpCountdownHeld = false

    /**
     * Latched the first time this session hands playback to the next episode,
     * so the card's PLAY NEXT and the auto-advance countdown - both of which
     * run through [launchNextEpisode] - cannot chain out twice and start the
     * same next episode a second time.
     */
    private var nextEpisodeHandoffStarted = false

    private val nextUpCountdownHandler = Handler(Looper.getMainLooper())
    private val nextUpCountdownRunnable = object : Runnable {
        override fun run() {
            // A sleep timer armed while this countdown was already running (the
            // card opens during the credits, so that is the ordinary way the two
            // meet) wins: the countdown stops here instead of handing off seconds
            // before the end the timer is waiting for. Re-checked every tick, so
            // the timer is honored whenever it is armed - up to the last second.
            if (sleepTimerBlocksAutoAdvance(SleepTimer.state.value)) {
                nextUpCountdownHeld = false
                nextUpCountdownRemaining = 0
                nextUpCountdown?.text = "Stopping here - sleep timer"
                return
            }
            nextUpCountdownRemaining--
            if (nextUpCountdownRemaining <= 0) {
                // The countdown fired unattended: count this as an auto-advanced
                // episode for the "Are you still there?" binge watchdog.
                if (AppPreferences.getStillTherePrompt(this@MpvPlayerActivity)) {
                    val count = AppPreferences.getConsecutiveAutoplays(this@MpvPlayerActivity) + 1
                    AppPreferences.setConsecutiveAutoplays(this@MpvPlayerActivity, count)
                }
                advanceToPendingNext()
                return
            }
            nextUpCountdown?.text = "Playing next in $nextUpCountdownRemaining"
            nextUpCountdownHandler.postDelayed(this, 1_000L)
        }
    }

    /**
     * Disarms the Up Next countdown.
     *
     * A session that has handed playback over (see [switchToExoPlayer]) or is
     * being torn down must not chain out from behind the player that is
     * actually on screen: this countdown is a Handler tick, so it fires
     * whether or not the surface is playing, and a backgrounded session doing
     * that starts the next episode a second time. The main player cancels the
     * same countdown in its own teardown; this engine now does too.
     *
     * Called from the switch handoff ([startActivityForResult]) - the one place
     * a switch leaves this activity - and from nothing else yet.
     */
    private fun cancelNextUpAutoAdvance() {
        nextUpCountdownHeld = false
        nextUpCountdownRemaining = 0
        nextUpCountdownHandler.removeCallbacks(nextUpCountdownRunnable)
    }

    /**
     * Takes the end-of-episode cards down, with their countdown and their
     * credits-mode geometry.
     *
     * They belong to the source and the episode that just ended, and the
     * countdown is the dangerous half: it is a Handler tick, so it fires whether
     * or not the surface is playing. Switching sources during the credits - the
     * ordinary case, since that is when the cards are up - used to leave the card
     * over the new file and let the countdown chain to the next episode a moment
     * later, mid-new-source. The main player resets the same state on a source
     * switch; see [switchToSource].
     */
    private fun hideEndPanels() {
        cancelNextUpAutoAdvance()
        nextUpPanel?.visibility = View.GONE
        becauseYouWatchedPanel?.visibility = View.GONE
        // The credits panel shrinks the video into a corner while it is up, and
        // the next source loads into that same surface.
        exitCreditsMode()
        endPanelsShown = false
    }

    /**
     * Closes the end-of-episode "Up next" card without ending the session.
     *
     * Back on this card used to reach exitPlayer(), so the press a viewer makes
     * to wave it away left the title instead. Dismissing hides the card with its
     * countdown (see [hideEndPanels]) and lands the D-pad back on the controls, so
     * the session stays open and Next or a second Back remain available.
     */
    private fun dismissNextUpPanel() {
        if (nextUpPanel?.visibility != View.VISIBLE) return
        hideEndPanels()
        if (controlsVisible) {
            playPauseButton?.requestFocus()
            keepControlsVisible()
        } else {
            showControls()
        }
    }

    // --- Because you watched (end-credits recommendations) ------------------
    //
    // The row itself is the shared [BecauseYouWatchedUi], the same one the main
    // player raises: the picks, the featured strip and the pill focus rules can
    // not drift between the engines. Only the handoff differs - this engine
    // hands the pick back to MainActivity with the same result extras the main
    // player uses, so the navigation side needs no engine-specific branch.
    private var becauseYouWatchedPanel: LinearLayout? = null
    private var bywTitle: TextView? = null
    private var bywRow: LinearLayout? = null
    private var bywUi: BecauseYouWatchedUi? = null
    private var bywDismissed = false

    /** True while the panel is up over a shrunk (corner) video. */
    private var creditsModeActive = false

    /** The panel's XML layout params, restored when credits mode ends. */
    private var creditsModePanelParams: FrameLayout.LayoutParams? = null

    // --- IntroDB: the skip prompt, and the credits marker the end-of-episode
    // panel times itself against -------------------------------------------------
    //
    // The rows themselves and the rules for using them are shared with the main
    // player ([fetchIntroDbStamps], [AutoSkipRules]); what lives here is only
    // this engine's side of it - following mpv's playhead and seeking with mpv.
    // Having the rows at all is what lets the panel open as the credits start
    // here just as it does in the main player.
    private var introDbStamps = emptyList<IntroDbStamp>()

    /** The segment the playhead is inside, as last offered by [updateSkipPrompt]. */
    private var activeSkipStamp: IntroDbStamp? = null

    /**
     * Segments already skipped with no press, so an automatic skip happens at
     * most once per session - seeking back into an intro on purpose must not be
     * fought. Keys come from [AutoSkipRules.key].
     */
    private val autoSkippedSegments = mutableSetOf<String>()

    /** True once the file is open: the prompt must never appear over the splash. */
    private var fileLoaded = false

    /**
     * Ranked sources this session has already failed to OPEN, for the
     * open-failure ladder (see [PlaybackRecoveryRules.shouldAdvancePastOpenFailure]).
     *
     * A session counter, not per-source state: [switchToSource] resets the
     * state belonging to the source being left and must NOT reset this one, or
     * the ladder would walk the whole list forever. It is cleared where a file
     * actually opens - see [onFileLoaded].
     */
    private var openFailureSourcesTried = 0

    /**
     * Set once a launch that reused a cached debrid link has had that link
     * forgotten, so a session that walks several errors forgets at most once.
     * See [invalidateCachedLinkBeforeFirstFrame].
     */
    private var linkCacheInvalidated = false

    // --- Session state, read from the launch intent -------------------------
    private var currentUrl = ""
    private var currentAudioUrl: String? = null
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
    private var itemName = ""
    private var episodeTitle: String? = null
    private var itemPoster: String? = null
    private var clearLogoUrl: String? = null
    private var backdropUrl: String? = null
    private var overview: String? = null
    private var totalEpisodesInSeason: Int? = null

    /*
     * One file is not one TMDB episode (see EpisodeScheme).
     *
     * The main player's field comment carries the long version; what matters
     * here is that this engine WALKS THE SAME MAPPING, because a binge that
     * starts in ExoPlayer and finishes here would otherwise renumber the show
     * halfway through. `season`/`episode` stay TMDB numbering, and the file the
     * viewer is on is the trailing number of [episodeStreamId] (see
     * currentFileEpisode).
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
    private var streamHeaders: Map<String, String> = emptyMap()
    private var historyParentIdOverride: String? = null
    private var startPositionMs = 0L

    /**
     * The launch asked for the beginning (Home's long-press "Play from
     * Beginning"). Kept beside the position it zeroed, because it is the one
     * flag that must also stop the watch-history resume (see PlaybackResume).
     */
    private var startFromBeginning = false
    private var historyId = ""

    /** True when ExoPlayer handed this session over (see [EXTRA_MPV_FALLBACK]). */
    private var isFallbackSession = false
    private var fallbackReason: String? = null

    /** Random-episode mode, carried from the launch intent into the handoff. */
    private var randomEpisodes = false

    private var canonicalParent: String? = null
    private var resolvedTmdbId: Int? = null

    private val handler = Handler(Looper.getMainLooper())
    private var audioFocusRequest: AudioFocusRequest? = null
    private var scrobbleStarted = false
    private var completionSent = false
    private var trackersMarkedWatched = false
    private var endedHandled = false
    private var positionMs = 0L
    private var durationMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The window behind and around the picture. The theme's static
        // colorBackground cannot follow the AMOLED toggle, so an AMOLED install
        // showed the ordinary #0A0E14 void wherever the video surface does not
        // reach - the same mismatch MainActivity fixes for its launch window,
        // and a player is the screen where a navy edge is most obvious.
        applyPlayerWindowTone(this)

        // Kids Mode: a daily limit or bedtime that lands mid-film has to end
        // the playback it is counting. The lock overlay lives in MainActivity,
        // behind this Activity, so without this the film simply ran to the end.
        com.kennyb1201.kbstream.data.sync.KidsTimeGuard.enforceLock(this)

        // Same guide-write gate the ExoPlayer path holds: libmpv decodes in
        // this process, and a 500/1000-row EPG batch landing on the shared
        // SQLite pool mid-film is the stall the gate exists to prevent.
        // Released in onStop - the surface is paused there, so there is no
        // playback left to protect.
        EpgWriteGate.setPlayerActive(true)

        // Same decoder accounting as the ExoPlayer path (see
        // NativePlayerActivity.onCreate): libmpv decodes in this process, and
        // the Home hero's paused trailer player would otherwise still be
        // holding one of the decoders it needs.
        TrailerPlayerPool.releaseForReuse()

        // The other half of that parity: playback is the memory peak of the
        // app whichever engine runs it, and this one is opened precisely when
        // the box is already struggling - an automatic handoff after decoder
        // resource exhaustion, or a profile set to MPV for a title ExoPlayer
        // cannot decode. libmpv decodes in software on the Java heap, so
        // freeing the browsing caches before it starts matters here at least as
        // much as it does on the ExoPlayer path, which has always done it (see
        // NativePlayerActivity.onCreate).
        runCatching {
            releaseImageMemoryCache(this)
            MemoryPressure.releaseBrowsingCaches()
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_mpv_player)

        readIntent(savedInstanceState)
        bindViews()
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
        historyId = PlaybackHistoryIds.historyId(parentId, season, episode, episodeStreamId)
        // The same line the main player writes: what this session files itself
        // under, next to what the id it plays says (see PlaybackSessionTrace).
        // The stored scheme goes with it, so the comparison reads the id as the
        // FILE it is instead of against the TMDB episode (see
        // PlaybackHistoryIds.playbackSessionLine).
        com.kennyb1201.kbstream.data.reporting.PlaybackSessionTrace.note(
            PlaybackHistoryIds.playbackSessionLine(
                season,
                episode,
                episodeStreamId,
                historyId,
                bingeScheme
            )
        )

        // The same per-title memory the main player keeps, under the same key,
        // so a title remembers its languages, A/V offsets and audio track
        // whichever engine played it last.
        titleKey = PlayerTitlePrefs.titleKeyFor(parentId, historyId)
        loadTitlePreferences()

        // Splash art, the same shape as the main player's: backdrop with the
        // clear logo over it, or the name when there is no logo art.
        (backdropUrl ?: itemPoster)?.takeIf { it.isNotBlank() }?.let { art ->
            runCatching { loadingBackdropView?.load(art) }
        }
        clearLogoUrl?.takeIf { it.isNotBlank() }?.let { logo ->
            runCatching {
                loadingLogoView?.load(logo)
                loadingLogoView?.visibility = View.VISIBLE
                loadingTitle?.visibility = View.GONE
            }
        }

        // The settings panel is built in code from the same helpers the main
        // player's panel section uses.
        settingsContainer = findViewById(R.id.mpv_settings_container)
        settingsSection = settingsContainer?.let { container ->
            MpvSettingsSection(this, container).also { section -> section.attach() }
        }

        // The sleep-timer rows go into the same column, as their own section.
        // This engine has no live-TV path, so its choices are the minute steps
        // and the end of whatever is playing - the row list is still read
        // through the activity, exactly as the main player's is, so the two
        // engines cannot offer different timers.
        settingsContainer?.let { container ->
            sleepTimerSection = SleepTimerSection(
                activity = this,
                optionsProvider = { sleepTimerChoices() },
                onSelect = { option -> chooseSleepTimer(option) }
            ).also { section -> section.attach(container) }
        }
        // The MPV chrome is plain XML with fixed surface fills, so the AMOLED /
        // pure-black toggles never reached it. Run the same re-tint pass the
        // main player runs, now that the settings panel has been built.
        applyPlayerChromeTheme()

        val view = findViewById<MpvPlayerView>(R.id.mpv_surface)
        surface = view
        view.onEngineFailed = { message ->
            Log.e(TAG, "MPV engine unavailable: $message")
            showError(
                message,
                "Switch player to try this in ExoPlayer, or press Back to exit."
            )
        }
        view.onFileLoaded = { title ->
            // A file that actually OPENED is this addon working for this
            // title, so the next episode's list leads with it instead of with
            // whichever addon the ranker happened to put first (see
            // SourceAddonMemory). No live guard is needed on this engine: it
            // has no live-TV path (see the class docs).
            com.kennyb1201.kbstream.data.player.SourceAddonMemory.rememberWorked(
                this@MpvPlayerActivity,
                parentId,
                currentAddonName
            )
            onFileLoaded(title)
        }
        view.onProgress = { position, duration -> onProgress(position, duration) }
        view.onPausedChanged = { paused -> onPausedChanged(paused) }
        view.onBufferingChanged = { buffering ->
            bufferingView?.visibility = if (buffering) View.VISIBLE else View.GONE
            onMpvBufferingChanged(buffering)
        }
        view.onEnded = { onPlaybackEnded() }
        view.onPlaybackError = { message ->
            // A cached link that never opened is dead: forget it now, so the
            // next replay resolves fresh instead of looping back into this card.
            invalidateCachedLinkBeforeFirstFrame()
            // This callback fires only when the file never opened, so this
            // addon's link is the thing that failed: remember it against this
            // TITLE, so the next episode's list - and the picker's own order -
            // stop heading with it (see SourceAddonMemory). A decoder or
            // container failure cannot land here, so a good link is never
            // demoted for this box's limits.
            com.kennyb1201.kbstream.data.player.SourceAddonMemory.rememberFailed(
                this@MpvPlayerActivity,
                parentId,
                currentAddonName
            )
            // ...and mark it dead for the REST of this session too, so the
            // advance below lands on the next source from a DIFFERENT addon
            // instead of walking the same addon's remaining links
            // (see SourceAddonSession). The persistent record above and this
            // set are independent: this one dies with the session.
            SourceAddonSession.markDead(sessionDeadAddons, currentAddonName)
            // This callback fires only when the file never opened (see
            // MpvPlayerView's END_FILE handling), which makes the ranked list
            // the first answer rather than the card: a source that will not
            // open is exactly what the main player walks past on its own, and
            // this engine is where a session lands when its FIRST source was
            // already trouble. Bounded, so a title whose whole list is dead
            // still ends on the card (see shouldAdvancePastOpenFailure).
            openFailureSourcesTried += 1
            val advance =
                PlaybackRecoveryRules.shouldAdvancePastOpenFailure(
                    sourcesTried = openFailureSourcesTried,
                    hasAnotherSource = nextSourceOrNull() != null
                )
            if (advance) {
                Log.w(
                    TAG,
                    "Source would not open (${openFailureSourcesTried}/" +
                        "${PlaybackRecoveryRules.MAX_MPV_OPEN_FAILURE_SOURCES})" +
                        " \u2014 trying the next one"
                )
                // Into the same report ring the failed open itself wrote to:
                // without this the card's absence is unexplainable from a dump
                // (see PlaybackEngineTrace).
                com.kennyb1201.kbstream.data.reporting.PlaybackEngineTrace.note(
                    com.kennyb1201.kbstream.data.reporting.PlaybackEngineTrace.describe(
                        cause = "MPV open failed",
                        detail = "trying the next source " +
                            "($openFailureSourcesTried of " +
                            "${PlaybackRecoveryRules.MAX_MPV_OPEN_FAILURE_SOURCES})"
                    )
                )
                showToast("That source won't open. Trying the next one\u2026")
                tryNextSource()
            } else {
                showError(
                    message,
                    "The stream may be offline, or the source may have changed. " +
                        "Switch player to try this in ExoPlayer, or press Back to exit."
                )
            }
        }
        view.onVideoFrameRateChanged = { fps ->
            // The same setting the main player reads, applied from the rate mpv
            // reports (see FrameRateMatcher). Created here rather than up
            // front because the mode it restores has to be captured before any
            // switch, and this is the first moment a switch is possible.
            if (frameRateMatcher == null && AppPreferences.getMatchFrameRate(this@MpvPlayerActivity)) {
                frameRateMatcher = FrameRateMatcher(
                    this@MpvPlayerActivity,
                    // mpv draws into its own SurfaceView, so the frame-rate
                    // request rides on that view's surface.
                    videoSurface = { surface?.holder?.surface }
                )
            }
            frameRateMatcher?.onContentFrameRate(fps)
        }

        setupControls()
        setupMediaSession()
        // IntroDB rows for this session: the skip prompt and the end-of-episode
        // panel's credits marker both come from them.
        setupIntroDb()
        // Addon-published subtitles for this video, fetched in the background:
        // the same offers the main player merges, so the SUBTITLE picker reads
        // the same on both engines (see loadAddonSubtitleOffers).
        loadAddonSubtitleOffers()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // BACK closes the panels first, exactly like the main player.
                    // The end-of-episode card is a question, not the session:
                    // Back waves it away and leaves playback alone, where it used
                    // to exit the player instead.
                    when {
                        nextUpPanel?.visibility == View.VISIBLE -> dismissNextUpPanel()
                        pickerOpen -> dismissPicker()
                        settingsOpen -> hideSettingsPanel()
                        else -> exitPlayer()
                    }
                }
            }
        )

        // Languages for this session: what the title remembers, else the global
        // Settings preference. Set BEFORE initialize(), because `alang`/`slang`
        // are options the demuxer honors at open time - the fast path, with no
        // flicker of the wrong track.
        //
        // The subtitle look is deliberately not set from here: it is a global
        // display preference rather than a per-title one, and MpvPlayerView
        // applies it inside applyOptions() from those same preferences. Setting
        // it here would also be a call against an mpv instance that mpv does not
        // create until initialize().
        view.setLanguagePreferences(effectiveAudioLanguage(), effectiveSubtitleLanguage())
        // Off / forced only / on, the global subtitle mode: also an open-time
        // choice, so it is set before initialize() beside the languages above.
        view.setSubtitleMode(AppPreferences.getSubtitleMode(this))

        if (!view.initialize()) return

        // These are runtime properties, so they only take effect after
        // initialize(): the A/V offsets this title remembers, the speed and the
        // aspect ratio.
        view.setAudioDelayMs(audioDelayMs)
        view.setSubtitleDelayMs(subtitleOffsetMs)
        view.setSpeed(playbackSpeed.toDouble())
        view.setAspectMode(resizeModeIndex)

        // Audio tuning, resolved the way the main player resolves it: this
        // title's own choice, else the global Settings value. Set here rather
        // than in applyOptions() because these are runtime properties - and the
        // buffer profile, which is not, is read by the view itself.
        view.setBufferMode(AppPreferences.getDefaultBufferMode(this))
        view.setDownmix(effectiveAudioDownmix())
        view.setDialogueBoost(effectiveAudioDialogueBoost())
        view.setVolumeBoostDb(effectiveAudioVolumeBoost())

        fun loadStream() {
            view.load(
                MpvPlayerView.LoadRequest(
                    url = currentUrl,
                    headers = streamHeaders,
                    audioUrl = currentAudioUrl,
                    startPositionMs = startPositionMs
                )
            )
        }

        showLoading(
            when {
                fallbackReason == FALLBACK_REASON_MANUAL -> "Switching to the MPV engine"
                isFallbackSession ->
                    "ExoPlayer could not play this stream \u2014 continuing in MPV"
                else -> "MPV engine"
            }
        )
        // Nothing in this launch asked to resume, so ask the watch history: a
        // source picked from the picker arrives with no position of its own
        // (see PlaybackResume), and this engine gets one chance at the file -
        // it is opened once, with the position it is handed, and there is no
        // seek to a saved point afterwards. Without this, picking a source for
        // a title Continue Watching has progress on restarted it from the
        // beginning in MPV while the same press resumed in ExoPlayer. The
        // splash is already up, so the read happens behind it and the file is
        // only opened once the position is known - nothing starts at 0 and
        // jumps. A failed or empty read leaves the launch as it was.
        if (
            PlaybackResume.mayResumeFromHistory(
                startPositionMs = startPositionMs,
                startFromBeginning = startFromBeginning,
                historyId = historyId
            )
        ) {
            lifecycleScope.launch {
                PlaybackResume.savedPositionMs(
                    context = this@MpvPlayerActivity,
                    historyId = historyId,
                    parentId = parentId,
                    parentType = parentType,
                    season = season,
                    episode = episode,
                    episodeStreamId = episodeStreamId
                )?.let { saved ->
                    startPositionMs = saved
                }
                if (isFinishing || isDestroyed) return@launch
                probeThenLoad { loadStream() }
            }
        } else {
            probeThenLoad { loadStream() }
        }
    }

    /**
     * Mechanism A on this engine: probe the head of the ranked list before the
     * player's first load, so the session never spends a full open timeout on a
     * link the probe already knows is dead.
     *
     * The pick's add-on is marked dead for the session, which is exactly what
     * the advance after it needs (see [SourceAddonSession]); the chosen source
     * then loads normally through [load]. `load` is passed in because the load
     * path also carries the playhead the launch resolved, and it is a local of
     * [onCreate].
     *
     * On any probe infrastructure failure the top-ranked source loads as today.
     */
    private fun probeThenLoad(load: () -> Unit) {
        // With fewer than two sources there is nothing to choose between, and
        // the probe only ever decides WHICH source to open.
        if (sources.size < 2) {
            load()
            return
        }
        val probe = SourcePlaybackProbe(sessionDeadAddons, probeHttpClient)
        lifecycleScope.launch {
            val pick = runCatching {
                probe.pickLiveSource(sources, sourceAddons) { stream -> stream.requestHeaders }
            }.getOrNull()
            if (isFinishing || isDestroyed) return@launch
            // Only a source the probe actually proved live moves the session;
            // a null pick (a probe infrastructure failure) loads the head of
            // the list exactly as before.
            if (pick != null && !pick.url.isNullOrBlank() && pick.url != currentUrl) {
                Log.w(TAG, "probe picked a different head source: ${pick.sourceLabel()}")
                adoptInitialSource(pick)
            }
            load()
        }
    }

    /**
     * Makes [stream] the source this session is about to open, before any
     * player exists: the same fields [switchToSource] moves across a mid-playback
     * switch, minus everything that belongs to a player being torn down.
     */
    private fun adoptInitialSource(stream: Stream) {
        val url = stream.url ?: return
        currentUrl = url
        currentAudioUrl = stream.audioUrl
        streamHeaders = stream.requestHeaders
        currentSourceLabel = stream.sourceLabel()
        currentBadges = stream.badges
        currentBingeGroup = stream.bingeGroup
        resolveAddonIdentity(currentSourceLabel)
    }

    // --- Intent ------------------------------------------------------------

    private fun readIntent(savedInstanceState: Bundle?) {
        val intent = intent ?: return
        currentUrl = intent.getStringExtra(EXTRA_STREAM_URL).orEmpty()
        currentAudioUrl = intent.getStringExtra("audio_url")
        parentId = intent.getStringExtra("parent_id").orEmpty()
        parentType = intent.getStringExtra("parent_type").orEmpty()
        sessionProfileId = PlaybackHistoryWriter.sessionProfileId(this, intent)
        season = intent.getIntExtra("season", -1).takeIf { it >= 0 }
        // A launch carrying 0 as its episode is carrying "no episode": see
        // EpisodeNumbering. Read as one, the up-next line said "Season 2
        // Episode 00".
        episode = namedEpisodeNumber(intent.getIntExtra("episode", -1))
        episodeStreamId = intent.getStringExtra("episode_stream_id")
        itemName = intent.getStringExtra("item_name")
            ?: intent.getStringExtra("display_name").orEmpty()
        episodeTitle = intent.getStringExtra("episode_title")
        itemPoster = intent.getStringExtra("item_poster")
        clearLogoUrl = intent.getStringExtra("clear_logo_url")
        backdropUrl = intent.getStringExtra("backdrop_url")
        overview = intent.getStringExtra("item_overview")
        totalEpisodesInSeason =
            intent.getIntExtra("total_episodes_in_season", -1).takeIf { it > 0 }
        // The launch's TMDB runtime for the episode being played: the scheme
        // detector's reference, so it needs no lookup of its own.
        launchRuntimeMinutes = intent.getIntExtra("runtime_minutes", -1).takeIf { it > 0 }
        historyParentIdOverride = intent.getStringExtra(EXTRA_HISTORY_PARENT_ID)
        isFallbackSession = intent.getBooleanExtra(EXTRA_MPV_FALLBACK, false)
        fallbackReason = intent.getStringExtra(EXTRA_MPV_FALLBACK_REASON)
        // Random-episode mode rides the session: the handoff starts a NEW
        // player, which would otherwise read its intent as a normal
        // (arithmetic) chain. The main player carries it the same way.
        randomEpisodes = intent.getBooleanExtra("random_episodes", false)
        streamHeaders = parseHeaders(intent.getStringExtra("stream_headers").orEmpty())

        // The ranked source list the caller shipped, read with the same helper
        // the main player uses, with this session's stream guaranteed to be on
        // it: the SOURCES picker must be able to mark what is playing even when
        // the session was handed over with only a stream_url.
        // Ranked here as well as upstream when Settings -> Stream ranking is on:
        // the payload usually arrives already ordered by the resolver, but a
        // direct launch (Settings -> Player engine = MPV) can carry an unruly
        // list, and both engines must offer the same order. StreamRanker.rank is
        // a stable sort, so re-ranking an already-ranked list changes nothing.
        // The session's own episode goes with it, so the in-player SOURCES
        // picker keeps a file that declares another episode of this season out
        // of its head exactly as the resolver's picker does (see EpisodeMatch).
        val requestedEpisode = season?.let { requestedSeason ->
            episode?.let { requestedEpisode -> requestedSeason to requestedEpisode }
        }
        val parsedSources = parseSourcesJson(intent.getStringExtra("sources_json"))
        // The add-on behind each parsed row, parallel to sources_json. Absent
        // or short reads as "unknown" for the missing entries, which are never
        // skipped and never demoted.
        val parsedAddons = parseSourceAddonsJson(intent.getStringExtra("source_addons"))
        // Whether a Dolby Vision label is worth anything on this box, read the
        // same way the resolver reads it (see StreamsViewModel): the device has
        // to advertise a DV decoder AND the viewer must not have said their
        // display has none. Otherwise the ranker must not put a DV copy ahead of
        // its HDR10 fallback over here either, or this re-rank would undo the
        // one the picker was built from.
        val dolbyVisionUseful =
            com.kennyb1201.kbstream.data.player.DolbyVisionCapability.supportsNativeDolbyVision &&
                AppPreferences.getDvCompatMode(this) != AppPreferences.DV_COMPAT_ALL
        val orderedSources = if (AppPreferences.getUseStreamRanker(this)) {
            StreamRanker.rank(
                parsedSources,
                requestedEpisode,
                constrainedDevice = DeviceCapability.constrainedStreamDevice(this),
                dolbyVisionUseful = dolbyVisionUseful
            )
        } else {
            parsedSources
        }
        sources = orderedSources.withCurrentSource(currentSourceStream(currentUrl, currentAudioUrl))
        // Resolved by stream identity, so the re-rank above and a prepended
        // current source both keep every name with its own row.
        sourceAddons = addonsFor(sources, parsedSources, parsedAddons)
        val playingSource = sources.firstOrNull { it.url == currentUrl }
        currentSourceLabel = playingSource?.sourceLabel()
        currentBadges = playingSource?.badges.orEmpty()
        currentBingeGroup = playingSource?.bingeGroup
        resolveAddonIdentity(currentSourceLabel)

        // The cast band's members, the same payload the main player renders.
        castMembers = parseCastJson(intent.getStringExtra("cast_json"))

        // A title started "from the beginning" must not become a resume.
        startFromBeginning = intent.getBooleanExtra("from_beginning", false)
        startPositionMs = if (startFromBeginning) {
            0L
        } else {
            intent.getLongExtra("start_position_ms", 0L)
        }
        val restored = savedInstanceState?.getLong(STATE_POSITION_MS, 0L) ?: 0L
        if (restored > startPositionMs) startPositionMs = restored
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // The launch intent only says where playback STARTED; after a system
        // recreate this is what keeps the title from restarting.
        val position = surface?.positionMs()?.coerceAtLeast(0L) ?: startPositionMs
        if (position > 0L) outState.putLong(STATE_POSITION_MS, position)
    }

    private fun bindViews() {
        loadingContainer = findViewById(R.id.mpv_loading)
        loadingTitle = findViewById(R.id.mpv_loading_title)
        loadingSubtitle = findViewById(R.id.mpv_loading_subtitle)
        errorContainer = findViewById(R.id.mpv_error)
        errorText = findViewById(R.id.mpv_error_text)
        errorHint = findViewById(R.id.mpv_error_hint)
        // The failure card's own SWITCH PLAYER: the press the control bar's
        // SWITCH makes (see switchToExoPlayer), on the one surface where the bar
        // is behind the card. Wired here because it belongs to that card alone.
        errorSwitchButton = findViewById(R.id.mpv_error_switch)
        errorSwitchButton?.setOnClickListener { switchToExoPlayerFromButton() }
        // The card's one-tap retry on a DIFFERENT source (see tryNextSource):
        // the same switch the SOURCES picker makes, without the dig.
        errorNextSourceButton = findViewById(R.id.mpv_error_next)
        errorNextSourceButton?.setOnClickListener { tryNextSource() }
        bufferingView = findViewById(R.id.mpv_buffering)
        loadingBackdropView = findViewById(R.id.mpv_loading_backdrop)
        loadingLogoView = findViewById(R.id.mpv_loading_logo)
        // Engine-only views inflated into the shared chrome's slots, so they keep
        // the exact place they had in this layout before it was unified.
        val chapterSlot = findViewById<LinearLayout>(R.id.chrome_extra_seek)
        val chapterStrip = layoutInflater.inflate(
            R.layout.player_mpv_chapter_row,
            chapterSlot,
            false
        )
        chapterSlot.addView(chapterStrip)
        chapterSlot.visibility = View.VISIBLE
        chapterRow = chapterStrip
        chapterNow = chapterStrip.findViewById(R.id.mpv_chapter_now)
        chapterStrip.findViewById<TextView>(R.id.mpv_chapter_prev)?.let { prev ->
            prev.setOnClickListener {
                surface?.addChapter(-1)
                refreshChapterTitle(positionMs)
            }
            prev.setOnFocusChangeListener { view, focused ->
                applyPillBackground(view as TextView, selected = false, focused = focused)
            }
            applyPillBackground(prev, selected = false, focused = false)
        }
        chapterStrip.findViewById<TextView>(R.id.mpv_chapter_next)?.let { next ->
            next.setOnClickListener {
                surface?.addChapter(1)
                refreshChapterTitle(positionMs)
            }
            next.setOnFocusChangeListener { view, focused ->
                applyPillBackground(view as TextView, selected = false, focused = focused)
            }
            applyPillBackground(next, selected = false, focused = false)
        }
        // The one shared overlay, driven by this engine through PlayerChromeHost.
        // MPV hid its bar after eight seconds, not the main player's six, so it
        // keeps its own timeout.
        chrome = PlayerChrome(
            findViewById(R.id.chrome_layer),
            this,
            autoHideMs = CONTROLS_TIMEOUT_MS
        )
        // The shared bar, kept only for this engine's own key handling.
        seekBar = findViewById(R.id.chrome_seekbar)
        playPauseButton = findViewById(R.id.chrome_btn_play_pause)
        playerSwitchButton = findViewById(R.id.chrome_btn_player_switch)
        // MPV is always a valid handoff partner (ExoPlayer is always there), so
        // its SWITCH is never hidden - the shared layout defaults it gone for the
        // engine that does hide it.
        playerSwitchButton?.visibility = View.VISIBLE
        externalButton = findViewById(R.id.chrome_btn_player_external)
        speedButton = findViewById(R.id.chrome_btn_speed)
        aspectButton = findViewById(R.id.chrome_btn_aspect)
        pickerContainer = findViewById(R.id.mpv_picker_container)
        pickerTitle = findViewById(R.id.mpv_picker_title)
        pickerList = findViewById(R.id.mpv_picker_list)
        // A RecyclerView lays out NOTHING without a layout manager, and this
        // layout declares none - the main player's picker sets one in code for
        // exactly that reason (see NativePlayerActivity). Without this the
        // SOURCES / AUDIO / SUBTITLES panels opened with their title and an
        // empty box under it, which is what "the lists are empty on the MPV
        // player" was: the rows were handed to the adapter and never placed.
        pickerList?.layoutManager = LinearLayoutManager(this)
        // Picker rows are inflated on demand, long after the chrome above was
        // themed, so each one is retinted as it attaches (a recycled row keeps
        // whatever background it was themed with).
        pickerList?.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    if (chromeThemeMoved(this@MpvPlayerActivity)) {
                        refillPlayerChrome(view)
                    }
                }

                override fun onChildViewDetachedFromWindow(view: View) = Unit
            }
        )
        nextUpPanel = findViewById(R.id.mpv_next_up_panel)
        nextUpThumb = findViewById(R.id.mpv_next_up_thumb)
        nextUpShowTitle = findViewById(R.id.mpv_next_up_show_title)
        nextUpEpisodeLabel = findViewById(R.id.mpv_next_up_episode_label)
        nextUpEpisodeTitle = findViewById(R.id.mpv_next_up_episode_title)
        nextUpCountdown = findViewById(R.id.mpv_next_up_countdown)
        btnNextPlay = findViewById(R.id.mpv_next_play)
        btnNextDismiss = findViewById(R.id.mpv_next_dismiss)

        // Same pill treatment the main player's card buttons get: PLAY NEXT is
        // the accent-filled one, EXIT the neutral one.
        btnNextPlay?.let { pillBackground(it, selected = true) }
        btnNextDismiss?.let { pillBackground(it, selected = false) }

        becauseYouWatchedPanel = findViewById(R.id.mpv_byw_panel)
        bywTitle = findViewById(R.id.mpv_byw_title)
        bywRow = findViewById(R.id.mpv_byw_row)
        setupBecauseYouWatched()

    }

    /** Same focus / selection look the main player's pills use. */
    private fun pillBackground(view: TextView, selected: Boolean) {
        applyPillBackground(view, selected, view.isFocused)
    }

    /**
     * The same pill treatment with focus passed in explicitly: the shared
     * end-credits row re-tints its pills from its own focus listeners, so it
     * cannot wait for the view's own flag to be current.
     */
    private fun applyPillBackground(view: TextView, selected: Boolean, focused: Boolean) {
        // Built from the current theme, not the fixed XML pill drawables: those
        // hard-code @color/kb_accent, and their focused variants are
        // layer-lists the accent re-tint walk cannot rebuild, so a selected or
        // focused pill (the Up Next card focuses PLAY NEXT) stayed the default
        // brass on a chosen accent. The neutral fill stays the theme's own
        // surface, so a pure-black theme cannot repaint #141A24 over a pill a
        // selection moves off. See [applyPillLook].
        applyPillLook(this, view, selected, focused)
    }

    /**
     * The MPV chrome is plain XML with fixed @color/kb_surface /
     * @color/kb_surface_raised fills and @color/kb_accent strokes, so the AMOLED
     * / pure-black toggles and the global accent never reached it: a pure-black
     * theme still painted #141A24 buttons and #1D2530 panels, and a chosen
     * accent still ringed the focused button in brass. The main player
     * re-resolves the same fills at runtime through the shared walk
     * ([retintPlayerChrome]); this is that pass, so all three engines' chrome -
     * buttons, panels and their focus rings included - follows the theme
     * together.
     */
    private fun applyPlayerChromeTheme() {
        // The XML chrome is already right only while NEITHER the surface toggles
        // nor the global accent have moved: AMOLED repaints the surface fills
        // and popups, a non-default accent repaints the accent fills, text and
        // tints.
        if (!chromeThemeMoved(this)) return
        val amoled = AppPreferences.getAmoledBlack(this)
        if (amoled) {
            // The end-of-episode popups carry their own fills on top of the
            // chrome walk below.
            applyPlayerPanelTheme()
        }
        // The fatal-error card's @color/kb_void fill is a plain ColorDrawable,
        // which the walk reaches like any other chrome tone - so it no longer
        // needs its own setBackgroundColor(hard-coded black) call here.
        refillPlayerChrome(findViewById(android.R.id.content))
    }

    /**
     * The end-of-episode popups - the Up Next card and the credits
     * recommendations - carry their own fills on top of the chrome walk. This
     * is the MPV engine's copy of the main player's pass of the same name, so
     * both engines' popups follow the AMOLED / pure-black toggles together, and
     * it re-runs when a popup is about to be shown (the theme can move between
     * the session's start and its credits).
     */
    private fun applyPlayerPanelTheme() {
        val raised = playerPanelRaisedColor(this)
        val surface = playerPanelSurfaceColor(this)
        listOf(becauseYouWatchedPanel, nextUpPanel).forEach { panel ->
            panel?.background = roundedPanelDrawable(this, raised, 16f)
        }
        // The next episode's still sits in its own frame, left alone it keeps
        // the XML's fixed @color/kb_surface.
        nextUpThumb?.setBackgroundColor(surface)
        btnNextDismiss?.let { pillBackground(it, selected = false) }
        // The credits panel re-tints its own artwork and pills through the
        // shared panel UI.
        bywUi?.applyTheme()
    }

    /**
     * [applyPlayerChromeTheme]'s walk. Also called for picker rows as they
     * attach: those are inflated on demand, long after the activity's own view
     * tree was themed. The walk itself is shared with the other two engines -
     * see [retintPlayerChrome] - because it is the same chrome and it had
     * already drifted twice as a private copy.
     */
    private fun refillPlayerChrome(root: View) = retintPlayerChrome(root, this)

    /**
     * The main player's control bar, driven by mpv instead: play / pause with
     * the same two icons, next episode when this session knows its episode, then
     * audio / subtitles / speed / aspect / info / settings on the right. LEFT and
     * RIGHT reach the same places they do there, and the overlay hides itself
     * after the same six seconds without input.
     */
    /**
     * Raises the system's now-playing session for this title.
     *
     * Everything the session publishes is read here, on demand, so there is one
     * source of truth for both engines: mpv's own playhead and the same title
     * facts this activity already holds. The transport rules are the main
     * player's, so a handoff does not change what PREVIOUS means - NEXT is the
     * next episode, and PREVIOUS rewinds to the start of this one unless the
     * playhead is already near it, in which case it is the previous episode.
     */
    private fun setupMediaSession() {
        mediaSession = MpvMediaSession(
            context = this,
            owner = this,
            now = {
                MpvMediaSession.Now(
                    title = episodeTitle.orEmpty(),
                    showName = itemName,
                    episodeLabel = if (season != null && episode != null) {
                        "S$season\u2009E$episode"
                    } else {
                        null
                    },
                    posterUrl = itemPoster,
                    durationMs = durationMs,
                    positionMs = positionMs,
                    buffering = bufferingView?.visibility == View.VISIBLE,
                    playing = surface?.isPaused() == false,
                    speed = playbackSpeed
                )
            },
            pendingIntent = { pendingIntentForSession() },
            onPlay = {
                // A finished session stays finished: an external transport play
                // (Bluetooth remote, headset, assistant, remote app) must not
                // unpause a file mpv has already run to its end, which replays
                // it from the top behind the end panel. Replay is an explicit
                // seek or a fresh session, not a toggle.
                if (!endedHandled) {
                    surface?.setPaused(false)
                    keepControlsVisible()
                }
            },
            onPause = {
                surface?.setPaused(true)
                keepControlsVisible()
            },
            onSkipNext = { skipToNextEpisode() },
            onSkipPrevious = {
                // The same rule the main player's session uses: near the start
                // of an episode, PREVIOUS means the one before this.
                if (positionMs > 5_000L) seekTo(0L) else skipToNextEpisode(-1)
            },
            onSeek = { position -> seekTo(position) }
        ).also { it.start() }
    }

    /** The same episode step the control bar's NEXT button takes, either way. */
    private fun skipToNextEpisode(offset: Int = 1) {
        val showSeason = season ?: return
        val showEpisode = episode ?: return
        val target = showEpisode + offset
        if (target < 1) return
        // A negative offset is the PREVIOUS control asking for the episode
        // before this one on purpose. It travels the same result extras the
        // forward step does, which is also how the main player's restartEpisode
        // steps back a title.
        launchNextEpisode(showSeason, target)
    }

    /** Opens this player when a remote app taps the now-playing card. */
    private fun pendingIntentForSession(): android.app.PendingIntent {
        val intent = Intent(this, MpvPlayerActivity::class.java).apply {
            putExtras(this@MpvPlayerActivity.intent)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = android.app.PendingIntent.FLAG_IMMUTABLE or
            android.app.PendingIntent.FLAG_UPDATE_CURRENT
        // A fixed request code: this is the one pending intent for this
        // activity, and re-issuing it updates the existing one.
        return android.app.PendingIntent.getActivity(this, mediaSessionRequestCode, intent, flags)
    }

    /**
     * The on-screen play/pause: the button, the seek bar's OK, and OK with the
     * controls down.
     *
     * A finished session stays finished here too. mpv's toggle is `cycle pause`,
     * so at the end of the file it replays from the top behind the end card -
     * exactly what the media-key guard in dispatchKeyEvent and the MediaSession's
     * play override already refuse. These three are the same press by other
     * names, and OK is the most common one on a TV remote, so they refuse it the
     * same way. Replay stays an explicit seek. Returns true when swallowed.
     */
    private fun togglePlayPauseFromControls(): Boolean {
        if (endedHandled) return true
        surface?.togglePause()
        return false
    }

    private fun setupControls() {
        updateNowPlayingText()
        updateControlsInfo()

        // The shared buttons (play/pause, next, source, audio, subtitles,
        // speed, aspect, switch, external, info, settings) are wired once by
        // PlayerChrome and reach this engine through the PlayerChromeHost
        // methods below. Only what is this engine's own is bound here.
        // Play in another app: offered only where this box has an app to hand
        // the stream to, so the press never dead-ends on a box without one.
        externalButton?.visibility =
            if (PlayerEngine.externalAvailable(this)) View.VISIBLE else View.GONE

        // The end-of-episode card's own buttons, guarded exactly like the main
        // player's: PLAY NEXT restarts the binge watchdog (a manual press means
        // a human is there), EXIT leaves the player.
        btnNextPlay?.setOnClickListener {
            AppPreferences.resetConsecutiveAutoplays(this)
            advanceToPendingNext()
        }
        btnNextDismiss?.setOnClickListener { exitPlayer() }
        listOf(btnNextPlay to true, btnNextDismiss to false).forEach { (button, selected) ->
            button?.setOnFocusChangeListener { view, _ ->
                pillBackground(view as TextView, selected)
            }
        }

        // Focus-hold and the bar's scrub listener are the shared chrome's now;
        // what stays here is this engine's own key handling on the bar.

        // The bar's keys are its own, and they have to be: a bare SeekBar answers
        // LEFT/RIGHT by moving its own thumb, which on this engine slid the bar -
        // and asked for preview frames - without ever asking mpv to seek, so the
        // picture carried on playing under a bar that said otherwise. Walking the
        // bar seeks the video, and OK plays and pauses, because the bar is where
        // the controls land when they come up (see focusControls) - so those are
        // the two presses a viewer makes with the overlay open.
        seekBar?.setOnKeyListener { _, keyCode, event ->
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    // Repeats seek again, which is this engine's hold-to-scrub:
                    // mpv seeks by keyframe, so a long press walks in steps.
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        seekStepBy(
                            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                                -SEEK_STEP_MS
                            } else {
                                SEEK_STEP_MS
                            }
                        )
                        keepControlsVisible()
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    // One press, one toggle: a held OK would otherwise walk the
                    // pause state back and forth. Swallowed at the end of
                    // playback (see togglePlayPauseFromControls).
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        togglePlayPauseFromControls()
                    }
                    true
                }
                else -> false
            }
        }
    }

    // --- Scrub previews -------------------------------------------------------

    /** One seek press - from the D-pad with the overlay down, or the seek bar with it up. */
    private fun seekStepBy(deltaMs: Long) {
        surface?.seekBy(deltaMs)
    }

    /**
     * The press half of the overlay-less hold-to-scrub: one ten-second step
     * now, and the accelerated ramp if the press is still down after
     * [ScrubAcceleration.HOLD_START_MS].
     *
     * A stream with no duration to scrub against (a live channel, or a file
     * that has not reported one yet) keeps the old behavior instead: mpv is
     * asked for one step and the overlay comes up, which is the only feedback
     * such a stream can give.
     */
    private fun beginMpvHoldScrub(direction: Int) {
        val view = surface
        val duration = view?.durationMs()?.takeIf { it > 0 }
        if (view == null || duration == null) {
            seekStepBy(if (direction > 0) SEEK_STEP_MS else -SEEK_STEP_MS)
            showControls()
            return
        }
        mpvScrubDirection = direction
        mpvScrubTargetMs = view.positionMs().coerceIn(0L, duration)
        mpvScrubHeldMs = 0L
        mpvScrubExtended = false
        seekStepBy(SEEK_STEP_MS * direction)
        mpvScrubTargetMs = (mpvScrubTargetMs + SEEK_STEP_MS * direction).coerceIn(0L, duration)
        mpvScrubHandler.removeCallbacks(mpvScrubHoldStarter)
        mpvScrubHandler.postDelayed(mpvScrubHoldStarter, ScrubAcceleration.HOLD_START_MS)
    }

    /**
     * The release half: stop the ramp, land exactly where the viewer scrubbed
     * to, and - for a press that was a tap rather than a hold - bring the
     * overlay up as the old single step did.
     */
    private fun endMpvHoldScrub() {
        val direction = mpvScrubDirection
        val extended = mpvScrubExtended
        mpvScrubDirection = 0
        mpvScrubExtended = false
        mpvScrubHeldMs = 0L
        mpvScrubHandler.removeCallbacks(mpvScrubHoldStarter)
        mpvScrubHandler.removeCallbacks(mpvScrubRunnable)
        if (direction == 0) return
        // The ticks seek opportunistically, and the last of a hold may not have
        // landed; this is the one seek that makes the release exact.
        if (extended) {
            surface?.seekTo(mpvScrubTargetMs)
        } else {
            // A tap keeps its old shape: it reveals the controls.
            showControls()
        }
    }

    // --- Chrome, matched to the main player's -----------------------------

    /**
     * Fills the top block: clear logo or name, episode row, synopsis, and the
     * next-episode button when this session knows which episode it is on.
     */
    private fun updateNowPlayingText() {
        // The title block (logo / name / episode row / overview / NEXT) is drawn
        // by the shared chrome from chromeTitleInfo().
        chrome?.refreshTitle()
    }

    /**
     * The two buttons that read state out in place (aspect, and the speed label
     * the picker leaves behind).
     *
     * The overlay is the badges, exactly like the main player's badge row: this
     * engine's own note - which source file is playing, that mpv is the engine,
     * and why the handoff happened - used to be pinned under them, and it read
     * as a file name plus a line of diagnostics left on screen for the whole
     * session. Which FILE is playing is the badges' job, and how it is being
     * played belongs to the INFO readout (see [diagnosticsText]).
     */
    private fun updateControlsInfo() {
        speedButton?.text = "${playbackSpeed}x"
        aspectButton?.text = ASPECT_MODES.getOrElse(resizeModeIndex) { "Fit" }
        // This runs as the file opens and whenever a title fact changes, which
        // is exactly when the now-playing card needs to be told: the tick in
        // [MpvMediaSession] covers the playhead between those moments.
        mediaSession?.refresh()
    }

    /**
     * Fills the cast band, the same one the main player shows: one focusable
     * tile per member with their TMDB photo, name and character. A press hands
     * the person id back to the catalog (the main player's own navigate_actor
     * contract), so the actor screen opens and this session resumes on return.
     */
    /**
     * Leaves the player for the actor screen: the main player's navigate_actor
     * result, so MainActivity opens the person page and keeps this session's
     * playhead - coming back resumes where it was. The playhead is saved first
     * (as any other exit does), so watch history is right either way.
     */
    private fun navigateToActor(member: PlayerCastMember) {
        val resumeAt = positionMs.coerceAtLeast(0L)
        surface?.setPaused(true)
        saveProgress(reason = "actor")
        setResult(
            RESULT_OK,
            Intent().apply {
                putExtra("player_result_action", "navigate_actor")
                putExtra("actor_person_id", member.id)
                putExtra("actor_resume_position_ms", resumeAt)
            }
        )
        finish()
    }

    /** Cycles the aspect modes and remembers the choice, like the main player. */
    private fun cycleAspect() {
        val next = (resizeModeIndex + 1) % ASPECT_MODES.size
        chooseAspect(next)
        showToast("Aspect: ${ASPECT_MODES[next]}")
    }

    // --- Picker: the main player's panel, driven by mpv ---------------------

    /**
     * Opens the picker on [mode], filled from mpv's own state.
     *
     * The rows come from the same two places the main player's do: the ranked
     * source list this session was launched with, and the tracks mpv found in
     * the file it opened. That is why a SOURCES row and an AUDIO row read the
     * same here as they do there, down to the selection mark.
     */
    private fun showPicker(mode: PickerMode) {
        currentPickerMode = mode
        val items = when (mode) {
            PickerMode.SOURCE -> {
                pickerTitle?.text = "SOURCES"
                sources.map { stream ->
                    PickerItem(
                        label = stream.sourceLabel(),
                        isSelected = stream.url == currentUrl,
                        badges = stream.badges,
                        onClick = {
                            switchToSource(stream)
                            dismissPicker()
                        }
                    )
                }
            }

            PickerMode.AUDIO -> {
                pickerTitle?.text = "AUDIO"
                surface?.audioTracks().orEmpty().map { track ->
                    PickerItem(
                        label = track.label,
                        isSelected = track.selected,
                        onClick = {
                            chooseAudioTrackById(track)
                            dismissPicker()
                        }
                    )
                }
            }

            PickerMode.SUBTITLE -> {
                pickerTitle?.text = "SUBTITLES"
                val tracks = surface?.subtitleTracks().orEmpty()
                // The two ways to bring in a subtitle mpv did not find in the
                // container, in the same place the main player's picker puts
                // them: a file off the device, and a search on OpenSubtitles.
                val openFileItem = PickerItem(
                    label = "OPEN SUBTITLE FILE",
                    isSelected = externalSubtitleUri != null,
                    onClick = {
                        dismissPicker()
                        launchExternalSubtitlePicker()
                    }
                )
                val searchItem = if (AppPreferences.getOpensubtitlesApiKey(this).isNotBlank()) {
                    PickerItem(
                        label = "SEARCH SUBTITLES ONLINE\u2026",
                        onClick = {
                            dismissPicker()
                            startOnlineSubtitleSearch()
                        }
                    )
                } else {
                    null
                }
                // Results of the last search, above the embedded tracks;
                // picking one downloads it and hands the file to mpv.
                val onlineRows = onlineSubResults.map { hit ->
                    PickerItem(
                        label = "${hit.language.uppercase()} \u00b7 ${hit.fileName} \u00b7 ${hit.downloads}\u2193",
                        onClick = {
                            dismissPicker()
                            downloadOnlineSubtitle(hit)
                        }
                    )
                }
                val offItem = PickerItem(
                    label = "OFF",
                    isSelected = tracks.none { it.selected } && externalSubtitleUri == null,
                    onClick = {
                        chooseSubtitlesOff()
                        dismissPicker()
                    }
                )
                // Subtitles the installed addons publish for this video (the
                // Stremio "subtitles" resource): the same offers the main
                // player merges, so the picker reads the same on both engines.
                // Tapping one downloads it and hands the file to libmpv.
                val addonRows = addonSubtitleOffers.map { offer ->
                    PickerItem(
                        label = addonSubtitleLabel(offer),
                        onClick = {
                            dismissPicker()
                            downloadAddonSubtitle(offer)
                        }
                    )
                }
                listOfNotNull(searchItem, openFileItem) + addonRows + onlineRows + listOf(offItem) +
                    tracks.map { track ->
                        PickerItem(
                            label = track.label,
                            isSelected = track.selected,
                            onClick = {
                                // A hand-picked row (an SDH or forced track) is
                                // not the first track in its language, so it is
                                // remembered by signature or the next file open
                                // replaces it.
                                subtitlesOff = false
                                subtitleTrackSignature = track.signature
                                persistTitlePreferences()
                                surface?.selectSubtitleTrack(track.id)
                                clearExternalSubtitle()
                                refreshSettings()
                                dismissPicker()
                            }
                        )
                    }
            }

            PickerMode.SPEED -> {
                pickerTitle?.text = "SPEED"
                SPEED_OPTIONS.map { speed ->
                    PickerItem(
                        label = "${speed}x",
                        isSelected = speed == playbackSpeed,
                        onClick = {
                            chooseSpeed(speed)
                            dismissPicker()
                        }
                    )
                }
            }
        }

        // An empty list is a dead end, not a panel: nothing to pick means the
        // picker stays shut rather than opening on a blank box.
        if (items.isEmpty()) return

        // A side panel is up from here on, so `settingsOpen` carries that fact
        // (it is what the auto-hide and key rules already read - see the field)
        // and `pickerOpen` says the picker is the one showing, since the
        // settings box must not be.
        settingsOpen = true
        pickerOpen = true
        settingsContainer?.visibility = View.GONE
        setControlsVisible(View.VISIBLE)
        // The panel owns the remote while it is up: no auto-hide, and the
        // D-pad goes to the rows.
        removeAutoHide()
        pickerContainer?.visibility = View.VISIBLE
        pickerList?.adapter = PickerAdapter(items)
        pickerList?.post {
            val list = pickerList ?: return@post
            if (list.childCount > 0) list.getChildAt(0).requestFocus() else list.requestFocus()
        }
    }

    /**
     * Closes the picker and hands the D-pad back to the control bar.
     *
     * Clears `settingsOpen` with it: that flag means "a side panel is up",
     * which is what the auto-hide and key rules test, and [pickerOpen] is what
     * says the picker was the panel showing.
     */
    private fun dismissPicker() {
        if (!pickerOpen) return
        pickerOpen = false
        currentPickerMode = null
        settingsOpen = false
        pickerContainer?.visibility = View.GONE
        if (controlsVisible) {
            playPauseButton?.requestFocus()
            keepControlsVisible()
        } else {
            showControls()
        }
    }

    /**
     * Re-opens mpv on another stream from the same list, at the playhead.
     *
     * This is the main player's source switch done with mpv's own load: the
     * activity, the history row and the scrobble stream are the ones already
     * open, so nothing downstream notices the picture came from somewhere else.
     * The end-of-episode latches are reset because this is a fresh load -
     * whatever plays next gets its own Up Next card and its own credits panel,
     * and the position travels with the switch instead of restarting the file.
     */
    // --- External subtitles, the other half of the SUBTITLES picker -------
    //
    // The same two entry points the main player's picker has, driving mpv's
    // `sub-add` instead of a sidecar renderer. Everything mpv is handed is a
    // file path in the app cache, so neither the device picker's content:// URI
    // nor a downloaded subtitle depends on a protocol libmpv may not ship.

    /** Opens the device's file picker on the subtitle types it can deliver. */
    private fun launchExternalSubtitlePicker() {
        runCatching {
            externalSubtitlePicker.launch(subtitleMimeTypes)
        }.onFailure {
            Log.w(TAG, "no document picker for subtitles here", it)
            showToast("No file picker on this device")
        }
    }

    /** There is only ever one external subtitle at a time, as in ExoPlayer. */
    private fun clearExternalSubtitle() {
        externalSubtitleUri = null
        externalSubtitleName = null
    }

    /**
     * Identity of the video the remembered subtitle belongs to. Shows key on
     * show + season + episode (a sidecar file is authored for one episode);
     * everything else falls back to the history item id. The same shape the
     * main player uses, so a subtitle attached there is restored here too.
     */
    private fun subtitleMemoryKey(): String? =
        PlayerTrackMemory.keyFor(
            parentId = parentId,
            mediaId = historyId,
            season = season,
            episode = episode
        )

    /**
     * Brings back the subtitle attached to THIS video last time - the main
     * player does the same on open, and without it a sidecar attached under
     * ExoPlayer vanished the moment the session fell over to this engine.
     * Called once the file is open ([onFileLoaded]), the first moment mpv can
     * accept a `sub-add`.
     */
    private fun restoreRememberedSubtitle() {
        if (subtitleMemoryRestored) return
        subtitleMemoryRestored = true
        if (externalSubtitleUri != null) return
        val remembered = PlayerTrackMemory.rememberedSubtitle(this, subtitleMemoryKey())
            ?: return
        runCatching { Uri.parse(remembered.uri) }.getOrNull()?.let { uri ->
            attachExternalSubtitle(uri, announce = false)
        }
    }

    /**
     * Copies the picked document into the cache and hands it to mpv.
     *
     * A copy rather than the URI itself: mpv resolves plain paths, and a URI
     * whose permission grant dies with this activity would leave the subtitle
     * unreadable part-way through an episode.
     */
    private fun attachExternalSubtitle(uri: Uri, announce: Boolean = true) {
        if (announce) showToast("Loading subtitle\u2026")
        lifecycleScope.launch {
            val copied = runCatchingCancellable {
                withContext(Dispatchers.IO) {
                    // Keyed by the document URI, not the clock: picking the same
                    // sidecar twice must reuse one file rather than leave the
                    // first behind (see DiskSweep). The extension comes from the
                    // display name so mpv still sniffs the right format.
                    val extension = displayNameFor(uri)
                        .substringAfterLast('.', missingDelimiterValue = "")
                        .lowercase()
                        .let { if (it in DiskSweep.SUBTITLE_EXTENSIONS) it else "srt" }
                    val cached = DiskSweep.existingSubtitleFile(
                        this@MpvPlayerActivity,
                        uri.toString(),
                        extension
                    )
                    cached ?: run {
                        val stream = contentResolver.openInputStream(uri)
                            ?: return@withContext null
                        val file = DiskSweep.targetSubtitleFile(
                            this@MpvPlayerActivity,
                            uri.toString(),
                            extension
                        )
                        stream.use { input ->
                            file.outputStream().use { output -> input.copyTo(output) }
                        }
                        file
                    }
                }
            }.getOrNull()
            if (copied == null) {
                if (announce) showToast("Could not read that subtitle file", 4_000L)
                return@launch
            }
            externalSubtitleUri = uri
            // The user's own file name, not the cache file's: the panel note
            // is the only place this is shown, and the cache name is a hash now.
            externalSubtitleName = displayNameFor(uri)
            surface?.addExternalSubtitle(Uri.fromFile(copied).toString())
            // Remembered per video, so reopening this episode re-attaches it
            // without a second pick. Profile-scoped and device-local, exactly
            // as the main player records it.
            PlayerTrackMemory.rememberSubtitle(
                context = this@MpvPlayerActivity,
                key = subtitleMemoryKey(),
                uri = uri.toString()
            )
            if (announce) showToast("Subtitle loaded: ${displayNameFor(uri)}", 4_000L)
            refreshSettings()
        }
    }

    /**
     * One addon subtitle row: the language first, then the addon's own label.
     * Mirrors how the main player names the track it merges in.
     */
    private fun addonSubtitleLabel(offer: SubtitleEntry): String {
        val lang = offer.lang?.uppercase()?.takeIf { it.isNotBlank() }
        // The label already carries the kind the offer's URL names, when the
        // offer's own label named none (see [addonSubtitleRowLabel]) - so a row
        // here reads "EN \u00b7 English \u00b7 SDH" rather than three rows that
        // all read "EN \u00b7 English".
        val label = addonSubtitleRowLabel(offer)
        return listOfNotNull(lang, label).distinct().joinToString(" \u00b7 ")
            .ifBlank { "Addon subtitle" }
    }

    /**
     * Downloads an addon subtitle offer and attaches it, through the same path
     * a picked file takes (cache copy, mpv `sub-add`, remembered per video).
     *
     * A download that is not actually a subtitle - a truncated file, a
     * plain-text limit notice, binary junk - used to be handed to mpv anyway
     * and to draw nothing, exactly the silent no-cues failure the online pick
     * and the auto-fetch already refuse (see
     * [SubtitleSearchHelper.isUsableSubtitleBody]). mpv renders ASS itself, so
     * an ASS body always counts here.
     */
    private fun downloadAddonSubtitle(offer: SubtitleEntry) {
        val url = offer.url
        if (url.isBlank()) return
        lifecycleScope.launch {
            val cached = addonSubtitleDownloads[url] ?: withContext(Dispatchers.IO) {
                AddonSubtitleSource.download(this@MpvPlayerActivity, url)
            }?.also { addonSubtitleDownloads[url] = it }
            if (cached == null) {
                showToast("Could not download that subtitle", 4_000L)
                return@launch
            }
            val body = readAddonSubtitleBody(cached)
            if (body == null ||
                !SubtitleSearchHelper.isUsableSubtitleBody(body, assRenderable = true)
            ) {
                showToast(
                    "Subtitle download failed: the file had no readable subtitles",
                    4_000L
                )
                return@launch
            }
            attachExternalSubtitle(cached)
        }
    }

    /**
     * Reads the whole downloaded addon subtitle back as text for validation,
     * bounded by [MAX_ADDON_SUBTITLE_BYTES]. Null when it cannot be read.
     */
    private suspend fun readAddonSubtitleBody(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val stream = contentResolver.openInputStream(uri)
                ?: return@runCatchingCancellable null
            stream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_ADDON_SUBTITLE_BYTES) return@runCatchingCancellable null
                    out.write(buffer, 0, read)
                }
                out.toString(Charsets.UTF_8.name())
            }
        }.getOrNull()
    }

    /**
     * Fetches the addon-published subtitles for this video in the background,
     * so the SUBTITLE picker can offer them next to the embedded tracks and
     * the file/online paths. The main player merges the same offers as sidecar
     * tracks; here each is downloaded on tap (see [downloadAddonSubtitle]).
     *
     * The id asked about is the session's own episode id - the video the stream
     * was resolved with - through the shared [addonSubtitleVideoId], so this
     * engine and the native one cannot end up asking about different files.
     */
    private fun loadAddonSubtitleOffers() {
        if (parentType == "channel") return
        val videoId = addonSubtitleVideoId(
            context = this,
            parentId = parentId,
            parentType = parentType,
            sessionStreamId = episodeStreamId,
            season = season,
            episode = episode
        ) ?: return
        val contentType = if (parentType == "series") "series" else "movie"
        addonSubtitleFetchJob = lifecycleScope.launch {
            val offers = withContext(Dispatchers.IO) {
                AddonSubtitleSource.discover(this@MpvPlayerActivity, videoId, contentType)
            }
            addonSubtitleOffers = offers
            // Offers that land while the picker is open repaint its rows.
            if (offers.isNotEmpty() && pickerOpen && currentPickerMode == PickerMode.SUBTITLE) {
                showPicker(PickerMode.SUBTITLE)
            }
        }
    }

    /**
     * Queries OpenSubtitles for the playing item, then re-opens the subtitle
     * picker with the hits as rows - the main player's own search, over mpv.
     */
    private fun startOnlineSubtitleSearch() {
        if (onlineSubLoading) return
        if (AppPreferences.getOpensubtitlesApiKey(this).isBlank()) {
            showToast("Add an OpenSubtitles API key in Settings", 4_000L)
            return
        }
        if (itemName.isBlank()) {
            showToast("No title available to search with")
            return
        }
        onlineSubLoading = true
        showToast("Searching subtitles\u2026")
        lifecycleScope.launch {
            val results = SubtitleSearchHelper.search(
                this@MpvPlayerActivity,
                title = itemName,
                season = season,
                episode = episode,
                languageHint = AppPreferences.getPreferredSubtitleLanguage(this@MpvPlayerActivity)
            )
            onlineSubLoading = false
            onlineSubResults = results
            if (results.isEmpty()) showToast("No subtitles found") else showPicker(PickerMode.SUBTITLE)
        }
    }

    /**
     * Downloads the picked hit into cache and hands it to mpv.
     *
     * The failure sentence comes ready-made from the helper: an exhausted daily
     * quota, a rejected API key and an unreachable server need different
     * answers, and this used to say "Subtitle download failed" for all three.
     */
    private fun downloadOnlineSubtitle(hit: SubtitleSearchResult) {
        showToast("Loading subtitle\u2026")
        lifecycleScope.launch {
            when (val result = SubtitleSearchHelper.download(this@MpvPlayerActivity, hit)) {
                is SubtitleDownload.Failed -> showToast(result.reason, 4_000L)

                is SubtitleDownload.Ready -> {
                    // A 200 that is not a subtitle used to reach mpv anyway and
                    // then announce "Subtitle loaded" - a success message for a
                    // track that never drew. Refuse it out loud instead. mpv
                    // renders ASS itself, so an ASS body is always usable here.
                    if (!SubtitleSearchHelper.isUsableSubtitleBody(
                            result.body,
                            assRenderable = true
                        )
                    ) {
                        showToast(
                            "Subtitle download failed: the file had no readable subtitles",
                            4_000L
                        )
                        return@launch
                    }
                    val uri = SubtitleSearchHelper.toCacheUri(this@MpvPlayerActivity, hit, result.body)
                    applyDownloadedSubtitle(hit, uri)
                    showToast("Subtitle loaded: ${hit.fileName}", 4_000L)
                }
            }
        }
    }

    /** Hands a downloaded subtitle to mpv and remembers it for this video. */
    private fun applyDownloadedSubtitle(hit: SubtitleSearchResult, uri: Uri) {
        applyDownloadedSubtitle(hit.fileName, uri)
    }

    /**
     * The same attach, for a track whose pick is no longer in hand - a
     * prefetched file, which the PREVIOUS episode's session chose (see
     * [SubtitlePrefetch]). The name is all the pick was used for.
     */
    private fun applyDownloadedSubtitle(fileName: String, uri: Uri) {
        externalSubtitleUri = uri
        externalSubtitleName = fileName
        surface?.addExternalSubtitle(uri.toString())
        PlayerTrackMemory.rememberSubtitle(
            context = this@MpvPlayerActivity,
            key = subtitleMemoryKey(),
            uri = uri.toString()
        )
        refreshSettings()
    }

    /**
     * Pulls a subtitle from OpenSubtitles when the file carries none the
     * preferred language can use - the MPV half of the main player's
     * auto-fetch (see [NativePlayerActivity.maybeAutoFetchSubtitle]).
     *
     * Runs once per session, off the file-loaded callback, and only when the
     * viewer asked for a subtitle language, set an OpenSubtitles key, left
     * auto-fetch on, and mpv selected no track in that language. The downloaded
     * file is handed to mpv the same way a hand-picked hit is (see
     * [applyDownloadedSubtitle]), so it renders and is remembered identically.
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
        val queryTitle = itemName
        if (queryTitle.isBlank()) return
        val lang = AppPreferences.getPreferredSubtitleLanguage(this)
        if (lang.isBlank()) return
        subtitlePrefetchStarted = true
        lifecycleScope.launch {
            SubtitlePrefetch.prefetchFor(
                this@MpvPlayerActivity,
                title = queryTitle,
                season = nextSeason,
                episode = nextEpisode,
                language = lang
            )
        }
    }

    private fun maybeAutoFetchSubtitle() {
        if (autoSubtitleFetchTried || autoSubtitleFetchInFlight) return
        // Only the language mode fetches: Forced wants foreign-dialogue cues,
        // not a full translation, and Off wants nothing.
        if (SubtitleModeRules.normalized(AppPreferences.getSubtitleMode(this)) !=
            SubtitleModeRules.ON
        ) {
            return
        }
        if (!AppPreferences.getAutoFetchSubtitles(this)) return
        if (AppPreferences.getOpensubtitlesApiKey(this).isBlank()) return
        val queryTitle = itemName
        if (queryTitle.isBlank()) return
        if (externalSubtitleUri != null) return
        // A remembered sidecar for this video is still being restored when this
        // runs (its copy is in flight), so fetching over it would replace the
        // viewer's own earlier choice.
        if (PlayerTrackMemory.rememberedSubtitle(this, subtitleMemoryKey()) != null) return
        val lang = AppPreferences.getPreferredSubtitleLanguage(this)
        if (lang.isBlank()) return
        // mpv already selected a preferred-language track (its `slang` option),
        // so there is nothing to fetch.
        if (surface?.subtitlesOn() == true) return
        autoSubtitleFetchTried = true
        autoSubtitleFetchInFlight = true
        lifecycleScope.launch {
            try {
                // Fetched while the PREVIOUS episode's credits rolled: the
                // search and the download are already done, so attach what is
                // on disk and never touch the network.
                SubtitlePrefetch.get(
                    this@MpvPlayerActivity,
                    title = queryTitle,
                    season = season,
                    episode = episode,
                    language = lang
                )?.let { hit ->
                    Log.i(TAG, "auto subtitle fetch: using prefetched ${hit.fileName}")
                    applyDownloadedSubtitle(hit.fileName, hit.uri)
                    showToast("Subtitles: ${hit.fileName.take(48)}")
                    return@launch
                }
                val results = SubtitleSearchHelper.search(
                    this@MpvPlayerActivity,
                    title = queryTitle,
                    season = season,
                    episode = episode,
                    languageHint = lang
                )
                val pick = AutoSubtitleRules.pick(results, lang) ?: return@launch
                when (val result = SubtitleSearchHelper.download(this@MpvPlayerActivity, pick)) {
                    is SubtitleDownload.Failed -> Log.w(
                        TAG,
                        "auto subtitle fetch came up empty: ${result.reason}"
                    )

                    is SubtitleDownload.Ready -> {
                        // Same rule as the manual picker: a 200 that parses to
                        // nothing is not a subtitle, so it must neither be
                        // attached nor announced as one.
                        if (!SubtitleSearchHelper.isUsableSubtitleBody(
                                result.body,
                                assRenderable = true
                            )
                        ) {
                            Log.w(
                                TAG,
                                "auto subtitle fetch: download parsed to 0 cues, ignoring"
                            )
                            return@launch
                        }
                        val uri = SubtitleSearchHelper.toCacheUri(
                            this@MpvPlayerActivity,
                            pick,
                            result.body
                        )
                        applyDownloadedSubtitle(pick, uri)
                        showToast("Subtitles: ${pick.fileName.take(48)}")
                    }
                }
            } finally {
                autoSubtitleFetchInFlight = false
            }
        }
    }

    /** The picked document's own name, for the cache file and the panel note. */
    private fun displayNameFor(uri: Uri): String {
        val fromProvider = runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()
        return fromProvider
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.takeIf { it.isNotBlank() }
            ?: "subtitle-${System.nanoTime()}.srt"
    }

    /**
     * Resolves the active source's addon display name by matching [label]
     * against the installed addons. Torrent/metadata-only streams and live
     * channels leave it at the raw label, exactly as the main player does.
     */
    private fun resolveAddonIdentity(label: String?) {
        val name = label?.trim().orEmpty()
        if (name.isEmpty()) {
            currentAddonName = null
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
    }

    private fun switchToSource(stream: Stream, isAutoRecovery: Boolean = false) {
        val newUrl = stream.url ?: return
        if (newUrl == currentUrl) return

        // A fresh source starts with an empty stall window: the source just
        // left cannot condemn its replacement, and its seek clock is gone too.
        rebufferDownshift.reset()
        rebufferDownshiftGivenUp = false
        bufferingStartedAtMs = 0L
        lastSeekAtMs = 0L

        val resumeAt = (if (positionMs > 0L) positionMs else startPositionMs).coerceAtLeast(0L)
        currentUrl = newUrl
        currentAudioUrl = stream.audioUrl
        // The switched source brings its OWN request headers. These used to
        // stay at whatever this activity launched with, so a host gated on a
        // Referer / User-Agent answered the new request with the previous
        // source's headers and the source failed - while the same source
        // played fine started fresh from the streams picker. Read them off
        // this stream, exactly as the launch path does.
        streamHeaders = stream.requestHeaders
        currentSourceLabel = stream.sourceLabel()
        currentBadges = stream.badges
        // The switched source brings its OWN binge identity: left behind, the
        // next-episode handoff would carry the previous provider's group and
        // chase a continuity the viewer had just abandoned.
        currentBingeGroup = stream.bingeGroup
        resolveAddonIdentity(currentSourceLabel)
        startPositionMs = resumeAt
        // The stall exclusion in onMpvBufferingChanged tests positionMs <= 0 to
        // skip a source's INITIAL buffering, but the playhead kept its old value
        // across a switch, so a just-switched source's normal load counted as a
        // stall and quietly lowered the downshift threshold by one (PB-P2-6).
        // The resume point lives in startPositionMs, so clearing the live clock
        // is safe; onProgress repopulates it with the new source's position.
        positionMs = 0L
        endedHandled = false
        completionSent = false
        // The end-of-episode cards and their countdown go with the source being
        // replaced: see hideEndPanels. endPanelsShown is reset there, and so is
        // bywDismissed - the replacement file reaches its own end, and the
        // credits panel belongs to THAT one (leaving the flag set would keep it
        // down for the rest of the session).
        hideEndPanels()
        bywDismissed = false
        fileLoaded = false
        autoSkippedSegments.clear()
        // The segment the old file's playhead was inside belongs to that file:
        // the prompt goes with it instead of hanging over the new load.
        activeSkipStamp = null
        hideSkipPrompt()

        Log.w(TAG, "source switch -> ${stream.sourceLabel()} at ${resumeAt}ms")
        // The note reads out which source is playing, so it has to follow.
        updateControlsInfo()
        // A MANUAL source switch is a fresh load: raise the full load splash
        // (backdrop + clear logo) so the viewer sees the new source coming up.
        // An AUTOMATIC switch mid-show (the rebuffer downshift, see
        // [onMpvBufferingChanged]) is not a fresh load - the viewer is already
        // watching, so keep the small buffering spinner and never cover the
        // picture with the splash until the new file reports ready.
        if (isAutoRecovery) {
            loadingContainer?.visibility = View.GONE
            bufferingView?.visibility = View.VISIBLE
        } else {
            showLoading("Switching source…")
        }
        surface?.load(
            MpvPlayerView.LoadRequest(
                url = newUrl,
                headers = stream.requestHeaders,
                audioUrl = currentAudioUrl,
                startPositionMs = resumeAt
            )
        )
    }

    /** Opens the settings side panel and puts focus inside it. */
    private fun showSettingsPanel() {
        val container = settingsContainer ?: return
        // The picker is the other side panel: opening this one closes it.
        pickerOpen = false
        pickerContainer?.visibility = View.GONE
        settingsOpen = true
        removeAutoHide()
        settingsSection?.refresh()
        // ...and the sleep timer's own rows, so the armed pill and its countdown
        // are right the moment the panel appears instead of on the next tick.
        sleepTimerSection?.refresh()
        container.visibility = View.VISIBLE
        focusFirstPill(container)
    }

    /**
     * Closes whichever side panel is up and hands focus back to the control
     * bar: BACK reaches this from either one (see the back callback).
     */
    private fun hideSettingsPanel() {
        settingsOpen = false
        pickerOpen = false
        settingsContainer?.visibility = View.GONE
        pickerContainer?.visibility = View.GONE
        setControlsVisible(View.VISIBLE)
        playPauseButton?.requestFocus()
        keepControlsVisible()
    }

    private fun refreshSettings() {
        settingsSection?.refresh()
    }

    /** First focusable descendant, so a D-pad press lands inside the panel. */
    private fun focusFirstPill(container: View): Boolean {
        val queue = ArrayDeque<View>()
        queue.add(container)
        while (queue.isNotEmpty()) {
            val view = queue.removeFirst()
            if (view !== container && view.isFocusable && view.visibility == View.VISIBLE) {
                return view.requestFocus()
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) queue.add(view.getChildAt(index))
            }
        }
        return false
    }

    // --- What this title remembers ------------------------------------------

    /** The language in force: this title's choice, else the global preference. */
    private fun effectiveAudioLanguage(): String =
        audioLanguage.ifBlank { AppPreferences.getPreferredAudioLanguage(this) }

    private fun effectiveSubtitleLanguage(): String =
        subtitleLanguage.ifBlank { AppPreferences.getPreferredSubtitleLanguage(this) }

    /**
     * Restores this title's remembered choices, plus the global display and
     * playback preferences - the same split the main player makes: languages,
     * A/V offsets and the specific track are per title, the subtitle look, the
     * aspect and the speed are global.
     */
    private fun loadTitlePreferences() {
        val remembered = PlayerTitlePrefs.get(this, titleKey)
        // Canonicalized on the way in, exactly as in the main player: a language
        // remembered from a track tag ("eng") has to come back as the code the
        // pills and the global setting use ("en").
        audioLanguage = LanguageMatch.canonical(remembered?.audioLang).orEmpty()
        subtitleLanguage = LanguageMatch.canonical(remembered?.subtitleLang).orEmpty()
        audioDelayMs = remembered?.audioDelayMs ?: 0
        subtitleOffsetMs = remembered?.subtitleOffsetMs ?: 0
        audioTrackSignature = remembered?.audioTrackSignature.orEmpty()
        subtitlesOff = remembered?.subtitleOff == true
        subtitleTrackSignature = remembered?.subtitleTrackSignature.orEmpty()
        audioDownmix = remembered?.audioDownmix ?: -1
        audioDialogueBoost = remembered?.audioDialogueBoost ?: -1
        audioVolumeBoostDb = remembered?.audioVolumeBoostDb ?: -1

        playbackSpeed = 1f
        // Aspect is per-title now: this show's own override wins, and every
        // title without one keeps following Settings' default.
        resizeModeIndex = remembered?.aspectRatio?.takeIf { it >= 0 }
            ?: AppPreferences.getDefaultAspectRatio(this)
        subtitleSize = AppPreferences.getDefaultSubtitleSize(this)
        subtitleBackground = AppPreferences.getDefaultSubtitleBackground(this)
        subtitlePosition = AppPreferences.getDefaultSubtitlePosition(this)
    }

    private fun persistTitlePreferences() {
        val key = titleKey ?: return
        // The aspect override is written by chooseAspect (it is not part of the
        // panel state this method carries), so preserve whatever is stored
        // instead of resetting it to "follow global" on the next track change.
        val storedAspect = PlayerTitlePrefs.get(this, key)?.aspectRatio ?: -1
        PlayerTitlePrefs.remember(
            context = this,
            key = key,
            prefs = PlayerTitlePrefs.Prefs(
                audioLang = audioLanguage,
                subtitleLang = subtitleLanguage,
                subtitleOffsetMs = subtitleOffsetMs,
                audioDelayMs = audioDelayMs,
                audioTrackSignature = audioTrackSignature,
                subtitleOff = subtitlesOff,
                subtitleTrackSignature = subtitleTrackSignature,
                audioDownmix = audioDownmix,
                audioDialogueBoost = audioDialogueBoost,
                audioVolumeBoostDb = audioVolumeBoostDb,
                aspectRatio = storedAspect
            )
        )
    }

    /** Re-states the remembered track and languages once the file is open. */
    private fun applyRememberedTracks() {
        val view = surface ?: return
        if (audioTrackSignature.isNotBlank()) {
            view.applyRememberedAudioTrack(audioTrackSignature)
        } else {
            view.selectAudioLanguage(effectiveAudioLanguage())
        }
        // Subtitle priority, the same order the main player's bridge uses. An
        // explicit per-show OFF wins over everything, so the next episode of a
        // show whose subtitles were turned off opens with sid=no even when the
        // global mode is On. Then the specific track the viewer picked (an SDH
        // or forced row is not the first track in its language, so a
        // language-only pass would replace it - the "my subtitle pick reverted"
        // report). Only then does the global On mode re-assert a language. OFF
        // wins over a remembered sidecar too: the external subtitle is attached
        // before this runs, and clearSubtitles (sid=no) deselects it.
        if (subtitlesOff) {
            view.clearSubtitles()
        } else if (subtitleTrackSignature.isNotBlank()) {
            view.applyRememberedSubtitleTrack(subtitleTrackSignature)
        } else if (SubtitleModeRules.normalized(AppPreferences.getSubtitleMode(this)) ==
            SubtitleModeRules.ON
        ) {
            view.selectSubtitleLanguage(effectiveSubtitleLanguage())
        }
        refreshSettings()
    }

    // --- State the settings panel reads and writes --------------------------
    //
    // Same shape as PlayerTrackBridge, which is what the main player's panel
    // talks to, so the two panels read the same way even though one drives
    // mpv and the other drives ExoPlayer.

    internal fun audioLanguage(): String = audioLanguage
    internal fun subtitleLanguage(): String = subtitleLanguage
    internal fun audioDelayMs(): Int = audioDelayMs
    internal fun subtitleOffsetMs(): Int = subtitleOffsetMs
    internal fun audioTrackSignature(): String = audioTrackSignature
    internal fun playbackSpeed(): Float = playbackSpeed
    internal fun aspectModeIndex(): Int = resizeModeIndex
    internal fun subtitleSize(): Int = subtitleSize
    internal fun subtitleBackground(): Int = subtitleBackground
    internal fun subtitlePosition(): Int = subtitlePosition
    internal fun hardwareDecoding(): Boolean = surface?.isHardwareDecoding() != false
    internal fun hasTitleMemory(): Boolean = titleKey != null

    // Audio tuning. The panel marks its pills with the OVERRIDE (-1 = follow the
    // global Settings value) and reads the resolved one out in the notes, which
    // is exactly how the main player's AUDIO section reads.
    internal fun audioDownmixOverride(): Int = audioDownmix
    internal fun dialogueBoostOverride(): Int = audioDialogueBoost
    internal fun volumeBoostOverride(): Int = audioVolumeBoostDb
    internal fun effectiveAudioDownmix(): Int =
        audioDownmix.takeIf { it >= 0 } ?: AppPreferences.getAudioDownmix(this)

    internal fun effectiveAudioDialogueBoost(): Int =
        audioDialogueBoost.takeIf { it >= 0 } ?: AppPreferences.getAudioDialogueBoost(this)

    internal fun effectiveAudioVolumeBoost(): Int =
        audioVolumeBoostDb.takeIf { it >= 0 } ?: AppPreferences.getAudioVolumeBoostDb(this)

    internal fun bufferMode(): Int = AppPreferences.getDefaultBufferMode(this)

    /** The loaded sidecar's name, or null; the panel shows it under SUBTITLES. */
    internal fun externalSubtitleNote(): String? = externalSubtitleName

    internal fun diagnosticsText(): String =
        surface?.diagnostics().orEmpty().ifBlank { "Waiting for the file to open" }

    internal fun languageMemoryNote(): String = PlayerTrackBridge.languageSummary(
        audioLanguage = audioLanguage,
        subtitleLanguage = subtitleLanguage,
        globalAudioLanguage = AppPreferences.getPreferredAudioLanguage(this),
        globalSubtitleLanguage = AppPreferences.getPreferredSubtitleLanguage(this)
    )

    /** The file's audio tracks as (label, signature), for the panel's list. */
    internal fun audioTrackOptions(): List<Pair<String, String>> =
        surface?.audioTracks().orEmpty().map { it.label to it.signature }

    internal fun chooseAudioLanguage(code: String) {
        audioLanguage = code
        // A language choice means "any track in this language": drop the more
        // specific track choice so the two cannot disagree.
        audioTrackSignature = ""
        persistTitlePreferences()
        val effective = effectiveAudioLanguage()
        surface?.selectAudioLanguage(effective)
        surface?.setLanguagePreferences(effective, effectiveSubtitleLanguage())
        refreshSettings()
    }

    internal fun chooseSubtitleLanguage(code: String) {
        subtitleLanguage = code
        // A language choice means "any track in this language": drop the more
        // specific OFF and track pick so the two cannot disagree, exactly like
        // the main player's bridge.
        subtitlesOff = false
        subtitleTrackSignature = ""
        persistTitlePreferences()
        val effective = effectiveSubtitleLanguage()
        surface?.setLanguagePreferences(effectiveAudioLanguage(), effective)
        surface?.selectSubtitleLanguage(effective)
        refreshSettings()
    }

    /** The picker's OFF row: explicit per-show "no subtitles", remembered. */
    internal fun chooseSubtitlesOff() {
        subtitlesOff = true
        subtitleTrackSignature = ""
        persistTitlePreferences()
        surface?.clearSubtitles()
        clearExternalSubtitle()
        refreshSettings()
    }

    internal fun chooseAudioTrack(signature: String) {
        audioTrackSignature = signature
        persistTitlePreferences()
        if (signature.isBlank()) {
            surface?.selectAudioLanguage(effectiveAudioLanguage())
        } else {
            surface?.applyRememberedAudioTrack(signature)
        }
        refreshSettings()
    }

    /**
     * An audio track picked by hand in the picker.
     *
     * mpv gets the exact track id, not the signature: one file often lists the
     * same language and codec twice (5.1 and stereo, say), and the id is what
     * tells those rows apart. The per-title memory still keeps the signature,
     * so the next session can look the choice up again.
     */
    private fun chooseAudioTrackById(track: MpvPlayerView.Track) {
        audioTrackSignature = track.signature
        persistTitlePreferences()
        surface?.selectAudioTrack(track.id)
        refreshSettings()
    }

    internal fun chooseAudioDelay(ms: Int) {
        audioDelayMs = ms.coerceIn(-5_000, 5_000)
        persistTitlePreferences()
        surface?.setAudioDelayMs(audioDelayMs)
        refreshSettings()
    }

    internal fun chooseSubtitleOffset(ms: Int) {
        subtitleOffsetMs = ms.coerceIn(-5_000, 5_000)
        persistTitlePreferences()
        surface?.setSubtitleDelayMs(subtitleOffsetMs)
        refreshSettings()
    }

    internal fun chooseSubtitleSize(size: Int) {
        subtitleSize = size
        AppPreferences.setDefaultSubtitleSize(this, size)
        surface?.applySubtitleAppearance(subtitleSize, subtitleBackground, subtitlePosition)
        refreshSettings()
    }

    internal fun chooseSubtitleBackground(background: Int) {
        subtitleBackground = background
        AppPreferences.setDefaultSubtitleBackground(this, background)
        surface?.applySubtitleAppearance(subtitleSize, subtitleBackground, subtitlePosition)
        refreshSettings()
    }

    /** [target] -1 = follow the global downmix setting, exactly as ExoPlayer does. */
    internal fun chooseAudioDownmix(target: Int) {
        audioDownmix = target
        persistTitlePreferences()
        surface?.setDownmix(effectiveAudioDownmix())
        refreshSettings()
    }

    internal fun chooseDialogueBoost(level: Int) {
        audioDialogueBoost = level
        persistTitlePreferences()
        surface?.setDialogueBoost(effectiveAudioDialogueBoost())
        refreshSettings()
    }

    internal fun chooseVolumeBoost(db: Int) {
        audioVolumeBoostDb = db
        persistTitlePreferences()
        surface?.setVolumeBoostDb(effectiveAudioVolumeBoost())
        refreshSettings()
    }

    /**
     * The buffering profile is a global choice, not a per-title one: it is about
     * what the box and the connection can do, which does not change with the
     * show. mpv takes it from the next file it opens (see
     * [MpvPlayerView.setBufferMode]), so the note under the row says so.
     */
    internal fun chooseBufferMode(mode: Int) {
        AppPreferences.setDefaultBufferMode(this, mode)
        surface?.setBufferMode(mode)
        refreshSettings()
    }

    internal fun chooseSubtitlePosition(position: Int) {
        subtitlePosition = position
        AppPreferences.setDefaultSubtitlePosition(this, position)
        surface?.applySubtitleAppearance(subtitleSize, subtitleBackground, subtitlePosition)
        refreshSettings()
    }

    internal fun chooseAspect(index: Int) {
        resizeModeIndex = index
        surface?.setAspectMode(index)
        // Per title, like the languages: Settings' aspect row stays the global
        // default for shows without an override.
        PlayerTitlePrefs.remember(
            this,
            titleKey,
            (PlayerTitlePrefs.get(this, titleKey) ?: PlayerTitlePrefs.Prefs())
                .copy(aspectRatio = index)
        )
        updateControlsInfo()
        refreshSettings()
    }

    internal fun chooseSpeed(speed: Float) {
        playbackSpeed = speed
        surface?.setSpeed(speed.toDouble())
        updateControlsInfo()
        refreshSettings()
    }

    internal fun chooseHardwareDecoding(enabled: Boolean) {
        surface?.setHardwareDecoding(enabled)
        refreshSettings()
    }

    /** Drops this title's remembered choices and goes back to the defaults. */
    internal fun forgetThisTitle() {
        PlayerTitlePrefs.forget(this, titleKey)
        audioLanguage = ""
        subtitleLanguage = ""
        subtitlesOff = false
        subtitleTrackSignature = ""
        audioTrackSignature = ""
        audioDelayMs = 0
        subtitleOffsetMs = 0
        // The aspect override is dropped with the rest, so the picture goes
        // back to the global default.
        resizeModeIndex = AppPreferences.getDefaultAspectRatio(this)
        surface?.setAspectMode(resizeModeIndex)
        // Audio tuning goes back to the global defaults with the rest: the
        // three knobs belong to this title and nothing else here.
        audioDownmix = -1
        audioDialogueBoost = -1
        audioVolumeBoostDb = -1
        surface?.setAudioDelayMs(0)
        surface?.setSubtitleDelayMs(0)
        surface?.setDownmix(effectiveAudioDownmix())
        surface?.setDialogueBoost(effectiveAudioDialogueBoost())
        surface?.setVolumeBoostDb(effectiveAudioVolumeBoost())
        surface?.setLanguagePreferences(
            AppPreferences.getPreferredAudioLanguage(this),
            AppPreferences.getPreferredSubtitleLanguage(this)
        )
        surface?.selectAudioLanguage(effectiveAudioLanguage())
        surface?.selectSubtitleLanguage(effectiveSubtitleLanguage())
        refreshSettings()
    }

    // --- End of episode: the Up Next card ----------------------------------

    /**
     * Raises the end-of-episode panel as the credits roll rather than waiting
     * for the file to end, at the point the user set for the panel this session
     * will raise - the Up Next card for a series episode, the credits
     * recommendations for anything else - or at the title's own credits marker
     * when IntroDB has one, exactly as the main player times it.
     */
    private fun maybeTriggerEndPanels(position: Long, duration: Long) {
        if (endPanelsShown || endedHandled || duration <= 0L) return
        // A sleep timer armed to stop at the end of this episode suppresses the
        // card entirely, exactly as it does in the main player: offering PLAY
        // NEXT while the timer is about to end the session is a promise this
        // player cannot keep.
        if (sleepTimerBlocksAutoAdvance(SleepTimer.state.value)) return
        // Both panels switched off: nothing to raise, so the file runs to its
        // own end instead. Acting on the point anyway would cut the last minutes
        // off with nothing to show for them.
        if (!AppPreferences.getNextEpisodePopup(this) &&
            !AppPreferences.getBecauseYouWatched(this)
        ) return
        val triggerAt = endPanelTriggerMs(duration)
        // A clip shorter than the point would otherwise pop the panel the moment
        // playback starts: onPlaybackEnded covers that case instead.
        if (triggerAt <= 0L) return
        if (position < triggerAt) return
        showEndPanels()
    }

    /**
     * When the end-of-episode panel opens, in milliseconds into the file.
     *
     * The percentage is picked for the panel this session will actually raise,
     * so setting one panel's point earlier cannot drag the other's earlier too.
     *
     * A title's own credits marker beats that percentage: it is a fact about the
     * file, and it is what makes the panel land as the credits start rather than
     * at a percentage that happens to fall mid-scene. Same two constants the
     * main player uses, so the engines cannot disagree about where the credits
     * begin.
     */
    private fun endPanelTriggerMs(durationMs: Long): Long {
        val isEpisode = season != null && episode != null
        val point = if (isEpisode && AppPreferences.getNextEpisodePopup(this)) {
            AppPreferences.getNextEpisodePopupPointTenths(this)
        } else {
            AppPreferences.getBecauseYouWatchedPointTenths(this)
        }
        val percentTrigger =
            (durationMs - durationMs * (1_000 - point) / 1_000L).coerceAtLeast(0L)
        val creditsStart = introDbStamps
            .filter {
                (it.type == IntroDbMarkerType.Credits ||
                    it.type == IntroDbMarkerType.Outro) &&
                    AutoSkipRules.isSkippableSegment(it)
            }
            .minByOrNull { it.startMs }
            ?.startMs
            ?: return percentTrigger
        val markerTrigger = (creditsStart - END_PANEL_CREDITS_LEAD_MS)
            .coerceAtMost(durationMs - END_PANEL_MIN_REMAINING_MS)
        return markerTrigger.coerceAtLeast(0L)
    }

    /**
     * The same decision the main player makes when an episode ends: a next
     * episode that has actually aired raises the Up Next card, and anything else
     * - a movie, a finished finale, an episode that is not out yet - gets the
     * because-you-watched credits recommendations instead. Raised once per
     * session; the air-date gate is the shared helper, so both engines chain to
     * the same place.
     *
     * Either panel can be switched off in Settings, and then it is simply not
     * raised: the session runs out instead.
     */
    private fun showEndPanels() {
        if (endPanelsShown) return
        endPanelsShown = true
        lifecycleScope.launch {
            val target = airedNextEpisodeTarget(
                context = this@MpvPlayerActivity,
                target = nextEpisodeTarget(),
                tmdbId = runCatchingCancellable { tmdbId() }.getOrNull(),
                showId = parentId
            )
            when {
                target != null && AppPreferences.getNextEpisodePopup(this@MpvPlayerActivity) ->
                    showNextUpPanel(target.first, target.second)

                target == null && AppPreferences.getBecauseYouWatched(this@MpvPlayerActivity) ->
                    showBecauseYouWatchedPanel()

                // Otherwise that panel is switched off, or there is nothing to
                // suggest: nothing is raised, and the credits play out.
            }

            // The next episode's subtitle, fetched while the viewer is still on
            // this one, so that episode's auto-fetch starts with the file
            // already on disk instead of paying for the search and the download
            // at playback start.
            if (target != null) prefetchNextEpisodeSubtitle(target.first, target.second)
        }
    }

    /**
     * The FILE episode the session is on now: the trailing number of
     * [episodeStreamId], which the id invariant keeps in file numbering. A
     * session with no stream id falls back to the TMDB episode mapped through
     * the detected scheme - exact for every kind but a split episode's second
     * file, where the id is the only thing that knows both halves are one
     * episode. Mirrors NativePlayerActivity.currentFileEpisode().
     */
    private fun currentFileEpisode(): Int? {
        val showEpisode = episode ?: return null
        episodeStreamId
            ?.substringAfterLast(':')
            ?.trim()
            ?.toIntOrNull()
            ?.takeIf { it >= 1 }
            ?.let { return it }
        return bingeScheme.fileForTmdbEpisode(showEpisode)
    }

    /**
     * Reads the show's detected scheme (see NativePlayerActivity.initBingeScheme):
     * a show this device has played before starts on the right file instead of
     * re-learning the mapping from whichever file it happens to open.
     */
    private fun initBingeScheme() {
        schemeStoreKey = EpisodeSchemeStore.stableShowId(parentId, resolvedTmdbId)
        bingeScheme = EpisodeSchemeStore.get(this, schemeStoreKey)
    }

    /**
     * The TMDB runtime of the episode playing now, in milliseconds, or null when
     * nothing trustworthy says: the launch's own runtime, TMDB's `runtime` for
     * this episode, or the show's average `episode_run_time`. Resolved at most
     * once per session - this is reached from the progress tick, and a runtime
     * that is unknown now is unknown a second later too.
     */
    private suspend fun currentEpisodeTmdbRuntimeMs(): Long? {
        launchRuntimeMinutes?.let { return it * 60_000L }
        val showSeason = season ?: return null
        val showEpisode = episode ?: return null
        if (currentEpisodeRuntimePrefetched) {
            return currentEpisodeRuntimeMinutes?.takeIf { it > 0 }?.let { it * 60_000L }
        }
        currentEpisodeRuntimePrefetched = true
        val minutes = withContext(Dispatchers.IO) {
            val repo = TmdbRepository.getInstance(this@MpvPlayerActivity)
            val tmdb = tmdbId()
            val fromSeason = tmdb?.let { id ->
                runCatchingCancellable {
                    repo.getSeasonEpisodes(id, showSeason, parentId)
                }.getOrNull()
                    ?.firstOrNull { it.episodeNumber == showEpisode }
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
     * Reads the file that is playing against the episode TMDB says it is, and
     * remembers what that says about the show (see EpisodeScheme.detect). Runs
     * from the progress tick, so it does nothing until a duration and a runtime
     * both exist, and at most once per file. Mirrors
     * NativePlayerActivity.maybeDetectScheme().
     */
    private fun maybeDetectScheme() {
        val sid = episodeStreamId ?: return
        if (schemeDetectedForFileId == sid) return
        val fileMs = durationMs
        if (fileMs <= 0L) return
        lifecycleScope.launch {
            if (schemeStoreKey == null) {
                schemeStoreKey = EpisodeSchemeStore.stableShowId(parentId, tmdbId())
                bingeScheme = EpisodeSchemeStore.get(this@MpvPlayerActivity, schemeStoreKey)
            }
            val tmdbMs = currentEpisodeTmdbRuntimeMs() ?: return@launch
            // The tick that started this coroutine may have been for a file the
            // session has already left, so the guard is re-read inside.
            if (schemeDetectedForFileId == sid) return@launch
            val detected = EpisodeScheme.detect(fileMs, tmdbMs)
            bingeScheme = detected
            EpisodeSchemeStore.put(this@MpvPlayerActivity, schemeStoreKey, detected)
            schemeDetectedForFileId = sid
            com.kennyb1201.kbstream.data.reporting.PlaybackSessionTrace.note(
                "scheme ${detected.encode() ?: "1:1"} for $sid " +
                    "(file=${fileMs}ms tmdb=${tmdbMs}ms)"
            )
        }
    }

    /**
     * The TMDB episodes the file that just finished covered: one for every
     * scheme but SEGMENTS_PER_FILE, which holds its own factor. Without this the
     * second segment of a doubled file is never marked and the tracker keeps
     * offering it. Anchored on the FILE cursor, not the session's TMDB label,
     * for the same reason as the main player: a session entered at a non-first
     * segment would otherwise mark the wrong episodes. Mirrors
     * NativePlayerActivity.coveredTmdbEpisodes().
     */
    private fun coveredTmdbEpisodes(tmdbEpisode: Int): List<Int> {
        val fileEpisode = currentFileEpisode() ?: tmdbEpisode
        return bingeScheme.episodesOfFileClamped(fileEpisode, totalEpisodesInSeason)
    }

    /**
     * The episode the end of a session should chain into: the next one, or the
     * first of the next season when this was the season's last. Mirrors
     * NativePlayerActivity.nextEpisodeTarget() so both engines chain alike,
     * including the detected episode scheme's own arithmetic.
     */
    private fun nextEpisodeTarget(): Pair<Int, Int>? {
        val showSeason = season ?: return null
        val showEpisode = episode ?: return null
        val fileEpisode = currentFileEpisode() ?: showEpisode
        // The label of the next FILE, not the session's label plus the factor:
        // the two differ for a session entered at a non-first segment. See
        // NativePlayerActivity.nextEpisodeTarget().
        val nextEpisode = bingeScheme.labelForFile(fileEpisode + 1)
        val maxEpisodes = totalEpisodesInSeason
        return if (maxEpisodes != null && nextEpisode > maxEpisodes) {
            (showSeason + 1) to 1
        } else {
            showSeason to nextEpisode
        }
    }

    /**
     * The FILE number for a target the labels call [targetEpisode]. The
     * arithmetic next episode is wherever the file cursor goes (the second half
     * of the episode already playing, when two files share one); any other
     * target is the file that HOLDS that TMDB episode. Mirrors
     * NativePlayerActivity.nextFileEpisodeFor().
     */
    private fun nextFileEpisodeFor(targetSeason: Int, targetEpisode: Int): Int {
        val showSeason = season
        val showEpisode = episode
        if (showSeason != null && showEpisode != null && targetSeason == showSeason) {
            val fileEpisode = currentFileEpisode() ?: showEpisode
            val (nextFile, nextEpisode) = bingeScheme.advance(fileEpisode, showEpisode)
            if (nextEpisode == targetEpisode) return nextFile
        }
        return bingeScheme.fileForTmdbEpisode(targetEpisode)
    }

    /** Fills and shows the card, then arms its countdown. */
    private fun showNextUpPanel(targetSeason: Int, targetEpisode: Int) {
        val panel = nextUpPanel ?: return
        pendingNextSeason = targetSeason
        pendingNextEpisode = targetEpisode
        pendingNextEpisodeName = null
        pendingNextEpisodeOverview = null

        // The session's name can be an internal id when the card that launched
        // it had no name of its own and enrichment failed (see
        // upNextPlayerDisplayName, which stops that at the sending end - this is
        // the receiving belt-and-braces, for a name that arrived by another
        // route: a persisted NextEpisodeResult, a deep link, the picker). An id
        // printed as the show title is worse than saying nothing, because the
        // "Season 4 • Episode 41" line below still says what is coming.
        if (looksLikeRawMediaId(itemName, hasArtwork = !(backdropUrl ?: itemPoster).isNullOrBlank())) {
            nextUpShowTitle?.visibility = android.view.View.GONE
        } else {
            nextUpShowTitle?.visibility = android.view.View.VISIBLE
            nextUpShowTitle?.text = itemName
        }
        nextUpEpisodeLabel?.text = "Season $targetSeason \u2022 Episode $targetEpisode"
        nextUpEpisodeTitle?.text = "S${targetSeason}E$targetEpisode"
        nextUpCountdown?.text = ""

        // The show's art first, swapped for the episode's own still when TMDB
        // answers - the same order the main player's card uses.
        val initialThumb = backdropUrl ?: itemPoster
        if (!initialThumb.isNullOrBlank()) {
            runCatching { nextUpThumb?.load(initialThumb) }
        } else {
            nextUpThumb?.setImageDrawable(null)
        }

        panel.visibility = View.VISIBLE
        btnNextPlay?.requestFocus()

        if (AppPreferences.getAutoPlayNext(this)) {
            val threshold = AppPreferences.getStillThereEpisodes(this).toInt()
            val autoAdvanced = AppPreferences.getConsecutiveAutoplays(this)
            if (AppPreferences.getStillTherePrompt(this) && autoAdvanced >= threshold) {
                // Binge watchdog: this many unattended episodes in a row, so hold
                // here and make the viewer confirm they are awake.
                nextUpCountdownHeld = false
                nextUpCountdownRemaining = 0
                nextUpCountdown?.text = "Are you still there? Press PLAY NEXT to continue"
                nextUpCountdown?.setTextColor(themeAccentColor(this))
                nextUpCountdownHandler.removeCallbacks(nextUpCountdownRunnable)
                return
            }
            armNextUpAutoAdvance(remainingMs = (durationMs - positionMs).coerceAtLeast(0L))
        } else {
            nextUpCountdownHeld = false
            nextUpCountdownRemaining = 0
            nextUpCountdown?.text = "PLAY NEXT to continue, or press BACK to exit"
        }

        fetchNextEpisodeDetails(targetSeason, targetEpisode)
    }

    /**
     * Arms the auto-advance. Held while the episode still has more than
     * [NEXT_UP_HOLD_THRESHOLD_MS] to run, so the countdown cannot cut the last
     * minute off it - the card is already up during the credits.
     */
    private fun armNextUpAutoAdvance(remainingMs: Long) {
        nextUpCountdownHandler.removeCallbacks(nextUpCountdownRunnable)
        if (remainingMs > NEXT_UP_HOLD_THRESHOLD_MS) {
            nextUpCountdownHeld = true
            nextUpCountdown?.text = "Playing next when this episode ends"
            nextUpCountdown?.setTextColor(getColor(R.color.kb_text_lo))
            return
        }
        nextUpCountdownHeld = false
        nextUpCountdownRemaining = NEXT_UP_COUNTDOWN_SECONDS
        nextUpCountdown?.text = "Playing next in $nextUpCountdownRemaining"
        nextUpCountdown?.setTextColor(getColor(R.color.kb_text_lo))
        nextUpCountdownHandler.postDelayed(nextUpCountdownRunnable, 1_000L)
    }

    /** Hands the pending episode to MainActivity, the way the main player does. */
    private fun advanceToPendingNext() {
        val showSeason = pendingNextSeason ?: return
        val showEpisode = pendingNextEpisode ?: return
        launchNextEpisode(showSeason, showEpisode, pendingNextEpisodeOverview)
    }

    /**
     * Best effort: the next episode's real name and still from TMDB, so the card
     * offers something concrete instead of just S#E#. Same source and same
     * fields the main player's card uses; a miss leaves the episode code in
     * place, which is what that card shows while it is still fetching.
     */
    private fun fetchNextEpisodeDetails(targetSeason: Int, targetEpisode: Int) {
        lifecycleScope.launch {
            val tmdb = withContext(Dispatchers.IO) {
                runCatchingCancellable { tmdbId() }.getOrNull()
            } ?: return@launch
            val nextEp = withContext(Dispatchers.IO) {
                runCatchingCancellable {
                    TmdbRepository.getInstance(this@MpvPlayerActivity)
                        .getSeasonEpisodes(tmdb, targetSeason, parentId)
                }.getOrNull()?.firstOrNull { it.episodeNumber == targetEpisode }
            } ?: return@launch
            if (nextUpPanel?.visibility != View.VISIBLE) return@launch
            pendingNextEpisodeName = nextEp.name
            pendingNextEpisodeOverview = nextEp.overview?.takeIf { it.isNotBlank() }
            // Spoiler-free mode. The panel offers the episode the viewer has NOT
            // reached, so the name just resolved and a frame from it are what
            // the mode hides; the panel keeps the show's artwork (loaded when it
            // was raised) and the S#E# line, so it still says which episode is
            // coming without saying which one it is. The handoff values above
            // stay whole - they are what that episode's own session opens with,
            // not what is drawn here.
            val hideNextEpisode = com.kennyb1201.kbstream.data.spoiler.SpoilerFree
                .hidesIdentity(
                    enabled = AppPreferences.getSpoilerFree(this@MpvPlayerActivity),
                    // Not watched, not started: the viewer is at the end of the
                    // episode before it. A rewatch is the only case where they
                    // have seen it, and there the cost is a number.
                    watched = false,
                    started = false
                )
            nextUpEpisodeTitle?.text =
                if (hideNextEpisode) {
                    "S${targetSeason}E$targetEpisode"
                } else {
                    nextEp.name ?: "S${targetSeason}E$targetEpisode"
                }
            if (!hideNextEpisode) {
                nextEp.thumbnail?.takeIf { it.isNotBlank() }?.let { still ->
                    runCatching { nextUpThumb?.load(still) }
                }
            }
        }
    }

    // --- Because you watched (end credits) ---

    /**
     * Raises the credits recommendations: TMDB picks for the title that just
     * finished, each with PLAY (resolve the top stream, straight into the next
     * playback) and DETAILS (deep-link into the catalog detail screen). Runs
     * only when there is no aired episode to chain, so the Up Next card keeps
     * owning the series flow, and the row comes from the shared
     * [BecauseYouWatchedUi] so it looks and drives like the main player's.
     */
    private fun showBecauseYouWatchedPanel() {
        val ui = bywUi
        // Never bound, or already answered: nothing to raise. Deliberately not
        // an exit - the panel opens while the credits are still rolling, and
        // closing the session there would cut them short.
        if (ui == null || bywDismissed) return
        ui.show(itemName)
        // The credits themselves are still rolling: shrink the video into the
        // corner so the picks get the screen, and take the chrome down with it -
        // the panel owns the remote while it is up.
        removeAutoHide()
        setControlsVisible(View.GONE)
        enterCreditsMode()

        lifecycleScope.launch {
            val picks: List<BywPick> = withContext(Dispatchers.IO) {
                val tmdb = runCatchingCancellable { tmdbId() }.getOrNull()
                    ?: return@withContext emptyList()
                buildBecauseYouWatchedPicks(
                    this@MpvPlayerActivity,
                    tmdb,
                    bywMediaType(parentType),
                    // The profile's own ceiling (null on an ordinary profile):
                    // a kids profile's credits row is held to it, exactly like
                    // every rail behind the player.
                    activeKidsMaxAge()
                )
            }

            if (picks.isEmpty() || !ui.isVisible) {
                // Nothing to recommend: put the video back full screen and let
                // the session run its course, exactly as the main player does.
                withContext(Dispatchers.Main) {
                    ui.hide()
                    exitCreditsMode()
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                ui.build(picks)
            }
        }
    }

    /**
     * Builds the row once the panel views exist. The cards, the featured strip
     * and the focus rules are the shared panel UI's; this supplies only what is
     * this engine's own - its theme colors, its pill styling, and where PLAY /
     * DETAILS go.
     */
    private fun setupBecauseYouWatched() {
        val panel = becauseYouWatchedPanel ?: return
        val title = bywTitle ?: return
        val row = bywRow ?: return
        bywUi = BecauseYouWatchedUi(
            host = this,
            panel = panel,
            title = title,
            row = row,
            surfaceColor = { playerPanelSurfaceColor(this) },
            raisedColor = { playerPanelRaisedColor(this) },
            applyPill = { pill, selected, focused ->
                applyPillBackground(pill, selected, focused)
            },
            scope = { lifecycleScope },
            onPlay = { pick, imdbId -> bywPlayPick(pick, imdbId) },
            onDetails = { pick, imdbId -> bywOpenDetails(pick, imdbId) }
        )
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

    /** DETAILS: hand the pick back for the catalog's detail screen. */
    private fun bywOpenDetails(pick: BywPick, imdbId: String) {
        bywDismissed = true
        finishWithBywResult(action = "go_details", pick = pick, imdbId = imdbId)
    }

    /**
     * The result contract MainActivity already understands from the main
     * player: "play_now" applies the autoplay rule for the pick (the best
     * source when auto-select is on, the source list when it is off), and
     * "go_details" opens the detail screen.
     */
    private fun finishWithBywResult(
        action: String,
        pick: BywPick,
        imdbId: String
    ) {
        surface?.setPaused(true)
        setResult(
            RESULT_OK,
            Intent().apply {
                putExtra("player_result_action", action)
                putExtra("byw_type", pick.type)
                putExtra("byw_id", imdbId)
                putExtra("byw_name", pick.name)
                putExtra("byw_poster", pick.posterUrl)
                putExtra("byw_backdrop", pick.backdropUrl)
            }
        )
        finish()
    }

    /**
     * The credits-recommendations arrangement, the same as the main player's:
     * shrink the video (the credits themselves) into the TOP-RIGHT corner so the
     * picks get the screen, and spread the panel across it.
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
        val panel = becauseYouWatchedPanel ?: return
        val view = surface ?: return
        creditsModeActive = true
        val density = resources.displayMetrics.density
        val screenW = resources.displayMetrics.widthPixels
        val pipW = (screenW * 0.32f).toInt()
        val pipH = pipW * 9 / 16
        val margin = (24 * density).toInt()
        (view.layoutParams as? FrameLayout.LayoutParams)?.apply {
            width = pipW
            height = pipH
            gravity = Gravity.TOP or Gravity.END
            setMargins(margin, margin, margin, margin)
        }
        view.requestLayout()

        // The notch is the video plus the gap the panel used to leave between
        // itself and the video: exactly the width the header gives up.
        val inset = pipW + margin
        creditsModePanelParams = panel.layoutParams as? FrameLayout.LayoutParams
        panel.layoutParams = FrameLayout.LayoutParams(
            screenW - margin * 2,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            setMargins(margin, margin, margin, margin)
        }
        bywUi?.setCreditsLayout(inset, pipH)
        panel.requestLayout()
    }

    /** Restores the video to full screen and the panel to its XML box. */
    private fun exitCreditsMode() {
        if (!creditsModeActive) return
        creditsModeActive = false
        bywUi?.setCreditsLayout(0, 0)
        (surface?.layoutParams as? FrameLayout.LayoutParams)?.apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = ViewGroup.LayoutParams.MATCH_PARENT
            gravity = Gravity.TOP or Gravity.START
            setMargins(0, 0, 0, 0)
        }
        surface?.requestLayout()
        creditsModePanelParams?.let { saved ->
            becauseYouWatchedPanel?.layoutParams = saved
            creditsModePanelParams = null
            becauseYouWatchedPanel?.requestLayout()
        }
    }

    // --- IntroDB: the skip prompt ---

    /**
     * Starts the segment lookup for this session. Called once from onCreate,
     * where the ids and the episode numbers are already known; the rows only
     * matter once the file is open, so there is nothing to wait for.
     *
     * The fetcher handles both id shapes itself - an IMDb id directly, a numeric
     * TMDB id through the secondary source - so this engine hands it what the
     * intent carried and nothing more.
     */
    private fun setupIntroDb() {
        lifecycleScope.launch {
            val stamps = withContext(Dispatchers.IO) {
                runCatchingCancellable { fetchIntroDbStamps(parentId, season, episode) }
                    .getOrElse { error ->
                        Log.w(TAG, "IntroDB lookup failed", error)
                        emptyList()
                    }
            }
            introDbStamps = stamps
            if (stamps.isNotEmpty()) {
                Log.i(TAG, "IntroDB: ${stamps.size} segments for ${itemTitle()}")
            }
        }
    }

    /**
     * Offers the skip prompt for whatever segment the playhead is inside, and
     * skips it outright when the Settings prefs ask for that. Driven from the
     * progress callback rather than a timer of its own: mpv reports the playhead
     * continuously while the file is open, which is exactly the input this needs.
     *
     * Nothing is offered while a panel owns the screen or the seekbar is being
     * dragged: a raised panel already has the credits in hand (it is showing
     * what comes next), it draws over the prompt, and it owns OK - so the prompt
     * there would be a button with no way to press it.
     */
    private fun updateSkipPrompt(positionMs: Long, durationMs: Long) {
        val bar = chrome ?: return
        // Only while the file is actually playing. A segment starting at 0 (a
        // recap, usually) would otherwise match during the load splash, when the
        // playhead is still 0 - the prompt must never appear before the first
        // frame. Staying up while paused would fight the pause.
        val offers = fileLoaded &&
            surface?.isPaused() != true &&
            !scrubbing &&
            !settingsOpen &&
            nextUpPanel?.visibility != View.VISIBLE &&
            bywUi?.isVisible != true
        val matching = if (offers) {
            introDbStamps.firstOrNull { stamp ->
                positionMs >= stamp.startMs && positionMs < stamp.endMs &&
                    AutoSkipRules.isSkippableSegment(stamp)
            }
        } else {
            null
        }
        if (matching == activeSkipStamp) return
        activeSkipStamp = matching
        if (matching == null) {
            hideSkipPrompt()
            return
        }
        // Auto-skip before showing anything, so a skipped segment never flashes
        // its prompt up: the playhead is past it by the next tick.
        if (autoSkipSegment(matching, durationMs)) return
        bar.setSkipIntro(matching.type.buttonLabel)
    }

    /**
     * The one place the playhead moves with no press. The rules are the shared
     * [AutoSkipRules], so this engine skips exactly what the main player skips -
     * including its refusal to skip a low-confidence crowd-sourced row, and its
     * never-touch list (post-credits scenes and next-episode previews are
     * content the viewer is waiting for, not filler).
     */
    private fun autoSkipSegment(stamp: IntroDbStamp, durationMs: Long): Boolean {
        val settings = AutoSkipRules.Settings(
            AppPreferences.getAutoSkipIntro(this),
            AppPreferences.getAutoSkipCredits(this)
        )
        if (!AutoSkipRules.shouldAutoSkip(stamp, settings)) return false
        if (!autoSkippedSegments.add(AutoSkipRules.key(stamp))) return false
        seekPastSegment(stamp, durationMs)
        Log.i(TAG, "auto-skipped ${stamp.type.name} ${stamp.startMs}..${stamp.endMs}")
        // Take the prompt down with it: the segment is behind the playhead now,
        // and the button would otherwise linger over the scene after it.
        hideSkipPrompt()
        return true
    }

    /** The prompt's own press: skip the segment the playhead is inside. */
    private fun performSkip() {
        val stamp = activeSkipStamp ?: return
        seekPastSegment(stamp, durationMs)
        hideSkipPrompt()
    }

    /**
     * Where a skip lands. The shared rule stops *at* a post-credits scene rather
     * than at the end of the file, so skipping the credits never eats the scene
     * the viewer is waiting for.
     */
    private fun seekPastSegment(stamp: IntroDbStamp, durationMs: Long) {
        val target = AutoSkipRules.targetMs(stamp, introDbStamps, durationMs)
        seekTo(target)
    }

    /**
     * The one seek entry point: stamps the time so a buffering that follows a
     * jump is not read as the source starving (see [rebufferFollowsSeek]).
     */
    private fun seekTo(position: Long) {
        lastSeekAtMs = System.currentTimeMillis()
        surface?.seekTo(position)
    }

    /**
     * Adaptive source downshift for the MPV engine (see [RebufferDownshift.kt]).
     *
     * The main player reacts to a source that opens and simply cannot keep up;
     * this engine only ever offered the error card's manual "next source"
     * button, so a viewer who fell through to mpv - or picked it deliberately -
     * watched a spinner in slices with no recovery. Same tracker and thresholds,
     * and the switch reuses the ranked list's own [nextSourceOrNull] ladder.
     */
    private fun onMpvBufferingChanged(buffering: Boolean) {
        if (buffering) {
            bufferingStartedAtMs = System.currentTimeMillis()
            return
        }
        val startedAt = bufferingStartedAtMs
        bufferingStartedAtMs = 0L
        if (startedAt == 0L) return
        if (rebufferDownshiftGivenUp) return
        if (rebufferFollowsSeek(startedAt, lastSeekAtMs)) return
        // Only a mid-playback stall counts: the buffer that covers the initial
        // load fires the same callback, and the playhead is still at zero for it.
        if (positionMs <= 0L) return
        if (endedHandled || errorContainer?.visibility == View.VISIBLE) return
        if (surface?.isPaused() == true) return
        if (System.currentTimeMillis() - startedAt < REBUFFER_DOWNSHIFT_MIN_STALL_MS) return
        val now = System.currentTimeMillis()
        rebufferDownshift.record(now)
        if (!rebufferDownshift.due(now)) return
        val stalledCount = rebufferDownshift.count(now)
        Log.w(
            TAG,
            "Source rebuffered $stalledCount times in ${REBUFFER_DOWNSHIFT_WINDOW_MS / 60_000}min " +
                "— downshifting to the next source"
        )
        val next = nextSourceOrNull()
        rebufferDownshift.reset()
        if (next == null) {
            rebufferDownshiftGivenUp = true
            return
        }
        // A switch is about to start, so this addon has opened the file and
        // failed to keep up for this title: record the downshift against the
        // TITLE, so the next episode's list (and the picker's order) does not
        // start from the same source again (see SourceAddonMemory). Read before
        // the switch, which re-resolves the on-screen addon identity to the
        // next source's. The in-session behavior is untouched.
        com.kennyb1201.kbstream.data.player.SourceAddonMemory.rememberStalled(
            this,
            parentId,
            currentAddonName
        )
        switchToSource(next, isAutoRecovery = true)
    }

    /**
     * Takes the prompt down, handing focus back to the control bar when it held
     * it: a hidden view cannot keep focus, and without a new target the next
     * D-pad press would go nowhere at all.
     */
    private fun hideSkipPrompt() {
        // The shared chrome owns the prompt: it takes the button down and hands
        // focus back to the bar if the prompt held it.
        chrome?.setSkipIntro(null)
    }

    // --- Playback callbacks ------------------------------------------------

    private fun onFileLoaded(mediaTitle: String?) {
        fileLoaded = true
        // The file is open, so mpv can report its chapters; resync (and hide
        // the strip for a file that has none) before the first frame.
        syncChapters()
        // Something opened, so the open-failure budget starts over: a source
        // that fails later in the session gets the same tolerance as the first
        // one, and the ladder only ever bounds a RUN of dead sources.
        openFailureSourcesTried = 0
        runOnUiThread {
            loadingContainer?.visibility = View.GONE
            updateNowPlayingText()
            updateControlsInfo()
            updatePlayPauseLabel(surface?.isPaused() == true)
        }
        // The tracks exist now, so the remembered ones can actually be selected:
        // the option pass at open time picked a language, this picks the exact
        // track this title was left on.
        applyRememberedTracks()
        // ...and the external subtitle this video was last opened with (see
        // restoreRememberedSubtitle); mpv only accepts a sub-add once the file
        // is open, which is exactly this callback.
        runOnUiThread { restoreRememberedSubtitle() }
        // ...and, when the file carries no subtitle in the preferred language,
        // one pulled from OpenSubtitles (see maybeAutoFetchSubtitle) - the same
        // missing press the main player now fills.
        runOnUiThread { maybeAutoFetchSubtitle() }
        // Scrobble once the file is really open — a stream that never loads
        // must not appear on a tracker as started.
        if (!scrobbleStarted) {
            scrobbleStarted = true
            scrobble("start")
        }
        // Every handoff announces itself. A manual switch used to be the
        // exception because the engine note above the control bar said
        // "switched from ExoPlayer" for it - that note is gone now (the overlay
        // is the badges, see [updateControlsInfo]), so the press is the one
        // thing left to say which engine this is.
        if (isFallbackSession) {
            val hint = when (fallbackReason) {
                FALLBACK_REASON_MANUAL ->
                    "\u2014 switch back from the bar's own SWITCH button"
                FALLBACK_REASON_DECODER ->
                    "\u2014 if it stutters, open the gear and set Decoding to Software"
                FALLBACK_REASON_SUBTITLE ->
                    "\u2014 its libass renders the subtitle format ExoPlayer cannot"
                else -> ""
            }
            runOnUiThread {
                showToast("Continuing in the MPV backup engine $hint".trim(), 5000L)
            }
        }
        Log.i(TAG, "playing ${itemTitle()} via MPV (fallback=$isFallbackSession)")
        mediaTitle?.let { Log.i(TAG, "MPV media-title: $it") }
    }

    private fun onProgress(position: Long, duration: Long) {
        positionMs = position
        durationMs = duration
        // The file the viewer is watching may hold two TMDB episodes, or be half
        // of one: read that from the file's own duration against the episode's
        // TMDB runtime, once per file. Cheap and non-blocking - it does nothing
        // until both numbers exist.
        maybeDetectScheme()
        runOnUiThread {
            // The position / duration / bar / play-pause glyph are painted by the
            // shared chrome from this engine's playhead.
            chrome?.refreshProgress()
            // While the seekbar is being dragged it owns the readout, and the
            // chrome leaves the thumb and the times alone then - so the chapter
            // strip follows the same rule. It also keeps the chapter ticks in
            // step with a duration that arrives after the first file-loaded
            // tick, and moves the readout to the chapter the playhead is inside.
            if (!scrubbing && chapterMarks.isNotEmpty()) {
                chrome?.setChapters(chapterMarks, durationMs)
                refreshChapterTitle(positionMs)
            }

            // The card opens as the credits roll, exactly as it does in the main
            // player, instead of waiting for the file to end.
            maybeTriggerEndPanels(positionMs, durationMs)

            // ...and the skip prompt follows the playhead through whatever
            // IntroDB segment it is inside.
            updateSkipPrompt(positionMs, durationMs)

            // The sleep timer rides this tick: it is already mpv's own
            // once-a-second progress report, so the fade cannot drift away from
            // the playhead it is stopping.
            enforceSleepTimer()

            // Completion is a watch-history fact, not an end-of-file event: a
            // title watched to 96% and then backed out of counts as watched,
            // exactly as it does in the main player.
            if (!completionSent &&
                durationMs > 0L &&
                positionMs >= (durationMs * COMPLETION_THRESHOLD_RATIO).toLong()
            ) {
                saveProgress(reason = "completed", forceCompleted = true)
            }
        }
    }

    private fun onPausedChanged(paused: Boolean) {
        runOnUiThread { updatePlayPauseLabel(paused) }
        if (paused) {
            if (scrobbleStarted) scrobble("pause")
            saveProgress(reason = "pause")
        }
    }

    /** The same two icons the main player's play/pause button swaps between. */
    private fun updatePlayPauseLabel(paused: Boolean) {
        playPauseButton?.setImageResource(
            if (paused) R.drawable.ic_player_play else R.drawable.ic_player_pause
        )
        playPauseButton?.let { tintPlayPauseIcon(it, this) }
    }

    /**
     * End of file. keep-open holds the last frame, so this is reached with the
     * player still alive: the completion write happens first, then the next
     * episode is handed to MainActivity the same way the main player does it.
     */
    private fun onPlaybackEnded() {
        if (endedHandled) return
        // Nothing was watched, so nothing is completed and no end-of-episode
        // card is raised: a source that never came up can still reach here as
        // "end of file". Filing that as finished is how a run of failed
        // sources marked itself watched and auto-advanced with the error card
        // still up (see PlayerCompletionRules.shouldRecordCompletion).
        if (!sessionHasPlayed()) {
            Log.w(TAG, "ignoring end-of-playback: this session never played")
            return
        }
        endedHandled = true
        runOnUiThread {
            bufferingView?.visibility = View.GONE
            saveProgress(reason = "ended", forceCompleted = true)
            scrobble("stop", progressOverride = 100.0)

            // A sleep timer armed to stop at the end of this episode is honored
            // here, where the episode really is over: no card, no auto-advance,
            // just out. The completion write above is what the history keeps.
            if (sleepTimerBlocksAutoAdvance(SleepTimer.state.value)) {
                exitForSleepTimer(savePartialProgress = false)
                return@runOnUiThread
            }

            // The card is normally already up from the credits trigger, with its
            // countdown held because the episode had not ended yet. Playback is
            // over now, so let the countdown run.
            if (nextUpCountdownHeld) armNextUpAutoAdvance(remainingMs = 0L)

            // Either the Up Next card or the credits recommendations, decided
            // exactly as the main player decides it.
            showEndPanels()
        }
    }

    /**
     * Whether this session ever actually played something: a playhead past the
     * start. A source that never came up leaves it at zero, and must not be
     * treated as a finished episode.
     */
    private fun sessionHasPlayed(): Boolean =
        runCatching { surface?.positionMs() ?: 0L }.getOrDefault(0L) > 0L

    /**
     * Records the episode this session is handing off FROM, before the next
     * one opens - the same row and the same scrobble the exit path writes, at
     * the moment the end-of-episode card hands playback over. Mirrors
     * NativePlayerActivity.fileEpisodeForHandoff().
     *
     * [shouldRecordCompletion] is the exit path's own "is it finished?" rule,
     * so an episode the viewer skipped out of early stays a resume point
     * instead of being marked watched.
     */
    private fun fileEpisodeForHandoff() {
        // Backstop for a file a viewer sat through without the progress tick
        // ever seeing a duration: the scheme has to be known BEFORE the handoff
        // decides what to chain into.
        maybeDetectScheme()
        val pos = runCatching { surface?.positionMs() ?: 0L }.getOrDefault(0L)
        val dur = runCatching { surface?.durationMs() ?: 0L }.getOrDefault(0L)
        // Reached only from launchNextEpisode, which IS an advance of the
        // session - so the rule is told so, exactly as the main player does.
        // Pressing Next in the credits, before the end-of-episode countdown
        // has raised the card, used to file a RESUME row with a minute or two
        // left while the "stop" scrobble told the tracker the episode was
        // watched. The rule keeps its tail gate, so a Next pressed in the
        // middle of an episode still files a resumable position.
        val completed = shouldRecordCompletion(
            playbackEnded = endedHandled,
            endPanelsShown = endPanelsShown,
            positionMs = pos,
            durationMs = dur,
            played = sessionHasPlayed(),
            explicitAdvance = true
        )
        // The verdict is reported AFTER the write commits (onWritten), so the
        // trace can no longer claim "filed" for a row that was never stored.
        saveProgress(reason = "handoff", forceCompleted = completed) { ok, profile ->
            com.kennyb1201.kbstream.data.reporting.PlaybackSessionTrace.note(
                "filed s=${season ?: "-"} e=${episode ?: "-"} " +
                    "row=$historyId completed=$completed written=$ok profile=${profile ?: "-"}"
            )
        }
        scrobble("stop", progressOverride = if (completed) 100.0 else null)
    }

    /**
     * Next-episode handoff. Same two channels as the main player: the persisted
     * [NextEpisodeResult] (survives MainActivity being killed while the player
     * was up) and the classic result extras for the live callback.
     */
    private fun launchNextEpisode(
        targetSeason: Int,
        targetEpisode: Int,
        episodeOverview: String? = null
    ) {
        // One handoff per session: whichever trigger gets here first wins.
        if (nextEpisodeHandoffStarted) return
        nextEpisodeHandoffStarted = true
        // Reaching here while a timer is armed to stop at the end of this
        // episode means the viewer pressed PLAY NEXT: an explicit press beats
        // the timer, so the timer goes with the episode it was waiting for. A
        // minutes timer is left alone - that intent is about the clock, not
        // about this episode.
        if (SleepTimer.state.value.stopsAtEndOfItem) SleepTimer.cancel()
        // File THIS episode before the handoff, exactly as the main player
        // does: a binge leaves every episode but the last through here, and
        // the row plus the tracker "stop" cannot wait on onStop, which the OS
        // can hold back behind the next player. Without it the finished
        // episodes kept no watch marker and their late stop ended the Simkl
        // session the next episode had just opened.
        fileEpisodeForHandoff()
        nextUpCountdownHeld = false
        nextUpCountdownRemaining = 0
        nextUpCountdownHandler.removeCallbacks(nextUpCountdownRunnable)
        val pending = NextEpisodeResult.PendingNext(
            season = targetSeason,
            episode = targetEpisode,
            title = "S$targetSeason\u2009E$targetEpisode",
            streamId = nextStreamId(targetSeason, nextFileEpisodeFor(targetSeason, targetEpisode)),
            runtimeMinutes = null,
            bingeGroup = currentBingeGroup,
            addonName = currentAddonName,
            overview = episodeOverview,
            randomEpisodes = randomEpisodes
        )
        com.kennyb1201.kbstream.data.reporting.PlaybackSessionTrace.note(
            "next: s=$targetSeason e=$targetEpisode from s=${season ?: "-"} e=${episode ?: "-"} id=${pending.streamId}"
        )
        NextEpisodeResult.persist(this, pending)
        setResult(
            RESULT_OK,
            Intent().apply {
                putExtra("player_result_action", "next_episode")
                putExtra("next_episode", pending.episode)
                putExtra("next_season", pending.season)
                putExtra("next_title", pending.title)
                putExtra("next_stream_id", pending.streamId)
                putExtra("next_binge_group", pending.bingeGroup)
                putExtra("next_addon_name", pending.addonName)
                putExtra("next_overview", pending.overview)
                putExtra("next_random", pending.randomEpisodes)
            }
        )
        finish()
    }

    /**
     * Mirrors NativePlayerActivity.nextStreamId: the episode id's own prefix,
     * with [targetFileEpisode] in FILE numbering - the identity the addons
     * resolve (see [nextFileEpisodeFor]).
     */
    private fun nextStreamId(targetSeason: Int, targetFileEpisode: Int): String {
        val prefix = episodeStreamId.orEmpty()
            .substringBeforeLast(':')
            .substringBeforeLast(':')
        return if (prefix.isNotBlank()) {
            "$prefix:$targetSeason:$targetFileEpisode"
        } else {
            "$parentId:$targetSeason:$targetFileEpisode"
        }
    }

    // --- Watch history -----------------------------------------------------

    /**
     * Writes the same row the main player writes — same id, same fields, same
     * canonical parentId — so a session that started in ExoPlayer and finished
     * here updates ONE Continue Watching card.
     */
    private fun saveProgress(
        reason: String,
        forceCompleted: Boolean = false,
        onWritten: ((Boolean, String?) -> Unit)? = null
    ) {
        val view = surface ?: return
        if (parentId.isBlank() || historyId.isBlank()) return

        val position = view.positionMs().coerceAtLeast(0L)
        val duration = view.durationMs()
        // "This episode reached its end" is sticky for the session (see
        // [endedHandled], set by onPlaybackEnded), so EVERY save in a finished
        // session is judged by the same fact as the end itself - a pause taken
        // after a rewind, the onStop save, an actor-return, a sleep-timer save.
        // Judged from the playhead alone, a seek back past the credits filed a
        // RESUME row over the completion: the episode lost its watched marker
        // and returned to Continue Watching. Mirrors
        // NativePlayerActivity.saveProgress's sessionCompleted, early returns
        // included.
        val sessionCompleted = forceCompleted || endedHandled
        // A missing duration used to abandon the write entirely - including
        // the completion write at end of file, which is the one fact the
        // player can state without knowing how long the file was. A finished
        // episode then kept no watch marker and its old resume bar.
        if (duration <= 0L && !sessionCompleted) return
        if (position < MIN_RESUME_POSITION_MS && !sessionCompleted) return

        // With no length the played position is the best duration we have.
        val effectiveDuration = if (duration > 0L) duration else position.coerceAtLeast(1L)
        val completed =
            sessionCompleted ||
                position >= (effectiveDuration * COMPLETION_THRESHOLD_RATIO).toLong()
        val safePosition = if (completed) 0L else position.coerceAtMost(effectiveDuration)
        val now = System.currentTimeMillis()
        if (completed) completionSent = true

        // A row filed from the credits tail is a "leaving" row even when the
        // local rule above did not call it completed: the tracker's own stop
        // can still mark the episode watched, and without a re-merge the
        // finished episode holds its Continue Watching card until a restart.
        // Ask Home to re-read the feeds exactly as a completion does. (A
        // completed row's push asks again once it resolves; duplicate requests
        // only restart the same bounded retry window.) The tail needs a REAL
        // duration, so the effective (position-as-duration) fallback is not
        // used here - an unknown length is not a tail.
        if (duration > 0L && isCreditsTail(position, duration)) {
            ContinueWatchingRefreshBus.requestRefresh()
        }

        Log.i(
            TAG,
            "save progress ($reason): ${safePosition}ms / ${effectiveDuration}ms completed=$completed"
        )

        // NonCancellable: this write must land even while the activity is being
        // torn down, exactly like the main player's exit save.
        lifecycleScope.launch(Dispatchers.IO + NonCancellable) {
            // The write goes through PlaybackHistoryWriter, which files the row
            // under the profile this SESSION started on and refuses it if the
            // user switched profiles while it was playing.
            val entry =
                WatchHistoryEntity(
                    id = historyId,
                    parentId = canonicalParentId(),
                    type = parentType,
                    name = itemName,
                    episodeTitle = episodeTitle,
                    overview = overview,
                    clearLogo = clearLogoUrl,
                    backdropUrl = backdropUrl,
                    totalEpisodesInSeason = totalEpisodesInSeason,
                    poster = itemPoster,
                    streamUrl = currentUrl,
                    season = season,
                    episode = episode,
                    episodeStreamId = episodeStreamId,
                    positionMs = safePosition,
                    durationMs = effectiveDuration,
                    updatedAt = now,
                    isCompleted = completed,
                    // Completed rows keep the stamp of the first completion;
                    // the writer reads it back from the row it replaces.
                    completedAt = null
                )
            val result = PlaybackHistoryWriter.write(this@MpvPlayerActivity, sessionProfileId, entry)
            if (completed) pushCompletion()
            onWritten?.invoke(result.ok, result.profileId)
        }
    }

    /**
     * The parent id the history row is stored under. Passed in by the handoff
     * when ExoPlayer already resolved it (so both engines agree), otherwise
     * resolved here with the same rules the main player uses.
     */
    /**
     * The control bar's SWITCH button in this engine: hand the session back to
     * ExoPlayer and carry on from where it is.
     *
     * The mirror of the main player's button - same glyph, same place in the
     * bar, same resumed position - and deliberately NOT remembered: switching
     * is a decision about this session, not a change to Settings. A file that
     * only libmpv can play therefore just comes back here through the automatic
     * fallback, which is the honest answer to "does ExoPlayer handle this?".
     */
    /**
     * Where this session continues from when the engine changes.
     *
     * mpv's own playhead is the right answer once it has opened something, and
     * [startPositionMs] - what THIS session was handed - is the right answer
     * when it has not. The distinction matters because "nothing loaded" reads as
     * 0, not as null: a switch pressed after mpv failed to open the file used to
     * carry that 0, so the title restarted from the beginning even though the
     * viewer had watched twenty minutes of it in the other engine. The elvis
     * being replaced only covered a null surface.
     *
     * This is the same rule the end-of-episode resume already uses (see the
     * `if (positionMs > 0L) positionMs else startPositionMs` at the EOF panel),
     * so a hand switch and a finished episode agree about where playback is.
     */
    private fun carriedPositionMs(): Long {
        val own =
            runCatching {
                surface?.positionMs()?.coerceAtLeast(0L) ?: positionMs
            }.getOrDefault(positionMs)

        return (if (own > 0L) own else startPositionMs).coerceAtLeast(0L)
    }

    private fun switchToExoPlayer() {
        if (playerSwitchStarted) return
        if (isFinishing || isDestroyed) return
        if (currentUrl.isBlank()) return
        val baseIntent = intent ?: return
        playerSwitchStarted = true

        val position = carriedPositionMs()

        val launch = Intent(baseIntent).apply {
            // Extras that describe THIS engine, not the session: ExoPlayer has
            // no use for them, and a leftover "this is a fallback" flag would
            // only mislabel the new session's notices.
            removeExtra(EXTRA_MPV_FALLBACK)
            removeExtra(EXTRA_MPV_FALLBACK_REASON)
            putExtra("stream_url", currentUrl)
            putExtra("audio_url", currentAudioUrl)
            putExtra("start_position_ms", position)
            // Resume, never restart: the file is already part-way through -
            // except for a from-the-beginning launch that never played a frame,
            // where there is no playhead to carry and dropping the flag would
            // let the successor's own watch-history resume start the title the
            // viewer asked to start over. See PlaybackResume.
            putExtra("from_beginning", position <= 0L && startFromBeginning)
            putExtra(
                "stream_headers",
                streamHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" }
            )
            // Only when it is already resolved; otherwise the ExoPlayer session
            // canonicalizes it with the same rules (PlaybackHistoryIds).
            historyParentIdOverride?.let {
                putExtra(EXTRA_HISTORY_PARENT_ID, it)
            }
        }

        Log.i(TAG, "switching playback to the ExoPlayer engine from ${position}ms")
        showToast("Switching to the ExoPlayer engine\u2026")
        exoSwitchLauncher.launch(exoSwitchIntent(launch))
    }

    /**
     * The control bar's "play in another app" button: hand this session to an
     * installed external player (see [ExternalPlayerActivity]).
     *
     * Deliberately the same handoff as [switchToExoPlayer] with a different
     * target, because it has to satisfy the same two constraints: this activity
     * stays in the chain (the result is forwarded to whoever started the
     * player, so "next episode" and the watch-history handoff behind it keep
     * working), and the position carries over, so a viewer who opened a file in
     * MPV and then wants their own player does not restart the title. What mpv
     * was handed - the stream URL, the request headers, the canonical parent id
     * - travels with it.
     */
    private fun switchToExternal() {
        val blocked = when {
            playerSwitchStarted -> "Already switching engines\u2026"

            currentUrl.isBlank() ->
                "Nothing is playing yet \u2014 try this again once it starts"

            intent == null || isFinishing || isDestroyed ->
                "Can't switch engines right now"

            !PlayerEngine.externalAvailable(this) ->
                "No external player is installed on this device"

            else -> null
        }
        if (blocked != null) {
            showToast(blocked)
            return
        }
        val baseIntent = intent ?: return
        playerSwitchStarted = true

        val position = carriedPositionMs()

        val launch = Intent(baseIntent).apply {
            setClass(this@MpvPlayerActivity, ExternalPlayerActivity::class.java)
            // Extras that describe THIS engine, not the session.
            removeExtra(EXTRA_MPV_FALLBACK)
            removeExtra(EXTRA_MPV_FALLBACK_REASON)
            putExtra("stream_url", currentUrl)
            putExtra("start_position_ms", position)
            // Resume, never restart - except for a from-the-beginning launch
            // with no playhead yet, which keeps the flag so the wrapper does not
            // resume the title the viewer asked to start over. See
            // PlaybackResume.
            putExtra("from_beginning", position <= 0L && startFromBeginning)
            putExtra(
                "stream_headers",
                streamHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" }
            )
            historyParentIdOverride?.let {
                putExtra(EXTRA_HISTORY_PARENT_ID, it)
            }
        }

        Log.i(TAG, "handing playback to the external player from ${position}ms")
        val label = ExternalPlayer.target(this)?.label ?: "the external player"
        showToast("Opening in $label\u2026")
        exoSwitchLauncher.launch(launch)
    }

    private suspend fun canonicalParentId(): String {
        canonicalParent?.let { return it }
        val resolved = PlaybackHistoryIds.canonicalParentId(
            context = this,
            rawParentId = parentId,
            parentType = parentType,
            resolvedTmdbId = resolvedTmdbId
        )
        canonicalParent = resolved
        return resolved
    }

    private suspend fun tmdbId(): Int? {
        resolvedTmdbId?.let { return it }
        val raw = parentId.trim()
        val resolved = when {
            raw.startsWith("tmdb:") || raw.all(Char::isDigit) ->
                raw.removePrefix("tmdb:").toIntOrNull()

            // Everything else - which in practice means the imdb id every addon
            // catalog hands out, and the form the history parent is
            // canonicalized to - resolves through the shared helper (addon meta
            // -> TMDB, cached in memory and on disk). A `startsWith("tt") ->
            // null` short-circuit used to stand in for that, and it left this
            // engine's because-you-watched row with no seed: it built an empty
            // lineup and hid itself again.
            else -> PlaybackHistoryIds.resolveTmdbId(this, raw, parentType)
        }
        resolvedTmdbId = resolved
        return resolved
    }

    // --- Scrobbling --------------------------------------------------------

    private fun scrobble(action: String, progressOverride: Double? = null) {
        if (parentId.isBlank()) return
        // A tracker call resolves the ACTIVE profile's token/key at fire time,
        // so a session that outlived a profile switch must not scrobble the
        // departing episode to the profile the viewer moved TO.
        if (!PlaybackHistoryWriter.sessionStillActive(this, sessionProfileId)) {
            com.kennyb1201.kbstream.data.reporting.PlaybackSessionTrace.note(
                "tracker $action skipped: session profile departed"
            )
            return
        }
        val progress = progressOverride ?: if (durationMs > 0L) {
            ((positionMs.toDouble() / durationMs.toDouble()) * 100.0).coerceIn(0.0, 100.0)
        } else {
            0.0
        }
        // Independent scope: a stop scrobble has to outlive this activity.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatchingCancellable {
                SimklRepository.getInstance(this@MpvPlayerActivity).scrobble(
                    action = action,
                    parentId = parentId,
                    parentType = parentType,
                    season = season,
                    episode = episode,
                    title = itemName,
                    progress = progress,
                    tmdbId = tmdbId()
                )
            }.onFailure { Log.w(TAG, "Simkl scrobble/$action failed", it) }
            runCatchingCancellable { scrobbleMdbList(action, progress) }
                .onFailure { Log.w(TAG, "MDBList scrobble/$action failed", it) }
        }
    }

    private suspend fun scrobbleMdbList(action: String, progress: Double) {
        if (MdbListClient.apiKey(this).isBlank() || parentId.isBlank()) return
        val imdbId = parentId.takeIf { it.startsWith("tt") }
        val tmdb = tmdbId()
        val isMovie = parentType.lowercase() == "movie"
        when (action) {
            "start" -> MdbListClient.scrobbleStart(this, isMovie, imdbId, tmdb, season, episode, progress)
            "pause" -> MdbListClient.scrobblePause(this, isMovie, imdbId, tmdb, season, episode, progress)
            "stop" -> MdbListClient.scrobbleStop(this, isMovie, imdbId, tmdb, season, episode, progress)
        }
    }

    /** Marks the title watched on both trackers, once per session. */
    private fun pushCompletion() {
        if (trackersMarkedWatched || parentId.isBlank()) return
        if (!PlaybackHistoryWriter.sessionStillActive(this, sessionProfileId)) {
            com.kennyb1201.kbstream.data.reporting.PlaybackSessionTrace.note(
                "tracker completion skipped: session profile departed"
            )
            return
        }
        trackersMarkedWatched = true
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatchingCancellable {
                val simkl = SimklRepository.getInstance(this@MpvPlayerActivity)
                val tmdb = tmdbId()
                when (parentType.lowercase()) {
                    "movie" -> simkl.pushWatchedMovie(
                        imdbId = parentId,
                        title = itemName,
                        tmdbId = tmdb
                    )

                    "series", "show", "tv" -> {
                        val showSeason = season
                        val showEpisode = episode
                        if (showSeason != null && showEpisode != null) {
                            // One file can cover several TMDB episodes (see
                            // coveredTmdbEpisodes): every one of them is marked,
                            // or the second segment of a doubled file stays
                            // unwatched on the tracker.
                            var pushed = true
                            coveredTmdbEpisodes(showEpisode).forEach { coveredEpisode ->
                                val ok = simkl.pushWatchedEpisode(
                                    showImdbId = parentId,
                                    season = showSeason,
                                    episode = coveredEpisode,
                                    title = itemName,
                                    tmdbId = tmdb
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
            }.onFailure { Log.w(TAG, "Simkl completion sync failed", it) }
            runCatchingCancellable {
                if (MdbListClient.apiKey(this@MpvPlayerActivity).isNotBlank()) {
                    val isMovie = parentType.lowercase() == "movie"
                    MdbListClient.pushWatched(
                        this@MpvPlayerActivity,
                        mediaType = if (isMovie) "movie" else "episode",
                        imdbId = parentId.takeIf { it.startsWith("tt") },
                        tmdbId = tmdbId(),
                        season = season,
                        episode = episode
                    )
                }
            }.onFailure { Log.w(TAG, "MDBList completion sync failed", it) }
            // The completion is on the tracker now, so the Continue Watching
            // feeds are stale. Home's ON_RESUME refresh races this write and
            // can read the pre-completion feed; ask for one more merge so a
            // finished title leaves the rail without waiting for a resume.
            ContinueWatchingRefreshBus.requestRefresh()
        }
    }

    // --- Controls ----------------------------------------------------------

    private fun showLoading(subtitle: String) {
        loadingContainer?.visibility = View.VISIBLE
        loadingTitle?.text = itemTitle()
        loadingSubtitle?.text = subtitle
        bufferingView?.visibility = View.GONE
    }

    /**
     * Forgets a cached debrid link whose launch failed before the file ever
     * opened - the signature of a link that expired or was pulled. Called from
     * the playback-error path only, which (see MpvPlayerView's END_FILE
     * handling) fires precisely when nothing was ever loaded.
     */
    private fun invalidateCachedLinkBeforeFirstFrame() {
        if (fileLoaded || linkCacheInvalidated) return
        val cacheKey = intent.getStringExtra("played_link_key") ?: return
        linkCacheInvalidated = true
        // Pinned to the LAUNCH profile: a mid-playback switch must not spare
        // the launching profile's dead entry (SD-2).
        PlayedLinkCache.forgetForProfile(this, cacheKey, sessionProfileId)
    }

    private fun showError(message: String, hint: String) {
        loadingContainer?.visibility = View.GONE
        bufferingView?.visibility = View.GONE
        setControlsVisible(View.GONE)
        errorContainer?.visibility = View.VISIBLE
        errorText?.text = message
        errorHint?.text = hint
        // The card's own SWITCH PLAYER, the same press the control bar's SWITCH
        // makes (see switchToExoPlayer). It is here because a viewer looking at a
        // failure is exactly who wants to try the other engine, and finding the
        // control bar behind a full-screen card is a gesture worth saving them.
        // ExoPlayer is always available where this engine is, so unlike the main
        // player's copy of this button there is no case where a press could not
        // land - and it takes focus, since the card is the only thing on screen.
        errorSwitchButton?.visibility = View.VISIBLE
        // "TRY NEXT SOURCE" only where the ranked list actually has one left:
        // a dead-end button is worse than none. Kept the secondary action -
        // SWITCH PLAYER stays the card's default focus.
        errorNextSourceButton?.visibility =
            if (nextSourceOrNull() != null) View.VISIBLE else View.GONE
        errorSwitchButton?.post { errorSwitchButton?.requestFocus() }
    }

    /**
     * The next source in the ranked list after the one playing now, or null
     * when nothing else is loaded. The list is the same ranked order the
     * SOURCES picker shows, so "next" means what the viewer sees: the row
     * under the current one.
     */
    private fun nextSourceOrNull(): Stream? {
        val current = currentUrl
        val index = sources.indexOfFirst { it.url == current }
        var start = if (index >= 0) index + 1 else 0
        while (true) {
            // First pass skips every add-on this session has marked dead (see
            // SourceAddonSession.nextIndex); when nothing live is left it falls
            // back to plain order, because a dead link is still better than no
            // sources. The blank-URL / same-URL guard below is the pre-existing
            // rule and applies to whichever index the skip lands on.
            val candidateIndex = SourceAddonSession.nextIndex(sourceAddons, start, sessionDeadAddons)
                ?: return null
            val stream = sources.getOrNull(candidateIndex) ?: return null
            val url = stream.url
            if (!url.isNullOrBlank() && url != current) return stream
            start = candidateIndex + 1
        }
    }

    /**
     * The error card's retry on ANOTHER source: it replaces the failed file
     * with the next one in the ranked list, saving the viewer a trip through
     * the SOURCES picker. Driven by the card's button, and by two automatic
     * recoveries that mirror the main player's: a source that never OPENED
     * (see the onPlaybackError handler and
     * [PlaybackRecoveryRules.shouldAdvancePastOpenFailure]) and the
     * repeated-rebuffer downshift (see [onMpvBufferingChanged]), so a source
     * that is dead, or one that opens but cannot keep up, is not left to fail
     * on this engine when the list has another one to try.
     */
    private fun tryNextSource() {
        val next = nextSourceOrNull() ?: return
        // The card is otherwise terminal for this engine; the new load replaces
        // it, and a failure of the new source raises the card again with fresh
        // state (see showError).
        errorContainer?.visibility = View.GONE
        switchToSource(next)
    }

    /**
     * The overlay's visibility, its wall clock, its auto-hide timer and its
     * D-pad focus order all live in the shared [PlayerChrome] now. These are thin
     * delegations, so every existing call site keeps working and the two engines
     * cannot drift apart again. The clock's own 1 Hz tick stays here (see
     * [clockRunnable]) because it rides mpv's progress reporting.
     */
    private fun showControls() {
        chrome?.show()
        startClock()
    }

    private fun setControlsVisible(visibility: Int) {
        if (visibility == View.VISIBLE) {
            chrome?.show()
            startClock()
        } else {
            chrome?.hide()
            clockHandler.removeCallbacks(clockRunnable)
        }
    }

    private fun keepControlsVisible() {
        chrome?.restartAutoHide()
    }

    /** Restarts the 1 Hz clock tick; it stops itself once the bar is down. */
    private fun startClock() {
        clockHandler.removeCallbacks(clockRunnable)
        clockHandler.postDelayed(clockRunnable, 1000L)
    }

    /** Holds the overlay open while one of its buttons has focus. */
    private fun removeAutoHide() {
        chrome?.cancelAutoHide()
    }

    private val controlsVisible: Boolean
        get() = chrome?.isVisible == true

    /**
     * Transient feedback for this engine's buttons and subtitle flow: the
     * platform Toast, the same one the native player raises (see
     * NativePlayerActivity), rather than a TextView in the layout that this
     * activity had to show and time out itself. The hand-rolled version had a
     * race - two lines in quick succession left the first one's GONE runnable
     * armed, and it blanked the second message the moment it fired - and it
     * had to be re-styled off the theme by hand every time the theme grew a
     * variant. [durationMs] is kept as the call sites' way of asking for a
     * long read; it picks the longer platform duration above the short one's
     * own lifetime.
     */
    private fun showToast(message: String, durationMs: Long = 2_500L) {
        Toast.makeText(
            this,
            message,
            if (durationMs > TOAST_SHORT_MAX_MS) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * D-pad first. While the overlay is hidden, OK plays/pauses and left/right
     * seek (the press that reveals the controls also does what was asked of
     * it); once it is up, the buttons take focus and OK activates them, which
     * is what a TV remote expects.
     */
    // androidx.core marks ComponentActivity.dispatchKeyEvent @RestrictedApi
    // ("same library group"), which an app cannot satisfy however it calls it.
    // The calls below are `super`, from an override of the same method: the
    // ordinary way to see a key before the view tree does, and the D-pad rules
    // in the doc above depend on being ahead of it.
    //
    // GestureBackNavigation: the KEYCODE_BACK arm below belongs to the player's
    // overlay - it decides whether Back exits playback or only closes what is
    // open - and it is deliberately handled here, ahead of the view tree, so a
    // Back press cannot fall through to the seek bar underneath. The activity
    // also registers an OnBackPressedCallback (the API-36 registration lint
    // asks for) that makes the same decision when the press reaches the
    // dispatcher; this override only pre-empts it for the live player chrome.
    // Migrating the chrome's Back handling onto the dispatcher is a
    // device-tested change, tracked with the API-36 behavior pass.
    @SuppressLint("RestrictedApi", "GestureBackNavigation")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The settings panel is a side panel, not a takeover: while it is up the
        // focus system owns the D-pad (BACK still reaches the activity, which
        // closes the panel), exactly as it does in the main player.
        if (settingsOpen) return super.dispatchKeyEvent(event)
        // A visible skip prompt owns OK while the chrome is down: pressing OK
        // during an intro should skip it, not pause underneath the prompt (the
        // same rule the main player uses). With the overlay up the prompt is an
        // ordinary focusable, so OK reaches it through the focus system instead.
        if (chrome?.isSkipIntroVisible == true && !controlsVisible &&
            event.action == KeyEvent.ACTION_DOWN
        ) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    // One press, one skip: autorepeat must not skip twice.
                    if (event.repeatCount == 0) performSkip()
                    return true
                }
            }
        }
        // The end-of-episode card owns the remote while it is up: OK is its
        // buttons, not play/pause - the same as in the main player.
        if (nextUpPanel?.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)
        // Same rule for the credits recommendations: LEFT/RIGHT step the picks
        // (parking focus on the first one if it is not inside the panel yet) and
        // every other key goes to the focused pill, so OK activates PLAY /
        // DETAILS instead of toggling playback underneath them.
        if (bywUi?.isVisible == true) {
            val horizontal = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            // UP/DOWN have nothing above or below the row to move to, and
            // letting them run focus search walked out of the row onto the
            // controls overlay - the panel then sat there without the D-pad,
            // so OK on a pick did nothing. A vertical press is swallowed, and
            // any press arriving before the row has focus parks it on the
            // first pick instead of reaching what is underneath.
            val vertical = event.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
            val confirm = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                event.keyCode == KeyEvent.KEYCODE_ENTER ||
                event.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT
            if (event.action == KeyEvent.ACTION_DOWN && vertical) {
                if (bywUi?.hasFocus() == false) bywUi?.focusFirst()
                return true
            }
            if (event.action == KeyEvent.ACTION_DOWN && confirm && bywUi?.hasFocus() == false) {
                bywUi?.focusFirst()
                return true
            }
            if (event.action == KeyEvent.ACTION_DOWN && horizontal &&
                bywUi?.hasFocus() == false
            ) {
                // Park focus on the first pick, and swallow the press whether or
                // not there was one to park on: the row fills in a beat after
                // the panel opens, and a press that falls through reaches the
                // seek bar underneath, which steps the finished episode. The
                // main player's credits panel already swallows this way.
                bywUi?.focusFirst()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        // An overlay-less hold-to-scrub owns the remote from the moment it
        // starts: its release lands the scrub, and the repeats in between belong
        // to its own timer rather than to the remote's repeat rate. Handled ahead
        // of the ACTION_DOWN gate below because a release is not a press. Any
        // other key ends the scrub (landing it) and is then handled normally.
        if (mpvScrubDirection != 0) {
            val horizontal = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            if (event.action != KeyEvent.ACTION_DOWN) {
                endMpvHoldScrub()
                return true
            }
            if (horizontal) return true
            endMpvHoldScrub()
        }
        if (event.action == KeyEvent.ACTION_DOWN && errorContainer?.visibility != View.VISIBLE) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    seekStepBy(-SEEK_STEP_MS)
                    return true
                }

                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    seekStepBy(SEEK_STEP_MS)
                    return true
                }

                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    // A finished session stays finished: a play press after the
                    // end (stray remote button, Bluetooth remote, assistant)
                    // must not restart the episode from the top. Replay is an
                    // explicit seek or a fresh session, not a toggle.
                    if (endedHandled) return true
                    surface?.togglePause()
                    showControls()
                    return true
                }

                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (!controlsVisible) {
                        // Swallowed at the end of playback, end card up (see
                        // togglePlayPauseFromControls).
                        if (togglePlayPauseFromControls()) return true
                        showControls()
                        return true
                    }
                }

                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!controlsVisible) {
                        // One ten-second step now; if the press is still down
                        // past the hold window it becomes an accelerated scrub
                        // and the overlay stays down until the release (see
                        // beginMpvHoldScrub).
                        beginMpvHoldScrub(
                            if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                        )
                        return true
                    }
                }

                // Back, volume and power belong to the system: showing the
                // overlay for them would swallow the one key that exits.
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_VOLUME_MUTE,
                KeyEvent.KEYCODE_POWER -> Unit

                else -> if (!controlsVisible) showControls()
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // --- Audio focus -------------------------------------------------------

    @RequiresApi(Build.VERSION_CODES.O)
    private fun requestAudioFocus() {
        val manager = getSystemService(AudioManager::class.java) ?: return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { change ->
                // mpv's own audio output does not follow focus, so losing focus
                // has to stop playback explicitly.
                if (change == AudioManager.AUDIOFOCUS_LOSS ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                ) {
                    surface?.setPaused(true)
                }
            }
            .build()
        audioFocusRequest = request
        manager.requestAudioFocus(request)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun abandonAudioFocus() {
        val request = audioFocusRequest ?: return
        getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(request)
        audioFocusRequest = null
    }

    // --- Lifecycle ---------------------------------------------------------

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) requestAudioFocus()
        // Coming back to a session whose panel mode was restored on the way out
        // (see onStop): mpv reported its rate when the file loaded, which has
        // already happened, so it is re-offered here rather than waited for.
        surface?.videoFrameRate()?.let { fps -> frameRateMatcher?.onContentFrameRate(fps) }
    }

    override fun onStop() {
        super.onStop()
        // A hold-to-scrub has no release once the screen is left, so land it
        // here rather than leaving its timer ticking against a paused surface.
        endMpvHoldScrub()
        // mpv is paused just below, so guide writes may proceed again - except on
        // a handoff: Android starts the successor activity before this one's
        // onStop, so the successor has already set the gate active, and clearing
        // it here ran EPG writes un-gated mid-playback (the stall the gate
        // exists to stop).
        if (!playerSwitchStarted && !nextEpisodeHandoffStarted) {
            EpgWriteGate.setPlayerActive(false)
        }
        // Paused and off screen, so the panel goes back to the mode the rest of
        // the TV interface expects and onStart asks for it again on the way
        // back. Deliberately not in onPause: Picture-in-Picture keeps the video
        // running, and the matched rate is still the right one there.
        frameRateMatcher?.release()
        chrome?.cancelAutoHide()
        if (playerSwitchStarted) {
            // Another engine has been launched and this Activity stays in the
            // chain for its result, so it is not destroyed until that session
            // ENDS - and until then libmpv would still be holding its own
            // hardware decoder. On a box that hands out one 4K decode per
            // process, that is the decoder the session now playing is refused
            // when it rebuilds: a source switch there comes back "out of video
            // decoder resources" while starting the same source fresh, with no
            // engine switch behind it, plays immediately. Hand the native
            // instance back here instead - onDestroy then has nothing to
            // release.
            //
            // release() is one-way (the view is never re-initialized), so this
            // is only safe because no path brings playback back to it: both
            // handoffs (ExoPlayer and the external player) finish this session
            // from their launcher's callback the moment the successor returns
            // (see exoSwitchLauncher), so onStart can only run with [surface]
            // already null, and nothing can resume against the released view.
            surface?.release()
            surface = null
            // The file is gone with the surface, so the chapter strip goes too.
            clearChapters()
        } else {
            // Pause rather than tear down: the native instance is released in
            // onDestroy, and a backgrounded player that kept playing would be a
            // bug report of its own.
            surface?.setPaused(true)
        }
        // Not when this session is being continued in another engine (the
        // ExoPlayer switch or an installed external player). That engine
        // scrobbles its own "start" and its own "stop"; ours raced the start
        // and ended the Simkl session immediately, so the real stop came back
        // 409 "already ended" and the title was never marked watched.
        // Not when the end-of-episode card already filed this episode and
        // closed its tracker session ([fileEpisodeForHandoff]).
        if (!playerSwitchStarted && !nextEpisodeHandoffStarted) {
            saveProgress(reason = "stop")
            scrobble("stop")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) abandonAudioFocus()
    }

    // --- Chapters (MPV only) -------------------------------------------------
    //
    // mpv reports the file's chapters natively (see MpvPlayerView.readChapters);
    // the ExoPlayer engine has no chapter API, so this strip is MPV-only for
    // now and says so on screen. The marks are paint-only - they never change
    // what the bar seeks to - so a file without chapters looks exactly as it
    // did before.

    /** Reads the open file's chapters and shows or hides the strip. */
    private fun syncChapters() {
        chapterMarks = surface?.readChapters().orEmpty()
        chapterRow?.visibility = if (chapterMarks.isEmpty()) View.GONE else View.VISIBLE
        chrome?.setChapters(chapterMarks, durationMs)
        refreshChapterTitle(positionMs)
    }

    /** Updates the strip's readout to the chapter the playhead is inside. */
    private fun refreshChapterTitle(position: Long) {
        val label = chapterNow ?: return
        label.text = currentChapterTitle(position).orEmpty()
    }

    /**
     * The title of the chapter containing [position], or null when the file has
     * no chapters (or none has started yet - the strip stays blank until the
     * first mark).
     */
    private fun currentChapterTitle(position: Long): String? =
        chapterMarks.lastOrNull { it.timeMs <= position }?.title

    /** Drops the chapter strip; the file that carried it is gone. */
    private fun clearChapters() {
        chapterMarks = emptyList()
        chapterRow?.visibility = View.GONE
        chrome?.setChapters(emptyList(), 0L)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Safety net for a player that never reached onStop: leaving this
        // set would hold guide writes back for the life of the process.
        EpgWriteGate.setPlayerActive(false)
        // Safety net for a session destroyed without a stop of its own;
        // release() is a no-op once onStop has already restored the panel.
        frameRateMatcher?.release()
        frameRateMatcher = null
        sleepTimerSection?.release()
        addonSubtitleFetchJob?.cancel()
        addonSubtitleFetchJob = null
        handler.removeCallbacksAndMessages(null)
        clockHandler.removeCallbacksAndMessages(null)
        nextUpCountdownHandler.removeCallbacksAndMessages(null)
        surface?.release()
        surface = null
        clearChapters()
    }

    // --- Sleep timer ---------------------------------------------------------

    /**
     * The rows this session can offer. Read through the activity rather than
     * captured once, so the panel section and the main player's are built from
     * the same rule even though this engine resolves none of the live ones.
     */
    internal fun sleepTimerChoices(): List<SleepTimerOption> = sleepTimerOptions(
        isLive = false,
        isEpisode = season != null && episode != null,
        hasProgramEnd = false
    )

    /**
     * Arms (or clears) the timer. A fade already in progress is undone here:
     * "Off" pressed during the last twenty seconds of a timer has to bring the
     * sound back rather than silence the rest of the film.
     */
    internal fun chooseSleepTimer(option: SleepTimerOption) {
        SleepTimer.select(option, nowMs = System.currentTimeMillis())
        sleepFadeGain = 1f
        surface?.setOutputGain(1f)
        sleepTimerSection?.refresh()
    }

    /**
     * The sleep timer, driven from the progress tick: the last [SLEEP_FADE_MS]
     * fade to silence, then the session stops.
     */
    private fun enforceSleepTimer() {
        val state = SleepTimer.state.value
        if (!state.isArmed) {
            restoreSleepFade()
            return
        }
        // "End of episode" has no deadline to count: it is honored where the
        // file actually ends (see onPlaybackEnded).
        if (state.stopsAtEndOfItem) return
        val remaining = (state.deadlineMs ?: return) - System.currentTimeMillis()
        val gain = sleepFadeGain(remaining)
        if (gain != sleepFadeGain) {
            sleepFadeGain = gain
            surface?.setOutputGain(gain)
        }
        if (remaining > 0L) return
        exitForSleepTimer(savePartialProgress = true)
    }

    private fun restoreSleepFade() {
        if (sleepFadeGain == 1f) return
        sleepFadeGain = 1f
        surface?.setOutputGain(1f)
    }

    /**
     * Leaves the player for a sleep timer that fired, or for one armed to stop
     * at the end of what was playing.
     *
     * The same two decisions the main player makes: it finishes rather than
     * holding a stopped frame on screen until the TV's own idle timer gives up,
     * and it disarms the binge chain first so the Up Next countdown cannot start
     * the next episode from behind a session that is already leaving.
     *
     * [savePartialProgress] is false on the end-of-episode path, where the
     * caller has already recorded the title as finished: saving again from here
     * would overwrite that completion with the resume point it was just
     * reported as.
     */
    private fun exitForSleepTimer(savePartialProgress: Boolean) {
        SleepTimer.cancel()
        cancelNextUpAutoAdvance()
        // Latch the handoff: whatever else runs as this activity goes down must
        // not start another episode.
        nextEpisodeHandoffStarted = true
        sleepFadeGain = 1f
        surface?.setOutputGain(1f)
        surface?.setPaused(true)
        if (savePartialProgress) saveProgress(reason = "sleep_timer")
        mediaSession?.release()
        mediaSession = null
        showToast("Sleep timer - playback stopped", 3_000L)
        finish()
    }

    // --- PiP ----------------------------------------------------------------

    /**
     * A Home press pops the picture into the corner instead of ending playback,
     * exactly as it does in the main player. This engine used to be the one
     * exception: it is the engine for anime and Dolby Vision titles, so those
     * were precisely the sessions where HOME dropped the viewer out of what they
     * were watching. The guards are shared with the main player (see PipSupport),
     * so both engines enter PiP - and refuse to - under the same rules.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPipIfEnabled()
    }

    override fun onPause() {
        super.onPause()
        // Belt and braces, as in the main player: onUserLeaveHint does not fire
        // for every way a window can lose focus, and this is skipped once the
        // corner window is already up.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isInPiPMode) {
            enterPipIfEnabled()
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPiPMode = isInPictureInPictureMode
        // The control bar is built for a full screen: in the corner it would
        // cover the picture, and the next press belongs to the system's PiP
        // chrome anyway. Playback itself is untouched - playing on in the corner
        // is the whole point.
        if (isInPictureInPictureMode) {
            removeAutoHide()
            setControlsVisible(View.GONE)
        }
    }

    private fun enterPipIfEnabled() {
        enterPipMode(this)
    }

    private fun exitPlayer() {
        surface?.setPaused(true)
        // Leaving from the end-of-episode card means the episode is over: save
        // it as watched rather than as a resume point, so backing out of the
        // card does not leave the finished episode unmarked with its old bar.
        val pos = runCatching { surface?.positionMs() ?: 0L }.getOrDefault(0L)
        val dur = runCatching { surface?.durationMs() ?: 0L }.getOrDefault(0L)
        val completedOnExit = shouldRecordCompletion(
            playbackEnded = endedHandled,
            endPanelsShown = endPanelsShown,
            positionMs = pos,
            durationMs = dur,
            played = sessionHasPlayed()
        )
        saveProgress(reason = "exit", forceCompleted = completedOnExit)
        scrobble("stop")
        finish()
    }

    // --- Helpers -----------------------------------------------------------

    private fun itemTitle(): String = when {
        itemName.isBlank() -> "Playback"
        season != null && episode != null && !episodeTitle.isNullOrBlank() ->
            "$itemName \u2014 S$season\u2009E$episode \u00b7 $episodeTitle"

        season != null && episode != null -> "$itemName \u2014 S$season\u2009E$episode"
        else -> itemName
    }

    // --- PlayerChromeHost: the shared overlay's calls into this engine --------
    //
    // This is a rename of the calls the control bar already made by hand: play /
    // pause, next, the pickers, aspect, settings, info, the engine switch and the
    // skip prompt all reach the same methods they always did.

    override fun chromeIsPlaying(): Boolean = surface?.isPaused() == false

    override fun chromePositionMs(): Long = positionMs

    override fun chromeDurationMs(): Long = durationMs

    override fun onChromePlayPause() {
        // Swallowed at the end of playback (see togglePlayPauseFromControls).
        togglePlayPauseFromControls()
    }

    override fun onChromeSeekTo(positionMs: Long) = seekTo(positionMs)

    override fun onChromeNext() {
        // The scheme-aware target, not `episode + 1`: on a show whose files hold
        // two segments each, adding one lands on the middle of a file the viewer
        // is already inside.
        val target = nextEpisodeTarget() ?: return
        launchNextEpisode(target.first, target.second)
    }

    override fun onChromeOpenSourcePicker() = showPicker(PickerMode.SOURCE)

    override fun onChromeOpenAudioPicker() = showPicker(PickerMode.AUDIO)

    override fun onChromeOpenSubtitlePicker() = showPicker(PickerMode.SUBTITLE)

    override fun onChromeOpenSpeedPicker() = showPicker(PickerMode.SPEED)

    override fun onChromeOpenAspectPicker() = cycleAspect()

    override fun onChromeOpenSettings() = showSettingsPanel()

    override fun onChromeOpenInfo() {
        // This readout is the engine's file-info screen: mpv's own account of
        // the file (resolution, codec, bitrate, decode mode, audio codec), plus
        // the engine name, because this is the only place on screen that says
        // whether the picture is coming from mpv or whether the session landed
        // here after ExoPlayer handed the file over. The overlay deliberately
        // carries none of this any more (see updateControlsInfo); the settings
        // panel's stream row reads the same string for a viewer already there.
        showToast("MPV  \u2022  ${diagnosticsText()}", 4_000L)
    }

    override fun onChromeSwitchPlayer() = switchToExoPlayerFromButton()

    override fun onChromeOpenExternal() = switchToExternal()

    override fun onChromeSkipIntro() = performSkip()

    override fun chromeTitleInfo(): ChromeTitleInfo = ChromeTitleInfo(
        clearLogoUrl = clearLogoUrl,
        itemName = itemName,
        episodeLabel = if (season != null && episode != null) {
            "S$season\u2009E$episode"
        } else {
            null
        },
        episodeTitle = episodeTitle,
        overview = overview,
        badges = currentBadges,
        cast = castMembers
    )

    override fun onChromeCastPressed(member: PlayerCastMember) = navigateToActor(member)

    companion object {
        private const val TAG = "PLAYER_MPV"
        private const val STATE_POSITION_MS = "mpv_position_ms"
        private const val COMPLETION_THRESHOLD_RATIO = 0.95f
        private const val MIN_RESUME_POSITION_MS = 10_000L
        private const val SEEK_STEP_MS = 10_000L
        private const val CONTROLS_TIMEOUT_MS = 8_000L

        /**
         * Above this a [showToast] call wants the platform's longer duration.
         * (LENGTH_SHORT is ~2s and LENGTH_LONG ~3.5s, hence the gap between
         * the 2.5s default and the 4s/5s reads that pass an explicit value.)
         */
        private const val TOAST_SHORT_MAX_MS = 3_000L

        /** Set when the handoff reason was the box running out of video decoders. */
        const val FALLBACK_REASON_DECODER = "decoder"

        /** Set when the handoff reason was any other unrecoverable error. */
        const val FALLBACK_REASON_ERROR = "error"

        /**
         * Set when the extractor refused the container itself — WMV/ASF and
         * anything else Media3 has no progressive extractor for (AVI is not
         * one of them: media3 ships an AviExtractor). The
         * decoder ladder cannot touch this: the failure arrives before a track
         * exists, so libmpv's own FFmpeg demuxers are the only thing that can
         * open the file.
         */
        const val FALLBACK_REASON_CONTAINER = "container"

        /**
         * Set when the viewer pressed the control bar's SWITCH button: the
         * same handoff, but nothing failed - so the notices must not call
         * this the backup engine.
         */
        const val FALLBACK_REASON_MANUAL = "manual"

        /**
         * Set when playback changed engines because the FILE's subtitles are a
         * bitmap format (PGS/VobSub/DVB) that this app's ExoPlayer engine has no
         * renderer for. Nothing about the video failed: the handoff is for the
         * subtitles, and libmpv renders them with libass and its own demuxers.
         *
         * Without this the failure was silent - media3 claims no PGS decoder,
         * so the track armed, matched the preferred language, and drew nothing,
         * raising no exception for the decoder ladder to react to.
         */
        const val FALLBACK_REASON_SUBTITLE = "subtitle"

        private const val EXTRA_STREAM_URL = "stream_url"

        /**
         * Canonical parent id resolved by whichever engine started playback.
         * The handoff passes it so the MPV session's history row lands under
         * the same parentId the ExoPlayer session wrote — Continue Watching
         * groups by that column, and two flavors of the same title means two
         * cards.
         */
        const val EXTRA_HISTORY_PARENT_ID = "history_parent_id"

        /** True when ExoPlayer handed this session over (shows a notice). */
        const val EXTRA_MPV_FALLBACK = "mpv_fallback"

        /** Why the handoff happened, for the notice and the diagnostics log. */
        const val EXTRA_MPV_FALLBACK_REASON = "mpv_fallback_reason"
    }
}

/** Rough key/value reader for the "Key: Value" per-line header extra. */
private fun parseHeaders(raw: String): Map<String, String> {
    if (raw.isBlank()) return emptyMap()
    return raw.lineSequence().mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isBlank()) return@mapNotNull null
        val separator = trimmed.indexOf(':')
        if (separator <= 0) return@mapNotNull null
        val key = trimmed.substring(0, separator).trim()
        val value = trimmed.substring(separator + 1).trim()
        if (key.isBlank() || value.isBlank()) null else key to value
    }.toMap()
}
