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
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.lifecycle.lifecycleScope
import com.kennyb1201.kbstream.R
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import com.kennyb1201.kbstream.data.mdblist.MdbListClient
import com.kennyb1201.kbstream.data.simkl.SimklRepository
import com.kennyb1201.kbstream.data.sync.SupabaseSync
import com.kennyb1201.kbstream.data.tv.TvLauncherPublisher
import com.kennyb1201.kbstream.ui.settings.AppPreferences
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
 * correction, tracks remembered per title (PlayerTrackMemory), the audio
 * tuning chain, intro/credits skip, and the media session. This engine's job is
 * to get a picture on screen for a file the main player could not open at all —
 * the controls it does have (pause, ±10s, subtitle track, audio track,
 * hardware/software decoding) are the ones that matter when that happens.
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
    private var controlTitle: TextView? = null
    private var controlSubtitle: TextView? = null
    private var positionView: TextView? = null
    private var durationView: TextView? = null
    private var seekBar: SeekBar? = null
    private var playPauseButton: Button? = null
    private var decodeButton: Button? = null

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
                    exitPlayer()
                }
            }
        )

        if (!view.initialize()) return

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
        controlsContainer = findViewById(R.id.mpv_controls)
        controlTitle = findViewById(R.id.mpv_control_title)
        controlSubtitle = findViewById(R.id.mpv_control_subtitle)
        positionView = findViewById(R.id.mpv_position)
        durationView = findViewById(R.id.mpv_duration)
        seekBar = findViewById(R.id.mpv_seekbar)
        playPauseButton = findViewById(R.id.mpv_btn_play_pause)
        decodeButton = findViewById(R.id.mpv_btn_decode)
        seekBar?.max = 1000
    }

    private fun setupControls() {
        controlTitle?.text = itemTitle()
        controlSubtitle?.text = if (isFallbackSession) {
            "MPV backup engine \u2014 ExoPlayer could not play this stream"
        } else {
            "MPV engine"
        }

        playPauseButton?.setOnClickListener {
            surface?.togglePause()
            keepControlsVisible()
        }
        findViewById<Button>(R.id.mpv_btn_back_10).setOnClickListener {
            surface?.seekBy(-SEEK_STEP_MS)
            keepControlsVisible()
        }
        findViewById<Button>(R.id.mpv_btn_fwd_10).setOnClickListener {
            surface?.seekBy(SEEK_STEP_MS)
            keepControlsVisible()
        }
        findViewById<Button>(R.id.mpv_btn_subs).setOnClickListener {
            showToast(surface?.cycleSubtitleTrack() ?: "Subtitles")
            keepControlsVisible()
        }
        findViewById<Button>(R.id.mpv_btn_audio).setOnClickListener {
            showToast(surface?.cycleAudioTrack() ?: "Audio")
            keepControlsVisible()
        }
        decodeButton?.setOnClickListener {
            val view = surface ?: return@setOnClickListener
            val hardware = !view.isHardwareDecoding()
            view.setHardwareDecoding(hardware)
            decodeButton?.text = if (hardware) "Decode: HW" else "Decode: SW"
            showToast(if (hardware) "Hardware decoding" else "Software decoding (smoother on files this box can't decode)")
            keepControlsVisible()
        }
    }

    // --- Playback callbacks ------------------------------------------------

    private fun onFileLoaded(mediaTitle: String?) {
        runOnUiThread {
            loadingContainer?.visibility = View.GONE
            controlTitle?.text = itemTitle()
            updatePlayPauseLabel(false)
        }
        // Scrobble once the file is really open — a stream that never loads
        // must not appear on a tracker as started.
        if (!scrobbleStarted) {
            scrobbleStarted = true
            scrobble("start")
        }
        if (isFallbackSession) {
            val hint = if (fallbackReason == FALLBACK_REASON_DECODER) {
                "\u2014 if it stutters, press Decode to switch to software decoding"
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
            positionView?.text = formatClock(positionMs)
            durationView?.text = if (durationMs > 0L) formatClock(durationMs) else "--:--"
            if (durationMs > 0L) {
                val progress = ((positionMs * 1000L) / durationMs).toInt().coerceIn(0, 1000)
                seekBar?.progress = progress
            }
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

    private fun updatePlayPauseLabel(paused: Boolean) {
        playPauseButton?.text = if (paused) "Play" else "Pause"
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

            if (AppPreferences.getAutoPlayNext(this) && season != null && episode != null) {
                launchNextEpisode(season!!, episode!! + 1)
            } else {
                finish()
            }
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
        controlsContainer?.visibility = View.GONE
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
