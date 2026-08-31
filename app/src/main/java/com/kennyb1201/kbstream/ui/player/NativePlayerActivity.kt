package com.kennyb1201.kbstream.ui.player

import android.app.PictureInPictureParams
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
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
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
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.kennyb1201.kbstream.R
import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import com.kennyb1201.kbstream.data.simkl.SimklRepository
import com.kennyb1201.kbstream.ui.settings.AppPreferences
import coil3.load
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
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

class NativePlayerActivity : ComponentActivity() {

    // Views
    private lateinit var playerView: PlayerView
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

    // Player
    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    // State
    private val handler = Handler(Looper.getMainLooper())
    private var controlsVisible = false
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

    // Retry
    private var retryAttempt = 0
    private var retryExhausted = false
    private var errorMessageStr: String? = null
    private var forceSoftwareDecoder = false
    private var manualRetryToken = 0
    private var rebufferStartedAtMs = 0L

    // History
    private var isLiveChannel = false
    private var parentId = ""
    private var parentType = ""
    private var season: Int? = null
    private var episode: Int? = null
    private var episodeStreamId: String? = null
    private var itemName = ""
    private var itemPoster: String? = null
    private var clearLogoUrl: String? = null
    private var backdropUrl: String? = null
    private var overview: String? = null
    private var sources: List<Stream> = emptyList()
    private var castMembers: List<PlayerCastMember> = emptyList()
    private var totalEpisodesInSeason: Int? = null
    private var streamHeaders = emptyMap<String, String>()
    private var drmLicenseUrl: String? = null
    private var drmHeaders = emptyMap<String, String>()
    private var externalSubtitleUri: Uri? = null
    private var startPositionMs = 0L
    private var historyId = ""

    // IntroDB
    private var introDbStamps = emptyList<IntroDbStamp>()
    private var activeIntroStamp: IntroDbStamp? = null

    // Settings prefs
    private var enableTunneling = false
    private var bufferMode = 0
    private var autoPlayNext = false
    private var episodeTitle: String? = null
    private var preferredAudioLang = ""
    private var preferredSubtitleLang = ""

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
                KeyEvent.KEYCODE_BACK -> { dismissAllPanels(); hideControls(); true }
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
        updateHeaderInfo()
        updateControlsInfo()

        setupListeners()
        setupKeyboardHandler()
        setupIntroDb()
        createPlayer()
    }

    private fun bindViews() {
        playerView = findViewById(R.id.player_view)
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
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP -> {
                    // Keep focus cycling within the settings panel
                    val focused = settingsContainer.findFocus()
                    val scrollBounds = intArrayOf(0, 0)
                    settingsContainer.getLocationOnScreen(scrollBounds)
                    val childCount = settingsContainer.childCount
                    if (childCount > 0) {
                        val inner = settingsContainer.getChildAt(0) as? android.view.ViewGroup
                        if (inner != null) {
                            val focusedY = if (focused != null) {
                                val loc = intArrayOf(0, 0)
                                focused.getLocationOnScreen(loc)
                                loc[1]
                            } else -1
                            val containerTop = scrollBounds[1]
                            val containerBottom = containerTop + settingsContainer.height
                            // If focus would leave the panel, snap to first/last child
                            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && focused != null &&
                                focusedY <= containerTop + 30) {
                                // Already at top, find first focusable
                                for (i in 0 until inner.childCount) {
                                    val v = inner.getChildAt(i)
                                    if (v.isFocusable) { v.requestFocus(); break }
                                }
                                true
                            } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && focused != null &&
                                focusedY >= containerBottom - 60) {
                                // Near bottom, scroll and find next focusable
                                for (i in inner.childCount - 1 downTo 0) {
                                    val v = inner.getChildAt(i)
                                    if (v.isFocusable) { v.requestFocus(); break }
                                }
                                true
                            } else false
                        } else false
                    } else false
                }
                else -> false
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
        btnNext.visibility = if (season != null && episode != null) View.VISIBLE else View.GONE
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
            if (season != null && episode != null) {
                val nextEp = episode!! + 1
                val maxEps = totalEpisodesInSeason
                if (maxEps == null || nextEp <= maxEps) {
                    setResult(RESULT_OK, Intent().apply {
                        putExtra("player_result_action", "next_episode")
                        putExtra("next_season", season!!)
                        putExtra("next_episode", nextEp)
                        putExtra("next_title", "Episode $nextEp")
                        putExtra("next_stream_id", episodeStreamId)
                    })
                    finish()
                }
            }
        }
        btnSource.setOnClickListener { showPicker(PickerMode.SOURCE) }
        btnSource.setOnFocusChangeListener { _, focused -> if (focused) removeAutoHide() else scheduleAutoHide() }
        btnAudio.setOnClickListener { showPicker(PickerMode.AUDIO) }
        btnAudio.setOnFocusChangeListener { _, focused -> if (focused) removeAutoHide() else scheduleAutoHide() }
        btnSubtitle.setOnClickListener { showPicker(PickerMode.SUBTITLE) }
        btnSpeed.setOnClickListener { showPicker(PickerMode.SPEED) }
        btnAspect.setOnClickListener {
            resizeModeIndex = (resizeModeIndex + 1) % 3
            playerView.resizeMode = when (resizeModeIndex) {
                1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
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
                    if (!controlsVisible) showControls() else hideControls()
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    showControls()
                    controlsOverlay.requestFocus()
                    true
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
        val agent = streamHeaders["User-Agent"] ?: streamHeaders["user-agent"]
            ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        val httpFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent(agent)

        val extraHeaders = streamHeaders
            .filterKeys { !it.equals("User-Agent", ignoreCase = true) }
            .filterValues { it.isNotBlank() }
        if (extraHeaders.isNotEmpty()) httpFactory.setDefaultRequestProperties(extraHeaders)

        val mediaSourceFactory = DefaultMediaSourceFactory(httpFactory, DefaultExtractorsFactory())

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
        val bufferDurations = if (resolvedBufferMode == 1) intArrayOf(2_500, 10_000, 1_500, 3_000)
        else intArrayOf(10_000, 30_000, 3_000, 6_000)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(bufferDurations[0], bufferDurations[1], bufferDurations[2], bufferDurations[3])
            .setPrioritizeTimeOverSizeThresholds(true).build()

        val extensionMode = when {
            forceSoftwareDecoder -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            AppPreferences.getDecoderMode(this) == 1 -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            else -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
        }
        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(extensionMode)
            .setEnableDecoderFallback(true)

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
                if (!audioUrl.isNullOrBlank()) {
                    val videoSource = ProgressiveMediaSource.Factory(httpFactory)
                        .createMediaSource(mediaItemBuilder.build())
                    val audioSource = ProgressiveMediaSource.Factory(httpFactory)
                        .createMediaSource(MediaItem.fromUri(audioUrl))
                    setMediaSource(MergingMediaSource(videoSource, audioSource))
                } else {
                    setMediaItem(mediaItemBuilder.build())
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
                        setMediaItem(currentItem)
                    } else {
                        Log.w(TAG, "External subtitles are not merged with a separate audio source")
                    }
                }

                if (!isLiveChannel && carryPositionMs > 0L) seekTo(carryPositionMs)
                setPlaybackSpeed(playbackSpeed)
                playWhenReady = true
                prepare()

                if (enableTunneling) {
                    trackSelectionParameters = androidx.media3.exoplayer.trackselection.DefaultTrackSelector
                        .Parameters.Builder(this@NativePlayerActivity)
                        .setTunnelingEnabled(true).build()
                    Log.i("PLAYER_TUNNEL", "Tunneled via TrackSelector")
                }
            }

        player.addListener(createPlayerListener())
        player.addAnalyticsListener(createAnalyticsListener())

        exoPlayer = player
        playerView.player = player

        mediaSession?.release()
        mediaSession = MediaSession.Builder(this, player).build()

        // Start position polling
        startPositionPolling()
        // Start intro stamp polling
        startIntroStampPolling()
    }

    private fun recreatePlayer() {
        scope?.cancel()
        exoPlayer?.release()
        exoPlayer = null
        createPlayer()
    }

    private fun createPlayerListener() = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) hideSplash()
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
                }
                Player.STATE_ENDED -> {
                    onPlaybackEnded()
                }
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            for (group in tracks.groups) {
                for (i in 0 until group.length) {
                    val fmt = group.getTrackFormat(i)
                    if (group.type == C.TRACK_TYPE_VIDEO) {
                        val codec = fmt.codecs.orEmpty()
                        val colorInfo = fmt.colorInfo
                        streamWidth = fmt.width
                        streamHeight = fmt.height
                        streamBitrate = fmt.bitrate
                        streamCodec = codec.ifBlank { null }
                        Log.i("PLAYER_CODEC", "video codec=$codec mime=${fmt.sampleMimeType} ${fmt.width}x${fmt.height}")
                    }
                }
            }
            updateStreamHealthDisplay()
        }

        override fun onPlayerError(error: PlaybackException) {
            val msg = friendlyErrorMessage(error)
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
    private fun showSplash() {
        splashContainer.visibility = View.VISIBLE
        bufferingSpinner.visibility = View.GONE
    }

    private fun hideSplash() {
        splashContainer.visibility = View.GONE
    }

    private fun updateUIBuffering() {
        if (!controlsVisible) {
            bufferingSpinner.visibility = View.VISIBLE
        }
        // Show splash overlay during buffering if backdrop is available
        if (splashBackdrop.drawable != null || splashClearLogo.drawable != null) {
            showSplash()
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

        // Load backdrop into splash overlay
        val resolvedBackdropUrl = backdropUrl?.takeIf { it.isNotBlank() }?.let { rawUrl ->
            if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) rawUrl
            else "https://image.tmdb.org/t/p/w1280${if (rawUrl.startsWith("/")) rawUrl else "/$rawUrl"}"
        } ?: itemPoster?.takeIf { it.isNotBlank() }?.let { rawPoster ->
            if (rawPoster.startsWith("http://") || rawPoster.startsWith("https://")) rawPoster
            else if (rawPoster.startsWith("/")) "https://image.tmdb.org/t/p/w1280$rawPoster"
            else null
        }
        if (resolvedBackdropUrl != null) {
            splashBackdrop.load(resolvedBackdropUrl)
            // Show splash initially before video plays
            showSplash()
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
                val codecLabel = normalizeCodec(streamCodec)
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

    private fun updateControlsInfo() {
        sourceLabel.text = "Source: $currentSourceLabel"
        btnPlayPause.setImageResource(
            if (exoPlayer?.isPlaying == true) R.drawable.ic_player_pause else R.drawable.ic_player_play
        )
        btnSpeed.text = "${playbackSpeed}x"
        updateStreamHealthDisplay()
    }

    private fun pillBg(selected: Boolean, focused: Boolean): Int = when {
        selected && focused -> R.drawable.pill_chip_selected_focused_bg
        selected -> R.drawable.pill_chip_selected_bg
        focused -> R.drawable.pill_chip_focused_bg
        else -> R.drawable.pill_chip_bg
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
            val codecLabel = normalizeCodec(streamCodec)
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
        updateControlsInfo()
        controlsOverlay.post { btnPlayPause.requestFocus() }
        scheduleAutoHide()
    }

    private fun hideControls() {
        controlsVisible = false
        controlsOverlay.visibility = View.GONE
        dismissAllPanels()
        if (exoPlayer?.isPlaying == true) {
            bufferingSpinner.visibility = View.GONE
            hideSplash()
        } else {
            bufferingSpinner.visibility = View.VISIBLE
        }
    }

    private fun updateSubtitleSettings() {
        val sizeLabels = listOf("Small", "Normal", "Large")
        val bgLabels = listOf("None", "Semi", "Solid")

        listOf(btnSubSmall to 0, btnSubNormal to 1, btnSubLarge to 2).forEach { (btn, idx) ->
            btn.setBackgroundResource(if (subtitleSize == idx) R.drawable.pill_chip_selected_bg else R.drawable.pill_chip_bg)
            btn.setTextColor(if (subtitleSize == idx) getColor(R.color.kb_void) else getColor(R.color.kb_text_hi))
        }
        listOf(btnSubBgNone to 0, btnSubBgSemi to 1, btnSubBgSolid to 2).forEach { (btn, idx) ->
            btn.setBackgroundResource(if (subtitleBackground == idx) R.drawable.pill_chip_selected_bg else R.drawable.pill_chip_bg)
            btn.setTextColor(if (subtitleBackground == idx) getColor(R.color.kb_void) else getColor(R.color.kb_text_hi))
        }
        subtitleOffsetValue.text = "${subtitleOffsetMs}ms"
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
            it.playWhenReady = !it.isPlaying
            if (it.isPlaying) {
                showControls()
            } else {
                hideControls()
            }
        }
    }

    private val autoHideRunnable = Runnable { hideControls() }

    private fun scheduleAutoHide() {
        handler.removeCallbacks(autoHideRunnable)
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
        scope?.launch {
            saveProgress(reason = "ended", forceCompleted = true)
            if (autoPlayNext && !isLiveChannel && season != null && episode != null) {
                val nextEp = episode!! + 1
                val maxEps = totalEpisodesInSeason
                if (maxEps == null || nextEp <= maxEps) {
                    setResult(RESULT_OK, Intent().apply {
                        putExtra("player_result_action", "next_episode")
                        putExtra("next_season", season!!)
                        putExtra("next_episode", nextEp)
                        putExtra("next_title", "Episode $nextEp")
                        putExtra("next_stream_id", episodeStreamId)
                    })
                    finish()
                }
            }
        }
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
            }
            handler.postDelayed(this, 1_000L)
        }
    }

    private fun startPositionPolling() {
        handler.removeCallbacks(positionRunnable)
        handler.post(positionRunnable)
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
        scope?.launch {
            if (isLiveChannel) return@launch
            val player = exoPlayer ?: return@launch
            val pos = withContext(Dispatchers.Main) { player.currentPosition.coerceAtLeast(0L) }
            val rawDur = withContext(Dispatchers.Main) { player.duration }
            val dur = if (rawDur == C.TIME_UNSET || rawDur <= 0L) null else rawDur
            if (parentId.isBlank() || historyId.isBlank() || dur == null) return@launch
            if (pos < MIN_RESUME_POSITION_MS && !forceCompleted) return@launch

            val isCompleted = forceCompleted || pos >= (dur * COMPLETION_THRESHOLD_RATIO).toLong()
            val safePos = if (isCompleted) 0L else pos.coerceAtMost(dur)
            val now = System.currentTimeMillis()
            val dao = WatchHistoryDatabase.getInstance(this@NativePlayerActivity).watchHistoryDao()
            val existing = withContext(Dispatchers.IO) { dao.getById(historyId) }

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
            withContext(Dispatchers.IO) { dao.upsert(entry) }

            if (isCompleted && existing?.completedAt == null) {
                launch(Dispatchers.IO) {
                    val simkl = SimklRepository(this@NativePlayerActivity)
                    when (parentType.lowercase()) {
                        "movie" -> simkl.pushWatchedMovie(imdbId = parentId, title = itemName)
                        "series", "show", "tv" -> {
                            val s = season; val e = episode
                            if (s != null && e != null) {
                                simkl.pushWatchedEpisode(showImdbId = parentId, season = s, episode = e, title = itemName)
                            }
                        }
                    }
                }
            }
        }
    }

    // --- PiP ---
    private fun enterPipIfEnabled() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && AppPreferences.getEnablePip(this)) {
            try {
                enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
            } catch (e: Exception) { Log.w("PLAYER_PIP", "PiP failed", e) }
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
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
    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        scope?.cancel()
        saveProgressSync(reason = "destroy")
        exoPlayer?.release()
        exoPlayer = null
        mediaSession?.release()
        mediaSession = null
    }

    private fun saveProgressSync(reason: String, forceCompleted: Boolean = false) {
        val player = exoPlayer ?: return
        if (isLiveChannel || parentId.isBlank() || historyId.isBlank()) return
        runBlocking {
            runCatching {
                val pos = player.currentPosition.coerceAtLeast(0L)
                val rawDur = player.duration
                val dur = if (rawDur == C.TIME_UNSET || rawDur <= 0L) null else rawDur
                if (dur == null) return@runBlocking
                if (pos < MIN_RESUME_POSITION_MS && !forceCompleted) return@runBlocking
                val isCompleted = forceCompleted || pos >= (dur * COMPLETION_THRESHOLD_RATIO).toLong()
                val safePos = if (isCompleted) 0L else pos.coerceAtMost(dur)
                val now = System.currentTimeMillis()
                val dao = WatchHistoryDatabase.getInstance(this@NativePlayerActivity).watchHistoryDao()
                val existing = withContext(Dispatchers.IO) { dao.getById(historyId) }
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
                withContext(Dispatchers.IO) { dao.upsert(entry) }
            }
        }
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
        retryAttempt = 0; retryExhausted = false; errorMessageStr = null; forceSoftwareDecoder = false; languagesAutoSelected = false
        dismissPicker()
        recreatePlayer()
    }

    companion object {

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

/**
 * Normalize raw codec string into a friendly label:
 * "hev1.1.6.L150" → "H.265", "avc1.640028" → "H.264",
 * "vp09.00" → "VP9", "av01" → "AV1".
 */
internal fun normalizeCodec(codec: String?): String {
    if (codec.isNullOrBlank()) return "—"
    val lower = codec.lowercase()
    return when {
        lower.startsWith("dv") || lower.contains("dolby vision") || lower.contains("dvhe") || lower.contains("dvh1") -> "Dolby Vision"
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
