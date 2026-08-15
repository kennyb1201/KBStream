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
import com.kennyb1201.kbstream.ui.components.PosterCard

private val SearchBg = Color(0xFF0B1220)
private val SearchPanel = Color(0xFF101A2B)
private val SearchPanelRaised = Color(0xFF162338)
private val SearchAccent = Color(0xFF4FC3F7)
private val SearchTextMuted = Color(0xFF9FB0C7)

@Composable
fun SearchScreen(
    onItemClick: (MetaPreview) -> Unit,
    viewModel: SearchViewModel = viewModel()
) {
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val watchedKeys by viewModel.watchedKeys.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val recentSearches by viewModel.recentSearches.collectAsStateWithLifecycle()

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
            contentPadding = PaddingValues(bottom = 28.dp)
        ) {
            item(key = "hero") {
                SearchHero(
                    query = query,
                    onQueryChanged = viewModel::onQueryChanged,
                    resultCount = results.size,
                    isLoading = isLoading
                )
            }

            if (query.isBlank() && recentSearches.isNotEmpty()) {
                item(key = "recent_header") {
                    SectionHeader(title = "Recent searches")
                }
                item(key = "recent_row") {
                    LazyRow(
                        contentPadding = PaddingValues(top = 2.dp, bottom = 4.dp)
                    ) {
                        items(recentSearches, key = { it }) { recent ->
                            SearchChip(
                                label = recent,
                                onClick = { viewModel.onRecentSearchClicked(recent) },
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                        item(key = "clear_recent") {
                            SearchChip(
                                label = "Clear recent",
                                onClick = viewModel::clearRecentSearches,
                                modifier = Modifier.padding(end = 8.dp),
                                accent = false
                            )
                        }
                    }
                }
            }

            if (query.isBlank() && recentSearches.isEmpty()) {
                item(key = "empty_idle") {
                    SearchMessagePanel(
                        title = "Search",
                        body = "Start typing to search across your installed catalogs."
                    )
                }
            }

            if (isLoading) {
                item(key = "loading") {
                    SearchMessagePanel(
                        title = "Searching...",
                        body = if (query.isBlank()) "Finding results" else "Looking for \"$query\" across your catalogs"
                    )
                }
            }

            if (!isLoading && query.isNotBlank() && results.isEmpty()) {
                item(key = "no_results") {
                    SearchMessagePanel(
                        title = "No matches found",
                        body = "Try a shorter title or a broader search term."
                    )
                }
            }

            if (!isLoading && topResult != null) {
                item(key = "best_match_header") {
                    SectionHeader(title = "Best match")
                }
                item(key = "best_match") {
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

            if (!isLoading && remainingResults.isNotEmpty()) {
                item(key = "all_results_header") {
                    SectionHeader(title = "More results")
                }

                items(
                    items = remainingResults,
                    key = { "${it.type}:${it.id}" }
                ) { meta ->
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

@Composable
private fun SearchHero(
    query: String,
    onQueryChanged: (String) -> Unit,
    resultCount: Int,
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
                    text = "Search...",
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
            resultCount == 1 -> "1 result"
            resultCount > 1 -> "$resultCount results"
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
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = Color.White,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp)
    )
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
    var focused by remember { mutableStateOf(false) }

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
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
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
    var focused by remember { mutableStateOf(false) }

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
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .onFocusChanged { focused = it.isFocused }
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
            .padding(top = 18.dp)
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
