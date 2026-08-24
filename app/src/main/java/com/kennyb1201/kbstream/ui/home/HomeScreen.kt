package com.kennyb1201.kbstream.ui.home

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
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
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import kotlinx.coroutines.delay

private val HomePosterWidth = 124.dp
private val HomePosterHeight = 180.dp
private val HomeRailGap = 12.dp
private const val HeroTrailerDwellMs = 4_000L

// Slightly shorter hero so the catalog titles and first poster row
// have enough vertical room on the TV screen.
private val HomeHeroHeight = 300.dp

// Reduced padding gives the catalog rails more usable screen space
// while still leaving enough room for focused cards to draw cleanly.
private val RailTopContentPadding = 28.dp
private val RailBottomContentPadding = 58.dp

private val RailHorizontalStartPadding = 20.dp
private val RailSectionGap = 20.dp

// Keep the first section very close to the hero fade.
private val HeroToFirstRailGap = 2.dp

// Real, measured headroom around each poster/card so the focused
// scale-up transform has actual layout space instead of overflowing
// past the LazyRow's clip bounds.
private val PosterFocusHeadroom = 28.dp

// Guards against TV overscan/panel-edge clipping. Nothing renders
// inside this margin from the physical screen edges.
private val TvSafeAreaHorizontal = 48.dp
private val TvSafeAreaVertical = 32.dp

@Composable
private fun TopActionItem(
    label: String,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }

    Text(
        text = label,
        color = if (focused) Color.White else Color.White.copy(alpha = .76f),
        fontSize = 13.sp,
        fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (focused) {
                    KBAccent.copy(alpha = .28f)
                } else {
                    Color.Transparent
                }
            )
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
            }
            .focusable()
            .clickable(onClick = onClick)
            .padding(
                horizontal = 12.dp,
                vertical = 8.dp
            )
    )
}

@Composable
private fun TopActionBar(
    onSearch: () -> Unit,
    onOpenGuide: () -> Unit,
    onManageAddons: () -> Unit,
    onOpenSimkl: () -> Unit,
    firstActionFocusRequester: FocusRequester,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = 24.dp,
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
            label = "ADD-ONS",
            onClick = onManageAddons,
            onDismiss = onDismiss,
            modifier = Modifier.padding(end = 8.dp)
        )

        TopActionItem(
            label = "SIMKL",
            onClick = onOpenSimkl,
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun HomeHero(
    preview: MetaPreview,
    meta: Meta?,
    heroBackdropUrl: String?,
    heroLogoUrl: String?,
    trailerKey: String?,
    autoPlayTrailer: Boolean
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

    val year = meta?.releaseInfo
        ?.let {
            Regex("""\b(?:19|20)\d{2}\b""")
                .find(it)
                ?.value
        }

    val rating = meta?.releaseInfo
        ?.let {
            Regex(
                """\b(?:G|PG|PG-13|R|NC-17|TV-Y7|TV-Y|TV-G|TV-PG|TV-14|TV-MA)\b"""
            )
                .find(it.uppercase())
                ?.value
        }

    val imdb = meta?.imdbRating
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { "IMDb $it" }

    val runtime = meta?.runtime
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    val genre = meta?.genres
        ?.firstOrNull()
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    val heroInfo = listOfNotNull(
        imdb,
        year,
        rating,
        runtime,
        genre
    ).joinToString("  •  ")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(HomeHeroHeight)
            .background(Color.Black)
    ) {
        if (trailerPlaying) {
            YouTubeTrailerPlayer(
                videoId = trailerKey,
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(.60f)
                    .align(Alignment.CenterEnd)
            )
        } else {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(backdrop)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(.60f)
                    .align(Alignment.CenterEnd),
                contentScale = ContentScale.Crop,
                alignment = Alignment.Center
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0f to Color.Black,
                            .30f to Color.Black,
                            .45f to Color.Black.copy(alpha = .96f),
                            .62f to Color.Black.copy(alpha = .58f),
                            .82f to Color.Black.copy(alpha = .12f),
                            1f to Color.Transparent
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
                            0f to Color.Transparent,
                            0.50f to Color.Transparent,
                            0.78f to Color.Black.copy(alpha = 0.48f),
                            1f to Color.Black
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(480.dp)
                .padding(
                    start = 32.dp,
                    end = 20.dp,
                )
        ) {
            if (!clearLogo.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(clearLogo)
                        .crossfade(true)
                        .build(),
                    contentDescription = title,
                    modifier = Modifier
                        .width(250.dp)
                        .height(68.dp),
                    contentScale = ContentScale.Fit
                )
            } else {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (heroInfo.isNotBlank()) {
                Text(
                    text = heroInfo,
                    color = Color.White.copy(alpha = .94f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            (meta?.description ?: preview.description)
                ?.takeIf { it.isNotBlank() }
                ?.let { description ->
                    Text(
                        text = description,
                        color = Color.White.copy(alpha = .80f),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 5.dp)
                    )
                }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = .94f),
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
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
    focusRequester: FocusRequester? = null,
    badgeColor: Color,
    badgeText: String
) {
    var focused by remember { mutableStateOf(false) }

    // Reserves real, measured height around the card so the focused
    // scale-up transform stays inside the LazyRow's clip bounds.
    Box(
        modifier = Modifier
            .width(HomePosterWidth)
            .height(HomePosterHeight + PosterFocusHeadroom)
            .padding(end = HomeRailGap),
        contentAlignment = Alignment.TopStart
    ) {
        KBCard(
            onClick = onClick,
            modifier = Modifier
                .then(
                    if (focusRequester != null) {
                        Modifier.focusRequester(focusRequester)
                    } else {
                        Modifier
                    }
                )
                .onPreviewKeyEvent {
                    if (
                        it.type == KeyEventType.KeyDown &&
                        it.key == Key.DirectionUp
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
                        .onFocusChanged {
                            focused = it.isFocused

                            if (it.isFocused) {
                                onFocus()
                            }
                        }
                        .focusable()
                        .clickable(onClick = onClick)
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(item.poster)
                            .crossfade(true)
                            .build(),
                        contentDescription = item.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
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

                    item.progressPercent
                        ?.takeIf {
                            item.badge == UpNextBadge.CONTINUE_WATCHING &&
                                it > 0f
                        }
                        ?.let { progress ->
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
                                        Color.White.copy(alpha = .28f)
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

                item.subtitle?.let {
                    Text(
                        text = it,
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
}

@Composable
fun HomeScreen(
    onItemClick: (MetaPreview) -> Unit,
    onOpenDetailTarget: (
        MetaPreview,
        StreamsTarget,
        String?
    ) -> Unit,
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

    val firstHomeItem = remember(rails) {
        rails.asSequence()
            .flatMap { it.items.asSequence() }
            .firstOrNull()
    }

    var focusedItem by remember {
        mutableStateOf<MetaPreview?>(firstHomeItem)
    }

    var heroTrailerReady by remember {
        mutableStateOf(false)
    }

    fun openTopBar(requester: FocusRequester) {
        lastPosterFocusRequester = requester
        showTopBar = true
    }

    fun dismissTopBar() {
        showTopBar = false
        lastPosterFocusRequester?.requestFocus()
    }

    fun selectHero(item: MetaPreview) {
        focusedItem = item
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
        focusedItem?.type
    ) {
        heroTrailerReady = false

        focusedItem?.let {
            viewModel.resolveHeroMeta(it)
            delay(HeroTrailerDwellMs)
            heroTrailerReady = true
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshUpNext()
        viewModel.refreshWatchedStatusForCurrentRails()
    }

    fun openUpNext(item: UpNextItem) {
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
            resumePositionMs = item.startPositionMs
        )

        selectHero(detail)

        onOpenDetailTarget(
            detail,
            target,
            item.poster
        )
    }

    val firstRailNeedsUpHook = upNext.isEmpty()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(
                horizontal = TvSafeAreaHorizontal,
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
                    heroBackdropUrl = heroBackdropUrl,
                    heroLogoUrl = heroLogoUrl,
                    trailerKey = heroTrailerKey,
                    autoPlayTrailer = heroTrailerReady
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    top = 0.dp,
                    bottom = 64.dp
                ),
                verticalArrangement = Arrangement.spacedBy(
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
                    item(key = "continue_watching") {
                        Column(
                            modifier = Modifier.padding(
                                start = 16.dp,
                                top = 0.dp,
                                bottom = 8.dp
                            )
                        ) {
                            SectionTitle("Continue Watching")

                            LazyRow(
                                contentPadding = PaddingValues(
                                    start = RailHorizontalStartPadding,
                                    end = 24.dp,
                                    top = RailTopContentPadding,
                                    bottom = RailBottomContentPadding
                                ),
                                horizontalArrangement =
                                    Arrangement.spacedBy(0.dp)
                            ) {
                                items(
                                    items = upNext,
                                    key = { it.id }
                                ) { item ->
                                    val requester = remember {
                                        FocusRequester()
                                    }

                                    val hero = MetaPreview(
                                        id = item.parentId
                                            ?: item.id,
                                        type = item.parentType
                                            ?: "movie",
                                        name = item.title,
                                        poster = item.poster
                                    )

                                    CompactUpNextCard(
                                        item = item,
                                        onClick = {
                                            openUpNext(item)
                                        },
                                        onFocus = {
                                            selectHero(hero)
                                        },
                                        onUpPressed = {
                                            openTopBar(requester)
                                        },
                                        focusRequester = requester,
                                        badgeColor = when (item.badge) {
                                            UpNextBadge.CONTINUE_WATCHING ->
                                                KBAccent

                                            UpNextBadge.NEXT_UP ->
                                                Color(0xFF2E5BFF)

                                            UpNextBadge.NEW_EPISODE ->
                                                Color(0xFF2E7D32)

                                            UpNextBadge.NEW_SEASON ->
                                                Color(0xFF6A1B9A)
                                        },
                                        badgeText = when (item.badge) {
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
                                text = "No catalogs available. Add an addon to get started.",
                                modifier = Modifier.padding(24.dp)
                            )
                        }
                    }

                    else -> {
                        itemsIndexed(
                            items = rails,
                            key = { _, rail ->
                                "${rail.addonName}:${rail.catalogName}:${rail.type}"
                            }
                        ) { railIndex, rail ->
                            Column(
                                modifier = Modifier.padding(
                                    start = 16.dp,
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
                                        end = 24.dp,
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

                                        // Reserves real, measured height
                                        // around the poster so the focused
                                        // scale-up transform stays inside
                                        // the LazyRow's clip bounds.
                                        Box(
                                            modifier = Modifier
                                                .width(HomePosterWidth)
                                                .height(
                                                    HomePosterHeight +
                                                        PosterFocusHeadroom
                                                )
                                                .padding(end = HomeRailGap),
                                            contentAlignment =
                                                Alignment.TopStart
                                        ) {
                                            PosterCard(
                                                posterUrl = meta.poster,
                                                contentDescription =
                                                    meta.name,
                                                isWatched = watched,
                                                onClick = {
                                                    selectHero(meta)
                                                    onItemClick(meta)
                                                },
                                                modifier = Modifier
                                                    .focusRequester(
                                                        requester
                                                    )
                                                    .width(
                                                        HomePosterWidth
                                                    )
                                                    .height(
                                                        HomePosterHeight
                                                    )
                                                    .onFocusChanged {
                                                        if (it.isFocused) {
                                                            selectHero(meta)
                                                        }
                                                    }
                                                    .let { modifier ->
                                                        if (
                                                            isFirstRailFirstRow
                                                        ) {
                                                            modifier
                                                                .onPreviewKeyEvent {
                                                                    event ->
                                                                    if (
                                                                        event.type ==
                                                                            KeyEventType.KeyDown &&
                                                                        event.key ==
                                                                            Key.DirectionUp
                                                                    ) {
                                                                        openTopBar(
                                                                            requester
                                                                        )
                                                                        true
                                                                    } else {
                                                                        false
                                                                    }
                                                                }
                                                        } else {
                                                            modifier
                                                        }
                                                    },
                                                onPosterError = {
                                                    throwable ->
                                                    Log.e(
                                                        "HOME_UI",
                                                        "Catalog poster load failed, title=${meta.name}, poster=${meta.poster}",
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
                        modifier = Modifier.height(72.dp)
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
                                Color.Black.copy(alpha = .96f),
                                Color.Black.copy(alpha = .72f),
                                Color.Transparent
                            )
                        )
                    )
            ) {
                TopActionBar(
                    onSearch = onSearch,
                    onOpenGuide = onOpenGuide,
                    onManageAddons = onManageAddons,
                    onOpenSimkl = onOpenSimkl,
                    firstActionFocusRequester =
                        topBarFocusRequester,
                    onDismiss = {
                        dismissTopBar()
                    }
                )
            }
        }
    }
}
