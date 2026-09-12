package com.kennyb1201.kbstream.ui.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.speech.RecognizerIntent
import android.provider.Settings
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
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
import com.kennyb1201.kbstream.data.tmdb.StudioItem
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.ui.settings.AppPreferences
import com.kennyb1201.kbstream.data.tmdb.TmdbSearchCollectionResult
import com.kennyb1201.kbstream.data.tmdb.TmdbSearchPersonResult
import com.kennyb1201.kbstream.data.tmdb.TmdbSearchStudioResult
import com.kennyb1201.kbstream.ui.components.PosterCaptions
import com.kennyb1201.kbstream.ui.components.KBTextField
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
    // Browse-browser entries that drill into existing discover screens
    // (genres/keywords -> Tag, networks/studios -> Studio, collections ->
    // Collection). Null = entries navigate nowhere (standalone previews).
    onOpenTagScreen: ((Int, String, Boolean, String) -> Unit)? = null,
    onOpenStudioScreen: ((Int, String, Boolean) -> Unit)? = null,
    onOpenCollectionScreen: ((Int, String) -> Unit)? = null,
    // Hoisted by MainActivity (survives Search -> Detail -> Back) so Back
    // lands on the same title. Defaults keep previews/standalone use working.
    listState: LazyListState = rememberLazyListState(),
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
    val resolvedIds by viewModel.resolvedIds.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val browseCategories by viewModel.browseCategories.collectAsStateWithLifecycle()
    val browseSelection by viewModel.browseSelection.collectAsStateWithLifecycle()
    val browseRails by viewModel.browseRails.collectAsStateWithLifecycle()
    val browseSubmenuLoading by viewModel.browseSubmenuLoading.collectAsStateWithLifecycle()

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
        viewModel.onOpenTagScreen = onOpenTagScreen
        viewModel.onOpenStudioScreen = onOpenStudioScreen
        viewModel.onOpenCollectionScreen = onOpenCollectionScreen
    }

    // NOTE: no onDispose reset here — SearchViewModel is activity-scoped, so
    // its state survives navigating into a collection/detail screen and back,
    // returning you to the same results instead of a fresh search. The VM
    // clears its own transient state when a new search starts.
    // Scroll position comes from the hoisted LazyListState (see signature):
    // Back from a drill-down restores the list at the same title. Exiting
    // Search itself resets it in MainActivity's BackHandler.

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
                        SectionHeader(title = "Suggestions")
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

            // Browse browser: fills the old trending spot on the blank
            // state, and the no-results whitespace on failed searches.
            if (query.isBlank() || (!isLoading && totalCount == 0)) {
                item(key = "browse_browser") {
                    SearchBrowseBrowser(
                        viewModel = viewModel,
                        categories = browseCategories,
                        selection = browseSelection,
                        rails = browseRails,
                        submenuLoading = browseSubmenuLoading,
                        resolvedIds = resolvedIds,
                        watchedKeys = watchedKeys,
                        onTileClick = { result ->
                            viewModel.onResultOpened(result)
                            onItemClick(result.meta)
                        },
                        onTileLongClick = { result, requester ->
                            lastPosterFocusRequester = requester
                            menuResult = result
                        }
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
    val context = LocalContext.current
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

    // Builds the voice-search intent targeted at the device's DEFAULT
    // speech recognizer (the one chosen under Settings > Language & input >
    // Voice input), instead of firing a bare ACTION_RECOGNIZE_SPEECH that
    // opens the "complete action using" chooser. Picking the wrong chooser
    // entry there (e.g. the Google app rather than the voice recognition
    // service) used to swallow the session: green mic and listening sounds,
    // but no transcript ever returned to KBStream.
    fun buildVoiceIntent(context: android.content.Context): Intent {
        val base = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Search KBStream")
        }

        // Settings.Secure "voice_recognition_service" is the ComponentName
        // ("pkg/cls") of the system's current default RecognitionService.
        // Its package hosts the recognizer activity too, so scoping the
        // intent to that package routes the session straight to it — no
        // chooser, no wrong-handler dead end. If anything is unreadable or
        // uninstalled, fall back to the bare intent (chooser) so voice
        // search still works, just the old way.
        val defaultRecognizerPackage = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                "voice_recognition_service"
            )
        }.getOrNull()
            ?.substringBefore("/")
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?: return base

        val scoped = Intent(base).apply {
            setPackage(defaultRecognizerPackage)
        }
        // If the default recognizer package can't resolve the activity
        // (uninstalled/changed), fall back to the chooser-style intent.
        val resolves = runCatching {
            context.packageManager.queryIntentActivities(scoped, 0)
        }.getOrDefault(emptyList())
        if (resolves.isEmpty()) {
            return base
        }
        return scoped
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

        KBTextField(
            value = query,
            onValueChange = onQueryChanged,
            placeholder = "Search titles, people, collections...",
            modifier = Modifier
                .padding(top = 12.dp)
                .fillMaxWidth(),
            onDone = { onSubmit() },
            leading = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = KBTextLo,
                    modifier = Modifier.size(18.dp)
                )
            }
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Voice search trigger — same focused Surface treatment as the app's
        // other focusable chips (raised surface + accent content + border +
        // glow) so it lights up consistently on D-pad focus.
        Surface(
            onClick = {
                runCatching { voiceLauncher.launch(buildVoiceIntent(context)) }
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
private fun SearchBrowseBrowser(
    viewModel: SearchViewModel,
    categories: List<BrowseCategory>,
    selection: SearchViewModel.BrowseSelection?,
    rails: List<SearchViewModel.BrowseRail>,
    submenuLoading: Boolean,
    resolvedIds: Map<String, String>,
    watchedKeys: Set<String>,
    onTileClick: (SearchTitleResult) -> Unit,
    onTileLongClick: (SearchTitleResult, FocusRequester) -> Unit
) {
    var activeCategory by remember { mutableStateOf<BrowseCategory?>(null) }

    Column(modifier = Modifier.padding(top = 8.dp)) {
        SectionHeader(title = "Browse")

        // Sidebar: one scrollable category row (the "separate categories"
        // strip; selecting one reveals its submenu below).
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = SEARCH_RAIL_EDGE_PADDING),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            categories.forEach { category ->
                SearchChip(
                    label = category.label,
                    accent = activeCategory?.key == category.key,
                    onClick = {
                        viewModel.selectBrowseCategory(category.key)
                        activeCategory = category
                    }
                )
            }
        }

        val category = activeCategory
        when {
            // In-place catalog (service / decade): back chip + its rails.
            selection != null -> {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 10.dp, start = SEARCH_RAIL_EDGE_PADDING),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SearchChip(
                        label = "\u2039 Browse",
                        accent = false,
                        onClick = {
                            viewModel.clearBrowseSelection()
                            activeCategory = null
                        }
                    )
                    Text(
                        text = selection.entryName,
                        style = MaterialTheme.typography.titleMedium,
                        color = KBTextHi
                    )
                }

                rails.forEach { rail ->
                    BrowseRailRow(
                        rail = rail,
                        resolvedIds = resolvedIds,
                        watchedKeys = watchedKeys,
                        viewModel = viewModel,
                        onTileClick = onTileClick,
                        onTileLongClick = onTileLongClick
                    )
                }
            }

            // Submenu chips for the active category.
            category != null -> {
                if (submenuLoading) {
                    Text(
                        text = "Resolving ${category.label.lowercase()}...",
                        style = MaterialTheme.typography.bodySmall,
                        color = KBTextLo,
                        modifier = Modifier.padding(
                            top = 12.dp,
                            start = SEARCH_RAIL_EDGE_PADDING
                        )
                    )
                } else if (category.entries.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        category.entries.forEach { entry ->
                            SearchChip(
                                label = entry.name,
                                onClick = {
                                    viewModel.onBrowseEntryClicked(
                                        category.key,
                                        entry
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowseRailRow(
    rail: SearchViewModel.BrowseRail,
    resolvedIds: Map<String, String>,
    watchedKeys: Set<String>,
    viewModel: SearchViewModel,
    onTileClick: (SearchTitleResult) -> Unit,
    onTileLongClick: (SearchTitleResult, FocusRequester) -> Unit
) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(top = 12.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (rail.items.isEmpty()) {
            Text(
                text = if (rail.hasMore) "Loading..." else "Nothing here",
                style = MaterialTheme.typography.bodySmall,
                color = KBTextLo,
                modifier = Modifier.padding(
                    start = SEARCH_RAIL_EDGE_PADDING,
                    top = 4.dp
                )
            )
        }
        rail.items.forEach { studioItem ->
            val result = remember(studioItem.item.id) {
                browseItemToResult(studioItem)
            } ?: return@forEach
            val requester = remember(studioItem.item.id) { FocusRequester() }

            TitlePosterTile(
                result = result,
                watched = watchedTile(
                    result = result,
                    resolvedIds = resolvedIds,
                    watchedKeys = watchedKeys,
                    viewModel = viewModel
                ),
                onClick = { onTileClick(result) },
                onLongClick = { onTileLongClick(result, requester) },
                modifier = Modifier
                    .focusRequester(requester)
            )
        }
        if (rail.items.isNotEmpty() && rail.hasMore && !rail.isLoadingMore) {
            SearchChip(
                label = "More",
                accent = false,
                onClick = { viewModel.loadMoreBrowseRail(rail.key) },
                modifier = Modifier.padding(top = 40.dp)
            )
        }
    }
}

/** Discover payload -> the same tile/navigation shape search results use. */
private fun browseItemToResult(item: StudioItem): SearchTitleResult? {
    val name = item.item.title?.takeIf { it.isNotBlank() }
        ?: item.item.name?.takeIf { it.isNotBlank() }
        ?: return null
    val type = when (item.mediaType.lowercase()) {
        "movie" -> "movie"
        "tv", "series" -> "series"
        else -> return null
    }
    val id = "tmdb:${item.item.id}"
    val poster = item.item.posterPath
        ?.takeIf { it.isNotBlank() }
        ?.let { TmdbRepository.POSTER_BASE + it }
    return SearchTitleResult(
        id = id,
        type = type,
        name = name,
        poster = poster,
        year = (item.item.releaseDate ?: item.item.firstAirDate)
            ?.take(4)?.toIntOrNull(),
        rating = item.item.voteAverage?.takeIf { it > 0.0 },
        meta = MetaPreview(
            id = id,
            type = type,
            name = name,
            poster = poster
        )
    )
}

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

        PosterCaptions(
            title = result.name,
            year = result.year?.toString(),
            rating = result.rating,
            modifier = Modifier.padding(top = 5.dp)
        )
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
