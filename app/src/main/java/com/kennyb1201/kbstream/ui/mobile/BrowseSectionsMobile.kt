package com.kennyb1201.kbstream.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kennyb1201.kbstream.data.library.HiddenTitles
import com.kennyb1201.kbstream.data.tmdb.StudioItem
import com.kennyb1201.kbstream.data.tmdb.StudioSection
import com.kennyb1201.kbstream.data.tmdb.TmdbGenre
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.ui.browse.BrowseReturnFocus
import com.kennyb1201.kbstream.ui.browse.BrowseReturnTarget
import com.kennyb1201.kbstream.ui.components.LibraryAddTarget
import com.kennyb1201.kbstream.ui.components.PosterContextAction
import com.kennyb1201.kbstream.ui.components.hideTarget
import com.kennyb1201.kbstream.ui.components.imageOverridePosterUrl
import com.kennyb1201.kbstream.ui.components.rememberHiddenTitleKeys
import com.kennyb1201.kbstream.ui.components.rememberImageOverridePaths
import com.kennyb1201.kbstream.ui.components.touch.GenreChipRow
import com.kennyb1201.kbstream.ui.components.touch.KBButton
import com.kennyb1201.kbstream.ui.components.touch.PosterCard
import com.kennyb1201.kbstream.ui.components.touch.PosterContextMenu
import com.kennyb1201.kbstream.ui.components.watchedMenuDescription
import com.kennyb1201.kbstream.ui.components.watchedMenuLabel
import com.kennyb1201.kbstream.ui.studio.BrandLogo
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid

/**
 * The sectioned browse pages (a genre, a keyword, a studio/network/service and a
 * decade), for a finger.
 *
 * All four TV pages are the same page with a different header: rails stream in
 * one at a time, each rail is a horizontal row of posters with its own paging
 * state, a long press raises the poster menu, and every card press records the
 * place its Detail page returns to. Reproducing that five times would give the
 * app five touch layouts to drift apart, so the layout lives here once and the
 * three screens hand it their own ViewModel's flows (Collection is the fourth
 * page of the family and is ported next to this file, because it is one rail
 * with a fixed header rather than a page of rails).
 *
 * The TV shape is a pin rail above a vertical list of horizontal rails. That is
 * the wrong shape for a thumb - it puts most of the catalogue behind a sideways
 * swipe per rail - so each rail becomes a titled band in ONE vertical grid. The
 * poster tile, the watched/partial badges, the caption block, the hidden-title
 * filter, the context sheet and the return record are all the shared ones.
 *
 * Paging is explicit on touch: a rail that has another page ends its band with a
 * LOAD MORE button. The TV triggers the next page from a scroll delta on the
 * rail's own row, which a phone grid has nowhere to read.
 *
 * There is deliberately no focus plumbing: the TV's return record carried a
 * rail key and a card key so a D-pad could be put back on the exact poster. On
 * touch the same record is used to scroll the grid to that card - the phone
 * equivalent of putting it back under the viewer.
 */

/** One rail's paging state, as the shared page needs it. */
internal data class RailPaging(
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false
) {
    val tailVisible: Boolean get() = hasMore || isLoadingMore
}

/** Everything a guided browse header needs, independent of which sheet it is. */
internal data class SectionsHeader(
    val eyebrow: String?,
    val title: String,
    val subtitle: String? = null,
    val meta: String? = null,
    val description: String? = null,
    val logoUrls: List<String> = emptyList()
)

/** The media type the app compares watched state under. */
internal fun sectionsMediaType(mediaType: String?): String =
    when (mediaType?.trim()?.lowercase()) {
        "tv", "series" -> "series"
        else -> "movie"
    }

private sealed interface BrowseEntry {
    val key: String

    data class SectionTitle(val railIndex: Int, val title: String) : BrowseEntry {
        override val key: String get() = "rail:$railIndex:$title"
    }

    data class Card(
        val railIndex: Int,
        val railKey: String,
        val position: Int,
        val item: StudioItem
    ) : BrowseEntry {
        override val key: String get() = "card:$railIndex:$position:${cardKey(item)}"
    }

    data class More(val railKey: String) : BrowseEntry {
        override val key: String get() = "more:$railKey"
    }
}

/** The key a rail card is identified by, the same one the TV rails use. */
internal fun cardKey(item: StudioItem): String = "${item.mediaType}:${item.item.id}"

@Composable
internal fun SectionsBrowseMobile(
    header: SectionsHeader,
    sectionsRaw: List<StudioSection>,
    isLoading: Boolean,
    error: String?,
    screenKey: String,
    emptyText: String,
    resolvedIds: Map<String, String>,
    watchedKeys: Set<String>,
    partialWatchedKeys: Set<String>,
    lookupKey: (Int, String) -> String,
    watchedKey: (String, String) -> String,
    genres: List<TmdbGenre> = emptyList(),
    selectedGenreId: Int? = null,
    onSelectGenre: (Int?) -> Unit = {},
    paging: (String) -> RailPaging = { RailPaging() },
    onLoadMore: (String) -> Unit = {},
    onRetry: () -> Unit,
    onOpenItem: (StudioItem) -> Unit,
    onToggleWatched: (StudioItem, Boolean) -> Unit
) {
    // A hidden title drops out of every rail, exactly as on TV - including a
    // rail that then has nothing left in it.
    val hiddenTitleKeys = rememberHiddenTitleKeys()
    val sections = remember(sectionsRaw, resolvedIds, hiddenTitleKeys) {
        sectionsRaw.mapNotNull { section ->
            val visible = section.items.filterNot { railItem ->
                val mediaType = sectionsMediaType(railItem.mediaType)
                HiddenTitles.hides(
                    hiddenTitleKeys,
                    mediaType,
                    railItem.item.title ?: railItem.item.name,
                    railItem.item.releaseDate?.take(4)?.toIntOrNull()
                        ?: railItem.item.firstAirDate?.take(4)?.toIntOrNull(),
                    railItem.item.id.toString(),
                    resolvedIds[lookupKey(railItem.item.id, mediaType)]
                )
            }
            section.takeIf { visible.isNotEmpty() }?.copy(items = visible)
        }
    }

    // The paging states are part of the entry list: a rail that gains
    // another page must gain its LOAD MORE row, and one that is loading must
    // start saying so. The signature is what makes that visible to `remember`
    // without re-building the whole list on every recomposition.
    val pagingSignature = sections.joinToString("|") { section ->
        val state = paging(section.title)
        "${section.title}:${state.hasMore}:${state.isLoadingMore}"
    }
    val entries = remember(sections, pagingSignature) {
        buildList {
            sections.forEachIndexed { railIndex, section ->
                add(BrowseEntry.SectionTitle(railIndex, section.title))
                section.items.forEachIndexed { position, item ->
                    add(
                        BrowseEntry.Card(
                            railIndex = railIndex,
                            railKey = section.title,
                            position = position,
                            item = item
                        )
                    )
                }
                if (paging(section.title).tailVisible) {
                    add(BrowseEntry.More(section.title))
                }
            }
        }
    }

    val overridePaths = rememberImageOverridePaths()
    val fanUrls = remember(sections, overridePaths) {
        sections.asSequence()
            .flatMap { it.items.asSequence() }
            .mapNotNull { railItem ->
                val path = railItem.item.posterPath ?: return@mapNotNull null
                imageOverridePosterUrl(
                    paths = overridePaths,
                    rawId = railItem.item.id.toString(),
                    fallbackUrl = "${TmdbRepository.POSTER_BASE}$path"
                )
            }
            .distinct()
            .take(5)
            .toList()
    }

    val gridState = remember { LazyGridState() }
    var returnTarget by remember { mutableStateOf<BrowseReturnTarget?>(null) }
    var menu by remember { mutableStateOf<Pair<StudioItem, BrowseReturnTarget>?>(null) }

    LaunchedEffect(screenKey) {
        returnTarget = BrowseReturnFocus.consume(screenKey)
    }

    // Puts the grid back on the card the Detail page was opened from. The rails
    // land after the grid composes, so the record is held until the card is
    // actually in the list.
    LaunchedEffect(returnTarget, entries) {
        val target = returnTarget ?: return@LaunchedEffect
        val index = entries.indexOfFirst { entry ->
            entry is BrowseEntry.Card &&
                entry.railKey == target.railKey &&
                cardKey(entry.item) == target.itemId
        }.takeIf { it >= 0 }
            ?: entries.indexOfFirst { it is BrowseEntry.Card }.takeIf { it >= 0 }
            ?: return@LaunchedEffect
        runCatching { gridState.scrollToItem(index) }
        returnTarget = null
    }

    fun record(sectionTitle: String, railIndex: Int, position: Int, item: StudioItem): BrowseReturnTarget =
        BrowseReturnTarget(
            screenKey = screenKey,
            itemId = cardKey(item),
            position = position,
            railKey = sectionTitle,
            railIndex = railIndex
        )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid)
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 104.dp),
            state = gridState,
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 16.dp,
                bottom = 28.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                BrowseHeaderPanel(header = header, fanUrls = fanUrls)
            }

            if (genres.isNotEmpty()) {
                item(key = "genres", span = { GridItemSpan(maxLineSpan) }) {
                    GenreChipRow(
                        genres = genres,
                        selectedGenreId = selectedGenreId,
                        onSelect = onSelectGenre,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            when {
                isLoading && sections.isEmpty() -> {
                    item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                        BrowseStatus(message = "Loading…")
                    }
                }

                // A failed load says so instead of reading as an empty page -
                // the TV pages fixed this and the phone must not regress it.
                error != null && sections.isEmpty() -> {
                    item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                        BrowseStatus(
                            message = "Error: $error",
                            action = "TRY AGAIN",
                            onAction = onRetry
                        )
                    }
                }

                sections.isEmpty() -> {
                    item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                        BrowseStatus(message = emptyText)
                    }
                }

                else -> items(
                    items = entries,
                    key = { it.key },
                    span = { entry ->
                        if (entry is BrowseEntry.Card) GridItemSpan(1)
                        else GridItemSpan(maxLineSpan)
                    }
                ) { entry ->
                    when (entry) {
                        is BrowseEntry.SectionTitle -> Text(
                            text = entry.title.uppercase(),
                            color = KBTextLo,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp)
                        )

                        is BrowseEntry.Card -> {
                            val item = entry.item
                            val mediaType = sectionsMediaType(item.mediaType)
                            val imdbId = resolvedIds[lookupKey(item.item.id, mediaType)]
                            val tmdbKeyId = "tmdb:${item.item.id}"
                            val watched =
                                imdbId?.let { watchedKey(it, mediaType) in watchedKeys } == true ||
                                    watchedKey(tmdbKeyId, mediaType) in watchedKeys
                            // The checkmark wins over the eye, tested here rather
                            // than trusted from the view model's subtraction.
                            val partial =
                                !watched && (
                                    imdbId?.let {
                                        watchedKey(it, mediaType) in partialWatchedKeys
                                    } == true ||
                                        watchedKey(tmdbKeyId, mediaType) in partialWatchedKeys
                                    )

                            BrowseTile(
                                item = item,
                                isWatched = watched,
                                isPartiallyWatched = partial,
                                onClick = {
                                    BrowseReturnFocus.record(
                                        record(entry.railKey, entry.railIndex, entry.position, item)
                                    )
                                    onOpenItem(item)
                                },
                                onLongClick = {
                                    menu = item to record(
                                        entry.railKey,
                                        entry.railIndex,
                                        entry.position,
                                        item
                                    )
                                }
                            )
                        }

                        is BrowseEntry.More -> {
                            val state = paging(entry.railKey)
                            KBButton(
                                label = if (state.isLoadingMore) "LOADING…" else "LOAD MORE",
                                enabled = !state.isLoadingMore,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                onLoadMore(entry.railKey)
                            }
                        }
                    }
                }
            }
        }

        // The row menu: the same sheet the rest of the app uses, with the same
        // library, hide, details and watched rows the TV rail menu holds.
        menu?.let { (selected, target) ->
            val mediaType = sectionsMediaType(selected.mediaType)
            val imdbId = resolvedIds[lookupKey(selected.item.id, mediaType)]
            val tmdbKeyId = "tmdb:${selected.item.id}"
            val isWatched =
                imdbId?.let { watchedKey(it, mediaType) in watchedKeys } == true ||
                    watchedKey(tmdbKeyId, mediaType) in watchedKeys

            PosterContextMenu(
                title = selected.item.title ?: selected.item.name ?: "",
                subtitle = (selected.item.releaseDate ?: selected.item.firstAirDate)
                    ?.take(4)
                    ?.let { year -> "$year · ${if (mediaType == "series") "Series" else "Movie"}" },
                libraryTarget = LibraryAddTarget(
                    mediaType = mediaType,
                    imdbId = imdbId,
                    tmdbId = selected.item.id,
                    title = selected.item.title ?: selected.item.name ?: "Untitled",
                    year = (selected.item.releaseDate ?: selected.item.firstAirDate)
                        ?.take(4)?.toIntOrNull(),
                    posterUrl = selected.item.posterPath
                        ?.takeIf { it.isNotBlank() }
                        ?.let { TmdbRepository.POSTER_BASE + it }
                ),
                hideTarget = hideTarget(
                    selected.item.title ?: selected.item.name ?: "",
                    selected.mediaType,
                    selected.item.posterPath
                        ?.takeIf { it.isNotBlank() }
                        ?.let { "${TmdbRepository.POSTER_BASE}$it" },
                    listOf(selected.item.id.toString(), imdbId),
                    year = (selected.item.releaseDate ?: selected.item.firstAirDate)
                        ?.take(4)?.toIntOrNull()
                ),
                actions = listOf(
                    PosterContextAction(
                        label = "Go to Details",
                        description = "Open this title's detail page"
                    ) {
                        menu = null
                        // The menu opens the same Detail page the card does,
                        // so it raises the same return record.
                        BrowseReturnFocus.record(target)
                        onOpenItem(selected)
                    },
                    PosterContextAction(
                        label = watchedMenuLabel(
                            isWatched = isWatched,
                            mediaType = selected.mediaType
                        ),
                        description = watchedMenuDescription(
                            isWatched = isWatched,
                            mediaType = selected.mediaType
                        )
                    ) {
                        menu = null
                        onToggleWatched(selected, isWatched)
                    }
                ),
                onDismiss = { menu = null }
            )
        }
    }
}

/** The page header: eyebrow, title, the brand mark or poster fan, and the facts. */
@Composable
private fun BrowseHeaderPanel(
    header: SectionsHeader,
    fanUrls: List<String>
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(KBShapeCard)
            .background(KBSurface)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            header.eyebrow?.takeIf { it.isNotBlank() }?.let { eyebrow ->
                Text(
                    text = eyebrow.uppercase(),
                    color = KBAccent,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = header.title,
                color = KBTextHi,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
            header.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                Text(
                    text = subtitle,
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            header.meta?.takeIf { it.isNotBlank() }?.let { meta ->
                Text(
                    text = meta,
                    color = KBAccent,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            header.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    text = description,
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        when {
            header.logoUrls.isNotEmpty() -> BrandLogo(
                urls = header.logoUrls,
                name = header.title,
                modifier = Modifier
                    .width(132.dp)
                    .height(60.dp)
                    .padding(start = 12.dp)
            )

            fanUrls.isNotEmpty() -> Row(
                modifier = Modifier.padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // A small fan, the touch reading of the TV header: the top
                // titles' own posters, overlapped and tilted, with the middle
                // one forward.
                val rotations = listOf(-9f, -4f, 0f, 4f, 9f)
                val widths = listOf(44.dp, 50.dp, 58.dp, 50.dp, 44.dp)
                val heights = listOf(66.dp, 75.dp, 87.dp, 75.dp, 66.dp)
                fanUrls.take(5).forEachIndexed { index, url ->
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .offset(x = (-10 * index).dp)
                            .rotate(rotations[index])
                            .clip(KBShapeCard)
                            .width(widths[index])
                            .height(heights[index])
                    )
                }
            }
        }
    }
}

/** One poster, with the shared caption block's title/year/rating lines. */
@Composable
private fun BrowseTile(
    item: StudioItem,
    isWatched: Boolean,
    isPartiallyWatched: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        PosterCard(
            posterUrl = item.item.posterPath
                ?.let { "${TmdbRepository.POSTER_BASE}$it" },
            contentDescription = item.item.title ?: item.item.name,
            isWatched = isWatched,
            isPartiallyWatched = isPartiallyWatched,
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
        )
        Text(
            text = item.item.title ?: item.item.name ?: "Untitled",
            color = KBTextHi,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        listOfNotNull(
            (item.item.releaseDate ?: item.item.firstAirDate)?.take(4),
            item.item.voteAverage?.takeIf { it > 0.0 }?.let { "★ %.1f".format(it) }
        ).takeIf { it.isNotEmpty() }?.let { line ->
            Text(
                text = line.joinToString(" · "),
                color = KBTextLo,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** A full-span status line, with an optional action under it. */
@Composable
private fun BrowseStatus(
    message: String,
    action: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = message,
            color = KBTextLo,
            style = MaterialTheme.typography.bodyMedium
        )
        if (action != null && onAction != null) {
            KBButton(label = action, onClick = onAction)
        }
    }
}
