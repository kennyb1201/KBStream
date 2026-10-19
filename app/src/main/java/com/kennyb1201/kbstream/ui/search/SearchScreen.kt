package com.kennyb1201.kbstream.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.data.sync.PrefsPayloadApplier
import com.kennyb1201.kbstream.data.tmdb.TmdbSearchCollectionResult
import com.kennyb1201.kbstream.data.tmdb.TmdbSearchPersonResult
import com.kennyb1201.kbstream.data.tmdb.TmdbSearchStudioResult
import com.kennyb1201.kbstream.ui.components.KBStatusMessage
import com.kennyb1201.kbstream.ui.components.KBSkeletonRailStack
import com.kennyb1201.kbstream.ui.components.posterEdgeShape
import com.kennyb1201.kbstream.ui.components.rememberPosterSize
import com.kennyb1201.kbstream.ui.components.KB_STATUS_ICON_EMPTY
import com.kennyb1201.kbstream.ui.components.KBSectionHeader
import com.kennyb1201.kbstream.data.library.LibraryIds
import com.kennyb1201.kbstream.ui.components.LibraryAddTarget
import com.kennyb1201.kbstream.data.library.HiddenTitles
import com.kennyb1201.kbstream.ui.components.hideTarget
import com.kennyb1201.kbstream.ui.components.PosterContextAction
import com.kennyb1201.kbstream.ui.components.rememberHiddenTitleKeys
import com.kennyb1201.kbstream.ui.components.PosterContextMenu
import com.kennyb1201.kbstream.ui.components.watchedMenuLabel
import com.kennyb1201.kbstream.ui.components.watchedMenuDescription
import com.kennyb1201.kbstream.ui.mobile.SearchScreenMobile
import com.kennyb1201.kbstream.ui.theme.KBFocusChipInset
import com.kennyb1201.kbstream.ui.theme.KBVoid

@Composable
fun SearchScreen(
    onItemClick: (MetaPreview) -> Unit,
    onPersonClick: ((TmdbSearchPersonResult) -> Unit)? = null,
    onStudioClick: ((TmdbSearchStudioResult) -> Unit)? = null,
    onCollectionClick: ((TmdbSearchCollectionResult) -> Unit)? = null,
    // Browse-browser entries that drill into dedicated discover screens
    // (genres/keywords -> Tag, networks/services -> Studio, studios ->
    // Studio, collections -> Collection, decades -> Decade). Null =
    // entries navigate nowhere (standalone previews).
    onOpenTagScreen: ((Int, String, Boolean, String) -> Unit)? = null,
    onOpenStudioScreen: ((Int, String, Boolean, Int?, Int?, Boolean, Int?) -> Unit)? = null,
    onOpenCollectionScreen: ((Int, String) -> Unit)? = null,
    onOpenDecadeScreen: ((Int, String) -> Unit)? = null,
    // Hoisted by MainActivity (survives Search -> Detail -> Back) so Back
    // lands on the same title. Defaults keep previews/standalone use working.
    listState: LazyListState = rememberLazyListState(),
    viewModel: SearchViewModel = viewModel()
) {
    // The phone entry: the same ViewModel, callbacks and hoisted list state,
    // laid out for touch. One branch; the TV page below is unchanged (see
    // SearchScreenMobile).
    if (!com.kennyb1201.kbstream.FormFactor.isTv) {
        SearchScreenMobile(
            onItemClick = onItemClick,
            onPersonClick = onPersonClick,
            onStudioClick = onStudioClick,
            onCollectionClick = onCollectionClick,
            onOpenTagScreen = onOpenTagScreen,
            onOpenStudioScreen = onOpenStudioScreen,
            onOpenCollectionScreen = onOpenCollectionScreen,
            onOpenDecadeScreen = onOpenDecadeScreen,
            listState = listState,
            viewModel = viewModel
        )
        return
    }

    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    val resultsRaw by viewModel.results.collectAsStateWithLifecycle()
    val actorResults by viewModel.actorResults.collectAsStateWithLifecycle()
    val studioResults by viewModel.studioResults.collectAsStateWithLifecycle()
    val collectionResults by viewModel.collectionResults.collectAsStateWithLifecycle()
    val addonResultGroupsRaw by viewModel.addonResultGroups.collectAsStateWithLifecycle()
    val watchedKeys by viewModel.watchedKeys.collectAsStateWithLifecycle()
    val partialWatchedKeys by viewModel.partialWatchedKeys.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val recentSearches by viewModel.recentSearches.collectAsStateWithLifecycle()
    val resolvedIds by viewModel.resolvedIds.collectAsStateWithLifecycle()
    // Hidden titles are dropped as Search reads its own results, so a
    // result hidden from its long-press menu leaves both the TMDB rows
    // and the add-on rails without a re-query, and a rail the user
    // emptied out is dropped instead of rendering as a gap.
    val hiddenTitleKeys = rememberHiddenTitleKeys()
    fun resultIsHidden(result: SearchTitleResult): Boolean {
        val tmdbId = result.id.removePrefix("tmdb:").toIntOrNull()
        val imdb =
            tmdbId?.let { resolvedIds[viewModel.lookupKey(it, result.type)] }
        return HiddenTitles.hides(
            hiddenTitleKeys,
            result.type,
            result.name,
            result.year,
            result.id,
            imdb
        )
    }
    val results = remember(resultsRaw, resolvedIds, hiddenTitleKeys) {
        resultsRaw.filterNot(::resultIsHidden)
    }
    val addonResultGroups = remember(
        addonResultGroupsRaw,
        resolvedIds,
        hiddenTitleKeys
    ) {
        addonResultGroupsRaw.mapNotNull { group ->
            val visible = group.results.filterNot(::resultIsHidden)
            group.takeIf { visible.isNotEmpty() }?.copy(results = visible)
        }
    }
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val browseCategories by viewModel.browseCategories.collectAsStateWithLifecycle()
    val browseSubmenuLoading by viewModel.browseSubmenuLoading.collectAsStateWithLifecycle()
    val hiddenBrowseChips by viewModel.hiddenBrowseChips.collectAsStateWithLifecycle()
    val browseHomeShortcuts by viewModel.browseHomeShortcuts.collectAsStateWithLifecycle()

    // Add-on search rail title toggles (addon name / catalog type). These
    // apply ONLY to the addon search rails below — the built-in TMDB
    // "Titles" row has no addon or catalog type to show.
    //
    // Both toggles are SYNCED prefs, so the reads are keyed on the sync
    // revision: flipping one on the other TV has to relabel the add-on rails
    // already on screen rather than at the next visit here (see
    // [PrefsPayloadApplier.revision]).
    val context = LocalContext.current
    val syncedRevision by PrefsPayloadApplier.revision.collectAsStateWithLifecycle()
    val showRailType by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getSearchRailShowCatalogType(context))
    }
    val showRailAddon by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getSearchRailShowAddonName(context))
    }

    // Long-press context menu for title tiles (trending/search/add-on rails).
    var menuResult by remember {
        mutableStateOf<SearchTitleResult?>(
            null
        )
    }

    // Which rail's list the long-press menu was opened from, so its Hide can
    // resolve the successor WITHIN that rail. Add-on tiles are not part of the
    // TMDB `results` list, so before this the add-on rail's Hide searched that
    // list, found nothing, and let the framework drop the focus wherever it
    // liked (see the menu's onBeforeHide).
    var menuRailKey by remember {
        mutableStateOf(TITLES_RAIL_FOCUS_KEY)
    }
    var menuRailResults by remember {
        mutableStateOf<List<SearchTitleResult>>(emptyList())
    }

    // Long-press menus for the browse browser: a submenu chip offers "Hide",
    // the category strip offers "Unhide all" for the hidden chips under it.
    // One FocusRequester per browse chip, keyed by BrowseChipVisibility.key(),
    // owned HERE rather than inside the chip grid. The grid's own per-item
    // `remember(entry.name)` node is unreachable from the Hide action below,
    // which runs in this screen's scope - and Hide is the one action that has
    // to name a chip the viewer is not looking at (the one taking the hidden
    // chip's place) and hand it the focus. Same shape as the browser's own
    // tabFocusRequesters.
    val browseChipFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }

    var hiddenChipMenu by remember {
        mutableStateOf<Pair<String, BrowseEntry>?>(null)
    }

    // The browse chip a long-press menu should hand focus back to when it
    // closes, as (category key, chip name).
    //
    // The menu is drawn over this screen in its own focus group (see
    // PosterContextMenu), so once it goes nothing holds focus and the browser's
    // first focusable - the category strip - takes it. On a submenu of dozens
    // of entries that threw the viewer back to the top of the browser after a
    // menu that changed nothing about the list: "Add to Home" left the chip
    // exactly where it was and still moved focus off it. Naming the chip here
    // is what puts focus back on the one that was long-pressed.
    //
    // It lives in this composition rather than the ViewModel because closing
    // the menu never leaves this screen - and unlike the ViewModel's own
    // return-chip arm, it has to be observable state: adding a chip to Home
    // changes nothing else the browser is given, so nothing else would make it
    // recompose and notice the arm.
    var browseChipMenuReturn by remember {
        mutableStateOf<Pair<String, String>?>(null)
    }
    var categoryChipMenu by remember {
        mutableStateOf<BrowseCategory?>(null)
    }

    // The category TAB a just-closed tab menu wants focus back on, by key. The
    // tab menu is a focus group drawn over the strip (see PosterContextMenu),
    // so closing it used to leave focus nowhere and bounce the D-pad down into
    // the grid; this is the same hand-back the chip menus already do.
    var categoryTabMenuReturn by remember {
        mutableStateOf<String?>(null)
    }

    var lastPosterFocusRequester by remember {
        mutableStateOf<FocusRequester?>(
            null
        )
    }

    // The chip a long-press menu was opened on, as that chip's focus requester:
    // what lets the menu hand focus STRAIGHT back to the chip in the same
    // callback that closes it (see the chip menu's actions below). The browser's
    // own return arm cannot do this job on its own - it runs a frame later, and
    // by then the framework has already filled the gap with the page's first
    // focusable, the category strip at the very top of the browser, which is
    // what scrolled the screen up and then back down to the chip.
    var lastChipFocusRequester by remember {
        mutableStateOf<FocusRequester?>(
            null
        )
    }

    // One requester per search-result tile, keyed by the identity its rail keys
    // it with ("type:id"). Owned here for the same reason the browse chips'
    // registry is: the menu's Hide runs in this screen's scope, and the tile
    // that takes a hidden one's place has to be named from outside the lazy
    // row. Without it the Hide handed focus to the tile that was leaving.
    val titleTileFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }

    fun dismissTitleMenu() {
        menuResult = null
        lastPosterFocusRequester?.requestFocus()
    }

    LaunchedEffect(Unit) {
        // Which chips are mirrored to Home can have changed while this screen
        // was away - the tile's own long-press removes one from Home - and the
        // browse chip menu decides between "Add to Home" and "Remove from
        // Home" from that list.
        viewModel.refreshBrowseHomeShortcuts()
        viewModel.onOpenTagScreen = onOpenTagScreen
        viewModel.onOpenStudioScreen = onOpenStudioScreen
        viewModel.onOpenCollectionScreen = onOpenCollectionScreen
        viewModel.onOpenDecadeScreen = onOpenDecadeScreen

        // A query handed over by the system search surface (TV remote mic) or
        // a voice deep link: submit it as if it had been typed, so the results
        // are already loading when this screen appears. Consume-and-forget, so
        // returning to Search later does not re-run the old query.
        SearchSeed.consume()?.let { spoken ->
            if (spoken.isNotBlank()) viewModel.onQueryChanged(spoken)
        }
    }

    // NOTE: no onDispose reset here — SearchViewModel is activity-scoped, so
    // its state survives navigating into a collection/detail screen and back,
    // returning you to the same results instead of a fresh search. The VM
    // clears its own transient state when a new search starts.
    // Scroll position comes from the hoisted LazyListState (see signature):
    // Back from a drill-down restores the list at the same title. Exiting
    // Search itself resets it in MainActivity's BackHandler.

    // Counted from the RAW lists, before the hidden filter drops rows: a viewer
    // who has hidden every hit still MATCHED something, so the "No matches
    // found" card must not stand in for results they chose to hide (LS-P2-6).
    val totalCount = resultsRaw.size + actorResults.size + studioResults.size +
        collectionResults.size + addonResultGroupsRaw.sumOf { it.results.size }

    // Full-bleed screen: edge spacing lives in the LazyColumn's
    // contentPadding, not on the container, so the poster rails can draw
    // their focused borders + glow all the way to the screen edge without
    // being clipped by a fixed-inset parent. Rail-level contentPadding
    // (SEARCH_RAIL_EDGE_PADDING) keeps the same visual margin as before.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = SEARCH_RAIL_EDGE_PADDING,
                end = SEARCH_RAIL_EDGE_PADDING,
                top = 16.dp,
                bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "hero") {
                SearchHero(
                    query = query,
                    onQueryChanged = viewModel::onQueryChanged,
                    onSubmit = { viewModel.commitSearch() },
                    totalCount = totalCount,
                    catalogCount = results.size,
                    actorCount = actorResults.size,
                    studioCount = studioResults.size,
                    collectionCount = collectionResults.size,
                    addonCount = addonResultGroups.sumOf { it.results.size },
                    isLoading = isLoading
                )
            }

            if (suggestions.isNotEmpty()) {
                item(key = "suggestions") {
                    Column {
                        KBSectionHeader(title = "Suggestions")
                        LazyRow(
                            contentPadding = PaddingValues(
                                top = 2.dp,
                                bottom = 4.dp,
                                start = KBFocusChipInset,
                                end = KBFocusChipInset
                            ),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            // The column has already placed this item at the
                            // screen edge; the inset above is only the row's own
                            // clip room, cancelled here so the chips line up
                            // with the heading over them.
                            modifier = Modifier.offset(x = -KBFocusChipInset)
                        ) {
                            items(
                                items = suggestions,
                                key = { suggestion: String -> "suggestion:$suggestion" }
                            ) { suggestion: String ->
                                SearchChip(
                                    label = suggestion,
                                    onClick = { viewModel.onSuggestionClicked(suggestion) }
                                )
                            }
                        }
                    }
                }
            }

            if (query.isBlank() && recentSearches.isNotEmpty()) {
                item(key = "recent_section") {
                    Column {
                        KBSectionHeader(title = "Recent searches")
                        LazyRow(
                            contentPadding = PaddingValues(
                                top = 2.dp,
                                bottom = 4.dp,
                                start = KBFocusChipInset,
                                end = KBFocusChipInset
                            ),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.offset(x = -KBFocusChipInset)
                        ) {
                            items(
                                items = recentSearches,
                                key = { recent: String -> recent }
                            ) { recent: String ->
                                SearchChip(
                                    label = recent,
                                    onClick = { viewModel.onRecentSearchClicked(recent) }
                                )
                            }

                            item(key = "clear_recent") {
                                SearchChip(
                                    // A chip label is a control label, so it
                                    // takes the app's ALL-CAPS casing - the
                                    // recent-searches strip was the last chip
                                    // still written in sentence case.
                                    label = "CLEAR RECENT",
                                    onClick = viewModel::clearRecentSearches,
                                    accent = false
                                )
                            }
                        }
                    }
                }
            }

            // Browse browser: fills the old trending spot on the blank
            // state, and the no-results whitespace on failed searches.
            // Every entry opens its dedicated discover screen (the genre,
            // service, and decade screens own rails/pagination now).
            if (query.isBlank() || (!isLoading && totalCount == 0)) {
                item(key = "browse_browser") {
                    SearchBrowseBrowser(
                        viewModel = viewModel,
                        categories = browseCategories,
                        chipFocusRequesters = browseChipFocusRequesters,
                        submenuLoading = browseSubmenuLoading,
                        returnChipName = browseChipMenuReturn,
                        onReturnChipNameConsumed = { browseChipMenuReturn = null },
                        returnCategoryKey = categoryTabMenuReturn,
                        onReturnCategoryKeyConsumed = { categoryTabMenuReturn = null },
                        onChipLongPress = { categoryKey, entry, chipRequester ->
                            // Kept, not just used to open the menu: it is what
                            // the menu hands focus back with.
                            lastChipFocusRequester = chipRequester
                            hiddenChipMenu = categoryKey to entry
                        },
                        onCategoryLongPress = { category ->
                            // Nothing to offer unless this category actually
                            // has hidden chips to bring back.
                            val hiddenCount = BrowseChipVisibility.countHidden(
                                category.key,
                                hiddenBrowseChips
                            )
                            if (hiddenCount > 0) categoryChipMenu = category
                        }
                    )
                }
            }

            if (isLoading) {
                item(key = "loading") {
                    // Poster-shaped placeholders rather than the status card:
                    // loading is the one state whose shape is known in advance
                    // (see KBSkeleton), and the results the viewer is waiting on
                    // are titled rails of poster tiles. The zero horizontal
                    // padding is deliberate - the LazyColumn has already placed
                    // this item at the screen edge, so a second inset would sit
                    // the placeholders 24dp inside the rails that replace them.
                    val skeletonSize = rememberPosterSize()
                    KBSkeletonRailStack(
                        posterWidth = skeletonSize.width,
                        posterHeight = skeletonSize.height,
                        modifier = Modifier.padding(vertical = 8.dp),
                        railCount = 2,
                        horizontalPadding = 0.dp,
                        shape = posterEdgeShape()
                    )
                }
            }

            if (!isLoading && query.isNotBlank() && totalCount == 0) {
                item(key = "no_results") {
                    KBStatusMessage(
                        icon = KB_STATUS_ICON_EMPTY,
                        // A full sentence, with the stop every other status
                        // line on this screen ends on ("Nothing to show
                        // here.", "All rails are hidden.").
                        message = "No matches found.",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    )
                }
            }

            if (!isLoading && results.isNotEmpty()) {
                item(key = "titles_section") {
                    Column {
                        KBSectionHeader(title = "Titles")
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(
                                top = 2.dp,
                                // 4dp, matching the suggestion and recent rows
                                // above: the room a focused tile's ring needs
                                // below it inside the row's own clip. Two
                                // rails of the same posters sat 2dp and 4dp.
                                bottom = 4.dp,
                                start = KBFocusChipInset,
                                end = KBFocusChipInset
                            ),
                            modifier = Modifier.offset(x = -KBFocusChipInset)
                        ) {
                            items(
                                items = results,
                                // Type-qualified: a movie and a series can share
                                // a TMDB id, and a bare id key then collides -
                                // LazyRow throws on duplicate keys and the two
                                // tiles fight over one slot.
                                key = { result: SearchTitleResult -> "${result.type}:${result.id}" }
                            ) { result: SearchTitleResult ->
                                // From the screen's registry rather than a
                                // local remember, so the menu's Hide can reach
                                // a tile it is not looking at (see
                                // titleTileFocusRequesters).
                                // Keyed by the rail AND the tile (see
                                // searchTileFocusKey), so the same title on two
                                // rails cannot share one node and an add-on
                                // rail's tile is reachable from the menu's Hide.
                                val tileKey = searchTileFocusKey(TITLES_RAIL_FOCUS_KEY, result)
                                // Remembered, for the same reason the browse
                                // grid's registry lookup is: a FocusRequester
                                // built during composition outside a remember
                                // block is what lint's RememberInComposition
                                // rule rejects (see BrowseBrowser).
                                val requester = remember(tileKey) {
                                    titleTileFocusRequesters.getOrPut(tileKey) {
                                        FocusRequester()
                                    }
                                }

                                TitlePosterTile(
                                    result = result,
                                    watched = watchedTile(
                                        result = result,
                                        resolvedIds = resolvedIds,
                                        watchedKeys = watchedKeys,
                                        viewModel = viewModel
                                    ),
                                    isPartiallyWatched = partialWatchedTile(
                                        result = result,
                                        resolvedIds = resolvedIds,
                                        partialWatchedKeys = partialWatchedKeys,
                                        viewModel = viewModel
                                    ),
                                    onClick = {
                                        viewModel.onResultOpened(result)
                                        onItemClick(result.meta)
                                    },
                                    onLongClick = {
                                        lastPosterFocusRequester =
                                            requester
                                        menuRailKey = TITLES_RAIL_FOCUS_KEY
                                        menuRailResults = results
                                        menuResult = result
                                    },
                                    modifier = Modifier
                                        .focusRequester(requester)
                                )
                            }
                        }
                    }
                }
            }

            if (!isLoading && query.isNotBlank() && addonResultGroups.isNotEmpty()) {
                addonResultGroups.forEach { group ->
                    // Identity key, never the position (LS-P2-3): the add-on
                    // rails publish as each add-on answers, so a slow one
                    // shifts every rail after it. A positional key would then
                    // read as a brand-new item and throw away the rail's
                    // remembered scroll and focus. The view model coalesces
                    // rails that share this identity, so it stays unique.
                    item(
                        // The view model's own identity, not a second spelling of
                        // it: the key has to be injective, and a separator the
                        // add-on name can contain (a colon) would let one rail
                        // forge another's key - the duplicate-key crash this is
                        // keyed by identity to avoid.
                        key = "addons_rail:" + addonRailIdentity(
                            addonName = group.addonName,
                            railLabel = group.railLabel,
                            catalogType = group.catalogType
                        )
                    ) {
                        SearchRail(
                            title = searchRailTitle(
                                addonName = group.addonName,
                                railLabel = group.railLabel,
                                type = group.catalogType,
                                showType = showRailType,
                                showAddon = showRailAddon
                            )
                        ) {
                            items(
                                items = group.results,
                                // Type-qualified for the same reason the Titles
                                // rail above is (see the comment there): an
                                // add-on's /search can return the same id as
                                // both a movie and a series, and a bare-id key
                                // then collides - LazyRow throws on duplicate
                                // keys instead of drawing the second tile. The
                                // rail index is not enough to disambiguate
                                // them: they are two entries in THIS list.
                                // The positional index is also left out of the
                                // key (LS-P2-3): the type-qualified id is
                                // already unique within the rail, and a key
                                // that changes when the rail moves would reset
                                // this LazyRow's scroll.
                                key = { result: SearchTitleResult ->
                                    "addon:${result.type}:${result.id}"
                                }
                            ) { result: SearchTitleResult ->
                                // Rail identity + tile key, so the menu's Hide
                                // can name the tile that takes a hidden one's
                                // place (see searchTileFocusKey). The old local
                                // `remember(result.id)` was invisible outside
                                // this lazy row.
                                val railKey = "addon\u0001" + addonRailIdentity(
                                    addonName = group.addonName,
                                    railLabel = group.railLabel,
                                    catalogType = group.catalogType
                                )
                                val tileKey = searchTileFocusKey(railKey, result)
                                val requester = remember(tileKey) {
                                    titleTileFocusRequesters.getOrPut(tileKey) {
                                        FocusRequester()
                                    }
                                }

                                TitlePosterTile(
                                    result = result,
                                    watched = watchedTile(
                                        result = result,
                                        resolvedIds = resolvedIds,
                                        watchedKeys = watchedKeys,
                                        viewModel = viewModel
                                    ),
                                    isPartiallyWatched = partialWatchedTile(
                                        result = result,
                                        resolvedIds = resolvedIds,
                                        partialWatchedKeys = partialWatchedKeys,
                                        viewModel = viewModel
                                    ),
                                    onClick = {
                                        viewModel.onResultOpened(result)
                                        onItemClick(result.meta)
                                    },
                                    onLongClick = {
                                        lastPosterFocusRequester =
                                            requester
                                        menuRailKey = railKey
                                        menuRailResults = group.results
                                        menuResult = result
                                    },
                                    modifier = Modifier
                                        .focusRequester(requester)
                                )
                            }
                        }
                    }
                }
            }

            if (!isLoading && actorResults.isNotEmpty()) {
                item(key = "actors_rail") {
                    SearchRail(title = "Actors") {
                        items(
                            items = actorResults,
                            key = { person: TmdbSearchPersonResult -> "actor:${person.id}" }
                        ) { person: TmdbSearchPersonResult ->
                            PersonResultCard(
                                person = person,
                                onClick = {
                                    viewModel.onActorOpened(person)
                                    onPersonClick?.invoke(person)
                                }
                            )
                        }
                    }
                }
            }

            if (!isLoading && studioResults.isNotEmpty()) {
                item(key = "studios_rail") {
                    SearchRail(title = "Studios") {
                        items(
                            items = studioResults,
                            key = { studio: TmdbSearchStudioResult -> "studio:${studio.id}" }
                        ) { studio: TmdbSearchStudioResult ->
                            StudioResultCard(
                                studio = studio,
                                onClick = {
                                    viewModel.onStudioOpened(studio)
                                    onStudioClick?.invoke(studio)
                                }
                            )
                        }
                    }
                }
            }

            if (!isLoading && collectionResults.isNotEmpty()) {
                item(key = "collections_rail") {
                    SearchRail(title = "Collections") {
                        items(
                            items = collectionResults,
                            key = { collection: TmdbSearchCollectionResult -> "collection:${collection.id}" }
                        ) { collection: TmdbSearchCollectionResult ->
                            CollectionPosterTile(
                                collection = collection,
                                onClick = {
                                    viewModel.onCollectionOpened(collection)
                                    onCollectionClick?.invoke(collection)
                                }
                            )
                        }
                    }
                }
            }
        }

        // Long-press context menu for title tiles.
        menuResult?.let { result ->
            // Same lookup the tile badge uses, so the toggle always matches
            // what the poster currently shows: "Mark as Unwatched" when the
            // badge is visible, "Mark as Watched" otherwise.
            val isWatched = watchedTile(
                result = result,
                resolvedIds = resolvedIds,
                watchedKeys = watchedKeys,
                viewModel = viewModel
            )

            // Search mixes TMDB rows ("tmdb:123") with add-on rows
            // ("tt12345"). Split the id so an IMDB-keyed result still gets a
            // real library badge, and so the picker below can actually add it.
            val resultIds = LibraryIds.split(result.id)

            PosterContextMenu(
                title = result.name,
                // The same id pair the tile's badge uses, so a search result
                // adds to My List and to the trackers exactly like a rail card.
                libraryTarget = LibraryAddTarget(
                    mediaType = result.type,
                    imdbId = resultIds.imdbId,
                    tmdbId = resultIds.tmdbId,
                    title = result.name,
                    year = result.year,
                    posterUrl = result.poster
                ),
                hideTarget = hideTarget(
                    result.name,
                    result.type,
                    result.poster,
                    listOf(
                        result.id,
                        resultIds.imdbId,
                        resultIds.tmdbId?.toString()
                    ),
                    year = result.year
                ),
                // Hide takes this tile off the rail, and the entry it pointed
                // at has nowhere to hand focus. So the tile that takes its
                // place is named and focused FIRST, while this one is still
                // composed and the menu is still in the tree - the same
                // hand-off the browse chips' Hide does (see
                // successorIndexAfter). Left alone, the D-pad was handed a
                // dying node and the rail shrank out from under it.
                onBeforeHide = {
                    // Resolve the successor INSIDE THE RAIL THE MENU CAME FROM:
                    // an add-on tile is not in the TMDB `results` list, so
                    // searching that list found nothing for it and left the
                    // D-pad with no landing.
                    val railResults = menuRailResults
                    val railKey = menuRailKey
                    val index = railResults.indexOfFirst {
                        it.type == result.type && it.id == result.id
                    }
                    // No neighbour (the rail held only this title) names
                    // nothing: the rail goes with it and the framework moves
                    // focus to the nearest surface still on screen.
                    successorIndexAfter(railResults.size, index)?.let { successor ->
                        val next = railResults[successor]
                        titleTileFocusRequesters[
                            searchTileFocusKey(railKey, next)
                        ]?.requestFocus()
                    }
                    // And the tile being hidden must not take the focus BACK:
                    // it is still attached for this frame, so the dismiss that
                    // follows would land its request on the dying node and
                    // lose the focus with it - the report this fixes. Clearing
                    // the target makes that dismiss a no-op.
                    lastPosterFocusRequester = null
                },
                actions = listOf(
                    PosterContextAction(
                        label = "Go to Details",
                        description = "Open this title's detail page"
                    ) {
                        val selected = result
                        menuResult = null
                        viewModel.onResultOpened(selected)
                        onItemClick(selected.meta)
                    },
                    PosterContextAction(
                        label = watchedMenuLabel(
                            isWatched = isWatched,
                            mediaType = result.type
                        ),
                        description = watchedMenuDescription(
                            isWatched = isWatched,
                            mediaType = result.type
                        )
                    ) {
                        val selected = result
                        menuResult = null
                        if (isWatched) {
                            viewModel.markUnwatched(selected)
                        } else {
                            viewModel.markAsWatched(selected)
                        }
                        lastPosterFocusRequester?.requestFocus()
                    }
                ),
                onDismiss = {
                    dismissTitleMenu()
                }
            )
        }

        // Long-press on a browse chip. A normal press already opens the
        // chip's discover screen, so the menu holds the two things a press
        // cannot say: mirror the chip onto Home, or take it out of the
        // browse list.
        hiddenChipMenu?.let { (categoryKey, entry) ->
            val onHome = com.kennyb1201.kbstream.data.kb.BrowseHomeShortcuts.contains(
                browseHomeShortcuts,
                categoryKey,
                entry.name
            )
            // The rail the chip will land in, named in the menu so the
            // action says WHERE: chips are grouped by kind (genres and tags
            // together, services and networks together, ...).
            val railTitle =
                com.kennyb1201.kbstream.data.kb.browseShortcutRail(categoryKey)
                    ?.title
                    ?: "Browse"
            PosterContextMenu(
                title = entry.name,
                subtitle = "Browse chip",
                actions = listOf(
                    PosterContextAction(
                        label = if (onHome) {
                            "Remove from Home"
                        } else {
                            "Add to Home"
                        },
                        description = if (onHome) {
                            "Take this chip off the $railTitle rail on Home"
                        } else {
                            "Keep this chip on Home, in the $railTitle rail"
                        },
                        isDestructive = onHome
                    ) {
                        // Focus goes back to the chip FIRST, while the menu is
                        // still in the tree to hand it over: neither action
                        // moves the chip, so nothing about the page may move
                        // either, and an emptied focus owner is what the
                        // framework fills with the category strip - taking the
                        // view with it (see lastChipFocusRequester).
                        lastChipFocusRequester?.requestFocus()
                        if (onHome) {
                            viewModel.removeBrowseChipFromHome(categoryKey, entry)
                        } else {
                            viewModel.addBrowseChipToHome(categoryKey, entry)
                        }
                        // Belt and braces for the hand-back: the browser
                        // re-asks for the chip by name when the request above
                        // could not land (a node that is not attached yet), and
                        // a chip that is already on screen is revealed by no
                        // scroll at all (see browseChipNeedsReveal).
                        browseChipMenuReturn = categoryKey to entry.name
                        hiddenChipMenu = null
                    },
                    PosterContextAction(
                        label = "Hide",
                        description = "Remove this chip from the browse list",
                        isDestructive = true
                    ) {
                        // The chip that takes this one's place gets the focus,
                        // and gets it BEFORE the hidden chip leaves: the
                        // request only lands while the menu is still in the
                        // tree, and the hidden chip's own node is the one going
                        // away. Doing nothing here let the framework fill the
                        // emptied focus owner with the category strip - the
                        // list scrolled up - and the ViewModel's armed neighbor
                        // then pulled it back down a frame later, so the page
                        // jumped twice before settling (see successorKey).
                        val successor = BrowseChipVisibility.successorKey(
                            categoryKey = categoryKey,
                            names = browseCategories
                                .firstOrNull { it.key == categoryKey }
                                ?.entries
                                .orEmpty()
                                .map { it.name },
                            name = entry.name
                        )
                        successor?.let { key ->
                            browseChipFocusRequesters[key]?.requestFocus()
                        }
                        viewModel.hideBrowseChip(categoryKey, entry)
                        // Nothing named for the menu's own hand-back: this chip
                        // is the one leaving the list. The ViewModel still arms
                        // the chip that took its place, which is the fallback
                        // for a successor whose node was not composed yet (a
                        // lazy grid composes only the cells on screen).
                        browseChipMenuReturn = null
                        hiddenChipMenu = null
                    }
                ),
                onDismiss = {
                    // Back out of the menu: the chip is still where it was, so
                    // focus belongs on it exactly as after an action - handed
                    // over the same way, for the same reason.
                    lastChipFocusRequester?.requestFocus()
                    browseChipMenuReturn = categoryKey to entry.name
                    hiddenChipMenu = null
                }
            )
        }

        // Long-press on a category chip: the way back from Hide. Chips are
        // hidden per category, so this restores this strip's chips only.
        categoryChipMenu?.let { category ->
            val hiddenCount = BrowseChipVisibility.countHidden(
                category.key,
                hiddenBrowseChips
            )
            PosterContextMenu(
                title = category.label,
                subtitle = if (hiddenCount == 1) {
                    "1 hidden chip"
                } else {
                    "$hiddenCount hidden chips"
                },
                actions = listOf(
                    PosterContextAction(
                        label = "Unhide all ($hiddenCount)",
                        description = "Bring every hidden chip in this category back"
                    ) {
                        viewModel.unhideAllBrowseChips(category.key)
                        // Focus goes back to the tab, which the action did not
                        // move - only its "N hidden" count changed.
                        categoryTabMenuReturn = category.key
                        categoryChipMenu = null
                    }
                ),
                onDismiss = {
                    categoryTabMenuReturn = category.key
                    categoryChipMenu = null
                }
            )
        }

    }
}

/** The TMDB "Titles" rail's key in the tile-focus registry (see searchTileFocusKey). */
private const val TITLES_RAIL_FOCUS_KEY = "tmdb\u0001titles"

/**
 * One search tile's key in [titleTileFocusRequesters].
 *
 * The RAIL is part of the key, not just the tile: search mixes the TMDB
 * "Titles" rail with one rail per installed add-on, and the same title can
 * legitimately appear in several of them. Keying by the tile alone let two
 * rails share one FocusRequester (so a Hidden one took the other's focus with
 * it), and made an add-on tile unreachable from the menu's Hide - which runs in
 * this screen's scope and has no local `remember` to name.
 */
private fun searchTileFocusKey(railKey: String, result: SearchTitleResult): String =
    "$railKey\u0001${result.type}:${result.id}"
