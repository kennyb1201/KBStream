package com.kennyb1201.kbstream.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.filter
import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.data.library.HiddenTitles
import com.kennyb1201.kbstream.data.library.LibraryIds
import com.kennyb1201.kbstream.ui.components.LibraryAddTarget
import com.kennyb1201.kbstream.ui.components.PosterContextAction
import com.kennyb1201.kbstream.ui.components.hideTarget
import com.kennyb1201.kbstream.ui.components.rememberHiddenTitleKeys
import com.kennyb1201.kbstream.ui.components.landscapeTileWidth
import com.kennyb1201.kbstream.ui.components.rememberHomeLandscape
import com.kennyb1201.kbstream.ui.components.touch.KBButton
import com.kennyb1201.kbstream.ui.components.touch.PosterContextMenu
import com.kennyb1201.kbstream.ui.components.touch.TouchGlobalPosterTile
import com.kennyb1201.kbstream.ui.components.touch.TouchPosterCaptions
import com.kennyb1201.kbstream.ui.components.touch.rememberTouchPosterSize
import com.kennyb1201.kbstream.ui.components.watchedMenuDescription
import com.kennyb1201.kbstream.ui.components.watchedMenuLabel
import com.kennyb1201.kbstream.ui.home.HomeViewModel
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import com.kennyb1201.kbstream.ui.theme.MobileTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map

/**
 * The catalog grid ("Open in Grid" on a Home rail's long-press menu), for a
 * finger.
 *
 * Same screen, same data path: the activity-scoped [HomeViewModel],
 * `openCatalogInGrid` to seed the pager from the rail's already-fetched batch,
 * `closeCatalogGrid` when the page leaves, the same hidden-title filter riding
 * the paging stream, the same watched/partial badges, the same append footer,
 * and the same row menu. What changes is the surface - a `LazyVerticalGrid` with
 * finger scrolling and the touch poster/landscape cards instead of a D-pad grid
 * of focusable ones - plus everything the D-pad needed and a thumb does not:
 * per-tile focus requesters, the successor-key hand-off after Hide, the
 * verified focus landing, and the caption marquee.
 *
 * Column width is the phone's own choice (the 104dp poster / 168dp landscape
 * step Library and the browse pages use) rather than the TV's Poster Size
 * setting, which is a 10-foot measurement. The *shape* switch still comes from
 * Home: a rail drawn landscape opens a landscape grid, exactly as on TV.
 */
@Composable
fun CatalogGridScreenMobile(
    title: String,
    addonName: String,
    onItemClick: (MetaPreview) -> Unit,
    onBack: () -> Unit,
    viewModel: HomeViewModel = viewModel()
) {
    MobileTheme {
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
        val partialWatchedKeys by viewModel.partialWatchedKeys.collectAsStateWithLifecycle()

        // The shape the rail itself was drawn in, so "Open in Grid" does not
        // hand a landscape viewer a wall of posters.
        val landscape = rememberHomeLandscape()
        // The viewer's Poster Size, in this grid's own cell: 104dp used to be
        // pinned here, so Settings' size switch never reached the grid.
        val posterSize = rememberTouchPosterSize()
        val tileWidth =
            if (landscape) landscapeTileWidth(posterSize.width) else posterSize.width
        // Three poster columns in portrait, and the width buys more columns when
        // the phone is turned - see [rememberMobileGridColumns]. The tile keeps
        // its shape; only the cell is narrower.
        val columns = rememberMobileGridColumns(
            preferredTileWidth = tileWidth,
            landscape = landscape
        )

        var menuTarget by remember { mutableStateOf<MetaPreview?>(null) }

        // Same open/close lifecycle as the TV grid, including the reason the
        // pop-back lives in the open effect: a fresh open composes one frame
        // with an unseeded grid, and a Back launched from that frame still
        // fires after the seed has landed.
        LaunchedEffect(title, addonName) {
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

        // Hoisted above the loading branch so the scroll position belongs to the
        // screen rather than to the first frame that had content.
        val gridState = rememberLazyGridState()

        if (openHeader == null || lazyItems == null) {
            // The one loading frame (plus a possible cold-restore wait). The
            // skeleton rows are the cards' own footprint, so the page does not
            // jump when the posters land.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(KBVoid)
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    userScrollEnabled = false,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(columns * if (landscape) 3 else 2) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(if (landscape) 16f / 9f else 2f / 3f)
                                .background(KBSurface, KBShapeCard)
                        )
                    }
                }
                // The screen's way back is here from the first frame: the
                // catalog can take a moment to seed (and gives up after five
                // seconds), and a skeleton with no visible exit is exactly the
                // dead end this pass removes.
                MobileBackButton(
                    onBack = onBack,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                )
            }
            return@MobileTheme
        }

        val refreshState = lazyItems.loadState.refresh
        val appendState = lazyItems.loadState.append

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(KBVoid)
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 14.dp,
                    bottom = 28.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier.padding(bottom = 2.dp)
                    ) {
                        // The screen already had onBack + a BackHandler and
                        // drew nothing; this is the drawn control, on the same
                        // pop the handler performs.
                        MobileBackButton(onBack = onBack)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = openHeader.title,
                                color = KBTextHi,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = openHeader.addonName,
                                color = KBTextLo,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }

                when {
                    refreshState is LoadState.Loading && lazyItems.itemCount == 0 -> {
                        item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                            GridStatus(message = "Loading…")
                        }
                    }

                    // A failed first page retries in place, through the same
                    // source, instead of sending the viewer back to Home.
                    refreshState is LoadState.Error && lazyItems.itemCount == 0 -> {
                        item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                            GridStatus(
                                message = "Error: ${refreshState.error.message}",
                                action = "TRY AGAIN",
                                onAction = { lazyItems.retry() }
                            )
                        }
                    }

                    lazyItems.itemCount == 0 -> {
                        item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                            GridStatus(message = "Nothing to show here.")
                        }
                    }

                    else -> items(
                        count = lazyItems.itemCount,
                        // PagingData may legitimately repeat a title (an
                        // add-on catalog whose pages overlap), and a duplicate
                        // key throws at composition. The index keeps the key
                        // unique while leaving every non-repeated item's
                        // identity stable.
                        key = { index ->
                            val meta = lazyItems.peek(index)
                            if (meta != null) {
                                "${meta.type}:${meta.id}#$index"
                            } else {
                                "placeholder#$index"
                            }
                        }
                    ) { index ->
                        // Null while a placeholder stands in for a not-yet-loaded
                        // item; placeholders are off, so this is only the
                        // differ's brief window.
                        val meta = lazyItems[index] ?: return@items

                        val isWatched =
                            viewModel.watchedKey(meta.id, meta.type) in watchedKeys
                        val isPartiallyWatched =
                            viewModel.watchedKey(meta.id, meta.type) in partialWatchedKeys

                        CatalogTile(
                            meta = meta,
                            landscape = landscape,
                            isWatched = isWatched,
                            isPartiallyWatched = isPartiallyWatched,
                            onClick = { onItemClick(meta) },
                            onLongClick = { menuTarget = meta }
                        )
                    }
                }

                if (appendState is LoadState.Loading) {
                    item(key = "footer", span = { GridItemSpan(maxLineSpan) }) {
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
                    // A failed append leaves the loaded rows on screen, so the
                    // retry is a footer row rather than the full-page card.
                    item(key = "footer", span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 14.dp)
                                .clickable { lazyItems.retry() },
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "Couldn't load more — tap to retry",
                                style = MaterialTheme.typography.labelMedium,
                                color = KBTextLo,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // The same sheet every other poster surface raises, with the same
            // rows the TV grid's menu holds. Hiding a title removes its tile;
            // there is no focus to hand on afterwards.
            menuTarget?.let { target ->
                val isWatched =
                    viewModel.watchedKey(target.id, target.type) in watchedKeys
                // A grid item's id is "tmdb:123" on a TMDB-sourced catalog and
                // "tt12345" on an add-on one.
                val gridIds = LibraryIds.split(target.id)

                PosterContextMenu(
                    title = target.name,
                    subtitle = listOfNotNull(
                        target.yearOrNull?.toString(),
                        if (target.type.trim().lowercase() in setOf("tv", "series")) {
                            "Series"
                        } else {
                            "Movie"
                        }
                    ).joinToString(" · "),
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
                            label = watchedMenuLabel(
                                isWatched = isWatched,
                                mediaType = target.type
                            ),
                            description = watchedMenuDescription(
                                isWatched = isWatched,
                                mediaType = target.type
                            )
                        ) {
                            if (isWatched) {
                                viewModel.markUnwatched(target)
                            } else {
                                viewModel.markAsWatched(target)
                            }
                            menuTarget = null
                        }
                    ),
                    onDismiss = { menuTarget = null }
                )
            }
        }
    }
}

/**
 * One catalog poster: the touch card in the shape the rail was drawn in, with
 * the caption block's title, year and rating under it.
 */
@Composable
private fun CatalogTile(
    meta: MetaPreview,
    landscape: Boolean,
    isWatched: Boolean,
    isPartiallyWatched: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // The shared touch tile: it takes the grid cell's width and sets the
        // height, so a landscape cell is a real 16:9 card rather than a poster
        // box with a backdrop cropped into it - and it resolves the same
        // backdrop + clearlogo the rails do, which is why the corner mark is
        // there at all. [landscape] comes from the shape the rail was drawn in,
        // so the grid cannot disagree with the row it was opened from.
        TouchGlobalPosterTile(
            posterUrl = meta.poster,
            contentDescription = meta.name,
            isWatched = isWatched,
            isPartiallyWatched = isPartiallyWatched,
            onClick = onClick,
            onLongClick = onLongClick,
            backdropUrl = meta.background,
            artId = meta.id,
            artType = meta.type,
            landscape = landscape,
            modifier = Modifier.fillMaxWidth()
        )

        // The shared caption block, so Settings' title/year/rating toggles
        // reach this grid like every other poster surface. The score is the
        // add-on's IMDb one where there is one, else the TMDB score the app's
        // own rails carry.
        TouchPosterCaptions(
            title = meta.name,
            year = meta.yearOrNull?.toString(),
            rating = meta.imdbRating?.toDoubleOrNull() ?: meta.tmdbRating,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/** A full-span status line, with an optional action under it. */
@Composable
private fun GridStatus(
    message: String,
    action: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = message,
            color = KBTextLo,
            style = MaterialTheme.typography.bodyMedium
        )
        if (action != null && onAction != null) {
            KBButton(label = action, onClick = onAction)
        }
    }
}
