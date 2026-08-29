package com.kennyb1201.kbstream.ui.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.kennyb1201.kbstream.R
import com.kennyb1201.kbstream.ui.components.*
import com.kennyb1201.kbstream.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val MAX_RETRY_ATTEMPTS = 3

data class IntroStamp(val startMs: Long, val endMs: Long, val type: IntroType)

enum class IntroType(val buttonLabel: String) {
    SKIP_INTRO("SKIP INTRO"),
    SKIP_RECAP("SKIP RECAP")
}

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    // Note: Bind your actual ExoPlayer instance here based on your intent extras/viewmodel setup
    private lateinit var exoPlayer: Player

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            KBStreamTheme {
                PlayerContent(
                    exoPlayer = exoPlayer,
                    itemName = intent.getStringExtra("ITEM_NAME") ?: "Media Stream",
                    clearLogoUrl = intent.getStringExtra("CLEAR_LOGO"),
                    overview = intent.getStringExtra("OVERVIEW"),
                    cast = intent.getStringArrayListExtra("CAST") ?: emptyList(),
                    isLiveChannel = intent.getBooleanExtra("IS_LIVE", false),
                    season = if (intent.hasExtra("SEASON")) intent.getIntExtra("SEASON", 1) else null,
                    episode = if (intent.hasExtra("EPISODE")) intent.getIntExtra("EPISODE", 1) else null,
                    sources = intent.getStringArrayListExtra("SOURCES") ?: listOf("Default Source"),
                    currentSourceLabel = intent.getStringExtra("CURRENT_SOURCE") ?: "Default Source",
                    onSourceChange = { /* Handle source change */ },
                    onNavigateActor = { /* Handle actor navigation */ },
                    onBack = { finish() }
                )
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerContent(
    exoPlayer: Player,
    itemName: String,
    clearLogoUrl: String?,
    overview: String?,
    cast: List<String>?,
    isLiveChannel: Boolean,
    season: Int?,
    episode: Int?,
    sources: List<String>,
    currentSourceLabel: String,
    onSourceChange: (String) -> Unit,
    onNavigateActor: (String) -> Unit,
    onBack: () -> Unit
) {
    var showControls by remember { mutableStateOf(false) }
    var showSourcePicker by remember { mutableStateOf(false) }
    var showAudioPicker by remember { mutableStateOf(false) }
    var showSubtitlePicker by remember { mutableStateOf(false) }
    var showSpeedPicker by remember { mutableStateOf(false) }
    var resizeModeIndex by remember { mutableStateOf(0) } // 0: Fit, 1: Zoom, 2: Fill

    var isBuffering by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(exoPlayer.isPlaying) }
    var currentPositionMs by remember { mutableStateOf(exoPlayer.currentPosition) }
    var durationMs by remember { mutableStateOf(exoPlayer.duration) }

    var errorMessage by remember { mutableStateOf<String?>(null) }
    var retryAttempt by remember { mutableStateOf(0) }
    var isRetrying by remember { mutableStateOf(false) }
    var retryExhausted by remember { mutableStateOf(false) }
    var manualRetryToken by remember { mutableStateOf(0) }

    var activeIntroStamp by remember { mutableStateOf<IntroStamp?>(null) }

    // Player polling ticker
    LaunchedEffect(exoPlayer, manualRetryToken) {
        val player = exoPlayer
        while (isActive) {
            isPlaying = player.isPlaying
            currentPositionMs = player.currentPosition.coerceAtLeast(0L)
            val dur = player.duration
            durationMs = if (dur != C.TIME_UNSET && dur > 0L) dur else 0L

            val state = player.playbackState
            isBuffering = state == Player.STATE_BUFFERING

            val error = player.playerError
            if (error != null) {
                errorMessage = error.localizedMessage ?: "Unknown playback error"
                if (retryAttempt < MAX_RETRY_ATTEMPTS) {
                    isRetrying = true
                    delay(2000L * (retryAttempt + 1))
                    retryAttempt++
                    player.prepare()
                    player.play()
                    isRetrying = false
                } else {
                    retryExhausted = true
                }
            } else {
                if (errorMessage != null && !retryExhausted) {
                    errorMessage = null
                    retryAttempt = 0
                }
            }

            delay(200L)
        }
    }

    // Auto-hide controls timer
    LaunchedEffect(showControls) {
        if (showControls) {
            delay(5000L)
            showControls = false
        }
    }

    val focusRequester = remember { FocusRequester() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                    when (event.nativeKeyEvent.keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                        android.view.KeyEvent.KEYCODE_ENTER,
                        android.view.KeyEvent.KEYCODE_BUTTON_SELECT,
                        android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
                        android.view.KeyEvent.KEYCODE_MEDIA_PAUSE,
                        android.view.KeyEvent.KEYCODE_DPAD_UP,
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (!showControls) {
                                showControls = true
                                true
                            } else {
                                false
                            }
                        }
                        android.view.KeyEvent.KEYCODE_BACK -> {
                            if (showControls) {
                                showControls = false
                                true
                            } else {
                                onBack()
                                true
                            }
                        }
                        else -> false
                    }
                } else {
                    false
                }
            }
    ) {
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }

        // 1. Video Surface Layer
        AndroidView(
            factory = { ctx ->
                val playerView = LayoutInflater.from(ctx)
                    .inflate(R.layout.player_view, null, false) as PlayerView
                playerView.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                playerView.setShutterBackgroundColor(android.graphics.Color.BLACK)
                playerView.setKeepContentOnPlayerReset(true)
                playerView.player = exoPlayer
                playerView
            },
            update = { playerView ->
                playerView.resizeMode = when (resizeModeIndex) {
                    1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
                if (playerView.player !== exoPlayer) {
                    playerView.player = exoPlayer
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. HUD / Indicators Layer
        Box(modifier = Modifier.fillMaxSize()) {
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
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = KBAccent)
                }
            }

            if (isRetrying && errorMessage != null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = KBAccent)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Reconnecting... (${retryAttempt + 1}/$MAX_RETRY_ATTEMPTS)",
                            color = KBTextHi,
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
                            .background(
                                KBSurfaceRaised.copy(alpha = 0.96f),
                                RoundedCornerShape(16.dp)
                            )
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
                            KBCard(
                                onClick = {
                                    retryAttempt = 0
                                    retryExhausted = false
                                    errorMessage = null
                                    manualRetryToken += 1
                                    exoPlayer.prepare()
                                    exoPlayer.play()
                                }
                            ) {
                                Text(
                                    text = "RETRY",
                                    color = KBTextHi,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                                )
                            }

                            if (sources.size > 1) {
                                Spacer(modifier = Modifier.width(12.dp))
                                KBCard(
                                    onClick = { showSourcePicker = true }
                                ) {
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
        }

        // 3. Guaranteed Top-Level Overlay Layer
        if (showControls) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(KBVoid.copy(alpha = 0.4f))
            ) {
                PlayerControlsOverlay(
                    isLiveChannel = isLiveChannel,
                    isPlaying = isPlaying,
                    sourceLabel = currentSourceLabel,
                    hasMultipleSources = sources.size > 1,
                    hasNextEpisode = season != null && episode != null,
                    itemName = itemName,
                    clearLogoUrl = clearLogoUrl,
                    overview = overview,
                    cast = cast,
                    currentPositionMs = currentPositionMs,
                    durationMs = durationMs,
                    onPlayPause = { exoPlayer.playWhenReady = !exoPlayer.playWhenReady },
                    onSeek = { targetMs -> exoPlayer.seekTo(targetMs) },
                    onSkipBack = {
                        exoPlayer.seekTo((exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L))
                    },
                    onSkipForward = {
                        val duration = exoPlayer.duration
                        val target = exoPlayer.currentPosition + 30_000L
                        exoPlayer.seekTo(if (duration > 0L) target.coerceAtMost(duration) else target)
                    },
                    onNextEpisode = {},
                    onSourcePicker = { showSourcePicker = true },
                    onAudioPicker = { showAudioPicker = true },
                    onSubtitlePicker = { showSubtitlePicker = true },
                    onSpeedPicker = { showSpeedPicker = true },
                    onAspect = { resizeModeIndex = (resizeModeIndex + 1) % 3 },
                    onNavigateActor = onNavigateActor
                )
            }
        }

        // Pickers / Modals
        if (showSourcePicker) {
            SourcePickerDialog(
                sources = sources,
                currentSource = currentSourceLabel,
                onSelectSource = { selected ->
                    onSourceChange(selected)
                    showSourcePicker = false
                },
                onDismiss = { showSourcePicker = false }
            )
        }

        if (showAudioPicker) {
            AudioTrackPickerDialog(
                exoPlayer = exoPlayer,
                onDismiss = { showAudioPicker = false }
            )
        }

        if (showSubtitlePicker) {
            SubtitlePickerDialog(
                exoPlayer = exoPlayer,
                onDismiss = { showSubtitlePicker = false }
            )
        }

        if (showSpeedPicker) {
            PlaybackSpeedDialog(
                exoPlayer = exoPlayer,
                onDismiss = { showSpeedPicker = false }
            )
        }
    }
}
