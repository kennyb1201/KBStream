package com.kennyb1201.kbstream.ui.kb

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kennyb1201.kbstream.data.kb.KBContentItem
import com.kennyb1201.kbstream.data.library.LibraryIds
import com.kennyb1201.kbstream.data.watched.WatchedStatusKeys
import com.kennyb1201.kbstream.ui.browse.BrowseReturnDecision
import com.kennyb1201.kbstream.ui.browse.BrowseReturnFocus
import com.kennyb1201.kbstream.ui.browse.BrowseReturnTarget
import com.kennyb1201.kbstream.ui.browse.browseReturnDecision
import com.kennyb1201.kbstream.ui.browse.kbFolderBrowseScreenKey
import com.kennyb1201.kbstream.ui.browse.landFocusOn
import com.kennyb1201.kbstream.ui.browse.revealBrowseReturnRail
import com.kennyb1201.kbstream.ui.components.LibraryAddTarget
import com.kennyb1201.kbstream.ui.components.PosterContextMenu
import com.kennyb1201.kbstream.ui.components.watchedMenuLabel
import com.kennyb1201.kbstream.ui.components.watchedMenuDescription
import com.kennyb1201.kbstream.ui.components.hideTarget
import com.kennyb1201.kbstream.ui.components.PosterContextAction
import com.kennyb1201.kbstream.ui.theme.KBVoid

/**
 * One imported KB folder. Layout comes from the folder's viewMode:
 *  - FOLLOW_LAYOUT (default): hero + rails visually identical to Home —
 *    same clearlogo, gradients, metadata line, inline trailers, and the
 *    global Landscape Cards / Show Catalog Type toggles
 *  - ROWS: plain rail list, no hero
 *  - GRID: poster grid of the merged items
 */
@Composable
fun KBFolderScreen(
    folderId: String,
    onBack: () -> Unit,
    onItemClick: (
        type: String,
        imdbId: String,
        poster: String?,
        backdrop: String?,
        title: String?
    ) -> Unit,
    viewModel: KBFolderViewModel = viewModel()
) {
    // The phone entry: the same ViewModel, folder load and navigation calls,
    // drawn as the collection's poster grid. One branch; the TV page below is
    // unchanged (see KBFolderScreenMobile).
    if (!com.kennyb1201.kbstream.FormFactor.isTv) {
        com.kennyb1201.kbstream.ui.mobile.KBFolderScreenMobile(
            folderId = folderId,
            // Same pop as system back: the folder's returnTo.
            onBack = onBack,
            onItemClick = onItemClick,
            viewModel = viewModel
        )
        return
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val resolvedIds by viewModel.resolvedIds.collectAsStateWithLifecycle()
    val selectedSourceId by viewModel.selectedSourceId.collectAsStateWithLifecycle()
    val watchedKeys by viewModel.watchedKeys.collectAsStateWithLifecycle()
    val partialWatchedKeys by viewModel.partialWatchedKeys.collectAsStateWithLifecycle()

    var menuTarget by remember { mutableStateOf<KBContentItem?>(null) }

    // Long-press "Open in Grid": force the folder into its GRID layout for
    // this visit, whatever its manifest viewMode says. Back returns to the
    // folder's own layout first, then exits — same feel as Home's
    // "Open in Grid" returning to the rails.
    //
    // Seeded from the ViewModel, which outlives the trip into a title's Detail
    // page: this composable does not, so a `remember` alone lost the override on
    // the way back - the folder came back in its manifest layout, and the grid's
    // own return record (a card and a position, no rail) had no rail layout that
    // could put the D-pad back on it (see [KBFolderViewModel.setGridOverride]).
    var showAsGrid by remember(folderId) {
        mutableStateOf(viewModel.gridOverrideActive(folderId))
    }

    // Everything a return out of Detail needs, owned HERE rather than in the
    // layout below: that trip disposes this whole composable and every scroll
    // position in it, which is why the folder used to reopen at the top with
    // the D-pad on whatever the framework picked (see [BrowseReturnFocus]).
    val browseReturnKey = remember(folderId) { kbFolderBrowseScreenKey(folderId) }
    // Keyed on the folder like every neighbour here (the return state, the
    // records, the card registry): the plain `rememberLazy*State()` survived a
    // route-argument swap IN PLACE - a folder opened from inside a folder - so
    // the incoming folder inherited the outgoing one's scroll position. Same
    // shape as the Library grid and BrowseBrowser, both of which key theirs.
    val browseListState = remember(folderId) { LazyListState() }
    val browseGridState = remember(folderId) { LazyGridState() }
    val returnFocusRequester = remember { FocusRequester() }
    // Where an ordinary open puts the D-pad: the page's own first card. The
    // folder used to place no entry focus at all, which left the first D-pad
    // press to the framework's own traversal - with the hero and the source
    // chips just as reachable as the first poster is. Every sibling browse page
    // pins its own first card the same way (see TagScreen's focusFirstCard).
    val entryFocusRequester = remember { FocusRequester() }
    var returnTarget by remember(folderId) {
        mutableStateOf<BrowseReturnTarget?>(null)
    }
    var returnAttempts by remember(folderId) { mutableStateOf(0) }
    // When the held record was first waited for, so a rail whose load failed
    // hands the D-pad back to the first card instead of holding it for ever
    // (see [BROWSE_RETURN_WAIT_MS]).
    var returnWaitStartMs by remember(folderId) { mutableStateOf(0L) }
    // Whether this page's focus has been placed - on the card a return came back
    // for, or on the page's own first card for an ordinary open. Set by the
    // landing itself, never before it: the rails stream in one at a time, and a
    // flag raised ahead of a request that never landed would leave the rest of
    // the page's arrivals skipping the landing.
    var returnHandled by remember(folderId) { mutableStateOf(false) }

    // Where the open long-press menu hands the D-pad back. Every other surface
    // stashes the pressed card's requester and restores it; this one follows
    // the Library instead, because a Hide takes the card off the rail - so the
    // SUCCESSOR's key is captured while the pressed card is still there to name
    // it, and resolved when the menu closes.
    val cardFocusRequesters = remember(folderId) {
        mutableMapOf<String, FocusRequester>()
    }
    var menuSuccessorKey by remember { mutableStateOf<String?>(null) }
    var menuReturnTarget by remember {
        mutableStateOf<BrowseReturnTarget?>(null)
    }
    var menuRestoreKey by remember { mutableStateOf<String?>(null) }
    var restoreFocusSignal by remember { mutableStateOf(0) }
    // Set by the Hide row on its way through [PosterContextMenu.onBeforeHide],
    // so the dismiss that follows it knows the pressed card is leaving rather
    // than staying.
    var menuHidePending by remember { mutableStateOf(false) }

    fun closeMenu(restoreKey: String?) {
        menuTarget = null
        menuSuccessorKey = null
        menuReturnTarget = null
        menuHidePending = false
        menuRestoreKey = restoreKey
        restoreFocusSignal++
    }

    LaunchedEffect(restoreFocusSignal) {
        if (restoreFocusSignal == 0) return@LaunchedEffect
        val key = menuRestoreKey
        menuRestoreKey = null
        // A dismiss owes the D-pad the card that was pressed; a Hide owes it
        // the card that took its place, which is the only one still on the
        // rail by the time this runs. A null key (the rail's last card went)
        // leaves the focus where the framework puts it rather than nowhere.
        landFocusOn(key?.let { cardFocusRequesters[it] })
    }

    val profileEpoch by viewModel.profileEpoch.collectAsStateWithLifecycle()

    // Keyed on the switch epoch as well as the route: the picker always lands
    // Home (so a switch disposes this page and this effect is an ordinary
    // entry), but a switch that arrives while the page is open drops the
    // folder - see [KBFolderViewModel.profileEpoch] - and this is what loads it
    // again, against the incoming profile.
    LaunchedEffect(folderId, profileEpoch) {
        viewModel.loadById(folderId)
    }

    // Puts the page back where the viewer left it when its Detail page was
    // opened from here: the rail on screen, its row scrolled onto the card,
    // and the focus on that card. The rails arrive one at a time, so the
    // record is HELD until it is honoured - the rail holding the card can land
    // after the first one does, and a record read and then dropped would lose
    // the place it was raised for (see [BrowseReturnFocus]).
    LaunchedEffect(state.isLoading, state.rails, selectedSourceId) {
        if (state.isLoading) {
            // A new load is a new list: a position in the old one means
            // nothing.
            returnHandled = false
            returnTarget = null
            returnAttempts = 0
            returnWaitStartMs = 0L
            return@LaunchedEffect
        }
        if (returnHandled) return@LaunchedEffect
        // The wait starts when the record is read, not when a rail arrives: it
        // is what bounds holding the record at all.
        val recorded = returnTarget
            ?: BrowseReturnFocus.consume(browseReturnKey)
                .also { if (it != null) returnWaitStartMs = System.currentTimeMillis() }

        // An ordinary open - no record waiting for this page - takes the page's
        // own first card. A landing that missed leaves the flag false and the
        // next rail that streams in asks again; a landing that succeeded leaves
        // it true, so later arrivals never pull the D-pad back off the card the
        // viewer has moved to.
        if (recorded == null) {
            returnHandled = landFocusOn(entryFocusRequester)
            return@LaunchedEffect
        }

        returnTarget = recorded
        when (
            browseReturnDecision(
                recorded = recorded,
                arrivedRailKeys = state.rails.map { "rail:${it.sourceId}" }.toSet(),
                attemptsSpent = returnAttempts,
                waitStartMs = returnWaitStartMs,
                nowMs = System.currentTimeMillis()
            )
        ) {
            // The rail holding the card has not landed yet. Hold the record and
            // spend nothing: an arrival is not a landing try, and charging one
            // for it is what ran this page's budget out on its other rails (see
            // [browseReturnDecision]).
            BrowseReturnDecision.WAIT_FOR_RAIL -> return@LaunchedEffect

            BrowseReturnDecision.TRY_LANDING -> {
                returnAttempts++
                // The rail has to be on screen before its own row can scroll onto
                // the card, and a rail outside the viewport has composed nothing
                // at all.
                recorded.railKey?.let { railKey ->
                    browseListState.revealBrowseReturnRail(railKey, recorded.railIndex)
                }
                // A request against a card that is not composed yet is a silent
                // miss (requestFocus() answers false rather than throwing), and
                // the rails and the grid scroll themselves onto the card from
                // the record they are handed above.
                returnHandled = landFocusOn(returnFocusRequester)
                // The record is SPENT once the landing succeeded. Left in state
                // it is handed on to the layouts, whose scroll effects key on it
                // AND on the item list itself - so a Hide (the hidden title
                // leaves the list, changing that key) or a source-tab switch
                // re-ran the scroll straight back onto the card the viewer had
                // just left, fighting the successor-key restore the card
                // registry had resolved for the menu. A landing that did NOT
                // succeed keeps it: the retry budget is still spending it.
                if (returnHandled) returnTarget = null
            }

            // The recorded card could never be put back (its rail never
            // arrived, or the landing budget ran out), so the page falls back to
            // its own first card rather than leaving the D-pad wherever the
            // framework dropped it.
            BrowseReturnDecision.FIRST_CARD -> {
                returnHandled = landFocusOn(entryFocusRequester)
            }
        }
    }

    val folder = state.folder
    val layoutMode = if (showAsGrid) {
        KBLayoutModes.Mode.GRID
    } else {
        KBLayoutModes.fromViewMode(folder?.viewMode)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid)
    ) {
        KBFolderBody(
            layoutMode = layoutMode,
            viewModel = viewModel,
            state = state,
            resolvedIds = resolvedIds,
            selectedSourceId = selectedSourceId,
            watchedKeys = watchedKeys,
            partialWatchedKeys = partialWatchedKeys,
            onSelectSource = viewModel::selectSource,
            browseReturnKey = browseReturnKey,
            listState = browseListState,
            gridState = browseGridState,
            focusRegistry = cardFocusRequesters,
            returnTarget = returnTarget,
            returnFocusRequester = returnFocusRequester,
            entryFocusRequester = entryFocusRequester,
            onOpenItem = { item, target ->
                // Remember this card, so backing out of its Detail page reopens
                // the folder ON it (see [BrowseReturnFocus]).
                BrowseReturnFocus.record(target)
                viewModel.resolveAndNavigate(item) { type, imdbId ->
                    onItemClick(
                        type,
                        imdbId,
                        item.posterUrl,
                        item.backdropUrl,
                        item.title
                    )
                }
            },
            onLongPressItem = { item, successorKey, target ->
                menuTarget = item
                menuSuccessorKey = successorKey
                menuReturnTarget = target
            },
            // The error card's RETRY, re-running the load by id: the folder may
            // not have resolved at all, in which case only the route's own
            // resolve can answer (see KBFolderViewModel.retry).
            onRetryFolder = { viewModel.retry(folderId) }
        )
    }

    menuTarget?.let { item ->
        // Same watched-key normalization the rails use: TMDB items resolve
        // through the ViewModel's tmdbId -> imdb id map, addon items key on
        // their own imdb id.
        val normalizedType = when (item.type.lowercase()) {
            "series", "tv" -> "series"
            else -> "movie"
        }
        val menuIds = LibraryIds.split(item.id)
        // Add-on items key on their own imdb id; a TMDB item checks the
        // resolved imdb flavor first, then its own `tmdb:<n>` one - the key it
        // has when the capped resolution pass never reached it (see
        // [WatchedStatusKeys]) - so the toggle matches the poster's badge.
        val menuWatched = item.id.takeIf { it.startsWith("tt") }?.let { imdbId ->
            viewModel.watchedKey(imdbId, normalizedType) in watchedKeys
        } ?: item.tmdbId?.let { tmdbId ->
            val imdbId = resolvedIds["$normalizedType::$tmdbId"]
            (imdbId != null && viewModel.watchedKey(imdbId, normalizedType) in watchedKeys) ||
                viewModel.watchedKey(
                    WatchedStatusKeys.tmdbKeyId(tmdbId),
                    normalizedType
                ) in watchedKeys
        } ?: false
        // The card the menu was raised on, by the key it is registered under
        // (see [kbFolderCardKey]).
        val menuCardKey = kbFolderCardKey(item)
        PosterContextMenu(
            title = item.title ?: "Untitled",
            // A KB row carries whatever its add-on sent: an IMDB id, a
            // "tmdb:123" id, or a stream key with the title id in its head
            // (LibraryIds strips that). When the head is a TMDB id, the one
            // this screen already resolved for the watched badge supplies the
            // IMDB half, so the add reaches the trackers.
            libraryTarget = LibraryAddTarget(
                mediaType = normalizedType,
                imdbId = menuIds.imdbId
                    ?: item.tmdbId?.let { resolvedIds["$normalizedType::$it"] },
                tmdbId = menuIds.tmdbId ?: item.tmdbId,
                title = item.title ?: "Untitled",
                year = item.year?.take(4)?.toIntOrNull(),
                posterUrl = item.posterUrl
            ),
            hideTarget = hideTarget(
                item.title ?: "Untitled",
                item.type,
                item.posterUrl,
                listOf(item.id, item.tmdbId?.toString())
            ),
            actions = listOf(
                PosterContextAction(
                    label = "Open in Grid",
                    description = "Browse this whole collection as a poster grid"
                ) {
                    // Nothing is leaving the rail here, so the D-pad goes back
                    // on the card that was pressed.
                    closeMenu(menuCardKey)
                    showAsGrid = true
                    // Recorded outside the composition, so the trip into a title's
                    // Detail page comes back to the grid (see the declaration).
                    viewModel.setGridOverride(folderId)
                },
                PosterContextAction(
                    label = "Go to Details",
                    description = "Open this title's detail page"
                ) {
                    // Where this menu's own drill-down is being opened from, so
                    // backing out of that Detail lands here rather than at the
                    // top (see [BrowseReturnFocus]). Raised BEFORE the menu
                    // state is cleared, which is where the record lives.
                    menuReturnTarget?.let { BrowseReturnFocus.record(it) }
                    closeMenu(null)
                    viewModel.resolveAndNavigate(item) { type, imdbId ->
                        onItemClick(
                            type,
                            imdbId,
                            item.posterUrl,
                            item.backdropUrl,
                            item.title
                        )
                    }
                },
                PosterContextAction(
                    label = watchedMenuLabel(
                        isWatched = menuWatched,
                        mediaType = item.type
                    ),
                    description = watchedMenuDescription(
                        isWatched = menuWatched,
                        mediaType = item.type
                    )
                ) {
                    closeMenu(menuCardKey)
                    if (menuWatched) {
                        viewModel.markUnwatched(item)
                    } else {
                        viewModel.markAsWatched(item)
                    }
                }
            ),
            // The Hide row announces itself here, before it removes anything:
            // the rail is filtered by hidden titles, so the pressed card is
            // gone by the next frame and the card owed the D-pad is the
            // successor named while the pressed one was still there.
            onBeforeHide = { menuHidePending = true },
            onDismiss = {
                val wasHidden = menuHidePending
                closeMenu(if (wasHidden) menuSuccessorKey else menuCardKey)
            }
        )
    }

    BackHandler {
        // Grid override is a visit-level view: Back first restores the
        // folder's own layout, and only a second Back exits the folder.
        if (showAsGrid) {
            showAsGrid = false
            // Cleared with it, so the folder is not still pinned to the grid on
            // its next open (see [KBFolderViewModel.setGridOverride]).
            viewModel.setGridOverride(null)
        } else {
            onBack()
        }
    }
}
