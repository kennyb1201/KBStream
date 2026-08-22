package com.kennyb1201.kbstream.ui.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.kennyb1201.kbstream.ui.detail.StreamsTarget
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBTextLo

private val HomePosterWidth = 124.dp
private val HomePosterHeight = 180.dp
private val HomeRailGap = 12.dp

private fun openTrailer(context: Context, meta: Meta) {
    val video = meta.videos
        ?.firstOrNull { !it.id.isNullOrBlank() }
        ?.id

    if (!video.isNullOrBlank()) {
        val youtubeApp = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("vnd.youtube:$video")
        )
        val youtubeWeb = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.youtube.com/watch?v=$video")
        )
        try {
            context.startActivity(youtubeApp)
        } catch (_: Exception) {
            try {
                context.startActivity(youtubeWeb)
            } catch (e: Exception) {
                Log.e("HOME_HERO", "Unable to open trailer", e)
            }
        }
    } else {
        val query = Uri.encode("${meta.name} official trailer")
        try {
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com/results?search_query=$query")
                )
            )
        } catch (e: Exception) {
            Log.e("HOME_HERO", "Unable to open trailer search", e)
        }
    }
}

@Composable
private fun HomeHero(
    preview: MetaPreview,
    meta: Meta?,
    onOpenDetail: () -> Unit,
    onTrailer: () -> Unit
) {
    val context = LocalContext.current
    val title = meta?.name ?: preview.name
    val background = meta?.background ?: preview.background ?: preview.poster
    val logo = meta?.logo ?: preview.logo

    var focused by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(390.dp)
            .padding(horizontal = 24.dp)
            .clip(RoundedCornerShape(18.dp))
            .focusable()
            .border(
                width = if (focused) 1.dp else 0.dp,
                color = if (focused) KBAccent.copy(alpha = .8f) else Color.Transparent,
                shape = RoundedCornerShape(18.dp)
            )
            .clickable {
                focused = true
                onOpenDetail()
            }
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(background)
                .crossfade(true)
                .build(),
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Black.copy(alpha = .94f),
                            Color.Black.copy(alpha = .68f),
                            Color.Black.copy(alpha = .20f),
                            Color.Transparent
                        )
                    )
                )
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = .08f),
                            Color.Black.copy(alpha = .82f)
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .width(540.dp)
                .padding(start = 30.dp, bottom = 28.dp)
        ) {
            if (!logo.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(logo)
                        .crossfade(true)
                        .build(),
                    contentDescription = title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .width(330.dp)
                        .height(105.dp)
                )
            } else {
                Text(
                    title,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            val info = listOfNotNull(
                meta?.releaseInfo,
                meta?.runtime,
                meta?.imdbRating?.let { "IMDb $it" }
            ).joinToString("  •  ")

            if (info.isNotBlank()) {
                Text(
                    info,
                    color = Color.White.copy(alpha = .86f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            meta?.genres
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString("  •  ")
                ?.let {
                    Text(
                        it,
                        color = KBTextLo,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

            (meta?.description ?: preview.description)
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    Text(
                        it,
                        color = KBTextLo,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 14.dp)
            ) {
                KBCard(onClick = onOpenDetail) {
                    Text(
                        "▶  PLAY",
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        fontWeight = FontWeight.Bold
                    )
                }

                if (meta != null) {
                    KBCard(onClick = onTrailer) {
                        Text(
                            "TRAILER",
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = .94f),
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun CompactUpNextCard(
    item: UpNextItem,
    watchedKeys: Set<String>,
    onClick: () -> Unit,
    onFocus: () -> Unit = {},
    badgeColor: Color,
    badgeText: String
) {
    var focused by remember { mutableStateOf(false) }
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
    ) {
        Column {
            Box(
                modifier = Modifier
                    .width(HomePosterWidth)
                    .height(HomePosterHeight)
                    .clip(RoundedCornerShape(8.dp))
                    .onFocusChanged { state ->
                        focused = state.isFocused
                        if (state.isFocused) onFocus()
                    }
                    .focusable()
                    .clickable { onClick() }
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

                if (item.badge != UpNextBadge.NEXT_UP &&
                    item.badge != UpNextBadge.CONTINUE_WATCHING
                ) {
                    Text(
                        badgeText,
                        color = Color.White,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .background(badgeColor)
                            .padding(horizontal = 5.dp, vertical = 3.dp)
                    )
                }

                val progress = item.progressPercent
                if (item.badge == UpNextBadge.CONTINUE_WATCHING &&
                    progress != null && progress > 0f
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(horizontal = 7.dp, vertical = 7.dp)
                            .height(4.dp)
                            .background(Color.White.copy(alpha = .28f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress.coerceIn(0f, 1f))
                                .height(4.dp)
                                .background(KBAccent)
                        )
                    }
                }
            }

            item.subtitle?.let {
                Text(
                    it,
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
    viewModel: HomeViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val context = LocalContext.current
    val rails by viewModel.rails.collectAsStateWithLifecycle()
    val watchedKeys by viewModel.watchedKeys.collectAsStateWithLifecycle()
    val upNext by viewModel.upNext.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val heroMeta by viewModel.heroMeta.collectAsStateWithLifecycle()

    val firstHomeItem = remember(rails) {
        rails.asSequence()
            .flatMap { it.items.asSequence() }
            .firstOrNull()
    }

    var focusedItem by remember { mutableStateOf<MetaPreview?>(firstHomeItem) }

    LaunchedEffect(firstHomeItem?.id, firstHomeItem?.type) {
        if (focusedItem == null && firstHomeItem != null) {
            focusedItem = firstHomeItem
        }
    }

    LaunchedEffect(focusedItem?.id, focusedItem?.type) {
        viewModel.resolveHeroMeta(focusedItem)
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshUpNext()
        viewModel.refreshWatchedStatusForCurrentRails()
    }

    fun selectHero(item: MetaPreview) {
        focusedItem = item
    }

    fun openUpNext(item: UpNextItem) {
        val parentId = item.parentId
        val parentType = item.parentType

        if (parentId.isNullOrBlank() || parentType.isNullOrBlank()) return

        val detailMeta = MetaPreview(
            id = parentId,
            type = parentType,
            name = item.title,
            poster = item.poster
        )

        val target = StreamsTarget(
            contentType = parentType,
            streamId = item.episodeStreamId ?: item.parentId ?: item.id,
            title = item.title,
            displayName = item.title,
            season = item.season,
            episode = item.episode,
            resumePositionMs = item.startPositionMs
        )

        selectHero(detailMeta)
        onOpenDetailTarget(detailMeta, target, item.poster)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // KBStream's modern Hero stays pinned while the user browses the shelves.
        // The focused poster below drives its artwork, logo and metadata.
        focusedItem?.let { item ->
            HomeHero(
                preview = item,
                meta = heroMeta?.takeIf { it.id == item.id && it.type == item.type },
                onOpenDetail = { onItemClick(item) },
                onTrailer = {
                    heroMeta
                        ?.takeIf { it.id == item.id && it.type == item.type }
                        ?.let { openTrailer(context, it) }
                }
            )
        }

        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            KBCard(onClick = onSearch) {
                Text("SEARCH", modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
            }
            KBCard(onClick = onOpenGuide) {
                Text("TV GUIDE", modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
            }
            KBCard(onClick = onManageAddons) {
                Text("ADD-ONS", modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
            }
            KBCard(onClick = onOpenSimkl) {
                Text("SIMKL", modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (upNext.isNotEmpty()) {
                item(key = "continue_watching") {
                    Column(
                        modifier = Modifier.padding(start = 24.dp, top = 10.dp, bottom = 8.dp)
                    ) {
                        SectionTitle("Continue Watching")
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            items(upNext, key = { it.id }) { item ->
                                val heroItem = MetaPreview(
                                    id = item.parentId ?: item.id,
                                    type = item.parentType ?: "movie",
                                    name = item.title,
                                    poster = item.poster
                                )

                                CompactUpNextCard(
                                    item = item,
                                    watchedKeys = watchedKeys,
                                    onClick = { openUpNext(item) },
                                    onFocus = { selectHero(heroItem) },
                                    badgeColor = when (item.badge) {
                                        UpNextBadge.CONTINUE_WATCHING -> KBAccent
                                        UpNextBadge.NEXT_UP -> Color(0xFF2E5BFF)
                                        UpNextBadge.NEW_EPISODE -> Color(0xFF2E7D32)
                                        UpNextBadge.NEW_SEASON -> Color(0xFF6A1B9A)
                                    },
                                    badgeText = when (item.badge) {
                                        UpNextBadge.CONTINUE_WATCHING -> "CONTINUE"
                                        UpNextBadge.NEXT_UP -> "NEXT UP"
                                        UpNextBadge.NEW_EPISODE -> "NEW"
                                        UpNextBadge.NEW_SEASON -> "NEW SEASON"
                                    }
                                )
                            }
                        }
                    }
                }
            }

            when {
                isLoading -> item(key = "loading") {
                    Text("Loading catalogs...", modifier = Modifier.padding(24.dp))
                }

                error != null -> item(key = "error") {
                    Text("Error: $error", modifier = Modifier.padding(24.dp))
                }

                rails.isEmpty() -> item(key = "empty") {
                    Text(
                        "No catalogs available. Add an addon to get started.",
                        modifier = Modifier.padding(24.dp)
                    )
                }

                else -> {
                    items(
                        items = rails,
                        key = { "${it.addonName}:${it.catalogName}:${it.type}" }
                    ) { rail ->
                        Column(
                            modifier = Modifier.padding(
                                start = 24.dp,
                                top = 6.dp,
                                bottom = 8.dp
                            )
                        ) {
                            SectionTitle(
                                rail.catalogName
                                    .replace("_", " ")
                                    .split(" ")
                                    .joinToString(" ") {
                                        it.lowercase().replaceFirstChar { c -> c.uppercase() }
                                    }
                            )

                            LazyRow {
                                items(
                                    items = rail.items,
                                    key = { meta -> "${meta.type}:${meta.id}" }
                                ) { meta ->
                                    val watched =
                                        viewModel.watchedKey(meta.id, meta.type) in watchedKeys

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
                                            .onFocusChanged { state ->
                                                if (state.isFocused) {
                                                    selectHero(meta)
                                                }
                                            },
                                        onPosterError = { throwable ->
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

            item(key = "bottom_spacer") {
                Spacer(Modifier.height(36.dp))
            }
        }
    }
}
