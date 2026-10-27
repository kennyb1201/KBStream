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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.filter
import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.ui.browse.landFocusOn
import androidx.compose.animation.core.tween
import com.kennyb1201.kbstream.ui.components.GlobalPosterCard
import com.kennyb1201.kbstream.ui.components.KB_ART_FADE_MS
import com.kennyb1201.kbstream.ui.components.KBPageTitle
import com.kennyb1201.kbstream.ui.components.KBSkeletonGrid
import com.kennyb1201.kbstream.ui.components.PosterCaptions
import com.kennyb1201.kbstream.ui.components.landscapeTileHeight
import com.kennyb1201.kbstream.ui.components.landscapeTileWidth
import com.kennyb1201.kbstream.ui.components.posterEdgeShape
import com.kennyb1201.kbstream.ui.components.rememberHomeLandscape
import com.kennyb1201.kbstream.ui.components.rememberMotionMs
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
import com.kennyb1201.kbstream.ui.search.successorIndexAfter
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBScreenEdge
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map

/**
 * How long the open effect waits between attempts while the rail it needs is
 * still being built.
 */
private const val CATALOG_GRID_OPEN_POLL_MS = 100L

/**
 * How long the open effect keeps trying after the rail list stops changing
 * before it concludes the catalog is really gone.
 */
private const val CATALOG_GRID_OPEN_SETTLE_MS = 2_000L

/**
 * Hard ceiling on the open wait, so a build that never settles cannot strand the
 * viewer on a skeleton forever. Well past the measured worst case (a slow add-on
 * at evening peak answers in seconds, not tens of seconds).
 */
private const val CATALOG_GRID_OPEN_MAX_MS = 30_000L

/** Polls allowed while the restored tile is indexed by the re-collected pager. */
private const val CATALOG_GRID_RESTORE_STEPS = 20

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
    // The rail's real identity, carried by the route: two rails that share a
    // catalog name under one add-on cannot be told apart by the name pair, so
    // the second one's grid could open the first one's catalog (grid S2). Null
    // on a route restored from a build that predates these, which falls back to
    // the name pair.
    catalogId: String? = null,
    type: String? = null,
    viewModel: HomeViewModel =
        viewModel(),
    onItemClick: (MetaPreview) -> Unit,
    onBack: () -> Unit
) {
    val header by viewModel.catalogGridHeader.collectAsStateWithLifecycle()
    val paging by viewModel.catalogGridPaging.collectAsStateWithLifecycle()

    // The raw half of the hidden-everything guard below: whether the pager holds
    // any title at all, whatever the filter then does with them.
    val catalogGridServed by
        viewModel.catalogGridServedItems.collectAsStateWithLifecycle()

    // A tile press leaves for a title page - a DETOUR, not an exit (see the
    // dispose effect below). Recorded by both ways into Detail, so the grid
    // keeps its loaded pages and the viewer's place for the trip back.
    var leftForDetail by remember { mutableStateOf(false) }

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

    // One focus requester per tile, keyed the way the grid keys its cells, so
    // the menu can name a tile by key after the composition that drew it has
    // already been asked for another frame. Registered as the tiles compose
    // (see the cells below); the map is not pruned, because the entry for a
    // tile that just left is exactly what a removal has to read.
    val cardFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }

    // Which tile was long-pressed, and which one takes its place if the title
    // leaves the grid (Hide): the successor is named while the pressed tile is
    // still composed, the same rule the Library grid and the search rail use.
    var menuCardKey by remember { mutableStateOf<String?>(null) }
    var menuSuccessorKey by remember { mutableStateOf<String?>(null) }
    var restoreFocusSignal by remember { mutableStateOf(0) }

    // Closing the menu owes the D-pad a landing on the tile that was pressed.
    //
    // This grid's menu was the one title menu in the app that never handed
    // focus back: it set its target to null and stopped, so the focused menu
    // row was disposed with the overlay and nothing asked for the card behind
    // it. From the sofa that is the reported "after adding to My List the D-pad
    // jumps to some other poster": the next press starts a focus search from a
    // dead node, and the framework lands on whatever it finds first - a random
    // tile - instead of the one the viewer was on. Every other poster menu
    // restores; this one now does too, through the app's one verified-landing
    // rule (see [landFocusOn]).
    fun closeMenu(restoreKey: String?) {
        menuTarget = null
        menuCardKey = restoreKey
        restoreFocusSignal++
    }

    LaunchedEffect(restoreFocusSignal) {
        if (restoreFocusSignal == 0) return@LaunchedEffect
        val key = menuCardKey
        menuCardKey = null
        // A null key means the pressed tile is the one leaving the grid (Hide)
        // and its successor has already been handed the focus by
        // `onBeforeHide`, which had to happen while the dying tile was still
        // attached. Asking a requester with no node would fight that hand-off.
        if (key != null) {
            landFocusOn(cardFocusRequesters[key])
        }
    }

    // Fresh screen instance (or restored route): (re)open the catalog from
    // its identity. No-op when this grid is already showing that rail — which
    // is what preserves the loaded pages, and the viewer's place in them,
    // across a trip to a Detail page.
    //
    // The pop-back-to-Home decision lives HERE, not in the null-branch
    // below: a fresh open always composes one frame with an unseeded grid
    // (the ViewModel state starts null), and an onBack launched from that
    // frame still fires after the open effect has successfully seeded the
    // grid — the grid flashed for one frame and dumped the user back on
    // Home with focus dropped on Continue Watching.
    LaunchedEffect(title, addonName, catalogId, type) {
        // The wait is on the RAILS, not on a clock. On a cold process restore
        // (or after a profile switch) the rails are still being built, and the
        // fixed five seconds this used to allow was shorter than a slow add-on
        // can take — so a grid whose catalog does exist backed out to Home
        // anyway (grid S1). The loop now keeps trying while a build is running
        // or while the rail list is still landing, and only gives up once the
        // list has been still for CATALOG_GRID_OPEN_SETTLE_MS without this
        // catalog in it — or at the CATALOG_GRID_OPEN_MAX_MS ceiling, so a
        // build that never settles cannot strand the viewer on a skeleton.
        var elapsedMs = 0L
        var settledMs = 0L
        var lastRailCount = -1
        while (
            elapsedMs < CATALOG_GRID_OPEN_MAX_MS &&
            settledMs < CATALOG_GRID_OPEN_SETTLE_MS
        ) {
            viewModel.openCatalogInGrid(title, addonName, catalogId, type)
            if (viewModel.catalogGridHeader.value != null) {
                return@LaunchedEffect
            }
            delay(CATALOG_GRID_OPEN_POLL_MS)
            elapsedMs += CATALOG_GRID_OPEN_POLL_MS

            val railCount = viewModel.rails.value.size
            if (viewModel.isLoading.value || railCount != lastRailCount) {
                // Still building, or rails are arriving while we watch: this
                // catalog may yet appear, so the grace clock restarts.
                settledMs = 0L
            } else {
                settledMs += CATALOG_GRID_OPEN_POLL_MS
            }
            lastRailCount = railCount
        }
        // The rails settled without this catalog: it is really gone from this
        // profile's Home.
        onBack()
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

    // HOISTED above the loading branch, not declared beside the grid. A lazy
    // list state that is created with the grid is created on the first frame
    // that has content, and it is disposed again on any frame that does not -
    // so the un-seeded frame this screen opens with (and any transient one
    // after it) dropped the scroll position with the state that carried it, and
    // the grid came back at the top. Created here it belongs to the screen for
    // as long as the screen is composed, which is what the grid's own scroll is
    // supposed to survive.
    //
    // Keyed by the catalog, the way the phone's grid has been: unkeyed, an
    // open catalog replaced in the same composition slot restored the previous
    // one's offset instead of starting at the top (grid I1 - mobile fixed this
    // exact failure and this is the TV half of it).
    val gridState = key(title, addonName) { rememberLazyGridState() }

    // Leaving the grid: a trip to a title page keeps its pager and its place,
    // any other way out drops them.
    //
    // Keyed on Unit on purpose: what this effect answers is "is this screen
    // leaving", and a route-keyed effect would also run for the grid being
    // REPLACED in the same composition slot - where the close belongs to the
    // outgoing grid and the open belongs to the incoming one, not the other way
    // round. A fresh open already resets the state it needs to (see
    // openCatalogInGrid).
    DisposableEffect(Unit) {
        onDispose {
            if (leftForDetail) {
                // Detail is a detour. The pager and its pages stay alive (the
                // re-open above is a no-op for the same rail), so all this
                // owes the return is the tile the viewer was on.
                viewModel.rememberCatalogGridScroll(gridState.firstVisibleItemIndex)
            } else {
                // Any other departure is final: the shared state goes with it,
                // so a grid opened later starts fresh instead of resuming a
                // catalog the viewer has left.
                viewModel.closeCatalogGrid()
            }
        }
    }

    // Back from a title page: put the viewer back on the tile they pressed.
    // Only the index survives the trip (the composition that held the scroll
    // state was disposed), so it is applied once the pager has indexed those
    // pages again - which, with them cached, is the first pass in practice.
    var returnRestored by remember { mutableStateOf(false) }
    var scrollSettled by remember { mutableStateOf(false) }
    LaunchedEffect(title, addonName, lazyItems) {
        if (returnRestored) return@LaunchedEffect
        returnRestored = true
        val restored = viewModel.catalogGridReturnIndex()
        val restoredItems = lazyItems
        if (restored > 0 && restoredItems != null) {
            var waits = 0
            while (
                restoredItems.itemCount <= restored &&
                waits < CATALOG_GRID_RESTORE_STEPS
            ) {
                delay(CATALOG_GRID_OPEN_POLL_MS)
                waits++
            }
            if (restoredItems.itemCount > restored) {
                gridState.scrollToItem(restored)
            }
        }
        // The entry anchor below waits on this: it aims at the tile at the top
        // of the VIEWPORT, so it must not run before the viewport has been put
        // back where the viewer left it.
        scrollSettled = true
    }

    // The D-pad's entry anchor.
    //
    // This screen's only focusable nodes are its tiles, and nothing asked for
    // one on entry, so the first press began a focus search from whatever node
    // happened to hold focus - which, on a screen that just replaced another, is
    // not guaranteed to be a tile (grid S3). Aimed at the tile at the top of the
    // VIEWPORT rather than at tile 0, because a return from a title page
    // restores a deep scroll position and the tile the viewer is looking at is
    // the one that should take the focus. One-shot, and released the moment
    // focus lands anywhere inside the grid (see the root below), so a page
    // arriving later cannot pull the viewer off the tile they moved to.
    var entryFocusPlaced by remember { mutableStateOf(false) }
    LaunchedEffect(openHeader, lazyItems?.itemCount, scrollSettled) {
        if (entryFocusPlaced || !scrollSettled) return@LaunchedEffect
        val items = lazyItems ?: return@LaunchedEffect
        if (items.itemCount == 0) return@LaunchedEffect
        // A frame first, so the landing below reads the position the restore
        // effect above asked for rather than the one it replaced.
        awaitFrame()
        val topKey =
            items.peek(gridState.firstVisibleItemIndex)
                ?.let { meta -> "${meta.type}:${meta.id}" }
                ?: return@LaunchedEffect
        entryFocusPlaced = landFocusOn(cardFocusRequesters[topKey])
    }

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

    val refreshState = lazyItems.loadState.refresh
    val appendState = lazyItems.loadState.append

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid)
            // Focus landing anywhere inside the grid retires the entry grab
            // above: the viewer's own move always wins.
            .onFocusChanged { if (it.hasFocus) entryFocusPlaced = true }
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

            // Every title this catalog served is hidden, so there is nothing
            // to draw - but the branch below would say the catalog HAS nothing,
            // which is a different fact, and it leaves the viewer with no way
            // back from here (the same gap Home's all-hidden card and the
            // browse pages close). Both halves are required: the served half is
            // true on every populated catalog, and the filtered half is true
            // whenever the hidden filter removed what was there.
            lazyItems.itemCount == 0 && catalogGridServed -> {
                val context = LocalContext.current
                KBStatusMessage(
                    icon = KB_STATUS_ICON_EMPTY,
                    message = "Every title here is hidden.",
                    onRetry = { HiddenTitles.unhideAll(context) },
                    actionLabel = "UNHIDE ALL"
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
                    // The Library grid's own rhythm (12/16), not the 10/14
                    // this was drawn with: the two grids hold the same posters
                    // in the same shape, so a row that sits 2dp tighter here
                    // than there reads as a different design scale rather than
                    // a different screen.
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        count = lazyItems.itemCount,
                        // Same guard as the mobile grid: a catalog whose pages
                        // repeat a title would produce duplicate keys, and a
                        // keyed LazyGrid throws on those. The index makes the
                        // key unique; non-repeated items keep stable identity.
                        key = { index ->
                            val meta = lazyItems.peek(index)
                            if (meta != null) {
                                "${meta.type}:${meta.id}#$index"
                            } else {
                                "placeholder#$index"
                            }
                        }
                    ) { index ->
                        // Null while a placeholder stands in for a not-yet-
                        // loaded item; placeholders are off, so this is only
                        // the differ's brief window between a page arriving
                        // and its items being indexed.
                        val meta = lazyItems[index] ?: return@items

                        // The tile's own focus drives the caption marquee below,
                        // like every other poster surface in the app.
                        var focused by remember { mutableStateOf(false) }

                        // This tile's own node, published for the menu's
                        // hand-back by the same key the pager keys the cell
                        // with - the card survives a reorder, so the viewer
                        // lands back on the poster they pressed rather than on
                        // whatever took its index.
                        val cardKey = "${meta.type}:${meta.id}"
                        val cardRequester = remember(cardKey) { FocusRequester() }
                        cardFocusRequesters[cardKey] = cardRequester

                        Column(
                            modifier = Modifier
                                .width(tileWidth)
                                // The tile fades in as its page lands, and
                                // nothing else: a paging grid re-flows enough
                                // on its own without every arriving cell also
                                // sliding to a new slot.
                                .animateItem(
                                    fadeInSpec = tween(rememberMotionMs(KB_ART_FADE_MS))
                                )
                                .onFocusChanged { focused = it.hasFocus }
                        ) {
                            GlobalPosterCard(
                                posterUrl = meta.poster,
                                backdropUrl = meta.background,
                                contentDescription = meta.name,
                                isWatched =
                                    viewModel.watchedKey(meta.id, meta.type) in
                                        watchedKeys,
                                isPartiallyWatched =
                                    viewModel.watchedKey(meta.id, meta.type) in
                                        partialWatchedKeys,
                                onClick = {
                                    // A title page is a DETOUR, not an exit:
                                    // recorded for the dispose effect below,
                                    // which keeps the pager and the viewer's
                                    // place for the trip back.
                                    leftForDetail = true
                                    onItemClick(meta)
                                },
                                onLongClick = {
                                    menuCardKey = cardKey
                                    // The tile that takes this one's place if
                                    // Hide is the row pressed, resolved from the
                                    // pager BEFORE the title is filtered out.
                                    menuSuccessorKey = successorIndexAfter(
                                        lazyItems.itemCount,
                                        index
                                    )?.let { at ->
                                        lazyItems.peek(at)?.let { "${it.type}:${it.id}" }
                                    }
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
                                    // The tile's own landing target for its
                                    // menu's hand-back (see closeMenu).
                                    .focusRequester(cardRequester)
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
                                // The add-on's IMDb score where there is one,
                                // else the TMDB score the app's own rails carry
                                // (see MetaPreview.tmdbRating): a built-in rail
                                // has no add-on preview behind it, so this is
                                // the only number its tiles have.
                                rating = meta.imdbRating?.toDoubleOrNull()
                                    ?: meta.tmdbRating,
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
        // The key this menu's own tile registered under, for the rows that
        // dismiss in place (a watched toggle, Back, the scrim).
        val targetKey = "${target.type}:${target.id}"

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
            // Hide takes this tile out of the grid, so the tile that takes its
            // place is named and focused FIRST, while this one is still
            // composed and the menu is still in the tree - the same hand-off
            // the Library grid and the search rail do. The dismiss that
            // follows must then not ask for the dying tile, which is why the
            // key is cleared here.
            onBeforeHide = {
                menuSuccessorKey?.let { key ->
                    cardFocusRequesters[key]?.requestFocus()
                }
                menuCardKey = null
            },
            actions = listOf(
                PosterContextAction(
                    label = "Go to Details",
                    description = "Open this title's detail page"
                ) {
                    menuTarget = null
                    // Same detour the tile press records (see the dispose
                    // effect): this is the other way into Detail.
                    leftForDetail = true
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
                    if (isWatched) {
                        viewModel.markUnwatched(target)
                    } else {
                        viewModel.markAsWatched(target)
                    }
                    closeMenu(targetKey)
                }
            ),
            onDismiss = {
                closeMenu(menuCardKey)
            }
        )
    }
}
