package com.kennyb1201.kbstream.ui.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Border
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.tmdb.TmdbSearchCollectionResult
import com.kennyb1201.kbstream.data.tmdb.TmdbSearchPersonResult
import com.kennyb1201.kbstream.data.tmdb.TmdbSearchStudioResult
import com.kennyb1201.kbstream.ui.components.KBSectionHeader
import com.kennyb1201.kbstream.ui.components.PosterCaptions
import com.kennyb1201.kbstream.ui.components.heroSourceElement
import com.kennyb1201.kbstream.ui.components.KBTextField
import com.kennyb1201.kbstream.ui.components.GlobalPosterCard
import com.kennyb1201.kbstream.ui.components.rememberLongPressModifier
import com.kennyb1201.kbstream.ui.components.VoiceSearchChip
import com.kennyb1201.kbstream.ui.components.voiceSearchAvailable
import com.kennyb1201.kbstream.ui.components.rememberPosterSize
import com.kennyb1201.kbstream.ui.components.rememberPosterTileWidth
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBFocusCard
import com.kennyb1201.kbstream.ui.theme.KBFocusChip
import com.kennyb1201.kbstream.ui.theme.KBFocusChipInset
import com.kennyb1201.kbstream.ui.theme.KBFocusGlow
import com.kennyb1201.kbstream.ui.theme.KBFocusGlowSmall
import com.kennyb1201.kbstream.ui.theme.KBFocusPressed
import com.kennyb1201.kbstream.ui.theme.KBScreenEdge
import com.kennyb1201.kbstream.ui.theme.KBShapePanel
import com.kennyb1201.kbstream.ui.theme.KBSpacing
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo

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
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    // Whether this device can run an in-app voice search at all - false on Fire
    // OS, which ships no recognizer (see [voiceSearchAvailable]).
    val voiceContext = androidx.compose.ui.platform.LocalContext.current
    val voiceSearchHere = remember(voiceContext) { voiceSearchAvailable(voiceContext) }

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
            placeholder = "Search titles, people, collections…",
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

        // Voice search trigger — the shared chip (see VoiceSearchChip) so this
        // screen and the guide's search overlay reach the recognizer and light
        // the mic up identically. Offered only where a recognizer exists: Fire
        // OS has none, so the chip there could only ever do nothing (see
        // [voiceSearchAvailable]).
        if (voiceSearchHere) {
            VoiceSearchChip(
                onTranscript = { spoken ->
                    onQueryChanged(spoken)
                    onSubmit()
                    focusManager.clearFocus()
                },
                onUnavailable = {
                    // No recognizer installed on this device.
                    keyboardController?.hide()
                },
                modifier = Modifier.padding(top = 10.dp)
            )
        }

        val statusText = when {
            // "…", not "...": one glyph, and the app's own spelling.
            isLoading -> "Searching…"
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
//
// It is the app's screen edge (KBScreenEdge), not a 20dp of its own: Search
// and the discover grids used to sit 4dp inside every other screen.
internal val SEARCH_RAIL_EDGE_PADDING = KBScreenEdge

/**
 * One titled rail of the search screen's results, on the app's vertical rhythm.
 *
 * The row's horizontal inset is [KBFocusChipInset], not the column's edge: the
 * LazyColumn this sits in has already placed the item at the screen edge, so a
 * second full inset put these tiles 4dp inside every other rail in the app (and
 * 20dp inside their own section heading). The room is taken inside the row's
 * own clip and immediately cancelled with `offset(x = -KBFocusChipInset)`, so
 * the tiles line up with the heading above them and the focused poster's border
 * and glow still have somewhere to grow.
 */
@Composable
internal fun SearchRail(
    title: String,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit
) {
    Column {
        KBSectionHeader(title = title)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(KBSpacing.md),
            contentPadding = PaddingValues(
                top = 2.dp,
                bottom = 2.dp,
                start = KBFocusChipInset,
                end = KBFocusChipInset
            ),
            modifier = Modifier.offset(x = -KBFocusChipInset)
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
    onLongClick: (() -> Unit)? = null,
    // Browse-submenu chips sit in a fixed-width lazy grid cell, so a long
    // curated name wraps onto a second line rather than being ellipsized
    // away. Every other chip is one line.
    labelMaxLines: Int = 1,
    // Browse-submenu chips open a dedicated discover screen; a chevron is what
    // says so, and it is the difference between a chip that filters in place
    // and one that leaves the screen. Plain select chips (suggestions, recent
    // searches) leave it off.
    showChevron: Boolean = false
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
        if (showChevron) {
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Text(
                    text = label,
                    maxLines = labelMaxLines,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = if (focused) KBAccent else KBTextLo.copy(alpha = 0.6f),
                    modifier = Modifier
                        .padding(start = 6.dp, top = 1.dp)
                        .size(14.dp)
                )
            }
        } else {
            Text(
                text = label,
                maxLines = labelMaxLines,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
            )
        }
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
    // Landscape tiles are wider than the poster they replace, so the tile's own
    // container follows the shape the card is about to draw.
    val tileWidth = rememberPosterTileWidth(posterSize.width)
    // The tile's own focus drives the caption marquee below.
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .width(tileWidth)
            .onFocusChanged { focused = it.hasFocus }
    ) {
        GlobalPosterCard(
            posterUrl = result.poster,
            backdropUrl = result.meta.background,
            logoUrl = result.meta.logo,
            contentDescription = result.name,
            isWatched = watched,
            isPartiallyWatched = isPartiallyWatched,
            onClick = onClick,
            onLongClick = onLongClick,
            posterWidth = posterSize.width,
            posterHeight = posterSize.height,
            // The add-on art above is usually present; the resolver still fills
            // in the alternate backdrop + clearlogo the rails use, exactly as
            // Home's scenery pass does.
            artId = result.meta.id,
            artType = result.meta.type,
            modifier = modifier
                // Key comes from meta, not the result: meta is what the
                // click actually navigates with.
                .heroSourceElement(result.meta.type, result.meta.id)
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
        // these had border + color feedback but no movement and no press.
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
    GlobalPosterCard(
        posterUrl = collection.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
        backdropUrl = collection.backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" },
        contentDescription = collection.name,
        isWatched = false,
        onClick = onClick,
        posterWidth = posterSize.width,
        posterHeight = posterSize.height
    )
}
