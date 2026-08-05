package com.kennyb1201.kbstream.ui.tag

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.ui.components.PosterCard
import kotlinx.coroutines.delay

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
    type: String,
    onNavigateDetail: (String, String) -> Unit,
    viewModel: TagViewModel = viewModel()
) {
    val items by viewModel.items.collectAsState()
    val watchedKeys by viewModel.watchedKeys.collectAsState()
    val resolvedIds by viewModel.resolvedIds.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    val firstItemFocusRequester = remember { FocusRequester() }

    LaunchedEffect(id, name, isKeyword, type) {
        viewModel.load(id, name, isKeyword, type)
    }

    LaunchedEffect(items, isLoading) {
        if (!isLoading && items.isNotEmpty()) {
            delay(100)
            runCatching { firstItemFocusRequester.requestFocus() }
        }
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides LocalTvBringIntoViewSpec) {
        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                ) {
                    Text(
                        text = "Loading...",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            items.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                ) {
                    Text(
                        text = "No results for $name",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            else -> {
                LazyRow(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 24.dp)
                        .focusGroup()
                        .focusRestorer(),
                    contentPadding = PaddingValues(horizontal = 24.dp)
                ) {
                    itemsIndexed(
                        items = items,
                        key = { _, item -> item.id }
                    ) { index, item ->
                        val imdbId = resolvedIds[viewModel.lookupKey(item.id, type)]
                        val isWatched = imdbId?.let {
                            viewModel.watchedKey(it, type) in watchedKeys
                        } == true

                        PosterCard(
                            posterUrl = item.posterPath?.let { TmdbRepository.POSTERBASE + it },
                            contentDescription = item.title ?: item.name ?: name,
                            isWatched = isWatched,
                            onClick = {
                                if (imdbId != null) {
                                    onNavigateDetail(type, imdbId)
                                }
                            },
                            modifier = Modifier
                                .width(124.dp)
                                .padding(end = 12.dp)
                                .then(
                                    if (index == 0) {
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
    }
}
