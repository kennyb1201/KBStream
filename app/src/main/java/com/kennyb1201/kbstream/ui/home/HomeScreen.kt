package com.kennyb1201.kbstream.ui.home

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.kennyb1201.kbstream.data.addon.Meta
import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.PosterCard
import com.kennyb1201.kbstream.ui.components.YouTubeTrailerPlayer
import com.kennyb1201.kbstream.ui.detail.StreamsTarget
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import kotlinx.coroutines.delay

private val HomePosterWidth = 124.dp
private val HomePosterHeight = 180.dp
private val HomeRailGap = 12.dp

private const val HeroTrailerDwellMs = 4_000L

@Composable
private fun TopActionBar(
    onSearch: () -> Unit,
    onOpenGuide: () -> Unit,
    onManageAddons: () -> Unit,
    onOpenSimkl: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = 24.dp,
                vertical = 14.dp
            ),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        KBCard(
            onClick = onSearch,
            modifier = Modifier.padding(end = 10.dp)
        ) {
            Text(
                text = "SEARCH",
                modifier = Modifier.padding(
                    horizontal = 14.dp,
                    vertical = 8.dp
                ),
                fontSize = 12.sp
            )
        }

        KBCard(
            onClick = onOpenGuide,
            modifier = Modifier.padding(end = 10.dp)
        ) {
            Text(
                text = "TV GUIDE",
                modifier = Modifier.padding(
                    horizontal = 14.dp,
                    vertical = 8.dp
                ),
                fontSize = 12.sp
            )
        }

        KBCard(
            onClick = onManageAddons,
            modifier = Modifier.padding(end = 10.dp)
        ) {
            Text(
                text = "ADD-ONS",
                modifier = Modifier.padding(
                    horizontal = 14.dp,
                    vertical = 8.dp
                ),
                fontSize = 12.sp
            )
        }

        KBCard(
            onClick = onOpenSimkl
        ) {
            Text(
                text = "SIMKL",
                modifier = Modifier.padding(
                    horizontal = 14.dp,
                    vertical = 8.dp
                ),
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun HomeHero(
    preview: MetaPreview,
    meta: Meta?,
    trailerKey: String?,
    autoPlayTrailer: Boolean
) {
    val context = LocalContext.current
    val title = meta?.name ?: preview.name

    /*
     * HomeViewModel prioritizes TMDB artwork for meta.background and
     * meta.logo. The normal catalog artwork is used only as a fallback.
     */
    val backdrop = meta?.background
        ?: preview.background
        ?: meta?.poster
        ?: preview.poster

    val clearLogo = meta?.logo
        ?: preview.logo

    var manualTrailer by remember(preview.id) {
        mutableStateOf(false)
    }

    val trailerPlaying =
        !trailerKey.isNullOrBlank() &&
            (autoPlayTrailer || manualTrailer)

    /*
     * Pull the first four-digit year out of Stremio releaseInfo.
     * Examples: "2025", "2024–", "2024 PG-13".
     */
    val year =
        meta?.releaseInfo
            ?.let { releaseInfo ->
                Regex("""\b(?:19|20)d{2}\b""")
                    .find(releaseInfo)
                    ?.value
            }

    /*
     * Stremio metadata may include a certificate in releaseInfo.
     * Supports common movie and US TV certificates.
     */
    val contentRating =
        meta?.releaseInfo
            ?.let { releaseInfo ->
                Regex(
                    """\b(?:G|PG|PG-13|R|NC-17|TV-Y7|TV-Y|TV-G|TV-PG|TV-14|TV-MA)\b"""
                )
                    .find(releaseInfo.uppercase())
                    ?.value
            }

    val imdbRating =
        meta?.imdbRating
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { rating ->
                "IMDb $rating"
            }

    val runtime =
        meta?.runtime
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    val mainGenre =
        meta?.genres
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    /*
     * Display order:
     * IMDb rating → year → age/certificate → runtime → main genre.
     */
    val heroInfo =
        listOfNotNull(
            imdbRating,
            year,
            contentRating,
            runtime,
            mainGenre
        ).joinToString("  •  ")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(330.dp)
            .background(Color.Black)
    ) {
        if (trailerPlaying) {
            YouTubeTrailerPlayer(
                videoId = trailerKey,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(backdrop)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        /*
         * The clearlogo and text sit in a dark safe area at the left.
         * The backdrop remains clean and visible on the right.
         */
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Black.copy(alpha = 0.98f),
                            0.22f to Color.Black.copy(alpha = 0.92f),
                            0.46f to Color.Black.copy(alpha = 0.62f),
                            0.72f to Color.Black.copy(alpha = 0.14f),
                            1.00f to Color.Transparent
                        )
                    )
                )
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Transparent,
                            0.58f to Color.Transparent,
                            1.00f to Color.Black.copy(alpha = 0.88f)
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .width(480.dp)
                .padding(
                    start = 32.dp,
                    bottom = 24.dp
                )
        ) {
            if (!clearLogo.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(clearLogo)
                        .crossfade(true)
                        .build(),
                    contentDescription = title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .width(290.dp)
                        .height(86.dp)
                )
            } else {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (heroInfo.isNotBlank()) {
                Text(
                    text = heroInfo,
                    color = Color.White.copy(alpha = 0.94f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 5.dp)
                )
            }

            (meta?.description ?: preview.description)
                ?.takeIf { it.isNotBlank() }
                ?.let { description ->
                    Text(
                        text = description,
                        color = Color.White.copy(alpha = 0.80f),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

            /*
             * The hero has no Play button. A Trailer button is optional
             * because it does not begin content playback.
             */
            if (!trailerKey.isNullOrBlank()) {
                KBCard(
                    onClick = {
                        manualTrailer = true
                    },
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    Text(
                        text =
                            if (trailerPlaying) {
                                "PLAYING"
                            } else {
                                "TRAILER"
                            },
                        modifier = Modifier.padding(
                            horizontal = 15.dp,
                            vertical = 7.dp
                        ),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(
    text: String
) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.94f),
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun CompactUpNextCard(
    item: UpNextItem,
    onClick: () -> Unit,
    onFocus: () -> Unit = {},
    onUpPressed: () -> Unit = {},
    badgeColor: Color,
    badgeText: String
) {
    var focused by remember {
        mutableStateOf(false)
    }

    val scale by animateFloatAsState(
        targetValue = if (focused) 1.045f else 1f,
        label = "homeCardScale"
    )

    KBCard(
        onClick = onClick,
        modifier = Modifier
            .width(HomePosterWidth)
            .scale(scale)
            .padding(end = HomeRailGap)
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
        Column {
            Box(
                modifier = Modifier
                    .width(HomePosterWidth)
                    .height(HomePosterHeight)
                    .clip(RoundedCornerShape(8.dp))
                    .onFocusChanged { state ->
                        focused = state.isFocused

                        if (state.isFocused) {
                            onFocus()
                        }
                    }
                    .focusable()
                    .clickable {
                        onClick()
                    }
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(item.poster)
                        .crossfade(true)
                        .build(),
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                if (
                    item.badge != UpNextBadge.NEXT_UP &&
                    item.badge != UpNextBadge.CONTINUE_WATCHING
                ) {
                    Text(
                        text = badgeText,
                        color = Color.White,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .background(badgeColor)
                            .padding(
                                horizontal = 5.dp,
                                vertical = 3.dp
                            )
                    )
                }

                val progress = item.progressPercent

                if (
                    item.badge == UpNextBadge.CONTINUE_WATCHING &&
                    progress != null &&
                    progress > 0f
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(
                                horizontal = 7.dp,
                                vertical = 7.dp
                            )
                            .height(4.dp)
                            .background(
                                Color.White.copy(alpha = 0.28f)
                            )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(
                                    progress.coerceIn(0f, 1f)
                                )
                                .height(4.dp)
                                .background(KBAccent)
                        )
                    }
                }
            }

            item.subtitle?.let { subtitle ->
                Text(
                    text = subtitle,
                    color = KBTextLo,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

@Composable
fun HomeScreen(
    onItemClick: (MetaPreview) -> Unit,
    onOpenDetailTarget: (MetaPreview, StreamsTarget, String?) -> Unit,
    onManageAddons: () -> Unit,
    onSearch: () -> Unit = {},
    onOpenGuide: () -> Unit = {},
    onOpenSimkl: () -> Unit,
    viewModel: HomeViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val rails by viewModel.rails.collectAsStateWithLifecycle()
    val watchedKeys by viewModel.watchedKeys.collectAsStateWithLifecycle()
    val upNext by viewModel.upNext.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val heroMeta by viewModel.heroMeta.collectAsStateWithLifecycle()
    val heroTrailerKey by viewModel.heroTrailerKey.collectAsStateWithLifecycle()

    var showTopBar by remember {
        mutableStateOf(false)
    }

    val firstHomeItem = remember(rails) {
        rails.asSequence()
            .flatMap { rail ->
                rail.items.asSequence()
            }
            .firstOrNull()
    }

    var focusedItem by remember {
        mutableStateOf<MetaPreview?>(firstHomeItem)
    }

    var heroTrailerReady by remember {
        mutableStateOf(false)
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
        focusedItem?.type
    ) {
        heroTrailerReady = false

        val item = focusedItem

        if (item != null) {
            viewModel.resolveHeroMeta(item)

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

    fun selectHero(
        item: MetaPreview
    ) {
        focusedItem = item
    }

    fun openUpNext(
        item: UpNextItem
    ) {
        val parentId = item.parentId
        val parentType = item.parentType

        if (
            parentId.isNullOrBlank() ||
            parentType.isNullOrBlank()
        ) {
            return
        }

        val detailMeta =
            MetaPreview(
                id = parentId,
                type = parentType,
                name = item.title,
                poster = item.poster
            )

        val target =
            StreamsTarget(
                contentType = parentType,
                streamId =
                    item.episodeStreamId
                        ?: item.parentId
                        ?: item.id,
                title = item.title,
                displayName = item.title,
                season = item.season,
                episode = item.episode,
                resumePositionMs = item.startPositionMs
            )

        selectHero(detailMeta)

        onOpenDetailTarget(
            detailMeta,
            target,
            item.poster
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            focusedItem?.let { item ->
                val activeMeta =
                    heroMeta?.takeIf { resolvedMeta ->
                        resolvedMeta.id == item.id &&
                            resolvedMeta.type == item.type
                    }

                val activeTrailerKey =
                    heroTrailerKey?.takeIf {
                        activeMeta != null
                    }

                HomeHero(
                    preview = item,
                    meta = activeMeta,
                    trailerKey = activeTrailerKey,
                    autoPlayTrailer = heroTrailerReady
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement =
                    Arrangement.spacedBy(2.dp)
            ) {
                if (upNext.isNotEmpty()) {
                    item(key = "continue_watching") {
                        Column(
                            modifier = Modifier.padding(
                                start = 24.dp,
                                top = 4.dp,
                                bottom = 6.dp
                            )
                        ) {
                            SectionTitle(
                                text = "Continue Watching"
                            )

                            LazyRow(
                                horizontalArrangement =
                                    Arrangement.spacedBy(0.dp)
                            ) {
                                items(
                                    items = upNext,
                                    key = { item ->
                                        item.id
                                    }
                                ) { item ->
                                    val heroItem =
                                        MetaPreview(
                                            id = item.parentId ?: item.id,
                                            type = item.parentType ?: "movie",
                                            name = item.title,
                                            poster = item.poster
                                        )

                                    CompactUpNextCard(
                                        item = item,
                                        onClick = {
                                            openUpNext(item)
                                        },
                                        onFocus = {
                                            selectHero(heroItem)
                                        },
                                        onUpPressed = {
                                            showTopBar = true
                                        },
                                        badgeColor =
                                            when (item.badge) {
                                                UpNextBadge.CONTINUE_WATCHING ->
                                                    KBAccent

                                                UpNextBadge.NEXT_UP ->
                                                    Color(0xFF2E5BFF)

                                                UpNextBadge.NEW_EPISODE ->
                                                    Color(0xFF2E7D32)

                                                UpNextBadge.NEW_SEASON ->
                                                    Color(0xFF6A1B9A)
                                            },
                                        badgeText =
                                            when (item.badge) {
                                                UpNextBadge.CONTINUE_WATCHING ->
                                                    "CONTINUE"

                                                UpNextBadge.NEXT_UP ->
                                                    "NEXT UP"

                                                UpNextBadge.NEW_EPISODE ->
                                                    "NEW"

                                                UpNextBadge.NEW_SEASON ->
                                                    "NEW SEASON"
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
                            Text(
                                text = "Loading catalogs...",
                                modifier = Modifier.padding(24.dp)
                            )
                        }
                    }

                    error != null -> {
                        item(key = "error") {
                            Text(
                                text = "Error: $error",
                                modifier = Modifier.padding(24.dp)
                            )
                        }
                    }

                    rails.isEmpty() -> {
                        item(key = "empty") {
                            Text(
                                text =
                                    "No catalogs available. " +
                                        "Add an addon to get started.",
                                modifier = Modifier.padding(24.dp)
                            )
                        }
                    }

                    else -> {
                        items(
                            items = rails,
                            key = { rail ->
                                "${rail.addonName}:" +
                                    "${rail.catalogName}:" +
                                    rail.type
                            }
                        ) { rail ->
                            Column(
                                modifier = Modifier.padding(
                                    start = 24.dp,
                                    top = 4.dp,
                                    bottom = 6.dp
                                )
                            ) {
                                SectionTitle(
                                    text =
                                        rail.catalogName
                                            .replace("_", " ")
                                            .split(" ")
                                            .joinToString(" ") { word ->
                                                word.lowercase()
                                                    .replaceFirstChar {
                                                        character ->
                                                        character.uppercase()
                                                    }
                                            }
                                )

                                LazyRow {
                                    items(
                                        items = rail.items,
                                        key = { meta ->
                                            "${meta.type}:${meta.id}"
                                        }
                                    ) { meta ->
                                        val watched =
                                            viewModel.watchedKey(
                                                meta.id,
                                                meta.type
                                            ) in watchedKeys

                                        PosterCard(
                                            posterUrl = meta.poster,
                                            contentDescription = meta.name,
                                            isWatched = watched,
                                            onClick = {
                                                selectHero(meta)
                                                onItemClick(meta)
                                            },
                                            modifier = Modifier
                                                .width(HomePosterWidth)
                                                .height(HomePosterHeight)
                                                .padding(end = HomeRailGap)
                                                .onPreviewKeyEvent { event ->
                                                    if (
                                                        event.type ==
                                                            KeyEventType.KeyDown &&
                                                        event.key ==
                                                            Key.DirectionUp
                                                    ) {
                                                        showTopBar = true
                                                        true
                                                    } else {
                                                        false
                                                    }
                                                }
                                                .onFocusChanged { state ->
                                                    if (state.isFocused) {
                                                        selectHero(meta)
                                                    }
                                                },
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

                item(key = "bottom_spacer") {
                    Spacer(
                        modifier = Modifier.height(36.dp)
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
                                Color.Black.copy(alpha = 0.96f),
                                Color.Black.copy(alpha = 0.72f),
                                Color.Transparent
                            )
                        )
                    )
            ) {
                TopActionBar(
                    onSearch = onSearch,
                    onOpenGuide = onOpenGuide,
                    onManageAddons = onManageAddons,
                    onOpenSimkl = onOpenSimkl
                )
            }
        }
    }
}
