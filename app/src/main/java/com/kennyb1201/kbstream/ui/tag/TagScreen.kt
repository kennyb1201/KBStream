package com.kennyb1201.kbstream.ui.tag

import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.tmdb.StudioItem
import com.kennyb1201.kbstream.data.tmdb.StudioSection
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.PosterCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.snapshotFlow

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
private class TvPivotBringIntoViewSpec(
    private val parentFraction: Float = 0.3f,
    private val childFraction: Float = 0f
) : BringIntoViewSpec {
    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float
    ): Float {
        val targetOffset = parentFraction * containerSize
        val childOffset = childFraction * size
        val destination = targetOffset - childOffset
        return offset - destination
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
private val LocalTvBringIntoViewSpec = TvPivotBringIntoViewSpec()

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun TagScreen(
    id: Int,
    name: String,
    isKeyword: Boolean,
    mediaType: String,
    onBack: () -> Unit = {},
    onNavigateDetail: (String, String) -> Unit,
    viewModel: TagViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    val watchedKeys by viewModel.watchedKeys.collectAsStateWithLifecycle()
    val resolvedIds by viewModel.resolvedIds.collectAsStateWithLifecycle()
    val pagingStates by viewModel.pagingStates.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    val firstItemFocusRequester = remember { FocusRequester() }

    LaunchedEffect(id, isKeyword, mediaType) {
        viewModel.load(id, isKeyword, mediaType)
    }

    LaunchedEffect(sections, isLoading) {
        val hasItems = sections.any { it.items.isNotEmpty() }
        if (!isLoading && hasItems) {
            delay(120)
            runCatching { firstItemFocusRequester.requestFocus() }
        }
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides LocalTvBringIntoViewSpec) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            when {
                isLoading -> {
                    Text(
                        text = "Loading...",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                error != null && sections.isEmpty() -> {
                    Text(
                        text = "Error: $error",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .focusGroup(),
                        contentPadding = PaddingValues(bottom = 32.dp)
                    ) {
                        item(key = "header") {
                            Column(
                                modifier = Modifier.padding(bottom = 20.dp)
                            ) {
                                Text(
                                    text = if (isKeyword) "Keyword" else "Genre",
                                    style = MaterialTheme.typography.labelMedium
                                )
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.headlineLarge,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                                Text(
                                    text = mediaType.uppercase(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(top = 6.dp)
                                )

                                error?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                }
                            }
                        }

                        sections.forEachIndexed { sectionIndex, section ->
                            item(key = "title_${section.title}_$sectionIndex") {
                                Text(
                                    text = section.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(bottom = 10.dp)
                                )
                            }

                            item(key = "row_${section.title}_$sectionIndex") {
                                TagRailRow(
                                    section = section,
                                    watchedKeys = watchedKeys,
                                    resolvedIds = resolvedIds,
                                    onNavigateDetail = onNavigateDetail,
                                    onLoadMore = { viewModel.loadMoreSection(section.title) },
                                    isFirstSection = sectionIndex == 0,
                                    firstItemFocusRequester = firstItemFocusRequester,
                                    viewModel = viewModel
                                )
                            }
                        }

                        item(key = "back_button") {
                            KBCard(
                                onClick = onBack,
                                modifier = Modifier.padding(top = 8.dp)
                            ) {
                                Text(
                                    text = "BACK",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(
                                        horizontal = 16.dp,
                                        vertical = 10.dp
                                    )
                                )
                            }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TagRailRow(
    section: StudioSection,
    watchedKeys: Set<String>,
    resolvedIds: Map<String, String>,
    onNavigateDetail: (String, String) -> Unit,
    onLoadMore: () -> Unit,
    isFirstSection: Boolean,
    firstItemFocusRequester: FocusRequester,
    viewModel: TagViewModel
) {
    val rowState = rememberLazyListState()

    InfiniteRailHandler(
        listState = rowState,
        itemCount = section.items.size,
        onLoadMore = onLoadMore
    )

    LazyRow(
        state = rowState,
        contentPadding = PaddingValues(bottom = 18.dp),
        modifier = Modifier
            .padding(bottom = 8.dp)
            .focusGroup()
            .focusRestorer()
    ) {
        items(
            items = section.items,
            key = { "${it.mediaType}:${it.item.id}" }
        ) { studioItem ->
            val tmdbId = studioItem.item.id
            val normalizedType = normalizeTagMediaType(studioItem.mediaType)

            val lookupKey = viewModel.lookupKey(
                tmdbId = tmdbId,
                mediaType = normalizedType
            )
            val imdbId = resolvedIds[lookupKey]
            val watchedKey = imdbId?.let {
                viewModel.watchedKey(it, normalizedType)
            }
            val isWatched = watchedKey?.let { it in watchedKeys } == true

            TagPosterCard(
                item = studioItem,
                isWatched = isWatched,
                onClick = {
                    viewModel.resolveAndNavigate(
                        tmdbId = tmdbId,
                        mediaType = studioItem.mediaType,
                        onNavigateDetail = onNavigateDetail
                    )
                },
                modifier = if (
                    isFirstSection &&
                    section.items.firstOrNull()?.item?.id == tmdbId
                ) {
                    Modifier.focusRequester(firstItemFocusRequester)
                } else {
                    Modifier
                }
            )
        }
    }
}

@Composable
private fun InfiniteRailHandler(
    listState: LazyListState,
    itemCount: Int,
    onLoadMore: () -> Unit
) {
    val shouldLoadMore = remember(listState, itemCount) {
        derivedStateOf {
            if (itemCount == 0) return@derivedStateOf false

            val lastVisibleItemIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val buffer = 6
            lastVisibleItemIndex >= itemCount - buffer
        }
    }

    LaunchedEffect(listState, itemCount) {
        snapshotFlow { shouldLoadMore.value }
            .distinctUntilChanged()
            .filter { it }
            .collect {
                onLoadMore()
            }
    }
}

@Composable
private fun TagPosterCard(
    item: StudioItem,
    isWatched: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    PosterCard(
        posterUrl = posterUrl(item.item.posterPath),
        contentDescription = item.item.title ?: item.item.name.orEmpty(),
        isWatched = isWatched,
        onClick = onClick,
        modifier = modifier
            .padding(end = 12.dp)
            .height(180.dp)
    )
}

private fun normalizeTagMediaType(mediaType: String): String {
    return when (mediaType.lowercase()) {
        "tv", "series" -> "series"
        else -> "movie"
    }
}

private fun posterUrl(path: String?): String? =
    path?.let { "https://image.tmdb.org/t/p/w342$it" }
