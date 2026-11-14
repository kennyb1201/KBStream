package com.kennyb1201.kbstream.ui.mobile

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kennyb1201.kbstream.data.library.HiddenTitles
import com.kennyb1201.kbstream.data.library.LibraryItem
import com.kennyb1201.kbstream.data.library.LibraryList
import com.kennyb1201.kbstream.data.library.LibrarySource
import com.kennyb1201.kbstream.data.library.ListControlWording
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
import com.kennyb1201.kbstream.ui.components.rememberKBFeedback
import com.kennyb1201.kbstream.ui.components.touch.KBButton
import com.kennyb1201.kbstream.ui.components.touch.KBDialogPanel
import com.kennyb1201.kbstream.ui.components.touch.KBTextField
import com.kennyb1201.kbstream.ui.components.touch.TouchGlobalPosterTile
import com.kennyb1201.kbstream.ui.components.touch.TouchPosterCaptions
import com.kennyb1201.kbstream.ui.components.touch.rememberTouchPosterSize
import com.kennyb1201.kbstream.ui.components.touch.PosterContextMenu
import com.kennyb1201.kbstream.ui.components.touch.TouchChoiceChip
import com.kennyb1201.kbstream.ui.library.LIBRARY_BROWSE_SCREEN_KEY
import com.kennyb1201.kbstream.data.library.LibraryHomeRails
import com.kennyb1201.kbstream.data.library.controlSummary
import com.kennyb1201.kbstream.data.library.libraryHomeRailIdSpellings
import com.kennyb1201.kbstream.data.library.libraryHomeRailPinFor
import com.kennyb1201.kbstream.data.library.pinsNamingList
import com.kennyb1201.kbstream.data.library.removeActionLabel
import com.kennyb1201.kbstream.data.library.removeConfirmAction
import com.kennyb1201.kbstream.data.library.removeConfirmBody
import com.kennyb1201.kbstream.data.library.removeConfirmTitle
import com.kennyb1201.kbstream.data.library.renameUnavailableNote
import com.kennyb1201.kbstream.data.library.sameListAs
import com.kennyb1201.kbstream.ui.library.LibraryCalendarEntry
import com.kennyb1201.kbstream.ui.library.LibraryCalendarPane
import com.kennyb1201.kbstream.ui.library.LibraryFilter
import com.kennyb1201.kbstream.ui.library.LibrarySort
import com.kennyb1201.kbstream.ui.library.LibraryViewModel
import com.kennyb1201.kbstream.ui.library.libraryWatchlistEmptyText
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
 *    counts and source, and a selected row highlights. A long-press opens the
 *    row's actions - rename where the source allows it, SHOW ON HOME / HIDE
 *    FROM HOME for any list, and a confirmed delete for a list this device
 *    owns - and "+ NEW LIST" opens an inline name field;
 *  - **the menu**: Go to Details, watched-independent hide, Add to list, and
 *    the per-source removal row - from this list, from the library, or from a
 *    Simkl watchlist (the TV's real removal; a Simkl LIST entry has no removal
 *    API, so it keeps only the attribution link);
 *  - **a row with no ids** says so instead of swallowing the tap: a tracker
 *    entry can carry only a title and a poster, and those have no detail page.
 *
 * The TV's focus plumbing (per-card requesters, successor keys, the return
 * focus loop) has no phone twin: hiding or removing a card just removes it.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun LibraryScreenMobile(
    onItemClick: (mediaType: String, id: String) -> Unit,
    /** Opens the year in review: the Library's year-round door beside the shelves. */
    onOpenYearReview: () -> Unit = {},
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
        var listMenu by remember { mutableStateOf<LibraryList?>(null) }
        var listPendingDelete by remember { mutableStateOf<LibraryList?>(null) }
        var listPendingRename by remember { mutableStateOf<LibraryList?>(null) }
        var renameListDraft by remember { mutableStateOf("") }
        // Bumped when a pin changes, so the menu's SHOW ON HOME / REMOVE FROM
        // HOME row re-reads the store instead of drawing the state it had when
        // it opened.
        var homeRailRevision by remember { mutableIntStateOf(0) }
        var showCreateField by remember { mutableStateOf(false) }
        var newListName by remember { mutableStateOf("") }
        // Where the field's CREATE goes, the same choice the TV rail offers.
        // Local is the default because it is the one source that always exists,
        // so a viewer with no account connected sees the field they always had.
        var createSource by remember { mutableStateOf(LibrarySource.LOCAL_LIST) }
        var notice by remember { mutableStateOf<String?>(null) }

        // A list write that failed, said out loud through the shell's one
        // transient message - the same channel the TV rail reads
        // (`LibraryScreen`'s listFeedback). A create, rename or delete is a
        // remote request behind a field or dialog that has already closed, so
        // without this the refusal is indistinguishable from success.
        val listFeedback = rememberKBFeedback()
        LaunchedEffect(state.listActionMessage) {
            val message = state.listActionMessage ?: return@LaunchedEffect
            listFeedback.show(message)
            viewModel.clearListActionMessage()
        }

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

            // The TV pane's own sentence, not a second copy of it: waiting,
            // unreachable, not connected and genuinely empty are four different
            // answers, and the phone used to collapse all four into one
            // finished-looking empty sentence (see
            // [libraryWatchlistEmptyText]).
            LibraryFilter.WATCHLIST -> libraryWatchlistEmptyText(
                loading = state.loading,
                remoteLoading = state.remoteLoading,
                simklConnected = state.simklConnected,
                mdbListConfigured = state.mdbListConfigured,
                traktConnected = state.traktConnected,
                failed = state.failedRemotes
            )

            LibraryFilter.LISTS -> when {
                state.loading && state.selectedList == null -> "Loading lists…"
                // m5: the phone's half of the TV label - an empty rail made
                // "Select a list above." a pointer to nothing with no way out.
                state.selectedList == null && state.lists.isEmpty() ->
                    "No lists yet. Create one with + NEW LIST above."
                state.selectedList == null -> "Select a list above."
                // F2: BEFORE the empty sentence - a switch clears the rows
                // first, so the phone drew "This list is empty" over a list
                // whose rows were still arriving.
                state.listLoading -> "Loading list…"
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
                        Text(
                            text = "LIBRARY",
                            color = KBAccent,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        // The source line is the widest thing in this header
                        // (five connected services is a long string), and on a
                        // 360dp portrait screen the space left beside the title
                        // is not enough for it: sharing the title's row meant
                        // the last name - BINGECAT, last by reading order - went
                        // to the ellipsis, so a connected, mapped BingeCat never
                        // reached the screen. It gets its own full-width line
                        // under the title instead: the title keeps the left edge
                        // to itself, and a line too long for the screen wraps to
                        // a second one rather than dropping a label. No maxLines
                        // and no ellipsis here on purpose - an ellipsized source
                        // list is a lie about what the shelf can show.
                        //
                        // Reading order, the same as the TV: what the shelf
                        // always holds first, then the trackers, then the
                        // read-only feeds. Display only - the merge order
                        // underneath is the ViewModel's.
                        Text(
                            text = buildList {
                                add("LOCAL")
                                if (state.traktConnected) add("TRAKT")
                                if (state.simklConnected) add("SIMKL")
                                if (state.mdbListConfigured) add("MDBLIST")
                                // Connected AND mapped, matching the
                                // Library section's own rule: an unmapped
                                // BingeCat has no rows to offer.
                                if (state.bingecatConnected) add("BINGECAT")
                            }.joinToString(" + "),
                            style = MaterialTheme.typography.labelMedium,
                            color = KBTextLo,
                            modifier = Modifier.fillMaxWidth()
                        )
                        // Spec F2, the phone's half: a mapped BingeCat whose
                        // session is gone is not "connected with no rows". Its
                        // lists cannot be read until the viewer reconnects, and
                        // the pinned rails are kept, so say so here rather than
                        // leaving an empty shelf to explain itself.
                        if (state.bingecatNeedsReconnect) {
                            Text(
                                text = "BINGECAT NEEDS RECONNECTING - Settings > BingeCat. " +
                                    "Your profile choice and pinned rails are kept.",
                                color = KBAccent,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        // The permanent way into the year in review (spec
                        // 2026-11-13), under the source line and inside the
                        // header cell: a new grid item would shift every poster
                        // index the return-from-Detail landing counts.
                        Text(
                            text = "YEAR IN REVIEW ›",
                            color = KBAccent,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clickable { onOpenYearReview() }
                                .padding(vertical = 6.dp)
                        )
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
                                    // F3b: the list's identity, not its id -
                                    // a BingeCat row's id is its slot in the
                                    // snapshot.
                                    selected = list.sameListAs(state.selectedList),
                                    onClick = { viewModel.selectList(list) },
                                    // Every row gets the menu, not only the ones
                                    // this app can rename: "Show on Home" is an
                                    // answer about this viewer's Home and needs
                                    // no write to the service. Rename and delete
                                    // stay gated inside the dialog.
                                    onLongClick = { listMenu = list }
                                )
                            }

                            if (showCreateField) {
                                // The create destinations, from what is actually
                                // connected - the TV rail's four, in its order and
                                // behind its gates: local always, Trakt when
                                // connected, MDBList when configured, BingeCat
                                // when connected AND mapped (an unmapped profile
                                // has no lists API to call). Simkl is absent by
                                // nature: its API exposes no custom lists at all.
                                val createSources = listOfNotNull(
                                    LibrarySource.LOCAL_LIST,
                                    LibrarySource.TRAKT_LIST.takeIf { state.traktConnected },
                                    LibrarySource.MDBLIST_LIST.takeIf { state.mdbListConfigured },
                                    LibrarySource.BINGECAT_LIST.takeIf { state.bingecatConnected }
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    // Drawn above the field so the destination is
                                    // chosen before the name is typed, as on TV.
                                    // A single-source Library draws nothing extra
                                    // and behaves exactly as it always did.
                                    //
                                    // FlowRow rather than a scroller: the last
                                    // destination must be on screen without a
                                    // swipe, which a horizontal strip would hide.
                                    if (createSources.size > 1) {
                                        FlowRow(
                                            horizontalArrangement =
                                                Arrangement.spacedBy(8.dp),
                                            verticalArrangement =
                                                Arrangement.spacedBy(8.dp)
                                        ) {
                                            createSources.forEach { source ->
                                                TouchChoiceChip(
                                                    label =
                                                        ListControlWording.createSourceLabel(source),
                                                    selected = createSource == source,
                                                    onClick = { createSource = source }
                                                )
                                            }
                                        }
                                    }
                                    KBTextField(
                                        value = newListName,
                                        onValueChange = { newListName = it },
                                        placeholder = "New list name",
                                        modifier = Modifier.fillMaxWidth(),
                                        onDone = {
                                            if (newListName.isNotBlank()) {
                                                viewModel.createList(createSource, newListName)
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
                                            viewModel.createList(createSource, newListName)
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
                            LibrarySource.TRAKT_WATCHLIST,
                            // Step 3 gives BingeCat a watchlist remove, so the
                            // phone's menu offers it like the TV's.
                            LibrarySource.BINGECAT_WATCHLIST -> add(
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
                            LibrarySource.TRAKT_LIST,
                            LibrarySource.BINGECAT_LIST -> add(
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

                            // BingeCat's saved list (favorites) stays read-only,
                            // and no BingeCat row has a link out.
                            LibrarySource.BINGECAT_SAVED -> Unit

                            // Simkl's watchlist is writable now (as a
                            // `plantowatch` entry, and its list-only inverse on
                            // a removal), so this is a real Remove row - the
                            // TV's branch, which had it while the phone still
                            // drew "Managed on Simkl" over a row that only
                            // closed the menu.
                            LibrarySource.SIMKL_WATCHLIST -> add(
                                PosterContextAction(
                                    label = "Remove from Simkl",
                                    description =
                                        "Take it off your Simkl watchlist",
                                    isDestructive = true
                                ) {
                                    val selected = item
                                    menuItem = null
                                    viewModel.removeItem(selected)
                                }
                            )

                            // The list bucket has no removal API to call, so a
                            // Simkl list row keeps the explanatory copy (the
                            // sheet's attribution note) and no dead removal.
                            LibrarySource.SIMKL_LIST -> Unit

                            // An anime list's rows are the service's: no
                            // remove-from-list write exists for AniList, Kitsu
                            // or MAL, so the phone offers no dead removal either
                            // (the TV's branch, same reason).
                            LibrarySource.ANILIST_LIST,
                            LibrarySource.KITSU_LIST,
                            LibrarySource.MAL_LIST -> Unit
                        }

                        // Simkl's API rules: wherever Simkl data appears, name
                        // Simkl and link back to the page for THAT item - a bare
                        // link to simkl.com is explicitly not attribution. The
                        // id came in with the read itself, so this costs no
                        // request. The TV adds it the same way, outside its own
                        // per-source branch.
                        if (
                            item.source == LibrarySource.SIMKL_WATCHLIST ||
                            item.source == LibrarySource.SIMKL_LIST
                        ) {
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
                        }
                    },
                    onDismiss = { menuItem = null }
                )
            }

            // One row's actions: rename where the source allows it, pin it to
            // Home (any list can be a Home rail), delete where this device owns
            // it. The TV reaches the same three through its long-press menu.
            listMenu?.let { list ->
                Dialog(onDismissRequest = { listMenu = null }) {
                    KBDialogPanel(
                        title = list.name,
                        // A saved community list's summary names its owner as
                        // well as its source - the same line the TV dialog uses,
                        // from the same wording (ListControlWording).
                        subtitle = list.controlSummary
                    ) {
                        val pin = libraryHomeRailPinFor(list)
                        // Every pin that names this row, not just the spelling
                        // this build would write today - the TV's rule, on the
                        // phone's menu (spec F3, see pinsNamingList).
                        val pinsForRow = remember(
                            homeRailRevision,
                            list.id,
                            list.source,
                            list.remoteId,
                            list.name
                        ) {
                            pinsNamingList(
                                LibraryHomeRails.pins(context),
                                list.source.name,
                                libraryHomeRailIdSpellings(list),
                                list.name
                            )
                        }
                        val onHome = pinsForRow.isNotEmpty()
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (list.source.canManageList && !list.liked) {
                                KBButton(
                                    label = "RENAME LIST",
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    renameListDraft = list.name
                                    listPendingRename = list
                                    listMenu = null
                                }
                            }
                            if (pin != null) {
                                KBButton(
                                    // The TV's own wording: one pin, two states.
                                    label = if (onHome) "HIDE FROM HOME" else "SHOW ON HOME",
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    if (onHome) {
                                        // Every spelling goes, or the ghost rail
                                        // stays on Home.
                                        LibraryHomeRails.removePins(context, pinsForRow + pin)
                                    } else {
                                        LibraryHomeRails.setPinned(context, pin, true)
                                        notice = "\"${list.name}\" is now a row on Home."
                                    }
                                    homeRailRevision += 1
                                    listMenu = null
                                }
                            }
                            // Only a list this device owns can be deleted here; a
                            // Trakt, MDBList or BingeCat list is deleted on its
                            // service, from the Library's own list actions there.
                            if (list.source.isLocalList) {
                                KBButton(
                                    label = "DELETE LIST",
                                    danger = true,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    listMenu = null
                                    listPendingDelete = list
                                }
                            }
                            // A saved community list leaves by UNLIKING it, which
                            // is the only removal this device can honestly offer
                            // for a list it does not own (spec F4): the row goes,
                            // the author's list stays.
                            if (list.liked) {
                                KBButton(
                                    label = list.removeActionLabel,
                                    danger = true,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    listMenu = null
                                    listPendingDelete = list
                                }
                            }
                            // No rename for a saved community list, and the sheet
                            // explains the gap rather than leaving the viewer to
                            // wonder why their own list offers one.
                            list.renameUnavailableNote?.let { note ->
                                Text(
                                    text = note,
                                    color = KBTextLo,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            KBButton(
                                label = "CANCEL",
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                listMenu = null
                            }
                        }
                    }
                }
            }

            // Rename is a name-entry dialog, seeded with the list's current
            // name, and the row is repainted by the ViewModel only after the
            // source accepted it.
            listPendingRename?.let { list ->
                Dialog(onDismissRequest = { listPendingRename = null }) {
                    KBDialogPanel(
                        title = "Rename list",
                        subtitle = list.locationLabel
                    ) {
                        KBTextField(
                            value = renameListDraft,
                            onValueChange = { renameListDraft = it },
                            placeholder = "List name",
                            modifier = Modifier.fillMaxWidth(),
                            // F5: an unchanged name is not a rename. The TV's
                            // dialog refuses it and stays open, and SAVE without
                            // an edit used to dismiss this one silently - it
                            // read as if it had saved.
                            onDone = {
                                if (
                                    renameListDraft.isNotBlank() &&
                                    renameListDraft.trim() != list.name
                                ) {
                                    viewModel.renameList(list, renameListDraft)
                                    listPendingRename = null
                                }
                            }
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            KBButton(
                                label = "CANCEL",
                                modifier = Modifier.weight(1f)
                            ) {
                                listPendingRename = null
                            }
                            KBButton(
                                label = "SAVE",
                                enabled = renameListDraft.isNotBlank(),
                                modifier = Modifier.weight(1f)
                            ) {
                                // F5: same rule as Done - the dialog stays open
                                // and nothing is written for an unchanged name.
                                if (
                                    renameListDraft.isNotBlank() &&
                                    renameListDraft.trim() != list.name
                                ) {
                                    viewModel.renameList(list, renameListDraft)
                                    listPendingRename = null
                                }
                            }
                        }
                    }
                }
            }

            // Deleting a list is confirmed, never done by the press itself.
            listPendingDelete?.let { list ->
                Dialog(onDismissRequest = { listPendingDelete = null }) {
                    KBDialogPanel(
                        title = list.removeConfirmTitle,
                        subtitle = list.name
                    ) {
                        Text(
                            text = list.removeConfirmBody,
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
                                label = list.removeConfirmAction,
                                danger = true,
                                modifier = Modifier.weight(1f)
                            ) {
                                listPendingDelete = null
                                if (list.liked) {
                                    viewModel.deleteRemoteList(list)
                                } else {
                                    viewModel.deleteLocalList(list.id)
                                }
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
            // Count and source from the one wording module, so this row says the
            // same thing as the TV's - a saved community list names its owner
            // (spec F5), an ordinary list names only its service.
            text = list.controlSummary,
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
