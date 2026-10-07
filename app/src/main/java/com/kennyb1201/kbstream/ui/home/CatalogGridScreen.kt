package com.kennyb1201.kbstream.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.paging.filter
import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.ui.components.GlobalPosterCard
import com.kennyb1201.kbstream.ui.components.KBPageTitle
import com.kennyb1201.kbstream.ui.components.KBSkeletonGrid
import com.kennyb1201.kbstream.ui.components.PosterCaptions
import com.kennyb1201.kbstream.ui.components.landscapeTileHeight
import com.kennyb1201.kbstream.ui.components.landscapeTileWidth
import com.kennyb1201.kbstream.ui.components.posterEdgeShape
import com.kennyb1201.kbstream.ui.components.rememberHomeLandscape
import com.kennyb1201.kbstream.ui.components.KBStatusMessage
import com.kennyb1201.kbstream.ui.components.KB_STATUS_ICON_EMPTY
import com.kennyb1201.kbstream.ui.components.KB_STATUS_LOADING
import com.kennyb1201.kbstream.ui.components.heroSourceElement
import com.kennyb1201.kbstream.ui.components.rememberPosterSize
import com.kennyb1201.kbstream.data.library.HiddenTitles
import com.kennyb1201.kbstream.data.library.LibraryIds
import com.kennyb1201.kbstream.ui.components.LibraryAddTarget
import com.kennyb1201.kbstream.ui.components.hideTarget
import com.kennyb1201.kbstream.ui.components.PosterContextAction
import com.kennyb1201.kbstream.ui.components.rememberHiddenTitleKeys
import com.kennyb1201.kbstream.ui.components.PosterContextMenu
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBScreenEdge
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map

/**
 * Full-catalog poster grid ("Open in Grid" on a Home rail's long-press
 * menu): the whole catalog browsable without endless horizontal scrolling.
 *
 * Paged by Paging 3. The rail the grid was opened from has already fetched its
 * first batch, so [HomeViewModel.openCatalogInGrid] seeds that batch as the
 * grid's first page (instant paint) and the PagingSource resumes from wherever
 * the rail stopped — this screen only renders what the pager emits and reacts
 * to its LoadState.
 */
@Composable
fun CatalogGridScreen(
    title: String,
    addonName: String,
    viewModel: HomeViewModel =
        viewModel(),
    onItemClick: (MetaPreview) -> Unit,
    onBack: () -> Unit
) {
    val header by viewModel.catalogGridHeader.collectAsStateWithLifecycle()
    val paging by viewModel.catalogGridPaging.collectAsStateWithLifecycle()

    // Hidden titles leave the grid the same way they leave the rails. The
    // filter rides the paging stream, so hiding a title that is already on
    // screen removes it without reloading the catalog.
    val hiddenTitleKeys = rememberHiddenTitleKeys()
    val pagedFlow = paging
    val items = remember(pagedFlow, hiddenTitleKeys) {
        pagedFlow?.map { pagingData ->
            pagingData.filter { meta ->
                !HiddenTitles.hides(
                    hiddenTitleKeys,
                    meta.type,
                    meta.name,
                    meta.yearOrNull,
                    meta.id
                )
            }
        }
    }?.collectAsLazyPagingItems()

    val watchedKeys by viewModel.watchedKeys.collectAsStateWithLifecycle()
    val partialWatchedKeys by
        viewModel.partialWatchedKeys.collectAsStateWithLifecycle()

    var menuTarget by remember { mutableStateOf<MetaPreview?>(null) }

    // Fresh screen instance (or restored route): (re)open the catalog from
    // its identity. No-op when the grid is already showing this catalog —
    // preserves loaded pages across recompositions. The DisposableEffect
    // clears the shared ViewModel state when the screen leaves composition
    // so a stale grid never leaks into later Home sessions.
    //
    // The pop-back-to-Home decision lives HERE, not in the null-branch
    // below: a fresh open always composes one frame with an unseeded grid
    // (the ViewModel state starts null), and an onBack launched from that
    // frame still fires after the open effect has successfully seeded the
    // grid — the grid flashed for one frame and dumped the user back on
    // Home with focus dropped on Continue Watching.
    LaunchedEffect(title, addonName) {
        // On a cold process restore the rails may still be loading, so
        // retry briefly before concluding the catalog is really gone.
        repeat(50) {
            viewModel.openCatalogInGrid(title, addonName)
            if (viewModel.catalogGridHeader.value != null) {
                return@LaunchedEffect
            }
            delay(100L)
        }
        onBack()
    }

    DisposableEffect(title, addonName) {
        onDispose {
            viewModel.closeCatalogGrid()
        }
    }

    val openHeader = header
    val lazyItems = items

    // The tile this grid draws, in the shape it is actually drawn in: a Home
    // surface follows the Home-rails landscape switch as well as the everywhere
    // one (see AppPreferences.homeLandscapeActive), exactly like the rail the
    // grid was opened from - a viewer who set up Home that way is not asking for
    // posters back the moment they press Open in Grid.
    //
    // Cell, card and caption all read from here, so the three cannot disagree
    // about the shape: that is what made these tiles square (see the grid
    // below).
    val posterSize = rememberPosterSize()
    val landscape = rememberHomeLandscape()
    val tileWidth =
        if (landscape) landscapeTileWidth(posterSize.width) else posterSize.width
    val tileHeight =
        if (landscape) landscapeTileHeight(posterSize.width) else posterSize.height

    if (openHeader == null || lazyItems == null) {
        // Grid not seeded yet. On a fresh open the effect above seeds it
        // within a frame; this is just that one loading frame (plus a
        // possible cold-restore wait for the rails to load). It NEVER
        // navigates from here — see the open effect above for why.
        // Skeleton grid rather than a centered spinner: the cards cost the
        // same space either way, so the page does not jump when the real
        // posters land. The placeholders take the shape the real tiles will
        // (see tileWidth/tileHeight above), or the page would jump between two
        // shapes instead of between nothing and one.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(KBVoid)
        ) {
            KBSkeletonGrid(
                cellWidth = tileWidth,
                cellHeight = tileHeight,
                // The live grid sizes its cells from the tile itself
                // (GridCells.Adaptive below), so it draws as many columns as
                // the pane holds. A landscape tile is 1.7x as wide as the
                // poster it replaces, so six of them no longer fit the pane
                // six posters did - and the skeleton rows are laid out at
                // fixed size, so they would run off it.
                columns = if (landscape) 4 else 6,
                rows = 2,
                shape = posterEdgeShape()
            )
        }
        return
    }

    val gridState = rememberLazyGridState()

    val refreshState = lazyItems.loadState.refresh
    val appendState = lazyItems.loadState.append

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid)
    ) {
        KBPageTitle(
            text = openHeader.title,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 24.dp,
                    end = 24.dp,
                    top = 18.dp,
                    bottom = 2.dp
                )
        )

        Text(
            text = openHeader.addonName,
            style = MaterialTheme.typography.labelMedium,
            color = KBTextLo,
            // One line, like the page title above it: a long catalog name used
            // to wrap and push the whole grid down.
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, bottom = 10.dp)
        )

        when {
            refreshState is LoadState.Loading && lazyItems.itemCount == 0 -> {
                KBStatusMessage(loading = true, message = KB_STATUS_LOADING)
            }

            // The shared status card, like the empty branch below it and every
            // other browse screen (Decade / Tag / Studio / Actor). Retrying
            // goes back through the same source, so a first page that failed
            // recovers in place instead of requiring a trip back to Home.
            refreshState is LoadState.Error && lazyItems.itemCount == 0 -> {
                KBStatusMessage(
                    message = "Error: ${refreshState.error.message}",
                    onRetry = { lazyItems.retry() }
                )
            }

            lazyItems.itemCount == 0 -> {
                KBStatusMessage(
                    icon = KB_STATUS_ICON_EMPTY,
                    message = "Nothing to show here."
                )
            }

            else -> {
                LazyVerticalGrid(
                    state = gridState,
                    // Sized from the tile, not a hardcoded six. Six cells of the
                    // POSTER width cannot hold a landscape card: the cell clamps
                    // the card's width back to roughly the poster width while its
                    // height stays the landscape height, so a 210x118 tile was
                    // drawn short and wide - square rather than 16:9. Library's
                    // grid carries the same fix and the same note for the same
                    // defect. Adapting to the tile also means the Poster Size
                    // setting reaches this grid.
                    columns = GridCells.Adaptive(minSize = tileWidth),
                    contentPadding = PaddingValues(
                        start = KBScreenEdge,
                        end = KBScreenEdge,
                        top = 4.dp,
                        bottom = 24.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        count = lazyItems.itemCount,
                        key = lazyItems.itemKey { "${it.type}:${it.id}" }
                    ) { index ->
                        // Null while a placeholder stands in for a not-yet-
                        // loaded item; placeholders are off, so this is only
                        // the differ's brief window between a page arriving
                        // and its items being indexed.
                        val meta = lazyItems[index] ?: return@items

                        // The tile's own focus drives the caption marquee below,
                        // like every other poster surface in the app.
                        var focused by remember { mutableStateOf(false) }

                        Column(
                            modifier = Modifier
                                .width(tileWidth)
                                .onFocusChanged { focused = it.hasFocus }
                        ) {
                            GlobalPosterCard(
                                posterUrl = meta.poster,
                                backdropUrl = meta.background,
                                logoUrl = meta.logo,
                                contentDescription = meta.name,
                                isWatched =
                                    viewModel.watchedKey(meta.id, meta.type) in
                                        watchedKeys,
                                isPartiallyWatched =
                                    viewModel.watchedKey(meta.id, meta.type) in
                                        partialWatchedKeys,
                                onClick = {
                                    onItemClick(meta)
                                },
                                onLongClick = {
                                    menuTarget = meta
                                },
                                posterWidth = posterSize.width,
                                posterHeight = posterSize.height,
                                // The add-on art above may be present; the
                                // resolver still supplies the alternate backdrop
                                // + clearlogo the rails use.
                                artId = meta.id,
                                artType = meta.type,
                                // This grid's own rule, not the global switch:
                                // it hangs off Home, so Home's landscape setting
                                // has to reach it (the same value the cell was
                                // sized from above).
                                landscape = landscape,
                                modifier = Modifier
                                    // Opts this tile into the poster ->
                                    // Detail hero flight (shared key is
                                    // type:id, same as the rail posters).
                                    .heroSourceElement(meta.type, meta.id)
                            )
                            // The shared caption block, so the "Poster Titles /
                            // Years / Star Ratings" toggles reach this grid like
                            // every other screen: it used to draw the title
                            // itself and ignore all three, and it had no rating
                            // or year to show at all. A catalog's own preview
                            // supplies both (see MetaPreview.yearOrNull and
                            // imdbRating); a catalog that ships neither simply
                            // shows what it has.
                            PosterCaptions(
                                title = meta.name,
                                year = meta.yearOrNull?.toString(),
                                rating = meta.imdbRating?.toDoubleOrNull(),
                                focused = focused,
                                modifier = Modifier.padding(top = 5.dp)
                            )
                        }
                    }

                    if (appendState is LoadState.Loading) {
                        item(
                            key = "grid_footer",
                            span = { GridItemSpan(maxLineSpan) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    color = KBAccent,
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                    } else if (appendState is LoadState.Error) {
                        // A failed *append* leaves the loaded rows on screen,
                        // so the full-page retry card above never applies.
                        // This footer is the retry: Paging holds the failing
                        // page until it is asked again.
                        item(
                            key = "grid_footer",
                            span = { GridItemSpan(maxLineSpan) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 14.dp)
                                    .clickable { lazyItems.retry() },
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "Couldn't load more - press to retry",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = KBTextLo,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    menuTarget?.let { target ->
        val isWatched =
            viewModel.watchedKey(target.id, target.type) in watchedKeys

        // Same split the rail menu uses: a grid item's id is "tmdb:123" on a
        // TMDB-sourced catalog and "tt12345" on an add-on one.
        val gridIds = LibraryIds.split(target.id)

        PosterContextMenu(
            title = target.name,
            libraryTarget = LibraryAddTarget(
                mediaType = target.type,
                imdbId = gridIds.imdbId,
                tmdbId = gridIds.tmdbId,
                title = target.name,
                year = target.yearOrNull,
                posterUrl = target.poster
            ),
            hideTarget = hideTarget(
                target.name,
                target.type,
                target.poster,
                listOf(target.id),
                year = target.yearOrNull
            ),
            actions = listOf(
                PosterContextAction(
                    label = "Go to Details",
                    description = "Open this title's detail page"
                ) {
                    menuTarget = null
                    onItemClick(target)
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
                    menuTarget = null
                    if (isWatched) {
                        viewModel.markUnwatched(target)
                    } else {
                        viewModel.markAsWatched(target)
                    }
                }
            ),
            onDismiss = {
                menuTarget = null
            }
        )
    }
}
