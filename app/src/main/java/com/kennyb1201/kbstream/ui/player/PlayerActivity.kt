package com.kennyb1201.kbstream.ui.player

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
import androidx.media3.exoplayer.ExoPlayer
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

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
                startPositionMs = startPositionMs
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
    startPositionMs: Long
) {
    val context = LocalContext.current
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val historyId = remember(parentId, season, episode, episodeStreamId) {
        when {
            !episodeStreamId.isNullOrBlank() -> episodeStreamId
            season != null && episode != null -> "$parentId:$season:$episode"
            else -> parentId
        }
    }

    val exoPlayer = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            if (startPositionMs > 0L) {
                seekTo(startPositionMs)
            }
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
        startPositionMs
    ) {
        val handler = CoroutineExceptionHandler { _, throwable ->
            errorMessage = "History save failed: ${throwable.message}"
            Log.e("PLAYER_HISTORY", "History save failed", throwable)
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + handler)
        val dao = WatchHistoryDatabase.getInstance(context).watchHistoryDao()

        var lastKnownDurationMs: Long? = null

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

            if (resolvedDuration != null) {
                lastKnownDurationMs = resolvedDuration
            }

            val durationToSave = resolvedDuration ?: lastKnownDurationMs

            if (parentId.isBlank() || historyId.isBlank() || durationToSave == null) {
                Log.e(
                    "PLAYER_HISTORY",
                    "Skipped save, reason=$reason, parentId=$parentId, historyId=$historyId, rawDuration=$rawDuration, durationToSave=$durationToSave"
                )
                return
            }

            if (position <= 0L && !forceCompleted) {
                Log.d("PLAYER_HISTORY", "Skipped save, reason=$reason, position=$position")
                return
            }

            val completionThresholdMs = (durationToSave * 0.95f).toLong()
            val isCompleted = forceCompleted || position >= completionThresholdMs
            val safePosition = if (isCompleted) durationToSave else position.coerceAtMost(durationToSave)
            val now = System.currentTimeMillis()

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
                completedAt = if (isCompleted) now else null
            )

            
Log.e(
    "KBStream",
    "save history historyId=${entry.id} parentId=${entry.parentId} type=${entry.type} season=${entry.season} episode=${entry.episode} episodeStreamId=${entry.episodeStreamId} positionMs=${entry.positionMs} isCompleted=${entry.isCompleted}"
)
            withContext(Dispatchers.IO) {
                dao.upsert(entry)
            }

            Log.d(
                "PLAYER_HISTORY",
                "Saved history reason=$reason id=$historyId parentId=$parentId completed=$isCompleted position=$safePosition duration=$durationToSave season=$season episode=$episode"
            )
        }

        val playerListener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                errorMessage = "Playback error: ${error.errorCodeName} — ${error.message}"
                Log.e("PLAYER", "Playback error", error)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    scope.launch {
                        try {
                            saveProgress("ended", forceCompleted = true)
                        } catch (e: Exception) {
                            Log.e("PLAYER_HISTORY", "Ended save failed", e)
                        }
                    }
                }
            }
        }

        exoPlayer.addListener(playerListener)

        val periodicSaveJob = scope.launch {
            while (true) {
                delay(5000)
                try {
                    saveProgress("periodic")
                } catch (e: Exception) {
                    Log.e("PLAYER_HISTORY", "Periodic save failed", e)
                }
            }
        }

        onDispose {
            exoPlayer.removeListener(playerListener)
            periodicSaveJob.cancel()

            runBlocking {
                try {
                    saveProgress("dispose")
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
