package com.kennyb1201.kbstream.ui.player

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import coil3.load
import com.kennyb1201.kbstream.R
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import com.kennyb1201.kbstream.data.mdblist.MdbListClient
import com.kennyb1201.kbstream.data.player.ExternalPlayer
import com.kennyb1201.kbstream.data.simkl.SimklRepository
import com.kennyb1201.kbstream.data.sync.SupabaseSync
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.tv.TvLauncherPublisher
import com.kennyb1201.kbstream.ui.settings.AppPreferences
import com.kennyb1201.kbstream.ui.streams.StreamsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "External player" engine: the title plays in an installed video app, but
 * the session stays ours.
 *
 * WHY A WRAPPER AND NOT JUST AN INTENT
 *
 * Handing a URL to VLC or MX Player is one `startActivity`. Everything this app
 * does around playback is what makes that a feature rather than a regression:
 * the playhead has to reach Continue Watching, both trackers have to be
 * scrobbled, and the end of an episode has to raise the same Up Next card and
 * "Because you watched" row the other two engines raise. None of that can be
 * drawn over another app, so it happens here, on the way back.
 *
 * MEASURED, NOT OBSERVED
 *
 * We cannot see another process's playhead, so the position is estimated from
 * the wall clock (the time between handing the stream over and coming back),
 * seeded by the resume point the session started at. That estimate is refined
 * when the player reports one: MX Player and VLC both answer a
 * `startActivityForResult` with `position` / `duration` extras, which is what
 * the `return_result` extra asks for. The estimate is a floor either way - a
 * viewer who paused for ten minutes gets a position that is too far ahead -
 * which is the honest cost of playing outside the app.
 *
 * WHAT STILL WORKS
 *
 *  - Continue Watching / watch history, written with the SAME history id and
 *    canonical parent id the other engines use, so one title has one card.
 *  - Simkl + MDBList scrobbling (start on hand-off, stop on return).
 *  - The Up Next card and the because-you-watched row, raised on return.
 *  - The next-episode handoff, over the same [NextEpisodeResult] contract the
 *    other two engines use - so autoplay keeps working and keeps using
 *    whichever engine is configured.
 *
 * WHAT DOES NOT
 *
 *  - The in-player panel (sources, tracks, subtitle styling, DV layers) belongs
 *    to the engine that owns the video, and that engine is no longer ours.
 *  - Custom request headers travel as a best-effort extra that many players
 *    ignore, and DRM streams cannot be handed to another app at all. Both are
 *    reported rather than silently failed.
 */
class ExternalPlayerActivity : ComponentActivity() {

    // ── Session: the same fields every engine carries ────────────────────────

    private var currentUrl = ""
    private var parentId = ""
    private var parentType = ""
    private var season: Int? = null
    private var episode: Int? = null
    private var episodeStreamId: String? = null
    private var itemName = ""
    private var episodeTitle: String? = null
    private var itemPoster: String? = null
    private var backdropUrl: String? = null
    private var overview: String? = null
    private var totalEpisodesInSeason: Int? = null
    private var runtimeMinutes: Int? = null
    private var historyParentIdOverride: String? = null
    private var streamHeaders: Map<String, String> = emptyMap()
    private var drmLicenseUrl: String? = null
    private var startPositionMs = 0L
    private var historyId = ""

    private var canonicalParent: String? = null
    private var resolvedTmdbId: Int? = null

    /** True for a live channel: no runtime, no progress, no end to chain from. */
    private var isLiveChannel = false

    // ── Progress: measured, then refined by whatever the player reports ──────

    private var positionMs = 0L
    private var durationMs = 0L

    /** When the stream was handed over, or 0 before the hand-off. */
    private var handoffAtMs = 0L

    /**
     * True once the external app has actually taken the foreground from us,
     * which is what tells a RETURN apart from the resume that happens a moment
     * after the launch - see [onResume].
     */
    private var leftForHandoff = false

    /**
     * True while the refused-stream card is up. That card is an answer the
     * viewer is looking at, not a session to conclude, so a resume must leave
     * it alone.
     */
    private var refused = false

    /** True once the viewer has seen the end-of-episode panels for this session. */
    private var endPanelsShown = false

    /** True once this session has concluded (panels up, or on its way out). */
    private var concluded = false

    private var scrobbleStarted = false
    private var completionSent = false
    private var trackersMarkedWatched = false
    private var switchStarted = false

    // ── Views ───────────────────────────────────────────────────────────────

    private var handoffCard: LinearLayout? = null
    private var handoffPlayer: TextView? = null
    private var handoffTitle: TextView? = null
    private var handoffHint: TextView? = null

    private var errorCard: LinearLayout? = null
    private var errorText: TextView? = null
    private var errorHint: TextView? = null
    private var errorRetry: TextView? = null
    private var errorSwitch: TextView? = null

    private var scrim: View? = null

    private var nextUpPanel: LinearLayout? = null
    private var nextUpThumb: ImageView? = null
    private var nextUpShowTitle: TextView? = null
    private var nextUpEpisodeLabel: TextView? = null
    private var nextUpEpisodeTitle: TextView? = null
    private var nextUpCountdown: TextView? = null
    private var btnNextPlay: TextView? = null
    private var btnNextDismiss: TextView? = null

    private var bywPanel: LinearLayout? = null
    private var bywTitle: TextView? = null
    private var bywRow: LinearLayout? = null
    private var bywUi: BecauseYouWatchedUi? = null
    private var bywDismissed = false

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
                // Unattended advance: count it for the "Are you still there?"
                // binge watchdog, exactly like the other two engines.
                if (AppPreferences.getStillTherePrompt(this@ExternalPlayerActivity)) {
                    val count =
                        AppPreferences.getConsecutiveAutoplays(this@ExternalPlayerActivity) + 1
                    AppPreferences.setConsecutiveAutoplays(this@ExternalPlayerActivity, count)
                }
                advanceToPendingNext()
                return
            }
            nextUpCountdown?.text = "Playing next in $nextUpCountdownRemaining"
            nextUpCountdownHandler.postDelayed(this, 1_000L)
        }
    }

    /**
     * Result of the hand-off. Started FOR RESULT so the viewer coming back from
     * the external app lands here - the whole point of the wrapper - and so a
     * player that reports its playhead can have that answer taken seriously.
     */
    private val handoffLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        onHandoffReturned(result.resultCode, result.data)
    }

    /**
     * Result of a manual fall-out back to the in-app player (the refused-stream
     * card). Mirrors MpvPlayerActivity's switch launcher: whatever the in-app
     * engine decides is forwarded to whoever started us, so "next episode" and
     * the watch-history handoff behind it keep working across the switch.
     */
    private val inAppLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (!isFinishing && !isDestroyed) {
            setResult(result.resultCode, result.data)
            finish()
        }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_external_player)

        readIntent()
        bindViews()
        historyId = PlaybackHistoryIds.historyId(parentId, season, episode, episodeStreamId)
        setupBecauseYouWatched()
        setupEndOfEpisodeHandlers()

        // Live TV has no runtime and nothing to chain into, so it is played and
        // left at that: no history row, no scrobbble, no panels.
        isLiveChannel = parentType.equals("channel", ignoreCase = true)

        if (currentUrl.isBlank()) {
            showRefused(
                "Nothing to play",
                "This session arrived without a stream URL."
            )
            return
        }

        if (drmLicenseUrl != null) {
            // A DRM session's keys are ours; another app cannot be handed them.
            showRefused(
                "This source is DRM-protected",
                "Protected streams can only be played inside KBStream, where the licence " +
                    "is requested. Play it here instead."
            )
            return
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                // BACK is always "I am done with this title": it saves the
                // measured position, stops the scrobble and leaves. The two
                // end-of-episode panels have their own buttons, so there is no
                // card for a press here to dismiss.
                override fun handleOnBackPressed() = exitPlayer()
            }
        )

        startHandoff()
    }

    /**
     * Records that the hand-off really left: from here the external app is in
     * front of us, so the next resume is a return.
     */
    override fun onPause() {
        super.onPause()
        if (handoffAtMs > 0L && !concluded && !refused) leftForHandoff = true
    }

    /**
     * Safety net for the case the result callback never runs: the viewer left
     * our app entirely while the external player was up (Recents, HOME, our
     * process trimmed). Returning then still lands here, so the session is
     * concluded from the measured position instead of leaving the hand-off card
     * on screen.
     *
     * Only a return counts. The resume that follows the launch itself is not
     * one - the other app has not been up yet - and concluding there would end
     * the session before anything had played.
     */
    override fun onResume() {
        super.onResume()
        if (!leftForHandoff) return
        if (handoffAtMs == 0L || concluded || refused) return
        // A return that arrives with no result at all (back from the external
        // app after it was killed, say): conclude from the clock.
        concludeFromMeasurement(reportedPosition = null, reportedDuration = null)
    }

    override fun onStop() {
        super.onStop()
        if (concluded) return
        // Leaving for any other reason (the external app coming to the front,
        // mostly) must not throw the measured position away - it is the only
        // record of where the viewer is.
        rememberMeasuredPosition()
    }

    override fun onDestroy() {
        super.onDestroy()
        nextUpCountdownHandler.removeCallbacksAndMessages(null)
        if (!concluded && !isLiveChannel && positionMs > 0L) {
            saveProgress(forceCompleted = false)
        }
    }

    // ── Intent ──────────────────────────────────────────────────────────────

    /**
     * Reads the same extras the other two engines read, so a handoff between
     * engines carries the whole session rather than just a URL.
     */
    private fun readIntent() {
        val intent = intent ?: return
        currentUrl = intent.getStringExtra("stream_url").orEmpty()
        parentId = intent.getStringExtra("parent_id").orEmpty()
        parentType = intent.getStringExtra("parent_type").orEmpty()
        season = intent.getIntExtra("season", -1).takeIf { it >= 0 }
        episode = intent.getIntExtra("episode", -1).takeIf { it >= 0 }
        episodeStreamId = intent.getStringExtra("episode_stream_id")
        itemName = intent.getStringExtra("item_name")
            ?: intent.getStringExtra("display_name").orEmpty()
        episodeTitle = intent.getStringExtra("episode_title")
        itemPoster = intent.getStringExtra("item_poster")
        backdropUrl = intent.getStringExtra("backdrop_url")
        overview = intent.getStringExtra("item_overview")
        totalEpisodesInSeason =
            intent.getIntExtra("total_episodes_in_season", -1).takeIf { it > 0 }
        runtimeMinutes = intent.getIntExtra("runtime_minutes", -1).takeIf { it > 0 }
        historyParentIdOverride = intent.getStringExtra("history_parent_id")
        drmLicenseUrl = intent.getStringExtra("drm_license_url")
        streamHeaders = parseHeaderExtras(intent.getStringExtra("stream_headers").orEmpty())
        startPositionMs = if (intent.getBooleanExtra("from_beginning", false)) {
            0L
        } else {
            intent.getLongExtra("start_position_ms", 0L).coerceAtLeast(0L)
        }
        positionMs = startPositionMs
    }

    private fun bindViews() {
        handoffCard = findViewById(R.id.ext_handoff)
        handoffPlayer = findViewById(R.id.ext_handoff_player)
        handoffTitle = findViewById(R.id.ext_handoff_title)
        handoffHint = findViewById(R.id.ext_handoff_hint)

        errorCard = findViewById(R.id.ext_error)
        errorText = findViewById(R.id.ext_error_text)
        errorHint = findViewById(R.id.ext_error_hint)
        errorRetry = findViewById(R.id.ext_error_retry)
        errorSwitch = findViewById(R.id.ext_error_switch)

        scrim = findViewById(R.id.ext_scrim)

        nextUpPanel = findViewById(R.id.ext_next_up_panel)
        nextUpThumb = findViewById(R.id.ext_next_up_thumb)
        nextUpShowTitle = findViewById(R.id.ext_next_up_show_title)
        nextUpEpisodeLabel = findViewById(R.id.ext_next_up_episode_label)
        nextUpEpisodeTitle = findViewById(R.id.ext_next_up_episode_title)
        nextUpCountdown = findViewById(R.id.ext_next_up_countdown)
        btnNextPlay = findViewById(R.id.ext_btn_next_play)
        btnNextDismiss = findViewById(R.id.ext_btn_next_dismiss)

        bywPanel = findViewById(R.id.ext_byw_panel)
        bywTitle = findViewById(R.id.ext_byw_title)
        bywRow = findViewById(R.id.ext_byw_row)
    }

    // ── Hand-off ────────────────────────────────────────────────────────────

    /**
     * Brings the external app to the front with the stream.
     *
     * The target is the remembered app when it is still installed, else the
     * only installed one. With several installed and nothing chosen, the system
     * chooser picks - and because the position is measured from the clock, the
     * session still tracks correctly even though the chooser hands back an
     * opaque result.
     */
    private fun startHandoff() {
        if (!ExternalPlayer.isAvailable(this)) {
            showRefused(
                "No external player found",
                "Install a video player (VLC, MX Player, Kodi) and choose it in Settings, " +
                    "or play this title in KBStream's own player."
            )
            return
        }

        // The remembered app, unless the viewer asked to be prompted (Settings
        // > Playback engine > Ask every time): the intent then carries no
        // package at all and the system's own chooser makes the choice.
        val prompt = ExternalPlayer.shouldPrompt(this)
        val target = if (prompt) null else ExternalPlayer.target(this)
        val playerName = target?.label
            ?: ExternalPlayer.rememberedLabel(this)
            ?: "a video player"

        handoffPlayer?.text = playerName
        handoffTitle?.text = itemTitle()
        handoffHint?.text = if (prompt) {
            "Pick a player, then press BACK in it when you are done"
        } else {
            "Press BACK in $playerName when you are done"
        }
        handoffCard?.visibility = View.VISIBLE
        // A fresh hand-off: nothing has left yet, and no card is up.
        leftForHandoff = false
        refused = false

        val launch = ExternalPlayer.launchIntent(
            url = currentUrl,
            title = itemTitle(),
            positionMs = startPositionMs,
            packageName = target?.packageName,
            headers = streamHeaders
        ).apply {
            // CATEGORY_DEFAULT is what startActivity resolves an implicit intent
            // against, and the EXTERNAL engine writes it explicitly so a player
            // registered without it is still reachable.
            addCategory(Intent.CATEGORY_DEFAULT)
        }

        handoffAtMs = SystemClock.elapsedRealtime()
        val launched = ErrorLog.runCatching {
            handoffLauncher.launch(launch)
        }
        if (launched.isSuccess) {
            // The other app has the stream now, so both trackers are told the
            // title has started - the same thing the in-app engines report once
            // their file opens. What we cannot see is whether the app then
            // refused the source; the refused-stream card says so on screen,
            // and the "stop" on the way out is what closes the scrobble.
            scrobbleStart()
        } else {
            // No activity answered after all (the app was uninstalled between
            // the query and the launch). Report it instead of dying silently.
            showRefused(
                "Could not open $playerName",
                launched.exceptionOrNull()?.message ?: "The app refused to start."
            )
        }
    }

    /**
     * The viewer is back. Works out where they got to, records it, and either
     * raises the end-of-episode panels or leaves the session.
     */
    private fun onHandoffReturned(resultCode: Int, data: Intent?) {
        if (concluded) return
        val reportedPosition = ExternalPlayer.reportedPositionMs(data)
        val reportedDuration = ExternalPlayer.reportedDurationMs(data)

        // A player that bounced straight back without reporting anything has
        // almost certainly refused the stream - a bad MIME type, a URL it will
        // not follow, a codec it cannot decode. That is a different thing from
        // "the viewer watched for a while and left", and it deserves a card
        // rather than a silent return to the catalog.
        val elapsedMs = if (handoffAtMs > 0L) {
            SystemClock.elapsedRealtime() - handoffAtMs
        } else {
            0L
        }
        if (resultCode != RESULT_OK &&
            reportedPosition == null &&
            elapsedMs < BOUNCE_THRESHOLD_MS
        ) {
            val label = ExternalPlayer.target(this)?.label ?: "The external player"
            showRefused(
                "$label closed straight away",
                "It may not support this source. Try again, or play it in KBStream's own " +
                    "player - the other engine can use the request headers this source needs."
            )
            return
        }

        concludeFromMeasurement(reportedPosition, reportedDuration)
    }

    /**
     * Turns the measured position into a result: completed sessions raise the
     * panels, anything else is saved and exits.
     */
    private fun concludeFromMeasurement(reportedPosition: Long?, reportedDuration: Long?) {
        if (concluded) return

        if (reportedDuration != null && reportedDuration > 0L) durationMs = reportedDuration
        if (durationMs <= 0L) durationMs = (runtimeMinutes ?: 0) * 60_000L

        positionMs = when {
            reportedPosition != null -> reportedPosition
            else -> startPositionMs + measuredElapsedMs()
        }.coerceAtLeast(0L)

        if (isLiveChannel) {
            concluded = true
            finish()
            return
        }

        val finished = durationMs > 0L &&
            positionMs >= (durationMs * COMPLETION_THRESHOLD_RATIO).toLong()

        concluded = true
        if (finished) {
            saveProgress(forceCompleted = true)
            scrobble("stop", progressOverride = 100.0)
            showEndPanels()
        } else {
            // A mid-title return: record the position and hand the viewer back
            // to wherever they came from. The resume point is what Continue
            // Watching and the Detail screen read next time.
            saveProgress(forceCompleted = false)
            scrobble("stop")
            finish()
        }
    }

    /** Milliseconds the external app was in front of us, minus the launch gap. */
    private fun measuredElapsedMs(): Long {
        if (handoffAtMs <= 0L) return 0L
        return (SystemClock.elapsedRealtime() - handoffAtMs).coerceAtLeast(0L)
    }

    private fun rememberMeasuredPosition() {
        if (durationMs <= 0L) durationMs = (runtimeMinutes ?: 0) * 60_000L
        positionMs = (startPositionMs + measuredElapsedMs()).coerceAtLeast(0L)
    }

    // ── Refused-stream card ─────────────────────────────────────────────────

    /**
     * The card shown when the hand-off cannot happen or the player refused it.
     * Both buttons are real answers: try the external app again, or fall back
     * to the in-app engine for this session.
     */
    private fun showRefused(message: String, hint: String) {
        refused = true
        handoffCard?.visibility = View.GONE
        errorCard?.visibility = View.VISIBLE
        errorText?.text = message
        errorHint?.text = hint
        errorRetry?.setOnClickListener { retryHandoff() }
        errorSwitch?.setOnClickListener { playInApp() }
        errorSwitch?.requestFocus()
    }

    private fun retryHandoff() {
        errorCard?.visibility = View.GONE
        concluded = false
        scrobbleStarted = false
        handoffAtMs = 0L
        startHandoff()
    }

    /**
     * Leaves the external engine for this session only - the mirror of the
     * in-app players' SWITCH button, and deliberately NOT written to Settings:
     * which engine a title needs is a fact about the source, not a preference.
     */
    private fun playInApp() {
        if (switchStarted) return
        switchStarted = true
        val launch = Intent(this, NativePlayerActivity::class.java).apply {
            putExtra("stream_url", currentUrl)
            putExtra("parent_id", parentId)
            putExtra("parent_type", parentType)
            putExtra("season", season ?: -1)
            putExtra("episode", episode ?: -1)
            episodeStreamId?.let { putExtra("episode_stream_id", it) }
            episodeTitle?.let { putExtra("episode_title", it) }
            putExtra("item_name", itemName)
            putExtra("display_name", itemName)
            itemPoster?.let { putExtra("item_poster", it) }
            backdropUrl?.let { putExtra("backdrop_url", it) }
            overview?.let { putExtra("item_overview", it) }
            totalEpisodesInSeason?.let { putExtra("total_episodes_in_season", it) }
            runtimeMinutes?.let { putExtra("runtime_minutes", it) }
            putExtra("start_position_ms", positionMs)
            putExtra("from_beginning", positionMs < MIN_RESUME_POSITION_MS)
            historyParentIdOverride?.let { putExtra("history_parent_id", it) }
            if (streamHeaders.isNotEmpty()) {
                putExtra(
                    "stream_headers",
                    streamHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                )
            }
        }
        inAppLauncher.launch(launch)
    }

    private fun exitPlayer() {
        nextUpCountdownHandler.removeCallbacks(nextUpCountdownRunnable)
        if (!concluded) {
            concluded = true
            saveProgress(forceCompleted = false)
            scrobble("stop")
        }
        finish()
    }

    // ── End of episode ──────────────────────────────────────────────────────

    /**
     * Raises the panel this session is entitled to, decided exactly as the
     * other two engines decide it: an aired next episode raises the Up Next
     * card, and nothing to chain into raises the recommendations row.
     */
    private fun showEndPanels() {
        if (endPanelsShown) return
        endPanelsShown = true
        lifecycleScope.launch {
            val target = airedNextEpisodeTarget(
                context = this@ExternalPlayerActivity,
                target = nextEpisodeTarget(),
                tmdbId = runCatching { tmdbId() }.getOrNull(),
                showId = parentId
            )
            when {
                target != null && AppPreferences.getNextEpisodePopup(this@ExternalPlayerActivity) ->
                    showNextUpPanel(target.first, target.second)

                target == null && AppPreferences.getBecauseYouWatched(this@ExternalPlayerActivity) ->
                    showBecauseYouWatchedPanel()

                else -> finish()
            }
        }
    }

    /** The next episode to chain into, or null for a film / a finished series. */
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

    private fun showNextUpPanel(targetSeason: Int, targetEpisode: Int) {
        val panel = nextUpPanel ?: return
        pendingNextSeason = targetSeason
        pendingNextEpisode = targetEpisode
        pendingNextEpisodeName = null

        nextUpShowTitle?.text = itemName
        nextUpEpisodeLabel?.text = "Season $targetSeason \u2022 Episode $targetEpisode"
        nextUpEpisodeTitle?.text = "S${targetSeason}E$targetEpisode"
        nextUpCountdown?.text = ""

        val initialThumb = backdropUrl ?: itemPoster
        if (!initialThumb.isNullOrBlank()) {
            runCatching { nextUpThumb?.load(initialThumb) }
        } else {
            nextUpThumb?.setImageDrawable(null)
        }

        scrim?.visibility = View.VISIBLE
        panel.visibility = View.VISIBLE
        btnNextPlay?.requestFocus()

        if (AppPreferences.getAutoPlayNext(this)) {
            val threshold = AppPreferences.getStillThereEpisodes(this).toInt()
            val autoAdvanced = AppPreferences.getConsecutiveAutoplays(this)
            if (AppPreferences.getStillTherePrompt(this) && autoAdvanced >= threshold) {
                nextUpCountdownHeld = false
                nextUpCountdownRemaining = 0
                nextUpCountdown?.text = "Are you still there? Press PLAY NEXT to continue"
                nextUpCountdown?.setTextColor(getColor(R.color.kb_accent))
                nextUpCountdownHandler.removeCallbacks(nextUpCountdownRunnable)
                return
            }
            // The title is already over by the time this engine can raise the
            // card - there is no credits period to hold the countdown for.
            armNextUpAutoAdvance(remainingMs = 0L)
        } else {
            nextUpCountdownHeld = false
            nextUpCountdownRemaining = 0
            nextUpCountdown?.text = "PLAY NEXT to continue, or press BACK to exit"
        }

        fetchNextEpisodeDetails(targetSeason, targetEpisode)
    }

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

    /** Best effort: the next episode's real name and still, from TMDB. */
    private fun fetchNextEpisodeDetails(targetSeason: Int, targetEpisode: Int) {
        lifecycleScope.launch {
            val tmdb = withContext(Dispatchers.IO) {
                runCatching { tmdbId() }.getOrNull()
            } ?: return@launch
            val nextEp = withContext(Dispatchers.IO) {
                runCatching {
                    TmdbRepository.getInstance(this@ExternalPlayerActivity)
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

    private fun setupEndOfEpisodeHandlers() {
        btnNextPlay?.setOnClickListener { advanceToPendingNext() }
        btnNextDismiss?.setOnClickListener { exitPlayer() }
    }

    private fun advanceToPendingNext() {
        val showSeason = pendingNextSeason ?: return
        val showEpisode = pendingNextEpisode ?: return
        launchNextEpisode(showSeason, showEpisode)
    }

    /**
     * Hands the episode to MainActivity, over the same two channels the other
     * engines use: the persisted [NextEpisodeResult] (which survives our
     * process being killed) and the classic result extras for the live
     * callback. MainActivity then resolves the source and relaunches the
     * configured engine - which is this one again, so a binge stays external.
     */
    private fun launchNextEpisode(targetSeason: Int, targetEpisode: Int) {
        nextUpCountdownHandler.removeCallbacks(nextUpCountdownRunnable)
        val label = buildString {
            append("S${targetSeason}E$targetEpisode")
            if (!pendingNextEpisodeName.isNullOrBlank()) append(" \u2022 $pendingNextEpisodeName")
        }
        val pending = NextEpisodeResult.PendingNext(
            season = targetSeason,
            episode = targetEpisode,
            title = label,
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

    /** The episode id's own prefix, the same rule every engine uses. */
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

    // ── Because you watched ─────────────────────────────────────────────────

    /**
     * The credits row, from the same shared panel UI and the same pick engine
     * the other two players use, so a recommendation looks and behaves the
     * same whichever engine raised it.
     */
    private fun setupBecauseYouWatched() {
        val panel = bywPanel ?: return
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

    private fun showBecauseYouWatchedPanel() {
        val ui = bywUi
        if (ui == null || bywDismissed) {
            finish()
            return
        }
        ui.show(itemName)
        scrim?.visibility = View.VISIBLE
        ui.focusFirst()

        lifecycleScope.launch {
            val picks: List<BywPick> = withContext(Dispatchers.IO) {
                val tmdb = runCatching { tmdbId() }.getOrNull()
                    ?: return@withContext emptyList()
                buildBecauseYouWatchedPicks(
                    this@ExternalPlayerActivity,
                    tmdb,
                    bywMediaType(parentType)
                )
            }

            if (picks.isEmpty() || !ui.isVisible) {
                withContext(Dispatchers.Main) {
                    ui.hide()
                    finish()
                }
                return@launch
            }

            withContext(Dispatchers.Main) { ui.build(picks) }
        }
    }

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
     * The result contract MainActivity already understands from the other two
     * players: "play_now" reopens the player on the resolved stream,
     * "go_details" opens the detail screen.
     */
    private fun finishWithBywResult(
        action: String,
        pick: BywPick,
        imdbId: String,
        streamUrl: String?,
        streamName: String?
    ) {
        nextUpCountdownHandler.removeCallbacks(nextUpCountdownRunnable)
        if (!concluded) concluded = true
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

    /** The pick pills' look, matching both in-app engines'. */
    private fun applyPillBackground(view: TextView, selected: Boolean, focused: Boolean) {
        view.background = when {
            selected && focused -> getDrawable(R.drawable.pill_chip_selected_focused_bg)
            selected -> getDrawable(R.drawable.pill_chip_selected_bg)
            focused -> getDrawable(R.drawable.pill_chip_focused_bg)
            else -> roundedPanelDrawable(this, playerPanelSurfaceColor(this), 6f)
        }
        view.setTextColor(getColor(if (selected) R.color.kb_void else R.color.kb_text_hi))
    }

    // ── Watch history ───────────────────────────────────────────────────────

    /**
     * Writes the same row the other engines write - same id, same fields, same
     * canonical parent id - so a title played externally updates the ONE
     * Continue Watching card it already has rather than starting a second.
     *
     * The position is the measured estimate; the duration is what the external
     * player reported, else the TMDB runtime the session carried. With neither,
     * the row is not written at all: a duration is what makes a position mean
     * "44% through", and a row without one would show as a broken card.
     */
    private fun saveProgress(forceCompleted: Boolean) {
        if (isLiveChannel || parentId.isBlank() || historyId.isBlank()) return
        if (durationMs <= 0L) {
            Log.i(TAG, "no duration for this session; skipping the history row")
            return
        }
        val rawPosition = if (forceCompleted) durationMs else positionMs
        if (!forceCompleted && rawPosition < MIN_RESUME_POSITION_MS) return

        val completed = forceCompleted ||
            rawPosition >= (durationMs * COMPLETION_THRESHOLD_RATIO).toLong()
        val safePosition = if (completed) 0L else rawPosition.coerceAtMost(durationMs)
        val now = System.currentTimeMillis()
        if (completed) completionSent = true

        Log.i(
            TAG,
            "save progress: ${safePosition}ms / ${durationMs}ms completed=$completed"
        )

        lifecycleScope.launch(Dispatchers.IO + NonCancellable) {
            runCatching {
                val dao = WatchHistoryDatabase.getInstanceScoped(this@ExternalPlayerActivity)
                    .watchHistoryDao()
                val existing = dao.getById(historyId)
                val entry = WatchHistoryEntity(
                    id = historyId,
                    parentId = canonicalParentId(),
                    type = parentType,
                    name = itemName,
                    episodeTitle = episodeTitle,
                    overview = overview,
                    backdropUrl = backdropUrl,
                    totalEpisodesInSeason = totalEpisodesInSeason,
                    poster = itemPoster,
                    streamUrl = currentUrl,
                    season = season,
                    episode = episode,
                    episodeStreamId = episodeStreamId,
                    positionMs = safePosition,
                    durationMs = durationMs,
                    updatedAt = now,
                    isCompleted = completed,
                    completedAt = if (completed) existing?.completedAt ?: now else null
                )
                dao.upsert(entry)
                SupabaseSync.enqueueHistory(entry)
                TvLauncherPublisher.sync(this@ExternalPlayerActivity, dao.getAll())
            }.onFailure { Log.w(TAG, "could not write watch history", it) }
            if (completed) pushCompletion()
        }
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

    // ── Scrobbling ──────────────────────────────────────────────────────────

    /**
     * Reports the start of the session, once per hand-off.
     *
     * The in-app engines scrobble "start" when their file actually opens; this
     * engine cannot see that, so the hand-off is the closest honest equivalent:
     * the other app has been given the stream and the title is playing
     * somewhere. Without it a title watched externally would only ever appear on
     * Simkl and MDBList as "stopped", which is the difference between "I am
     * watching this" and "I watched this" on the other device.
     */
    private fun scrobbleStart() {
        if (scrobbleStarted || isLiveChannel || parentId.isBlank()) return
        scrobbleStarted = true
        scrobble("start")
    }

    /**
     * Scrobbles the session from the measured position. Independent scope, so a
     * final "stop" outlives this activity - the same rule both in-app engines
     * follow.
     */
    private fun scrobble(action: String, progressOverride: Double? = null) {
        if (isLiveChannel || parentId.isBlank()) return
        val progress = progressOverride ?: if (durationMs > 0L) {
            ((positionMs.toDouble() / durationMs.toDouble()) * 100.0).coerceIn(0.0, 100.0)
        } else {
            0.0
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                SimklRepository.getInstance(this@ExternalPlayerActivity).scrobble(
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
            "start" -> MdbListClient.scrobbleStart(
                this, isMovie, imdbId, tmdb, season, episode, progress
            )

            "pause" -> MdbListClient.scrobblePause(
                this, isMovie, imdbId, tmdb, season, episode, progress
            )

            "stop" -> MdbListClient.scrobbleStop(
                this, isMovie, imdbId, tmdb, season, episode, progress
            )
        }
    }

    /** Marks the title watched on both trackers, once per session. */
    private fun pushCompletion() {
        if (trackersMarkedWatched || parentId.isBlank()) return
        trackersMarkedWatched = true
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                val simkl = SimklRepository.getInstance(this@ExternalPlayerActivity)
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
                if (MdbListClient.apiKey(this@ExternalPlayerActivity).isNotBlank()) {
                    val isMovie = parentType.lowercase() == "movie"
                    MdbListClient.pushWatched(
                        this@ExternalPlayerActivity,
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

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** One "Show — S1 E2 · Episode" line, the shape both other engines use. */
    private fun itemTitle(): String = when {
        itemName.isBlank() -> "Playback"
        season != null && episode != null && !episodeTitle.isNullOrBlank() ->
            "$itemName \u2014 S$season\u2009E$episode \u00b7 $episodeTitle"

        season != null && episode != null -> "$itemName \u2014 S$season\u2009E$episode"
        else -> itemName
    }

    private fun showToast(message: String) {
        runCatching { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    /**
     * The request headers the addon asked for, as the two engines' shared
     * "Key: Value" lines.
     */
    private fun parseHeaderExtras(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) return@mapNotNull null
                val name = line.substring(0, separator).trim()
                val value = line.substring(separator + 1).trim()
                if (name.isEmpty() || value.isEmpty()) null else name to value
            }
            .toMap()
    }

    /** Wraps a failed hand-off launch so the error card can report it. */
    private object ErrorLog {
        fun runCatching(block: () -> Unit): Result<Unit> =
            kotlin.runCatching { block() }
    }

    companion object {
        private const val TAG = "PLAYER_EXTERNAL"

        /** Fraction of the runtime that counts as "finished". */
        private const val COMPLETION_THRESHOLD_RATIO = 0.95f

        /** Below this a "resume" is really a restart. */
        private const val MIN_RESUME_POSITION_MS = 10_000L

        /** Next Up's unattended-advance countdown, the same as both engines'. */
        private const val NEXT_UP_COUNTDOWN_SECONDS = 10
        private const val NEXT_UP_HOLD_THRESHOLD_MS = 60_000L

        /**
         * A return this soon, with no reported playhead, means the external app
         * never really played anything - which is what the refused-stream card
         * is for.
         */
        private const val BOUNCE_THRESHOLD_MS = 4_000L
    }
}
