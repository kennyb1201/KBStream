package com.kennyb1201.kbstream.ui.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.Surface
import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.ui.settings.AppPreferences
import com.kennyb1201.kbstream.data.tmdb.TmdbSearchCollectionResult
import com.kennyb1201.kbstream.data.tmdb.TmdbSearchPersonResult
import com.kennyb1201.kbstream.data.tmdb.TmdbSearchStudioResult
import com.kennyb1201.kbstream.ui.components.PosterCard
import com.kennyb1201.kbstream.ui.components.PosterContextAction
import com.kennyb1201.kbstream.ui.components.PosterContextMenu
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid

@Composable
fun SearchScreen(
    onItemClick: (MetaPreview) -> Unit,
    onPersonClick: ((TmdbSearchPersonResult) -> Unit)? = null,
    onStudioClick: ((TmdbSearchStudioResult) -> Unit)? = null,
    onCollectionClick: ((TmdbSearchCollectionResult) -> Unit)? = null,
    viewModel: SearchViewModel = viewModel()
) {
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val actorResults by viewModel.actorResults.collectAsStateWithLifecycle()
    val studioResults by viewModel.studioResults.collectAsStateWithLifecycle()
    val collectionResults by viewModel.collectionResults.collectAsStateWithLifecycle()
    val addonResultGroups by viewModel.addonResultGroups.collectAsStateWithLifecycle()
    val watchedKeys by viewModel.watchedKeys.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val recentSearches by viewModel.recentSearches.collectAsStateWithLifecycle()
    val trendingResults by viewModel.trendingResults.collectAsStateWithLifecycle()
    val resolvedIds by viewModel.resolvedIds.collectAsStateWithLifecycle()

    // Add-on search rail title toggles (addon name / catalog type). These
    // apply ONLY to the addon search rails below — the built-in TMDB
    // "Titles" row has no addon or catalog type to show.
    val context = LocalContext.current
    val showRailType by remember {
        mutableStateOf(AppPreferences.getSearchRailShowCatalogType(context))
    }
    val showRailAddon by remember {
        mutableStateOf(AppPreferences.getSearchRailShowAddonName(context))
    }

    // Long-press context menu for title tiles (trending/search/add-on rails).
    var menuResult by remember {
        mutableStateOf<SearchTitleResult?>(
            null
        )
    }

    var lastPosterFocusRequester by remember {
        mutableStateOf<FocusRequester?>(
            null
        )
    }

    fun dismissTitleMenu() {
        menuResult = null
        lastPosterFocusRequester?.requestFocus()
    }

    LaunchedEffect(Unit) {
        viewModel.loadTrending()
    }

    // NOTE: no onDispose reset here — SearchViewModel is activity-scoped, so
    // its state survives navigating into a collection/detail screen and back,
    // returning you to the same results instead of a fresh search. The VM
    // clears its own transient state when a new search starts.

    val totalCount = results.size + actorResults.size + studioResults.size + collectionResults.size +
        addonResultGroups.sumOf { it.results.size }

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

            if (query.isBlank() && recentSearches.isNotEmpty()) {
                item(key = "recent_section") {
                    Column {
                        SectionHeader(title = "Recent searches")
                        LazyRow(
                            contentPadding = PaddingValues(
                                top = 2.dp,
                                bottom = 4.dp,
                                start = SEARCH_RAIL_EDGE_PADDING,
                                end = SEARCH_RAIL_EDGE_PADDING
                            ),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                                    label = "Clear recent",
                                    onClick = viewModel::clearRecentSearches,
                                    accent = false
                                )
                            }
                        }
                    }
                }
            }

            if (query.isBlank() && trendingResults.isNotEmpty()) {
                item(key = "trending_section") {
                    Column {
                        SectionHeader(title = "Trending now")
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(
                                top = 2.dp,
                                bottom = 2.dp,
                                start = SEARCH_RAIL_EDGE_PADDING,
                                end = SEARCH_RAIL_EDGE_PADDING
                            )
                        ) {
                            items(
                                items = trendingResults,
                                key = { result: SearchTitleResult -> result.id }
                            ) { result: SearchTitleResult ->
                                val requester = remember(
                                    result.id
                                ) {
                                    FocusRequester()
                                }

                                TitlePosterTile(
                                    result = result,
                                    watched = watchedTile(
                                        result = result,
                                        resolvedIds = resolvedIds,
                                        watchedKeys = watchedKeys,
                                        viewModel = viewModel
                                    ),
                                    onClick = {
                                        onItemClick(result.meta)
                                    },
                                    onLongClick = {
                                        lastPosterFocusRequester =
                                            requester
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

            if (query.isBlank() && recentSearches.isEmpty() && trendingResults.isEmpty()) {
                item(key = "empty_idle") {
                    SearchMessagePanel(
                        title = "Search",
                        body = "Start typing to search across titles, actors, collections, and studios — or browse what's trending below."
                    )
                }
            }

            if (isLoading) {
                item(key = "loading") {
                    SearchMessagePanel(
                        title = "Searching...",
                        body = if (query.isBlank()) {
                            "Finding results"
                        } else {
                            """Looking for "$query" across titles, people, collections, and studios"""
                        }
                    )
                }
            }

            if (!isLoading && query.isNotBlank() && totalCount == 0) {
                item(key = "no_results") {
                    SearchMessagePanel(
                        title = "No matches found",
                        body = "Try a shorter title or a broader search term."
                    )
                }
            }

            if (!isLoading && results.isNotEmpty()) {
                item(key = "titles_section") {
                    Column {
                        SectionHeader(title = "Titles")
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(
                                top = 2.dp,
                                bottom = 2.dp,
                                start = SEARCH_RAIL_EDGE_PADDING,
                                end = SEARCH_RAIL_EDGE_PADDING
                            )
                        ) {
                            items(
                                items = results,
                                key = { result: SearchTitleResult -> result.id }
                            ) { result: SearchTitleResult ->
                                val requester = remember(
                                    result.id
                                ) {
                                    FocusRequester()
                                }

                                TitlePosterTile(
                                    result = result,
                                    watched = watchedTile(
                                        result = result,
                                        resolvedIds = resolvedIds,
                                        watchedKeys = watchedKeys,
                                        viewModel = viewModel
                                    ),
                                    onClick = {
                                        viewModel.onResultOpened(result)
                                        onItemClick(result.meta)
                                    },
                                    onLongClick = {
                                        lastPosterFocusRequester =
                                            requester
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
                addonResultGroups.forEachIndexed { index, group ->
                    item(key = "addons_rail_$index") {
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
                                key = { result: SearchTitleResult ->
                                    "addon:$index:${result.id}"
                                }
                            ) { result: SearchTitleResult ->
                                val requester = remember(
                                    result.id
                                ) {
                                    FocusRequester()
                                }

                                TitlePosterTile(
                                    result = result,
                                    watched = watchedTile(
                                        result = result,
                                        resolvedIds = resolvedIds,
                                        watchedKeys = watchedKeys,
                                        viewModel = viewModel
                                    ),
                                    onClick = {
                                        viewModel.onResultOpened(result)
                                        onItemClick(result.meta)
                                    },
                                    onLongClick = {
                                        lastPosterFocusRequester =
                                            requester
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

            PosterContextMenu(
                title = result.name,
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
    }
}

/**
 * Builds an add-on search rail header: optional addon name and catalog type
 * around the catalog label, e.g. "AIOMetadata · Trending · Series". Mirrors
 * HomeScreen.homeRailTitle. `type` is null for mixed rails (e.g. a standard
 * search endpoint that returns movies + series in one rail).
 */
private fun searchRailTitle(
    addonName: String,
    railLabel: String,
    type: String?,
    showType: Boolean,
    showAddon: Boolean
): String {
    val parts = buildList {
        if (showAddon && addonName.isNotBlank()) {
            add(addonName)
        }
        add(railLabel)
        if (showType && !type.isNullOrBlank()) {
            val cap = type.replaceFirstChar { it.uppercase() }
            // Skip when the rail label already conveys the type — regular
            // catalogs get type-derived labels ("Movies" / "Series" / "All"),
            // so appending again would render "Movies · Movie".
            if (railLabel.lowercase() !in setOf("movie", "movies", "series", "all", cap.lowercase())) {
                add(cap)
            }
        }
    }
    return parts.joinToString(" · ")
}

/**
 * Watched badge for a search tile: TMDB-keyed results compare against the
 * resolved IMDB id (populated asynchronously into [SearchViewModel.resolvedIds]);
 * add-on results are already keyed by their IMDB id and compare directly.
 */
private fun watchedTile(
    result: SearchTitleResult,
    resolvedIds: Map<String, String>,
    watchedKeys: Set<String>,
    viewModel: SearchViewModel
): Boolean {
    val tmdbId = result.id.removePrefix("tmdb:").toIntOrNull()
    val keyId = if (tmdbId != null) {
        resolvedIds[viewModel.lookupKey(tmdbId, result.type)]
    } else {
        result.id
    } ?: return false

    return viewModel.watchedKey(keyId, result.type) in watchedKeys
}

@Composable
private fun SearchHero(
    query: String,
    onQueryChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    totalCount: Int,
    catalogCount: Int,
    actorCount: Int,
    studioCount: Int,
    collectionCount: Int,
    addonCount: Int,
    isLoading: Boolean
) {
    var searchFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // Voice search: fires the platform speech recognizer (Fire TV / Android TV
    // remote mic or system voice dialog) and pipes the transcript into the
    // query field. RESULT_OK with no matches yields an empty list.
    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (spoken.isNotEmpty()) {
            onQueryChanged(spoken)
            onSubmit()
            focusManager.clearFocus()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(KBSurface, RoundedCornerShape(16.dp))
            .border(1.dp, KBTextLo.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Text(
            text = "Search",
            style = MaterialTheme.typography.titleLarge,
            color = KBTextHi
        )

        Row(
            modifier = Modifier
                .padding(top = 12.dp)
                .fillMaxWidth()
                .background(KBSurfaceRaised, RoundedCornerShape(12.dp))
                .border(1.dp, KBTextLo.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = KBTextLo,
                modifier = Modifier.size(18.dp)
            )

            Box(
                modifier = Modifier
                    .padding(start = 12.dp)
                    .weight(1f)
            ) {
                if (query.isBlank()) {
                    Text(
                        text = "Search titles, people, collections...",
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                BasicTextField(
                    value = query,
                    onValueChange = onQueryChanged,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = KBTextHi,
                        fontSize = MaterialTheme.typography.bodyMedium.fontSize
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            // IME-action path: leanback keyboards whose
                            // checkmark dispatches ImeAction.Done land here.
                            onSubmit()
                            keyboardController?.hide()
                            focusManager.clearFocus()
                        }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { searchFocused = it.isFocused }
                        // TV D-pad escape: the field consumes DirectionDown/Up
                        // for cursor movement, and the leanback IME grabs the
                        // D-pad entirely while it is up. Intercepting here
                        // moves focus out of the field (down into the results,
                        // up back to the tab bar) so search results are always
                        // reachable with the remote.
                        // Enter (checkmark) is ALSO handled here: many TV
                        // IMEs deliver it as a raw KEYCODE_ENTER key event
                        // instead of an IME action, in which case onDone
                        // above never fires.
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) {
                                false
                            } else {
                                when (event.key) {
                                    Key.DirectionDown -> {
                                        val moved = focusManager.moveFocus(FocusDirection.Down)
                                        if (moved) {
                                            keyboardController?.hide()
                                        }
                                        moved
                                    }
                                    Key.DirectionUp -> {
                                        val moved = focusManager.moveFocus(FocusDirection.Up)
                                        if (moved) {
                                            keyboardController?.hide()
                                        }
                                        moved
                                    }
                                    Key.Enter, Key.NumPadEnter -> {
                                        onSubmit()
                                        keyboardController?.hide()
                                        focusManager.clearFocus()
                                        true
                                    }
                                    else -> false
                                }
                            }
                        }
                )
            }
        }

        // Voice search trigger — same focused Surface treatment as the app's
        // other focusable chips (raised surface + accent content + border +
        // glow) so it lights up consistently on D-pad focus.
        Surface(
            onClick = {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Search KBStream")
                }
                runCatching { voiceLauncher.launch(intent) }
                    .onFailure {
                        // No recognizer installed on this device.
                        keyboardController?.hide()
                    }
            },
            shape = ClickableSurfaceDefaults.shape(
                shape = RoundedCornerShape(10.dp)
            ),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = KBSurfaceRaised,
                contentColor = KBTextLo,
                focusedContainerColor = KBAccent,
                focusedContentColor = KBVoid,
                pressedContainerColor = KBAccent,
                pressedContentColor = KBVoid
            ),
            scale = ClickableSurfaceDefaults.scale(
                focusedScale = 1.06f
            ),
            border = ClickableSurfaceDefaults.border(
                border = Border(
                    border = BorderStroke(1.dp, KBTextLo.copy(alpha = 0.35f)),
                    shape = RoundedCornerShape(10.dp)
                ),
                focusedBorder = Border(
                    border = BorderStroke(2.dp, KBAccent),
                    shape = RoundedCornerShape(10.dp)
                )
            ),
            glow = ClickableSurfaceDefaults.glow(
                focusedGlow = Glow(
                    elevationColor = KBAccent,
                    elevation = 10.dp
                )
            ),
            modifier = Modifier.padding(top = 10.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "Voice search",
                    // tv Surface drives tv-material's LocalContentColor, not
                    // material3's — read it explicitly so the mic follows the
                    // same KBTextLo -> KBVoid flip as the label on focus.
                    tint = androidx.tv.material3.LocalContentColor.current,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Voice search",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 7.dp)
                )
            }
        }

        val statusText = when {
            isLoading -> "Searching..."
            query.isBlank() -> ""
            totalCount == 1 -> "1 match"
            totalCount > 1 -> "$totalCount matches"
            else -> ""
        }

        if (statusText.isNotBlank()) {
            Text(
                text = statusText,
                color = KBAccent,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        if (!isLoading && query.isNotBlank() && totalCount > 0) {
            Text(
                text = buildString {
                    val chips = buildList {
                        if (catalogCount > 0) add("$catalogCount titles")
                        if (addonCount > 0) add("$addonCount from add-ons")
                        if (actorCount > 0) add("$actorCount actors")
                        if (collectionCount > 0) add("$collectionCount collections")
                        if (studioCount > 0) add("$studioCount studios")
                    }
                    append(chips.joinToString(" · "))
                },
                color = KBTextLo,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 5.dp)
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = KBTextHi,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

// Edge inset shared by the search screen's column and every rail's
// contentPadding. Living in contentPadding (rather than a parent modifier
// padding) keeps the ends of each rail inside the LazyRow's clip bounds so
// focused poster borders + glow never get cut off at the first/last item.
private val SEARCH_RAIL_EDGE_PADDING = 20.dp

@Composable
private fun SearchRail(
    title: String,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit
) {
    Column {
        SectionHeader(title = title)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(
                top = 2.dp,
                bottom = 2.dp,
                start = SEARCH_RAIL_EDGE_PADDING,
                end = SEARCH_RAIL_EDGE_PADDING
            )
        ) {
            content()
        }
    }
}

@Composable
private fun SearchChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = true
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor = when {
        focused -> KBAccent
        accent -> KBTextLo.copy(alpha = 0.35f)
        else -> KBTextLo.copy(alpha = 0.20f)
    }

    Card(
        onClick = onClick,
        colors = CardDefaults.colors(
            containerColor = if (focused) KBSurfaceRaised else KBSurface,
            contentColor = KBTextHi,
            focusedContainerColor = KBSurfaceRaised,
            focusedContentColor = KBTextHi,
            pressedContainerColor = KBSurfaceRaised,
            pressedContentColor = KBTextHi
        ),
        border = CardDefaults.border(
            border = Border(BorderStroke(1.dp, borderColor)),
            focusedBorder = Border(BorderStroke(2.dp, KBAccent))
        ),
        modifier = modifier.onFocusChanged { focused = it.isFocused }
    ) {
        Text(
            text = label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun TitlePosterTile(
    result: SearchTitleResult,
    watched: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = Modifier.width(124.dp)
    ) {
        PosterCard(
            posterUrl = result.poster,
            contentDescription = result.name,
            isWatched = watched,
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier
                .width(124.dp)
                .height(186.dp)
        )

        Text(
            text = result.name,
            style = MaterialTheme.typography.bodySmall,
            color = KBTextHi,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 5.dp)
        )

        val caption = buildString {
            result.year?.let { append(it) }
            result.rating?.let {
                if (isNotEmpty()) append("  ·  ")
                append("★ ${String.format("%.1f", it)}")
            }
        }

        if (caption.isNotBlank()) {
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = KBTextLo,
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun PersonResultCard(
    person: TmdbSearchPersonResult,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.colors(
            containerColor = KBSurface,
            contentColor = KBTextHi,
            focusedContainerColor = KBSurfaceRaised,
            focusedContentColor = KBTextHi,
            pressedContainerColor = KBSurfaceRaised,
            pressedContentColor = KBTextHi
        ),
        border = CardDefaults.border(
            border = Border(BorderStroke(1.dp, KBTextLo.copy(alpha = 0.25f))),
            focusedBorder = Border(BorderStroke(2.dp, KBAccent))
        ),
        modifier = Modifier.width(260.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(
                text = person.name,
                style = MaterialTheme.typography.titleMedium,
                color = KBTextHi,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp)
            )

            val subtitle = buildList {
                person.knownForDepartment?.takeIf { it.isNotBlank() }?.let { add(it) }
                person.knownFor
                    .mapNotNull { it.title ?: it.name }
                    .take(3)
                    .takeIf { it.isNotEmpty() }
                    ?.let { add(it.joinToString(", ")) }
            }.joinToString(" · ")

            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = KBTextLo,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 5.dp)
                )
            }
        }
    }
}

@Composable
private fun StudioResultCard(
    studio: TmdbSearchStudioResult,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.colors(
            containerColor = KBSurface,
            contentColor = KBTextHi,
            focusedContainerColor = KBSurfaceRaised,
            focusedContentColor = KBTextHi,
            pressedContainerColor = KBSurfaceRaised,
            pressedContentColor = KBTextHi
        ),
        border = CardDefaults.border(
            border = Border(BorderStroke(1.dp, KBTextLo.copy(alpha = 0.25f))),
            focusedBorder = Border(BorderStroke(2.dp, KBAccent))
        ),
        modifier = Modifier.width(240.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(
                text = studio.name,
                style = MaterialTheme.typography.titleMedium,
                color = KBTextHi,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp)
            )

            studio.originCountry?.takeIf { it.isNotBlank() }?.let { country ->
                Text(
                    text = country,
                    style = MaterialTheme.typography.bodySmall,
                    color = KBTextLo,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 5.dp)
                )
            }
        }
    }
}

/**
 * Poster-only tile for the Collections rail — no text label underneath,
 * the poster art carries the collection name.
 */
@Composable
private fun CollectionPosterTile(
    collection: TmdbSearchCollectionResult,
    onClick: () -> Unit
) {
    PosterCard(
        posterUrl = collection.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
        contentDescription = collection.name,
        isWatched = false,
        onClick = onClick,
        modifier = Modifier
            .width(124.dp)
            .height(186.dp)
    )
}

@Composable
private fun SearchMessagePanel(
    title: String,
    body: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(KBSurface, RoundedCornerShape(14.dp))
            .border(1.dp, KBTextLo.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = KBTextHi
        )
        Text(
            text = body,
            color = KBTextLo,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}
