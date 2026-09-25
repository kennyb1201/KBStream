package com.kennyb1201.kbstream.ui.decade

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
import com.kennyb1201.kbstream.data.tmdb.StudioItem
import com.kennyb1201.kbstream.data.tmdb.StudioSection
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.ui.components.GenreChipRow
import com.kennyb1201.kbstream.ui.components.KBStatusMessage
import com.kennyb1201.kbstream.ui.components.KB_STATUS_ICON_EMPTY
import com.kennyb1201.kbstream.ui.components.KB_STATUS_LOADING
import com.kennyb1201.kbstream.ui.components.PosterSize
import com.kennyb1201.kbstream.ui.components.rememberPosterSize
import com.kennyb1201.kbstream.ui.components.PosterCaptions
import com.kennyb1201.kbstream.ui.components.PosterCard
import com.kennyb1201.kbstream.data.library.HiddenTitles
import com.kennyb1201.kbstream.ui.components.hideTarget
import com.kennyb1201.kbstream.ui.components.PosterContextAction
import com.kennyb1201.kbstream.ui.components.rememberHiddenTitleKeys
import com.kennyb1201.kbstream.ui.components.PosterContextMenu
import com.kennyb1201.kbstream.ui.components.watchedMenuLabel
import com.kennyb1201.kbstream.ui.components.watchedMenuDescription
import com.kennyb1201.kbstream.ui.components.LibraryAddToListDialog
import com.kennyb1201.kbstream.ui.components.LibraryAddTarget
import com.kennyb1201.kbstream.ui.tag.RailPagingState
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Decade screen (Screen.Decade): the genre-screen experience for decades —
 * pinned header with a DECADE eyebrow and a tilted poster fan built from
 * the decade's top titles, then MOVIES/SERIES rails (Popular / Top Rated —
 * no RECENT rails; a decade is old by definition) with the same infinite
 * scroll and long-press watched toggles as the genre screens.
 */
@Composable
fun DecadeScreen(
    decadeStart: Int,
    name: String,
    onNavigateDetail: (String, String) -> Unit = { _, _ -> },
    viewModel: DecadeViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val sectionsRaw by viewModel.sections.collectAsStateWithLifecycle()
    val browseGenres by viewModel.browseGenres.collectAsStateWithLifecycle()
    val selectedGenreId by viewModel.selectedGenreId.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val watchedKeys by viewModel.watchedKeys.collectAsStateWithLifecycle()
    val partialWatchedKeys by viewModel.partialWatchedKeys.collectAsStateWithLifecycle()
    val resolvedIds by viewModel.resolvedIds.collectAsStateWithLifecycle()
    val hiddenTitleKeys = rememberHiddenTitleKeys()
    // Hidden titles are filtered out of the rails as they are read, so a
    // long-press Hide empties the rail it was pressed on (and the whole
    // screen, when that was the only rail left).
    val sections = remember(sectionsRaw, resolvedIds, hiddenTitleKeys) {
        sectionsRaw.mapNotNull { section ->
            val visible = section.items.filterNot { railItem ->
                val mediaType = when (railItem.mediaType.lowercase()) {
                    "tv", "series" -> "series"
                    else -> "movie"
                }
                HiddenTitles.hides(
                    hiddenTitleKeys,
                    mediaType,
                    railItem.item.id.toString(),
                    resolvedIds[
                        viewModel.lookupKey(railItem.item.id, mediaType)
                    ]
                )
            }
            section.takeIf { visible.isNotEmpty() }?.copy(items = visible)
        }
    }
    val pagingStates by viewModel.pagingStates.collectAsStateWithLifecycle()

    val firstItemFocusRequester = remember { FocusRequester() }

    // Long-press context menu for rail posters.
    var menuItem by remember {
        mutableStateOf<StudioItem?>(
            null
        )
    }

    // "Add to list…" picker target (title + which lists to offer).
    var addToListTarget by remember {
        mutableStateOf<LibraryAddTarget?>(null)
    }

    var lastRailFocusRequester by remember {
        mutableStateOf<FocusRequester?>(
            null
        )
    }

    fun dismissRailMenu() {
        menuItem = null
        lastRailFocusRequester?.requestFocus()
    }

    LaunchedEffect(decadeStart) {
        // Re-pass the current chip selection so the VM's same-route guard
        // treats Back-restore as a no-op instead of resetting the filter.
        viewModel.load(decadeStart, viewModel.selectedGenreId.value)
    }

    LaunchedEffect(sections, isLoading) {
        if (!isLoading && sections.any { it.items.isNotEmpty() }) {
            delay(100)
            runCatching { firstItemFocusRequester.requestFocus() }
        }
    }

    // Full-bleed screen (matches TagScreen): the background fills the whole
    // display and edge spacing lives in the LazyColumn's contentPadding, so
    // focused poster borders + glow draw to the screen edge without being
    // clipped by a fixed-inset parent. The header is PINNED above the
    // scrolling rails so it can never be pushed under the top edge.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top posters in this decade, for the header's poster fan.
            val headerPosters = remember(sections) {
                sections.asSequence()
                    .flatMap { it.items.asSequence() }
                    .mapNotNull { it.item.posterPath }
                    .distinct()
                    .take(5)
                    .toList()
                    .map { "${TmdbRepository.POSTER_BASE}$it" }
            }

            DecadeHeader(
                name = name,
                posterUrls = headerPosters
            )

            GenreChipRow(
                genres = browseGenres,
                selectedGenreId = selectedGenreId,
                onSelect = { viewModel.onGenreSelected(it) },
                modifier = Modifier.padding(start = 28.dp, top = 4.dp)
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = 8.dp,
                    bottom = 24.dp
                )
            ) {
                when {
                isLoading -> {
                    item(key = "loading") {
                        KBStatusMessage(
                            loading = true,
                            message = KB_STATUS_LOADING,
                            modifier = Modifier.fillParentMaxSize()
                        )
                    }
                }

                // A failed load used to fall through to the empty branch, so
                // a network error was indistinguishable from "this decade has
                // no titles". ActorScreen has always shown the error; the
                // browse pages now say it the same way.
                error != null -> {
                    item(key = "error") {
                        KBStatusMessage(
                            message = "Error: $error",
                            modifier = Modifier.fillParentMaxSize()
                        )
                    }
                }

                sections.isEmpty() -> {
                    item(key = "empty") {
                        KBStatusMessage(
                            icon = KB_STATUS_ICON_EMPTY,
                            message = "Nothing found for $name",
                            modifier = Modifier.fillParentMaxSize()
                        )
                    }
                }

                else -> {
                    items(
                        items = sections,
                        key = { section: StudioSection -> section.title }
                    ) { section ->
                    val pagingState = pagingStates[section.title] ?: RailPagingState()

                    DecadeRailRow(
                        section = section,
                        watchedKeys = watchedKeys,
                        partialWatchedKeys = partialWatchedKeys,
                        resolvedIds = resolvedIds,
                        onNavigateDetail = onNavigateDetail,
                        onLoadMore = { viewModel.loadMoreSection(section.title) },
                        hasMore = pagingState.hasMore,
                        isLoadingMore = pagingState.isLoadingMore,
                        isFirstSection = section == sections.firstOrNull(),
                        firstItemFocusRequester = firstItemFocusRequester,
                        viewModel = viewModel,
                        onOpenPosterMenu = { item, requester ->
                            lastRailFocusRequester =
                                requester
                            menuItem = item
                        }
                    )
                }

                item(key = "bottom_spacer") {
                    Box(modifier = Modifier.height(24.dp))
                }
            }
        }
        }
    }

        // Long-press context menu for rail posters (movies/series).
        menuItem?.let { studioItem ->
            // Same lookup the rail badge uses, so the toggle always matches
            // what the poster currently shows: "Mark as Unwatched" when the
            // badge is visible, "Mark as Watched" otherwise.
            val menuTmdbId = studioItem.item.id
            val menuMediaType =
                when (studioItem.mediaType.lowercase()) {
                    "tv", "series" -> "series"
                    else -> "movie"
                }

            val isWatched =
                resolvedIds[
                    viewModel.lookupKey(
                        menuTmdbId,
                        menuMediaType
                    )
                ]?.let { imdbId ->
                    viewModel.watchedKey(
                        imdbId,
                        menuMediaType
                    ) in watchedKeys
                } == true

            PosterContextMenu(
                title = studioItem.item.title
                    ?: studioItem.item.name
                    ?: "",
                hideTarget = hideTarget(
                    studioItem.item.title
                        ?: studioItem.item.name
                        ?: "",
                    studioItem.mediaType,
                    studioItem.item.posterPath
                        ?.takeIf { it.isNotBlank() }
                        ?.let { "${TmdbRepository.POSTER_BASE}$it" },
                    listOf(
                        studioItem.item.id.toString(),
                        resolvedIds[
                            viewModel.lookupKey(
                                studioItem.item.id,
                                menuMediaType
                            )
                        ]
                    )
                ),
                actions = listOf(
                    PosterContextAction(
                        label = "Go to Details",
                        description = "Open this title's detail page"
                    ) {
                        menuItem = null
                        viewModel.resolveAndNavigate(
                            tmdbId = studioItem.item.id,
                            mediaType = studioItem.mediaType,
                            onNavigateDetail = onNavigateDetail
                        )
                    },
                    PosterContextAction(
                        label = watchedMenuLabel(
                            isWatched = isWatched,
                            mediaType = studioItem.mediaType
                        ),
                        description = watchedMenuDescription(
                            isWatched = isWatched,
                            mediaType = studioItem.mediaType
                        )
                    ) {
                        menuItem = null
                        if (isWatched) {
                            viewModel.markUnwatched(
                                tmdbId = studioItem.item.id,
                                mediaType = studioItem.mediaType
                            )
                        } else {
                            viewModel.markAsWatched(
                                tmdbId = studioItem.item.id,
                                mediaType = studioItem.mediaType
                            )
                        }
                        lastRailFocusRequester?.requestFocus()
                    },
                    PosterContextAction(
                        label = "Add to list…",
                        description = "Pick a personal list or watchlist"
                    ) {
                        val selected = studioItem
                        menuItem = null
                        addToListTarget = LibraryAddTarget(
                            mediaType = selected.mediaType,
                            imdbId = null,
                            tmdbId = selected.item.id,
                            title = selected.item.title
                                ?: selected.item.name
                                ?: "Untitled",
                            year = (selected.item.releaseDate
                                ?: selected.item.firstAirDate)
                                ?.take(4)?.toIntOrNull(),
                            posterUrl = selected.item.posterPath
                                ?.takeIf { it.isNotBlank() }
                                ?.let { TmdbRepository.POSTER_BASE + it }
                        )
                        lastRailFocusRequester?.requestFocus()
                    }
                ),
                onDismiss = {
                    dismissRailMenu()
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
 * Pinned header for decade screens: gradient-accented decade name with a
 * DECADE eyebrow, plus the same tilted poster fan the genre screens use —
 * five cards from the decade's top titles, tapering in size with
 * alternating tilt, front card largest.
 */
@Composable
private fun DecadeHeader(
    name: String,
    posterUrls: List<String>
) {
    val accent = KBAccent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 28.dp, end = 28.dp, top = 28.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "DECADE",
                style = MaterialTheme.typography.labelLarge,
                color = accent,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            Text(
                text = name,
                style = MaterialTheme.typography.displayLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.background(
                    Brush.horizontalGradient(
                        listOf(
                            accent.copy(alpha = 0.35f),
                            Color.Transparent
                        )
                    )
                )
            )
        }

        if (posterUrls.isNotEmpty()) {
            // Staggered poster fan: back cards peek out behind the front
            // one (same treatment as the genre screens).
            Row {
                val rotations = listOf(-10f, -5f, 3f, 7f, -3f)
                val widths = listOf(66.dp, 76.dp, 96.dp, 76.dp, 66.dp)
                val heights = listOf(99.dp, 114.dp, 144.dp, 114.dp, 99.dp)
                posterUrls.take(5).forEachIndexed { index, url ->
                    val context = LocalContext.current
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(url)
                            .size(Size(200, 300))
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .zIndex((posterUrls.size - index).toFloat())
                            .offset(x = (-12 * index).dp)
                            .rotate(rotations[index])
                            .clip(RoundedCornerShape(8.dp))
                            .border(
                                1.dp,
                                Color.White.copy(alpha = 0.25f),
                                RoundedCornerShape(8.dp)
                            )
                            .width(widths[index])
                            .height(heights[index])
                    )
                }
            }
        }
    }
}

@Composable
private fun DecadeRailRow(
    section: StudioSection,
    watchedKeys: Set<String>,
    partialWatchedKeys: Set<String>,
    resolvedIds: Map<String, String>,
    onNavigateDetail: (String, String) -> Unit,
    onLoadMore: () -> Unit,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    isFirstSection: Boolean,
    firstItemFocusRequester: FocusRequester,
    viewModel: DecadeViewModel,
    onOpenPosterMenu: (StudioItem, FocusRequester) -> Unit
) {
    val rowState = rememberLazyListState()

    InfiniteDecadeRailHandler(
        listState = rowState,
        itemCount = section.items.size,
        hasMore = hasMore,
        isLoadingMore = isLoadingMore,
        onLoadMore = onLoadMore
    )

    Column(modifier = Modifier.padding(bottom = 20.dp)) {
        Text(
            text = section.title,
            style = MaterialTheme.typography.titleMedium,
            color = KBTextLo,
            // Aligned with the posters' 20dp rail inset instead of sitting
            // flush against the screen edge.
            modifier = Modifier.padding(start = 20.dp, bottom = 8.dp)
        )

        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp
            ),
            modifier = Modifier.focusGroup()
        ) {
            items(
                items = section.items,
                key = { item: StudioItem ->
                    "${item.mediaType}:${item.item.id}"
                }
            ) { studioItem ->
                // Focus requester for restoring focus after the long-press
                // menu dismisses, so the D-pad lands back on this exact card.
                val requester = remember(
                    studioItem.item.id,
                    studioItem.mediaType
                ) {
                    FocusRequester()
                }

                val tmdbId = studioItem.item.id
                val rawMediaType = studioItem.mediaType
                val normalizedType = when (rawMediaType.lowercase()) {
                    "tv", "series" -> "series"
                    else -> "movie"
                }

                val imdbId = resolvedIds[
                    viewModel.lookupKey(tmdbId, normalizedType)
                ]

                val watched = imdbId?.let {
                    viewModel.watchedKey(it, normalizedType) in watchedKeys
                } == true

                // Eye badge: started but not finished. The completed
                // checkmark wins when the title is fully watched.
                val watchedPartially = !watched && imdbId?.let {
                    viewModel.watchedKey(it, normalizedType) in partialWatchedKeys
                } == true

                val isFirstItem =
                    isFirstSection && studioItem == section.items.firstOrNull()

                val posterSize = rememberPosterSize()

                Column(
                    modifier = Modifier.padding(end = 12.dp)
                ) {
                    PosterCard(
                        posterUrl = studioItem.item.posterPath
                            ?.let { "${TmdbRepository.POSTER_BASE}$it" },
                        contentDescription = studioItem.item.title
                            ?: studioItem.item.name,
                        isWatched = watched,
                        isPartiallyWatched = watchedPartially,
                        onClick = {
                            viewModel.resolveAndNavigate(
                                tmdbId = studioItem.item.id,
                                mediaType = rawMediaType,
                                onNavigateDetail = onNavigateDetail
                            )
                        },
                        onLongClick = {
                            onOpenPosterMenu(studioItem, requester)
                        },
                        modifier = Modifier
                            .width(posterSize.width)
                            .height(posterSize.height)
                            .focusRequester(requester)
                            .then(
                                if (isFirstItem) {
                                    Modifier.focusRequester(firstItemFocusRequester)
                                } else {
                                    Modifier
                                }
                            )
                    )

                    PosterCaptions(
                        title = studioItem.item.title ?: studioItem.item.name,
                        year = (studioItem.item.releaseDate
                            ?: studioItem.item.firstAirDate)?.take(4),
                        rating = studioItem.item.voteAverage,
                        modifier = Modifier
                            .width(posterSize.width)
                            .padding(top = 5.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun InfiniteDecadeRailHandler(
    listState: androidx.compose.foundation.lazy.LazyListState,
    itemCount: Int,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit
) {
    LaunchedEffect(listState, itemCount, hasMore, isLoadingMore) {
        snapshotFlow {
            val lastVisibleIndex =
                listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisibleIndex to itemCount
        }
            .distinctUntilChanged()
            .collect { (lastVisibleIndex, totalItems) ->
                val threshold = 6
                val shouldLoadMore =
                    hasMore &&
                    !isLoadingMore &&
                    totalItems > 0 &&
                    lastVisibleIndex >= totalItems - threshold

                if (shouldLoadMore) {
                    onLoadMore()
                }
            }
    }
}
