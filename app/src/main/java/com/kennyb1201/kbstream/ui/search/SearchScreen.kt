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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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

private val SearchBg = Color(0xFF0B1220)
private val SearchPanel = Color(0xFF101A2B)
private val SearchPanelRaised = Color(0xFF162338)
private val SearchAccent = Color(0xFF4FC3F7)
private val SearchTextMuted = Color(0xFF9FB0C7)

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

    val totalCount = results.size + actorResults.size + studioResults.size + collectionResults.size
    val topResult = results.firstOrNull()
    val remainingResults = if (results.size > 1) results.drop(1) else emptyList()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        SearchBg,
                        Color(0xFF0F1B2F),
                        SearchBg
                    )
                )
            )
            .padding(horizontal = 24.dp, vertical = 20.dp)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
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

            if (query.isBlank() && recentSearches.isEmpty()) {
                item(key = "empty_idle") {
                    SearchMessagePanel(
                        title = "Search",
                        body = "Start typing to search across titles, actors, collections, and studios."
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

            if (!isLoading && topResult != null) {
                item(key = "best_match") {
                    Column {
                        SectionHeader(title = "Best match")
                        FeaturedSearchResult(
                            meta = topResult,
                            watched = viewModel.watchedKey(topResult.id, topResult.type) in watchedKeys,
                            onClick = {
                                viewModel.onResultOpened(topResult)
                                onItemClick(topResult)
                            }
                        )
                    }
                }
            }

            if (!isLoading && remainingResults.isNotEmpty()) {
                item(key = "titles_section") {
                    Column {
                        SectionHeader(title = "Titles")
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            remainingResults.forEach { meta ->
                                val watched = viewModel.watchedKey(meta.id, meta.type) in watchedKeys
                                SearchResultRow(
                                    meta = meta,
                                    watched = watched,
                                    onClick = {
                                        viewModel.onResultOpened(meta)
                                        onItemClick(meta)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (!isLoading && actorResults.isNotEmpty()) {
                item(key = "actors_rail") {
                    SearchRail(
                        title = "Actors"
                    ) {
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

            if (!isLoading && collectionResults.isNotEmpty()) {
                item(key = "collections_rail") {
                    SearchRail(
                        title = "Collections"
                    ) {
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

            if (!isLoading && studioResults.isNotEmpty()) {
                item(key = "studios_rail") {
                    SearchRail(
                        title = "Studios"
                    ) {
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
            .background(SearchPanel.copy(alpha = 0.94f), RoundedCornerShape(18.dp))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
            .padding(18.dp)
    ) {
        Text(
            text = "Search",
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White
        )

        Box(
            modifier = Modifier
                .padding(top = 14.dp)
                .fillMaxWidth()
                .background(SearchPanelRaised, RoundedCornerShape(14.dp))
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            if (query.isBlank()) {
                Text(
                    text = "Search titles, people, collections...",
                    color = SearchTextMuted,
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            BasicTextField(
                value = query,
                onValueChange = onQueryChanged,
                singleLine = true,
                textStyle = TextStyle(
                    color = Color.White,
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize
                ),
                modifier = Modifier.fillMaxWidth()
            )
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
                color = SearchAccent,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 10.dp)
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
                color = SearchTextMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = Color.White,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 8.dp)
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
            horizontalArrangement = Arrangement.spacedBy(12.dp),
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
        focused -> SearchAccent
        accent -> Color.White.copy(alpha = 0.18f)
        else -> Color.White.copy(alpha = 0.10f)
    }

    Card(
        onClick = onClick,
        colors = CardDefaults.colors(
            containerColor = if (focused) SearchPanelRaised else SearchPanel,
            contentColor = Color.White,
            focusedContainerColor = SearchPanelRaised,
            focusedContentColor = Color.White,
            pressedContainerColor = SearchPanelRaised,
            pressedContentColor = Color.White
        ),
        border = CardDefaults.border(
            border = Border(BorderStroke(1.dp, borderColor)),
            focusedBorder = Border(BorderStroke(2.dp, SearchAccent))
        ),
        modifier = modifier.onFocusChanged { focused = it.isFocused }
    ) {
        Text(
            text = label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun FeaturedSearchResult(
    meta: MetaPreview,
    watched: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.colors(
            containerColor = SearchPanel.copy(alpha = 0.96f),
            contentColor = Color.White,
            focusedContainerColor = SearchPanelRaised,
            focusedContentColor = Color.White,
            pressedContainerColor = SearchPanelRaised,
            pressedContentColor = Color.White
        ),
        border = CardDefaults.border(
            border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))),
            focusedBorder = Border(BorderStroke(2.dp, SearchAccent))
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PosterCard(
                posterUrl = meta.poster,
                contentDescription = meta.name,
                isWatched = watched,
                onClick = onClick,
                modifier = Modifier
                    .width(118.dp)
                    .height(177.dp)
            )

            Column(
                modifier = Modifier
                    .padding(start = 14.dp)
                    .fillMaxWidth()
            ) {
                TypePill(meta.type)

                Text(
                    text = meta.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    meta: MetaPreview,
    watched: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.colors(
            containerColor = SearchPanel.copy(alpha = 0.92f),
            contentColor = Color.White,
            focusedContainerColor = SearchPanelRaised,
            focusedContentColor = Color.White,
            pressedContainerColor = SearchPanelRaised,
            pressedContentColor = Color.White
        ),
        border = CardDefaults.border(
            border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))),
            focusedBorder = Border(BorderStroke(2.dp, SearchAccent))
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PosterCard(
                posterUrl = meta.poster,
                contentDescription = meta.name,
                isWatched = watched,
                onClick = onClick,
                modifier = Modifier
                    .width(92.dp)
                    .height(138.dp)
            )

            Column(
                modifier = Modifier
                    .padding(start = 12.dp)
                    .weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TypePill(meta.type)
                    if (watched) {
                        Text(
                            text = "WATCHED",
                            color = SearchAccent,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Text(
                    text = meta.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
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
            containerColor = SearchPanel.copy(alpha = 0.92f),
            contentColor = Color.White,
            focusedContainerColor = SearchPanelRaised,
            focusedContentColor = Color.White,
            pressedContainerColor = SearchPanelRaised,
            pressedContentColor = Color.White
        ),
        border = CardDefaults.border(
            border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))),
            focusedBorder = Border(BorderStroke(2.dp, SearchAccent))
        ),
        modifier = Modifier.width(320.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            TypePill("actor")

            Text(
                text = person.name,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp)
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
                    style = MaterialTheme.typography.bodyMedium,
                    color = SearchTextMuted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp)
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
            containerColor = SearchPanel.copy(alpha = 0.92f),
            contentColor = Color.White,
            focusedContainerColor = SearchPanelRaised,
            focusedContentColor = Color.White,
            pressedContainerColor = SearchPanelRaised,
            pressedContentColor = Color.White
        ),
        border = CardDefaults.border(
            border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))),
            focusedBorder = Border(BorderStroke(2.dp, SearchAccent))
        ),
        modifier = Modifier.width(280.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            TypePill("studio")

            Text(
                text = studio.name,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp)
            )

            studio.originCountry?.takeIf { it.isNotBlank() }?.let { country ->
                Text(
                    text = country,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SearchTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp)
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
            containerColor = SearchPanel.copy(alpha = 0.92f),
            contentColor = Color.White,
            focusedContainerColor = SearchPanelRaised,
            focusedContentColor = Color.White,
            pressedContainerColor = SearchPanelRaised,
            pressedContentColor = Color.White
        ),
        border = CardDefaults.border(
            border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))),
            focusedBorder = Border(BorderStroke(2.dp, SearchAccent))
        ),
        modifier = Modifier.width(170.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            PosterCard(
                posterUrl = collection.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" },
                contentDescription = collection.name,
                isWatched = false,
                onClick = onClick,
                modifier = Modifier
                    .width(146.dp)
                    .height(219.dp)
            )

            TypePill("collection")

            Text(
                text = collection.name,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun TypePill(type: String) {
    Box(
        modifier = Modifier
            .background(SearchAccent.copy(alpha = 0.16f), RoundedCornerShape(999.dp))
            .border(1.dp, SearchAccent.copy(alpha = 0.40f), RoundedCornerShape(999.dp))
            .padding(horizontal = 9.dp, vertical = 3.dp)
    ) {
        Text(
            text = type.uppercase(),
            color = SearchAccent,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold
        )
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
            .background(SearchPanel.copy(alpha = 0.94f), RoundedCornerShape(16.dp))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .padding(18.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White
        )
        Text(
            text = body,
            color = SearchTextMuted,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}
