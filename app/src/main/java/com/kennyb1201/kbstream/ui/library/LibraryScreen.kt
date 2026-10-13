package com.kennyb1201.kbstream.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kennyb1201.kbstream.data.library.LibraryItem
import com.kennyb1201.kbstream.data.library.LibraryList
import com.kennyb1201.kbstream.data.library.LibrarySource
import com.kennyb1201.kbstream.data.library.LocalLibraryStore
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.KBDialogPanel
import com.kennyb1201.kbstream.ui.components.KBPageTitle
import com.kennyb1201.kbstream.ui.components.KBStatusMessage
import com.kennyb1201.kbstream.ui.components.KB_STATUS_ICON_EMPTY
import com.kennyb1201.kbstream.ui.components.KBTextField
import com.kennyb1201.kbstream.ui.components.PosterCaptions
import com.kennyb1201.kbstream.ui.components.heroSourceElement
import com.kennyb1201.kbstream.data.library.HiddenTitles
import com.kennyb1201.kbstream.ui.browse.BROWSE_RETURN_FOCUS_DELAY_MS
import com.kennyb1201.kbstream.ui.browse.BROWSE_RETURN_FOCUS_STEPS
import com.kennyb1201.kbstream.ui.browse.BrowseReturnFocus
import com.kennyb1201.kbstream.ui.browse.BrowseReturnTarget
import com.kennyb1201.kbstream.ui.browse.landFocusOn
import com.kennyb1201.kbstream.ui.components.hideTarget
import com.kennyb1201.kbstream.ui.components.LibraryAddTarget
import com.kennyb1201.kbstream.ui.components.PosterContextAction
import com.kennyb1201.kbstream.ui.components.rememberKBFeedback
import com.kennyb1201.kbstream.ui.components.rememberHiddenTitleKeys
import com.kennyb1201.kbstream.ui.components.PosterContextMenu
import com.kennyb1201.kbstream.ui.components.rememberLongPressModifier
import com.kennyb1201.kbstream.ui.components.GlobalPosterCard
import com.kennyb1201.kbstream.ui.components.rememberPosterSize
import com.kennyb1201.kbstream.ui.search.successorIndexAfter
import com.kennyb1201.kbstream.ui.components.rememberPosterTileWidth
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBFocusChip
import com.kennyb1201.kbstream.ui.theme.KBFocusChipInset
import com.kennyb1201.kbstream.ui.theme.KBFocusGlowSmall
import com.kennyb1201.kbstream.ui.theme.KBFocusPressed
import com.kennyb1201.kbstream.ui.theme.KBFocusRow
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import kotlinx.coroutines.delay

/**
 * The Library's own page key, the only place it is spelled out (see
 * [BrowseReturnFocus]). One Library screen whatever filter it shows: the filter
 * is ViewModel state, so backing out of Detail comes back to the filter the
 * viewer left, and the recorded position is a position in that filter's grid.
 */
private const val LIBRARY_BROWSE_SCREEN_KEY = "library"

/**
 * Library tab: the user's personal lists and watchlists in one screen —
 * the local profile "My List", the Simkl Plan-to-Watch list, the MDBList
 * watchlist, and MDBList personal lists. Rows carry a source tag so it's
 * always clear which tracker an entry lives on.
 */
@Composable
fun LibraryScreen(
    onItemClick: (mediaType: String, id: String) -> Unit,
    viewModel: LibraryViewModel =
        viewModel()
) {
    val context = LocalContext.current
    val stateRaw by viewModel.uiState.collectAsStateWithLifecycle()
    // A hidden title leaves My List, the merged All list and every
    // personal list at once. Filtered here rather than in the store so
    // the rows the user is looking at update on the spot.
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
            watchlistItems =
                stateRaw.watchlistItems.filterNot(::hiddenItem),
            selectedListItems =
                stateRaw.selectedListItems.filterNot(::hiddenItem)
        )
    }

    val topFocusRequester = remember { FocusRequester() }

    // The grid state and the return record, both owned here rather than inside
    // the grid: a Back out of Detail disposes THIS composable and everything in
    // it, so the state a return has to land in cannot live below this point.
    // (See [BrowseReturnFocus].)
    // Keyed on the filter: switching filters is a new list, and a scroll
    // offset from the one before it means nothing in this one, so the strip
    // still starts the new list at the top. A return reads the same filter it
    // recorded under, so its own state is the one it scrolls (see
    // [BrowseReturnFocus]).
    val gridState = remember(state.filter) { LazyGridState() }
    var returnTarget by remember {
        mutableStateOf<BrowseReturnTarget?>(null)
    }
    val returnFocusRequester = remember { FocusRequester() }
    // What the grid reports when it has finished with the record: the place is
    // spent either way, and a return this page could not put back (the poster
    // was removed while Detail was open) still owes the viewer a focus target,
    // which is the strip's first chip the ordinary open uses.
    val onReturnHandled: (Boolean) -> Unit = { landed ->
        returnTarget = null
        if (!landed) topFocusRequester.requestFocus()
    }

    // The row whose long-press menu is open (null = no menu). A long press
    // opens this menu instead of removing straight away, so removal is an
    // explicit choice rather than an accidental one.
    var menuItem by remember { mutableStateOf<LibraryItem?>(null) }

    // Where the open menu's card is, so closing it puts the D-pad back where
    // the viewer was. Every other screen stashes the pressed card's requester
    // and restores it; the Library closed onto whatever the framework picked
    // next, which is the sort/filter strip. One requester per title, keyed the
    // way the grid keys its cells, because the row being REMOVED is the one
    // requester that must not be used: its successor is named by key before
    // the removal happens (see [successorIndexAfter]).
    val cardFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    var menuFocusKey by remember { mutableStateOf<String?>(null) }
    var menuSuccessorKey by remember { mutableStateOf<String?>(null) }
    var restoreFocusSignal by remember { mutableStateOf(0) }

    // Closing the menu owes the D-pad a landing: on the card that was pressed
    // for a plain dismiss, on the card that takes its place after a removal.
    fun closeMenu(restoreKey: String?) {
        menuItem = null
        menuFocusKey = restoreKey
        restoreFocusSignal++
    }

    LaunchedEffect(restoreFocusSignal) {
        if (restoreFocusSignal == 0) return@LaunchedEffect
        val key = menuFocusKey
        menuFocusKey = null
        landFocusOn(key?.let { cardFocusRequesters[it] })
    }

    // A local list waiting to be deleted (null = no dialog). The list rail's
    // long press opened straight onto a delete that nothing asked about; the
    // app's own rule for a long press is that it opens a menu and leaves the
    // destructive choice to the viewer.
    var listPendingDelete by remember { mutableStateOf<LibraryList?>(null) }

    LaunchedEffect(Unit) {
        // Re-read the list every time this tab is opened. The ViewModel is
        // activity-scoped, so without this the tab kept showing whatever
        // snapshot it took the first time it was visited — a title added from
        // a detail page (or removed here) never appeared, which made
        // "Add to Library" look like it had silently failed.
        viewModel.refresh()
        // Then read back where this page's Detail page was opened from, if it
        // was opened from here: the poster the viewer pressed is where the
        // grid comes back to, instead of the top with the first filter chip
        // focused losing their place.
        val recorded = BrowseReturnFocus.consume(LIBRARY_BROWSE_SCREEN_KEY)
        if (recorded != null) {
            returnTarget = recorded
        } else {
            topFocusRequester.requestFocus()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // The app background, not pure black: Home is the only screen that
            // paints black on its own, and leaving this one black made Library
            // the exception the AMOLED toggle could not reach (nothing to
            // change when the toggle swaps black for black).
            .background(KBVoid)
            .padding(horizontal = 24.dp, vertical = 18.dp)
    ) {
        // Header. Title on the left, connection health on the right — each on
        // ONE line. This used to be a single Row holding the title, nine chips,
        // a weight spacer AND the status text, so with nine chips the status
        // text was measured into whatever pixels were left over and wrapped one
        // letter per line ("S I M K L +"), while the leading chips were pushed
        // off the left edge.
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.fillMaxWidth()
        ) {
            KBPageTitle(text = "LIBRARY", color = KBAccent)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = buildList {
                    if (state.simklConnected) add("SIMKL")
                    if (state.mdbListConfigured) add("MDBLIST")
                    add("LOCAL")
                }.joinToString(" + "),
                style = MaterialTheme.typography.labelMedium,
                color = KBTextLo,
                maxLines = 1,
                softWrap = false
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Every control lives in ONE horizontally scrollable strip: focus walks
        // it left to right and the strip scrolls to follow, so no chip is ever
        // clipped at an edge and none has to wrap its label ("RATING" was
        // breaking into "RATI/NG" when the old fixed Row ran out of room). The
        // sort group is separated by a rule rather than pushed to its own line.
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            // The strip is a lazy row, so it clips its own content - the
            // first control (which this screen focuses on entry) lost its
            // grown left edge and glow against that boundary. The inset sits
            // inside the clip and the offset cancels it outside, so the
            // chips still line up with the title's 24dp inset.
            contentPadding = PaddingValues(horizontal = KBFocusChipInset),
            modifier = Modifier
                .fillMaxWidth()
                .offset(x = -KBFocusChipInset)
        ) {
            items(LibraryFilter.entries, key = { "filter_${it.name}" }) { filter ->
                LibraryFilterChip(
                    label = filter.label,
                    selected = state.filter == filter,
                    onClick = { viewModel.setFilter(filter) },
                    modifier = if (filter == LibraryFilter.entries.first()) {
                        Modifier.focusRequester(topFocusRequester)
                    } else {
                        Modifier
                    }
                )
            }

            // UNWATCHED toggle: hides rows already marked watched.
            item(key = "unwatched") {
                LibraryFilterChip(
                    label = if (state.hideWatched) "WATCHED HIDDEN" else "UNWATCHED",
                    selected = state.hideWatched,
                    onClick = { viewModel.toggleHideWatched() }
                )
            }

            item(key = "sort_rule") { ChipDivider() }

            // Sort chips: Added / Title / Date / Rating. Each is a TWO-state
            // control - first tap sorts ascending, the next flips it - so the
            // selected chip carries the direction (▲ / ▼) and the others stay
            // bare. Without the glyph the second tap would look like a no-op on
            // a chip that is already selected.
            items(LibrarySort.entries, key = { "sort_${it.name}" }) { sort ->
                val selected = state.sort == sort
                LibraryFilterChip(
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

        Spacer(modifier = Modifier.height(14.dp))

        when (state.filter) {
            LibraryFilter.ALL -> {
                ItemGrid(
                    items = state.allItems,
                    emptyText = if (state.loading) {
                        "Loading…"
                    } else {
                        "Nothing here yet. Long-press any poster and choose " +
                            "\"Add to list…\" — My List, watchlists and personal " +
                            "lists all land in this merged view."
                    },
                    loading = state.loading,
                    sourceLabel = { it.source.label },
                    onItemClick = onItemClick,
                    onItemLongClick = { item, successorKey ->
                        menuItem = item
                        // Whose D-pad the removal hands on to, if the menu's
                        // Remove row is the one pressed (see closeMenu).
                        menuSuccessorKey = successorKey
                    },
                    cardFocusRequesters = cardFocusRequesters,
                    ratings = state.ratings,
                    watchedKeys = state.watchedKeys,
                    partiallyWatchedKeys = state.partiallyWatchedKeys,
                    gridState = gridState,
                    returnTarget = returnTarget,
                    returnFocusRequester = returnFocusRequester,
                    onReturnHandled = onReturnHandled
                )
            }

            LibraryFilter.MY_LIST -> {
                ItemGrid(
                    items = state.localItems,
                    emptyText = if (state.loading) {
                        "Loading…"
                    } else {
                        "Your list is empty. Long-press any poster on a " +
                            "detail page and choose \"Add to Library\"."
                    },
                    loading = state.loading,
                    sourceLabel = { it.source.label },
                    onItemClick = onItemClick,
                    onItemLongClick = { item, successorKey ->
                        menuItem = item
                        // Whose D-pad the removal hands on to, if the menu's
                        // Remove row is the one pressed (see closeMenu).
                        menuSuccessorKey = successorKey
                    },
                    cardFocusRequesters = cardFocusRequesters,
                    ratings = state.ratings,
                    watchedKeys = state.watchedKeys,
                    partiallyWatchedKeys = state.partiallyWatchedKeys,
                    gridState = gridState,
                    returnTarget = returnTarget,
                    returnFocusRequester = returnFocusRequester,
                    onReturnHandled = onReturnHandled
                )
            }

            LibraryFilter.WATCHLIST -> {
                ItemGrid(
                    items = state.watchlistItems,
                    emptyText = if (state.loading) {
                        "Loading watchlists…"
                    } else {
                        val sources = mutableListOf<String>()
                        if (!state.simklConnected) sources.add("Simkl not connected")
                        if (!state.mdbListConfigured) sources.add("MDBList key not set")
                        if (sources.isEmpty()) {
                            "Watchlist is empty."
                        } else {
                            "Watchlist is empty. " + sources.joinToString(" · ")
                        }
                    },
                    loading = state.loading,
                    sourceLabel = { it.source.label },
                    onItemClick = onItemClick,
                    onItemLongClick = { item, successorKey ->
                        menuItem = item
                        // Whose D-pad the removal hands on to, if the menu's
                        // Remove row is the one pressed (see closeMenu).
                        menuSuccessorKey = successorKey
                    },
                    cardFocusRequesters = cardFocusRequesters,
                    ratings = state.ratings,
                    watchedKeys = state.watchedKeys,
                    partiallyWatchedKeys = state.partiallyWatchedKeys,
                    gridState = gridState,
                    returnTarget = returnTarget,
                    returnFocusRequester = returnFocusRequester,
                    onReturnHandled = onReturnHandled
                )
            }

            LibraryFilter.LISTS -> {
                ListsPane(
                    lists = state.lists,
                    selectedList = state.selectedList,
                    listItems = state.selectedListItems,
                    loading = state.loading,
                    onListSelect = { viewModel.selectList(it) },
                    onListLongPress = { list ->
                        // Local lists can be deleted; MDBList lists are
                        // managed on MDBList. Asked about first: a long press
                        // opens a menu everywhere else in the app, and a whole
                        // list leaving on one is not a choice a stray press
                        // should be able to make.
                        if (list.id < 0) {
                            listPendingDelete = list
                        }
                    },
                    onCreateList = { viewModel.createLocalList(it) },
                    onItemClick = onItemClick,
                    onItemLongClick = { item, successorKey ->
                        menuItem = item
                        // Whose D-pad the removal hands on to, if the menu's
                        // Remove row is the one pressed (see closeMenu).
                        menuSuccessorKey = successorKey
                    },
                    cardFocusRequesters = cardFocusRequesters,
                    ratings = state.ratings,
                    watchedKeys = state.watchedKeys,
                    partiallyWatchedKeys = state.partiallyWatchedKeys,
                    gridState = gridState,
                    returnTarget = returnTarget,
                    returnFocusRequester = returnFocusRequester,
                    onReturnHandled = onReturnHandled
                )
            }
        }
    }

    // Long-press menu overlay. Rendered as a sibling of the content Column so
    // it dims and traps focus above the grid, exactly like the shared poster
    // menu on every other screen.
    menuItem?.let { item ->
        LibraryItemMenu(
            item = item,
            onDismiss = { closeMenu(LocalLibraryStore.dedupeKey(item)) },
            onOpenDetails = { id ->
                // Navigating away: the screen behind this one is about to be
                // disposed, so there is nothing to hand the focus back to.
                menuItem = null
                onItemClick(item.mediaType, id)
            },
            onRemove = {
                // The card leaves the grid, so the D-pad lands on the one that
                // takes its place (or the one before it when the last row is
                // the one going) - named from the grid's own list BEFORE the
                // removal, the same successor the browse rails' Hide uses.
                val successor = menuSuccessorKey
                menuItem = null
                viewModel.removeItem(item)
                closeMenu(successor)
            }
        )
    }

    listPendingDelete?.let { list ->
        LibraryDeleteListDialog(
            list = list,
            onDismiss = { listPendingDelete = null },
            onConfirm = {
                listPendingDelete = null
                viewModel.deleteLocalList(list.id)
            }
        )
    }
    }
}

/**
 * Long-press menu for one Library row, built on the app-wide
 * [PosterContextMenu] so a long press looks and behaves the same here as on
 * Home, Search and the detail rails. Simkl rows are add-only (the API has no
 * remove-from-watchlist endpoint), so they get an explanatory row instead of
 * a destructive one.
 */
@Composable
private fun LibraryItemMenu(
    item: LibraryItem,
    onDismiss: () -> Unit,
    onOpenDetails: (String) -> Unit,
    onRemove: () -> Unit
) {
    val subtitle = listOfNotNull(
        item.year?.toString(),
        item.source.label
    ).joinToString(" · ")

    PosterContextMenu(
        title = item.title,
        subtitle = subtitle,
        // A row here is already in the library, so the add row reads
        // "In Library ✓" — but it still carries "Add to list…", which is how a
        // title saved from a tracker gets into a personal list without going
        // back to the poster it came from.
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
            item.navigationId?.let { id ->
                add(
                    PosterContextAction(
                        label = "Go to Details",
                        description = "Open this title's detail page"
                    ) {
                        onOpenDetails(id)
                    }
                )
            }

            when (item.source) {
                LibrarySource.LOCAL,
                LibrarySource.MDBLIST_WATCHLIST -> add(
                    PosterContextAction(
                        label = "Remove from Library",
                        description = "Remove it here and on every connected tracker",
                        isDestructive = true
                    ) {
                        onRemove()
                    }
                )

                LibrarySource.LOCAL_LIST,
                LibrarySource.MDBLIST_LIST -> add(
                    PosterContextAction(
                        label = "Remove from this list",
                        description = item.listName?.let { "Take it out of \"$it\"" },
                        isDestructive = true
                    ) {
                        onRemove()
                    }
                )

                LibrarySource.SIMKL_WATCHLIST,
                LibrarySource.SIMKL_LIST -> add(
                    PosterContextAction(
                        label = "Managed on Simkl",
                        description = "Remove it from your watchlist on simkl.com"
                    ) {
                        onDismiss()
                    }
                )
            }
        },
        onDismiss = onDismiss
    )
}

/**
 * Confirms deleting one local list.
 *
 * The list rail's long press opens this instead of deleting on the spot: what
 * leaves is a list the viewer made, and the app's one rule for a long press is
 * that it opens a choice rather than acting (see [LibraryItemMenu]). MDBList
 * lists never get here - they are managed on MDBList, and the rail's own
 * callback refuses one.
 *
 * CANCEL is drawn FIRST and takes the focus, so the safe action is the default
 * stop: this is the rule the Settings clear-history dialog needed, where
 * Compose's first-focusable default put the destructive row under the OK of a
 * double-tapped confirm. The request is retried across frames because the
 * dialog's buttons attach a frame or more after it opens, and an unattached
 * requester answers false rather than throwing.
 */
@Composable
private fun LibraryDeleteListDialog(
    list: LibraryList,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val cancelFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        var landed = false
        var attempts = 0
        while (!landed && attempts < BROWSE_RETURN_FOCUS_STEPS) {
            delay(BROWSE_RETURN_FOCUS_DELAY_MS)
            landed = runCatching { cancelFocusRequester.requestFocus() }
                .getOrDefault(false)
            attempts++
        }
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        KBDialogPanel(title = "Delete list?", subtitle = list.name) {
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
                KBCard(
                    onClick = onDismiss,
                    modifier = Modifier.focusRequester(cancelFocusRequester)
                ) {
                    Text(
                        text = "CANCEL",
                        color = KBTextLo,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
                KBCard(onClick = onConfirm) {
                    Text(
                        text = "DELETE",
                        color = KBAccent,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}

/** Thin rule between the filter group and the sort group in the chip strip. */
@Composable
private fun ChipDivider() {
    Box(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .width(1.dp)
            .height(24.dp)
            .background(KBTextLo.copy(alpha = 0.35f))
    )
}

/**
 * One pill in the Library's control strips.
 *
 * Built on the TV clickable Surface, NOT a hand-rolled
 * `.focusable().clickable()` box. Two stacked focus/click modifiers make two
 * targets: the D-pad press landed focus on the outer one and only the second
 * press reached the inner clickable — the "you have to double click chips"
 * behavior. A Surface carries focus, activation and the focused colors as a
 * single target, so one press selects, like every other control in the app.
 */
@Composable
private fun LibraryFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val shape = KBShapeCard

    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = when {
                selected -> KBAccent.copy(alpha = 0.28f)
                focused -> KBSurfaceRaised
                else -> KBSurface
            },
            contentColor = when {
                selected -> KBAccent
                focused -> KBTextHi
                else -> KBTextLo
            }
        ),
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = KBFocusChip,
            pressedScale = KBFocusPressed
        ),
        // Surface-owned border, not a `.clip(shape).border()` on the modifier:
        // the caller-side clip wraps the whole Surface, so it pins the animated
        // growth to the chip's layout bounds and swallows the focus scale.
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(
                    1.dp,
                    if (selected) KBAccent else KBTextLo.copy(alpha = 0.35f)
                ),
                shape = shape
            ),
            focusedBorder = Border(
                border = BorderStroke(
                    2.dp,
                    if (selected) KBAccent else KBTextHi
                ),
                shape = shape
            )
        ),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(
                elevationColor = KBAccent,
                elevation = KBFocusGlowSmall
            )
        ),
        modifier = modifier.onFocusChanged { focused = it.isFocused }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            // A pill is one line by definition.
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

/**
 * Poster grid shared by MY LIST and WATCHLIST. Long-press removes a local
 * row when [onItemLongClick] is provided (the KBCard long-press fires on
 * the same menu-button/D-pad-hold gesture the rest of the app uses).
 */
@Composable
private fun ItemGrid(
    items: List<LibraryItem>,
    emptyText: String,
    // Whether the empty grid is "still loading" - the card draws the shared
    // spinner for it instead of the empty-state icon, exactly as the browse
    // screens do. The empty COPY stays with the caller: "Loading watchlists…"
    // and "Nothing here yet" are different sentences, not differently-drawn
    // states.
    loading: Boolean = false,
    sourceLabel: (LibraryItem) -> String,
    onItemClick: (String, String) -> Unit,
    // (the pressed item, the key of the card that takes its place if that item
    // is removed - null when it is the only card). The successor is worked out
    // HERE because this composable is the one holding the list, and it has to
    // be known before the removal: by the time the menu closes, the pressed
    // card's own node is gone.
    onItemLongClick: ((LibraryItem, String?) -> Unit)?,
    // One requester per cell, keyed the way this grid keys its cells: what the
    // screen's long-press menu hands the D-pad back to (see its closeMenu).
    cardFocusRequesters: MutableMap<String, FocusRequester>,
    ratings: Map<String, Double> = emptyMap(),
    watchedKeys: Set<String> = emptySet(),
    // Started-but-unfinished rows: the eye badge. Drawn only for keys the
    // ViewModel did NOT also mark watched, so the checkmark keeps the corner
    // on a title that was finished after being started.
    partiallyWatchedKeys: Set<String> = emptySet(),
    modifier: Modifier = Modifier.fillMaxSize(),
    // The screen's grid state and its record of the poster a Detail page was
    // opened from (see [BrowseReturnFocus]).
    gridState: LazyGridState,
    returnTarget: BrowseReturnTarget?,
    returnFocusRequester: FocusRequester,
    onReturnHandled: (Boolean) -> Unit
) {
    // The app-wide transient-message channel (see KBFeedback): a card with no
    // detail page says why instead of swallowing the press.
    val feedback = rememberKBFeedback()

    if (items.isEmpty()) {
        // The app's ONE status card, not a bare centered line: every browse
        // screen says "nothing here" with this plate, and the Library's empty
        // states were the last ones drawn as loose text dropped into a pane.
        //
        // Deliberately no retry action here. On Home and Detail the retry card
        // has to take focus itself, because the state it appears in has
        // nothing else focusable; this screen always has its sort/filter strip
        // and its list rail, so a focus-grabbing card would take the D-pad
        // away from a screen that does not need it handed anywhere.
        KBStatusMessage(
            message = emptyText,
            icon = KB_STATUS_ICON_EMPTY,
            loading = loading,
            modifier = modifier
        )
        return
    }

    val posterSize = rememberPosterSize()
    // Landscape tiles are wider than the poster they replace, so the grid cell
    // has to follow the shape GlobalPosterCard actually draws. Sized from
    // posterSize.width alone, a landscape card was clamped to the ~124dp poster
    // cell while its height stayed the landscape height, so it read as square.
    val tileWidth = rememberPosterTileWidth(posterSize.width)

    // A real grid whose cells are sized from the Poster Size setting, instead
    // of hand-chunked rows of six: with six hard-coded tiles the row could be
    // wider than the pane (clipping the last poster) and the tile count never
    // adapted to the screen or the setting. Adaptive cells always fill the
    // pane, so every poster is fully visible and nothing is left half cut.
    // Puts the grid back where the viewer left it: the poster they pressed,
    // its place, and the focus on it. The rows can land after this grid
    // composes (the screen's refresh is in flight), so the record is held until
    // they do; and a card that is not composed yet has no focus node, so a
    // request against it fails silently and is retried (see [BrowseReturnFocus]).
    LaunchedEffect(returnTarget, items) {
        val target = returnTarget ?: return@LaunchedEffect
        val index = items.indexOfFirst {
            LocalLibraryStore.dedupeKey(it) == target.itemId
        }.takeIf { it >= 0 }
            ?: target.position.takeIf { it in items.indices }
            ?: return@LaunchedEffect
        runCatching { gridState.scrollToItem(index) }
        var landed = false
        var attempts = 0
        while (!landed && attempts < BROWSE_RETURN_FOCUS_STEPS) {
            delay(BROWSE_RETURN_FOCUS_DELAY_MS)
            landed = runCatching { returnFocusRequester.requestFocus() }
                .getOrDefault(false)
            attempts++
        }
        onReturnHandled(landed)
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = tileWidth),
        // The screen's own state, not one re-created here: a Back out of Detail
        // disposes this grid, so a return has a state to scroll (see
        // [BrowseReturnFocus]).
        state = gridState,
        // The top gap is not decoration: a focused KBCard scales to 1.03 and
        // throws a 12.dp glow, and a lazy grid clips its viewport — with
        // bottom-only padding the FIRST row was sliced flat along its top edge
        // the moment it took focus (poster, focus border and glow all cut).
        // Every other poster grid in the app leaves this room; this one was
        // added last and did not, which is why only the Library clipped.
        contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
    ) {
        itemsIndexed(
            items = items,
            // The same key [BrowseReturnFocus] records a card under, so a grid
            // that has re-ordered under the viewer comes back on the poster
            // that is still there rather than on whatever took its place.
            key = { _, item -> LocalLibraryStore.dedupeKey(item) }
        ) { index, item ->
            val cardKey = LocalLibraryStore.dedupeKey(item)
            // This cell's own node, published so the screen's menu can ask for
            // it by key - including AFTER this card is the one that left the
            // grid, which is when the neighbour's entry is the one that is
            // read (the map is not pruned for the same reason: the entry is
            // what a removal lands on).
            val cardRequester = remember(cardKey) { FocusRequester() }
            cardFocusRequesters[cardKey] = cardRequester
            // The card that replaces this one when it is removed: the next one,
            // or the one before it when this is the last - the same successor
            // rule the browse chips' and the search rail's Hide use.
            val successorKey = successorIndexAfter(items.size, index)
                ?.let { LocalLibraryStore.dedupeKey(items[it]) }
            val isReturnItem =
                returnTarget != null &&
                    index == returnTarget.position &&
                    cardKey == returnTarget.itemId
            LibraryPosterCard(
                item = item,
                sourceLabel = sourceLabel(item),
                rating = ratings[LocalLibraryStore.dedupeKey(item)],
                isWatched = item.watchedKey() in watchedKeys,
                isPartiallyWatched = item.watchedKey() in partiallyWatchedKeys,
                onClick = {
                    val navigationId = item.navigationId
                    if (navigationId != null) {
                        // Remember this poster, so backing out of its Detail
                        // page reopens the grid ON it (see [BrowseReturnFocus]).
                        BrowseReturnFocus.record(
                            BrowseReturnTarget(
                                screenKey = LIBRARY_BROWSE_SCREEN_KEY,
                                itemId = cardKey,
                                position = index
                            )
                        )
                        onItemClick(item.mediaType, navigationId)
                    } else {
                        // A tracker row can carry only a title and a poster —
                        // no IMDB or TMDB id — so there is no detail page to
                        // open. The press used to do nothing at all, which
                        // reads as a frozen grid; say so instead of dropping
                        // it. The row is kept (rather than filtered) so its
                        // long-press menu can still remove it.
                        feedback.show("No details page for \"${item.title}\"")
                    }
                },
                onLongClick = onItemLongClick?.let { handler ->
                    { handler(item, successorKey) }
                },
                modifier = Modifier
                    .focusRequester(cardRequester)
                    .then(
                        if (isReturnItem) {
                            Modifier.focusRequester(returnFocusRequester)
                        } else {
                            Modifier
                        }
                    )
            )
        }
    }
}

/**
 * PERSONAL LISTS: left rail of local + MDBList lists (plus a create-list
 * row), right pane of the selected list's items.
 */
@Composable
private fun ListsPane(
    lists: List<LibraryList>,
    selectedList: LibraryList?,
    listItems: List<LibraryItem>,
    loading: Boolean,
    onListSelect: (LibraryList) -> Unit,
    onListLongPress: (LibraryList) -> Unit,
    onCreateList: (String) -> Unit,
    onItemClick: (String, String) -> Unit,
    onItemLongClick: (LibraryItem, String?) -> Unit,
    // The screen's per-card requesters, registered by the grids below and read
    // by its long-press menu's hand-back (see the screen's closeMenu).
    cardFocusRequesters: MutableMap<String, FocusRequester>,
    ratings: Map<String, Double>,
    watchedKeys: Set<String>,
    partiallyWatchedKeys: Set<String>,
    gridState: LazyGridState,
    returnTarget: BrowseReturnTarget?,
    returnFocusRequester: FocusRequester,
    onReturnHandled: (Boolean) -> Unit
) {
    var showCreateField by remember { mutableStateOf(false) }
    var newListName by remember { mutableStateOf("") }

    Row(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            // The rail is a fixed 280dp column, so it must NOT chain a
            // `fillMaxSize()` after `width(280.dp)`. Modifiers are wrapper
            // nodes and the FIRST size modifier sets the exact width it wraps,
            // so the chain happened to resolve to 280dp here; `fillMaxHeight()`
            // states the intent (fixed width, fill the row's height) and cannot
            // drift into filling the row's width if the order is ever touched.
            modifier = Modifier
                .width(280.dp)
                .fillMaxHeight()
        ) {
            items(lists, key = { list -> list.id }) { list ->
                var focused by remember { mutableStateOf(false) }
                val selected = selectedList?.id == list.id
                val sourceLabel = if (list.id < 0) "This device" else "MDBList"
                // The delete gesture the local lists never had: the parameter
                // and the ViewModel call existed, nothing ever invoked them.
                // Only for a list this device owns - an MDBList list is
                // managed on MDBList, and the screen's callback refuses one
                // anyway; not offering the press is what says so.
                val onRowLongPress: (() -> Unit)? =
                    if (list.id < 0) {
                        { onListLongPress(list) }
                    } else {
                        null
                    }
                Surface(
                    onClick = { onListSelect(list) },
                    shape = ClickableSurfaceDefaults.shape(
                        shape = KBShapeCard
                    ),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = when {
                            selected -> KBAccent.copy(alpha = 0.22f)
                            focused -> KBSurfaceRaised
                            else -> KBSurface
                        },
                        contentColor = KBTextHi
                    ),
                    // A rail row, so it takes the row step of the shared focus
                    // scale -- and the press-in every other row in the app
                    // has. It previously changed container color only, which
                    // left the list rail the one place a Select press did
                    // nothing at all.
                    scale = ClickableSurfaceDefaults.scale(
                        focusedScale = KBFocusRow,
                        pressedScale = KBFocusPressed
                    ),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, KBAccent),
                            shape = KBShapeCard
                        )
                    ),
                    glow = ClickableSurfaceDefaults.glow(
                        focusedGlow = Glow(
                            elevationColor = KBAccent,
                            elevation = KBFocusGlowSmall
                        )
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        // A select-held gesture, so the row keeps its press and
                        // its focus: the modifier swallows only the long KeyUp
                        // (see KBCard), which is what leaves the short press
                        // opening the list as it always did.
                        .then(rememberLongPressModifier(onRowLongPress))
                        .onFocusChanged { focused = it.isFocused }
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = list.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) KBAccent else KBTextHi,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${list.itemCount} titles · $sourceLabel",
                            style = MaterialTheme.typography.labelSmall,
                            color = KBTextLo
                        )
                    }
                }
            }

            // Create-list affordance at the bottom of the rail.
            item(key = "create_list") {
                if (showCreateField) {
                    Column {
                        KBTextField(
                            value = newListName,
                            onValueChange = { newListName = it },
                            placeholder = "New list name",
                            modifier = Modifier.fillMaxWidth(),
                            onDone = {
                                if (newListName.isNotBlank()) {
                                    onCreateList(newListName)
                                }
                                newListName = ""
                                showCreateField = false
                            }
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        LibraryFilterChip(
                            label = "CREATE",
                            selected = false,
                            onClick = {
                                if (newListName.isNotBlank()) {
                                    onCreateList(newListName)
                                }
                                newListName = ""
                                showCreateField = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    LibraryFilterChip(
                        label = "+ NEW LIST",
                        selected = false,
                        onClick = { showCreateField = true },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(20.dp))

        ItemGrid(
            items = listItems,
            emptyText = when {
                loading && selectedList == null -> "Loading lists…"
                selectedList == null -> "Select a list on the left."
                else -> "This list is empty. Long-press any poster app-wide " +
                    "and choose \"Add to list…\" to fill it."
            },
            loading = loading,
            sourceLabel = { it.source.label },
            onItemClick = onItemClick,
            onItemLongClick = onItemLongClick,
            cardFocusRequesters = cardFocusRequesters,
            ratings = ratings,
            watchedKeys = watchedKeys,
            partiallyWatchedKeys = partiallyWatchedKeys,
            gridState = gridState,
            returnTarget = returnTarget,
            returnFocusRequester = returnFocusRequester,
            onReturnHandled = onReturnHandled,
            // Takes the remaining width beside the list rail; fillMaxSize
            // inside a Row measured against the whole row.
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun LibraryPosterCard(
    item: LibraryItem,
    sourceLabel: String,
    rating: Double?,
    isWatched: Boolean,
    isPartiallyWatched: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    // Carries the return focus requester on the card a Back out of Detail is
    // coming back for (see [BrowseReturnFocus]); it wraps the focusable card,
    // which is where requestFocus() resolves to.
    modifier: Modifier = Modifier
) {
    val posterSize = rememberPosterSize()
    // The tile's own focus drives the caption marquee below.
    var focused by remember { mutableStateOf(false) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        // Fills the grid cell (a grid picks the cell width), so the caption and
        // source line sit under the poster instead of being measured against a
        // width the cell may not have.
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.hasFocus }
            .then(modifier)
    ) {
        GlobalPosterCard(
            posterUrl = item.posterUrl,
            contentDescription = item.title,
            isWatched = isWatched,
            isPartiallyWatched = isPartiallyWatched,
            onClick = onClick,
            onLongClick = onLongClick,
            posterWidth = posterSize.width,
            posterHeight = posterSize.height,
            // A tracker row carries only a poster URL; the shared resolver
            // fills in the backdrop + clearlogo for the landscape shape.
            artId = item.navigationId,
            artType = item.mediaType,
            modifier = Modifier
                // Same shared key the Library hands to Detail, so a poster
                // flies into the hero instead of the grid hard-cutting.
                // navigationId is null for rows with no TMDB id, and those
                // cannot navigate at all, so the empty key is never a match.
                .heroSourceElement(item.mediaType, item.navigationId.orEmpty())
        )

        // Captions go through the shared PosterCaptions block so the
        // Settings toggles (poster titles / years / star ratings) behave
        // here exactly like on every other screen. The source tag rides
        // below, un-gated, so tracker origin stays visible.
        PosterCaptions(
            title = item.title,
            year = item.year?.toString(),
            rating = rating,
            focused = focused,
            modifier = Modifier.padding(top = 6.dp)
        )
        Text(
            text = sourceLabel,
            style = MaterialTheme.typography.labelSmall,
            color = KBTextLo,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
