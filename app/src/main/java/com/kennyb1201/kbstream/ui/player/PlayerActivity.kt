package com.kennyb1201.kbstream.ui.player

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

private const val PERIODIC_SAVE_INTERVAL_MS = 5_000L
private const val MIN_RESUME_POSITION_MS = 10_000L
private const val COMPLETION_THRESHOLD_RATIO = 0.95f
private const val EXTRA_HEADERS = "stream_headers"

private const val MAX_RETRY_ATTEMPTS = 6
private val RETRY_BACKOFF_MS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)

private val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

class PlayerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra("stream_url")
        if (url.isNullOrBlank()) {
            finish()
            return
        }

        val parentId = intent.getStringExtra("parent_id") ?: ""
        val parentType = intent.getStringExtra("parent_type") ?: ""
        val season = intent.getIntExtra("season", -1).takeIf { it >= 0 }
        val episode = intent.getIntExtra("episode", -1).takeIf { it >= 0 }
        val episodeStreamId = intent.getStringExtra("episode_stream_id")
        val itemName = intent.getStringExtra("item_name") ?: ""
        val itemPoster = intent.getStringExtra("item_poster")
        val startPositionMs = intent.getLongExtra("start_position_ms", 0L)
        val streamHeaders = intent.getStringExtra(EXTRA_HEADERS).orEmpty()

        setContent {
            PlayerScreen(
                url = url,
                parentId = parentId,
                parentType = parentType,
                season = season,
                episode = episode,
                episodeStreamId = episodeStreamId,
                itemName = itemName,
                itemPoster = itemPoster,
                startPositionMs = startPositionMs,
                streamHeaders = parseHeaders(streamHeaders),
                sources = emptyList()
            )
        }
    }
}

private fun resolveMimeType(url: String): String? {
    val lower = url.lowercase()
    return when {
        lower.contains(".m3u8") -> MimeTypes.APPLICATION_M3U8
        lower.contains(".mpd") -> MimeTypes.APPLICATION_MPD
        lower.contains(".ts") -> MimeTypes.VIDEO_MP2T
        lower.contains(".mp4") -> MimeTypes.VIDEO_MP4
        lower.contains(".mkv") -> MimeTypes.VIDEO_MATROSKA
        else -> null
    }
}

private fun isLikelyRetryable(error: PlaybackException): Boolean {
    val code = error.errorCode
    return code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
        code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
        code == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
        code == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
        code == PlaybackException.ERROR_CODE_TIMEOUT ||
        code == PlaybackException.ERROR_CODE_DECODING_FAILED ||
        code == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
}

private fun Stream.label(): String =
    title?.takeIf { it.isNotBlank() } ?: name?.takeIf { it.isNotBlank() } ?: "Unknown source"

@Composable
fun PlayerScreen(
    url: String,
    parentId: String,
    parentType: String,
    season: Int?,
    episode: Int?,
    episodeStreamId: String?,
    itemName: String,
    itemPoster: String?,
    startPositionMs: Long,
    streamHeaders: Map<String, String> = emptyMap(),
    sources: List<Stream> = emptyList()
) {
    val context = LocalContext.current
    val isLiveChannel = parentType == "channel"

    var currentUrl by remember(url) { mutableStateOf(url) }
    var currentSourceLabel by remember(url) {
        mutableStateOf(sources.firstOrNull { it.url == url }?.label() ?: "Source 1")
    }
    var carryPositionMs by remember { mutableStateOf(startPositionMs) }

    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isRetrying by remember { mutableStateOf(false) }
    var retryAttempt by remember(currentUrl) { mutableIntStateOf(0) }
    var retryExhausted by remember(currentUrl) { mutableStateOf(false) }
    var manualRetryToken by remember { mutableStateOf(0) }
    var forceSoftwareDecoder by remember(currentUrl) { mutableStateOf(false) }

    var showControls by remember { mutableStateOf(true) }
    var showSourcePicker by remember { mutableStateOf(false) }
    var showAudioPicker by remember { mutableStateOf(false) }
    var showSubtitlePicker by remember { mutableStateOf(false) }
    var showSpeedPicker by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableStateOf(1f) }
    var resizeModeIndex by remember { mutableIntStateOf(0) }

    val isPlayingFlow = remember { MutableStateFlow(false) }
    val currentPositionFlow = remember { MutableStateFlow(0L) }
    val durationFlow = remember { MutableStateFlow(0L) }
    val isBufferingFlow = remember { MutableStateFlow(false) }
    val isBuffering by isBufferingFlow.collectAsState()
    val isPlaying by isPlayingFlow.collectAsState()

    val historyId = remember(parentId, season, episode, episodeStreamId) {
        when {
            !episodeStreamId.isNullOrBlank() -> episodeStreamId
            season != null && episode != null -> "$parentId:$season:$episode"
            else -> parentId
        }
    }

    val exoPlayer = remember(currentUrl, streamHeaders, manualRetryToken, forceSoftwareDecoder) {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setUserAgent(streamHeaders["User-Agent"] ?: streamHeaders["user-agent"] ?: "VLC/3.0.20 LibVLC/3.0.20")

        val extraHeaders = streamHeaders
            .filterKeys { !it.equals("User-Agent", ignoreCase = true) }
            .filterValues { it.isNotBlank() }

        if (extraHeaders.isNotEmpty()) {
            httpFactory.setDefaultRequestProperties(extraHeaders)
        }

        val extractorsFactory = DefaultExtractorsFactory()
        val mediaSourceFactory = DefaultMediaSourceFactory(httpFactory, extractorsFactory)

        val mimeType = resolveMimeType(currentUrl)
        val mediaItemBuilder = MediaItem.Builder().setUri(currentUrl)
        if (mimeType != null) mediaItemBuilder.setMimeType(mimeType)
        val mediaItem = mediaItemBuilder.build()

        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(
                if (forceSoftwareDecoder) {
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                } else {
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                }
            )
            .setEnableDecoderFallback(true)

        ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                setMediaItem(mediaItem)
                if (!isLiveChannel && carryPositionMs > 0L) seekTo(carryPositionMs)
                setPlaybackSpeed(playbackSpeed)
                playWhenReady = true
                prepare()
            }
    }

    LaunchedEffect(exoPlayer, errorMessage, retryExhausted) {
        val error = errorMessage
        if (error != null && !retryExhausted && !isRetrying) {
            if (retryAttempt < MAX_RETRY_ATTEMPTS) {
                isRetrying = true
                if (retryAttempt == 2 && !forceSoftwareDecoder) {
                    forceSoftwareDecoder = true
                }
                val delayMs = RETRY_BACKOFF_MS.getOrElse(retryAttempt) { RETRY_BACKOFF_MS.last() }
                delay(delayMs)
                retryAttempt += 1
                errorMessage = null
                isRetrying = false
                manualRetryToken += 1
            } else {
                retryExhausted = true
            }
        }
    }

    LaunchedEffect(showControls) {
        if (showControls) {
            delay(6000)
            if (!showSourcePicker && !showAudioPicker && !showSubtitlePicker && !showSpeedPicker) {
                showControls = false
            }
        }
    }

    DisposableEffect(
        currentUrl,
        parentId,
        parentType,
        season,
        episode,
        episodeStreamId,
        itemName,
        itemPoster,
        streamHeaders,
        manualRetryToken,
        forceSoftwareDecoder
    ) {
        val handler = CoroutineExceptionHandler { _, throwable ->
            Log.e("PLAYER_HISTORY", "History save failed", throwable)
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + handler)
        val dao = WatchHistoryDatabase.getInstance(context).watchHistoryDao()

        var lastKnownDurationMs: Long? = null
        var hasEnded = false

        suspend fun saveProgress(reason: String, forceCompleted: Boolean = false) {
            if (isLiveChannel) return

            val snapshot = withContext(Dispatchers.Main.immediate) {
                val position = exoPlayer.currentPosition.coerceAtLeast(0L)
                val rawDuration = exoPlayer.duration
                position to rawDuration
            }

            val position = snapshot.first
            val rawDuration = snapshot.second

            val resolvedDuration = when {
                rawDuration == C.TIME_UNSET -> null
                rawDuration <= 0L -> null
                else -> rawDuration
            }

            if (resolvedDuration != null) lastKnownDurationMs = resolvedDuration
            val durationToSave = resolvedDuration ?: lastKnownDurationMs

            if (parentId.isBlank() || historyId.isBlank() || durationToSave == null) {
                Log.e(
                    "PLAYER_HISTORY",
                    "Skipped save, reason=$reason, parentId=$parentId, historyId=$historyId, rawDuration=$rawDuration, durationToSave=$durationToSave"
                )
                return
            }

            if (position < MIN_RESUME_POSITION_MS && !forceCompleted) {
                Log.d("PLAYER_HISTORY", "Skipped save, reason=$reason, position=$position < $MIN_RESUME_POSITION_MS")
                return
            }

            val completionThresholdMs = (durationToSave * COMPLETION_THRESHOLD_RATIO).toLong()
            val isCompleted = forceCompleted || position >= completionThresholdMs
            val safePosition = if (isCompleted) 0L else position.coerceAtMost(durationToSave)
            val now = System.currentTimeMillis()

            val existing = withContext(Dispatchers.IO) { dao.getById(historyId) }

            val completedAt = when {
                isCompleted -> existing?.completedAt ?: now
                else -> null
            }

            val entry = WatchHistoryEntity(
                id = historyId,
                parentId = parentId,
                type = parentType,
                name = itemName,
                poster = itemPoster,
                streamUrl = currentUrl,
                season = season,
                episode = episode,
                episodeStreamId = episodeStreamId,
                positionMs = safePosition,
                durationMs = durationToSave,
                updatedAt = now,
                isCompleted = isCompleted,
                completedAt = completedAt
            )

            withContext(Dispatchers.IO) { dao.upsert(entry) }
        }

        val playerListener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                isPlayingFlow.value = isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBufferingFlow.value = (playbackState == Player.STATE_BUFFERING)
                if (playbackState == Player.STATE_READY) {
                    durationFlow.value = exoPlayer.duration.coerceAtLeast(0L)
                    currentPositionFlow.value = exoPlayer.currentPosition
                    errorMessage = null
                    retryAttempt = 0
                    retryExhausted = false
                }
                if (playbackState == Player.STATE_ENDED && !hasEnded) {
                    hasEnded = true
                    scope.launch {
                        try {
                            saveProgress("ended", forceCompleted = true)
                        } catch (e: Exception) {
                            Log.e("PLAYER_HISTORY", "Ended save failed", e)
                        }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val retryable = isLikelyRetryable(error)
                errorMessage = buildString {
                    append(error.errorCodeName)
                    error.message?.takeIf { it.isNotBlank() }?.let {
                        append(" — ")
                        append(it)
                    }
                    if (forceSoftwareDecoder) append(" (software decoder)")
                }
                if (!retryable) {
                    retryExhausted = true
                }
                Log.e(
                    "PLAYER",
                    "Playback error for url=$currentUrl headers=$streamHeaders retryable=$retryable softwareDecoder=$forceSoftwareDecoder",
                    error
                )
            }
        }

        exoPlayer.addListener(playerListener)

        val positionTrackerJob = scope.launch {
            while (true) {
                delay(1000L)
                if (exoPlayer.isPlaying) {
                    currentPositionFlow.value = exoPlayer.currentPosition
                    val dur = exoPlayer.duration
                    if (dur > 0) durationFlow.value = dur
                }
            }
        }

        val periodicSaveJob = scope.launch {
            while (true) {
                delay(PERIODIC_SAVE_INTERVAL_MS)
                if (hasEnded) break
                try {
                    saveProgress("periodic")
                } catch (e: Exception) {
                    Log.e("PLAYER_HISTORY", "Periodic save failed", e)
                }
            }
        }

        onDispose {
            exoPlayer.removeListener(playerListener)
            positionTrackerJob.cancel()
            periodicSaveJob.cancel()

            runBlocking {
                try {
                    saveProgress("dispose", forceCompleted = hasEnded)
                } catch (e: Exception) {
                    Log.e("PLAYER_HISTORY", "Final dispose save failed", e)
                }
            }

            scope.cancel()
            exoPlayer.release()
        }
    }

    fun switchToSource(stream: Stream) {
        val newUrl = stream.url ?: return
        if (newUrl == currentUrl) return
        carryPositionMs = if (isLiveChannel) 0L else exoPlayer.currentPosition.coerceAtLeast(0L)
        currentSourceLabel = stream.label()
        currentUrl = newUrl
        retryAttempt = 0
        retryExhausted = false
        errorMessage = null
        forceSoftwareDecoder = false
        showSourcePicker = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid)
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                }
            },
            update = { view ->
                view.player = exoPlayer
            },
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent)
        ) {
            if (isLiveChannel) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(20.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(KBAccent)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "LIVE",
                        color = KBVoid,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            if (isBuffering && errorMessage == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = KBAccent)
                }
            }

            if (isRetrying && errorMessage != null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = KBAccent)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Reconnecting... (attempt ${retryAttempt + 1} of $MAX_RETRY_ATTEMPTS)",
                            color = KBTextHi,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            if (retryExhausted && errorMessage != null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .background(KBSurfaceRaised.copy(alpha = 0.96f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 28.dp, vertical = 22.dp)
                    ) {
                        Text(
                            text = if (isLiveChannel) "Channel unavailable" else "Playback failed",
                            color = KBTextHi,
                            style = MaterialTheme.typography.titleLarge
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = errorMessage.orEmpty(),
                            color = KBTextLo,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row {
                            KBCard(onClick = {
                                retryAttempt = 0
                                retryExhausted = false
                                errorMessage = null
                                manualRetryToken += 1
                            }) {
                                Text(
                                    text = "RETRY",
                                    color = KBTextHi,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                                )
                            }
                            if (sources.size > 1) {
                                Spacer(modifier = Modifier.width(12.dp))
                                KBCard(onClick = { showSourcePicker = true }) {
                                    Text(
                                        text = "CHANGE SOURCE",
                                        color = KBAccent,
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (showControls) {
                PlayerControlsOverlay(
                    isLiveChannel = isLiveChannel,
                    isPlaying = isPlaying,
                    sourceLabel = currentSourceLabel,
                    hasMultipleSources = sources.size > 1,
                    hasNextEpisode = false,
                    onPlayPause = {
                        exoPlayer.playWhenReady = !exoPlayer.playWhenReady
                    },
                    onSkipBack = {
                        exoPlayer.seekTo((exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L))
                    },
                    onSkipForward = {
                        val dur = exoPlayer.duration
                        val target = exoPlayer.currentPosition + 30_000L
                        exoPlayer.seekTo(if (dur > 0) target.coerceAtMost(dur) else target)
                    },
                    onNextEpisode = { },
                    onSourcePicker = { showSourcePicker = true },
                    onAudioPicker = { showAudioPicker = true },
                    onSubtitlePicker = { showSubtitlePicker = true },
                    onSpeedPicker = { showSpeedPicker = true },
                    onAspect = {
                        resizeModeIndex = (resizeModeIndex + 1) % 3
                    }
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Transparent)
            )
        }
    }

    if (showSourcePicker) {
        TrackPickerDialog(
            title = "SOURCES",
            onDismiss = { showSourcePicker = false }
        ) {
            items(sources) { stream ->
                val selected = stream.url == currentUrl
                PickerRow(
                    label = stream.label(),
                    selected = selected,
                    onClick = { switchToSource(stream) }
                )
            }
        }
    }

    if (showAudioPicker) {
        val tracks = exoPlayer.currentTracks
        TrackPickerDialog(
            title = "AUDIO",
            onDismiss = { showAudioPicker = false }
        ) {
            val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            items(audioGroups.size) { index ->
                val group = audioGroups[index]
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val selected = group.isTrackSelected(i)
                    PickerRow(
                        label = format.language?.uppercase() ?: "Track ${index + 1}",
                        selected = selected,
                        onClick = {
                            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                .buildUpon()
                                .setOverrideForType(
                                    androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, i)
                                )
                                .build()
                            showAudioPicker = false
                        }
                    )
                }
            }
        }
    }

    if (showSubtitlePicker) {
        val tracks = exoPlayer.currentTracks
        TrackPickerDialog(
            title = "SUBTITLES",
            onDismiss = { showSubtitlePicker = false }
        ) {
            item {
                PickerRow(
                    label = "OFF",
                    selected = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.none { it.isSelected },
                    onClick = {
                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                            .build()
                        showSubtitlePicker = false
                    }
                )
            }
            val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
            items(textGroups.size) { index ->
                val group = textGroups[index]
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val selected = group.isTrackSelected(i)
                    PickerRow(
                        label = format.language?.uppercase() ?: "Track ${index + 1}",
                        selected = selected,
                        onClick = {
                            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                .buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .setOverrideForType(
                                    androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, i)
                                )
                                .build()
                            showSubtitlePicker = false
                        }
                    )
                }
            }
        }
    }

    if (showSpeedPicker) {
        TrackPickerDialog(
            title = "SPEED",
            onDismiss = { showSpeedPicker = false }
        ) {
            items(SPEED_OPTIONS) { speed ->
                PickerRow(
                    label = "${speed}x",
                    selected = speed == playbackSpeed,
                    onClick = {
                        playbackSpeed = speed
                        exoPlayer.setPlaybackSpeed(speed)
                        showSpeedPicker = false
                    }
                )
            }
        }
    }
}

@Composable
private fun PlayerControlsOverlay(
    isLiveChannel: Boolean,
    isPlaying: Boolean,
    sourceLabel: String,
    hasMultipleSources: Boolean,
    hasNextEpisode: Boolean,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onNextEpisode: () -> Unit,
    onSourcePicker: () -> Unit,
    onAudioPicker: () -> Unit,
    onSubtitlePicker: () -> Unit,
    onSpeedPicker: () -> Unit,
    onAspect: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Transparent, KBVoid.copy(alpha = 0.85f))
                )
            ),
        verticalArrangement = Arrangement.Bottom
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = sourceLabel,
                color = KBTextLo,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!isLiveChannel) {
                ControlIconButton(icon = Icons.Filled.Replay10, contentDescription = "Back 10 seconds", onClick = onSkipBack)
                Spacer(modifier = Modifier.width(20.dp))
            }

            ControlIconButton(
                icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                onClick = onPlayPause,
                large = true
            )

            if (!isLiveChannel) {
                Spacer(modifier = Modifier.width(20.dp))
                ControlIconButton(icon = Icons.Filled.Forward30, contentDescription = "Forward 30 seconds", onClick = onSkipForward)

                if (hasNextEpisode) {
                    Spacer(modifier = Modifier.width(20.dp))
                    ControlIconButton(icon = Icons.Filled.SkipNext, contentDescription = "Next episode", onClick = onNextEpisode)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (hasMultipleSources) {
                ControlIconButton(icon = Icons.Filled.SwapHoriz, contentDescription = "Change source", onClick = onSourcePicker)
                Spacer(modifier = Modifier.width(16.dp))
            }
            if (!isLiveChannel) {
                ControlIconButton(icon = Icons.Filled.Audiotrack, contentDescription = "Audio track", onClick = onAudioPicker)
                Spacer(modifier = Modifier.width(16.dp))
                ControlIconButton(icon = Icons.Filled.ClosedCaption, contentDescription = "Subtitles", onClick = onSubtitlePicker)
                Spacer(modifier = Modifier.width(16.dp))
                ControlIconButton(icon = Icons.Filled.Speed, contentDescription = "Playback speed", onClick = onSpeedPicker)
                Spacer(modifier = Modifier.width(16.dp))
            }
            ControlIconButton(icon = Icons.Filled.AspectRatio, contentDescription = "Aspect ratio", onClick = onAspect)
            Spacer(modifier = Modifier.width(16.dp))
            ControlIconButton(icon = Icons.Filled.Settings, contentDescription = "Settings", onClick = { })
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
private fun ControlIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    large: Boolean = false
) {
    KBCard(
        onClick = onClick,
        modifier = Modifier
            .clip(CircleShape)
            .size(if (large) 64.dp else 48.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = KBTextHi,
                modifier = Modifier.size(if (large) 32.dp else 22.dp)
            )
        }
    }
}

@Composable
private fun TrackPickerDialog(
    title: String,
    onDismiss: () -> Unit,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid.copy(alpha = 0.75f)),
        contentAlignment = Alignment.CenterEnd
    ) {
        Column(
            modifier = Modifier
                .width(320.dp)
                .padding(24.dp)
                .background(KBSurfaceRaised, RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Text(
                text = title,
                color = KBAccent,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(10.dp))
            LazyColumn(content = content)
            Spacer(modifier = Modifier.height(10.dp))
            KBCard(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "CLOSE", color = KBTextHi, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun PickerRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    KBCard(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(KBSurface)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                color = if (selected) KBAccent else KBTextHi,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

private fun parseHeaders(raw: String): Map<String, String> {
    if (raw.isBlank()) return emptyMap()

    return raw.split("\n")
        .mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isBlank()) return@mapNotNull null

            val separator = trimmed.indexOf(':')
            if (separator <= 0) return@mapNotNull null

            val key = trimmed.substring(0, separator).trim()
            val value = trimmed.substring(separator + 1).trim()

            if (key.isBlank() || value.isBlank()) null else key to value
        }
        .toMap()
}

fun encodeHeaders(headers: Map<String, String>): String {
    return headers.entries.joinToString("\n") { (key, value) ->
        "${key.trim()}: ${value.trim()}"
    }
}

fun appendHeadersToUrl(url: String, headers: Map<String, String>): String {
    if (headers.isEmpty()) return url
    val parsed = Uri.parse(url)
    val builder = parsed.buildUpon().clearQuery()
    parsed.queryParameterNames.forEach { name ->
        parsed.getQueryParameters(name).forEach { value ->
            builder.appendQueryParameter(name, value)
        }
    }
    headers.forEach { (key, value) -> builder.appendQueryParameter(key, value) }
    return builder.build().toString()
}
