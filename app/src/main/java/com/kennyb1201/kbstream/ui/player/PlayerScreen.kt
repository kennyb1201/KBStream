package com.kennyb1201.kbstream.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView

@Composable
fun PlayerScreen(
    videoUrl: String,
    imdbId: String?,
    mediaType: String?,
    onBackPressed: () -> Unit,
    viewModel: PlayerViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    DisposableEffect(videoUrl) {
        viewModel.playStream(videoUrl, imdbId, mediaType)
        onDispose {
            viewModel.markAsWatchedIfNeeded()
        }
    }

    BackHandler {
        viewModel.markAsWatchedIfNeeded()
        viewModel.exoPlayer.stop()
        onBackPressed()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = viewModel.exoPlayer
                    useController = true
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
