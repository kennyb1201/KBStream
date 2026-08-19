package com.kennyb1201.kbstream.ui.player

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import com.kennyb1201.kbstream.ui.components.KBCard
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
                streamHeaders = parseHeaders(streamHeaders)
            )
        }
    }
}

/**
 * Only set an explicit MIME type when the URL extension is unambiguous.
 * Leaving it null for extensionless IPTV links (very common with Xtream-style
 * panels) lets Media3's DefaultExtractorsFactory sniff the container
 * (usually raw MPEG-TS) instead of forcing HLS parsing, which was causing
 * the black screen.
 */
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
    streamHeaders: Map<String, String> = emptyMap()
) {
    val context = LocalContext.current
    val isLiveChannel = parentType == "channel"

    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isRetrying by remember { mutableStateOf(false) }
    var retryAttempt by remember(url) { mutableIntStateOf(0) }
    var retryExhausted by remember(url) { mutableStateOf(false) }
    var manualRetryToken by remember { mutableStateOf(0) }
    // When a hardware decoder rejects a stream, we drop to extension/software
    // decoders (requires the media3-decoder-ffmpeg module, see notes below).
    var forceSoftwareDecoder by remember(url) { mutableStateOf(false) }

    val isPlayingFlow = remember { MutableStateFlow(false) }
    val currentPositionFlow = remember { MutableStateFlow(0L) }
    val durationFlow = remember { MutableStateFlow(0L) }
    val isBufferingFlow = remember { MutableStateFlow(false) }
    val isBuffering by isBufferingFlow.collectAsState()

    val historyId = remember(parentId, season, episode, episodeStreamId) {
        when {
            !episodeStreamId.isNullOrBlank() -> episodeStreamId
            season != null && episode != null -> "$parentId:$season:$episode"
            else -> parentId
        }
    }

    val exoPlayer = remember(url, streamHeaders, manualRetryToken, forceSoftwareDecoder) {
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

        // Tolerant TS extraction: many IPTV panels emit non-standard timestamps
        // or missing PMT/PAT timing. These flags make TsExtractor more forgiving.
        val extractorsFactory = DefaultExtractorsFactory()
            .setTsExtractorFlags(TsExtractor.FLAG_ALLOW_NON_IDR_KEYFRAMES)

        val mediaSourceFactory = DefaultMediaSourceFactory(httpFactory, extractorsFactory)

        val mimeType = resolveMimeType(url)
        val mediaItemBuilder = MediaItem.Builder().setUri(url)
        if (mimeType != null) mediaItemBuilder.setMimeType(mimeType)
        val mediaItem = mediaItemBuilder.build()

        // EXTENSION_RENDERER_MODE_PREFER lets a software decoder extension
        // (e.g. media3-decoder-ffmpeg) take over automatically when the
        // hardware decoder can't handle a stream, instead of failing outright.
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
                if (!isLiveChannel && startPositionMs > 0L) seekTo(startPositionMs)
                playWhenReady = true
                prepare()
            }
    }

    LaunchedEffect(exoPlayer, errorMessage, retryExhausted) {
        val error = errorMessage
        if (error != null && !retryExhausted && !isRetrying) {
            if (retryAttempt < MAX_RETRY_ATTEMPTS) {
                isRetrying = true
                // After a couple of failed attempts, try forcing the
                // software/extension decoder path in case hardware decoding
                // is what's failing.
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

    DisposableEffect(
        url,
        parentId,
        parentType,
        season,
        episode,
        episodeStreamId,
        itemName,
        itemPoster,
        startPositionMs,
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
                streamUrl = url,
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
                    "Playback error for url=$url headers=$streamHeaders retryable=$retryable softwareDecoder=$forceSoftwareDecoder",
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

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (isLiveChannel) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(20.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFE53935))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "LIVE",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        if (isBuffering && errorMessage == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color.White)
            }
        }

        if (isRetrying && errorMessage != null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Reconnecting... (attempt ${retryAttempt + 1} of $MAX_RETRY_ATTEMPTS)",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        if (retryExhausted && errorMessage != null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 28.dp, vertical = 22.dp)
                ) {
                    Text(
                        text = if (isLiveChannel) "Channel unavailable" else "Playback failed",
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMessage.orEmpty(),
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    KBCard(onClick = {
                        retryAttempt = 0
                        retryExhausted = false
                        errorMessage = null
                        manualRetryToken += 1
                    }) {
                        Text(
                            text = "RETRY",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                        )
                    }
                }
            }
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
    return headers.entries.joinToString("\n") { (key, value) -> "${key.trim()}: ${value.trim()}" }
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
