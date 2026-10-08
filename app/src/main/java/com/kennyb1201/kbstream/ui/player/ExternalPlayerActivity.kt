package com.kennyb1201.kbstream.ui.player

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import coil3.load
import com.kennyb1201.kbstream.R
import com.kennyb1201.kbstream.ui.home.looksLikeRawMediaId
import com.kennyb1201.kbstream.ui.theme.themeAccentColor
import com.kennyb1201.kbstream.data.history.PlaybackHistoryWriter
import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import com.kennyb1201.kbstream.data.namedEpisodeNumber
import com.kennyb1201.kbstream.data.mdblist.MdbListClient
import com.kennyb1201.kbstream.data.player.EpisodeScheme
import com.kennyb1201.kbstream.data.player.EpisodeSchemeStore
import com.kennyb1201.kbstream.data.player.ExternalPlayer
import com.kennyb1201.kbstream.data.player.PlayedLinkCache
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.simkl.SimklRepository
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.tmdb.displayRuntimeMinutes
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.data.spoiler.SpoilerFree
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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

    /**
     * The profile this playback session belongs to, pinned at launch and
     * carried in the launch intent (see [PlaybackHistoryWriter]). Read from
     * the launch intent rather than the active profile so that switching
     * profiles during playback cannot make this session file its progress
     * into the other profile's Continue Watching.
     */
    private var sessionProfileId: String? = null
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

    /*
     * One file is not one TMDB episode (see EpisodeScheme).
     *
     * This engine has a persisted scheme and nothing more: the stream plays in
     * another app, so there is no duration to DETECT from - the runtime this
     * session knows is the episode's own TMDB runtime, not the file's, and
     * comparing one against itself would answer 1:1 every time. The scheme the
     * in-app engines detected for this show still applies, which is what keeps a
     * binge that reaches this engine stepping the file cursor correctly; a show
     * only ever played here keeps today's one-file-one-episode behavior.
     */
    private var bingeScheme: EpisodeScheme = EpisodeScheme.ONE_TO_ONE
    private var schemeStoreKey: String? = null
    private var historyParentIdOverride: String? = null
    private var streamHeaders: Map<String, String> = emptyMap()
    private var drmLicenseUrl: String? = null
    private var startPositionMs = 0L

    /**
     * The launch asked for the beginning (Home's long-press "Play from
     * Beginning"). The flag travels with the position it zeroes, because it is
     * the one thing that must also stop the watch-history resume (see
     * PlaybackResume).
     */
    private var startFromBeginning = false
    private var historyId = ""

    /**
     * The played-link cache key this session's URL came from, or null when the
     * source was resolved fresh. Only a cached link is worth forgetting.
     */
    private var playedLinkKey: String? = null

    /**
     * Set once this session has retired its cached link, so a retry loop
     * forgets at most once. See [invalidateCachedLink].
     */
    private var linkCacheInvalidated = false

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

        // The window behind this hand-off card: the theme's static
        // colorBackground cannot follow the AMOLED toggle, so an AMOLED install
        // showed the ordinary #0A0E14 void behind it.
        applyPlayerWindowTone(this)

        // Kids Mode: end the hand-off the moment a daily limit or bedtime
        // lands rather than leaving this picker up behind it. Once the chosen
        // app is on top this Activity stops too, so - like watching in any
        // other app - the time is not counted while it plays.
        com.kennyb1201.kbstream.data.sync.KidsTimeGuard.enforceLock(this)

        setContentView(R.layout.activity_external_player)

        readIntent()
        bindViews()
        // This engine's panels, buttons and pills are the same XML chrome the
        // other two engines wear, and it resolved @color/kb_accent,
        // @color/kb_surface and @color/kb_surface_raised at inflation - so a
        // custom global accent and the AMOLED / pure-black toggles both have to
        // be re-applied over the whole tree.
        retintPlayerChrome(findViewById(android.R.id.content), this)
        historyId = PlaybackHistoryIds.historyId(parentId, season, episode, episodeStreamId)
        // The scheme a previous session detected for this show, if any. The imdb
        // parent id answers it here; a tmdb-only parent is re-keyed from tmdbId()
        // on the first suspend path that needs the scheme (see loadBingeScheme).
        schemeStoreKey = EpisodeSchemeStore.stableShowId(parentId, null)
        bingeScheme = EpisodeSchemeStore.get(this, schemeStoreKey)
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
                "Protected streams can only be played inside KBStream, where the license " +
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
        lifecycleScope.launch {
            concludeFromMeasurement(reportedPosition = null, reportedDuration = null)
        }
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
        backdropUrl = intent.getStringExtra("backdrop_url")
        overview = intent.getStringExtra("item_overview")
        totalEpisodesInSeason =
            intent.getIntExtra("total_episodes_in_season", -1).takeIf { it > 0 }
        runtimeMinutes = intent.getIntExtra("runtime_minutes", -1).takeIf { it > 0 }
        historyParentIdOverride = intent.getStringExtra("history_parent_id")
        drmLicenseUrl = intent.getStringExtra("drm_license_url")
        // Only non-null when MainActivity reused a cached debrid link, which is
        // exactly the case a failed hand-off has to retire.
        playedLinkKey = intent.getStringExtra("played_link_key")
        streamHeaders = parseHeaderExtras(intent.getStringExtra("stream_headers").orEmpty())
        // Kept beside the position it zeroes: the flag is also what stops the
        // watch-history resume (see PlaybackResume), because a title asked for
        // from the beginning has no saved position to fall back to.
        startFromBeginning = intent.getBooleanExtra("from_beginning", false)
        startPositionMs = if (startFromBeginning) {
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
     *
     * This is also where an intro is skipped, because it is the only place one
     * can be: once the stream is handed over, another app owns the playhead and
     * we cannot raise the SKIP button the in-app engines draw. With auto-skip on
     * (Settings > Interface) the resume point is moved past a segment the
     * session is about to run into.
     */
    private fun startHandoff() {
        if (!ExternalPlayer.isAvailable(this)) {
            showRefused(
                "No external player found",
                "Install any video player app (VLC, MX Player, Kodi, Just Player, …) " +
                    "and pick it in Settings, or play this title in KBStream's own player."
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

        val skipSettings = AutoSkipRules.Settings(
            skipIntros = AppPreferences.getAutoSkipIntro(this),
            skipCredits = AppPreferences.getAutoSkipCredits(this)
        )

        lifecycleScope.launch {
            // Nothing in this launch asked to resume, so ask the watch history
            // before the hand-off: this is the LAST place a resume can be
            // decided, because the position is handed to another app that
            // never asks us again (see PlaybackResume). A source picked from
            // the picker arrives with no position of its own, so without this
            // a manual pick on a title Continue Watching has progress on was
            // handed over from the beginning. A failed or empty read leaves
            // the launch as it was.
            if (
                PlaybackResume.mayResumeFromHistory(
                    startPositionMs = startPositionMs,
                    startFromBeginning = startFromBeginning,
                    historyId = historyId
                )
            ) {
                PlaybackResume.savedPositionMs(
                    context = this@ExternalPlayerActivity,
                    historyId = historyId,
                    parentId = parentId,
                    parentType = parentType,
                    season = season,
                    episode = episode,
                    episodeStreamId = episodeStreamId
                )?.let { saved ->
                    startPositionMs = saved
                    positionMs = saved
                }
            }
            if (isFinishing || isDestroyed) return@launch

            // With both prefs off there is nothing a segment lookup could be used
            // for, so the stream is handed over at once and a box that never turned
            // auto-skip on never waits for it.
            if (isLiveChannel || parentId.isBlank() ||
                (!skipSettings.skipIntros && !skipSettings.skipCredits)
            ) {
                handOff(prompt, target, playerName, startPositionMs, skipped = null)
                return@launch
            }

            showHandoffCard(prompt, playerName, skipped = null)
            // Bounded, and bounded generously enough for the second source: a
            // lookup that does not answer in time simply means the segment is
            // not skipped rather than the hand-off never happening.
            val stamps = withTimeoutOrNull(SEGMENT_LOOKUP_TIMEOUT_MS) {
                fetchIntroDbStamps(parentId, season, episode)
            }.orEmpty()
            if (isFinishing || isDestroyed) return@launch

            val skipped = AutoSkipRules.handoffSkipTarget(stamps, startPositionMs, skipSettings)
            val seekTo = skipped?.let { AutoSkipRules.targetMs(it, stamps, durationMs) }
            if (skipped != null && seekTo != null) {
                // The measurement has to start from the skip, or the intro would
                // be reported as watched and the resume point written back
                // inside the segment that was just skipped.
                startPositionMs = seekTo
                positionMs = seekTo
            }
            handOff(prompt, target, playerName, startPositionMs, skipped?.type)
        }
    }

    /**
     * The hand-off card: which app is about to play what, and what BACK means.
     * [skipped] is the segment this hand-off is already starting past, if any.
     */
    private fun showHandoffCard(
        prompt: Boolean,
        playerName: String,
        skipped: IntroDbMarkerType?
    ) {
        handoffPlayer?.text = playerName
        handoffTitle?.text = itemTitle()
        val base = if (prompt) {
            "Pick a player, then press BACK in it when you are done"
        } else {
            "Press BACK in $playerName when you are done"
        }
        handoffHint?.text = skipped?.let { "Skipped ${it.skippedLabel()} \u2022 $base" } ?: base
        handoffCard?.visibility = View.VISIBLE
    }

    /** Hands [seekMs] of the stream to [target] and starts the session. */
    private fun handOff(
        prompt: Boolean,
        target: ExternalPlayer.Installed?,
        playerName: String,
        seekMs: Long,
        skipped: IntroDbMarkerType?
    ) {
        showHandoffCard(prompt, playerName, skipped)
        // A fresh hand-off: nothing has left yet, and no card is up.
        leftForHandoff = false
        refused = false

        val launch = ExternalPlayer.launchIntent(
            url = currentUrl,
            title = itemTitle(),
            positionMs = seekMs,
            target = target,
            headers = streamHeaders
        ).apply {
            // CATEGORY_DEFAULT is what startActivity resolves an implicit intent
            // against, and the EXTERNAL engine writes it explicitly so a player
            // registered without it is still reachable - but only for a plain
            // VIEW hand-off. A scheme-only player (VidHub) was discovered by a
            // category-free query and is launched the same way; adding a
            // category here could turn a resolvable request into an
            // ActivityNotFoundException.
            if (target?.scheme == null) addCategory(Intent.CATEGORY_DEFAULT)
        }

        // The hand-off used to be silent, which left the refused-stream card as
        // the only evidence that anything went wrong - and no way to tell a
        // player that refused a header-gated URL from one that could not take
        // the mime at all. Header VALUES are never logged (they carry the
        // addon's tokens); the key names are, because "was a Referer attached"
        // is the fact that decides whether an external player could ever have
        // loaded this source.
        Log.i(
            TAG,
            "handoff -> ${target?.label ?: "chooser"} " +
                "pkg=${target?.packageName ?: "-"} scheme=${target?.scheme ?: "-"} " +
                "mime=${launch.type ?: "-"} " +
                "headers=[${streamHeaders.keys.joinToString(",")}] seek=${seekMs}ms"
        )

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

    /** "the intro", "the credits" - the segment as the card names it. */
    private fun IntroDbMarkerType.skippedLabel(): String = when (this) {
        IntroDbMarkerType.Intro -> "the intro"
        IntroDbMarkerType.Recap -> "the recap"
        IntroDbMarkerType.Outro -> "the outro"
        IntroDbMarkerType.Credits -> "the credits"
        else -> "the segment"
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
        // The Activity Result callback is delivered once we are STARTED again,
        // so elapsedMs is how long the other app actually stayed up in front of
        // the viewer - not a measurement taken while it was still launching.
        Log.i(
            TAG,
            "handoff returned resultCode=$resultCode elapsed=${elapsedMs}ms " +
                "position=${reportedPosition ?: "-"} duration=${reportedDuration ?: "-"} " +
                "headers=[${streamHeaders.keys.joinToString(",")}]"
        )
        if (resultCode != RESULT_OK &&
            reportedPosition == null &&
            elapsedMs < BOUNCE_THRESHOLD_MS
        ) {
            // The app took the stream and closed straight back: the link itself
            // is the suspect, so retire the cached entry (PB-P2-3). This is the
            // external engine's version of the in-app "failed before the first
            // frame" rule - there is no frame to watch for here, so an
            // immediate bounce with no playhead reported is the equivalent
            // evidence. A viewer who watched and then left keeps the entry.
            invalidateCachedLink()
            val label = ExternalPlayer.target(this)?.label ?: "The external player"
            // Name the most likely cause instead of leaving the viewer to guess.
            // A source that carries request headers is the case the External
            // engine cannot fix: the extras we send are the MX Player / VLC
            // convention, and a player that ignores them makes its own bare
            // request, gets a 403 and bounces. That is worth saying outright.
            val hint = if (streamHeaders.isNotEmpty()) {
                "It may not support this source. This one needs request headers " +
                    "(${streamHeaders.keys.joinToString(", ")}), which external players " +
                    "often ignore - so it may have been refused for that alone. Play it in " +
                    "KBStream's own player, which sends them."
            } else {
                "It may not support this source. Try again, or play it in KBStream's own " +
                    "player - the other engine can use the request headers this source needs."
            }
            showRefused("$label closed straight away", hint)
            return
        }

        lifecycleScope.launch {
            concludeFromMeasurement(reportedPosition, reportedDuration)
        }
    }

    /**
     * Turns the measured position into a result: completed sessions raise the
     * panels, anything else is saved and exits.
     *
     * Suspends once, on [ensureDurationMs]: a session neither the launch extras
     * nor the external player gave a runtime still gets one from TMDB, because
     * "finished" - which decides the end panels AND the history row - is a
     * comparison against it.
     */
    private suspend fun concludeFromMeasurement(
        reportedPosition: Long?,
        reportedDuration: Long?
    ) {
        if (concluded) return
        // Claimed BEFORE the first suspension point: the Activity Result
        // callback and the onResume safety net can both land here, and the
        // duration lookup must not let the second one raise the panels too.
        concluded = true

        if (reportedDuration != null && reportedDuration > 0L) durationMs = reportedDuration
        if (ensureDurationMs() <= 0L) {
            Log.i(TAG, "no duration for this session; nothing to conclude against")
        }

        positionMs = when {
            reportedPosition != null -> reportedPosition
            else -> startPositionMs + measuredElapsedMs()
        }.coerceAtLeast(0L)

        if (isLiveChannel) {
            finish()
            return
        }

        val measuredFinished = durationMs > 0L &&
            positionMs >= (durationMs * COMPLETION_THRESHOLD_RATIO).toLong()
        // A playhead the player reported itself is evidence, and it wins. When
        // it reported nothing the position above is only the wall clock, so a
        // viewer who paused or scrubbed for the credits comes back short of the
        // threshold on a title they did finish - nothing was marked watched and
        // neither end card came up. With the setting on, the return itself is
        // the answer. (A reported 0 is the same no-answer as no report at all:
        // players that do not track a playhead send a default.)
        val reportedEvidence =
            reportedPosition != null && reportedPosition > MIN_RESUME_POSITION_MS
        val trustReturn =
            !reportedEvidence && AppPreferences.getExternalTrustReturn(this)
        val finished = measuredFinished || trustReturn

        Log.i(
            TAG,
            "conclude: pos=${positionMs}ms dur=${durationMs}ms finished=$finished " +
                "(reported=$reportedPosition measured=$measuredFinished trust=$trustReturn)"
        )

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
        // A hand-off that already told the trackers the title was playing - the
        // app launched and closed straight away, or the stream arrived
        // unplayable - must not leave an open "now watching" session behind.
        // A refusal that never reached a hand-off has no session to close.
        if (scrobbleStarted) scrobble("stop")
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
            // The in-app engine takes over THIS session, so it inherits the
            // profile the session started on rather than re-reading whichever
            // profile is active by the time the hand-off happens.
            sessionProfileId?.let {
                putExtra(PlaybackHistoryWriter.EXTRA_SESSION_PROFILE_ID, it)
            }
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
            loadBingeScheme()
            val target = airedNextEpisodeTarget(
                context = this@ExternalPlayerActivity,
                target = nextEpisodeTarget(),
                tmdbId = runCatchingCancellable { tmdbId() }.getOrNull(),
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

    /**
     * The scheme for this show, resolved through tmdbId() when the parent id
     * alone could not key it. Called from the coroutine that raises the end
     * panels, so the store is read at most once.
     */
    private suspend fun loadBingeScheme() {
        if (schemeStoreKey == null) {
            schemeStoreKey = EpisodeSchemeStore.stableShowId(parentId, tmdbId())
            bingeScheme = EpisodeSchemeStore.get(this, schemeStoreKey)
        }
    }

    /**
     * The FILE episode the session is on now: the trailing number of
     * [episodeStreamId], which the id invariant keeps in file numbering. Mirrors
     * the in-app engines' own reader.
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
     * The TMDB episodes the file that played covered: one for every scheme but
     * SEGMENTS_PER_FILE, which holds its own factor. Anchored on the FILE cursor,
     * not the session's TMDB label, for the same reason as the in-app engines -
     * a session entered at a non-first segment would otherwise mark the wrong
     * episodes (see NativePlayerActivity.coveredTmdbEpisodes).
     */
    private fun coveredTmdbEpisodes(tmdbEpisode: Int): List<Int> {
        val fileEpisode = currentFileEpisode() ?: tmdbEpisode
        return bingeScheme.episodesOfFileClamped(fileEpisode, totalEpisodesInSeason)
    }

    /**
     * The next episode to chain into, or null for a film / a finished series.
     *
     * The file cursor is advanced through the detected scheme, exactly as the
     * two in-app engines do, so a binge that reaches this engine keeps stepping
     * by files rather than by TMDB episodes.
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
     * The FILE number for a target the labels call [targetEpisode]: the file
     * cursor's own next when the target is the arithmetic one, and the file that
     * HOLDS that TMDB episode otherwise. Mirrors the in-app engines.
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

    private fun showNextUpPanel(targetSeason: Int, targetEpisode: Int) {
        val panel = nextUpPanel ?: return
        pendingNextSeason = targetSeason
        pendingNextEpisode = targetEpisode
        pendingNextEpisodeName = null

        // The session's name can be an internal id when the card that launched
        // it had no name of its own and enrichment failed (see
        // upNextPlayerDisplayName, which stops that at the sending end - this is
        // the receiving belt-and-braces, for a name that arrived by another
        // route: a persisted NextEpisodeResult, a deep link, the picker). An id
        // printed as the show title is worse than saying nothing, because the
        // "Season 4 • Episode 41" line below still says what is coming.
        if (looksLikeRawMediaId(itemName, hasArtwork = !(backdropUrl ?: itemPoster).isNullOrBlank())) {
            nextUpShowTitle?.visibility = View.GONE
        } else {
            nextUpShowTitle?.visibility = View.VISIBLE
            nextUpShowTitle?.text = itemName
        }
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
                nextUpCountdown?.setTextColor(themeAccentColor(this))
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
                runCatchingCancellable { tmdbId() }.getOrNull()
            } ?: return@launch
            val nextEp = withContext(Dispatchers.IO) {
                runCatchingCancellable {
                    TmdbRepository.getInstance(this@ExternalPlayerActivity)
                        .getSeasonEpisodes(tmdb, targetSeason, parentId)
                }.getOrNull()?.firstOrNull { it.episodeNumber == targetEpisode }
            } ?: return@launch
            if (nextUpPanel?.visibility != View.VISIBLE) return@launch
            pendingNextEpisodeName = nextEp.name
            // Spoiler-free mode, the same rule and the same reasoning as the
            // other two engines (see NativePlayerActivity.showNextUpPanel).
            // This panel offers the episode the viewer has NOT reached, so the
            // name just resolved and a frame from that episode are exactly what
            // the mode hides. The S#E# line set when the panel was raised and
            // the show's own artwork stay, so it still says which episode is
            // coming without saying which one it is - and the handoff value
            // above stays whole, since it is what that episode's own session
            // opens with rather than what is drawn here.
            val hideNextEpisode = SpoilerFree.hidesIdentity(
                enabled = AppPreferences.getSpoilerFree(this@ExternalPlayerActivity),
                // The viewer is at the end of the episode before it: not
                // watched and not started. A rewatch is the only case where
                // they have seen it, and there the cost is a number.
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

    private fun setupEndOfEpisodeHandlers() {
        btnNextPlay?.setOnClickListener {
            // A manual press means a human is there: restart the binge
            // watchdog, exactly as both in-app engines do. Without this the
            // count only ever climbed, so "Are you still there?" arrived early
            // - even on a binge the viewer kept answering.
            AppPreferences.resetConsecutiveAutoplays(this)
            advanceToPendingNext()
        }
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
            streamId = nextStreamId(targetSeason, nextFileEpisodeFor(targetSeason, targetEpisode)),
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

    /**
     * The episode id's own prefix, the same rule every engine uses.
     * [targetFileEpisode] is FILE numbering - the identity the addons resolve
     * (see [nextFileEpisodeFor]).
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
                val tmdb = runCatchingCancellable { tmdbId() }.getOrNull()
                // The panel used to vanish without a trace in this case (an
                // addon-only title TMDB cannot map). Say so, so a missing row
                // is a line in the log rather than a mystery.
                if (tmdb == null) {
                    Log.w(
                        TAG,
                        "byw: no TMDB id for parent=$parentId type=$parentType - no row"
                    )
                }
                tmdb
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

    /**
     * The result contract MainActivity already understands from the other two
     * players: "play_now" applies the autoplay rule for the pick (the best
     * source when auto-select is on, the source list when it is off), and
     * "go_details" opens the detail screen.
     */
    private fun finishWithBywResult(
        action: String,
        pick: BywPick,
        imdbId: String
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
            }
        )
        finish()
    }

    /** The pick pills' look, matching both in-app engines' - literally: they all
     * call the one shared look (see [applyPillLook]), so the end-credits picker
     * cannot drift from the two in-app players' pills. */
    private fun applyPillBackground(view: TextView, selected: Boolean, focused: Boolean) {
        applyPillLook(this, view, selected, focused)
    }

    // ── Watch history ───────────────────────────────────────────────────────

    /**
     * Forgets the cached debrid link this session was opened from, so the next
     * replay resolves fresh instead of handing the same dead URL to the
     * external app again (PB-P2-3).
     *
     * The in-app engines have always done this the moment their launch fails
     * before a first frame; the external engine did not, so a dead link stayed
     * cached for its full TTL and every replay of that title bounced off it.
     * Deliberately not called on a launch that never happened (the app was
     * uninstalled between the query and the start): no player ever saw the URL,
     * so nothing was learned about the link.
     */
    private fun invalidateCachedLink() {
        if (linkCacheInvalidated) return
        val cacheKey = playedLinkKey ?: return
        linkCacheInvalidated = true
        // Pinned to the LAUNCH profile, exactly like the in-app engines (SD-2):
        // a mid-session switch must not spare the launching profile's dead
        // entry.
        PlayedLinkCache.forgetForProfile(this, cacheKey, sessionProfileId)
    }

    /**
     * The duration a session is measured against, from whichever source knows
     * it: the external player's own report, the TMDB runtime the launch
     * carried, and finally TMDB itself, looked up from the parent id.
     *
     * That last one is the fallback that makes an end decision possible at all
     * for a hand-off carrying no `runtime_minutes` extra (a deep link, a source
     * picked on the Streams screen). Without it the session had NO duration,
     * which meant no because-you-watched row - the title could never compare as
     * finished - and no watch-history row either, so it never even reached
     * Continue Watching.
     *
     * Cached in [durationMs] once resolved, so its two callers (the end
     * decision and the history write) pay for the lookup at most once.
     */
    private suspend fun ensureDurationMs(): Long {
        if (durationMs > 0L) return durationMs

        durationMs = (runtimeMinutes ?: 0) * 60_000L
        if (durationMs > 0L) return durationMs

        val tmdb = runCatchingCancellable { tmdbId() }.getOrNull() ?: return 0L
        val showSeason = season
        val showEpisode = episode

        val minutes = withContext(Dispatchers.IO) {
            val repo = TmdbRepository.getInstance(this@ExternalPlayerActivity)
            if (showSeason != null && showEpisode != null) {
                // An episode is measured against ITS runtime, not the show's.
                runCatchingCancellable {
                    repo.getSeasonEpisodes(tmdb, showSeason, parentId)
                        .firstOrNull { it.episodeNumber == showEpisode }
                        ?.runtimeMinutes
                        ?.takeIf { it > 0 }
                }.getOrNull()
            } else {
                null
            } ?: runCatchingCancellable {
                repo.getDetailByTmdbId(tmdb, parentType)
                    ?.displayRuntimeMinutes()
            }.getOrNull()
        }

        durationMs = (minutes ?: 0) * 60_000L
        if (durationMs > 0L) {
            Log.i(TAG, "duration resolved from TMDB: ${minutes}min")
        }
        return durationMs
    }

    /**
     * Writes the same row the other engines write - same id, same fields, same
     * canonical parent id - so a title played externally updates the ONE
     * Continue Watching card it already has rather than starting a second.
     *
     * The position is the measured estimate; the duration is the external
     * player's report, else the runtime the session carried, else TMDB's own
     * for the parent id ([ensureDurationMs]) - a duration is what makes a
     * position mean "44% through", so a session without one wrote no row at
     * all.
     */
    private fun saveProgress(forceCompleted: Boolean) {
        if (isLiveChannel || parentId.isBlank() || historyId.isBlank()) return
        val measuredPosition = positionMs
        lifecycleScope.launch(Dispatchers.IO + NonCancellable) {
            if (ensureDurationMs() <= 0L) {
                Log.i(TAG, "no duration for this session; skipping the history row")
                return@launch
            }
            val rawPosition = if (forceCompleted) durationMs else measuredPosition
            if (!forceCompleted && rawPosition < MIN_RESUME_POSITION_MS) return@launch

            val completed = forceCompleted ||
                rawPosition >= (durationMs * COMPLETION_THRESHOLD_RATIO).toLong()
            val safePosition = if (completed) 0L else rawPosition.coerceAtMost(durationMs)
            val now = System.currentTimeMillis()
            if (completed) completionSent = true

            Log.i(
                TAG,
                "save progress: ${safePosition}ms / ${durationMs}ms completed=$completed"
            )

            // The write goes through PlaybackHistoryWriter, which files the
            // row under the profile this SESSION started on and refuses it if
            // the user switched profiles while it was playing.
            val entry =
                WatchHistoryEntity(
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
                    // Completed rows keep the stamp of the first completion;
                    // the writer reads it back from the row it replaces.
                    completedAt = null
                )
            PlaybackHistoryWriter.write(this@ExternalPlayerActivity, sessionProfileId, entry)
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

            // Everything else - which in practice means the imdb id every addon
            // catalog hands out, and the form the history parent is
            // canonicalized to - resolves through the shared helper (addon meta
            // -> TMDB, cached in memory and on disk). A `startsWith("tt") ->
            // null` short-circuit used to stand in for that, and it left the
            // because-you-watched panel with no seed: it built an empty lineup,
            // hid itself, and finished the session, so the row never appeared
            // for a film played in the external engine.
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
        // A tracker call resolves the ACTIVE profile's token/key at fire time,
        // so a session that outlived a profile switch must not scrobble the
        // departing episode to the profile the viewer moved TO.
        if (!PlaybackHistoryWriter.sessionStillActive(this, sessionProfileId)) {
            com.kennyb1201.kbstream.data.reporting.PlaybackSessionTrace.note(
                "tracker $action skipped: session profile departed"
            )
            return
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // The percentage IS the report, and a session that never carried a
            // runtime has to resolve one before it can be computed: telling the
            // trackers 0% for a film watched to the end is worse than telling
            // them nothing, because both of them act on it.
            val progress = progressOverride ?: run {
                if (durationMs <= 0L) ensureDurationMs()
                if (durationMs > 0L) {
                    ((positionMs.toDouble() / durationMs.toDouble()) * 100.0)
                        .coerceIn(0.0, 100.0)
                } else {
                    0.0
                }
            }
            runCatchingCancellable {
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
        if (!PlaybackHistoryWriter.sessionStillActive(this, sessionProfileId)) {
            com.kennyb1201.kbstream.data.reporting.PlaybackSessionTrace.note(
                "tracker completion skipped: session profile departed"
            )
            return
        }
        trackersMarkedWatched = true
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatchingCancellable {
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

        /**
         * How long the hand-off waits for intro/credits stamps before giving up
         * and handing over anyway. The lookup is two 5s-timeout HTTP requests in
         * the worst case, and a viewer waiting on a black card is worse than an
         * intro they have to sit through.
         */
        private const val SEGMENT_LOOKUP_TIMEOUT_MS = 1_500L

        /**
         * Next Up's unattended-advance countdown. It is the package-level
         * [NEXT_UP_COUNTDOWN_SECONDS] both engines share, not a private copy:
         * the hand-off used to count ten seconds where the native and MPV
         * players counted five, so the same end-of-episode card sat for twice
         * as long on the one engine that raised it.
         */
        private const val NEXT_UP_HOLD_THRESHOLD_MS = 60_000L

        /**
         * A return this soon, with no reported playhead, means the external app
         * never really played anything - which is what the refused-stream card
         * is for.
         */
        private const val BOUNCE_THRESHOLD_MS = 4_000L
    }
}
