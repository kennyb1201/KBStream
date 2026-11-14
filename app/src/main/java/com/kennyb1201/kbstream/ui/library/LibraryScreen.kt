package com.kennyb1201.kbstream.ui.library

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import com.kennyb1201.kbstream.data.library.LibraryHomeRailPin
import com.kennyb1201.kbstream.data.library.LibraryHomeRails
import com.kennyb1201.kbstream.data.library.LibraryItem
import com.kennyb1201.kbstream.data.library.LibraryList
import com.kennyb1201.kbstream.data.library.LibrarySource
import com.kennyb1201.kbstream.data.library.ListControlWording
import com.kennyb1201.kbstream.data.library.LocalLibraryStore
import com.kennyb1201.kbstream.data.library.controlAttribution
import com.kennyb1201.kbstream.data.library.controlSummary
import com.kennyb1201.kbstream.data.library.libraryHomeRailIdSpellings
import com.kennyb1201.kbstream.data.library.libraryListRowKey
import com.kennyb1201.kbstream.data.library.libraryHomeRailPinFor
import com.kennyb1201.kbstream.data.library.pinsNamingList
import com.kennyb1201.kbstream.data.library.removeActionLabel
import com.kennyb1201.kbstream.data.library.removeConfirmAction
import com.kennyb1201.kbstream.data.library.removeConfirmBody
import com.kennyb1201.kbstream.data.library.removeConfirmTitle
import com.kennyb1201.kbstream.data.library.renameUnavailableNote
import com.kennyb1201.kbstream.data.library.sameListAs
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.KBDialogPanel
import com.kennyb1201.kbstream.ui.components.KBPageTitle
import com.kennyb1201.kbstream.ui.components.KBStatusMessage
import com.kennyb1201.kbstream.ui.components.KB_STATUS_ICON_EMPTY
import com.kennyb1201.kbstream.ui.components.KBTextField
import com.kennyb1201.kbstream.ui.components.PosterCaptions
import com.kennyb1201.kbstream.ui.components.heroSourceElement
import com.kennyb1201.kbstream.data.library.HiddenTitles
import com.kennyb1201.kbstream.data.simkl.simklItemUrl
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
import com.kennyb1201.kbstream.ui.mobile.LibraryScreenMobile
import com.kennyb1201.kbstream.ui.sports.SportsCardBringIntoViewSpec
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBFocusChip
import com.kennyb1201.kbstream.ui.theme.KBFocusChipInset
import com.kennyb1201.kbstream.ui.theme.KBFocusGlowSmall
import com.kennyb1201.kbstream.ui.theme.KBFocusPressed
import com.kennyb1201.kbstream.ui.theme.KBFocusRow
import com.kennyb1201.kbstream.ui.theme.KBFocusRowInset
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Library's own page key, the only place it is spelled out (see
 * [BrowseReturnFocus]). One Library screen whatever filter it shows: the filter
 * is ViewModel state, so backing out of Detail comes back to the filter the
 * viewer left, and the recorded position is a position in that filter's grid.
 */
internal const val LIBRARY_BROWSE_SCREEN_KEY = "library"

/**
 * Library tab: the user's personal lists and watchlists in one screen —
 * the local profile "My List", the Simkl Plan-to-Watch list, the MDBList
 * watchlist, and MDBList personal lists. Rows carry a source tag so it's
 * always clear which tracker an entry lives on.
 */
@Composable
fun LibraryScreen(
    onItemClick: (mediaType: String, id: String) -> Unit,
    /** Opens the year in review: the Library's year-round door beside the shelves. */
    onOpenYearReview: () -> Unit = {},
    viewModel: LibraryViewModel =
        viewModel()
) {
    // The phone entry: the same ViewModel, callbacks and filter/sort state,
    // laid out for touch. One branch; the TV page below is unchanged (see
    // LibraryScreenMobile).
    if (!com.kennyb1201.kbstream.FormFactor.isTv) {
        LibraryScreenMobile(
            onItemClick = onItemClick,
            onOpenYearReview = onOpenYearReview,
            viewModel = viewModel
        )
        return
    }

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
    // Set only by the Home Upcoming header's shortcut (see
    // [LibraryCalendarEntry]): the Calendar pane then takes the D-pad instead of
    // the chip. Any chip press clears it, so an ordinary visit never does.
    var calendarTakeFocus by remember { mutableStateOf(false) }

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
    // The strip's own scroll state, so the entry landing can bring the SELECTED
    // chip into composed view before asking it for focus (see the landing below).
    val stripState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // The grid state and the return record, both owned here rather than inside
    // the grid: a Back out of Detail disposes THIS composable and everything in
    // it, so the state a return has to land in cannot live below this point.
    // (See [BrowseReturnFocus].)
    // Keyed on the filter AND the open personal list: switching filters is a
    // new list, and a scroll offset from the one before it means nothing in
    // this one. The selected list is the second half of that identity - the
    // LISTS filter shows ONE list's rows, so opening list B kept list A's
    // offset and B opened scrolled mid-list (or clamped to its end, when B was
    // shorter). Mobile never had this: its lists view is a different layout,
    // keyed on the filter alone. A return reads the same filter (and list) it
    // recorded under, so its own state is the one it scrolls (see
    // [BrowseReturnFocus]).
    val gridState = remember(state.filter, state.selectedList?.id) { LazyGridState() }
    var returnTarget by remember {
        mutableStateOf<BrowseReturnTarget?>(null)
    }
    val returnFocusRequester = remember { FocusRequester() }

    /**
     * The Library's entry landing: put the D-pad on the SELECTED filter chip,
     * and keep asking until it is actually there.
     *
     * This was the app's last bare one-shot entry ask, and its target makes the
     * miss likely rather than rare: the chip strip is a `LazyRow`, the selected
     * filter lives in the activity-scoped ViewModel, so re-entering Library on
     * WATCHLIST or LISTS could ask a chip that was outside the row's composed
     * window. An unattached requester answers false, once, with no retry and the
     * answer discarded, and nothing else on this screen re-asked - so the page
     * opened with nothing focused. Scrolling the strip to that chip first makes
     * the node real, and [landFocusOn] covers the frames between the scroll and
     * the attach. The same rule is used by the return's last resort above.
     */
    suspend fun landOnSelectedChip() {
        val index = LibraryFilter.entries.indexOf(state.filter)
        if (index >= 0) {
            runCatching { stripState.scrollToItem(index) }
        }
        landFocusOn(topFocusRequester)
    }
    // What the grid reports when it has finished with the record: the place is
    // spent either way, and a return this page could not put back (the poster
    // was removed while Detail was open) still owes the viewer a focus target,
    // which is the strip's first chip the ordinary open uses.
    val onReturnHandled: (Boolean) -> Unit = { landed ->
        returnTarget = null
        if (!landed) {
            // The same landing the ordinary open uses, not a bare ask: this is
            // the last-resort hand-back, and the chip it aims at can be outside
            // the strip's composed window exactly as it can on entry (see the
            // landing below).
            scope.launch {
                landOnSelectedChip()
            }
        }
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

    // The list whose long-press menu is open (null = no menu). The rail's long
    // press used to open straight onto a delete for a local list and do nothing
    // at all for the others; both are wrong now that a list can be renamed here
    // and deleted from the service that holds it, so the press opens a choice.
    var listMenu by remember { mutableStateOf<LibraryList?>(null) }
    // Bumped whenever a list is put on Home or taken off it, so the rail's own
    // "ON HOME" marker and the dialog's label re-read the pinned set without
    // waiting for a refresh of the lists themselves (which does not change).
    var homeRailRevision by remember { mutableIntStateOf(0) }
    // The pins as they stand. Both readers - the row's "· On Home" and the
    // menu's one toggle - look up EVERY spelling a list can be pinned under
    // (see pinsNamingList): a pin written as `id:<n>` before the row carried a
    // service id names the same list, and reading only today's spelling made
    // the row say it was not on Home while its rail sat on Home.
    val homeRailPins = remember(homeRailRevision, state.lists) {
        LibraryHomeRails.pins(context)
    }

    // A list waiting for its new name, and one waiting to be deleted from its
    // own source (or from this device, for a local list).
    var listPendingRename by remember { mutableStateOf<LibraryList?>(null) }
    var listPendingDelete by remember { mutableStateOf<LibraryList?>(null) }

    // A list write that failed, said out loud. A rename/delete is a remote
    // request behind a dialog that has already closed, so without this the
    // refusal is indistinguishable from success.
    val listFeedback = rememberKBFeedback()
    LaunchedEffect(state.listActionMessage) {
        val message = state.listActionMessage ?: return@LaunchedEffect
        listFeedback.show(message)
        viewModel.clearListActionMessage()
    }

    LaunchedEffect(Unit) {
        // The Home Upcoming header's shortcut: arrive on the Calendar pane with
        // focus in its grid, not on the chip. Consumed once, so a later visit
        // through the Library tab is the ordinary chip landing.
        if (LibraryCalendarEntry.consume()) {
            calendarTakeFocus = true
            viewModel.setFilter(LibraryFilter.CALENDAR)
            return@LaunchedEffect
        }

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
            landOnSelectedChip()
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

            // The permanent way into the year in review (spec 2026-11-13).
            // Home's card only exists in December and January, and a review is
            // the kind of thing someone opens in June; the header of the shelf
            // it summarises is where they will look for it.
            Surface(
                onClick = onOpenYearReview,
                shape = ClickableSurfaceDefaults.shape(shape = KBShapeCard),
                colors = ClickableSurfaceDefaults.colors(containerColor = KBSurface)
            ) {
                Text(
                    text = "YEAR IN REVIEW",
                    color = KBAccent,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                // Reading order: what the shelf always holds first, then the
                // trackers, then the read-only feeds. This names the sources;
                // it does not order them - which one wins a duplicate is the
                // ViewModel's merge, and it is untouched by this line.
                text = buildList {
                    add("LOCAL")
                    if (state.traktConnected) add("TRAKT")
                    if (state.simklConnected) add("SIMKL")
                    if (state.mdbListConfigured) add("MDBLIST")
                    // Connected AND mapped: BingeCat is read-only, and an
                    // unmapped profile contributes no rows by design, so
                    // naming it here would advertise a source with nothing
                    // behind it.
                    if (state.bingecatConnected) add("BINGECAT")
                }.joinToString(" + "),
                style = MaterialTheme.typography.labelMedium,
                color = KBTextLo,
                maxLines = 1,
                softWrap = false
            )
        }

        // Spec F2: a mapped profile whose session is gone is not "connected with
        // nothing in it". The header names the sources that answer; this line
        // names the one that needs the viewer, because the lists are not coming
        // back until they reconnect - and the pinned rails are waiting for them.
        if (state.bingecatNeedsReconnect) {
            Text(
                text = "BINGECAT NEEDS RECONNECTING - Settings > BingeCat. Your profile " +
                    "choice and pinned rails are kept.",
                style = MaterialTheme.typography.labelMedium,
                color = KBAccent,
                maxLines = 2
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Every control lives in ONE horizontally scrollable strip: focus walks
        // it left to right and the strip scrolls to follow, so no chip is ever
        // clipped at an edge and none has to wrap its label ("RATING" was
        // breaking into "RATI/NG" when the old fixed Row ran out of room). The
        // sort group is separated by a rule rather than pushed to its own line.
        LazyRow(
            state = stripState,
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
                    onClick = {
                        // The calendar's D-pad landing belongs to the Home
                        // shortcut only; a chip press is the ordinary
                        // entry-focus landing.
                        calendarTakeFocus = false
                        viewModel.setFilter(filter)
                    },
                    // The entry focus goes on the SELECTED chip, not always on
                    // ALL: the filter lives in the activity-scoped ViewModel, so
                    // re-entering Library on WATCHLIST or LISTS with focus on the
                    // ALL chip meant the first D-pad press undid the viewer's
                    // choice. The selected chip is composed whenever this row is.
                    modifier = if (filter == state.filter) {
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
                    // Four states, not two (see [libraryWatchlistEmptyText]):
                    // waiting on the remote half, a source that could not be
                    // reached, a source that was never connected, and a
                    // watchlist that really is empty. The old two collapsed
                    // all four into one finished-looking empty answer, so a
                    // failed fetch read as though the list were empty.
                    emptyText = libraryWatchlistEmptyText(
                        loading = state.loading,
                        remoteLoading = state.remoteLoading,
                        simklConnected = state.simklConnected,
                        mdbListConfigured = state.mdbListConfigured,
                        traktConnected = state.traktConnected,
                        failed = state.failedRemotes
                    ),
                    // The card draws its spinner while either half is still
                    // coming in: the local phase clears `loading` first, and
                    // the wait the copy names is the remote one.
                    loading = state.loading || state.remoteLoading,
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

            LibraryFilter.CALENDAR -> {
                LibraryCalendarPane(
                    onOpenDetails = { navigationId ->
                        onItemClick("series", navigationId)
                    },
                    takeInitialFocus = calendarTakeFocus
                )
            }

            LibraryFilter.LISTS -> {
                ListsPane(
                    lists = state.lists,
                    selectedList = state.selectedList,
                    listItems = state.selectedListItems,
                    loading = state.loading,
                    listLoading = state.listLoading,
                    onListSelect = { viewModel.selectList(it) },
                    onListLongPress = { list ->
                        // EVERY list row gets the menu now, not only the ones
                        // this app can rename: "Show on Home" is an answer
                        // about this viewer's Home and needs no write to the
                        // service, so a MDBList/Trakt/BingeCat list that cannot
                        // be renamed here can still become a Home rail. Rename
                        // and delete stay gated on canManageList (they are
                        // answered inside the dialog).
                        listMenu = list
                    },
                    homeRailPins = homeRailPins,
                    onCreateList = { source, name -> viewModel.createList(source, name) },
                    createSources = listOfNotNull(
                        LibrarySource.LOCAL_LIST,
                        LibrarySource.TRAKT_LIST.takeIf { state.traktConnected },
                        LibrarySource.MDBLIST_LIST.takeIf { state.mdbListConfigured },
                        LibrarySource.BINGECAT_LIST.takeIf { state.bingecatConnected }
                    ),
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
                // disposed, so there is nothing to hand the focus back to -
                // but the page still has to be told WHERE it is going, or the
                // way back lands on the filter chip.
                //
                // Only the grid's own press recorded a return target, and the
                // long-press menu is the other way into a Detail page from
                // here: Back out of one opened that way fell through to the
                // no-record fallback (the selected chip) while the poster
                // path came back to the card. Mobile's menu has recorded this
                // all along (see LibraryScreenMobile); this is the same
                // record, raised from the same row, with the position the row
                // holds in the grid the viewer is looking at.
                val itemKey = LocalLibraryStore.dedupeKey(item)
                val position = libraryGridRows(state).indexOfFirst {
                    LocalLibraryStore.dedupeKey(it) == itemKey
                }
                menuItem = null
                if (position >= 0) {
                    BrowseReturnFocus.record(
                        BrowseReturnTarget(
                            screenKey = LIBRARY_BROWSE_SCREEN_KEY,
                            itemId = itemKey,
                            position = position
                        )
                    )
                }
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

    listMenu?.let { list ->
        // One pin object for the row, so the label, the write and the rail's
        // marker all name the same list identity (see libraryHomeRailId).
        val pin: LibraryHomeRailPin? = libraryHomeRailPinFor(list)
        // Every pin that names this row, not just the spelling this build would
        // write today: a list pinned as `id:<n>` (before the row carried a
        // service id) or left behind under an older id reads as ON Home here, so
        // the toggle offers Remove instead of a second Add - the tap that drew
        // the second rail (spec F3).
        val pinsForRow = remember(homeRailRevision, list.id, list.source, list.remoteId, list.name) {
            pinsNamingList(
                LibraryHomeRails.pins(context),
                list.source.name,
                libraryHomeRailIdSpellings(list),
                list.name
            )
        }
        LibraryListActionsDialog(
            list = list,
            onHome = pinsForRow.isNotEmpty(),
            onDismiss = { listMenu = null },
            onToggleHome = {
                if (pin != null) {
                    if (pinsForRow.isEmpty()) {
                        LibraryHomeRails.setPinned(context, pin, true)
                    } else {
                        // Remove drops EVERY spelling, or the ghost stays.
                        LibraryHomeRails.removePins(context, pinsForRow + pin)
                    }
                    homeRailRevision++
                }
                listMenu = null
            },
            onRename = if (list.source.canManageList && !list.liked) {
                {
                    listMenu = null
                    listPendingRename = list
                }
            } else {
                null
            },
            onDelete = if (list.source.canManageList) {
                {
                    listMenu = null
                    listPendingDelete = list
                }
            } else {
                null
            }
        )
    }

    listPendingRename?.let { list ->
        LibraryRenameListDialog(
            list = list,
            onDismiss = { listPendingRename = null },
            onConfirm = { name ->
                listPendingRename = null
                viewModel.renameList(list, name)
            }
        )
    }

    listPendingDelete?.let { list ->
        LibraryDeleteListDialog(
            list = list,
            onDismiss = { listPendingDelete = null },
            onConfirm = {
                listPendingDelete = null
                if (list.source.isLocalList) {
                    viewModel.deleteLocalList(list.id)
                } else {
                    viewModel.deleteRemoteList(list)
                }
            }
        )
    }
    }
}

/**
 * The rows the poster grid is showing for [state]'s filter, in the grid's own
 * order.
 *
 * The screen composes one [ItemGrid] per filter, so "which list is this row
 * in" is a function of the filter and the open personal list. The long-press
 * menu needs it to record a return position that means something in the grid
 * the viewer comes back to.
 */
private fun libraryGridRows(state: LibraryUiState): List<LibraryItem> =
    when (state.filter) {
        LibraryFilter.ALL -> state.allItems
        LibraryFilter.MY_LIST -> state.localItems
        LibraryFilter.WATCHLIST -> state.watchlistItems
        LibraryFilter.LISTS -> state.selectedListItems
        // The calendar is a pane, not a grid of library rows.
        LibraryFilter.CALENDAR -> emptyList()
    }

/**
 * Long-press menu for one Library row, built on the app-wide
 * [PosterContextMenu] so a long press looks and behaves the same here as on
 * Home, Search and the detail rails. Every source's watchlist copy can be
 * dropped now: Simkl's un-save is its list-only inverse, not a history delete
 * (see SimklRepository.removeFromWatchlist), so a Simkl row offers the same
 * removal as the rest.
 */
@Composable
private fun LibraryItemMenu(
    item: LibraryItem,
    onDismiss: () -> Unit,
    onOpenDetails: (String) -> Unit,
    onRemove: () -> Unit
) {
    val context = LocalContext.current
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
                LibrarySource.MDBLIST_WATCHLIST,
                LibrarySource.TRAKT_WATCHLIST,
                // Step 3: a BingeCat watchlist row is a watchlist row, so the
                // same action drops it there too (see removeItem).
                LibrarySource.BINGECAT_WATCHLIST -> add(
                    PosterContextAction(
                        label = "Remove from Library",
                        description = "Remove it here and on every connected tracker",
                        isDestructive = true
                    ) {
                        onRemove()
                    }
                )

                LibrarySource.LOCAL_LIST,
                LibrarySource.MDBLIST_LIST,
                LibrarySource.TRAKT_LIST,
                // And a BingeCat user-list row leaves that list by its own ref.
                LibrarySource.BINGECAT_LIST -> add(
                    PosterContextAction(
                        label = "Remove from this list",
                        description = item.listName?.let { "Take it out of \"$it\"" },
                        isDestructive = true
                    ) {
                        onRemove()
                    }
                )

                // BingeCat's saved list (favorites) stays read-only, and no
                // BingeCat row has a web-link action: the long press offers
                // only the actions every row has (details, and the picker's).
                LibrarySource.BINGECAT_SAVED -> Unit

                // Simkl's watchlist is writable now (as `plantowatch`, and its
                // list-only inverse on a removal), so it is a normal removal row
                // rather than the old "Managed on Simkl" dead end. The Simkl
                // list bucket itself has no API to write to, so a SIMKL_LIST row
                // keeps the explanatory copy.
                LibrarySource.SIMKL_WATCHLIST -> add(
                    PosterContextAction(
                        label = "Remove from Simkl",
                        description = "Take it off your Simkl watchlist",
                        isDestructive = true
                    ) {
                        onRemove()
                    }
                )

                LibrarySource.SIMKL_LIST -> Unit

                // An anime list's rows are the service's: there is no
                // remove-from-list write for AniList, Kitsu or MAL, so the menu
                // offers the actions every row has (details, the picker) and no
                // removal that could not keep its promise.
                LibrarySource.ANILIST_LIST,
                LibrarySource.KITSU_LIST,
                LibrarySource.MAL_LIST -> Unit
            }

            // Simkl's API rules: wherever Simkl data appears, name Simkl and
            // link back to the page for THAT item - a bare link to simkl.com is
            // explicitly not attribution. The id came in with the read itself,
            // so this costs no request.
            if (
                item.source == LibrarySource.SIMKL_WATCHLIST ||
                item.source == LibrarySource.SIMKL_LIST
            ) {
                simklItemUrl(item.mediaType, item.simklId, item.simklSlug)?.let { url ->
                    add(
                        PosterContextAction(
                            label = "View on Simkl",
                            description = "Open this title's page on simkl.com"
                        ) {
                            try {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                )
                            } catch (_: ActivityNotFoundException) {
                                // No browser on this box: the row is a link out,
                                // so there is nothing else to do.
                            }
                            onDismiss()
                        }
                    )
                }
            }
        },
        onDismiss = onDismiss
    )
}

/**
 * The rail's chip name for one create destination.
 *
 * The wording itself lives in [ListControlWording], which the phone's create
 * field reads too - the two surfaces must not name a destination differently.
 * The name stays here so the dialog below reads as it always has.
 */
private fun createSourceLabel(source: LibrarySource): String =
    ListControlWording.createSourceLabel(source)

/**
 * What can be done to one list, asked before anything happens.
 *
 * Every row names its own effect: SHOW ON HOME / HIDE FROM HOME (a Home row
 * this device draws, which needs no write to the service at all - the one
 * action available on every list), RENAME (the list keeps its titles), and the
 * delete, whose wording names WHERE it happens - "REMOVE FROM THIS DEVICE"
 * for a local list, "DELETE FROM TRAKT" and friends for a remote one. The
 * second is the distinction this dialog exists for: the same press used to mean
 * "hide it here" while a service copy survived, and a whole-list delete on a
 * service has no undo.
 *
 * CANCEL is drawn first and takes the focus, the same rule the delete confirm
 * follows, so the safe choice is the default stop.
 */
@Composable
private fun LibraryListActionsDialog(
    list: LibraryList,
    /** Whether this list is already a row on Home (the label answers the press). */
    onHome: Boolean,
    onDismiss: () -> Unit,
    onToggleHome: () -> Unit,
    /** Null for a source whose lists this app cannot rename (renames are the service's). */
    onRename: (() -> Unit)?,
    /** Null for the same reason as [onRename] - or when deleting is not offered at all. */
    onDelete: (() -> Unit)?
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
        // The subtitle names the owner for a saved community list: the dialog's
        // actions are about SOMEONE ELSE'S list, and the row it was opened from
        // is a like (see ListControlWording).
        KBDialogPanel(title = list.name, subtitle = list.controlAttribution) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                // The one action every list has, whatever service holds it:
                // the rail is Home's arrangement, not the service's, so
                // nothing has to be writable anywhere for a list to become a
                // row on Home - which is what makes a read-only watchlist list
                // and an account's own list behave the same here.
                KBCard(onClick = onToggleHome) {
                    Text(
                        text = if (onHome) "HIDE FROM HOME" else "SHOW ON HOME",
                        color = KBTextHi,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
                onRename?.let { rename ->
                    KBCard(onClick = rename) {
                        Text(
                            text = "RENAME",
                            color = KBTextHi,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }
                }
                // A saved community list has no rename row, and the dialog says
                // why instead of leaving a gap the viewer has to guess at.
                list.renameUnavailableNote?.let { note ->
                    Text(
                        text = note,
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
                onDelete?.let { delete ->
                    KBCard(onClick = delete) {
                        Text(
                            // REMOVE for a saved community list: the press drops
                            // the like, not the list (spec F4).
                            text = list.removeActionLabel,
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
}

/**
 * The name-entry step of a rename, seeded with the list's current name.
 *
 * The field is not auto-focused: on a TV, focusing a text field pops the
 * keyboard over the dialog before the viewer has said they want to type. DONE
 * on the keyboard and SAVE both commit; a blank name (or the unchanged one) is
 * refused here rather than sent to the source as a no-op write.
 */
@Composable
private fun LibraryRenameListDialog(
    list: LibraryList,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember(list.id) { mutableStateOf(list.name) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        KBDialogPanel(title = "Rename list", subtitle = list.locationLabel) {
            KBTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = "List name",
                modifier = Modifier.fillMaxWidth(),
                onDone = {
                    if (name.isNotBlank() && name.trim() != list.name) {
                        onConfirm(name)
                    }
                }
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                KBCard(onClick = onDismiss) {
                    Text(
                        text = "CANCEL",
                        color = KBTextLo,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
                KBCard(
                    onClick = {
                        if (name.isNotBlank() && name.trim() != list.name) {
                            onConfirm(name)
                        }
                    }
                ) {
                    Text(
                        text = "SAVE",
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

/**
 * Confirms deleting one local list.
 *
 * The list rail's long press opens this (through
 * [LibraryListActionsDialog]) instead of deleting on the spot, and this dialog
 * names where the delete happens: a local list leaves THIS DEVICE, while a
 * Trakt/MDBList/BingeCat list leaves that account, where there is no undo. The
 * wording is the whole point of the second sentence - "Delete list?" was true
 * of both and described neither.
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
        KBDialogPanel(
            title = list.removeConfirmTitle,
            // The question names the action; the line under it still names the
            // list, which is what the viewer is confirming about.
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
                        text = list.removeConfirmAction,
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
 * One pill in the Library's control strips, and the Rated Titles pane's filter
 * row (hence `internal`: the settings surface draws the same chip rather than a
 * second, drifting copy of it).
 *
 * Built on the TV clickable Surface, NOT a hand-rolled
 * `.focusable().clickable()` box. Two stacked focus/click modifiers make two
 * targets: the D-pad press landed focus on the outer one and only the second
 * press reached the inner clickable — the "you have to double click chips"
 * behavior. A Surface carries focus, activation and the focused colors as a
 * single target, so one press selects, like every other control in the app.
 */
@Composable
internal fun LibraryFilterChip(
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
            },
            // The FOCUSED colors have to be stated too, not inferred from the
            // `containerColor` above: tv-material3 defaults a focused surface
            // to the theme's `primary`, and KBStream's primary is the accent -
            // so focusing a chip painted it solid lime under a near-white label
            // and the label vanished into it (the "hard to read on hover"
            // report). Focus is carried by the accent border and the glow on
            // every other row in the app; the fill stays a raised surface.
            //
            // A SELECTED chip deepens its wash a step instead of flattening
            // into one accent, because the wash is what says "this is the
            // filter that is on" while the D-pad sits on it.
            focusedContainerColor = if (selected) {
                KBAccent.copy(alpha = 0.4f)
            } else {
                KBSurfaceRaised
            },
            // The LABEL never takes the accent on focus, selected or not: it
            // is accent text on an accent wash the moment a selected chip is
            // focused, and the pill reads as one solid bright block with the
            // word gone (the focused WATCHLIST chip, 2026-11-05). Selection is
            // carried by the fill tint, the accent border and the bold label -
            // never by the label's color - so the focused label is the same
            // light tone every other focused surface uses. The unfocused
            // selected label stays accent: that is what the strip reads as
            // "selected" when focus is elsewhere (see GenreChipRow, which
            // learned this exact lesson).
            focusedContentColor = KBTextHi
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
            key = { _, item -> LocalLibraryStore.dedupeKey(item) },
            contentType = { _, _ -> "library" }
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
                // Both id flavors the enrichment can file a row under: the
                // IMDB one, then the raw `tmdb:<n>` one a row with no IMDB id
                // (or no resolution) is watched under (see
                // [LibraryItem.watchedKeyCandidates]).
                isWatched = item.watchedKeyCandidates().any { it in watchedKeys },
                isPartiallyWatched = item.watchedKeyCandidates().any { it in partiallyWatchedKeys },
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
                    // A removal or a sort no longer teleports the grid: the
                    // cell fades out where it was and its neighbours close the
                    // gap by placement. The focus requester stays OUTSIDE this
                    // - the hand-back asks a node by key, and an item that is
                    // mid-placement is still the same node.
                    .animateItem()
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
    /**
     * True while the OPEN list's rows are in flight (spec F2). The pane's rows
     * are cleared on a switch, so without this it fell through to "This list is
     * empty" for the whole fetch - a sentence about the wrong thing.
     */
    listLoading: Boolean,
    onListSelect: (LibraryList) -> Unit,
    onListLongPress: (LibraryList) -> Unit,
    /**
     * The pins as they stand, for the row's "· On Home" marker - read through
     * `pinsNamingList` so every spelling of a list's pin counts.
     */
    homeRailPins: List<LibraryHomeRailPin>,
    onCreateList: (LibrarySource, String) -> Unit,
    /**
     * The sources a list can be created on, in the order the chips show them.
     * Local always; Trakt/MDBList/BingeCat only while connected, because a
     * chip that would create nothing is a promise the screen cannot keep.
     * Simkl is never here - its API has no custom lists.
     */
    createSources: List<LibrarySource>,
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
    // Where the field's CREATE goes. Local is the default because it is the one
    // source that always exists, so a viewer with no account connected sees
    // exactly the field they always had.
    var createSource by remember { mutableStateOf(LibrarySource.LOCAL_LIST) }

    Row(modifier = Modifier.fillMaxSize()) {
        RailListColumn(
            // Room at both ends, INSIDE the clip, for a focused row's grown
            // edge and glow: at scroll 0 the first row's drawn top is
            // `padding - overhang`, and no landing can give it that room - the
            // list is already at its start. The bottom is the landing margin
            // plus one row gap, because the landing can only bring the LAST row
            // (the create affordance, taller still with its field open) to that
            // margin if the list can scroll that far past it.
            //
            // KBFocusRowInset (16dp) is the theme's computed row room: the
            // 12dp glow the shared row cue draws plus the ~1% of its own height
            // KBFocusRow grows a row by per side. These rows are shorter than
            // that bound - a 60dp list row overhangs by ~9dp at this rail's 8dp
            // glow, and the 130dp create field by ~10dp - so 16dp clears both
            // with room to spare, and 12dp (the floor) would not.
            contentPadding = PaddingValues(
                top = KBFocusRowInset,
                bottom = KBFocusRowInset + 8.dp
            ),
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
            // Keyed on the list's own identity, not on its LibraryList.id: two
            // shelves can share that one number, and a key used twice is a
            // fatal crash in a lazy list (ANDROID-T). See libraryListRowKey.
            items(lists, key = { list -> libraryListRowKey(list) }) { list ->
                var focused by remember { mutableStateOf(false) }
                // F3b: by the list's IDENTITY, not its id - a BingeCat row's id
                // is its slot in the snapshot, so a stale selection after a
                // delete used to highlight the row that shifted into that slot.
                val selected = list.sameListAs(selectedList)
                // The manage gesture: rename and delete for a list this app
                // can actually change - a local list, or one of the accounts'
                // own managed lists. A source with neither (Simkl) gets no
                // press rather than a menu of refusals.
                // Always present: the dialog holds "Show on Home" for every
                // source, and gates rename/delete itself on canManageList.
                val onRowLongPress: (() -> Unit) = { onListLongPress(list) }
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
                        contentColor = KBTextHi,
                        // Stated, not inherited: the tv-material3 default for a
                        // focused surface is the theme's `primary`, which here
                        // is the accent - so a focused list row filled lime and
                        // its title (near-white) disappeared, leaving only the
                        // grey count line readable on top of it. This rail was
                        // the one surface in the app that left that default in
                        // place; every other row carries focus with the accent
                        // border and glow over a raised fill.
                        focusedContainerColor = if (selected) {
                            KBAccent.copy(alpha = 0.34f)
                        } else {
                            KBSurfaceRaised
                        },
                        focusedContentColor = KBTextHi
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
                            // Never the accent: a selected row is already an
                            // accent wash (34% under the D-pad), so an accent
                            // title was one colour on one colour - the focused
                            // first row whose photo showed only its count line
                            // ("50 titles · BingeCat", 2026-11-05). Selection is
                            // carried by the wash, the accent focus border and
                            // the bold weight; the title is the same light tone
                            // in every state, exactly like the chips' labels.
                            color = KBTextHi,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val onHome = pinsNamingList(
                            homeRailPins,
                            list.source.name,
                            libraryHomeRailIdSpellings(list),
                            list.name
                        ).isNotEmpty()
                        Text(
                            // Count and source from the one wording module, which
                            // names the OWNER of a saved community list as well as
                            // its vendor (spec F5): the row sits among the viewer's
                            // own lists, and an unowned line would read as one.
                            text = list.controlSummary +
                                if (onHome) " · On Home" else "",
                            style = MaterialTheme.typography.labelSmall,
                            // Brighter while the row is the one under the remote:
                            // the count line is the line the photo showed
                            // surviving on the old lime fill, so it must not be
                            // the OTHER line that goes dim now that the fill is
                            // dark again.
                            color = if (focused || selected) {
                                KBTextHi.copy(alpha = 0.82f)
                            } else {
                                KBTextLo
                            }
                        )
                    }
                }
            }

            // Create-list affordance at the bottom of the rail.
            item(key = "create_list") {
                if (showCreateField) {
                    Column {
                        // Which account the new list goes on. Drawn above the
                        // field so the destination is chosen before the name is
                        // typed; a single-source rail (local only) draws nothing
                        // extra and behaves exactly as it always did.
                        if (createSources.size > 1) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                createSources.forEach { source ->
                                    LibraryFilterChip(
                                        label = createSourceLabel(source),
                                        selected = createSource == source,
                                        onClick = { createSource = source }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        KBTextField(
                            value = newListName,
                            onValueChange = { newListName = it },
                            placeholder = "New list name",
                            modifier = Modifier.fillMaxWidth(),
                            onDone = {
                                if (newListName.isNotBlank()) {
                                    onCreateList(createSource, newListName)
                                }
                                newListName = ""
                                showCreateField = false
                            }
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        LibraryFilterChip(
                            label = "CREATE",
                            selected = false,
                            // A blank name is disabled rather than refused after
                            // the press: the same rule the picker's create row
                            // follows.
                            onClick = {
                                if (newListName.isNotBlank()) {
                                    onCreateList(createSource, newListName)
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
                // m5: with no lists at all there is nothing to select, so "Select
                // a list on the left" pointed at an empty rail and named no way
                // out. The only path forward is the + NEW LIST chip above it.
                selectedList == null && lists.isEmpty() ->
                    "No lists yet. Create one with + NEW LIST on the left."
                selectedList == null -> "Select a list on the left."
                // F2: BEFORE the empty sentence, or a switch reads as "this
                // list is empty" while its rows are still arriving.
                listLoading -> "Loading list…"
                else -> "This list is empty. Long-press any poster app-wide " +
                    "and choose \"Add to list…\" to fill it."
            },
            // The wait the copy names is the list's own, so the status plate
            // draws the spinner for it.
            loading = loading || listLoading,
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

/**
 * The personal-lists rail's column: a [LazyColumn] that lands a focused row with
 * the room its focus cue needs on both sides (see
 * [SportsCardBringIntoViewSpec]).
 *
 * The default landing reads the focused row's LAYOUT rectangle and counts it
 * visible the moment it fits the viewport, so a row the D-pad scrolls to came to
 * rest with its grown edge and its glow sheared flat along the column's clip -
 * the first row's top, and the + NEW LIST affordance's whole bottom edge that
 * the photo showed half-cut (2026-11-05). `contentPadding` cannot fix that on
 * its own: it sits inside the clip, and only a bring-into-view spec is handed
 * the viewport to inset. The same switch Home's rail column, the sports hub and
 * the detail screen throw - supplied around the list, so the poster grid beside
 * it keeps its own landing.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RailListColumn(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit
) {
    val density = LocalDensity.current
    val rowBringIntoViewSpec = remember(density) {
        SportsCardBringIntoViewSpec(
            insetPx = with(density) { KBFocusRowInset.toPx() },
            // This rail's rows carry the small glow, not the shared card's -
            // the landing has to account for what is actually drawn past the
            // layout rect.
            glowPx = with(density) { KBFocusGlowSmall.toPx() },
            // Rail rows grow by the row step of the shared focus scale, and the
            // scale pivots at the centre: half the growth lands on each edge.
            focusedScale = KBFocusRow
        )
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides rowBringIntoViewSpec) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = contentPadding,
            modifier = modifier,
            content = content
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
