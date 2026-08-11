package com.kennyb1201.kbstream.ui.player

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.kennyb1201.kbstream.data.watched.WatchedStatusRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val watchedStatusRepository = WatchedStatusRepository(application)

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(application).build().apply {
        playWhenReady = true
    }

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private var currentImdbId: String? = null
    private var currentMediaType: String? = typeOrNull@{
        // Default media type tracking
        null
    }
    private var resolvedMediaType: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            if (exoPlayer.isPlaying) {
                _currentPosition.value = exoPlayer.currentPosition
                _duration.value = exoPlayer.duration.coerceAtLeast(0L)
            }
            handler.postDelayed(this, 1000L)
        }
    }

    init {
        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (isPlaying) {
                    handler.post(progressRunnable)
                } else {
                    handler.removeCallbacks(progressRunnable)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _isBuffering.value = (playbackState == Player.STATE_BUFFERING)
                if (playbackState == Player.STATE_READY) {
                    _duration.value = exoPlayer.duration.coerceAtLeast(0L)
                    _currentPosition.value = exoPlayer.currentPosition
                }
            }
        })
    }

    fun playStream(url: String, imdbId: String?, mediaType: String?) {
        currentImdbId = imdbId
        resolvedMediaType = mediaType

        val mediaItem = MediaItem.fromUri(url)
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.play()
    }

    fun markAsWatchedIfNeeded() {
        val imdbId = currentImdbId
        val mediaType = resolvedMediaType
        if (!imdbId.isNullOrBlank() && !mediaType.isNullOrBlank()) {
            viewModelScope.launch {
                watchedStatusRepository.markWatched(imdbId, mediaType)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        handler.removeCallbacks(progressRunnable)
        exoPlayer.release()
    }
}
