package com.kennyb1201.kbstream.ui.player

import android.net.Uri
import android.os.Bundle
import android.util.Log
import java.util.concurrent.TimeUnit
import android.view.LayoutInflater
import android.view.ViewGroup
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
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapHoriz
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
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.R
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
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

private const val PERIODIC_SAVE_INTERVAL_MS = 5_000L
private const val MIN_RESUME_POSITION_MS = 10_000L
private const val COMPLETION_THRESHOLD_RATIO = 0.95f
private const val EXTRA_HEADERS = "stream_headers"

private enum class IntroDbMarkerType(
    val buttonLabel: String
) {
    Intro("SKIP INTRO"),
    Recap("SKIP RECAP"),
    Outro("SKIP OUTRO"),
    Credits("SKIP CREDITS"),
    Preview("SKIP PREVIEW")
}

private data class IntroDbStamp(
    val startMs: Long,
    val endMs: Long,
    val type: IntroDbMarkerType
)

private val introDbHttpClient = OkHttpClient.Builder()
    .callTimeout(5, TimeUnit.SECONDS)
    .build()

private fun JSONObject.readIntroDbStamp(
    type: IntroDbMarkerType
): IntroDbStamp? {
    fun readMillis(
        millisKey: String,
        secondsKey: String
    ): Long {
        val millis = optLong(millisKey, -1L)
        if (millis >= 0L) return millis

        val seconds = optDouble(secondsKey, -1.0)
        return if (seconds >= 0.0) {
            (seconds * 1_000.0).toLong()
        } else {
            -1L
        }
    }

    val startMs = readMillis("start_ms", "start_sec")
    val endMs = readMillis("end_ms", "end_sec")

    return if (startMs >= 0L && endMs > startMs) {
        IntroDbStamp(
            startMs = startMs,
            endMs = endMs,
            type = type
        )
    } else {
        null
    }
}

private fun JSONObject.readIntroDbArray(
    key: String,
    type: IntroDbMarkerType
): List<IntroDbStamp> {
    val markers = optJSONArray(key) ?: return emptyList()

    return buildList {
        for (index in 0 until markers.length()) {
            markers.optJSONObject(index)
                ?.readIntroDbStamp(type)
                ?.let { add(it) }
        }
    }
}

private fun fetchIntroDbJson(url: String): JSONObject? {
    return runCatching {
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        introDbHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return@use null
            }

            JSONObject(response.body?.string().orEmpty())
        }
    }.getOrElse { error ->
        Log.w("INTRO_DB", "IntroDB lookup failed", error)
        null
    }
}

private fun normalizeIntroDbStamps(
    markers: List<IntroDbStamp>
): List<IntroDbStamp> {
    return markers
        .distinctBy { marker ->
            Triple(marker.type, marker.startMs, marker.endMs)
        }
        .sortedWith(
            compareBy<IntroDbStamp> { it.startMs }
                .thenBy { it.endMs }
        )
}

private suspend fun fetchIntroDbStamps(
    parentId: String,
    season: Int?,
    episode: Int?
): List<IntroDbStamp> = withContext(Dispatchers.IO) {
    val normalizedId = parentId.trim()
    val idQuery = when {
        normalizedId.startsWith("tt") &&
            normalizedId.drop(2).isNotEmpty() &&
            normalizedId.drop(2).all { it.isDigit() } ->
            "imdb_id=${Uri.encode(normalizedId)}"

        normalizedId.toLongOrNull() != null ->
            "tmdb_id=$normalizedId"

        else ->
            return@withContext emptyList()
    }

    val episodeQuery = if (season != null && episode != null) {
        "&season=$season&episode=$episode"
    } else {
        ""
    }

    // IntroDB is the primary source for episodic markers.
    val introDbMarkers = if (
        normalizedId.startsWith("tt") &&
            season != null &&
            episode != null
    ) {
        fetchIntroDbJson(
            "https://api.introdb.app/segments?" +
                "imdb_id=${Uri.encode(normalizedId)}" +
                "&season=$season&episode=$episode"
        )?.let { root ->
            listOfNotNull(
                root.optJSONObject("intro")
                    ?.readIntroDbStamp(IntroDbMarkerType.Intro),
                root.optJSONObject("recap")
                    ?.readIntroDbStamp(IntroDbMarkerType.Recap),
                root.optJSONObject("outro")
                    ?.readIntroDbStamp(IntroDbMarkerType.Outro)
            )
        }.orEmpty()
    } else {
        emptyList()
    }

    if (introDbMarkers.isNotEmpty()) {
        return@withContext normalizeIntroDbStamps(introDbMarkers)
    }

    // TheIntroDB is the fallback when IntroDB has no usable markers.
    val theIntroDbMarkers = fetchIntroDbJson(
        "https://api.theintrodb.org/v2/media?$idQuery$episodeQuery"
    )?.let { root ->
        root.readIntroDbArray("intro", IntroDbMarkerType.Intro) +
            root.readIntroDbArray("recap", IntroDbMarkerType.Recap) +
            root.readIntroDbArray("credits", IntroDbMarkerType.Credits) +
            root.readIntroDbArray("preview", IntroDbMarkerType.Preview)
    }.orEmpty()

    return@withContext normalizeIntroDbStamps(theIntroDbMarkers)
}

private const val MAX_RETRY_ATTEMPTS = 6

private val RETRY_BACKOFF_MS = listOf(
    1_000L,
    2_000L,
    4_000L,
    8_000L,
    16_000L,
    30_000L
)

private val SPEED_OPTIONS = listOf(
    0.5f,
    0.75f,
    1f,
    1.25f,
    1.5f,
    2f
)

class PlayerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra("stream_url")
        
        val audioUrl = intent.getStringExtra("audio_url")

        if (url.isNullOrBlank()) {
            finish()
            return
        }

        val parentId =
            intent.getStringExtra("parent_id") ?: ""

        val parentType =
            intent.getStringExtra("parent_type") ?: ""

        val season =
            intent.getIntExtra("season", -1)
                .takeIf { it >= 0 }

        val episode =
            intent.getIntExtra("episode", -1)
                .takeIf { it >= 0 }

        val episodeStreamId =
            intent.getStringExtra("episode_stream_id")

        val itemName =
            intent.getStringExtra("item_name") ?: ""

        val itemPoster =
            intent.getStringExtra("item_poster")

        val startPositionMs =
            intent.getLongExtra(
                "start_position_ms",
                0L
            )

        val streamHeaders =
            intent.getStringExtra(
                EXTRA_HEADERS
            ).orEmpty()

        setContent {
            PlayerScreen(
                url = url,
                audioUrl = audioUrl,
                parentId = parentId,
                parentType = parentType,
                season = season,
                episode = episode,
                episodeStreamId = episodeStreamId,
                itemName = itemName,
                itemPoster = itemPoster,
                startPositionMs = startPositionMs,
                streamHeaders =
                    parseHeaders(streamHeaders),
                sources = emptyList()
            )
        }
    }
}

private fun resolveMimeType(
    url: String
): String? {
    val lower = url.lowercase()

    return when {
        ".m3u8" in lower ->
            MimeTypes.APPLICATION_M3U8

        ".mpd" in lower ->
            MimeTypes.APPLICATION_MPD

        ".ts" in lower ->
            MimeTypes.VIDEO_MP2T

        ".mp4" in lower ->
            MimeTypes.VIDEO_MP4

        ".mkv" in lower ->
            MimeTypes.VIDEO_MATROSKA

        else ->
            null
    }
}

private fun isLikelyRetryable(
    error: PlaybackException
): Boolean {
    return when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_TIMEOUT,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> true

        else -> false
    }
}

private fun Stream.label(): String {
    return title?.takeIf { it.isNotBlank() }
        ?: name?.takeIf { it.isNotBlank() }
        ?: "Unknown source"
}

@Composable
fun PlayerScreen(
    url: String,
    audioUrl: String? = null,
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

    val isLiveChannel =
        parentType == "channel"

    var currentUrl by remember(url) {
        mutableStateOf(url)
    }

    var currentAudioUrl by remember(url, audioUrl) {
    mutableStateOf(audioUrl)
    }

    var currentSourceLabel by remember(
        url,
        sources
    ) {
        mutableStateOf(
            sources
                .firstOrNull {
                    it.url == url
                }
                ?.label()
                ?: "Source 1"
        )
    }

    var carryPositionMs by remember {
        mutableStateOf(startPositionMs)
    }

    var errorMessage by remember {
        mutableStateOf<String?>(null)
    }

    var isRetrying by remember {
        mutableStateOf(false)
    }

    var retryAttempt by remember(
        currentUrl
    ) {
        mutableIntStateOf(0)
    }

    var retryExhausted by remember(
        currentUrl
    ) {
        mutableStateOf(false)
    }

    var manualRetryToken by remember {
        mutableIntStateOf(0)
    }

    var forceSoftwareDecoder by remember(
        currentUrl
    ) {
        mutableStateOf(false)
    }

    var showControls by remember {
        mutableStateOf(true)
    }

    var showSourcePicker by remember {
        mutableStateOf(false)
    }

    var showAudioPicker by remember {
        mutableStateOf(false)
    }

    var showSubtitlePicker by remember {
        mutableStateOf(false)
    }

    var showSpeedPicker by remember {
        mutableStateOf(false)
    }

    var playbackSpeed by remember {
        mutableStateOf(1f)
    }

    var resizeModeIndex by remember {
        mutableIntStateOf(0)
    }

    val isPlayingFlow = remember {
        MutableStateFlow(false)
    }

    val isBufferingFlow = remember {
        MutableStateFlow(false)
    }

    val isPlaying by
        isPlayingFlow.collectAsState()

    val isBuffering by
        isBufferingFlow.collectAsState()

    val historyId = remember(
        parentId,
        season,
        episode,
        episodeStreamId
    ) {
        when {
            !episodeStreamId.isNullOrBlank() ->
                episodeStreamId

            season != null &&
                episode != null ->
                "$parentId:$season:$episode"

            else ->
                parentId
        }
    }

    val exoPlayer = remember(
        currentUrl,
        currentAudioUrl,
        streamHeaders,
        manualRetryToken,
        forceSoftwareDecoder
    ) {
        val httpFactory =
            DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(
                    true
                )
                .setConnectTimeoutMs(
                    15_000
                )
                .setReadTimeoutMs(
                    15_000
                )
                .setUserAgent(
                    streamHeaders["User-Agent"]
                        ?: streamHeaders["user-agent"]
                        ?: "VLC/3.0.20 LibVLC/3.0.20"
                )

        val extraHeaders =
            streamHeaders
                .filterKeys {
                    !it.equals(
                        "User-Agent",
                        ignoreCase = true
                    )
                }
                .filterValues {
                    it.isNotBlank()
                }

        if (extraHeaders.isNotEmpty()) {
            httpFactory
                .setDefaultRequestProperties(
                    extraHeaders
                )
        }

        val mediaSourceFactory =
            DefaultMediaSourceFactory(
                httpFactory,
                DefaultExtractorsFactory()
            )

        val mimeType =
            resolveMimeType(currentUrl)

        val mediaItemBuilder =
            MediaItem.Builder()
                .setUri(currentUrl)

        if (mimeType != null) {
            mediaItemBuilder
                .setMimeType(mimeType)
        }

        val renderersFactory =
            DefaultRenderersFactory(context)
                .setExtensionRendererMode(
                    if (forceSoftwareDecoder) {
                        DefaultRenderersFactory
                            .EXTENSION_RENDERER_MODE_PREFER
                    } else {
                        DefaultRenderersFactory
                            .EXTENSION_RENDERER_MODE_ON
                    }
                )
                .setEnableDecoderFallback(true)

         ExoPlayer.Builder(context, renderersFactory)
    .setMediaSourceFactory(mediaSourceFactory)
    .build()
    .apply {
        val selectedAudioUrl = currentAudioUrl

if (!selectedAudioUrl.isNullOrBlank()) {
    val videoSource =
        ProgressiveMediaSource.Factory(httpFactory)
            .createMediaSource(
                mediaItemBuilder.build()
            )

    val audioSource =
        ProgressiveMediaSource.Factory(httpFactory)
            .createMediaSource(
                MediaItem.fromUri(selectedAudioUrl)
            )

    setMediaSource(
        MergingMediaSource(
            videoSource,
            audioSource
        )
    )
} else {
    setMediaItem(
        mediaItemBuilder.build()
    )
}

        if (!isLiveChannel && carryPositionMs > 0L) {
            seekTo(carryPositionMs)
        }

        setPlaybackSpeed(playbackSpeed)
        playWhenReady = true
        prepare()
    }
    }

    var introDbStamps by remember(
        parentId,
        season,
        episode,
        parentType
    ) {
        mutableStateOf(emptyList<IntroDbStamp>())
    }

    LaunchedEffect(
        parentId,
        parentType,
        season,
        episode
    ) {
        introDbStamps = emptyList()

        if (!isLiveChannel) {
            introDbStamps = fetchIntroDbStamps(
                parentId = parentId,
                season = season,
                episode = episode
            )

            if (introDbStamps.isNotEmpty()) {
                Log.d(
                    "INTRO_DB",
                    "Loaded ${introDbStamps.size} intro stamp(s)"
                )
            }
        }
    }

    var activeIntroStamp by remember(
        parentId,
        season,
        episode,
        parentType
    ) {
        mutableStateOf<IntroDbStamp?>(null)
    }

    LaunchedEffect(exoPlayer, introDbStamps) {
        while (true) {
            delay(250L)

            val positionMs = exoPlayer.currentPosition
            val nextActiveStamp = introDbStamps.firstOrNull { stamp ->
                positionMs >= stamp.startMs &&
                    positionMs < stamp.endMs
            }

            if (nextActiveStamp != activeIntroStamp) {
                activeIntroStamp = nextActiveStamp
            }
        }
    }

    LaunchedEffect(
        exoPlayer,
        errorMessage,
        retryExhausted
    ) {
        if (
            errorMessage != null &&
            !retryExhausted &&
            !isRetrying
        ) {
            if (
                retryAttempt <
                MAX_RETRY_ATTEMPTS
            ) {
                isRetrying = true

                if (
                    retryAttempt == 2 &&
                    !forceSoftwareDecoder
                ) {
                    forceSoftwareDecoder = true
                }

                delay(
                    RETRY_BACKOFF_MS.getOrElse(
                        retryAttempt
                    ) {
                        RETRY_BACKOFF_MS.last()
                    }
                )

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
            delay(6_000L)

            if (
                !showSourcePicker &&
                !showAudioPicker &&
                !showSubtitlePicker &&
                !showSpeedPicker
            ) {
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
        val handler =
            CoroutineExceptionHandler {
                    _,
                    throwable ->
                Log.e(
                    "PLAYER_HISTORY",
                    "History save failed",
                    throwable
                )
            }

        val scope = CoroutineScope(
            SupervisorJob() +
                Dispatchers.Main.immediate +
                handler
        )

        val dao =
            WatchHistoryDatabase
                .getInstance(context)
                .watchHistoryDao()

        var lastKnownDurationMs:
            Long? = null

        var hasEnded = false

        suspend fun saveProgress(
            reason: String,
            forceCompleted: Boolean = false
        ) {
            if (isLiveChannel) return

            val (
                position,
                rawDuration
            ) = withContext(
                Dispatchers.Main.immediate
            ) {
                exoPlayer.currentPosition
                    .coerceAtLeast(0L) to
                    exoPlayer.duration
            }

            val resolvedDuration =
                when {
                    rawDuration ==
                        C.TIME_UNSET ->
                        null

                    rawDuration <= 0L ->
                        null

                    else ->
                        rawDuration
                }

            if (
                resolvedDuration != null
            ) {
                lastKnownDurationMs =
                    resolvedDuration
            }

            val durationToSave =
                resolvedDuration
                    ?: lastKnownDurationMs

            if (
                parentId.isBlank() ||
                historyId.isBlank() ||
                durationToSave == null
            ) {
                Log.d(
                    "PLAYER_HISTORY",
                    "Skip save ($reason): " +
                        "missing id or duration"
                )
                return
            }

            if (
                position <
                MIN_RESUME_POSITION_MS &&
                !forceCompleted
            ) {
                return
            }

            val isCompleted =
                forceCompleted ||
                    position >=
                    (
                        durationToSave *
                            COMPLETION_THRESHOLD_RATIO
                    ).toLong()

            val safePosition =
                if (isCompleted) {
                    0L
                } else {
                    position.coerceAtMost(
                        durationToSave
                    )
                }

            val now =
                System.currentTimeMillis()

            val existing =
                withContext(Dispatchers.IO) {
                    dao.getById(historyId)
                }

            val entry =
                WatchHistoryEntity(
                    id = historyId,
                    parentId = parentId,
                    type = parentType,
                    name = itemName,
                    poster = itemPoster,
                    streamUrl = currentUrl,
                    season = season,
                    episode = episode,
                    episodeStreamId =
                        episodeStreamId,
                    positionMs = safePosition,
                    durationMs = durationToSave,
                    updatedAt = now,
                    isCompleted = isCompleted,
                    completedAt =
                        if (isCompleted) {
                            existing?.completedAt
                                ?: now
                        } else {
                            null
                        }
                )

            withContext(Dispatchers.IO) {
                dao.upsert(entry)
            }
        }

        val playerListener =
            object : Player.Listener {

                override fun onIsPlayingChanged(
                    isPlayingNow: Boolean
                ) {
                    isPlayingFlow.value =
                        isPlayingNow
                }

                override fun onPlaybackStateChanged(
                    playbackState: Int
                ) {
                    isBufferingFlow.value =
                        playbackState ==
                            Player.STATE_BUFFERING

                    if (
                        playbackState ==
                        Player.STATE_READY
                    ) {
                        errorMessage = null
                        retryAttempt = 0
                        retryExhausted = false
                    }

                    if (
                        playbackState ==
                            Player.STATE_ENDED &&
                        !hasEnded
                    ) {
                        hasEnded = true

                        scope.launch {
                            saveProgress(
                                reason = "ended",
                                forceCompleted = true
                            )
                        }
                    }
                }

                override fun onPlayerError(
                    error: PlaybackException
                ) {
                    val retryable =
                        isLikelyRetryable(error)

                    errorMessage =
                        buildString {
                            append(
                                error.errorCodeName
                            )

                            error.message
                                ?.takeIf {
                                    it.isNotBlank()
                                }
                                ?.let {
                                    append(" — ")
                                    append(it)
                                }

                            if (
                                forceSoftwareDecoder
                            ) {
                                append(
                                    " (software decoder)"
                                )
                            }
                        }

                    if (!retryable) {
                        retryExhausted = true
                    }

                    Log.e(
                        "PLAYER",
                        "Playback error: " +
                            "url=$currentUrl " +
                            "retryable=$retryable",
                        error
                    )
                }
            }

        exoPlayer.addListener(
            playerListener
        )

        val periodicSaveJob =
            scope.launch {
                while (true) {
                    delay(
                        PERIODIC_SAVE_INTERVAL_MS
                    )

                    if (hasEnded) {
                        break
                    }

                    try {
                        saveProgress(
                            reason = "periodic"
                        )
                    } catch (
                        error: Exception
                    ) {
                        Log.e(
                            "PLAYER_HISTORY",
                            "Periodic save failed",
                            error
                        )
                    }
                }
            }

        onDispose {
            exoPlayer.removeListener(
                playerListener
            )

            periodicSaveJob.cancel()

            runBlocking {
                try {
                    saveProgress(
                        reason = "dispose",
                        forceCompleted =
                            hasEnded
                    )
                } catch (
                    error: Exception
                ) {
                    Log.e(
                        "PLAYER_HISTORY",
                        "Final save failed",
                        error
                    )
                }
            }

            scope.cancel()
            exoPlayer.release()
        }
    }

    fun switchToSource(
        stream: Stream
    ) {
        val newUrl =
            stream.url ?: return

        if (newUrl == currentUrl) {
            return
        }

        carryPositionMs =
            if (isLiveChannel) {
                0L
            } else {
                exoPlayer.currentPosition
                    .coerceAtLeast(0L)
            }

        currentSourceLabel =
            stream.label()

        currentUrl = newUrl
currentAudioUrl = stream.audioUrl
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

                val playerView =
                    LayoutInflater
                        .from(ctx)
                        .inflate(
                            R.layout.player_view,
                            null,
                            false
                        ) as PlayerView

                playerView.layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )

                playerView.resizeMode =
                    AspectRatioFrameLayout
                        .RESIZE_MODE_FIT

                playerView
                    .setShutterBackgroundColor(
                        android.graphics.Color.BLACK
                    )

                playerView
                    .setKeepContentOnPlayerReset(
                        true
                    )

                playerView.player =
                    exoPlayer

                playerView
            },
            update = { playerView ->

                playerView.resizeMode =
                    when (resizeModeIndex) {
                        1 ->
                            AspectRatioFrameLayout
                                .RESIZE_MODE_ZOOM

                        2 ->
                            AspectRatioFrameLayout
                                .RESIZE_MODE_FILL

                        else ->
                            AspectRatioFrameLayout
                                .RESIZE_MODE_FIT
                    }

                if (
                    playerView.player !==
                    exoPlayer
                ) {
                    playerView.player =
                        exoPlayer
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier.fillMaxSize()
        ) {

            if (isLiveChannel) {
                Row(
                    modifier = Modifier
                        .align(
                            Alignment.TopStart
                        )
                        .padding(20.dp)
                        .clip(
                            RoundedCornerShape(6.dp)
                        )
                        .background(KBAccent)
                        .padding(
                            horizontal = 10.dp,
                            vertical = 4.dp
                        )
                ) {
                    Text(
                        text = "LIVE",
                        color = KBVoid,
                        style =
                            MaterialTheme
                                .typography
                                .labelSmall
                    )
                }
            }

            if (
                isBuffering &&
                errorMessage == null
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment =
                        Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = KBAccent
                    )
                }
            }

            if (
                isRetrying &&
                errorMessage != null
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment =
                        Alignment.Center
                ) {
                    Column(
                        horizontalAlignment =
                            Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            color = KBAccent
                        )

                        Spacer(
                            modifier =
                                Modifier.height(12.dp)
                        )

                        Text(
                            text =
                                "Reconnecting... " +
                                    "(${retryAttempt + 1}/" +
                                    "$MAX_RETRY_ATTEMPTS)",
                            color = KBTextHi,
                            style =
                                MaterialTheme
                                    .typography
                                    .bodyMedium
                        )
                    }
                }
            }

            if (
                retryExhausted &&
                errorMessage != null
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment =
                        Alignment.Center
                ) {
                    Column(
                        horizontalAlignment =
                            Alignment.CenterHorizontally,
                        modifier = Modifier
                            .background(
                                KBSurfaceRaised.copy(
                                    alpha = 0.96f
                                ),
                                RoundedCornerShape(16.dp)
                            )
                            .padding(
                                horizontal = 28.dp,
                                vertical = 22.dp
                            )
                    ) {
                        Text(
                            text =
                                if (isLiveChannel) {
                                    "Channel unavailable"
                                } else {
                                    "Playback failed"
                                },
                            color = KBTextHi,
                            style =
                                MaterialTheme
                                    .typography
                                    .titleLarge
                        )

                        Spacer(
                            modifier =
                                Modifier.height(8.dp)
                        )

                        Text(
                            text =
                                errorMessage.orEmpty(),
                            color = KBTextLo,
                            style =
                                MaterialTheme
                                    .typography
                                    .bodyMedium
                        )

                        Spacer(
                            modifier =
                                Modifier.height(16.dp)
                        )

                        Row {

                            KBCard(
                                onClick = {
                                    retryAttempt = 0
                                    retryExhausted = false
                                    errorMessage = null
                                    manualRetryToken += 1
                                }
                            ) {
                                Text(
                                    text = "RETRY",
                                    color = KBTextHi,
                                    style =
                                        MaterialTheme
                                            .typography
                                            .titleMedium,
                                    modifier =
                                        Modifier.padding(
                                            horizontal = 20.dp,
                                            vertical = 10.dp
                                        )
                                )
                            }

                            if (sources.size > 1) {
                                Spacer(
                                    modifier =
                                        Modifier.width(12.dp)
                                )

                                KBCard(
                                    onClick = {
                                        showSourcePicker =
                                            true
                                    }
                                ) {
                                    Text(
                                        text =
                                            "CHANGE SOURCE",
                                        color = KBAccent,
                                        style =
                                            MaterialTheme
                                                .typography
                                                .titleMedium,
                                        modifier =
                                            Modifier.padding(
                                                horizontal = 20.dp,
                                                vertical = 10.dp
                                            )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            activeIntroStamp?.let { stamp ->
                KBCard(
                    onClick = {
                        val durationMs = exoPlayer.duration
                        val targetMs = if (
                            durationMs > 0L &&
                            durationMs != C.TIME_UNSET
                        ) {
                            stamp.endMs.coerceAtMost(durationMs)
                        } else {
                            stamp.endMs
                        }

                        exoPlayer.seekTo(targetMs)
                        activeIntroStamp = null
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 120.dp)
                ) {
                    Text(
                        text = stamp.type.buttonLabel,
                        color = KBTextHi,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(
                            horizontal = 24.dp,
                            vertical = 12.dp
                        )
                    )
                }
            }

            if (showControls) {
                PlayerControlsOverlay(
                    isLiveChannel =
                        isLiveChannel,
                    isPlaying =
                        isPlaying,
                    sourceLabel =
                        currentSourceLabel,
                    hasMultipleSources =
                        sources.size > 1,
                    hasNextEpisode =
                        false,
                    onPlayPause = {
                        exoPlayer.playWhenReady =
                            !exoPlayer.playWhenReady
                    },
                    onSkipBack = {
                        exoPlayer.seekTo(
                            (
                                exoPlayer.currentPosition -
                                    10_000L
                            ).coerceAtLeast(0L)
                        )
                    },
                    onSkipForward = {
                        val duration =
                            exoPlayer.duration

                        val target =
                            exoPlayer.currentPosition +
                                30_000L

                        exoPlayer.seekTo(
                            if (duration > 0L) {
                                target.coerceAtMost(
                                    duration
                                )
                            } else {
                                target
                            }
                        )
                    },
                    onNextEpisode = {},
                    onSourcePicker = {
                        showSourcePicker = true
                    },
                    onAudioPicker = {
                        showAudioPicker = true
                    },
                    onSubtitlePicker = {
                        showSubtitlePicker = true
                    },
                    onSpeedPicker = {
                        showSpeedPicker = true
                    },
                    onAspect = {
                        resizeModeIndex =
                            (resizeModeIndex + 1) % 3
                    }
                )
            }
        }
    }

    if (showSourcePicker) {
        TrackPickerDialog(
            title = "SOURCES",
            onDismiss = {
                showSourcePicker = false
            }
        ) {
            items(sources) { stream ->
                PickerRow(
                    label = stream.label(),
                    selected =
                        stream.url == currentUrl,
                    onClick = {
                        switchToSource(stream)
                    }
                )
            }
        }
    }

    if (showAudioPicker) {
        val tracks =
            exoPlayer.currentTracks

        TrackPickerDialog(
            title = "AUDIO",
            onDismiss = {
                showAudioPicker = false
            }
        ) {
            val audioGroups =
                tracks.groups.filter {
                    it.type ==
                        C.TRACK_TYPE_AUDIO
                }

            items(audioGroups.size) { groupIndex ->

                val group =
                    audioGroups[groupIndex]

                for (
                    trackIndex in
                    0 until group.length
                ) {
                    val format =
                        group.getTrackFormat(
                            trackIndex
                        )

                    PickerRow(
                        label =
                            format.language
                                ?.uppercase()
                                ?: "Track " +
                                    (groupIndex + 1),
                        selected =
                            group.isTrackSelected(
                                trackIndex
                            ),
                        onClick = {

                            exoPlayer
                                .trackSelectionParameters =
                                exoPlayer
                                    .trackSelectionParameters
                                    .buildUpon()
                                    .setOverrideForType(
                                        androidx.media3.common
                                            .TrackSelectionOverride(
                                                group.mediaTrackGroup,
                                                trackIndex
                                            )
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
        val tracks =
            exoPlayer.currentTracks

        TrackPickerDialog(
            title = "SUBTITLES",
            onDismiss = {
                showSubtitlePicker = false
            }
        ) {
            item {
                PickerRow(
                    label = "OFF",
                    selected =
                        tracks.groups
                            .filter {
                                it.type ==
                                    C.TRACK_TYPE_TEXT
                            }
                            .none {
                                it.isSelected
                            },
                    onClick = {

                        exoPlayer
                            .trackSelectionParameters =
                            exoPlayer
                                .trackSelectionParameters
                                .buildUpon()
                                .setTrackTypeDisabled(
                                    C.TRACK_TYPE_TEXT,
                                    true
                                )
                                .build()

                        showSubtitlePicker = false
                    }
                )
            }

            val subtitleGroups =
                tracks.groups.filter {
                    it.type ==
                        C.TRACK_TYPE_TEXT
                }

            items(subtitleGroups.size) { groupIndex ->

                val group =
                    subtitleGroups[groupIndex]

                for (
                    trackIndex in
                    0 until group.length
                ) {
                    val format =
                        group.getTrackFormat(
                            trackIndex
                        )

                    PickerRow(
                        label =
                            format.language
                                ?.uppercase()
                                ?: "Track " +
                                    (groupIndex + 1),
                        selected =
                            group.isTrackSelected(
                                trackIndex
                            ),
                        onClick = {

                            exoPlayer
                                .trackSelectionParameters =
                                exoPlayer
                                    .trackSelectionParameters
                                    .buildUpon()
                                    .setTrackTypeDisabled(
                                        C.TRACK_TYPE_TEXT,
                                        false
                                    )
                                    .setOverrideForType(
                                        androidx.media3.common
                                            .TrackSelectionOverride(
                                                group.mediaTrackGroup,
                                                trackIndex
                                            )
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
            onDismiss = {
                showSpeedPicker = false
            }
        ) {
            items(SPEED_OPTIONS) { speed ->

                PickerRow(
                    label = "${speed}x",
                    selected =
                        speed == playbackSpeed,
                    onClick = {
                        playbackSpeed = speed
                        exoPlayer
                            .setPlaybackSpeed(speed)
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
                androidx.compose.ui.graphics
                    .Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            Color.Transparent,
                            KBVoid.copy(
                                alpha = 0.85f
                            )
                        )
                    )
            ),
        verticalArrangement =
            Arrangement.Bottom
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 6.dp),
            horizontalArrangement =
                Arrangement.SpaceBetween
        ) {
            Text(
                text = sourceLabel,
                color = KBTextLo,
                style =
                    MaterialTheme
                        .typography
                        .bodySmall
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 20.dp,
                    vertical = 16.dp
                ),
            horizontalArrangement =
                Arrangement.Center,
            verticalAlignment =
                Alignment.CenterVertically
        ) {

            if (!isLiveChannel) {
                ControlIconButton(
                    icon =
                        Icons.Filled.Replay10,
                    contentDescription =
                        "Back 10 seconds",
                    onClick =
                        onSkipBack
                )

                Spacer(
                    modifier =
                        Modifier.width(20.dp)
                )
            }

            ControlIconButton(
                icon =
                    if (isPlaying) {
                        Icons.Filled.Pause
                    } else {
                        Icons.Filled.PlayArrow
                    },
                contentDescription =
                    if (isPlaying) {
                        "Pause"
                    } else {
                        "Play"
                    },
                onClick =
                    onPlayPause,
                large = true
            )

            if (!isLiveChannel) {
                Spacer(
                    modifier =
                        Modifier.width(20.dp)
                )

                ControlIconButton(
                    icon =
                        Icons.Filled.Forward30,
                    contentDescription =
                        "Forward 30 seconds",
                    onClick =
                        onSkipForward
                )

                if (hasNextEpisode) {
                    Spacer(
                        modifier =
                            Modifier.width(20.dp)
                    )

                    ControlIconButton(
                        icon =
                            Icons.Filled.SkipNext,
                        contentDescription =
                            "Next episode",
                        onClick =
                            onNextEpisode
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 20.dp,
                    vertical = 12.dp
                ),
            horizontalArrangement =
                Arrangement.Center,
            verticalAlignment =
                Alignment.CenterVertically
        ) {

            if (hasMultipleSources) {
                ControlIconButton(
                    icon =
                        Icons.Filled.SwapHoriz,
                    contentDescription =
                        "Change source",
                    onClick =
                        onSourcePicker
                )

                Spacer(
                    modifier =
                        Modifier.width(16.dp)
                )
            }

            if (!isLiveChannel) {
                ControlIconButton(
                    icon =
                        Icons.Filled.Audiotrack,
                    contentDescription =
                        "Audio track",
                    onClick =
                        onAudioPicker
                )

                Spacer(
                    modifier =
                        Modifier.width(16.dp)
                )

                ControlIconButton(
                    icon =
                        Icons.Filled.ClosedCaption,
                    contentDescription =
                        "Subtitles",
                    onClick =
                        onSubtitlePicker
                )

                Spacer(
                    modifier =
                        Modifier.width(16.dp)
                )

                ControlIconButton(
                    icon =
                        Icons.Filled.Speed,
                    contentDescription =
                        "Playback speed",
                    onClick =
                        onSpeedPicker
                )

                Spacer(
                    modifier =
                        Modifier.width(16.dp)
                )
            }

            ControlIconButton(
                icon =
                    Icons.Filled.AspectRatio,
                contentDescription =
                    "Aspect ratio",
                onClick =
                    onAspect
            )

            Spacer(
                modifier =
                    Modifier.width(16.dp)
            )

            ControlIconButton(
                icon =
                    Icons.Filled.Settings,
                contentDescription =
                    "Settings",
                onClick = {}
            )
        }

        Spacer(
            modifier =
                Modifier.height(20.dp)
        )
    }
}

@Composable
private fun ControlIconButton(
    icon:
        androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    large: Boolean = false
) {
    KBCard(
        onClick = onClick,
        modifier = Modifier
            .clip(CircleShape)
            .size(
                if (large) {
                    64.dp
                } else {
                    48.dp
                }
            )
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment =
                Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription =
                    contentDescription,
                tint = KBTextHi,
                modifier = Modifier.size(
                    if (large) {
                        32.dp
                    } else {
                        22.dp
                    }
                )
            )
        }
    }
}

@Composable
private fun TrackPickerDialog(
    title: String,
    onDismiss: () -> Unit,
    content:
        androidx.compose.foundation.lazy
            .LazyListScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                KBVoid.copy(alpha = 0.75f)
            ),
        contentAlignment =
            Alignment.CenterEnd
    ) {
        Column(
            modifier = Modifier
                .width(320.dp)
                .padding(24.dp)
                .background(
                    KBSurfaceRaised,
                    RoundedCornerShape(16.dp)
                )
                .padding(16.dp)
        ) {
            Text(
                text = title,
                color = KBAccent,
                style =
                    MaterialTheme
                        .typography
                        .titleMedium
            )

            Spacer(
                modifier =
                    Modifier.height(10.dp)
            )

            LazyColumn(
                content = content
            )

            Spacer(
                modifier =
                    Modifier.height(10.dp)
            )

            KBCard(
                onClick = onDismiss,
                modifier =
                    Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            vertical = 10.dp
                        ),
                    contentAlignment =
                        Alignment.Center
                ) {
                    Text(
                        text = "CLOSE",
                        color = KBTextHi,
                        style =
                            MaterialTheme
                                .typography
                                .labelLarge
                    )
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
                .padding(
                    horizontal = 14.dp,
                    vertical = 12.dp
                ),
            horizontalArrangement =
                Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                color =
                    if (selected) {
                        KBAccent
                    } else {
                        KBTextHi
                    },
                style =
                    MaterialTheme
                        .typography
                        .bodyMedium
            )
        }
    }
}

private fun parseHeaders(
    raw: String
): Map<String, String> {
    if (raw.isBlank()) {
        return emptyMap()
    }

    return raw
        .lineSequence()
        .mapNotNull { line ->
            val trimmed = line.trim()

            if (trimmed.isBlank()) {
                return@mapNotNull null
            }

            val separator =
                trimmed.indexOf(':')

            if (separator <= 0) {
                return@mapNotNull null
            }

            val key =
                trimmed
                    .substring(0, separator)
                    .trim()

            val value =
                trimmed
                    .substring(separator + 1)
                    .trim()

            if (
                key.isBlank() ||
                value.isBlank()
            ) {
                null
            } else {
                key to value
            }
        }
        .toMap()
}

fun encodeHeaders(
    headers: Map<String, String>
): String {
    return headers.entries.joinToString(
        separator = System.lineSeparator()
    ) { (key, value) ->
        "${key.trim()}: ${value.trim()}"
    }
}

fun appendHeadersToUrl(
    url: String,
    headers: Map<String, String>
): String {
    if (headers.isEmpty()) {
        return url
    }

    val parsed = Uri.parse(url)

    val builder =
        parsed
            .buildUpon()
            .clearQuery()

    parsed.queryParameterNames.forEach { name ->
        parsed
            .getQueryParameters(name)
            .forEach { value ->
                builder.appendQueryParameter(
                    name,
                    value
                )
            }
    }

    headers.forEach { (key, value) ->
        builder.appendQueryParameter(
            key,
            value
        )
    }

    return builder
        .build()
        .toString()
}
