package com.kennyb1201.kbstream.ui.collection

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.kennyb1201.kbstream.data.images.ImageOverrides
import com.kennyb1201.kbstream.data.tmdb.TmdbCollectionPart
import com.kennyb1201.kbstream.ui.components.KBStatusMessage
import com.kennyb1201.kbstream.ui.components.KB_STATUS_ICON_EMPTY
import com.kennyb1201.kbstream.ui.components.KB_STATUS_LOADING
import com.kennyb1201.kbstream.ui.components.PosterCaptions
import com.kennyb1201.kbstream.ui.components.GlobalPosterCard
import com.kennyb1201.kbstream.ui.components.imageOverrideBackdropUrl
import com.kennyb1201.kbstream.ui.components.imageOverridePosterUrl
import com.kennyb1201.kbstream.ui.components.rememberImageOverridePaths
import com.kennyb1201.kbstream.ui.components.rememberPosterSize
import com.kennyb1201.kbstream.ui.components.rememberPosterTileWidth
import com.kennyb1201.kbstream.data.library.HiddenTitles
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.ui.components.hideTarget
import com.kennyb1201.kbstream.ui.components.LibraryAddTarget
import com.kennyb1201.kbstream.ui.browse.BROWSE_RETURN_FOCUS_DELAY_MS
import com.kennyb1201.kbstream.ui.browse.BROWSE_RETURN_FOCUS_STEPS
import com.kennyb1201.kbstream.ui.browse.BrowseReturnFocus
import com.kennyb1201.kbstream.ui.browse.BrowseReturnTarget
import com.kennyb1201.kbstream.ui.browse.collectionBrowseScreenKey
import com.kennyb1201.kbstream.ui.components.PosterContextAction
import com.kennyb1201.kbstream.ui.components.rememberHiddenTitleKeys
import com.kennyb1201.kbstream.ui.components.PosterContextMenu
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBFocusRowInset
import com.kennyb1201.kbstream.ui.theme.KBShapePanel
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.mobile.CollectionScreenMobile
import com.kennyb1201.kbstream.ui.theme.KBVoid
import kotlinx.coroutines.delay

/**
 * Collection screen: a fixed (non-scrolling) header — poster, name, count,
 * overview — above a single poster rail. Collections hold 2-5 movies, so
 * everything fits on one screen without the old full-width rows and their
 * wasted space.
 */
@Composable
fun CollectionScreen(
    collectionId: Int,
    collectionName: String,
    // The phone's header arrow; the TV page draws no back control.
    onBack: () -> Unit,
    onNavigateDetail: (type: String, id: String) -> Unit,
    viewModel: CollectionViewModel = viewModel()
) {
    // The phone entry: the same ViewModel, release order, hidden-title filter
    // and part actions, laid out for touch. One branch; the TV page below is
    // unchanged (see CollectionScreenMobile).
    if (!com.kennyb1201.kbstream.FormFactor.isTv) {
        CollectionScreenMobile(
            collectionId = collectionId,
            collectionName = collectionName,
            onBack = onBack,
            onNavigateDetail = onNavigateDetail,
            viewModel = viewModel
        )
        return
    }

    val collectionRaw by viewModel.collection.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val watchedKeys by viewModel.watchedKeys.collectAsStateWithLifecycle()
    val partialWatchedKeys by viewModel.partialWatchedKeys.collectAsStateWithLifecycle()
    val resolvedIds by viewModel.resolvedIds.collectAsStateWithLifecycle()
    // A hidden film drops out of its franchise rail too.
    val hiddenTitleKeys = rememberHiddenTitleKeys()
    val collection =
        remember(collectionRaw, resolvedIds, hiddenTitleKeys) {
            collectionRaw?.let { detail ->
                detail.copy(
                    parts = detail.parts.filterNot { part ->
                        HiddenTitles.hides(
                            hiddenTitleKeys,
                            "movie",
                            part.title ?: part.name,
                            part.releaseDate?.take(4)?.toIntOrNull(),
                            part.id.toString(),
                            resolvedIds[
                                viewModel.lookupKey(part.id, "movie")
                            ]
                        )
                    }
                )
            }
        }

    // Long-press context menu for collection part posters.
    var menuPart by remember {
        mutableStateOf<TmdbCollectionPart?>(
            null
        )
    }

    var lastPartFocusRequester by remember {
        mutableStateOf<FocusRequester?>(
            null
        )
    }

    fun dismissPartMenu() {
        menuPart = null
        lastPartFocusRequester?.requestFocus()
    }

    LaunchedEffect(collectionId) {
        viewModel.load(collectionId)
    }

    val detail = collection

    // The viewer's image choices (see ImageOverrides), read as state so a still
    // picked in the Media row's menu repaints this header on the next frame.
    // This is the screen the report named: its header drew TMDB's own artwork
    // whatever had been chosen, because the poster was handed to the card with
    // no id to look a choice up by and the header drew no backdrop at all.
    val overridePaths = rememberImageOverridePaths()

    // Release order, resolved once: the rail paints in this order and the card
    // a return from Detail is coming back for is a position IN this list, so
    // the two have to be the same list (see [BrowseReturnFocus]).
    val parts = remember(detail) {
        detail?.parts?.sortedBy { it.releaseDate ?: "9999-99-99" }.orEmpty()
    }

    // Where this page's Detail page was opened from, read once when the films
    // land: the card the viewer pressed and its place in the rail, so backing
    // out of Detail reopens the franchise where they left it instead of at the
    // top. A collection is ONE rail that is already on screen, so there is no
    // outer list to put back first - only the rail's own scroll and focus.
    val browseReturnKey = remember(collectionId) {
        collectionBrowseScreenKey(collectionId)
    }
    val rowState = rememberLazyListState()
    // PER COLLECTION, like the actor page's record (see ActorScreen): this
    // composable is one page for every collection, so a page opened FROM
    // another one reuses it and an unkeyed record would be the previous
    // collection's card, tried and budgeted against a rail that is not here.
    // Unreachable while no collection links to another; cheap hardening.
    var returnTarget by remember(collectionId) {
        mutableStateOf<BrowseReturnTarget?>(null)
    }
    val returnFocusRequester = remember { FocusRequester() }

    /**
     * The rail's own first card. The landing a return falls THROUGH to when the
     * recorded card is not on screen any more (the collection changed while its
     * Detail page was up): without it the effect early-returned and left nothing
     * focused, so Back out of Detail left the D-pad dead until the viewer
     * pressed something that happened to grab focus.
     */
    val firstPartFocusRequester = remember { FocusRequester() }

    // The card is named by the key its own rail keys it with, so a collection
    // that has re-ordered under the viewer comes back on the film that is still
    // there rather than on whatever took its position; the recorded position is
    // the fallback for a card the record cannot name.
    val returnIndex = returnTarget?.let { target ->
        parts.indexOfFirst { "collection_part:${it.id}" == target.itemId }
            .takeIf { it >= 0 }
            ?: target.position
    }

    LaunchedEffect(parts, returnTarget) {
        val recorded = returnTarget ?: BrowseReturnFocus.consume(browseReturnKey)
            ?: return@LaunchedEffect
        // Held from the moment it is read: the films arrive after the screen
        // composes, and a record read and then dropped because they are not
        // there yet would lose the place it was raised for.
        if (returnTarget == null) returnTarget = recorded
        if (parts.isEmpty()) return@LaunchedEffect
        val index = parts.indexOfFirst { "collection_part:${it.id}" == recorded.itemId }
            .takeIf { it >= 0 }
            ?: recorded.position.takeIf { it in parts.indices }
            ?: return@LaunchedEffect
        runCatching { rowState.scrollToItem(index) }
        // The card is only focusable once its row has composed it, which is a
        // frame or more after the rail itself came back, and a request against
        // a card that is not composed yet is a silent miss.
        var landed = false
        var attempts = 0
        while (!landed && attempts < BROWSE_RETURN_FOCUS_STEPS) {
            delay(BROWSE_RETURN_FOCUS_DELAY_MS)
            landed = runCatching { returnFocusRequester.requestFocus() }
                .getOrDefault(false)
            attempts++
        }
        // The record is SPENT, either way. Left in state it also keys this
        // effect, so the next change to the rail re-ran the whole landing - and
        // the second drill-down got the stale record instead of the fresh one
        // the card just recorded.
        returnTarget = null
        if (!landed) {
            // This page could never put the viewer back (the card it named is
            // gone). The rail's first card is where an ordinary open lands, so
            // that is the fallthrough - not an early return with nothing
            // focused, which is a dead D-pad on the way back from Detail.
            var firstLanded = false
            var firstAttempts = 0
            while (!firstLanded && firstAttempts < BROWSE_RETURN_FOCUS_STEPS) {
                delay(BROWSE_RETURN_FOCUS_DELAY_MS)
                firstLanded = runCatching { firstPartFocusRequester.requestFocus() }
                    .getOrDefault(false)
                firstAttempts++
            }
        }
    }

    // Screen root Box so the long-press context menu's full-screen scrim
    // overlays everything (the menu fills whatever parent it's placed in).
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid)
    ) {
        // Full-bleed screen (matches SearchScreen): horizontal edge spacing
        // lives in the header/panel padding and the rail's contentPadding,
        // NOT on this container. A fixed-inset parent would clip the rail
        // items at the inset edge, chopping the focused poster's 1.03 scale
        // + accent glow on the first/last tile.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Fixed header: never scrolls with the content. Own horizontal
            // padding keeps its inset now that the container is full-bleed.
            Box(Modifier.padding(horizontal = 24.dp)) {
                CollectionHeader(
                    name = detail?.name ?: collectionName,
                    overview = detail?.overview,
                    // Keyed on the collection's own TMDB id, so a poster chosen
                    // for it is the one drawn; TMDB's own otherwise.
                    posterUrl = imageOverridePosterUrl(
                        paths = overridePaths,
                        rawId = collectionId.toString(),
                        fallbackUrl = detail?.posterPath
                            ?.let { "${TmdbRepository.POSTER_BASE}$it" }
                    ),
                    // The header's still, through the same chain every other
                    // backdrop surface walks (see
                    // ImageOverrides.backdropPathFor): the still chosen for
                    // this screen, then the all-screens one, then TMDB's own
                    // for the collection. A collection offers no Media row of
                    // its own, so for it the chain is what reaches the header
                    // at all - and the all-screens key is the one a viewer can
                    // reach for a title the collection holds.
                    backdropUrl = imageOverrideBackdropUrl(
                        paths = overridePaths,
                        tmdbId = collectionId,
                        screen = ImageOverrides.SCREEN_ALL,
                        fallbackUrl = detail?.backdropPath
                            ?.takeIf { it.isNotBlank() }
                            ?.let { TmdbRepository.fullResBackdropUrl(it) }
                    ),
                    partCount = detail?.parts?.size
                )
            }

            when {
                isLoading -> {
                    KBStatusMessage(
                        loading = true,
                        message = KB_STATUS_LOADING,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 4.dp)
                    )
                }

                detail == null -> {
                    KBStatusMessage(
                        message = "We couldn't load this collection right now.",
                        onRetry = { viewModel.load(collectionId) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 4.dp)
                    )
                }

                detail.parts.isEmpty() -> {
                    KBStatusMessage(
                        icon = KB_STATUS_ICON_EMPTY,
                        message = "This collection lists no movies yet.",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 4.dp)
                    )
                }

                else -> {
                    // Compact poster rail: release order, watched badges, and
                    // title/year captions — 2-5 titles fill one screen.
                    LazyRow(
                        state = rowState,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        // 24dp edge insets (matching the header) keep the focused
                        // poster's 1.03 scale + glow from clipping on the first
                        // and last tiles.
                        contentPadding = PaddingValues(
                            // Vertical room for the focus glow too, not just the
                            // 2dp/4dp that was here: the row clips to its own
                            // bounds, so the top of the halo on the films was
                            // sliced off flat (see [KBFocusRowInset]).
                            top = KBFocusRowInset,
                            bottom = KBFocusRowInset,
                            start = 24.dp,
                            end = 24.dp
                        )
                    ) {
                        itemsIndexed(
                            items = parts,
                            key = { _, part: TmdbCollectionPart ->
                                "collection_part:${part.id}"
                            }
                        ) { index: Int, part: TmdbCollectionPart ->
                            // Focus requester for restoring focus after the
                            // long-press menu dismisses.
                            val requester = remember(
                                part.id
                            ) {
                                FocusRequester()
                            }

                            // The card this page's Detail page was opened from:
                            // the one the focus is handed back to.
                            val isReturnItem = index == returnIndex

                            // The OTHER id flavor a part can be keyed under,
                            // and the one that needs no resolution: its own
                            // TMDB id. Only consulted when the IMDB key misses,
                            // so a part that resolved badges exactly as it did.
                            val tmdbKeyId = "tmdb:${part.id}"
                            val watched =
                                resolvedIds[
                                    viewModel.lookupKey(part.id, "movie")
                                ]?.let { imdbId ->
                                    viewModel.watchedKey(
                                        imdbId,
                                        "movie"
                                    ) in watchedKeys
                                } == true ||
                                    viewModel.watchedKey(tmdbKeyId, "movie") in watchedKeys

                            CollectionPosterTile(
                                part = part,
                                isWatched = watched,
                                // The checkmark wins over the eye - tested
                                // here rather than trusted from the view
                                // model's own subtraction, which reads the
                                // PREVIOUS watchedKeys emission.
                                isPartiallyWatched =
                                    !watched && (
                                        resolvedIds[
                                            viewModel.lookupKey(part.id, "movie")
                                        ]?.let { imdbId ->
                                            viewModel.watchedKey(
                                                imdbId,
                                                "movie"
                                            ) in partialWatchedKeys
                                        } == true ||
                                            viewModel.watchedKey(tmdbKeyId, "movie") in partialWatchedKeys
                                        ),
                                onClick = {
                                    // Remember this film, so backing out of its
                                    // Detail page reopens the rail ON it.
                                    BrowseReturnFocus.record(
                                        BrowseReturnTarget(
                                            screenKey = browseReturnKey,
                                            itemId = "collection_part:${part.id}",
                                            position = index
                                        )
                                    )
                                    onNavigateDetail(
                                        "movie",
                                        part.id.toString()
                                    )
                                },
                                onLongClick = {
                                    lastPartFocusRequester =
                                        requester
                                    menuPart = part
                                },
                                modifier = Modifier
                                    .focusRequester(requester)
                                    .then(
                                        when {
                                            isReturnItem -> Modifier.focusRequester(
                                                returnFocusRequester
                                            )
                                            // Only reachable when no return is
                                            // pending, so the two requesters can
                                            // never be attached to one card.
                                            index == 0 -> Modifier.focusRequester(
                                                firstPartFocusRequester
                                            )
                                            else -> Modifier
                                        }
                                    )
                            )
                        }
                    }
                }
            }
        }

        // Long-press context menu for collection part posters. Sits inside
        // the root Box so its full-screen scrim covers the whole screen.
        menuPart?.let { part ->
            // Same lookup the poster badge uses, so the toggle matches what
            // the poster currently shows.
            val isWatched =
                resolvedIds[
                    viewModel.lookupKey(
                        part.id,
                        "movie"
                    )
                ]?.let { imdbId ->
                    viewModel.watchedKey(
                        imdbId,
                        "movie"
                    ) in watchedKeys
                } == true

            PosterContextMenu(
                title = part.title
                    ?: part.name
                    ?: "",
                // The part's TMDB id plus the IMDB one this screen already
                // resolved for the badge, so the add reaches the trackers.
                libraryTarget = LibraryAddTarget(
                    mediaType = "movie",
                    imdbId = resolvedIds[
                        viewModel.lookupKey(part.id, "movie")
                    ],
                    tmdbId = part.id,
                    title = part.title
                        ?: part.name
                        ?: "Untitled",
                    year = (part.releaseDate)
                        ?.take(4)?.toIntOrNull(),
                    posterUrl = part.posterPath
                        ?.takeIf { it.isNotBlank() }
                        ?.let { TmdbRepository.POSTER_BASE + it }
                ),
                hideTarget = hideTarget(
                    part.title ?: part.name ?: "",
                    "movie",
                    part.posterPath
                        ?.takeIf { it.isNotBlank() }
                        ?.let { "${TmdbRepository.POSTER_BASE}$it" },
                    listOf(
                        part.id.toString(),
                        resolvedIds[
                            viewModel.lookupKey(part.id, "movie")
                        ]
                    ),
                    year = part.releaseDate?.take(4)?.toIntOrNull()
                ),
                actions = listOf(
                    PosterContextAction(
                        label = "Go to Details",
                        description = "Open this movie's detail page"
                    ) {
                        val selected = part
                        menuPart = null
                        // Raise the record a card press would, from the place
                        // this menu was opened: this action opens the SAME
                        // Detail page, and with no record Back reopened the
                        // rail at its top (see [BrowseReturnFocus]). The key
                        // and the place come from the same release-ordered list
                        // the rail paints, so the two agree by construction.
                        BrowseReturnFocus.record(
                            BrowseReturnTarget(
                                screenKey = browseReturnKey,
                                itemId = "collection_part:${selected.id}",
                                position = parts
                                    .indexOfFirst { it.id == selected.id }
                                    .takeIf { it >= 0 } ?: 0
                            )
                        )
                        onNavigateDetail(
                            "movie",
                            selected.id.toString()
                        )
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
                            "Show this movie as watched"
                        }
                    ) {
                        val selected = part
                        menuPart = null
                        if (isWatched) {
                            viewModel.markUnwatched(selected.id)
                        } else {
                            viewModel.markAsWatched(selected.id)
                        }
                        lastPartFocusRequester?.requestFocus()
                    }
                ),
                onDismiss = {
                    dismissPartMenu()
                }
            )
        }
    }
}

/**
 * Fixed screen header: poster, name, accent count line, and the collection
 * overview. No type pill — the screen itself says what it is.
 */
@Composable
private fun CollectionHeader(
    name: String,
    overview: String?,
    posterUrl: String?,
    backdropUrl: String?,
    partCount: Int?
) {
    // The panel, with the collection's still behind its content when there is
    // one: a franchise header that carried only a poster left the rest of the
    // row as flat surface, and a chosen still had nowhere to be drawn. The
    // scrim is what keeps the name and the overview readable over it, and the
    // hairline edge is drawn last so the image cannot paint over it.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(KBShapePanel)
            .background(KBSurface, KBShapePanel)
    ) {
        if (!backdropUrl.isNullOrBlank()) {
            AsyncImage(
                model = backdropUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                KBVoid.copy(alpha = 0.9f),
                                KBVoid.copy(alpha = 0.6f)
                            )
                        )
                    )
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlobalPosterCard(
                posterUrl = posterUrl,
                contentDescription = name,
                isWatched = false,
                onClick = {},
                posterWidth = 110.dp,
                posterHeight = 165.dp,
                // The header's own poster is decoration, and it always was -
                // its click was a no-op. It stayed focusable, so it was the
                // first thing the D-pad highlighted on open, OK did nothing on
                // it, and reaching the films cost an extra DOWN. Out of the
                // focus order, the page opens on the first film instead, which
                // is what the press was going to be spent on anyway.
                modifier = Modifier.focusProperties { canFocus = false }
            )

            Column(
                modifier = Modifier
                    .padding(start = 18.dp)
                    .weight(1f)
            ) {
                Text(
                    text = name,
                    // A collection hero is a screen title, not a section label:
                    // it was headlineSmall (22sp), so a saga's name read smaller
                    // than the page title of the screen that opened it.
                    style = MaterialTheme.typography.headlineLarge,
                    color = KBTextHi,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                partCount?.let { count ->
                    Text(
                        text = if (count == 1) "1 movie" else "$count movies",
                        color = KBAccent,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                overview
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        Text(
                            text = it,
                            color = KBTextLo,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
            }
        }

        Box(
            Modifier
                .matchParentSize()
                .border(1.dp, KBTextLo.copy(alpha = 0.25f), KBShapePanel)
        )
    }
}

/**
 * Poster tile for a collection part: poster with watched badge plus a small
 * title/year caption — same proportions as search tiles, so a handful of
 * movies fills the rail instead of one stretched row each.
 */
@Composable
private fun CollectionPosterTile(
    part: TmdbCollectionPart,
    isWatched: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    isPartiallyWatched: Boolean = false
) {
    val posterSize = rememberPosterSize()
    // Landscape tiles are wider than the poster they replace, so the tile's own
    // container follows the shape the card is about to draw.
    val tileWidth = rememberPosterTileWidth(posterSize.width)
    // The tile's own focus drives the caption marquee below.
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .width(tileWidth)
            .onFocusChanged { focused = it.hasFocus }
    ) {
        GlobalPosterCard(
            posterUrl = part.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
            contentDescription = part.title
                ?: part.name
                ?: "Collection movie",
            isWatched = isWatched,
            isPartiallyWatched = isPartiallyWatched,
            onClick = onClick,
            onLongClick = onLongClick,
            posterWidth = posterSize.width,
            posterHeight = posterSize.height,
            // A collection part carries only a poster; the shared resolver
            // fills in the backdrop + clearlogo for the landscape shape.
            artId = part.id.toString(),
            artType = "movie"
        )

        PosterCaptions(
            title = part.title ?: part.name,
            focused = focused,
            year = part.releaseDate?.take(4),
            rating = part.voteAverage,
            modifier = Modifier.padding(top = 5.dp)
        )
    }
}

