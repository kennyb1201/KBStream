package com.kennyb1201.kbstream.ui.player

import android.app.PictureInPictureParams
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import java.util.concurrent.TimeUnit
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.media3.session.MediaSession
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.kennyb1201.kbstream.R
import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import com.kennyb1201.kbstream.data.simkl.SimklRepository
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.settings.AppPreferences
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
private const val EXTRA_DRM_LICENSE_URL = "drm_license_url"
private const val EXTRA_DRM_HEADERS = "drm_headers"

/**
 * Friendly error messages for common ExoPlayer error codes.
 */
private fun friendlyErrorMessage(error: PlaybackException): String {
    return when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_TIMEOUT -> "No internet connection. Check your network and try again."
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The server returned an error. The stream may be unavailable."
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "This video format is not supported by your device."
        PlaybackException.ERROR_CODE_DRM_UNSPECIFIED,
        PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED -> "DRM license error. This content may require a valid license."
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> "Audio playback failed. Try restarting."
        else -> error.message?.takeIf { it.isNotBlank() }
            ?: error.errorCodeName
    }
}

data class PlayerCastMember(
    val id: Int,
    val name: String,
    val character: String?,
    val profilePath: String?
)

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

/**
 * Normalize raw pixel height into a human-friendly label:
 * 2160 → "4K", 1080 → "1080p", 720 → "720p", etc.
 * Falls back to "WxH" when height is unknown.
 */
internal fun normalizeResolution(width: Int, height: Int): String {
    if (width <= 0 && height <= 0) return "—"
    // Use the larger dimension for classification — this correctly
    // handles ultrawide content like 3840x1920 (should show "4K")
    // and tall/portrait content like 1920x3840.
    val maxDim = maxOf(width, height)
    return when {
        maxDim >= 3840 -> "4K"
        maxDim >= 2560 -> "1440p"
        maxDim >= 1920 -> "1080p"
        maxDim >= 1280 -> "720p"
        maxDim >= 854  -> "480p"
        maxDim >= 640  -> "360p"
        else           -> "${maxDim}p"
    }
}

/**
 * Normalize raw codec string into a friendly label:
 * "hev1.1.6.L150" → "H.265", "avc1.640028" → "H.264",
 * "vp09.00" → "VP9", "av01" → "AV1".
 */
internal fun normalizeCodec(codec: String?): String {
    if (codec.isNullOrBlank()) return "—"
    val lower = codec.lowercase()
    return when {
        // Video codecs — ExoPlayer reports these as codec strings like
        // "avc1.640028", "hvc1.1.6.L150.90", "hev1.2.4.L150", etc.
        lower.startsWith("avc") || lower.contains("h264") || lower.contains("h.264") -> "H.264"
        lower.startsWith("hev") || lower.startsWith("hvc") || lower.contains("h265") || lower.contains("h.265") -> "H.265"
        lower.startsWith("vp09") || lower.startsWith("vp9") -> "VP9"
        lower.startsWith("vp08") || lower.startsWith("vp8") -> "VP8"
        lower.startsWith("av01") || lower.startsWith("av1") -> "AV1"
        lower.startsWith("mp4a") || lower.startsWith("mp3") || lower.contains("aac") -> "AAC"
        lower.contains("ac-3") || lower.contains("ac3") -> "AC-3"
        lower.contains("ec-3") || lower.contains("eac3") -> "EAC3"
        lower.contains("opus") -> "Opus"
        lower.contains("vorbis") -> "Vorbis"
        lower.contains("flac") -> "FLAC"
        // MIME types — some addons report these instead of codec strings
        lower.contains("video/h264") || lower.contains("video/avc") -> "H.264"
        lower.contains("video/hevc") || lower.contains("video/h265") -> "H.265"
        lower.contains("video/vp9") -> "VP9"
        lower.contains("video/av01") || lower.contains("video/av1") -> "AV1"
        else -> codec.uppercase()
    }
}

class PlayerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on during playback
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val url = intent.getStringExtra("stream_url")
        val audioUrl = intent.getStringExtra("audio_url")

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
        val clearLogoUrl = intent.getStringExtra("clear_logo_url")
        val overview = intent.getStringExtra("item_overview")
        val startPositionMs = intent.getLongExtra("start_position_ms", 0L)
        val streamHeaders = intent.getStringExtra(EXTRA_HEADERS).orEmpty()
        val drmLicenseUrl = intent.getStringExtra(EXTRA_DRM_LICENSE_URL)
        val drmHeadersRaw = intent.getStringExtra(EXTRA_DRM_HEADERS).orEmpty()

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
                clearLogoUrl = clearLogoUrl,
                overview = overview,
                startPositionMs = startPositionMs,
                streamHeaders = parseHeaders(streamHeaders),
                drmLicenseUrl = drmLicenseUrl,
                drmHeaders = parseHeaders(drmHeadersRaw),
                sources = emptyList(),
                cast = emptyList(),
                onNavigateActor = { personId -> }
            )
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // PlayerScreen observes this via PiP state; the overlay auto-hides in PiP
    }
}

private fun resolveMimeType(url: String): String? {
    val lower = url.lowercase()
    // Strip query params and fragments for more reliable extension matching.
    val path = lower.substringBefore('?').substringBefore('#')
    return when {
        // Adaptive manifests — always preferred when detected
        ".m3u8" in path || ".m3u8/" in path -> MimeTypes.APPLICATION_M3U8
        ".mpd" in path -> MimeTypes.APPLICATION_MPD

        // Progressive containers
        ".mp4" in path || ".m4v" in path -> MimeTypes.VIDEO_MP4
        ".mkv" in path || ".webm" in path -> MimeTypes.VIDEO_MATROSKA
        ".ts" in path -> MimeTypes.VIDEO_MP2T
        ".flv" in path -> MimeTypes.VIDEO_FLV
        ".avi" in path -> MimeTypes.VIDEO_UNKNOWN // Force probe
        ".mov" in path -> MimeTypes.VIDEO_MP4 // MOV ≈ MP4 for ExoPlayer

        // Audio
        ".aac" in path -> MimeTypes.AUDIO_AAC
        ".mp3" in path -> MimeTypes.AUDIO_MPEG
        ".flac" in path -> MimeTypes.AUDIO_FLAC
        ".opus" in path -> MimeTypes.AUDIO_OPUS
        ".ogg" in path -> MimeTypes.AUDIO_OGG
        ".wav" in path -> MimeTypes.AUDIO_WAV

        // Common wrapper patterns — some addons wrap URL paths
        // like /video.mp4/path or /stream.m3u8?... — handle them
        path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
        path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
        path.endsWith(".mp4") -> MimeTypes.VIDEO_MP4
        path.endsWith(".mkv") -> MimeTypes.VIDEO_MATROSKA

        else -> null // Let ExoPlayer probe
    }
}

private fun isLikelyRetryable(error: PlaybackException): Boolean {
    return when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_TIMEOUT,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        PlaybackException.ERROR_CODE_DRM_UNSPECIFIED -> true
        else -> false
    }
}

private fun Stream.label(): String {
    return listOfNotNull(
        name?.takeIf { it.isNotBlank() },
        title?.takeIf { it.isNotBlank() },
        description?.takeIf { it.isNotBlank() }
    )
        .distinct()
        .joinToString("\n")
        .ifBlank { "Stream details unavailable" }
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
    episodeTitle: String? = null,
    itemName: String,
    itemPoster: String?,
    clearLogoUrl: String? = null,
    overview: String? = null,
    startPositionMs: Long,
    streamHeaders: Map<String, String> = emptyMap(),
    drmLicenseUrl: String? = null,
    drmHeaders: Map<String, String> = emptyMap(),
    sources: List<Stream> = emptyList(),
    cast: List<PlayerCastMember> = emptyList(),
    onNavigateActor: (Int) -> Unit = {},
    onNextEpisode: () -> Unit = {}
) {
    val context = LocalContext.current
    val isLiveChannel = parentType == "channel"

    var currentUrl by remember(url) { mutableStateOf(url) }
    var currentAudioUrl by remember(url, audioUrl) { mutableStateOf(audioUrl) }
    var currentSourceLabel by remember(url, sources) {
        mutableStateOf(
            sources.firstOrNull { it.url == url }?.label() ?: "Source 1"
        )
    }

    var carryPositionMs by remember { mutableStateOf(startPositionMs) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isRetrying by remember { mutableStateOf(false) }
    var retryAttempt by remember(currentUrl) { mutableIntStateOf(0) }
    var retryExhausted by remember(currentUrl) { mutableStateOf(false) }
    var manualRetryToken by remember { mutableIntStateOf(0) }
    var forceSoftwareDecoder by remember(currentUrl) { mutableStateOf(false) }
    // Overlay starts hidden (video first). It appears on pause / D-pad tap
    // and disappears immediately on play.
    var showControls by remember { mutableStateOf(false) }

    var showSourcePicker by remember { mutableStateOf(false) }
    var showAudioPicker by remember { mutableStateOf(false) }
    var showSubtitlePicker by remember { mutableStateOf(false) }
    var showSpeedPicker by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableStateOf(1f) }
    var resizeModeIndex by remember { mutableIntStateOf(AppPreferences.getDefaultAspectRatio(context)) }

    // PiP state
    val activity = context as? ComponentActivity
    var isInPiPMode by remember { mutableStateOf(false) }

    // Stream health state
    var streamWidth by remember { mutableIntStateOf(0) }
    var streamHeight by remember { mutableIntStateOf(0) }
    var streamBitrate by remember { mutableIntStateOf(0) }
    var streamCodec by remember { mutableStateOf<String?>(null) }

    // Settings panel
    var showSettingsPanel by remember { mutableStateOf(false) }
    var subtitleOffsetMs by remember { mutableIntStateOf(0) }
    var subtitleSize by remember { mutableIntStateOf(AppPreferences.getDefaultSubtitleSize(context)) }
    var showSubtitlePickerForFile by remember { mutableStateOf(false) }

    // External subtitle file
    var externalSubtitleUri by remember { mutableStateOf<String?>(null) }
    var externalSubtitleLabel by remember { mutableStateOf<String?>(null) }

    // Player settings
    var decoderMode by remember { mutableIntStateOf(AppPreferences.getDecoderMode(context)) } // 0=auto, 1=device, 2=ffmpeg
    var enableTunneling by remember { mutableStateOf(AppPreferences.getEnableTunneling(context)) }
    var subtitleBackground by remember { mutableIntStateOf(AppPreferences.getDefaultSubtitleBackground(context)) }
    var bufferMode by remember { mutableIntStateOf(AppPreferences.getDefaultBufferMode(context)) }
    var autoPlayNext by remember { mutableStateOf(AppPreferences.getAutoPlayNext(context)) }

    val isPlayingFlow = remember { MutableStateFlow(false) }
    val isBufferingFlow = remember { MutableStateFlow(false) }
    val isPlaying by isPlayingFlow.collectAsState()
    val isBuffering by isBufferingFlow.collectAsState()

    var currentPositionMs by remember { mutableStateOf(startPositionMs) }
    var durationMs by remember { mutableStateOf(0L) }

    val historyId = remember(parentId, season, episode, episodeStreamId) {
        when {
            !episodeStreamId.isNullOrBlank() -> episodeStreamId
            season != null && episode != null -> "$parentId:$season:$episode"
            else -> parentId
        }
    }

    val exoPlayer = remember(
        currentUrl,
        currentAudioUrl,
        streamHeaders,
        manualRetryToken,
        forceSoftwareDecoder,
        retryAttempt,
        decoderMode,
        drmLicenseUrl,
        drmHeaders,
        enableTunneling,
        bufferMode
    ) {
        // Nuvio-style HTTP factory: generous timeouts for slow CDN
        // edges, cross-protocol redirects (http → https), and a UA
        // string that many CDN providers whitelist.
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)  // longer for slow CDNs
            .setReadTimeoutMs(20_000)     // longer for non-faststart MP4s
            .setUserAgent(
                streamHeaders["User-Agent"]
                    ?: streamHeaders["user-agent"]
                    ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/125.0.0.0 Safari/537.36"
            )

        val extraHeaders = streamHeaders
            .filterKeys { !it.equals("User-Agent", ignoreCase = true) }
            .filterValues { it.isNotBlank() }

        if (extraHeaders.isNotEmpty()) {
            httpFactory.setDefaultRequestProperties(extraHeaders)
        }

        val drmLicenseUrlLocal = drmLicenseUrl

        // Build DRM session manager if a license URL is provided,
        // then attach it to the media source factory.
        val drmSessionManager = if (!drmLicenseUrlLocal.isNullOrBlank()) {
            val drmCallback = androidx.media3.exoplayer.drm.HttpMediaDrmCallback(
                drmLicenseUrlLocal,
                DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(15_000)
                    .setReadTimeoutMs(15_000)
            )
            Log.i("PLAYER_DRM", "Widevine DRM configured: licenseUrl=$drmLicenseUrlLocal")
            androidx.media3.exoplayer.drm.DefaultDrmSessionManager.Builder()
                .build(drmCallback)
        } else null

        val mediaSourceFactory = DefaultMediaSourceFactory(
            httpFactory,
            DefaultExtractorsFactory()
        ).apply {
            setLiveTargetOffsetMs(3_000L)
            if (drmSessionManager != null) {
                setDrmSessionManagerProvider { drmSessionManager }
            }
        }

        // Nuvio-style: on retry attempts >= 3, skip the mime hint and
        // let ExoPlayer probe the raw bytes — this fixes containers
        // that don't match their file extension (common with addon proxies).
        val mimeType = if (retryAttempt < 3) resolveMimeType(currentUrl) else null
        val mediaItemBuilder = MediaItem.Builder().setUri(currentUrl)
        if (mimeType != null) {
            mediaItemBuilder.setMimeType(mimeType)
        }

        // ---------- Buffered playback for smooth playback ----------
        // Balanced mode: 25 s / 120 s gives 4K and high-bitrate content
        // enough runway to absorb network jitter without rebuffering.
        // The old 15 s / 60 s was too aggressive for 4K HDR streams.
        // Low-latency mode: 2.5 s / 10 s for live/fast-start content.
        val bufferDurations = if (bufferMode == 1) {
            intArrayOf(2_500, 10_000, 1_500, 3_000) // low latency
        } else {
            intArrayOf(25_000, 120_000, 10_000, 15_000) // balanced (4K-ready)
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                bufferDurations[0], bufferDurations[1],
                bufferDurations[2], bufferDurations[3]
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // ---------- Renderers ----------
        // Extension renderer mode ON means MediaCodec hardware decoders
        // are tried first; the FFmpeg extension (libde265 / libavcodec)
        // is used only when no HW decoder matches (e.g. DV7 HEVC10 on
        // a device without a DV-capable SoC).  Decoder fallback allows
        // graceful downgrade instead of an immediate error.
        //
        // Nuvio-style: when forceSoftwareDecoder is true (triggered by
        // codec error or HDR green-tint), PREFER means FFmpeg handles
        // the entire decode pipeline including color space conversion,
        // which fixes green-tint DV7 content on non-DV-capable SoCs.
        // Decoder mode:
        // 0 = Auto: HW first, FFmpeg fallback (EXTENSION_RENDERER_MODE_ON)
        // 1 = FFmpeg only: software for everything (EXTENSION_RENDERER_MODE_PREFER)
        // forceSoftwareDecoder overrides to PREFER on retry when HW fails.
        val extensionMode = when {
            forceSoftwareDecoder -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            decoderMode == 1     -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            else                 -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
        }
        // Tunneled playback: routes audio/video through a single tunnel
        // session for tighter A/V sync. Especially useful on HDR content
        // connected to an AVR via HDMI.
        val audioSink = if (enableTunneling) {
            Log.i("PLAYER_TUNNEL", "Tunneled playback enabled")
            androidx.media3.exoplayer.audio.DefaultAudioSink.Builder()
                .setTunneling(true)
                .build()
        } else null

        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(extensionMode)
            .setEnableDecoderFallback(true)
            .apply {
                if (audioSink != null) {
                    setAudioSink(audioSink)
                }
            }
            // Enable rendering immediately on audio completion — prevents
            // the brief blank screen that occurs when video finishes
            // rendering before the next item is ready.

        ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setHandleAudioBecomingNoisy(true) // auto-pause on BT disconnect
            .setWakeMode(C.WAKE_MODE_NETWORK)  // keep Wi-Fi alive while buffering
            .setLivePlaybackSpeedControl(
                // Nuvio-style: smooth speed control for live content to
                // prevent constant A/V desync corrections on live streams.
                androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl.Builder()
                    .setFallbackMinPlaybackSpeed(0.97f)
                    .setFallbackMaxPlaybackSpeed(1.03f)
                    .setMinUpdateIntervalMs(100)
                    .setProportionalControlFactor(0.1f)
                    .build()
            )
            .build()
            .apply {
                // Single audio-attributes call — handles audio focus and
                // routes to the correct output (e.g. HDMI ARC, BT, TV speakers).
                val audioAttrs = AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build()
                setAudioAttributes(audioAttrs, true)

                // External audio track merge (separate AC-3/EAC3 streams).
                val selectedAudioUrl = currentAudioUrl
                if (!selectedAudioUrl.isNullOrBlank()) {
                    val videoSource = ProgressiveMediaSource.Factory(httpFactory)
                        .createMediaSource(mediaItemBuilder.build())
                    val audioSource = ProgressiveMediaSource.Factory(httpFactory)
                        .createMediaSource(MediaItem.fromUri(selectedAudioUrl))
                    setMediaSource(MergingMediaSource(videoSource, audioSource))
                } else {
                    setMediaItem(mediaItemBuilder.build())
                }

                if (!isLiveChannel && carryPositionMs > 0L) {
                    seekTo(carryPositionMs)
                }

                setPlaybackSpeed(playbackSpeed)
                playWhenReady = true
                prepare()
            }
    }

    // ---------- Dolby Vision / HDR detection ----------
    // After prepare(), the player's tracks are resolved.  We listen for
    // video format changes to log the codec, color space, and whether
    // tunnelled playback is active — useful for diagnosing green-tint
    // or washed-out DV7 files where the tone-mapping path matters.
    exoPlayer.addListener(object : Player.Listener {
        override fun onTracksChanged(
            tracks: androidx.media3.common.Tracks
        ) {
            for (group in tracks.groups) {
                for (i in 0 until group.length) {
                    val fmt = group.getTrackFormat(i)
                    if (group.type == C.TRACK_TYPE_VIDEO) {
                        val codec = fmt.codecs.orEmpty()
                        val colorInfo = fmt.colorInfo
                        // Update stream health state for the overlay
                        streamWidth = fmt.width
                        streamHeight = fmt.height
                        streamBitrate = fmt.bitrate
                        streamCodec = codec.ifBlank { null }

                        Log.i(
                            "PLAYER_CODEC",
                            buildString {
                                append("video codec=$codec")
                                append(" mime=${fmt.sampleMimeType}")
                                append(" ${fmt.width}x${fmt.height}")
                                if (fmt.bitrate > 0) append(" ${fmt.bitrate / 1_000}kbps")
                                colorInfo?.let { c ->
                                    append(" color=${c.colorSpace}/${c.colorTransfer}/${c.colorRange}")
                                    append(" hdr=${c.hdrStaticInfo != null}")
                                }
                            }
                        )
                        // If the file reports a BT.2020 color space or PQ/HLG
                        // transfer function but the device decoder is not tone-
                        // mapping (green tint), force a software-decoder retry
                        // so the FFmpeg extension handles the colour pipeline.
                        val isHdr = colorInfo?.let {
                            it.colorTransfer == C.COLOR_TRANSFER_ST2084 ||
                                it.colorTransfer == C.COLOR_TRANSFER_HLG ||
                                it.colorSpace == C.COLOR_SPACE_BT2020
                        } ?: false
                        if (isHdr && !forceSoftwareDecoder) {
                            // Nuvio approach: HDR/DV content on hardware decoders
                            // without proper tone-mapping produces green-tint or
                            // washed-out colors.  Auto-retry with software decoder
                            // on the first failed attempt so the FFmpeg extension
                            // handles the colour pipeline correctly.
                            Log.w(
                                "PLAYER_CODEC",
                                "HDR/DV content detected with color space " +
                                    "${colorInfo?.colorSpace}/${colorInfo?.colorTransfer} — " +
                                    "will use FFmpeg for tone-mapping"
                            )
                        }
                    }
                }
            }
        }
    })

    // ---------- MediaSession ----------
    // Exposes playback state to the system: lock-screen controls, notification
    // transport, and Google Assistant / voice commands.
    DisposableEffect(exoPlayer) {
        val session = MediaSession.Builder(context, exoPlayer).build()
        onDispose {
            session.release()
        }
    }

    // ---------- PiP auto-enter ----------
    // When the user navigates away (Home key on TV), automatically enter PiP
    // if the device supports it.  This covers the common TV pattern of
    // pressing Home while a video plays.
    LaunchedEffect(activity, isPlaying) {
        if (activity != null && isPlaying && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.addOnPictureInPictureModeChangedListener(
                androidx.core.util.Consumer { info ->
                    isInPiPMode = info.isInPictureInPictureMode
                }
            )
        }
    }

    // Poll position/duration for the whole player lifetime (not only while
    // the overlay is up). Keeping the loop always-on avoids recomposing the
    // overlay's slider from a cold start every time controls reappear, which
    // showed up as visible lag when pausing.
    LaunchedEffect(exoPlayer) {
        while (true) {
            val position = exoPlayer.currentPosition.coerceAtLeast(0L)
            val dur = exoPlayer.duration
            if (position / 1000L != currentPositionMs / 1000L) {
                currentPositionMs = position
            }
            if (dur != C.TIME_UNSET && dur > 0L && dur != durationMs) {
                durationMs = dur
            }
            delay(500L)
        }
    }

    var introDbStamps by remember(parentId, parentType, season, episode) {
        mutableStateOf(emptyList<IntroDbStamp>())
    }

    LaunchedEffect(parentId, parentType, season, episode) {
        introDbStamps = emptyList()
        if (!isLiveChannel) {
            introDbStamps = fetchIntroDbStamps(parentId, season, episode)
        }
    }

    var activeIntroStamp by remember(parentId, season, episode, parentType) {
        mutableStateOf<IntroDbStamp?>(null)
    }

    LaunchedEffect(exoPlayer, introDbStamps) {
        while (true) {
            delay(750L)
            val positionMs = exoPlayer.currentPosition
            val matchingStamp = introDbStamps.firstOrNull { stamp ->
                positionMs >= stamp.startMs && positionMs < stamp.endMs
            }
            if (matchingStamp?.type != activeIntroStamp?.type ||
                matchingStamp?.startMs != activeIntroStamp?.startMs ||
                matchingStamp?.endMs != activeIntroStamp?.endMs) {
                activeIntroStamp = matchingStamp
            }
        }
    }

    LaunchedEffect(exoPlayer, errorMessage, retryExhausted) {
        if (errorMessage != null && !retryExhausted && !isRetrying) {
            if (retryAttempt < MAX_RETRY_ATTEMPTS) {
                isRetrying = true
                // --- Nuvio-style codec-aware retry ---
                // Retry 1: just retry (transient network glitch)
                // Retry 2: force software decoder (HW can't handle this codec)
                // Retry 3+: try probing with a different mime hint
                if (retryAttempt == 2 && !forceSoftwareDecoder) {
                    forceSoftwareDecoder = true
                    Log.i("PLAYER_RETRY", "Attempt ${retryAttempt + 1}: forcing software decoder")
                } else if (retryAttempt >= 3) {
                    // Nuvio's "dynamically probe stream mime type on parsing error"
                    // When ExoPlayer can't parse the container, clear the mime hint
                    // and let the extractor factory probe the raw bytes.
                    Log.i("PLAYER_RETRY", "Attempt ${retryAttempt + 1}: probing with raw extractor")
                }
                delay(RETRY_BACKOFF_MS.getOrElse(retryAttempt) { RETRY_BACKOFF_MS.last() })
                retryAttempt += 1
                errorMessage = null
                isRetrying = false
                manualRetryToken += 1
            } else {
                retryExhausted = true
            }
        }
    }

    LaunchedEffect(showControls, isPlaying) {
        if (showControls && isPlaying) {
            delay(6_000L)
            if (isPlaying &&
                !showSourcePicker &&
                !showAudioPicker &&
                !showSubtitlePicker &&
                !showSpeedPicker &&
                !showSettingsPanel
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
        val handler = CoroutineExceptionHandler { _, throwable ->
            Log.e("PLAYER_HISTORY", "History save failed", throwable)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + handler)
        val dao = WatchHistoryDatabase.getInstance(context).watchHistoryDao()
        var lastKnownDurationMs: Long? = null
        var hasEnded = false
        var rebufferStartedAtMs = 0L

        suspend fun saveProgress(reason: String, forceCompleted: Boolean = false) {
            if (isLiveChannel) return
            val (position, rawDuration) = withContext(Dispatchers.Main.immediate) {
                exoPlayer.currentPosition.coerceAtLeast(0L) to exoPlayer.duration
            }
            val resolvedDuration = when {
                rawDuration == C.TIME_UNSET || rawDuration <= 0L -> null
                else -> rawDuration
            }
            if (resolvedDuration != null) {
                lastKnownDurationMs = resolvedDuration
            }
            val durationToSave = resolvedDuration ?: lastKnownDurationMs
            if (parentId.isBlank() || historyId.isBlank() || durationToSave == null) return
            if (position < MIN_RESUME_POSITION_MS && !forceCompleted) return

            val isCompleted = forceCompleted || position >= (durationToSave * COMPLETION_THRESHOLD_RATIO).toLong()
            val safePosition = if (isCompleted) 0L else position.coerceAtMost(durationToSave)
            val now = System.currentTimeMillis()
            val existing = withContext(Dispatchers.IO) { dao.getById(historyId) }

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
                completedAt = if (isCompleted) existing?.completedAt ?: now else null
            )
            withContext(Dispatchers.IO) { dao.upsert(entry) }

            // Scrobble to Simkl when an item is first completed locally.
            // Fire-and-forget on IO: never blocks or fails the save, and the
            // existing?.completedAt guard prevents duplicate pushes on re-save.
            if (isCompleted && existing?.completedAt == null) {
                scope.launch(Dispatchers.IO) {
                    val simklRepository = SimklRepository(context)
                    when (parentType.lowercase()) {
                        "movie" -> simklRepository.pushWatchedMovie(
                            imdbId = parentId,
                            title = itemName
                        )
                        "series", "show", "tv" -> {
                            val s = season
                            val e = episode
                            if (s != null && e != null) {
                                simklRepository.pushWatchedEpisode(
                                    showImdbId = parentId,
                                    season = s,
                                    episode = e,
                                    title = itemName
                                )
                            }
                        }
                    }
                }
            }
        }

        val playerListener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                isPlayingFlow.value = isPlayingNow
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // Nothing in the app currently surfaces how often or how
                // long rebuffers actually last -- that's the difference
                // between "network hiccup" and "buffer settings are too
                // small," and no code change should guess at that without
                // seeing it happen. This logs it so a real lag episode
                // produces real numbers instead of a guess.
                if (playbackState == Player.STATE_BUFFERING) {
                    rebufferStartedAtMs = System.currentTimeMillis()
                } else if (rebufferStartedAtMs != 0L) {
                    val stalledMs = System.currentTimeMillis() - rebufferStartedAtMs
                    Log.w(
                        "PLAYER_PERF",
                        "Rebuffer stall: ${stalledMs}ms " +
                            "(url=$currentUrl, software=$forceSoftwareDecoder)"
                    )
                    rebufferStartedAtMs = 0L
                }

                isBufferingFlow.value = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    errorMessage = null
                    retryAttempt = 0
                    retryExhausted = false
                }
                if (playbackState == Player.STATE_ENDED && !hasEnded) {
                    hasEnded = true
                    scope.launch { saveProgress(reason = "ended", forceCompleted = true) }
                    // Auto-play next episode for series content
                    if (autoPlayNext && !isLiveChannel && season != null && episode != null) {
                        scope.launch(Dispatchers.Main.immediate) {
                            onNextEpisode()
                        }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val retryable = isLikelyRetryable(error)
                errorMessage = buildString {
                    append(friendlyErrorMessage(error))
                    if (forceSoftwareDecoder) {
                        append(" (software decoder)")
                    }
                }
                if (!retryable) {
                    retryExhausted = true
                }
            }
        }

        exoPlayer.addListener(playerListener)

        // Dropped frames specifically point at decoding (often software
        // fallback on a codec the device doesn't have hardware support
        // for) rather than network -- distinguishing that from a rebuffer
        // stall above is exactly what's needed before touching LoadControl
        // or decoder settings, instead of tuning them blind.
        exoPlayer.addAnalyticsListener(
            object : AnalyticsListener {
                override fun onDroppedVideoFrames(
                    eventTime: AnalyticsListener.EventTime,
                    droppedFrames: Int,
                    elapsedMs: Long
                ) {
                    if (droppedFrames > 0) {
                        Log.w(
                            "PLAYER_PERF",
                            "Dropped $droppedFrames video frames over " +
                                "${elapsedMs}ms (software=$forceSoftwareDecoder)"
                        )
                    }
                }
            }
        )

        val periodicSaveJob = scope.launch {
            while (true) {
                delay(PERIODIC_SAVE_INTERVAL_MS)
                if (hasEnded) break
                runCatching { saveProgress(reason = "periodic") }
            }
        }

        onDispose {
            exoPlayer.removeListener(playerListener)
            periodicSaveJob.cancel()
            runBlocking {
                runCatching { saveProgress(reason = "dispose", forceCompleted = hasEnded) }
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
        currentAudioUrl = stream.audioUrl
        retryAttempt = 0
        retryExhausted = false
        errorMessage = null
        forceSoftwareDecoder = false
        showSourcePicker = false
    }

    val focusRequester = remember { FocusRequester() }
    val playPauseFocusRequester = remember { FocusRequester() }

    // Picker dialogs trap focus while open (see TrackPickerDialog's
    // focusProperties). When the last one closes, the node that had
    // focus is disposed along with the dialog, so Compose has to pick
    // a new target -- without this, that pick was landing wherever the
    // platform's default focus search decided (often the video surface,
    // sometimes a random control). Send it back to Play/Pause instead.
    val anyPickerShowing =
        showSourcePicker ||
        showAudioPicker ||
        showSubtitlePicker ||
        showSpeedPicker ||
        showSettingsPanel

    // The dialog owns focus while open. Do not request focus from the
    // background overlay on every picker state change: doing so can race
    // the remote center event and immediately steal focus back to the
    // control row, making the picker look like it vanished.
    var wasPickerShowing by remember { mutableStateOf(false) }
    LaunchedEffect(anyPickerShowing) {
        if (wasPickerShowing && !anyPickerShowing) {
            withFrameNanos { }
            runCatching { playPauseFocusRequester.requestFocus() }
        }
        wasPickerShowing = anyPickerShowing
    }

    // Block system Back from exiting the Activity while a picker is open.
    // BackHandler is the standard Compose mechanism — more reliable than
    // manually registering an OnBackPressedCallback via DisposableEffect.
    // (Each TrackPickerDialog also has its own BackHandler for close-on-back;
    // this outer one is a safety net in case the dialog's doesn't fire.)
    BackHandler(enabled = anyPickerShowing) {
        showSourcePicker = false
        showAudioPicker = false
        showSubtitlePicker = false
        showSpeedPicker = false
        showSettingsPanel = false
    }

    // When the overlay hides (play pressed), move focus back to the root
    // surface so the remote keeps working and focus is never lost to a
    // disposed control row.
    LaunchedEffect(showControls) {
        if (!showControls) {
            withFrameNanos { }
            runCatching { focusRequester.requestFocus() }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.nativeKeyEvent.action != android.view.KeyEvent.ACTION_DOWN) {
                    return@onKeyEvent false
                }
                when (event.nativeKeyEvent.keyCode) {
                    android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        exoPlayer.pause()
                        showControls = true
                        true
                    }
                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> {
                        exoPlayer.play()
                        showControls = false
                        true
                    }
                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        exoPlayer.playWhenReady = !exoPlayer.playWhenReady
                        showControls = !exoPlayer.playWhenReady
                        true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_ENTER,
                    android.view.KeyEvent.KEYCODE_BUTTON_SELECT -> {
                        // Remote OK with the overlay hidden pops the overlay.
                        showControls = true
                        true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_UP,
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                    android.view.KeyEvent.KEYCODE_CHANNEL_UP,
                    android.view.KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                        showControls = true
                        false
                    }
                    else -> false
                }
            }
    ) {
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }

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

            activeIntroStamp?.let { stamp ->
                KBCard(
                    onClick = {
                        val durationMsTarget = exoPlayer.duration
                        val targetMs = if (durationMsTarget > 0L && durationMsTarget != C.TIME_UNSET) {
                            stamp.endMs.coerceAtMost(durationMsTarget)
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
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                    )
                }
            }

            if (showControls) {
                PlayerControlsOverlay(
                    isLiveChannel = isLiveChannel,
                    isPlaying = isPlaying,
                    sourceLabel = currentSourceLabel,
                    hasMultipleSources = sources.size > 1,
                    hasNextEpisode = season != null && episode != null,
                    itemName = itemName,
                    season = season,
                    episode = episode,
                    episodeTitle = episodeTitle,
                    clearLogoUrl = clearLogoUrl,
                    overview = overview,
                    cast = cast,
                    currentPositionMs = currentPositionMs,
                    durationMs = durationMs,
                    onPlayPause = {
                        exoPlayer.playWhenReady = !exoPlayer.playWhenReady
                        // Overlay follows playback state: visible while paused,
                        // gone the instant playback resumes.
                        showControls = !exoPlayer.playWhenReady
                    },
                    onSeek = { targetMs -> exoPlayer.seekTo(targetMs) },
                    onNextEpisode = onNextEpisode,
                    onSourcePicker = { showSourcePicker = true },
                    onAudioPicker = { showAudioPicker = true },
                    onSubtitlePicker = { showSubtitlePicker = true },
                    onSpeedPicker = { showSpeedPicker = true },
                    onAspect = { resizeModeIndex = (resizeModeIndex + 1) % 3 },
                    onSettings = { showSettingsPanel = true },
                    onNavigateActor = onNavigateActor,
                    streamWidth = streamWidth,
                    streamHeight = streamHeight,
                    streamBitrate = streamBitrate,
                    streamCodec = streamCodec,
                    playPauseFocusRequester = playPauseFocusRequester
                )
            }
        }
    }

    if (showSourcePicker) {
        TrackPickerDialog(
            title = "SOURCES",
            onDismiss = { showSourcePicker = false }
        ) {
            items(sources) { stream ->
                PickerRow(
                    label = stream.label(),
                    selected = stream.url == currentUrl,
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
            items(audioGroups.size) { groupIndex ->
                val group = audioGroups[groupIndex]
                for (trackIndex in 0 until group.length) {
                    val format = group.getTrackFormat(trackIndex)
                    PickerRow(
                        label = format.language?.uppercase() ?: "Track ${groupIndex + 1}",
                        selected = group.isTrackSelected(trackIndex),
                        onClick = {
                            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                .buildUpon()
                                .setOverrideForType(
                                    androidx.media3.common.TrackSelectionOverride(
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
        val tracks = exoPlayer.currentTracks
        TrackPickerDialog(
            title = "SUBTITLES",
            onDismiss = { showSubtitlePicker = false }
        ) {
            item {
                PickerRow(
                    label = "OFF",
                    selected = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.none { g -> (0 until g.length).any { g.isTrackSelected(it) } },
                    onClick = {
                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                            .build()
                        showSubtitlePicker = false
                    }
                )
            }

            val subtitleGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
            items(subtitleGroups.size) { groupIndex ->
                val group = subtitleGroups[groupIndex]
                for (trackIndex in 0 until group.length) {
                    val format = group.getTrackFormat(trackIndex)
                    PickerRow(
                        label = format.language?.uppercase() ?: "Track ${groupIndex + 1}",
                        selected = group.isTrackSelected(trackIndex),
                        onClick = {
                            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                .buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .setOverrideForType(
                                    androidx.media3.common.TrackSelectionOverride(
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

    if (showSettingsPanel) {
        SettingsPanel(
            streamWidth = streamWidth,
            streamHeight = streamHeight,
            streamBitrate = streamBitrate,
            streamCodec = streamCodec,
            playbackSpeed = playbackSpeed,
            resizeModeIndex = resizeModeIndex,
            subtitleOffsetMs = subtitleOffsetMs,
            subtitleSize = subtitleSize,
            subtitleBackground = subtitleBackground,
            externalSubtitleLabel = externalSubtitleLabel,
            isLiveChannel = isLiveChannel,
            enableTunneling = enableTunneling,
            bufferMode = bufferMode,
            autoPlayNext = autoPlayNext,
            onSubtitleOffsetChange = { subtitleOffsetMs = it },
            onSubtitleSizeChange = { subtitleSize = it },
            onSubtitleBackgroundChange = { subtitleBackground = it },
            onTunnelingChange = { enableTunneling = it },
            onBufferModeChange = { bufferMode = it },
            onAutoPlayNextChange = { autoPlayNext = it },
            onDismiss = { showSettingsPanel = false }
        )
    }

    // PiP: hide overlay when in PiP mode
    LaunchedEffect(isInPiPMode) {
        if (isInPiPMode) {
            showControls = false
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
    itemName: String,
    season: Int?,
    episode: Int?,
    episodeTitle: String?,
    clearLogoUrl: String?,
    overview: String?,
    cast: List<PlayerCastMember>,
    currentPositionMs: Long,
    durationMs: Long,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onNextEpisode: () -> Unit,
    onSourcePicker: () -> Unit,
    onAudioPicker: () -> Unit,
    onSubtitlePicker: () -> Unit,
    onSpeedPicker: () -> Unit,
    onAspect: () -> Unit,
    onSettings: () -> Unit,
    onNavigateActor: (Int) -> Unit,
    streamWidth: Int,
    streamHeight: Int,
    streamBitrate: Int,
    streamCodec: String?,
    playPauseFocusRequester: FocusRequester
) {
    val seekBarFocusRequester = remember { FocusRequester() }
    var seekDirection by remember { mutableStateOf<Int?>(null) }
    var seekMultiplier by remember { mutableIntStateOf(1) }
    var seekPositionMs by remember(currentPositionMs) { mutableStateOf(currentPositionMs) }

    LaunchedEffect(seekDirection) {
        val direction = seekDirection ?: return@LaunchedEffect
        while (seekDirection == direction) {
            val stepMs = 5_000L * seekMultiplier
            seekPositionMs = (seekPositionMs + direction * stepMs)
                .coerceIn(0L, durationMs)
            onSeek(seekPositionMs)
            seekMultiplier = (seekMultiplier + 1).coerceAtMost(12)
            delay(180L)
        }
    }

    // Focused state drives a highlight ring on the slider so it is obvious
    // when the scrubber has focus.
    var seekBarFocused by remember { mutableStateOf(false) }
    val seekBarModifier = Modifier
        .fillMaxWidth()
        .focusRequester(seekBarFocusRequester)
        .onFocusChanged { seekBarFocused = it.isFocused }
        .then(
            if (seekBarFocused) {
                Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .border(3.dp, KBAccent, RoundedCornerShape(24.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            } else {
                Modifier
            }
        )
        .focusable()
        .onKeyEvent { event ->
            if (event.type == KeyEventType.KeyUp) {
                seekDirection = null
                seekMultiplier = 1
                return@onKeyEvent true
            }
            if (event.type != KeyEventType.KeyDown || durationMs <= 0L) return@onKeyEvent false
            val direction = when (event.key) {
                androidx.compose.ui.input.key.Key.DirectionLeft -> -1
                androidx.compose.ui.input.key.Key.DirectionRight -> 1
                else -> return@onKeyEvent false
            }
            if (seekDirection != direction) {
                seekPositionMs = currentPositionMs
                seekMultiplier = 1
                seekDirection = direction
            }
            true
        }

    LaunchedEffect(Unit) {
        // Wait one frame so the control row has attached its focus nodes.
        withFrameNanos { }
        runCatching { playPauseFocusRequester.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(
                        KBVoid.copy(alpha = 0.92f),
                        KBVoid.copy(alpha = 0.18f),
                        KBVoid.copy(alpha = 0.96f)
                    )
                )
            )
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top Header: ClearLogo or Item Name + Source label
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.fillMaxWidth(0.7f)) {
                if (!clearLogoUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = clearLogoUrl,
                        contentDescription = itemName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .height(64.dp)
                            .fillMaxWidth(0.5f)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                } else if (itemName.isNotBlank()) {
                    Text(
                        text = itemName,
                        color = KBTextHi,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
                if (season != null && episode != null) {
                    Text(
                        text = "S${season.toString().padStart(2, '0')} · E${episode.toString().padStart(2, '0')}",
                        color = KBTextHi,
                        style = MaterialTheme.typography.titleMedium
                    )
                    episodeTitle?.takeIf { it.isNotBlank() }?.let { title ->
                        Text(
                            text = title,
                            color = KBTextLo,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }
                Text(
                    text = "Source: $sourceLabel",
                    color = KBTextHi,
                    style = MaterialTheme.typography.bodySmall
                )
                if (streamWidth > 0 && streamHeight > 0) {
                    val healthText = buildString {
                        append(normalizeResolution(streamWidth, streamHeight))
                        if (streamBitrate > 0) {
                            append(" • ${streamBitrate / 1_000} kbps")
                        }
                        val codecLabel = normalizeCodec(streamCodec)
                        if (codecLabel != "—") append(" • $codecLabel")
                    }
                    Text(
                        text = healthText,
                        color = KBTextLo,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        // Middle Section: Overview and Clickable Cast Row (Always available on overlay invoke)
        if (!overview.isNullOrBlank() || cast.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!overview.isNullOrBlank()) {
                    Text(
                        text = overview,
                        color = KBTextHi,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(0.75f)
                    )
                }

                if (cast.isNotEmpty()) {
                    Text(
                        text = "CAST",
                        color = KBAccent,
                        style = MaterialTheme.typography.labelMedium
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(cast, key = { it.id }) { actor ->
                            KBCard(
                                onClick = { onNavigateActor(actor.id) }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .background(KBSurfaceRaised, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(CircleShape)
                                            .background(KBSurface)
                                    ) {
                                        if (!actor.profilePath.isNullOrBlank()) {
                                            AsyncImage(
                                                model = actor.profilePath,
                                                contentDescription = actor.name,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                    }
                                    Column {
                                        Text(
                                            text = actor.name,
                                            color = KBTextHi,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                        if (!actor.character.isNullOrBlank()) {
                                            Text(
                                                text = actor.character,
                                                color = KBTextLo,
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Bottom Section: Seekbar + Control Buttons
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!isLiveChannel && durationMs > 0L) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    androidx.compose.material3.Slider(
                        value = seekPositionMs.toFloat(),
                        onValueChange = {
                            seekDirection = null
                            seekPositionMs = it.toLong()
                            onSeek(seekPositionMs)
                        },
                        onValueChangeFinished = {
                            seekDirection = null
                            onSeek(seekPositionMs)
                        },
                        valueRange = 0f..durationMs.toFloat(),
                        colors = androidx.compose.material3.SliderDefaults.colors(
                            thumbColor = KBAccent,
                            activeTrackColor = KBAccent,
                            inactiveTrackColor = KBSurfaceRaised
                        ),
                        modifier = seekBarModifier
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatMillis(currentPositionMs),
                            color = KBTextHi,
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            text = formatMillis(durationMs),
                            color = KBTextHi,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .focusGroup(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!isLiveChannel) {
                }

                ControlIconButton(
                    icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    onClick = onPlayPause,
                    modifier = Modifier.focusRequester(playPauseFocusRequester)
                )

                if (!isLiveChannel) {
                    if (hasNextEpisode) {
                        Spacer(modifier = Modifier.width(16.dp))
                        ControlIconButton(
                            icon = Icons.Filled.SkipNext,
                            contentDescription = "Next episode",
                            onClick = onNextEpisode
                        )
                    }
                }

                Spacer(modifier = Modifier.width(24.dp))

                if (hasMultipleSources) {
                    ControlIconButton(
                        icon = Icons.Filled.SwapHoriz,
                        contentDescription = "Change source",
                        onClick = onSourcePicker
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }

                if (!isLiveChannel) {
                    ControlIconButton(
                        icon = Icons.Filled.Audiotrack,
                        contentDescription = "Audio track",
                        onClick = onAudioPicker
                    )
                    Spacer(modifier = Modifier.width(12.dp))

                    ControlIconButton(
                        icon = Icons.Filled.ClosedCaption,
                        contentDescription = "Subtitles",
                        onClick = onSubtitlePicker
                    )
                    Spacer(modifier = Modifier.width(12.dp))

                    ControlIconButton(
                        icon = Icons.Filled.Speed,
                        contentDescription = "Playback speed",
                        onClick = onSpeedPicker
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }

                ControlIconButton(
                    icon = Icons.Filled.AspectRatio,
                    contentDescription = "Aspect ratio",
                    onClick = onAspect
                )

                Spacer(modifier = Modifier.width(12.dp))

                ControlIconButton(
                    icon = Icons.Filled.Settings,
                    contentDescription = "Settings",
                    onClick = onSettings
                )
            }
        }
    }
}

@Composable
private fun ControlIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    isPrimary: Boolean = false,
    modifier: Modifier = Modifier
) {
    // Focus state drives a hard border + color inversion so the highlighted
    // control is unmistakable on a TV (KBCard's border alone is too subtle
    // on the small 40dp buttons over a dark video surface).
    var focused by remember { mutableStateOf(false) }
    KBCard(
        onClick = onClick,
        modifier = modifier
            .size(40.dp)
            .onFocusChanged { focused = it.isFocused }
            .then(
                if (focused) {
                    Modifier.border(
                        3.dp,
                        if (isPrimary) KBVoid else KBAccent,
                        RoundedCornerShape(10.dp)
                    )
                } else {
                    Modifier
                }
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    when {
                        focused && isPrimary -> KBVoid
                        focused -> KBAccent
                        isPrimary -> KBAccent
                        else -> KBSurface
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = when {
                    focused && isPrimary -> KBAccent
                    focused -> KBVoid
                    isPrimary -> KBVoid
                    else -> KBTextHi
                },
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

private fun formatMillis(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TrackPickerDialog(
    title: String,
    onDismiss: () -> Unit,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit
) {
    val dialogFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { dialogFocusRequester.requestFocus() }
    }

    // The Box is a focus group (not a focusable leaf) so that requestFocus()
    // delegates to the first child (first PickerRow) instead of trapping
    // focus on the scrim.  exit = Cancel prevents D-pad from escaping
    // past the dialog to the controls underneath.  Back is handled by
    // BackHandler (Android Back goes through onBackPressed, not key events).
    BackHandler { onDismiss() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid.copy(alpha = 0.75f))
            .focusRequester(dialogFocusRequester)
            .focusGroup()
            .focusProperties {
                exit = { FocusRequester.Cancel }
            },
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
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 5
            )
        }
    }
}

private fun parseHeaders(raw: String): Map<String, String> {
    if (raw.isBlank()) return emptyMap()
    return raw.lineSequence()
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
    return headers.entries.joinToString(separator = System.lineSeparator()) { (key, value) ->
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
    headers.forEach { (key, value) ->
        builder.appendQueryParameter(key, value)
    }
    return builder.build().toString()
}
