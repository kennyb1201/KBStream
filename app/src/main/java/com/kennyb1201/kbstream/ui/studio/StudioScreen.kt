package com.kennyb1201.kbstream.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.tmdb.StudioItem
import com.kennyb1201.kbstream.data.tmdb.StudioSection
import com.kennyb1201.kbstream.data.tmdb.TmdbCompanyDetail
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.ui.components.BrandMarkImage
import com.kennyb1201.kbstream.ui.components.GenreChipRow
import com.kennyb1201.kbstream.ui.components.KBSkeletonRailStack
import com.kennyb1201.kbstream.ui.components.InfiniteScrollEffect
import com.kennyb1201.kbstream.ui.components.KBStatusMessage
import com.kennyb1201.kbstream.ui.components.KB_STATUS_ICON_EMPTY
import com.kennyb1201.kbstream.ui.components.rememberPosterSize
import com.kennyb1201.kbstream.ui.components.rememberPosterTileWidth
import com.kennyb1201.kbstream.ui.components.PosterCaptions
import com.kennyb1201.kbstream.ui.components.GlobalPosterCard
import com.kennyb1201.kbstream.data.library.HiddenTitles
import com.kennyb1201.kbstream.ui.components.hideTarget
import com.kennyb1201.kbstream.ui.components.PosterContextAction
import com.kennyb1201.kbstream.ui.components.rememberHiddenTitleKeys
import com.kennyb1201.kbstream.ui.components.PosterContextMenu
import com.kennyb1201.kbstream.ui.components.watchedMenuLabel
import com.kennyb1201.kbstream.ui.components.watchedMenuDescription
import com.kennyb1201.kbstream.ui.components.LibraryAddTarget
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import kotlinx.coroutines.delay

@Composable
fun StudioScreen(
    id: Int,
    name: String,
    isNetwork: Boolean,
    // Set when this page is a streaming SERVICE (Services & Networks browse
    // entries with a watch-provider id): rails then cover movies + series
    // via provider discover while the header still shows the brand logo.
    providerId: Int? = null,
    // ORIGINALS rails (what the brand made): network/company discover ids
    // carried from the browse entry; nullable for plain pages.
    networkOrCompanyId: Int? = null,
    networkIsCompany: Boolean = false,
    originalsCompanyId: Int? = null,
    onNavigateDetail: (String, String) -> Unit = { _, _ -> },
    viewModel: StudioViewModel = viewModel()
) {
    val sectionsRaw by viewModel.sections.collectAsStateWithLifecycle()
    val browseGenres by viewModel.browseGenres.collectAsStateWithLifecycle()
    val selectedGenreId by viewModel.selectedGenreId.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    // Poster geometry for the loading skeleton, so the placeholders occupy
    // exactly the space the real rails will.
    val posterSize = rememberPosterSize()
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
                    railItem.item.title ?: railItem.item.name,
                    railItem.item.releaseDate?.take(4)?.toIntOrNull()
                        ?: railItem.item.firstAirDate?.take(4)?.toIntOrNull(),
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
    val logoUrls by viewModel.logoUrls.collectAsStateWithLifecycle()
    val companyInfo by viewModel.companyInfo.collectAsStateWithLifecycle()
    val isService by viewModel.isService.collectAsStateWithLifecycle()

    val firstItemFocusRequester = remember { FocusRequester() }

    // Long-press context menu for rail posters.
    var menuItem by remember {
        mutableStateOf<StudioItem?>(
            null
        )
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

    LaunchedEffect(id, isNetwork, providerId) {
        // Re-pass the current chip selection so the VM's same-route guard
        // treats Back-restore as a no-op instead of resetting the filter.
        viewModel.load(
            id,
            isNetwork,
            providerId,
            networkOrCompanyId,
            networkIsCompany,
            originalsCompanyId,
            // The route's label for the brand: the logo lookup searches for a
            // brand TMDB holds no artwork for under its name, and only trusts
            // a twin id whose entry carries this name.
            name,
            viewModel.selectedGenreId.value
        )
    }

    // The rails stream in one at a time, so this effect re-runs after the first
    // one lands; [focusPlaced] keeps that from pulling focus back to the first
    // card every time another rail arrives behind the viewer. A new load (a
    // genre chip, another studio) resets it, so the fresh page still takes
    // focus once its first rail is on screen.
    var focusPlaced by remember { mutableStateOf(false) }
    LaunchedEffect(sections, isLoading) {
        if (isLoading) {
            focusPlaced = false
        } else if (!focusPlaced && sections.any { it.items.isNotEmpty() }) {
            focusPlaced = true
            delay(100)
            runCatching { firstItemFocusRequester.requestFocus() }
        }
    }

    // Full-bleed screen (matches SearchScreen): the background fills the
    // whole display and edge spacing lives in the LazyColumn's
    // contentPadding, so focused poster borders + glow draw to the screen
    // edge without being clipped by a fixed-inset parent.
    // The header is PINNED above the scrolling rails: when focus lands on
    // the first poster, the LazyColumn only scrolls its own items, so the
    // title/logo can never be pushed up under the top screen edge and
    // clipped.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.padding(
                    start = 28.dp,
                    end = 28.dp,
                    top = 28.dp
                )
            ) {
                StudioHeader(
                    name = name,
                    logoUrls = logoUrls,
                    info = companyInfo,
                    isService = isService
                )
            }

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
                        KBSkeletonRailStack(
                            posterWidth = posterSize.width,
                            posterHeight = posterSize.height,
                            horizontalPadding = 20.dp
                        )
                    }
                }

                // A failed load used to fall through to the empty branch, so
                // a network error was indistinguishable from "this company
                // has no titles". ActorScreen has always shown the error; the
                // browse pages now say it the same way.
                error != null -> {
                    item(key = "error") {
                        KBStatusMessage(
                            message = "Error: $error",
                            // Same argument list as the LaunchedEffect above
                            // (including the current chip selection, which the
                            // VM's same-route guard compares).
                            onRetry = {
                                viewModel.load(
                                    id,
                                    isNetwork,
                                    providerId,
                                    networkOrCompanyId,
                                    networkIsCompany,
                                    originalsCompanyId,
                                    name,
                                    viewModel.selectedGenreId.value
                                )
                            },
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
                    val pagingState = pagingStates[section.title] ?: StudioRailPagingState()

                    StudioRailRow(
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
                // The same lookup the poster badge uses gives the IMDB id this
                // screen already resolved, so an add fired from here reaches
                // the trackers with the pair they key off instead of a TMDB id
                // alone. Everything else the menu needs (the title, the year,
                // the artwork) is on the row itself.
                libraryTarget = LibraryAddTarget(
                    mediaType = menuMediaType,
                    imdbId = resolvedIds[
                        viewModel.lookupKey(
                            studioItem.item.id,
                            menuMediaType
                        )
                    ],
                    tmdbId = studioItem.item.id,
                    title = studioItem.item.title
                        ?: studioItem.item.name
                        ?: "Untitled",
                    year = (studioItem.item.releaseDate
                        ?: studioItem.item.firstAirDate)
                        ?.take(4)?.toIntOrNull(),
                    posterUrl = studioItem.item.posterPath
                        ?.takeIf { it.isNotBlank() }
                        ?.let { TmdbRepository.POSTER_BASE + it }
                ),
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
                    }
                ),
                onDismiss = {
                    dismissRailMenu()
                }
            )
        }
    }
}

@Composable
private fun StudioHeader(
    name: String,
    logoUrls: List<String>,
    info: TmdbCompanyDetail?,
    isService: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (info?.let { it.name.isNullOrBlank() } == false) {
                    info.name.orEmpty()
                } else {
                    name
                },
                style = MaterialTheme.typography.displayLarge
            )

            Text(
                text = when {
                    isService -> "Streaming Service"
                    info?.description != null -> "Production Company"
                    else -> "Network"
                },
                style = MaterialTheme.typography.titleMedium,
                color = KBTextLo,
                modifier = Modifier.padding(top = 2.dp)
            )

            val location = listOfNotNull(
                info?.originCountry?.takeIf { it.isNotBlank() },
                info?.headquarters?.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            if (location.isNotBlank()) {
                Text(
                    text = location,
                    style = MaterialTheme.typography.bodyMedium,
                    color = KBAccent,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }

        if (logoUrls.isNotEmpty()) {
            BrandLogo(
                urls = logoUrls,
                name = name,
                modifier = Modifier
                    .width(360.dp)
                    .height(150.dp)
                    .padding(start = 24.dp)
            )
        }
    }
}

/**
 * Brand logo rendered for a dark surface, in one of three ways decided by
 * sampling the decoded artwork (see [BrandMarkImage], which owns that and is
 * what every other drawing site of a brand mark uses too):
 *
 *  - [BrandMark.WHITEN]   the dark, colorless glyph-on-transparency that the
 *                         TMDB company/network endpoints default to, and a
 *                         dark plate with its wordmark knocked out of it. A
 *                         SrcIn tint preserves the alpha and turns the dark
 *                         parts white, so both read as light lettering.
 *  - [BrandMark.AS_IS]    colored marks (Netflix N, NBC peacock), light
 *                         marks, and dark PLATES that carry their own light
 *                         lettering — tinting one of those is what produced
 *                         the unreadable solid white circles, because it
 *                         whitens the plate and its opaque letters alike.
 *  - [BrandMark.UNUSABLE] a featureless filled plate (TNT's, E!'s), a 1x1
 *                         stub, or artwork that never arrives. Nothing is
 *                         drawn for it, and the next candidate is tried.
 *
 * [urls] is the brand's ranked candidate list - an entity's own marks, its
 * company twin, and (for a streaming service with no entity artwork at all)
 * the watch provider's registered logo. The best-ranked mark is not always
 * drawable, so the list is walked until one is; only when it runs out does
 * the header fall back to its name text. That walk is what gives the brands
 * whose single top mark is an unreadable plate their logo back.
 *
 * The drawing itself - and the rule for which of the three ways a mark takes -
 * lives in [BrandMarkImage], so a single-URL caller draws a brand mark the same
 * way without owning a candidate list.
 */
@Composable
fun BrandLogo(
    urls: List<String>,
    name: String,
    modifier: Modifier = Modifier
) {
    var index by remember(urls) { mutableStateOf(0) }
    val url = urls.getOrNull(index)
    // Set when every candidate has been rejected. The header then keeps its
    // (large) name text and reclaims this component's width.
    var unreadable by remember(urls) { mutableStateOf(false) }

    if (url == null || unreadable) return

    BrandMarkImage(
        url = url,
        contentDescription = name,
        modifier = modifier,
        // Move to the next candidate, or give up once the list is exhausted.
        onUnusable = {
            if (index + 1 < urls.size) index += 1 else unreadable = true
        }
    )
}

@Composable
private fun StudioRailRow(
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
    viewModel: StudioViewModel,
    onOpenPosterMenu: (StudioItem, FocusRequester) -> Unit
) {
    val rowState = rememberLazyListState()

    InfiniteStudioRailHandler(
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
                // Landscape tiles are wider than the poster they replace, so the
                // caption under one follows the shape the card draws.
                val tileWidth = rememberPosterTileWidth(posterSize.width)

                // The tile's own focus drives the caption marquee below.
                var focused by remember { mutableStateOf(false) }

                Column(
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .onFocusChanged { focused = it.hasFocus }
                ) {
                    GlobalPosterCard(
                        posterUrl = studioItem.item.posterPath
                            ?.let { "${TmdbRepository.POSTER_BASE}$it" },
                        backdropUrl = studioItem.item.backdropPath
                            ?.let { "${TmdbRepository.BACKDROP_BASE}$it" },
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
                        posterWidth = posterSize.width,
                        posterHeight = posterSize.height,
                        // The discover item ships a backdrop; the resolver adds
                        // the corner clearlogo the landscape shape wants.
                        artId = studioItem.item.id.toString(),
                        artType = rawMediaType,
                        modifier = Modifier
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
                        focused = focused,
                        year = (studioItem.item.releaseDate
                            ?: studioItem.item.firstAirDate)?.take(4),
                        rating = studioItem.item.voteAverage,
                        modifier = Modifier
                            .width(tileWidth)
                            .padding(top = 5.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun InfiniteStudioRailHandler(
    listState: LazyListState,
    itemCount: Int,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit
) {
    InfiniteScrollEffect(
        listState = listState,
        itemCount = itemCount,
        hasMore = hasMore,
        isLoadingMore = isLoadingMore,
        onLoadMore = onLoadMore
    )
}
