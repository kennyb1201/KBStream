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
import androidx.compose.foundation.shape.RoundedCornerShape
import com.kennyb1201.kbstream.data.tmdb.displayDescription
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
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
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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
import com.kennyb1201.kbstream.data.tmdb.TmdbDetail
import com.kennyb1201.kbstream.data.tmdb.certification
import com.kennyb1201.kbstream.data.tmdb.movieStatusTag
import com.kennyb1201.kbstream.data.youtube.TrailerPlayerLauncher
import com.kennyb1201.kbstream.ui.components.PosterCard
import com.kennyb1201.kbstream.ui.components.PosterContextAction
import com.kennyb1201.kbstream.ui.components.PosterContextMenu
import com.kennyb1201.kbstream.ui.detail.StreamsTarget
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import com.kennyb1201.kbstream.data.youtube.PlayableSource
import com.kennyb1201.kbstream.data.youtube.YoutubeChunkedDataSourceFactory
import kotlinx.coroutines.delay

private val HomePosterWidth = 124.dp
private val HomePosterHeight = 180.dp
private val HomeRailGap = 12.dp
private val ContinueWatchingCardWidth = 260.dp
private val ContinueWatchingCardImageHeight = 146.dp
private const val HeroTrailerDwellMs = 4_000L

private val HomeHeroHeight = 300.dp

private val RailTopContentPadding = 14.dp
private val RailBottomContentPadding = 24.dp

private val RailHorizontalStartPadding = 12.dp
private val RailSectionGap = 20.dp

private val HeroToFirstRailGap = 2.dp

private val PosterFocusHeadroom = 16.dp

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
    onOpenSettings: () -> Unit,
    firstActionFocusRequester: FocusRequester,
    onDismiss: () -> Unit
) {
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
    modifier: Modifier = Modifier,
    onEnded: () -> Unit = {}
) {
    val context = LocalContext.current
    val exoPlayer = remember(source) {

        // Fire TV suppresses debug logs, so surface what the player actually
        // receives -- this proves whether the source was cobalt or googlevideo.
        Log.w(
            "HOME_HERO",
            "Hero player mounting with source: " + heroSourceOrigin(source)
        )

        // googlevideo signed URLs 403 unless the request carries the same
        // YouTube client User-Agent that resolved them and uses bounded
        // range requests. YoutubeChunkedDataSourceFactory handles that for
        // googlevideo hosts (1 MB chunks via OkHttp, ratebypass fallbacks)
        // and passes every other host straight through - the same stack the
        // full player uses for HLS and progressive playback.
        val mediaSourceFactory =
            DefaultMediaSourceFactory(
                YoutubeChunkedDataSourceFactory(),
                DefaultExtractorsFactory()
            )

        val renderersFactory =
            DefaultRenderersFactory(context)
                .setExtensionRendererMode(
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                )
                .setEnableDecoderFallback(true)

        ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(
                mediaSourceFactory
            )
            .build()
            .apply {
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
                        setMediaItem(mediaItem)
                    }

                    is PlayableSource.Adaptive -> {
                        // This is only a last-resort source. The resolver prefers
                        // HLS and muxed streams because signed googlevideo video
                        // URLs can reject ExoPlayer's later range requests.
                        setMediaItem(MediaItem.fromUri(source.videoUrl))
                    }
                }

                repeatMode = Player.REPEAT_MODE_OFF
                volume = 1f
                playWhenReady = true
                prepare()
            }
    }

    DisposableEffect(exoPlayer) {
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
                    // fall back to the backdrop image immediately.
                    Log.e(
                        "HOME_HERO",
                        "Inline trailer playback failed: ${error.errorCodeName}",
                        error
                    )
                    handler.removeCallbacks(watchdog)
                    onEnded()
                }
            }

        exoPlayer.addListener(listener)

        // Give the trailer up to 8s to start rendering; if it hasn't,
        // fall back to the backdrop.
        handler.postDelayed(watchdog, 8_000L)

        onDispose {
            handler.removeCallbacks(watchdog)
            exoPlayer.removeListener(listener)
            exoPlayer.release()
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

@Composable
private fun HomeHero(
    preview: MetaPreview,
    meta: Meta?,
    tmdbDetail: TmdbDetail?,
    heroBackdropUrl: String?,
    heroLogoUrl: String?,
    trailerKey: String?,
    autoPlayTrailer: Boolean,
    continueWatchingItem: UpNextItem? = null
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

    LaunchedEffect(trailerPlaying, trailerKey) {
        resolvedTrailerSource = null

        if (trailerPlaying && !trailerKey.isNullOrBlank()) {
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
    continueWatchingItem?.let { item ->
        val watched = item.episodesWatched
        val total = item.episodesTotal

        if (
            watched != null &&
            total != null &&
            total > 0
        ) {
            "$watched of $total episodes watched"
        } else {
            null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(HomeHeroHeight)
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
            modifier = Modifier.fillMaxSize(),
            onEnded = {
                // Video finished (or the watchdog gave up on a stuck
                // player): drop the source so the hero crossfades back
                // to the backdrop.
                resolvedTrailerSource = null
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

        Column(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.4f)
                .padding(
                    start = 32.dp,
                    end = 20.dp
                ),
            verticalArrangement = Arrangement.Center
        ) {
            if (!clearLogo.isNullOrBlank()) {                    HeroClearLogo(
                        url = clearLogo,
                        name = title,
                        modifier = Modifier
                            .width(300.dp)
                            .height(82.dp)
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
            }

            if (continueWatchingItem != null) {
    continueWatchingItem.episodeTitle
        ?.trim()
        ?.takeIf {
            it.isNotBlank()
        }
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

                    

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = KBTextHi.copy(alpha = 0.94f),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(
            top = 4.dp,
            bottom = 6.dp
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
    "1 episode left"
} else {
    "$remaining episodes left"
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
    onOpenSettings: () -> Unit = {},
    viewModel: HomeViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val rails by viewModel.rails.collectAsStateWithLifecycle()
    val watchedKeys by viewModel.watchedKeys.collectAsStateWithLifecycle()
    val upNext by viewModel.upNext.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val heroMeta by viewModel.heroMeta.collectAsStateWithLifecycle()
    val heroTmdbDetail by viewModel.heroTmdbDetail.collectAsStateWithLifecycle()
    val heroBackdropUrl by viewModel.heroBackdropUrl.collectAsStateWithLifecycle()
    val heroLogoUrl by viewModel.heroLogoUrl.collectAsStateWithLifecycle()
    val heroTrailerKey by viewModel.heroTrailerKey.collectAsStateWithLifecycle()

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

    val firstHomeItem = remember(rails) {
        rails.asSequence()
            .flatMap {
                it.items.asSequence()
            }
            .firstOrNull()
    }

    var focusedItem by remember {
        mutableStateOf<MetaPreview?>(firstHomeItem)
    }

    var focusedContinueWatchingItem by remember {
        mutableStateOf<UpNextItem?>(null)
    }

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

    fun dismissPosterMenu() {
        posterMenu = null
        lastPosterFocusRequester?.requestFocus()
    }

    fun selectHero(
        item: MetaPreview
    ) {
        focusedItem = item
        focusedContinueWatchingItem = null
    }

    fun selectContinueWatchingHero(
        item: MetaPreview,
        upNextItem: UpNextItem
    ) {
        focusedItem = item
        focusedContinueWatchingItem = upNextItem
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
            focusedItem == null &&
            firstHomeItem != null
        ) {
            focusedItem = firstHomeItem
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
        startAtBeginning: Boolean = false
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
            poster = item.poster
        )

        val target = StreamsTarget(
            contentType = parentType,
            streamId = item.episodeStreamId
                ?: item.parentId
                ?: item.id,
            title = item.title,
            displayName = item.title,
            season = item.season,
            episode = item.episode,
            resumePositionMs =
                if (startAtBeginning) {
                    0L
                } else {
                    item.startPositionMs
                }
        )

        if (openInStreamsScreen) {

            // "Play Manually" / "Play from Beginning": go straight to the
            // streams picker for this item. Resume position is preserved for
            // Play Manually; Play from Beginning forces position 0. Both
            // follow the global auto-select setting.
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
            focusedItem?.let {
                HomeHero(
                    preview = it,
                    meta = heroMeta,
                    tmdbDetail = heroTmdbDetail,
                    heroBackdropUrl = heroBackdropUrl,
                    heroLogoUrl = heroLogoUrl,
                    trailerKey = heroTrailerKey,
                    autoPlayTrailer =
                        heroTrailerReady &&
                            focusedContinueWatchingItem == null,
                    continueWatchingItem =
                        focusedContinueWatchingItem
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    top = 0.dp,
                    bottom = 16.dp
                ),
                verticalArrangement =
                    Arrangement.spacedBy(
                        RailSectionGap
                    )
            ) {
                item(key = "hero_spacer") {
                    Spacer(
                        modifier = Modifier.height(
                            HeroToFirstRailGap
                        )
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
                                                item.poster
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
                                                    Color(
                                                        0xFFB71C1C
                                                    )

                                                item.isSeasonFinale ->
                                                    Color(
                                                        0xFFD84315
                                                    )

                                                else ->
                                                    when (
                                                        item.badge
                                                    ) {
                                                        UpNextBadge.CONTINUE_WATCHING ->
                                                            KBAccent

                                                        UpNextBadge.NEXT_UP ->
                                                            Color(
                                                                0xFF2E5BFF
                                                            )

                                                        UpNextBadge.NEW_EPISODE ->
                                                            Color(
                                                                0xFF2E7D32
                                                            )

                                                        UpNextBadge.NEW_SEASON ->
                                                            Color(
                                                                0xFF6A1B9A
                                                            )
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

                when {
                    isLoading -> {
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
                            Text(
                                text =
                                    "No catalogs available. Add an addon to get started.",
                                modifier =
                                    Modifier.padding(
                                        24.dp
                                    )
                            )
                        }
                    }

                    else -> {
                        itemsIndexed(
                            items = rails,
                            // Index prefix guarantees uniqueness even if two
                            // rails ever share addon/catalog/type.
                            key = { railIndex, rail ->
                                "$railIndex|" +
                                    "${rail.addonName}:" +
                                    "${rail.catalogName}:" +
                                    "${rail.type}"
                            }
                        ) { railIndex, rail ->

                            Column(
                                modifier = Modifier.padding(
                                    start = TvSafeAreaHorizontal,
                                    top = 0.dp,
                                    bottom = 8.dp
                                )
                            ) {
                                SectionTitle(
                                    rail.catalogName
                                        .replace("_", " ")
                                        .split(" ")
                                        .joinToString(" ") {
                                            it.lowercase()
                                                .replaceFirstChar { char ->
                                                    char.uppercase()
                                                }
                                        }
                                )

                                LazyRow(
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

                                        val isFirstRailFirstRow =
                                            railIndex == 0 &&
                                                firstRailNeedsUpHook

                                        val posterModifier = Modifier
                                            .offset(y = (-3).dp)
                                            .focusRequester(requester)
                                            .width(HomePosterWidth)
                                            .height(HomePosterHeight)
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
                                                .width(HomePosterWidth)
                                                .height(
                                                    HomePosterHeight +
                                                        PosterFocusHeadroom
                                                )
                                                .padding(
                                                    end = HomeRailGap
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            PosterCard(
                                                posterUrl = meta.poster,
                                                contentDescription = meta.name,
                                                isWatched = watched,
                                                onClick = {
                                                    selectHero(meta)
                                                    onItemClick(meta)
                                                },
                                                onLongClick = {
                                                    lastPosterFocusRequester =
                                                        requester
                                                    posterMenu =
                                                        PosterMenuTarget(
                                                            meta
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

                item(key = "bottom_spacer") {
                    Spacer(
                        modifier = Modifier.height(
                            0.dp
                        )
                    )
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
                    onOpenSettings = onOpenSettings,
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
                        label = "Go to Details",
                        description = "Open this title's detail page"
                    ) {
                        val selectedItem = menuItem
                        continueWatchingMenu = null
                        openUpNext(selectedItem)
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
                            openInStreamsScreen = true,
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

            PosterContextMenu(
                title = target.meta.name,
                actions = listOf(
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
    }
}

/**
 * Target for the long-press menu on a regular poster rail card.
 */
private data class PosterMenuTarget(
    val meta: MetaPreview
)


