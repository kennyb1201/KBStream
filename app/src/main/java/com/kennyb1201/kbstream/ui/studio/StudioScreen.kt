package com.kennyb1201.kbstream.ui.studio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.kennyb1201.kbstream.data.tmdb.StudioItem
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun StudioScreen(
    id: Int,
    name: String,
    isNetwork: Boolean,
    onNavigateDetail: (String, String) -> Unit,
    viewModel: StudioViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val scope = rememberCoroutineScope()
    val items by viewModel.items.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val firstItemFocusRequester = remember { FocusRequester() }

    LaunchedEffect(id) {
        viewModel.load(id, isNetwork)
    }

    LaunchedEffect(items) {
        if (items.isNotEmpty()) {
            delay(100) // give the grid's first item time to actually attach before requesting focus
            runCatching { firstItemFocusRequester.requestFocus() }
        }
    }

    Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Column {
            Text(name, style = MaterialTheme.typography.displayLarge)
            Text(
                if (isNetwork) "SERIES" else "MOVIES & SERIES",
                style = MaterialTheme.typography.titleMedium,
                color = KBTextLo,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
            )

            when {
                isLoading -> Text("Loading...")
                items.isEmpty() -> Text("Nothing found for $name")
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(items) { index, studioItem: StudioItem ->
                        KBCard(
                            onClick = {
                                scope.launch {
                                    val imdbId = viewModel.resolveImdbId(studioItem.item.id, studioItem.mediaType)
                                    if (imdbId != null) onNavigateDetail(studioItem.mediaType, imdbId)
                                }
                            },
                            modifier = Modifier
                                .width(140.dp)
                                .height(210.dp)
                                .padding(8.dp)
                                .let { if (index == 0) it.focusRequester(firstItemFocusRequester) else it }
                        ) {
                            AsyncImage(
                                model = studioItem.item.posterPath?.let { "${TmdbRepository.PROFILE_BASE}$it" },
                                contentDescription = studioItem.item.title ?: studioItem.item.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}
