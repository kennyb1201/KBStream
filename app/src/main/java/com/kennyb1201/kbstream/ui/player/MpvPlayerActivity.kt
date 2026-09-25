package com.kennyb1201.kbstream.ui.player

import android.content.Intent
import android.graphics.drawable.GradientDrawable
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
import coil3.load
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.kennyb1201.kbstream.R
import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.data.badges.StreamBadge
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.player.ExternalPlayer
import com.kennyb1201.kbstream.data.player.LanguageMatch
import com.kennyb1201.kbstream.data.player.PlayerEngine
import com.kennyb1201.kbstream.data.player.PlayerTitlePrefs
import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import com.kennyb1201.kbstream.data.iptv.EpgWriteGate
import com.kennyb1201.kbstream.data.mdblist.MdbListClient
import com.kennyb1201.kbstream.data.simkl.SimklRepository
import com.kennyb1201.kbstream.data.sync.SupabaseSync
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.domain.streamengine.StreamRanker
import kotlinx.coroutines.withContext
import com.kennyb1201.kbstream.data.tv.TvLauncherPublisher
import com.kennyb1201.kbstream.ui.settings.AppPreferences
import com.kennyb1201.kbstream.ui.streams.StreamsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * The cast card's avatar circle fill, as fixed by its XML. A pure-black theme
 * has to repaint it (see [MpvPlayerActivity.refillPlayerChromeView]) instead of
 * leaving a grey circle behind every headshot - and the circle is all that shows
 * for the cast members TMDB has no photo for. Matches the main player's.
 */
private val AVATAR_PLACEHOLDER_FILL: Int = 0xFF1D2530.toInt()

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
class MpvPlayerActivity : ComponentActivity() {

    /**
     * Points the switch back at the MAIN engine.
     *
     * [switchToExoPlayer] builds its launch out of THIS activity's own intent,
     * so what it gives [exoSwitchLauncher] still names MpvPlayerActivity as its
     * component. Launched unchanged it opened a second MPV session instead of
     * ExoPlayer, which made the control bar's SWITCH look dead from this side
     * too. Every ActivityResultLauncher launch goes through here, so the
     * component is corrected at that one choke point.
     *
     * Only a switch-back launches this activity from here: the subtitle picker
     * opens the system's document UI, next-episode hands its result back to
     * MainActivity, and the now-playing intent goes out as a PendingIntent.
     */
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        if (intent.component?.className == MpvPlayerActivity::class.java.name) {
            intent.setClass(this, NativePlayerActivity::class.java)
            // Disarm this session's own Up Next countdown as playback leaves: it
            // is a Handler tick, so it fires whether or not this surface is the
            // one on screen. Left armed behind ExoPlayer it would chain an
            // episode out from under it and replace ExoPlayer's result with its
            // own, which reads exactly like the switch did nothing.
            cancelNextUpAutoAdvance()
        }
        super.startActivityForResult(intent, requestCode, options)
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
    private var loadingContainer: View? = null
    private var loadingTitle: TextView? = null
    private var loadingSubtitle: TextView? = null
    private var errorContainer: View? = null
    private var errorText: TextView? = null
    private var errorHint: TextView? = null
    private var errorSwitchButton: TextView? = null
    private var bufferingView: View? = null
    private var toastView: TextView? = null
    private var controlsContainer: View? = null
    private var loadingBackdropView: ImageView? = null
    private var loadingLogoView: ImageView? = null
    private var clearLogoView: ImageView? = null
    private var itemNameView: TextView? = null
    private var episodeLabelView: TextView? = null
    private var episodeTitleView: TextView? = null
    private var overviewView: TextView? = null
    private var engineNoteView: TextView? = null
    private var positionView: TextView? = null
    private var durationView: TextView? = null
    private var seekBar: SeekBar? = null
    private var playPauseButton: ImageView? = null
    // Icon buttons, like the main player's bar (see NativePlayerActivity): this
    // one used to draw "⏭" and "⇄" as font glyphs while SOURCE beside them was a
    // vector, so the two players' bars did not even match each other. SPEED and
    // ASPECT keep their words - a rate and a mode name ARE their state.
    private var nextButton: ImageView? = null
    private var playerSwitchButton: ImageView? = null
    private var externalButton: ImageView? = null
    private var speedButton: TextView? = null
    private var aspectButton: TextView? = null
    private var settingsContainer: View? = null
    private var settingsSection: MpvSettingsSection? = null

    // --- Picker: the same side panel the main player opens -------------------
    private var sourceButton: ImageView? = null
    private var pickerContainer: View? = null
    private var pickerTitle: TextView? = null
    private var pickerList: RecyclerView? = null

    // --- Badge row + cast band: the main player's two rows, over mpv --------
    private var badgeRow: LinearLayout? = null
    private var castSection: View? = null
    private var castRow: LinearLayout? = null

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

    /** Label of the source playing now, for the SOURCES picker's selected row. */
    private var currentSourceLabel: String? = null

    /** The playing source's badge chips, for the row the main player shows. */
    private var currentBadges: List<StreamBadge> = emptyList()

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
    private var scrubbing = false

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

    /** Where sidecar subtitles are copied; shared with the online downloads. */
    private val subtitleCacheDirName = "kbstream_subs"

    // --- External subtitles: the other half of the main player's SUBTITLES
    // picker ---------------------------------------------------------------
    private var externalSubtitleUri: Uri? = null
    private var externalSubtitleName: String? = null

    /** Results of the last online search, rendered as rows while they stand. */
    private var onlineSubResults: List<SubtitleSearchResult> = emptyList()
    private var onlineSubLoading = false

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
    private var nextUpCountdownRemaining = 0
    private var nextUpCountdownHeld = false

    private val nextUpCountdownHandler = Handler(Looper.getMainLooper())
    private val nextUpCountdownRunnable = object : Runnable {
        override fun run() {
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
    private var skipButton: TextView? = null
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

    // --- Session state, read from the launch intent -------------------------
    private var currentUrl = ""
    private var currentAudioUrl: String? = null
    private var parentId = ""
    private var parentType = ""
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
    private var streamHeaders: Map<String, String> = emptyMap()
    private var historyParentIdOverride: String? = null
    private var startPositionMs = 0L
    private var historyId = ""

    /** True when ExoPlayer handed this session over (see [EXTRA_MPV_FALLBACK]). */
    private var isFallbackSession = false
    private var fallbackReason: String? = null

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

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_mpv_player)

        readIntent(savedInstanceState)
        bindViews()
        historyId = PlaybackHistoryIds.historyId(parentId, season, episode, episodeStreamId)

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
        view.onFileLoaded = { title -> onFileLoaded(title) }
        view.onProgress = { position, duration -> onProgress(position, duration) }
        view.onPausedChanged = { paused -> onPausedChanged(paused) }
        view.onBufferingChanged = { buffering ->
            bufferingView?.visibility = if (buffering) View.VISIBLE else View.GONE
        }
        view.onEnded = { onPlaybackEnded() }
        view.onPlaybackError = { message ->
            showError(
                message,
                "The stream may be offline, or the source may have changed. " +
                    "Switch player to try this in ExoPlayer, or press Back to exit."
            )
        }

        setupControls()
        setupMediaSession()
        // IntroDB rows for this session: the skip prompt and the end-of-episode
        // panel's credits marker both come from them.
        setupIntroDb()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // BACK closes the panels first, exactly like the main player.
                    when {
                        pickerOpen -> dismissPicker()
                        settingsOpen -> hideSettingsPanel()
                        else -> exitPlayer()
                    }
                }
            }
        )

        // Languages for this session: what the title remembers, else the global
        // Settings preference. Set BEFORE initialize(), because `alang`/`slang`
        // are options the demuxer honours at open time - the fast path, with no
        // flicker of the wrong track.
        //
        // The subtitle look is deliberately not set from here: it is a global
        // display preference rather than a per-title one, and MpvPlayerView
        // applies it inside applyOptions() from those same preferences. Setting
        // it here would also be a call against an mpv instance that mpv does not
        // create until initialize().
        view.setLanguagePreferences(effectiveAudioLanguage(), effectiveSubtitleLanguage())

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

        view.load(
            MpvPlayerView.LoadRequest(
                url = currentUrl,
                headers = streamHeaders,
                audioUrl = currentAudioUrl,
                startPositionMs = startPositionMs
            )
        )
        showLoading(
            when {
                fallbackReason == FALLBACK_REASON_MANUAL -> "Switching to the MPV engine"
                isFallbackSession ->
                    "ExoPlayer could not play this stream \u2014 continuing in MPV"
                else -> "MPV engine"
            }
        )
    }

    // --- Intent ------------------------------------------------------------

    private fun readIntent(savedInstanceState: Bundle?) {
        val intent = intent ?: return
        currentUrl = intent.getStringExtra(EXTRA_STREAM_URL).orEmpty()
        currentAudioUrl = intent.getStringExtra("audio_url")
        parentId = intent.getStringExtra("parent_id").orEmpty()
        parentType = intent.getStringExtra("parent_type").orEmpty()
        season = intent.getIntExtra("season", -1).takeIf { it >= 0 }
        episode = intent.getIntExtra("episode", -1).takeIf { it >= 0 }
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
        historyParentIdOverride = intent.getStringExtra(EXTRA_HISTORY_PARENT_ID)
        isFallbackSession = intent.getBooleanExtra(EXTRA_MPV_FALLBACK, false)
        fallbackReason = intent.getStringExtra(EXTRA_MPV_FALLBACK_REASON)
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
        val parsedSources = parseSourcesJson(intent.getStringExtra("sources_json"))
        val orderedSources = if (AppPreferences.getUseStreamRanker(this)) {
            StreamRanker.rank(parsedSources)
        } else {
            parsedSources
        }
        sources = orderedSources.withCurrentSource(currentSourceStream(currentUrl, currentAudioUrl))
        val playingSource = sources.firstOrNull { it.url == currentUrl }
        currentSourceLabel = playingSource?.sourceLabel()
        currentBadges = playingSource?.badges.orEmpty()

        // The cast band's members, the same payload the main player renders.
        castMembers = parseCastJson(intent.getStringExtra("cast_json"))

        // A title started "from the beginning" must not become a resume.
        startPositionMs = if (intent.getBooleanExtra("from_beginning", false)) {
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
        bufferingView = findViewById(R.id.mpv_buffering)
        toastView = findViewById(R.id.mpv_toast)
        // The stream-info readout this engine's INFO button brings up - and
        // every other transient line - on the solid, theme-aware panel fill:
        // the translucent version it shipped with let the overlay's own
        // gradient wash through it, and it ignored the AMOLED toggle.
        toastView?.background = infoPanelDrawable(this)
        loadingBackdropView = findViewById(R.id.mpv_loading_backdrop)
        loadingLogoView = findViewById(R.id.mpv_loading_logo)
        controlsContainer = findViewById(R.id.mpv_controls)
        clearLogoView = findViewById(R.id.mpv_clear_logo)
        itemNameView = findViewById(R.id.mpv_item_name)
        episodeLabelView = findViewById(R.id.mpv_episode_label)
        episodeTitleView = findViewById(R.id.mpv_episode_title)
        overviewView = findViewById(R.id.mpv_overview)
        engineNoteView = findViewById(R.id.mpv_engine_note)
        positionView = findViewById(R.id.mpv_position)
        durationView = findViewById(R.id.mpv_duration)
        seekBar = findViewById(R.id.mpv_seekbar)
        playPauseButton = findViewById(R.id.mpv_btn_play_pause)
        nextButton = findViewById(R.id.mpv_btn_next)
        playerSwitchButton = findViewById(R.id.mpv_btn_player_switch)
        externalButton = findViewById(R.id.mpv_btn_player_external)
        speedButton = findViewById(R.id.mpv_btn_speed)
        aspectButton = findViewById(R.id.mpv_btn_aspect)
        sourceButton = findViewById(R.id.mpv_btn_source)
        pickerContainer = findViewById(R.id.mpv_picker_container)
        pickerTitle = findViewById(R.id.mpv_picker_title)
        pickerList = findViewById(R.id.mpv_picker_list)
        // Picker rows are inflated on demand, long after the chrome above was
        // themed, so each one is retinted as it attaches (a recycled row keeps
        // whatever background it was themed with).
        pickerList?.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    if (AppPreferences.getAmoledBlack(this@MpvPlayerActivity)) {
                        refillPlayerChrome(view)
                    }
                }

                override fun onChildViewDetachedFromWindow(view: View) = Unit
            }
        )
        badgeRow = findViewById(R.id.mpv_badge_row)
        castSection = findViewById(R.id.mpv_cast_section)
        castRow = findViewById(R.id.mpv_cast_row)
        setupCastRow()
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

        // The skip prompt is an accent-filled pill - the one control that acts
        // on the video rather than changing a setting, so it reads as the same
        // button the main player shows.
        skipButton = findViewById(R.id.mpv_skip_intro)
        skipButton?.setOnClickListener { performSkip() }
        skipButton?.setOnFocusChangeListener { view, focused ->
            applyPillBackground(view as TextView, selected = true, focused = focused)
        }
        skipButton?.let { applyPillBackground(it, selected = true, focused = false) }

        seekBar?.max = 1000
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
        view.background = when {
            selected && focused -> getDrawable(R.drawable.pill_chip_selected_focused_bg)
            selected -> getDrawable(R.drawable.pill_chip_selected_bg)
            focused -> getDrawable(R.drawable.pill_chip_focused_bg)
            // The neutral pill's fill is the theme's own surface, exactly like
            // the main player's applyPillBackground: the fixed
            // @drawable/pill_chip_bg would repaint #141A24 over a pure-black
            // theme every time a selection moved off a pill.
            else -> roundedPanelDrawable(this, playerPanelSurfaceColor(this), 6f)
        }
        view.setTextColor(getColor(if (selected) R.color.kb_void else R.color.kb_text_hi))
    }

    /**
     * The MPV chrome is plain XML with fixed @color/kb_surface /
     * @color/kb_surface_raised fills, so the AMOLED / pure-black toggles never
     * reached it: a pure-black theme still painted #141A24 buttons and #1D2530
     * panels. The main player re-resolves the same fills at runtime; this is
     * that pass, so both engines' chrome follows the theme together.
     */
    private fun applyPlayerChromeTheme() {
        // Without AMOLED the XML fills are already exactly right.
        if (!AppPreferences.getAmoledBlack(this)) return
        // getColor()/getCornerRadius() on a drawable are API 24+; older devices
        // simply keep the (dark, not pure-black) XML fills.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        // The end-of-episode popups carry their own fills on top of the chrome
        // walk below.
        applyPlayerPanelTheme()
        // The fatal-error card is a full-screen @color/kb_void fill - a plain
        // ColorDrawable, which the chrome walk (GradientDrawables only) cannot
        // reach - so AMOLED left it the fixed #0A0E14. Paint the theme's void,
        // the same pure black the rest of the app turns to.
        errorContainer?.setBackgroundColor(0xFF000000.toInt())
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
     * tree was themed.
     */
    private fun refillPlayerChrome(root: View) {
        refillPlayerChromeView(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                refillPlayerChrome(root.getChildAt(index))
            }
        }
    }

    /**
     * Retints one view when its background is one of the XML chrome fills.
     * Views are matched by the fill their own background carries, so every
     * button, pill and panel is covered without a hand-kept list, and each one
     * keeps its own corner radius and press ripple - the main player's rule.
     */
    private fun refillPlayerChromeView(view: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val background = view.background ?: return

        // A RippleDrawable IS a LayerDrawable, and layer 0 is only its content
        // while it actually has one: a ripple without a content layer must be
        // treated as "not a chrome fill" rather than assumed (see the main
        // player's refillPlayerChromeView for the crash this avoids).
        val ripple = background as? android.graphics.drawable.RippleDrawable
        val rippled = ripple != null

        val content = if (ripple == null) {
            background
        } else {
            if (ripple.numberOfLayers <= 0) return
            val contentIndex = (0 until ripple.numberOfLayers).firstOrNull { index ->
                runCatching {
                    ripple.getId(index) == android.R.id.content
                }.getOrDefault(false)
            }
            runCatching { ripple.getDrawable(contentIndex ?: 0) }.getOrNull() ?: return
        }

        val shape = content as? GradientDrawable ?: return
        val fill = shape.color?.defaultColor ?: return
        val replacement = when {
            fill == getColor(R.color.kb_surface) ->
                themedChromeBackground(playerPanelSurfaceColor(this), shape.cornerRadius, rippled)

            fill == getColor(R.color.kb_surface_raised) ->
                themedChromeBackground(playerPanelRaisedColor(this), shape.cornerRadius, false)

            fill == AVATAR_PLACEHOLDER_FILL ->
                themedAvatarBackground(playerPanelSurfaceColor(this))

            else -> null
        }
        replacement?.let { view.background = it }
    }

    /**
     * AMOLED-aware stand-in for one XML chrome fill: the same corner radius and
     * (for the ripple drawables) the same accent press ripple, but the fill
     * follows the theme toggles.
     */
    private fun themedChromeBackground(
        fill: Int,
        radiusPx: Float,
        rippled: Boolean
    ): android.graphics.drawable.Drawable {
        val body = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = radiusPx
        }
        if (!rippled) return body
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
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
    private fun themedAvatarBackground(fill: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
        }

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
                surface?.setPaused(false)
                keepControlsVisible()
            },
            onPause = {
                surface?.setPaused(true)
                keepControlsVisible()
            },
            onSkipNext = { skipToNextEpisode() },
            onSkipPrevious = {
                // The same rule the main player's session uses: near the start
                // of an episode, PREVIOUS means the one before this.
                if (positionMs > 5_000L) surface?.seekTo(0L) else skipToNextEpisode(-1)
            },
            onSeek = { position -> surface?.seekTo(position) }
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

    private fun setupControls() {
        updateNowPlayingText()
        updateControlsInfo()

        playPauseButton?.setOnClickListener {
            surface?.togglePause()
            keepControlsVisible()
        }
        nextButton?.setOnClickListener {
            val showSeason = season
            val showEpisode = episode
            if (showSeason != null && showEpisode != null) {
                launchNextEpisode(showSeason, showEpisode + 1)
            }
        }
        playerSwitchButton?.setOnClickListener {
            keepControlsVisible()
            switchToExoPlayerFromButton()
        }
        // Play in another app: the main player's third engine, offered here
        // too. Shown only where this box has an app to hand the stream to, so
        // the press never dead-ends on a box without one.
        externalButton?.visibility =
            if (PlayerEngine.externalAvailable(this)) View.VISIBLE else View.GONE
        externalButton?.setOnClickListener {
            keepControlsVisible()
            switchToExternal()
        }
        sourceButton?.setOnClickListener {
            keepControlsVisible()
            showPicker(PickerMode.SOURCE)
        }
        // AUDIO / SUBTITLES / SPEED open the same lists the main player opens,
        // in the same order: a track or a speed is picked by name rather than
        // stepped to blind.
        findViewById<ImageView>(R.id.mpv_btn_audio).setOnClickListener {
            keepControlsVisible()
            showPicker(PickerMode.AUDIO)
        }
        findViewById<ImageView>(R.id.mpv_btn_subtitle).setOnClickListener {
            keepControlsVisible()
            showPicker(PickerMode.SUBTITLE)
        }
        speedButton?.setOnClickListener {
            keepControlsVisible()
            showPicker(PickerMode.SPEED)
        }
        aspectButton?.setOnClickListener {
            cycleAspect()
            keepControlsVisible()
        }
        findViewById<ImageView>(R.id.mpv_btn_info).setOnClickListener {
            // The readout names its engine: this is the only place on screen
            // that says whether the picture is coming from mpv or whether the
            // session landed here after ExoPlayer handed the file over.
            showToast("MPV  •  ${diagnosticsText()}", 4_000L)
            keepControlsVisible()
        }
        findViewById<ImageView>(R.id.mpv_btn_settings).setOnClickListener {
            showSettingsPanel()
        }

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

        // Focus holds the overlay open, the same rule the main player uses.
        listOfNotNull(playPauseButton, nextButton, sourceButton, speedButton, aspectButton).forEach { button ->
            button.setOnFocusChangeListener { _, focused ->
                if (focused) removeAutoHide() else keepControlsVisible()
            }
        }

        // The seekbar scrubs, it is not a readout: the same as the main
        // player's. Seeking happens on release - mpv seeks by keyframe, and a
        // remote fires a lot of progress changes on the way.
        seekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || durationMs <= 0L) return
                positionView?.text = formatClock(durationMs * progress / 1000L)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {
                scrubbing = true
                removeAutoHide()
            }

            override fun onStopTrackingTouch(bar: SeekBar?) {
                scrubbing = false
                if (durationMs > 0L) {
                    surface?.seekTo(durationMs * (bar?.progress ?: 0) / 1000L)
                }
                keepControlsVisible()
            }
        })
    }

    // --- Chrome, matched to the main player's -----------------------------

    /**
     * Fills the top block: clear logo or name, episode row, synopsis, and the
     * next-episode button when this session knows which episode it is on.
     */
    private fun updateNowPlayingText() {
        val logoUrl = clearLogoUrl
        val logo = clearLogoView
        if (logo != null && !logoUrl.isNullOrBlank()) {
            runCatching { logo.load(logoUrl) }
            logo.visibility = View.VISIBLE
            itemNameView?.visibility = View.GONE
        } else {
            logo?.setImageDrawable(null)
            logo?.visibility = View.GONE
            itemNameView?.visibility = View.VISIBLE
        }
        itemNameView?.text = itemName

        val showSeason = season
        val showEpisode = episode
        val hasEpisode = showSeason != null && showEpisode != null
        episodeLabelView?.text = if (hasEpisode) "S$showSeason\u2009E$showEpisode" else null
        episodeLabelView?.visibility = if (hasEpisode) View.VISIBLE else View.GONE
        episodeTitleView?.text = episodeTitle
        episodeTitleView?.visibility =
            if (!episodeTitle.isNullOrBlank()) View.VISIBLE else View.GONE
        overviewView?.text = overview
        overviewView?.visibility = if (!overview.isNullOrBlank()) View.VISIBLE else View.GONE
        nextButton?.visibility = if (hasEpisode) View.VISIBLE else View.GONE
    }

    /**
     * The engine note that stands where the main player's source badges sit, and
     * the two buttons that read state out in place (aspect, and the speed label
     * the picker leaves behind).
     */
    private fun updateControlsInfo() {
        engineNoteView?.text = buildString {
            // Which source is playing comes first: it is what the main player's
            // badge row says there, and it can change mid-session now that
            // SOURCES can switch without leaving the title.
            currentSourceLabel?.takeIf { it.isNotBlank() }?.let { append("$it  \u00b7  ") }
            append(
                when {
                    !isFallbackSession -> "MPV engine"
                    fallbackReason == FALLBACK_REASON_MANUAL ->
                        "MPV engine  \u00b7  switched from ExoPlayer on the remote"
                    else -> "MPV backup engine"
                }
            )
            if (isFallbackSession && fallbackReason == FALLBACK_REASON_DECODER) {
                append("  \u00b7  ExoPlayer had run out of video decoders")
            }
            val parsed = surface?.diagnostics().orEmpty()
            if (parsed.isNotBlank()) append("  \u00b7  $parsed")
        }
        engineNoteView?.visibility = View.VISIBLE
        speedButton?.text = "${playbackSpeed}x"
        aspectButton?.text = ASPECT_MODES.getOrElse(resizeModeIndex) { "Fit" }
        // The playing source's own badge chips - the same shared adapter the
        // main player's row uses, so the two cannot drift apart.
        badgeRow?.let { PickerAdapter.bindBadgeRow(it, currentBadges) }
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
    private fun setupCastRow() {
        val section = castSection ?: return
        val row = castRow ?: return
        if (castMembers.isEmpty()) {
            section.visibility = View.GONE
            return
        }
        section.visibility = View.VISIBLE
        row.removeAllViews()
        castMembers.forEach { member ->
            val itemView = layoutInflater.inflate(R.layout.cast_member_item, row, false)
            // The band is filled a beat after the activity's own chrome was
            // themed, so each card is themed as it lands - the same reason the
            // picker's rows retint when they attach.
            if (AppPreferences.getAmoledBlack(this@MpvPlayerActivity)) refillPlayerChrome(itemView)
            // The shared tile points its next-focus at the main player's
            // seekbar, which is not on screen in this layout: re-point it at
            // mpv's own so D-pad DOWN still lands on the bar.
            itemView.nextFocusDownId = R.id.mpv_seekbar
            itemView.findViewById<TextView>(R.id.cast_member_name).text = member.name
            itemView.findViewById<TextView>(R.id.cast_member_character).apply {
                val character = member.character
                if (character.isNullOrBlank()) {
                    visibility = View.GONE
                } else {
                    text = character
                    visibility = View.VISIBLE
                }
            }
            itemView.findViewById<ImageView>(R.id.cast_member_image).apply {
                val url = member.profileImageUrl()
                if (url.isNullOrBlank()) {
                    setImageResource(R.drawable.ic_cast_placeholder)
                } else {
                    runCatching { load(url) }
                        .onFailure { setImageResource(R.drawable.ic_cast_placeholder) }
                }
            }
            itemView.setOnClickListener { navigateToActor(member) }
            row.addView(itemView)
        }
    }

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
                        surface?.clearSubtitles()
                        clearExternalSubtitle()
                        refreshSettings()
                        dismissPicker()
                    }
                )
                listOfNotNull(searchItem, openFileItem) + onlineRows + listOf(offItem) +
                    tracks.map { track ->
                        PickerItem(
                            label = track.label,
                            isSelected = track.selected,
                            onClick = {
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
        controlsContainer?.visibility = View.VISIBLE
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
     * Copies the picked document into the cache and hands it to mpv.
     *
     * A copy rather than the URI itself: mpv resolves plain paths, and a URI
     * whose permission grant dies with this activity would leave the subtitle
     * unreadable part-way through an episode.
     */
    private fun attachExternalSubtitle(uri: Uri) {
        showToast("Loading subtitle\u2026")
        lifecycleScope.launch {
            val copied = runCatching {
                withContext(Dispatchers.IO) {
                    val directory = File(cacheDir, subtitleCacheDirName).apply { mkdirs() }
                    val target = File(directory, "sidecar-${System.nanoTime()}-${displayNameFor(uri)}")
                    val stream = contentResolver.openInputStream(uri)
                        ?: return@withContext null
                    stream.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    target
                }
            }.getOrNull()
            if (copied == null) {
                showToast("Could not read that subtitle file", 4_000L)
                return@launch
            }
            externalSubtitleUri = uri
            externalSubtitleName = copied.name
            surface?.addExternalSubtitle(Uri.fromFile(copied).toString())
            showToast("Subtitle loaded: ${copied.name}", 4_000L)
            refreshSettings()
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

    /** Downloads the picked hit into cache and hands it to mpv. */
    private fun downloadOnlineSubtitle(hit: SubtitleSearchResult) {
        showToast("Loading subtitle\u2026")
        lifecycleScope.launch {
            val body = SubtitleSearchHelper.download(this@MpvPlayerActivity, hit)
            if (body.isNullOrBlank()) {
                showToast("Subtitle download failed", 4_000L)
                return@launch
            }
            val uri = SubtitleSearchHelper.toCacheUri(this@MpvPlayerActivity, hit, body)
            externalSubtitleUri = uri
            externalSubtitleName = hit.fileName
            surface?.addExternalSubtitle(uri.toString())
            showToast("Subtitle loaded: ${hit.fileName}", 4_000L)
            refreshSettings()
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

    private fun switchToSource(stream: Stream) {
        val newUrl = stream.url ?: return
        if (newUrl == currentUrl) return

        val resumeAt = (if (positionMs > 0L) positionMs else startPositionMs).coerceAtLeast(0L)
        currentUrl = newUrl
        currentAudioUrl = stream.audioUrl
        currentSourceLabel = stream.sourceLabel()
        currentBadges = stream.badges
        startPositionMs = resumeAt
        endedHandled = false
        completionSent = false
        endPanelsShown = false
        fileLoaded = false
        autoSkippedSegments.clear()
        // The segment the old file's playhead was inside belongs to that file:
        // the prompt goes with it instead of hanging over the new load.
        activeSkipStamp = null
        hideSkipPrompt()

        Log.w(TAG, "source switch -> ${stream.sourceLabel()} at ${resumeAt}ms")
        // The note reads out which source is playing, so it has to follow.
        updateControlsInfo()
        showLoading("Switching source…")
        surface?.load(
            MpvPlayerView.LoadRequest(
                url = newUrl,
                headers = streamHeaders,
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
        controlsContainer?.visibility = View.VISIBLE
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
        audioDownmix = remembered?.audioDownmix ?: -1
        audioDialogueBoost = remembered?.audioDialogueBoost ?: -1
        audioVolumeBoostDb = remembered?.audioVolumeBoostDb ?: -1

        playbackSpeed = 1f
        resizeModeIndex = AppPreferences.getDefaultAspectRatio(this)
        subtitleSize = AppPreferences.getDefaultSubtitleSize(this)
        subtitleBackground = AppPreferences.getDefaultSubtitleBackground(this)
        subtitlePosition = AppPreferences.getDefaultSubtitlePosition(this)
    }

    private fun persistTitlePreferences() {
        val key = titleKey ?: return
        PlayerTitlePrefs.remember(
            context = this,
            key = key,
            prefs = PlayerTitlePrefs.Prefs(
                audioLang = audioLanguage,
                subtitleLang = subtitleLanguage,
                subtitleOffsetMs = subtitleOffsetMs,
                audioDelayMs = audioDelayMs,
                audioTrackSignature = audioTrackSignature,
                audioDownmix = audioDownmix,
                audioDialogueBoost = audioDialogueBoost,
                audioVolumeBoostDb = audioVolumeBoostDb
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
        view.selectSubtitleLanguage(effectiveSubtitleLanguage())
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
        persistTitlePreferences()
        val effective = effectiveSubtitleLanguage()
        surface?.setLanguagePreferences(effectiveAudioLanguage(), effective)
        surface?.selectSubtitleLanguage(effective)
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
        AppPreferences.setDefaultAspectRatio(this, index)
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
        audioTrackSignature = ""
        audioDelayMs = 0
        subtitleOffsetMs = 0
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
                tmdbId = runCatching { tmdbId() }.getOrNull(),
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
        }
    }

    /**
     * The episode the end of a session should chain into: the next one, or the
     * first of the next season when this was the season's last. Mirrors
     * NativePlayerActivity.nextEpisodeTarget() so both engines chain alike.
     */
    private fun nextEpisodeTarget(): Pair<Int, Int>? {
        val showSeason = season ?: return null
        val showEpisode = episode ?: return null
        val nextEpisode = showEpisode + 1
        val maxEpisodes = totalEpisodesInSeason
        return if (maxEpisodes != null && nextEpisode > maxEpisodes) {
            (showSeason + 1) to 1
        } else {
            showSeason to nextEpisode
        }
    }

    /** Fills and shows the card, then arms its countdown. */
    private fun showNextUpPanel(targetSeason: Int, targetEpisode: Int) {
        val panel = nextUpPanel ?: return
        pendingNextSeason = targetSeason
        pendingNextEpisode = targetEpisode
        pendingNextEpisodeName = null

        nextUpShowTitle?.text = itemName
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
                nextUpCountdown?.setTextColor(getColor(R.color.kb_accent))
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
        launchNextEpisode(showSeason, showEpisode)
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
                runCatching { tmdbId() }.getOrNull()
            } ?: return@launch
            val nextEp = withContext(Dispatchers.IO) {
                runCatching {
                    TmdbRepository.getInstance(this@MpvPlayerActivity)
                        .getSeasonEpisodes(tmdb, targetSeason, parentId)
                }.getOrNull()?.firstOrNull { it.episodeNumber == targetEpisode }
            } ?: return@launch
            if (nextUpPanel?.visibility != View.VISIBLE) return@launch
            pendingNextEpisodeName = nextEp.name
            nextUpEpisodeTitle?.text = nextEp.name ?: "S${targetSeason}E$targetEpisode"
            nextEp.thumbnail?.takeIf { it.isNotBlank() }?.let { still ->
                runCatching { nextUpThumb?.load(still) }
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
        controlsContainer?.visibility = View.GONE
        enterCreditsMode()

        lifecycleScope.launch {
            val picks: List<BywPick> = withContext(Dispatchers.IO) {
                val tmdb = runCatching { tmdbId() }.getOrNull()
                    ?: return@withContext emptyList()
                buildBecauseYouWatchedPicks(
                    this@MpvPlayerActivity,
                    tmdb,
                    bywMediaType(parentType)
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
     * this engine's own - its theme colours, its pill styling, and where PLAY /
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

    /** PLAY: resolve streams for the pick and hand the top one back. */
    private fun bywPlayPick(pick: BywPick, imdbId: String) {
        lifecycleScope.launch {
            val vm = StreamsViewModel(application = application)
            val streams = withContext(Dispatchers.IO) {
                runCatching { vm.resolve(pick.type, imdbId) }.getOrNull()
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

    /** DETAILS: hand the pick back for the catalog's detail screen. */
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

    /**
     * The result contract MainActivity already understands from the main
     * player: "play_now" reopens the player on the resolved stream,
     * "go_details" opens the detail screen.
     */
    private fun finishWithBywResult(
        action: String,
        pick: BywPick,
        imdbId: String,
        streamUrl: String?,
        streamName: String?
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
                putExtra("byw_stream_url", streamUrl)
                putExtra("byw_stream_name", streamName)
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
                runCatching { fetchIntroDbStamps(parentId, season, episode) }
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
        val button = skipButton ?: return
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
        button.text = matching.type.buttonLabel
        button.visibility = View.VISIBLE
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
        surface?.seekTo(target)
    }

    /**
     * Takes the prompt down, handing focus back to the control bar when it held
     * it: a hidden view cannot keep focus, and without a new target the next
     * D-pad press would go nowhere at all.
     */
    private fun hideSkipPrompt() {
        val button = skipButton ?: return
        val wasFocused = button.isFocused
        button.visibility = View.GONE
        if (wasFocused && controlsVisible) playPauseButton?.requestFocus()
    }

    // --- Playback callbacks ------------------------------------------------

    private fun onFileLoaded(mediaTitle: String?) {
        fileLoaded = true
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
        // Scrobble once the file is really open — a stream that never loads
        // must not appear on a tracker as started.
        if (!scrobbleStarted) {
            scrobbleStarted = true
            scrobble("start")
        }
        // A hand switch needs no notice - the button press said it, and the
        // engine note above the control bar now reads "switched from
        // ExoPlayer" - so this stays the automatic handoff's announcement.
        if (isFallbackSession && fallbackReason != FALLBACK_REASON_MANUAL) {
            val hint = if (fallbackReason == FALLBACK_REASON_DECODER) {
                "\u2014 if it stutters, open the gear and set Decoding to Software"
            } else {
                ""
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
        runOnUiThread {
            durationView?.text = if (durationMs > 0L) formatClock(durationMs) else "--:--"
            // While the seekbar is being dragged it owns the readout, so a
            // progress tick cannot yank the thumb back out from under it.
            if (!scrubbing) {
                positionView?.text = formatClock(positionMs)
                if (durationMs > 0L) {
                    val progress = ((positionMs * 1000L) / durationMs).toInt().coerceIn(0, 1000)
                    seekBar?.progress = progress
                }            }

            // The card opens as the credits roll, exactly as it does in the main
            // player, instead of waiting for the file to end.
            maybeTriggerEndPanels(positionMs, durationMs)

            // ...and the skip prompt follows the playhead through whatever
            // IntroDB segment it is inside.
            updateSkipPrompt(positionMs, durationMs)

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
    }

    /**
     * End of file. keep-open holds the last frame, so this is reached with the
     * player still alive: the completion write happens first, then the next
     * episode is handed to MainActivity the same way the main player does it.
     */
    private fun onPlaybackEnded() {
        if (endedHandled) return
        endedHandled = true
        runOnUiThread {
            bufferingView?.visibility = View.GONE
            saveProgress(reason = "ended", forceCompleted = true)
            scrobble("stop", progressOverride = 100.0)

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
     * Next-episode handoff. Same two channels as the main player: the persisted
     * [NextEpisodeResult] (survives MainActivity being killed while the player
     * was up) and the classic result extras for the live callback.
     */
    private fun launchNextEpisode(targetSeason: Int, targetEpisode: Int) {
        val pending = NextEpisodeResult.PendingNext(
            season = targetSeason,
            episode = targetEpisode,
            title = "S$targetSeason\u2009E$targetEpisode",
            streamId = nextStreamId(targetSeason, targetEpisode),
            runtimeMinutes = null,
            bingeGroup = null,
            addonName = null,
            randomEpisodes = false
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
                putExtra("next_random", false)
            }
        )
        finish()
    }

    /** Mirrors NativePlayerActivity.nextStreamId: the episode id's own prefix. */
    private fun nextStreamId(targetSeason: Int, targetEpisode: Int): String {
        val prefix = episodeStreamId.orEmpty()
            .substringBeforeLast(':')
            .substringBeforeLast(':')
        return if (prefix.isNotBlank()) {
            "$prefix:$targetSeason:$targetEpisode"
        } else {
            "$parentId:$targetSeason:$targetEpisode"
        }
    }

    // --- Watch history -----------------------------------------------------

    /**
     * Writes the same row the main player writes — same id, same fields, same
     * canonical parentId — so a session that started in ExoPlayer and finished
     * here updates ONE Continue Watching card.
     */
    private fun saveProgress(reason: String, forceCompleted: Boolean = false) {
        val view = surface ?: return
        if (parentId.isBlank() || historyId.isBlank()) return

        val position = view.positionMs().coerceAtLeast(0L)
        val duration = view.durationMs()
        if (duration <= 0L) return
        if (position < MIN_RESUME_POSITION_MS && !forceCompleted) return

        val completed = forceCompleted || position >= (duration * COMPLETION_THRESHOLD_RATIO).toLong()
        val safePosition = if (completed) 0L else position.coerceAtMost(duration)
        val now = System.currentTimeMillis()
        if (completed) completionSent = true

        Log.i(
            TAG,
            "save progress ($reason): ${safePosition}ms / ${duration}ms completed=$completed"
        )

        // NonCancellable: this write must land even while the activity is being
        // torn down, exactly like the main player's exit save.
        lifecycleScope.launch(Dispatchers.IO + NonCancellable) {
            runCatching {
                val dao = WatchHistoryDatabase.getInstanceScoped(this@MpvPlayerActivity)
                    .watchHistoryDao()
                val existing = dao.getById(historyId)
                val entry = WatchHistoryEntity(
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
                    durationMs = duration,
                    updatedAt = now,
                    isCompleted = completed,
                    completedAt = if (completed) existing?.completedAt ?: now else null
                )
                dao.upsert(entry)
                SupabaseSync.enqueueHistory(entry)
                TvLauncherPublisher.sync(this@MpvPlayerActivity, dao.getAll())
            }.onFailure { Log.w(TAG, "could not write watch history", it) }
            if (completed) pushCompletion()
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
    private fun switchToExoPlayer() {
        if (playerSwitchStarted) return
        if (isFinishing || isDestroyed) return
        if (currentUrl.isBlank()) return
        val baseIntent = intent ?: return
        playerSwitchStarted = true

        val position = runCatching {
            surface?.positionMs()?.coerceAtLeast(0L) ?: positionMs
        }.getOrDefault(positionMs)

        val launch = Intent(baseIntent).apply {
            // Extras that describe THIS engine, not the session: ExoPlayer has
            // no use for them, and a leftover "this is a fallback" flag would
            // only mislabel the new session's notices.
            removeExtra(EXTRA_MPV_FALLBACK)
            removeExtra(EXTRA_MPV_FALLBACK_REASON)
            putExtra("stream_url", currentUrl)
            putExtra("audio_url", currentAudioUrl)
            putExtra("start_position_ms", position)
            // Resume, never restart: the file is already part-way through.
            putExtra("from_beginning", false)
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
        exoSwitchLauncher.launch(launch)
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

        val position = runCatching {
            surface?.positionMs()?.coerceAtLeast(0L) ?: positionMs
        }.getOrDefault(positionMs)

        val launch = Intent(baseIntent).apply {
            setClass(this@MpvPlayerActivity, ExternalPlayerActivity::class.java)
            // Extras that describe THIS engine, not the session.
            removeExtra(EXTRA_MPV_FALLBACK)
            removeExtra(EXTRA_MPV_FALLBACK_REASON)
            putExtra("stream_url", currentUrl)
            putExtra("start_position_ms", position)
            putExtra("from_beginning", false)
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

            raw.startsWith("tt") -> null

            else -> PlaybackHistoryIds.resolveTmdbId(this, raw, parentType)
        }
        resolvedTmdbId = resolved
        return resolved
    }

    // --- Scrobbling --------------------------------------------------------

    private fun scrobble(action: String, progressOverride: Double? = null) {
        if (parentId.isBlank()) return
        val progress = progressOverride ?: if (durationMs > 0L) {
            ((positionMs.toDouble() / durationMs.toDouble()) * 100.0).coerceIn(0.0, 100.0)
        } else {
            0.0
        }
        // Independent scope: a stop scrobble has to outlive this activity.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
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
            runCatching { scrobbleMdbList(action, progress) }
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
        trackersMarkedWatched = true
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
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
                            simkl.pushWatchedEpisode(
                                showImdbId = parentId,
                                season = showSeason,
                                episode = showEpisode,
                                title = itemName,
                                tmdbId = tmdb
                            )
                        } else {
                            false
                        }
                    }

                    else -> false
                }
            }.onFailure { Log.w(TAG, "Simkl completion sync failed", it) }
            runCatching {
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
        }
    }

    // --- Controls ----------------------------------------------------------

    private fun showLoading(subtitle: String) {
        loadingContainer?.visibility = View.VISIBLE
        loadingTitle?.text = itemTitle()
        loadingSubtitle?.text = subtitle
        bufferingView?.visibility = View.GONE
    }

    private fun showError(message: String, hint: String) {
        loadingContainer?.visibility = View.GONE
        bufferingView?.visibility = View.GONE
        controlsContainer?.visibility = View.GONE
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
        errorSwitchButton?.post { errorSwitchButton?.requestFocus() }
    }

    private fun showControls() {
        controlsContainer?.visibility = View.VISIBLE
        playPauseButton?.requestFocus()
        keepControlsVisible()
    }

    private fun keepControlsVisible() {
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, CONTROLS_TIMEOUT_MS)
    }

    private val hideControlsRunnable = Runnable {
        // The settings panel is its own screen: hiding the chrome under it would
        // strand the user in the panel with nothing to go back to.
        if (settingsOpen) return@Runnable
        controlsContainer?.visibility = View.GONE
    }

    /** Holds the overlay open while one of its buttons has focus. */
    private fun removeAutoHide() {
        handler.removeCallbacks(hideControlsRunnable)
    }

    private val controlsVisible: Boolean
        get() = controlsContainer?.visibility == View.VISIBLE

    private fun showToast(message: String, durationMs: Long = 2_500L) {
        val view = toastView ?: return
        view.text = message
        view.visibility = View.VISIBLE
        handler.postDelayed({ view.visibility = View.GONE }, durationMs)
    }

    /**
     * D-pad first. While the overlay is hidden, OK plays/pauses and left/right
     * seek (the press that reveals the controls also does what was asked of
     * it); once it is up, the buttons take focus and OK activates them, which
     * is what a TV remote expects.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The settings panel is a side panel, not a takeover: while it is up the
        // focus system owns the D-pad (BACK still reaches the activity, which
        // closes the panel), exactly as it does in the main player.
        if (settingsOpen) return super.dispatchKeyEvent(event)
        // A visible skip prompt owns OK while the chrome is down: pressing OK
        // during an intro should skip it, not pause underneath the prompt (the
        // same rule the main player uses). With the overlay up the prompt is an
        // ordinary focusable, so OK reaches it through the focus system instead.
        if (skipButton?.visibility == View.VISIBLE && !controlsVisible &&
            event.action == KeyEvent.ACTION_DOWN
        ) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    // One press, one skip: autorepeat must not skip twice.
                    if (event.repeatCount == 0) skipButton?.performClick()
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
            if (event.action == KeyEvent.ACTION_DOWN && horizontal &&
                bywUi?.hasFocus() == false && bywUi?.focusFirst() == true
            ) {
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN && errorContainer?.visibility != View.VISIBLE) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    surface?.seekBy(-SEEK_STEP_MS)
                    return true
                }

                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    surface?.seekBy(SEEK_STEP_MS)
                    return true
                }

                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    surface?.togglePause()
                    showControls()
                    return true
                }

                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (!controlsVisible) {
                        surface?.togglePause()
                        showControls()
                        return true
                    }
                }

                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!controlsVisible) {
                        surface?.seekBy(
                            if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                                -SEEK_STEP_MS
                            } else {
                                SEEK_STEP_MS
                            }
                        )
                        showControls()
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
    }

    override fun onStop() {
        super.onStop()
        // mpv is paused just below, so guide writes may proceed again.
        EpgWriteGate.setPlayerActive(false)
        handler.removeCallbacks(hideControlsRunnable)
        // Pause rather than tear down: the native instance is released in
        // onDestroy, and a backgrounded player that kept playing would be a bug
        // report of its own.
        surface?.setPaused(true)
        saveProgress(reason = "stop")
        scrobble("stop")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) abandonAudioFocus()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Safety net for a player that never reached onStop: leaving this
        // set would hold guide writes back for the life of the process.
        EpgWriteGate.setPlayerActive(false)
        handler.removeCallbacksAndMessages(null)
        nextUpCountdownHandler.removeCallbacksAndMessages(null)
        surface?.release()
        surface = null
    }

    private fun exitPlayer() {
        surface?.setPaused(true)
        saveProgress(reason = "exit")
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

    private fun formatClock(ms: Long): String {
        val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    companion object {
        private const val TAG = "PLAYER_MPV"
        private const val STATE_POSITION_MS = "mpv_position_ms"
        private const val COMPLETION_THRESHOLD_RATIO = 0.95f
        private const val MIN_RESUME_POSITION_MS = 10_000L
        private const val SEEK_STEP_MS = 10_000L
        private const val CONTROLS_TIMEOUT_MS = 8_000L

        /** Set when the handoff reason was the box running out of video decoders. */
        const val FALLBACK_REASON_DECODER = "decoder"

        /** Set when the handoff reason was any other unrecoverable error. */
        const val FALLBACK_REASON_ERROR = "error"

        /**
         * Set when the viewer pressed the control bar's SWITCH button: the
         * same handoff, but nothing failed - so the notices must not call
         * this the backup engine.
         */
        const val FALLBACK_REASON_MANUAL = "manual"

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
