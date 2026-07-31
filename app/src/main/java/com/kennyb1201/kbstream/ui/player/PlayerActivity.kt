package com.kennyb1201.kbstream.ui.player

import android.os.Bundle
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            if (startPositionMs > 0) seekTo(startPositionMs)
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    errorMessage = "Playback error: ${error.errorCodeName} — ${error.message}"
                }
            })
            prepare()
        }
    }

    DisposableEffect(Unit) {
        val handler = CoroutineExceptionHandler { _, throwable ->
            errorMessage = "History save failed: ${throwable.message}"
        }
        val scope = CoroutineScope(Dispatchers.IO + handler)
        val job = scope.launch {
            val dao = WatchHistoryDatabase.getInstance(context).watchHistoryDao()
            while (true) {
                delay(5000)
                try {
                    val position = exoPlayer.currentPosition
                    val duration = exoPlayer.duration.coerceAtLeast(1L)
                    if (position > 0 && parentId.isNotBlank()) {
                        dao.upsert(
                            WatchHistoryEntity(
                                id = parentId,
                                type = parentType,
                                name = itemName,
                                poster = itemPoster,
                                streamUrl = url,
                                season = season,
                                episode = episode,
                                episodeStreamId = episodeStreamId,
                                positionMs = position,
                                durationMs = duration,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }
                } catch (e: Exception) {
                    // don't let a single failed save kill the loop or the process
                }
            }
        }
        onDispose {
            job.cancel()
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
            Text(it, modifier = Modifier.padding(24.dp))
        }
    }
}
