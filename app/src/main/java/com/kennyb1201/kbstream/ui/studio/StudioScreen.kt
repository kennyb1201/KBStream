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
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.kennyb1201.kbstream.ui.components.posterEdgeShape
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
import com.kennyb1201.kbstream.ui.browse.BROWSE_RETURN_FOCUS_DELAY_MS
import com.kennyb1201.kbstream.ui.browse.BROWSE_RETURN_FOCUS_STEPS
import com.kennyb1201.kbstream.ui.browse.BrowseReturnFocus
import com.kennyb1201.kbstream.ui.browse.BrowseReturnTarget
import com.kennyb1201.kbstream.ui.browse.revealBrowseReturnRail
import com.kennyb1201.kbstream.ui.browse.studioBrowseScreenKey
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBFocusRowInset
import com.kennyb1201.kbstream.ui.theme.KBScreenEdge
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

    // Where this page's Detail page was opened from, read once when the rails
    // land: the card the viewer pressed, its rail and its position, so backing
    // out of Detail reopens the page where they left it instead of at the top
    // (see [BrowseReturnFocus]).
    val browseReturnKey = remember(id, isNetwork, providerId) {
        studioBrowseScreenKey(id, isNetwork, providerId)
    }
    val browseListState = rememberLazyListState()
    var returnTarget by remember {
        mutableStateOf<BrowseReturnTarget?>(null)
    }
    // How many rails this page has waited for the return's rail to land behind.
    var returnAttempts by remember { mutableStateOf(0) }
    val returnFocusRequester = remember { FocusRequester() }

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

    // Where the open menu's "Go to Details" is being opened FROM: the card that
    // raised the menu, its rail and its position, kept while the menu is up so
    // that action can raise the record a card press would (see
    // [BrowseReturnFocus]).
    var menuReturnTarget by remember {
        mutableStateOf<BrowseReturnTarget?>(
            null
        )
    }

    fun dismissRailMenu() {
        menuItem = null
        menuReturnTarget = null
        lastRailFocusRequester?.requestFocus()
    }

    /**
     * Puts the page back where the viewer left it, if its Detail page was opened
     * from here: the rail on screen, its row scrolled to the card, and the focus
     * on that card.
     *
     * Answers whether the focus landed. A record for a rail this page does not
     * paint answers false, and the caller holds the record and asks again when
     * the next rail lands - the rails stream in one at a time, so the rail
     * holding the card can arrive after the first one does.
     */
    suspend fun restoreBrowseReturn(recorded: BrowseReturnTarget): Boolean {
        val railKey = recorded.railKey ?: return false
        if (sections.none { it.title == railKey }) return false
        browseListState.revealBrowseReturnRail(railKey, recorded.railIndex)
        // The card is only focusable once its row has composed AND scrolled to
        // it, which is a frame or more after the rail itself came back, and a
        // request against a card that is not composed yet is a silent miss.
        var landed = false
        var attempts = 0
        while (!landed && attempts < BROWSE_RETURN_FOCUS_STEPS) {
            delay(BROWSE_RETURN_FOCUS_DELAY_MS)
            landed = runCatching { returnFocusRequester.requestFocus() }
                .getOrDefault(false)
            attempts++
        }
        return landed
    }

    /**
     * Takes the page's own first card: where an ordinary open lands, and the
     * last resort for a return this page could never put back. Answers whether
     * the focus actually landed.
     *
     * Retried across frames rather than requested once: the request can land
     * before the rail has composed its first card, and it is then silently
     * missed. requestFocus() returns false rather than throwing for a node that
     * is not attached yet, so the RETURN VALUE is the success test - runCatching's
     * isSuccess only ever means "no exception was thrown", which is always true
     * (see [KBStatusMessage], which documents the same trap).
     */
    suspend fun focusFirstCard(): Boolean {
        var landed = false
        var attempts = 0
        while (!landed && attempts < BROWSE_RETURN_FOCUS_STEPS) {
            delay(BROWSE_RETURN_FOCUS_DELAY_MS)
            landed = runCatching { firstItemFocusRequester.requestFocus() }
                .getOrDefault(false)
            attempts++
        }
        return landed
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
    //
    // A page reopened from one of its own Detail pages is the same move in
    // reverse: the card the viewer pressed comes back, and only an ordinary
    // open - or a return this page could never put back - falls through to the
    // first card (see [BrowseReturnFocus]). The record is HELD here until it is
    // honoured: the rail that holds the card can land after the first one does,
    // and a record read and then dropped would lose the place it was raised for.
    var focusPlaced by remember { mutableStateOf(false) }
    LaunchedEffect(sections, isLoading) {
        if (isLoading) {
            focusPlaced = false
            // A new load is a new list: a position in the old one means nothing.
            returnTarget = null
            returnAttempts = 0
        } else if (!focusPlaced && sections.any { it.items.isNotEmpty() }) {
            val recorded = returnTarget ?: BrowseReturnFocus.consume(browseReturnKey)
            if (recorded != null && returnAttempts < BROWSE_RETURN_FOCUS_STEPS) {
                returnTarget = recorded
                returnAttempts++
                // The landing is what marks the page focused; a false leaves the
                // retry to the next rail that lands behind this one.
                focusPlaced = restoreBrowseReturn(recorded)
                return@LaunchedEffect
            }
            // The landing is what marks the page focused, exactly as the
            // return path does. A flag set BEFORE the suspend request was the
            // trap: this effect restarts whenever another rail streams in, so
            // an attempt cancelled inside its delay left the flag true and the
            // restarted effect skipped the branch - an ordinary first open with
            // nothing focused and the D-pad dead. A false landing now leaves
            // the retry to the next rail that arrives.
            focusPlaced = focusFirstCard()
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
                state = browseListState,
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
                            shape = posterEdgeShape()
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
                    // Indexed, so a card that is clicked can name the rail it
                    // sits in AND where that rail was, which is what puts the
                    // page back on it (see [BrowseReturnFocus]).
                    itemsIndexed(
                        items = sections,
                        key = { _, section: StudioSection -> section.title }
                    ) { railIndex, section ->
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
                        returnTarget = returnTarget?.takeIf { it.railKey == section.title },
                        returnFocusRequester = returnFocusRequester,
                        onBeforeOpenDetail = { cardKey, index ->
                            BrowseReturnFocus.record(
                                BrowseReturnTarget(
                                    screenKey = browseReturnKey,
                                    itemId = cardKey,
                                    position = index,
                                    railKey = section.title,
                                    railIndex = railIndex
                                )
                            )
                        },
                        viewModel = viewModel,
                        onOpenPosterMenu = { item, requester, cardKey, index ->
                            // The menu's "Go to Details" opens the same Detail
                            // page this card's own press does, so it has to
                            // raise the same record - without one, Back from
                            // there reopens this rail at the top (see
                            // [BrowseReturnFocus]).
                            menuReturnTarget = BrowseReturnTarget(
                                screenKey = browseReturnKey,
                                itemId = cardKey,
                                position = index,
                                railKey = section.title,
                                railIndex = railIndex
                            )
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
                        // Raise the record a card press would, from the place
                        // this menu was opened: this action opens the SAME
                        // Detail page, and with no record Back reopened the
                        // page at its top (see [BrowseReturnFocus]).
                        menuReturnTarget?.let { BrowseReturnFocus.record(it) }
                        menuReturnTarget = null
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
    // The card a return from Detail is coming back for, and the requester the
    // page lands the focus with - both null/unused on an ordinary open (see
    // [BrowseReturnFocus]).
    returnTarget: BrowseReturnTarget?,
    returnFocusRequester: FocusRequester,
    onBeforeOpenDetail: (cardKey: String, index: Int) -> Unit,
    viewModel: StudioViewModel,
    // The long-press menu's own action needs the same pair the card press would
    // record: which card it was raised on, and where that card sits, so "Go to
    // Details" can raise the record too (see [BrowseReturnFocus]).
    onOpenPosterMenu: (StudioItem, FocusRequester, cardKey: String, index: Int) -> Unit
) {
    val rowState = rememberLazyListState()

    // Where this rail's return lands, resolved by the card's OWN key rather
    // than by the recorded position: a rail that has re-ordered under the
    // viewer (a hidden title, a new release) still holds the card, and testing
    // the position first meant NOTHING matched once the card had moved - the
    // return requester attached to no card, its retry missed the budget, and
    // the page fell through to its first-card path. The recorded position is
    // only the fallback, for a card the record cannot name.
    val returnIndex = returnTarget?.let { target ->
        section.items
            .indexOfFirst { "${it.mediaType}:${it.item.id}" == target.itemId }
            .takeIf { it >= 0 }
            ?: target.position.takeIf { it in section.items.indices }
    }

    // Puts this rail ON that card: a rail the viewer scrolled twenty cards into
    // has not composed it, so it has no focus node yet (see
    // [BrowseReturnFocus]).
    LaunchedEffect(returnIndex) {
        returnIndex?.let { index ->
            runCatching { rowState.scrollToItem(index) }
        }
    }

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
            // Aligned with the posters' rail inset - the app's screen edge -
            // instead of sitting 4dp inside it, which is the drift that made
            // this page's rails miss the line every other browse page keeps.
            modifier = Modifier.padding(start = KBScreenEdge, bottom = 8.dp)
        )

        LazyRow(
            state = rowState,
            // The vertical inset is the room a focused tile needs INSIDE the
            // row's own clips-to-bounds viewport: the 12dp glow plus the ~3% a
            // poster grows by per side (see [KBFocusRowInset]). Without it the
            // top of the halo on the first row of tiles was sliced off flat.
            contentPadding = PaddingValues(
                start = KBScreenEdge,
                end = KBScreenEdge,
                top = KBFocusRowInset,
                bottom = KBFocusRowInset
            ),
            modifier = Modifier.focusGroup()
        ) {
            itemsIndexed(
                items = section.items,
                key = { _, item: StudioItem ->
                    "${item.mediaType}:${item.item.id}"
                }
            ) { itemIndex, studioItem ->
                // Focus requester for restoring focus after the long-press
                // menu dismisses, so the D-pad lands back on this exact card.
                val requester = remember(
                    studioItem.item.id,
                    studioItem.mediaType
                ) {
                    FocusRequester()
                }

                val cardKey = "${studioItem.mediaType}:${studioItem.item.id}"

                // Matched on the key alone (see [returnIndex]), which is what
                // makes the promise true: a rail that has re-ordered under the
                // viewer comes back on the card that is still there rather
                // than on whatever took its position.
                val isReturnItem = itemIndex == returnIndex

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
                            // Remember this card, so backing out of its Detail
                            // page reopens this rail ON it.
                            onBeforeOpenDetail(cardKey, itemIndex)
                            viewModel.resolveAndNavigate(
                                tmdbId = studioItem.item.id,
                                mediaType = rawMediaType,
                                onNavigateDetail = onNavigateDetail
                            )
                        },
                        onLongClick = {
                            onOpenPosterMenu(studioItem, requester, cardKey, itemIndex)
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
                            .then(
                                if (isReturnItem) {
                                    Modifier.focusRequester(returnFocusRequester)
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
