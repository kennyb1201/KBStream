package com.kennyb1201.kbstream.ui.home

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs
import androidx.compose.foundation.shape.RoundedCornerShape
import com.kennyb1201.kbstream.data.tmdb.displayDescription
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.foundation.BorderStroke
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
import coil3.toBitmap
import com.kennyb1201.kbstream.data.tmdb.displaySeasonEpisodeCount
import com.kennyb1201.kbstream.data.tmdb.releaseYear
import com.kennyb1201.kbstream.data.addon.Meta
import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.data.kb.KBFolder
import com.kennyb1201.kbstream.data.tmdb.TmdbDetail
import com.kennyb1201.kbstream.data.tmdb.certification
import com.kennyb1201.kbstream.data.tmdb.movieStatusTag
import com.kennyb1201.kbstream.data.youtube.TrailerPlayerLauncher
import com.kennyb1201.kbstream.data.youtube.TrailerPlayerPool
import com.kennyb1201.kbstream.data.library.LibraryIds
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.LibraryAddToListDialog
import com.kennyb1201.kbstream.ui.components.LibraryAddTarget
import com.kennyb1201.kbstream.ui.components.LandscapeCard
import com.kennyb1201.kbstream.ui.components.PosterCard
import com.kennyb1201.kbstream.ui.kb.KBHomeCollectionRail
import com.kennyb1201.kbstream.ui.settings.AppPreferences
import com.kennyb1201.kbstream.ui.components.PosterContextAction
import com.kennyb1201.kbstream.ui.components.PosterContextMenu
import com.kennyb1201.kbstream.ui.detail.StreamsTarget
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBDanger
import com.kennyb1201.kbstream.ui.theme.KBSuccess
import com.kennyb1201.kbstream.ui.theme.KBPlum
import com.kennyb1201.kbstream.ui.theme.KBRust
import com.kennyb1201.kbstream.ui.theme.KBSteel
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import com.kennyb1201.kbstream.ui.home.UpcomingEpisode
import com.kennyb1201.kbstream.data.youtube.PlayableSource
import com.kennyb1201.kbstream.data.youtube.YoutubeChunkedDataSourceFactory
import kotlinx.coroutines.delay

private val HomePosterWidth = 124.dp
private val HomePosterHeight = 180.dp
private val HomeLandscapeWidth = 210.dp
private val HomeLandscapeHeight = 118.dp
private val HomeRailGap = 12.dp
private val ContinueWatchingCardWidth = 260.dp
private val ContinueWatchingCardImageHeight = 146.dp
private const val HeroTrailerDwellMs = 4_000L

private val HomeHeroHeight = 300.dp

private val RailTopContentPadding = 4.dp
private val RailBottomContentPadding = 12.dp

private val RailHorizontalStartPadding = 12.dp
private val RailSectionGap = 20.dp

// KB parity: MODERN_ROW_HEADER_FOCUS_INSET. When a row takes focus, its
// header lands this far below the rails viewport top — deterministic landing
// kills both the CW/Upcoming sliver and the per-focus-step bounce.
private val RailHeaderFocusInset = 40.dp

private val HeroToFirstRailGap = 2.dp

// Hero clearlogo box (ContentScale.Fit inside). Collection (KB folder)
// manifests supply their own titleLogoUrl, which is frequently a wordmark
// that reads small at the shared size — render it noticeably larger.
private val HeroLogoWidth = 300.dp
private val HeroLogoHeight = 82.dp
private val CollectionHeroLogoWidth = 400.dp
private val CollectionHeroLogoHeight = 120.dp

private val PosterFocusHeadroom = 24.dp

private val TvSafeAreaHorizontal = 12.dp
private val TvSafeAreaVertical = 0.dp

/**
 * Formats UpNextItem.remainingMinutes as "1h 12m left" / "42m left".
 */
private fun formatTimeLeft(remainingMinutes: Int?): String? {
    if (remainingMinutes == null || remainingMinutes <= 0) return null

    val hours = remainingMinutes / 60
    val minutes = remainingMinutes % 60

    return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}m left"
        hours > 0 -> "${hours}h left"
        else -> "${minutes}m left"
    }
}

@Composable
private fun TopActionItem(
    label: String,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }

    androidx.tv.material3.Surface(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged {
                focused = it.isFocused
            }
            .onPreviewKeyEvent { event ->
                if (
                    event.type == KeyEventType.KeyDown &&
                    event.key == Key.DirectionDown
                ) {
                    onDismiss()
                    true
                } else {
                    false
                }
            },
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(6.dp)
        ),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = KBTextHi.copy(alpha = 0.76f),
            focusedContainerColor = KBAccent.copy(alpha = 0.28f),
            focusedContentColor = KBTextHi,
            pressedContainerColor = KBAccent.copy(alpha = 0.28f),
            pressedContentColor = KBTextHi
        ),
        border = androidx.tv.material3.ClickableSurfaceDefaults.border(
            border = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    Color.Transparent
                ),
                shape = RoundedCornerShape(6.dp)
            ),
            focusedBorder = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(
                    2.dp,
                    KBAccent
                ),
                shape = RoundedCornerShape(6.dp)
            )
        ),
        glow = androidx.tv.material3.ClickableSurfaceDefaults.glow(),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(
            focusedScale = 1f
        )
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(
                horizontal = 12.dp,
                vertical = 8.dp
            )
        )
    }
}

@Composable
private fun TopActionBar(
    onSearch: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
    onSwitchProfile: () -> Unit,
    firstActionFocusRequester: FocusRequester,
    onDismiss: () -> Unit
) {
    // Quick profile switch lives on the top bar: the button IS the active
    // profile, so it's obvious what's running and one click swaps profiles
    // (ProfilePicker → setActive → Home rails reload). Falls back to
    // PROFILES before any profile exists.
    val activeProfile by
        com.kennyb1201.kbstream.data.sync.ProfileManager.activeProfile
            .collectAsState()
    val profileLabel = activeProfile?.name?.uppercase()
        ?.takeIf { it.isNotBlank() } ?: "PROFILES"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = 20.dp,
                vertical = 14.dp
            ),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TopActionItem(
            label = "SEARCH",
            onClick = onSearch,
            onDismiss = onDismiss,
            modifier = Modifier
                .focusRequester(firstActionFocusRequester)
                .padding(end = 8.dp)
        )

        TopActionItem(
            label = "TV GUIDE",
            onClick = onOpenGuide,
            onDismiss = onDismiss,
            modifier = Modifier.padding(end = 8.dp)
        )

        TopActionItem(
            label = "LIBRARY",
            onClick = onOpenLibrary,
            onDismiss = onDismiss,
            modifier = Modifier.padding(end = 8.dp)
        )

        TopActionItem(
            label = profileLabel,
            onClick = onSwitchProfile,
            onDismiss = onDismiss,
            modifier = Modifier.padding(end = 8.dp)
        )

        TopActionItem(
            label = "SETTINGS",
            onClick = onOpenSettings,
            onDismiss = onDismiss
        )
    }
}

/** Human-readable origin of a resolved trailer source (host only, no signed URL). */
private fun heroSourceOrigin(source: PlayableSource): String =
    when (source) {
        is PlayableSource.Muxed ->
            "muxed host=" +
                (runCatching {
                    android.net.Uri.parse(source.url).host
                }.getOrNull() ?: "?")

        is PlayableSource.Adaptive ->
            "adaptive videoHost=" +
                (runCatching {
                    android.net.Uri.parse(source.videoUrl).host
                }.getOrNull() ?: "?") +
                " audioHost=" +
                (runCatching {
                    android.net.Uri.parse(source.audioUrl).host
                }.getOrNull() ?: "?")
    }

@Composable
private fun HeroInlineTrailerPlayer(
    source: PlayableSource,
    muted: Boolean,
    modifier: Modifier = Modifier,
    onEnded: () -> Unit = {},
    onFailed: () -> Unit = {}
) {
    val context = LocalContext.current
    // One pooled player for every hero trailer: renderer initialization is
    // the expensive part of ExoPlayer startup, so TrailerPlayerPool hands
    // back the same instance across focus changes and this composable only
    // swaps the MediaSource. (remember(source) used to rebuild the whole
    // player on every resolved-source change.)
    val exoPlayer = remember {
        TrailerPlayerPool.acquire {
            val renderersFactory =
                DefaultRenderersFactory(context.applicationContext)
                    .setExtensionRendererMode(
                        DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                    )
                    .setEnableDecoderFallback(true)

            ExoPlayer.Builder(context.applicationContext, renderersFactory)
                .setLoadControl(
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(2_000, 12_000, 500, 1_000)
                        .build()
                )
                .build()
        }
    }

    DisposableEffect(exoPlayer, source) {

        // Fire TV suppresses debug logs, so surface what the player actually
        // receives -- this proves which resolver produced the source.
        Log.w(
            "HOME_HERO",
            "Hero player mounting with source: " + heroSourceOrigin(source)
        )

        // googlevideo signed URLs 403 unless the request carries the same
        // YouTube client User-Agent that resolved them and uses bounded
        // range requests. YoutubeChunkedDataSourceFactory handles that for
        // googlevideo hosts (1 MB chunks via OkHttp, ratebypass fallbacks)
        // and passes every other host straight through - the same stack the
        // full player uses for HLS and progressive playback. The resolved
        // source carries the UA of the client that signed its URL, so the
        // factory leads with it instead of the legacy hardcoded one.
        val mediaSourceFactory =
            DefaultMediaSourceFactory(
                YoutubeChunkedDataSourceFactory(
                    userAgentHint = when (source) {
                        is PlayableSource.Muxed -> source.userAgent
                        is PlayableSource.Adaptive -> source.userAgent
                    }
                ),
                DefaultExtractorsFactory()
            )

        when (source) {
            is PlayableSource.Muxed -> {
                val mediaItem = MediaItem.Builder()
                    .setUri(source.url)
                    .apply {
                        if (source.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) {
                            setMimeType(MimeTypes.APPLICATION_M3U8)
                        }
                    }
                    .build()
                exoPlayer.setMediaSource(
                    mediaSourceFactory.createMediaSource(mediaItem)
                )
            }

            is PlayableSource.Adaptive -> {
                // Adaptive streams are video-only: the audio URL MUST be
                // merged in or the trailer plays silently. Same pattern
                // the fullscreen player uses (NativePlayerActivity).
                // Both URLs are googlevideo, so the chunked factory
                // serves both.
                val videoSource = mediaSourceFactory
                    .createMediaSource(MediaItem.fromUri(source.videoUrl))
                val audioSource = mediaSourceFactory
                    .createMediaSource(MediaItem.fromUri(source.audioUrl))
                exoPlayer.setMediaSource(MergingMediaSource(videoSource, audioSource))
            }
        }

        exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
        exoPlayer.volume = if (muted) 0f else 1f
        exoPlayer.playWhenReady = true
        exoPlayer.prepare()

        onDispose {
            // Intentionally empty: during a crossfade this instance can be
            // the OUTGOING content while the incoming one already prepared
            // new media on the shared pooled player, so a stop here would
            // kill the new trailer. HomeHero owns the stops instead (backdrop
            // transitions, ON_STOP, navigation teardown).
        }
    }

    // Volume tracks the mute toggle without re-prepping media (re-prepping
    // would restart the trailer from the beginning mid-viewing).
    DisposableEffect(exoPlayer, muted) {
        exoPlayer.volume = if (muted) 0f else 1f
        onDispose { }
    }

    // Backgrounding the app (TV Home press, input switch) STOPS the activity
    // but does not dispose the composition, so the DisposableEffect cleanups
    // above never run and the trailer audio keeps playing over other apps.
    // Pause the pooled player on ON_STOP; the resume epoch in HomeHero
    // re-resolves and re-preps it when the app returns.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        TrailerPlayerPool.pauseCurrent()
    }

    DisposableEffect(exoPlayer, source) {
        val handler = android.os.Handler(
            android.os.Looper.getMainLooper()
        )

        // Watchdog: if the trailer doesn't actually start playing
        // (or gets stuck buffering), bail out to the backdrop so the
        // hero never stays on a blank grey screen.
        val watchdog = object : Runnable {
            override fun run() {
                val state = exoPlayer.playbackState
                val playing = exoPlayer.isPlaying
                if (state != Player.STATE_READY || !playing) {
                    Log.w(
                        "HOME_HERO",
                        "Inline trailer stuck (state=$state playing=$playing); " +
                            "falling back to backdrop"
                    )
                    onEnded()
                }
            }
        }

        var startedPlaying = false

        val listener =
            object : Player.Listener {
                override fun onPlaybackStateChanged(
                    playbackState: Int
                ) {
                    if (playbackState == Player.STATE_ENDED) {
                        handler.removeCallbacks(watchdog)
                        onEnded()
                    } else if (playbackState == Player.STATE_READY && exoPlayer.isPlaying) {
                        startedPlaying = true
                        handler.removeCallbacks(watchdog)
                    } else if (playbackState == Player.STATE_BUFFERING && startedPlaying) {
                        // Re-buffer mid-playback: give it a few seconds before bailing.
                        handler.removeCallbacks(watchdog)
                        handler.postDelayed(watchdog, 5_000L)
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) {
                        startedPlaying = true
                        handler.removeCallbacks(watchdog)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    // A failed trailer must never leave a blank hero:
                    // fall back to the backdrop image immediately, then
                    // give the caller one shot at re-resolving a fresh
                    // signed URL (googlevideo URLs can go stale mid-stream).
                    Log.e(
                        "HOME_HERO",
                        "Inline trailer playback failed: ${error.errorCodeName}",
                        error
                    )
                    handler.removeCallbacks(watchdog)
                    onEnded()
                    onFailed()
                }
            }

        exoPlayer.addListener(listener)

        // Give the trailer up to 8s to start rendering; if it hasn't,
        // fall back to the backdrop.
        handler.postDelayed(watchdog, 8_000L)

        onDispose {
            handler.removeCallbacks(watchdog)
            exoPlayer.removeListener(listener)
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode =
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM

                setShutterBackgroundColor(
                    android.graphics.Color.TRANSPARENT
                )

                player = exoPlayer
                keepScreenOn = true
            }
        },
        modifier = modifier
    )
}


@Composable
private fun HeroClearLogo(
    url: String,
    name: String,
    modifier: Modifier = Modifier
) {
    var logoIsDark by remember(url) { mutableStateOf(false) }
    // A logo URL that exists but fails to load (dead TMDB path, CDN 404)
    // used to render as blank space. Fall back to the plain title text,
    // same as the no-logo case.
    var loadFailed by remember(url) { mutableStateOf(false) }

    if (loadFailed) {
        Text(
            text = name,
            color = KBTextHi,
            style = MaterialTheme.typography.headlineLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        return
    }

    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(url)
            .build(),
        contentDescription = name,
        contentScale = ContentScale.Fit,
        onSuccess = { state ->
            logoIsDark = runCatching {
                isDarkMonochromeArtwork(state.result.image.toBitmap())
            }.getOrDefault(false)
        },
        onError = { loadFailed = true },
        colorFilter = if (logoIsDark) ColorFilter.tint(KBTextHi) else null,
        modifier = modifier
    )
}

private fun isDarkMonochromeArtwork(bitmap: android.graphics.Bitmap): Boolean {
    val sample = android.graphics.Bitmap.createScaledBitmap(bitmap, 48, 24, true)
    var luminanceSum = 0L
    var saturationSum = 0L
    var count = 0L

    for (y in 0 until sample.height) {
        for (x in 0 until sample.width) {
            val pixel = sample.getPixel(x, y)
            val alpha = (pixel ushr 24) and 0xFF
            if (alpha > 20) {
                val red = (pixel ushr 16) and 0xFF
                val green = (pixel ushr 8) and 0xFF
                val blue = pixel and 0xFF
                luminanceSum +=
                    (0.2126f * red + 0.7152f * green + 0.0722f * blue).toLong()
                saturationSum += (maxOf(red, green, blue) - minOf(red, green, blue)).toLong()
                count++
            }
        }
    }

    if (sample !== bitmap) sample.recycle()
    if (count == 0L) return false

    return luminanceSum / count < 148L && saturationSum / count < 42L
}

/**
 * Public alias so KB FOLLOW_LAYOUT folder screens can render the exact
 * same hero (clearlogo, gradients, metadata, inline trailer) as Home —
 * keeping one implementation guarantees the two stay identical.
 */
@Composable
internal fun HomeHeroArtwork(
    preview: MetaPreview,
    meta: Meta?,
    tmdbDetail: TmdbDetail?,
    heroBackdropUrl: String?,
    heroLogoUrl: String?,
    trailerKey: String?,
    autoPlayTrailer: Boolean,
    muted: Boolean,
    heroHeight: Dp = HomeHeroHeight,
    heroLogoWidth: Dp = HeroLogoWidth,
    heroLogoHeight: Dp = HeroLogoHeight
) {
    HomeHero(
        preview = preview,
        meta = meta,
        tmdbDetail = tmdbDetail,
        heroBackdropUrl = heroBackdropUrl,
        heroLogoUrl = heroLogoUrl,
        trailerKey = trailerKey,
        autoPlayTrailer = autoPlayTrailer,
        muted = muted,
        heroHeight = heroHeight,
        heroLogoWidth = heroLogoWidth,
        heroLogoHeight = heroLogoHeight
    )
}

@Composable
private fun HomeHero(
    preview: MetaPreview,
    meta: Meta?,
    tmdbDetail: TmdbDetail?,
    heroBackdropUrl: String?,
    heroLogoUrl: String?,
    trailerKey: String?,
    autoPlayTrailer: Boolean,
    muted: Boolean,
    continueWatchingItem: UpNextItem? = null,
    heroHeight: Dp = HomeHeroHeight,
    heroLogoWidth: Dp = HeroLogoWidth,
    heroLogoHeight: Dp = HeroLogoHeight
) {
    val context = LocalContext.current
    val title = meta?.name ?: preview.name

    val backdrop = heroBackdropUrl
        ?: meta?.background
        ?: preview.background
        ?: meta?.poster
        ?: preview.poster

    val clearLogo = heroLogoUrl
        ?: meta?.logo
        ?: preview.logo

    val trailerPlaying =
        !trailerKey.isNullOrBlank() && autoPlayTrailer

    // Inline ExoPlayer is the only hero trailer path. The YouTube web
    // embed was tried but renders a grey screen with audio + subtitles on
    // some TVs and has no reliable end-of-video signal. ExoPlayer shares
    // the main player's proven stack (OkHttp + the youtube client UA +
    // bounded range requests via YoutubeChunkedDataSourceFactory), renders
    // no subtitles, and reports ENDED so the hero returns to the backdrop.
    var resolvedTrailerSource by remember(trailerKey) {
        mutableStateOf<PlayableSource?>(null)
    }

    // One-shot playback-failure retry: googlevideo signed URLs can go stale
    // mid-stream (403 on a later chunk). Re-resolving fetches a fresh URL —
    // capped at a single retry per trailer so a genuinely dead video can't
    // loop resolve → mount → 403 forever.
    var trailerAttempt by remember(trailerKey) { mutableStateOf(0) }

    // Re-arm the trailer when the app returns to the foreground: ON_STOP
    // released the player (audio-leak fix), so bump the epoch to drop the
    // stale source and re-resolve instead of leaving a released/blank player.
    var appInBackground by remember { mutableStateOf(false) }
    var resumeEpoch by remember { mutableStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { appInBackground = true }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (appInBackground) {
            appInBackground = false
            resumeEpoch += 1
        }
    }

    // Backdrop transitions (trailer ended, watchdog bail, resume re-arm)
    // must silence the pooled player immediately: the inline player's own
    // dispose can't do it -- during a crossfade the outgoing instance is
    // disposed AFTER the incoming one already prepared new media, and a stop
    // there would kill the new trailer.
    LaunchedEffect(resolvedTrailerSource) {
        if (resolvedTrailerSource == null) {
            TrailerPlayerPool.releaseForReuse()
        }
    }

    // Leaving Home entirely (Details, Settings, Search) disposes the hero
    // while the pooled player survives composition; without this its audio
    // would keep playing over the next screen.
    DisposableEffect(Unit) {
        onDispose { TrailerPlayerPool.releaseForReuse() }
    }

    LaunchedEffect(trailerPlaying, trailerKey, trailerAttempt, resumeEpoch) {
        resolvedTrailerSource = null

        if (trailerPlaying && !trailerKey.isNullOrBlank()) {
            if (trailerAttempt > 0) {
                // Bust the cached source so the retry gets a fresh signed URL.
                TrailerPlayerLauncher.invalidate(trailerKey)
                Log.w(
                    "HOME_HERO",
                    "Retrying hero trailer after playback failure " +
                        "(attempt=$trailerAttempt key=$trailerKey)"
                )
            }
            Log.w(
                "HOME_HERO",
                "Resolving hero trailer key=$trailerKey"
            )

            TrailerPlayerLauncher
                .resolvePlayableUrl(trailerKey)
                .onSuccess { source ->
                    resolvedTrailerSource = source
                    Log.w(
                        "HOME_HERO",
                        "Hero trailer resolved: " +
                            heroSourceOrigin(source)
                    )
                }
                .onFailure { error ->
                    Log.e(
                        "HOME_HERO",
                        "Failed to resolve hero trailer, " +
                            "keeping backdrop",
                        error
                    )
                }
        } else {
            Log.w(
                "HOME_HERO",
                "Hero trailer skipped (trailerPlaying=$trailerPlaying key=$trailerKey)"
            )
        }
    }

    val year = if (preview.type == "movie") {
        tmdbDetail?.releaseYear()
            ?: meta?.releaseInfo
                ?.let {
                    Regex("""\b(?:19|20)\d{2}\b""")
                        .find(it)
                        ?.value
                }
    } else {
        val startYear =
            tmdbDetail?.firstAirDate
                ?.take(4)
                ?.takeIf {
                    it.length == 4 &&
                        it.all(Char::isDigit)
                }
                ?: tmdbDetail?.releaseYear()

        val endYear =
            tmdbDetail?.lastEpisodeToAir
                ?.airDate
                ?.take(4)
                ?.takeIf {
                    it.length == 4 &&
                        it.all(Char::isDigit)
                }

        val isStillRunning =
            when (
                tmdbDetail?.status
                    ?.trim()
                    ?.lowercase()
            ) {
                "returning series",
                "in production",
                "planned" -> true

                else -> false
            }

        when {
            startYear == null -> null

            isStillRunning -> "$startYear–"

            !endYear.isNullOrBlank() &&
                endYear != startYear ->
                "$startYear–$endYear"

            tmdbDetail?.status
                ?.trim()
                ?.equals(
                    "ended",
                    ignoreCase = true
                ) == true ->
                startYear

            else -> startYear
        }
    }

    val rating =
        tmdbDetail
            ?.certification(
                preview.type == "movie"
            )
            ?.takeIf {
                it.isNotBlank()
            }
            ?: meta?.releaseInfo
                ?.let {
                    Regex(
                        """\b(?:PG-13|NC-17|TV-Y7|TV-Y|TV-G|TV-PG|TV-14|TV-MA|PG|G|R)\b"""
                    )
                        .find(
                            it.uppercase()
                        )
                        ?.value
                }

    // Status tag: series show their TMDB lifecycle (Ongoing / Ended /
    // Canceled / ...). TMDB's movie status is only the production
    // lifecycle, so movie tags are derived from release dates instead
    // (In Theaters / Streaming / Coming Soon / ...).
    val statusTag =
        if (preview.type.equals("movie", ignoreCase = true)) {
            tmdbDetail?.movieStatusTag()
        } else {
            when (
                tmdbDetail?.status
                    ?.trim()
                    ?.lowercase()
            ) {
                "returning series" -> "Ongoing"
                "ended" -> "Ended"
                "canceled",
                "cancelled" -> "Canceled"
                "in production" -> "In Production"
                "planned" -> "Planned"
                else -> null
            }
        }

    val imdb =
        if (continueWatchingItem != null) {
            continueWatchingItem.imdbRating
                ?.let {
                    "IMDb %.1f".format(it)
                }
                ?: meta?.imdbRating
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?.let {
                        "IMDb $it"
                    }
        } else {
            meta?.imdbRating
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?.let {
                    "IMDb $it"
                }
        }

    val runtime =
        meta?.runtime
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }

        val genre =
        meta?.genres
            ?.firstOrNull()
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }

    val seasonEpisodeCount =
        if (preview.type != "movie") {
            tmdbDetail?.displaySeasonEpisodeCount()
        } else {
            null
        }

        val heroInfoParts =
        listOfNotNull(
            imdb,
            year,
            rating,
            runtime,
            genre
        )

    val heroInfo =
        heroInfoParts.joinToString("  •  ")

    // Regular hero description.
    // Prefer TMDB's overview, then addon metadata, then preview metadata.
    val heroDescription =
    tmdbDetail?.displayDescription()
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: meta?.description
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        ?: preview.description
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        
    val continueEpisodeLabel =
        continueWatchingItem?.let { item ->
            val prefix =
                when {
                    item.isSeriesFinale ->
                        "Series Finale"

                    item.isSeasonFinale ->
                        "Season Finale"

                    // Upcoming-rail items carry their relative air-date
                    // label — the hero says "Airs Today" / "Airs In 5 days"
                    // instead of the generic "Next Up".
                    item.airDateLabel != null ->
                        "Airs ${item.airDateLabel}"

                    else ->
                        when (item.badge) {
                            UpNextBadge.CONTINUE_WATCHING ->
                                "Resume"

                            UpNextBadge.NEXT_UP ->
                                "Next Up"

                            UpNextBadge.NEW_EPISODE ->
                                "New Episode"

                            UpNextBadge.NEW_SEASON ->
                                "New Season"
                        }
                }

            when {
                item.season != null &&
                    item.episode != null ->
                    "$prefix  •  S%02d · E%02d".format(
                        item.season,
                        item.episode
                    )

                item.season != null ->
                    "$prefix  •  S%02d".format(
                        item.season
                    )

                item.episode != null ->
                    "$prefix  •  E%02d".format(
                        item.episode
                    )

                else ->
                    prefix
            }
        }

    val continueProgress =
        continueWatchingItem
            ?.progressPercent
            ?.coerceIn(0f, 1f)

    val continueTimeLeft =
        formatTimeLeft(continueWatchingItem?.remainingMinutes)

        val continueEpisodeCount =
    continueWatchingItem?.let { item ->                                                val watched = item.episodesWatched
        val total = item.episodesTotal

        if (
            watched != null &&
            total != null &&
            total > 0
        ) {
            "$watched of $total aired episodes watched"
        } else {
            null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(heroHeight)
            .background(Color.Black)
    ) {
        Row(
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(0.4f)
                    .background(Color.Black)
            )

            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(0.6f)
            ) {
            
                Crossfade(
    targetState = resolvedTrailerSource,
    label = "hero_backdrop_crossfade"
) { trailerSource ->
    if (trailerSource != null) {
        HeroInlineTrailerPlayer(
            source = trailerSource,
            muted = muted,
            modifier = Modifier.fillMaxSize(),
            onEnded = {
                // Video finished (or the watchdog gave up on a stuck
                // player): drop the source so the hero crossfades back
                // to the backdrop.
                resolvedTrailerSource = null
            },
            onFailed = {
                // Playback error (e.g. the signed URL went stale and a
                // chunk 403'd): drop the source and retry resolution once
                // with a fresh URL. onEnded still fires first, so the
                // backdrop shows immediately either way.
                if (trailerAttempt < 1) {
                    trailerAttempt += 1
                } else {
                    resolvedTrailerSource = null
                }
            }
        )
    } else {
        AsyncImage(
            model = remember(backdrop) {
                ImageRequest.Builder(context)
                    .data(backdrop)
                    .size(Size(1920, 1080))
                    .crossfade(true)
                    .build()
            },
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alignment = Alignment.Center
        )
    }
}

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                colorStops = arrayOf(
                                    // Anchor stays fully opaque so this
                                    // still blends seamlessly into the
                                    // solid black column beside it -- only
                                    // the falloff was shortened so less of
                                    // the backdrop gets darkened by it.
                                    0.00f to Color.Black.copy(alpha = 1.00f),
                                    0.06f to Color.Black.copy(alpha = 0.80f),
                                    0.14f to Color.Black.copy(alpha = 0.50f),
                                    0.22f to Color.Black.copy(alpha = 0.24f),
                                    0.32f to Color.Black.copy(alpha = 0.08f),
                                    0.42f to Color.Transparent,
                                    1.00f to Color.Transparent
                                )
                            )
                        )
                )
            }
        }

        // Bottom seam gradient: blends the backdrop into the rails below.
        // Skipped while an inline trailer is playing — otherwise its fully
        // opaque bottom edge paints a dark band across the video.
        if (resolvedTrailerSource == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to Color.Transparent,
                                .45f to Color.Transparent,
                                .62f to Color.Black.copy(alpha = .04f),
                                .72f to Color.Black.copy(alpha = .12f),
                                .82f to Color.Black.copy(alpha = .26f),
                                .90f to Color.Black.copy(alpha = .45f),
                                // Ramps the rest of the way to fully opaque
                                // right at the bottom edge so it matches the
                                // solid black behind the rails below exactly --
                                // everything above stays light so most of the
                                // hero isn't darkened just to blend this seam.
                                .96f to Color.Black.copy(alpha = .78f),
                                1f to Color.Black.copy(alpha = 1.00f)
                            )
                        )
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxHeight()
                // 0.46f (was 0.4f): gives the hero logo/wordmark box room —
                // the logo's Modifier.width() coerces to this column, so a
                // bigger logo box without a wider column would clamp back.
                .fillMaxWidth(0.46f)
                .padding(
                    start = 32.dp,
                    end = 20.dp,
                    bottom = 16.dp
                ),
            verticalArrangement = Arrangement.Bottom
        ) {
            if (!clearLogo.isNullOrBlank()) {
                HeroClearLogo(
                    url = clearLogo,
                    name = title,
                    // Fixed size, coerced by the parent column's constraints
                    // on narrow screens; Collection manifests get the larger
                    // box so their wordmark logos carry the hero.
                    modifier = Modifier
                        .width(heroLogoWidth)
                        .height(heroLogoHeight)
                )
            } else {
                Text(
                    text = title,
                    color = KBTextHi,
                    style = MaterialTheme.typography.headlineLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

                        // Main metadata line.
            if (heroInfo.isNotBlank()) {
                Text(
                    text = heroInfo,
                    color = KBTextHi.copy(alpha = 0.94f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 14.dp)
                )
            }

            // Regular catalog items:
            // Status comes FIRST, followed by season/episode totals.
            //
            // Continue Watching items intentionally do NOT show the
            // TMDB status (Ongoing / Ended / Cancelled / etc.).
            if (
                continueWatchingItem == null &&
                (
                    !statusTag.isNullOrBlank() ||
                    !seasonEpisodeCount.isNullOrBlank()
                )
            ) {
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    statusTag?.let { status ->
                        Text(
                            text = status,
                            color = KBAccent,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (
                        !statusTag.isNullOrBlank() &&
                        !seasonEpisodeCount.isNullOrBlank()
                    ) {
                        Text(
                            text = "  •  ",
                            color = KBTextHi.copy(alpha = 0.94f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    seasonEpisodeCount?.let { count ->
                        Text(
                            text = count,
                            color = KBTextHi.copy(alpha = 0.94f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Episode title FIRST, above the "Resume  •  S02 · E05" line and
            // the progress bar: the name of what is about to play is the
            // headline, and the resume state is the detail under it.
            continueWatchingItem
                ?.episodeTitle
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { episodeTitle ->
                    Text(
                        text = episodeTitle,
                        color = KBTextHi,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }

            continueEpisodeLabel?.let { label ->
                Text(
                    text = label,
                    color = KBAccent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 10.dp)
                )

                continueProgress?.let { progress ->
    Box(
        modifier = Modifier
            .padding(top = 7.dp)
            .width(260.dp)
            .height(4.dp)
            .background(
                KBTextHi.copy(alpha = 0.28f),
                RoundedCornerShape(2.dp)
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress)
                .height(4.dp)
                .background(
                    KBAccent,
                    RoundedCornerShape(2.dp)
                )
        )
    }
}

continueTimeLeft?.let { label ->
    Text(
        text = label,
        color = KBTextHi.copy(alpha = 0.70f),
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        modifier = Modifier.padding(top = 5.dp)
    )
}

            continueEpisodeCount?.let { label ->
    Text(
        text = label,
        color = KBTextHi.copy(alpha = 0.70f),
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        modifier = Modifier.padding(top = 5.dp)
    )
            }

            // Upcoming-rail items: the real calendar date under the
            // "Airs …" label ("Mon, Sep 15"), styled like the other
            // hero detail lines. Skipped when the label already IS the
            // date — ≥7 days out the relative label formats as the
            // absolute date, so "Airs Thu, Sep 25" + the date line showed
            // the same date twice (the hero double-date on far-out items,
            // e.g. new-season premieres). Relative labels ("In 5 days",
            // "Today") keep the date line — there it adds real info.
            continueWatchingItem?.airDateFull
                ?.takeIf { it.isNotBlank() }
                ?.takeIf { fullDate ->
                    continueWatchingItem.airDateLabel != fullDate
                }
                ?.let { fullDate ->
                    Text(
                        text = fullDate,
                        color = KBTextHi.copy(alpha = 0.70f),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 5.dp)
                    )
                }
            }

            if (continueWatchingItem != null) {
    // Only show the episode description for non-resume items (a finale
    // that is being resumed still has a saved position, so it behaves
    // like a plain resume card here).
    if (
        continueWatchingItem.badge !=
            UpNextBadge.CONTINUE_WATCHING &&
        continueWatchingItem.startPositionMs <= 0L
    ) {
        continueWatchingItem.episodeDescription
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
            ?.let { description ->
                Text(
                    text = description,
                    color = KBTextHi.copy(alpha = 0.80f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 7.dp)
                )
            }
    }

    // Continue-watching items never show the show's/movie's metadata
    // description: resume items (CONTINUE_WATCHING) show no description
    // at all, and every other continue-watching badge shows only the
    // episode description above.
} else {
    heroDescription
        ?.trim()
        ?.takeIf {
            it.isNotBlank()
        }
        ?.let { description ->
            Text(
                text = description,
                color = KBTextHi.copy(alpha = 0.80f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
}
    }
}
}

                    

/**
 * Builds the rail header: optional addon name and catalog type around the
 * formatted catalog name, e.g. "AIOMetadata · Trending · Series".
 */
private fun homeRailTitle(
    catalogName: String,
    addonName: String,
    type: String,
    showType: Boolean,
    showAddon: Boolean
): String {

    val parts = buildList {

        if (showAddon && addonName.isNotBlank()) {
            add(addonName)
        }

        add(catalogName)

        if (showType && type.isNotBlank()) {
            add(type.replaceFirstChar { it.uppercase() })
        }
    }

    return parts.joinToString(" · ")
}

@Composable
private fun UpcomingEpisodeCard(
    upcoming: UpcomingEpisode,
    onClick: () -> Unit,
    onFocus: () -> Unit = {}
) {
    var focused by remember {
        mutableStateOf(false)
    }

    PosterCard(
        posterUrl = upcoming.backdrop ?: upcoming.poster,
        contentDescription = upcoming.title,
        isWatched = false,
        onClick = onClick,
        modifier = Modifier
            .width(224.dp)
            .height(146.dp)
            .padding(end = HomeRailGap)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) {
                    onFocus()
                }
            }
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            // Same 4-stop treatment as the Continue Watching
                            // cards: the old 5-stop gradient ended in TWO
                            // 100%-opaque stops, painting a flat solid-black
                            // band across the bottom third instead of a
                            // smooth fade.
                            colors = listOf(
                                Color.Black.copy(alpha = 0.08f),
                                Color.Black.copy(alpha = 0.34f),
                                Color.Black.copy(alpha = 0.80f),
                                KBVoid.copy(alpha = 0.97f)
                            )
                        )
                    )
            )

            // Top chip: the AIR DATE (or NEW SEASON flag) — the thing that
            // makes this card "upcoming". A season premiere airing TODAY
            // still shows the TODAY chip: "NEW SEASON" says what it is, but
            // on premiere day "TODAY" is the urgent part (the date below
            // already says which day) — MobLand S2 landing today read as
            // "NEW SEASON / Thu, Sep 18" instead of "TODAY".
            if (upcoming.isSeasonPremiere && upcoming.airDateLabel != "Today") {
                Text(
                    text = "NEW SEASON",
                    color = KBTextHi,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(
                            color = KBPlum,
                            shape = RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                )
            } else {
                Text(
                    text = upcoming.airDateLabel.uppercase(),
                    color = Color.Black,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(
                            color = KBAccent,
                            shape = RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }

            // Bottom block: calendar date first ("When is this on?" is the
            // primary question for an unaired episode), then show title,
            // then season/episode + episode title.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                // Calendar date ("Mon, Sep 15") — promoted ABOVE the show
                // title: on upcoming cards the date outranks the title as
                // the scanning hook, and it's the only timing info NEW
                // SEASON cards carry (their top chip is the flag, not a
                // date).
                upcoming.airDateFull
                    .takeIf { it.isNotBlank() }
                    ?.let { fullDate ->
                        Text(
                            text = fullDate,
                            color = if (focused) {
                                KBTextHi.copy(alpha = 0.80f)
                            } else {
                                KBTextLo
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }

                Text(
                    text = upcoming.title,
                    color = KBTextHi,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp)
                )

                val seLabel = "S%02d · E%02d".format(
                    upcoming.season,
                    upcoming.episode
                )
                // Show title -> S·E line -> episode title line.
                Text(
                    text = seLabel,
                    color = if (focused) {
                        KBTextHi.copy(alpha = 0.78f)
                    } else {
                        KBTextLo
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )

                upcoming.episodeTitle
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { episodeTitle ->
                        Text(
                            text = episodeTitle,
                            color = if (focused) {
                                KBTextHi.copy(alpha = 0.88f)
                            } else {
                                KBTextLo
                            },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 1.dp)
                        )
                    }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = KBTextHi.copy(alpha = 0.94f),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(
            top = 4.dp,
            bottom = 2.dp
        )
    )
}

@Composable
private fun CompactUpNextCard(
    item: UpNextItem,
    onClick: () -> Unit,
    onFocus: () -> Unit = {},
    onUpPressed: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    badgeColor: Color,
    badgeText: String
) {
    var focused by remember {
        mutableStateOf(false)
    }

    val episodeLabel = when {
        item.season != null && item.episode != null ->
            "S%02d · E%02d".format(
                item.season,
                item.episode
            )

        item.season != null ->
            "S%02d".format(item.season)

        item.episode != null ->
            "E%02d".format(item.episode)

        else -> null
    }

    val displayBadge =
        when {
            item.isSeriesFinale ->
                "SERIES FINALE"

            item.isSeasonFinale ->
                "SEASON FINALE"

            else ->
                when (item.badge) {
                    UpNextBadge.CONTINUE_WATCHING ->
                        "RESUME"

                    UpNextBadge.NEXT_UP ->
                        "NEXT UP"

                    UpNextBadge.NEW_EPISODE ->
                        "NEW EPISODE"

                    UpNextBadge.NEW_SEASON ->
                        "NEW SEASON"
                }
        }

    val subtitle = item.subtitle
        ?.removePrefix("Resume - ")
        ?.removePrefix("Up Next - ")
        ?.trim()
        ?.takeIf {
            it.isNotBlank() &&
                it != episodeLabel &&
                it != item.title
        }

    val progress = item.progressPercent
        ?.coerceIn(0f, 1f)

    // Resume cards keep their progress bar and time-left. A finale being
    // resumed still has a saved position, so treat it like a resume card;
    // plain up-next finales have no position to show.
    val isResumeCard =
        item.badge == UpNextBadge.CONTINUE_WATCHING ||
            (item.startPositionMs > 0L &&
                (item.isSeasonFinale ||
                    item.isSeriesFinale))

    val timeLeft =
    if (isResumeCard) {
        formatTimeLeft(item.remainingMinutes)
    } else {
        null
    }

    PosterCard(
        posterUrl = item.episodeThumbnail ?: item.backdrop ?: item.poster ?: "",
        contentDescription = item.title,
        isWatched = false,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier
            .width(224.dp)
            .height(146.dp)
            .padding(end = HomeRailGap)
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .onFocusChanged {
                focused = it.isFocused

                if (it.isFocused) {
                    onFocus()
                }
            }
            .onPreviewKeyEvent { event ->
                if (
                    event.type == KeyEventType.KeyDown &&
                    event.key == Key.DirectionUp
                ) {
                    onUpPressed()
                    true
                } else {
                    false
                }
            }
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.08f),
                                Color.Black.copy(alpha = 0.34f),
                                Color.Black.copy(alpha = 0.80f),
                                KBVoid.copy(alpha = 0.97f)
                            )
                        )
                    )
            )

            Text(
                text = displayBadge,
                color = KBTextHi,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(
                        color = badgeColor,
                        shape = RoundedCornerShape(4.dp)
                    )
                    .padding(
                        horizontal = 6.dp,
                        vertical = 3.dp
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomStart)
                    .padding(
                        start = 10.dp,
                        end = 10.dp,
                        bottom = if (
                            isResumeCard &&
                            progress != null &&
                            progress > 0f
                        ) {
                            15.dp
                        } else {
                            9.dp
                        }
                    )
            ) {
                Text(
                    text = item.title,
                    color = KBTextHi,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                episodeLabel?.let { label ->
                    Text(
                        text = label,
                        color = if (focused) {
                            KBTextHi.copy(alpha = 0.78f)
                        } else {
                            KBTextLo
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                item.episodeTitle
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?.let { episodeTitle ->
                        Text(
                            text = episodeTitle,
                            color = if (focused) {
                                KBTextHi.copy(alpha = 0.88f)
                            } else {
                                KBTextLo
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
            }

            item.episodesRemaining
    ?.takeIf { it > 0 }
    ?.let { remaining ->
        Text(text = if (remaining == 1) {
    "1 ep. left"
} else {
    "$remaining ep. left"
},
            color = KBTextHi,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = 10.dp,
                    bottom = if (
                        isResumeCard &&
                        progress != null &&
                        progress > 0f
                    ) {
                        15.dp
                    } else {
                        9.dp
                    }
                )
                .background(
                    color = KBVoid.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(4.dp)
                )
                .padding(
                    horizontal = 6.dp,
                    vertical = 3.dp
                )
        )
    }
        
            timeLeft?.let { label ->
                Text(
                    text = label,
                    color = KBTextHi.copy(alpha = 0.90f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(
                            color = KBVoid.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(4.dp)
                        )
                        .padding(
                            horizontal = 6.dp,
                            vertical = 3.dp
                        )
                )
            }

            if (
                isResumeCard &&
                progress != null &&
                progress > 0f
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(
                            KBTextHi.copy(alpha = 0.28f)
                        )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .height(4.dp)
                            .background(KBAccent)
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onItemClick: (MetaPreview) -> Unit,
    onOpenDetailTarget: (
        MetaPreview,
        StreamsTarget,
        String?
    ) -> Unit,
    onOpenStreams: (
        MetaPreview,
        StreamsTarget,
        String?
    ) -> Unit = { _, _, _ -> },
    onSearch: () -> Unit = {},
    onOpenGuide: () -> Unit = {},
    onOpenLibrary: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onSwitchProfile: () -> Unit = {},
    onOpenKBFolder: (String) -> Unit = {},
    onOpenCatalogGrid: (Rail) -> Unit = {},
    viewModel: HomeViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val context = LocalContext.current

    // KB collections (imported from a profile URL) interleaved with the
    // addon rails below; arrangement (pin/reorder/hide) from the manager.
    val kbViewModel: com.kennyb1201.kbstream.ui.kb.KBHomeViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel()

    // Keyed on the active profile: these are profile-scoped prefs and
    // remember{} would otherwise keep the previous profile's display
    // settings for the whole session after a switch.
    val activeProfileId = com.kennyb1201.kbstream.data.sync.ProfileManager
        .activeProfile.collectAsStateWithLifecycle().value?.id
    val showRailType by remember(activeProfileId) {
        mutableStateOf(AppPreferences.getHomeRailShowCatalogType(context))
    }
    val showRailAddon by remember(activeProfileId) {
        mutableStateOf(AppPreferences.getHomeRailShowAddonName(context))
    }
    val landscapeCards by remember(activeProfileId) {
        mutableStateOf(AppPreferences.getHomeLandscapeCards(context))
    }
    // Profile-gated: rows render only while they belong to the ACTIVE
    // profile. The ViewModel clears them on a switch, but that clear is
    // dispatched, so without this gate the profile the user just left could
    // paint for a frame before the empty list landed.
    val railsBuiltForProfile by viewModel.railsProfileId.collectAsStateWithLifecycle()
    val railsRaw by viewModel.rails.collectAsStateWithLifecycle()
    val rails = if (railsBuiltForProfile == activeProfileId) railsRaw else emptyList()
    val watchedKeys by viewModel.watchedKeys.collectAsStateWithLifecycle()
    val partialWatchedKeys by viewModel.partialWatchedKeys.collectAsStateWithLifecycle()
    val upNext by viewModel.upNext.collectAsStateWithLifecycle()
    val upcomingSchedule by
        viewModel.upcomingSchedule.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val heroMeta by viewModel.heroMeta.collectAsStateWithLifecycle()
    val heroTmdbDetail by viewModel.heroTmdbDetail.collectAsStateWithLifecycle()
    val heroBackdropUrl by viewModel.heroBackdropUrl.collectAsStateWithLifecycle()
    val heroLogoUrl by viewModel.heroLogoUrl.collectAsStateWithLifecycle()
    val heroTrailerKey by viewModel.heroTrailerKey.collectAsStateWithLifecycle()

    // Pre-warm the hero trailer resolve: on a cold start the source cache is
    // empty, so the first post-dwell resolve otherwise starts the full
    // InnerTube -> NewPipe chain only AFTER the 4s dwell elapses. Kicking the
    // resolve off in the background the moment the key arrives means the
    // post-dwell resolve is usually a cache hit (the resolve mutex coalesces
    // both callers into one network flight) and playback starts immediately.
    LaunchedEffect(heroTrailerKey) {
        // Local val: delegated state properties can't be smart-cast to
        // non-null after the isNullOrBlank() check.
        val key = heroTrailerKey
        if (
            !key.isNullOrBlank() &&
            AppPreferences.getHeroTrailerAutoplay(context)
        ) {
            runCatching {
                TrailerPlayerLauncher.resolvePlayableUrl(
                    key,
                    recordFailure = false
                )
            }
        }
    }

    // KB collections interleaved with addon rails (merged order from the
    // Collections manager: pin / reorder / hide). Computed in composable
    // context — the LazyColumn builder lambda below is LazyListScope, not
    // composable, so it must receive only finished values.
    val kbState by kbViewModel.state.collectAsStateWithLifecycle()
    val mergedEntries = remember(rails, kbState) {
        com.kennyb1201.kbstream.ui.kb.KBHomeSlots
            .buildMergedEntries(context, rails, kbState)
    }

    // The up-onto-topbar hook belongs to the first rail in DISPLAY order,
    // which a pinned collection can push away from rails[0].
    val firstDisplayedRailSourceIndex =
        mergedEntries
            .indexOfFirst {
                it is com.kennyb1201.kbstream.ui.kb.HomeEntry.AddonRail
            }
            .takeIf { it >= 0 }
            ?.let { index ->
                (
                    mergedEntries[index] as
                        com.kennyb1201.kbstream.ui.kb.HomeEntry.AddonRail
                    ).sourceIndex
            }
            ?: -1

    var showTopBar by remember {
        mutableStateOf(false)
    }

    val topBarFocusRequester = remember {
        FocusRequester()
    }

    var lastPosterFocusRequester by remember {
        mutableStateOf<FocusRequester?>(null)
    }

    var lastFocusedItemKey by remember {
        mutableStateOf<String?>(null)
    }


    // Seed hero = first focusable card: the first Continue Watching item
    // when that rail exists, else the first catalog poster. Keeps the hero
    // in sync with where D-pad focus lands on entry (the topmost card);
    // previously this only looked at catalog rails, so returning home with
    // focus restored onto Continue Watching showed the first rail's poster
    // in the hero instead.
    val firstHomeItem = remember(upNext, rails) {
        upNext.firstOrNull()?.let { cw ->
            MetaPreview(
                id = cw.parentId ?: cw.id,
                type = cw.parentType ?: "movie",
                name = cw.title,
                poster = cw.poster
            )
        } ?: rails.asSequence()
            .flatMap {
                it.items.asSequence()
            }
            .firstOrNull()
    }

    // False until the user moves focus themselves; gates the auto-seed
    // effect below so it stops overriding the hero after first interaction.
    var userAdjustedFocus by remember {
        mutableStateOf(false)
    }

    var focusedItem by remember {
        mutableStateOf<MetaPreview?>(firstHomeItem)
    }

    // KB folder tile currently under focus (null = a normal catalog item
    // owns the hero). The manifest supplies the folder's heroBackdropUrl /
    // titleLogoUrl directly, so the hero swaps to the collection's own
    // artwork without probing addons — no meta/trailer resolution happens
    // for folders.
    var focusedFolder by remember {
        mutableStateOf<KBFolder?>(null)
    }

    var focusedContinueWatchingItem by remember {
        mutableStateOf<UpNextItem?>(null)
    }

    // Scroll position of the rails LazyColumn; read/written by selectHero so
    // the Continue Watching row can be cleared out of the viewport on rail
    // focus (see below).
    val railListState = rememberLazyListState()

    var heroTrailerReady by remember {
        mutableStateOf(false)
    }

    fun openTopBar(
        requester: FocusRequester
    ) {
        lastPosterFocusRequester = requester
        showTopBar = true
    }

    fun dismissTopBar() {
        showTopBar = false
        lastPosterFocusRequester?.requestFocus()
    }

    // Long-press menu on Continue Watching cards.
    var continueWatchingMenu by remember {
        mutableStateOf<UpNextItem?>(null)
    }

    fun dismissContinueWatchingMenu() {
        continueWatchingMenu = null
        lastPosterFocusRequester?.requestFocus()
    }

    fun openContinueWatchingMenu(item: UpNextItem) {
        continueWatchingMenu = item
    }

    // Long-press menu on regular poster rails (movies/series catalogs).
    var posterMenu by remember {
        mutableStateOf<PosterMenuTarget?>(null)
    }

    // "Add to list…" picker target (title + which lists to offer).
    var addToListTarget by remember {
        mutableStateOf<LibraryAddTarget?>(null)
    }

    fun dismissPosterMenu() {
        posterMenu = null
        lastPosterFocusRequester?.requestFocus()
    }

    // KB-style focus landing (ported from KBTV's ModernHomeContent /
    // ModernHomeRowsList). No snap scrolls anywhere: the rows LazyColumn is
    // wrapped in a BringIntoViewSpec so that when a catalog rail takes
    // focus, Compose's native focus bring-into-view scroll lands the row
    // at a fixed inset below the viewport top. Deterministic landing means:
    //  - no Continue Watching / Upcoming sliver peeking under the hero
    //    (landing on a catalog rail always pushes those rows fully above),
    //  - no bounce (the spec returns the same distance for every child of
    //    the focused row, so the scroll target never changes mid-flight),
    //  - the hero stays visible above the first rail exactly like KB.
    // KB uses MODERN_ROW_HEADER_FOCUS_INSET = 40.dp for the same job;
    // RailHeaderFocusInset mirrors that (defined with the Home constants).
    val density = LocalDensity.current
    val railRowsBringIntoViewSpec = remember(density) {
        val topInsetPx = with(density) { RailHeaderFocusInset.toPx() }
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(
                offset: Float,
                size: Float,
                containerSize: Float
            ): Float {
                val currentLeadingEdge = offset
                // Already resting at the landing line: done. This also keeps
                // the spring quiet during horizontal focus moves (no bounce).
                if (abs(currentLeadingEdge - topInsetPx) < 1f) return 0f
                val distance = currentLeadingEdge - topInsetPx
                // Never force the list above its start (mirrors KB's
                // canScrollBackward guard).
                if (distance < 0f && !railListState.canScrollBackward) return 0f
                return distance
            }
        }
    }

    // Horizontal counterpart (KB's ModernHomeRows horizontalBringIntoViewSpec):
    // a focused card lands with its leading edge at the rail's start padding,
    // so the row tracks focus like KB's rails instead of the minimal
    // default scroll (cards never disappear past the left edge). Wrapping
    // each rail's LazyRow with this also SHADOWS the vertical spec above,
    // which would otherwise leak into the rows via CompositionLocalProvider.
    val railCardsBringIntoViewSpec = remember(density) {
        val startPaddingPx = with(density) { RailHorizontalStartPadding.toPx() }
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(
                offset: Float,
                size: Float,
                containerSize: Float
            ): Float {
                val childSize = abs(size)
                val initialTarget = startPaddingPx
                val spaceAvailable = containerSize - initialTarget
                val targetForLeadingEdge =
                    if (childSize <= containerSize && spaceAvailable < childSize) {
                        containerSize - childSize
                    } else {
                        initialTarget
                    }
                return offset - targetForLeadingEdge
            }
        }
    }

    fun selectHero(
        item: MetaPreview
    ) {
        userAdjustedFocus = true
        focusedItem = item
        focusedFolder = null
        focusedContinueWatchingItem = null
    }

    /**
     * Focus landed on a card inside the Continue Watching OR Upcoming rows:
     * both drive the hero's episode/progress view. No snap scrolls here
     * anymore — the rows LazyColumn's BringIntoViewSpec handles landing
     * deterministically, and the user's own row is never scrolled away.
     */
    fun selectContinueWatchingHero(
        item: MetaPreview,
        upNextItem: UpNextItem
    ) {
        userAdjustedFocus = true
        focusedItem = item
        focusedFolder = null
        focusedContinueWatchingItem = upNextItem
    }

    // Re-apply rail display settings changed in Settings while we were away
    // (catalog-type / addon-name are read per-composition; the digital
    // release filter needs a rail rebuild from the warm cache).
    LaunchedEffect(Unit) {
        viewModel.onHomeResumed()
        // Pick up Collections-manager edits (import / pin / reorder / hide)
        // made while we were away.
        kbViewModel.load()
    }

    LaunchedEffect(showTopBar) {
        if (showTopBar) {
            topBarFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(
        firstHomeItem?.id,
        firstHomeItem?.type
    ) {
        if (
            !userAdjustedFocus &&
            firstHomeItem != null
        ) {
            focusedItem = firstHomeItem
            // When the seed came from the Continue Watching rail, also
            // attach its UpNextItem so the hero renders the episode /
            // progress view exactly like focusing the card does.
            if (focusedContinueWatchingItem == null) {
                focusedContinueWatchingItem = upNext.firstOrNull()?.takeIf { cw ->
                    (cw.parentId ?: cw.id) == firstHomeItem.id
                }
            }
        }
    }

    LaunchedEffect(
    focusedItem?.id,
    focusedItem?.type,
    focusedContinueWatchingItem?.id
) {
    heroTrailerReady = false

    focusedItem?.let {
        viewModel.resolveHeroMeta(it)
        delay(HeroTrailerDwellMs)
        heroTrailerReady = true
    }
}

    LifecycleEventEffect(
        Lifecycle.Event.ON_RESUME
    ) {
        viewModel.refreshUpNext()
        viewModel.refreshWatchedStatusForCurrentRails()
    }

    fun openUpNext(
        item: UpNextItem,
        openInStreamsScreen: Boolean = false,
        startAtBeginning: Boolean = false,
        openDetailsOnly: Boolean = false
    ) {
        val parentId = item.parentId
        val parentType = item.parentType

        if (
            parentId.isNullOrBlank() ||
            parentType.isNullOrBlank()
        ) {
            return
        }

        val detail = MetaPreview(
            id = parentId,
            type = parentType,
            name = item.title,
            poster = item.poster,
            background = item.backdrop,
            description = item.episodeDescription,
            logo = item.clearLogo
        )

        // Series-episode cards carry the show name in [item.title] with the
        // episode details in separate fields (season/episode/episodeTitle).
        // The streams picker (and everything downstream that re-parses the
        // title) needs the canonical "Sx Ey • Name" shape, so compose it when
        // the card title doesn't already contain the marker.
        val targetTitle = run {
            val hasMarker =
                item.season != null &&
                    item.episode != null &&
                    Regex(
                        "S\\s*0*${item.season}\\s*E\\s*0*${item.episode}\\b",
                        RegexOption.IGNORE_CASE
                    ).containsMatchIn(item.title)
            if (
                parentType == "series" &&
                item.season != null &&
                item.episode != null &&
                !hasMarker
            ) {
                buildString {
                    append(item.title)
                    append(" S${item.season} E${item.episode}")
                    item.episodeTitle
                        ?.takeIf { it.isNotBlank() }
                        ?.let { append(" • $it") }
                }
            } else {
                item.title
            }
        }

        val target = StreamsTarget(
            contentType = parentType,
            streamId = item.episodeStreamId
                ?: item.parentId
                ?: item.id,
            title = targetTitle,
            displayName = item.title,
            season = item.season,
            episode = item.episode,
            resumePositionMs =
                if (startAtBeginning) {
                    0L
                } else {
                    item.startPositionMs
                },
            runtimeMinutes =
                item.runtimeMinutes
                    ?.takeIf { it > 0 },
            // "Play from Beginning" is the one path that wants position 0
            // applied as-is: a launch carrying no position of its own resumes
            // the saved watch-history progress instead.
            startFromBeginning = startAtBeginning
        )

        if (openDetailsOnly) {
            selectHero(detail)
            onItemClick(detail)
        } else if (openInStreamsScreen) {

            // "Play Manually" goes straight to the streams picker and
            // intentionally bypasses Auto-select.
            onOpenStreams(
                detail,
                target,
                item.poster
            )
        } else {

            selectHero(detail)

            onOpenDetailTarget(
                detail,
                target,
                item.poster
            )
        }
    }

    val firstRailNeedsUpHook =
        upNext.isEmpty()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(
                vertical = TvSafeAreaVertical
            )
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Hero owner: a focused KB folder tile (manifest artwork)
            // or a focused catalog/Continue-Watching item. Folders render
            // even when no catalog item has been focused yet.
            val heroFolder = focusedFolder
            val folderBackdrop = heroFolder?.let { f ->
                f.heroBackdropUrl?.takeIf { it.isNotBlank() }
                    ?: f.coverImageUrl?.takeIf { it.isNotBlank() }
            }
            val folderLogo = heroFolder?.titleLogoUrl?.takeIf { it.isNotBlank() }
            // KB's ModernHomeModels blanks the hero title for
            // hideTitle folders (the clearlogo stands alone; with no logo
            // the hero shows artwork only).
            val folderPreview = heroFolder?.let { f ->
                MetaPreview(
                    id = "kb-folder:${f.id ?: f.title}",
                    type = "movie",
                    name = if (f.hideTitle) "" else f.title
                )
            }
            (folderPreview ?: focusedItem)?.let {
                // KB-style proportional hero: give the rails a fixed
                // fraction of the real screen height, and the hero whatever
                // remains (minus one row title + breathing room). Scales to
                // any TV density, unlike the old fixed 300.dp which pushed
                // the first rail's posters off the bottom on shorter
                // panels.
                val configuration = LocalConfiguration.current
                val screenHeight = configuration.screenHeightDp.dp
                val railsFraction = 0.52f
                val heroComputedHeight =
                    (screenHeight * (1f - railsFraction))
                        .coerceAtMost(HomeHeroHeight)

                HomeHero(
                    preview = it,
                    heroHeight = heroComputedHeight,
                    meta = if (heroFolder != null) null else heroMeta,
                    tmdbDetail = if (heroFolder != null) null else heroTmdbDetail,
                    heroBackdropUrl = if (heroFolder != null) folderBackdrop else heroBackdropUrl,
                    heroLogoUrl = if (heroFolder != null) folderLogo else heroLogoUrl,
                    trailerKey = if (heroFolder != null) null else heroTrailerKey,
                    autoPlayTrailer =
                        heroTrailerReady &&
                            focusedContinueWatchingItem == null &&
                            // Settings > Playback: hero trailer autoplay toggle
                            com.kennyb1201.kbstream.ui.settings.AppPreferences
                                .getHeroTrailerAutoplay(context),
                    // Settings > Interface: mute hero trailers toggle
                    muted =
                        com.kennyb1201.kbstream.ui.settings.AppPreferences
                            .getHeroTrailerMuted(context),
                    continueWatchingItem =
                        focusedContinueWatchingItem,
                    // Collection manifests supply their own wordmark logo —
                    // render it larger than the shared TMDB hero logo.
                    heroLogoWidth =
                        if (heroFolder != null) {
                            CollectionHeroLogoWidth
                        } else {
                            HeroLogoWidth
                        },
                    heroLogoHeight =
                        if (heroFolder != null) {
                            CollectionHeroLogoHeight
                        } else {
                            HeroLogoHeight
                        }
                )
            }

            // KB parity: the rows list gets the custom vertical
            // BringIntoViewSpec (fixed header landing inset). The opaque
            // hero spacer below is what lets KB's fixed-viewport trick
            // work with a flowing hero: focus scrolling the catalog rails
            // slides the spacer under the hero instead of leaving the
            // CW/Upcoming sliver peeking out from behind it.
            CompositionLocalProvider(
                LocalBringIntoViewSpec provides railRowsBringIntoViewSpec
            ) {
                // KB parity: bottom contentPadding equals the rows
                // viewport so the LAST rail can also land at the focus
                // inset — without it the tail rows stop short and leave a
                // sliver of the rows above them peeking under the hero.
                val screenHeight = LocalConfiguration.current.screenHeightDp.dp
                LazyColumn(
                    state = railListState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(
                        top = 0.dp,
                        bottom = screenHeight * 0.52f
                    ),
                    verticalArrangement =
                        Arrangement.spacedBy(
                            RailSectionGap
                        )
                ) {
                    item(key = "hero_spacer") {
                        // Opaque spacer that prevents CW progress bar
                        // from peeking below the hero gradient.
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(HeroToFirstRailGap)
                                .background(Color.Black)
                        )
                    }

                    if (upNext.isNotEmpty()) {
                        item(
                            key = "continue_watching"
                        ) {
                            Column(
                                modifier = Modifier.padding(
                                    start = TvSafeAreaHorizontal,
                                    top = 0.dp,
                                    bottom = 8.dp
                                )
                            ) {
                                SectionTitle(
                                    "Continue Watching"
                                )

                                CompositionLocalProvider(
                                    LocalBringIntoViewSpec provides railCardsBringIntoViewSpec
                                ) {
                                    LazyRow(
                                        contentPadding =
                                            PaddingValues(
                                                start =
                                                    RailHorizontalStartPadding,
                                                end =
                                                    TvSafeAreaHorizontal,
                                                top = 10.dp,
                                                bottom = 12.dp
                                            ),
                                        horizontalArrangement =
                                            Arrangement.spacedBy(
                                                0.dp
                                            )
                                    ) {
                                        items(
                                            items = upNext,
                                            key = { it.id }
                                        ) { item ->
                                            val requester =
                                                remember {
                                                    FocusRequester()
                                                }

                                            // Carry the row's own backdrop + clearLogo
                                            // into the hero preview: while TMDB
                                            // resolution is pending the hero renders
                                            // THESE instead of falling back to the
                                            // poster (which showed as an ugly zoomed
                                            // backdrop with a plain-text title).
                                            val hero =
                                                MetaPreview(
                                                    id =
                                                        item.parentId
                                                            ?: item.id,
                                                    type =
                                                        item.parentType
                                                            ?: "movie",
                                                    name =
                                                        item.title,
                                                    poster =
                                                        item.poster,
                                                    background =
                                                        item.backdrop,
                                                    logo =
                                                        item.clearLogo
                                                )

                                            CompactUpNextCard(
                                                item = item,
                                                onClick = {
                                                    openUpNext(
                                                        item
                                                    )
                                                },
                                                onLongClick = {
                                                    // Remember this card's requester so
                                                    // dismissing the menu restores focus
                                                    // to the exact card that opened it.
                                                    lastPosterFocusRequester =
                                                        requester
                                                    openContinueWatchingMenu(
                                                        item
                                                    )
                                                },
                                                onFocus = {
                                                    selectContinueWatchingHero(
                                                        hero,
                                                        item
                                                    )
                                                },
                                                onUpPressed = {
                                                    openTopBar(
                                                        requester
                                                    )
                                                },
                                                focusRequester =
                                                    requester,
                                                badgeColor =
                                                    when {
                                                        item.isSeriesFinale ->
                                                            KBDanger

                                                        item.isSeasonFinale ->
                                                            KBRust

                                                        else ->
                                                            when (
                                                                item.badge
                                                            ) {
                                                                UpNextBadge.CONTINUE_WATCHING ->
                                                                    KBAccent

                                                                UpNextBadge.NEXT_UP ->
                                                                    KBSteel

                                                                UpNextBadge.NEW_EPISODE ->
                                                                    KBSuccess

                                                                UpNextBadge.NEW_SEASON ->
                                                                    KBPlum
                                                            }
                                                    },
                                                badgeText =
                                                    when {
                                                        item.isSeriesFinale ->
                                                            "SERIES FINALE"

                                                        item.isSeasonFinale ->
                                                            "SEASON FINALE"

                                                        else ->
                                                            when (
                                                                item.badge
                                                            ) {
                                                                UpNextBadge.CONTINUE_WATCHING ->
                                                                    "RESUME"

                                                                UpNextBadge.NEXT_UP ->
                                                                    "NEXT UP"

                                                                UpNextBadge.NEW_EPISODE ->
                                                                    "NEW"

                                                                UpNextBadge.NEW_SEASON ->
                                                                    "NEW SEASON"
                                                            }
                                                    }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (upcomingSchedule.isNotEmpty()) {
                        item(
                            key = "upcoming_schedule"
                        ) {
                            Column(
                                modifier = Modifier.padding(
                                    start = TvSafeAreaHorizontal,
                                    top = 0.dp,
                                    bottom = 8.dp
                                )
                            ) {
                                SectionTitle(
                                    "Upcoming"
                                )

                                CompositionLocalProvider(
                                    LocalBringIntoViewSpec provides railCardsBringIntoViewSpec
                                ) {
                                    LazyRow(
                                        contentPadding =
                                            PaddingValues(
                                                start =
                                                    RailHorizontalStartPadding,
                                                end =
                                                    TvSafeAreaHorizontal,
                                                top = 10.dp,
                                                bottom = 12.dp
                                            ),
                                        horizontalArrangement =
                                            Arrangement.spacedBy(
                                                0.dp
                                            )
                                    ) {
                                        items(
                                            items = upcomingSchedule,
                                            key = { it.id }
                                        ) { upcoming ->
                                            val heroItem =
                                                UpNextItem(
                                                    id = upcoming.id,
                                                    title = upcoming.title,
                                                    poster = upcoming.poster,
                                                    badge = UpNextBadge.NEXT_UP,
                                                    backdrop = upcoming.backdrop,
                                                    parentId = upcoming.parentId,
                                                    parentType = upcoming.parentType,
                                                    // Hero renders "Airs <label>" +
                                                    // the calendar date for these.
                                                    airDateLabel = upcoming.airDateLabel,
                                                    airDateFull = upcoming.airDateFull,
                                                    season = upcoming.season,
                                                    episode = upcoming.episode,
                                                    episodeTitle = upcoming.episodeTitle
                                                )

                                            Box(
                                                modifier = Modifier
                                                    .width(224.dp)
                                                    .height(146.dp + PosterFocusHeadroom)
                                                    .padding(end = HomeRailGap),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                UpcomingEpisodeCard(
                                                    upcoming = upcoming,
                                                    onClick = {
                                                        openUpNext(
                                                            heroItem,
                                                            openDetailsOnly = true
                                                        )
                                                    },
                                                    onFocus = {
                                                        selectContinueWatchingHero(
                                                            MetaPreview(
                                                                id = upcoming.parentId,
                                                                type = upcoming.parentType,
                                                                name = upcoming.title,
                                                                poster = upcoming.poster,
                                                                background = upcoming.backdrop
                                                            ),
                                                            heroItem
                                                        )
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    when {
                        // Full-screen spinner only when there is nothing to show
                        // yet: a rebuild with existing rails keeps them visible
                        // (rails stream in progressively on top of the old list
                        // instead of flashing a loader on every refresh).
                        isLoading && rails.isEmpty() -> {
                            item(key = "loading") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        color = KBAccent,
                                        strokeWidth = 3.dp
                                    )
                                }
                            }
                        }

                        error != null -> {
                            item(key = "error") {
                                Text(
                                    text =
                                        "Error: $error",
                                    modifier =
                                        Modifier.padding(
                                            24.dp
                                        )
                                )
                            }
                        }

                        rails.isEmpty() -> {
                            item(key = "empty") {
                                // Clicking (OK on the remote) retries the
                                // rail build immediately - no need to leave
                                // Home or poke a setting when a cold-start
                                // load failed.
                                KBCard(
                                    onClick = {
                                        viewModel.refreshRailsOnly()
                                    },
                                    modifier = Modifier
                                        .padding(24.dp)
                                ) {
                                    Text(
                                        text =
                                            "No catalogs available. Press OK to retry, or add an addon to get started."
                                    )
                                }
                            }
                        }

                        else -> {
                            // KB collections interleave with addon rails:
                            // the merged order was computed above in composable
                            // context (pin / reorder / hide from the manager;
                            // unarranged collections sit after the addon rails).
                            itemsIndexed(
                                items = mergedEntries,
                                key = { _, entry ->
                                    when (entry) {
                                        is com.kennyb1201.kbstream.ui.kb.HomeEntry.AddonRail ->
                                            "rail|" + entry.sourceIndex + "|" +
                                                entry.rail.addonName + ":" +
                                                entry.rail.catalogName + ":" + entry.rail.type
                                        is com.kennyb1201.kbstream.ui.kb.HomeEntry.Collection ->
                                            "kb|" + (entry.collection.id ?: entry.collection.title)
                                    }
                                }
                            ) { _, entry ->
                                when (val e = entry) {
                                    is com.kennyb1201.kbstream.ui.kb.HomeEntry.Collection ->
                                        KBHomeCollectionRail(
                                            collection = e.collection,
                                            onOpenFolder = onOpenKBFolder,
                                            onFolderFocused = { folder: KBFolder ->
                                                userAdjustedFocus = true
                                                focusedFolder = folder
                                                focusedContinueWatchingItem = null
                                            }
                                        )
                                    is com.kennyb1201.kbstream.ui.kb.HomeEntry.AddonRail -> {
                                        val rail = e.rail
                                        val railIndex = e.sourceIndex

                                        Column(
                                            modifier = Modifier.padding(
                                                start = TvSafeAreaHorizontal,
                                                top = 0.dp,
                                                bottom = 8.dp
                                            )
                                        ) {
                                            SectionTitle(
                                                homeRailTitle(
                                                    catalogName = rail.catalogName,
                                                    addonName = rail.addonName,
                                                    type = rail.type,
                                                    showType = showRailType,
                                                    showAddon = showRailAddon
                                                )
                                            )

                                    val railRowState = rememberLazyListState()

                                    InfiniteRailPageHandler(
                                        listState = railRowState,
                                        itemCount = rail.items.size,
                                        railKey = rail.addonName + "::" +
                                            rail.catalogId + "::" + rail.type,
                                        onLoadMore = viewModel::loadMoreForRail
                                    )

                                    CompositionLocalProvider(
                                        LocalBringIntoViewSpec provides railCardsBringIntoViewSpec
                                    ) {
                                        LazyRow(
                                            state = railRowState,
                                            contentPadding = PaddingValues(
                                                start = RailHorizontalStartPadding,
                                                end = TvSafeAreaHorizontal,
                                                top = RailTopContentPadding,
                                                bottom = RailBottomContentPadding
                                            ),
                                            horizontalArrangement =
                                                Arrangement.spacedBy(0.dp)
                                        ) {
                                            items(
                                                items = rail.items,
                                                key = {
                                                    "${it.type}:${it.id}"
                                                }
                                            ) { meta ->

                                                val requester = remember {
                                                    FocusRequester()
                                                }

                                                val watched =
                                                    viewModel.watchedKey(
                                                        meta.id,
                                                        meta.type
                                                    ) in watchedKeys

                                                // Eye badge: started but not
                                                // finished. Never shown when the
                                                // completed checkmark resolves.
                                                val watchedPartially =
                                                    !watched &&
                                                        viewModel.watchedKey(
                                                            meta.id,
                                                            meta.type
                                                        ) in partialWatchedKeys

                                                val isFirstRailFirstRow =
                                                    railIndex == firstDisplayedRailSourceIndex &&
                                                        firstRailNeedsUpHook

                                                val cardWidth =
                                                    if (landscapeCards) {
                                                        HomeLandscapeWidth
                                                    } else {
                                                        HomePosterWidth
                                                    }

                                                val cardHeight =
                                                    if (landscapeCards) {
                                                        HomeLandscapeHeight
                                                    } else {
                                                        HomePosterHeight
                                                    }

                                                val posterModifier = Modifier
                                                    .offset(y = (-3).dp)
                                                    .focusRequester(requester)
                                                    .width(cardWidth)
                                                    .height(cardHeight)
                                                    .onFocusChanged { focusState ->
                                                        if (focusState.isFocused) {
                                                            lastFocusedItemKey = "${meta.type}:${meta.id}"
                                                            selectHero(meta)
                                                        }
                                                    }
                                                    .then(
                                                        if (isFirstRailFirstRow) {
                                                            Modifier.onPreviewKeyEvent { event ->
                                                                if (
                                                                    event.type == KeyEventType.KeyDown &&
                                                                    event.key == Key.DirectionUp
                                                                ) {
                                                                    openTopBar(requester)
                                                                    true
                                                                } else {
                                                                    false
                                                                }
                                                            }
                                                        } else {
                                                            Modifier
                                                        }
                                                    )

                                                Box(
                                                    modifier = Modifier
                                                        .width(cardWidth)
                                                        .height(
                                                            cardHeight +
                                                                PosterFocusHeadroom
                                                        )
                                                        .padding(
                                                            end = HomeRailGap
                                                        ),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    val art =
                                                        rail.landscapeArt[
                                                            "${meta.type}:${meta.id}"
                                                        ]

                                                    if (landscapeCards) {
                                                        LandscapeCard(
                                                            backdropUrl = art?.first
                                                                ?: meta.background,
                                                            logoUrl = art?.second
                                                                ?: meta.logo,
                                                            fallbackTitle = meta.name,
                                                            contentDescription = meta.name,
                                                            isWatched = watched,
                                                            isPartiallyWatched = watchedPartially,
                                                            onClick = {
                                                                selectHero(meta)
                                                                onItemClick(meta)
                                                            },
                                                            onLongClick = {
                                                                lastPosterFocusRequester =
                                                                    requester
                                                                posterMenu =
                                                                    PosterMenuTarget(
                                                                        meta,
                                                                        rail
                                                                    )
                                                            },
                                                            modifier = posterModifier
                                                        )
                                                    } else {
                                                        PosterCard(
                                                            posterUrl = meta.poster,
                                                            contentDescription = meta.name,
                                                            isWatched = watched,
                                                            isPartiallyWatched = watchedPartially,
                                                            onClick = {
                                                                selectHero(meta)
                                                                onItemClick(meta)
                                                            },
                                                            onLongClick = {
                                                                lastPosterFocusRequester =
                                                                    requester
                                                                posterMenu =
                                                                    PosterMenuTarget(
                                                                        meta,
                                                                        rail
                                                                    )
                                                            },
                                                            modifier = posterModifier,
                                                            onPosterError = { throwable ->
                                                                Log.e(
                                                                    "HOME_UI",
                                                                    "Catalog poster load failed, " +
                                                                        "title=${meta.name}, " +
                                                                        "poster=${meta.poster}",
                                                                    throwable
                                                                )
                                                            }
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
                        }
                    }

                    item(key = "bottom_spacer") {
                        Spacer(
                            modifier = Modifier.height(
                                0.dp
                            )
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = showTopBar,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(
                                    alpha = .96f
                                ),
                                Color.Black.copy(
                                    alpha = .72f
                                ),
                                Color.Transparent
                            )
                        )
                    )
            ) {
                TopActionBar(
                    onSearch = onSearch,
                    onOpenGuide = onOpenGuide,
                    onOpenLibrary = onOpenLibrary,
                    onOpenSettings = onOpenSettings,
                    onSwitchProfile = onSwitchProfile,
                    firstActionFocusRequester =
                        topBarFocusRequester,
                    onDismiss = {
                        dismissTopBar()
                    }
                )
            }
        }

        // Long-press menu for Continue Watching cards.
        continueWatchingMenu?.let { menuItem ->
            PosterContextMenu(
                title = menuItem.title,
                subtitle = buildString {
                    val seasonEpisode = listOfNotNull(
                        menuItem.season?.let { "S%02d".format(it) },
                        menuItem.episode?.let { "E%02d".format(it) }
                    ).joinToString(" · ")
                    if (seasonEpisode.isNotBlank()) {
                        append(seasonEpisode)
                    }
                    menuItem.episodeTitle
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { episodeName ->
                            if (isNotEmpty()) append(" · ")
                            append(episodeName)
                        }
                }.ifBlank { null },
                actions = listOf(
                    PosterContextAction(
                        label = "Add to Library",
                        description = "Save the show to My List" +
                            (if (viewModel.simklConnectedForLibrary()) ", Simkl" else "") +
                            (if (viewModel.mdbListConnectedForLibrary()) " and MDBList" else "")
                    ) {
                        val selectedItem = menuItem
                        continueWatchingMenu = null
                        val showType = selectedItem.parentType?.lowercase()
                            ?: selectedItem.id.substringBefore(':').lowercase()
                        // parentId is the SHOW's own id ("tt12345" or
                        // "tmdb:123"); the row's id is a stream key
                        // ("tt12345:2:5"). Split whichever one the item
                        // actually has: the old numeric-TMDB-only read
                        // produced a null id for IMDB-keyed shows, and an
                        // add with no id at all was dropped silently — the
                        // press looked like it did nothing.
                        val showIds = LibraryIds.splitFirst(
                            selectedItem.parentId,
                            selectedItem.id
                        )
                        viewModel.addToLibrary(
                            mediaType = showType,
                            imdbId = showIds.imdbId,
                            tmdbId = showIds.tmdbId,
                            title = selectedItem.showTitle ?: selectedItem.title,
                            posterUrl = selectedItem.poster
                        )
                    },
                    PosterContextAction(
                        label = "Add to list…",
                        description = "Pick a personal list or watchlist"
                    ) {
                        val selectedItem = menuItem
                        continueWatchingMenu = null
                        // parentId is the SHOW's own id ("tt12345" or
                        // "tmdb:123"); the row's id is a stream key
                        // ("tt12345:2:5"). Split whichever one the item
                        // actually has: the old numeric-TMDB-only read
                        // produced a null id for IMDB-keyed shows, and an
                        // add with no id at all was dropped silently — the
                        // press looked like it did nothing.
                        val showIds = LibraryIds.splitFirst(
                            selectedItem.parentId,
                            selectedItem.id
                        )
                        addToListTarget = LibraryAddTarget(
                            mediaType = selectedItem.parentType?.lowercase()
                                ?: selectedItem.id.substringBefore(':').lowercase(),
                            imdbId = showIds.imdbId,
                            tmdbId = showIds.tmdbId,
                            title = selectedItem.showTitle ?: selectedItem.title,
                            posterUrl = selectedItem.poster
                        )
                    },
                    PosterContextAction(
                        label = "Go to Details",
                        description = "Open this title's detail page"
                    ) {
                        val selectedItem = menuItem
                        continueWatchingMenu = null
                        openUpNext(
                            selectedItem,
                            openDetailsOnly = true
                        )
                    },
                    PosterContextAction(
                        label = "Play Manually",
                        description = if (menuItem.startPositionMs > 0L) {
                            "Open the streams picker - still resumes at your progress"
                        } else {
                            "Open the streams picker"
                        }
                    ) {
                        val selectedItem = menuItem
                        continueWatchingMenu = null
                        openUpNext(
                            selectedItem,
                            openInStreamsScreen = true,
                            startAtBeginning = false
                        )
                    },
                    PosterContextAction(
                        label = "Play from Beginning",
                        description = "Start over from the beginning"
                    ) {
                        val selectedItem = menuItem
                        continueWatchingMenu = null
                        openUpNext(
                            selectedItem,
                            startAtBeginning = true
                        )
                    },
                    PosterContextAction(
                        label = "Remove",
                        description = "Hide this from Continue Watching",
                        isDestructive = true
                    ) {
                        val selectedItem = menuItem
                        continueWatchingMenu = null
                        viewModel.removeFromContinueWatching(
                            selectedItem
                        )
                        lastPosterFocusRequester?.requestFocus()
                    }
                ),
                onDismiss = {
                    dismissContinueWatchingMenu()
                }
            )
        }

        // Long-press menu for regular poster rails (movies/series catalogs).
        posterMenu?.let { target ->
            // Same key the rail badge uses, so the toggle always matches what
            // the poster currently shows: "Mark as Unwatched" when the badge
            // is visible, "Mark as Watched" otherwise.
            val isWatched =
                viewModel.watchedKey(
                    target.meta.id,
                    target.meta.type
                ) in watchedKeys

            // The id the rail card actually carries: TMDB rails use
            // "tmdb:123", add-on catalogs use "tt12345". Splitting it keys
            // the badge and the add off the same id, so a title saved from
            // an add-on rail still shows "In Library ✓" here.
            val railIds = LibraryIds.split(target.meta.id)
            val railInLibrary = viewModel.isInLocalLibrary(
                target.meta.type,
                railIds.imdbId,
                railIds.tmdbId
            )

            PosterContextMenu(
                title = target.meta.name,
                actions = listOf(
                    PosterContextAction(
                        label = if (railInLibrary) {
                            "In Library ✓"
                        } else {
                            "Add to Library"
                        },
                        description = if (railInLibrary) {
                            "Already on this profile's My List"
                        } else {
                            "Save to My List" +
                                (if (viewModel.simklConnectedForLibrary()) ", Simkl" else "") +
                                (if (viewModel.mdbListConnectedForLibrary()) " and MDBList" else "")
                        }
                    ) {
                        val selected = target
                        posterMenu = null
                        if (!railInLibrary) {
                            viewModel.addToLibrary(
                                mediaType = selected.meta.type,
                                imdbId = railIds.imdbId,
                                tmdbId = railIds.tmdbId,
                                title = selected.meta.name,
                                year = selected.meta.yearOrNull,
                                posterUrl = selected.meta.poster
                            )
                        }
                        lastPosterFocusRequester?.requestFocus()
                    },
                    PosterContextAction(
                        label = "Add to list…",
                        description = "Pick a personal list or watchlist"
                    ) {
                        val selected = target
                        posterMenu = null
                        addToListTarget = LibraryAddTarget(
                            mediaType = selected.meta.type,
                            imdbId = railIds.imdbId,
                            tmdbId = railIds.tmdbId,
                            title = selected.meta.name,
                            year = selected.meta.yearOrNull,
                            posterUrl = selected.meta.poster
                        )
                    },
                    PosterContextAction(
                        label = "Open in Grid",
                        description = "Browse this whole catalog as a poster grid"
                    ) {
                        posterMenu = null
                        target.rail?.let(onOpenCatalogGrid)
                    },
                    PosterContextAction(
                        label = "Go to Details",
                        description = "Open this title's detail page"
                    ) {
                        posterMenu = null
                        selectHero(target.meta)
                        onItemClick(target.meta)
                    },
                    PosterContextAction(
                        label = if (isWatched) {
                            "Mark as Unwatched"
                        } else {
                            "Mark as Watched"
                        },
                        description = if (isWatched) {
                            "Clear watched status on this device and Simkl"
                        } else {
                            "Show this title as watched"
                        }
                    ) {
                        posterMenu = null
                        if (isWatched) {
                            viewModel.markUnwatched(target.meta)
                        } else {
                            viewModel.markAsWatched(target.meta)
                        }
                        lastPosterFocusRequester?.requestFocus()
                    }
                ),
                onDismiss = {
                    dismissPosterMenu()
                }
            )
        }

        addToListTarget?.let { target ->
            LibraryAddToListDialog(
                mediaType = target.mediaType,
                imdbId = target.imdbId,
                tmdbId = target.tmdbId,
                title = target.title,
                year = target.year,
                posterUrl = target.posterUrl,
                onDismiss = { addToListTarget = null }
            )
        }
    }
}

/**
 * Target for the long-press menu on a regular poster rail card. Carries the
 * owning rail so "Open in Grid" can browse the whole catalog.
 */
private data class PosterMenuTarget(
    val meta: MetaPreview,
    val rail: Rail? = null
)

/**
 * Invisible card-sized sentinel that lives at the end of a rail's LazyRow.
 * When the user scrolls far enough that this slot becomes the last visible
 * item in the rail column, it requests the rail's next catalog page from
 * the ViewModel (infinite scroll). Purely invisible — no visual footprint.
 */
/**
 * Infinite scroll for Home rails — same pattern as the genres screen
 * (InfiniteTagRailHandler): when the user scrolls within [threshold] items
 * of the rail's end, ask the ViewModel for the catalog's next page. Keys
 * off the rail's live item count so each append re-arms the check.
 */
@Composable
private fun InfiniteRailPageHandler(
    listState: LazyListState,
    itemCount: Int,
    railKey: String?,
    onLoadMore: (String) -> Unit
) {
    LaunchedEffect(listState, itemCount, railKey) {
        snapshotFlow {
            val lastVisibleIndex =
                listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisibleIndex to itemCount
        }
            .distinctUntilChanged()
            .collect { (lastVisibleIndex, totalItems) ->
                val threshold = 6
                val shouldLoadMore =
                    railKey != null &&
                        totalItems > 0 &&
                        lastVisibleIndex >= totalItems - threshold

                if (shouldLoadMore) {
                    onLoadMore(railKey!!)
                }
            }
    }
}
