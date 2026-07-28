package com.kennyb1201.kbstream.ui.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PlayerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra("stream_url") ?: return finish()
        val itemId = intent.getStringExtra("item_id") ?: ""
        val itemType = intent.getStringExtra("item_type") ?: ""
        val itemName = intent.getStringExtra("item_name") ?: ""
        val itemPoster = intent.getStringExtra("item_poster")
        val startPositionMs = intent.getLongExtra("start_position_ms", 0L)

        setContent {
            PlayerScreen(
                url = url,
                itemId = itemId,
                itemType = itemType,
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
    itemId: String,
    itemType: String,
    itemName: String,
    itemPoster: String?,
    startPositionMs: Long
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val dao = remember2 { WatchHistoryDatabase.getInstance(context).watchHistoryDao() }

    val exoPlayer = remember2 {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            if (startPositionMs > 0) seekTo(startPositionMs)
            playWhenReady = true
            prepare()
        }
    }

    DisposableEffect(Unit) {
        val scope = CoroutineScope(Dispatchers.IO)
        val job = scope.launch {
            while (true) {
                delay(5000)
                val position = exoPlayer.currentPosition
                val duration = exoPlayer.duration.coerceAtLeast(1L)
                if (position > 0) {
                    dao.upsert(
                        WatchHistoryEntity(
                            id = itemId,
                            type = itemType,
                            name = itemName,
                            poster = itemPoster,
                            positionMs = position,
                            durationMs = duration,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
            }
        }
        onDispose {
            job.cancel()
            exoPlayer.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = true
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun <T> remember2(calculation: () -> T): T =
    androidx.compose.runtime.remember { calculation() }
