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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.data.tmdb.TmdbSearchCollectionResult
import com.kennyb1201.kbstream.data.tmdb.TmdbSearchPersonResult
import com.kennyb1201.kbstream.data.tmdb.TmdbSearchStudioResult
import com.kennyb1201.kbstream.ui.components.PosterCard
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
    val watchedKeys by viewModel.watchedKeys.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val recentSearches by viewModel.recentSearches.collectAsStateWithLifecycle()
    val trendingResults by viewModel.trendingResults.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.loadTrending()
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.resetSearchState()
        }
    }

    val totalCount = results.size + actorResults.size + studioResults.size + collectionResults.size

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "hero") {
                SearchHero(
                    query = query,
                    onQueryChanged = viewModel::onQueryChanged,
                    totalCount = totalCount,
                    catalogCount = results.size,
                    actorCount = actorResults.size,
                    studioCount = studioResults.size,
                    collectionCount = collectionResults.size,
                    isLoading = isLoading
                )
            }

            if (query.isBlank() && recentSearches.isNotEmpty()) {
                item(key = "recent_section") {
                    Column {
                        SectionHeader(title = "Recent searches")
                        LazyRow(
                            contentPadding = PaddingValues(top = 2.dp, bottom = 4.dp),
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
                            contentPadding = PaddingValues(vertical = 2.dp)
                        ) {
                            items(
                                items = trendingResults,
                                key = { result: SearchTitleResult -> result.id }
                            ) { result: SearchTitleResult ->
                                TitlePosterTile(
                                    result = result,
                                    watched = false,
                                    onClick = { onItemClick(result.meta) }
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
                            contentPadding = PaddingValues(vertical = 2.dp)
                        ) {
                            items(
                                items = results,
                                key = { result: SearchTitleResult -> result.id }
                            ) { result: SearchTitleResult ->
                                TitlePosterTile(
                                    result = result,
                                    watched = viewModel.watchedKey(result.id, result.type) in watchedKeys,
                                    onClick = {
                                        viewModel.onResultOpened(result)
                                        onItemClick(result.meta)
                                    }
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
                            CollectionResultCard(
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
    }
}

@Composable
private fun SearchHero(
    query: String,
    onQueryChanged: (String) -> Unit,
    totalCount: Int,
    catalogCount: Int,
    actorCount: Int,
    studioCount: Int,
    collectionCount: Int,
    isLoading: Boolean
) {
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
                    modifier = Modifier.fillMaxWidth()
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

@Composable
private fun SearchRail(
    title: String,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit
) {
    Column {
        SectionHeader(title = title)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 2.dp)
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
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier.width(124.dp)
    ) {
        PosterCard(
            posterUrl = result.poster,
            contentDescription = result.name,
            isWatched = watched,
            onClick = onClick,
            modifier = Modifier
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

@Composable
private fun CollectionResultCard(
    collection: TmdbSearchCollectionResult,
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
        modifier = Modifier.width(150.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
        ) {
            PosterCard(
                posterUrl = collection.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
                contentDescription = collection.name,
                isWatched = false,
                onClick = onClick,
                modifier = Modifier
                    .width(128.dp)
                    .height(192.dp)
            )

            Text(
                text = collection.name,
                style = MaterialTheme.typography.bodyMedium,
                color = KBTextHi,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
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
