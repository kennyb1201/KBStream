package com.kennyb1201.kbstream.ui.player

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
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
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val isPlayingFlow = remember { MutableStateFlow(false) }
    val currentPositionFlow = remember { MutableStateFlow(0L) }
    val durationFlow = remember { MutableStateFlow(0L) }
    val isBufferingFlow = remember { MutableStateFlow(false) }

    val historyId = remember(parentId, season, episode, episodeStreamId) {
        when {
            !episodeStreamId.isNullOrBlank() -> episodeStreamId
            season != null && episode != null -> "$parentId:$season:$episode"
            else -> parentId
        }
    }

    val exoPlayer = remember(url, streamHeaders) {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(streamHeaders["User-Agent"] ?: streamHeaders["user-agent"] ?: "VLC/3.0.20 LibVLC/3.0.20")

        val extraHeaders = streamHeaders
            .filterKeys { !it.equals("User-Agent", ignoreCase = true) }
            .filterValues { it.isNotBlank() }

        if (extraHeaders.isNotEmpty()) {
            httpFactory.setDefaultRequestProperties(extraHeaders)
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(httpFactory)

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(url))
                if (startPositionMs > 0L) seekTo(startPositionMs)
                playWhenReady = true
                prepare()
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
        streamHeaders
    ) {
        val handler = CoroutineExceptionHandler { _, throwable ->
            errorMessage = "History save failed: ${throwable.message}"
            Log.e("PLAYER_HISTORY", "History save failed", throwable)
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + handler)
        val dao = WatchHistoryDatabase.getInstance(context).watchHistoryDao()

        var lastKnownDurationMs: Long? = null
        var hasEnded = false

        suspend fun saveProgress(reason: String, forceCompleted: Boolean = false) {
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
                errorMessage = buildString {
                    append("Playback error: ")
                    append(error.errorCodeName)
                    error.message?.takeIf { it.isNotBlank() }?.let {
                        append(" — ")
                        append(it)
                    }
                    if (streamHeaders.isNotEmpty()) {
                        append(" (headers sent: ")
                        append(streamHeaders.keys.joinToString())
                        append(")")
                    }
                }
                Log.e("PLAYER", "Playback error for url=$url headers=$streamHeaders", error)
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

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        errorMessage?.let {
            Text(
                text = it,
                modifier = Modifier.padding(24.dp)
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
