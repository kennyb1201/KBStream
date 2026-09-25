package com.kennyb1201.kbstream.ui.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import com.kennyb1201.kbstream.ui.settings.AppPreferences
import com.kennyb1201.kbstream.data.tmdb.TmdbSearchCollectionResult
import com.kennyb1201.kbstream.data.tmdb.TmdbSearchPersonResult
import com.kennyb1201.kbstream.data.tmdb.TmdbSearchStudioResult
import com.kennyb1201.kbstream.ui.components.KBSectionHeader
import com.kennyb1201.kbstream.ui.components.PosterCaptions
import com.kennyb1201.kbstream.ui.components.heroSharedElement
import com.kennyb1201.kbstream.ui.components.KBTextField
import com.kennyb1201.kbstream.data.library.LibraryIds
import com.kennyb1201.kbstream.ui.components.LibraryAddToListDialog
import com.kennyb1201.kbstream.ui.components.LibraryAddTarget
import com.kennyb1201.kbstream.ui.components.PosterCard
import com.kennyb1201.kbstream.data.library.HiddenTitles
import com.kennyb1201.kbstream.ui.components.hideTarget
import com.kennyb1201.kbstream.ui.components.PosterContextAction
import com.kennyb1201.kbstream.ui.components.rememberHiddenTitleKeys
import com.kennyb1201.kbstream.ui.components.PosterContextMenu
import com.kennyb1201.kbstream.ui.components.watchedMenuLabel
import com.kennyb1201.kbstream.ui.components.watchedMenuDescription
import com.kennyb1201.kbstream.ui.components.rememberLongPressModifier
import com.kennyb1201.kbstream.ui.components.PosterSize
import com.kennyb1201.kbstream.ui.components.rememberPosterSize
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBFocusCard
import com.kennyb1201.kbstream.ui.theme.KBFocusChip
import com.kennyb1201.kbstream.ui.theme.KBFocusGlow
import com.kennyb1201.kbstream.ui.theme.KBFocusGlowSmall
import com.kennyb1201.kbstream.ui.theme.KBFocusPressed
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBShapeChip
import com.kennyb1201.kbstream.ui.theme.KBShapePanel
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid

// Part 2 of the SearchScreen.kt split. This file's
// declarations were moved here verbatim by scripts/split_kotlin.py,
// which cuts only at top-level boundaries -- the editor's file view
// stops at ~60 KB, so the original had an unreachable tail. Only the
// declarations another part calls were widened to `internal`.

/**
 * Builds an add-on search rail header: optional addon name and catalog type
 * around the catalog label, e.g. "AIOMetadata · Trending · Series". Mirrors
 * HomeScreen.homeRailTitle. `type` is null for mixed rails (e.g. a standard
 * search endpoint that returns movies + series in one rail).
 */
internal fun searchRailTitle(
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
internal fun watchedTile(
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

/**
 * Eye-badge twin of [watchedTile]: same id resolution, compared against the
 * partially-watched key set. The tile only shows the eye when the completed
 * checkmark is absent.
 */
internal fun partialWatchedTile(
    result: SearchTitleResult,
    resolvedIds: Map<String, String>,
    partialWatchedKeys: Set<String>,
    viewModel: SearchViewModel
): Boolean {
    val tmdbId = result.id.removePrefix("tmdb:").toIntOrNull()
    val keyId = if (tmdbId != null) {
        resolvedIds[viewModel.lookupKey(tmdbId, result.type)]
    } else {
        result.id
    } ?: return false

    return viewModel.watchedKey(keyId, result.type) in partialWatchedKeys
}

@Composable
internal fun SearchHero(
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
            .background(KBSurface, KBShapePanel)
            .border(1.dp, KBTextLo.copy(alpha = 0.25f), KBShapePanel)
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
                shape = KBShapeChip
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
                focusedScale = KBFocusChip,
                pressedScale = KBFocusPressed
            ),
            border = ClickableSurfaceDefaults.border(
                border = Border(
                    border = BorderStroke(1.dp, KBTextLo.copy(alpha = 0.35f)),
                    shape = KBShapeChip
                ),
                focusedBorder = Border(
                    border = BorderStroke(2.dp, KBAccent),
                    shape = KBShapeChip
                )
            ),
            glow = ClickableSurfaceDefaults.glow(
                focusedGlow = Glow(
                    elevationColor = KBAccent,
                    elevation = KBFocusGlowSmall
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

// Edge inset shared by the search screen's column and every rail's
// contentPadding. Living in contentPadding (rather than a parent modifier
// padding) keeps the ends of each rail inside the LazyRow's clip bounds so
// focused poster borders + glow never get cut off at the first/last item.
internal val SEARCH_RAIL_EDGE_PADDING = 20.dp

@Composable
internal fun SearchBrowseBrowser(
    viewModel: SearchViewModel,
    categories: List<BrowseCategory>,
    submenuLoading: Boolean,
    onChipLongPress: ((String, BrowseEntry) -> Unit)? = null,
    onCategoryLongPress: ((BrowseCategory) -> Unit)? = null
) {
    // Selected category lives in the activity-scoped ViewModel, so backing
    // out of a discover screen re-opens the same submenu instead of the
    // browser resetting to no selection.
    val selectedKey by viewModel.selectedBrowseCategoryKey.collectAsStateWithLifecycle()
    val activeCategory = categories.firstOrNull { it.key == selectedKey }

    // Re-focus the chip that launched the discover screen we just backed
    // out of. The matching chip renders with grabInitialFocus so the TV
    // focus system lands on it (which also scrolls it into view); the
    // stored chip is cleared once consumed so later recompositions don't
    // steal focus back. Consumption is tracked inside SubmenuChipFlowRow
    // (scoped to the submenu that actually rendered the chip).
    val returnChip = viewModel.browseReturnChip

    Column(modifier = Modifier.padding(top = 8.dp)) {
        KBSectionHeader(title = "Browse")

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
                    },
                    onLongClick = onCategoryLongPress?.let { handler ->
                        { handler(category) }
                    }
                )
            }
        }

        // Submenu chips for the active category; each opens a dedicated
        // discover screen (Tag / Studio / Collection / Decade). Wrapped
        // FlowRow, not one long scrolling row: the big submenus (91 entries
        // in Services & Networks) would otherwise need 90 D-pad right-presses
        // to reach the end — wrapped rows let focus move straight DOWN.
        val category = activeCategory
        if (category != null) {
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
                // Horizontal edge padding matches the category strip /
                // poster rails so chip borders never clip at the screen
                // edge; the 10.dp top gap mirrors the old row spacing.
                SubmenuChipFlowRow(
                    entries = category.entries,
                    categoryKey = category.key,
                    onEntryClicked = viewModel::onBrowseEntryClicked,
                    onEntryLongClick = onChipLongPress,
                    returnChip = returnChip,
                    onReturnChipConsumed = {
                        viewModel.browseReturnChip = null
                    }
                )
            }
        }
    }
}

/**
 * Wrapped multi-row chip grid for the browse submenu (FlowRow). One long
 * horizontal row stops scaling once a category holds dozens of entries:
 * the Fire TV D-pad would need a right-press per chip to reach the far
 * end. Wrapping into rows keeps every entry a few presses away, and
 * vertical D-pad movement walks the rows naturally.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubmenuChipFlowRow(
    entries: List<BrowseEntry>,
    categoryKey: String,
    onEntryClicked: (String, BrowseEntry) -> Unit,
    onEntryLongClick: ((String, BrowseEntry) -> Unit)? = null,
    returnChip: Pair<String, Int>?,
    onReturnChipConsumed: () -> Unit
) {
    var returnChipConsumed by remember { mutableStateOf(false) }

    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = 10.dp,
                start = SEARCH_RAIL_EDGE_PADDING,
                end = SEARCH_RAIL_EDGE_PADDING
            ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        entries.forEachIndexed { entryIndex, entry ->
            val isReturnChip = !returnChipConsumed &&
                returnChip?.first == categoryKey &&
                returnChip?.second == entryIndex
            SearchChip(
                label = entry.name,
                onClick = { onEntryClicked(categoryKey, entry) },
                onLongClick = onEntryLongClick?.let { handler ->
                    { handler(categoryKey, entry) }
                },
                grabInitialFocus = isReturnChip,
                onInitialFocusConsumed = {
                    returnChipConsumed = true
                    onReturnChipConsumed()
                }
            )
        }
    }
}

@Composable
internal fun SearchRail(
    title: String,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit
) {
    Column {
        KBSectionHeader(title = title)
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
internal fun SearchChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = true,
    grabInitialFocus: Boolean = false,
    onInitialFocusConsumed: (() -> Unit)? = null,
    // Long press (hold Select/Enter) opens the caller's context menu, e.g.
    // Hide on a browse chip. Null keeps a plain select-only chip.
    onLongClick: (() -> Unit)? = null
) {
    var focused by remember { mutableStateOf(false) }
    // Return-chip restore: when this chip is the one that opened the
    // discover screen we just backed out of, pull TV focus onto it (the
    // focus system also scrolls its row to make it visible), then tell the
    // caller so later recompositions don't re-grab.
    val returnFocusRequester = remember { FocusRequester() }
    if (grabInitialFocus) {
        LaunchedEffect(returnFocusRequester) {
            runCatching { returnFocusRequester.requestFocus() }
            onInitialFocusConsumed?.invoke()
        }
    }
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
        // Chip step of the shared focus scale, plus the press-in. These browse
        // chips lit up on focus but never moved, so a Select press read as
        // nothing happening at all.
        scale = CardDefaults.scale(
            scale = 1f,
            focusedScale = KBFocusChip,
            pressedScale = KBFocusPressed
        ),
        glow = CardDefaults.glow(
            focusedGlow = Glow(
                elevationColor = KBAccent,
                elevation = KBFocusGlowSmall
            )
        ),
        modifier = modifier
            .then(rememberLongPressModifier(onLongClick))
            .focusRequester(returnFocusRequester)
            .onFocusChanged { focused = it.isFocused }
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
internal fun TitlePosterTile(
    result: SearchTitleResult,
    watched: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    isPartiallyWatched: Boolean = false
) {
    val posterSize = rememberPosterSize()
    // The tile's own focus drives the caption marquee below.
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .width(posterSize.width)
            .onFocusChanged { focused = it.hasFocus }
    ) {
        PosterCard(
            posterUrl = result.poster,
            contentDescription = result.name,
            isWatched = watched,
            isPartiallyWatched = isPartiallyWatched,
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier
                // Key comes from meta, not the result: meta is what the
                // click actually navigates with.
                .heroSharedElement(result.meta.type, result.meta.id)
                .width(posterSize.width)
                .height(posterSize.height)
        )

        PosterCaptions(
            title = result.name,
            focused = focused,
            year = result.year?.toString(),
            rating = result.rating,
            modifier = Modifier.padding(top = 5.dp)
        )
    }
}

@Composable
internal fun PersonResultCard(
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
        // Card step of the shared focus scale: a search result is a card, and
        // these had border + colour feedback but no movement and no press.
        scale = CardDefaults.scale(
            scale = 1f,
            focusedScale = KBFocusCard,
            pressedScale = KBFocusPressed
        ),
        glow = CardDefaults.glow(
            focusedGlow = Glow(
                elevationColor = KBAccent,
                elevation = KBFocusGlow
            )
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
internal fun StudioResultCard(
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
        // Same card treatment as the person result, so the two rows of results
        // move together (see KBFocus* in the theme).
        scale = CardDefaults.scale(
            scale = 1f,
            focusedScale = KBFocusCard,
            pressedScale = KBFocusPressed
        ),
        glow = CardDefaults.glow(
            focusedGlow = Glow(
                elevationColor = KBAccent,
                elevation = KBFocusGlow
            )
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
internal fun CollectionPosterTile(
    collection: TmdbSearchCollectionResult,
    onClick: () -> Unit
) {
    val posterSize = rememberPosterSize()
    PosterCard(
        posterUrl = collection.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
        contentDescription = collection.name,
        isWatched = false,
        onClick = onClick,
        modifier = Modifier
            .width(posterSize.width)
            .height(posterSize.height)
    )
}

@Composable
internal fun SearchMessagePanel(
    title: String,
    body: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(KBSurface, KBShapeCard)
            .border(1.dp, KBTextLo.copy(alpha = 0.25f), KBShapeCard)
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
