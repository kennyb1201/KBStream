package com.kennyb1201.kbstream.ui.mobile

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kennyb1201.kbstream.data.library.HiddenTitles
import com.kennyb1201.kbstream.data.library.LibraryItem
import com.kennyb1201.kbstream.data.library.LibraryList
import com.kennyb1201.kbstream.data.library.LibrarySource
import com.kennyb1201.kbstream.data.library.LocalLibraryStore
import com.kennyb1201.kbstream.data.simkl.simklItemUrl
import com.kennyb1201.kbstream.ui.browse.BrowseReturnFocus
import com.kennyb1201.kbstream.ui.browse.BrowseReturnTarget
import com.kennyb1201.kbstream.ui.components.LibraryAddTarget
import com.kennyb1201.kbstream.ui.components.PosterContextAction
import com.kennyb1201.kbstream.ui.components.heroSourceOnPress
import com.kennyb1201.kbstream.ui.components.hideTarget
import com.kennyb1201.kbstream.ui.components.landscapeTileWidth
import com.kennyb1201.kbstream.ui.components.rememberGlobalLandscape
import com.kennyb1201.kbstream.ui.components.rememberHiddenTitleKeys
import com.kennyb1201.kbstream.ui.components.touch.KBButton
import com.kennyb1201.kbstream.ui.components.touch.KBDialogPanel
import com.kennyb1201.kbstream.ui.components.touch.KBTextField
import com.kennyb1201.kbstream.ui.components.touch.TouchGlobalPosterTile
import com.kennyb1201.kbstream.ui.components.touch.TouchPosterCaptions
import com.kennyb1201.kbstream.ui.components.touch.rememberTouchPosterSize
import com.kennyb1201.kbstream.ui.components.touch.PosterContextMenu
import com.kennyb1201.kbstream.ui.components.touch.TouchChoiceChip
import com.kennyb1201.kbstream.ui.library.LIBRARY_BROWSE_SCREEN_KEY
import com.kennyb1201.kbstream.ui.library.LibraryCalendarEntry
import com.kennyb1201.kbstream.ui.library.LibraryCalendarPane
import com.kennyb1201.kbstream.ui.library.LibraryFilter
import com.kennyb1201.kbstream.ui.library.LibrarySort
import com.kennyb1201.kbstream.ui.library.LibraryViewModel
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import com.kennyb1201.kbstream.ui.theme.MobileTheme
import kotlinx.coroutines.delay

/**
 * Library, for a finger.
 *
 * The TV page is one strip of chips over a poster grid, with a side rail for
 * personal lists. The phone keeps every piece of that - the same
 * activity-scoped [LibraryViewModel], the same hidden-title filter, the same
 * four filters, the UNWATCHED toggle, the four sort fields with their
 * direction, the same per-source menus and the same delete confirmation - and
 * lays it out as one vertical grid whose header, chip strips and list rail
 * span the full width.
 *
 * The grid is the ROOT scrollable on purpose: a `LazyVerticalGrid` with
 * full-span header items is the touch-native shape here, whereas a column with
 * a nested grid would give the page two scrollbars.
 *
 * What is ported, not just listed:
 *
 *  - **return-to-poster**: a tap records the same [BrowseReturnTarget] the TV
 *    grid records, so backing out of a Detail page reopens the library on the
 *    poster that was pressed (scrolled to it, without a D-pad to focus);
 *  - **personal lists**: the rail becomes a vertical list of lists with their
 *    counts and source, a selected row highlights, local rows carry the
 *    long-press delete (confirmed by a dialog, never on the press itself), and
 *    "+ NEW LIST" opens an inline name field;
 *  - **the menu**: Go to Details, watched-independent hide, Add to list, and
 *    the per-source removal row - from this list, from the library, or the
 *    explanatory "Managed on Simkl" row with its attribution link;
 *  - **a row with no ids** says so instead of swallowing the tap: a tracker
 *    entry can carry only a title and a poster, and those have no detail page.
 *
 * The TV's focus plumbing (per-card requesters, successor keys, the return
 * focus loop) has no phone twin: hiding or removing a card just removes it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreenMobile(
    onItemClick: (mediaType: String, id: String) -> Unit,
    viewModel: LibraryViewModel = viewModel()
) {
    MobileTheme {
        val context = LocalContext.current
        // This phone's downloads, so a tile can say the title is here offline
        // (spec §4.4: the badge appears where the surface renders badges, and a
        // library tile already renders the watched marker, so it is one of
        // them). Held as state rather than read per tile from the store, so a
        // download that finishes while the grid is up redraws it.
        val downloads = rememberDownloadRecords()

        val stateRaw by viewModel.uiState.collectAsStateWithLifecycle()
        // A hidden title leaves My List, the merged All list and every personal
        // list at once - the TV's filter, applied to the same four lists.
        val hiddenTitleKeys = rememberHiddenTitleKeys()
        fun hiddenItem(item: LibraryItem): Boolean =
            HiddenTitles.hides(
                hiddenTitleKeys,
                item.mediaType,
                item.title,
                item.year,
                item.imdbId,
                item.tmdbId?.toString()
            )
        val state = remember(stateRaw, hiddenTitleKeys) {
            stateRaw.copy(
                localItems = stateRaw.localItems.filterNot(::hiddenItem),
                allItems = stateRaw.allItems.filterNot(::hiddenItem),
                watchlistItems = stateRaw.watchlistItems.filterNot(::hiddenItem),
                selectedListItems = stateRaw.selectedListItems.filterNot(::hiddenItem)
            )
        }

        // One grid per filter, exactly as on TV: switching filters is a new
        // list, and the old scroll offset means nothing in it. `key` gives each
        // filter its own slot in the saveable registry, so a ROTATION keeps the
        // position inside the filter on screen while a filter switch still
        // starts at the top.
        val gridState = key(state.filter) { rememberLazyGridState() }
        var returnTarget by remember { mutableStateOf<BrowseReturnTarget?>(null) }

        var menuItem by remember { mutableStateOf<LibraryItem?>(null) }
        var listPendingDelete by remember { mutableStateOf<LibraryList?>(null) }
        var showCreateField by remember { mutableStateOf(false) }
        var newListName by remember { mutableStateOf("") }
        var notice by remember { mutableStateOf<String?>(null) }

        // A row with no ids has no detail page; say so instead of a dead tap.
        LaunchedEffect(notice) {
            if (notice != null) {
                delay(2500)
                notice = null
            }
        }

        LaunchedEffect(Unit) {
            // The Home Upcoming header's shortcut (see LibraryCalendarEntry):
            // arriving on the Calendar pane rather than on a poster grid.
            if (LibraryCalendarEntry.consume()) {
                viewModel.setFilter(LibraryFilter.CALENDAR)
                return@LaunchedEffect
            }

            // Re-read the list on every visit: the ViewModel is activity-scoped,
            // and without this a title added from a Detail page (or removed
            // here) never appeared.
            viewModel.refresh()
            // Then read back where this page's Detail was opened from, if it
            // was opened from here.
            returnTarget = BrowseReturnFocus.consume(LIBRARY_BROWSE_SCREEN_KEY)
        }

        val items = remember(state) {
            when (state.filter) {
                LibraryFilter.ALL -> state.allItems
                LibraryFilter.MY_LIST -> state.localItems
                LibraryFilter.WATCHLIST -> state.watchlistItems
                LibraryFilter.LISTS -> state.selectedListItems
                // The calendar draws itself in one full-span cell below; it
                // has no poster rows to feed this grid.
                LibraryFilter.CALENDAR -> emptyList()
            }
        }

        // Puts the grid back on the poster the viewer pressed. The rows can
        // land after this grid composes (the refresh is in flight), so the
        // record is held until the item is in the list.
        // Where the POSTERS start in the grid. scrollToItem takes an item
        // index, and this grid puts full-span items in front of them (header,
        // filter row, sort row, and the lists row under FILTER=LISTS), so the
        // old items-index target landed that many cells BEFORE the tapped
        // poster - typically a row early. ActorScreenMobile does the same
        // accounting explicitly.
        val posterOffset = 3 + if (state.filter == LibraryFilter.LISTS) 1 else 0

        LaunchedEffect(returnTarget, items, posterOffset) {
            val target = returnTarget ?: return@LaunchedEffect
            val index = items.indexOfFirst {
                LocalLibraryStore.dedupeKey(it) == target.itemId
            }.takeIf { it >= 0 }
                ?: target.position.takeIf { it in items.indices }
                ?: return@LaunchedEffect
            runCatching { gridState.scrollToItem(posterOffset + index) }
            returnTarget = null
        }

        fun openItem(item: LibraryItem, index: Int) {
            val navigationId = item.navigationId
            if (navigationId == null) {
                notice = "No details page for \"${item.title}\""
                return
            }
            BrowseReturnFocus.record(
                BrowseReturnTarget(
                    screenKey = LIBRARY_BROWSE_SCREEN_KEY,
                    itemId = LocalLibraryStore.dedupeKey(item),
                    position = index
                )
            )
            onItemClick(item.mediaType, navigationId)
        }

        val emptyText = when (state.filter) {
            LibraryFilter.ALL -> if (state.loading) {
                "Loading…"
            } else {
                "Nothing here yet. Long-press any poster and choose " +
                    "\"Add to list…\" — My List, watchlists and personal lists " +
                    "all land in this merged view."
            }

            LibraryFilter.MY_LIST -> if (state.loading) {
                "Loading…"
            } else {
                "Your list is empty. Long-press any poster on a detail page " +
                    "and choose \"Add to Library\"."
            }

            LibraryFilter.WATCHLIST -> if (state.loading) {
                "Loading watchlists…"
            } else {
                val sources = buildList {
                    if (!state.simklConnected) add("Simkl not connected")
                    if (!state.mdbListConfigured) add("MDBList key not set")
                    if (!state.traktConnected) add("Trakt not connected")
                }
                if (sources.isEmpty()) {
                    "Watchlist is empty."
                } else {
                    "Watchlist is empty — " + sources.joinToString(" · ")
                }
            }

            LibraryFilter.LISTS -> when {
                state.loading && state.selectedList == null -> "Loading lists…"
                state.selectedList == null -> "Select a list above."
                else -> "This list is empty. Long-press any poster app-wide " +
                    "and choose \"Add to list…\" to fill it."
            }

            LibraryFilter.CALENDAR -> "The calendar loads itself."
        }

        // Landscape cards are wider than posters, so the columns widen with
        // them - the same 168dp the catalog grid uses.
        val landscape = rememberGlobalLandscape()
        val posterSize = rememberTouchPosterSize()
        val tileWidth =
            if (landscape) landscapeTileWidth(posterSize.width) else posterSize.width
        // Three poster columns in portrait, more when the phone is turned.
        val columns = rememberMobileGridColumns(
            preferredTileWidth = tileWidth,
            landscape = landscape
        )
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
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Text(
                                text = "LIBRARY",
                                color = KBAccent,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            // The source line is the widest thing in this
                            // header (four connected services is a long
                            // string). It takes the leftover space and ends
                            // there, instead of being measured at its full
                            // intrinsic width and pushing the LIBRARY title
                            // off the left edge of a 360dp portrait screen.
                            Text(
                                text = buildList {
                                    if (state.simklConnected) add("SIMKL")
                                    if (state.mdbListConfigured) add("MDBLIST")
                                    add("LOCAL")
                                    if (state.traktConnected) add("TRAKT")
                                }.joinToString(" + "),
                                style = MaterialTheme.typography.labelMedium,
                                color = KBTextLo,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.End,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        notice?.let { text ->
                            Text(
                                text = text,
                                color = KBTextLo,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                item(key = "filters", span = { GridItemSpan(maxLineSpan) }) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 2.dp)
                    ) {
                        items(LibraryFilter.entries, key = { "filter_${it.name}" }) { filter ->
                            TouchChoiceChip(
                                label = filter.label,
                                selected = state.filter == filter,
                                onClick = { viewModel.setFilter(filter) }
                            )
                        }
                        item(key = "unwatched") {
                            TouchChoiceChip(
                                label = if (state.hideWatched) {
                                    "WATCHED HIDDEN"
                                } else {
                                    "UNWATCHED"
                                },
                                selected = state.hideWatched,
                                onClick = { viewModel.toggleHideWatched() }
                            )
                        }
                    }
                }

                item(key = "sorts", span = { GridItemSpan(maxLineSpan) }) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 2.dp)
                    ) {
                        // Each sort chip is a TWO-state control: the first tap
                        // sorts ascending, the next flips it, and the selected
                        // chip carries the direction glyph.
                        items(LibrarySort.entries, key = { "sort_${it.name}" }) { sort ->
                            val selected = state.sort == sort
                            TouchChoiceChip(
                                label = if (selected) {
                                    "${sort.label} ${if (state.sortAscending) "▲" else "▼"}"
                                } else {
                                    sort.label
                                },
                                selected = selected,
                                onClick = { viewModel.setSort(sort) }
                            )
                        }
                    }
                }

                if (state.filter == LibraryFilter.CALENDAR) {
                    item(key = "calendar", span = { GridItemSpan(maxLineSpan) }) {
                        // The pane owns the rest of the viewport: its day list
                        // scrolls inside itself, and the grid only ever scrolls
                        // this one cell. `fillParentMaxHeight` is the grid's own
                        // measure of that viewport, so no device height is
                        // guessed at here.
                        // A bounded height is what the pane needs: its day
                        // list is the only scroller on the phone, so the cell
                        // holding it is sized off the window rather than left
                        // to the grid's own measurement.
                        val viewportHeight = LocalConfiguration.current.screenHeightDp.dp
                        LibraryCalendarPane(
                            onOpenDetails = { navigationId ->
                                onItemClick("series", navigationId)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(viewportHeight * 0.78f),
                            takeInitialFocus = false
                        )
                    }
                }

                if (state.filter == LibraryFilter.LISTS) {
                    item(key = "lists", span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.lists.forEach { list ->
                                ListRow(
                                    list = list,
                                    selected = state.selectedList?.id == list.id,
                                    onClick = { viewModel.selectList(list) },
                                    onLongClick = {
                                        // Only a list this device owns can be
                                        // deleted; an MDBList or Trakt list is
                                        // managed on its service.
                                        if (list.source.isLocalList) {
                                            listPendingDelete = list
                                        }
                                    }
                                )
                            }

                            if (showCreateField) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    KBTextField(
                                        value = newListName,
                                        onValueChange = { newListName = it },
                                        placeholder = "New list name",
                                        modifier = Modifier.fillMaxWidth(),
                                        onDone = {
                                            if (newListName.isNotBlank()) {
                                                viewModel.createLocalList(newListName)
                                            }
                                            newListName = ""
                                            showCreateField = false
                                        }
                                    )
                                    KBButton(
                                        label = "CREATE",
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        if (newListName.isNotBlank()) {
                                            viewModel.createLocalList(newListName)
                                        }
                                        newListName = ""
                                        showCreateField = false
                                    }
                                }
                            } else {
                                KBButton(
                                    label = "+ NEW LIST",
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    showCreateField = true
                                }
                            }
                        }
                    }
                }

                if (state.filter != LibraryFilter.CALENDAR && items.isEmpty()) {
                    item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = emptyText,
                            color = KBTextLo,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 10.dp)
                        )
                    }
                } else if (state.filter != LibraryFilter.CALENDAR) {
                    itemsIndexed(
                        items = items,
                        key = { _, item -> LocalLibraryStore.dedupeKey(item) },
                        // A library tile's shape follows its media type (a film
                        // and a series draw different captions), so the two are
                        // never asked to reuse each other's slot.
                        contentType = { _, item -> item.mediaType }
                    ) { index, item ->
                        LibraryTile(
                            item = item,
                            // The show's whole title, not one episode: a library
                            // row is the title, so any downloaded episode of it
                            // marks the row.
                            isDownloaded = downloadsHaveAny(
                                downloads,
                                item.watchedKeyCandidates() +
                                    listOfNotNull(item.navigationId)
                            ),
                            // Removals and filter/sort changes stop teleporting
                            // the grid: a tile fading out and its neighbours
                            // closing the gap is the phone's whole point here.
                            modifier = Modifier
                                .animateItem()
                                // The phone's poster -> Detail flight: a press is
                                // the source signal (a touch has no focus), the
                                // same hand-off Home's cards make.
                                .heroSourceOnPress(
                                    item.mediaType,
                                    item.navigationId.orEmpty()
                                ),
                            rating = state.ratings[LocalLibraryStore.dedupeKey(item)],
                            isWatched = item.watchedKeyCandidates()
                                .any { it in state.watchedKeys },
                            isPartiallyWatched = item.watchedKeyCandidates()
                                .any { it in state.partiallyWatchedKeys },
                            onClick = { openItem(item, index) },
                            onLongClick = { menuItem = item }
                        )
                    }
                }
            }

            // The row menu: the same rows the TV menu holds, over the touch
            // sheet.
            menuItem?.let { item ->
                val subtitle = listOfNotNull(
                    item.year?.toString(),
                    item.source.label
                ).joinToString(" · ")
                PosterContextMenu(
                    title = item.title,
                    subtitle = subtitle,
                    libraryTarget = LibraryAddTarget(
                        mediaType = item.mediaType,
                        imdbId = item.imdbId,
                        tmdbId = item.tmdbId,
                        title = item.title,
                        year = item.year,
                        posterUrl = item.posterUrl
                    ),
                    hideTarget = hideTarget(
                        item.title,
                        item.mediaType,
                        item.posterUrl,
                        listOf(item.imdbId, item.tmdbId?.toString()),
                        year = item.year
                    ),
                    actions = buildList {
                        item.navigationId?.let { navigationId ->
                            add(
                                PosterContextAction(
                                    label = "Go to Details",
                                    description = "Open this title's detail page"
                                ) {
                                    val selected = item
                                    menuItem = null
                                    BrowseReturnFocus.record(
                                        BrowseReturnTarget(
                                            screenKey = LIBRARY_BROWSE_SCREEN_KEY,
                                            itemId = LocalLibraryStore.dedupeKey(selected),
                                            position = items.indexOf(selected)
                                        )
                                    )
                                    onItemClick(selected.mediaType, navigationId)
                                }
                            )
                        }

                        when (item.source) {
                            LibrarySource.LOCAL,
                            LibrarySource.MDBLIST_WATCHLIST,
                            LibrarySource.TRAKT_WATCHLIST -> add(
                                PosterContextAction(
                                    label = "Remove from Library",
                                    description =
                                        "Remove it here and on every connected tracker",
                                    isDestructive = true
                                ) {
                                    val selected = item
                                    menuItem = null
                                    viewModel.removeItem(selected)
                                }
                            )

                            LibrarySource.LOCAL_LIST,
                            LibrarySource.MDBLIST_LIST,
                            LibrarySource.TRAKT_LIST -> add(
                                PosterContextAction(
                                    label = "Remove from this list",
                                    description = item.listName?.let {
                                        "Take it out of \"$it\""
                                    },
                                    isDestructive = true
                                ) {
                                    val selected = item
                                    menuItem = null
                                    viewModel.removeItem(selected)
                                }
                            )

                            LibrarySource.SIMKL_WATCHLIST,
                            LibrarySource.SIMKL_LIST -> {
                                // Simkl's attribution rule: name Simkl and link
                                // back to the page for THAT item.
                                simklItemUrl(
                                    item.mediaType,
                                    item.simklId,
                                    item.simklSlug
                                )?.let { url ->
                                    add(
                                        PosterContextAction(
                                            label = "View on Simkl",
                                            description =
                                                "Open this title's page on simkl.com"
                                        ) {
                                            try {
                                                context.startActivity(
                                                    Intent(
                                                        Intent.ACTION_VIEW,
                                                        Uri.parse(url)
                                                    )
                                                )
                                            } catch (_: ActivityNotFoundException) {
                                                // No browser on this device: the
                                                // row is a link out, so there is
                                                // nothing else to do.
                                            }
                                            menuItem = null
                                        }
                                    )
                                }
                                add(
                                    PosterContextAction(
                                        label = "Managed on Simkl",
                                        description =
                                            "Remove it from your watchlist on simkl.com"
                                    ) {
                                        menuItem = null
                                    }
                                )
                            }
                        }
                    },
                    onDismiss = { menuItem = null }
                )
            }

            // Deleting a list is confirmed, never done by the press itself.
            listPendingDelete?.let { list ->
                Dialog(onDismissRequest = { listPendingDelete = null }) {
                    KBDialogPanel(
                        title = "Delete list?",
                        subtitle = list.name
                    ) {
                        Text(
                            text = "The list is removed from this device. Its " +
                                "${list.itemCount} titles stay in your Library.",
                            color = KBTextLo,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            KBButton(
                                label = "CANCEL",
                                modifier = Modifier.weight(1f)
                            ) {
                                listPendingDelete = null
                            }
                            KBButton(
                                label = "DELETE",
                                danger = true,
                                modifier = Modifier.weight(1f)
                            ) {
                                listPendingDelete = null
                                viewModel.deleteLocalList(list.id)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One row of the personal-lists rail: name, count and where it lives. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListRow(
    list: LibraryList,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?
) {
    val sourceLabel = list.locationLabel
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(KBShapeCard)
            .background(if (selected) KBAccent.copy(alpha = 0.22f) else KBSurface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = list.name,
            color = if (selected) KBAccent else KBTextHi,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "${list.itemCount} titles · $sourceLabel",
            color = KBTextLo,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
    }
}

/**
 * One library poster: art, the shared caption block's fields (title, year and
 * rating, all gated by the Settings toggles elsewhere), and the source tag
 * that says which tracker the row lives on.
 */
@Composable
private fun LibraryTile(
    item: LibraryItem,
    rating: Double?,
    isWatched: Boolean,
    isPartiallyWatched: Boolean,
    /** True when a completed download of this title is on this phone (spec §4.4). */
    isDownloaded: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // A library row is a title tile, so it follows Settings' global
        // landscape switch like every other catalog surface.
        TouchGlobalPosterTile(
            posterUrl = item.posterUrl,
            contentDescription = item.title,
            isWatched = isWatched,
            isPartiallyWatched = isPartiallyWatched,
            onClick = onClick,
            onLongClick = onLongClick,
            artId = item.tmdbId?.toString() ?: item.imdbId,
            artType = item.mediaType,
            modifier = Modifier.fillMaxWidth()
        )
        // Shared caption block: title/year/rating all follow Settings' three
        // poster-caption switches. The source line below is not one of those -
        // it says where the ENTRY came from, not what the title is.
        TouchPosterCaptions(
            title = item.title,
            year = item.year?.toString(),
            rating = rating
        )
        // The badge sits with the captions rather than over the artwork: the
        // shared poster tile has no slot for it, and a download is a fact about
        // the title, not a mark on its poster.
        if (isDownloaded) {
            DownloadedBadge()
        }
        Text(
            text = item.source.label,
            color = KBTextLo,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
