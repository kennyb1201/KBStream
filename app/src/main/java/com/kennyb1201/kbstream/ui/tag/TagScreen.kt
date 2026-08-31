package com.kennyb1201.kbstream.ui.tag

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.tmdb.StudioItem
import com.kennyb1201.kbstream.data.tmdb.StudioSection
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.ui.components.PosterCard
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun TagScreen(
    id: Int,
    name: String,
    isKeyword: Boolean,
    type: String,
    onNavigateDetail: (String, String) -> Unit = { _, _ -> },
    viewModel: TagViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val watchedKeys by viewModel.watchedKeys.collectAsStateWithLifecycle()
    val resolvedIds by viewModel.resolvedIds.collectAsStateWithLifecycle()
    val pagingStates by viewModel.pagingStates.collectAsStateWithLifecycle()

    val firstItemFocusRequester = remember { FocusRequester() }

    LaunchedEffect(id, isKeyword, type) {
        viewModel.load(id, isKeyword, type)
    }

    LaunchedEffect(sections, isLoading) {
        if (!isLoading && sections.any { it.items.isNotEmpty() }) {
            delay(100)
            runCatching { firstItemFocusRequester.requestFocus() }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Column {
            Text(
                text = name,
                style = MaterialTheme.typography.displayLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            when {
                isLoading -> {
                    CircularProgressIndicator(color = KBAccent, strokeWidth = 3.dp)
                }

                sections.isEmpty() -> {
                    Text("Nothing found for $name")
                }

                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(
                            items = sections,
                            key = { section: StudioSection -> section.title }
                        ) { section ->
                            val pagingState = pagingStates[section.title] ?: RailPagingState()

                            TagRailRow(
                                section = section,
                                watchedKeys = watchedKeys,
                                resolvedIds = resolvedIds,
                                onNavigateDetail = onNavigateDetail,
                                onLoadMore = { viewModel.loadMoreSection(section.title) },
                                hasMore = pagingState.hasMore,
                                isLoadingMore = pagingState.isLoadingMore,
                                isFirstSection = section == sections.firstOrNull(),
                                firstItemFocusRequester = firstItemFocusRequester,
                                viewModel = viewModel
                            )
                        }

                        item(key = "bottom_spacer") {
                            Box(modifier = Modifier.height(24.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TagRailRow(
    section: StudioSection,
    watchedKeys: Set<String>,
    resolvedIds: Map<String, String>,
    onNavigateDetail: (String, String) -> Unit,
    onLoadMore: () -> Unit,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    isFirstSection: Boolean,
    firstItemFocusRequester: FocusRequester,
    viewModel: TagViewModel
) {
    val rowState = rememberLazyListState()

    InfiniteTagRailHandler(
        listState = rowState,
        itemCount = section.items.size,
        hasMore = hasMore,
        isLoadingMore = isLoadingMore,
        onLoadMore = onLoadMore
    )

    Column(modifier = Modifier.padding(bottom = 20.dp)) {
        Text(
            text = section.title,
            style = MaterialTheme.typography.titleMedium,
            color = KBTextLo,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(end = 8.dp),
            modifier = Modifier.focusGroup()
        ) {
            items(
                items = section.items,
                key = { item: StudioItem ->
                    "${item.mediaType}:${item.item.id}"
                }
            ) { studioItem ->
                val tmdbId = studioItem.item.id
                val rawMediaType = studioItem.mediaType
                val normalizedType = when (rawMediaType.lowercase()) {
                    "tv", "series" -> "series"
                    else -> "movie"
                }

                val imdbId = resolvedIds[
                    viewModel.lookupKey(tmdbId, normalizedType)
                ]

                val watched = imdbId?.let {
                    viewModel.watchedKey(it, normalizedType) in watchedKeys
                } == true

                val isFirstItem =
                    isFirstSection && studioItem == section.items.firstOrNull()

                PosterCard(
                    posterUrl = studioItem.item.posterPath
                        ?.let { "${TmdbRepository.POSTER_BASE}$it" },
                    contentDescription = studioItem.item.title
                        ?: studioItem.item.name,
                    isWatched = watched,
                    onClick = {
                        viewModel.resolveAndNavigate(
                            tmdbId = studioItem.item.id,
                            mediaType = rawMediaType,
                            onNavigateDetail = onNavigateDetail
                        )
                    },
                    modifier = Modifier
                        .width(140.dp)
                        .height(210.dp)
                        .padding(end = 12.dp)
                        .then(
                            if (isFirstItem) {
                                Modifier.focusRequester(firstItemFocusRequester)
                            } else {
                                Modifier
                            }
                        )
                )
            }
        }
    }
}

@Composable
private fun InfiniteTagRailHandler(
    listState: LazyListState,
    itemCount: Int,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit
) {
    LaunchedEffect(listState, itemCount, hasMore, isLoadingMore) {
        snapshotFlow {
            val lastVisibleIndex =
                listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisibleIndex to itemCount
        }
            .distinctUntilChanged()
            .collect { (lastVisibleIndex, totalItems) ->
                val threshold = 6
                val shouldLoadMore =
                    hasMore &&
                    !isLoadingMore &&
                    totalItems > 0 &&
                    lastVisibleIndex >= totalItems - threshold

                if (shouldLoadMore) {
                    onLoadMore()
                }
            }
    }
}
