package com.kennyb1201.kbstream.ui.player

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
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
import androidx.annotation.RequiresApi
import androidx.lifecycle.lifecycleScope
import com.kennyb1201.kbstream.R
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.player.LanguageMatch
import com.kennyb1201.kbstream.data.player.PlayerTitlePrefs
import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import com.kennyb1201.kbstream.data.mdblist.MdbListClient
import com.kennyb1201.kbstream.data.simkl.SimklRepository
import com.kennyb1201.kbstream.data.sync.SupabaseSync
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import kotlinx.coroutines.withContext
import com.kennyb1201.kbstream.data.tv.TvLauncherPublisher
import com.kennyb1201.kbstream.ui.settings.AppPreferences
import com.kennyb1201.kbstream.ui.streams.StreamsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
 * What is deliberately NOT here, because it belongs to ExoPlayer: the source
 * picker and stream ranking, the Dolby Vision compat layer, the P5 GPU
 * correction, the audio tuning chain, intro/credits skip, and the media
 * session. This engine's job is to get a picture on screen for a file the main
 * player could not open at all — and the chrome it does have mirrors the main
 * player's (play/pause, next episode, audio, subtitles, speed, aspect, stream
 * info, and a settings panel holding the engine-specific controls), so a
 * session that lands here does not feel like a different app. ±10s seeking is
 * the D-pad while the overlay is down, and the hardware/software decoding
 * switch lives in that settings panel.
 *
 * Two things the engines genuinely share rather than mirror: the per-title
 * memory (languages, A/V offsets, chosen track - kept under the main player's
 * own key, so a title remembers itself whichever engine played it last) and the
 * end-of-playback panels, which are the same Up Next card and the same
 * Because-you-watched row, built by the same shared code.
 */
class MpvPlayerActivity : ComponentActivity() {

    private var surface: MpvPlayerView? = null
    private var loadingContainer: View? = null
    private var loadingTitle: TextView? = null
    private var loadingSubtitle: TextView? = null
    private var errorContainer: View? = null
    private var errorText: TextView? = null
    private var errorHint: TextView? = null
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
    private var nextButton: TextView? = null
    private var speedButton: TextView? = null
    private var aspectButton: TextView? = null
    private var settingsContainer: View? = null
    private var settingsSection: MpvSettingsSection? = null

    /** True while the settings side panel is up (BACK closes it first). */
    private var settingsOpen = false

    /** True while the seekbar is being dragged, so progress cannot fight it. */
    private var scrubbing = false

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

        val view = findViewById<MpvPlayerView>(R.id.mpv_surface)
        surface = view
        view.onEngineFailed = { message ->
            Log.e(TAG, "MPV engine unavailable: $message")
            showError(
                message,
                "Pick ExoPlayer in Settings \u2192 Playback engine. Press Back to exit."
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
                    "Press Back to exit, then try another source."
            )
        }

        setupControls()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // BACK closes the panel first, exactly like the main player.
                    if (settingsOpen) hideSettingsPanel() else exitPlayer()
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

        view.load(
            MpvPlayerView.LoadRequest(
                url = currentUrl,
                headers = streamHeaders,
                audioUrl = currentAudioUrl,
                startPositionMs = startPositionMs
            )
        )
        showLoading(
            if (isFallbackSession) {
                "ExoPlayer could not play this stream \u2014 continuing in MPV"
            } else {
                "MPV engine"
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
        bufferingView = findViewById(R.id.mpv_buffering)
        toastView = findViewById(R.id.mpv_toast)
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
        speedButton = findViewById(R.id.mpv_btn_speed)
        aspectButton = findViewById(R.id.mpv_btn_aspect)
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
        view.setBackgroundResource(
            when {
                selected && focused -> R.drawable.pill_chip_selected_focused_bg
                selected -> R.drawable.pill_chip_selected_bg
                focused -> R.drawable.pill_chip_focused_bg
                else -> R.drawable.pill_chip_bg
            }
        )
        view.setTextColor(getColor(if (selected) R.color.kb_void else R.color.kb_text_hi))
    }

    /**
     * The main player's control bar, driven by mpv instead: play / pause with
     * the same two icons, next episode when this session knows its episode, then
     * audio / subtitles / speed / aspect / info / settings on the right. LEFT and
     * RIGHT reach the same places they do there, and the overlay hides itself
     * after the same six seconds without input.
     */
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
        findViewById<TextView>(R.id.mpv_btn_audio).setOnClickListener {
            showToast(surface?.cycleAudioTrack() ?: "Audio")
            refreshSettings()
            keepControlsVisible()
        }
        findViewById<TextView>(R.id.mpv_btn_subtitle).setOnClickListener {
            showToast(surface?.cycleSubtitleTrack() ?: "Subtitles")
            refreshSettings()
            keepControlsVisible()
        }
        speedButton?.setOnClickListener {
            cycleSpeed()
            keepControlsVisible()
        }
        aspectButton?.setOnClickListener {
            cycleAspect()
            keepControlsVisible()
        }
        findViewById<TextView>(R.id.mpv_btn_info).setOnClickListener {
            showToast(surface?.diagnostics() ?: "Stream info", 4_000L)
            keepControlsVisible()
        }
        findViewById<TextView>(R.id.mpv_btn_settings).setOnClickListener {
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
        listOfNotNull(playPauseButton, nextButton, speedButton, aspectButton).forEach { button ->
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
     * the two buttons that read out state instead of opening a picker.
     */
    private fun updateControlsInfo() {
        engineNoteView?.text = buildString {
            append(if (isFallbackSession) "MPV backup engine" else "MPV engine")
            if (isFallbackSession && fallbackReason == FALLBACK_REASON_DECODER) {
                append("  \u00b7  ExoPlayer had run out of video decoders")
            }
            val parsed = surface?.diagnostics().orEmpty()
            if (parsed.isNotBlank()) append("  \u00b7  $parsed")
        }
        engineNoteView?.visibility = View.VISIBLE
        speedButton?.text = "${playbackSpeed}x"
        aspectButton?.text = ASPECT_MODES.getOrElse(resizeModeIndex) { "Fit" }
    }

    /** Cycles the same speeds the panel lists (the main player opens a picker). */
    private fun cycleSpeed() {
        val index = SPEED_OPTIONS.indexOfFirst { it == playbackSpeed }
        val next = SPEED_OPTIONS[(index + 1).mod(SPEED_OPTIONS.size)]
        chooseSpeed(next)
        showToast("Speed: ${next}x")
    }

    /** Cycles the aspect modes and remembers the choice, like the main player. */
    private fun cycleAspect() {
        val next = (resizeModeIndex + 1) % ASPECT_MODES.size
        chooseAspect(next)
        showToast("Aspect: ${ASPECT_MODES[next]}")
    }

    /** Opens the settings side panel and puts focus inside it. */
    private fun showSettingsPanel() {
        val container = settingsContainer ?: return
        settingsOpen = true
        removeAutoHide()
        settingsSection?.refresh()
        container.visibility = View.VISIBLE
        focusFirstPill(container)
    }

    /** Closes the panel and hands focus back to the control bar. */
    private fun hideSettingsPanel() {
        settingsOpen = false
        settingsContainer?.visibility = View.GONE
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
                audioTrackSignature = audioTrackSignature
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
        surface?.setAudioDelayMs(0)
        surface?.setSubtitleDelayMs(0)
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
     * Raises the card as the credits roll rather than waiting for the file to
     * end, on the same timing the main player uses for a source that carries no
     * credits marker: [END_PANEL_LEAD_MS] before the declared duration.
     */
    private fun maybeTriggerEndPanels(position: Long, duration: Long) {
        if (endPanelsShown || endedHandled || duration <= 0L) return
        val triggerAt = (duration - END_PANEL_LEAD_MS).coerceAtLeast(0L)
        // A clip shorter than the lead would otherwise pop the card the moment
        // playback starts: onPlaybackEnded covers that case instead.
        if (triggerAt <= 0L) return
        if (position < triggerAt) return
        showEndPanels()
    }

    /**
     * The same decision the main player makes when an episode ends: a next
     * episode that has actually aired raises the Up Next card, and anything else
     * - a movie, a finished finale, an episode that is not out yet - gets the
     * because-you-watched credits recommendations instead. Raised once per
     * session; the air-date gate is the shared helper, so both engines chain to
     * the same place.
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
            if (target != null) {
                showNextUpPanel(target.first, target.second)
            } else {
                showBecauseYouWatchedPanel()
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
     * shrink the video (the credits themselves) into the bottom-right corner so
     * the picks get the screen, and move the panel into the space that leaves on
     * the left so it never covers them.
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
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(margin, margin, margin, margin)
        }
        view.requestLayout()

        creditsModePanelParams = panel.layoutParams as? FrameLayout.LayoutParams
        panel.layoutParams = FrameLayout.LayoutParams(
            (screenW - pipW - margin * 3).coerceAtLeast(screenW / 2),
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            setMargins(margin, margin, margin, margin)
        }
        panel.requestLayout()
    }

    /** Restores the video to full screen and the panel to its XML box. */
    private fun exitCreditsMode() {
        if (!creditsModeActive) return
        creditsModeActive = false
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

    // --- Playback callbacks ------------------------------------------------

    private fun onFileLoaded(mediaTitle: String?) {
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
        if (isFallbackSession) {
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
