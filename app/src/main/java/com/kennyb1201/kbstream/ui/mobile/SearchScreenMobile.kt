package com.kennyb1201.kbstream.ui.mobile

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.data.library.HiddenTitles
import com.kennyb1201.kbstream.data.library.LibraryIds
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.data.sync.PrefsPayloadApplier
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.tmdb.TmdbSearchCollectionResult
import com.kennyb1201.kbstream.data.tmdb.TmdbSearchPersonResult
import com.kennyb1201.kbstream.data.tmdb.TmdbSearchStudioResult
import com.kennyb1201.kbstream.ui.components.KBSkeletonRail
import com.kennyb1201.kbstream.ui.components.LibraryAddTarget
import com.kennyb1201.kbstream.ui.components.PosterContextAction
import com.kennyb1201.kbstream.ui.components.hideTarget
import com.kennyb1201.kbstream.ui.components.landscapeTileWidth
import com.kennyb1201.kbstream.ui.components.rememberGlobalLandscape
import com.kennyb1201.kbstream.ui.components.rememberHiddenTitleKeys
import com.kennyb1201.kbstream.ui.components.touch.KBTextField
import com.kennyb1201.kbstream.ui.components.touch.MobileStatus
import com.kennyb1201.kbstream.ui.components.touch.TouchGlobalPosterCard
import com.kennyb1201.kbstream.ui.components.touch.TouchPosterCaptions
import com.kennyb1201.kbstream.ui.components.touch.rememberTouchPosterSize
import com.kennyb1201.kbstream.ui.components.touch.PosterContextMenu
import com.kennyb1201.kbstream.ui.components.touch.TouchChoiceChip
import com.kennyb1201.kbstream.ui.components.watchedMenuDescription
import com.kennyb1201.kbstream.ui.components.watchedMenuLabel
import com.kennyb1201.kbstream.ui.search.BROWSE_FILTER_MIN_ENTRIES
import com.kennyb1201.kbstream.ui.search.BrowseCategory
import com.kennyb1201.kbstream.ui.search.BrowseChipVisibility
import com.kennyb1201.kbstream.ui.search.BrowseEntry
import com.kennyb1201.kbstream.ui.search.SearchSeed
import com.kennyb1201.kbstream.ui.search.SearchTitleResult
import com.kennyb1201.kbstream.ui.search.SearchViewModel
import com.kennyb1201.kbstream.ui.search.addonRailIdentity
import com.kennyb1201.kbstream.ui.search.browseCategoryBlurb
import com.kennyb1201.kbstream.ui.search.browseCategoryNounPlural
import com.kennyb1201.kbstream.ui.search.browseCountLabel
import com.kennyb1201.kbstream.ui.search.filterBrowseEntries
import com.kennyb1201.kbstream.ui.search.partialWatchedTile
import com.kennyb1201.kbstream.ui.search.searchRailTitle
import com.kennyb1201.kbstream.ui.search.watchedTile
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import com.kennyb1201.kbstream.ui.theme.MobileTheme

/**
 * Search, for a finger.
 *
 * The TV screen is a D-pad page: a hero field that holds focus, rails the
 * remote walks one card at a time, and a browse browser whose grid is one more
 * focus surface. The phone keeps all of its DATA - the same activity-scoped
 * [SearchViewModel], the same hidden-title filter, the same watched badges, the
 * same add-on rails and the same browse categories - and rebuilds the
 * presentation as a vertical scroll: a real keyboard field at the top, chips
 * for suggestions and recents, a touch browse browser (category tabs, a filter
 * field, a tappable chip grid), then the result rails.
 *
 * What is ported, not just listed:
 *
 *  - **the query path** is the field's own (`onQueryChanged` per keystroke,
 *    `commitSearch` on Done), plus the one-shot [SearchSeed] a voice search or
 *    deep link leaves behind;
 *  - **browse** is the full browser: six categories, the active category's
 *    blurb and count, the filter field where a category is big enough
 *    ([BROWSE_FILTER_MIN_ENTRIES]), "Surprise me", and a chip grid. A chip tap
 *    opens its discover screen; a long press offers Add to / Remove from Home
 *    and Hide; a category tab's long press brings back the chips hidden under
 *    it;
 *  - **the results** are the TV's rails - Titles, one per add-on source
 *    (honouring the two synced rail-label toggles), Actors, Studios,
 *    Collections - drawn as touch cards with the same watched/partial badges;
 *  - **the title long press** is the TV menu: Go to Details, watched toggle,
 *    Add to Library and Hide - with the same id pair the tile's badge uses.
 *
 * The TV's focus plumbing (requester registries, `successorIndexAfter`, the
 * return-focus loops) has no phone twin: a tap is a tap, and hiding a tile just
 * removes it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchScreenMobile(
    onItemClick: (MetaPreview) -> Unit,
    onPersonClick: ((TmdbSearchPersonResult) -> Unit)? = null,
    onStudioClick: ((TmdbSearchStudioResult) -> Unit)? = null,
    onCollectionClick: ((TmdbSearchCollectionResult) -> Unit)? = null,
    onOpenTagScreen: ((Int, String, Boolean, String) -> Unit)? = null,
    onOpenStudioScreen: ((Int, String, Boolean, Int?, Int?, Boolean, Int?) -> Unit)? = null,
    onOpenCollectionScreen: ((Int, String) -> Unit)? = null,
    onOpenDecadeScreen: ((Int, String) -> Unit)? = null,
    // Hoisted by MainActivity so Back from a result returns to the same place.
    // The same LazyListState the TV column uses - the two are never on screen
    // together, so one scroll record is enough.
    listState: LazyListState = rememberLazyListState(),
    viewModel: SearchViewModel = viewModel()
) {
    MobileTheme {
        val context = LocalContext.current
        val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
        val fieldFocus = remember { FocusRequester() }

        val query by viewModel.searchQuery.collectAsStateWithLifecycle()
        val resultsRaw by viewModel.results.collectAsStateWithLifecycle()
        val actorResults by viewModel.actorResults.collectAsStateWithLifecycle()
        val studioResults by viewModel.studioResults.collectAsStateWithLifecycle()
        val collectionResults by viewModel.collectionResults.collectAsStateWithLifecycle()
        val addonResultGroupsRaw by viewModel.addonResultGroups.collectAsStateWithLifecycle()
        val watchedKeys by viewModel.watchedKeys.collectAsStateWithLifecycle()
        val partialWatchedKeys by viewModel.partialWatchedKeys.collectAsStateWithLifecycle()
        val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
        val searchError by viewModel.searchError.collectAsStateWithLifecycle()
        val recentSearches by viewModel.recentSearches.collectAsStateWithLifecycle()
        val resolvedIds by viewModel.resolvedIds.collectAsStateWithLifecycle()
        val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
        val browseCategories by viewModel.browseCategories.collectAsStateWithLifecycle()
        val browseSubmenuLoading by
            viewModel.browseSubmenuLoading.collectAsStateWithLifecycle()
        val hiddenBrowseChips by viewModel.hiddenBrowseChips.collectAsStateWithLifecycle()
        val browseHomeShortcuts by
            viewModel.browseHomeShortcuts.collectAsStateWithLifecycle()
        val selectedCategoryKey by
            viewModel.selectedBrowseCategoryKey.collectAsStateWithLifecycle()

        // Both rail-label toggles are SYNCED prefs, re-read on the sync revision
        // exactly as on TV: a rename made on another device relabels the rails
        // already on screen.
        val syncRevision by PrefsPayloadApplier.revision.collectAsStateWithLifecycle()
        val showRailType = remember(syncRevision) {
            AppPreferences.getSearchRailShowCatalogType(context)
        }
        val showRailAddon = remember(syncRevision) {
            AppPreferences.getSearchRailShowAddonName(context)
        }

        // Hidden titles drop out as Search reads its own results - the TV's
        // rule, and the reason a Hide from this screen's menu takes a result
        // off both the TMDB and the add-on rails without a second query.
        val hiddenTitleKeys = rememberHiddenTitleKeys()
        fun resultIsHidden(result: SearchTitleResult): Boolean {
            val tmdbId = result.id.removePrefix("tmdb:").toIntOrNull()
            val imdb = tmdbId?.let { resolvedIds[viewModel.lookupKey(it, result.type)] }
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

        // Counted from the RAW lists, before the hidden filter: a viewer who hid
        // every hit still MATCHED something, so the empty state must not stand
        // in for results they chose to hide.
        val totalCount = resultsRaw.size + actorResults.size + studioResults.size +
            collectionResults.size + addonResultGroupsRaw.sumOf { it.results.size }

        var menuResult by remember { mutableStateOf<SearchTitleResult?>(null) }
        var chipMenu by remember { mutableStateOf<Pair<String, BrowseEntry>?>(null) }
        var categoryMenu by remember { mutableStateOf<BrowseCategory?>(null) }

        LaunchedEffect(Unit) {
            // Which chips are mirrored to Home can have changed while this
            // screen was away; the chip menu reads it to choose Add vs Remove.
            viewModel.refreshBrowseHomeShortcuts()
            viewModel.onOpenTagScreen = onOpenTagScreen
            viewModel.onOpenStudioScreen = onOpenStudioScreen
            viewModel.onOpenCollectionScreen = onOpenCollectionScreen
            viewModel.onOpenDecadeScreen = onOpenDecadeScreen

            // A query handed over by a voice search or a deep link: submitted
            // as if typed, so the results are loading when the screen appears.
            SearchSeed.consume()?.let { spoken ->
                if (spoken.isNotBlank()) viewModel.onQueryChanged(spoken)
            }

            // A fresh visit opens the keyboard; a visit that is back from a
            // result keeps the results in view instead of throwing the IME
            // back over them.
            if (viewModel.searchQuery.value.isBlank()) fieldFocus.requestFocus()
        }

        // ---- browse browser state ----
        val activeCategory = browseCategories.firstOrNull { it.key == selectedCategoryKey }
        var browseFilter by remember(activeCategory?.key) { mutableStateOf("") }
        val visibleEntries = remember(activeCategory?.entries, browseFilter) {
            filterBrowseEntries(activeCategory?.entries.orEmpty(), browseFilter)
        }
        // Browse is a grid of chips, not a row: three across a portrait phone,
        // more once the phone is turned and the width is there for them. The
        // number follows the window (see [mobileGridColumns]' reasoning), and
        // the chips keep their own size rather than stretching.
        val browseChipColumns = if (LocalConfiguration.current.screenWidthDp >= 600) 5 else 3
        val chipRows = remember(visibleEntries, browseChipColumns) {
            visibleEntries.chunked(browseChipColumns)
        }
        // A failed search keeps the browser out of the way: the browser's
        // chips are the "nothing matched, try browsing" answer, and they are
        // the wrong answer when the query never reached the service.
        val showBrowser =
            query.isBlank() || (!isLoading && totalCount == 0 && searchError == null)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(KBVoid)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item(key = "searchbar") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, end = 12.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MobileBackButton(onBack = { backDispatcher?.onBackPressed() })
                        KBTextField(
                            value = query,
                            onValueChange = viewModel::onQueryChanged,
                            placeholder = "Search titles, people, collections…",
                            focusRequester = fieldFocus,
                            modifier = Modifier.weight(1f),
                            onDone = { viewModel.commitSearch() },
                            onClear = {
                                viewModel.onQueryChanged("")
                                fieldFocus.requestFocus()
                            },
                            leading = {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                    tint = KBTextLo,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        )
                    }
                }

                val statusText = when {
                    isLoading -> "Searching…"
                    query.isBlank() -> ""
                    totalCount == 1 -> "1 match"
                    totalCount > 1 -> "$totalCount matches"
                    else -> ""
                }
                if (statusText.isNotBlank()) {
                    item(key = "status") {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = statusText,
                                color = KBAccent,
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (!isLoading && totalCount > 0) {
                                Text(
                                    text = buildString {
                                        val chips = buildList {
                                            if (results.isNotEmpty()) {
                                                add("${results.size} titles")
                                            }
                                            val addonCount = addonResultGroups
                                                .sumOf { it.results.size }
                                            if (addonCount > 0) {
                                                add("$addonCount from add-ons")
                                            }
                                            if (actorResults.isNotEmpty()) {
                                                add("${actorResults.size} actors")
                                            }
                                            if (collectionResults.isNotEmpty()) {
                                                add("${collectionResults.size} collections")
                                            }
                                            if (studioResults.isNotEmpty()) {
                                                add("${studioResults.size} studios")
                                            }
                                        }
                                        append(chips.joinToString(" · "))
                                    },
                                    color = KBTextLo,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                if (suggestions.isNotEmpty()) {
                    item(key = "suggestions") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            MobileSectionHeading("Suggestions")
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(
                                    suggestions,
                                    key = { "suggestion:$it" },
                                    contentType = { "suggestion" }
                                ) { suggestion ->
                                    TouchChoiceChip(
                                        label = suggestion,
                                        selected = false,
                                        onClick = {
                                            viewModel.onSuggestionClicked(suggestion)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                if (query.isBlank() && recentSearches.isNotEmpty()) {
                    item(key = "recent_section") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            MobileSectionHeading("Recent searches")
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(
                                    recentSearches,
                                    key = { "recent:$it" },
                                    contentType = { "recent" }
                                ) { recent ->
                                    TouchChoiceChip(
                                        label = recent,
                                        selected = false,
                                        onClick = {
                                            viewModel.onRecentSearchClicked(recent)
                                        }
                                    )
                                }
                                item(key = "clear_recent") {
                                    TouchChoiceChip(
                                        label = "CLEAR RECENT",
                                        selected = false,
                                        onClick = viewModel::clearRecentSearches
                                    )
                                }
                            }
                        }
                    }
                }

                if (showBrowser) {
                    item(key = "browse_header") {
                        MobileSectionHeading(
                            "Browse",
                            trailing = "${browseCategories.size} categories"
                        )
                    }

                    item(key = "browse_tabs") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(
                                browseCategories,
                                key = { it.key },
                                contentType = { "category" }
                            ) { category ->
                                TouchChoiceChip(
                                    label = category.label,
                                    selected = category.key == activeCategory?.key,
                                    onClick = { viewModel.selectBrowseCategory(category.key) },
                                    onLongClick = {
                                        // Nothing to offer unless this category
                                        // actually has hidden chips to bring
                                        // back.
                                        val hiddenCount = BrowseChipVisibility.countHidden(
                                            category.key,
                                            hiddenBrowseChips
                                        )
                                        if (hiddenCount > 0) categoryMenu = category
                                    }
                                )
                            }
                        }
                    }

                    val category = activeCategory
                    if (category != null) {
                        item(key = "browse_active") {
                            Column(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = category.label,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = KBTextHi
                                        )
                                        Text(
                                            text = browseCategoryBlurb(category.key),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = KBTextLo,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text(
                                        text = browseCountLabel(
                                            shown = visibleEntries.size,
                                            total = category.entries.size,
                                            key = category.key
                                        ),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = KBAccent,
                                        modifier = Modifier.padding(start = 12.dp)
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    if (
                                        category.entries.size >= BROWSE_FILTER_MIN_ENTRIES
                                    ) {
                                        KBTextField(
                                            value = browseFilter,
                                            onValueChange = { browseFilter = it },
                                            placeholder = "Filter " +
                                                browseCategoryNounPlural(category.key),
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    TouchChoiceChip(
                                        label = if (browseFilter.isBlank()) {
                                            "SURPRISE ME"
                                        } else {
                                            "CLEAR"
                                        },
                                        selected = false,
                                        onClick = {
                                            if (browseFilter.isBlank()) {
                                                category.entries.randomOrNull()?.let { entry ->
                                                    viewModel.onBrowseEntryClicked(
                                                        category.key,
                                                        entry
                                                    )
                                                }
                                            } else {
                                                browseFilter = ""
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        when {
                            browseSubmenuLoading && category.entries.isEmpty() -> {
                                item(key = "browse_loading") {
                                    Text(
                                        text = "Resolving ${category.label.lowercase()}…",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = KBTextLo,
                                        modifier = Modifier.padding(horizontal = 16.dp)
                                    )
                                }
                            }

                            category.entries.isEmpty() -> {
                                item(key = "browse_empty") {
                                    Text(
                                        text = "Nothing to browse here yet.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = KBTextLo,
                                        modifier = Modifier.padding(horizontal = 16.dp)
                                    )
                                }
                            }

                            visibleEntries.isEmpty() -> {
                                item(key = "browse_no_match") {
                                    Text(
                                        text = "No " +
                                            browseCategoryNounPlural(category.key) +
                                            " match \"${browseFilter.trim()}\"",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = KBTextLo,
                                        modifier = Modifier.padding(horizontal = 16.dp)
                                    )
                                }
                            }

                            else -> {
                                // Audit B5: keyed by the row's POSITION, not by
                                // its first chip's display name. Two browse
                                // entries can share a name (duplicate studio or
                                // network labels are ordinary), and a duplicate
                                // key crashes Compose's lazy list. A chunk's
                                // index is unique by construction and stable
                                // for the same entry list.
                                itemsIndexed(
                                    chipRows,
                                    key = { index, _ -> "chiprow:$index" },
                                    contentType = { _, _ -> "chiprow" }
                                ) { _, row ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        row.forEach { entry ->
                                            BrowseChip(
                                                entry = entry,
                                                onClick = {
                                                    viewModel.onBrowseEntryClicked(
                                                        category.key,
                                                        entry
                                                    )
                                                },
                                                onLongClick = {
                                                    chipMenu = category.key to entry
                                                },
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                        // Keep the last row's cells the same
                                        // width as a full row's.
                                        repeat(3 - row.size) {
                                            Box(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (isLoading) {
                    // A shaped skeleton, not a spinner (improvement F1): results
                    // arrive as poster rails, so the waiting state is a rail of
                    // poster placeholders at the viewer's Poster Size - the space
                    // the results will occupy, drawn before they land.
                    item(key = "loading") {
                        val loadingSize = rememberTouchPosterSize()
                        KBSkeletonRail(
                            posterWidth = loadingSize.width,
                            posterHeight = loadingSize.height,
                            itemCount = 4
                        )
                    }
                }

                val error = searchError
                if (!isLoading && error != null) {
                    item(key = "search_error") {
                        MobileStatus(
                            message = error,
                            actionLabel = "TRY AGAIN",
                            onAction = { viewModel.search(query) }
                        )
                    }
                }

                if (!isLoading && searchError == null && query.isNotBlank() && totalCount == 0) {
                    item(key = "no_results") {
                        MobileStatus("No matches found.")
                    }
                }

                if (!isLoading && results.isNotEmpty()) {
                    item(key = "titles_section") {
                        MobileSearchRail(title = "Titles") {
                            items(
                                results,
                                key = { result: SearchTitleResult ->
                                    "title:${result.type}:${result.id}"
                                },
                                contentType = { "title" }
                            ) { result ->
                                SearchTile(
                                    result = result,
                                    watched = watchedTile(
                                        result = result,
                                        resolvedIds = resolvedIds,
                                        watchedKeys = watchedKeys,
                                        viewModel = viewModel
                                    ),
                                    partiallyWatched = partialWatchedTile(
                                        result = result,
                                        resolvedIds = resolvedIds,
                                        partialWatchedKeys = partialWatchedKeys,
                                        viewModel = viewModel
                                    ),
                                    onClick = {
                                        viewModel.onResultOpened(result)
                                        onItemClick(result.meta)
                                    },
                                    onLongClick = { menuResult = result }
                                )
                            }
                        }
                    }
                }

                if (!isLoading && query.isNotBlank() && addonResultGroups.isNotEmpty()) {
                    addonResultGroups.forEach { group ->
                        item(
                            key = "addons_rail:" + addonRailIdentity(
                                addonName = group.addonName,
                                railLabel = group.railLabel,
                                catalogType = group.catalogType
                            )
                        ) {
                            MobileSearchRail(
                                title = searchRailTitle(
                                    addonName = group.addonName,
                                    railLabel = group.railLabel,
                                    type = group.catalogType,
                                    showType = showRailType,
                                    showAddon = showRailAddon
                                )
                            ) {
                                items(
                                    group.results,
                                    key = { result: SearchTitleResult ->
                                        "addon:${result.type}:${result.id}"
                                    },
                                    contentType = { "addon-title" }
                                ) { result ->
                                    SearchTile(
                                        result = result,
                                        watched = watchedTile(
                                            result = result,
                                            resolvedIds = resolvedIds,
                                            watchedKeys = watchedKeys,
                                            viewModel = viewModel
                                        ),
                                        partiallyWatched = partialWatchedTile(
                                            result = result,
                                            resolvedIds = resolvedIds,
                                            partialWatchedKeys = partialWatchedKeys,
                                            viewModel = viewModel
                                        ),
                                        onClick = {
                                            viewModel.onResultOpened(result)
                                            onItemClick(result.meta)
                                        },
                                        onLongClick = { menuResult = result }
                                    )
                                }
                            }
                        }
                    }
                }

                if (!isLoading && actorResults.isNotEmpty()) {
                    item(key = "actors_rail") {
                        MobileSearchRail(title = "Actors") {
                            items(
                                actorResults,
                                key = { person: TmdbSearchPersonResult -> "actor:${person.id}" },
                                contentType = { "actor" }
                            ) { person ->
                                PersonTile(
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
                        MobileSearchRail(title = "Studios") {
                            items(
                                studioResults,
                                key = { studio: TmdbSearchStudioResult ->
                                    "studio:${studio.id}"
                                },
                                contentType = { "studio" }
                            ) { studio ->
                                StudioTile(
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
                        MobileSearchRail(title = "Collections") {
                            items(
                                collectionResults,
                                key = { collection: TmdbSearchCollectionResult ->
                                    "collection:${collection.id}"
                                },
                                contentType = { "collection" }
                            ) { collection ->
                                CollectionTile(
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

            // ---- long-press menus ----

            // A result tile: the TV menu, with the same id pair the tile's badge
            // uses, so an add reaches My List and the trackers with both ids.
            menuResult?.let { result ->
                val isWatched = watchedTile(
                    result = result,
                    resolvedIds = resolvedIds,
                    watchedKeys = watchedKeys,
                    viewModel = viewModel
                )
                val resultIds = LibraryIds.split(result.id)

                PosterContextMenu(
                    title = result.name,
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
                        }
                    ),
                    onDismiss = { menuResult = null }
                )
            }

            // A browse chip: a tap already opens its discover screen, so the
            // long press holds the two things a tap cannot say.
            chipMenu?.let { (categoryKey, entry) ->
                val onHome = com.kennyb1201.kbstream.data.kb.BrowseHomeShortcuts.contains(
                    browseHomeShortcuts,
                    categoryKey,
                    entry.id,
                    entry.name
                )
                val railTitle =
                    com.kennyb1201.kbstream.data.kb.browseShortcutRail(categoryKey)
                        ?.title
                        ?: "Browse"
                PosterContextMenu(
                    title = entry.name,
                    subtitle = "Browse chip",
                    actions = listOf(
                        PosterContextAction(
                            label = if (onHome) "Remove from Home" else "Add to Home",
                            description = if (onHome) {
                                "Take this chip off the $railTitle rail on Home"
                            } else {
                                "Keep this chip on Home, in the $railTitle rail"
                            },
                            isDestructive = onHome
                        ) {
                            if (onHome) {
                                viewModel.removeBrowseChipFromHome(categoryKey, entry)
                            } else {
                                viewModel.addBrowseChipToHome(categoryKey, entry)
                            }
                            chipMenu = null
                        },
                        PosterContextAction(
                            label = "Hide",
                            description = "Remove this chip from the browse list",
                            isDestructive = true
                        ) {
                            viewModel.hideBrowseChip(categoryKey, entry)
                            chipMenu = null
                        }
                    ),
                    onDismiss = { chipMenu = null }
                )
            }

            // A category tab: the way back from Hide, per category.
            categoryMenu?.let { category ->
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
                            categoryMenu = null
                        }
                    ),
                    onDismiss = { categoryMenu = null }
                )
            }
        }
    }
}

/** A heading above one block of the phone's search page. */
@Composable
private fun MobileSectionHeading(text: String, trailing: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            color = KBTextHi,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        trailing?.let { label ->
            Text(
                text = label,
                color = KBTextLo,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

/**
 * One titled result rail. The TV twin carries a focus inset so a focused
 * poster's ring is not clipped; on a phone the row is simply inset by the
 * screen edge.
 */
@Composable
private fun MobileSearchRail(
    title: String,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MobileSectionHeading(title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            content()
        }
    }
}

/** One title result: poster, watched badge, and its name/year/rating below. */
@Composable
private fun SearchTile(
    result: SearchTitleResult,
    watched: Boolean,
    partiallyWatched: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    // The column tracks the card's own width: a landscape tile is wider than
    // the poster it replaces, and a column pinned to the poster width would clip
    // it and its caption. Both come from the viewer's Poster Size - this tile
    // used to pin 112dp, which is why the size switch did nothing here.
    val landscape = rememberGlobalLandscape()
    val posterSize = rememberTouchPosterSize()
    Column(
        modifier = Modifier.width(
            if (landscape) landscapeTileWidth(posterSize.width) else posterSize.width
        ),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Shape follows Settings' global landscape switch, like every other
        // catalog surface.
        TouchGlobalPosterCard(
            posterUrl = result.poster,
            contentDescription = result.name,
            isWatched = watched,
            isPartiallyWatched = partiallyWatched,
            onClick = onClick,
            onLongClick = onLongClick,
            artId = result.id,
            artType = result.type,
            posterWidth = posterSize.width,
            posterHeight = posterSize.height
        )
        // Shared caption block: the Settings toggles for title / year / rating
        // apply here exactly as they do on a rail.
        TouchPosterCaptions(
            title = result.name,
            year = result.year?.toString(),
            rating = result.rating
        )
    }
}

/** One actor result: round portrait, name and what they are known for. */
@Composable
private fun PersonTile(
    person: TmdbSearchPersonResult,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(112.dp)
            .clip(KBShapeCard)
            .clickable(onClick = onClick)
            .background(KBSurface)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        AsyncImage(
            model = person.profilePath?.let { TmdbRepository.PROFILE_BASE + it },
            contentDescription = person.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(KBSurfaceRaised)
        )
        Text(
            text = person.name,
            color = KBTextHi,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        buildList {
            person.knownForDepartment?.takeIf { it.isNotBlank() }?.let { add(it) }
            person.knownFor
                .mapNotNull { it.title ?: it.name }
                .take(2)
                .takeIf { it.isNotEmpty() }
                ?.let { add(it.joinToString(", ")) }
        }.joinToString(" · ").takeIf { it.isNotBlank() }?.let { subtitle ->
            Text(
                text = subtitle,
                color = KBTextLo,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** One studio result: its logo on the white plate where TMDB has one. */
@Composable
private fun StudioTile(
    studio: TmdbSearchStudioResult,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(160.dp)
            .clip(KBShapeCard)
            .clickable(onClick = onClick)
            .background(KBSurface)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (studio.logoPath != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(KBShapeCard)
                    .background(Color.White)
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = TmdbRepository.LOGO_BASE + studio.logoPath,
                    contentDescription = studio.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                )
            }
        }
        Text(
            text = studio.name,
            color = KBTextHi,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        studio.originCountry?.takeIf { it.isNotBlank() }?.let { country ->
            Text(
                text = country,
                color = KBTextLo,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
    }
}

/** One collection result: the poster art carries the name, as on TV. */
@Composable
private fun CollectionTile(
    collection: TmdbSearchCollectionResult,
    onClick: () -> Unit
) {
    val posterSize = rememberTouchPosterSize()
    TouchGlobalPosterCard(
        posterUrl = collection.posterPath
            ?.let { TmdbRepository.POSTER_BASE + it },
        contentDescription = collection.name,
        isWatched = false,
        onClick = onClick,
        artId = collection.id.toString(),
        // A collection is its own id space: the card must not read a title's
        // overrides for it, and resolves the collection's own art.
        artType = "collection",
        posterWidth = posterSize.width,
        posterHeight = posterSize.height
    )
}

/**
 * One browse chip. A tap opens the chip's discover screen; a long press raises
 * the chip menu (see the screen's `chipMenu`), which is the touch equivalent of
 * holding Select on the TV grid.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrowseChip(
    entry: BrowseEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(KBShapeCard)
            .background(KBSurface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = entry.name,
            color = KBTextHi,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
