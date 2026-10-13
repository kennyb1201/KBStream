package com.kennyb1201.kbstream.ui.kb

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.kennyb1201.kbstream.ui.browse.BROWSE_RETURN_FOCUS_STEPS
import com.kennyb1201.kbstream.ui.browse.BrowseReturnFocus
import com.kennyb1201.kbstream.ui.browse.BrowseReturnTarget
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
    var showAsGrid by remember(folderId) { mutableStateOf(false) }

    // Everything a return out of Detail needs, owned HERE rather than in the
    // layout below: that trip disposes this whole composable and every scroll
    // position in it, which is why the folder used to reopen at the top with
    // the D-pad on whatever the framework picked (see [BrowseReturnFocus]).
    val browseReturnKey = remember(folderId) { kbFolderBrowseScreenKey(folderId) }
    val browseListState = rememberLazyListState()
    val browseGridState = rememberLazyGridState()
    val returnFocusRequester = remember { FocusRequester() }
    var returnTarget by remember(folderId) {
        mutableStateOf<BrowseReturnTarget?>(null)
    }
    var returnAttempts by remember(folderId) { mutableStateOf(0) }
    // Whether the card this page came back for has been landed on. Set by the
    // landing itself, never before it: the rails stream in one at a time, and a
    // flag raised ahead of a request that never landed would leave the rest of
    // the page's arrivals skipping the restore.
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

    LaunchedEffect(folderId) {
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
            return@LaunchedEffect
        }
        if (returnHandled) return@LaunchedEffect
        val recorded = returnTarget ?: BrowseReturnFocus.consume(browseReturnKey)
            ?: return@LaunchedEffect
        if (returnAttempts >= BROWSE_RETURN_FOCUS_STEPS) return@LaunchedEffect
        returnTarget = recorded
        returnAttempts++
        // The rail has to be on screen before its own row can scroll onto the
        // card, and a rail outside the viewport has composed nothing at all.
        recorded.railKey?.let { railKey ->
            browseListState.revealBrowseReturnRail(railKey, recorded.railIndex)
        }
        // A request against a card that is not composed yet is a silent miss
        // (requestFocus() answers false rather than throwing), and the rails
        // and the grid scroll themselves onto the card from the record they
        // are handed above.
        returnHandled = landFocusOn(returnFocusRequester)
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
        val menuWatched = item.id.takeIf { it.startsWith("tt") }?.let { imdbId ->
            viewModel.watchedKey(imdbId, normalizedType) in watchedKeys
        } ?: item.tmdbId?.let { tmdbId ->
            val imdbId = resolvedIds["$normalizedType::$tmdbId"]
            imdbId != null && viewModel.watchedKey(imdbId, normalizedType) in watchedKeys
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
        } else {
            onBack()
        }
    }
}
